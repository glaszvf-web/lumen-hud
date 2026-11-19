package com.lumen.hud

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.TrafficStats
import android.os.BatteryManager
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
//  J.A.R.V.I.S. HUD — 移动端全息终端仪表
//  三屏：CORE 仪表盘 / TERMINAL 终端 / THEME 主题
// ============================================================

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { JarvisApp(this) }
    }
}

// ---------- 主题 ----------

data class HudTheme(
    val id: String,
    val label: String,
    val primary: Color,
    val dim: Color,
    val warn: Color
)

val THEMES = listOf(
    HudTheme("arc", "ARC REACTOR", Color(0xFF00E5FF), Color(0xFF0A3A42), Color(0xFFFF5252)),
    HudTheme("amber", "IRON LEGACY", Color(0xFFFFB300), Color(0xFF4A3510), Color(0xFFFF5252)),
    HudTheme("red", "CODE RED", Color(0xFFFF1744), Color(0xFF4A0E1C), Color(0xFFFFD600))
)

// ---------- 遥测数据 ----------

data class SystemStats(
    val battery: Int,
    val charging: Boolean,
    val battTempC: Float,
    val memPct: Int,
    val memTotalGb: Float,
    val storagePct: Int,
    val storageFreeGb: Float,
    val cpuPct: Int,
    val cpuCores: Int,
    val cpuFreqMhz: Int,
    val netRxKBs: Int,
    val netTxKBs: Int,
    val uptimeMs: Long
)

object StatsCollector {
    private var prevTotal = 0L
    private var prevIdle = 0L
    private var prevRx = 0L
    private var prevTx = 0L
    private var prevNetAt = 0L

    fun read(ctx: Context): SystemStats {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val charging = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ==
            BatteryManager.BATTERY_STATUS_CHARGING
        val battTemp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f

        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val memPct = if (mi.totalMem > 0) ((mi.totalMem - mi.availMem) * 100 / mi.totalMem).toInt() else 0
        val memGb = mi.totalMem / (1024f * 1024f * 1024f)

        val stat = StatFs(Environment.getDataDirectory().path)
        val stTotal = stat.totalBytes
        val stFree = stat.availableBytes
        val stPct = if (stTotal > 0) (((stTotal - stFree) * 100) / stTotal).toInt() else 0
        val stFreeGb = stFree / (1024f * 1024f * 1024f)

        var cpu = 0
        try {
            val parts = File("/proc/stat").bufferedReader().readLine().trim().split(Regex("\\s+"))
            val idle = parts[4].toLong() + parts[5].toLong()
            val total = parts.drop(1).sumOf { it.toLong() }
            if (prevTotal > 0) {
                val dTotal = total - prevTotal
                val dIdle = idle - prevIdle
                if (dTotal > 0) cpu = ((1f - dIdle.toFloat() / dTotal) * 100).toInt().coerceIn(0, 100)
            }
            prevTotal = total
            prevIdle = idle
        } catch (_: Exception) { }

        val cores = Runtime.getRuntime().availableProcessors()
        var freq = 0
        try {
            val line = File("/sys/devices/system/cpu/cpu0/cpufreq/scaling_cur_freq").readText().trim()
            freq = (line.toLong() / 1000).toInt()
        } catch (_: Exception) { }

        val now = SystemClock.elapsedRealtime()
        var rxK = 0; var txK = 0
        try {
            val rx = TrafficStats.getTotalRxBytes()
            val tx = TrafficStats.getTotalTxBytes()
            if (prevNetAt > 0) {
                val dt = ((now - prevNetAt) / 1000f).coerceAtLeast(0.5f)
                rxK = ((rx - prevRx) / 1024f / dt).toInt().coerceAtLeast(0)
                txK = ((tx - prevTx) / 1024f / dt).toInt().coerceAtLeast(0)
            }
            prevRx = rx; prevTx = tx; prevNetAt = now
        } catch (_: Exception) { }

        return SystemStats(
            battery = level.coerceIn(0, 100), charging = charging, battTempC = battTemp,
            memPct = memPct, memTotalGb = memGb,
            storagePct = stPct, storageFreeGb = stFreeGb,
            cpuPct = cpu, cpuCores = cores, cpuFreqMhz = freq,
            netRxKBs = rxK, netTxKBs = txK, uptimeMs = SystemClock.elapsedRealtime()
        )
    }
}

object TerminalLog {
    val lines = mutableStateListOf<Pair<String, String>>() // time, text
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var seq = 0

    fun stamp(text: String) {
        val tag = if (text.contains("WARN")) "!!" else ">>"
        lines.add(0, tag to "${fmt.format(Date())}  $text")
        if (lines.size > 200) lines.removeRange(200, lines.size)
    }

    fun ambient() {
        seq++
        val text = when (seq % 10) {
            0 -> "MEM SWEEP COMPLETE — 0 ANOMALIES"
            1 -> "NEURAL CACHE DEFRAGGED [%.1fGB FREED]".format((seq % 7) * 0.35f)
            2 -> "PACKAGE INTEGRITY VERIFIED"
            3 -> "AMBIENT SENSOR POLL OK (%d Hz)".format(120)
            4 -> "ARC REACTOR OUTPUT NOMINAL @ %.2f GJ".format(3.7f + (seq % 5) * 0.1f)
            5 -> "TELEMETRY PACKET #%d SEALED".format(seq)
            6 -> "ROTATING SESSION KEY ... OK"
            7 -> "LATENCY PROBE: %.1f ms — EXCELLENT".format(2.8f + (seq % 4) * 0.4f)
            8 -> "GC HEURISTIC RETUNED (GENERATION %d)".format(seq % 3 + 1)
            else -> "DARK WATCH: NO INTRUSIONS DETECTED"
        }
        stamp(text)
    }
}

// ---------- 根组件 ----------

@Composable
fun JarvisApp(activity: MainActivity) {
    val prefs = remember { activity.getSharedPreferences("jarvis_hud", Context.MODE_PRIVATE) }
    var theme by remember {
        mutableStateOf(THEMES[prefs.getInt("theme_idx", 0).coerceIn(0, THEMES.size - 1)])
    }
    var page by remember { mutableStateOf("boot") }

    // 主题持久化：切换即落盘，重启锁定
    LaunchedEffect(theme) { prefs.edit().putInt("theme_idx", THEMES.indexOfFirst { it.id == theme.id }).apply() }

    // 语音问候：首次进入主界面时系统TTS播报（无TTS引擎则静默）
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    var spoken by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val engine = TextToSpeech(activity) { }
        tts = engine
        onDispose { engine.stop(); engine.shutdown() }
    }
    LaunchedEffect(page) {
        if (page != "boot" && !spoken) {
            spoken = true
            delay(600)
            try {
                tts?.apply {
                    language = java.util.Locale.CHINA
                    setSpeechRate(1.05f)
                    speak("欢迎回来。所有系统正常。", TextToSpeech.QUEUE_FLUSH, null, "jarvis_greet")
                }
            } catch (_: Exception) { }
        }
    }

    LaunchedEffect(page) {
        if (page != "boot") {
            while (true) { delay(4200); TerminalLog.ambient() }
        }
    }

    val dark = Color(0xFF04070C)
    Box(
        Modifier
            .fillMaxSize()
            .background(dark)
    ) {
        ScanlineOverlay(theme)
        when (page) {
            "boot" -> BootScreen(theme) {
                TerminalLog.stamp("BOOT SEQUENCE COMPLETE — ALL SYSTEMS NOMINAL")
                page = "core"
            }
            else -> Column(Modifier.fillMaxSize()) {
                TopBar(theme, page)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (page) {
                        "core" -> CoreScreen(theme, activity)
                        "term" -> TerminalScreen(theme, onSelectTheme = { theme = it })
                        "sensor" -> SensorScreen(theme)
                        "theme" -> ThemeScreen(theme, onSelect = { theme = it })
                    }
                }
                BottomBar(theme, page, onSelect = { page = it })
            }
        }
    }
}

// ---------- 启动序列 ----------

val BOOT_LINES = listOf(
    "LUMEN KERNEL v9.3.0",
    "BUILD 20261027 // COMPOSE RUNTIME",
    "",
    "INITIALIZING ARC REACTOR INTERFACE ...... OK",
    "MOUNTING NEURAL CORE .................... OK",
    "SYNCING SYSTEM TELEMETRY ................ OK",
    "ENCRYPTING LOCAL UPLINK ................. OK",
    "CALIBRATING HOLOGRAPHIC LATTICE ......... OK",
    "",
    "IDENTITY CONFIRMED: OPERATOR",
    "ALL SYSTEMS NOMINAL — WELCOME BACK."
)

@Composable
fun BootScreen(theme: HudTheme, onDone: () -> Unit) {
    var shown by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        for (i in BOOT_LINES.indices) {
            delay(if (BOOT_LINES[i].isEmpty()) 120L else 280L)
            if (BOOT_LINES[i].isNotEmpty()) TerminalLog.stamp("BOOT: ${BOOT_LINES[i]}")
            shown = i + 1
        }
        delay(700)
        onDone()
    }
    Column(
        Modifier.fillMaxSize().padding(28.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("◢◤ 启明 LUMEN ◥", color = theme.primary, fontSize = 20.sp,
            fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("黎明前最亮的星 // INTELLIGENT CORE", color = theme.primary.copy(alpha = 0.55f),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        Spacer(Modifier.height(28.dp))
        BOOT_LINES.take(shown).forEach { line ->
            Text(
                if (line.isEmpty()) " " else "▸ $line",
                color = if (line.contains("WELCOME") || line.contains("OPERATOR")) theme.warn
                        else if (line.endsWith("OK")) theme.primary
                        else theme.primary.copy(alpha = 0.75f),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 17.sp
            )
        }
        Spacer(Modifier.height(16.dp))
        Text(if (shown < BOOT_LINES.size) "LOADING${".".repeat(shown % 4)}" else "STANDBY",
            color = theme.primary.copy(alpha = 0.6f), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
    }
}

// ---------- 顶栏 / 底栏 ----------

@Composable
fun TopBar(theme: HudTheme, page: String) {
    var now by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val f = SimpleDateFormat("yyyy.MM.dd  HH:mm:ss", Locale.US)
        while (true) { now = f.format(Date()); delay(1000) }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text("启明 LUMEN", color = theme.primary, fontSize = 15.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(
                when (page) { "core" -> "SYSTEM CORE"; "term" -> "COMMAND LOG"; "sensor" -> "MOTION MATRIX"; else -> "INTERFACE CONFIG" },
                color = theme.primary.copy(alpha = 0.5f), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace
            )
        }
        Text(now, color = theme.primary.copy(alpha = 0.8f), fontSize = 11.sp,
            fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun BottomBar(theme: HudTheme, page: String, onSelect: (String) -> Unit) {
    val items = listOf("core" to "◆ CORE", "term" to "≡ TERM", "sensor" to "◉ SENS", "theme" to "✦ THEME")
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { (id, label) ->
            val active = page == id
            Box(
                Modifier
                    .weight(1f)
                    .height(40.dp)
                    .background(
                        if (active) theme.primary.copy(alpha = 0.16f) else Color.Transparent,
                        RoundedCornerShape(4.dp)
                    )
                    .border(
                        1.dp,
                        if (active) theme.primary else theme.primary.copy(alpha = 0.25f),
                        RoundedCornerShape(4.dp)
                    )
                    .clickable { onSelect(id) },
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (active) theme.primary else theme.primary.copy(alpha = 0.55f),
                    fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
        }
    }
}

// ---------- 核心仪表盘 ----------

@Composable
fun CoreScreen(theme: HudTheme, ctx: Context) {
    var stats by remember { mutableStateOf<SystemStats?>(null) }
    val cpuHistory = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (true) {
            val s = StatsCollector.read(ctx)
            stats = s
            cpuHistory.add(s.cpuPct.toFloat())
            if (cpuHistory.size > 48) cpuHistory.removeAt(0)
            delay(1000)
        }
    }

    val s = stats ?: return
    Column(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Gauge(theme, "POWER", "${s.battery}%", if (s.charging) "CHARGING" else "ON BATT",
                s.battery / 100f, Modifier.weight(1f),
                primaryOverride = if (s.battery < 20) theme.warn else theme.primary)
            Gauge(theme, "MEMORY", "${s.memPct}%", "%.1f GB".format(s.memTotalGb),
                s.memPct / 100f, Modifier.weight(1f))
            Gauge(theme, "STORAGE", "${s.storagePct}%", "%.0f GB FREE".format(s.storageFreeGb),
                s.storagePct / 100f, Modifier.weight(1f))
        }
        Spacer(Modifier.height(14.dp))
        CpuPanel(theme, cpuHistory.toList())
        ArcReactor(theme, Modifier.weight(1f))
        Spacer(Modifier.height(14.dp))
        InfoGrid(theme, s)
    }
}

@Composable
fun Gauge(
    theme: HudTheme,
    label: String,
    value: String,
    sub: String,
    frac: Float,
    modifier: Modifier,
    primaryOverride: Color? = null
) {
    val target = if (frac.isNaN()) 0f else frac.coerceIn(0f, 1f)
    var anim by remember { mutableStateOf(0f) }
    val sweep by animateFloatAsState(target, tween(900), label = "gauge")
    LaunchedEffect(Unit) { anim = 1f }
    val shown = target * anim
    val color = primaryOverride ?: theme.primary
    val ringSize = 104.dp

    Box(modifier.height(160.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(ringSize)) {
                    val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    val inset = stroke.width
                    val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
                    val topLeft = Offset(inset, inset)
                    // 刻度点
                    for (i in 0 until 36) {
                        rotate(i * 10f, pivot = center) {
                            drawLine(
                                color.copy(alpha = 0.25f),
                                Offset(center.x, inset * 0.3f),
                                Offset(center.x, inset * 0.3f + 3.dp.toPx()),
                                strokeWidth = 1.5f
                            )
                        }
                    }
                    drawArc(theme.dim, -225f, 270f, false, topLeft, arcSize, style = stroke)
                    drawArc(
                        Brush.sweepGradient(
                            0f to color.copy(alpha = 0.35f), 0.9f to color, 1f to color,
                            center = center
                        ),
                        -225f, 270f * shown, false, topLeft, arcSize, style = stroke
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(value, color = color, fontSize = 17.sp,
                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(sub, color = theme.primary.copy(alpha = 0.5f), fontSize = 7.5.sp,
                        fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(label, color = theme.primary.copy(alpha = 0.75f), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun CpuPanel(theme: HudTheme, history: List<Float>) {
    Column(
        Modifier.fillMaxWidth().background(theme.primary.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
            .border(1.dp, theme.primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("CORE LOAD / CPU", color = theme.primary.copy(alpha = 0.75f), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(history.lastOrNull()?.let { "$it%" } ?: "--", color = theme.primary,
                fontSize = 11.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(74.dp)) {
            if (history.size < 2) return@Canvas
            val w = size.width
            val h = size.height
            // 网格
            for (gy in 0..3) {
                drawLine(theme.primary.copy(alpha = 0.12f), Offset(0f, h * gy / 3f), Offset(w, h * gy / 3f), 1f)
            }
            val stepX = w / (history.size - 1).coerceAtLeast(1)
            val path = Path()
            val fill = Path()
            history.forEachIndexed { i, v ->
                val x = i * stepX
                val y = h - (v / 100f) * (h - 6f) - 3f
                if (i == 0) { path.moveTo(x, y); fill.moveTo(x, h); fill.lineTo(x, y) }
                else { path.lineTo(x, y); fill.lineTo(x, y) }
            }
            fill.lineTo(w, h); fill.close()
            drawPath(fill, Brush.verticalGradient(
                listOf(theme.primary.copy(alpha = 0.28f), theme.primary.copy(alpha = 0.02f)),
                startY = 0f, endY = h))
            drawPath(path, theme.primary, style = Stroke(2f, cap = StrokeCap.Round))
            // 末端光点
            history.lastOrNull()?.let { v ->
                drawCircle(theme.primary, 3.5f, Offset(w, h - (v / 100f) * (h - 6f) - 3f))
            }
        }
    }
}

@Composable
fun ArcReactor(theme: HudTheme, modifier: Modifier) {
    val trans = rememberInfiniteTransition(label = "reactor")
    val spin by trans.animateFloat(0f, 360f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "spin")
    val spin2 by trans.animateFloat(360f, 0f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "spin2")
    val pulse by trans.animateFloat(0.45f, 1f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "pulse")
    // 点击反应环：触发重新校准脉冲波 + 写入终端日志
    var calib by remember { mutableStateOf(0) }
    var wave by remember { mutableStateOf(1f) }
    LaunchedEffect(calib) {
        if (calib > 0) {
            androidx.compose.animation.core.animate(0f, 1f, animationSpec = tween(900)) { v, _ -> wave = v }
        }
    }
    Box(
        modifier.fillMaxWidth().clickable {
            calib++
            TerminalLog.stamp("RECALIBRATION CYCLE #${"%03d".format(calib)} COMPLETE")
        },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.size(230.dp)) {
            if (calib > 0) {
                drawCircle(
                    theme.primary.copy(alpha = (1f - wave) * 0.55f),
                    radius = size.minDimension * (0.3f + 0.4f * wave),
                    style = Stroke(3f)
                )
            }
            rotate(spin) {
                drawArc(theme.primary.copy(alpha = 0.65f * pulse), 0f, 300f, false, style = Stroke(3f, cap = StrokeCap.Round))
                drawArc(theme.primary.copy(alpha = 0.3f), 180f, 60f, false, style = Stroke(1.5f))
            }
            rotate(spin2) {
                drawArc(theme.primary.copy(alpha = 0.55f), 0f, 130f, false, style = Stroke(7f, cap = StrokeCap.Round))
                for (i in 0 until 12) {
                    rotate(i * 30f) {
                        drawLine(
                            theme.primary.copy(alpha = 0.28f),
                            Offset(center.x, size.height * 0.13f),
                            Offset(center.x, size.height * 0.17f), 2f
                        )
                    }
                }
            }
            drawCircle(theme.primary.copy(alpha = 0.16f * pulse), size.minDimension * 0.17f, center)
            drawCircle(theme.primary.copy(alpha = 0.6f), style = Stroke(2f))
        }
    }
}

@Composable
fun InfoGrid(theme: HudTheme, s: SystemStats) {
    val up = s.uptimeMs / 1000
    val upStr = if (up >= 86400) "%dD %02d:%02d".format(up / 86400, (up % 86400) / 3600, (up % 3600) / 60)
                else "%02d:%02d:%02d".format(up / 3600, (up % 3600) / 60, up % 60)
    val freqStr = if (s.cpuFreqMhz > 0) "${s.cpuFreqMhz} MHz" else "MICRO-GRID"
    val rows = listOf(
        "UPTIME" to upStr,
        "KERNEL" to "ANDROID 15 // API 35",
        "NODE" to "LUMEN-01 // SECURE",
        "SILICON" to "${s.cpuCores} CORES // $freqStr",
        "THERMAL" to "%.1f °C".format(s.battTempC),
        "UPLINK" to "▼%d ▲%d KB/s".format(s.netRxKBs, s.netTxKBs),
        "REACTOR" to if (s.charging) "INTAKE MODE" else "SELF-SUSTAINING"
    )
    Column(
        Modifier.fillMaxWidth().background(theme.primary.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
            .border(1.dp, theme.primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        rows.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp),
                horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = theme.primary.copy(alpha = 0.45f), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
                Text(v, color = theme.primary.copy(alpha = 0.85f), fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ---------- 终端屏 ----------

private val JOKES = listOf(
    "这一天应当有25小时 — 正在向宇宙申请",
    "检测到凡人软件正在占用您的注意力",
    "本机不存在bug，只有未发布的特性",
    "已为您预订明天的好运",
    "反应堆输出尚有余量，梦想可以再大一点"
)

@Composable
fun TerminalScreen(theme: HudTheme, onSelectTheme: (HudTheme) -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    LaunchedEffect(TerminalLog.lines.size) {
        if (TerminalLog.lines.isNotEmpty()) listState.animateScrollToItem(0)
    }
    // 本地玩具命令解析器：只做字符串匹配写日志/切主题，无任何系统调用
    fun handleCommand(raw: String) {
        val cmd = raw.trim()
        if (cmd.isEmpty()) return
        TerminalLog.stamp("CMD> $cmd")
        val parts = cmd.lowercase().split(Regex("\\s+"))
        when (parts[0]) {
            "help", "帮助" -> TerminalLog.stamp("CMDS: help | status | scan | clear | joke | whoami | theme arc|amber|red")
            "status", "状态" -> {
                val s = StatsCollector.read(ctx)
                TerminalLog.stamp("BATT ${s.battery}% ${if (s.charging) "CHARGING" else "ON BATT"} | MEM ${s.memPct}% | CPU ${s.cpuPct}% @ ${s.cpuCores} CORES")
            }
            "scan", "扫描" -> scope.launch {
                listOf("SWEEPING SUBNET ...", "3 NODES FOUND — ALL FRIENDLY", "SCAN COMPLETE: 0 THREATS")
                    .forEachIndexed { i, t -> delay(600L + i * 500L); TerminalLog.stamp(t) }
            }
            "clear", "清屏" -> TerminalLog.lines.clear()
            "joke", "笑话" -> TerminalLog.stamp(JOKES.random())
            "whoami" -> TerminalLog.stamp("OPERATOR — 本机管理员")
            "version" -> TerminalLog.stamp("LUMEN HUD v3.0 // COMPOSE RUNTIME")
            "theme", "主题" -> {
                val t = THEMES.find { it.id == parts.getOrNull(1) }
                if (t != null) { onSelectTheme(t); TerminalLog.stamp("INTERFACE PRESET → ${t.label}") }
                else TerminalLog.stamp("!! UNKNOWN PRESET — TRY: theme arc|amber|red")
            }
            else -> TerminalLog.stamp("!! UNRECOGNIZED COMMAND — TRY 'HELP'")
        }
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f).padding(horizontal = 16.dp, vertical = 8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(TerminalLog.lines) { (time, text) ->
                Row {
                    Text(time, color = theme.primary.copy(alpha = 0.4f), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text,
                        color = if (text.startsWith("!!")) theme.warn else theme.primary.copy(alpha = 0.85f),
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        lineHeight = 14.sp
                    )
                }
            }
        }
        // 快捷命令芯片：一键执行，免打字
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf(
                "HELP" to "help", "STATUS" to "status", "SCAN" to "scan",
                "JOKE" to "joke", "RED" to "theme red"
            ).forEach { (label, cmd) ->
                Text(
                    label,
                    color = theme.primary.copy(alpha = 0.85f),
                    fontSize = 8.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .border(1.dp, theme.primary.copy(alpha = 0.5f), RoundedCornerShape(3.dp))
                        .clickable { handleCommand(cmd) }
                        .padding(horizontal = 7.dp, vertical = 5.dp)
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                .height(42.dp)
                .background(theme.primary.copy(alpha = 0.06f), RoundedCornerShape(4.dp))
                .border(1.dp, theme.primary.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("> ", color = theme.primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            androidx.compose.foundation.text.BasicTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = theme.primary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                ),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Send
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = { handleCommand(input); input = "" }
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(theme.primary),
                modifier = Modifier.weight(1f)
            )
        }
    }
}

// ---------- 主题屏 ----------

@Composable
fun ThemeScreen(current: HudTheme, onSelect: (HudTheme) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("SELECT INTERFACE PRESET", color = current.primary.copy(alpha = 0.6f),
            fontSize = 10.sp, fontFamily = FontFamily.Monospace)
        THEMES.forEach { t ->
            val active = t.id == current.id
            Row(
                Modifier.fillMaxWidth().height(64.dp)
                    .background(t.primary.copy(alpha = if (active) 0.14f else 0.05f), RoundedCornerShape(6.dp))
                    .border(if (active) 2.dp else 1.dp, if (active) t.primary else t.primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                    .clickable { onSelect(t) }
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Canvas(Modifier.size(30.dp)) {
                    drawCircle(t.primary.copy(alpha = 0.25f))
                    drawCircle(t.primary, style = Stroke(3f, cap = StrokeCap.Round))
                    drawCircle(t.primary, 4f)
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(t.label, color = if (active) t.primary else t.primary.copy(alpha = 0.7f),
                        fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(
                        when (t.id) { "arc" -> "默认 / 冷静蓝"; "amber" -> "经典钢铁侠配色"; else -> "警报级 · 高对比" },
                        color = t.primary.copy(alpha = 0.45f), fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(Modifier.weight(1f))
                if (active) Text("● ACTIVE", color = t.primary, fontSize = 9.sp,
                    fontFamily = FontFamily.Monospace)
            }
        }
        Spacer(Modifier.weight(1f))
        Text("LUMEN HUD v3.0 // STANDBY",
            color = current.primary.copy(alpha = 0.35f), fontSize = 8.sp,
            fontFamily = FontFamily.Monospace)
    }
}

// ---------- 传感器雷达屏 ----------

@Composable
fun SensorScreen(theme: HudTheme) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val ax = remember { mutableStateListOf<Float>() }
    val ay = remember { mutableStateListOf<Float>() }
    val az = remember { mutableStateListOf<Float>() }
    var heading by remember { mutableStateOf(0f) }
    var hasSensor by remember { mutableStateOf<Boolean?>(null) }

    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (acc == null) {
            hasSensor = false
            onDispose { }
        } else {
            hasSensor = true
            val g = FloatArray(3)
            val m = FloatArray(3)
            val rotM = FloatArray(9)
            val incM = FloatArray(9)
            val ori = FloatArray(3)
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    when (e.sensor.type) {
                        Sensor.TYPE_ACCELEROMETER -> {
                            e.values.copyInto(g)
                            ax.add(e.values[0]); ay.add(e.values[1]); az.add(e.values[2])
                            if (ax.size > 96) { ax.removeAt(0); ay.removeAt(0); az.removeAt(0) }
                        }
                        Sensor.TYPE_MAGNETIC_FIELD -> e.values.copyInto(m)
                    }
                    if (SensorManager.getRotationMatrix(rotM, incM, g, m)) {
                        SensorManager.getOrientation(rotM, ori)
                        heading = ((Math.toDegrees(ori[0].toDouble()) + 360.0) % 360.0).toFloat()
                    }
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) {}
            }
            sm.registerListener(listener, acc, SensorManager.SENSOR_DELAY_UI)
            if (mag != null) sm.registerListener(listener, mag, SensorManager.SENSOR_DELAY_UI)
            onDispose { sm.unregisterListener(listener) }
        }
    }

    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("NAV COMPASS // MOTION MATRIX", color = theme.primary.copy(alpha = 0.75f), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("${heading.toInt()}°", color = theme.primary, fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        // 罗盘：环随方位角旋转，红针指向北
        Canvas(Modifier.size(132.dp)) {
            val stroke = Stroke(3f, cap = StrokeCap.Round)
            drawCircle(theme.primary.copy(alpha = 0.2f), style = stroke)
            drawCircle(theme.primary.copy(alpha = 0.12f), style = Stroke(1f))
            rotate(-heading) {
                rotate(0f) {
                    drawPath(Path().apply {
                        moveTo(center.x, 8f); lineTo(center.x - 7f, 22f); lineTo(center.x + 7f, 22f); close()
                    }, theme.warn)
                }
                for (i in 0 until 24) {
                    rotate(i * 15f) {
                        val major = i % 6 == 0
                        drawLine(
                            theme.primary.copy(alpha = if (major) 0.85f else 0.3f),
                            Offset(center.x, 26f),
                            Offset(center.x, if (major) 38f else 32f),
                            if (major) 2.5f else 1.2f
                        )
                    }
                }
            }
            // 中心固定航向标
            drawPath(Path().apply {
                moveTo(center.x, center.y - 12f); lineTo(center.x - 6f, center.y + 8f); lineTo(center.x + 6f, center.y + 8f); close()
            }, theme.primary.copy(alpha = 0.9f))
            drawCircle(theme.primary.copy(alpha = 0.6f), 3f, center)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("MOTION MATRIX", color = theme.primary.copy(alpha = 0.75f), fontSize = 9.sp,
                fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text("%.2f g".format(az.lastOrNull() ?: 0f), color = theme.primary,
                fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(150.dp)
                .background(theme.primary.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                .border(1.dp, theme.primary.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
        ) {
            val w = size.width; val h = size.height
            for (gy in 0..4) drawLine(theme.primary.copy(alpha = 0.12f), Offset(0f, h * gy / 4f), Offset(w, h * gy / 4f), 1f)
            drawLine(theme.primary.copy(alpha = 0.3f), Offset(0f, h / 2f), Offset(w, h / 2f), 1f)
            fun trace(data: List<Float>, color: Color, width: Float) {
                if (data.size < 2) return
                val stepX = w / 95f
                val p = Path()
                data.forEachIndexed { i, v ->
                    val x = w - (data.size - 1 - i) * stepX
                    val y = h / 2f - (v / 12f) * (h / 2f - 6f)
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                drawPath(p, color, style = Stroke(width, cap = StrokeCap.Round))
            }
            trace(ax.toList(), theme.primary.copy(alpha = 0.9f), 2.2f)
            trace(ay.toList(), theme.primary.copy(alpha = 0.55f), 1.8f)
            trace(az.toList(), theme.warn.copy(alpha = 0.8f), 1.8f)
        }
        Spacer(Modifier.height(10.dp))
        val fmt = { v: Float -> "%+.2f".format(v) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("X" to fmt(ax.lastOrNull() ?: 0f), "Y" to fmt(ay.lastOrNull() ?: 0f), "Z" to fmt(az.lastOrNull() ?: 0f))
                .forEach { (k, v) ->
                    Text("$k $v g", color = theme.primary.copy(alpha = 0.75f), fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace)
                }
        }
        Spacer(Modifier.weight(1f))
        Text(
            when {
                hasSensor == false -> "NO MOTION SENSOR ON THIS DEVICE"
                (ax.size < 5) -> "CALIBRATING ... MOVE THE DEVICE"
                else -> "TILT TO STEER THE COMPASS // SHAKE TO BEND THE MATRIX"
            },
            color = theme.primary.copy(alpha = 0.5f), fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- 扫描线 + 漂浮粒子背景 ----------

@Composable
fun ScanlineOverlay(theme: HudTheme) {
    // 粒子漂移相位：90秒一个循环
    val drift by rememberInfiniteTransition(label = "drift").animateFloat(
        0f, 1f, infiniteRepeatable(tween(90000, easing = LinearEasing)), label = "phase"
    )
    Canvas(Modifier.fillMaxSize()) {
        val gap = 7.dp.toPx()
        var y = 0f
        while (y < size.height) {
            drawLine(theme.primary.copy(alpha = 0.035f), Offset(0f, y), Offset(size.width, y), 1f)
            y += gap
        }
        // 24 颗微光粒子：固定伪随机底位 + 缓慢漂移 + 呼吸闪烁
        for (i in 0 until 24) {
            val baseX = kotlin.math.abs(kotlin.math.sin(i * 12.9898) * 43758.5453) % 1.0
            val baseY = kotlin.math.abs(kotlin.math.sin(i * 78.233) * 12543.123) % 1.0
            val speed = 0.008 + (i % 5) * 0.006
            val px = (baseX + kotlin.math.sin(drift * 6.283 + i) * 0.02) % 1.0
            val py = (baseY + drift * speed) % 1.0
            val twinkle = 0.5 + 0.5 * kotlin.math.sin(drift * 6.283 * 3 + i * 1.7)
            drawCircle(
                theme.primary.copy(alpha = (0.10f + 0.16f * twinkle.toFloat())),
                radius = (1.2f + (i % 3)) * 1.4f,
                center = Offset((px * size.width).toFloat(), (py * size.height).toFloat())
            )
        }
        // 四角括号
        val len = 26.dp.toPx()
        val m = 10.dp.toPx()
        val c = theme.primary.copy(alpha = 0.5f)
        val st = Stroke(2f, cap = StrokeCap.Square)
        drawPath(Path().apply {
            moveTo(m, m + len); lineTo(m, m); lineTo(m + len, m) }, c, style = st)
        drawPath(Path().apply {
            moveTo(size.width - m - len, m); lineTo(size.width - m, m); lineTo(size.width - m, m + len) }, c, style = st)
        drawPath(Path().apply {
            moveTo(m, size.height - m - len); lineTo(m, size.height - m); lineTo(m + len, size.height - m) }, c, style = st)
        drawPath(Path().apply {
            moveTo(size.width - m - len, size.height - m); lineTo(size.width - m, size.height - m); lineTo(size.width - m, size.height - m - len) }, c, style = st)
    }
}
