package com.altitudeplusplus.android.download

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.media3.exoplayer.offline.Download
import com.altitudeplusplus.android.playback.Mp4Rendition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Thrown when saving a clip needs Android's "All files access" first. */
class NeedsFileAccessException : IOException("Allow All files access so clips can be saved to Internal storage/A++.")

@Serializable
private data class ClipRecord(
    val meta: SavedMeta,
    val path: String,
    val state: Int,
    val bytes: Long = 0,
    val total: Long = 0,
)

/**
 * Unencrypted clips saved as ordinary MP4 files in the top-level `A++` folder of
 * internal storage, so any player or file manager can open them.
 */
class ClipFiles(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true }
    private val indexFile = File(context.filesDir, "clips.json")
    private val jobs = mutableMapOf<String, Job>()
    private val _records = MutableStateFlow(load())

    val folder: File get() = File(Environment.getExternalStorageDirectory(), FOLDER)

    val saved: StateFlow<List<SavedVideo>> get() = _saved
    private val _saved = MutableStateFlow(_records.value.map(::toSaved))

    init {
        // Anything mid-download when the app last stopped didn't finish.
        update { list ->
            list.map { if (it.state == Download.STATE_DOWNLOADING) it.copy(state = Download.STATE_FAILED) else it }
                // Forget files the user deleted or moved outside the app.
                .filter { it.state != Download.STATE_COMPLETED || File(it.path).exists() }
        }
    }

    fun hasFileAccess(): Boolean = Environment.isExternalStorageManager()

    fun save(meta: SavedMeta, renditions: List<Mp4Rendition>, maxHeight: Int) {
        if (!hasFileAccess()) throw NeedsFileAccessException()
        val rendition = renditions.lastOrNull { it.height <= maxHeight } ?: renditions.first()
        val existing = _records.value.firstOrNull { it.meta.videoId == meta.videoId }
        val target = existing?.path?.let(::File)?.takeIf { existing.state != Download.STATE_COMPLETED }
            ?: uniqueFile(meta.title)
        val record = ClipRecord(meta.copy(quality = "${rendition.height}p MP4"), target.absolutePath, Download.STATE_DOWNLOADING)
        update { list -> list.filter { it.meta.videoId != meta.videoId } + record }
        Log.i(TAG, "Saving clip ${meta.videoId} at ${rendition.height}p to ${target.absolutePath}")

        jobs[meta.videoId]?.cancel()
        jobs[meta.videoId] = scope.launch {
            val part = File(target.path + ".part")
            try {
                withContext(Dispatchers.IO) {
                    target.parentFile?.mkdirs()
                    http.newCall(Request.Builder().url(rendition.url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        val body = response.body ?: throw IOException("Empty response")
                        val total = body.contentLength()
                        var done = 0L
                        var lastReport = 0L
                        body.byteStream().use { input ->
                            part.outputStream().use { output ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) {
                                    ensureActive()
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    output.write(buffer, 0, read)
                                    done += read
                                    val now = System.currentTimeMillis()
                                    if (now - lastReport > 500) {
                                        lastReport = now
                                        progress(meta.videoId, done, total)
                                    }
                                }
                            }
                        }
                        if (!part.renameTo(target)) throw IOException("Couldn't move the file into A++")
                        finish(meta.videoId, Download.STATE_COMPLETED, target.length(), total)
                    }
                }
            } catch (e: Exception) {
                part.delete()
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "Clip save failed", e)
                finish(meta.videoId, Download.STATE_FAILED, 0, 0)
            } finally {
                jobs.remove(meta.videoId)
            }
        }
    }

    /** Stops any download and deletes the file. */
    fun remove(videoId: String) {
        jobs.remove(videoId)?.cancel()
        val record = _records.value.firstOrNull { it.meta.videoId == videoId } ?: return
        File(record.path).delete()
        File(record.path + ".part").delete()
        update { list -> list.filter { it.meta.videoId != videoId } }
    }

    fun removeAll() = _records.value.map { it.meta.videoId }.forEach(::remove)

    fun contains(videoId: String) = _records.value.any { it.meta.videoId == videoId }

    private fun progress(videoId: String, bytes: Long, total: Long) =
        update { list -> list.map { if (it.meta.videoId == videoId) it.copy(bytes = bytes, total = total) else it } }

    private fun finish(videoId: String, state: Int, bytes: Long, total: Long) =
        update { list -> list.map { if (it.meta.videoId == videoId) it.copy(state = state, bytes = bytes, total = total) else it } }

    private fun uniqueFile(title: String): File {
        val base = title.replace(Regex("""[\\/:*?"<>|]"""), "-").replace(Regex("\\s+"), " ").trim().take(120).ifEmpty { "Clip" }
        var candidate = File(folder, "$base.mp4")
        var n = 2
        val taken = _records.value.map { it.path }.toSet()
        while (candidate.exists() || candidate.absolutePath in taken) candidate = File(folder, "$base ($n).mp4").also { n++ }
        return candidate
    }

    @Synchronized
    private fun update(transform: (List<ClipRecord>) -> List<ClipRecord>) {
        _records.update(transform)
        val records = _records.value
        _saved.value = records.map(::toSaved)
        runCatching { indexFile.writeText(json.encodeToString(ListSerializer(ClipRecord.serializer()), records)) }
            .onFailure { Log.w(TAG, "Couldn't write clip index", it) }
    }

    private fun load(): List<ClipRecord> = runCatching {
        json.decodeFromString(ListSerializer(ClipRecord.serializer()), indexFile.readText())
    }.getOrDefault(emptyList())

    private fun toSaved(r: ClipRecord) = SavedVideo(
        meta = r.meta,
        state = r.state,
        percent = if (r.total > 0) r.bytes * 100f / r.total else 0f,
        bytes = r.bytes,
        request = null,
        file = File(r.path),
    )

    companion object {
        const val FOLDER = "A++"
        private const val TAG = OfflineStore.TAG
    }
}
