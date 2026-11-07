package com.altitudeplusplus.android

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.altitudeplusplus.android.download.OfflineStore
import com.altitudeplusplus.android.playback.StreamResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic, not a regression test: asks Altitude+'s license server for a
 * saved (offline) Widevine license for one replay under each device identity
 * and logs what it answers. Uses the signed-in session already on the device.
 * Run with: -e replayId <id>
 */
@RunWith(AndroidJUnit4::class)
class OfflineLicenseProbeTest {
    @Test
    fun probeOfflineLicensePerIdentity() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val replayId = InstrumentationRegistry.getArguments().getString("replayId")
        assumeTrue("Pass -e replayId <id>", replayId != null)
        val graph = (instrumentation.targetContext.applicationContext as AltitudeApp).graph
        assumeTrue("Not signed in", graph.auth.session.value != null)
        val resolver = StreamResolver(graph.client)

        for (profile in listOf(DeviceProfile.FIRE_TV, DeviceProfile.ANDROID_PHONE, DeviceProfile.WEB)) {
            val result = runCatching {
                val token = graph.auth.validAuthorization(profile)
                val json = resolver.videoEntitlement(replayId!!, token, profile, graph.location.current())
                val source = StreamResolver.source(json, profile, graph.auth.deviceId)
                val drm = source.widevine ?: error("no Widevine entry")
                val http = graph.offline.httpFactory
                val manifest = DashUtil.loadManifest(http.createDataSource(), android.net.Uri.parse(source.url))
                val format = DashUtil.loadFormatWithDrmInitData(http.createDataSource(), manifest.getPeriod(0))
                    ?: error("no DRM init data")
                val config = MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(drm.licenseUrl)
                    .setLicenseRequestHeaders(mapOf("X-AxDRM-Message" to drm.licenseToken!!))
                    .build()
                val helper = OfflineLicenseHelper.newWidevineInstance(config, http, DrmSessionEventListener.EventDispatcher())
                try {
                    val keySetId = helper.downloadLicense(format)
                    val remaining = helper.getLicenseDurationRemainingSec(keySetId)
                    runCatching { helper.releaseLicense(keySetId) }
                    "GRANTED, license ${remaining.first}s, playback ${remaining.second}s"
                } finally {
                    helper.release()
                }
            }.getOrElse { "REFUSED: ${OfflineStore.licenseErrorDetail(it)}" }
            Log.i(TAG, "${profile.name}: $result")
            println("PROBE ${profile.name}: $result")
        }
    }

    private companion object {
        const val TAG = "LicenseProbe"
    }
}
