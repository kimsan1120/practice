package com.kimsan.runexport

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------- 색 ----------

/** 항목별 색 (삼성 헬스처럼 수치마다 색을 다르게) */
object MetricColors {
    val Time = Color(0xFF4C8DFF)
    val Distance = Color(0xFFFFB938)
    val Calories = Color(0xFFFF5C93)
    val Steps = Color(0xFF3CCB8F)
    val Pace = Color(0xFF38B6FF)
    val Speed = Color(0xFF7C8CFF)
    val Elevation = Color(0xFF5BC96A)
    val Heart = Color(0xFFFF5252)
    val Cadence = Color(0xFF26C6B0)
    val Vo2 = Color(0xFFA77BFF)
    val Weather = Color(0xFFFFA14A)
    val Neutral = Color(0xFF9AA3B2)

    /** 라벨 이름으로 색 고르기 */
    fun forLabel(label: String): Color = when {
        "시간" in label -> Time
        "거리" in label || "오르막" in label -> Distance
        "칼로리" in label -> Calories
        "걸음" in label -> Steps
        "페이스" in label -> Pace
        "속도" in label -> Speed
        "고도" in label || "상승" in label || "하강" in label -> Elevation
        "심박" in label -> Heart
        "케이던스" in label -> Cadence
        "VO2" in label -> Vo2
        else -> Neutral
    }
}

/** 심박 구간 색 (회색 → 파랑 → 초록 → 주황 → 빨강) */
val ZoneColors = listOf(Color(0xFF9AA3B2), Color(0xFF4C8DFF), Color(0xFF3CCB8F), Color(0xFFFFA14A), Color(0xFFFF5252))

private val Coral = Color(0xFFFF5A36)
private val Sunset = Color(0xFFFF9A3D)

/** 상단 카드용 그라데이션 */
val HeroBrush = Brush.linearGradient(listOf(Coral, Sunset))

private val LightColors = lightColorScheme(
    primary = Color(0xFFE5482A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDBD1),
    onPrimaryContainer = Color(0xFF3B0900),
    secondary = Color(0xFF2B6CB0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE8FF),
    onSecondaryContainer = Color(0xFF0B2A55),
    tertiary = Color(0xFF8A4DDB),
    tertiaryContainer = Color(0xFFF0E4FF),
    onTertiaryContainer = Color(0xFF2E0A5C),
    background = Color(0xFFF6F4F2),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFF6F4F2),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEDE8E4),
    onSurfaceVariant = Color(0xFF605B57),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFF3EFEC),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFFB9B2AD),
    outlineVariant = Color(0xFFE6E0DB),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF7A59),
    onPrimary = Color(0xFF3B0900),
    primaryContainer = Color(0xFF5C1A08),
    onPrimaryContainer = Color(0xFFFFDBD1),
    secondary = Color(0xFF8DB8FF),
    onSecondary = Color(0xFF0B2A55),
    secondaryContainer = Color(0xFF1D3A66),
    onSecondaryContainer = Color(0xFFDCE8FF),
    tertiary = Color(0xFFC6A6FF),
    tertiaryContainer = Color(0xFF3E2470),
    onTertiaryContainer = Color(0xFFF0E4FF),
    background = Color(0xFF0E1014),
    onBackground = Color(0xFFE8E9EC),
    surface = Color(0xFF0E1014),
    onSurface = Color(0xFFE8E9EC),
    surfaceVariant = Color(0xFF242832),
    onSurfaceVariant = Color(0xFFA8AFBC),
    surfaceContainerLowest = Color(0xFF0B0D10),
    surfaceContainerLow = Color(0xFF15181E),
    surfaceContainer = Color(0xFF181B22),
    surfaceContainerHigh = Color(0xFF20242C),
    surfaceContainerHighest = Color(0xFF1A1D24),
    outline = Color(0xFF4A505C),
    outlineVariant = Color(0xFF2A2F38),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)

/** 숫자는 폭이 같은 숫자(tnum)로 → 표에서 줄이 맞음 */
private val Tnum = "tnum"

private val AppTypography = Typography().let { t ->
    t.copy(
        headlineLarge = t.headlineLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = Tnum),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontFeatureSettings = Tnum),
        bodyMedium = t.bodyMedium.copy(fontFeatureSettings = Tnum),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

/** 큰 숫자 (상단 카드) */
val BigNumber = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", letterSpacing = (-1).sp)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

// ---------- 공통 조각 ----------

/** 둥근 카드 + 왼쪽 색 막대가 있는 제목 */
@Composable
fun SectionCard(
    title: String,
    accent: Color = MaterialTheme.colorScheme.primary,
    trailing: @Composable RowScope.() -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(4.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(accent))
                Spacer(Modifier.width(10.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                trailing()
            }
            Spacer(Modifier.height(2.dp))
            content()
        }
    }
}

/** 색 점 + 라벨 + 값 타일 */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, color: Color = MetricColors.forLabel(label)) {
    Column(
        modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        Text(value, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** 2열 타일 격자 */
@Composable
fun StatTiles(stats: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        stats.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, value) -> StatTile(label, value, Modifier.weight(1f)) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 작은 알약 모양 라벨 */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}
