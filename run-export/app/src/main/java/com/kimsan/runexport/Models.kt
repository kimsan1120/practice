package com.kimsan.runexport

import androidx.health.connect.client.records.ExerciseSessionRecord
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** GPS 경로를 읽을 수 있는 상태 */
enum class RouteState { AVAILABLE, CONSENT_REQUIRED, NONE }

/** 운동 1회(세션)의 요약 수치 */
data class RunSummary(
    val id: String,
    val exerciseType: Int,
    val title: String?,
    val start: Instant,
    val end: Instant,
    val sourcePackage: String,
    val activeDuration: Duration,
    val distanceMeters: Double?,
    val avgHr: Long?,
    val maxHr: Long?,
    val minHr: Long?,
    val steps: Long?,
    val totalKcal: Double?,
    val activeKcal: Double?,
    val avgSpeedKmh: Double?,
    val maxSpeedKmh: Double?,
    val elevationGainM: Double?,
    val routeState: RouteState,
    /** 운동 시간을 어떤 기준으로 정했는지 */
    val durationBasis: String = "",
) {
    /** 평균 페이스 (초/km) */
    val paceSecPerKm: Double?
        get() = distanceMeters?.takeIf { it > 10 }?.let { activeDuration.seconds / (it / 1000.0) }

    /** 평균 케이던스 (걸음/분) */
    val cadenceSpm: Double?
        get() {
            val minutes = activeDuration.seconds / 60.0
            return steps?.takeIf { minutes > 0 }?.let { it / minutes }
        }

    val typeName: String get() = exerciseTypeName(exerciseType)
}

data class HrPoint(val time: Instant, val bpm: Long)
data class SpeedPoint(val time: Instant, val kmh: Double)
data class RoutePoint(val time: Instant, val lat: Double, val lon: Double, val altitudeM: Double?)

data class CadencePoint(val time: Instant, val spm: Double)
data class StepsInterval(val start: Instant, val end: Instant, val count: Long)
data class Vo2Point(val time: Instant, val value: Double)

/** 1km 구간 기록 */
data class Split(
    val index: Int,
    val distanceM: Double,
    val duration: Duration,
    val avgHr: Long?,
    val start: Instant,
    val end: Instant,
) {
    val paceSecPerKm: Double? get() = if (distanceM > 0) duration.seconds / (distanceM / 1000.0) else null
}

/** 워치에서 기록한 랩 (있을 때만) */
data class LapInfo(val index: Int, val start: Instant, val end: Instant, val lengthM: Double?)

/** Health Connect 에 어떤 데이터가 몇 개 들어왔는지 (데이터 진단) */
data class DiagItem(val label: String, val value: String)

/** 운동 1회의 상세 데이터 */
data class RunDetail(
    val summary: RunSummary,
    val heartRate: List<HrPoint>,
    val speed: List<SpeedPoint>,
    val route: List<RoutePoint>,
    val routeState: RouteState,
    val splits: List<Split>,
    val splitSource: String,
    val laps: List<LapInfo>,
    val cadence: List<CadencePoint> = emptyList(),
    val stepsIntervals: List<StepsInterval> = emptyList(),
    /** 운동 종료 후 3분 동안의 심박 */
    val recoveryHr: List<HrPoint> = emptyList(),
    /** 이 운동에 가장 가까운 VO2max 측정값 */
    val vo2Max: Vo2Point? = null,
    /** 최근 VO2max 추이 */
    val vo2History: List<Vo2Point> = emptyList(),
    /** 그날 안정시 심박 (Health Connect) */
    val restingHr: Long? = null,
    /** 운동 직전 최근 체중 (Health Connect) */
    val weightKg: Double? = null,
    val diagnostics: List<DiagItem> = emptyList(),
    /** 페이스 계산 근거용: Health Connect 운동 시간 집계 */
    val durationAggregated: Duration? = null,
    /** 일시정지/휴식 세그먼트 합계(초) */
    val pauseSeconds: Long = 0,
    /** 모든 앱 기준 거리 집계 */
    val distanceAllOrigins: Double? = null,
    /** 세션과 겹치는 거리 기록의 합 */
    val distanceRecordsSum: Double? = null,
    /** 구간 계산에 쓴 (시각 → 누적 거리) 타임라인 */
    val timeline: List<SplitCalculator.Mark> = emptyList(),
)

/** 베스트 기록 대상 거리 (m) */
val BEST_EFFORT_DISTANCES = listOf(1000, 3000, 5000, 10000, 21097)

fun bestEffortLabel(m: Int): String = when (m) {
    21097 -> "하프"
    else -> "${m / 1000}km"
}

/** 기록별 계산 지표 (통계·베스트 기록용으로 저장) */
data class RunMetrics(
    val id: String,
    val start: Instant,
    val distanceM: Double?,
    /** 대상 거리(m) → 가장 빠른 시간(초) */
    val bestEfforts: Map<Int, Long>,
    /** 효율 지수: 분당 이동 거리(m) ÷ 평균 심박 */
    val ef: Double?,
    /** 유산소 디커플링 %: 전반 대비 후반 효율 저하 */
    val decouplingPct: Double?,
    /** 후반 평균 심박 − 전반 평균 심박 */
    val hrDriftBpm: Double?,
    /** 훈련 부하 (Banister TRIMP) */
    val trimp: Double?,
)

enum class Sex { MALE, FEMALE }

/** 사용자 프로필 (설정 화면) */
data class Profile(
    val birthYear: Int? = null,
    val sex: Sex? = null,
    val maxHr: Int? = null,
    /** 유산소 역치 심박 */
    val aerobicThreshold: Int? = null,
    /** 무산소 역치 심박 */
    val anaerobicThreshold: Int? = null,
    val weightKg: Double? = null,
    /** 월 목표 거리 */
    val monthlyGoalKm: Double? = null,
    /** 새 러닝 알림 */
    val notifyNewRuns: Boolean = false,
) {
    fun ageAt(year: Int): Int? = birthYear?.let { year - it }
}

/** 기록별 직접 입력값 */
data class RunNotes(
    /** 체감 난이도 1~10 */
    val rpe: Int? = null,
    val memo: String = "",
    /** 아침 안정시 심박 직접 입력 */
    val restingHr: Int? = null,
)

/** 운동 시각의 날씨 (Open-Meteo) */
data class WeatherInfo(
    val tempC: Double,
    val apparentC: Double?,
    val humidity: Double?,
    val windKmh: Double?,
    val hourLabel: String,
)

// ---------- 포맷 도우미 ----------

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")
private val dateTimeFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

fun Instant.localTime(): String = timeFmt.format(atZone(ZoneId.systemDefault()))
fun Instant.localDateTime(): String = dateTimeFmt.format(atZone(ZoneId.systemDefault()))

fun formatDuration(d: Duration): String {
    val s = d.seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    else String.format(Locale.US, "%d:%02d", m, sec)
}

/** 초/km → 5'32" 형태 */
fun formatPace(secPerKm: Double?): String {
    if (secPerKm == null || !secPerKm.isFinite() || secPerKm <= 0 || secPerKm > 3600) return "-"
    val t = secPerKm.roundToLong()
    return String.format(Locale.US, "%d'%02d\"", t / 60, t % 60)
}

fun formatKm(meters: Double?): String =
    meters?.let { String.format(Locale.US, "%.2f km", it / 1000.0) } ?: "-"

fun fmt1(v: Double?): String = v?.let { String.format(Locale.US, "%.1f", it) } ?: "-"
fun fmt0(v: Double?): String = v?.let { String.format(Locale.US, "%.0f", it) } ?: "-"

fun exerciseTypeName(type: Int): String = when (type) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "러닝"
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "트레드밀 러닝"
    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "걷기"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "하이킹"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "사이클"
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "실내 사이클"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "수영 (실내)"
    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "수영 (야외)"
    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING -> "근력 운동"
    ExerciseSessionRecord.EXERCISE_TYPE_YOGA -> "요가"
    ExerciseSessionRecord.EXERCISE_TYPE_OTHER_WORKOUT -> "기타 운동"
    else -> "운동 #$type"
}

val RUNNING_TYPES = setOf(
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
)
