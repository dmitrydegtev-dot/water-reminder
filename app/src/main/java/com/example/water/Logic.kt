package com.example.water

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.*

data class Entry(val m: Int, val ok: Boolean, val ml: Int)
data class Plan(val norm: Int, val drunk: Int, val k: Int, val at: Int?)

fun nowMin(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
fun parseTime(t: String): Int? {
    val p = t.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].trim().toIntOrNull() ?: return null
    val m = p[1].trim().toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("water", 0)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    private fun str(k: String) = sp.getString(k, "") ?: ""

    var weight: Int get() = i("w", 0); set(v) = put("w", v)
    var height: Int get() = i("h", 0); set(v) = put("h", v)
    var age: Int get() = i("age", 30); set(v) = put("age", v)
    var sex: Int get() = i("sex", 0); set(v) = put("sex", v)
    var act: Int get() = i("act", 0); set(v) = put("act", v)
    var portion: Int get() = i("portion", 250); set(v) = put("portion", v)
    var wake: Int get() = i("wake", 450); set(v) = put("wake", v)
    var sleep: Int get() = i("sleep", 1380); set(v) = put("sleep", v)
    var mode: Int get() = i("mode", 0); set(v) = put("mode", v)
    var again: Int get() = i("again", 15); set(v) = put("again", v)
    var custom: String get() = str("custom"); set(v) = sp.edit().putString("custom", v).apply()
    var ykey: String get() = str("ykey"); set(v) = sp.edit().putString("ykey", v).remove("tdate").apply()
    val ready get() = weight > 0 && height > 0
    val temp get() = sp.getFloat("t", 10f)
    val hasWeather get() = sp.contains("t")
    val tsrc get() = str("tsrc")

    private fun get(u: String, key: String? = null): String {
        val c = URL(u).openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 3000
        if (key != null) c.setRequestProperty("X-Yandex-Weather-Key", key)
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    fun fetchWeather() {
        val today = LocalDate.now().toString()
        if (sp.getString("tdate", "") == today) return
        var t: Double? = null
        var src = ""
        val key = ykey.trim()
        if (key.isNotEmpty()) for (v in listOf("v2", "v1")) {
            if (t != null) break
            try {
                val f = JSONObject(get("https://api.weather.yandex.ru/$v/forecast?lat=59.94&lon=30.31&limit=1&hours=false", key))
                t = f.optJSONArray("forecasts")?.optJSONObject(0)?.optJSONObject("parts")?.optJSONObject("day_short")
                    ?.optDouble("temp")?.takeIf { !it.isNaN() }
                    ?: f.optJSONObject("fact")?.optDouble("temp")?.takeIf { !it.isNaN() }
                if (t != null) src = "Яндекс Погода"
            } catch (_: Exception) {}
        }
        if (t == null) try {
            val j = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=59.94&longitude=30.31&daily=temperature_2m_max&timezone=Europe%2FMoscow&forecast_days=1"))
            t = j.getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(0)
            src = "Open-Meteo"
        } catch (_: Exception) {}
        t?.let { sp.edit().putFloat("t", it.toFloat()).putString("tdate", today).putString("tsrc", src).apply() }
    }

    private fun roll() {
        val today = LocalDate.now().toString()
        if (sp.getString("date", "") != today)
            sp.edit().putString("date", today).putString("log", "[]").putInt("ln", -1).putInt("retry", -1).putInt("rep", 0).apply()
    }
    fun log(): MutableList<Entry> {
        roll()
        val a = JSONArray(sp.getString("log", "[]"))
        return MutableList(a.length()) { val o = a.getJSONObject(it); Entry(o.getInt("m"), o.getBoolean("ok"), o.getInt("ml")) }
    }
    fun saveLog(l: List<Entry>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("m", it.m).put("ok", it.ok).put("ml", it.ml)) }
        sp.edit().putString("log", a.toString()).apply()
    }
    fun add(ok: Boolean) {
        val l = log(); l.add(Entry(nowMin(), ok, portion)); saveLog(l)
        put("rep", 0)
        put("retry", if (ok) -1 else nowMin() + again) // пропустил: напомнить повторно
    }
    fun onNotified() {
        val r = i("rep", 0)
        put("ln", nowMin()); put("rep", r + 1)
        put("retry", nowMin() + if (r < 3) again else 60)
    }

    fun norm(): Int {
        val bsa = sqrt(height * weight / 3600.0)
        var n = bsa * 1150 * (if (age < 30) 1.05 else if (age > 55) 0.9 else 1.0)
        n += listOf(0, 350, 700)[act]
        val t = temp
        n += if (t >= 30) 700 else if (t >= 25) 500 else if (t >= 20) 250 else 0
        return (n / 50).roundToInt() * 50
    }

    fun plan(): Plan {
        val l = log(); val n = norm()
        val dr = l.filter { it.ok }.sumOf { it.ml }
        val k = ceil(max(0, n - dr).toDouble() / portion).toInt()
        val end = sleep - 30
        val base = max(l.lastOrNull()?.m ?: wake, wake)
        val at = if (k > 0 && end > base) (if (l.isEmpty()) wake else (base + (end - base) / k.toDouble()).roundToInt()) else null
        return Plan(n, dr, k, at)
    }

    fun times(): List<Int> = custom.split(",").mapNotNull { parseTime(it) }.sorted()
    private val useCustom get() = mode == 1 && times().isNotEmpty()

    /** Время ближайшего напоминания. Если сегодня всё закончено, то завтра. */
    fun next(): LocalDateTime {
        val p = plan(); val now = LocalDateTime.now(); val nm = nowMin(); val end = sleep - 30
        val planned = if (p.k == 0) null else if (useCustom) times().firstOrNull { it > nm } else p.at?.takeIf { it > i("ln", -1) }
        val retry = i("retry", -1).takeIf { it > 0 && it < end && p.k > 0 }
        val today = listOfNotNull(planned, retry).minOrNull()
        if (today != null) { val m = max(today, nm); return now.toLocalDate().atTime(m / 60, m % 60) }
        val first = if (useCustom) times().first() else wake
        return now.toLocalDate().plusDays(1).atTime(first / 60, first % 60)
    }
}

object Sched {
    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Всегда ставит следующий будильник, в том числе на завтрашнее утро. */
    fun schedule(c: Context) {
        val s = Store(c)
        if (!s.ready) return
        val t = s.next().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        c.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(c))
    }
}

private fun showNotification(c: Context, s: Store) {
    val nm = c.getSystemService(NotificationManager::class.java)
    nm.createNotificationChannel(NotificationChannel("water", "Напоминания о воде", NotificationManager.IMPORTANCE_HIGH))
    fun act(a: String) = PendingIntent.getBroadcast(
        c, a.hashCode(), Intent(c, ActionReceiver::class.java).putExtra("a", a),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
    nm.notify(1, NotificationCompat.Builder(c, "water")
        .setSmallIcon(R.drawable.ic_drop)
        .setContentTitle("Пора выпить воды")
        .setContentText("Выпейте ${s.portion} мл")
        .setContentIntent(open).setAutoCancel(true)
        .addAction(0, "Выпил", act("done"))
        .addAction(0, "Пропустил", act("skip"))
        .build())
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = goAsync()
        Thread {
            try {
                val s = Store(c)
                if (s.ready) {
                    if (s.plan().k > 0) { showNotification(c, s); s.onNotified() }
                    Sched.schedule(c)
                    s.fetchWeather()
                }
            } finally { r.finish() }
        }.start()
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Store(c).add(i.getStringExtra("a") == "done")
        c.getSystemService(NotificationManager::class.java).cancel(1)
        Sched.schedule(c)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = Sched.schedule(c)
}
