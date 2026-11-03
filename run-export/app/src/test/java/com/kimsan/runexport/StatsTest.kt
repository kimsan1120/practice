package com.kimsan.runexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

class StatsTest {

    private val zone = ZoneId.systemDefault()

    private fun run(id: String, date: LocalDate, km: Double, minutes: Long, hr: Long? = 150) = RunSummary(
        id = id, exerciseType = 56, title = null,
        start = date.atTime(6, 0).atZone(zone).toInstant(),
        end = date.atTime(6, 0).plusMinutes(minutes).atZone(zone).toInstant(),
        sourcePackage = "t", activeDuration = Duration.ofMinutes(minutes), distanceMeters = km * 1000,
        avgHr = hr, maxHr = null, minHr = null, steps = null, totalKcal = null, activeKcal = null,
        avgSpeedKmh = null, maxSpeedKmh = null, elevationGainM = null, routeState = RouteState.NONE,
    )

    @Test
    fun `주간 기간은 월요일 시작이고 기간별로 기록이 나뉜다`() {
        val today = LocalDate.of(2026, 11, 4) // 수요일
        val runs = listOf(
            run("a", LocalDate.of(2026, 11, 2), 3.0, 18), // 이번 주 월
            run("b", LocalDate.of(2026, 11, 4), 5.0, 30), // 이번 주 수
            run("c", LocalDate.of(2026, 11, 1), 10.0, 60), // 지난 주 일
        )
        val p = Stats.periods(runs, StatsMode.WEEK, today)
        assertEquals(12, p.size)
        assertEquals(LocalDate.of(2026, 11, 2), p.last().start)
        assertEquals(listOf("a", "b"), p.last().runs.map { it.id })
        assertEquals(8.0, p.last().distanceKm, 1e-9)
        assertEquals(360.0, p.last().paceSecPerKm!!, 1e-9) // 48분 / 8km
        assertEquals(listOf("c"), p[10].runs.map { it.id })
    }

    @Test
    fun `월간 기간과 시간 가중 평균 심박`() {
        val today = LocalDate.of(2026, 11, 4)
        val runs = listOf(run("a", LocalDate.of(2026, 11, 1), 3.0, 10, 140), run("b", LocalDate.of(2026, 11, 3), 3.0, 30, 160))
        val p = Stats.periods(runs, StatsMode.MONTH, today)
        assertEquals("11월", p.last().label)
        assertEquals(LocalDate.of(2025, 12, 1), p.first().start)
        assertEquals(155.0, p.last().avgHr!!, 1e-9)
    }

    @Test
    fun `비슷한 거리의 가장 최근 이전 기록`() {
        val cur = run("x", LocalDate.of(2026, 11, 4), 3.0, 19)
        val cands = listOf(
            run("old", LocalDate.of(2026, 10, 20), 3.2, 20),
            run("recent", LocalDate.of(2026, 10, 30), 2.8, 18),
            run("far", LocalDate.of(2026, 11, 1), 10.0, 60),
            run("future", LocalDate.of(2026, 11, 5), 3.0, 18),
        )
        assertEquals("recent", Stats.previousSimilar(cur, cands)?.id)
        assertNull(Stats.previousSimilar(cur, listOf(cands[2])))
    }

    @Test
    fun `거리별 개인 최고 기록`() {
        val t = java.time.Instant.parse("2026-11-01T00:00:00Z")
        val m1 = RunMetrics("a", t, 5000.0, mapOf(1000 to 300L, 5000 to 1700L), null, null, null, null)
        val m2 = RunMetrics("b", t, 3000.0, mapOf(1000 to 290L), null, null, null, null)
        val pb = Stats.personalBests(listOf(m1, m2))
        assertEquals("b", pb[1000]?.id)
        assertEquals("a", pb[5000]?.id)
        assertNull(pb[10000])
    }
}
