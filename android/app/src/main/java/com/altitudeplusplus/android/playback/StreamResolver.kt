package com.altitudeplusplus.android.playback

import com.altitudeplusplus.android.AltitudeConfig
import com.altitudeplusplus.android.DeviceProfile
import com.altitudeplusplus.android.api.ViewLiftClient
import com.altitudeplusplus.android.api.ViewLiftException
import com.altitudeplusplus.android.api.bool
import com.altitudeplusplus.android.api.first
import com.altitudeplusplus.android.api.get
import com.altitudeplusplus.android.api.string
import com.altitudeplusplus.android.location.Coordinate
import kotlinx.serialization.json.JsonElement

data class WidevineInfo(val licenseUrl: String, val licenseToken: String?)

/** What the player needs: a manifest URL, and Widevine details for protected streams. */
data class PlaybackSource(val url: String, val isDash: Boolean, val widevine: WidevineInfo?)

class EntitlementException(val code: String, serverMessage: String?) : Exception(describe(code, serverMessage)) {
    companion object {
        fun describe(code: String, serverMessage: String?): String {
            val upper = code.uppercase()
            return when {
                "GEO" in upper || "COUNTRY" in upper || "HBA" in upper ->
                    "Altitude+ says this location is outside its viewing territory. Allow location access, or check you're inside the broadcast area."
                upper in setOf("SVOD_TVE_SUBSCRIPTION_NOT_FOUND", "SUBSCRIPTION_NOT_FOUND", "FORBIDDEN") ->
                    "This account doesn't have an active Altitude+ subscription."
                upper == "CANT_ACCESS_TVE_CONTENT" -> "Your TV provider login doesn't include this channel."
                else -> serverMessage ?: "Altitude+ wouldn't start the stream ($code)."
            }
        }
    }
}

/**
 * Turns ViewLift entitlement responses into something ExoPlayer can play.
 *
 * Live (`/v3/entitlement/linearchannel`) and on-demand (`/entitlement/video/status`)
 * share a shape: `<contentType>.streamingInfo.videoAssets`.
 * - DRM content (live, full replays) has a `widevine` entry with a DASH `url`,
 *   ViewLift's Axinom proxy `licenseUrl`, and a `licenseToken` sent as the
 *   `X-AxDRM-Message` header. The token is tied to this device's IP and device
 *   type, so it must be fetched on the phone that plays it.
 * - Clips are unencrypted HLS in `hls`.
 */
class StreamResolver(private val client: ViewLiftClient) {

    suspend fun liveEntitlement(token: String, profile: DeviceProfile, coordinate: Coordinate?): JsonElement =
        client.get(
            "v3/entitlement/linearchannel",
            mapOf(
                "id" to AltitudeConfig.LIVE_CONTENT_ID,
                "channelId" to AltitudeConfig.LIVE_CHANNEL_ID,
                "ssaiDisable" to "false",
            ) + deviceQuery(profile, coordinate),
            token,
        )

    suspend fun videoEntitlement(videoId: String, token: String, profile: DeviceProfile, coordinate: Coordinate?): JsonElement =
        client.get("entitlement/video/status", mapOf("id" to videoId) + deviceQuery(profile, coordinate), token)

    private fun deviceQuery(profile: DeviceProfile, coordinate: Coordinate?) = mapOf(
        "deviceType" to profile.deviceType,
        "contentConsumption" to profile.contentConsumption,
        "latitude" to coordinate?.latitude?.toString(),
        "longitude" to coordinate?.longitude?.toString(),
    )

    companion object {
        fun source(json: JsonElement, profile: DeviceProfile, deviceId: String): PlaybackSource {
            (json["errorCode"].string ?: json["code"].string)?.let { code ->
                throw EntitlementException(code, json["errorMessage"].string ?: json["message"].string)
            }
            // Ignore the placeholder copies under `linearchannel.channels[]`,
            // which carry blank license URLs.
            val contentType = if (json["linearchannel"] != null) "linearchannel" else json["contentType"].string ?: "video"
            val assets = json[contentType]["streamingInfo"]["videoAssets"] ?: json.first("videoAssets")
                ?: run {
                    if (json["playable"].bool == false) {
                        throw ViewLiftException("Altitude+ says this video isn't available to play right now.")
                    }
                    throw ViewLiftException("Altitude+ returned no stream for this video.")
                }

            val widevine = listOf(assets["widevineCmaf"], assets["widevine"])
                .firstOrNull { it["url"].string.isUsable() && it["licenseUrl"].string.isUsable() }
            if (widevine != null) {
                return PlaybackSource(
                    url = expandMacros(widevine["url"].string!!, deviceId, profile),
                    isDash = true,
                    widevine = WidevineInfo(widevine["licenseUrl"].string!!.trim(), widevine["licenseToken"].string),
                )
            }

            val drmEnabled = json[contentType]["drmEnabled"].bool ?: json.first("drmEnabled").bool ?: false
            val hls = assets["hls"].string
            if (!drmEnabled && hls.isUsable()) {
                return PlaybackSource(expandMacros(hls!!, deviceId, profile), isDash = false, widevine = null)
            }
            throw ViewLiftException(
                "Altitude+ didn't offer a Widevine stream for this device. Try Settings → Device profile → Web browser.",
            )
        }

        private fun String?.isUsable() = !this.isNullOrBlank()

        /** The web player substitutes `VIEWLIFT_*` ad macros before loading a URL. */
        fun expandMacros(url: String, deviceId: String, profile: DeviceProfile): String {
            if ("VIEWLIFT_" !in url) return url
            val now = System.currentTimeMillis()
            val values = mapOf(
                "VIEWLIFT_CACHEBUSTER" to (now / 1000).toString(),
                "VIEWLIFT_TIMESTAMP" to (now / 1000).toString(),
                "VIEWLIFT_TIMESTAMP_MS" to now.toString(),
                "VIEWLIFT_DEVICE_ID" to deviceId,
                "VIEWLIFT_DID" to deviceId,
                "VIEWLIFT_DEVICETYPE" to profile.deviceType,
                "VIEWLIFT_WIDTH" to "1920",
                "VIEWLIFT_HEIGHT" to "1080",
                "VIEWLIFT_AUTOPLAY" to "1",
                "VIEWLIFT_MUTE" to "0",
                "VIEWLIFT_DNT" to "1",
            )
            var result = url
            values.entries.sortedByDescending { it.key.length }.forEach { (macro, value) ->
                result = result.replace(macro, value)
            }
            return result.replace(Regex("VIEWLIFT_[A-Z0-9_]+"), "")
        }
    }
}
