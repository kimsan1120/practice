package com.kimsan.runexport

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseRoute
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate

data class UiState(
    val sdkStatus: Int? = null,
    val hasPermission: Boolean = false,
    val missingPermissions: Set<String> = emptySet(),
    val date: LocalDate = LocalDate.now(),
    val runningOnly: Boolean = true,
    val loading: Boolean = false,
    val sessions: List<RunSummary> = emptyList(),
    val detail: RunDetail? = null,
    val analysis: RunAnalysis? = null,
    val notes: RunNotes = RunNotes(),
    val weather: WeatherInfo? = null,
    val weatherLoading: Boolean = false,
    val detailLoading: Boolean = false,
    val showSettings: Boolean = false,
    val profile: Profile = Profile(),
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HealthRepository(app)
    private val prefs = Prefs(app)

    var state by mutableStateOf(UiState(profile = prefs.profile()))
        private set

    private var loadJob: Job? = null

    fun permissionsToRequest(): Set<String> = repo.permissionsToRequest()

    /** 앱이 화면에 다시 나타날 때마다 호출: 상태/권한 확인 후 목록 새로고침 */
    fun refreshAll() {
        val status = repo.sdkStatus()
        state = state.copy(sdkStatus = status)
        if (status != HealthConnectClient.SDK_AVAILABLE) return
        viewModelScope.launch {
            val granted = runCatching { repo.grantedPermissions() }.getOrDefault(emptySet())
            val has = HealthRepository.EXERCISE_PERMISSION in granted
            state = state.copy(
                hasPermission = has,
                missingPermissions = HealthRepository.DATA_PERMISSIONS - granted,
            )
            if (has) loadSessions()
        }
    }

    fun shiftDate(days: Long) {
        state = state.copy(date = state.date.plusDays(days))
        loadSessions()
    }

    fun goToday() {
        state = state.copy(date = LocalDate.now())
        loadSessions()
    }

    fun setRunningOnly(value: Boolean) {
        state = state.copy(runningOnly = value)
        loadSessions()
    }

    private fun loadSessions() {
        loadJob?.cancel()
        val date = state.date
        val runningOnly = state.runningOnly
        loadJob = viewModelScope.launch {
            state = state.copy(loading = true)
            try {
                val list = repo.readSessions(date, runningOnly)
                state = state.copy(sessions = list, loading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(sessions = emptyList(), loading = false, message = "불러오기 실패: ${e.message}")
            }
        }
    }

    fun open(summary: RunSummary) {
        viewModelScope.launch {
            state = state.copy(detailLoading = true)
            try {
                val d = repo.readDetail(summary)
                setDetail(d)
                state = state.copy(
                    detailLoading = false,
                    notes = prefs.notes(summary.id),
                    weather = prefs.weather(summary.id),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(detailLoading = false, message = "상세 기록 불러오기 실패: ${e.message}")
            }
        }
    }

    private fun setDetail(d: RunDetail) {
        state = state.copy(detail = d, analysis = Analysis.analyze(d, state.profile))
    }

    fun closeDetail() {
        state = state.copy(detail = null, analysis = null)
    }

    fun onRouteResult(route: ExerciseRoute?) {
        val d = state.detail ?: return
        if (route == null) {
            state = state.copy(message = "GPS 경로를 받지 못했어요 (거부했거나 경로가 없는 기록이에요)")
            return
        }
        setDetail(HealthRepository.withRoute(d, route))
        state = state.copy(message = "GPS 포인트 ${route.route.size}개를 불러왔어요")
    }

    fun updateNotes(n: RunNotes) {
        val id = state.detail?.summary?.id ?: return
        prefs.saveNotes(id, n)
        state = state.copy(notes = n)
    }

    fun fetchWeather() {
        val d = state.detail ?: return
        val p = d.route.firstOrNull() ?: run {
            state = state.copy(message = "기온을 가져오려면 먼저 GPS 경로를 불러와야 해요")
            return
        }
        viewModelScope.launch {
            state = state.copy(weatherLoading = true)
            try {
                val w = Weather.fetch(p.lat, p.lon, d.summary.start, d.summary.end)
                prefs.saveWeather(d.summary.id, w)
                state = state.copy(weather = w, weatherLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(weatherLoading = false, message = "기온 가져오기 실패: ${e.message}")
            }
        }
    }

    fun openSettings(show: Boolean) {
        state = state.copy(showSettings = show)
    }

    fun saveProfile(p: Profile) {
        prefs.saveProfile(p)
        state = state.copy(profile = p, showSettings = false, message = "설정을 저장했어요")
        state.detail?.let { setDetail(it) }
    }

    fun showMessage(msg: String) {
        state = state.copy(message = msg)
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }
}
