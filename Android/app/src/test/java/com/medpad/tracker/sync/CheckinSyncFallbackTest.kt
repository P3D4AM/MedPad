package com.medpad.tracker.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.Session
import com.medpad.tracker.api.ApiClient
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.QUEUE_STATUS_FAILED
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * P08 (permanent protocol) — the old-panel fallback check-in request must
 * not silently discard the recorded event data.
 *
 * History (the defect, confirmed from source): after a 400/404 on the
 * multipart check-in (an old ≤1.7 panel cannot parse multipart with
 * voice), the fallback JSON body carried ONLY customerId/note/localId —
 * the real event time (clientAt, v1.9.12) and the GPS fix
 * (lat/lng/accuracy, G-6) were silently dropped whenever the fallback
 * succeeded. Voice loss is the one DELIBERATE, documented drop (an old
 * panel cannot receive a file; the app shows the old-panel warning) —
 * but time/location had no reason to be lost: old panels ignore unknown
 * JSON keys, so sending them is harmless there and future/JSON-speaking
 * servers get the full event.
 *
 * Fix under test: the fallback body now mirrors ApiClient.checkin's
 * multipart fields exactly (clientAt always; lat/lng as a pair;
 * accuracy only when > 0). The current panel parses multipart only, so
 * a fallback against it fails and the event is kept as 'failed' (data
 * preserved) — that path is asserted too, plus the fact that checkout
 * never uses the fallback.
 *
 * Strategy: a REAL local HTTP panel (dependency-free raw ServerSocket,
 * same pattern as the P06/P07 tests) that answers the first multipart
 * check-in with 400, captures every request (head + body), and answers
 * JSON requests according to a configurable mode.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckinSyncFallbackTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()

    /** Captured request bodies per path (raw bytes → string). */
    private val requests = CopyOnWriteArrayList<Pair<String, String>>() // path+method → body

    /** "ok" = JSON fallback gets 200 ok; "reject" = JSON also gets 400. */
    @Volatile private var jsonMode = "ok"

    @Before
    fun setUp() {
        runBlocking {
            val dao = AppDatabase.get(app).pendingCheckinDao()
            dao.all().forEach { dao.delete(it) }
        }
        CheckinSync.markSessionAlive()
        requests.clear()
        jsonMode = "ok"

        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        pool.submit {
            while (!server.isClosed) {
                try {
                    val sock = server.accept()
                    pool.submit { handle(sock) }
                } catch (_: Exception) {
                    return@submit
                }
            }
        }
        Session.serverUrl = "http://127.0.0.1:${server.localPort}"
        Session.token = "unit-test-token-p08"
    }

    @After
    fun tearDown() {
        server.close()
        pool.shutdownNow()
        runBlocking {
            val dao = AppDatabase.get(app).pendingCheckinDao()
            dao.all().forEach { dao.delete(it) }
        }
        CheckinSync.markSessionAlive()
    }

    private fun handle(sock: Socket) {
        sock.use { s ->
            try {
                s.soTimeout = 10_000
                val input = BufferedInputStream(s.getInputStream())
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) {
                    val b = input.read()
                    if (b < 0) return
                    head.append(b.toChar())
                }
                val lines = head.toString().split("\r\n")
                val path = lines.first().split(" ").getOrNull(1) ?: return
                val contentType = lines
                    .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.lowercase() ?: ""
                val contentLength = lines
                    .firstOrNull { it.startsWith("Content-Length:", ignoreCase = true) }
                    ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                var bodyText = ""
                if (contentLength > 0) {
                    val body = ByteArray(contentLength)
                    var off = 0
                    while (off < body.size) {
                        val r = input.read(body, off, body.size - off)
                        if (r < 0) break
                        off += r
                    }
                    bodyText = String(body, Charsets.UTF_8)
                }
                requests.add("$contentType $path" to bodyText)

                val isJson = contentType.startsWith("application/json")
                val (status, json) = when {
                    path == "/api/app/checkin" && isJson ->
                        if (jsonMode == "ok") 200 to """{"ok":true}"""
                        else 400 to """{"ok":false,"error":"پنل قدیمی هم قبول نکرد"}"""
                    // multipart check-in: the "old panel" rejects it with 400
                    path == "/api/app/checkin" -> 400 to """{"ok":false,"error":"فرم نامعتبر است"}"""
                    else -> 404 to """{"ok":false}"""
                }
                val bytes = json.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write(
                    ("HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                // client hangup — ignore
            }
        }
    }

    private fun jsonCheckinRequests(): List<String> =
        requests.filter { (k, _) -> k.startsWith("application/json") && k.endsWith("/api/app/checkin") }
            .map { it.second }

    private fun multipartRequests(): List<String> =
        requests.filter { (k, _) -> k.startsWith("multipart/") && k.endsWith("/api/app/checkin") }
            .map { it.second }

    private fun tempVoice(marker: String): File =
        File.createTempFile("p8-voice", ".m4a", app.cacheDir).apply {
            writeText(marker)
            deleteOnExit()
        }

    @Test
    fun `successful fallback carries clientAt, lat, lng and accuracy — not just text`() = runBlocking {
        val voice = tempVoice("P8-VOICE-MARKER-حداقل-یک-صوت")
        val item = CheckinSync.enqueue(
            kind = "checkin",
            customerId = "cust-old-panel",
            note = "ویزیت با پنل قدیمی",
            voiceFile = voice,
            lat = 35.7112,
            lng = 51.3923,
            accuracyM = 7.25,
        )

        val sent = CheckinSync.flush()

        assertEquals(1, sent) // the fallback delivered the event

        // the first (rejected) request WAS the multipart with the voice file
        assertEquals(1, multipartRequests().size)
        assertTrue("multipart must carry the voice file", multipartRequests().first().contains("P8-VOICE-MARKER"))

        // the fallback JSON carries EVERY recorded field (P08 core claim)
        val fallbackBodies = jsonCheckinRequests()
        assertEquals(1, fallbackBodies.size)
        val body = JSONObject(fallbackBodies.first())
        assertEquals("cust-old-panel", body.getString("customerId"))
        assertEquals("ویزیت با پنل قدیمی", body.getString("note"))
        assertEquals(item.localId, body.getString("localId"))
        assertEquals(ApiClient.isoTime(item.createdAt), body.getString("clientAt")) // real event time preserved
        assertEquals(35.7112, body.getDouble("lat"), 1e-9) // GPS fix preserved
        assertEquals(51.3923, body.getDouble("lng"), 1e-9)
        assertEquals(7.25, body.getDouble("accuracy"), 1e-9) // accuracy preserved

        // voice is the ONLY deliberate drop (documented; old panel cannot receive it)
        assertFalse(body.has("voice"))

        // server-confirmed → left the queue
        val dao = AppDatabase.get(app).pendingCheckinDao()
        assertTrue(dao.all().none { it.localId == item.localId })
    }

    @Test
    fun `fallback failure keeps the event as failed with its data`() = runBlocking {
        jsonMode = "reject" // even the JSON fallback is rejected
        val voice = tempVoice("P8-VOICE-REJECT")
        val item = CheckinSync.enqueue(
            kind = "checkin",
            customerId = "cust-gone",
            note = "رویداد حفظ‌شود",
            voiceFile = voice,
            lat = 35.70,
            lng = 51.40,
            accuracyM = 5.0,
        )

        val sent = CheckinSync.flush()

        assertEquals(0, sent) // nothing delivered
        // both attempts were actually made (multipart, then JSON)
        assertEquals(1, multipartRequests().size)
        assertEquals(1, jsonCheckinRequests().size)

        // the event survives as 'failed' with its data (no silent loss)
        val dao = AppDatabase.get(app).pendingCheckinDao()
        val survivor = dao.all().first { it.localId == item.localId }
        assertEquals(QUEUE_STATUS_FAILED, survivor.status)
        assertEquals(1, survivor.attempts)
        assertNotNull(survivor.lastError)
        assertEquals("cust-gone", survivor.customerId)
        assertEquals(35.70, survivor.lat!!, 1e-9)
        assertEquals(51.40, survivor.lng!!, 1e-9)
    }

    @Test
    fun `checkout never uses the fallback — straight to failed`() = runBlocking {
        val voice = tempVoice("P8-VOICE-CHECKOUT")
        val item = CheckinSync.enqueue(
            kind = "checkout",
            customerId = null,
            note = "خروج",
            voiceFile = voice,
            lat = 35.70,
            lng = 51.40,
            accuracyM = 5.0,
        )

        val sent = CheckinSync.flush()

        assertEquals(0, sent)
        // exactly ONE request (the multipart checkout) — no JSON fallback ever
        assertEquals(1, requests.count { it.first.endsWith("/api/app/checkout") })
        assertTrue(jsonCheckinRequests().isEmpty())

        val dao = AppDatabase.get(app).pendingCheckinDao()
        val survivor = dao.all().first { it.localId == item.localId }
        assertEquals(QUEUE_STATUS_FAILED, survivor.status)
    }
}
