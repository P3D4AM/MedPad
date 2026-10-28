package com.medpad.tracker.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.Session
import com.medpad.tracker.util.NotificationPrefs
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * P07 (permanent protocol) — a notification DISPLAY FAILURE must not
 * silently lose the notification, and delivery/read states must stay
 * distinct.
 *
 * History (the bug, confirmed from source): the old TrackingService poll
 * called NotificationPrefs.markShown(id) BEFORE AppNotifier.notify(...)
 * and then blanket-marked everything read server-side
 * (markNotificationsRead(null)). AppNotifier swallowed display failures
 * internally. Net effect: one failed display (notifications disabled,
 * channel blocked, manager error) made the notification invisible forever
 * — deduped locally AND consumed server-side. Silent loss.
 *
 * Fix under test (ServerNotifications.pollOnce):
 *  - dedup (markShown) + per-id mark-read happen ONLY after a successful
 *    display; failed displays are retried on the next poll (bounded, no
 *    duplicates);
 *  - the server read call carries EXACT ids (never blanket mark-all —
 *    late-arriving server rows stay unread instead of being consumed
 *    unseen);
 *  - wasShown-but-unread rows (a previous round whose read call failed)
 *    are never re-displayed but their readAt still converges.
 *
 * Strategy: a REAL local HTTP panel (dependency-free raw ServerSocket —
 * com.sun.net.httpserver is not on the android.jar test classpath)
 * serves /api/app/notifications from a mutable list and records every
 * /api/app/notifications/read request body. The display callback is
 * injected: the test decides per-title whether the display "succeeds" or
 * "fails", simulating system-notification failures without touching the
 * real NotificationManager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServerNotificationsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()

    /** Server-side notification rows (id → readAt ISO string or null). */
    private val serverRows = LinkedHashMap<String, JSONObject>()

    /** Captured bodies of every /api/app/notifications/read request. */
    private val readRequests = CopyOnWriteArrayList<JSONObject>()

    /** Titles whose display must FAIL this round (simulates system failure). */
    private val failingTitles = CopyOnWriteArrayList<String>()

    @Before
    fun setUp() {
        NotificationPrefs.clearShown(app)
        serverRows.clear()
        readRequests.clear()
        failingTitles.clear()

        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        pool.submit {
            while (!server.isClosed) {
                try {
                    val sock = server.accept()
                    pool.submit { handle(sock) }
                } catch (_: Exception) {
                    return@submit // closed by tearDown
                }
            }
        }
        Session.serverUrl = "http://127.0.0.1:${server.localPort}"
        Session.token = "unit-test-token-p07"
    }

    @After
    fun tearDown() {
        server.close()
        pool.shutdownNow()
        NotificationPrefs.clearShown(app)
    }

    private fun addServerRow(id: String, title: String, readAt: String? = null) {
        serverRows[id] = JSONObject()
            .put("id", id)
            .put("title", title)
            .put("body", "متن $title")
            .put("readAt", readAt ?: JSONObject.NULL)
    }

    /** The injected display: fails for titles registered in [failingTitles]. */
    private fun testDisplay(): (Int, String, String) -> Boolean = { _, title, _ ->
        if (failingTitles.contains(title)) {
            false // simulate a system display failure
        } else {
            true
        }
    }

    private fun lastReadIds(): JSONArray? {
        if (readRequests.isEmpty()) return null
        return readRequests.last().optJSONArray("ids")
    }

    /** Minimal HTTP/1.1 handling (same pattern as CheckinSync403Test). */
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

                val (status, json) = when {
                    path == "/api/app/notifications" -> {
                        200 to JSONObject().put("notifications", JSONArray(serverRows.values)).toString()
                    }
                    path == "/api/app/notifications/read" -> {
                        val body = if (bodyText.isEmpty()) JSONObject() else JSONObject(bodyText)
                        readRequests.add(body)
                        val ids = body.optJSONArray("ids")
                        if (ids != null) {
                            for (i in 0 until ids.length()) serverRows.remove(ids.getString(i))
                        } else {
                            serverRows.clear() // mark-ALL (must never be sent by the client)
                        }
                        200 to """{"ok":true}"""
                    }
                    else -> 404 to """{"ok":false}"""
                }
                val bytes = json.toByteArray(Charsets.UTF_8)
                val out = s.getOutputStream()
                out.write(
                    ("HTTP/1.1 $status ${if (status == 200) "OK" else "Not Found"}\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
                )
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                // client hangup between tests — ignore
            }
        }
    }

    @Test
    fun `display failure keeps the notification for the next poll instead of losing it`() = runBlocking {
        // two unread notifications; B's display will FAIL this round
        addServerRow("notif-a", "اعلان سالم")
        addServerRow("notif-b", "اعلان شکست‌خورده")
        failingTitles.add("اعلان شکست‌خورده")

        val r1 = ServerNotifications.pollOnce(app, testDisplay())

        // A displayed; B's display failed → kept for retry (NOT lost)
        assertEquals(1, r1.displayed)
        assertEquals(1, r1.failedDisplays)
        assertEquals(1, r1.markedRead)

        // dedup: A yes, B no (the old code deduped B before displaying — the bug)
        assertTrue(NotificationPrefs.wasShown(app, "notif-a"))
        assertFalse("failed display must NOT be deduped — it must retry", NotificationPrefs.wasShown(app, "notif-b"))

        // read marked per-id: exactly A, never a blanket mark-all
        val ids = lastReadIds()
        assertTrue("read request must carry an ids array", ids != null && ids.length() == 1 && ids.getString(0) == "notif-a")
        // B is still on the server (not consumed) — it survived
        assertTrue(serverRows.containsKey("notif-b"))

        // next poll: the display now succeeds → B is delivered, deduped, read
        failingTitles.clear()
        val r2 = ServerNotifications.pollOnce(app, testDisplay())
        assertEquals(1, r2.displayed)
        assertEquals(0, r2.failedDisplays)
        assertTrue(NotificationPrefs.wasShown(app, "notif-b"))
        val ids2 = lastReadIds()
        assertTrue(ids2 != null && ids2.length() == 1 && ids2.getString(0) == "notif-b")
        assertTrue(serverRows.isEmpty()) // both consumed on the server now
    }

    @Test
    fun `already-shown notification is never re-displayed but its readAt still converges`() = runBlocking {
        // C is unread on the server but was displayed in an earlier round
        // (e.g. that round's read call failed — the package-11 race)
        addServerRow("notif-c", "اعلان قبلی")
        NotificationPrefs.markShown(app, "notif-c")

        var displayCalls = 0
        val r = ServerNotifications.pollOnce(app) { _, _, _ -> displayCalls++; true }

        // NOT re-displayed (no duplicate) …
        assertEquals(0, displayCalls)
        assertEquals(0, r.displayed)
        // … but still marked read — readAt converges instead of sticking forever
        assertEquals(1, r.markedRead)
        val ids = lastReadIds()
        assertTrue(ids != null && ids.length() == 1 && ids.getString(0) == "notif-c")
        assertTrue(serverRows.isEmpty())
    }

    @Test
    fun `nothing to process means no read request at all — never a blanket mark-all`() = runBlocking {
        // everything already read server-side
        addServerRow("notif-old", "اعلان خوانده‌شده", readAt = "2026-10-29T00:00:00.000Z")

        var displayCalls = 0
        val r = ServerNotifications.pollOnce(app) { _, _, _ -> displayCalls++; true }

        assertEquals(0, displayCalls)
        assertEquals(0, r.displayed)
        assertEquals(0, r.markedRead)
        assertTrue("no read request may be sent when nothing was processed", readRequests.isEmpty())
    }

    @Test
    fun `display exceptions count as failures and do not abort the round`() = runBlocking {
        addServerRow("notif-x", "استثنا")
        addServerRow("notif-y", "عادی")

        val r = ServerNotifications.pollOnce(app) { _, title, _ ->
            if (title == "استثنا") throw IllegalStateException("notification manager exploded")
            true
        }

        // the throwing display did not kill the round — y still got delivered
        assertEquals(1, r.displayed)
        assertEquals(1, r.failedDisplays)
        assertTrue(NotificationPrefs.wasShown(app, "notif-y"))
        assertFalse(NotificationPrefs.wasShown(app, "notif-x"))
        assertTrue(serverRows.containsKey("notif-x")) // kept for retry
    }
}
