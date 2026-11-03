package com.kimsan.runexport

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.Duration

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailScreen(
    bundle: ExportBundle,
    weatherLoading: Boolean,
    onRequestRoute: () -> Unit,
    onOpenHealthConnect: () -> Unit,
    onNotesChange: (RunNotes) -> Unit,
    onFetchWeather: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
) {
    val d = bundle.detail
    val a = bundle.analysis
    val s = d.summary
    val context = LocalContext.current
    val fastest = a.fastestSplit?.let { it.start to it.end }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------- 머리말 (그라데이션 카드) + 내보내기 ----------
        item {
            Column(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(HeroBrush).padding(20.dp),
            ) {
                Text(
                    "${s.title ?: s.typeName} · ${s.start.localDateTime().take(10)} ${timeOfDay(s.start)} ${s.start.localTime()}–${s.end.localTime()}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White.copy(alpha = 0.9f),
                )
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(s.distanceMeters?.let { String.format(java.util.Locale.US, "%.2f", it / 1000) } ?: "-", style = BigNumber, color = Color.White)
                    Text(" km", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    HeroStat("운동 시간", formatDuration(s.activeDuration))
                    HeroStat("평균 페이스", formatPace(s.paceSecPerKm))
                    HeroStat("평균 심박", s.avgHr?.let { "$it bpm" } ?: "-")
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "출처: ${appLabel(context, s.sourcePackage)}" +
                        ((bundle.weather?.tempC ?: bundle.notes.tempC)?.let { " · ${fmt1(it)}°C" } ?: "") +
                        (bundle.notes.rpe?.let { " · 체감 $it/10" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
            Spacer(Modifier.height(12.dp))
            Button(onClick = onCopy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("분석용 텍스트 복사") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                OutlinedButton(onClick = onShare, modifier = Modifier.weight(1f).height(46.dp)) { Text("파일 공유") }
                OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).height(46.dp)) { Text("다운로드 저장") }
            }
            Hint(
                "복사: 상세정보 + 구간 + 심박 구간 + 회복 심박 + 컨디션을 붙여넣기 좋은 글로 · 공유/저장: 분석 텍스트 + CSV + GPX",
                Modifier.padding(top = 6.dp),
            )
        }

        // ---------- 1. 운동 상세정보 ----------
        item {
            Section("운동 상세정보") {
                StatGrid(detailStats(bundle))
                if (a.cadenceSource.isNotEmpty()) Note("케이던스: ${a.cadenceSource}")
                if (a.elevation == null) Note("최저·최고 고도와 오르막/내리막 거리는 GPS 경로가 있어야 계산돼요.")
                Note("운동 시간 기준: ${s.durationBasis} · 평균 페이스·속도 = 거리 ÷ 운동 시간 · 최고 페이스 = 최고 속도 기준")
            }
        }

        // ---------- GPS 경로 ----------
        item {
            Section("경로") {
                when (d.routeState) {
                    RouteState.AVAILABLE -> {
                        RouteShape(d.route, fastest)
                        Note("초록 = 출발, 검정 = 도착${if (fastest != null) ", 빨강 = 가장 빠른 1km (${a.fastestSplit!!.index}km)" else ""}")
                    }
                    RouteState.CONSENT_REQUIRED -> {
                        Text("GPS 경로는 기록마다 허락이 필요해요. (고도·경사·지도·기온에 사용)", style = MaterialTheme.typography.bodySmall)
                        Button(onClick = onRequestRoute) { Text("GPS 경로 불러오기") }
                        Note("매번 묻지 않게 하려면: Health Connect 설정 → 앱 권한 → 러닝 기록 내보내기 → 운동 경로 → '항상 허용'")
                        TextButton(onClick = onOpenHealthConnect) { Text("Health Connect 설정 열기") }
                    }
                    RouteState.NONE -> Text("이 기록에는 Health Connect 에 저장된 경로가 없어요.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        // ---------- 2. 차트 ----------
        item {
            Section("차트") { ChartTabs(bundle, fastest) }
        }

        // ---------- 3. 구간기록 ----------
        item {
            Section("구간기록 (1km${if (d.splitSource.isNotEmpty()) ", 기준: ${d.splitSource}" else ""})") {
                if (d.splits.isEmpty()) {
                    Text(
                        "구간을 계산할 데이터(경로·거리·속도 기록)가 부족해요." +
                            if (d.routeState == RouteState.CONSENT_REQUIRED) " GPS 경로를 불러오면 계산돼요." else "",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    SplitBars(d.splits, a.fastestSplit)
                    Note("막대가 길수록 빠른 구간 · ★ 가장 빠른 1km · 구간 시간에는 일시정지가 포함될 수 있어요.")
                }
                if (d.laps.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("워치 랩 기록", style = MaterialTheme.typography.labelLarge)
                    d.laps.forEach { lap ->
                        TableRow("랩 ${lap.index}", formatDuration(Duration.between(lap.start, lap.end)), lap.lengthM?.let { "${fmt0(it)} m" } ?: "-", "")
                    }
                }
            }
        }

        // ---------- 베스트 구간 · 효율 · 부하 ----------
        item {
            Section("베스트 구간 · 효율 · 부하") {
                val m = a.metrics
                if (m.bestEfforts.isNotEmpty()) {
                    m.bestEfforts.toSortedMap().forEach { (dist, sec) ->
                        val isPb = bundle.pbs[dist]?.id == s.id
                        val pbSec = bundle.pbs[dist]?.bestEfforts?.get(dist)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Pill(bestEffortLabel(dist), MaterialTheme.colorScheme.primary, Modifier.width(64.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(formatDuration(Duration.ofSeconds(sec)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("${formatPace(sec / (dist / 1000.0))}/km", Modifier.width(76.dp), style = MaterialTheme.typography.bodyMedium, color = MetricColors.Pace)
                            when {
                                isPb -> Pill("🏅 PB", MetricColors.Distance)
                                pbSec != null -> Text("PB ${formatDuration(Duration.ofSeconds(pbSec))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    Note("기록 안에서 그 거리를 가장 빨리 달린 구간 · PB 는 분석한 기록들 중 최고")
                    Spacer(Modifier.height(6.dp))
                }
                StatGrid(
                    listOfNotNull(
                        m.ef?.let { "효율 지수 (EF)" to String.format(java.util.Locale.US, "%.2f", it) },
                        m.decouplingPct?.let { "유산소 디커플링" to "${fmt1(it)}%" },
                        m.hrDriftBpm?.let { "심박 드리프트" to "${if (it >= 0) "+" else ""}${fmt1(it)} bpm" },
                        m.trimp?.let { "훈련 부하 (TRIMP)" to fmt0(it) },
                    )
                )
                m.trimp?.let { t ->
                    Text(
                        Analysis.trimpRelative(t, bundle.trimpBaseline)
                            ?: "최근 4주 비교 기록이 없어요 (통계 → 모든 기록 분석을 누르면 생겨요)",
                        style = MaterialTheme.typography.labelLarge,
                        color = MetricColors.Calories,
                    )
                }
                if (m.decouplingPct == null && s.activeDuration < Analysis.DECOUPLING_MIN_DURATION) {
                    Note("유산소 디커플링은 30분 이상 달린 기록에서만 계산해요 (앞 10분 워밍업 제외).")
                }
                if (m.trimp == null && a.maxHrUsed == null) {
                    Note("훈련 부하는 최대 심박이 필요해요. 설정에 최대 심박(예: 삼성 헬스의 186)을 입력하세요.")
                }
                Note(
                    "EF = 분당 이동 거리 ÷ 평균 심박 (같은 심박에 더 빠르면 ↑) · 디커플링 = 전반 대비 후반 효율 저하" +
                        (m.decouplingPct?.let { " → ${Analysis.decouplingLabel(it)}" } ?: "") +
                        " · TRIMP = 심박 강도×시간 (안정시 ${a.restHrUsed} ${a.restHrSource}, 최대 ${a.maxHrUsed ?: "-"})"
                )
            }
        }

        // ---------- 지난 기록과 비교 ----------
        bundle.compare?.let { c ->
            item {
                Section("지난 기록과 비교") {
                    val cd = c.start.atZone(java.time.ZoneId.systemDefault())
                    Note("${cd.monthValue}/${cd.dayOfMonth} ${formatKm(c.distanceMeters)} 러닝 (비슷한 거리 중 가장 최근)")
                    CompareRow("", "이번", "지난번", "", header = true)
                    CompareRow("거리", formatKm(s.distanceMeters), formatKm(c.distanceMeters), "")
                    CompareRow("운동 시간", formatDuration(s.activeDuration), formatDuration(c.activeDuration), "")
                    val dp = if (s.paceSecPerKm != null && c.paceSecPerKm != null) s.paceSecPerKm!! - c.paceSecPerKm!! else null
                    CompareRow(
                        "평균 페이스", formatPace(s.paceSecPerKm), formatPace(c.paceSecPerKm),
                        dp?.let { if (it <= 0) "▲ ${fmt0(-it)}초" else "▼ ${fmt0(it)}초" } ?: "",
                        good = dp?.let { it <= 0 },
                    )
                    val dh = if (s.avgHr != null && c.avgHr != null) s.avgHr - c.avgHr else null
                    CompareRow(
                        "평균 심박", s.avgHr?.toString() ?: "-", c.avgHr?.toString() ?: "-",
                        dh?.let { if (it <= 0) "▼ ${-it}" else "▲ $it" } ?: "",
                        good = dh?.let { it <= 0 },
                    )
                    CompareRow("케이던스", fmt0(s.cadenceSpm), fmt0(c.cadenceSpm), "")
                    val ce = bundle.compareMetrics?.ef
                    val me = a.metrics.ef
                    if (ce != null && me != null) {
                        CompareRow(
                            "효율 지수", String.format(java.util.Locale.US, "%.2f", me), String.format(java.util.Locale.US, "%.2f", ce),
                            if (me >= ce) "▲" else "▼", good = me >= ce,
                        )
                    }
                    Note("▲ 초록 = 좋아짐 · 페이스는 빨라지면, 심박은 낮아지면 좋아진 것")
                }
            }
        }

        // ---------- 4. 심박수 구간 ----------
        item {
            Section("심박수 구간") {
                if (a.zones5.isEmpty()) {
                    Text(
                        if (d.heartRate.isEmpty()) "심박 데이터가 없어요."
                        else "심박 구간은 고정된 최대 심박이 필요해요. 설정에 최대 심박(삼성 헬스 심박수 구간 화면의 값, 예: 186)이나 출생연도를 입력하세요. 기록마다 그날 최대값을 쓰면 기록끼리 비교가 안 돼요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("최대 심박 ${a.maxHrUsed} 기준 (${a.maxHrSource})", style = MaterialTheme.typography.labelLarge)
                    ZoneBars(a.zones5)
                    Note(
                        "구간 합계 ${formatDuration(Duration.ofSeconds(a.zones5.sumOf { it.seconds }))} " +
                            "(운동 시간 ${formatDuration(s.activeDuration)}) · 구간 1(${a.zones5.first().fromBpm}) 미만과 심박이 비는 시간은 빠져요." +
                            if (a.maxHrSource != "설정값") " 삼성 헬스와 맞추려면 설정에 삼성 헬스의 최대 심박을 넣으세요." else ""
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (a.zones3.isEmpty()) {
                    Note("설정에서 유산소·무산소 역치 심박(예: 131 / 173)을 입력하면 역치 기준 3구간도 계산해요. 삼성 헬스의 역치 추정치는 Health Connect 로 넘어오지 않아요.")
                } else {
                    Text("역치 기준 (유산소 ${bundle.profile.aerobicThreshold} / 무산소 ${bundle.profile.anaerobicThreshold})", style = MaterialTheme.typography.labelLarge)
                    ZoneBars(a.zones3)
                }
            }
        }

        // ---------- 회복 심박 ----------
        item {
            Section("회복 심박 (종료 후 2분)") {
                val r = a.recovery
                if (r == null) {
                    Text(
                        "삼성 헬스가 운동 종료 후 심박을 Health Connect 로 넘기지 않아요 (데이터 진단 참고). " +
                            "삼성 헬스 앱의 회복 심박 그래프에서 0초·1분·2분 값을 메모에 적어두면 분석 텍스트에 함께 들어가요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    StatGrid(
                        listOf(
                            "종료 시" to "${r.atEnd ?: "-"} bpm",
                            "1분 후" to "${r.after1Min ?: "-"} bpm${r.drop1?.let { " (−$it)" } ?: ""}",
                            "2분 후" to "${r.after2Min ?: "-"} bpm${r.drop2?.let { " (−$it)" } ?: ""}",
                        )
                    )
                    LineChart(
                        a.recoverySeries, s.end.minusSeconds(30), s.end.plusSeconds(180),
                        Color(0xFFEF5350), { "${it.toInt()}" },
                    )
                    Note("그래프: 종료 30초 전 ~ 종료 후 3분")
                }
            }
        }

        // ---------- VO2max ----------
        item {
            Section("VO2max") {
                val v = d.vo2Max
                if (v == null) {
                    Text("이 운동 무렵의 VO2max 값이 Health Connect 에 없어요.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text(
                        "${fmt1(v.value)} ml/kg/min" + (a.vo2Grade?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                    )
                    Note(
                        "측정: ${v.time.localDateTime()}" +
                            if (a.vo2Grade == null) " · 등급은 설정에서 출생연도와 성별을 입력하면 보여요." else " · 등급은 Heyward 기준표 참고용"
                    )
                }
                if (d.vo2History.size >= 2) {
                    Spacer(Modifier.height(6.dp))
                    Text("최근 추이 (${d.vo2History.size}회)", style = MaterialTheme.typography.labelLarge)
                    LineChart(
                        d.vo2History.map { TimeValue(it.time, it.value) },
                        d.vo2History.first().time, d.vo2History.last().time,
                        Color(0xFF7E57C2), { fmt1(it) },
                    )
                    Note("${d.vo2History.first().time.localDateTime().take(10)} ~ ${d.vo2History.last().time.localDateTime().take(10)}")
                }
            }
        }

        // ---------- 컨디션 / 메모 ----------
        item {
            Section("컨디션 · 메모") {
                NotesEditor(bundle, onNotesChange)
                Spacer(Modifier.height(10.dp))
                Text("기온", style = MaterialTheme.typography.labelLarge)
                val w = bundle.weather
                when {
                    weatherLoading -> CircularProgressIndicator(Modifier.padding(4.dp))
                    w != null -> Text(weatherText(w), style = MaterialTheme.typography.bodyMedium)
                    else -> {
                        if (d.route.isNotEmpty()) {
                            OutlinedButton(onClick = onFetchWeather) { Text("운동 시각 기온 가져오기") }
                            Note("버튼을 누르면 대략적인 위치(약 1km 단위)와 날짜를 Open-Meteo 에 보내 기온을 받아요.")
                        } else {
                            Note("GPS 경로가 없어서 자동으로 못 가져와요. 직접 입력하거나, 위 '경로'에서 GPS 경로를 불러오세요.")
                        }
                        var temp by rememberSaveable(s.id + "temp") { mutableStateOf(bundle.notes.tempC?.let { fmt1(it) } ?: "") }
                        OutlinedTextField(
                            value = temp,
                            onValueChange = { v ->
                                temp = v.filter { it.isDigit() || it == '.' || it == '-' }.take(5)
                                onNotesChange(bundle.notes.copy(tempC = temp.toDoubleOrNull()))
                            },
                            label = { Text("기온 직접 입력 (°C)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // ---------- 데이터 진단 ----------
        item {
            Section("데이터 진단 (Health Connect 에 들어온 것)") {
                d.diagnostics.forEach { TableRow(it.label, it.value, "", "", wide = true) }
                Note("여기서 0개 / 없음 인 항목은 삼성 헬스가 Health Connect 로 넘기지 않는 데이터예요.")
                Spacer(Modifier.height(10.dp))
                PaceBasisTable(d)
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotesEditor(bundle: ExportBundle, onChange: (RunNotes) -> Unit) {
    val n = bundle.notes
    Text("체감 난이도 (1~10)", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..10).forEach { v ->
            FilterChip(
                selected = n.rpe == v,
                onClick = { onChange(n.copy(rpe = if (n.rpe == v) null else v)) },
                label = { Text("$v") },
            )
        }
    }
    var memo by rememberSaveable(bundle.detail.summary.id) { mutableStateOf(n.memo) }
    OutlinedTextField(
        value = memo,
        onValueChange = { memo = it; onChange(n.copy(memo = it)) },
        label = { Text("메모 (예: 6km 중 4km째부터 9)") },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(6.dp))
    val hcRest = bundle.detail.restingHr
    var rest by rememberSaveable(bundle.detail.summary.id + "rest") { mutableStateOf(n.restingHr?.toString() ?: "") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = rest,
            onValueChange = { v -> rest = v.filter { it.isDigit() }.take(3); onChange(n.copy(restingHr = rest.toIntOrNull())) },
            label = { Text("아침 안정시 심박") },
            placeholder = { Text(hcRest?.toString() ?: "") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.weight(1f),
        )
        Column(Modifier.weight(1f).padding(top = 8.dp)) {
            Text("체중", style = MaterialTheme.typography.labelSmall)
            Text(bundle.weightText(), style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (hcRest != null && n.restingHr == null) Note("Health Connect 값 $hcRest bpm 을 사용해요. 직접 입력하면 그 값을 써요.")
}

@Composable
private fun ChartTabs(bundle: ExportBundle, fastest: Pair<java.time.Instant, java.time.Instant>?) {
    val a = bundle.analysis
    val s = bundle.detail.summary
    var tab by remember { mutableStateOf(0) }
    val tabs = listOf("페이스", "심박", "케이던스", "고도")
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        tabs.forEachIndexed { i, t -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) }) }
    }
    when (tab) {
        0 -> LineChart(a.paceSeries, s.start, s.end, MaterialTheme.colorScheme.primary, { formatPace(it) }, invertY = true, highlight = fastest)
        1 -> LineChart(a.hrSeries, s.start, s.end, Color(0xFFEF5350), { "${it.toInt()} bpm" }, highlight = fastest)
        2 -> LineChart(a.cadenceSeries, s.start, s.end, Color(0xFF26A69A), { "${it.toInt()} spm" }, highlight = fastest)
        else -> LineChart(a.altitudeSeries, s.start, s.end, Color(0xFF8D6E63), { "${it.toInt()} m" }, highlight = fastest)
    }
    Note(
        when (tab) {
            0 -> "30초 평균 · 위로 갈수록 빠름" + if (bundle.detail.speed.size < 3) " · GPS 경로로 계산" else ""
            1 -> "5초 평균"
            2 -> if (a.cadenceSeries.isEmpty()) "시간별 케이던스는 삼성 헬스 미제공 (평균은 걸음 ÷ 시간으로 계산)" else "20초 평균"
            else -> if (a.altitudeSeries.isEmpty()) "GPS 경로(고도)가 있어야 그려져요" else "GPS 고도, 5포인트 평균"
        } + if (fastest != null) " · 음영 = 가장 빠른 1km" else ""
    )
}

fun detailStats(b: ExportBundle): List<Pair<String, String>> {
    val s = b.detail.summary
    val a = b.analysis
    val e = a.elevation
    val gain = s.elevationGainM ?: e?.gainM
    return listOfNotNull(
        "운동 시간" to formatDuration(s.activeDuration),
        "총 시간" to formatDuration(a.totalTime),
        "거리" to formatKm(s.distanceMeters),
        "칼로리" to "${fmt0(s.totalKcal)} kcal" + (s.activeKcal?.let { " (활동 ${fmt0(it)})" } ?: ""),
        "걸음 수" to (s.steps?.toString() ?: "-"),
        "평균 페이스" to "${formatPace(s.paceSecPerKm)} /km",
        "최고 페이스" to "${formatPace(a.maxPaceSecPerKm)} /km",
        "평균 속도" to "${fmt1(s.avgSpeedKmh ?: s.distanceMeters?.let { it / 1000 / (s.activeDuration.seconds / 3600.0) })} km/h",
        "최고 속도" to "${fmt1(s.maxSpeedKmh ?: a.maxPaceSecPerKm?.let { 3600 / it })} km/h",
        "평균 심박" to "${s.avgHr ?: "-"} bpm",
        "최대 심박" to "${s.maxHr ?: "-"} bpm",
        "평균 케이던스" to "${fmt0(a.avgCadence)} spm",
        "최대 케이던스" to "${fmt0(a.maxCadence)} spm",
        "누적 상승" to "${fmt1(gain)} m",
        "누적 하강" to "${fmt1(e?.lossM)} m",
        "최저 / 최고 고도" to if (e != null) "${fmt0(e.minM)} / ${fmt0(e.maxM)} m" else "-",
        "오르막 / 내리막" to if (e != null) "${formatKm(e.uphillM)} / ${formatKm(e.downhillM)}" else "-",
        "VO2max" to (b.detail.vo2Max?.let { fmt1(it.value) + (a.vo2Grade?.let { g -> " ($g)" } ?: "") } ?: "-"),
    )
}

fun weatherText(w: WeatherInfo): String = buildString {
    append("${fmt1(w.tempC)}°C")
    w.apparentC?.let { append(" (체감 ${fmt1(it)}°C)") }
    w.humidity?.let { append(" · 습도 ${fmt0(it)}%") }
    w.windKmh?.let { append(" · 바람 ${fmt0(it)} km/h") }
    append(" · ${w.hourLabel} 기준")
}

/** 평균 페이스가 삼성 헬스와 다를 때 원인을 찾기 위한 표 */
@Composable
private fun PaceBasisTable(d: RunDetail) {
    val (times, dists) = Analysis.paceBases(d)
    val s = d.summary
    Text("평균 페이스 계산 근거", style = MaterialTheme.typography.labelLarge)
    Note("앱의 평균 페이스 = ${formatDuration(s.activeDuration)} ÷ ${formatKm(s.distanceMeters)} = ${formatPace(s.paceSecPerKm)}/km")
    TableRow("시간 후보", "값", "페이스*", "", header = true)
    times.forEach { t ->
        val pace = if (t.duration != null && s.distanceMeters != null && s.distanceMeters > 10)
            t.duration.seconds / (s.distanceMeters / 1000) else null
        TableRow(t.label, t.duration?.let { formatDuration(it) } ?: "-", formatPace(pace), "")
    }
    Note("* 페이스 = 시간 후보 ÷ 앱의 거리(${formatKm(s.distanceMeters)})")
    TableRow("거리 후보", "값", "페이스**", "", header = true)
    dists.forEach { x ->
        val pace = x.distanceM?.takeIf { it > 10 }?.let { s.activeDuration.seconds / (it / 1000) }
        TableRow(x.label, formatKm(x.distanceM), formatPace(pace), "")
    }
    Note("** 페이스 = 앱의 운동 시간(${formatDuration(s.activeDuration)}) ÷ 거리 후보. 삼성 헬스의 운동 시간·거리와 같은 줄을 알려주시면 그 기준으로 맞출게요.")
}

// ---------- 공통 조각 ----------

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val accent = when {
        "상세" in title -> MaterialTheme.colorScheme.primary
        "경로" in title -> MetricColors.Distance
        "차트" in title -> MetricColors.Pace
        "구간기록" in title -> MetricColors.Time
        "심박" in title -> MetricColors.Heart
        "VO2" in title -> MetricColors.Vo2
        "컨디션" in title -> MetricColors.Weather
        else -> MetricColors.Neutral
    }
    SectionCard(title, accent) { content() }
}

@Composable
private fun StatGrid(stats: List<Pair<String, String>>) = StatTiles(stats)

@Composable
private fun CompareRow(label: String, now: String, prev: String, delta: String, header: Boolean = false, good: Boolean? = null) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(label, Modifier.weight(1.1f), style = style, color = dim)
        Text(now, Modifier.weight(1f), style = if (header) style else MaterialTheme.typography.titleSmall)
        Text(prev, Modifier.weight(1f), style = style, color = dim)
        Text(
            delta, Modifier.weight(0.8f), style = MaterialTheme.typography.labelLarge,
            color = when (good) { true -> MetricColors.Steps; false -> MetricColors.Heart; null -> dim },
        )
    }
}

@Composable
private fun Note(text: String) = Hint(text)

/** 1km 구간: 빠를수록 긴 막대, 가장 빠른 구간은 강조색 */
@Composable
private fun SplitBars(splits: List<Split>, fastest: Split?) {
    val paces = splits.mapNotNull { it.paceSecPerKm }
    if (paces.isEmpty()) return
    val fastestPace = paces.min()
    val slowest = paces.max()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text("구간", Modifier.width(64.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("페이스", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("시간", Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("심박", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        splits.forEach { sp ->
            val p = sp.paceSecPerKm ?: return@forEach
            // 가장 느린 구간 = 45%, 가장 빠른 구간 = 100% 길이
            val f = if (slowest - fastestPace < 1) 1f else (0.45f + 0.55f * ((slowest - p) / (slowest - fastestPace)).toFloat())
            val isFast = sp == fastest
            val barColor = if (isFast) MaterialTheme.colorScheme.primary else MetricColors.Pace.copy(alpha = 0.55f)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    (if (sp.distanceM >= 999) "${sp.index} km" else "+${fmt0(sp.distanceM)}m") + if (isFast) " ★" else "",
                    Modifier.width(64.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isFast) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
                Box(Modifier.weight(1f).height(26.dp)) {
                    Box(
                        Modifier.fillMaxWidth(f.coerceIn(0.1f, 1f)).fillMaxHeight()
                            .clip(RoundedCornerShape(8.dp)).background(barColor),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(
                            formatPace(p),
                            Modifier.padding(start = 10.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color.White,
                        )
                    }
                }
                Text(formatDuration(sp.duration), Modifier.width(56.dp).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                Text(sp.avgHr?.toString() ?: "-", Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium, color = MetricColors.Heart)
            }
        }
    }
}

@Composable
private fun TableRow(a: String, b: String, c: String, d: String, header: Boolean = false, wide: Boolean = false) {
    val style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodyMedium
    val weight = if (header) FontWeight.Bold else FontWeight.Normal
    Column {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(a, Modifier.weight(if (wide) 1.2f else 1f), style = style, fontWeight = weight)
            Text(b, Modifier.weight(if (wide) 1.8f else 1f), style = style, fontWeight = weight)
            if (!wide) {
                Text(c, Modifier.weight(1f), style = style, fontWeight = weight)
                Text(d, Modifier.width(56.dp), style = style, fontWeight = weight)
            }
        }
        HorizontalDivider()
    }
}

fun appLabel(context: Context, pkg: String): String = runCatching {
    val pm = context.packageManager
    pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
}.getOrElse { if (pkg == "com.sec.android.app.shealth") "Samsung Health" else pkg }
