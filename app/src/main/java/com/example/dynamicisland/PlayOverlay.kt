package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Adanın ekrandaki yeri (px) ve efekt penceresinin ihtiyaç duyduğu bilgiler. Servis hesaplar. */
data class PlayAnchor(
    val cx: Float, val top: Float, val w: Float, val h: Float,
    val inverted: Boolean,
    val compactVisible: Boolean,     // kapalı ada ekranda (açık / yutma / yığın değil)
    val shown: Boolean,              // ada hiç gizli değil (uygulamada gizleme vb.)
    val screenW: Float, val screenH: Float,
    val color: Color,                // adanın (opak) rengi: damlalar aynı renkte çizilir
)

/**
 * Adanın ALTINDA duran, dokunuş almayan tam ekran pencere: evcil, mıknatıs damlaları, dalga, savrulma tozu.
 * Ada üstte kaldığı için damlalar ve evcilin elleri adanın kenarından "çıkıyor" gibi görünür.
 */
class PlayOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val anchor: StateFlow<PlayAnchor?>,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var tries = 0

    fun wanted(s: IslandSettings) = s.playPet || s.playMagnet || s.playRipple || s.playPuff || s.playEater || s.playDaylight ||
        s.playFling || s.playPlant || s.playSeason || s.playParty ||
        s.playVisitors || s.playDice || s.playBreath || s.playNightLight || s.playShadow || s.more.trashIsland ||
        s.more.notifFlow || s.more.notifBirds || s.more.notifShatter || (s.more.fxPets && s.more.petCompact)

    fun attach() {
        if (view != null || !wanted(settings.value)) return
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { PlayLayer(settings, anchor) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val p2 = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(v, p2); host = h2; lp = p2; added = true
            }
        }
        if (!added) {
            val p3 = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            added = runCatching { wm.addView(v, p3); host = wm; lp = p3 }.isSuccess
        }
        if (!added) { v.disposeComposition(); return }
        view = v
        tries = 0
        v.postDelayed(calibrate, 300)
    }

    /** Pencere ekranın en üstünden başlamıyorsa farkı kadar yukarı çeker (çizim ekran koordinatıyla yapılır). */
    private val calibrate = object : Runnable {
        override fun run() {
            val v = view ?: return
            val params = lp ?: return
            val h = host ?: return
            if (!v.isAttachedToWindow) return
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dy = loc[1]
            if (abs(dy) <= 1 || tries >= 3) return
            tries++
            params.y -= dy
            params.height = h.currentWindowMetrics.bounds.height() + abs(params.y)
            runCatching { h.updateViewLayout(v, params) }
            v.postDelayed(this, 250)
        }
    }

    fun detach() {
        val v = view ?: return
        v.removeCallbacks(calibrate)
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        // Sahipler kaldırılmaz: pencere eklenip hemen kaldırılırsa bekleyen ilk çizim sahibi bulamayıp çöker
        view = null; host = null; lp = null
    }
}

@Composable
/** [previewStart] > 0: ayarlar önizlemesi (gece lambası zorla görünür, nefes gerçek oturumdan bağımsız, fırlatma yok). */
internal fun PlayLayer(settings: StateFlow<IslandSettings>, anchorFlow: StateFlow<PlayAnchor?>, previewStart: Long = 0L) {
    val preview = previewStart > 0L
    val s by settings.collectAsState()
    val a by anchorFlow.collectAsState()
    val anchor = a ?: return
    if (!anchor.shown) return
    Box(Modifier.fillMaxSize()) {
        if (s.playShadow) ShadowFx(anchor)
        if (s.playNightLight) NightLightFx(anchor, s.playNightAmt, force = preview)
        if (s.playDaylight) DaylightFx(anchor, s.playDaylightAmt)
        if (s.playBreath) BreathFx(anchor, s.playBreathPattern, s.playBreathMin, previewStart)
        if (s.playSeason) SeasonFx(anchor, seasonOf(s.playSeasonMode))
        if (s.playPlant) PlantFx(anchor)
        if (s.playEater) EaterFx(anchor)
        if (s.more.trashIsland) TrashFx(anchor)
        if (s.more.notifShatter) NotifShatterFx(anchor)
        if (s.more.fxPets && s.more.petCompact) CompactPetsFx(anchor, s.more.fxPetCount, s.more.petGrowth)
        if (s.more.notifFlow) NotifFlowFx(anchor)
        if (s.more.notifBirds) NotifBirdFx(anchor)
        if (s.playRipple) RippleFx(anchor)
        if (s.playMagnet) MagnetFx(anchor)
        if (s.playPuff) PuffDustFx(anchor)
        if (s.playPet) PetFx(anchor, s.playPetTalk, s.playPlant)
        if (s.playVisitors) VisitorFx(anchor)
        if (s.playDice) DiceFx(anchor, s.playDiceMode)
        if (s.playParty) PartyFx(anchor)
        if (s.playFling && !preview) FlingFx(anchor)
    }
}

/** Adanın (fizik / cep kaymasıyla birlikte) anlık merkezi ve alt kenarı. */
internal fun PlayAnchor.liveCx() = cx + PlayFx.slideX
internal fun PlayAnchor.liveCy() = top + h / 2f + PlayFx.dropY
/** Adanın ekranın ortasına bakan kenarı (normalde alt, ters adada üst) ve o yönün işareti. */
internal fun PlayAnchor.edgeY() = if (inverted) top + PlayFx.dropY else top + h + PlayFx.dropY
internal fun PlayAnchor.dir() = if (inverted) -1f else 1f

// ================================================================ 8) Dalga

@Composable
private fun RippleFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val rings = remember { mutableStateListOf<Long>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        bus.events.collect { if (it == PlayEvent.Ripple && rings.size < 4) rings.add(SystemClock.uptimeMillis()) }
    }
    val busy = rings.isNotEmpty()
    LaunchedEffect(busy) {
        while (rings.isNotEmpty()) {
            withFrameNanos { now = SystemClock.uptimeMillis() }
            rings.removeAll { now - it > RIPPLE_MS + 2 * RIPPLE_GAP + 50 }
        }
    }
    if (!busy) return
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(a.liveCx(), a.liveCy())
        val maxR = hypot(a.screenW, a.screenH)
        val d = density
        for (t0 in rings) {
            for (k in 0..2) {
                val t = (now - t0 - k * RIPPLE_GAP) / RIPPLE_MS.toFloat()
                if (t <= 0f || t >= 1f) continue
                val e = 1f - (1f - t) * (1f - t) * (1f - t)
                val r = a.h * 0.6f + e * maxR
                val fade = (1f - t)
                val alpha = fade * fade * (0.55f - k * 0.12f)
                val sw = (3.2f - 2f * t) * d
                // Suyun kırılması: dışta parlak, hemen içinde koyu ince halka
                drawCircle(Color.White.copy(alpha = alpha), r, c, style = Stroke(sw))
                drawCircle(Color.Black.copy(alpha = alpha * 0.35f), (r - sw * 1.4f).coerceAtLeast(1f), c, style = Stroke(sw * 0.8f))
            }
        }
    }
}
private const val RIPPLE_MS = 1_500
private const val RIPPLE_GAP = 140

// ================================================================ 3) Mıknatıs

@Composable
private fun MagnetFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val charge = remember { Animatable(0f) }
    val buds = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        bus.events.collect { e ->
            when (e) {
                PlayEvent.Charger -> launch {
                    bus.kick(-2.5f)                                   // ada önce kabloya doğru uzar
                    charge.snapTo(0f)
                    charge.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                    delay(140)
                    charge.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 420f))
                    bus.kick(3.5f)                                    // damla geri girince ada sarsılır
                }
                PlayEvent.Buds -> launch {
                    buds.snapTo(0f)
                    buds.animateTo(1f, tween(650, easing = FastOutLinearInEasing))   // mıknatıs gibi hızlanarak gelir
                    bus.kick(3f)
                    buds.snapTo(0f)
                }
                else -> Unit
            }
        }
    }
    val cv = charge.value
    val bv = buds.value
    if (cv <= 0.003f && bv <= 0.003f) return
    Canvas(Modifier.fillMaxSize()) {
        val col = a.color
        val cx = a.liveCx()
        val dir = a.dir()
        val edge = a.edgeY()
        // ---- Şarj: adanın alt kenarından kabloya (ekranın altına) doğru uzayan sıvı damla
        if (cv > 0.003f) {
            val len = cv * a.screenH * 0.20f
            val r = a.h * 0.30f * (0.6f + 0.4f * cv)
            val dropY = edge + dir * len
            val w0 = a.h * 0.42f
            val wn = r * 0.45f
            val p = Path().apply {
                moveTo(cx - w0, edge - dir * 2f)
                cubicTo(cx - w0 * 0.6f, edge + dir * len * 0.25f, cx - wn, dropY - dir * (r + len * 0.2f), cx - wn, dropY)
                lineTo(cx + wn, dropY)
                cubicTo(cx + wn, dropY - dir * (r + len * 0.2f), cx + w0 * 0.6f, edge + dir * len * 0.25f, cx + w0, edge - dir * 2f)
                close()
            }
            drawPath(p, col)
            drawCircle(col, r, Offset(cx, dropY))
        }
        // ---- Kulaklık: iki yandan gelen iki damla adaya yapışıp içine girer
        if (bv > 0.003f) {
            val r = a.h * 0.28f
            val cy = a.liveCy()
            for (side in intArrayOf(-1, 1)) {
                val sx = if (side < 0) -r * 2f else a.screenW + r * 2f
                val sy = cy + dir * a.h * 1.4f
                val ex = cx + side * (a.w / 2f - r * 0.3f)
                val x = sx + (ex - sx) * bv
                val y = sy + (cy - sy) * bv
                val islandEdge = Offset(cx + side * (a.w / 2f - a.h * 0.15f), cy)
                val dist = hypot(x - islandEdge.x, y - islandEdge.y)
                val reach = a.h * 1.6f
                if (dist < reach) {   // sıvı köprü: yaklaştıkça kalınlaşır
                    val k = 1f - dist / reach
                    drawLine(col, islandEdge, Offset(x, y), r * 1.3f * k, StrokeCap.Round)
                }
                drawCircle(col, r, Offset(x, y))
            }
        }
    }
}

// ================================================================ 4) Dokununca savrulma: toz

private class Dust(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val size: Float)

@Composable
private fun PuffDustFx(a: PlayAnchor) {
    val parts = remember { ArrayList<Dust>() }
    var frame by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    val live by rememberUpdatedState(a)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            if (PlayFx.puff < 0.03f && parts.isEmpty()) { last = 0L; level = 0f; delay(100); continue }
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = now
                val b = PlayFx.puff
                level = b
                val a = live
                val d = a.h / 37f                                      // yaklaşık dp ölçeği (ada yüksekliğinden)
                // Yeni parçacıklar: adanın kenarı boyunca, şiddetle orantılı
                val n = (b * 6f).toInt() + if (Random.nextFloat() < b * 6f % 1f) 1 else 0
                repeat(n) {
                    val px = a.liveCx() + (Random.nextFloat() - 0.5f) * a.w * 0.9f
                    val spread = (px - a.liveCx()) / (a.w / 2f)
                    parts.add(
                        Dust(
                            px, a.edgeY(),
                            vx = spread * (120f + Random.nextFloat() * 160f) * d * (0.5f + b),
                            vy = a.dir() * (180f + Random.nextFloat() * 320f) * d * (0.4f + b),
                            life = 1f, size = (1.2f + Random.nextFloat() * 2f) * d,
                        )
                    )
                }
                val iter = parts.iterator()
                while (iter.hasNext()) {
                    val p = iter.next()
                    p.vx *= (1f - 1.6f * dt); p.vy *= (1f - 1.2f * dt)
                    p.x += p.vx * dt; p.y += p.vy * dt
                    p.life -= dt / 0.9f
                    if (p.life <= 0f) iter.remove()
                }
                if (parts.size > 400) parts.subList(0, parts.size - 400).clear()
                frame = now
            }
        }
    }
    if (level < 0f) return                 // (level okunur: savrulma başlayınca yeniden oluşturulur)
    Canvas(Modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas      // her karede yeniden çizilsin
        for (p in parts) drawCircle(Color.White.copy(alpha = (p.life * 0.75f).coerceIn(0f, 1f)), p.size, Offset(p.x, p.y))
    }
}

// ================================================================ 2) Ada evcili

@Composable
private fun PetFx(a: PlayAnchor, talk: Boolean, plantOn: Boolean) {
    val bus = LocalPlayBus.current
    val show = a.compactVisible && PlayFx.pocket < 0.5f
    val hide = remember { Animatable(if (show) 0f else 1f) }
    LaunchedEffect(show) { hide.animateTo(if (show) 0f else 1f, tween(if (show) 520 else 320, easing = FastOutSlowInEasing)) }
    val peek = remember { Animatable(0f) }
    // ---- Konuşma baloncuğu
    val measurer = rememberTextMeasurer()
    var say by remember { mutableStateOf<String?>(null) }
    val sayAnim = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val sayJob = remember { arrayOfNulls<Job>(1) }
    val talkNow by rememberUpdatedState(talk && show)
    fun speak(text: String) {
        if (!talkNow) return
        sayJob[0]?.cancel()
        say = text
        sayJob[0] = scope.launch {
            sayAnim.snapTo(0f)
            sayAnim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 500f))
            delay(2_400L)
            sayAnim.animateTo(0f, tween(220))
        }
    }
    LaunchedEffect(Unit) {
        bus.events.collect { e ->
            when (e) {
                PlayEvent.Notif -> {
                    launch {                                       // bildirim: heyecanla aşağı sarkıp geri tırmanır
                        peek.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                        peek.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 260f))
                    }
                    speak(listOf("Yeni bildirim!", "Bir şey geldi!", "Bak bak!").random())
                }
                PlayEvent.Party -> speak("Yaşasın!")
                PlayEvent.Water -> speak("Teşekkürler!")
                PlayEvent.Puff -> speak("Uçuyoruuum!")
                PlayEvent.Fling -> speak("Vuuuu!")
                is PlayEvent.Caught -> speak("Yakaladın: ${e.kind.title}!")
                is PlayEvent.Say -> speak(e.text)
                else -> Unit
            }
        }
    }
    // Ara sıra kendi kendine konuşur (duruma göre)
    LaunchedEffect(talk, show) {
        while (talk && show) {
            delay(Random.nextLong(60_000L, 120_000L))
            speak(petChatter(plantOn))
        }
    }
    if (hide.value >= 0.999f && !show) return
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) t += ((now - last) / 1e9f).coerceIn(0f, 0.1f)
                last = now
            }
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val time = t
        val h = a.h
        val R = h * 0.42f * (1f + 0.14f * PlayFx.breath)              // nefes egzersizinde evcil de nefes alır
        val dir = a.dir()
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val sleeping = PlayFx.charging || hour < 6
        val dancing = (!sleeping && PlayFx.musicPlaying) || now < PlayFx.partyUntil
        val partying = now < PlayFx.partyUntil
        val bored = !sleeping && !dancing && !partying && now - PlayFx.lastActivityMs > 10 * 60_000L
        val blow = PlayFx.puff

        // Askı noktası: adanın kenarında, ortanın biraz sağında
        val pivot = Offset(a.liveCx() + a.w * 0.22f, a.edgeY() - dir * 1f)
        val armLen = R * (if (bored) 1.25f else 0.55f) + R * 0.9f * peek.value
        val bob = when {
            dancing -> abs(sin(time * PI.toFloat() * 2f)) * R * 0.22f
            sleeping -> sin(time * 1.4f) * R * 0.04f
            else -> sin(time * 2.1f) * R * 0.05f
        }
        // Sallanma açısı: sakin sarkaç, dansta ritim, dokununca savrulma, jölede sarsılma
        var ang = when {
            dancing -> sin(time * PI.toFloat() * 2f) * 12f
            sleeping -> sin(time * 0.9f) * 3f
            else -> sin(time * 2f * PI.toFloat() / 3.2f) * 6f
        }
        ang += blow * 38f * (0.8f + 0.2f * sin(time * 25f)) + PlayFx.jelly * 60f - PlayFx.slideX / max(1f, a.w) * 40f
        // Saklanma: adanın içine tırmanır (pencere adanın altında olduğu için ada onu örter)
        val hideShift = hide.value * (armLen + R * 2.6f)
        val bodyC = Offset(pivot.x, pivot.y + dir * (armLen + R + bob - hideShift))

        val body = Color(0xFFF4EFE6)
        val shade = Color(0xFFD9D0C2)
        rotate(ang * dir, pivot) {
            // Kollar ve eller (eller adanın kenarını tutar, üst yarısı adanın altında kalır)
            for (side in intArrayOf(-1, 1)) {
                val hand = Offset(pivot.x + side * R * 0.5f, pivot.y)
                val shoulder = Offset(bodyC.x + side * R * 0.62f, bodyC.y - dir * R * 0.55f)
                drawLine(body, hand, shoulder, R * 0.30f, StrokeCap.Round)
                drawCircle(body, R * 0.2f, hand)
            }
            // Gövde (hafif gölgeli)
            drawCircle(
                Brush.radialGradient(listOf(body, body, shade), center = Offset(bodyC.x - R * 0.3f, bodyC.y - dir * R * 0.35f), radius = R * 1.5f),
                R, bodyC,
            )
            // Yanaklar
            val blush = Color(0xFFFF8FA3).copy(alpha = 0.35f)
            drawCircle(blush, R * 0.13f, Offset(bodyC.x - R * 0.48f, bodyC.y + dir * R * 0.18f))
            drawCircle(blush, R * 0.13f, Offset(bodyC.x + R * 0.48f, bodyC.y + dir * R * 0.18f))
            // Gözler
            val ink = Color(0xFF2B2B2B)
            val blink = (time % 4.3f) < 0.12f
            for (side in intArrayOf(-1, 1)) {
                val e = Offset(bodyC.x + side * R * 0.33f, bodyC.y - dir * R * 0.08f)
                when {
                    sleeping || blink -> drawArc(ink, if (dir > 0) 20f else 200f, 140f, false,
                        Offset(e.x - R * 0.16f, e.y - R * 0.12f), Size(R * 0.32f, R * 0.22f), style = Stroke(R * 0.07f, cap = StrokeCap.Round))
                    blow > 0.15f -> {                                    // > <
                        val r = R * 0.12f; val dx = -side * r
                        drawLine(ink, Offset(e.x - dx, e.y - r), Offset(e.x + dx, e.y), R * 0.07f, StrokeCap.Round)
                        drawLine(ink, Offset(e.x + dx, e.y), Offset(e.x - dx, e.y + r), R * 0.07f, StrokeCap.Round)
                    }
                    dancing -> drawArc(ink, if (dir > 0) 200f else 20f, 140f, false,
                        Offset(e.x - R * 0.16f, e.y - R * 0.08f), Size(R * 0.32f, R * 0.24f), style = Stroke(R * 0.07f, cap = StrokeCap.Round))
                    else -> {
                        val big = 1f + 0.35f * peek.value
                        val eh = R * (if (bored) 0.12f else 0.22f) * big
                        drawOval(ink, Offset(e.x - R * 0.08f * big, e.y - eh / 2f), Size(R * 0.16f * big, eh))
                    }
                }
            }
            // Ağız: sıkılınca esner, dansta gülümser
            val m = Offset(bodyC.x, bodyC.y + dir * R * 0.32f)
            when {
                bored && (time % 9f) < 1.4f -> drawOval(ink, Offset(m.x - R * 0.1f, m.y - R * 0.08f), Size(R * 0.2f, R * 0.18f))
                peek.value > 0.3f -> drawCircle(ink, R * 0.07f, m)
                else -> drawArc(ink, if (dir > 0) 20f else 200f, 140f, false, Offset(m.x - R * 0.12f, m.y - R * 0.09f),
                    Size(R * 0.24f, R * 0.14f), style = Stroke(R * 0.06f, cap = StrokeCap.Round))
            }
            // Uyurken "z"
            if (sleeping) {
                val zs = R * 0.3f
                val zx = bodyC.x + R * 0.9f
                val zy = bodyC.y - dir * R * 0.2f + dir * sin(time * 1.6f) * R * 0.1f
                val zc = Color.White.copy(alpha = 0.75f)
                val zw = R * 0.07f
                drawLine(zc, Offset(zx, zy), Offset(zx + zs, zy), zw, StrokeCap.Round)
                drawLine(zc, Offset(zx + zs, zy), Offset(zx, zy + zs), zw, StrokeCap.Round)
                drawLine(zc, Offset(zx, zy + zs), Offset(zx + zs, zy + zs), zw, StrokeCap.Round)
            }
        }
    }
    // Konuşma baloncuğu (evcilin yanında; sığmazsa solunda)
    val txt = say
    if (txt != null && sayAnim.value > 0.01f) {
        Canvas(Modifier.fillMaxSize()) {
            val R = a.h * 0.42f
            val dir = a.dir()
            val bodyX = a.liveCx() + a.w * 0.22f
            val bodyY = a.edgeY() + dir * R * 1.55f
            val lay = measurer.measure(txt, TextStyle(color = Color(0xFF1C1C1E), fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
            val pad = 7.dp.toPx()
            val bw = lay.size.width + pad * 2f
            val bh = lay.size.height + pad * 1.3f
            var bx = bodyX + R * 1.25f
            val leftSide = bx + bw > a.screenW - 6.dp.toPx()
            if (leftSide) bx = bodyX - R * 1.25f - bw
            val by = bodyY - bh / 2f
            val tailX = if (leftSide) bx + bw else bx
            scale(sayAnim.value, Offset(tailX, by + bh / 2f)) {
                val tail = Path().apply {
                    moveTo(tailX, by + bh * 0.35f)
                    lineTo(tailX + (if (leftSide) 1f else -1f) * R * 0.45f, by + bh * 0.62f)
                    lineTo(tailX, by + bh * 0.72f)
                    close()
                }
                drawPath(tail, Color.White.copy(alpha = 0.96f))
                drawRoundRect(Color.White.copy(alpha = 0.96f), Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2f))
                drawText(lay, topLeft = Offset(bx + pad, by + (bh - lay.size.height) / 2f))
            }
        }
    }
}

private fun petChatter(plantOn: Boolean): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        plantOn && PlantStore.thirst >= 1 -> "Çiçeği sula!"
        PlayFx.musicPlaying -> listOf("♪ ♫", "Bu şarkı güzel!", "♪").random()
        PlayFx.batteryLow -> "Acıktım…"
        PlayFx.charging -> "Zzz…"
        h in 6..9 -> "Günaydın!"
        h >= 23 || h < 5 -> "Uyku vakti…"
        System.currentTimeMillis() - PlayFx.lastActivityMs > 10 * 60_000L -> "Sıkıldım…"
        else -> listOf("Merhaba!", "Buradayım", "Nasılsın?", "Hey!", "Bugün güzel bir gün").random()
    }
}

// ================================================================ Bildirim yiyen ada

private class Morsel(val start: Long, val fromLeft: Boolean, val yFrac: Float, val hue: Int)

@Composable
private fun EaterFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val bites = remember { mutableStateListOf<Morsel>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        bus.events.collect {
            if (it == PlayEvent.Notif && bites.size < 3) {
                bites.add(Morsel(SystemClock.uptimeMillis(), Random.nextBoolean(), 0.28f + Random.nextFloat() * 0.2f, Random.nextInt(BITE_COLORS.size)))
            }
        }
    }
    val busy = bites.isNotEmpty()
    LaunchedEffect(busy) {
        while (bites.isNotEmpty()) {
            withFrameNanos { now = SystemClock.uptimeMillis() }
            val done = bites.filter { now - it.start >= EAT_MS }
            if (done.isNotEmpty()) {
                bites.removeAll(done)
                done.forEach { _ -> bus.emit(PlayEvent.Eaten) }      // ada yutkunur
            }
        }
    }
    if (!busy) return
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        val target = Offset(a.liveCx(), a.liveCy())
        for (m in bites) {
            val t = ((now - m.start) / EAT_MS.toFloat()).coerceIn(0f, 1f)
            val e = t * t * t                                     // emilir gibi: yavaş başlar, adaya yaklaştıkça hızlanır
            val r0 = 11f * d
            val start = Offset(if (m.fromLeft) -r0 * 2f else a.screenW + r0 * 2f, a.top + a.screenH * m.yFrac * a.dir())
            // Kavisli yol: önce yukarı süzülür, sonra adaya çekilir
            val ctrl = Offset((start.x + target.x) / 2f, start.y - a.dir() * a.screenH * 0.12f)
            val u = 1f - e
            val p = Offset(
                u * u * start.x + 2f * u * e * ctrl.x + e * e * target.x,
                u * u * start.y + 2f * u * e * ctrl.y + e * e * target.y,
            )
            val r = r0 * (1f - 0.45f * e)
            val col = BITE_COLORS[m.hue]
            // Adaya yaklaştıkça adaya doğru uzayan sıvı köprü (ada pencerenin üstünde olduğu için köprünün ucu adanın altına girer)
            val dist = (p - target).getDistance()
            val reach = a.h * 2.2f
            if (dist < reach) drawLine(a.color, target, p, r * 1.5f * (1f - dist / reach), StrokeCap.Round)
            drawCircle(Brush.radialGradient(listOf(Color.White, col), center = Offset(p.x - r * 0.35f, p.y - r * 0.35f), radius = r * 1.6f), r, p)
            drawCircle(Color.White.copy(alpha = 0.8f), r * 0.22f, Offset(p.x - r * 0.38f, p.y - r * 0.38f))
        }
    }
}
private const val EAT_MS = 750

// ---------------------------------------------------------------- Çöp kutusu ada
private class Trash(val start: Long, val icon: androidx.compose.ui.graphics.ImageBitmap?, val fromLeft: Boolean, val spin: Float)
private const val TRASH_MS = 720

/**
 * Silinen bildirimin simgesi, bildirim panelindeki yerine yakın bir noktadan dönerek ve küçülerek adaya uçar;
 * ada onu yutkunarak yer (Mevcut "bildirim yiyen ada" hareketinin tersi: gelen değil, silinen bildirim).
 */
@Composable
private fun TrashFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val items = remember { mutableStateListOf<Trash>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        bus.events.collect {
            if (it is PlayEvent.Trashed && items.size < 4) {
                items.add(Trash(SystemClock.uptimeMillis() + items.size * 90L, it.icon, Random.nextBoolean(), if (Random.nextBoolean()) 540f else -540f))
            }
        }
    }
    val busy = items.isNotEmpty()
    LaunchedEffect(busy) {
        while (items.isNotEmpty()) {
            withFrameNanos { now = SystemClock.uptimeMillis() }
            val done = items.filter { now - it.start >= TRASH_MS }
            if (done.isNotEmpty()) {
                items.removeAll(done)
                done.forEach { _ -> bus.emit(PlayEvent.Eaten) }      // ada yutkunur
            }
        }
    }
    if (!busy) return
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        val target = Offset(a.liveCx(), a.liveCy())
        for (m in items) {
            val t = ((now - m.start) / TRASH_MS.toFloat()).coerceIn(0f, 1f)
            if (now < m.start) continue
            val e = t * t * (3f - 2f * t)
            val start = Offset(a.cx + (if (m.fromLeft) -1f else 1f) * a.screenW * 0.22f, a.top + a.dir() * a.screenH * 0.22f)
            val ctrl = Offset((start.x + target.x) / 2f + (if (m.fromLeft) -1f else 1f) * a.screenW * 0.12f, (start.y + target.y) / 2f)
            val u = 1f - e
            val p = Offset(
                u * u * start.x + 2f * u * e * ctrl.x + e * e * target.x,
                u * u * start.y + 2f * u * e * ctrl.y + e * e * target.y,
            )
            val size = 40f * d * (1f - 0.8f * e)
            val alpha = if (t > 0.85f) (1f - t) / 0.15f else 1f
            withTransform({ rotate(m.spin * e, p) }) {
                val ic = m.icon
                if (ic != null) {
                    drawImage(
                        ic, dstOffset = androidx.compose.ui.unit.IntOffset((p.x - size / 2f).toInt(), (p.y - size / 2f).toInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(size.toInt().coerceAtLeast(1), size.toInt().coerceAtLeast(1)),
                        alpha = alpha.coerceIn(0f, 1f),
                    )
                } else {
                    drawCircle(Color(0xFFFF453A).copy(alpha = alpha.coerceIn(0f, 1f)), size / 2f, p)
                }
            }
            // Adaya yaklaşınca adaya doğru uzayan sıvı köprü (ada onu çekiyormuş gibi)
            val dist = (p - target).getDistance()
            val reach = a.h * 2.4f
            if (dist < reach) drawLine(a.color, target, p, size * 0.5f * (1f - dist / reach), StrokeCap.Round)
        }
    }
}
private val BITE_COLORS = listOf(Color(0xFF64D2FF), Color(0xFF30D158), Color(0xFFFF9F0A), Color(0xFFFF375F), Color(0xFFBF5AF2))

// ================================================================ Gün ışığı rengi

/** Günün saatine göre hale rengi: gece lacivert, şafak turuncu, gün beyaz, gün batımı pembe-mor. */
private val DAY_KEYS = listOf(
    0f to Color(0xFF3A5BFF), 5f to Color(0xFF3A5BFF), 6.5f to Color(0xFFFF8A3D), 8.5f to Color(0xFFFFE2A8),
    12f to Color(0xFFDDF2FF), 16.5f to Color(0xFFFFB347), 18.5f to Color(0xFFFF5FA2), 20f to Color(0xFF8E5CFF),
    21.5f to Color(0xFF3A5BFF), 24f to Color(0xFF3A5BFF),
)

private fun daylightColor(hour: Float): Color {
    for (i in 0 until DAY_KEYS.size - 1) {
        val (h0, c0) = DAY_KEYS[i]; val (h1, c1) = DAY_KEYS[i + 1]
        if (hour <= h1) return androidx.compose.ui.graphics.lerp(c0, c1, ((hour - h0) / (h1 - h0)).coerceIn(0f, 1f))
    }
    return DAY_KEYS.last().second
}

@Composable
private fun DaylightFx(a: PlayAnchor, amount: Float) {
    var minute by remember { mutableLongStateOf(System.currentTimeMillis() / 60_000L) }
    LaunchedEffect(Unit) { while (true) { delay(60_000L); minute = System.currentTimeMillis() / 60_000L } }
    if (!a.compactVisible) return
    Canvas(Modifier.fillMaxSize()) {
        if (minute < 0L) return@Canvas
        val cal = java.util.Calendar.getInstance()
        val hour = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f
        val col = daylightColor(hour)
        val night = hour < 5.5f || hour > 21f
        val amt = amount.coerceIn(0f, 1f) * (if (night) 0.6f else 1f)
        val d = density
        val cx = a.liveCx(); val cy = a.liveCy()
        // Yumuşak hale: dışa doğru sönen halkalar (ada üstte olduğu için iç kısım görünmez)
        val rings = 8
        for (i in 1..rings) {
            val grow = i * 2.6f * d
            val w = a.w + grow * 2f; val h = a.h + grow * 2f
            val k = 1f - i / (rings + 1f)
            drawRoundRect(
                col.copy(alpha = amt * 0.55f * k * k),
                Offset(cx - w / 2f, cy - h / 2f), Size(w, h),
                androidx.compose.ui.geometry.CornerRadius(h / 2f),
                style = Stroke(3f * d),
            )
        }
    }
}

// ================================================================ Adayı fırlat

/**
 * Ada yana savrulunca gerçek ada gizlenir, burada onun kopyası uçar: ekran kenarlarına çarpıp seker, yerçekimiyle
 * düşer, ~1,8 sn sonra (ya da yavaşlayınca) yaylanarak yerine döner ve gerçek ada yeniden görünür.
 */
@Composable
private fun FlingFx(a: PlayAnchor) {
    val live by rememberUpdatedState(a)
    var pos by remember { mutableStateOf(Offset.Zero) }
    var rot by remember { mutableFloatStateOf(0f) }
    val flying = PlayFx.flying
    LaunchedEffect(flying) {
        if (!flying) return@LaunchedEffect
        val an = live
        val d = an.h / 37f
        val home = Offset(an.cx, an.top + an.h / 2f)
        var p = home
        val maxV = 4_000f * d
        var vx = PlayFx.flingVx.coerceIn(-maxV, maxV)
        var vy = PlayFx.flingVy.coerceIn(-maxV, maxV) + 300f * d
        var r = 0f
        var last = 0L
        var t = 0f
        val hw = an.w / 2f; val hh = an.h / 2f
        // 1) Serbest uçuş
        while (t < 1.8f && (t < 0.4f || hypot(vx, vy) > 120f * d)) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.033f)
                last = now; t += dt
                vy += 1_600f * d * dt                           // yerçekimi
                vx *= (1f - 0.4f * dt); vy *= (1f - 0.2f * dt)
                var nx = p.x + vx * dt; var ny = p.y + vy * dt
                if (nx - hw < 0f) { nx = hw; vx = abs(vx) * 0.72f; PlayFx.kick(2f) }
                if (nx + hw > an.screenW) { nx = an.screenW - hw; vx = -abs(vx) * 0.72f; PlayFx.kick(2f) }
                if (ny - hh < 0f) { ny = hh; vy = abs(vy) * 0.72f; PlayFx.kick(2f) }
                if (ny + hh > an.screenH) { ny = an.screenH - hh; vy = -abs(vy) * 0.6f; vx *= 0.9f; PlayFx.kick(2.5f) }
                p = Offset(nx, ny)
                r += vx * dt * 0.08f
                pos = p; rot = r
            }
        }
        // 2) Yerine dönüş
        val from = p; val r0 = r
        val back = Animatable(0f)
        back.animateTo(1f, tween(560, easing = FastOutSlowInEasing)) {
            val k = value
            // Kavisli dönüş: önce biraz aşağı, sonra adanın yerine
            val mid = Offset((from.x + home.x) / 2f, max(from.y, home.y) + an.h * 2f)
            val u = 1f - k
            pos = Offset(u * u * from.x + 2f * u * k * mid.x + k * k * home.x, u * u * from.y + 2f * u * k * mid.y + k * k * home.y)
            rot = r0 * u
        }
        PlayFx.flying = false
        PlayFx.kick(4f)
    }
    if (!flying) return
    Canvas(Modifier.fillMaxSize()) {
        val w = a.w; val h = a.h
        val p = if (pos == Offset.Zero) Offset(a.cx, a.top + h / 2f) else pos
        rotate(rot, p) {
            // hafif gölge
            drawRoundRect(Color.Black.copy(alpha = 0.25f), Offset(p.x - w / 2f + 2f * density, p.y - h / 2f + 4f * density), Size(w, h), CornerRadius(h / 2f))
            drawRoundRect(a.color, Offset(p.x - w / 2f, p.y - h / 2f), Size(w, h), CornerRadius(h / 2f))
        }
    }
}

// ================================================================ Ada saksısı (sarkan bitki)

@Composable
private fun PlantFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { PlantStore.load(ctx) }
    // Gün dönünce susuzluk durumu güncellensin
    var dayTick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) { delay(10 * 60_000L); PlantStore.decay(ctx); dayTick++ } }
    val water = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        bus.events.collect { if (it == PlayEvent.Water) launch { water.snapTo(0f); water.animateTo(1f, tween(900)) ; water.snapTo(0f) } }
    }
    if (!a.compactVisible) return
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now -> if (last != 0L) t += ((now - last) / 1e9f).coerceIn(0f, 0.1f); last = now }
            delay(40)                                           // ~20 kare/sn yeter: yavaş salınım
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        if (dayTick < 0L) return@Canvas
        val h = a.h
        val dir = a.dir()
        val g = PlantStore.growth.coerceIn(1, 14)
        val thirst = PlantStore.thirst
        val root = Offset(a.liveCx() - a.w * 0.30f, a.edgeY() - dir * 2f)
        val len = h * (0.45f + 0.17f * g)
        val droop = when (thirst) { 0 -> 0f; 1 -> 0.35f; else -> 1f }
        val stem = when (thirst) { 0 -> Color(0xFF2E9E4F); 1 -> Color(0xFF7A9A3A); else -> Color(0xFF7A5A3A) }
        val leaf = when (thirst) { 0 -> Color(0xFF34C759); 1 -> Color(0xFFA4B84A); else -> Color(0xFF9C7A4E) }
        val sway = h * 0.10f * (1f - droop * 0.6f)
        fun at(u: Float) = Offset(root.x + kotlin.math.sin(u * 3f + t * 1.1f) * sway * u, root.y + dir * u * len)
        // Gövde
        val path = Path()
        for (i in 0..24) {
            val q = at(i / 24f)
            if (i == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
        }
        drawPath(path, stem, style = Stroke(h * 0.06f, cap = StrokeCap.Round))
        // Yapraklar: düğümlerde sırayla iki yana; susayınca aşağı sarkar, solunca kahverengi
        val nodes = (2 + g / 2).coerceAtMost(9)
        for (k in 1..nodes) {
            val u = k / (nodes + 0.6f)
            val c = at(u)
            val side = if (k % 2 == 0) 1f else -1f
            val ang = side * (35f + 45f * droop) * dir + (if (dir > 0) 90f else -90f) * droop * 0.4f
            rotate(ang, c) {
                drawOval(leaf, Offset(c.x, c.y - h * 0.05f), Size(h * 0.20f * (1f - 0.25f * droop), h * 0.10f))
            }
        }
        // Çiçekler: 7 gün üst üste bakılınca
        if (thirst == 0 && g >= 7) {
            val n = (g - 6).coerceAtMost(4)
            for (k in 0 until n) {
                val c = at(0.35f + k * 0.18f)
                val fc = if (k % 2 == 0) Color(0xFFFF8FB1) else Color(0xFFFFFFFF)
                val pr = h * 0.06f
                for (i in 0 until 5) {
                    val an = (i * 72f + t * 8f) * (PI.toFloat() / 180f)
                    drawCircle(fc, pr, Offset(c.x + kotlin.math.cos(an) * pr * 1.1f, c.y + kotlin.math.sin(an) * pr * 1.1f))
                }
                drawCircle(Color(0xFFFFD60A), pr * 0.7f, c)
            }
        }
        // Ucunda tomurcuk
        drawCircle(leaf, h * 0.045f, at(1f))
        // Sulama damlaları
        val wv = water.value
        if (wv > 0f) {
            for (k in 0 until 4) {
                val u = (wv * 1.3f - k * 0.12f).coerceIn(0f, 1f)
                if (u <= 0f || u >= 1f) continue
                val c = at(u)
                drawCircle(Color(0xFF64D2FF).copy(alpha = 1f - u), h * 0.05f, Offset(c.x + (k - 1.5f) * h * 0.05f, c.y))
            }
        }
    }
}

// ================================================================ Mevsim modu

/** 1 kış, 2 ilkbahar, 3 yaz, 4 sonbahar. 0 = tarihe göre (kuzey yarıküre). */
private fun seasonOf(mode: Int): Int {
    if (mode in 1..4) return mode
    return when (java.time.LocalDate.now().monthValue) { 12, 1, 2 -> 1; 3, 4, 5 -> 2; 6, 7, 8 -> 3; else -> 4 }
}

private class Flake(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val size: Float, var rot: Float, val spin: Float, val col: Color)

@Composable
private fun SeasonFx(a: PlayAnchor, season: Int) {
    val parts = remember { ArrayList<Flake>() }
    var frame by remember { mutableLongStateOf(0L) }
    val live by rememberUpdatedState(a)
    val visible = a.compactVisible
    LaunchedEffect(visible, season) {
        parts.clear()
        if (!visible) return@LaunchedEffect
        var last = 0L
        var acc = 0f
        while (true) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 30f else ((now - last) / 1e9f).coerceIn(0.001f, 0.1f)
                last = now
                val an = live
                val d = an.h / 37f
                acc += dt * (if (season == 3) 2.5f else 1.4f)         // saniyede ~1-2 parça
                while (acc >= 1f && parts.size < 24) {
                    acc -= 1f
                    val x = an.liveCx() + (Random.nextFloat() - 0.5f) * an.w * 0.9f
                    when (season) {
                        1 -> parts.add(Flake(x, an.edgeY(), (Random.nextFloat() - 0.5f) * 10f * d, an.dir() * (18f + Random.nextFloat() * 18f) * d, 1f, (1.4f + Random.nextFloat() * 1.6f) * d, 0f, 0f, Color.White))
                        2 -> parts.add(Flake(x, an.edgeY(), (Random.nextFloat() - 0.5f) * 20f * d, an.dir() * (14f + Random.nextFloat() * 14f) * d, 1f, (2.2f + Random.nextFloat() * 1.5f) * d, Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 180f, if (Random.nextBoolean()) Color(0xFFFFB7CE) else Color(0xFFFFE4EC)))
                        3 -> {   // parıltı: adanın çevresinde rastgele noktada belirip söner
                            val side = Random.nextFloat()
                            val px = an.liveCx() + (Random.nextFloat() - 0.5f) * (an.w + 16f * d)
                            val py = an.liveCy() + an.dir() * (an.h / 2f + (2f + side * 10f) * d)
                            parts.add(Flake(px, py, 0f, 0f, 1f, (2f + Random.nextFloat() * 2.5f) * d, Random.nextFloat() * 45f, 0f, Color(0xFFFFE9A8)))
                        }
                        else -> parts.add(Flake(x, an.edgeY(), (Random.nextFloat() - 0.5f) * 30f * d, an.dir() * (22f + Random.nextFloat() * 22f) * d, 1f, (3f + Random.nextFloat() * 2f) * d, Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 260f,
                            listOf(Color(0xFFFF9F0A), Color(0xFFFF6B2C), Color(0xFFD9822B), Color(0xFFE8B23A)).random()))
                    }
                }
                val it2 = parts.iterator()
                while (it2.hasNext()) {
                    val f = it2.next()
                    f.life -= dt / (if (season == 3) 0.9f else 4.5f)
                    f.x += (f.vx + kotlin.math.sin(f.y * 0.02f) * 8f * d) * dt
                    f.y += f.vy * dt
                    f.rot += f.spin * dt
                    if (f.life <= 0f) it2.remove()
                }
                frame = now
            }
            delay(16)
        }
    }
    if (!visible) return
    Canvas(Modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas
        val d = a.h / 37f
        val cx = a.liveCx()
        // Sabit süs: kışın adanın kenarında kar, ilkbaharda kenarda çiçekler
        if (season == 1) {
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(cx - a.w / 2f + a.h * 0.2f, a.liveCy() - a.h / 2f + a.dir() * 2.2f * d),
                Size(a.w - a.h * 0.4f, a.h), CornerRadius(a.h / 2f))
        }
        if (season == 2) {
            for (k in 0 until 4) {
                val fx = cx - a.w * 0.32f + k * a.w * 0.21f
                val fy = a.edgeY() + a.dir() * 2.5f * d
                val pr = 2.6f * d
                val fc = if (k % 2 == 0) Color(0xFFFF8FB1) else Color(0xFFFFFFFF)
                for (i in 0 until 5) {
                    val an = i * 72f * (PI.toFloat() / 180f)
                    drawCircle(fc, pr, Offset(fx + kotlin.math.cos(an) * pr * 1.1f, fy + kotlin.math.sin(an) * pr * 1.1f))
                }
                drawCircle(Color(0xFFFFD60A), pr * 0.7f, Offset(fx, fy))
            }
        }
        for (f in parts) {
            val al = f.life.coerceIn(0f, 1f)
            when (season) {
                1 -> drawCircle(f.col.copy(alpha = 0.9f * al), f.size, Offset(f.x, f.y))
                3 -> {   // dört köşeli yıldız
                    val tw = kotlin.math.sin(al * PI.toFloat())
                    val r = f.size * tw
                    drawLine(f.col.copy(alpha = tw), Offset(f.x - r, f.y), Offset(f.x + r, f.y), 1.2f * d, StrokeCap.Round)
                    drawLine(f.col.copy(alpha = tw), Offset(f.x, f.y - r), Offset(f.x, f.y + r), 1.2f * d, StrokeCap.Round)
                }
                else -> rotate(f.rot, Offset(f.x, f.y)) {
                    drawOval(f.col.copy(alpha = al), Offset(f.x - f.size, f.y - f.size * 0.5f), Size(f.size * 2f, f.size))
                }
            }
        }
    }
}

// ================================================================ Kutlama (konfeti)

private class Confetti(var x: Float, var y: Float, var vx: Float, var vy: Float, var rot: Float, val spin: Float, val col: Color, val w: Float, val h: Float)

@Composable
private fun PartyFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val parts = remember { ArrayList<Confetti>() }
    var frame by remember { mutableLongStateOf(0L) }
    var active by remember { mutableStateOf(false) }
    val live by rememberUpdatedState(a)
    LaunchedEffect(Unit) {
        bus.events.collect {
            if (it == PlayEvent.Party) {
                val an = live
                val d = an.h / 37f
                if (!bus.preview) PlayFx.partyUntil = System.currentTimeMillis() + 4_000L
                bus.kick(5f)
                repeat(140) {
                    val ang = (Random.nextFloat() - 0.5f) * 2.8f       // aşağı yönde geniş yelpaze
                    val sp = (400f + Random.nextFloat() * 800f) * d
                    parts.add(Confetti(an.liveCx(), an.edgeY(), kotlin.math.sin(ang) * sp, an.dir() * kotlin.math.cos(ang) * sp,
                        Random.nextFloat() * 360f, (Random.nextFloat() - 0.5f) * 720f, CONFETTI.random(),
                        (4f + Random.nextFloat() * 4f) * d, (2f + Random.nextFloat() * 2f) * d))
                }
                active = true
            }
        }
    }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        var last = 0L
        while (parts.isNotEmpty()) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.05f)
                last = now
                val an = live
                val d = an.h / 37f
                val iter = parts.iterator()
                while (iter.hasNext()) {
                    val c = iter.next()
                    c.vy += an.dir() * 900f * d * dt
                    c.vx *= (1f - 1.2f * dt); c.vy *= (1f - 0.6f * dt)
                    c.x += c.vx * dt; c.y += c.vy * dt
                    c.rot += c.spin * dt
                    if (c.y > an.screenH + 40f || c.y < -40f) iter.remove()
                }
                frame = now
            }
        }
        active = false
    }
    if (!active) return
    Canvas(Modifier.fillMaxSize()) {
        if (frame < 0L) return@Canvas
        for (c in parts) {
            // dönerken yassılaşan şerit: 3B dönme hissi
            val flip = kotlin.math.abs(kotlin.math.cos(c.rot * PI.toFloat() / 90f))
            rotate(c.rot, Offset(c.x, c.y)) {
                drawRect(c.col, Offset(c.x - c.w / 2f, c.y - c.h * flip / 2f), Size(c.w, c.h * flip + 0.5f))
            }
        }
    }
}
private val CONFETTI = listOf(Color(0xFFFF375F), Color(0xFFFFD60A), Color(0xFF30D158), Color(0xFF64D2FF), Color(0xFFBF5AF2), Color(0xFFFF9F0A), Color.White)
