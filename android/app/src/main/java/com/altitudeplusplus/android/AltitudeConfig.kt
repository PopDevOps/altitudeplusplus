package com.altitudeplusplus.android

/**
 * Static values for the Altitude+ tenant on the ViewLift platform.
 * See docs/API.md. Mirrors appletv/AltitudePlusPlus/Config/AltitudeConfig.swift.
 */
object AltitudeConfig {
    const val SITE = "altitude"
    const val API_BASE = "https://altitude.api.viewlift.com"
    const val GRAPHQL = "$API_BASE/graphql"

    /** Public API key the website sends as `x-api-key` on every request. */
    const val API_KEY = "WX41iaJiOw7hJW8sNbDP5JpVwmjaH6t6y3xbQUsc"

    /** The "24x7 FEED" linear channel (altitudeplus.com/live-24x7). */
    const val LIVE_CONTENT_ID = "a6967d3f-2501-47f1-a7db-92faf7f6872f"
    const val LIVE_CHANNEL_ID = "de0a3981-03fa-4104-bf95-87735ebe4a8a"
    const val LIVE_CHANNEL_TITLE = "Altitude Sports"

    /** XMLTV guide for the live channel. Public, no auth required. */
    const val EPG_URL =
        "https://altitude-cached.api.viewlift.com/v4/content/epg/de0a3981-03fa-4104-bf95-87735ebe4a8a/tv.xml?meta=eyJmb3JtYXQiOiJHUkFDRU5PVEVfSlNPTiIsInNvdXJjZVVybCI6Imh0dHBzOi8vZGF0YS50bXNhcGkuY29tL3YxLjEvc3RhdGlvbnMvNjU1OTYvYWlyaW5ncz9hcGlfa2V5PWN4OWM4M2piNXp5enI2NzZxZW13Zm04eiJ9"
}

/**
 * How the app identifies itself to ViewLift. There is no Android identity, so
 * Android clients present as Fire TV (docs/API.md, "Device identity"). The web
 * profile is a fallback if an account or plan rejects the Fire TV identity.
 */
enum class DeviceProfile(
    val title: String,
    /** GraphQL `EntitlementDevice` and REST `deviceType`. */
    val deviceType: String,
    /** REST `contentConsumption`. */
    val contentConsumption: String,
    /** GraphQL `Device` for page layouts. */
    val pageDevice: String,
) {
    FIRE_TV("Fire TV", "fire_tv", "fireTv", "FIRETV"),
    WEB("Web browser", "web_browser", "web", "WEB"),
}
