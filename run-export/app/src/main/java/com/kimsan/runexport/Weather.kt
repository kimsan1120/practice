package com.kimsan.runexport

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Open-Meteo (무료, API 키 없음) 에서 운동 시각의 기온을 가져온다.
 * 위치는 소수점 2자리(약 1km)로 반올림해서 보낸다.
 */
object Weather {

    private val hourFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:00")

    suspend fun fetch(lat: Double, lon: Double, start: Instant, end: Instant): WeatherInfo = withContext(Dispatchers.IO) {
        val zone = ZoneId.systemDefault()
        val mid = start.plusMillis(java.time.Duration.between(start, end).toMillis() / 2).atZone(zone)
        val date = mid.toLocalDate()
        val target = hourFmt.format(mid.truncatedTo(ChronoUnit.HOURS))

        val recent = ChronoUnit.DAYS.between(date, LocalDate.now(zone)) <= 60
        val hosts = if (recent) listOf(FORECAST, ARCHIVE) else listOf(ARCHIVE, FORECAST)
        var lastError: Exception? = null
        for (host in hosts) {
            try {
                return@withContext parse(get(url(host, lat, lon, date, zone)), target)
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("날씨 정보를 가져오지 못했어요")
    }

    private const val FORECAST = "https://api.open-meteo.com/v1/forecast"
    private const val ARCHIVE = "https://archive-api.open-meteo.com/v1/archive"

    private fun url(host: String, lat: Double, lon: Double, date: LocalDate, zone: ZoneId) =
        String.format(
            Locale.US,
            "%s?latitude=%.2f&longitude=%.2f&hourly=temperature_2m,apparent_temperature,relative_humidity_2m,wind_speed_10m" +
                "&start_date=%s&end_date=%s&timezone=%s",
            host, lat, lon, date, date, java.net.URLEncoder.encode(zone.id, "UTF-8"),
        )

    private fun get(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        try {
            if (conn.responseCode !in 200..299) error("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** hourly.time 중 target 시각(없으면 가장 가까운 시각)의 값 */
    internal fun parse(json: String, target: String): WeatherInfo {
        val hourly = JSONObject(json).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        var idx = -1
        for (i in 0 until times.length()) if (times.getString(i) == target) { idx = i; break }
        if (idx == -1) {
            // 같은 날 안에서 가장 가까운 시각
            val th = target.substringAfter('T').take(2).toIntOrNull() ?: 12
            idx = (0 until times.length()).minByOrNull {
                kotlin.math.abs((times.getString(it).substringAfter('T').take(2).toIntOrNull() ?: 0) - th)
            } ?: error("시간별 데이터가 없어요")
        }
        fun value(key: String): Double? = hourly.optJSONArray(key)?.let { if (it.isNull(idx)) null else it.getDouble(idx) }
        val temp = value("temperature_2m") ?: error("기온 데이터가 없어요")
        return WeatherInfo(
            tempC = temp,
            apparentC = value("apparent_temperature"),
            humidity = value("relative_humidity_2m"),
            windKmh = value("wind_speed_10m"),
            hourLabel = times.getString(idx).replace('T', ' '),
        )
    }
}
