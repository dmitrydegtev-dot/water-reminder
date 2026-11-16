package com.example.water

import android.app.*
import android.content.*
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.*

data class Entry(val m: Int, val ok: Boolean, val ml: Int)
data class Plan(val norm: Int, val drunk: Int, val k: Int, val at: Int?)

fun nowMin(): Int = LocalTime.now().let { it.hour * 60 + it.minute }
fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("water", 0)
    private fun i(k: String, d: Int) = sp.getInt(k, d)
    private fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    var weight: Int get() = i("w", 0); set(v) = put("w", v)
    var height: Int get() = i("h", 0); set(v) = put("h", v)
    var age: Int get() = i("age", 30); set(v) = put("age", v)
    var sex: Int get() = i("sex", 0); set(v) = put("sex", v)
    var act: Int get() = i("act", 0); set(v) = put("act", v)
    var portion: Int get() = i("portion", 250); set(v) = put("portion", v)
    var wake: Int get() = i("wake", 450); set(v) = put("wake", v)
    var sleep: Int get() = i("sleep", 1380); set(v) = put("sleep", v)
    val ready get() = weight > 0 && height > 0
    val temp get() = sp.getFloat("t", 10f)
    val hasWeather get() = sp.contains("t")

    fun fetchWeather() {
        val today = LocalDate.now().toString()
        if (sp.getString("tdate", "") == today) return
        try {
            val s = URL("https://api.open-meteo.com/v1/forecast?latitude=59.94&longitude=30.31&daily=temperature_2m_max&timezone=Europe%2FMoscow&forecast_days=1").readText()
            val t = JSONObject(s).getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(0)
            sp.edit().putFloat("t", t.toFloat()).putString("tdate", today).apply()
        } catch (_: Exception) {}
    }

    fun log(): MutableList<Entry> {
        val today = LocalDate.now().toString()
        if (sp.getString("date", "") != today) sp.edit().putString("date", today).putString("log", "[]").apply()
        val a = JSONArray(sp.getString("log", "[]"))
        return MutableList(a.length()) { val o = a.getJSONObject(it); Entry(o.getInt("m"), o.getBoolean("ok"), o.getInt("ml")) }
    }
    fun saveLog(l: List<Entry>) {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("m", it.m).put("ok", it.ok).put("ml", it.ml)) }
        sp.edit().putString("log", a.toString()).apply()
    }
    fun add(ok: Boolean) { val l = log(); l.add(Entry(nowMin(), ok, portion)); saveLog(l) }

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
        val at = if (k > 0 && end > base) (base + (end - base) / k.toDouble()).roundToInt() else null
        return Plan(n, dr, k, at)
    }
}

object Sched {
    private fun pi(c: Context) = PendingIntent.getBroadcast(
        c, 1, Intent(c, AlarmReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(c: Context) {
        val s = Store(c)
        if (!s.ready) return
        val at = s.plan().at
        if (at == null) c.getSystemService(AlarmManager::class.java).cancel(pi(c))
        else set(c, max(at - nowMin(), 0))
    }
    fun set(c: Context, delayMin: Int) {
        c.getSystemService(AlarmManager::class.java)
            .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delayMin * 60_000L, pi(c))
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val r = goAsync()
        Thread {
            try {
                val s = Store(c)
                s.fetchWeather()
                if (s.ready && s.plan().k > 0) {
                    val nm = c.getSystemService(NotificationManager::class.java)
                    nm.createNotificationChannel(NotificationChannel("water", "Напоминания о воде", NotificationManager.IMPORTANCE_HIGH))
                    fun act(a: String) = PendingIntent.getBroadcast(
                        c, a.hashCode(), Intent(c, ActionReceiver::class.java).putExtra("a", a),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    val n = NotificationCompat.Builder(c, "water")
                        .setSmallIcon(android.R.drawable.ic_popup_reminder)
                        .setContentTitle("Пора выпить воды")
                        .setContentText("Выпейте ${s.portion} мл")
                        .setContentIntent(open).setAutoCancel(true)
                        .addAction(0, "Выпил", act("done"))
                        .addAction(0, "Пропустил", act("skip"))
                        .build()
                    nm.notify(1, n)
                    Sched.set(c, 20) // повторное напоминание, если не отреагировали
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
