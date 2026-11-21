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
private const val CARD_H_DP = 224f
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
        drawTorchGlyph(c, s * 1.15f, TorchLav)
    }
}

/** Yukarı bakan fener simgesi (iOS): düz başlık, ince aralık, daha ince gövde. c = simgenin merkezi, hg = toplam yükseklik. */
private fun DrawScope.drawTorchGlyph(c: Offset, hg: Float, color: Color) {
    val u = hg / 2.15f                                  // simgenin genişliği
    val top = c.y - hg / 2f
    drawRoundRect(color, Offset(c.x - 0.50f * u, top), Size(u, 0.55f * u), CornerRadius(0.14f * u))
    val bx = c.x - 0.39f * u
    val by = top + 0.68f * u
    drawRoundRect(color, Offset(bx, by), Size(0.78f * u, 1.47f * u), CornerRadius(0.30f * u))
    drawRect(color, Offset(bx, by), Size(0.78f * u, 0.45f * u))   // gövdenin üst köşeleri düz
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
    val corner = lerpF(pillH / d / 2f, 40f, a)
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
                        if (off.y > cardHpx * 0.60f) offNow()      // fener simgesine dokununca kapanır
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

private const val APEX_Y = 0.64f      // ışığın çıktığı nokta (kart yüksekliğine oranla)
private const val DOT_R = 0.36f       // noktalı dış yayın yarıçapı (kart yüksekliğine oranla)
private const val GLYPH_TOP = 0.665f
private const val GLYPH_H = 0.205f

private fun beamHalfAngle(spread: Float) = lerpF(8f, 40f, spread)

/** Parlaklığa bağlı yay / huzme yarıçapı: 0.7'ye kadar noktalı yaya varır, sonra hızla kartın dışına taşar. */
private fun beamRadius(level: Float, h: Float): Float =
    if (level <= 0.7f) lerpF(0.10f * h, DOT_R * h, level / 0.7f)
    else { val t = (level - 0.7f) / 0.3f; DOT_R * h + 1.45f * h * t * t }

private fun lerpColor(a: Color, b: Color, t: Float) =
    Color(lerpF(a.red, b.red, t), lerpF(a.green, b.green, t), lerpF(a.blue, b.blue, t), lerpF(a.alpha, b.alpha, t))

/** Işık huzmesi: fener başından yukarı açılan V. İçi mavi/mor, dış kenarı turuncu renk kaymalı, ucu yumuşakça söner. */
@Composable
private fun TorchBeam(level: Float, spread: Float) {
    Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val w = size.width
        val h = size.height
        val apex = Offset(w / 2f, h * APEX_Y)
        val half = beamHalfAngle(spread)
        val rr = beamRadius(level, h)
        val core = lerpColor(Color(0xFFA9A2DB), Color.White, level)
        val mid = lerpColor(Color(0xFF8F89C8), TorchLav, level)
        val fr = smooth(0.15f, 0.70f, level)               // renk kayması parlaklıkla belirginleşir
        val f = half / 360f
        val c0 = 0.75f                                      // yukarı yön
        val none = Color(0xFFFF8A5C).copy(alpha = 0f)
        val red = Color(0xFFFF7A4D); val org = Color(0xFFFFB067)
        val pur = Color(0xFF9B7BFF); val blu = Color(0xFF6FA8FF)
        val stops = arrayOf(
            0f to none,
            (c0 - 1.40f * f) to none,
            (c0 - 1.14f * f) to red.copy(alpha = 0.30f * fr),
            (c0 - 1.03f * f) to org.copy(alpha = 0.30f * fr),
            (c0 - 0.94f * f) to pur.copy(alpha = 0.20f + 0.25f * fr),
            (c0 - 0.82f * f) to blu.copy(alpha = 0.50f),
            (c0 - 0.60f * f) to mid.copy(alpha = 0.76f),
            c0 to core.copy(alpha = 0.92f),
            (c0 + 0.60f * f) to mid.copy(alpha = 0.76f),
            (c0 + 0.82f * f) to blu.copy(alpha = 0.50f),
            (c0 + 0.94f * f) to pur.copy(alpha = 0.20f + 0.25f * fr),
            (c0 + 1.03f * f) to org.copy(alpha = 0.30f * fr),
            (c0 + 1.14f * f) to red.copy(alpha = 0.30f * fr),
            (c0 + 1.40f * f) to none,
            1f to none,
        )
        drawRect(Brush.sweepGradient(*stops, center = apex), alpha = 0.30f + 0.70f * level)
        // Uzunluk boyunca sönme: ışık fener başında en parlak, yayın olduğu yerde biter
        drawRect(
            Brush.radialGradient(
                0f to Color.White, 0.12f to Color.White.copy(alpha = 0.95f),
                0.50f to Color.White.copy(alpha = 0.42f), 1f to Color.White.copy(alpha = 0f),
                center = apex, radius = rr,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
}

@Composable
private fun TorchOverlayArt(level: Float, spread: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val dpx = 1.dp.toPx()
        val apex = Offset(w / 2f, h * APEX_Y)
        val half = beamHalfAngle(spread)
        val rr = beamRadius(level, h)

        // Çıkış noktasındaki parlak nokta
        val hot = 0.30f + 0.70f * level
        drawCircle(
            brush = Brush.radialGradient(
                0f to Color.White.copy(alpha = 0.95f * hot), 0.45f to TorchLav.copy(alpha = 0.40f * hot),
                1f to TorchLav.copy(alpha = 0f), center = apex, radius = 0.13f * h,
            ),
            radius = 0.13f * h, center = apex,
        )

        // Noktalı dış yay: solid yay ona yaklaşınca silinir
        val dotA = 0.38f * (1f - smooth(0.55f, 0.72f, level))
        if (dotA > 0.01f) {
            val ro = DOT_R * h
            var ang = -41f
            while (ang <= 41.01f) {
                val rad = (ang - 90f) * (PI / 180.0)
                drawCircle(Color.White.copy(alpha = dotA), 1.1f * dpx,
                    Offset(apex.x + ro * cos(rad).toFloat(), apex.y + ro * sin(rad).toFloat()))
                ang += 3.4f
            }
        }

        // Parlaklığı gösteren yay (açısı = huzme genişliği) ve ucundaki güneş
        val arcCol = lerpColor(Color(0xFFB9B0F0), Color(0xFFD3D0E0), level)
        val arcA = 0.92f - 0.30f * level
        drawArc(
            color = arcCol.copy(alpha = arcA), startAngle = -90f - half, sweepAngle = half * 2f, useCenter = false,
            topLeft = Offset(apex.x - rr, apex.y - rr), size = Size(rr * 2f, rr * 2f),
            style = Stroke(width = 1.7f * dpx, cap = StrokeCap.Round),
        )
        val sunRad = (-90f + half + 7f) * (PI / 180.0)
        val sunR = lerpF(2.6f, 5.2f, smooth(0.6f, 1f, level)) * dpx
        val sr = rr * 1.03f + 2f * sunR
        val sx = (apex.x + sr * cos(sunRad).toFloat()).coerceIn(14f * dpx, w - 14f * dpx)
        val sy = (apex.y + sr * sin(sunRad).toFloat()).coerceIn(18f * dpx, h)
        drawSun(Offset(sx, sy), sunR, arcCol.copy(alpha = 0.55f))

        // Fener simgesi: parlaklıkla beyazlaşır
        val glyph = lerpColor(Color(0xFFB4ADE6), Color(0xFFF1EDFF), level)
        val gh = GLYPH_H * h
        drawTorchGlyph(Offset(w / 2f, GLYPH_TOP * h + gh / 2f), gh, glyph)
    }
}
