package com.altitudeplusplus.android

import android.app.Application
import android.content.Context
import com.altitudeplusplus.android.api.ViewLiftClient
import com.altitudeplusplus.android.auth.AuthService
import com.altitudeplusplus.android.guide.GuideRepository
import com.altitudeplusplus.android.location.LocationProvider
import com.altitudeplusplus.android.ondemand.OnDemandRepository
import com.altitudeplusplus.android.playback.PlaybackSource
import com.altitudeplusplus.android.playback.StreamResolver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AltitudeApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** App-wide services. Small enough not to need a DI framework. */
class AppGraph(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val client = ViewLiftClient()
    val auth = AuthService(context, client)
    val location = LocationProvider(context)
    val guide = GuideRepository(client.http)
    val onDemand = OnDemandRepository(client)
    private val streams = StreamResolver(client)

    private val _profile = MutableStateFlow(
        prefs.getString("profile", null)?.let { runCatching { DeviceProfile.valueOf(it) }.getOrNull() }
            ?: DeviceProfile.FIRE_TV,
    )
    val profile: StateFlow<DeviceProfile> = _profile

    fun setProfile(profile: DeviceProfile) {
        prefs.edit().putString("profile", profile.name).apply()
        _profile.value = profile
    }

    /** Live channel when [videoId] is null, otherwise one on-demand video. */
    suspend fun resolvePlayback(videoId: String?): PlaybackSource {
        val profile = profile.value
        val token = auth.validAuthorization(profile)
        val coordinate = location.current()
        val json = if (videoId == null) {
            streams.liveEntitlement(token, profile, coordinate)
        } else {
            streams.videoEntitlement(videoId, token, profile, coordinate)
        }
        return StreamResolver.source(json, profile, auth.deviceId)
    }
}
