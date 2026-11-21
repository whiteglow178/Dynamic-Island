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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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

    fun wanted(s: IslandSettings) = s.playPet || s.playMagnet || s.playRipple || s.playPuff || s.playEater || s.playDaylight

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
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        view = null; host = null; lp = null
    }
}

@Composable
private fun PlayLayer(settings: StateFlow<IslandSettings>, anchorFlow: StateFlow<PlayAnchor?>) {
    val s by settings.collectAsState()
    val a by anchorFlow.collectAsState()
    val anchor = a ?: return
    if (!anchor.shown) return
    Box(Modifier.fillMaxSize()) {
        if (s.playDaylight) DaylightFx(anchor, s.playDaylightAmt)
        if (s.playEater) EaterFx(anchor)
        if (s.playRipple) RippleFx(anchor)
        if (s.playMagnet) MagnetFx(anchor)
        if (s.playPuff) PuffDustFx(anchor)
        if (s.playPet) PetFx(anchor)
    }
}

/** Adanın (fizik / cep kaymasıyla birlikte) anlık merkezi ve alt kenarı. */
private fun PlayAnchor.liveCx() = cx + PlayFx.slideX
private fun PlayAnchor.liveCy() = top + h / 2f + PlayFx.dropY
/** Adanın ekranın ortasına bakan kenarı (normalde alt, ters adada üst) ve o yönün işareti. */
private fun PlayAnchor.edgeY() = if (inverted) top + PlayFx.dropY else top + h + PlayFx.dropY
private fun PlayAnchor.dir() = if (inverted) -1f else 1f

// ================================================================ 8) Dalga

@Composable
private fun RippleFx(a: PlayAnchor) {
    val rings = remember { mutableStateListOf<Long>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        PlayFx.events.collect { if (it == PlayEvent.Ripple && rings.size < 4) rings.add(SystemClock.uptimeMillis()) }
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
    val charge = remember { Animatable(0f) }
    val buds = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        PlayFx.events.collect { e ->
            when (e) {
                PlayEvent.Charger -> launch {
                    PlayFx.kick(-2.5f)                                   // ada önce kabloya doğru uzar
                    charge.snapTo(0f)
                    charge.animateTo(1f, tween(420, easing = FastOutSlowInEasing))
                    delay(140)
                    charge.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 420f))
                    PlayFx.kick(3.5f)                                    // damla geri girince ada sarsılır
                }
                PlayEvent.Buds -> launch {
                    buds.snapTo(0f)
                    buds.animateTo(1f, tween(650, easing = FastOutLinearInEasing))   // mıknatıs gibi hızlanarak gelir
                    PlayFx.kick(3f)
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
private fun PetFx(a: PlayAnchor) {
    val show = a.compactVisible && PlayFx.pocket < 0.5f
    val hide = remember { Animatable(if (show) 0f else 1f) }
    LaunchedEffect(show) { hide.animateTo(if (show) 0f else 1f, tween(if (show) 520 else 320, easing = FastOutSlowInEasing)) }
    val peek = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        PlayFx.events.collect { e ->
            if (e == PlayEvent.Notif) launch {                     // bildirim: heyecanla aşağı sarkıp geri tırmanır
                peek.animateTo(1f, tween(180, easing = FastOutSlowInEasing))
                peek.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 260f))
            }
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
        val R = h * 0.42f
        val dir = a.dir()
        val now = System.currentTimeMillis()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val sleeping = PlayFx.charging || hour < 6
        val dancing = !sleeping && PlayFx.musicPlaying
        val bored = !sleeping && !dancing && now - PlayFx.lastActivityMs > 10 * 60_000L
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
}

// ================================================================ Bildirim yiyen ada

private class Morsel(val start: Long, val fromLeft: Boolean, val yFrac: Float, val hue: Int)

@Composable
private fun EaterFx(a: PlayAnchor) {
    val bites = remember { mutableStateListOf<Morsel>() }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        PlayFx.events.collect {
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
                done.forEach { _ -> PlayFx.emit(PlayEvent.Eaten) }      // ada yutkunur
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
