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
    val detailLoading: Boolean = false,
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HealthRepository(app)

    var state by mutableStateOf(UiState())
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
                state = state.copy(detail = repo.readDetail(summary), detailLoading = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(detailLoading = false, message = "상세 기록 불러오기 실패: ${e.message}")
            }
        }
    }

    fun closeDetail() {
        state = state.copy(detail = null)
    }

    fun onRouteResult(route: ExerciseRoute?) {
        val d = state.detail ?: return
        if (route == null) {
            state = state.copy(message = "GPS 경로를 받지 못했어요 (거부했거나 경로가 없는 기록이에요)")
            return
        }
        state = state.copy(detail = HealthRepository.withRoute(d, route), message = "GPS 포인트 ${route.route.size}개를 불러왔어요")
    }

    fun showMessage(msg: String) {
        state = state.copy(message = msg)
    }

    fun consumeMessage() {
        state = state.copy(message = null)
    }
}
