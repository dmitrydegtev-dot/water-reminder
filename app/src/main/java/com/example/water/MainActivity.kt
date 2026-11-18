package com.example.water

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime

class MainActivity : ComponentActivity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        val s = Store(this)
        val ver = mutableIntStateOf(0)
        Thread { s.fetchWeather(); Sched.schedule(this); runOnUiThread { ver.intValue++ } }.start()
        setContent { Theme { App(s, ver.intValue) } }
    }
}

@Composable
fun Theme(content: @Composable () -> Unit) {
    val cs = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFF4FC3D6), onPrimary = Color(0xFF00363F), background = Color(0xFF0A1C22),
            surface = Color(0xFF112A32), surfaceVariant = Color(0xFF1F3D46), onSurfaceVariant = Color(0xFF86A8AE),
            tertiary = Color(0xFFE0A06A), onBackground = Color(0xFFE6F4F2), onSurface = Color(0xFFE6F4F2))
    else
        lightColorScheme(primary = Color(0xFF0F6E80), onPrimary = Color.White, background = Color(0xFFEAF6F4),
            surface = Color.White, surfaceVariant = Color(0xFFCFE5E3), onSurfaceVariant = Color(0xFF5B7A82),
            tertiary = Color(0xFFC9854A), onBackground = Color(0xFF0D2B33), onSurface = Color(0xFF0D2B33))
    MaterialTheme(colorScheme = cs) { Surface(Modifier.fillMaxSize(), color = cs.background) { content() } }
}

@Composable
fun Panel(content: @Composable ColumnScope.() -> Unit) =
    Card(Modifier.fillMaxWidth(), RoundedCornerShape(24.dp), CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }

@Composable
fun Ring(f: Float, main: String, sub: String) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val arc = MaterialTheme.colorScheme.primary
    Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val st = Stroke(22.dp.toPx(), cap = StrokeCap.Round)
            val pad = 11.dp.toPx()
            val sz = Size(size.width - 2 * pad, size.height - 2 * pad)
            drawArc(track, 0f, 360f, false, Offset(pad, pad), sz, style = st)
            drawArc(arc, -90f, 360f * f, false, Offset(pad, pad), sz, style = st)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(main, fontSize = 46.sp, fontWeight = FontWeight.Bold)
            Text(sub, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun F(label: String, v: String, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())

@Composable
fun App(s: Store, ver: Int) {
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
    var mode by remember { mutableIntStateOf(s.mode) }
    var cs by remember { mutableStateOf(s.custom) }
    var ag by remember { mutableStateOf("${s.again}") }
    var yk by remember { mutableStateOf(s.ykey) }
    var err by remember { mutableStateOf("") }

    Column(Modifier.systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Вода", fontSize = 30.sp, fontWeight = FontWeight.Bold)
            if (!edit) TextButton({ edit = true }) { Text("Настройки") }
        }
        if (!edit) {
            val p = remember(tick, ver) { s.plan() }
            val nx = remember(tick, ver) { s.next() }
            val log = remember(tick) { s.log() }
            val now = LocalDateTime.now()
            val nxm = nx.hour * 60 + nx.minute
            val status = when {
                p.k == 0 -> "Норма выполнена"
                nx.toLocalDate() != now.toLocalDate() -> "Следующее напоминание завтра в ${fmt(nxm)}"
                !nx.isAfter(now) -> "Пора пить воду"
                else -> "Следующее напоминание в ${fmt(nxm)}"
            }
            Panel {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Ring((p.drunk.toFloat() / p.norm).coerceIn(0f, 1f), "${p.drunk}", "из ${p.norm} мл")
                }
                Text(status, fontWeight = FontWeight.SemiBold, modifier = Modifier.fillMaxWidth())
                Text(
                    if (s.hasWeather) "Санкт-Петербург ${s.temp.toInt()} °C · ${s.tsrc}" else "Погода недоступна",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button({ s.add(true); Sched.schedule(ctx); tick++ }, Modifier.weight(1f).height(56.dp), shape = RoundedCornerShape(18.dp)) {
                    Text("Выпил ${s.portion} мл")
                }
                OutlinedButton({ s.add(false); Sched.schedule(ctx); tick++ }, Modifier.height(56.dp), shape = RoundedCornerShape(18.dp)) {
                    Text("Пропустил")
                }
            }
            Panel {
                Text("Сегодня", fontWeight = FontWeight.Bold)
                if (log.isEmpty()) Text("Отметок пока нет", color = MaterialTheme.colorScheme.onSurfaceVariant)
                log.forEachIndexed { i, e ->
                    Row(Modifier.fillMaxWidth().clickable {
                        val l = s.log(); l[i] = l[i].copy(ok = !l[i].ok); s.saveLog(l); Sched.schedule(ctx); tick++
                    }.padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(fmt(e.m)); Text("${e.ml} мл")
                        Text(if (e.ok) "Выпито" else "Пропущено",
                            color = if (e.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        } else {
            Panel {
                Text("Параметры", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { F("Вес, кг", w) { w = it } }
                    Box(Modifier.weight(1f)) { F("Рост, см", h) { h = it } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { F("Возраст", a) { a = it } }
                    Box(Modifier.weight(1f)) { F("Порция, мл", pt) { pt = it } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Мужской", "Женский").forEachIndexed { i, t -> FilterChip(sex == i, { sex = i }, { Text(t) }) }
                }
                Text("Активность", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Низкая", "Средняя", "Высокая").forEachIndexed { i, t -> FilterChip(act == i, { act = i }, { Text(t) }) }
                }
            }
            Panel {
                Text("Расписание", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) { F("Подъём", wk) { wk = it } }
                    Box(Modifier.weight(1f)) { F("Сон", sl) { sl = it } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Автоматически", "Своё время").forEachIndexed { i, t -> FilterChip(mode == i, { mode = i }, { Text(t) }) }
                }
                if (mode == 1) F("Время напоминаний, например 08:00, 11:30, 15:00", cs) { cs = it }
                F("Повторить, если пропустили (мин)", ag) { ag = it }
            }
            Panel {
                Text("Погода", fontWeight = FontWeight.Bold)
                F("Ключ API Яндекс Погоды (необязательно)", yk) { yk = it }
                Text("Без ключа или при ошибке погода берётся из Open-Meteo.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
            if (err.isNotEmpty()) Text(err, color = MaterialTheme.colorScheme.error)
            Button({
                val wi = w.toIntOrNull(); val hi = h.toIntOrNull(); val ai = a.toIntOrNull(); val pi = pt.toIntOrNull()
                val gi = ag.toIntOrNull(); val wm = parseTime(wk); val sm = parseTime(sl)
                val ct = cs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (wi == null || hi == null || ai == null || pi == null || pi < 50 || gi == null || gi < 1 || wm == null || sm == null) {
                    err = "Проверьте поля: числа и время в формате 07:30"
                } else if (mode == 1 && (ct.isEmpty() || ct.any { parseTime(it) == null })) {
                    err = "Укажите время напоминаний через запятую, например 08:00, 12:00"
                } else {
                    s.weight = wi; s.height = hi; s.age = ai; s.portion = pi; s.wake = wm; s.sleep = sm
                    s.sex = sex; s.act = act; s.mode = mode; s.custom = ct.joinToString(","); s.again = gi; s.ykey = yk.trim()
                    err = ""
                    Thread { s.fetchWeather() }.start()
                    Sched.schedule(ctx); edit = false; tick++
                }
            }, Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp)) { Text("Сохранить") }
        }
    }
}
