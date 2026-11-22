package com.example.dynamicisland

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/*
 * Oyuncak ada (3. parti): ziyaretçiler, zar, nefes egzersizi, gece lambası, gölge.
 * Hepsi adanın ALTINDAKİ tam ekran efekt penceresinde çizilir (PlayOverlay.PlayLayer çağırır).
 */

private val TAU_F = (2.0 * PI).toFloat()

/** Ekran açıkken sürekli kare isteyen efektler için zaman sayacı (yalnızca [on] iken çalışır). */
@Composable
internal fun rememberClock(on: Boolean, fpsCap: Int = 60): Float {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        var last = 0L
        val pause = if (fpsCap >= 60) 0L else (1000L / fpsCap - 16L).coerceAtLeast(0L)
        while (true) {
            withFrameNanos { now -> if (last != 0L) t += ((now - last) / 1e9f).coerceIn(0f, 0.1f); last = now }
            if (pause > 0L) delay(pause)
        }
    }
    return t
}

// ================================================================ Gölge

@Composable
internal fun ShadowFx(a: PlayAnchor) {
    if (!a.compactVisible) return
    Canvas(Modifier.fillMaxSize()) {
        val d = a.h / 37f
        val dx = PlayFx.tiltX * 7f * d
        val dy = (5f + PlayFx.tiltY * 2.5f) * d * a.dir()
        val cx = a.liveCx() + dx
        val cy = a.liveCy() + dy
        // Yumuşak gölge: dışa doğru sönen katmanlar (bulanıklık yerine, her Android sürümünde çalışır)
        for (i in 0 until 7) {
            val g = i * 2.2f * d
            val w = a.w + g * 2f; val h = a.h + g * 2f
            drawRoundRect(Color.Black.copy(alpha = 0.10f * (1f - i / 7f)), Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(h / 2f))
        }
    }
}

// ================================================================ Gece lambası

@Composable
internal fun NightLightFx(a: PlayAnchor, amount: Float, force: Boolean = false) {
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) { while (true) { delay(20_000L); minute = System.currentTimeMillis() / 60_000L } }
    val cal = java.util.Calendar.getInstance()
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val on = force || (PlayFx.charging && (hour >= 22 || hour < 7) && minute > 0L)   // force: ayarlar önizlemesi
    if (!on || !a.compactVisible) return
    val measurer = rememberTextMeasurer()
    Canvas(Modifier.fillMaxSize()) {
        val d = a.h / 37f
        val amt = amount.coerceIn(0.1f, 1f)
        val warm = Color(0xFFFFB25B)
        val cx = a.liveCx(); val cy = a.liveCy()
        // Sıcak hale
        for (i in 1..10) {
            val g = i * 3f * d
            val w = a.w + g * 2f; val h = a.h + g * 2f
            val k = 1f - i / 11f
            drawRoundRect(warm.copy(alpha = amt * 0.45f * k * k), Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(h / 2f), style = Stroke(3.2f * d))
        }
        // Büyük saat ve tarih
        val c = java.util.Calendar.getInstance()
        val time = "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
        val lay = measurer.measure(time, TextStyle(color = warm.copy(alpha = 0.55f + 0.4f * amt), fontSize = 64.sp, fontWeight = FontWeight.Light))
        val y = a.edgeY() + a.dir() * 46f * d - (if (a.inverted) lay.size.height.toFloat() else 0f)
        drawText(lay, topLeft = Offset(cx - lay.size.width / 2f, y))
        val days = arrayOf("Pazar", "Pazartesi", "Salı", "Çarşamba", "Perşembe", "Cuma", "Cumartesi")
        val sub = measurer.measure("${days[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]} · şarj %${(PlayFx.batteryPct * 100).toInt()}",
            TextStyle(color = warm.copy(alpha = 0.45f + 0.3f * amt), fontSize = 14.sp))
        drawText(sub, topLeft = Offset(cx - sub.size.width / 2f, y + a.dir() * (lay.size.height + 2f * d)))
    }
}

// ================================================================ Zar / yazı-tura

@Composable
internal fun DiceFx(a: PlayAnchor, mode: Int) {
    val bus = LocalPlayBus.current
    val roll = remember { Animatable(0f) }          // 0..1 yuvarlanma, sonra sabit
    val fade = remember { Animatable(0f) }
    var results by remember { mutableStateOf(intArrayOf(1, 1)) }
    var seed by remember { mutableIntStateOf(0) }
    // Çizim, zarın ATILDIĞI moda göre yapılır: sonuç ekrandayken ayardan mod değişirse (yazı-tura -> iki zar)
    // eski sonuç dizisi yeni moda uymuyordu ve uygulama çöküyordu (ArrayIndexOutOfBounds)
    var rolledMode by remember { mutableIntStateOf(mode) }
    val modeNow by rememberUpdatedState(mode)
    val measurer = rememberTextMeasurer()
    LaunchedEffect(Unit) {
        bus.events.collect { e ->
            if (e == PlayEvent.Dice) launch {
                val m = modeNow
                results = if (m == 2) intArrayOf(Random.nextInt(2)) else intArrayOf(Random.nextInt(1, 7), Random.nextInt(1, 7))
                rolledMode = m
                seed = Random.nextInt()
                fade.snapTo(1f); roll.snapTo(0f)
                roll.animateTo(1f, tween(950, easing = FastOutSlowInEasing))
                bus.kick(2.5f)
                bus.emit(PlayEvent.Say(when (m) {
                    2 -> if (results[0] == 0) "Yazı!" else "Tura!"
                    1 -> "${results[0]} + ${results[1]} = ${results[0] + results[1]}"
                    else -> "Zar: ${results[0]}!"
                }))
                delay(3_000L)
                fade.animateTo(0f, tween(400))
            }
        }
    }
    if (fade.value <= 0.001f) return
    Canvas(Modifier.fillMaxSize()) {
        val d = a.h / 37f
        val r = roll.value
        val al = fade.value
        val baseY = a.edgeY() + a.dir() * 64f * d
        // Düşüp sekme: adanın altından çıkar, iki kez seker
        val bounce = abs(cos(r * 2.5f * PI.toFloat())) * (1f - r) * 50f * d
        val y = baseY - a.dir() * bounce
        val mode = rolledMode
        if (mode == 2) {
            val coin = 22f * d
            val flip = abs(cos(r * 9f * PI.toFloat()))
            val faceShown = if (r < 1f) ((r * 9f).toInt() + seed) % 2 else results.getOrElse(0) { 0 }
            scale(max(flip, 0.06f), 1f, Offset(a.liveCx(), y)) {
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE08A), Color(0xFFD4A017)), center = Offset(a.liveCx() - coin * 0.3f, y - coin * 0.3f), radius = coin * 1.4f), coin, Offset(a.liveCx(), y), alpha = al)
                drawCircle(Color(0xFFB8860B), coin * 0.82f, Offset(a.liveCx(), y), alpha = al, style = Stroke(1.6f * d))
                val lay = measurer.measure(if (faceShown == 0) "Y" else "T", TextStyle(color = Color(0xFF7A5A00), fontSize = 20.sp, fontWeight = FontWeight.Bold))
                drawText(lay, topLeft = Offset(a.liveCx() - lay.size.width / 2f, y - lay.size.height / 2f), alpha = al)
            }
            if (r >= 1f) {
                val lay = measurer.measure(if (results.getOrElse(0) { 0 } == 0) "Yazı" else "Tura", TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                drawText(lay, topLeft = Offset(a.liveCx() - lay.size.width / 2f, y + a.dir() * (coin + 6f * d) - (if (a.inverted) lay.size.height.toFloat() else 0f)), alpha = al)
            }
            return@Canvas
        }
        val n = if (mode == 1) 2 else 1
        val size = 40f * d
        for (i in 0 until n) {
            val cx = a.liveCx() + (if (n == 2) (i - 0.5f) * (size + 14f * d) else 0f)
            val face = if (r < 1f) ((r * 14f).toInt() + seed + i * 3).mod(6) + 1 else results.getOrElse(i) { 1 }.coerceIn(1, 6)
            val ang = (1f - r) * (540f + i * 200f)
            rotate(ang, Offset(cx, y)) { drawDie(Offset(cx, y), size, face, al) }
        }
    }
}

private fun DrawScope.drawDie(c: Offset, size: Float, face: Int, alpha: Float) {
    val tl = Offset(c.x - size / 2f, c.y - size / 2f)
    drawRoundRect(Color.Black.copy(alpha = 0.25f * alpha), tl + Offset(0f, size * 0.06f), Size(size, size), CornerRadius(size * 0.22f))
    drawRoundRect(Color.White, tl, Size(size, size), CornerRadius(size * 0.22f), alpha = alpha)
    val p = size * 0.26f
    val pr = size * 0.085f
    val dot = Color(0xFF1C1C1E)
    val spots = when (face) {
        1 -> listOf(0 to 0)
        2 -> listOf(-1 to -1, 1 to 1)
        3 -> listOf(-1 to -1, 0 to 0, 1 to 1)
        4 -> listOf(-1 to -1, 1 to -1, -1 to 1, 1 to 1)
        5 -> listOf(-1 to -1, 1 to -1, 0 to 0, -1 to 1, 1 to 1)
        else -> listOf(-1 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 1 to 1)
    }
    for ((x, y) in spots) drawCircle(if (face == 1) Color(0xFFFF3B30) else dot, pr * (if (face == 1) 1.4f else 1f), Offset(c.x + x * p, c.y + y * p), alpha = alpha)
}

// ================================================================ Nefes egzersizi

@Composable
internal fun BreathFx(a: PlayAnchor, pattern: Int, minutes: Float, previewStart: Long = 0L) {
    // previewStart > 0: ayarlar önizlemesi; gerçek nefes oturumuna (BreathSession) ve evcile dokunmaz
    val preview = previewStart > 0L
    val active = preview || BreathSession.active
    val t = rememberClock(active)
    val measurer = rememberTextMeasurer()
    if (!active) { SideEffect { if (PlayFx.breath != 0f) PlayFx.breath = 0f }; return }
    val elapsed = (SystemClock.uptimeMillis() - (if (preview) previewStart else BreathSession.startedAt)) / 1000f
    if (t < 0f) return
    val total = minutes.coerceIn(0.5f, 10f) * 60f
    if (elapsed >= total && !preview) {
        LaunchedEffect(Unit) { BreathSession.stop(); PlayFx.emit(PlayEvent.Say("Harika, aferin!")) }
        return
    }
    val p = BreathSession.pattern(pattern)
    val cycle = p.sum()
    var x = elapsed % cycle
    var phase = 0
    while (phase < 3 && x >= p[phase]) { x -= p[phase]; phase++ }
    val dur = p[phase].coerceAtLeast(0.01f)
    val k = (x / dur).coerceIn(0f, 1f)
    val ease = 0.5f - 0.5f * cos(k * PI.toFloat())
    val b = when (phase) { 0 -> ease; 1 -> 1f; 2 -> 1f - ease; else -> 0f }
    SideEffect { if (!preview) PlayFx.breath = b }
    val label = when (phase) { 0 -> "Nefes al"; 1 -> "Tut"; 2 -> "Nefes ver"; else -> "Tut" }
    val left = (dur - x).toInt() + 1
    Canvas(Modifier.fillMaxSize()) {
        val d = a.h / 37f
        val cx = a.liveCx(); val cy = a.liveCy()
        val w = a.w * (1f + 0.9f * b)
        val h = a.h * (1f + 2.2f * b)
        val teal = Color(0xFF5EEAD4)
        // Işıltı
        for (i in 1..8) {
            val g = i * 3f * d
            val ww = w + g * 2f; val hh = h + g * 2f
            val kk = 1f - i / 9f
            drawRoundRect(teal.copy(alpha = (0.18f + 0.25f * b) * kk * kk), Offset(cx - ww / 2f, cy - hh / 2f), Size(ww, hh), CornerRadius(hh / 2f), style = Stroke(3f * d))
        }
        // Şişen ada (ada pencereden üstte kaldığı için ada bu kapsülün içinde büyüyormuş gibi görünür)
        drawRoundRect(a.color, Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(h / 2f))
        val ty = cy + a.dir() * (h / 2f + 18f * d)
        val l1 = measurer.measure(label, TextStyle(color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold))
        val l2 = measurer.measure("$left", TextStyle(color = teal, fontSize = 15.sp, fontWeight = FontWeight.Medium))
        val l3 = measurer.measure("Bitirmek için adaya dokun · ${((total - elapsed) / 60f).toInt() + 1} dk", TextStyle(color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp))
        val inv = if (a.inverted) -1f else 0f
        drawText(l1, topLeft = Offset(cx - l1.size.width / 2f, ty + inv * l1.size.height))
        drawText(l2, topLeft = Offset(cx - l2.size.width / 2f, ty + a.dir() * (l1.size.height + 2f * d) + inv * l2.size.height))
        drawText(l3, topLeft = Offset(cx - l3.size.width / 2f, ty + a.dir() * (l1.size.height + l2.size.height + 10f * d) + inv * l3.size.height))
    }
}



// ================================================================ Ada ziyaretçileri

@Composable
internal fun VisitorFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { VisitorStore.load(ctx) }
    val cur = VisitorStore.current
    var shown by remember { mutableStateOf<VisitorKind?>(null) }
    var pos by remember { mutableStateOf(Offset.Zero) }
    var facing by remember { mutableFloatStateOf(1f) }
    val catchAnim = remember { Animatable(0f) }
    var catchFrom by remember { mutableStateOf(Offset.Zero) }
    val live by rememberUpdatedState(a)
    LaunchedEffect(cur) { if (cur != null) shown = cur }
    // Yakalanınca: ziyaretçi adaya doğru küçülerek çekilir, ışıltı saçar
    LaunchedEffect(Unit) {
        bus.events.collect { e ->
            if (e is PlayEvent.Caught) launch {
                catchFrom = pos
                catchAnim.snapTo(0f)
                catchAnim.animateTo(1f, tween(450, easing = FastOutSlowInEasing))
                bus.kick(3.5f)
                shown = null
                catchAnim.snapTo(0f)
            }
        }
    }
    val active = shown != null
    val t = rememberClock(active)
    // Uçuş yolu: kenardan gelir, adanın yanında ~25 sn dolaşır, sonra uçup gider
    LaunchedEffect(shown) {
        val k = shown ?: return@LaunchedEffect
        val fromLeft = Random.nextBoolean()
        val start = SystemClock.uptimeMillis()
        var last = Offset.Zero
        while (shown == k) {
            withFrameNanos {
                val an = live
                val d = an.h / 37f
                val el = (SystemClock.uptimeMillis() - start) / 1000f
                val side = if (fromLeft) -1f else 1f
                val hover = Offset(an.liveCx() + side * (an.w / 2f + 34f * d), an.edgeY() + an.dir() * 38f * d)
                val wander = Offset(sin(el * 1.3f) * 22f * d + sin(el * 2.9f) * 6f * d, cos(el * 1.7f) * 12f * d)
                val enterFrom = Offset(if (fromLeft) -30f * d else an.screenW + 30f * d, hover.y + an.dir() * 120f * d)
                val p = when {
                    k == VisitorKind.Star -> {   // kayan yıldız: çapraz geçer, adanın yanında biraz parlar
                        val u = (el / 3f).coerceIn(0f, 1f)
                        val e2 = 1f - (1f - u) * (1f - u)
                        Offset(enterFrom.x + (hover.x - enterFrom.x) * e2, hover.y - an.dir() * 30f * d * (1f - e2)) + wander * 0.3f
                    }
                    el < 1.6f -> {
                        val u = el / 1.6f
                        val e2 = 1f - (1f - u) * (1f - u) * (1f - u)
                        enterFrom + (hover + wander - enterFrom) * e2
                    }
                    el < 25f -> hover + wander
                    else -> {
                        val u = ((el - 25f) / 1.6f).coerceIn(0f, 1f)
                        hover + wander + Offset(side * u * u * an.screenW, an.dir() * u * u * 200f * d)
                    }
                }
                if (abs(p.x - last.x) > 0.3f) facing = if (p.x > last.x) 1f else -1f
                last = p
                pos = p
                if (el > 26.6f && VisitorStore.current == k) { if (!bus.preview) VisitorStore.current = null; shown = null }
            }
        }
    }
    if (shown == null && catchAnim.value <= 0f) return
    val k = shown ?: return
    Canvas(Modifier.fillMaxSize()) {
        val d = a.h / 37f
        val ca = catchAnim.value
        val target = Offset(a.liveCx(), a.liveCy())
        val p = if (ca > 0f) catchFrom + (target - catchFrom) * ca else pos
        if (p == Offset.Zero) return@Canvas
        val sc = 1f - 0.8f * ca
        // Yakalanırken ışıltı
        if (ca > 0f) {
            for (i in 0 until 8) {
                val ang = i * TAU_F / 8f + ca * 3f
                val rr = (6f + 26f * ca) * d
                drawCircle(Color(0xFFFFE9A8).copy(alpha = 1f - ca), 1.6f * d, Offset(p.x + cos(ang) * rr, p.y + sin(ang) * rr))
            }
        }
        scale(sc * facing, sc, p) { drawVisitor(k, p, d, t) }
    }
}

internal fun DrawScope.drawVisitor(k: VisitorKind, c: Offset, d: Float, t: Float) {
    when (k) {
        VisitorKind.Butterfly, VisitorKind.GoldButterfly -> {
            val gold = k == VisitorKind.GoldButterfly
            val w1 = if (gold) Color(0xFFFFD54A) else Color(0xFF64B5FF)
            val w2 = if (gold) Color(0xFFFFA000) else Color(0xFFFF7EB6)
            val flap = 0.25f + 0.75f * abs(sin(t * 16f))
            for (s in intArrayOf(-1, 1)) {
                scale(flap, 1f, c) {
                    drawOval(Brush.linearGradient(listOf(w1, w2)), Offset(c.x + (if (s < 0) -9f else 0f) * d, c.y - 8f * d), Size(9f * d, 8f * d))
                    drawOval(w2, Offset(c.x + (if (s < 0) -7f else 0f) * d, c.y - 0.5f * d), Size(7f * d, 6f * d))
                }
            }
            drawLine(Color(0xFF2B2B2B), Offset(c.x, c.y - 6f * d), Offset(c.x, c.y + 5f * d), 1.6f * d, StrokeCap.Round)
            if (gold) for (i in 0 until 3) drawCircle(Color(0xFFFFF3B0).copy(alpha = 0.5f + 0.5f * sin(t * 6f + i)), 1f * d, Offset(c.x + (i - 1) * 7f * d, c.y + 9f * d))
        }
        VisitorKind.Bird -> {
            val wing = sin(t * 14f) * 35f
            drawOval(Color(0xFF8D6E4F), Offset(c.x - 7f * d, c.y - 4f * d), Size(13f * d, 8f * d))
            drawCircle(Color(0xFF8D6E4F), 3.6f * d, Offset(c.x + 6f * d, c.y - 4f * d))
            drawCircle(Color(0xFFF3E5D0), 2.4f * d, Offset(c.x + 0.5f * d, c.y + 0.5f * d))
            val beak = Path().apply { moveTo(c.x + 9f * d, c.y - 5f * d); lineTo(c.x + 12.5f * d, c.y - 3.8f * d); lineTo(c.x + 9f * d, c.y - 2.8f * d); close() }
            drawPath(beak, Color(0xFFFFA726))
            drawCircle(Color.Black, 0.9f * d, Offset(c.x + 7f * d, c.y - 4.8f * d))
            rotate(-wing, Offset(c.x - 1f * d, c.y - 2f * d)) { drawOval(Color(0xFF6D4C35), Offset(c.x - 8f * d, c.y - 5f * d), Size(9f * d, 4.5f * d)) }
        }
        VisitorKind.Ladybug -> {
            val wing = abs(sin(t * 20f))
            drawOval(Color.White.copy(alpha = 0.35f), Offset(c.x - 10f * d, c.y - 9f * d * wing), Size(8f * d, 6f * d))
            drawOval(Color.White.copy(alpha = 0.35f), Offset(c.x + 2f * d, c.y - 9f * d * wing), Size(8f * d, 6f * d))
            drawCircle(Color(0xFF1C1C1E), 3.2f * d, Offset(c.x + 6.5f * d, c.y))
            drawCircle(Color(0xFFE53935), 7f * d, c)
            drawLine(Color(0xFF1C1C1E), Offset(c.x, c.y - 7f * d), Offset(c.x, c.y + 7f * d), 0.9f * d)
            for ((dx, dy) in listOf(-3.5f to -3f, 3f to -3.2f, -3.2f to 2.8f, 3.4f to 2.6f)) drawCircle(Color(0xFF1C1C1E), 1.5f * d, Offset(c.x + dx * d, c.y + dy * d))
        }
        VisitorKind.Bee -> {
            val wing = abs(sin(t * 26f))
            drawOval(Color.White.copy(alpha = 0.5f), Offset(c.x - 5f * d, c.y - 6f * d - 3f * d * wing), Size(6f * d, 5f * d))
            drawOval(Color.White.copy(alpha = 0.5f), Offset(c.x - 1f * d, c.y - 6f * d - 3f * d * wing), Size(6f * d, 5f * d))
            drawOval(Color(0xFFFFC928), Offset(c.x - 7f * d, c.y - 4f * d), Size(14f * d, 8.5f * d))
            for (i in 0 until 2) drawRect(Color(0xFF1C1C1E), Offset(c.x + (-3f + i * 3.6f) * d, c.y - 4f * d), Size(1.8f * d, 8.5f * d))
            drawCircle(Color(0xFF1C1C1E), 2.6f * d, Offset(c.x + 7f * d, c.y + 0.2f * d))
        }
        VisitorKind.Firefly -> {
            val glow = 0.45f + 0.55f * (0.5f + 0.5f * sin(t * 3.2f))
            drawCircle(Brush.radialGradient(listOf(Color(0xCCD7FF6B), Color.Transparent), center = c, radius = 14f * d * glow), 14f * d * glow, c)
            drawOval(Color(0xFF3A3A2A), Offset(c.x - 3.5f * d, c.y - 2f * d), Size(7f * d, 4f * d))
            drawCircle(Color(0xFFEFFF8A), 2.2f * d * glow, Offset(c.x - 3f * d, c.y))
        }
        VisitorKind.Star -> {
            drawLine(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.7f)), Offset(c.x - 40f * d, c.y - 12f * d), c), Offset(c.x - 40f * d, c.y - 12f * d), c, 2f * d, StrokeCap.Round)
            val tw = 0.7f + 0.3f * sin(t * 9f)
            val r = 6f * d * tw
            drawCircle(Brush.radialGradient(listOf(Color(0xAAFFF6C8), Color.Transparent), center = c, radius = r * 2.4f), r * 2.4f, c)
            drawLine(Color.White, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.6f * d, StrokeCap.Round)
            drawLine(Color.White, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 1.6f * d, StrokeCap.Round)
        }
        VisitorKind.Ufo -> {
            translate(0f, sin(t * 2.4f) * 1.5f * d) {
                drawPath(Path().apply {
                    moveTo(c.x - 5f * d, c.y + 3f * d); lineTo(c.x + 5f * d, c.y + 3f * d); lineTo(c.x + 14f * d, c.y + 30f * d); lineTo(c.x - 14f * d, c.y + 30f * d); close()
                }, Brush.verticalGradient(listOf(Color(0x5594F0FF), Color.Transparent), startY = c.y, endY = c.y + 30f * d))
                drawOval(Color(0xFF9AE6FF).copy(alpha = 0.85f), Offset(c.x - 5f * d, c.y - 7f * d), Size(10f * d, 8f * d))
                drawOval(Color(0xFFB0B7C3), Offset(c.x - 12f * d, c.y - 3f * d), Size(24f * d, 7f * d))
                for (i in 0 until 4) {
                    val on = ((t * 4f).toInt() + i) % 4 == 0
                    drawCircle(if (on) Color(0xFFFFE066) else Color(0xFF6B7280), 1.1f * d, Offset(c.x + (-7.5f + i * 5f) * d, c.y + 0.5f * d))
                }
            }
        }
    }
}
