package com.medpad.tracker.api

import com.medpad.tracker.Session
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * v1.9.1 fallback path — when the panel is too old for multipart check-ins,
 * notes are still delivered as JSON (voice is dropped, text is never lost).
 */
object ApiClientFallback {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Throws(Exception::class)
    fun postJson(path: String, body: JSONObject): JSONObject {
        val req = Request.Builder()
            .url(Session.serverUrl + path)
            .header(ApiClient.HEADER_TOKEN, Session.token)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(req).execute().use { resp ->
            val json = JSONObject(resp.body?.string() ?: "{}")
            if (!resp.isSuccessful) throw ApiClient.HttpException(resp.code, json.optString("error"))
            return json
        }
    }
}
