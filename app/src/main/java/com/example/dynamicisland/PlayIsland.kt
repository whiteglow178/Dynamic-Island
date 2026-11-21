package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sign
import kotlin.random.Random

/*
 * Ada penceresinin içinde çalışan oyuncak efektler: ada içeriğini saran katman.
 *  - Fiziksel ada (kayma + jöle), cep hissi (düşme / kamera deliğine girme), dokununca savrulma -> graphicsLayer dönüşümü
 *  - Ada yüzü -> boştaki adanın üstüne gözler çizilir
 * Hiçbir efekt açık değilse katman hiçbir şey yapmaz (içerik aynen çizilir).
 */

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

@Composable
fun PlayIslandHost(
    settingsFlow: StateFlow<IslandSettings>,
    stateFlow: StateFlow<IslandUiState>,
    content: @Composable () -> Unit,
) {
    val s by settingsFlow.collectAsState()
    val u by stateFlow.collectAsState()
    val d = LocalDensity.current.density
    val cfg by rememberUpdatedState(s)

    val compact = !u.expanded && u.stack.isEmpty() && !u.swallow
    val compactIdle = compact && u.content is IslandContent.Idle && s.showIdle && !s.dotMode
    val compactNow by rememberUpdatedState(compact)

    val padPx = s.windowPadDp() * d
    val chPx = s.compactHeightDp * d
    val cwPx = compactWidthFor(s, false) * d
    val dropFrom by rememberUpdatedState(padPx + chPx * 1.4f)

    // ---------------------------------------------------------------- Fizik + jöle döngüsü
    // Hareket yokken kare istemez (120 ms'de bir yokla), böylece pil tüketmez.
    LaunchedEffect(Unit) {
        var x = 0f; var v = 0f
        var last = 0L
        while (true) {
            val c = cfg
            val range = if (c.playPhysics && compactNow) c.playPhysicsRangeDp * d else 0f
            val target = if (c.playPhysics) (-PlayFx.gx / 9.81f).coerceIn(-1f, 1f) * range * 1.6f else 0f
            val moving = abs(target.coerceIn(-range, range) - x) > 0.4f || abs(v) > 2f ||
                abs(PlayFx.jelly) > 0.002f || abs(PlayFx.jellyV) > 0.02f || PlayFx.shake > 0f ||
                (c.playPuff && PlayFx.puff > 0.02f)
            if (!moving) {
                if (PlayFx.slideX != x) PlayFx.slideX = x
                last = 0L
                delay(120)
                continue
            }
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = now
                // Sallama: jöle darbesi + rastgele yana itiş
                val sh = PlayFx.shake
                if (sh > 0f) {
                    PlayFx.shake = 0f
                    if (c.playPhysics && c.playJelly) {
                        val k = ((sh - 6f) / 10f).coerceIn(0.1f, 1f)
                        PlayFx.kick(4.5f * k)
                        v += (if (Random.nextBoolean()) 1f else -1f) * range * 7f * k
                        if (c.playFace && k > 0.45f) PlayFace.dizzyUntil = System.currentTimeMillis() + 1_100L
                    }
                }
                // Kayma: hedefe yay ile çekilir, kenarda sekip jöle gibi ezilir
                if (range > 0f || abs(x) > 0.01f) {
                    val a = 70f * (target - x) - 7f * v
                    v += a * dt
                    x += v * dt
                    if (range <= 0f) { x *= 0.85f; if (abs(x) < 0.3f) { x = 0f; v = 0f } }
                    else if (abs(x) > range) {
                        val impact = abs(v)
                        x = sign(x) * range
                        if (v * sign(x) > 0f) v = -v * 0.32f
                        if (impact > range * 1.2f && c.playJelly) PlayFx.kick((impact / (range * 6f)).coerceAtMost(3f))
                    }
                } else { x = 0f; v = 0f }
                PlayFx.slideX = x
                // Savrulma: ada titrer
                if (c.playPuff && PlayFx.puff > 0.02f) PlayFx.jellyV += (Random.nextFloat() - 0.5f) * 5f * PlayFx.puff
                // Jöle: sönümlü yay
                var j = PlayFx.jelly
                val ja = -700f * j - 9f * PlayFx.jellyV
                PlayFx.jellyV += ja * dt
                j += PlayFx.jellyV * dt
                if (abs(j) < 0.0015f && abs(PlayFx.jellyV) < 0.015f) { j = 0f; PlayFx.jellyV = 0f }
                PlayFx.jelly = j.coerceIn(-0.6f, 0.6f)
            }
        }
    }

    // ---------------------------------------------------------------- Dokununca savrulma
    // Adaya dokununca kısa bir "rüzgâr" darbesi: hızla yükselip yavaşça söner (toz, evcil ve gözler de bunu okur)
    val puff = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        PlayFx.events.collect { e ->
            if (e == PlayEvent.Puff && cfg.playPuff) launch {
                val peak = (0.35f + 0.65f * cfg.playPuffStrength).coerceIn(0f, 1f)
                puff.animateTo(peak, tween(90)) { PlayFx.puff = value }
                PlayFx.kick(3f * peak)
                puff.animateTo(0f, tween(560, easing = FastOutSlowInEasing)) { PlayFx.puff = value }
                PlayFx.puff = 0f
            }
        }
    }

    // ---------------------------------------------------------------- Cep hissi
    val drop = remember { Animatable(0f) }
    val sink = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        PlayFx.events.collect { e ->
            if (e == PlayEvent.ScreenOn && cfg.playPocket) {
                launch {
                    sink.snapTo(0f)
                    drop.snapTo(-dropFrom)
                    var landed = false
                    drop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 260f)) {
                        PlayFx.dropY = value
                        if (!landed && value >= 0f) { landed = true; PlayFx.kick(5f) }
                    }
                    PlayFx.dropY = 0f
                }
            }
        }
    }
    // Uzun süre boşta kalınca kamera deliğine girer; bir olay gelince fırlayıp çıkar
    LaunchedEffect(compactIdle, s.playPocket, s.playPocketIdleSec) {
        if (s.playPocket && compactIdle) {
            delay((s.playPocketIdleSec * 1000f).toLong())
            sink.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) { PlayFx.pocket = value }
        } else if (sink.value > 0.01f) {
            val wasIn = sink.value > 0.5f
            sink.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 380f)) { PlayFx.pocket = value }
            if (wasIn) PlayFx.kick(3.5f)
        }
        PlayFx.pocket = sink.value
    }

    // ---------------------------------------------------------------- Ada yüzü
    val face = rememberFaceState(s.playFace, compactIdle)

    val anyTransform = s.playPhysics || s.playPocket || s.playPuff || s.playMagnet
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                if (!anyTransform) return@graphicsLayer
                val j = PlayFx.jelly
                val b = if (cfg.playPuff) PlayFx.puff else 0f
                val p = PlayFx.pocket
                val sinkX = if (cwPx > 0f) (chPx * 0.6f / cwPx).coerceIn(0.05f, 1f) else 1f
                translationX = PlayFx.slideX
                translationY = PlayFx.dropY
                scaleX = (1f + 0.16f * j + 0.10f * b) * lerpF(1f, sinkX, p)
                scaleY = (1f - 0.13f * j - 0.16f * b) * lerpF(1f, 0.6f, p)
                // Dönüşüm merkezi: kapalı adanın ortası (ters adada alttan)
                val pivotY = if (cfg.invertedIsland) size.height - padPx - chPx / 2f else padPx + chPx / 2f
                transformOrigin = TransformOrigin(0.5f, if (size.height > 0f) (pivotY / size.height).coerceIn(0f, 1f) else 0.5f)
            }
            .drawWithContent {
                drawContent()
                if (face.visible) {
                    val cy = if (cfg.invertedIsland) size.height - padPx - chPx / 2f else padPx + chPx / 2f
                    drawFace(face, Offset(size.width / 2f, cy), cwPx, chPx)
                }
            }
    ) { content() }
}

// ================================================================ Ada yüzü

internal object PlayFace {
    @Volatile var dizzyUntil = 0L
}

internal class FaceState {
    var visible by mutableStateOf(false)
    val open = Animatable(1f)          // göz açıklığı (göz kırpma)
    val lookX = Animatable(0f)         // -1..1 bakış yönü
    var happyUntil by mutableLongStateOf(0L)
    var tick by mutableIntStateOf(0)   // dakikada bir: gece / pil durumu yeniden çizilsin
}

@Composable
private fun rememberFaceState(on: Boolean, compactIdle: Boolean): FaceState {
    val f = remember { FaceState() }
    val visible = on && compactIdle
    SideEffect { f.visible = visible }
    var pendingHappy by remember { mutableStateOf(false) }

    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        PlayFx.events.collect { e ->
            if (e == PlayEvent.Notif) pendingHappy = true
            if (e == PlayEvent.ScreenOn) launch {
                f.open.snapTo(0.05f); delay(260); f.open.animateTo(1f, tween(320))
            }
        }
    }
    // Bildirim bitip ada boşa dönünce kısa süre sevinir
    LaunchedEffect(compactIdle, pendingHappy) {
        if (compactIdle && pendingHappy) {
            pendingHappy = false
            f.happyUntil = System.currentTimeMillis() + 1_600L
            delay(1_650L)
            f.happyUntil = 0L
        }
    }
    // Göz kırpma
    LaunchedEffect(visible) {
        while (visible) {
            delay(Random.nextLong(2_200L, 5_200L))
            f.open.animateTo(0.06f, tween(70))
            f.open.animateTo(1f, tween(110))
            if (Random.nextFloat() < 0.2f) {       // ara sıra çift kırpma
                delay(120)
                f.open.animateTo(0.06f, tween(70)); f.open.animateTo(1f, tween(110))
            }
        }
    }
    // Etrafa bakınma
    LaunchedEffect(visible) {
        while (visible) {
            delay(Random.nextLong(1_800L, 4_200L))
            val to = if (Random.nextFloat() < 0.4f) 0f else Random.nextFloat() * 2f - 1f
            f.lookX.animateTo(to, tween(260, easing = FastOutSlowInEasing))
        }
    }
    LaunchedEffect(visible) {
        while (visible) { delay(60_000L); f.tick++ }
    }
    return f
}

private enum class Mood { Normal, Sleepy, Tired, Happy, Dizzy, Squint }

private fun currentMood(f: FaceState): Mood {
    val now = System.currentTimeMillis()
    if (PlayFx.puff > 0.15f) return Mood.Squint
    if (now < PlayFace.dizzyUntil) return Mood.Dizzy
    if (now < f.happyUntil) return Mood.Happy
    val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    if (PlayFx.charging || hour < 6) return Mood.Sleepy
    if (PlayFx.batteryLow) return Mood.Tired
    return Mood.Normal
}

private fun DrawScope.drawFace(f: FaceState, c: Offset, cw: Float, ch: Float) {
    if (f.tick < 0) return   // dakikalık yenileme: değeri okununca çizim her dakika tazelenir
    val mood = currentMood(f)
    val eyeW = ch * 0.20f
    val eyeH = ch * 0.46f
    val sep = min(cw * 0.24f, ch * 1.05f)
    val tilt = (-PlayFx.gx / 9.81f).coerceIn(-1f, 1f)
    val look = (f.lookX.value * 0.6f + tilt * 0.7f).coerceIn(-1f, 1f) * ch * 0.14f
    val white = Color(0xFFF4F4F4)
    val sw = ch * 0.085f
    for (side in intArrayOf(-1, 1)) {
        val ex = c.x + side * sep + look
        val ey = c.y
        when (mood) {
            Mood.Normal -> {
                val h = (eyeH * f.open.value).coerceAtLeast(eyeW * 0.35f)
                drawRoundRect(white, Offset(ex - eyeW / 2f, ey - h / 2f), Size(eyeW, h), CornerRadius(eyeW / 2f))
            }
            Mood.Tired -> {   // yarı kapalı: göz kapağı düz
                val h = (eyeH * 0.5f * f.open.value).coerceAtLeast(eyeW * 0.3f)
                drawRoundRect(white, Offset(ex - eyeW / 2f, ey - h * 0.2f), Size(eyeW, h), CornerRadius(eyeW / 2f))
            }
            Mood.Sleepy -> {  // kapalı gözler: aşağı bakan yay
                drawArc(white, 20f, 140f, false, Offset(ex - eyeW, ey - eyeH * 0.35f), Size(eyeW * 2f, eyeH * 0.5f),
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
            Mood.Happy -> {   // ^ ^
                drawArc(white, 200f, 140f, false, Offset(ex - eyeW, ey - eyeH * 0.15f), Size(eyeW * 2f, eyeH * 0.6f),
                    style = Stroke(sw, cap = StrokeCap.Round))
            }
            Mood.Dizzy -> {   // x x
                val r = eyeW * 0.8f
                drawLine(white, Offset(ex - r, ey - r), Offset(ex + r, ey + r), sw, StrokeCap.Round)
                drawLine(white, Offset(ex - r, ey + r), Offset(ex + r, ey - r), sw, StrokeCap.Round)
            }
            Mood.Squint -> {  // > <
                val r = eyeW * 0.8f
                val dir = -side.toFloat()
                drawLine(white, Offset(ex - dir * r, ey - r), Offset(ex + dir * r, ey), sw, StrokeCap.Round)
                drawLine(white, Offset(ex + dir * r, ey), Offset(ex - dir * r, ey + r), sw, StrokeCap.Round)
            }
        }
    }
    // Uyurken küçük "z"
    if (mood == Mood.Sleepy) {
        val zx = c.x + sep + eyeW * 1.6f
        val zy = c.y - ch * 0.12f
        val zs = ch * 0.16f
        val zc = white.copy(alpha = 0.7f)
        val zw = ch * 0.05f
        drawLine(zc, Offset(zx, zy), Offset(zx + zs, zy), zw, StrokeCap.Round)
        drawLine(zc, Offset(zx + zs, zy), Offset(zx, zy + zs), zw, StrokeCap.Round)
        drawLine(zc, Offset(zx, zy + zs), Offset(zx + zs, zy + zs), zw, StrokeCap.Round)
    }
}
