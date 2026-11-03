package com.kimsan.runexport

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** 기록 하나를 내보낼 때 필요한 것 전부 */
data class ExportBundle(
    val detail: RunDetail,
    val analysis: RunAnalysis,
    val notes: RunNotes,
    val weather: WeatherInfo?,
    val profile: Profile,
    val compare: RunSummary? = null,
    val compareMetrics: RunMetrics? = null,
    val pbs: Map<Int, RunMetrics> = emptyMap(),
    val trimpBaseline: Double? = null,
) {
    val restingHr: Long? get() = notes.restingHr?.toLong() ?: detail.restingHr
    val weightKg: Double? get() = detail.weightKg ?: profile.weightKg

    fun weightText(): String = when {
        detail.weightKg != null -> "${fmt1(detail.weightKg)} kg (Health Connect)"
        profile.weightKg != null -> "${fmt1(profile.weightKg)} kg (설정값)"
        else -> "-"
    }
}

/** CSV / GPX / 분석 텍스트 만들기 + 공유 / 다운로드 폴더 저장 */
object Exporter {

    data class ExportFile(val name: String, val mime: String, val content: String)

    private const val BOM = "﻿" // 엑셀에서 한글이 깨지지 않도록
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

    // ---------- 파일 묶음 ----------

    fun filesForDay(date: LocalDate, runs: List<RunSummary>): List<ExportFile> = listOf(
        ExportFile("runs_${date.toString().replace("-", "")}_summary.csv", "text/csv", summaryCsv(runs))
    )

    fun filesForRun(b: ExportBundle): List<ExportFile> {
        val d = b.detail
        val stamp = fileStamp.format(d.summary.start.atZone(ZoneId.systemDefault()))
        val base = "run_$stamp"
        val files = mutableListOf(
            ExportFile("${base}_analysis.txt", "text/plain", analysisText(b)),
            ExportFile("${base}_summary.csv", "text/csv", summaryCsv(listOf(d.summary))),
            ExportFile("${base}_samples.csv", "text/csv", samplesCsv(b)),
        )
        if (d.splits.isNotEmpty()) files += ExportFile("${base}_splits.csv", "text/csv", splitsCsv(d))
        if (b.analysis.zones5.isNotEmpty()) files += ExportFile("${base}_hr_zones.csv", "text/csv", zonesCsv(b.analysis))
        if (d.laps.isNotEmpty()) files += ExportFile("${base}_laps.csv", "text/csv", lapsCsv(d))
        if (d.vo2History.isNotEmpty()) files += ExportFile("vo2max_history.csv", "text/csv", vo2Csv(d))
        if (d.route.isNotEmpty()) files += ExportFile("$base.gpx", "application/gpx+xml", gpx(d))
        return files
    }

    // ---------- 분석용 텍스트 (채팅에 붙여넣기 좋게) ----------

    fun analysisText(b: ExportBundle): String = buildString {
        val d = b.detail
        val s = d.summary
        val a = b.analysis
        val z = s.start.atZone(ZoneId.systemDefault())
        val dow = z.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.KOREAN)
        appendLine("[러닝 기록] ${z.toLocalDate()} ($dow) ${timeOfDay(s.start)} ${s.start.localTime()}~${s.end.localTime()} · ${s.title ?: s.typeName}")
        when {
            b.weather != null -> appendLine("기온: ${weatherText(b.weather)}")
            b.notes.tempC != null -> appendLine("기온: ${fmt1(b.notes.tempC)}°C (직접 입력)")
        }
        val cond = listOfNotNull(
            b.notes.rpe?.let { "체감 난이도 $it/10" },
            b.notes.memo.takeIf { it.isNotBlank() }?.let { "메모: $it" },
        )
        if (cond.isNotEmpty()) appendLine(cond.joinToString(" · "))
        val body = listOfNotNull(
            b.weightKg?.let { "체중 ${fmt1(it)}kg" },
            b.restingHr?.let { "안정시 심박 ${it}bpm" },
        )
        if (body.isNotEmpty()) appendLine(body.joinToString(" · "))
        appendLine()

        appendLine("■ 상세정보")
        detailStats(b).forEach { (k, v) -> if (!v.startsWith("-")) appendLine("- $k: $v") }
        appendLine()

        if (d.splits.isNotEmpty()) {
            appendLine("■ 구간기록 (기준: ${d.splitSource})")
            d.splits.forEach { sp ->
                val label = if (sp.distanceM >= 999) "${sp.index}km" else "+${fmt0(sp.distanceM)}m"
                val star = if (sp == a.fastestSplit) " ★가장 빠름" else ""
                appendLine("- $label ${formatDuration(sp.duration)} · ${formatPace(sp.paceSecPerKm)}/km · 심박 ${sp.avgHr ?: "-"}$star")
            }
            appendLine()
        }

        if (a.zones5.isNotEmpty()) {
            appendLine("■ 심박수 구간 (최대 심박 ${a.maxHrUsed}, ${a.maxHrSource})")
            a.zones5.forEach { appendLine("- ${it.name} ${zoneRange(it)}: ${formatDuration(Duration.ofSeconds(it.seconds))} (${(it.fraction * 100).toInt()}%)") }
            appendLine()
        }
        if (a.zones3.isNotEmpty()) {
            appendLine("■ 역치 기준 3구간 (유산소 ${b.profile.aerobicThreshold} / 무산소 ${b.profile.anaerobicThreshold})")
            a.zones3.forEach { appendLine("- ${it.name} ${zoneRange(it)}: ${formatDuration(Duration.ofSeconds(it.seconds))} (${(it.fraction * 100).toInt()}%)") }
            appendLine()
        }
        val m = a.metrics
        if (m.bestEfforts.isNotEmpty()) {
            appendLine("■ 이번 기록 베스트 구간")
            m.bestEfforts.toSortedMap().forEach { (dist, sec) ->
                val pb = if (b.pbs[dist]?.id == s.id) " 🏅개인 최고" else b.pbs[dist]?.bestEfforts?.get(dist)?.let { " (PB ${formatDuration(Duration.ofSeconds(it))})" } ?: ""
                appendLine("- ${bestEffortLabel(dist)}: ${formatDuration(Duration.ofSeconds(sec))} (${formatPace(sec / (dist / 1000.0))}/km)$pb")
            }
            appendLine()
        }
        if (m.ef != null || m.decouplingPct != null || m.trimp != null) {
            appendLine("■ 효율 · 부하")
            m.ef?.let { appendLine("- 효율 지수(EF): ${String.format(Locale.US, "%.2f", it)} (분당 m ÷ 평균 심박)") }
            m.decouplingPct?.let { appendLine("- 유산소 디커플링: ${fmt1(it)}% (${Analysis.decouplingLabel(it)})") }
            m.hrDriftBpm?.let { appendLine("- 심박 드리프트: 후반 ${if (it >= 0) "+" else ""}${fmt1(it)} bpm") }
            m.trimp?.let {
                val rel = Analysis.trimpRelative(it, b.trimpBaseline) ?: "최근 4주 비교 기록 없음"
                appendLine("- 훈련 부하(TRIMP): ${fmt0(it)} ($rel · 안정시 ${a.restHrUsed}${if (a.restHrSource == "기본값") "(기본값)" else ""}/최대 ${a.maxHrUsed})")
            }
            appendLine()
        }
        b.compare?.let { c ->
            appendLine("■ 지난 비슷한 거리 기록과 비교 (${c.start.localDateTime().take(10)}, ${formatKm(c.distanceMeters)})")
            val dp = if (s.paceSecPerKm != null && c.paceSecPerKm != null) s.paceSecPerKm!! - c.paceSecPerKm!! else null
            appendLine("- 페이스: ${formatPace(s.paceSecPerKm)} vs ${formatPace(c.paceSecPerKm)}" + (dp?.let { " (${if (it <= 0) "${fmt0(-it)}초 빠름" else "${fmt0(it)}초 느림"})" } ?: ""))
            appendLine("- 평균 심박: ${s.avgHr ?: "-"} vs ${c.avgHr ?: "-"}")
            if (m.ef != null && b.compareMetrics?.ef != null) appendLine("- 효율 지수: ${String.format(Locale.US, "%.2f", m.ef)} vs ${String.format(Locale.US, "%.2f", b.compareMetrics.ef)}")
            appendLine()
        }
        a.recovery?.let { r ->
            appendLine("■ 회복 심박")
            appendLine(
                "- 종료 시 ${r.atEnd ?: "-"} → 1분 후 ${r.after1Min ?: "-"}${r.drop1?.let { " (−$it)" } ?: ""}" +
                    " → 2분 후 ${r.after2Min ?: "-"}${r.drop2?.let { " (−$it)" } ?: ""}"
            )
            appendLine()
        }
        if (d.vo2History.size >= 2) {
            appendLine("■ VO2max 추이 (최근 ${d.vo2History.size}회)")
            d.vo2History.takeLast(8).forEach { appendLine("- ${it.time.localDateTime().take(10)}: ${fmt1(it.value)}") }
            appendLine()
        }
        appendLine("■ 데이터 상태")
        appendLine(d.diagnostics.drop(2).joinToString(" · ") { "${it.label} ${it.value}" })
    }

    /** 여러 기록 요약 + 지표 (기간 내보내기) */
    fun periodCsv(runs: List<RunSummary>, metrics: Map<String, RunMetrics>): String = buildString {
        append(BOM)
        line(
            "날짜", "시작", "운동시간", "운동시간(초)", "거리(km)", "평균페이스(/km)", "평균속도(km/h)", "평균심박", "최고심박",
            "걸음수", "케이던스(spm)", "칼로리(kcal)", "상승고도(m)", "효율지수", "디커플링(%)", "심박드리프트", "TRIMP",
            "베스트1km", "베스트3km", "베스트5km", "베스트10km", "베스트하프",
        )
        for (r in runs.sortedBy { it.start }) {
            val m = metrics[r.id]
            val z = r.start.atZone(ZoneId.systemDefault())
            line(
                z.toLocalDate().toString(), r.start.localTime(), formatDuration(r.activeDuration), r.activeDuration.seconds.toString(),
                r.distanceMeters?.let { String.format(Locale.US, "%.3f", it / 1000) } ?: "",
                formatPace(r.paceSecPerKm).orBlank(), fmt1(r.avgSpeedKmh).orBlank(),
                r.avgHr?.toString() ?: "", r.maxHr?.toString() ?: "", r.steps?.toString() ?: "", fmt0(r.cadenceSpm).orBlank(),
                fmt0(r.totalKcal).orBlank(), fmt1(r.elevationGainM).orBlank(),
                m?.ef?.let { String.format(Locale.US, "%.3f", it) } ?: "", fmt1(m?.decouplingPct).orBlank(),
                fmt1(m?.hrDriftBpm).orBlank(), fmt0(m?.trimp).orBlank(),
                *BEST_EFFORT_DISTANCES.map { d -> m?.bestEfforts?.get(d)?.let { formatDuration(Duration.ofSeconds(it)) } ?: "" }.toTypedArray(),
            )
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("러닝 기록", text))
    }

    // ---------- CSV ----------

    fun summaryCsv(runs: List<RunSummary>): String = buildString {
        append(BOM)
        line(
            "날짜", "시작", "종료", "운동", "운동시간", "운동시간(초)", "거리(km)", "평균페이스(/km)",
            "평균속도(km/h)", "최고속도(km/h)", "평균심박", "최고심박", "최저심박", "걸음수",
            "케이던스(spm)", "총칼로리(kcal)", "활동칼로리(kcal)", "상승고도(m)", "출처앱",
        )
        for (r in runs) {
            val z = r.start.atZone(ZoneId.systemDefault())
            line(
                z.toLocalDate().toString(), r.start.localTime(), r.end.localTime(), r.typeName,
                formatDuration(r.activeDuration), r.activeDuration.seconds.toString(),
                r.distanceMeters?.let { String.format(Locale.US, "%.3f", it / 1000) } ?: "",
                formatPace(r.paceSecPerKm).takeIf { it != "-" } ?: "",
                fmt1(r.avgSpeedKmh).orBlank(), fmt1(r.maxSpeedKmh).orBlank(),
                r.avgHr?.toString() ?: "", r.maxHr?.toString() ?: "", r.minHr?.toString() ?: "",
                r.steps?.toString() ?: "", fmt0(r.cadenceSpm).orBlank(),
                fmt0(r.totalKcal).orBlank(), fmt0(r.activeKcal).orBlank(), fmt1(r.elevationGainM).orBlank(),
                r.sourcePackage,
            )
        }
    }

    fun splitsCsv(d: RunDetail): String = buildString {
        append(BOM)
        line("구간", "거리(m)", "구간시간", "구간시간(초)", "페이스(/km)", "평균심박", "계산기준")
        for (s in d.splits) {
            line(
                s.index.toString(), String.format(Locale.US, "%.0f", s.distanceM),
                formatDuration(s.duration), s.duration.seconds.toString(),
                formatPace(s.paceSecPerKm), s.avgHr?.toString() ?: "", d.splitSource,
            )
        }
    }

    fun lapsCsv(d: RunDetail): String = buildString {
        append(BOM)
        line("랩", "시작", "종료", "시간", "거리(m)")
        for (l in d.laps) {
            line(
                l.index.toString(), l.start.localDateTime(), l.end.localDateTime(),
                formatDuration(Duration.between(l.start, l.end)), fmt0(l.lengthM).orBlank(),
            )
        }
    }

    /** 시각별 원시 데이터: 심박 / 속도 / 페이스 / 케이던스 / GPS 를 시각 기준으로 합친 표 */
    fun samplesCsv(b: ExportBundle): String = buildString {
        val d = b.detail
        append(BOM)
        line("시각", "경과(초)", "심박(bpm)", "속도(km/h)", "페이스(/km)", "케이던스(spm)", "위도", "경도", "고도(m)")
        val hr = d.heartRate.associateBy { it.time }
        val sp = Analysis.speedSeries(d).associateBy { it.time }
        val cad = Analysis.cadenceSeries(d).associateBy { it.time }
        val rt = d.route.associateBy { it.time }
        val times = (hr.keys + sp.keys + cad.keys + rt.keys).toSortedSet()
        for (t in times) {
            val p = rt[t]
            val kmh = sp[t]?.value
            line(
                t.localDateTime(),
                (Duration.between(d.summary.start, t).toMillis() / 1000.0).let { String.format(Locale.US, "%.0f", it) },
                hr[t]?.bpm?.toString() ?: "",
                kmh?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                kmh?.takeIf { it >= 3 }?.let { formatPace(3600 / it) } ?: "",
                cad[t]?.value?.let { String.format(Locale.US, "%.0f", it) } ?: "",
                p?.lat?.let { String.format(Locale.US, "%.7f", it) } ?: "",
                p?.lon?.let { String.format(Locale.US, "%.7f", it) } ?: "",
                p?.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            )
        }
    }

    fun zonesCsv(a: RunAnalysis): String = buildString {
        append(BOM)
        line("기준", "구간", "심박 범위", "시간", "시간(초)", "비율(%)")
        for (z in a.zones5) line("최대심박 ${a.maxHrUsed}", z.name, zoneRange(z), formatDuration(Duration.ofSeconds(z.seconds)), z.seconds.toString(), fmt1(z.fraction * 100))
        for (z in a.zones3) line("역치", z.name, zoneRange(z), formatDuration(Duration.ofSeconds(z.seconds)), z.seconds.toString(), fmt1(z.fraction * 100))
    }

    fun vo2Csv(d: RunDetail): String = buildString {
        append(BOM)
        line("측정 시각", "VO2max(ml/kg/min)")
        for (v in d.vo2History) line(v.time.localDateTime(), fmt1(v.value))
    }

    // ---------- GPX (Strava 등에 올릴 수 있는 형식, 심박 포함) ----------

    fun gpx(d: RunDetail): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine(
            """<gpx version="1.1" creator="RunExport" xmlns="http://www.topografix.com/GPX/1/1" """ +
                """xmlns:gpxtpx="http://www.garmin.com/xmlschemas/TrackPointExtension/v1">"""
        )
        appendLine("  <metadata><time>${d.summary.start}</time></metadata>")
        appendLine("  <trk>")
        appendLine("    <name>${xml(d.summary.title ?: d.summary.typeName)} ${d.summary.start.localDateTime()}</name>")
        appendLine("    <type>${if (d.summary.exerciseType in RUNNING_TYPES) "running" else "other"}</type>")
        appendLine("    <trkseg>")
        for (p in d.route) {
            append("      <trkpt lat=\"${String.format(Locale.US, "%.7f", p.lat)}\" lon=\"${String.format(Locale.US, "%.7f", p.lon)}\">")
            p.altitudeM?.let { append("<ele>${String.format(Locale.US, "%.1f", it)}</ele>") }
            append("<time>${p.time}</time>")
            nearestHr(d.heartRate, p.time)?.let {
                append("<extensions><gpxtpx:TrackPointExtension><gpxtpx:hr>$it</gpxtpx:hr></gpxtpx:TrackPointExtension></extensions>")
            }
            appendLine("</trkpt>")
        }
        appendLine("    </trkseg>")
        appendLine("  </trk>")
        appendLine("</gpx>")
    }

    private fun nearestHr(hr: List<HrPoint>, t: Instant, maxGapSec: Long = 10): Long? {
        if (hr.isEmpty()) return null
        var lo = 0
        var hi = hr.size - 1
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (hr[mid].time.isBefore(t)) lo = mid + 1 else hi = mid
        }
        val best = listOfNotNull(hr.getOrNull(lo), hr.getOrNull(lo - 1))
            .minByOrNull { abs(Duration.between(it.time, t).seconds) } ?: return null
        return if (abs(Duration.between(best.time, t).seconds) <= maxGapSec) best.bpm else null
    }

    // ---------- 공유 / 저장 ----------

    /** 공유 시트 열기 (카톡, 드라이브, 메일, 내 파일 등) */
    fun share(context: Context, files: List<ExportFile>) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val uris = ArrayList<Uri>()
        for (f in files) {
            val file = File(dir, f.name)
            file.writeText(f.content, Charsets.UTF_8)
            uris += FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                type = files.first().mime
                putExtra(Intent.EXTRA_STREAM, uris.first())
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "*/*"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            }
        }.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(intent, "기록 내보내기"))
    }

    /** 다운로드/RunExport 폴더에 저장. 저장한 파일 수를 돌려줌 (Android 9 이하는 0) */
    fun saveToDownloads(context: Context, files: List<ExportFile>): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return 0
        val resolver = context.contentResolver
        var saved = 0
        for (f in files) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, f.name)
                // gpx 는 MIME 을 모르는 기기에서 확장자가 바뀔 수 있어 octet-stream 으로 저장
                put(MediaStore.MediaColumns.MIME_TYPE, if (f.name.endsWith(".gpx")) "application/octet-stream" else f.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/RunExport")
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            resolver.openOutputStream(uri)?.use { it.write(f.content.toByteArray(Charsets.UTF_8)) } ?: continue
            saved++
        }
        return saved
    }

    // ---------- 도우미 ----------

    private fun StringBuilder.line(vararg fields: String) {
        append(fields.joinToString(",") { csv(it) })
        append("\r\n")
    }

    private fun csv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    private fun String.orBlank() = if (this == "-") "" else this

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
