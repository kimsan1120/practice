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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewmodel.compose.viewModel
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
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            state.showSettings -> "설정"
                            state.detail != null -> "기록 상세"
                            else -> "러닝 기록 내보내기"
                        }
                    )
                },
                navigationIcon = {
                    when {
                        state.showSettings -> TextButton(onClick = { vm.openSettings(false) }) { Text("← 닫기") }
                        state.detail != null -> TextButton(onClick = vm::closeDetail) { Text("← 목록") }
                    }
                },
                actions = {
                    if (!state.showSettings && state.hasPermission) {
                        TextButton(onClick = { vm.openSettings(true) }) { Text("설정") }
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
                state.detail != null && state.analysis != null -> {
                    val bundle = ExportBundle(state.detail, state.analysis, state.notes, state.weather, state.profile)
                    DetailScreen(
                        bundle = bundle,
                        weatherLoading = state.weatherLoading,
                        onRequestRoute = { routeLauncher.launch(state.detail.summary.id) },
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
                }
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
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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

@Composable
private fun NotAvailableScreen(status: Int?) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Health Connect 를 사용할 수 없어요", style = MaterialTheme.typography.titleLarge)
        Text(
            if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED)
                "Health Connect 를 설치하거나 업데이트해야 해요."
            else "이 기기는 Health Connect 를 지원하지 않아요. (Android 9 이상 필요)"
        )
        Button(onClick = {
            val uri = Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding")
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.android.vending"))
            }.onFailure { Toast.makeText(context, "Play 스토어를 열 수 없어요", Toast.LENGTH_SHORT).show() }
        }) { Text("Play 스토어에서 Health Connect 받기") }
    }
}

@Composable
private fun PermissionScreen(onRequest: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("권한이 필요해요", style = MaterialTheme.typography.titleLarge)
        Text(
            "삼성 헬스가 Health Connect 에 저장한 운동 기록(운동, 거리, 심박, 속도, 칼로리, 걸음, 고도)을 " +
                "읽기만 해요. 데이터는 휴대폰 밖으로 나가지 않고, 내보내기를 누를 때만 파일로 만들어져요."
        )
        Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) { Text("권한 허용하기") }
        OutlinedButton(onClick = { openHealthConnectSettings(context) }, modifier = Modifier.fillMaxWidth()) {
            Text("Health Connect 설정 열기")
        }
        Text(
            "권한 창이 뜨지 않으면 (두 번 거부하면 안 뜰 수 있어요) 'Health Connect 설정 열기' → 앱 권한 → " +
                "'러닝 기록 내보내기' 에서 직접 허용해주세요.",
            style = MaterialTheme.typography.bodySmall,
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

private val dateFmt = DateTimeFormatter.ofPattern("yyyy.MM.dd")

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
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onPrev) { Text("◀") }
                val dow = state.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)
                Text(
                    "${dateFmt.format(state.date)} ($dow)",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
                TextButton(onClick = onNext, enabled = state.date.isBefore(LocalDate.now())) { Text("▶") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = state.runningOnly, onClick = onToggleRunning, label = { Text("러닝만 보기") })
                if (state.date != LocalDate.now()) OutlinedButton(onClick = onToday) { Text("오늘") }
            }
        }

        if (state.missingPermissions.isNotEmpty()) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("일부 권한이 꺼져 있어서 몇몇 수치가 비어 있을 수 있어요.", style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = onRequestMore) { Text("권한 다시 요청") }
                    }
                }
            }
        }

        when {
            state.loading -> item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            state.sessions.isEmpty() -> item {
                Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("이 날짜에 기록된 ${if (state.runningOnly) "러닝" else "운동"}이 없어요.", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "· 워치 → 삼성 헬스 동기화가 끝났는지 확인하세요.\n" +
                            "· 삼성 헬스 → 설정 → Health Connect 에서 '운동' 데이터 쓰기를 허용했는지 확인하세요.\n" +
                            "· 삼성 헬스가 Health Connect 로 보내는 데 몇 분 걸릴 수 있어요. 삼성 헬스를 한 번 열었다가 다시 와보세요.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { openHealthConnectSettings(context) }) { Text("Health Connect 설정 열기") }
                }
            }
            else -> {
                items(state.sessions, key = { it.id }) { RunCard(it, onClick = { onOpen(it) }) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = onShare, modifier = Modifier.weight(1f)) { Text("요약 CSV 공유") }
                        OutlinedButton(onClick = onSave, modifier = Modifier.weight(1f)) { Text("다운로드에 저장") }
                    }
                    Text(
                        "기록을 누르면 1km 구간, 심박/속도 원본, GPS 경로(GPX)까지 내보낼 수 있어요.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RunCard(r: RunSummary, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "${r.start.localTime()} ~ ${r.end.localTime()} · ${r.title ?: r.typeName}",
                style = MaterialTheme.typography.labelLarge,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Big(formatKm(r.distanceMeters), "거리")
                Big(formatDuration(r.activeDuration), "시간")
                Big(formatPace(r.paceSecPerKm), "페이스")
                Big(r.avgHr?.toString() ?: "-", "평균 심박")
            }
        }
    }
}

@Composable
private fun Big(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
