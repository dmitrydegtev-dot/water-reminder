package com.example.water

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val s = Store(this)
        Thread { s.fetchWeather(); Sched.schedule(this) }.start()
        setContent { MaterialTheme { App(s) } }
    }
}

fun parseTime(t: String): Int? {
    val p = t.trim().split(":")
    if (p.size != 2) return null
    val h = p[0].toIntOrNull() ?: return null
    val m = p[1].toIntOrNull() ?: return null
    return if (h in 0..23 && m in 0..59) h * 60 + m else null
}

@Composable
fun F(label: String, v: String, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(s: Store) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var edit by remember { mutableStateOf(!s.ready) }
    var w by remember { mutableStateOf(if (s.weight > 0) "${s.weight}" else "") }
    var h by remember { mutableStateOf(if (s.height > 0) "${s.height}" else "") }
    var a by remember { mutableStateOf("${s.age}") }
    var pt by remember { mutableStateOf("${s.portion}") }
    var wk by remember { mutableStateOf(fmt(s.wake)) }
    var sl by remember { mutableStateOf(fmt(s.sleep)) }
    var sex by remember { mutableIntStateOf(s.sex) }
    var act by remember { mutableIntStateOf(s.act) }
    var err by remember { mutableStateOf("") }

    Column(Modifier.systemBarsPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Вода", fontSize = 30.sp)
        if (!edit) {
            val p = remember(tick) { s.plan() }
            val log = remember(tick) { s.log() }
            Text(if (s.hasWeather) "Санкт-Петербург, сегодня до ${s.temp.toInt()} °C" else "Погода недоступна")
            Text("${p.drunk} / ${p.norm} мл", fontSize = 36.sp)
            LinearProgressIndicator(progress = { (p.drunk.toFloat() / p.norm).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text(when {
                p.k == 0 -> "Норма выполнена"
                p.at == null -> "День заканчивается, осталось ${p.k * s.portion} мл"
                p.at <= nowMin() -> "Пора пить воду"
                else -> "Следующее напоминание в ${fmt(p.at)}"
            })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ s.add(true); Sched.schedule(ctx); tick++ }, Modifier.weight(1f)) { Text("Выпил ${s.portion} мл") }
                OutlinedButton({ s.add(false); Sched.schedule(ctx); tick++ }) { Text("Пропустил") }
            }
            if (log.isEmpty()) Text("Отметок пока нет")
            log.forEachIndexed { i, e ->
                Row(Modifier.fillMaxWidth().clickable {
                    val l = s.log(); l[i] = l[i].copy(ok = !l[i].ok); s.saveLog(l); Sched.schedule(ctx); tick++
                }.padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt(e.m)); Text("${e.ml} мл"); Text(if (e.ok) "Выпито" else "Пропущено")
                }
            }
            TextButton({ edit = true }) { Text("Параметры") }
        } else {
            F("Вес, кг", w) { w = it }
            F("Рост, см", h) { h = it }
            F("Возраст", a) { a = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Мужской", "Женский").forEachIndexed { i, t -> FilterChip(sex == i, { sex = i }, { Text(t) }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Низкая", "Средняя", "Высокая").forEachIndexed { i, t -> FilterChip(act == i, { act = i }, { Text(t) }) }
            }
            F("Объём порции, мл", pt) { pt = it }
            F("Подъём (ЧЧ:ММ)", wk) { wk = it }
            F("Сон (ЧЧ:ММ)", sl) { sl = it }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            Button({
                val wi = w.toIntOrNull(); val hi = h.toIntOrNull(); val ai = a.toIntOrNull(); val pi = pt.toIntOrNull()
                val wm = parseTime(wk); val sm = parseTime(sl)
                if (wi == null || hi == null || ai == null || pi == null || pi < 50 || wm == null || sm == null) {
                    err = "Проверьте поля: числа и время в формате 07:30"
                } else {
                    s.weight = wi; s.height = hi; s.age = ai; s.portion = pi; s.wake = wm; s.sleep = sm
                    s.sex = sex; s.act = act; err = ""
                    Sched.schedule(ctx); edit = false; tick++
                }
            }, Modifier.fillMaxWidth()) { Text("Сохранить") }
        }
    }
}
