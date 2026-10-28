package com.medpad.tracker.api

import com.medpad.tracker.Session
import okhttp3.MediaType.Companion.toMediaType
import okio.buffer
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the MedPad panel.
 * v1.9.1: 120s timeouts for voice uploads (default 10s made uploads die mid-flight
 * and left items stuck in the offline queue forever).
 */
object ApiClient {

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    const val HEADER_TOKEN = "X-Medpad-Token"

    class HttpException(val code: Int, message: String) : Exception(message)

    private fun url(path: String): String = Session.serverUrl + path

    private fun req(path: String, method: String = "GET", body: RequestBody? = null): Request.Builder {
        val b = Request.Builder().url(url(path))
            .header(HEADER_TOKEN, Session.token)
        when (method) {
            "POST" -> b.post(body ?: JSONObject().toString().toRequestBody("application/json".toMediaType()))
            "PUT" -> b.put(body ?: JSONObject().toString().toRequestBody("application/json".toMediaType()))
            "DELETE" -> b.delete()
        }
        return b
    }

    @Throws(Exception::class)
    private fun exec(b: Request.Builder): JSONObject {
        http.newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string() ?: "{}"
            val json = JSONObject(text)
            if (!resp.isSuccessful) throw HttpException(resp.code, json.optString("error", "HTTP ${resp.code}"))
            return json
        }
    }

    // ── auth ────────────────────────────────────────────────
    @Throws(Exception::class)
    fun login(baseUrl: String, username: String, password: String): JSONObject {
        val body = JSONObject().put("username", username).put("password", password)
            .toString().toRequestBody("application/json".toMediaType())
        val call = Request.Builder().url(baseUrl.trimEnd('/') + "/api/app/login").post(body).build()
        http.newCall(call).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            if (!resp.isSuccessful) throw HttpException(resp.code, json.optString("error", "login failed"))
            return json
        }
    }

    // ── config ──────────────────────────────────────────────
    @Throws(Exception::class)
    fun config(): JSONObject = exec(req("/api/app/config"))

    // ── customers sync ──────────────────────────────────────
    @Throws(Exception::class)
    fun customers(since: String? = null): JSONObject =
        exec(req(if (since != null) "/api/app/customers?since=$since" else "/api/app/customers"))

    @Throws(Exception::class)
    fun createCustomer(name: String, lat: Double, lng: Double, address: String, phone: String, type: String): JSONObject {
        val body = JSONObject()
            .put("name", name)
            .put("lat", lat)
            .put("lng", lng)
            .put("address", address)
            .put("phone", phone)
            .put("type", type)
        return exec(req("/api/app/customers/create", "POST",
            body.toString().toRequestBody("application/json".toMediaType())))
    }

    @Throws(Exception::class)
    fun editCustomer(id: String, name: String, lat: Double, lng: Double, address: String, phone: String, clientUpdatedAt: String): JSONObject {
        val body = JSONObject()
            .put("name", name)
            .put("lat", lat)
            .put("lng", lng)
            .put("address", address)
            .put("phone", phone)
            .put("updatedAt", clientUpdatedAt)
        return exec(req("/api/app/customers/$id", "PUT",
            body.toString().toRequestBody("application/json".toMediaType())))
    }

    // ── check-in / check-out ────────────────────────────────
    @Throws(Exception::class)
    fun checkin(
        customerId: String,
        note: String,
        lat: Double?,
        lng: Double?,
        localId: String,
        voice: File?,
        clientAt: Long? = null,
        accuracyM: Double? = null,
        onProgress: ((Float) -> Unit)? = null,
    ): JSONObject {
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("customerId", customerId)
            .addFormDataPart("note", note)
            .addFormDataPart("localId", localId)
        // v1.9.12 (phase 1): real event time from the offline queue so the
        // server records when the check-in HAPPENED, not when it was synced.
        clientAt?.let { mp.addFormDataPart("clientAt", isoTime(it)) }
        // G-6 (phase 1 / task 2): real device GPS fix + reported accuracy.
        // Absent lat/lng = no location — the server records the event as
        // explicitly location-missing (never the customer's coordinates).
        if (lat != null && lng != null) {
            mp.addFormDataPart("lat", lat.toString())
            mp.addFormDataPart("lng", lng.toString())
            accuracyM?.let { if (it > 0) mp.addFormDataPart("accuracy", it.toString()) }
        }
        if (voice != null && voice.exists()) {
            mp.addFormDataPart("voice", voice.name,
                voice.asRequestBody("audio/mp4".toMediaType()))
        }
        val tracked = ProgressBody(mp.build()) { f -> onProgress?.invoke(f) }
        return exec(req("/api/app/checkin", "POST", tracked))
    }

    @Throws(Exception::class)
    fun checkout(note: String, localId: String, voice: File?, clientAt: Long? = null, lat: Double? = null, lng: Double? = null, accuracyM: Double? = null): JSONObject {
        val mp = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("note", note)
            .addFormDataPart("localId", localId)
        // v1.9.12 (phase 1): real event time from the offline queue (see checkin)
        clientAt?.let { mp.addFormDataPart("clientAt", isoTime(it)) }
        // G-6 (phase 1 / task 2): real device GPS fix (same rules as checkin)
        if (lat != null && lng != null) {
            mp.addFormDataPart("lat", lat.toString())
            mp.addFormDataPart("lng", lng.toString())
            accuracyM?.let { if (it > 0) mp.addFormDataPart("accuracy", it.toString()) }
        }
        if (voice != null && voice.exists()) {
            mp.addFormDataPart("voice", voice.name,
                voice.asRequestBody("audio/mp4".toMediaType()))
        }
        return exec(req("/api/app/checkout", "POST", mp.build()))
    }

    @Throws(Exception::class)
    fun openVisits(): JSONObject = exec(req("/api/app/checkout"))

    // ── notifications / reports ─────────────────────────────
    @Throws(Exception::class)
    fun notifications(): JSONObject = exec(req("/api/app/notifications"))

    // ── assignment requests (package 6 — rep asks the admin for a customer) ──
    @Throws(Exception::class)
    fun assignmentRequests(): JSONObject = exec(req("/api/app/assignment-requests"))

    @Throws(Exception::class)
    fun createAssignmentRequest(customerId: String, note: String): JSONObject {
        val body = JSONObject().put("customerId", customerId).put("note", note)
            .toString().toRequestBody("application/json".toMediaType())
        return exec(req("/api/app/assignment-requests", "POST", body))
    }

    @Throws(Exception::class)
    fun markNotificationsRead(ids: List<String>? = null): JSONObject {
        val body = JSONObject()
        if (ids != null) body.put("ids", org.json.JSONArray(ids))
        return exec(req("/api/app/notifications/read", "POST",
            body.toString().toRequestBody("application/json".toMediaType())))
    }

    @Throws(Exception::class)
    fun reports(from: String? = null, to: String? = null): JSONObject {
        var path = "/api/app/reports"
        if (from != null && to != null) path += "?from=$from&to=$to"
        return exec(req(path))
    }

    // ── planned visits (phase 2 / task 2 — read-only) ─────
    /** Own visit plans for an inclusive server-local day range
     *  (YYYY-MM-DD). The server pins the employee to the token's identity. */
    @Throws(Exception::class)
    fun plannedVisits(from: String, to: String): JSONObject =
        exec(req("/api/app/planned-visits?from=$from&to=$to"))

    @Throws(Exception::class)
    fun proximity(customerId: String, lat: Double?, lng: Double?): JSONObject {
        val body = JSONObject().put("customerId", customerId)
        if (lat != null) body.put("lat", lat)
        if (lng != null) body.put("lng", lng)
        return exec(req("/api/app/proximity", "POST",
            body.toString().toRequestBody("application/json".toMediaType())))
    }

    fun mediaUrl(voicePath: String): String =
        "${Session.serverUrl}/api/media/$voicePath?token=${Session.token}"

    /** Epoch ms → ISO 8601 instant (UTC) for the server's clientAt field. */
    private fun isoTime(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).toString()

    /** Raw authenticated GET returning bytes (voice download for playback) */
    @Throws(Exception::class)
    fun httpCall(urlOrPath: String): ByteArray {
        val full = if (urlOrPath.startsWith("http")) urlOrPath else Session.serverUrl + urlOrPath
        val req = Request.Builder().url(full).header(HEADER_TOKEN, Session.token).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw HttpException(resp.code, "HTTP ${resp.code}")
            return resp.body?.bytes() ?: ByteArray(0)
        }
    }
}

/** Wraps a body to report upload progress */
private class ProgressBody(
    private val delegate: RequestBody,
    private val onProgress: (Float) -> Unit,
) : RequestBody() {
    override fun contentType() = delegate.contentType()
    override fun contentLength() = delegate.contentLength()

    override fun writeTo(sink: okio.BufferedSink) {
        val total = contentLength()
        val counting = object : okio.ForwardingSink(sink) {
            var written = 0L
            override fun write(source: okio.Buffer, byteCount: Long) {
                super.write(source, byteCount)
                written += byteCount
                if (total > 0) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
            }
        }
        val buffered = counting.buffer()
        delegate.writeTo(buffered)
        buffered.flush()
    }
}
