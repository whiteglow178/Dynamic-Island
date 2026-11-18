package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.*
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * ADA OLAYLARI: yağmur / şimşek / kar (donma) / asit yağmuru (erime) / adayı kırma.
 * Genişletilmiş müzik panelindeki öğeler (kapak, şarkı adı, sözler, ilerleme çubuğu, düğmeler) Modifier.fxTarget ile kaydolur;
 * damlalar ve kar bu öğelerin KONUMUNA göre çarpışır (içlerinden geçmez).
 * Adadan dışarı düşen parçalar, tam ekran dokunmatik olmayan ayrı bir pencerede (IslandFxWindow) çizilir.
 */

val LocalIslandFx = compositionLocalOf<IslandFxState?> { null }

// ---------------------------------------------------------------- Öğe (hedef) durumu
@Stable
class FxTarget(val id: String) {
    var rect by mutableStateOf(Rect.Zero)          // ada içi yerel px
    var freeze by mutableFloatStateOf(0f)
    var taps by mutableIntStateOf(0)
    var melt by mutableFloatStateOf(0f)
    var away by mutableStateOf(false)              // adadan koptu (görünmez)
    var layer: GraphicsLayer? = null
    var coolUntil = 0L
    var meltRestoreAt = 0L
    var awayRestoreAt = 0L
    var snow = 0f
    var blobs = 0

    /** Erirken öğe alttan yukarı doğru küçülür; üst kenarı aşağı iner. */
    val visualTop: Float get() = rect.bottom - rect.height * (1f - 0.85f * melt.coerceIn(0f, 1f))
    val frozen: Boolean get() = freeze >= 1f
    val gone: Boolean get() = away || melt >= 1f
    val blocked: Boolean get() = gone || frozen || melt >= 0.6f

    fun reset() {
        freeze = 0f; taps = 0; melt = 0f; away = false
        coolUntil = 0L; meltRestoreAt = 0L; awayRestoreAt = 0L; snow = 0f; blobs = 0
    }
}

class Crack(val lines: List<List<Offset>>, val bornMs: Long)

@Stable
class IslandFxState {
    val targets = mutableStateMapOf<String, FxTarget>()
    var origin: LayoutCoordinates? = null
    var live = false
    val cracks = ArrayList<Crack>()

    fun screenOrigin(): Offset? = origin?.takeIf { it.isAttached }?.positionOnScreen()
}

/** Öğeyi efektlere kaydeder: konumunu ölçer, erirken küçültür, düşerken görüntüsünü yakalar. */
@Composable
fun Modifier.fxTarget(id: String): Modifier {
    val fx = LocalIslandFx.current ?: return this
    val t = remember(fx, id) { FxTarget(id) }
    val layer = rememberGraphicsLayer()
    SideEffect { t.layer = layer }
    DisposableEffect(fx, id) { onDispose { if (fx.targets[id] === t) fx.targets.remove(id) } }
    return this
        .onGloballyPositioned { c ->
            val o = fx.origin
            if (o != null && o.isAttached && c.isAttached) {
                t.rect = o.localBoundingBoxOf(c, false)
                if (fx.targets[id] !== t) fx.targets[id] = t
            }
        }
        .graphicsLayer {
            val m = t.melt.coerceIn(0f, 1f)
            alpha = if (t.away) 0f else (1f - 0.85f * m * m)
            scaleY = 1f - 0.85f * m
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
        }
}

// ---------------------------------------------------------------- Adadan dışarı düşen parçalar (tüm ekran penceresi)
class FxPiece(
    val kind: Int,                 // 0 = öğe görüntüsü, 1 = ada gövdesi parçası
    val bmp: ImageBitmap?,
    val poly: List<Offset>?,       // kind 1: köşeler (merkeze göre)
    val color: Color,
    val halfW: Float, val halfH: Float,
    var x: Float, var y: Float,    // ekran merkezi (px)
    val homeX: Float, val homeY: Float,   // ada içi yerel merkez (px)
) {
    var rot = 0f; var vx = 0f; var vy = 0f; var vr = 0f
    var state = 0                  // 0 düşüyor, 1 yatıyor, 2 geri dönüyor, 3 evde
    var restedAt = 0L; var delayMs = 0L
    var sx = 0f; var sy = 0f; var srot = 0f; var t = 0f
    var onHome: (() -> Unit)? = null
}

object FxBridge {
    val pieces = ArrayList<FxPiece>()
    var count by mutableIntStateOf(0)
    var tick by mutableLongStateOf(0L)
    var originX by mutableFloatStateOf(0f)     // adanın (yerel 0,0) ekran konumu
    var originY by mutableFloatStateOf(0f)
    var winX = 0f; var winY = 0f               // düşme penceresinin ekran konumu (kalibrasyon)
    var screenW = 1080f; var screenH = 2400f
    var restoreMs = 5000L
    var islandHidden by mutableStateOf(false)
    var onShardsDone: (() -> Unit)? = null

    fun add(p: FxPiece) { pieces.add(p); count = pieces.size }
    fun clear() { pieces.clear(); count = 0; islandHidden = false; onShardsDone = null }

    private fun ease(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - Math.pow((-2.0 * t + 2.0), 3.0).toFloat() / 2f

    fun step(dt: Float, nowMs: Long, d: Float) {
        if (pieces.isEmpty()) return
        val g = 2700f * d
        val floorY = screenH - 40f * d
        val shardsLeft = pieces.any { it.kind == 1 }
        var i = pieces.size - 1
        while (i >= 0) {
            val p = pieces[i]
            when (p.state) {
                0 -> {
                    p.vy += g * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
                    if (p.x < p.halfW) { p.x = p.halfW; p.vx = -p.vx * 0.5f }
                    else if (p.x > screenW - p.halfW) { p.x = screenW - p.halfW; p.vx = -p.vx * 0.5f }
                    if (p.y + p.halfH > floorY) {
                        p.y = floorY - p.halfH
                        if (abs(p.vy) > 350f * d) { p.vy = -p.vy * 0.32f; p.vx *= 0.7f; p.vr *= 0.6f }
                        else { p.vy = 0f; p.vx = 0f; p.vr = 0f; p.state = 1; p.restedAt = nowMs }
                    }
                }
                1 -> if (nowMs - p.restedAt >= restoreMs + p.delayMs && (p.kind == 1 || !shardsLeft)) {
                    p.sx = p.x; p.sy = p.y
                    var a = p.rot % 360f
                    if (a > 180f) a -= 360f
                    if (a < -180f) a += 360f
                    p.srot = a; p.t = 0f; p.state = 2
                }
                2 -> {
                    p.t += dt / 0.9f
                    val e = ease(p.t.coerceIn(0f, 1f))
                    val hx = originX + p.homeX; val hy = originY + p.homeY
                    p.x = p.sx + (hx - p.sx) * e
                    p.y = p.sy + (hy - p.sy) * e - sin(PI * e).toFloat() * 70f * d
                    p.rot = p.srot * (1f - e)
                    if (p.t >= 1f) {
                        p.state = 3; p.x = hx; p.y = hy; p.rot = 0f
                        if (p.kind == 0) { p.onHome?.invoke(); pieces.removeAt(i) }
                    }
                }
                else -> { p.x = originX + p.homeX; p.y = originY + p.homeY }
            }
            i--
        }
        // Tüm ada parçaları yerine gelince: parçalar kalkar, ada geri görünür
        if (pieces.any { it.kind == 1 } && pieces.none { it.kind == 1 && it.state != 3 }) {
            pieces.removeAll { it.kind == 1 }
            islandHidden = false
            onShardsDone?.invoke(); onShardsDone = null
        }
        count = pieces.size
    }
}

@Composable
fun FxFallRoot() {
    val d = LocalDensity.current.density
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = n
                FxBridge.step(dt, System.currentTimeMillis(), d)
                FxBridge.tick = n
            }
        }
    }
    Canvas(
        Modifier.fillMaxSize().onGloballyPositioned {
            val p = it.positionOnScreen()
            FxBridge.winX = p.x; FxBridge.winY = p.y
            FxBridge.screenW = it.size.width.toFloat() + p.x
            FxBridge.screenH = it.size.height.toFloat() + p.y
        }
    ) {
        @Suppress("UNUSED_VARIABLE") val t = FxBridge.tick
        for (p in FxBridge.pieces) {
            val cx = p.x - FxBridge.winX
            val cy = p.y - FxBridge.winY
            rotate(p.rot, Offset(cx, cy)) {
                if (p.kind == 0 && p.bmp != null) {
                    drawImage(p.bmp, topLeft = Offset(cx - p.halfW, cy - p.halfH))
                } else if (p.poly != null) {
                    val path = Path().apply {
                        p.poly.forEachIndexed { i, o -> if (i == 0) moveTo(cx + o.x, cy + o.y) else lineTo(cx + o.x, cy + o.y) }
                        close()
                    }
                    drawPath(path, p.color)
                    drawPath(path, Color.White.copy(alpha = 0.22f), style = Stroke(width = 1.2f))
                }
            }
        }
    }
}

/** Tam ekran, dokunmatik olmayan pencere. Parça varken servis ekler, yokken kaldırır. */
class IslandFxWindow(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null

    fun attach() {
        if (view != null) return
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { FxFallRoot() }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
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
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(v, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                host = h2; added = true
            }
        }
        if (!added) added = runCatching { wm.addView(v, params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)); host = wm }.isSuccess
        if (added) view = v else v.disposeComposition()
    }

    fun detach() {
        val v = view ?: return
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        view = null; host = null
    }
}

// ---------------------------------------------------------------- Parçacık simülasyonu
private const val K_RAIN = 0
private const val K_FLAKE = 1
private const val K_ACID = 2
private const val K_SPLASH = 3
private const val K_BLOB = 4
private const val K_ICE = 5
private const val K_DRIP = 6

private class P(var kind: Int, var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var size: Float) {
    var tid: String? = null
    var ox = 0f
    var rot = 0f
    var vr = 0f
}

private class Bolt(val pts: List<Offset>, var t: Float)

private class FxSim {
    val ps = ArrayList<P>()
    val rnd = java.util.Random()
    var acc = 0f
    var boltIn = 3f
    var bolt: Bolt? = null
    var flash = 0f
    var armed = true
    var autoStart = 0L
    var autoNext = 0L
    var autoCount = 0
    var shattered = false

    fun f(): Float = rnd.nextFloat()

    fun clear() {
        ps.clear(); acc = 0f; boltIn = 3f; bolt = null; flash = 0f
        armed = true; autoStart = 0L; autoNext = 0L; autoCount = 0; shattered = false
    }

    private fun hit(targets: Collection<FxTarget>, x: Float, y: Float): FxTarget? {
        var best: FxTarget? = null
        for (t in targets) {
            if (t.gone) continue
            val r = t.rect
            if (x >= r.left && x <= r.right && y >= t.visualTop && y <= r.bottom) {
                if (best == null || t.visualTop < best.visualTop) best = t
            }
        }
        return best
    }

    private fun splash(x: Float, y: Float, d: Float, n: Int) {
        repeat(n) {
            ps.add(P(K_SPLASH, x, y, (f() - 0.5f) * 240f * d, -(80f + f() * 140f) * d, 0.28f, (1f + f()) * d))
        }
    }

    fun iceBurst(t: FxTarget, d: Float) {
        repeat(18) {
            val p = P(K_ICE, t.rect.left + f() * t.rect.width, t.rect.top + f() * t.rect.height,
                (f() - 0.5f) * 520f * d, -(60f + f() * 260f) * d, 0.9f + f() * 0.5f, (3f + f() * 4f) * d)
            p.vr = (f() - 0.5f) * 600f
            ps.add(p)
        }
    }

    fun makeBolt(x: Float, endY: Float, d: Float): Bolt {
        val pts = ArrayList<Offset>()
        val n = 9
        for (k in 0..n) {
            val y = endY * k / n
            val px = if (k == n) x else x + (f() - 0.5f) * 34f * d
            pts.add(Offset(px, y))
        }
        return Bolt(pts, 1f)
    }

    fun makeCrack(p: Offset, d: Float, nowMs: Long): Crack {
        val lines = ArrayList<List<Offset>>()
        val arms = 3 + rnd.nextInt(3)
        for (a in 0 until arms) {
            var ang = f() * 6.2832f
            var cur = p
            val pts = arrayListOf(cur)
            val segs = 4 + rnd.nextInt(4)
            for (s in 0 until segs) {
                ang += (f() - 0.5f) * 0.9f
                val len = (8f + f() * 16f) * d
                cur = Offset(cur.x + cos(ang) * len, cur.y + sin(ang) * len)
                pts.add(cur)
            }
            lines.add(pts)
        }
        return Crack(lines, nowMs)
    }

    fun step(
        dt: Float, now: Long, W: Float, H: Float, d: Float,
        cfg: IslandSettings, fx: IslandFxState, strike: (FxTarget) -> Unit,
    ) {
        val targets = fx.targets.values
        val weather = cfg.fxWeather
        val inten = cfg.fxIntensity.coerceIn(0.05f, 1f)
        val restoreMs = (cfg.fxRestoreSec * 1000f).toLong()

        if (weather != 0) {
            val base = when (weather) { 1 -> 90f; 2 -> 28f; else -> 45f }
            acc += base * inten * (W / (340f * d)).coerceIn(0.6f, 1.8f) * dt
            while (acc >= 1f) {
                acc -= 1f
                val x = f() * W
                when (weather) {
                    1 -> ps.add(P(K_RAIN, x, -6f * d, -30f * d, (650f + f() * 250f) * d, 0f, 1f))
                    2 -> ps.add(P(K_FLAKE, x, -6f * d, (f() - 0.5f) * 30f * d, (55f + f() * 60f) * d, f() * 6.28f, (1.4f + f() * 1.8f) * d))
                    else -> ps.add(P(K_ACID, x, -6f * d, -20f * d, (520f + f() * 200f) * d, 0f, 1f))
                }
            }
        }

        for (t in targets) t.blobs = 0

        var i = ps.size - 1
        while (i >= 0) {
            val p = ps[i]
            var dead = false
            when (p.kind) {
                K_RAIN, K_ACID -> {
                    p.x += p.vx * dt; p.y += p.vy * dt
                    if (p.y > H + 10f * d || p.x < -20f * d) dead = true
                    else {
                        val t = hit(targets, p.x, p.y)
                        if (t != null) {
                            dead = true
                            splash(p.x, t.visualTop, d, if (p.kind == K_RAIN) 3 else 2)
                            if (p.kind == K_ACID) {
                                if (t.blobs < 14) {
                                    val b = P(K_BLOB, p.x, t.visualTop, 0f, 0f, 0f, (2.5f + f() * 3f) * d)
                                    b.tid = t.id
                                    b.ox = ((p.x - t.rect.left) / max(1f, t.rect.width)).coerceIn(0.03f, 0.97f)
                                    ps.add(b); t.blobs++
                                } else {
                                    t.melt = (t.melt + 0.01f * cfg.fxMeltSpeed).coerceAtMost(1f)
                                }
                            }
                        }
                    }
                }
                K_FLAKE -> {
                    p.life += dt * 2f
                    p.x += (p.vx + sin(p.life) * 14f * d) * dt; p.y += p.vy * dt
                    if (p.y > H + 6f * d) dead = true
                    else {
                        val t = hit(targets, p.x, p.y)
                        if (t != null) {
                            dead = true
                            t.snow = (t.snow + 0.04f).coerceAtMost(1f)
                            if (!t.frozen && now >= t.coolUntil) t.freeze = (t.freeze + 0.011f * cfg.fxFreezeSpeed).coerceAtMost(1f)
                        }
                    }
                }
                K_SPLASH -> {
                    p.life -= dt; p.vy += 900f * d * dt; p.x += p.vx * dt; p.y += p.vy * dt
                    if (p.life <= 0f) dead = true
                }
                K_BLOB -> {
                    val t = p.tid?.let { fx.targets[it] }
                    if (t == null || t.gone) {
                        p.kind = K_ACID; p.tid = null; p.vx = 0f; p.vy = 200f * d
                    } else {
                        t.blobs++
                        p.x = t.rect.left + p.ox * t.rect.width
                        p.y = t.visualTop + 1f * d
                        if (t.melt < 1f) {
                            t.melt = (t.melt + dt * 0.045f * cfg.fxMeltSpeed).coerceAtMost(1f)
                            if (t.melt >= 1f) t.meltRestoreAt = now + restoreMs
                        }
                    }
                }
                K_ICE -> {
                    p.life -= dt; p.vy += 1500f * d * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
                    if (p.life <= 0f) dead = true
                }
                K_DRIP -> {
                    p.life -= dt; p.vy += 700f * d * dt; p.y += p.vy * dt
                    if (p.life <= 0f) dead = true
                }
            }
            if (dead) ps.removeAt(i)
            i--
        }

        for (t in targets) {
            if (!t.frozen && t.freeze > 0f) t.freeze = (t.freeze - 0.012f * dt).coerceAtLeast(0f)
            if (t.snow > 0f && weather != 2 && !t.frozen) t.snow = (t.snow - 0.05f * dt).coerceAtLeast(0f)
            if (t.melt >= 1f && t.meltRestoreAt == 0L) t.meltRestoreAt = now + restoreMs
            if (t.melt > 0f) {
                if (t.meltRestoreAt > 0L && now >= t.meltRestoreAt) {
                    t.melt -= 0.6f * dt
                    if (t.melt <= 0f) { t.melt = 0f; t.meltRestoreAt = 0L }
                } else if (t.blobs == 0 && t.melt < 1f) {
                    t.melt = (t.melt - 0.05f * dt).coerceAtLeast(0f)
                }
                if (t.melt in 0.08f..0.99f && f() < 6f * dt * t.melt) {
                    val dp = P(K_DRIP, t.rect.left + f() * t.rect.width, t.rect.bottom, 0f, 20f * d, 0.9f, 2f * d)
                    ps.add(dp)
                }
            }
            if (t.away && t.awayRestoreAt > 0L && now >= t.awayRestoreAt) { t.away = false; t.awayRestoreAt = 0L }
        }

        if (weather == 1 && cfg.fxLightning) {
            boltIn -= dt
            if (boltIn <= 0f) {
                boltIn = cfg.fxLightningSec * (0.6f + f() * 0.8f)
                val x = (0.08f + f() * 0.84f) * W
                var tgt: FxTarget? = null
                for (t in targets) {
                    if (t.gone || t.frozen) continue
                    if (x >= t.rect.left && x <= t.rect.right && (tgt == null || t.rect.top < tgt.rect.top)) tgt = t
                }
                val endY = tgt?.rect?.center?.y ?: (H * 0.92f)
                bolt = makeBolt(x, endY, d); flash = 1f
                if (tgt != null) strike(tgt)
            }
        }
        if (flash > 0f) flash = (flash - dt * 3.2f).coerceAtLeast(0f)
        bolt?.let { it.t -= dt * 3.5f; if (it.t <= 0f) bolt = null }
    }
}

// ---------------------------------------------------------------- Efekt katmanı (adanın içinde, içeriğin üstünde)
@Composable
fun FxLayer(
    fx: IslandFxState,
    cfg: IslandSettings,
    progress: IslandProgress,
    live: Boolean,
    bodyColor: () -> Color,
) {
    val dens = LocalDensity.current
    val d = dens.density
    val scope = rememberCoroutineScope()
    val sim = remember { FxSim() }
    val cfgNow by rememberUpdatedState(cfg)
    val bodyNow by rememberUpdatedState(bodyColor)
    var tick by remember { mutableLongStateOf(0L) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val expandedFull by remember { derivedStateOf { progress.mean > 0.97f } }
    SideEffect { fx.live = live }

    fun resetAll() {
        for (t in fx.targets.values) t.reset()
        sim.clear(); fx.cracks.clear()
        if (fx.live) FxBridge.clear()
    }
    DisposableEffect(Unit) { onDispose { resetAll() } }
    LaunchedEffect(expandedFull) { if (!expandedFull) resetAll() }

    fun detach(t: FxTarget) {
        if (t.gone) return
        val restoreMs = (cfgNow.fxRestoreSec * 1000f).toLong()
        if (!fx.live) {
            t.away = true; t.awayRestoreAt = System.currentTimeMillis() + restoreMs
            return
        }
        val layer = t.layer
        scope.launch {
            val bmp = layer?.let { runCatching { it.toImageBitmap() }.getOrNull() }
            val o = fx.screenOrigin()
            t.away = true
            if (bmp == null || o == null) {
                t.awayRestoreAt = System.currentTimeMillis() + restoreMs
                return@launch
            }
            FxBridge.originX = o.x; FxBridge.originY = o.y
            FxBridge.restoreMs = restoreMs
            val c = t.rect.center
            val p = FxPiece(0, bmp, null, Color.Unspecified, bmp.width / 2f, bmp.height / 2f,
                o.x + c.x, o.y + c.y, c.x, c.y)
            p.vx = (sim.f() - 0.5f) * 220f * d
            p.vy = -(150f + sim.f() * 200f) * d
            p.vr = (sim.f() - 0.5f) * 220f
            p.onHome = { t.away = false }
            FxBridge.add(p)
        }
    }

    fun shatter() {
        val o = fx.screenOrigin() ?: return
        val W = size.width.toFloat(); val H = size.height.toFloat()
        if (W <= 0f || H <= 0f) return
        FxBridge.originX = o.x; FxBridge.originY = o.y
        FxBridge.restoreMs = (cfgNow.fxRestoreSec * 1000f).toLong()
        val cols = 8; val rows = 3
        val vx = Array(rows + 1) { r -> FloatArray(cols + 1) { c ->
            val base = W * c / cols
            if (c == 0 || c == cols) base else base + (sim.f() - 0.5f) * (W / cols) * 0.6f
        } }
        val vy = Array(rows + 1) { r -> FloatArray(cols + 1) { c ->
            val base = H * r / rows
            if (r == 0 || r == rows) base else base + (sim.f() - 0.5f) * (H / rows) * 0.6f
        } }
        val base = bodyNow().copy(alpha = 1f)
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = Offset(vx[r][c], vy[r][c]); val b = Offset(vx[r][c + 1], vy[r][c + 1])
            val cc = Offset(vx[r + 1][c + 1], vy[r + 1][c + 1]); val dd = Offset(vx[r + 1][c], vy[r + 1][c])
            val tris = if (sim.rnd.nextBoolean()) listOf(listOf(a, b, cc), listOf(a, cc, dd)) else listOf(listOf(a, b, dd), listOf(b, cc, dd))
            for (tri in tris) {
                val g = Offset((tri[0].x + tri[1].x + tri[2].x) / 3f, (tri[0].y + tri[1].y + tri[2].y) / 3f)
                val rel = tri.map { Offset(it.x - g.x, it.y - g.y) }
                val rad = rel.maxOf { max(abs(it.x), abs(it.y)) }
                val col = lerp(base, Color.White, 0.05f + sim.f() * 0.09f)
                val p = FxPiece(1, null, rel, col, rad, rad, o.x + g.x, o.y + g.y, g.x, g.y)
                p.vx = (sim.f() - 0.5f) * 700f * d
                p.vy = -(sim.f() * 500f) * d
                p.vr = (sim.f() - 0.5f) * 500f
                p.delayMs = (sim.f() * 1500f).toLong()
                FxBridge.add(p)
            }
        }
        FxBridge.islandHidden = true
        FxBridge.onShardsDone = { sim.shattered = false; sim.autoStart = 0L; sim.autoCount = 0; fx.cracks.clear() }
    }

    fun shatterIce(t: FxTarget) {
        sim.iceBurst(t, d)
        t.freeze = 0f; t.taps = 0; t.snow = 0f
        t.coolUntil = System.currentTimeMillis() + 6000L
    }

    fun onBlockedTap(t: FxTarget) {
        if (t.frozen) {
            t.taps++
            if (t.taps >= cfgNow.fxThawTaps) shatterIce(t)
        }
    }

    fun onBreakTap(pos: Offset) {
        if (FxBridge.islandHidden) return
        val now = System.currentTimeMillis()
        var hitT: FxTarget? = null
        for (t in fx.targets.values) if (!t.gone && pos in t.rect) hitT = t
        fx.cracks.add(sim.makeCrack(pos, d, now))
        if (fx.cracks.size > 24) fx.cracks.removeAt(0)
        hitT?.let { detach(it) }
    }

    LaunchedEffect(expandedFull, cfg.fxWeather, cfg.fxBreak) {
        if (!expandedFull) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = n
                val now = System.currentTimeMillis()
                val c = cfgNow
                val W = size.width.toFloat(); val H = size.height.toFloat()
                if (W > 0f && H > 0f) {
                    if (fx.live) fx.screenOrigin()?.let { FxBridge.originX = it.x; FxBridge.originY = it.y }
                    sim.step(dt, now, W, H, d, c, fx) { t -> detach(t) }
                    if (c.fxBreak) {
                        val all = fx.targets.size >= 2 && fx.targets.values.all { it.gone }
                        if (!all) sim.armed = true
                        if (all && sim.armed && sim.autoStart == 0L) { sim.autoStart = now; sim.autoNext = now; sim.autoCount = 0; sim.armed = false }
                        if (sim.autoStart != 0L && !sim.shattered) {
                            if (now >= sim.autoNext && sim.autoCount < 9) {
                                val p = Offset((0.05f + sim.f() * 0.9f) * W, (0.1f + sim.f() * 0.8f) * H)
                                fx.cracks.add(sim.makeCrack(p, d, now))
                                sim.autoCount++; sim.autoNext = now + 330L
                            }
                            if (now - sim.autoStart > 3600L) {
                                if (fx.live) { sim.shattered = true; shatter() }
                                else if (now - sim.autoStart > 5200L) resetAll()   // önizleme: parça düşmez, her şey eski haline döner
                            }
                        }
                    }
                }
                tick = n
            }
        }
    }

    val blockedList by remember {
        derivedStateOf { fx.targets.values.filter { it.blocked } }
    }
    val breakOn = cfg.fxBreak && expandedFull && !FxBridge.islandHidden

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val tk = tick
            val now = System.currentTimeMillis()

            // ---- öğe üstü görünümler: kar yığını, buz, asit
            for (t in fx.targets.values) {
                val r = t.rect
                if (r.width <= 0f) continue
                if (t.snow > 0.02f && !t.away) {
                    val ph = (t.snow * 7f * d)
                    drawRoundRect(Color.White.copy(alpha = 0.92f), Offset(r.left + 2f * d, t.visualTop - ph * 0.6f), Size(r.width - 4f * d, ph), CornerRadius(4f * d))
                }
                if (t.freeze > 0.02f && !t.away) {
                    val a = t.freeze.coerceIn(0f, 1f)
                    drawRoundRect(Color(0xFFBFE9FF).copy(alpha = 0.10f + 0.42f * a), r.topLeft, r.size, CornerRadius(10f * d))
                    drawRoundRect(Color.White.copy(alpha = a * 0.85f), r.topLeft, r.size, CornerRadius(10f * d), style = Stroke(1.6f * d))
                    if (t.frozen) {
                        // parlama
                        drawLine(Color.White.copy(alpha = 0.35f), Offset(r.left + r.width * 0.15f, r.bottom - 3f * d), Offset(r.left + r.width * 0.45f, r.top + 3f * d), 2f * d)
                        // dokundukça çatlaklar
                        val rr = java.util.Random(t.id.hashCode().toLong())
                        val need = cfg.fxThawTaps.coerceAtLeast(1)
                        val show = ceil(10f * t.taps / need).toInt()
                        val c0 = r.center
                        for (k in 0 until 10) {
                            var ang = rr.nextFloat() * 6.2832f
                            val len = (0.35f + rr.nextFloat() * 0.5f) * max(r.width, r.height) / 2f
                            var cur = c0
                            val path = Path().apply { moveTo(cur.x, cur.y) }
                            for (s in 0 until 3) {
                                ang += (rr.nextFloat() - 0.5f) * 0.8f
                                cur = Offset(cur.x + cos(ang) * len / 3f, cur.y + sin(ang) * len / 3f)
                                path.lineTo(cur.x, cur.y)
                            }
                            if (k < show) drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(1.4f * d, cap = StrokeCap.Round))
                        }
                    }
                }
                if (t.melt > 0.02f && t.melt < 1f) {
                    val top = t.visualTop
                    drawRoundRect(Color(0xFF8BE000).copy(alpha = 0.30f * t.melt), Offset(r.left, top), Size(r.width, r.bottom - top), CornerRadius(8f * d))
                }
            }

            // ---- parçacıklar
            for (p in sim.ps) {
                when (p.kind) {
                    K_RAIN -> drawLine(Color(0xFFA9D8FF).copy(alpha = 0.6f), Offset(p.x, p.y), Offset(p.x - p.vx * 0.022f, p.y - p.vy * 0.022f), 1.4f * d, StrokeCap.Round)
                    K_ACID -> drawLine(Color(0xFFB5F23D).copy(alpha = 0.85f), Offset(p.x, p.y), Offset(p.x - p.vx * 0.022f, p.y - p.vy * 0.022f), 1.8f * d, StrokeCap.Round)
                    K_FLAKE -> drawCircle(Color.White.copy(alpha = 0.9f), p.size, Offset(p.x, p.y))
                    K_SPLASH -> drawCircle(Color.White.copy(alpha = (p.life / 0.28f).coerceIn(0f, 1f) * 0.7f), p.size, Offset(p.x, p.y))
                    K_BLOB -> {
                        drawCircle(Color(0xFF9BE81B).copy(alpha = 0.95f), p.size, Offset(p.x, p.y))
                        drawCircle(Color.White.copy(alpha = 0.35f), p.size * 0.35f, Offset(p.x - p.size * 0.25f, p.y - p.size * 0.25f))
                    }
                    K_ICE -> rotate(p.rot, Offset(p.x, p.y)) {
                        drawRect(Color(0xFFCDEEFF).copy(alpha = (p.life / 0.9f).coerceIn(0f, 1f) * 0.9f), Offset(p.x - p.size / 2f, p.y - p.size * 0.3f), Size(p.size, p.size * 0.6f))
                    }
                    K_DRIP -> drawCircle(Color(0xFFB5F23D).copy(alpha = (p.life / 0.9f).coerceIn(0f, 1f)), p.size, Offset(p.x, p.y))
                }
            }

            // ---- çatlaklar
            for (cr in fx.cracks) {
                val k = ((now - cr.bornMs) / 1400f).coerceIn(0f, 1f)
                for (pts in cr.lines) {
                    val n = pts.size - 1
                    val upto = k * n
                    for (s in 0 until n) {
                        if (s >= upto) break
                        val frac = (upto - s).coerceAtMost(1f)
                        val a = pts[s]; val b = pts[s + 1]
                        val e = Offset(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
                        drawLine(Color.Black.copy(alpha = 0.55f), a, e, 2.6f * d)
                        drawLine(Color.White.copy(alpha = 0.75f), a, e, 1.1f * d)
                    }
                }
            }

            // ---- şimşek
            sim.bolt?.let { b ->
                val a = b.t.coerceIn(0f, 1f)
                val path = Path().apply { b.pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(path, Color(0xFFBFD8FF).copy(alpha = 0.45f * a), style = Stroke(7f * d, cap = StrokeCap.Round))
                drawPath(path, Color.White.copy(alpha = a), style = Stroke(2.4f * d, cap = StrokeCap.Round))
            }
            if (sim.flash > 0f) drawRect(Color.White.copy(alpha = 0.32f * sim.flash))
        }

        // Kırma modu: hızlı dokunuş kırar; kaydırma ve uzun basma adanın kendi işlevine gider
        if (breakOn) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation()
                        if (up != null && (up.position - down.position).getDistance() < 24.dp.toPx() &&
                            up.uptimeMillis - down.uptimeMillis < 350L
                        ) {
                            up.consume()
                            onBreakTap(up.position)
                        }
                    }
                }
            )
        }

        // Donmuş / erimiş / kopmuş öğelerin altındaki gerçek düğmeler çalışmaz: dokunuşu bu katman alır
        for (t in blockedList) {
            key(t.id) {
                Box(
                    Modifier
                        .offset { IntOffset(t.rect.left.roundToInt(), t.rect.top.roundToInt()) }
                        .size(with(dens) { t.rect.width.toDp() }, with(dens) { t.rect.height.toDp() })
                        .pointerInput(t) { detectTapGestures { onBlockedTap(t) } }
                )
            }
        }
    }
}
