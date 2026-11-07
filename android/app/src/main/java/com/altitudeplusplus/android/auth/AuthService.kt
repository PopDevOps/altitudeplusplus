package com.altitudeplusplus.android.auth

import android.content.Context
import android.os.Build
import android.util.Base64
import com.altitudeplusplus.android.AltitudeConfig
import com.altitudeplusplus.android.DeviceProfile
import com.altitudeplusplus.android.api.ViewLiftClient
import com.altitudeplusplus.android.api.ViewLiftException
import com.altitudeplusplus.android.api.bool
import com.altitudeplusplus.android.api.get
import com.altitudeplusplus.android.api.string
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.long
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.UUID

data class UserSession(
    val authorizationToken: String,
    val refreshToken: String,
    val userId: String?,
    val email: String?,
    val isSubscribed: Boolean?,
) {
    /** Epoch seconds, from the JWT's `exp` claim. */
    val expiresAt: Long? get() = Jwt.expiry(authorizationToken)
}

object Jwt {
    fun expiry(token: String): Long? = runCatching {
        val part = token.split(".")[1]
        val decoded = String(Base64.decode(part, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        Json.parseToJsonElement(decoded).jsonObject["exp"]?.jsonPrimitive?.long
    }.getOrNull()

    fun secondsLeft(token: String): Long? = expiry(token)?.let { it - System.currentTimeMillis() / 1000 }
}

/**
 * Passwordless email sign-in (the same OTP flow altitudeplus.com uses) and token
 * lifecycle. Tokens are kept in app-private SharedPreferences, which Android
 * excludes from backup here (allowBackup=false).
 */
class AuthService(context: Context, private val client: ViewLiftClient) {
    private val prefs = context.getSharedPreferences("auth", Context.MODE_PRIVATE)
    private val refreshLock = Mutex()
    private var anonymousToken: String? = null

    private val _session = MutableStateFlow(load())
    val session: StateFlow<UserSession?> = _session

    /** Stable per-install identifier. */
    val deviceId: String by lazy {
        prefs.getString("deviceId", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("deviceId", it).apply()
        }
    }

    /** Step 1: Altitude+ emails a one-time code. Returns the key needed to validate it. */
    suspend fun sendCode(email: String, profile: DeviceProfile): String {
        val token = anonymousAuthorization(profile)
        val mutation = """
            mutation (${'$'}site: String!, ${'$'}device: EntitlementDevice!, ${'$'}input: IdentityAuthOtpInitiateInput!) {
              identityAuthOtpInitiate(site: ${'$'}site, device: ${'$'}device, input: ${'$'}input) { key }
            }
        """.trimIndent()
        // The campaign fields must be present (empty is fine) or validation
        // fails server-side after the code is accepted. See docs/API.md.
        val variables = buildJsonObject {
            put("site", AltitudeConfig.SITE)
            put("device", profile.deviceType)
            putJsonObject("input") {
                put("email", email)
                put("deviceName", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("campaign", "")
                put("campaignSource", "")
                put("campaignMedium", "")
                putJsonObject("deviceMetadata") {
                    put("manufacturerName", Build.MANUFACTURER)
                    put("modelName", Build.MODEL)
                    put("osName", "Android")
                    put("osVersion", Build.VERSION.RELEASE)
                    put("manufacturingYear", "")
                    put("serialNumber", deviceId)
                    put("userAgent", "AltitudePlusPlus-Android Android/${Build.VERSION.RELEASE}")
                }
            }
        }
        val data = client.graphQL(mutation, variables, token)
        return data["identityAuthOtpInitiate"]["key"].string
            ?: throw ViewLiftException("Altitude+ didn't accept that email address.")
    }

    /** Step 2: exchange the emailed code for user tokens. */
    suspend fun verify(code: String, key: String, email: String, profile: DeviceProfile): UserSession {
        val token = anonymousAuthorization(profile)
        val mutation = """
            mutation (${'$'}site: String!, ${'$'}key: String, ${'$'}otp: String) {
              identityAuthOtpValidate(site: ${'$'}site, key: ${'$'}key, otp: ${'$'}otp) {
                userId refreshToken email authorizationToken isSubscribed
              }
            }
        """.trimIndent()
        val variables = buildJsonObject {
            put("site", AltitudeConfig.SITE)
            put("key", key)
            put("otp", code.trim())
        }
        val result = client.graphQL(mutation, variables, token)["identityAuthOtpValidate"]
        val authorization = result["authorizationToken"].string
        val refresh = result["refreshToken"].string
        if (authorization == null || refresh == null) {
            throw ViewLiftException("That code didn't work. Request a new one and try again.")
        }
        val session = UserSession(
            authorizationToken = authorization,
            refreshToken = refresh,
            userId = result["userId"].string,
            email = result["email"].string ?: email,
            isSubscribed = result["isSubscribed"].bool,
        )
        store(session)
        return session
    }

    suspend fun signOut(profile: DeviceProfile) {
        val token = _session.value?.authorizationToken
        clear()
        if (token != null) {
            runCatching {
                client.post(
                    "identity/signout",
                    mapOf("site" to AltitudeConfig.SITE, "platform" to profile.deviceType),
                    token,
                )
            }
        }
    }

    /** A user token valid for at least the next five minutes. */
    suspend fun validAuthorization(profile: DeviceProfile): String {
        val current = _session.value ?: throw ViewLiftException("Sign in to Altitude+ to watch.", "SIGNED_OUT")
        val left = Jwt.secondsLeft(current.authorizationToken)
        if (left != null && left > 300) return current.authorizationToken
        return refresh(profile).authorizationToken
    }

    /** User token if signed in, otherwise an anonymous one (for page queries). */
    suspend fun anyAuthorization(profile: DeviceProfile): String? =
        runCatching { validAuthorization(profile) }.getOrNull()
            ?: runCatching { anonymousAuthorization(profile) }.getOrNull()

    suspend fun refresh(profile: DeviceProfile): UserSession = refreshLock.withLock {
        val current = _session.value ?: throw ViewLiftException("Sign in to Altitude+ to watch.", "SIGNED_OUT")
        // Another caller may have refreshed while we waited for the lock.
        Jwt.secondsLeft(current.authorizationToken)?.let { if (it > 300) return current }

        val mutation = """
            mutation (${'$'}site: String!, ${'$'}device: EntitlementDevice!, ${'$'}refreshToken: String!) {
              identityRefreshToken(site: ${'$'}site, device: ${'$'}device, refreshToken: ${'$'}refreshToken) {
                authorizationToken refreshToken userId
              }
            }
        """.trimIndent()
        val updated = try {
            val result = client.graphQL(
                mutation,
                buildJsonObject {
                    put("site", AltitudeConfig.SITE)
                    put("device", profile.deviceType)
                    put("refreshToken", current.refreshToken)
                },
                current.authorizationToken,
            )["identityRefreshToken"]
            val authorization = result["authorizationToken"].string
                ?: throw ViewLiftException("Token refresh returned no token")
            current.copy(
                authorizationToken = authorization,
                refreshToken = result["refreshToken"].string ?: current.refreshToken,
            )
        } catch (e: Exception) {
            // The website refreshes through REST; try that before giving up.
            val json = runCatching { client.get("identity/refresh/${current.refreshToken}", token = null) }.getOrNull()
            val authorization = json["authorizationToken"].string
            if (authorization == null) {
                clear()
                throw ViewLiftException("Your Altitude+ sign-in expired. Please sign in again.", "SIGNED_OUT")
            }
            current.copy(
                authorizationToken = authorization,
                refreshToken = json["refreshToken"].string ?: current.refreshToken,
            )
        }
        store(updated)
        updated
    }

    /** Anonymous token the website uses before sign-in (needed for the OTP mutations). */
    suspend fun anonymousAuthorization(profile: DeviceProfile): String {
        anonymousToken?.let { token -> Jwt.secondsLeft(token)?.let { if (it > 300) return token } }
        val json = client.get(
            "identity/anonymous-token",
            mapOf("site" to AltitudeConfig.SITE, "platform" to profile.deviceType, "deviceId" to deviceId),
            token = null,
        )
        val token = json["authorizationToken"].string
            ?: throw ViewLiftException("Couldn't reach Altitude+. Check your internet connection.")
        anonymousToken = token
        return token
    }

    private fun load(): UserSession? {
        val authorization = prefs.getString("authorizationToken", null) ?: return null
        val refresh = prefs.getString("refreshToken", null) ?: return null
        return UserSession(
            authorizationToken = authorization,
            refreshToken = refresh,
            userId = prefs.getString("userId", null),
            email = prefs.getString("email", null),
            isSubscribed = if (prefs.contains("isSubscribed")) prefs.getBoolean("isSubscribed", false) else null,
        )
    }

    private fun store(session: UserSession) {
        prefs.edit()
            .putString("authorizationToken", session.authorizationToken)
            .putString("refreshToken", session.refreshToken)
            .putString("userId", session.userId)
            .putString("email", session.email)
            .apply { session.isSubscribed?.let { putBoolean("isSubscribed", it) } ?: remove("isSubscribed") }
            .apply()
        _session.value = session
    }

    private fun clear() {
        prefs.edit()
            .remove("authorizationToken").remove("refreshToken").remove("userId")
            .remove("email").remove("isSubscribed")
            .apply()
        _session.value = null
    }
}
