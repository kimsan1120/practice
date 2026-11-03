@file:OptIn(ExperimentalMaterial3Api::class)

package com.kimsan.runexport

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { AppRoot() } }
    }
}

@Composable
fun AppRoot(vm: MainViewModel = viewModel()) {
    val state = vm.state
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val permissionLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { vm.refreshAll() }
    val routeLauncher = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { vm.onRouteResult(it) }

    // 앱으로 돌아올 때마다 (설정에서 권한을 바꿨을 수도 있으니) 새로고침
    LifecycleResumeEffect(Unit) {
        vm.refreshAll()
        onPauseOrDispose { }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbar.showSnackbar(it)
            vm.consumeMessage()
        }
    }

    BackHandler(enabled = state.detail != null || state.showSettings) {
        if (state.showSettings) vm.openSettings(false) else vm.closeDetail()
    }

    val bundle = if (state.detail != null && state.analysis != null)
        ExportBundle(state.detail, state.analysis, state.notes, state.weather, state.profile) else null

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Text(
                        when {
                            state.showSettings -> "설정"
                            bundle != null -> "기록 상세"
                            else -> "러닝 기록"
                        },
                        fontWeight = FontWeight.ExtraBold,
                    )
                },
                navigationIcon = {
                    if (state.showSettings || bundle != null) {
                        IconButton(onClick = { if (state.showSettings) vm.openSettings(false) else vm.closeDetail() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                        }
                    }
                },
                actions = {
                    if (bundle != null && !state.showSettings) {
                        IconButton(onClick = { Exporter.share(context, Exporter.filesForRun(bundle)) }) {
                            Icon(Icons.Filled.Share, contentDescription = "공유")
                        }
                    }
                    if (!state.showSettings && state.hasPermission) {
                        IconButton(onClick = { vm.openSettings(true) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "설정")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when {
                state.sdkStatus == null -> Loading()
                state.sdkStatus != HealthConnectClient.SDK_AVAILABLE -> NotAvailableScreen(state.sdkStatus)
                !state.hasPermission -> PermissionScreen(
                    onRequest = { permissionLauncher.launch(vm.permissionsToRequest()) },
                )
                state.showSettings -> SettingsScreen(
                    profile = state.profile,
                    onSave = vm::saveProfile,
                    onOpenHealthConnect = { openHealthConnectSettings(context) },
                )
                bundle != null -> DetailScreen(
                    bundle = bundle,
                    weatherLoading = state.weatherLoading,
                    onRequestRoute = { routeLauncher.launch(bundle.detail.summary.id) },
                    onOpenHealthConnect = { openHealthConnectSettings(context) },
                    onNotesChange = vm::updateNotes,
                    onFetchWeather = vm::fetchWeather,
                    onShare = { Exporter.share(context, Exporter.filesForRun(bundle)) },
                    onSave = { saveAndReport(context, vm, Exporter.filesForRun(bundle)) },
                    onCopy = {
                        Exporter.copyToClipboard(context, Exporter.analysisText(bundle))
                        vm.showMessage("분석용 텍스트를 복사했어요. 채팅창에 붙여넣기 하세요.")
                    },
                )
                else -> ListScreen(
                    state = state,
                    onPrev = { vm.shiftDate(-1) },
                    onNext = { vm.shiftDate(1) },
                    onToday = vm::goToday,
                    onToggleRunning = { vm.setRunningOnly(!state.runningOnly) },
                    onOpen = vm::open,
                    onRequestMore = { permissionLauncher.launch(vm.permissionsToRequest()) },
                    onShare = { Exporter.share(context, Exporter.filesForDay(state.date, state.sessions)) },
                    onSave = { saveAndReport(context, vm, Exporter.filesForDay(state.date, state.sessions)) },
                )
            }
            if (state.detailLoading) {
                Box(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.6f)),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
        }
    }
}

private fun saveAndReport(context: Context, vm: MainViewModel, files: List<Exporter.ExportFile>) {
    val n = runCatching { Exporter.saveToDownloads(context, files) }.getOrElse {
        vm.showMessage("저장 실패: ${it.message}"); return
    }
    if (n == 0) vm.showMessage("이 기기에서는 다운로드 저장을 지원하지 않아요. '공유'를 이용해주세요.")
    else vm.showMessage("다운로드/RunExport 폴더에 ${n}개 파일을 저장했어요")
}

// ---------------- 화면들 ----------------

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

/** 가운데 큰 그림 + 제목 + 설명 + 버튼 */
@Composable
private fun CenterMessage(emoji: String, title: String, body: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.clip(MaterialTheme.shapes.large).background(HeroBrush).padding(22.dp),
            contentAlignment = Alignment.Center,
        ) { Text(emoji, style = MaterialTheme.typography.displaySmall) }
        Spacer(Modifier.height(20.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        content()
    }
}

@Composable
private fun NotAvailableScreen(status: Int?) {
    val context = LocalContext.current
    CenterMessage(
        "🩺", "Health Connect 가 필요해요",
        if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
            "Health Connect 를 설치하거나 업데이트해야 해요."
        else "이 기기는 Health Connect 를 지원하지 않아요. (Android 9 이상 필요)",
    ) {
        Button(onClick = {
            val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
            }.onFailure { Toast.makeText(context, "Play 스토어를 열 수 없어요", Toast.LENGTH_SHORT).show() }
        }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Play 스토어에서 받기") }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    val context = LocalContext.current
    CenterMessage(
        "🏃", "러닝 기록을 불러올게요",
        "삼성 헬스가 Health Connect 에 저장한 운동·심박·거리·속도·칼로리·걸음·고도를 읽기만 해요. " +
            "데이터는 휴대폰 밖으로 나가지 않아요.",
    ) {
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("권한 허용하기") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { openHealthConnectSettings(context) }, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text("Health Connect 설정 열기")
        }
        Spacer(Modifier.height(12.dp))
        Hint(
            "권한 창이 뜨지 않으면 (두 번 거부하면 안 뜰 수 있어요) 'Health Connect 설정 열기' → 앱 권한 → " +
                "'러닝 기록 내보내기' 에서 직접 허용해주세요.",
            Modifier.padding(horizontal = 8.dp),
        )
    }
}

fun openHealthConnectSettings(context: Context) {
    // Android 14 이상: 시스템에 내장된 Health Connect, 13 이하: Health Connect 앱
    val actions = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) add("android.health.connect.action.HEALTH_HOME_SETTINGS")
        add(HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS)
    }
    val opened = actions.any { action ->
        runCatching { context.startActivity(Intent(action)) }.isSuccess
    }
    if (!opened) Toast.makeText(context, "Health Connect 설정을 열 수 없어요", Toast.LENGTH_SHORT).show()
}

private val dateFmt = DateTimeFormatter.ofPattern("M월 d일")

@Composable
private fun ListScreen(
    state: UiState,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
    onToggleRunning: () -> Unit,
    onOpen: (RunSummary) -> Unit,
    onRequestMore: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    val context = LocalContext.current
    val isToday = state.date == LocalDate.now()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 날짜 선택
        item {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surfaceContainer).padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onPrev) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "이전 날") }
                Column(Modifier.weight(1f).clickable(enabled = !isToday, onClick = onToday), horizontalAlignment = Alignment.CenterHorizontally) {
                    val dow = state.date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.KOREAN)
                    Text(dateFmt.format(state.date), style = MaterialTheme.typography.titleLarge)
                    Text(
                        if (isToday) "오늘 · $dow" else "$dow · 눌러서 오늘로",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onNext, enabled = state.date.isBefore(LocalDate.now())) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "다음 날")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.runningOnly, onClick = { if (!state.runningOnly) onToggleRunning() }, label = { Text("러닝") })
                FilterChip(selected = !state.runningOnly, onClick = { if (state.runningOnly) onToggleRunning() }, label = { Text("모든 운동") })
            }
        }

        if (state.missingPermissions.isNotEmpty()) {
            item {
                Card(
                    shape = MaterialTheme.shapes.medium,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "일부 권한이 꺼져 있어 몇몇 수치가 비어 있을 수 있어요.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onRequestMore) { Text("허용") }
                    }
                }
            }
        }

        when {
            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.sessions.isEmpty() -> item {
                Column(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
                        .background(MaterialTheme.colorScheme.surfaceContainer).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("😴", style = MaterialTheme.typography.displaySmall)
                    Text("이 날은 기록된 ${if (state.runningOnly) "러닝" else "운동"}이 없어요", style = MaterialTheme.typography.titleMedium)
                    Hint(
                        "· 워치 → 삼성 헬스 동기화가 끝났는지 확인하세요.\n" +
                            "· 삼성 헬스 → 설정 → Health Connect 에서 '운동' 데이터 공유를 켜세요.\n" +
                            "· 삼성 헬스를 한 번 열었다 닫으면 더 빨리 넘어와요.",
                    )
                    OutlinedButton(onClick = { openHealthConnectSettings(context) }) { Text("Health Connect 설정 열기") }
                }
            }
            else -> {
                item { DayHero(state.sessions) }
                items(state.sessions, key = { it.id }) { RunCard(it, onClick = { onOpen(it) }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Button(onClick = onShare, modifier = Modifier.weight(1f).height(48.dp)) { Text("요약 CSV 공유") }
                        OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f).height(48.dp)) { Text("다운로드 저장") }
                    }
                    Hint("기록을 누르면 차트·구간·심박 구간·회복 심박까지 볼 수 있어요.", Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}

/** 그날 합계 (그라데이션 카드) */
@Composable
private fun DayHero(runs: List<RunSummary>) {
    val dist = runs.sumOf { it.distanceMeters ?: 0.0 }
    val time = runs.fold(Duration.ZERO) { acc, r -> acc.plus(r.activeDuration) }
    val kcal = runs.sumOf { it.totalKcal ?: 0.0 }
    val pace = if (dist > 10) time.seconds / (dist / 1000) else null
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(HeroBrush).padding(20.dp),
    ) {
        Text("이날의 러닝 · ${runs.size}회", style = MaterialTheme.typography.labelLarge, color = Color.White.copy(alpha = 0.9f))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(String.format(Locale.US, "%.2f", dist / 1000), style = BigNumber, color = Color.White)
            Text(" km", style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(bottom = 8.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            HeroStat("운동 시간", formatDuration(time))
            HeroStat("평균 페이스", formatPace(pace))
            HeroStat("칼로리", "${fmt0(kcal)} kcal")
        }
    }
}

@Composable
fun HeroStat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.8f))
        Text(value, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

@Composable
private fun RunCard(r: RunSummary, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min)) {
            Box(Modifier.width(6.dp).fillMaxHeight().background(HeroBrush))
            Column(Modifier.padding(16.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Pill(r.title ?: r.typeName, MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${r.start.localTime()} – ${r.end.localTime()}",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        r.distanceMeters?.let { String.format(Locale.US, "%.2f", it / 1000) } ?: "-",
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(" km", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(bottom = 4.dp))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MiniStat("시간", formatDuration(r.activeDuration), MetricColors.Time)
                    MiniStat("페이스", formatPace(r.paceSecPerKm), MetricColors.Pace)
                    MiniStat("심박", r.avgHr?.let { "$it" } ?: "-", MetricColors.Heart)
                    MiniStat("칼로리", fmt0(r.totalKcal), MetricColors.Calories)
                }
            }
        }
    }
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        Text(value, style = MaterialTheme.typography.titleSmall)
    }
}
