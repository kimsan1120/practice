package com.kimsan.runexport

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** 시각 + 값 (차트용 공통 형식) */
data class TimeValue(val time: Instant, val value: Double)

data class ElevationStats(
    val minM: Double,
    val maxM: Double,
    val gainM: Double,
    val lossM: Double,
    val uphillM: Double,
    val downhillM: Double,
    val flatM: Double,
)

/** 심박 구간 하나 */
data class Zone(val name: String, val fromBpm: Int?, val toBpm: Int?, val seconds: Long, val fraction: Double)

data class Recovery(
    val atEnd: Long?,
    val after1Min: Long?,
    val after2Min: Long?,
) {
    val drop1: Long? get() = if (atEnd != null && after1Min != null) atEnd - after1Min else null
    val drop2: Long? get() = if (atEnd != null && after2Min != null) atEnd - after2Min else null
}

/** 평균 페이스 계산 근거: (이름, 값) */
data class PaceBasis(val label: String, val duration: Duration?, val distanceM: Double?)

/** 화면/내보내기에 쓰는 계산 결과 묶음 */
data class RunAnalysis(
    val totalTime: Duration,
    val maxPaceSecPerKm: Double?,
    val elevation: ElevationStats?,
    val avgCadence: Double?,
    val maxCadence: Double?,
    val cadenceSource: String,
    val paceSeries: List<TimeValue>,
    val hrSeries: List<TimeValue>,
    val cadenceSeries: List<TimeValue>,
    val altitudeSeries: List<TimeValue>,
    val fastestSplit: Split?,
    val maxHrUsed: Int?,
    val maxHrSource: String,
    val zones5: List<Zone>,
    val zones3: List<Zone>,
    val recovery: Recovery?,
    val recoverySeries: List<TimeValue>,
    val vo2Grade: String?,
    val age: Int?,
)

object Analysis {

    fun analyze(d: RunDetail, profile: Profile): RunAnalysis {
        val s = d.summary
        val zone = ZoneId.systemDefault()
        val age = profile.ageAt(s.start.atZone(zone).year)

        val speed = speedSeries(d)
        val (maxHr, maxHrSource) = when {
            profile.maxHr != null -> profile.maxHr to "설정값"
            age != null -> (220 - age) to "220 − 나이"
            s.maxHr != null -> s.maxHr.toInt() to "이번 기록 최대"
            else -> null to ""
        }
        val cadence = cadenceSeries(d)
        val (avgCad, maxCad) = cadenceStats(cadence)
        val hrSamples = d.heartRate
        val fullSplits = d.splits.filter { it.distanceM >= 999 }

        return RunAnalysis(
            totalTime = Duration.between(s.start, s.end),
            // 최고 페이스 = 최고 속도 (삼성 헬스와 같은 방식), 없으면 10초 평균 속도 최댓값
            maxPaceSecPerKm = s.maxSpeedKmh?.takeIf { it in 3.0..30.0 }?.let { 3600.0 / it } ?: maxPace(speed),
            elevation = elevation(d.route),
            avgCadence = avgCad ?: s.cadenceSpm,
            maxCadence = maxCad,
            cadenceSource = when {
                d.cadence.size >= 3 -> "케이던스 기록"
                cadence.isNotEmpty() -> "걸음 기록으로 계산"
                s.cadenceSpm != null -> "총 걸음 ÷ 운동 시간"
                else -> ""
            },
            paceSeries = paceSeries(speed),
            hrSeries = rolling(hrSamples.map { TimeValue(it.time, it.bpm.toDouble()) }, 5),
            cadenceSeries = rolling(cadence, 20),
            altitudeSeries = altitudeSeries(d.route),
            fastestSplit = fullSplits.minByOrNull { it.duration },
            maxHrUsed = maxHr,
            maxHrSource = maxHrSource,
            zones5 = maxHr?.let { zones5(hrSamples, s.end, it) }.orEmpty(),
            zones3 = if (profile.aerobicThreshold != null && profile.anaerobicThreshold != null)
                zones3(hrSamples, s.end, profile.aerobicThreshold, profile.anaerobicThreshold) else emptyList(),
            recovery = recovery(hrSamples, d.recoveryHr, s.end),
            recoverySeries = recoverySeries(hrSamples, d.recoveryHr, s.end),
            vo2Grade = d.vo2Max?.let { v -> if (age != null && profile.sex != null) vo2Grade(v.value, age, profile.sex) else null },
            age = age,
        )
    }

    // ---------- 평균 페이스 계산 근거 ----------

    /**
     * 실제로 움직인 시간: 속도 2km/h 이상인 구간의 합.
     * 샘플 사이가 10초 넘게 비면 일시정지로 보고 세지 않는다.
     */
    fun movingTime(speedKmh: List<TimeValue>): Duration? {
        if (speedKmh.size < 3) return null
        var ms = 0L
        for (i in 1 until speedKmh.size) {
            if (speedKmh[i].value < 2.0) continue
            val dt = Duration.between(speedKmh[i - 1].time, speedKmh[i].time).toMillis()
            if (dt in 1..10_000) ms += dt
        }
        return Duration.ofMillis(ms)
    }

    /** 시간 후보와 거리 후보 (삼성 헬스 값과 비교용) */
    fun paceBases(d: RunDetail): Pair<List<PaceBasis>, List<PaceBasis>> {
        val s = d.summary
        val span = Duration.between(s.start, s.end)
        val times = listOf(
            PaceBasis("시작~종료 (총 시간)", span, null),
            PaceBasis("Health Connect 운동 시간 집계", d.durationAggregated, null),
            PaceBasis("일시정지 구간 제외", if (d.pauseSeconds > 0) span.minusSeconds(d.pauseSeconds) else null, null),
            PaceBasis("움직인 시간 (2km/h 이상)", movingTime(speedSeries(d)), null),
        )
        val routeM = if (d.route.size >= 2) SplitCalculator.fromRoute(d.route).last().meters else null
        val dists = listOf(
            PaceBasis("거리 집계 (세션 앱)", null, s.distanceMeters),
            PaceBasis("거리 집계 (모든 앱)", null, d.distanceAllOrigins),
            PaceBasis("거리 기록 합계", null, d.distanceRecordsSum),
            PaceBasis("GPS 경로 거리", null, routeM),
        )
        return times to dists
    }

    // ---------- 속도 / 페이스 ----------

    /** 속도(km/h) 시계열: 속도 기록이 있으면 그것, 없으면 GPS 경로로 계산 */
    fun speedSeries(d: RunDetail): List<TimeValue> =
        if (d.speed.size >= 3) d.speed.map { TimeValue(it.time, it.kmh) } else speedFromRoute(d.route)

    fun speedFromRoute(route: List<RoutePoint>): List<TimeValue> {
        val out = mutableListOf<TimeValue>()
        for (i in 1 until route.size) {
            val a = route[i - 1]
            val b = route[i]
            val dt = Duration.between(a.time, b.time).toMillis() / 1000.0
            if (dt <= 0 || dt > 30) continue
            val m = SplitCalculator.haversine(a.lat, a.lon, b.lat, b.lon)
            out += TimeValue(b.time, m / dt * 3.6)
        }
        return out
    }

    /** 최고 페이스: 10초 평균 속도의 최댓값 (GPS 튐 방지, 30km/h 이상은 오류로 제외) */
    fun maxPace(speedKmh: List<TimeValue>): Double? {
        val best = rolling(speedKmh.filter { it.value in 0.0..30.0 }, 10).maxOfOrNull { it.value } ?: return null
        return if (best >= 3) 3600.0 / best else null
    }

    /** 페이스(초/km) 시계열: 30초 평균, 20분/km 보다 느린 구간(걷기·정지)은 제외 */
    fun paceSeries(speedKmh: List<TimeValue>): List<TimeValue> =
        rolling(speedKmh.filter { it.value in 0.0..30.0 }, 30)
            .filter { it.value >= 3 }
            .map { TimeValue(it.time, 3600.0 / it.value) }

    // ---------- 고도 / 경사 ----------

    fun altitudeSeries(route: List<RoutePoint>): List<TimeValue> {
        val pts = route.filter { it.altitudeM != null }
        val smooth = movingAvg(pts.map { it.altitudeM!! }, 5)
        return pts.mapIndexed { i, p -> TimeValue(p.time, smooth[i]) }
    }

    /**
     * 고도 통계. 고도는 5포인트 이동평균으로 다듬고,
     * 경사 +1% 초과는 오르막, −1% 미만은 내리막, 그 사이는 평지로 거리를 나눈다.
     */
    fun elevation(route: List<RoutePoint>): ElevationStats? {
        val pts = route.filter { it.altitudeM != null }
        if (pts.size < 2) return null
        val alt = movingAvg(pts.map { it.altitudeM!! }, 5)
        var gain = 0.0; var loss = 0.0; var up = 0.0; var down = 0.0; var flat = 0.0
        for (i in 1 until pts.size) {
            val horiz = SplitCalculator.haversine(pts[i - 1].lat, pts[i - 1].lon, pts[i].lat, pts[i].lon)
            val dAlt = alt[i] - alt[i - 1]
            if (dAlt > 0) gain += dAlt else loss -= dAlt
            if (horiz <= 0) continue
            val grade = dAlt / horiz
            when {
                grade > 0.01 -> up += horiz
                grade < -0.01 -> down += horiz
                else -> flat += horiz
            }
        }
        return ElevationStats(alt.min(), alt.max(), gain, loss, up, down, flat)
    }

    // ---------- 케이던스 ----------

    /** 케이던스(spm) 시계열: 케이던스 기록 > 짧은 걸음 기록으로 계산 */
    fun cadenceSeries(d: RunDetail): List<TimeValue> {
        if (d.cadence.size >= 3) return d.cadence.map { TimeValue(it.time, it.spm) }
        val fromSteps = d.stepsIntervals.mapNotNull { s ->
            val sec = Duration.between(s.start, s.end).toMillis() / 1000.0
            if (sec < 5 || sec > 180) null
            else TimeValue(s.start.plusMillis((sec * 500).toLong()), s.count / (sec / 60.0))
        }.sortedBy { it.time }
        return if (fromSteps.size >= 3) fromSteps else emptyList()
    }

    /** (평균, 최대) — 달리기 범위(60~260spm)만, 최대는 20초 평균 기준 */
    fun cadenceStats(series: List<TimeValue>): Pair<Double?, Double?> {
        val running = series.filter { it.value in 60.0..260.0 }
        if (running.isEmpty()) return null to null
        return running.map { it.value }.average() to rolling(running, 20).maxOf { it.value }
    }

    // ---------- 심박 구간 ----------

    /** 최대 심박 기준 5구간 (Z1 <60%, Z2 60~70, Z3 70~80, Z4 80~90, Z5 90%+) */
    fun zones5(hr: List<HrPoint>, end: Instant, maxHr: Int): List<Zone> {
        val b = listOf(0.6, 0.7, 0.8, 0.9).map { (maxHr * it).roundToInt() }
        return zones(hr, end, b, listOf("Z1 회복", "Z2 지방 연소", "Z3 유산소", "Z4 무산소", "Z5 최대"))
    }

    /** 역치 기준 3구간 (유산소 역치 미만 / 사이 / 무산소 역치 이상) */
    fun zones3(hr: List<HrPoint>, end: Instant, aet: Int, ant: Int): List<Zone> =
        zones(hr, end, listOf(aet, ant), listOf("유산소 역치 미만", "역치 사이", "무산소 역치 이상"))

    /** bounds 로 나눈 구간별 시간. 각 샘플은 다음 샘플까지(최대 10초) 유지된 것으로 계산 */
    fun zones(hr: List<HrPoint>, end: Instant, bounds: List<Int>, names: List<String>): List<Zone> {
        val secs = LongArray(bounds.size + 1)
        for (i in hr.indices) {
            val next = hr.getOrNull(i + 1)?.time ?: end
            val dt = min(Duration.between(hr[i].time, next).seconds, 10).coerceAtLeast(0)
            val z = bounds.count { hr[i].bpm >= it }
            secs[z] += dt
        }
        val total = secs.sum().coerceAtLeast(1)
        return names.mapIndexed { i, name ->
            Zone(
                name = name,
                fromBpm = if (i == 0) null else bounds[i - 1],
                toBpm = if (i == bounds.size) null else bounds[i] - 1,
                seconds = secs[i],
                fraction = secs[i].toDouble() / total,
            )
        }
    }

    // ---------- 회복 심박 ----------

    fun recovery(session: List<HrPoint>, after: List<HrPoint>, end: Instant): Recovery? {
        if (after.isEmpty()) return null
        val lastInSession = session.filter { Duration.between(it.time, end).seconds in 0..10 }
        val atEnd = if (lastInSession.isNotEmpty()) lastInSession.map { it.bpm }.average().toLong()
        else nearest(after, end, 10)
        return Recovery(atEnd, nearest(after, end.plusSeconds(60), 10), nearest(after, end.plusSeconds(120), 10))
    }

    /** 종료 30초 전 ~ 종료 후 3분 심박 곡선 */
    fun recoverySeries(session: List<HrPoint>, after: List<HrPoint>, end: Instant): List<TimeValue> {
        if (after.isEmpty()) return emptyList()
        val before = session.filter { !it.time.isBefore(end.minusSeconds(30)) }
        return (before + after).distinctBy { it.time }.sortedBy { it.time }.map { TimeValue(it.time, it.bpm.toDouble()) }
    }

    private fun nearest(hr: List<HrPoint>, t: Instant, maxGapSec: Long): Long? =
        hr.minByOrNull { abs(Duration.between(it.time, t).seconds) }
            ?.takeIf { abs(Duration.between(it.time, t).seconds) <= maxGapSec }?.bpm

    // ---------- VO2max 등급 ----------

    /**
     * VO2max 등급 (Heyward, Advanced Fitness Assessment 기준표 — 참고용).
     * 각 행: 매우 낮음 | 낮음 | 보통 | 좋음 | 매우 좋음 의 상한값. 그 이상은 최상.
     */
    fun vo2Grade(value: Double, age: Int, sex: Sex): String {
        val male = listOf(
            29 to doubleArrayOf(33.0, 36.5, 42.5, 46.5, 52.5),
            39 to doubleArrayOf(31.5, 35.5, 41.0, 45.0, 49.5),
            49 to doubleArrayOf(30.2, 33.6, 39.0, 43.8, 48.1),
            59 to doubleArrayOf(26.1, 31.0, 35.8, 41.0, 45.4),
            200 to doubleArrayOf(20.5, 26.1, 32.3, 36.5, 44.3),
        )
        val female = listOf(
            29 to doubleArrayOf(23.6, 29.0, 33.0, 37.0, 41.1),
            39 to doubleArrayOf(22.8, 27.0, 31.5, 35.7, 40.1),
            49 to doubleArrayOf(21.0, 24.5, 29.0, 32.9, 37.0),
            59 to doubleArrayOf(20.2, 22.8, 27.0, 31.5, 35.8),
            200 to doubleArrayOf(17.5, 20.2, 24.5, 30.3, 31.5),
        )
        val row = (if (sex == Sex.MALE) male else female).first { age <= it.first }.second
        val labels = listOf("매우 낮음", "낮음", "보통", "좋음", "매우 좋음")
        val idx = row.indexOfFirst { value < it }
        return if (idx == -1) "최상" else labels[idx]
    }

    // ---------- 도우미 ----------

    /** windowSec 초 이동평균 (투 포인터) */
    fun rolling(series: List<TimeValue>, windowSec: Long): List<TimeValue> {
        if (series.isEmpty()) return series
        val sorted = series.sortedBy { it.time }
        val out = ArrayList<TimeValue>(sorted.size)
        var lo = 0
        var sum = 0.0
        for (hi in sorted.indices) {
            sum += sorted[hi].value
            while (Duration.between(sorted[lo].time, sorted[hi].time).seconds >= windowSec) {
                sum -= sorted[lo].value
                lo++
            }
            out += TimeValue(sorted[hi].time, sum / (hi - lo + 1))
        }
        return out
    }

    fun movingAvg(values: List<Double>, window: Int): List<Double> {
        val half = window / 2
        return values.indices.map { i ->
            val from = (i - half).coerceAtLeast(0)
            val to = (i + half).coerceAtMost(values.size - 1)
            values.subList(from, to + 1).average()
        }
    }

    /** 차트용으로 점 수 줄이기 */
    fun downsample(series: List<TimeValue>, maxPoints: Int = 400): List<TimeValue> {
        if (series.size <= maxPoints) return series
        val step = series.size.toDouble() / maxPoints
        return (0 until maxPoints).map { series[(it * step).toInt()] } + series.last()
    }
}
