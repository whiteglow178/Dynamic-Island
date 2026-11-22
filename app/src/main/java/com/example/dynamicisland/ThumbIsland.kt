package com.example.dynamicisland

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Başparmak modu durumu: ada aşağı indirildi mi ve ekrandaki yeri (ekran oranı olarak; dönünce de doğru kalır). */
object ThumbStore {
    private const val FILE = "island_thumb"
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        _active.value = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean("on", false)
    }

    fun set(ctx: Context, on: Boolean) {
        load(ctx)
        _active.value = on
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()
    }

    /** Kaydedilmiş konum (adanın merkezi, ekranın oranı); yoksa sağ alt, başparmak bölgesi. */
    fun position(ctx: Context): Pair<Float, Float> {
        val p = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return p.getFloat("fx", 0.68f) to p.getFloat("fy", 0.80f)
    }

    fun savePosition(ctx: Context, fx: Float, fy: Float) {
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putFloat("fx", fx.coerceIn(0f, 1f)).putFloat("fy", fy.coerceIn(0f, 1f)).apply()
    }
}

private const val THUMB_PAD_DP = 16f      // gölge ve giriş / çıkış animasyonu için pencere payı

/**
 * Başparmak modu: boştaki ada, kameranın yanından aşağı, başparmağın kolayca eriştiği yere iner ve orada
 * küçük, gölgeli, yüzen bir ada olarak durur. Sürükleyerek yeri değiştirilir; dokununca adanın normal
 * dokunma işi yapılır (favoriler, fotoğraf, müzik paneli...); iki kez dokununca ada kameranın yanına geri döner.
 * Bir olay gelince (bildirim, arama...) olay her zamanki gibi kameranın yanında açılır; olay bitince ada buraya geri iner.
 */
class ThumbIsland(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val registryOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val ui: StateFlow<IslandUiState>,
    private val onTap: () -> Unit,
    private val onReturn: () -> Unit,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private val shown = mutableStateOf(false)
    private val handler = Handler(Looper.getMainLooper())
    private val hideTouch = Runnable { setTouchable(false) }

    private fun dp(v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()

    /** Adayı gösterir ya da (animasyonla) gizler. Gizliyken pencere dokunuş almaz. */
    fun setShown(on: Boolean) {
        if (on) {
            if (view == null && !attach()) return
            handler.removeCallbacks(hideTouch)
            setTouchable(true)
            shown.value = true
        } else {
            if (view == null) return
            shown.value = false
            handler.removeCallbacks(hideTouch)
            handler.postDelayed(hideTouch, 280)
        }
    }

    /** Erişilebilirlik servisi açılıp kapanınca pencere türü değişsin diye yeniden kurar. */
    fun rehost() {
        if (view == null) return
        val was = shown.value
        detach()
        if (was) setShown(true)
    }

    fun detach() {
        handler.removeCallbacksAndMessages(null)
        val v = view ?: return
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        view = null; host = null; lp = null
        shown.value = false
    }

    private fun winSize(s: IslandSettings): Pair<Int, Int> =
        dp(s.compactWidthDp + 2 * THUMB_PAD_DP) to dp(s.compactHeightDp + 2 * THUMB_PAD_DP)

    private fun setTouchable(on: Boolean) {
        val v = view ?: return
        val p = lp ?: return
        val f = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (f == p.flags) return
        p.flags = f
        runCatching { (host ?: wm).updateViewLayout(v, p) }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attach(): Boolean {
        val s = settings.value
        val (ww, wh) = winSize(s)
        val b = wm.currentWindowMetrics.bounds
        val (fx, fy) = ThumbStore.position(ctx)
        val x0 = (b.width() * fx - ww / 2f).roundToInt().coerceIn(0, (b.width() - ww).coerceAtLeast(0))
        val y0 = (b.height() * fy - wh / 2f).roundToInt().coerceIn(0, (b.height() - wh).coerceAtLeast(0))

        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(registryOwner)
            setContent { ThumbPill(shown.value, settings, ui) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            ww, wh, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = x0; y = y0
            fitInsetsTypes = 0
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
        if (!added) { v.disposeComposition(); return false }
        view = v
        v.setOnTouchListener(TouchHandler())
        return true
    }

    /**
     * Dokunuşlar ekran koordinatlarıyla işlenir: pencere parmakla birlikte kaydığı için yerel koordinatlar titrerdi.
     * Sürükleme = taşı, tek dokunuş = adanın dokunma işi, çift dokunuş = kameranın yanına geri dön.
     */
    private inner class TouchHandler : android.view.View.OnTouchListener {
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
        private val dbl = ViewConfiguration.getDoubleTapTimeout().toLong()
        private var dx0 = 0f; private var dy0 = 0f
        private var x0 = 0; private var y0 = 0
        private var dragging = false
        private var lastUp = 0L
        private val single = Runnable { onTap() }

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx0 = e.rawX; dy0 = e.rawY; x0 = p.x; y0 = p.y; dragging = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val mx = e.rawX - dx0; val my = e.rawY - dy0
                    if (!dragging && hypot(mx, my) > slop) { dragging = true; handler.removeCallbacks(single) }
                    if (dragging) {
                        val b = wm.currentWindowMetrics.bounds
                        p.x = (x0 + mx).roundToInt().coerceIn(0, (b.width() - p.width).coerceAtLeast(0))
                        p.y = (y0 + my).roundToInt().coerceIn(0, (b.height() - p.height).coerceAtLeast(0))
                        runCatching { (host ?: wm).updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (dragging) {
                        val b = wm.currentWindowMetrics.bounds
                        ThumbStore.savePosition(ctx, (p.x + p.width / 2f) / b.width(), (p.y + p.height / 2f) / b.height())
                    } else {
                        val now = e.eventTime
                        if (now - lastUp < dbl) {
                            lastUp = 0L
                            handler.removeCallbacks(single)
                            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onReturn()
                        } else {
                            lastUp = now
                            handler.postDelayed(single, dbl)
                        }
                    }
                    dragging = false
                }
                MotionEvent.ACTION_CANCEL -> dragging = false
            }
            return true
        }
    }
}

/** Yüzen küçük ada: gölgeli, adanın rengi / tasarımı; müzik çalıyorsa küçük kapak + ekolayzır. */
@Composable
private fun ThumbPill(shown: Boolean, settingsFlow: StateFlow<IslandSettings>, uiFlow: StateFlow<IslandUiState>) {
    val s by settingsFlow.collectAsState()
    val u by uiFlow.collectAsState()
    val a = remember { Animatable(0f) }
    LaunchedEffect(shown) {
        if (shown) a.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
        else a.animateTo(0f, tween(240, easing = FastOutSlowInEasing))
    }
    val media = u.content as? IslandContent.Media
    val shape = RoundedCornerShape(50)
    val dsPhoto = rememberCustomImage(
        CustomImages.Slot.DESIGN,
        if (s.more.designEnabled && s.more.designStyle == DESIGN_PHOTO) s.more.designPhotoUri else "",
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(s.compactWidthDp.dp, s.compactHeightDp.dp)
                .graphicsLayer {
                    // Gelirken yukarıdan düşüp yaylanır, giderken yukarı (kameraya doğru) uçup küçülür
                    val v = a.value
                    alpha = v.coerceIn(0f, 1f)
                    scaleX = 0.4f + 0.6f * v; scaleY = 0.4f + 0.6f * v
                    translationY = (1f - v) * -28.dp.toPx()
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                .shadow(10.dp, shape)
                .clip(shape)
                .background(Color(s.islandColorArgb)),
            contentAlignment = Alignment.Center,
        ) {
            if (s.more.portalEnabled) PortalScene(s.more.portalScene, s.more.portalDepth, Modifier.fillMaxSize())
            else if (s.more.designEnabled) DesignFill(s.more.designStyle, dsPhoto, Modifier.fillMaxSize())
            if (media != null) {
                val art = media.art
                Box(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    if (art != null) {
                        androidx.compose.foundation.Image(
                            bitmap = art, contentDescription = null,
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = Modifier.align(Alignment.CenterStart).size((s.compactHeightDp - 12f).coerceAtLeast(12f).dp)
                                .clip(RoundedCornerShape(6.dp)),
                        )
                    }
                    Box(Modifier.align(Alignment.CenterEnd)) {
                        Equalizer(Color.White, height = 12.dp, barWidth = 2.5.dp, animate = media.playing)
                    }
                }
            }
        }
    }
}
