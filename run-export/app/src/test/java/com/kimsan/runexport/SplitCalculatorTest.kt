package com.kimsan.runexport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SplitCalculatorTest {

    private val t0 = Instant.parse("2026-11-03T06:00:00Z")

    @Test
    fun `일정한 속도 10kmh 로 2_5km 를 달리면 6분 구간 2개 + 500m 나머지`() {
        // 10 km/h, 1초 간격 샘플 15분 = 2.5km
        val speed = (1..900).map { SpeedPoint(t0.plusSeconds(it.toLong()), 10.0) }
        val hr = (0..900).map { HrPoint(t0.plusSeconds(it.toLong()), 150) }
        val splits = SplitCalculator.compute(SplitCalculator.fromSpeed(t0, speed), null, hr)

        assertEquals(3, splits.size)
        assertEquals(360.0, splits[0].duration.toMillis() / 1000.0, 1.0)
        assertEquals(360.0, splits[1].duration.toMillis() / 1000.0, 1.0)
        assertEquals(500.0, splits[2].distanceM, 1.0)
        assertEquals(360.0, splits[0].paceSecPerKm!!, 1.0)
        assertEquals(150L, splits[0].avgHr)
    }

    @Test
    fun `총 거리로 보정한다`() {
        // 속도 적분은 2km 인데 세션 총 거리가 3km 이면 구간도 3개
        val speed = (1..720).map { SpeedPoint(t0.plusSeconds(it.toLong()), 10.0) }
        val splits = SplitCalculator.compute(SplitCalculator.fromSpeed(t0, speed), 3000.0, emptyList())
        assertEquals(3, splits.size)
        assertEquals(240.0, splits[0].duration.toMillis() / 1000.0, 1.0)
        assertNull(splits[0].avgHr)
    }

    @Test
    fun `GPS 경로로 거리 계산`() {
        // 위도 0.001도 ≈ 111m, 20개 포인트 ≈ 2.1km
        val route = (0..19).map { RoutePoint(t0.plusSeconds(it * 30L), 37.0 + it * 0.001, 127.0, null) }
        val tl = SplitCalculator.fromRoute(route)
        assertEquals(2.0 * 1056, tl.last().meters, 30.0)
        val splits = SplitCalculator.compute(tl, null, emptyList())
        assertEquals(2, splits.count { it.distanceM == 1000.0 })
    }

    @Test
    fun `데이터가 부족하면 빈 목록`() {
        assertTrue(SplitCalculator.compute(emptyList(), 5000.0, emptyList()).isEmpty())
        assertTrue(SplitCalculator.fromDistanceRecords(t0, listOf(t0.plus(Duration.ofMinutes(30)) to 5000.0)).isEmpty())
    }
}
