package com.kimsan.runexport

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import kotlin.math.cos

/**
 * 시간축 꺾은선 차트.
 * invertY = true 면 작은 값이 위 (페이스: 빠를수록 위).
 * highlight 구간은 배경을 칠해서 표시 (가장 빠른 구간 등).
 */
@Composable
fun LineChart(
    series: List<TimeValue>,
    start: Instant,
    end: Instant,
    color: Color,
    yLabel: (Double) -> String,
    modifier: Modifier = Modifier,
    invertY: Boolean = false,
    highlight: Pair<Instant, Instant>? = null,
) {
    val pts = remember(series) { Analysis.downsample(series) }
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val hlColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
    if (pts.size < 2) {
        Box(modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
            Text("데이터가 없어요", style = MaterialTheme.typography.bodySmall, color = labelColor)
        }
        return
    }
    val minV = pts.minOf { it.value }
    val maxV = pts.maxOf { it.value }
    val pad = ((maxV - minV) * 0.08).coerceAtLeast(1.0)
    val lo = minV - pad
    val hi = maxV + pad
    val totalSec = Duration.between(start, end).seconds.coerceAtLeast(1).toFloat()

    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        val left = 0f
        val bottomPad = 36f
        val w = size.width
        val h = size.height - bottomPad
        fun x(t: Instant) = left + (Duration.between(start, t).seconds / totalSec).coerceIn(0f, 1f) * (w - left)
        fun y(v: Double): Float {
            val f = ((v - lo) / (hi - lo)).toFloat()
            return if (invertY) f * h else h - f * h
        }

        highlight?.let { (a, b) ->
            drawRect(hlColor, topLeft = Offset(x(a), 0f), size = androidx.compose.ui.geometry.Size(x(b) - x(a), h))
        }
        // 가로 격자 3줄
        for (i in 0..2) {
            val gy = h * i / 2f
            drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f)
        }
        val path = Path()
        pts.forEachIndexed { i, p -> if (i == 0) path.moveTo(x(p.time), y(p.value)) else path.lineTo(x(p.time), y(p.value)) }
        drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val paint = android.graphics.Paint().apply {
            this.color = labelColor.toArgb()
            textSize = 28f
            isAntiAlias = true
        }
        val c = drawContext.canvas.nativeCanvas
        // 위/아래 값
        val topV = if (invertY) lo else hi
        val botV = if (invertY) hi else lo
        c.drawText(yLabel(topV), 4f, 28f, paint)
        c.drawText(yLabel(botV), 4f, h - 6f, paint)
        // 시간축
        c.drawText("0분", 0f, size.height - 4f, paint)
        val endLabel = "${(totalSec / 60).toInt()}분"
        c.drawText(endLabel, w - paint.measureText(endLabel), size.height - 4f, paint)
    }
}

/** 지도 배경 없이 GPS 경로 모양만 그리기. 가장 빠른 구간은 빨간색으로 */
@Composable
fun RouteShape(route: List<RoutePoint>, fastest: Pair<Instant, Instant>?, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.primary
    val fast = Color(0xFFE53935)
    val startColor = Color(0xFF43A047)
    if (route.size < 2) return
    Canvas(modifier.fillMaxWidth().height(240.dp)) {
        val lat0 = route.map { it.lat }.average()
        val k = cos(Math.toRadians(lat0))
        val xs = route.map { it.lon * k }
        val ys = route.map { it.lat }
        val minX = xs.min(); val maxX = xs.max(); val minY = ys.min(); val maxY = ys.max()
        val spanX = (maxX - minX).coerceAtLeast(1e-9)
        val spanY = (maxY - minY).coerceAtLeast(1e-9)
        val margin = 16f
        val scale = minOf((size.width - 2 * margin) / spanX, (size.height - 2 * margin) / spanY)
        val offX = (size.width - spanX * scale) / 2
        val offY = (size.height - spanY * scale) / 2
        fun pt(i: Int) = Offset((offX + (xs[i] - minX) * scale).toFloat(), (size.height - offY - (ys[i] - minY) * scale).toFloat())

        val all = Path().apply { moveTo(pt(0).x, pt(0).y); for (i in 1 until route.size) lineTo(pt(i).x, pt(i).y) }
        drawPath(all, base, style = Stroke(width = 6f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        fastest?.let { (a, b) ->
            val idx = route.indices.filter { !route[it].time.isBefore(a) && !route[it].time.isAfter(b) }
            if (idx.size >= 2) {
                val p = Path().apply { moveTo(pt(idx.first()).x, pt(idx.first()).y); idx.drop(1).forEach { lineTo(pt(it).x, pt(it).y) } }
                drawPath(p, fast, style = Stroke(width = 9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        drawCircle(startColor, 10f, pt(0))
        drawCircle(Color.Black, 10f, pt(route.size - 1))
    }
}

/** 심박 구간 막대 */
@Composable
fun ZoneBars(zones: List<Zone>) {
    val colors = listOf(
        Color(0xFF90A4AE), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFFFA726), Color(0xFFEF5350),
    )
    Column {
        zones.forEachIndexed { i, z ->
            val c = if (zones.size == 3) listOf(colors[1], colors[3], colors[4])[i] else colors[i % colors.size]
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(118.dp)) {
                    Text(z.name, style = MaterialTheme.typography.labelMedium)
                    Text(zoneRange(z), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Box(Modifier.fillMaxHeight().fillMaxWidth(z.fraction.toFloat().coerceIn(0f, 1f)).background(c))
                }
                Text(
                    "${formatDuration(Duration.ofSeconds(z.seconds))} (${(z.fraction * 100).toInt()}%)",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.width(92.dp).padding(start = 8.dp),
                )
            }
        }
    }
}

fun zoneRange(z: Zone): String = when {
    z.toBpm == null -> "${z.fromBpm}+ bpm"
    z.fromBpm == null || z.fromBpm <= 0 -> "≤ ${z.toBpm} bpm"
    else -> "${z.fromBpm}–${z.toBpm} bpm"
}
