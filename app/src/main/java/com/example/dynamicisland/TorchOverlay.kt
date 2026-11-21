package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * FENER (FLAŞ) GÖSTERGESİ
 * Telefonun feneri (flaşı) açılınca adanın solunda küçük, parıltılı bir fener simgesi görünür (ada boşken).
 * Simgeye dokununca adanın altında siyah bir ışık kartı açılır:
 *   - yukarı / aşağı kaydır: parlaklık (cihaz kademeli flaşı destekliyorsa telefonun gerçek flaş seviyesi ayarlanır),
 *   - sağa / sola kaydır: ışık huzmesinin genişliği (görsel),
 *   - fener simgesine dokun: feneri kapatır.
 * Ek izin gerekmez (CameraManager.TorchCallback / setTorchMode / turnOnTorchWithStrengthLevel).
 */

/** Adanın ekrandaki konumu (px): merkez x, üst kenar y, kapalı yarı genişlik, kapalı yükseklik. */
data class TorchAnchor(val cx: Float, val top: Float, val halfW: Float, val h: Float, val inverted: Boolean)

private const val CARD_W_DP = 156f
private const val CARD_H_DP = 212f
private const val CARD_IDLE_MS = 6_000L

private val TorchLav = Color(0xFFCFCBFF)
private val TorchBlue = Color(0xFF7C86FF)
private val TorchBg = Color(0xFF09090B)
private val TorchFringe = Color(0xFFFF9B62)   // huzmenin dış kenarındaki turuncu renk kayması
private val TorchTeal = Color(0xFF8FB6FF)     // iç kenardaki mavimsi renk kayması

// ---------------------------------------------------------------- Donanım
private class TorchHw(ctx: Context) {
    val cm: CameraManager? = ctx.getSystemService(CameraManager::class.java)
    val id: String?
    val maxLevel: Int
    val defLevel: Int

    init {
        var pick: String? = null
        var pickBack = false
        runCatching {
            val m = cm ?: return@runCatching
            for (cid in m.cameraIdList) {
                val ch = m.getCameraCharacteristics(cid)
                if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                val back = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                if (pick == null || (back && !pickBack)) { pick = cid; pickBack = back }
            }
        }
        id = pick
        var mx = 1
        var df = 1
        val pid = pick
        if (pid != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                val ch = cm!!.getCameraCharacteristics(pid)
                mx = ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                df = ch.get(CameraCharacteristics.FLASH_INFO_STRENGTH_DEFAULT_LEVEL) ?: 1
            }
        }
        maxLevel = mx.coerceAtLeast(1)
        defLevel = df.coerceIn(1, maxLevel)
    }

    /** Cihaz kademeli flaş seviyesini destekliyor mu (Android 13+ ve en az 2 seviye)? */
    val supportsLevels: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && id != null && maxLevel > 1

    /** Şu anki flaş seviyesi (1..maxLevel). */
    fun currentLevel(): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val i = id
            if (i != null && maxLevel > 1) {
                return runCatching { cm!!.getTorchStrengthLevel(i) }.getOrDefault(defLevel).coerceIn(1, maxLevel)
            }
        }
        return maxLevel
    }

    fun frac(level: Int): Float = if (maxLevel <= 1) 1f else ((level - 1f) / (maxLevel - 1f)).coerceIn(0f, 1f)

    /** Flaşı verilen seviyede (1..maxLevel) yakar. Başarılıysa true. */
    fun setLevel(level: Int): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val i = id ?: return false
            if (maxLevel <= 1) return false
            return runCatching { cm!!.turnOnTorchWithStrengthLevel(i, level.coerceIn(1, maxLevel)); true }.getOrDefault(false)
        }
        return false
    }

    fun off() { val i = id ?: return; runCatching { cm?.setTorchMode(i, false) } }
}

// ---------------------------------------------------------------- Denetleyici
class TorchController(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val scope: CoroutineScope,
    private val anchor: (IslandSettings) -> TorchAnchor,
) {
    private val hw by lazy { TorchHw(ctx) }
    private val handler = Handler(Looper.getMainLooper())
    private var cb: CameraManager.TorchCallback? = null

    private var on = false
    private var islandFree = true          // ada boşta ve kapalı mı (simge yalnızca o zaman görünür)
    private var cardOpen = false
    private var lastApplied = -1

    private val level = mutableFloatStateOf(1f)     // 0..1 parlaklık
    private val spread = mutableFloatStateOf(0.7f)  // 0..1 huzme genişliği (görsel)
    private val mode = mutableIntStateOf(0)         // 0 = yok, 1 = küçük simge, 2 = ışık kartı

    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    private val closeCardRun = Runnable { closeCard() }

    fun start() {
        if (cb != null) return
        val m = ctx.getSystemService(CameraManager::class.java) ?: return
        val c = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != hw.id) return
                on = enabled
                if (enabled) level.floatValue = hw.frac(hw.currentLevel()) else cardOpen = false
                runCatching { refresh() }
            }
        }
        cb = c
        runCatching { m.registerTorchCallback(c, handler) }
        scope.launch { settings.collect { runCatching { refresh() } } }
    }

    fun stop() {
        cb?.let { c -> runCatching { ctx.getSystemService(CameraManager::class.java).unregisterTorchCallback(c) } }
        cb = null
        handler.removeCallbacks(closeCardRun)
        detach()
    }

    /** Pencere türü (erişilebilirlik servisi) değişince yeniden ekler. */
    fun rehost() { detach(); runCatching { refresh() } }

    /** Ada boş ve kapalıysa true: fener simgesi yalnızca o zaman adanın içinde görünür. */
    fun onIslandState(free: Boolean) {
        if (islandFree == free) return
        islandFree = free
        runCatching { refresh() }
    }

    // ---- durum -> pencere
    private fun refresh() {
        val s = settings.value
        val want = on && s.torchIndicator && !s.dotMode
        if (!want) { cardOpen = false; handler.removeCallbacks(closeCardRun); apply(0); return }
        apply(if (cardOpen) 2 else if (islandFree) 1 else 0)
    }

    private fun openCard() {
        if (!on) return
        // Kart açılırken seviye donanımdan okunur (sistemden de değişmiş olabilir)
        level.floatValue = hw.frac(hw.currentLevel())
        cardOpen = true
        poke()
        refresh()
    }

    private fun closeCard() {
        handler.removeCallbacks(closeCardRun)
        if (!cardOpen) return
        cardOpen = false
        refresh()
    }

    /** Kullanıcı etkileşimi: boşta kalma süresini yeniler. */
    private fun poke() {
        handler.removeCallbacks(closeCardRun)
        handler.postDelayed(closeCardRun, CARD_IDLE_MS)
    }

    private fun onLevelDelta(d: Float) {
        if (!hw.supportsLevels) return
        val f = (level.floatValue + d).coerceIn(0f, 1f)
        level.floatValue = f
        val lv = (f * (hw.maxLevel - 1)).roundToInt() + 1
        if (lv != lastApplied) { lastApplied = lv; hw.setLevel(lv) }
    }

    private fun onSpreadDelta(d: Float) { spread.floatValue = (spread.floatValue + d).coerceIn(0f, 1f) }

    private fun turnOff() { handler.removeCallbacks(closeCardRun); cardOpen = false; hw.off() }

    // ---- pencere
    private class Box4(val w: Int, val h: Int, val x: Int, val y: Int)

    private fun apply(m: Int) {
        mode.intValue = m
        if (m == 0) { detach(); return }
        val s = settings.value
        val a = anchor(s)
        val d = ctx.resources.displayMetrics.density
        val sw = wm.currentWindowMetrics.bounds.width()
        val b = if (m == 1) {
            val side = a.h.roundToInt().coerceAtLeast(1)
            Box4((side * 1.25f).roundToInt(), side, (a.cx - a.halfW + 3f * d).roundToInt(), a.top.roundToInt())
        } else {
            val w = (CARD_W_DP * d).roundToInt()
            val h = (CARD_H_DP * d).roundToInt()
            val x = (a.cx - w / 2f).roundToInt().coerceIn((8 * d).roundToInt(), (sw - w - 8 * d).roundToInt().coerceAtLeast((8 * d).roundToInt()))
            // Kart adanın kendisinden büyür: üst kenarı adanın üst kenarıyla aynı (ters adada alt kenarı)
            val y = if (a.inverted) (a.top + a.h - h).roundToInt() else a.top.roundToInt()
            Box4(w, h, x, y)
        }
        val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (m == 2) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0)
        val v0 = view
        val p0 = lp
        if (v0 != null && p0 != null) {
            p0.width = b.w; p0.height = b.h; p0.x = b.x; p0.y = b.y; p0.flags = flags
            runCatching { (host ?: wm).updateViewLayout(v0, p0) }
            return
        }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setOnTouchListener { _, ev ->
                if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) closeCard()
                false
            }
            setContent {
                TorchRoot(
                    mode = mode.intValue,
                    level = level.floatValue,
                    spread = spread.floatValue,
                    supportsLevels = hw.supportsLevels,
                    pillW = anchor(settings.value).halfW * 2f,
                    pillH = anchor(settings.value).h,
                    inverted = anchor(settings.value).inverted,
                    onOpenCard = { openCard() },
                    onLevel = { dv -> poke(); onLevelDelta(dv) },
                    onSpread = { dv -> poke(); onSpreadDelta(dv) },
                    onInteract = { poke() },
                    onOff = { turnOff() },
                )
            }
        }
        fun params(type: Int) = WindowManager.LayoutParams(b.w, b.h, type, flags, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = b.x; y = b.y
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val p = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(v, p); host = h2; lp = p; added = true
            }
        }
        if (!added) added = runCatching {
            val p = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            wm.addView(v, p); host = wm; lp = p
        }.isSuccess
        if (!added) { v.disposeComposition(); return }
        view = v
    }

    private fun detach() {
        val v = view ?: return
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        view = null; host = null; lp = null
    }
}

// ---------------------------------------------------------------- Arayüz
private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
private fun TorchRoot(
    mode: Int,
    level: Float,
    spread: Float,
    supportsLevels: Boolean,
    pillW: Float,
    pillH: Float,
    inverted: Boolean,
    onOpenCard: () -> Unit,
    onLevel: (Float) -> Unit,
    onSpread: (Float) -> Unit,
    onInteract: () -> Unit,
    onOff: () -> Unit,
) {
    when (mode) {
        1 -> TorchBadge(onOpenCard)
        2 -> TorchCard(level, spread, supportsLevels, pillW, pillH, inverted, onLevel, onSpread, onInteract, onOff)
        else -> Unit
    }
}

/** Adanın solundaki küçük, parıltılı fener simgesi. Dokununca ışık kartı açılır. */
@Composable
private fun TorchBadge(onOpen: () -> Unit) {
    val openNow by rememberUpdatedState(onOpen)
    Canvas(
        Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures(onTap = { openNow() }) },
    ) {
        val s = size.minDimension * 0.44f
        val c = center
        drawCircle(
            brush = Brush.radialGradient(0f to TorchLav.copy(alpha = 0.50f), 1f to Color.Transparent, center = c, radius = s * 1.25f),
            radius = s * 1.25f, center = c,
        )
        drawTorchGlyph(c, s, TorchLav, Color.Black)
    }
}

/** Yukarı bakan fener simgesi (geniş başlık + gövde + düğme çentiği). */
private fun DrawScope.drawTorchGlyph(c: Offset, s: Float, color: Color, notch: Color) {
    val head = Path().apply {
        moveTo(c.x - 0.30f * s, c.y - 0.50f * s); lineTo(c.x + 0.30f * s, c.y - 0.50f * s)
        lineTo(c.x + 0.19f * s, c.y - 0.24f * s); lineTo(c.x - 0.19f * s, c.y - 0.24f * s); close()
    }
    drawPath(head, color)
    drawRoundRect(color, Offset(c.x - 0.19f * s, c.y - 0.20f * s), Size(0.38f * s, 0.70f * s), CornerRadius(0.10f * s))
    drawRoundRect(notch, Offset(c.x - 0.045f * s, c.y - 0.06f * s), Size(0.09f * s, 0.20f * s), CornerRadius(0.045f * s))
}

/** Parlaklık göstergesi güneşi: küçük halka + çevresinde 8 nokta (iOS'taki gibi). */
private fun DrawScope.drawSun(c: Offset, r: Float, color: Color) {
    drawCircle(color, r, c, style = Stroke(width = r * 0.30f))
    for (i in 0 until 8) {
        val a = i * (PI / 4.0)
        drawCircle(color, r * 0.20f, Offset(c.x + cos(a).toFloat() * r * 1.85f, c.y + sin(a).toFloat() * r * 1.85f))
    }
}

@Composable
private fun TorchCard(
    level: Float,
    spread: Float,
    supportsLevels: Boolean,
    pillW: Float,
    pillH: Float,
    inverted: Boolean,
    onLevel: (Float) -> Unit,
    onSpread: (Float) -> Unit,
    onInteract: () -> Unit,
    onOff: () -> Unit,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    val levelNow by rememberUpdatedState(onLevel)
    val spreadNow by rememberUpdatedState(onSpread)
    val interactNow by rememberUpdatedState(onInteract)
    val offNow by rememberUpdatedState(onOff)
    val supportsNow by rememberUpdatedState(supportsLevels)

    val d = LocalDensity.current.density
    val a = appear.value
    // Ada hapından tam karta: genişlik, yükseklik ve köşe yarıçapı birlikte büyür
    val curW = lerpF(pillW / d, CARD_W_DP, a)
    val curH = lerpF(pillH / d, CARD_H_DP, a)
    val corner = lerpF(pillH / d / 2f, 44f, a)
    val cardWpx = CARD_W_DP * d
    val cardHpx = CARD_H_DP * d
    val content = smooth(0.30f, 0.85f, a)

    Box(Modifier.fillMaxSize(), contentAlignment = if (inverted) Alignment.BottomCenter else Alignment.TopCenter) {
        Box(
            Modifier
                .size(curW.dp, curH.dp)
                .clip(RoundedCornerShape(corner.dp))
                .background(TorchBg)
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { off ->
                        interactNow()
                        if (off.y > cardHpx * 0.62f) offNow()      // fener simgesine dokununca kapanır
                    })
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { interactNow() },
                        onDrag = { change, drag ->
                            change.consume()
                            spreadNow(drag.x / (cardWpx * 0.9f))                      // sağa / sola: huzme genişliği
                            if (supportsNow) levelNow(-drag.y / (cardHpx * 0.75f))    // yukarı / aşağı: parlaklık
                        },
                    )
                },
        ) {
            Box(
                Modifier
                    .wrapContentSize(align = if (inverted) Alignment.BottomCenter else Alignment.TopCenter, unbounded = true)
                    .requiredSize(CARD_W_DP.dp, CARD_H_DP.dp)
                    .graphicsLayer { alpha = content },
            ) {
                TorchBeam(level, spread)
                TorchOverlayArt(level, spread)
            }
        }
    }
}

private fun beamHalfAngle(spread: Float) = lerpF(3f, 50f, spread)

/** Işık huzmesi: yumuşak kenarlı koni; kenarlarda hafif turuncu / mavi renk kayması (iOS'taki gibi). */
@Composable
private fun TorchBeam(level: Float, spread: Float) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width
        val h = size.height
        val apex = Offset(w / 2f, h * 0.715f)
        val half = beamHalfAngle(spread)
        val len = beamRadius(level, h)
        val k = 0.42f + 0.58f * level
        val f = half / 360f
        val c0 = 0.75f                                    // yukarı yön (sweep'te 270°)
        val fr0 = TorchFringe.copy(alpha = 0f)
        val stops = arrayOf(
            0f to fr0,
            (c0 - 1.25f * f) to fr0,
            (c0 - 1.05f * f) to TorchFringe.copy(alpha = 0.28f),
            (c0 - 0.84f * f) to TorchBlue.copy(alpha = 0.40f),
            (c0 - 0.52f * f) to TorchTeal.copy(alpha = 0.62f),
            (c0 - 0.22f * f) to TorchLav.copy(alpha = 0.86f),
            c0 to Color.White.copy(alpha = 0.97f),
            (c0 + 0.22f * f) to TorchLav.copy(alpha = 0.86f),
            (c0 + 0.52f * f) to TorchTeal.copy(alpha = 0.62f),
            (c0 + 0.84f * f) to TorchBlue.copy(alpha = 0.40f),
            (c0 + 1.05f * f) to TorchFringe.copy(alpha = 0.28f),
            (c0 + 1.25f * f) to fr0,
            1f to fr0,
        )
        drawRect(Brush.sweepGradient(*stops, center = apex), alpha = k)
        // Uzaklaştıkça sönen yumuşak maske
        drawRect(
            Brush.radialGradient(
                0f to Color.White, 0.28f to Color.White.copy(alpha = 0.88f),
                0.66f to Color.White.copy(alpha = 0.30f), 1f to Color.White.copy(alpha = 0f),
                center = apex, radius = len,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

/** Parlaklığa bağlı yay yarıçapı: sonunda kartın dışına taşar (en parlak: ekran ışıkla dolar). */
private fun beamRadius(level: Float, h: Float): Float {
    val base = lerpF(0.14f * h, 0.60f * h, level)
    return base + smooth(0.80f, 1f, level) * 0.75f * h
}

@Composable
private fun TorchOverlayArt(level: Float, spread: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val dpx = 1.dp.toPx()
        val apex = Offset(w / 2f, h * 0.715f)
        val half = beamHalfAngle(spread)
        val rIn = beamRadius(level, h)
        val k = 0.42f + 0.58f * level
        val burst = smooth(0.80f, 1f, level)

        // Sıcak çıkış noktası (fener başının hemen üstü)
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.95f * k), 0.4f to TorchLav.copy(alpha = 0.45f * k),
                1f to TorchLav.copy(alpha = 0f), center = apex, radius = 0.17f * h,
            ),
            radius = 0.17f * h, center = apex,
        )

        // En parlakta ışık tüm kartı doldurur
        if (burst > 0.01f) {
            val fr = lerpF(0.5f * h, 1.15f * h, burst)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.80f * burst), 0.45f to TorchLav.copy(alpha = 0.38f * burst),
                    1f to TorchLav.copy(alpha = 0f), center = apex, radius = fr,
                ),
                radius = fr, center = apex,
            )
        }

        // En parlak + en geniş: ortada büyük ışık topu
        val gl = smooth(0.85f, 1f, level) * smooth(0.55f, 1f, spread)
        if (gl > 0.01f) {
            val r = lerpF(0.10f * w, 0.60f * w, gl)
            val oc = Offset(w / 2f, h * 0.46f)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.95f * gl), 0.35f to TorchLav.copy(alpha = 0.50f * gl),
                    1f to TorchLav.copy(alpha = 0f), center = oc, radius = r,
                ),
                radius = r, center = oc,
            )
        }

        // Noktalı dış ölçek yayı
        val rOuter = 0.60f * h
        var ang = -50f
        while (ang <= 50f) {
            val rad = (ang - 90f) * (PI / 180.0)
            drawCircle(Color.White.copy(alpha = 0.34f), 0.95f * dpx,
                Offset(apex.x + rOuter * cos(rad).toFloat(), apex.y + rOuter * sin(rad).toFloat()))
            ang += 4f
        }

        // Parlaklığa göre büyüyen düz yay + ucunda güneş
        val span = max(half, 5f)
        drawArc(
            color = Color.White.copy(alpha = 0.85f), startAngle = -90f - span, sweepAngle = span * 2f, useCenter = false,
            topLeft = Offset(apex.x - rIn, apex.y - rIn), size = Size(rIn * 2f, rIn * 2f),
            style = Stroke(width = 1.7f * dpx, cap = StrokeCap.Round),
        )
        val endRad = (-90f + span) * (PI / 180.0)
        val ex = cos(endRad).toFloat(); val ey = sin(endRad).toFloat()
        val sunR = lerpF(2.5f, 5.5f, burst) * dpx
        drawSun(Offset(apex.x + (rIn + 4.2f * sunR) * ex, apex.y + (rIn + 4.2f * sunR) * ey), sunR, Color.White.copy(alpha = 0.72f))

        // Fener simgesi ve parıltısı
        val gc = Offset(w / 2f, h * 0.83f)
        drawCircle(
            brush = Brush.radialGradient(0f to TorchLav.copy(alpha = 0.40f * k), 1f to TorchLav.copy(alpha = 0f), center = Offset(gc.x, gc.y - 0.07f * w), radius = 0.36f * w),
            radius = 0.36f * w, center = Offset(gc.x, gc.y - 0.07f * w),
        )
        val glyph = Color(
            red = lerpF(0.56f, 0.93f, level), green = lerpF(0.54f, 0.91f, level), blue = lerpF(0.72f, 1f, level),
        )
        drawTorchGlyph(gc, 0.30f * w, glyph, TorchBg)
    }
}
