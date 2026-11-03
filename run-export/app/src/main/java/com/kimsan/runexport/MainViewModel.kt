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
    /** 지난 비슷한 거리 기록 (비교용) */
    val compare: RunSummary? = null,
    val compareMetrics: RunMetrics? = null,
    /** 거리별 개인 최고 기록 */
    val pbs: Map<Int, RunMetrics> = emptyMap(),
    val showStats: Boolean = false,
    val stats: StatsUi = StatsUi(),
    /** Health Connect 백그라운드 읽기 권한 (새 러닝 알림용) */
    val backgroundGranted: Boolean = false,
)

data class StatsUi(
    val mode: StatsMode = StatsMode.WEEK,
    val loading: Boolean = false,
    val progress: String? = null,
    val runs: List<RunSummary> = emptyList(),
    val metrics: Map<String, RunMetrics> = emptyMap(),
    /** 선택한 기간 (0 = 가장 오래된, 11 = 이번 주/달) */
    val selected: Int = 11,
    val analyzing: Boolean = false,
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
                backgroundGranted = androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND in granted,
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
                val notes = prefs.notes(summary.id)
                state = state.copy(notes = notes, weather = prefs.weather(summary.id), compare = null, compareMetrics = null)
                setDetail(d)
                state = state.copy(detailLoading = false)
                loadCompare(summary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(detailLoading = false, message = "상세 기록 불러오기 실패: ${e.message}")
            }
        }
    }

    /** 알림에서 열기 */
    fun openById(id: String) {
        viewModelScope.launch {
            val s = repo.summaryById(id)
            if (s == null) state = state.copy(message = "기록을 찾을 수 없어요") else open(s)
        }
    }

    /** 비교 대상 찾기: 저장된 요약이 적으면 최근 120일을 읽어 채운다 */
    private suspend fun loadCompare(current: RunSummary) {
        var candidates = prefs.summaries().values.map { it.first }
        if (candidates.count { it.start.isBefore(current.start) } < 3) {
            candidates = runCatching {
                repo.readRunsRange(current.start.minus(java.time.Duration.ofDays(120)), current.start)
            }.getOrDefault(candidates)
        }
        val prev = Stats.previousSimilar(current, candidates)
        val metrics = prefs.metrics()
        state = state.copy(compare = prev, compareMetrics = prev?.let { metrics[it.id] }, pbs = Stats.personalBests(metrics.values))
    }

    private fun setDetail(d: RunDetail) {
        val rest = state.notes.restingHr?.toLong() ?: d.restingHr
        val a = Analysis.analyze(d, state.profile, rest)
        // 베스트 기록 / 효율 / 부하 저장 (통계·PB 용)
        prefs.saveMetrics(a.metrics)
        val all = prefs.metrics()
        state = state.copy(detail = d, analysis = a, pbs = Stats.personalBests(all.values))
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
        val d = state.detail ?: return
        prefs.saveNotes(d.summary.id, n)
        val restChanged = n.restingHr != state.notes.restingHr
        state = state.copy(notes = n)
        if (restChanged) setDetail(d) // 안정시 심박이 바뀌면 훈련 부하 다시 계산
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

    // ---------- 통계 ----------

    fun openStats(show: Boolean) {
        state = state.copy(showStats = show)
        if (show) loadStats()
    }

    fun setStatsMode(mode: StatsMode) {
        state = state.copy(stats = state.stats.copy(mode = mode, selected = 11))
    }

    fun selectPeriod(i: Int) {
        state = state.copy(stats = state.stats.copy(selected = i))
    }

    /** 최근 12개월 러닝 요약 불러오기 (저장된 요약은 다시 계산하지 않음) */
    private fun loadStats() {
        viewModelScope.launch {
            val today = LocalDate.now()
            val zone = java.time.ZoneId.systemDefault()
            val from = today.withDayOfMonth(1).minusMonths(11).atStartOfDay(zone).toInstant()
            val to = today.plusDays(1).atStartOfDay(zone).toInstant()
            state = state.copy(stats = state.stats.copy(loading = true, progress = "기록 불러오는 중…", metrics = prefs.metrics()))
            try {
                val runs = repo.readRunsRange(from, to) { i, n ->
                    state = state.copy(stats = state.stats.copy(progress = "기록 불러오는 중… $i / $n"))
                }
                state = state.copy(stats = state.stats.copy(loading = false, progress = null, runs = runs, metrics = prefs.metrics()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state = state.copy(stats = state.stats.copy(loading = false, progress = null), message = "통계 불러오기 실패: ${e.message}")
            }
        }
    }

    /** 아직 분석 안 한 기록들의 베스트 기록 / 효율 / 부하 계산 */
    fun analyzeAll() {
        val runs = state.stats.runs
        viewModelScope.launch {
            state = state.copy(stats = state.stats.copy(analyzing = true))
            val existing = prefs.metrics()
            val todo = runs.filter { it.id !in existing }
            val profile = state.profile
            todo.forEachIndexed { i, r ->
                state = state.copy(stats = state.stats.copy(progress = "기록 분석 중… ${i + 1} / ${todo.size}"))
                try {
                    val d = repo.readDetail(r, full = false)
                    val age = profile.ageAt(r.start.atZone(java.time.ZoneId.systemDefault()).year)
                    val maxHr = profile.maxHr ?: age?.let { 220 - it } ?: r.maxHr?.toInt()
                    val rest = prefs.notes(r.id).restingHr ?: Analysis.DEFAULT_REST_HR
                    prefs.saveMetrics(Analysis.metrics(d, maxHr, rest, profile.sex))
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // 한 기록이 실패해도 나머지는 계속
                }
            }
            val all = prefs.metrics()
            state = state.copy(
                stats = state.stats.copy(analyzing = false, progress = null, metrics = all),
                pbs = Stats.personalBests(all.values),
                message = if (todo.isEmpty()) "이미 모두 분석했어요" else "${todo.size}개 기록을 분석했어요",
            )
        }
    }

    fun openSettings(show: Boolean) {
        state = state.copy(showSettings = show)
    }

    fun backgroundSupported(): Boolean = repo.backgroundReadSupported()

    fun backgroundPermissions(): Set<String> =
        if (repo.backgroundReadSupported())
            setOf(androidx.health.connect.client.permission.HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND)
        else emptySet()

    fun saveProfile(p: Profile) {
        val turnedOn = p.notifyNewRuns && !state.profile.notifyNewRuns
        prefs.saveProfile(p)
        NewRunWorker.schedule(getApplication(), p.notifyNewRuns)
        if (turnedOn) {
            // 지금 시점부터의 변경만 알리도록 기준점 저장
            viewModelScope.launch {
                runCatching { prefs.changesToken = repo.newRunsSince(null).second }
            }
        }
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
