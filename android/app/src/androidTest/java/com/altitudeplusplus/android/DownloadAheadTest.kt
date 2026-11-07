package com.altitudeplusplus.android

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.altitudeplusplus.android.download.DownloadQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Download-ahead end to end on a real replay: download the first minute,
 * fetch a fresh streaming license, play the downloaded copy, and confirm the
 * video came from disk. Needs a signed-in session and internet.
 * Run with: -e replayId <id>
 */
@RunWith(AndroidJUnit4::class)
class DownloadAheadTest {
    @Test
    fun downloadedReplayPlaysFromDiskWithStreamingLicense() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val replayId = InstrumentationRegistry.getArguments().getString("replayId")
        assumeTrue("Pass -e replayId <id>", replayId != null)
        val graph = (instrumentation.targetContext.applicationContext as AltitudeApp).graph
        assumeTrue("Not signed in", graph.auth.session.value != null)
        val offline = graph.offline
        val id = replayId!!

        try {
            // 1. Download the first minute at 480p (no saved license involved).
            val source = graph.resolvePlayback(id)
            assumeTrue("Not a protected replay", source.widevine != null)
            // -e useService true exercises the real background download service.
            val useService = InstrumentationRegistry.getArguments().getString("useService") == "true"
            offline.save(source, id, "Test replay", null, 60, DownloadQuality.DATA_SAVER, testDurationMs = 60_000, useService = useService)
            val saved = withTimeout(180_000) {
                while (true) {
                    val s = offline.find(id)
                    if (s != null && (s.isComplete || s.isFailed)) return@withTimeout s
                    delay(500)
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }
            assertTrue("Download failed", saved.isComplete)
            assertTrue("Should need internet to start", saved.needsInternet)
            Log.i(TAG, "Downloaded ${saved.bytes} bytes")

            // 2. Fresh streaming license, then play the downloaded copy.
            val license = graph.resolvePlayback(id).widevine!!
            val cachedBytes = AtomicLong()
            val ready = CountDownLatch(1)
            var error: PlaybackException? = null
            var state = Player.STATE_IDLE
            lateinit var player: ExoPlayer
            instrumentation.runOnMainSync {
                val factory = offline.playbackDataSourceFactory(object : CacheDataSource.EventListener {
                    override fun onCachedBytesRead(cacheSizeBytes: Long, cachedBytesRead: Long) {
                        cachedBytes.addAndGet(cachedBytesRead)
                    }

                    override fun onCacheIgnored(reason: Int) {}
                })
                player = ExoPlayer.Builder(instrumentation.targetContext)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(factory))
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
                player.volume = 0f
                player.setMediaItem(offline.offlineMediaItem(saved, "Test replay", license))
                player.prepare()
                player.play()
            }
            ready.await(30, TimeUnit.SECONDS)
            // Let it decode a few seconds of protected video.
            Thread.sleep(4_000)
            var position = 0L
            instrumentation.runOnMainSync {
                position = player.currentPosition
                player.release()
            }
            Log.i(TAG, "Played to ${position}ms, ${cachedBytes.get()} bytes read from the download")
            assertNull("Playback error: ${error?.errorCodeName} ${error?.cause}", error)
            assertEquals(Player.STATE_READY, state)
            assertTrue("Video didn't advance", position > 1_000)
            assertTrue("Nothing was read from the download", cachedBytes.get() > 0)
        } finally {
            removeAndWait(id)
        }
    }

    /** Removes the test download; run alone with -e replayId <id> to clean up. */
    @Test
    fun cleanUpTestDownload() = runBlocking {
        val replayId = InstrumentationRegistry.getArguments().getString("replayId")
        assumeTrue("Pass -e replayId <id>", replayId != null)
        removeAndWait(replayId!!)
    }

    private suspend fun removeAndWait(id: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val offline = (instrumentation.targetContext.applicationContext as AltitudeApp).graph.offline
        instrumentation.runOnMainSync { offline.downloadManager.removeDownload(id) }
        // Removal is asynchronous; don't let the test process exit first.
        withTimeout(30_000) {
            while (offline.downloadManager.downloadIndex.getDownload(id) != null) delay(250)
        }
    }

    private companion object {
        const val TAG = "DownloadAheadTest"
    }
}
