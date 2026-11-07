package com.altitudeplusplus.android.api

import com.altitudeplusplus.android.AltitudeConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

class ViewLiftException(
    message: String,
    val code: String? = null,
    val status: Int? = null,
) : IOException(message)

/**
 * Thin client over ViewLift's REST and GraphQL endpoints. ViewLift expects the
 * raw JWT in `Authorization` (no "Bearer" prefix) plus the site's `x-api-key`.
 */
class ViewLiftClient(
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun get(path: String, query: Map<String, String?> = emptyMap(), token: String?): JsonElement =
        rest("GET", path, query, token)

    suspend fun post(path: String, query: Map<String, String?> = emptyMap(), token: String?): JsonElement =
        rest("POST", path, query, token)

    private suspend fun rest(method: String, path: String, query: Map<String, String?>, token: String?): JsonElement {
        val url = "${AltitudeConfig.API_BASE}/$path".toHttpUrl().newBuilder().apply {
            query.entries.sortedBy { it.key }.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
        }.build()
        val body = if (method == "POST") ByteArray(0).toRequestBody(null) else null
        val request = Request.Builder().url(url).method(method, body).headers(token).build()
        return send(request)
    }

    /** Runs a GraphQL operation and returns its `data` object. */
    suspend fun graphQL(query: String, variables: JsonObject, token: String?): JsonElement {
        val payload = buildJsonObject {
            put("query", query)
            put("variables", variables)
        }
        val request = Request.Builder()
            .url(AltitudeConfig.GRAPHQL)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .headers(token)
            .build()
        val body = send(request)
        body["errors"].array?.firstOrNull()?.let { first ->
            val code = first["extensions"]["code"].string
            val message = first["message"].string ?: code ?: "Request failed"
            throw ViewLiftException(friendlyGraphQLMessage(code, message), code)
        }
        return body["data"] ?: throw ViewLiftException("Empty response from Altitude+")
    }

    private fun Request.Builder.headers(token: String?): Request.Builder = apply {
        header("x-api-key", AltitudeConfig.API_KEY)
        header("Accept", "application/json")
        if (token != null) header("Authorization", token)
    }

    private suspend fun send(request: Request): JsonElement = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            val parsed = runCatching { json.parseToJsonElement(text) }.getOrDefault(JsonNull)
            if (response.isSuccessful) return@withContext parsed
            // Entitlement endpoints return 4xx with a JSON body describing why.
            // Hand that body back so callers can map `errorCode` themselves.
            if (parsed["errorCode"] != null || parsed["code"] != null) return@withContext parsed
            val message = parsed["errorMessage"].string ?: parsed["message"].string ?: "HTTP ${response.code}"
            throw ViewLiftException(message, parsed["code"].string, response.code)
        }
    }

    private fun friendlyGraphQLMessage(code: String?, message: String): String = when (code?.uppercase()) {
        "INVALID_OTP", "OTP_INVALID" -> "That code didn't match. Check the latest email from Altitude+ and try again."
        "OTP_EXPIRED" -> "That code has expired. Request a new one."
        else -> message
    }
}
