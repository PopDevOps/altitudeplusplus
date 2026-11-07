package com.altitudeplusplus.android

import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Plays an MP4 from Internal storage/A++ through the same data source the
 * player uses for saved videos. Regression test for file:// URIs being sent
 * to the HTTP-only data source.
 */
@RunWith(AndroidJUnit4::class)
class SavedClipPlaybackTest {
    private val file = File(File(Environment.getExternalStorageDirectory(), "A++"), "__playback_test.mp4")

    @After
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun savedMp4InAppPlusPlusReachesReady() {
        assumeTrue("All files access not granted", Environment.isExternalStorageManager())
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val app = instrumentation.targetContext.applicationContext as AltitudeApp

        // A real 240p Altitude+ clip. It may be pushed over adb beforehand
        // (so the test works with the phone offline); otherwise download it.
        file.parentFile!!.mkdirs()
        if (!file.exists() || file.length() == 0L) {
            OkHttpClient().newCall(Request.Builder().url(CLIP_URL).build()).execute().use { response ->
                check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }
                file.outputStream().use { response.body!!.byteStream().copyTo(it) }
            }
        }

        val ready = CountDownLatch(1)
        var error: PlaybackException? = null
        var state = Player.STATE_IDLE
        lateinit var player: ExoPlayer
        instrumentation.runOnMainSync {
            player = ExoPlayer.Builder(instrumentation.targetContext)
                .setMediaSourceFactory(DefaultMediaSourceFactory(app.graph.offline.playbackDataSourceFactory))
                .build()
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    state = playbackState
                    if (playbackState == Player.STATE_READY) ready.countDown()
                }

                override fun onPlayerError(e: PlaybackException) {
                    error = e
                    ready.countDown()
                }
            })
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.volume = 0f
            player.prepare()
        }
        ready.await(20, TimeUnit.SECONDS)
        var duration = 0L
        instrumentation.runOnMainSync {
            duration = player.duration
            player.release()
        }
        assertNull("Playback error: ${error?.errorCodeName} ${error?.cause}", error)
        assertEquals(Player.STATE_READY, state)
        check(duration > 10_000) { "Unexpected duration $duration ms" }
    }

    private companion object {
        const val CLIP_URL = "https://altitude.asset.viewlift.com/Renditions/20261106/" +
            "1794007812316_altitude_Bednar-Post-Presser-11-5-1794007730488_CUSTOM_CODEC_TS/" +
            "Bednar-Post-Presser-11-5-1794007730488_240_0.mp4"
    }
}
