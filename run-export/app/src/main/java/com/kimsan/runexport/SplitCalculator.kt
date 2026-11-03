package com.kimsan.runexport

import java.time.Duration
import java.time.Instant
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 1km 구간(스플릿) 계산.
 *
 * Health Connect 에는 구간 기록이 따로 없어서, (시각 → 누적 거리) 타임라인을 만든 뒤
 * 1000m 지점을 지나는 시각을 보간해서 구간 시간을 구한다.
 * 타임라인은 GPS 경로 > 거리 기록 > 속도 기록 순으로 사용 가능한 것을 쓰고,
 * 마지막 누적 거리를 세션 총 거리에 맞춰 보정한다.
 */
object SplitCalculator {

    data class Mark(val time: Instant, val meters: Double)

    fun fromRoute(route: List<RoutePoint>): List<Mark> {
        if (route.size < 2) return emptyList()
        val out = ArrayList<Mark>(route.size)
        var cum = 0.0
        var prev: RoutePoint? = null
        for (p in route.sortedBy { it.time }) {
            prev?.let { cum += haversine(it.lat, it.lon, p.lat, p.lon) }
            out += Mark(p.time, cum)
            prev = p
        }
        return out
    }

    /** 거리 기록 (구간 끝 시각, 구간 거리) 목록 */
    fun fromDistanceRecords(start: Instant, records: List<Pair<Instant, Double>>): List<Mark> {
        if (records.size < 3) return emptyList() // 기록이 1~2개뿐이면 구간을 나눌 수 없음
        val out = ArrayList<Mark>(records.size + 1)
        out += Mark(start, 0.0)
        var cum = 0.0
        for ((end, m) in records.sortedBy { it.first }) {
            cum += m
            out += Mark(end, cum)
        }
        return out
    }

    fun fromSpeed(start: Instant, samples: List<SpeedPoint>): List<Mark> {
        if (samples.size < 3) return emptyList()
        val out = ArrayList<Mark>(samples.size + 1)
        var cum = 0.0
        var prevTime = start
        out += Mark(start, 0.0)
        for (s in samples.sortedBy { it.time }) {
            // 샘플 간격이 길면 일시정지로 보고 최대 10초만 적분
            val dt = min(Duration.between(prevTime, s.time).toMillis() / 1000.0, 10.0).coerceAtLeast(0.0)
            cum += s.kmh / 3.6 * dt
            out += Mark(s.time, cum)
            prevTime = s.time
        }
        return out
    }

    fun compute(
        timeline: List<Mark>,
        totalMeters: Double?,
        hr: List<HrPoint>,
        splitMeters: Double = 1000.0,
    ): List<Split> {
        if (timeline.size < 2) return emptyList()
        val rawTotal = timeline.last().meters
        if (rawTotal <= 0) return emptyList()
        val scale = if (totalMeters != null && totalMeters > 0) totalMeters / rawTotal else 1.0
        val tl = timeline.map { it.copy(meters = it.meters * scale) }

        val splits = mutableListOf<Split>()
        var target = splitMeters
        var boundaryTime = tl.first().time
        for (i in 1 until tl.size) {
            val a = tl[i - 1]
            val b = tl[i]
            while (b.meters >= target && b.meters > a.meters) {
                val frac = (target - a.meters) / (b.meters - a.meters)
                val spanMs = Duration.between(a.time, b.time).toMillis()
                val t = a.time.plusMillis((spanMs * frac).toLong())
                splits += Split(splits.size + 1, splitMeters, Duration.between(boundaryTime, t), avgHr(hr, boundaryTime, t))
                boundaryTime = t
                target += splitMeters
            }
        }
        val rest = tl.last().meters - (target - splitMeters)
        if (rest >= 50) {
            val end = tl.last().time
            splits += Split(splits.size + 1, rest, Duration.between(boundaryTime, end), avgHr(hr, boundaryTime, end))
        }
        return splits
    }

    private fun avgHr(hr: List<HrPoint>, from: Instant, to: Instant): Long? {
        val inRange = hr.filter { !it.time.isBefore(from) && it.time.isBefore(to) }
        return if (inRange.isEmpty()) null else inRange.map { it.bpm }.average().toLong()
    }

    /** 두 좌표 사이 거리 (m) */
    fun haversine(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
