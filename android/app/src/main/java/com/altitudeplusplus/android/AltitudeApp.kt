package com.altitudeplusplus.android

import android.app.Application
import android.content.Context
import com.altitudeplusplus.android.api.ViewLiftClient
import com.altitudeplusplus.android.auth.AuthService
import com.altitudeplusplus.android.download.DownloadQuality
import com.altitudeplusplus.android.download.OfflineStore
import com.altitudeplusplus.android.guide.GuideRepository
import com.altitudeplusplus.android.location.LocationProvider
import com.altitudeplusplus.android.ondemand.OnDemandRepository
import com.altitudeplusplus.android.playback.OfflineReport
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
    val offline = OfflineStore(context)
    private val streams = StreamResolver(client)

    private val _profile = MutableStateFlow(
        prefs.getString("profile", null)?.let { runCatching { DeviceProfile.valueOf(it) }.getOrNull() }
            ?: DeviceProfile.FIRE_TV,
    )
    val profile: StateFlow<DeviceProfile> = _profile

    private val _quality = MutableStateFlow(
        prefs.getString("downloadQuality", null)?.let { runCatching { DownloadQuality.valueOf(it) }.getOrNull() }
            ?: DownloadQuality.STANDARD,
    )
    val downloadQuality: StateFlow<DownloadQuality> = _quality

    fun setDownloadQuality(quality: DownloadQuality) {
        prefs.edit().putString("downloadQuality", quality.name).apply()
        _quality.value = quality
    }

    /** Saves a clip as an MP4, or downloads a protected replay ahead. Returns true for replays. */
    suspend fun saveVideo(videoId: String, title: String, imageUrl: String?, runtime: Int?): Boolean {
        val source = resolvePlayback(videoId)
        if (source.widevine == null && source.mp4.isNotEmpty()) {
            // Unencrypted clip: save a real MP4 into Internal storage/A++.
            offline.saveClipFile(source, videoId, title, imageUrl, runtime, downloadQuality.value)
        } else {
            offline.save(source, videoId, title, imageUrl, runtime, downloadQuality.value)
        }
        return source.widevine != null
    }

    /**
     * Signing out removes saved replays (their licenses belong to the account).
     * Clip MP4s in A++ are ordinary files and stay.
     */
    suspend fun signOut() {
        offline.removeAllInApp()
        auth.signOut(profile.value)
    }

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

    /**
     * Asks Altitude+'s license server for a saved (persistent) license for this
     * stream, exactly as a true offline save would, and reports its answer.
     */
    suspend fun checkOffline(videoId: String?, title: String): OfflineReport {
        val source = resolvePlayback(videoId)
        if (source.widevine == null) return OfflineReport.Unencrypted(title)
        return OfflineReport.Protected(title, offline.probePersistentLicense(source))
    }
}
