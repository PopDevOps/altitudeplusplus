package com.altitudeplusplus.android.download

import android.content.Context
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.dash.DashUtil
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.drm.DefaultDrmSessionManagerProvider
import androidx.media3.exoplayer.drm.DrmSessionEventListener
import androidx.media3.exoplayer.drm.OfflineLicenseHelper
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import com.altitudeplusplus.android.playback.PlaybackSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import okhttp3.OkHttpClient
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Saved alongside each download (in `DownloadRequest.data`). */
@Serializable
data class SavedMeta(
    val videoId: String,
    val title: String,
    val imageUrl: String? = null,
    val runtime: Int? = null,
    val protected: Boolean,
    val licenseUrl: String? = null,
    val savedAt: Long,
    val quality: String,
)

data class SavedVideo(
    val meta: SavedMeta,
    val state: Int,
    val percent: Float,
    val bytes: Long,
    /** Set for videos kept inside the app (Media3 download). */
    val request: DownloadRequest?,
    /** Set for clips saved as MP4 files in Internal storage/A++. */
    val file: File? = null,
) {
    /** Where the video lives, for display. */
    val location: String
        get() = file?.absolutePath?.replace(Regex("^/storage/emulated/0"), "Internal storage")
            ?: "Inside Altitude++ (private app storage${if (meta.protected) ", encrypted" else ""})"

    val isComplete get() = state == Download.STATE_COMPLETED
    val isActive get() = state == Download.STATE_DOWNLOADING || state == Download.STATE_QUEUED || state == Download.STATE_RESTARTING
    val isFailed get() = state == Download.STATE_FAILED
    /** Downloaded ahead: plays from disk but needs internet to get a streaming license. */
    val needsInternet get() = meta.protected
}

enum class DownloadQuality(val title: String, val maxHeight: Int) {
    DATA_SAVER("Data saver (480p)", 480),
    STANDARD("Standard (720p)", 720),
    HIGH("High (1080p)", 1080),
}

class SaveException(message: String) : IOException(message)

/**
 * Offline copies of on-demand videos.
 *
 * Protected videos stay encrypted on disk. Playing them offline uses a saved
 * Widevine license that Altitude+'s license server issues to this device; when
 * it expires, the video can't play until the license is renewed online. The app
 * never sees keys or decrypted video.
 */
@OptIn(UnstableApi::class)
class OfflineStore(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val json = Json { ignoreUnknownKeys = true }
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val cacheDir = File(context.filesDir, "downloads")
    private val cache = SimpleCache(cacheDir, NoOpCacheEvictor(), databaseProvider)

    val httpFactory: DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setUserAgent("AltitudePlusPlus-Android")
        .setAllowCrossProtocolRedirects(true)

    /**
     * Reads saved segments from disk and loads anything else through
     * DefaultDataSource, which handles http(s) streams and file:// MP4s in A++.
     */
    val playbackDataSourceFactory: DataSource.Factory = playbackDataSourceFactory(null)

    /** Same as [playbackDataSourceFactory], with an optional listener for cache reads (tests). */
    fun playbackDataSourceFactory(listener: CacheDataSource.EventListener?): DataSource.Factory = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, httpFactory))
        .setCacheWriteDataSinkFactory(null)
        .setEventListener(listener)

    val downloadManager = DownloadManager(context, databaseProvider, cache, httpFactory, Executors.newFixedThreadPool(4)).apply {
        maxParallelDownloads = 2
    }

    private val _inApp = MutableStateFlow<List<SavedVideo>>(emptyList())
    val clips = ClipFiles(context, OkHttpClient(), scope)

    /** Everything saved: in-app downloads plus clip MP4s in A++, newest first. */
    val saved: StateFlow<List<SavedVideo>> = combine(_inApp, clips.saved) { inApp, files ->
        (inApp + files).sortedByDescending { it.meta.savedAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())
    private var ticker: Job? = null

    init {
        downloadManager.addListener(object : DownloadManager.Listener {
            override fun onDownloadChanged(manager: DownloadManager, download: Download, finalException: Exception?) {
                finalException?.let { Log.w(TAG, "Download ${download.request.id} ended with error", it) }
                refresh()
            }

            override fun onDownloadRemoved(manager: DownloadManager, download: Download) = refresh()
            override fun onInitialized(manager: DownloadManager) = refresh()
        })
        refresh()
    }

    fun find(videoId: String): SavedVideo? = saved.value.firstOrNull { it.meta.videoId == videoId }

    /** Saves an unencrypted clip as an MP4 file in Internal storage/A++. */
    fun saveClipFile(source: PlaybackSource, videoId: String, title: String, imageUrl: String?, runtime: Int?, quality: DownloadQuality) {
        // Replace an older in-app copy of the same clip.
        if (_inApp.value.any { it.meta.videoId == videoId }) remove(videoId)
        val meta = SavedMeta(
            videoId = videoId, title = title, imageUrl = imageUrl, runtime = runtime, protected = false,
            savedAt = System.currentTimeMillis(), quality = quality.title,
        )
        clips.save(meta, source.mp4, quality.maxHeight)
    }

    /**
     * Prepares a download: picks one video quality, then hands it to
     * [AltitudeDownloadService]. Protected videos stay encrypted on disk.
     */
    suspend fun save(
        source: PlaybackSource,
        videoId: String,
        title: String,
        imageUrl: String?,
        runtime: Int?,
        quality: DownloadQuality,
        /** Tests only: download just the first part, and skip the background service. */
        testDurationMs: Long? = null,
        useService: Boolean = true,
    ) = withContext(Dispatchers.Main) {
        // DownloadHelper and its track selector must stay on one Looper thread.
        val drm = source.widevine
        val mediaItem = MediaItem.Builder()
            .setUri(source.url)
            .setMimeType(if (source.isDash) MimeTypes.APPLICATION_MPD else MimeTypes.APPLICATION_M3U8)
            .apply {
                if (drm != null) setDrmConfiguration(drmConfiguration(drm.licenseUrl, drm.licenseToken))
            }
            .build()

        val helper = DownloadHelper.Factory()
            .setDataSourceFactory(httpFactory)
            .setRenderersFactory(DefaultRenderersFactory(context))
            .setTrackSelectionParameters(DefaultTrackSelector.Parameters.Builder().build())
            .apply {
                if (drm != null) {
                    setDrmSessionManager(
                        DefaultDrmSessionManagerProvider().apply { setDrmHttpDataSourceFactory(httpFactory) }.get(mediaItem),
                    )
                }
            }
            .create(mediaItem)
        try {
            helper.prepareAwait()
            val selected = selectSingleQuality(helper, quality.maxHeight)
            Log.i(TAG, "Saving $videoId at ${selected?.height}p, ${selected?.bitrate} bps, periods=${helper.periodCount}")
            checkSpace(selected, runtime)

            // Altitude+ never issues saved (persistent) licenses for replays, so
            // protected videos are downloaded ahead and get a normal streaming
            // license from Altitude+ each time playback starts.
            val meta = SavedMeta(
                videoId = videoId,
                title = title,
                imageUrl = imageUrl,
                runtime = runtime,
                protected = drm != null,
                licenseUrl = drm?.licenseUrl,
                savedAt = find(videoId)?.meta?.savedAt ?: System.currentTimeMillis(),
                quality = quality.title,
            )
            val data = json.encodeToString(SavedMeta.serializer(), meta).toByteArray()
            val request = if (testDurationMs != null) {
                helper.getDownloadRequest(videoId, data, 0, testDurationMs)
            } else {
                helper.getDownloadRequest(videoId, data)
            }
            if (useService) {
                DownloadService.sendAddDownload(context, AltitudeDownloadService::class.java, request, false)
            } else {
                // The manager starts paused; normally the service resumes it.
                downloadManager.addDownload(request)
                downloadManager.resumeDownloads()
            }
        } finally {
            helper.release()
        }
    }

    fun remove(videoId: String) {
        if (clips.contains(videoId)) clips.remove(videoId)
        if (_inApp.value.any { it.meta.videoId == videoId }) {
            DownloadService.sendRemoveDownload(context, AltitudeDownloadService::class.java, videoId, false)
        }
    }

    /** Deletes everything, including clip MP4s in A++. */
    fun removeAll() {
        clips.removeAll()
        removeAllInApp()
    }

    /** Deletes saved replays and in-app clips; MP4s in A++ stay (they're ordinary files). */
    fun removeAllInApp() =
        DownloadService.sendRemoveAllDownloads(context, AltitudeDownloadService::class.java, false)

    /**
     * MediaItem that plays a saved video from disk. Clips need nothing else.
     * Protected (downloaded-ahead) videos need [streamingLicense]: a fresh
     * streaming license from the current entitlement, since Altitude+ doesn't
     * allow saved licenses.
     */
    fun offlineMediaItem(saved: SavedVideo, title: String?, streamingLicense: com.altitudeplusplus.android.playback.WidevineInfo? = null): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder().setTitle(title ?: saved.meta.title).build()
        saved.file?.let { file ->
            return MediaItem.Builder().setUri(android.net.Uri.fromFile(file)).setMediaMetadata(metadata).build()
        }
        val request = saved.request ?: error("Saved video has no data")
        val builder = MediaItem.Builder().setMediaMetadata(metadata)
        if (saved.meta.protected) {
            val license = streamingLicense ?: error("Protected download needs a streaming license")
            builder.setDrmConfiguration(drmConfiguration(license.licenseUrl, license.licenseToken))
        }
        return request.toMediaItem(builder)
    }

    /**
     * Asks Altitude+'s license server for a saved (persistent) license, the way
     * a true offline save would, and reports its answer. Releases it if granted.
     */
    suspend fun probePersistentLicense(source: PlaybackSource): String = withContext(Dispatchers.IO) {
        val drm = source.widevine ?: return@withContext "Unencrypted — no license needed."
        runCatching {
            val manifest = DashUtil.loadManifest(httpFactory.createDataSource(), android.net.Uri.parse(source.url))
            val format = DashUtil.loadFormatWithDrmInitData(httpFactory.createDataSource(), manifest.getPeriod(0))
                ?: error("No DRM details in the stream")
            val helper = OfflineLicenseHelper.newWidevineInstance(
                drmConfiguration(drm.licenseUrl, drm.licenseToken), httpFactory, DrmSessionEventListener.EventDispatcher(),
            )
            try {
                val keySetId = helper.downloadLicense(format)
                val remaining = helper.getLicenseDurationRemainingSec(keySetId).first
                runCatching { helper.releaseLicense(keySetId) }
                "GRANTED: Altitude+ issued a saved license (${remaining}s)."
            } finally {
                helper.release()
            }
        }.getOrElse { e ->
            val detail = licenseErrorDetail(e)
            if ("does not allow persisting" in detail) "REFUSED: $detail" else "ERROR: $detail"
        }
    }

    fun meta(request: DownloadRequest): SavedMeta? =
        runCatching { json.decodeFromString(SavedMeta.serializer(), String(request.data)) }.getOrNull()

    private fun refresh() {
        scope.launch {
            val list = withContext(Dispatchers.IO) {
                val result = mutableListOf<SavedVideo>()
                downloadManager.downloadIndex.getDownloads().use { cursor ->
                    while (cursor.moveToNext()) {
                        val d = cursor.download
                        if (d.state == Download.STATE_REMOVING) continue
                        val meta = meta(d.request) ?: continue
                        result += SavedVideo(meta, d.state, d.percentDownloaded.coerceAtLeast(0f), d.bytesDownloaded, d.request)
                    }
                }
                result
            }
            _inApp.value = list
            // Download progress isn't pushed, so poll while anything is active.
            if (list.any { it.isActive }) startTicker() else ticker?.cancel()
        }
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(1_000)
                refresh()
            }
        }
    }

    private fun drmConfiguration(licenseUrl: String, licenseToken: String?) =
        MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
            .setLicenseUri(licenseUrl)
            .setLicenseRequestHeaders(licenseToken?.let { mapOf("X-AxDRM-Message" to it) } ?: emptyMap())
            .setMultiSession(true)
            .build()

    /**
     * Keeps a single video rendition per period (the best one at or below
     * [maxHeight]) so a game isn't saved in all eight qualities. Audio uses the
     * default selection.
     */
    private fun selectSingleQuality(helper: DownloadHelper, maxHeight: Int): Format? {
        var chosen: Format? = null
        val params = DefaultTrackSelector.Parameters.Builder().build()
        for (period in 0 until helper.periodCount) {
            val mapped = helper.getMappedTrackInfo(period)
            helper.clearTrackSelections(period)
            for (renderer in 0 until mapped.rendererCount) {
                when (mapped.getRendererType(renderer)) {
                    C.TRACK_TYPE_VIDEO -> {
                        val groups = mapped.getTrackGroups(renderer)
                        var best: Triple<Int, Int, Format>? = null
                        var lowest: Triple<Int, Int, Format>? = null
                        for (g in 0 until groups.length) {
                            val group = groups[g]
                            for (t in 0 until group.length) {
                                val format = group.getFormat(t)
                                val supported = mapped.getTrackSupport(renderer, g, t) == C.FORMAT_HANDLED
                                if (!supported) continue
                                if (lowest == null || format.height < lowest.third.height) lowest = Triple(g, t, format)
                                if (format.height <= maxHeight && (best == null || format.bitrate > best.third.bitrate)) {
                                    best = Triple(g, t, format)
                                }
                            }
                        }
                        val pick = best ?: lowest ?: continue
                        chosen = pick.third
                        helper.addTrackSelectionForSingleRenderer(
                            period,
                            renderer,
                            params,
                            listOf(DefaultTrackSelector.SelectionOverride(pick.first, pick.second)),
                        )
                    }
                    C.TRACK_TYPE_AUDIO -> helper.addTrackSelectionForSingleRenderer(period, renderer, params, emptyList())
                }
            }
        }
        return chosen
    }

    private fun checkSpace(format: Format?, runtime: Int?) {
        val bitrate = format?.bitrate?.takeIf { it > 0 } ?: return
        val seconds = runtime?.takeIf { it > 0 } ?: return
        // Video plus ~10% for audio and overhead.
        val needed = (bitrate.toLong() * seconds / 8 * 1.1).toLong()
        val free = context.filesDir.usableSpace
        if (needed > free - 500L * 1024 * 1024) {
            throw SaveException(
                "Not enough space: this needs about ${needed / 1_000_000} MB and ${free / 1_000_000} MB is free.",
            )
        }
    }

    private suspend fun DownloadHelper.prepareAwait() = suspendCancellableCoroutine { continuation ->
        prepare(object : DownloadHelper.Callback {
            override fun onPrepared(helper: DownloadHelper, tracksInfoAvailable: Boolean) {
                if (continuation.isActive) continuation.resume(Unit)
            }

            override fun onPrepareError(helper: DownloadHelper, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(SaveException("Couldn't read this video's stream: ${e.message}"))
            }
        })
    }

    companion object {
        const val TAG = "AltitudeOffline"

        /** HTTP status plus the license server's own error header/body, if any. */
        fun licenseErrorDetail(e: Throwable): String {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                    // The status line is stored under a null key (despite the non-null
                    // Kotlin type), so treat keys as nullable.
                    val headers = cause.headerFields.entries
                        .filter { entry -> (entry.key as String?)?.contains("errormessage", ignoreCase = true) == true }
                        .joinToString { entry -> "${entry.key}=${entry.value.joinToString()}" }
                    val body = String(cause.responseBody).take(300).replace("\n", " ")
                    return "HTTP ${cause.responseCode}" +
                        (if (headers.isNotEmpty()) "; $headers" else "") +
                        (if (body.isNotBlank()) "; body=$body" else "")
                }
                cause = cause.cause
            }
            return e.message ?: e.javaClass.simpleName
        }
    }
}
