package com.kimsan.runexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class AnalysisTest {

    private val t0 = Instant.parse("2026-11-03T06:00:00Z")
    private fun t(sec: Long) = t0.plusSeconds(sec)

    @Test
    fun `삼성 헬스와 같은 5구간 경계 (최대 심박 186)`() {
        val z = Analysis.zones5(listOf(HrPoint(t(0), 150)), t(10), 186)
        assertEquals(listOf("93–111 bpm", "112–130 bpm", "131–148 bpm", "149–167 bpm", "168+ bpm"), z.map { zoneRange(it) })
    }

    @Test
    fun `5구간 시간 - 최대 초과는 5구간, 50% 미만은 제외, 비율은 구간 합계 기준`() {
        // 186 기준: 90 은 제외, 120 → 2구간, 198 → 5구간
        val hr = listOf(HrPoint(t(0), 90), HrPoint(t(10), 120), HrPoint(t(20), 198))
        val z = Analysis.zones5(hr, t(30), 186)
        assertEquals(listOf(0L, 10L, 0L, 0L, 10L), z.map { it.seconds })
        assertEquals(0.5, z[4].fraction, 1e-9)
    }

    @Test
    fun `샘플 간격이 길면 10초까지만 센다`() {
        val hr = listOf(HrPoint(t(0), 150), HrPoint(t(100), 150))
        val zones = Analysis.zones3(hr, t(100), 131, 173, 186)
        assertEquals(10L, zones[1].seconds) // 첫 샘플 10초, 마지막 샘플은 종료까지 0초
    }

    @Test
    fun `역치 3구간 경계는 삼성 헬스와 같다 93–131, 132–173, 174 이상`() {
        val hr = listOf(HrPoint(t(0), 92), HrPoint(t(5), 131), HrPoint(t(10), 132), HrPoint(t(15), 173), HrPoint(t(20), 174))
        val z = Analysis.zones3(hr, t(25), 131, 173, 186)
        assertEquals(listOf(5L, 10L, 5L), z.map { it.seconds })
        assertEquals(listOf("93–131 bpm", "132–173 bpm", "174+ bpm"), z.map { zoneRange(it) })
        // 최대 심박을 모르면 하한 없음
        assertEquals("≤ 131 bpm", zoneRange(Analysis.zones3(hr, t(25), 131, 173, null)[0]))
    }

    @Test
    fun `올림 계산은 부동소수 오차가 없다`() {
        assertEquals(114, Analysis.pctCeil(190, 60))
        assertEquals(131, Analysis.pctCeil(186, 70))
        assertEquals(93, Analysis.pctCeil(186, 50))
    }

    @Test
    fun `최고 페이스는 10초 평균 기준이고 튀는 값은 무시`() {
        val speed = (0..60L).map { TimeValue(t(it), 12.0) } + TimeValue(t(61), 80.0) // 80km/h GPS 오류
        val pace = Analysis.maxPace(speed)!!
        assertEquals(300.0, pace, 0.5) // 12km/h = 5'00"/km
    }

    @Test
    fun `고도 통계 - 오르막 내리막 거리`() {
        // 북쪽으로 0.001도(≈111m)씩, 처음 5구간 오르막(+5m), 다음 5구간 내리막(−5m)
        val route = (0..10).map { i ->
            val alt = if (i <= 5) i * 5.0 else (10 - i) * 5.0
            RoutePoint(t(i * 30L), 37.0 + i * 0.001, 127.0, alt)
        }
        val e = Analysis.elevation(route)!!
        assertTrue(e.uphillM > 300)
        assertTrue(e.downhillM > 300)
        assertEquals(e.gainM, e.lossM, 1e-6)
        assertTrue(e.maxM > e.minM)
        assertNull(Analysis.elevation(route.map { it.copy(altitudeM = null) }))
    }

    @Test
    fun `걸음 기록으로 케이던스 계산`() {
        val steps = (0 until 10).map { i -> StepsInterval(t(i * 60L), t(i * 60L + 60), 170) }
        val d = RunDetail(summary(), emptyList(), emptyList(), emptyList(), RouteState.NONE, emptyList(), "", emptyList(), stepsIntervals = steps)
        val series = Analysis.cadenceSeries(d)
        assertEquals(10, series.size)
        val (avg, max) = Analysis.cadenceStats(series)
        assertEquals(170.0, avg!!, 1e-9)
        assertEquals(170.0, max!!, 1e-9)
    }

    @Test
    fun `회복 심박`() {
        val session = listOf(HrPoint(t(595), 170), HrPoint(t(600), 172))
        val after = listOf(HrPoint(t(630), 160), HrPoint(t(660), 140), HrPoint(t(720), 120))
        val r = Analysis.recovery(session, after, t(600))!!
        assertEquals(171L, r.atEnd)
        assertEquals(140L, r.after1Min)
        assertEquals(120L, r.after2Min)
        assertEquals(31L, r.drop1)
        assertNull(Analysis.recovery(session, emptyList(), t(600)))
    }

    @Test
    fun `VO2max 등급`() {
        assertEquals("좋음", Analysis.vo2Grade(44.0, 27, Sex.MALE))
        assertEquals("최상", Analysis.vo2Grade(55.0, 27, Sex.MALE))
        assertEquals("매우 낮음", Analysis.vo2Grade(20.0, 35, Sex.FEMALE))
        assertEquals("보통", Analysis.vo2Grade(27.0, 45, Sex.FEMALE))
    }

    @Test
    fun `analyze - 최대 심박 출처와 가장 빠른 구간`() {
        val splits = listOf(
            Split(1, 1000.0, java.time.Duration.ofSeconds(330), 150, t(0), t(330)),
            Split(2, 1000.0, java.time.Duration.ofSeconds(300), 160, t(330), t(630)),
            Split(3, 400.0, java.time.Duration.ofSeconds(100), 165, t(630), t(730)),
        )
        val d = RunDetail(summary(), listOf(HrPoint(t(0), 150)), emptyList(), emptyList(), RouteState.NONE, splits, "속도 기록", emptyList())
        val a1 = Analysis.analyze(d, Profile(birthYear = 1996))
        assertEquals(190, a1.maxHrUsed) // 2026 - 1996 = 30 → 220 - 30
        assertEquals(2, a1.fastestSplit!!.index) // 400m 나머지 구간은 제외
        val a2 = Analysis.analyze(d, Profile(maxHr = 185, aerobicThreshold = 131, anaerobicThreshold = 173))
        assertEquals(185, a2.maxHrUsed)
        assertEquals(3, a2.zones3.size)
        assertNotNull(a2.totalTime)
    }

    @Test
    fun `움직인 시간 - 일시정지(샘플 공백, 정지 속도)는 뺀다`() {
        // 0~600초 1초 간격 달리기, 600~654초 샘플 없음(일시정지), 654~1190초 다시 달리기, 마지막 10초는 정지(0km/h)
        val run1 = (0..600L).map { TimeValue(t(it), 10.0) }
        val run2 = (654..1180L).map { TimeValue(t(it), 10.0) }
        val stop = (1181..1190L).map { TimeValue(t(it), 0.0) }
        val moving = Analysis.movingTime(run1 + run2 + stop)!!
        assertEquals(600 + 526L, moving.seconds)
        assertNull(Analysis.movingTime(emptyList()))
    }

    @Test
    fun `최고 페이스는 최고 속도가 있으면 그것을 쓴다`() {
        val d = RunDetail(summary(), emptyList(), emptyList(), emptyList(), RouteState.NONE, emptyList(), "", emptyList())
        val a = Analysis.analyze(d, Profile())
        assertEquals(225.0, a.maxPaceSecPerKm!!, 1e-9) // 16km/h = 3'45"
    }

    @Test
    fun `이동평균`() {
        val s = (0..9L).map { TimeValue(t(it), it.toDouble()) }
        val r = Analysis.rolling(s, 3)
        assertEquals(9.0 + 8 + 7, r.last().value * 3, 1e-9)
        assertEquals(listOf(1.0, 2.0, 3.0), Analysis.movingAvg(listOf(0.0, 2.0, 4.0), 3).let { listOf(it[0], it[1], it[2]) })
    }

    private fun summary() = RunSummary(
        id = "x", exerciseType = 56, title = null, start = t(0), end = t(600), sourcePackage = "test",
        activeDuration = java.time.Duration.ofSeconds(600), distanceMeters = 2400.0, avgHr = 150, maxHr = 175, minHr = 100,
        steps = 1700, totalKcal = 200.0, activeKcal = 150.0, avgSpeedKmh = 14.4, maxSpeedKmh = 16.0, elevationGainM = null,
        routeState = RouteState.NONE,
    )
}
