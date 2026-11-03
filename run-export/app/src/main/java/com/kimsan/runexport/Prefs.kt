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
        monthlyGoalKm = sp.getString("goalKm", null)?.toDoubleOrNull(),
        notifyNewRuns = sp.getBoolean("notify", false),
    )

    fun saveProfile(p: Profile) {
        sp.edit()
            .putIntOrRemove("birthYear", p.birthYear)
            .putString("sex", p.sex?.name)
            .putIntOrRemove("maxHr", p.maxHr)
            .putIntOrRemove("aet", p.aerobicThreshold)
            .putIntOrRemove("ant", p.anaerobicThreshold)
            .putString("weightKg", p.weightKg?.toString())
            .putString("goalKm", p.monthlyGoalKm?.toString())
            .putBoolean("notify", p.notifyNewRuns)
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
                tempC = if (o.has("temp")) o.getDouble("temp") else null,
            )
        }.getOrDefault(RunNotes())
    }

    fun saveNotes(sessionId: String, n: RunNotes) {
        val o = JSONObject()
        n.rpe?.let { o.put("rpe", it) }
        o.put("memo", n.memo)
        n.restingHr?.let { o.put("restHr", it) }
        n.tempC?.let { o.put("temp", it) }
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

    // ---------- 요약 캐시 (통계용) ----------

    fun saveSummary(r: RunSummary, lastModified: Long) {
        val o = JSONObject()
            .put("id", r.id).put("type", r.exerciseType).put("title", r.title ?: JSONObject.NULL)
            .put("start", r.start.toEpochMilli()).put("end", r.end.toEpochMilli())
            .put("src", r.sourcePackage).put("dur", r.activeDuration.seconds).put("basis", r.durationBasis)
            .put("route", r.routeState.name).put("mod", lastModified)
        o.putOpt("dist", r.distanceMeters); o.putOpt("avgHr", r.avgHr); o.putOpt("maxHr", r.maxHr); o.putOpt("minHr", r.minHr)
        o.putOpt("steps", r.steps); o.putOpt("kcal", r.totalKcal); o.putOpt("akcal", r.activeKcal)
        o.putOpt("avgSpd", r.avgSpeedKmh); o.putOpt("maxSpd", r.maxSpeedKmh); o.putOpt("elev", r.elevationGainM)
        sp.edit().putString("sum_${r.id}", o.toString()).apply()
    }

    /** id → (요약, 마지막 수정 시각) */
    fun summaries(): Map<String, Pair<RunSummary, Long>> = sp.all.entries
        .filter { it.key.startsWith("sum_") }
        .mapNotNull { e ->
            runCatching {
                val o = JSONObject(e.value as String)
                fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
                fun l(k: String) = if (o.has(k) && !o.isNull(k)) o.getLong(k) else null
                val r = RunSummary(
                    id = o.getString("id"),
                    exerciseType = o.getInt("type"),
                    title = if (o.isNull("title")) null else o.getString("title"),
                    start = java.time.Instant.ofEpochMilli(o.getLong("start")),
                    end = java.time.Instant.ofEpochMilli(o.getLong("end")),
                    sourcePackage = o.getString("src"),
                    activeDuration = java.time.Duration.ofSeconds(o.getLong("dur")),
                    distanceMeters = d("dist"), avgHr = l("avgHr"), maxHr = l("maxHr"), minHr = l("minHr"),
                    steps = l("steps"), totalKcal = d("kcal"), activeKcal = d("akcal"),
                    avgSpeedKmh = d("avgSpd"), maxSpeedKmh = d("maxSpd"), elevationGainM = d("elev"),
                    routeState = RouteState.valueOf(o.getString("route")),
                    durationBasis = o.optString("basis", ""),
                )
                r.id to (r to o.getLong("mod"))
            }.getOrNull()
        }.toMap()

    // ---------- 지표 캐시 (베스트 기록 / 효율 / 부하) ----------

    fun saveMetrics(m: RunMetrics) {
        val best = JSONObject().apply { m.bestEfforts.forEach { (k, v) -> put(k.toString(), v) } }
        val o = JSONObject().put("id", m.id).put("start", m.start.toEpochMilli()).put("best", best)
        o.putOpt("dist", m.distanceM); o.putOpt("ef", m.ef); o.putOpt("dec", m.decouplingPct)
        o.putOpt("drift", m.hrDriftBpm); o.putOpt("trimp", m.trimp)
        sp.edit().putString("met_${m.id}", o.toString()).apply()
    }

    fun metrics(): Map<String, RunMetrics> = sp.all.entries
        .filter { it.key.startsWith("met_") }
        .mapNotNull { e ->
            runCatching {
                val o = JSONObject(e.value as String)
                fun d(k: String) = if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
                val b = o.getJSONObject("best")
                val m = RunMetrics(
                    id = o.getString("id"),
                    start = java.time.Instant.ofEpochMilli(o.getLong("start")),
                    distanceM = d("dist"),
                    bestEfforts = b.keys().asSequence().associate { it.toInt() to b.getLong(it) },
                    ef = d("ef"), decouplingPct = d("dec"), hrDriftBpm = d("drift"), trimp = d("trimp"),
                )
                m.id to m
            }.getOrNull()
        }.toMap()

    // ---------- 새 러닝 알림 ----------

    var changesToken: String?
        get() = sp.getString("changesToken", null)
        set(v) { sp.edit().putString("changesToken", v).apply() }

    fun wasNotified(id: String): Boolean = sp.getStringSet("notified", emptySet())!!.contains(id)

    fun markNotified(id: String) {
        val set = sp.getStringSet("notified", emptySet())!!.toMutableSet().apply { add(id) }
        // 너무 커지지 않게 최근 200개만
        sp.edit().putStringSet("notified", set.toList().takeLast(200).toSet()).apply()
    }

    private fun android.content.SharedPreferences.int(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.Editor.putIntOrRemove(key: String, v: Int?) =
        if (v == null) remove(key) else putInt(key, v)
}
