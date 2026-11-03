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
import androidx.health.connect.client.records.SpeedRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
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

        val base = RunDetail(
            summary = summary,
            heartRate = hr,
            speed = speed,
            route = route,
            routeState = routeResult.toState(),
            splits = emptyList(),
            splitSource = "",
            laps = laps,
        )
        return computeSplits(base, distanceRecords)
    }

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
