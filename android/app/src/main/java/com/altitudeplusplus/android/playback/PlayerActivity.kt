package com.altitudeplusplus.android.playback

import android.app.PictureInPictureParams
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.util.Log
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.altitudeplusplus.android.AltitudeApp
import com.altitudeplusplus.android.ui.AltitudeTheme
import kotlinx.coroutines.launch

/**
 * Full-screen player for the live channel and on-demand videos.
 *
 * Protected streams are decrypted only inside Android's Widevine module, using
 * licenses Altitude+ issues to the signed-in account. This app never sees keys
 * or decrypted video (docs/API.md, "DRM").
 */
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private var status by mutableStateOf<Status>(Status.Loading)
    private val isLive get() = intent.getStringExtra(EXTRA_VIDEO_ID) == null
    private var playingSaved = false

    private sealed interface Status {
        data object Loading : Status
        data object Playing : Status
        data class Failed(val message: String) : Status
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val graph = (application as AltitudeApp).graph
        val exo = ExoPlayer.Builder(this)
            // Saved segments come from disk; everything else streams as usual.
            .setMediaSourceFactory(androidx.media3.exoplayer.source.DefaultMediaSourceFactory(graph.offline.playbackDataSourceFactory))
            .build()
            .also { player = it }
        exo.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.e(TAG, "Player error ${error.errorCodeName}", error)
                status = Status.Failed(describe(error))
            }

            override fun onPlaybackStateChanged(state: Int) {
                Log.i(TAG, "State ${stateName(state)}")
                if (state == Player.STATE_READY) status = Status.Playing
            }
        })

        setContent {
            AltitudeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { context ->
                            PlayerView(context).apply {
                                player = exo
                                setShowNextButton(false)
                                setShowPreviousButton(false)
                            }
                        },
                    )
                    when (val current = status) {
                        Status.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                        Status.Playing -> Unit
                        is Status.Failed -> Column(
                            modifier = Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(current.message, color = Color.White, textAlign = TextAlign.Center)
                            Button(onClick = ::start) { Text("Try again") }
                        }
                    }
                }
            }
        }
        start()
    }

    private fun start() {
        status = Status.Loading
        val graph = (application as AltitudeApp).graph
        val videoId = intent.getStringExtra(EXTRA_VIDEO_ID)
        val title = intent.getStringExtra(EXTRA_TITLE)
        val saved = videoId?.let { graph.offline.find(it) }?.takeIf { it.isComplete }
        if (saved != null) {
            playingSaved = true
            if (!saved.needsInternet) {
                Log.i(TAG, "Playing saved file for $videoId")
                playItem(graph.offline.offlineMediaItem(saved, title))
                return
            }
            // Downloaded ahead: video is on the phone, but Altitude+ only issues
            // streaming licenses, so get a fresh one for this playback.
            Log.i(TAG, "Playing downloaded copy of $videoId with a fresh streaming license")
            lifecycleScope.launch {
                try {
                    val license = graph.resolvePlayback(videoId).widevine
                        ?: error("Altitude+ returned no license details")
                    playItem(graph.offline.offlineMediaItem(saved, title, license))
                } catch (e: Exception) {
                    Log.e(TAG, "Couldn't get a streaming license for the download", e)
                    status = Status.Failed(
                        "This replay is downloaded, but Altitude+ needs to approve each playback online. " +
                            "Connect to the internet and try again.\n\n(${e.message})",
                    )
                }
            }
            return
        }
        Log.i(TAG, "Resolving ${videoId ?: "live channel"} ($title)")
        lifecycleScope.launch {
            try {
                val source = graph.resolvePlayback(videoId)
                // Never log the license token.
                Log.i(TAG, "Source ${if (source.isDash) "DASH" else "HLS"}, drm=${source.widevine != null}, url=${source.url.substringBefore('?')}")
                play(source, title)
            } catch (e: Exception) {
                Log.e(TAG, "Couldn't resolve stream", e)
                status = Status.Failed(e.message ?: "Couldn't start playback.")
            }
        }
    }

    private fun playItem(item: MediaItem) {
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun play(source: PlaybackSource, title: String?) {
        val item = MediaItem.Builder()
            .setUri(source.url)
            .setMimeType(if (source.isDash) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
            .apply {
                source.widevine?.let { drm ->
                    setDrmConfiguration(
                        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                            .setLicenseUri(drm.licenseUrl)
                            .setLicenseRequestHeaders(
                                drm.licenseToken?.let { mapOf("X-AxDRM-Message" to it) } ?: emptyMap(),
                            )
                            // The live stream rotates keys; each new key ID needs its own session.
                            .setMultiSession(true)
                            .build(),
                    )
                }
            }
            .build()
        player?.apply {
            setMediaItem(item)
            prepare()
            playWhenReady = true
        }
    }

    private fun describe(error: PlaybackException): String = if (playingSaved && error.errorCode in
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED..PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED
    ) {
        "Altitude+ didn't approve playback of this download. Check your internet connection and sign-in, then try again."
    } else when (error.errorCode) {
        PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED ->
            "Altitude+ refused the playback license. Your sign-in or location may have changed — try again."
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
        PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
        PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR ->
            "This phone's Widevine DRM couldn't play the stream (${error.errorCodeName})."
        PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> "Fell behind the live stream. Try again to jump to live."
        else -> "Playback failed (${error.errorCodeName})."
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (player?.isPlaying == true) {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (!isInPictureInPictureMode && !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            player?.pause()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isInPictureInPictureMode) player?.pause()
    }

    override fun onStart() {
        super.onStart()
        // Resume where we left off; for live, jump back to the live edge.
        player?.let { if (it.playbackState != Player.STATE_IDLE) { if (isLive) it.seekToDefaultPosition(); it.play() } }
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun stateName(state: Int) = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> state.toString()
    }

    companion object {
        private const val TAG = "AltitudePlayer"
        private const val EXTRA_VIDEO_ID = "videoId"
        private const val EXTRA_TITLE = "title"

        fun live(context: Context, title: String?) =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_TITLE, title)

        fun video(context: Context, videoId: String, title: String) =
            Intent(context, PlayerActivity::class.java).putExtra(EXTRA_VIDEO_ID, videoId).putExtra(EXTRA_TITLE, title)
    }
}
