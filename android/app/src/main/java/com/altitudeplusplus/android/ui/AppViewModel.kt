package com.altitudeplusplus.android.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.altitudeplusplus.android.AltitudeApp
import com.altitudeplusplus.android.DeviceProfile
import com.altitudeplusplus.android.guide.Program
import com.altitudeplusplus.android.ondemand.ContentRow
import com.altitudeplusplus.android.ondemand.Team
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
            graph.auth.signOut(profile.value)
            _rows.value = emptyMap()
        }
    }

    fun setProfile(profile: DeviceProfile) {
        graph.setProfile(profile)
        _rows.value = emptyMap()
    }
}
