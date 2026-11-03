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

/** CSV / GPX 파일 만들기 + 공유 / 다운로드 폴더 저장 */
object Exporter {

    data class ExportFile(val name: String, val mime: String, val content: String)

    private const val BOM = "﻿" // 엑셀에서 한글이 깨지지 않도록
    private val fileStamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")

    // ---------- 파일 묶음 ----------

    fun filesForDay(date: LocalDate, runs: List<RunSummary>): List<ExportFile> = listOf(
        ExportFile("runs_${date.toString().replace("-", "")}_summary.csv", "text/csv", summaryCsv(runs))
    )

    fun filesForRun(d: RunDetail): List<ExportFile> {
        val stamp = fileStamp.format(d.summary.start.atZone(ZoneId.systemDefault()))
        val base = "run_$stamp"
        val files = mutableListOf(
            ExportFile("${base}_summary.csv", "text/csv", summaryCsv(listOf(d.summary))),
            ExportFile("${base}_samples.csv", "text/csv", samplesCsv(d)),
        )
        if (d.splits.isNotEmpty()) files += ExportFile("${base}_splits.csv", "text/csv", splitsCsv(d))
        if (d.laps.isNotEmpty()) files += ExportFile("${base}_laps.csv", "text/csv", lapsCsv(d))
        if (d.route.isNotEmpty()) files += ExportFile("$base.gpx", "application/gpx+xml", gpx(d))
        return files
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

    /** 시각별 원시 데이터: 심박 / 속도 / GPS 를 시각 기준으로 합친 표 */
    fun samplesCsv(d: RunDetail): String = buildString {
        append(BOM)
        line("시각", "경과(초)", "심박(bpm)", "속도(km/h)", "위도", "경도", "고도(m)")
        val hr = d.heartRate.associateBy { it.time }
        val sp = d.speed.associateBy { it.time }
        val rt = d.route.associateBy { it.time }
        val times = (hr.keys + sp.keys + rt.keys).toSortedSet()
        for (t in times) {
            val p = rt[t]
            line(
                t.localDateTime(),
                (Duration.between(d.summary.start, t).toMillis() / 1000.0).let { String.format(Locale.US, "%.0f", it) },
                hr[t]?.bpm?.toString() ?: "",
                sp[t]?.kmh?.let { String.format(Locale.US, "%.2f", it) } ?: "",
                p?.lat?.let { String.format(Locale.US, "%.7f", it) } ?: "",
                p?.lon?.let { String.format(Locale.US, "%.7f", it) } ?: "",
                p?.altitudeM?.let { String.format(Locale.US, "%.1f", it) } ?: "",
            )
        }
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
