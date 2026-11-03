package com.kimsan.runexport

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsCadenceRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.Vo2MaxRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.reflect.KClass

/** Health Connect 에서 운동 기록을 읽어오는 곳 */
class HealthRepository(private val context: Context) {

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    fun sdkStatus(): Int = HealthConnectClient.getSdkStatus(context)

    /** 요청할 권한 목록 (기기가 지원하면 과거 기록 읽기 권한도 포함) */
    fun permissionsToRequest(): Set<String> {
        val historySupported = runCatching {
            client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_HISTORY) ==
                HealthConnectFeatures.FEATURE_STATUS_AVAILABLE
        }.getOrDefault(false)
        return if (historySupported) DATA_PERMISSIONS + HISTORY_PERMISSION else DATA_PERMISSIONS
    }

    suspend fun grantedPermissions(): Set<String> = client.permissionController.getGrantedPermissions()

    /** 해당 날짜(기기 시간대 기준)의 운동 세션 목록 */
    suspend fun readSessions(date: LocalDate, runningOnly: Boolean): List<RunSummary> {
        val zone = ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant()
        val end = date.plusDays(1).atStartOfDay(zone).toInstant()
        val granted = grantedPermissions()
        return readAll(ExerciseSessionRecord::class, start, end)
            .filter { !runningOnly || it.exerciseType in RUNNING_TYPES }
            .sortedBy { it.startTime }
            .map { summarize(it, granted) }
    }

    private suspend fun summarize(s: ExerciseSessionRecord, granted: Set<String>): RunSummary {
        val metrics = buildSet<AggregateMetric<*>> {
            add(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
            if (has(granted, DistanceRecord::class)) add(DistanceRecord.DISTANCE_TOTAL)
            if (has(granted, HeartRateRecord::class)) {
                add(HeartRateRecord.BPM_AVG); add(HeartRateRecord.BPM_MAX); add(HeartRateRecord.BPM_MIN)
            }
            if (has(granted, StepsRecord::class)) add(StepsRecord.COUNT_TOTAL)
            if (has(granted, TotalCaloriesBurnedRecord::class)) add(TotalCaloriesBurnedRecord.ENERGY_TOTAL)
            if (has(granted, ActiveCaloriesBurnedRecord::class)) add(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)
            if (has(granted, SpeedRecord::class)) { add(SpeedRecord.SPEED_AVG); add(SpeedRecord.SPEED_MAX) }
            if (has(granted, ElevationGainedRecord::class)) add(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)
        }
        val range = TimeRangeFilter.between(s.startTime, s.endTime)
        // 1순위: 세션을 기록한 앱(삼성 헬스)이 쓴 값, 없으면 2순위: 모든 앱의 값
        val own = aggregateOrNull(metrics, range, setOf(s.metadata.dataOrigin))
        val any = aggregateOrNull(metrics, range, emptySet())
        fun <T : Any> pick(m: AggregateMetric<T>): T? = own?.get(m) ?: any?.get(m)

        val duration = own?.get(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL)
            ?.takeIf { !it.isZero }
            ?: Duration.between(s.startTime, s.endTime)

        return RunSummary(
            id = s.metadata.id,
            exerciseType = s.exerciseType,
            title = s.title,
            start = s.startTime,
            end = s.endTime,
            sourcePackage = s.metadata.dataOrigin.packageName,
            activeDuration = duration,
            distanceMeters = pick(DistanceRecord.DISTANCE_TOTAL)?.inMeters,
            avgHr = pick(HeartRateRecord.BPM_AVG),
            maxHr = pick(HeartRateRecord.BPM_MAX),
            minHr = pick(HeartRateRecord.BPM_MIN),
            steps = pick(StepsRecord.COUNT_TOTAL),
            totalKcal = pick(TotalCaloriesBurnedRecord.ENERGY_TOTAL)?.inKilocalories,
            activeKcal = pick(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL)?.inKilocalories,
            avgSpeedKmh = pick(SpeedRecord.SPEED_AVG)?.inKilometersPerHour,
            maxSpeedKmh = pick(SpeedRecord.SPEED_MAX)?.inKilometersPerHour,
            elevationGainM = pick(ElevationGainedRecord.ELEVATION_GAINED_TOTAL)?.inMeters,
            routeState = s.exerciseRouteResult.toState(),
        )
    }

    /** 세션 하나의 상세 데이터 (심박/속도 샘플, 경로, 구간) */
    suspend fun readDetail(summary: RunSummary): RunDetail {
        val session = client.readRecord(ExerciseSessionRecord::class, summary.id).record
        val granted = grantedPermissions()
        val start = session.startTime
        val end = session.endTime
        val origin = setOf(session.metadata.dataOrigin)

        val hr = if (has(granted, HeartRateRecord::class)) {
            readPreferOrigin(HeartRateRecord::class, start, end, origin)
                .flatMap { r -> r.samples.map { HrPoint(it.time, it.beatsPerMinute) } }
                .filter { !it.time.isBefore(start) && !it.time.isAfter(end) }
                .distinctBy { it.time }
                .sortedBy { it.time }
        } else emptyList()

        val speed = if (has(granted, SpeedRecord::class)) {
            readPreferOrigin(SpeedRecord::class, start, end, origin)
                .flatMap { r -> r.samples.map { SpeedPoint(it.time, it.speed.inKilometersPerHour) } }
                .filter { !it.time.isBefore(start) && !it.time.isAfter(end) }
                .distinctBy { it.time }
                .sortedBy { it.time }
        } else emptyList()

        val distanceRecords = if (has(granted, DistanceRecord::class)) {
            readPreferOrigin(DistanceRecord::class, start, end, origin)
                .map { it.endTime to it.distance.inMeters }
        } else emptyList()

        val routeResult = session.exerciseRouteResult
        val route = (routeResult as? ExerciseRouteResult.Data)?.exerciseRoute?.toPoints().orEmpty()

        val laps = session.laps.sortedBy { it.startTime }.mapIndexed { i, lap ->
            LapInfo(i + 1, lap.startTime, lap.endTime, lap.length?.inMeters)
        }

        // 걸음 / 케이던스 (StepsCadence 도 READ_STEPS 권한을 씀)
        val hasSteps = has(granted, StepsRecord::class)
        val cadence = if (hasSteps) safe {
            readPreferOrigin(StepsCadenceRecord::class, start, end, origin)
                .flatMap { r -> r.samples.map { CadencePoint(it.time, it.rate) } }
                .filter { !it.time.isBefore(start) && !it.time.isAfter(end) }
                .distinctBy { it.time }.sortedBy { it.time }
        } else emptyList()
        val stepsRecords = if (hasSteps) safe { readPreferOrigin(StepsRecord::class, start, end, origin) } else emptyList()
        val stepsIntervals = stepsRecords.map { StepsInterval(it.startTime, it.endTime, it.count) }.sortedBy { it.start }

        // 회복 심박: 종료 후 3분 (모든 앱)
        val recoveryEnd = end.plusSeconds(180)
        val recovery = if (has(granted, HeartRateRecord::class)) safe {
            readAll(HeartRateRecord::class, end, recoveryEnd)
                .flatMap { r -> r.samples.map { HrPoint(it.time, it.beatsPerMinute) } }
                .filter { it.time.isAfter(end) && !it.time.isAfter(recoveryEnd) }
                .distinctBy { it.time }.sortedBy { it.time }
        } else emptyList()

        // VO2max: 운동 당일 ~ 다음날 아침 사이 측정값 중 운동에 가장 가까운 값, 추이는 최근 180일
        val zone = ZoneId.systemDefault()
        val day = start.atZone(zone).toLocalDate()
        val dayStart = day.atStartOfDay(zone).toInstant()
        val dayEnd = day.plusDays(1).atStartOfDay(zone).toInstant()
        val hasVo2 = has(granted, Vo2MaxRecord::class)
        val vo2History = if (hasVo2) safe {
            readAll(Vo2MaxRecord::class, end.minus(Duration.ofDays(180)), end.plus(Duration.ofHours(12)))
                .map { Vo2Point(it.time, it.vo2MillilitersPerMinuteKilogram) }
                .sortedBy { it.time }
        } else emptyList()
        val vo2 = vo2History
            .filter { !it.time.isBefore(dayStart) && it.time.isBefore(end.plus(Duration.ofHours(12))) }
            .minByOrNull { kotlin.math.abs(Duration.between(it.time, end).seconds) }

        // 그날 안정시 심박 (가장 이른 값 = 아침)
        val hasRest = has(granted, RestingHeartRateRecord::class)
        val resting = if (hasRest) safe {
            readAll(RestingHeartRateRecord::class, dayStart, dayEnd).sortedBy { it.time }
        } else emptyList()

        // 운동 전 90일 안의 가장 최근 체중
        val hasWeight = has(granted, WeightRecord::class)
        val weights = if (hasWeight) safe {
            readAll(WeightRecord::class, end.minus(Duration.ofDays(90)), end)
        } else emptyList()
        val weight = weights.maxByOrNull { it.time }

        val sessionRecordsDistance = distanceRecords.size
        val diag = listOf(
            DiagItem("기록한 앱", session.metadata.dataOrigin.packageName),
            DiagItem("기록 장치", session.metadata.device?.let { listOfNotNull(it.manufacturer, it.model).joinToString(" ") }?.ifBlank { null } ?: "-"),
            DiagItem("심박 샘플", perm(granted, HeartRateRecord::class) { "${hr.size}개" }),
            DiagItem("속도 샘플", perm(granted, SpeedRecord::class) { "${speed.size}개" }),
            DiagItem("거리 기록", perm(granted, DistanceRecord::class) { "${sessionRecordsDistance}개" }),
            DiagItem("걸음 기록", perm(granted, StepsRecord::class) { "${stepsRecords.size}개" }),
            DiagItem("케이던스 샘플", perm(granted, StepsRecord::class) { "${cadence.size}개" }),
            DiagItem("GPS 경로", when (routeResult.toState()) {
                RouteState.AVAILABLE -> "${route.size}개 포인트"
                RouteState.CONSENT_REQUIRED -> "있음 (허락 필요)"
                RouteState.NONE -> "없음"
            }),
            DiagItem("랩 / 세그먼트", "${session.laps.size}개 / ${session.segments.size}개"),
            DiagItem("종료 후 3분 심박", perm(granted, HeartRateRecord::class) { "${recovery.size}개" }),
            DiagItem("VO2max (최근 180일)", perm(granted, Vo2MaxRecord::class) { "${vo2History.size}개" }),
            DiagItem("안정시 심박 (당일)", perm(granted, RestingHeartRateRecord::class) { "${resting.size}개" }),
            DiagItem("체중 (최근 90일)", perm(granted, WeightRecord::class) { "${weights.size}개" }),
        )

        val base = RunDetail(
            summary = summary,
            heartRate = hr,
            speed = speed,
            route = route,
            routeState = routeResult.toState(),
            splits = emptyList(),
            splitSource = "",
            laps = laps,
            cadence = cadence,
            stepsIntervals = stepsIntervals,
            recoveryHr = recovery,
            vo2Max = vo2,
            vo2History = vo2History,
            restingHr = resting.firstOrNull()?.beatsPerMinute,
            weightKg = weight?.weight?.inKilograms,
            diagnostics = diag,
        )
        return computeSplits(base, distanceRecords)
    }

    /** 부가 데이터 읽기 실패는 상세 화면 전체를 막지 않도록 빈 목록으로 */
    private inline fun <T> safe(block: () -> List<T>): List<T> = runCatching(block).getOrDefault(emptyList())

    private inline fun perm(granted: Set<String>, type: KClass<out Record>, value: () -> String): String =
        if (has(granted, type)) value() else "권한 없음"

    private fun has(granted: Set<String>, type: KClass<out Record>) =
        HealthPermission.getReadPermission(type) in granted

    private suspend fun aggregateOrNull(
        metrics: Set<AggregateMetric<*>>,
        range: TimeRangeFilter,
        origins: Set<DataOrigin>,
    ): AggregationResult? = runCatching {
        client.aggregate(AggregateRequest(metrics, range, origins))
    }.getOrNull()

    /** 세션을 기록한 앱의 데이터를 우선 읽고, 없으면 모든 앱의 데이터를 읽는다 */
    private suspend fun <T : Record> readPreferOrigin(
        type: KClass<T>, start: Instant, end: Instant, origin: Set<DataOrigin>,
    ): List<T> = readAll(type, start, end, origin).ifEmpty { readAll(type, start, end) }

    private suspend fun <T : Record> readAll(
        type: KClass<T>, start: Instant, end: Instant, origins: Set<DataOrigin> = emptySet(),
    ): List<T> {
        val out = mutableListOf<T>()
        var token: String? = null
        do {
            val resp = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    dataOriginFilter = origins,
                    pageSize = 1000,
                    pageToken = token,
                )
            )
            out += resp.records
            token = resp.pageToken
        } while (!token.isNullOrEmpty())
        return out
    }

    companion object {
        val EXERCISE_PERMISSION = HealthPermission.getReadPermission(ExerciseSessionRecord::class)

        val DATA_PERMISSIONS: Set<String> = setOf(
            EXERCISE_PERMISSION,
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(StepsRecord::class),
            HealthPermission.getReadPermission(SpeedRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class),
            HealthPermission.getReadPermission(Vo2MaxRecord::class),
            HealthPermission.getReadPermission(RestingHeartRateRecord::class),
            HealthPermission.getReadPermission(WeightRecord::class),
        )

        const val HISTORY_PERMISSION = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY

        /** 경로 동의 화면에서 받은 GPS 경로를 붙이고 구간을 다시 계산 */
        fun withRoute(detail: RunDetail, route: ExerciseRoute): RunDetail =
            computeSplits(
                detail.copy(route = route.toPoints(), routeState = RouteState.AVAILABLE),
                distanceRecords = emptyList(),
            )

        private fun computeSplits(d: RunDetail, distanceRecords: List<Pair<Instant, Double>>): RunDetail {
            val start = d.summary.start
            val candidates = listOf(
                "GPS 경로" to SplitCalculator.fromRoute(d.route),
                "거리 기록" to SplitCalculator.fromDistanceRecords(start, distanceRecords),
                "속도 기록" to SplitCalculator.fromSpeed(start, d.speed),
            )
            for ((label, timeline) in candidates) {
                val splits = SplitCalculator.compute(timeline, d.summary.distanceMeters, d.heartRate)
                if (splits.isNotEmpty()) return d.copy(splits = splits, splitSource = label)
            }
            // 경로가 새로 붙었는데 계산이 안 되면 기존 구간 유지
            return if (d.splits.isNotEmpty()) d else d.copy(splits = emptyList(), splitSource = "")
        }

        private fun ExerciseRoute.toPoints(): List<RoutePoint> =
            route.map { RoutePoint(it.time, it.latitude, it.longitude, it.altitude?.inMeters) }
                .sortedBy { it.time }

        private fun ExerciseRouteResult.toState(): RouteState = when (this) {
            is ExerciseRouteResult.Data -> RouteState.AVAILABLE
            is ExerciseRouteResult.ConsentRequired -> RouteState.CONSENT_REQUIRED
            else -> RouteState.NONE
        }
    }
}
