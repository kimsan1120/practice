package com.kimsan.runexport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

@Composable
fun StatsScreen(
    stats: StatsUi,
    profile: Profile,
    onMode: (StatsMode) -> Unit,
    onSelect: (Int) -> Unit,
    onOpenRun: (RunSummary) -> Unit,
    onAnalyzeAll: () -> Unit,
    onExport: (List<RunSummary>, String) -> Unit,
) {
    val today = LocalDate.now()
    val periods = Stats.periods(stats.runs, stats.mode, today)
    val sel = periods.getOrNull(stats.selected) ?: periods.last()
    val zone = ZoneId.systemDefault()
    val byId = stats.runs.associateBy { it.id }
    val pbs = Stats.personalBests(stats.metrics.values)
    val unanalyzed = stats.runs.count { it.id !in stats.metrics }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = stats.mode == StatsMode.WEEK, onClick = { onMode(StatsMode.WEEK) }, label = { Text("주간") })
                FilterChip(selected = stats.mode == StatsMode.MONTH, onClick = { onMode(StatsMode.MONTH) }, label = { Text("월간") })
                Spacer(Modifier.weight(1f))
                if (stats.loading || stats.analyzing) CircularProgressIndicator(Modifier.width(22.dp).height(22.dp), strokeWidth = 2.dp)
            }
            stats.progress?.let { Hint(it, Modifier.padding(top = 4.dp)) }
        }

        // ---------- 이번 달 목표 ----------
        item {
            val monthStart = today.withDayOfMonth(1)
            val monthKm = stats.runs.filter { !Stats.localDate(it).isBefore(monthStart) }.sumOf { it.distanceMeters ?: 0.0 } / 1000
            val goal = profile.monthlyGoalKm
            Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(HeroBrush).padding(20.dp)) {
                Text("${today.monthValue}월 누적 거리", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(String.format(Locale.US, "%.1f", monthKm), style = BigNumber, color = Color.White)
                    Text(
                        if (goal != null) " / ${fmt0(goal)} km" else " km",
                        style = MaterialTheme.typography.titleLarge, color = Color.White,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (goal != null && goal > 0) {
                    val f = (monthKm / goal).toFloat().coerceIn(0f, 1f)
                    Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color.White.copy(alpha = 0.3f))) {
                        Box(Modifier.fillMaxWidth(f).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(Color.White))
                    }
                    val daysLeft = monthStart.plusMonths(1).toEpochDay() - today.toEpochDay()
                    val remain = (goal - monthKm).coerceAtLeast(0.0)
                    Text(
                        if (remain <= 0) "🎉 목표 달성!" else "${(f * 100).toInt()}% · 남은 ${fmt1(remain)} km · ${daysLeft}일 남음 (하루 ${fmt1(remain / daysLeft.coerceAtLeast(1))} km)",
                        style = MaterialTheme.typography.labelMedium, color = Color.White,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                } else {
                    Text("설정에서 월 목표 거리를 정하면 진행률을 보여줘요.", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
                }
            }
        }

        // ---------- 기간별 거리 막대 ----------
        item {
            SectionCard(if (stats.mode == StatsMode.WEEK) "주간 거리 (최근 12주)" else "월간 거리 (최근 12개월)", MetricColors.Distance) {
                PeriodBars(periods, stats.selected, onSelect)
                Hint("막대를 누르면 그 기간을 자세히 봐요.")
            }
        }

        // ---------- 선택한 기간 ----------
        item {
            val title = if (stats.mode == StatsMode.WEEK)
                "${sel.start.monthValue}/${sel.start.dayOfMonth} – ${sel.end.minusDays(1).monthValue}/${sel.end.minusDays(1).dayOfMonth} 주"
            else "${sel.start.year}년 ${sel.start.monthValue}월"
            SectionCard(title, MetricColors.Time) {
                StatTiles(
                    listOf(
                        "거리" to "${fmt1(sel.distanceKm)} km",
                        "러닝 횟수" to "${sel.runs.size}회",
                        "운동 시간" to formatDuration(sel.time),
                        "평균 페이스" to "${formatPace(sel.paceSecPerKm)} /km",
                        "평균 심박" to (sel.avgHr?.let { "${it.toInt()} bpm" } ?: "-"),
                        "훈련 부하 (TRIMP 합)" to (sel.trimp(stats.metrics)?.let { fmt0(it) } ?: "-"),
                    )
                )
                if (sel.runs.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    sel.runs.sortedByDescending { it.start }.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).clickable { onOpenRun(r) }.padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            val d = r.start.atZone(zone)
                            Text("${d.monthValue}/${d.dayOfMonth}", Modifier.width(48.dp), style = MaterialTheme.typography.labelLarge)
                            Text(formatKm(r.distanceMeters), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            Text(formatPace(r.paceSecPerKm), Modifier.width(64.dp), style = MaterialTheme.typography.bodyMedium, color = MetricColors.Pace)
                            Text(r.avgHr?.toString() ?: "-", Modifier.width(40.dp), style = MaterialTheme.typography.bodyMedium, color = MetricColors.Heart)
                        }
                    }
                    OutlinedButton(onClick = { onExport(sel.runs, title) }, modifier = Modifier.fillMaxWidth()) { Text("이 기간 CSV 내보내기") }
                }
            }
        }

        // ---------- 추이 ----------
        item {
            val withPace = periods.filter { it.paceSecPerKm != null }
            SectionCard("추이", MetricColors.Pace) {
                if (withPace.size < 2) {
                    Hint("기간이 2개 이상 있어야 추이를 그려요.")
                } else {
                    val first = periods.first().start.atStartOfDay(zone).toInstant()
                    val last = periods.last().start.atStartOfDay(zone).toInstant()
                    val xl = periods.first().label to periods.last().label
                    Text("평균 페이스", style = MaterialTheme.typography.labelLarge)
                    LineChart(
                        withPace.map { TimeValue(it.start.atStartOfDay(zone).toInstant(), it.paceSecPerKm!!) },
                        first, last, MetricColors.Pace, { formatPace(it) }, invertY = true, xLabels = xl,
                    )
                    val withHr = periods.filter { it.avgHr != null }
                    if (withHr.size >= 2) {
                        Text("평균 심박", style = MaterialTheme.typography.labelLarge)
                        LineChart(
                            withHr.map { TimeValue(it.start.atStartOfDay(zone).toInstant(), it.avgHr!!) },
                            first, last, MetricColors.Heart, { "${it.toInt()}" }, xLabels = xl,
                        )
                    }
                    val withEf = periods.mapNotNull { p ->
                        p.runs.mapNotNull { stats.metrics[it.id]?.ef }.takeIf { it.isNotEmpty() }
                            ?.let { TimeValue(p.start.atStartOfDay(zone).toInstant(), it.average()) }
                    }
                    if (withEf.size >= 2) {
                        Text("효율 지수 (높을수록 같은 심박에 더 빠름)", style = MaterialTheme.typography.labelLarge)
                        LineChart(withEf, first, last, MetricColors.Steps, { String.format(Locale.US, "%.2f", it) }, xLabels = xl)
                    }
                }
            }
        }

        // ---------- 개인 최고 기록 ----------
        item {
            SectionCard("🏅 개인 최고 기록", MetricColors.Distance) {
                if (pbs.isEmpty()) {
                    Hint("아직 분석한 기록이 없어요. 아래 '모든 기록 분석'을 누르거나 기록을 하나씩 열어보세요.")
                } else {
                    BEST_EFFORT_DISTANCES.forEach { m ->
                        val pb = pbs[m] ?: return@forEach
                        val sec = pb.bestEfforts[m]!!
                        val run = byId[pb.id]
                        val d = pb.start.atZone(zone)
                        Row(
                            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                                .clickable(enabled = run != null) { run?.let(onOpenRun) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Pill(bestEffortLabel(m), MaterialTheme.colorScheme.primary, Modifier.width(64.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(formatDuration(Duration.ofSeconds(sec)), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("${formatPace(sec / (m / 1000.0))}/km", Modifier.width(80.dp), style = MaterialTheme.typography.bodyMedium, color = MetricColors.Pace)
                            Text("${d.year % 100}.${d.monthValue}.${d.dayOfMonth}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Hint("기록 안에서 그 거리를 가장 빨리 달린 구간이에요 (GPS·거리·속도 기록으로 계산, 일시정지 포함될 수 있음).")
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onAnalyzeAll,
                    enabled = !stats.analyzing && !stats.loading && stats.runs.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (unanalyzed > 0) "모든 기록 분석 (${unanalyzed}개 남음)" else "모두 분석됨") }
            }
        }

        item {
            OutlinedButton(
                onClick = { onExport(stats.runs, "최근 12개월") },
                enabled = stats.runs.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("최근 12개월 전체 CSV 내보내기 (${stats.runs.size}개)") }
        }
    }
}

/** 기간별 거리 막대. 누르면 선택 */
@Composable
private fun PeriodBars(periods: List<Period>, selected: Int, onSelect: (Int) -> Unit) {
    val maxKm = (periods.maxOfOrNull { it.distanceKm } ?: 0.0).coerceAtLeast(1.0)
    val base = MetricColors.Distance.copy(alpha = 0.45f)
    val sel = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        Modifier.fillMaxWidth().height(170.dp).pointerInput(periods.size) {
            detectTapGestures { pos ->
                val i = (pos.x / (size.width / periods.size.toFloat())).toInt().coerceIn(0, periods.size - 1)
                onSelect(i)
            }
        }
    ) {
        val n = periods.size
        val slot = size.width / n
        val barW = slot * 0.62f
        val top = 30f
        val bottom = size.height - 34f
        val paint = android.graphics.Paint().apply { textSize = 26f; isAntiAlias = true; textAlign = android.graphics.Paint.Align.CENTER }
        periods.forEachIndexed { i, p ->
            val h = ((p.distanceKm / maxKm) * (bottom - top)).toFloat()
            val x = slot * i + (slot - barW) / 2
            val c = if (i == selected) sel else base
            if (h > 0) drawRoundRect(c, Offset(x, bottom - h), Size(barW, h), CornerRadius(8f, 8f))
            else drawRoundRect(base.copy(alpha = 0.2f), Offset(x, bottom - 4f), Size(barW, 4f), CornerRadius(2f, 2f))
            val cx = slot * i + slot / 2
            if (i == selected || n <= 6 || i % 2 == (n - 1) % 2) {
                paint.color = (if (i == selected) sel else label).toArgb()
                drawContext.canvas.nativeCanvas.drawText(p.label, cx, size.height - 6f, paint)
            }
            if (i == selected && p.distanceKm > 0) {
                paint.color = sel.toArgb()
                drawContext.canvas.nativeCanvas.drawText(String.format(Locale.US, "%.1f", p.distanceKm), cx, (bottom - h - 8f).coerceAtLeast(24f), paint)
            }
        }
    }
}
