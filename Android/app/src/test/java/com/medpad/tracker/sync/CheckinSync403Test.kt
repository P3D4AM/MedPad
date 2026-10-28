package com.medpad.tracker.sync

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.medpad.tracker.Session
import com.medpad.tracker.db.AppDatabase
import com.medpad.tracker.db.QUEUE_STATUS_FAILED
import kotlinx.coroutines.runBlocking
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * P06 (permanent protocol) — an offline check-in rejected with HTTP 403
 * ("customer not assigned — server policy") must NOT be silently deleted.
 *
 * History: the original G-1 decision (package 1 era) dropped the record.
 * P06 reverses that: the rejected event stays in the queue as 'failed'
 * with the server's error text, stays visible in the queue UI and stays
 * manually retryable. Recorded offline data survives a permission
 * rejection.
 *
 * Strategy: a REAL local HTTP panel — a minimal dependency-free raw
 * ServerSocket server (com.sun.net.httpserver is not on the android.jar
 * test compile classpath, so we speak the few needed HTTP bytes
 * ourselves) that answers the FIRST /api/app/checkin with 403 + a
 * Persian error body and everything else with ok — deterministic
 * regardless of flush order. A checkout event sits in the queue as well,
 * proving the 403 does not stall the queue (it must still be delivered).
 * The retry leg then flips the server to accepting and proves the
 * recovery path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CheckinSync403Test {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private lateinit var server: ServerSocket
    private val pool = Executors.newCachedThreadPool()
    private val checkinCalls = AtomicInteger(0)

    /** true = answer the FIRST /api/app/checkin with 403 (the reject leg). */
    @Volatile private var rejectFirstCheckin = true

    @Before
    fun setUp() {
        // The Room singleton ("medpad.db") is shared with other test classes
        // in this JVM — start from a clean queue so only OUR rows matter.
        runBlocking {
            val dao = AppDatabase.get(app).pendingCheckinDao()
            dao.all().forEach { dao.delete(it) }
        }
        CheckinSync.markSessionAlive()

        server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        pool.submit {
            while (!server.isClosed) {
                try {
                    val sock = server.accept()
                    pool.submit { handle(sock) }
                } catch (_: Exception) {
                    return@submit // server closed by tearDown
                }
            }
        }

        Session.serverUrl = "http://127.0.0.1:${server.localPort}"
        Session.token = "unit-test-token-403"
    }

    @After
    fun tearDown() {
        server.close()
        pool.shutdownNow()
        // leave the shared queue table empty for the next test class
        runBlocking {
            val dao = AppDatabase.get(app).pendingCheckinDao()
            dao.all().forEach { dao.delete(it) }
        }
        CheckinSync.markSessionAlive()
    }

    /** Minimal HTTP/1.1 handling: read request head + body, answer one
     *  JSON response, close (Connection: close — OkHttp handles it). */
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
                if (contentLength > 0) { // consume the (multipart) request body
                    var remaining = contentLength
                    val buf = ByteArray(8192)
                    while (remaining > 0) {
                        val r = input.read(buf, 0, minOf(buf.size, remaining))
                        if (r < 0) break
                        remaining -= r
                    }
                }

                val (status, json) = when {
                    path == "/api/app/checkout" -> 200 to """{"ok":true}"""
                    path == "/api/app/checkin" && rejectFirstCheckin && checkinCalls.incrementAndGet() == 1 ->
                        403 to """{"ok":false,"error":"این مشتری به شما منتسب نیست"}"""
                    else -> 200 to """{"ok":true}"""
                }
                val bytes = json.toByteArray(Charsets.UTF_8)
                val head2 = "HTTP/1.1 $status ${if (status == 200) "OK" else "Forbidden"}\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"
                val out = s.getOutputStream()
                out.write(head2.toByteArray(Charsets.ISO_8859_1))
                out.write(bytes)
                out.flush()
            } catch (_: Exception) {
                // client hangup between tests — ignore
            }
        }
    }

    @Test
    fun `403 keeps the rejected offline check-in as failed, preserves its data and flows the queue`() = runBlocking {
        // arrange: one check-in (the 403 victim) + one checkout behind it
        val checkin = CheckinSync.enqueue(
            kind = "checkin",
            customerId = "cust-unassigned",
            note = "ویزیت ثبت‌شده در حالت آفلاین",
            voiceFile = null,
            lat = 35.7011,
            lng = 51.4012,
            accuracyM = 8.5,
        )
        val checkout = CheckinSync.enqueue(
            kind = "checkout",
            customerId = null,
            note = "",
            voiceFile = null,
            lat = 35.7013,
            lng = 51.4014,
            accuracyM = 9.0,
        )

        // act
        val sent = CheckinSync.flush()

        // assert — the checkout was still delivered: a 403 must not stall the queue
        assertEquals(1, sent)

        // the rejected record SURVIVED (P06 core claim: not deleted)
        val dao = AppDatabase.get(app).pendingCheckinDao()
        val survivor = dao.all().first { it.localId == checkin.localId }
        assertEquals(QUEUE_STATUS_FAILED, survivor.status)
        assertEquals(1, survivor.attempts)

        // the error is readable: category + the server's own Persian text
        assertNotNull(survivor.lastError)
        assertTrue(survivor.lastError!!.contains("403"))
        assertTrue(survivor.lastError!!.contains("این مشتری به شما منتسب نیست"))

        // the recorded offline data is intact
        assertEquals("cust-unassigned", survivor.customerId)
        assertEquals("ویزیت ثبت‌شده در حالت آفلاین", survivor.note)
        assertEquals(35.7011, survivor.lat!!, 1e-9)
        assertEquals(51.4012, survivor.lng!!, 1e-9)
        assertEquals(8.5, survivor.accuracy!!, 1e-9)
        assertEquals("checkin", survivor.kind)

        // visible through the queue-details UI accessors (package 1)
        assertTrue(CheckinSync.failedItems().any { it.localId == checkin.localId })
        assertEquals(1, CheckinSync.failedCount())

        // a 403 is record-level, NOT session-level (that is 401's job, G-2)
        assertFalse(CheckinSync.sessionExpired)

        // recovery path: the server accepts now → manual retry delivers it
        rejectFirstCheckin = false
        val retried = CheckinSync.retryFailed()
        assertEquals(1, retried)
        assertTrue(dao.all().none { it.localId == checkin.localId })
        assertEquals(0, CheckinSync.failedCount())
        assertTrue(dao.all().none { it.localId == checkout.localId }) // still delivered earlier
    }
}
