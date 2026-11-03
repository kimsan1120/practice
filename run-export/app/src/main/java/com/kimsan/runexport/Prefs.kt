package com.kimsan.runexport

import android.content.Context
import org.json.JSONObject

/** 휴대폰 안에만 저장하는 설정 / 메모 / 날씨 캐시 */
class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("runexport", Context.MODE_PRIVATE)

    fun profile(): Profile = Profile(
        birthYear = sp.int("birthYear"),
        sex = sp.getString("sex", null)?.let { runCatching { Sex.valueOf(it) }.getOrNull() },
        maxHr = sp.int("maxHr"),
        aerobicThreshold = sp.int("aet"),
        anaerobicThreshold = sp.int("ant"),
        weightKg = sp.getString("weightKg", null)?.toDoubleOrNull(),
    )

    fun saveProfile(p: Profile) {
        sp.edit()
            .putIntOrRemove("birthYear", p.birthYear)
            .putString("sex", p.sex?.name)
            .putIntOrRemove("maxHr", p.maxHr)
            .putIntOrRemove("aet", p.aerobicThreshold)
            .putIntOrRemove("ant", p.anaerobicThreshold)
            .putString("weightKg", p.weightKg?.toString())
            .apply()
    }

    fun notes(sessionId: String): RunNotes {
        val raw = sp.getString("notes_$sessionId", null) ?: return RunNotes()
        return runCatching {
            val o = JSONObject(raw)
            RunNotes(
                rpe = if (o.has("rpe")) o.getInt("rpe") else null,
                memo = o.optString("memo", ""),
                restingHr = if (o.has("restHr")) o.getInt("restHr") else null,
            )
        }.getOrDefault(RunNotes())
    }

    fun saveNotes(sessionId: String, n: RunNotes) {
        val o = JSONObject()
        n.rpe?.let { o.put("rpe", it) }
        o.put("memo", n.memo)
        n.restingHr?.let { o.put("restHr", it) }
        sp.edit().putString("notes_$sessionId", o.toString()).apply()
    }

    fun weather(sessionId: String): WeatherInfo? {
        val raw = sp.getString("weather_$sessionId", null) ?: return null
        return runCatching {
            val o = JSONObject(raw)
            WeatherInfo(
                tempC = o.getDouble("t"),
                apparentC = if (o.has("a")) o.getDouble("a") else null,
                humidity = if (o.has("h")) o.getDouble("h") else null,
                windKmh = if (o.has("w")) o.getDouble("w") else null,
                hourLabel = o.optString("hour"),
            )
        }.getOrNull()
    }

    fun saveWeather(sessionId: String, w: WeatherInfo) {
        val o = JSONObject().put("t", w.tempC).put("hour", w.hourLabel)
        w.apparentC?.let { o.put("a", it) }
        w.humidity?.let { o.put("h", it) }
        w.windKmh?.let { o.put("w", it) }
        sp.edit().putString("weather_$sessionId", o.toString()).apply()
    }

    private fun android.content.SharedPreferences.int(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putIntOrRemove(key: String, v: Int?) =
        if (v == null) remove(key) else putInt(key, v)
}
