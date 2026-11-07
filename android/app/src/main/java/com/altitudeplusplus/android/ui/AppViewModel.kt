package com.altitudeplusplus.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.altitudeplusplus.android.AltitudeApp
import com.altitudeplusplus.android.DeviceProfile
import com.altitudeplusplus.android.guide.Program
import com.altitudeplusplus.android.ondemand.ContentRow
import com.altitudeplusplus.android.ondemand.Team
import com.altitudeplusplus.android.ondemand.VideoItem
import com.altitudeplusplus.android.download.DownloadQuality
import com.altitudeplusplus.android.download.NeedsFileAccessException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.altitudeplusplus.android.playback.OfflineReport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Loaded<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class AppViewModel(app: Application) : AndroidViewModel(app) {
    val graph = (app as AltitudeApp).graph
    val session = graph.auth.session
    val profile = graph.profile

    private val _guide = MutableStateFlow<Load<List<Program>>>(Load.Loading)
    val guide: StateFlow<Load<List<Program>>> = _guide
    private var guideLoadedAt = 0L

    private val _rows = MutableStateFlow<Map<Team, Load<List<ContentRow>>>>(emptyMap())
    val rows: StateFlow<Map<Team, Load<List<ContentRow>>>> = _rows
    private val loadingMore = mutableSetOf<String>()

    // Sign-in
    private val _signIn = MutableStateFlow(SignInState())
    val signIn: StateFlow<SignInState> = _signIn

    data class SignInState(
        val email: String = "",
        val key: String? = null,
        val busy: Boolean = false,
        val error: String? = null,
    )

    fun refreshGuideIfStale() {
        if (System.currentTimeMillis() - guideLoadedAt < 30 * 60_000 && _guide.value is Load.Loaded) return
        viewModelScope.launch {
            if (_guide.value !is Load.Loaded) _guide.value = Load.Loading
            _guide.value = runCatching { graph.guide.load() }
                .onSuccess { guideLoadedAt = System.currentTimeMillis() }
                .fold({ Load.Loaded(it) }, { Load.Failed(it.message ?: "Couldn't load the guide.") })
        }
    }

    fun loadTeam(team: Team, force: Boolean = false) {
        val current = _rows.value[team]
        if (!force && (current is Load.Loaded || current is Load.Loading)) return
        _rows.update { it + (team to Load.Loading) }
        viewModelScope.launch {
            val p = profile.value
            val result = runCatching { graph.onDemand.rows(team, p, graph.auth.anyAuthorization(p)) }
            _rows.update {
                it + (team to result.fold({ rows -> Load.Loaded(rows) }, { e -> Load.Failed(e.message ?: "Couldn't load videos.") }))
            }
        }
    }

    fun loadMore(team: Team, row: ContentRow) {
        if (row.next == null || !loadingMore.add(row.id)) return
        viewModelScope.launch {
            try {
                val p = profile.value
                val updated = runCatching { graph.onDemand.more(team, row, p, graph.auth.anyAuthorization(p)) }.getOrNull()
                    ?: row.copy(next = null)
                _rows.update { map ->
                    val loaded = map[team] as? Load.Loaded ?: return@update map
                    map + (team to Load.Loaded(loaded.value.map { if (it.id == row.id) updated else it }))
                }
            } finally {
                loadingMore.remove(row.id)
            }
        }
    }

    val saved = graph.offline.saved
    val downloadQuality = graph.downloadQuality
    private val savingIds = MutableStateFlow<Set<String>>(emptySet())
    /** Videos being prepared (license + track selection) before the download starts. */
    val preparing: StateFlow<Set<String>> = savingIds

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    fun save(item: VideoItem) = save(item.id, item.title, item.imageUrl, item.runtime)

    fun save(videoId: String, title: String, imageUrl: String?, runtime: Int?) {
        if (videoId in savingIds.value) return
        savingIds.update { it + videoId }
        viewModelScope.launch {
            runCatching { graph.saveVideo(videoId, title, imageUrl, runtime) }
                .onSuccess { isReplay ->
                    _messages.tryEmit(
                        if (isReplay) "Downloading “$title”. Replays need internet to start playing."
                        else "Saving “$title” to Internal storage/A++",
                    )
                }
                .onFailure {
                    if (it is NeedsFileAccessException) _needsFileAccess.value = true
                    else _messages.tryEmit(it.message ?: "Couldn't save this video.")
                }
            savingIds.update { it - videoId }
        }
    }

    private val _needsFileAccess = MutableStateFlow(false)
    /** Shows a prompt to grant All files access (needed for Internal storage/A++). */
    val needsFileAccess: StateFlow<Boolean> = _needsFileAccess

    fun dismissFileAccess() {
        _needsFileAccess.value = false
    }

    fun removeSaved(videoId: String) = graph.offline.remove(videoId)

    fun removeAllSaved() = graph.offline.removeAll()

    fun setDownloadQuality(quality: DownloadQuality) = graph.setDownloadQuality(quality)

    private val _offline = MutableStateFlow<Load<OfflineReport>?>(null)
    /** Offline-playback check shown in a dialog; null when dismissed. */
    val offline: StateFlow<Load<OfflineReport>?> = _offline

    /** Live channel when [videoId] is null. */
    fun checkOffline(videoId: String?, title: String) {
        _offline.value = Load.Loading
        viewModelScope.launch {
            val result = runCatching { graph.checkOffline(videoId, title) }
            // Ignore the result if the dialog was dismissed meanwhile.
            if (_offline.value == null) return@launch
            _offline.value = result.fold({ Load.Loaded(it) }, { Load.Failed(it.message ?: "Check failed.") })
        }
    }

    fun dismissOffline() {
        _offline.value = null
    }

    fun setEmail(email: String) = _signIn.update { it.copy(email = email, error = null) }

    fun sendCode() {
        val email = _signIn.value.email.trim()
        if (email.isEmpty()) return
        _signIn.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { graph.auth.sendCode(email, profile.value) }
                .onSuccess { key -> _signIn.update { it.copy(busy = false, key = key) } }
                .onFailure { e -> _signIn.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun verify(code: String) {
        val state = _signIn.value
        val key = state.key ?: return
        _signIn.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { graph.auth.verify(code, key, state.email.trim(), profile.value) }
                .onSuccess {
                    _signIn.value = SignInState()
                    _rows.value = emptyMap()
                }
                .onFailure { e -> _signIn.update { it.copy(busy = false, error = e.message) } }
        }
    }

    fun restartSignIn() = _signIn.update { it.copy(key = null, error = null, busy = false) }

    fun signOut() {
        viewModelScope.launch {
            graph.signOut()
            _rows.value = emptyMap()
        }
    }

    fun setProfile(profile: DeviceProfile) {
        graph.setProfile(profile)
        _rows.value = emptyMap()
    }
}
