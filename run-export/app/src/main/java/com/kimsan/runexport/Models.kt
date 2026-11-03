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

/** 1km 구간 기록 */
data class Split(val index: Int, val distanceM: Double, val duration: Duration, val avgHr: Long?) {
    val paceSecPerKm: Double? get() = if (distanceM > 0) duration.seconds / (distanceM / 1000.0) else null
}

/** 워치에서 기록한 랩 (있을 때만) */
data class LapInfo(val index: Int, val start: Instant, val end: Instant, val lengthM: Double?)

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
