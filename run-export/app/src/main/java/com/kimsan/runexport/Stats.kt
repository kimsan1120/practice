package com.kimsan.runexport

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

enum class StatsMode { WEEK, MONTH }

/** 주 / 월 한 칸 */
data class Period(
    val label: String,
    val start: LocalDate,
    /** 다음 기간 시작일 (미포함) */
    val end: LocalDate,
    val runs: List<RunSummary>,
) {
    val distanceKm: Double get() = runs.sumOf { it.distanceMeters ?: 0.0 } / 1000
    val time: Duration get() = runs.fold(Duration.ZERO) { a, r -> a.plus(r.activeDuration) }
    val paceSecPerKm: Double? get() = if (distanceKm > 0.01) time.seconds / distanceKm else null

    /** 운동 시간으로 가중 평균한 심박 */
    val avgHr: Double?
        get() {
            val w = runs.filter { it.avgHr != null }
            val total = w.sumOf { it.activeDuration.seconds }
            return if (total > 0) w.sumOf { it.avgHr!! * it.activeDuration.seconds }.toDouble() / total else null
        }

    fun trimp(metrics: Map<String, RunMetrics>): Double? =
        runs.mapNotNull { metrics[it.id]?.trimp }.takeIf { it.isNotEmpty() }?.sum()
}

object Stats {

    fun localDate(r: RunSummary): LocalDate = r.start.atZone(ZoneId.systemDefault()).toLocalDate()

    /** 최근 count 개 기간 (오래된 것부터). 주는 월요일 시작 */
    fun periods(runs: List<RunSummary>, mode: StatsMode, today: LocalDate, count: Int = 12): List<Period> {
        val firstStart = when (mode) {
            StatsMode.WEEK -> today.with(DayOfWeek.MONDAY).minusWeeks((count - 1).toLong())
            StatsMode.MONTH -> today.withDayOfMonth(1).minusMonths((count - 1).toLong())
        }
        return (0 until count).map { i ->
            val start = if (mode == StatsMode.WEEK) firstStart.plusWeeks(i.toLong()) else firstStart.plusMonths(i.toLong())
            val end = if (mode == StatsMode.WEEK) start.plusWeeks(1) else start.plusMonths(1)
            val label = if (mode == StatsMode.WEEK) "${start.monthValue}/${start.dayOfMonth}" else "${start.monthValue}월"
            Period(label, start, end, runs.filter { val d = localDate(it); !d.isBefore(start) && d.isBefore(end) })
        }
    }

    /** 거리별 개인 최고 기록: 거리(m) → 그 기록의 지표 */
    fun personalBests(metrics: Collection<RunMetrics>): Map<Int, RunMetrics> =
        BEST_EFFORT_DISTANCES.mapNotNull { d ->
            metrics.filter { it.bestEfforts[d] != null }.minByOrNull { it.bestEfforts[d]!! }?.let { d to it }
        }.toMap()

    /** 비교 대상: 이 기록 이전의 비슷한 거리(±15%) 러닝 중 가장 최근 */
    fun previousSimilar(current: RunSummary, candidates: Collection<RunSummary>): RunSummary? {
        val dist = current.distanceMeters ?: return null
        return candidates
            .filter { it.id != current.id && it.start.isBefore(current.start) }
            .filter { it.distanceMeters != null && kotlin.math.abs(it.distanceMeters - dist) <= dist * 0.15 }
            .maxByOrNull { it.start }
    }
}
