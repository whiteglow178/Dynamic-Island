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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.drawText
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
    /** Yelpaze menü: favori uygulamalar ve seçileni açma. */
    private val favApps: () -> List<FavApp> = { emptyList() },
    private val onLaunch: (String) -> Unit = {},
) {
    private val fan by lazy { ThumbFan(ctx, lifecycleOwner, registryOwner, wm) }

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
        runCatching { fan.close(false) }
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
        private var fanOpen = false
        private var downView: android.view.View? = null
        private val longPress = Runnable {
            val v = downView ?: return@Runnable
            val p = lp ?: return@Runnable
            val apps = runCatching { favApps() }.getOrDefault(emptyList())
            if (apps.isEmpty()) {
                android.widget.Toast.makeText(ctx, "Yelpaze için önce Favori uygulamaları seç", android.widget.Toast.LENGTH_SHORT).show()
                return@Runnable
            }
            handler.removeCallbacks(single)
            lastUp = 0L
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            fanOpen = fan.open(apps, p.x + p.width / 2f, p.y + p.height / 2f)
        }

        override fun onTouch(v: android.view.View, e: MotionEvent): Boolean {
            val p = lp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dx0 = e.rawX; dy0 = e.rawY; x0 = p.x; y0 = p.y; dragging = false
                    fanOpen = false
                    downView = v
                    handler.removeCallbacks(longPress)
                    if (settings.value.more.thumbFan) handler.postDelayed(longPress, 420L)
                }
                MotionEvent.ACTION_MOVE -> {
                    if (fanOpen) {
                        // Yelpaze açık: parmağın altındaki uygulama vurgulanır (sürükleyip bırak)
                        if (fan.point(e.rawX, e.rawY)) v.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        return true
                    }
                    val mx = e.rawX - dx0; val my = e.rawY - dy0
                    if (!dragging && hypot(mx, my) > slop) { dragging = true; handler.removeCallbacks(single); handler.removeCallbacks(longPress) }
                    if (dragging) {
                        val b = wm.currentWindowMetrics.bounds
                        p.x = (x0 + mx).roundToInt().coerceIn(0, (b.width() - p.width).coerceAtLeast(0))
                        p.y = (y0 + my).roundToInt().coerceIn(0, (b.height() - p.height).coerceAtLeast(0))
                        runCatching { (host ?: wm).updateViewLayout(v, p) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    if (fanOpen) {
                        fanOpen = false
                        val pick = fan.selected()
                        fan.close(pick != null)
                        if (pick != null) onLaunch(pick)
                        dragging = false
                        return true
                    }
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
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (fanOpen) { fanOpen = false; fan.close(false) }
                    dragging = false
                }
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

// ================================================================ Yelpaze menü

private const val FAN_RADIUS_DP = 112f
private const val FAN_ICON_DP = 46f
private const val FAN_STEP_DEG = 34f

/**
 * Başparmak adasına basılı tutunca favori uygulamalar adanın çevresinde yay şeklinde açılır.
 * Parmağı kaldırmadan bir simgeye kaydırıp bırakınca o uygulama açılır. Dokunuş almayan tam ekran pencere;
 * parmak hareketlerini başparmak adası iletir.
 */
internal class ThumbFan(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val registryOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private val apps = mutableStateOf<List<FavApp>>(emptyList())
    private val center = mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
    private val hover = androidx.compose.runtime.mutableIntStateOf(-1)
    private val openState = mutableStateOf(false)
    private val launched = mutableStateOf(false)
    private var points: List<androidx.compose.ui.geometry.Offset> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val remove = Runnable { detach() }

    private fun px(v: Float) = v * ctx.resources.displayMetrics.density

    fun open(list: List<FavApp>, cx: Float, cy: Float): Boolean {
        val b = wm.currentWindowMetrics.bounds
        val n = list.size.coerceAtMost(8)
        val items = list.take(n)
        val r = px(FAN_RADIUS_DP)
        // Ada ekranın üst kısmındaysa yelpaze aşağı, değilse yukarı açılır; kenara yakınsa içe doğru döner
        var base = if (cy < b.height() * 0.32f) 90f else -90f
        val edge = r + px(FAN_ICON_DP) / 2f
        if (cx > b.width() - edge) base += if (base < 0f) -38f else 38f
        else if (cx < edge) base += if (base < 0f) 38f else -38f
        val step = if (n <= 1) 0f else minOf(FAN_STEP_DEG, 170f / (n - 1))
        val start = base - step * (n - 1) / 2f
        points = List(n) { i ->
            val ang = Math.toRadians((start + step * i).toDouble())
            androidx.compose.ui.geometry.Offset(cx + (r * kotlin.math.cos(ang)).toFloat(), cy + (r * kotlin.math.sin(ang)).toFloat())
        }
        apps.value = items
        center.value = androidx.compose.ui.geometry.Offset(cx, cy)
        hover.intValue = -1
        launched.value = false
        handler.removeCallbacks(remove)
        if (view == null && !attach()) return false
        openState.value = true
        return true
    }

    /** Parmak konumu (ekran px). Vurgulanan değiştiyse true (titreşim için). */
    fun point(x: Float, y: Float): Boolean {
        val lim = px(FAN_ICON_DP) * 0.95f
        var best = -1; var bd = Float.MAX_VALUE
        points.forEachIndexed { i, p ->
            val d = hypot(p.x - x, p.y - y)
            if (d < bd) { bd = d; best = i }
        }
        val h = if (bd <= lim) best else -1
        if (h == hover.intValue) return false
        hover.intValue = h
        return h >= 0
    }

    fun selected(): String? = apps.value.getOrNull(hover.intValue)?.pkg

    fun close(withLaunch: Boolean) {
        if (view == null) return
        launched.value = withLaunch
        openState.value = false
        handler.removeCallbacks(remove)
        handler.postDelayed(remove, 260L)
    }

    private fun attach(): Boolean {
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(registryOwner)
            setContent { FanContent() }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(v, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)); host = h2; added = true
            }
        }
        if (!added) added = runCatching {
            wm.addView(v, params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)); host = wm
        }.isSuccess
        if (!added) { v.disposeComposition(); return false }
        view = v
        return true
    }

    private fun detach() {
        val v = view ?: return
        view = null
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        host = null
    }

    @Composable
    private fun FanContent() {
        val open by openState
        val prog = remember { Animatable(0f) }
        LaunchedEffect(open) {
            if (open) prog.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
            else prog.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
        }
        val hov = hover.intValue
        val hoverAnim = remember { Animatable(0f) }
        LaunchedEffect(hov) { hoverAnim.snapTo(0f); hoverAnim.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)) }
        val tm = androidx.compose.ui.text.rememberTextMeasurer()
        val list = apps.value
        val c = center.value
        val pts = points
        val launch = launched.value
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            val pr = prog.value
            if (pr <= 0.001f) return@Canvas
            // Arka plan hafif kararır, adanın çevresinde yumuşak hale
            drawRect(Color.Black.copy(alpha = 0.28f * pr.coerceIn(0f, 1f)))
            val icon = FAN_ICON_DP.dp.toPx()
            pts.forEachIndexed { i, p ->
                val app = list.getOrNull(i) ?: return@forEachIndexed
                // Her simge adadan sırayla, hafif gecikmeyle fırlar
                val local = ((pr - i * 0.04f) / (1f - i * 0.04f).coerceAtLeast(0.2f)).coerceIn(-0.2f, 1.2f)
                val pos = c + (p - c) * local
                val isHov = i == hov
                val chosenFade = if (launch && !open) (if (isHov) 1f else pr) else 1f
                val sc = (0.3f + 0.7f * local.coerceIn(0f, 1.2f)) * (if (isHov) 1f + 0.32f * hoverAnim.value else 1f)
                val sz = icon * sc
                val alpha = (local.coerceIn(0f, 1f) * chosenFade).coerceIn(0f, 1f)
                drawCircle(Color.Black.copy(alpha = 0.35f * alpha), sz * 0.56f, pos + androidx.compose.ui.geometry.Offset(0f, 2.dp.toPx()))
                val ic = app.icon
                if (ic != null) {
                    drawImage(
                        ic,
                        dstOffset = androidx.compose.ui.unit.IntOffset((pos.x - sz / 2f).roundToInt(), (pos.y - sz / 2f).roundToInt()),
                        dstSize = androidx.compose.ui.unit.IntSize(sz.roundToInt().coerceAtLeast(1), sz.roundToInt().coerceAtLeast(1)),
                        alpha = alpha,
                    )
                } else drawCircle(Color.White.copy(alpha = alpha), sz / 2f, pos)
                if (isHov && open) {
                    val t = tm.measure(
                        app.label,
                        androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                        maxLines = 1,
                    )
                    val tw = t.size.width.toFloat(); val th = t.size.height.toFloat()
                    val ty = pos.y - sz / 2f - th - 10.dp.toPx()
                    drawRoundRect(
                        Color.Black.copy(alpha = 0.75f),
                        androidx.compose.ui.geometry.Offset(pos.x - tw / 2f - 8.dp.toPx(), ty - 3.dp.toPx()),
                        androidx.compose.ui.geometry.Size(tw + 16.dp.toPx(), th + 6.dp.toPx()),
                        androidx.compose.ui.geometry.CornerRadius((th + 6.dp.toPx()) / 2f),
                    )
                    drawText(t, topLeft = androidx.compose.ui.geometry.Offset(pos.x - tw / 2f, ty))
                }
            }
        }
    }
}
