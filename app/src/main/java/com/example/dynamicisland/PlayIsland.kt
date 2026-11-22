package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
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
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tan
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
                (c.playPuff && PlayFx.puff > 0.02f) ||
                (c.playLiquid && (abs(liquidTarget() - PlayFx.liquidAngle) > 0.004f || abs(PlayFx.liquidV) > 0.01f))
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
                // Pil sıvısı: yüzey dünyaya göre düz kalmaya çalışır, az sönümlü yay olduğu için çalkalanır;
                // ada sallanınca / jöle titreyince de dalgalanır
                if (c.playLiquid) {
                    val la = 38f * (liquidTarget() - PlayFx.liquidAngle) - 2.4f * PlayFx.liquidV - PlayFx.jellyV * 0.6f
                    PlayFx.liquidV += la * dt
                    var ang = PlayFx.liquidAngle + PlayFx.liquidV * dt
                    if (abs(ang - liquidTarget()) < 0.002f && abs(PlayFx.liquidV) < 0.008f) { ang = liquidTarget(); PlayFx.liquidV = 0f }
                    PlayFx.liquidAngle = ang.coerceIn(-0.7f, 0.7f)
                }
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

    // ---------------------------------------------------------------- Bildirim yiyen ada
    LaunchedEffect(Unit) {
        PlayFx.events.collect { e ->
            if (e == PlayEvent.Eaten && (cfg.playEater || cfg.more.trashIsland)) {
                PlayFx.kick(-3.2f)                                          // yutkunma: önce uzar, sonra yayvanlaşır
                if (cfg.playEater && cfg.playEaterFull) PlayFx.fullness = (PlayFx.fullness + 1f).coerceAtMost(4f)
            }
        }
    }
    // Tokluk 20 dakikada geçer (dokununca hemen geçer: servis sıfırlar)
    LaunchedEffect(PlayFx.fullness) { if (PlayFx.fullness > 0f) { delay(20 * 60_000L); PlayFx.fullness = 0f } }
    val full by animateFloatAsState(if (s.playEater && s.playEaterFull) PlayFx.fullness else 0f, spring(dampingRatio = 0.45f, stiffness = 300f), label = "full")

    // ---------------------------------------------------------------- Çekiştirme
    val scope = rememberCoroutineScope()
    var drag by remember { mutableFloatStateOf(0f) }
    var dragging by remember { mutableStateOf(false) }
    val pullBack = remember { Animatable(0f) }
    val maxPull = ((s.windowPadDp() - 2f) * d).coerceAtLeast(0f)
    val pullOn = s.playPull && compact
    // Ada çekilirken açılırsa (örn. aşağı kaydırma müziği açtı) hareket yarıda kalır: gerilimi bırak
    LaunchedEffect(pullOn) { if (!pullOn && (dragging || pullBack.value != 0f)) { dragging = false; drag = 0f; pullBack.snapTo(0f) } }

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

    // ---------------------------------------------------------------- Pil sıvısı
    val liquidOn = s.playLiquid && compactIdle
    val liquidBubbles = remember { Animatable(0f) }
    LaunchedEffect(liquidOn, PlayFx.charging) {
        // Şarj olurken kabarcıklar yükselir (yalnızca bu sırada kare ister)
        while (liquidOn && PlayFx.charging) {
            liquidBubbles.snapTo(0f)
            liquidBubbles.animateTo(1f, tween(2_400, easing = LinearEasing))
        }
    }

    val flingOn = s.playFling && compactIdle
    val doodleOn = s.playDoodle && compactIdle
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(s.playDoodle) { if (s.playDoodle) DoodleStore.load(ctx) }
    // Ada savrulmuşken açılırsa / gizlenirse uçuş takılı kalmasın
    LaunchedEffect(PlayFx.flying) { if (PlayFx.flying) { delay(4_000L); PlayFx.flying = false } }

    val anyTransform = s.playPhysics || s.playPocket || s.playPuff || s.playMagnet || s.playEater || s.playPull || s.more.trashIsland
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (PlayFx.flying) 0f else 1f                    // savrulurken ada efekt penceresinde uçar
                if (!anyTransform) return@graphicsLayer
                val j = PlayFx.jelly
                val b = if (cfg.playPuff) PlayFx.puff else 0f
                val p = PlayFx.pocket
                val sinkX = if (cwPx > 0f) (chPx * 0.6f / cwPx).coerceIn(0.05f, 1f) else 1f
                translationX = PlayFx.slideX
                // Çekiştirme: üst kenar sabit, ada aşağı uzar (ters adada yukarı)
                val pl = if (dragging) drag else pullBack.value
                val pk = if (chPx > 0f) pl / chPx else 0f
                val dirY = if (cfg.invertedIsland) -1f else 1f
                translationY = PlayFx.dropY + dirY * pl / 2f
                scaleX = (1f + 0.16f * j + 0.10f * b + 0.02f * full - 0.12f * pk.coerceIn(0f, 1f)) * lerpF(1f, sinkX, p)
                scaleY = (1f - 0.13f * j - 0.16f * b + 0.04f * full + pk) * lerpF(1f, 0.6f, p)
                // Dönüşüm merkezi: kapalı adanın ortası (ters adada alttan)
                val pivotY = if (cfg.invertedIsland) size.height - padPx - chPx / 2f else padPx + chPx / 2f
                transformOrigin = TransformOrigin(0.5f, if (size.height > 0f) (pivotY / size.height).coerceIn(0f, 1f) else 0.5f)
            }
            .pointerInput(pullOn, maxPull) {
                if (!pullOn || maxPull <= 0f) return@pointerInput
                // Dokunuşlar yalnızca izlenir, tüketilmez: adanın kendi dokunma / kaydırma işleri aynen çalışır
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val sign = if (cfg.invertedIsland) -1f else 1f
                    var cur = 0f
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        val dy = (ch.position.y - down.position.y) * sign
                        // Lastik direnci: çektikçe zorlaşır, pencere payını aşmaz
                        cur = if (dy > 0f) maxPull * (1f - exp(-dy / (maxPull * 1.6f))) else (dy * 0.15f).coerceAtLeast(-chPx * 0.12f)
                        if (abs(cur) > 0.5f) { drag = cur; dragging = true }
                    }
                    if (dragging) {
                        val from = cur
                        scope.launch {
                            pullBack.snapTo(from)
                            dragging = false
                            drag = 0f
                            PlayFx.kick(-(from / maxPull).coerceIn(0f, 1f) * 4f)   // geri fırlarken jöle gibi sarsılır
                            pullBack.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = 620f))
                        }
                    }
                }
            }
            .pointerInput(flingOn) {
                if (!flingOn) return@pointerInput
                // Savurma: hızlı yatay kaydırma. Dokunuşlar tüketilmez.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    val vt = VelocityTracker()
                    vt.addPosition(down.uptimeMillis, down.position)
                    var lastPos = down.position
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        vt.addPosition(ch.uptimeMillis, ch.position)
                        lastPos = ch.position
                    }
                    val v = vt.calculateVelocity()
                    val moved = lastPos - down.position
                    if (abs(v.x) > 1_100.dp.toPx() && abs(v.x) > abs(v.y) * 1.2f && abs(moved.x) > 24.dp.toPx() && !PlayFx.flying) {
                        PlayFx.flingVx = v.x; PlayFx.flingVy = v.y
                        PlayFx.flying = true
                        PlayFx.emit(PlayEvent.Fling)
                    }
                }
            }
            .drawWithContent {
                drawContent()
                if (liquidOn) {
                    val cy = if (cfg.invertedIsland) size.height - padPx - chPx / 2f else padPx + chPx / 2f
                    drawLiquid(Offset(size.width / 2f, cy), cwPx, chPx, liquidBubbles.value)
                }
                if (doodleOn && DoodleStore.strokes.isNotEmpty()) {
                    val cy = if (cfg.invertedIsland) size.height - padPx - chPx / 2f else padPx + chPx / 2f
                    drawDoodle(DoodleStore.strokes, Offset(size.width / 2f, cy), cwPx, chPx)
                }
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
internal fun rememberFaceState(on: Boolean, compactIdle: Boolean): FaceState {
    val bus = LocalPlayBus.current
    val f = remember { FaceState() }
    val visible = on && compactIdle
    SideEffect { f.visible = visible }
    var pendingHappy by remember { mutableStateOf(false) }

    LaunchedEffect(on) {
        if (!on) return@LaunchedEffect
        bus.events.collect { e ->
            if (e == PlayEvent.Notif) pendingHappy = true
            if (e == PlayEvent.Party || e == PlayEvent.Water) launch {
                f.happyUntil = System.currentTimeMillis() + (if (e == PlayEvent.Party) 4_000L else 1_500L)
                delay(if (e == PlayEvent.Party) 4_050L else 1_550L)
                f.happyUntil = 0L
            }
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

internal fun DrawScope.drawFace(f: FaceState, c: Offset, cw: Float, ch: Float) {
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

// ================================================================ Pil sıvısı

/** Sıvı yüzeyinin dünyaya göre düz kalması için ekrandaki hedef açısı (radyan). Sağ taraf aşağıdaysa sıvı sağda toplanır. */
private fun liquidTarget(): Float = atan2(-PlayFx.gx, PlayFx.gy.coerceAtLeast(1f)).coerceIn(-0.6f, 0.6f)

internal fun DrawScope.drawLiquid(c: Offset, cw: Float, ch: Float, bubbleT: Float) {
    val pct = PlayFx.batteryPct.coerceIn(0f, 1f)
    val col = when {
        PlayFx.charging -> Color(0xFF30D158)
        pct < 0.2f -> Color(0xFFFF453A)
        pct < 0.5f -> Color(0xFFFFD60A)
        else -> Color(0xFF30D158)
    }
    val l = c.x - cw / 2f; val r = c.x + cw / 2f
    val t = c.y - ch / 2f; val b = c.y + ch / 2f
    val pill = Path().apply { addRoundRect(RoundRect(l, t, r, b, CornerRadius(ch / 2f))) }
    // Yüzey: pil seviyesi kadar yüksekte, eğime göre yatık, çalkalanırken hafif dalgalı
    val level = b - ch * (0.08f + 0.84f * pct)
    val slope = tan(PlayFx.liquidAngle)
    val wave = (abs(PlayFx.liquidV) * ch * 0.18f).coerceAtMost(ch * 0.12f)
    val surf = Path()
    val body = Path()
    val steps = 24
    for (i in 0..steps) {
        val x = l + (r - l) * i / steps
        val y = level - slope * (x - c.x) + wave * sin(i / steps.toFloat() * 2f * PI.toFloat() * 1.5f)
        if (i == 0) { surf.moveTo(x, y); body.moveTo(x, y) } else { surf.lineTo(x, y); body.lineTo(x, y) }
    }
    body.lineTo(r, b + ch); body.lineTo(l, b + ch); body.close()
    clipPath(pill) {
        drawPath(body, Brush.verticalGradient(listOf(col.copy(alpha = 0.92f), col.copy(alpha = 0.55f)), startY = level - ch * 0.3f, endY = b))
        // Şarjda yükselen kabarcıklar
        if (PlayFx.charging) {
            for (k in 0 until 5) {
                val ph = (bubbleT + k / 5f) % 1f
                val bx = l + cw * (0.15f + 0.7f * ((k * 0.37f) % 1f)) + sin(ph * 9f + k) * ch * 0.05f
                val by = b - ph * (b - level)
                drawCircle(Color.White.copy(alpha = 0.55f * (1f - ph)), ch * (0.035f + 0.02f * (k % 2)), Offset(bx, by))
            }
        }
        // Yüzeydeki ışık çizgisi
        drawPath(surf, Color.White.copy(alpha = 0.55f), style = Stroke(ch * 0.04f, cap = StrokeCap.Round))
    }
}

// ================================================================ Karalama tahtası

/** Kayıtlı çizimi boştaki adanın içine (kenarlardan biraz içeride) beyaz çizgilerle çizer. */
internal fun DrawScope.drawDoodle(strokes: List<List<Offset>>, c: Offset, cw: Float, ch: Float) {
    val l = c.x - cw / 2f + ch * 0.35f
    val t = c.y - ch / 2f + ch * 0.14f
    val w = cw - ch * 0.7f
    val h = ch * 0.72f
    val col = Color.White.copy(alpha = 0.92f)
    for (st in strokes) {
        if (st.size == 1) { drawCircle(col, ch * 0.04f, Offset(l + st[0].x * w, t + st[0].y * h)); continue }
        val p = Path()
        st.forEachIndexed { i, o -> if (i == 0) p.moveTo(l + o.x * w, t + o.y * h) else p.lineTo(l + o.x * w, t + o.y * h) }
        drawPath(p, col, style = Stroke(ch * 0.07f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}
