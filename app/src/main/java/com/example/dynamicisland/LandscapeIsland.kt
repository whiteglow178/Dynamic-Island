package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * YATAY MOD
 * Telefon yan çevrilince (ayar açıksa) normal ada gizlenir; kamera kenarında DİKEY bir hap çizilir.
 *  - Olaylar (bildirim, şarj, zil modu, arama, müzik...) adanın yanında, simgesi DÜZ duran küçük baloncuk olarak
 *    adadan ayrılarak (yaylı animasyonla) çıkar; bitince adanın içine geri çekilir.
 *  - Müzik adasına / baloncuğuna dokununca genişletilmiş müzik paneli adanın bulunduğu yerden, ekranın içine doğru
 *    YATAY açılır. Favori paneli kamera kenarından DİKEY (uzun) bir panel olarak açılır, kaydırılabilir.
 *  - Dokunma, basılı tutma ve kaydırma servisteki normal ada işleyicilerine bağlanır:
 *      ekranın içine doğru kaydırma = aç, kenara doğru kaydırma = kapat,
 *      kenar boyunca (yukarı / aşağı) kaydırma = sonraki / önceki şarkı.
 * Ayrı, küçük bir pencere kullanır; ana ada penceresine dokunmaz. Olay verisi servisin `ui` akışından gelir.
 */

private const val LAND_MAX_BUBBLES = 3
private const val LAND_FAV_COLS = 2
private const val LAND_FAV_CELL_DP = 76f

/** Servisteki normal ada işleyicileri (yatay modda aynıları kullanılır). */
internal class LandActions(
    val onTap: () -> Unit,
    val onLongPress: () -> Unit,
    val onSwipe: (Int) -> Unit,                 // +1 = ekranın içine doğru (aç), -1 = kenara doğru (kapat)
    val onCommand: (MediaCommand) -> Unit,
    val onOutput: () -> Unit,
    val onLaunch: (String) -> Unit,
    val onOpenMusic: () -> Unit,
    val onSideTap: (Int) -> Unit,
    val onNotifSwipeSide: () -> Unit,
    val onAnswer: () -> Unit,
    val onDecline: () -> Unit,
    val onSpeaker: () -> Unit,
    val onRecStop: () -> Unit,
    val onShazamStop: () -> Unit,
)

/** Yatay modda pencere + çizim ölçüleri (px). Konumlar pencerenin sol-üstüne göredir. */
internal data class LandGeo(
    val winX: Int, val winY: Int, val winW: Int, val winH: Int,
    val islandX: Int, val islandY: Int,
    val islandLen: Int,      // adanın uzun kenarı (dikey)
    val thick: Int,          // adanın / baloncuğun kalınlığı (yatay)
    val bubbleLen: Int,
    val gap: Int,
    val below: Boolean,
    val camLeft: Boolean,
    val panelX: Int, val panelY: Int, val panelW: Int, val panelH: Int,   // panelW = 0: panel yok
)

/** Baloncuk olarak gösterilecek içerik mi? (Favori / çeviri / asistan / boşta gösterilmez.) */
private fun landShows(c: IslandContent): Boolean = when (c) {
    is IslandContent.Notif, is IslandContent.Media, is IslandContent.Call,
    is IslandContent.Status, is IslandContent.ScreenRec, is IslandContent.Shazam -> true
    else -> false
}

private fun landKey(c: IslandContent): String = when (c) {
    is IslandContent.Notif -> "n:" + c.key.ifEmpty { c.pkg + "|" + c.title }
    is IslandContent.Media -> "media"
    is IslandContent.Call -> "call"
    is IslandContent.Status -> "s:" + c.kind.name
    is IslandContent.ScreenRec -> "rec"
    is IslandContent.Shazam -> "shazam"
    else -> ""
}

/** En yeni olay başta: önce ortadaki içerik, sonra yan baloncuktakiler. */
private fun landEvents(u: IslandUiState): List<IslandContent> =
    (listOf(u.content) + u.side).filter(::landShows).distinctBy(::landKey).take(LAND_MAX_BUBBLES)

/**
 * Açık panelin boyutu (dp) ya da panel yoksa null. Bildirim ve durum olayları (şarj, zil...) panel açmaz; yalnızca baloncuk olur.
 * Kamera kenarındaki boşluk (kalınlık kadar) genişliğe dahildir.
 */
private fun landPanelDp(u: IslandUiState, s: IslandSettings, screenWDp: Float, screenHDp: Float): Pair<Float, Float>? {
    if (!u.expanded) return null
    val t = s.compactHeightDp
    val maxW = (screenWDp - 16f).coerceAtLeast(120f)
    val maxH = (screenHDp - 16f).coerceAtLeast(120f)
    val wh: Pair<Float, Float> = when (val c = u.content) {
        is IslandContent.Favorites -> {
            val rows = ceil(c.apps.size / LAND_FAV_COLS.toFloat()).toInt().coerceAtLeast(1)
            (t + 20f + LAND_FAV_COLS * LAND_FAV_CELL_DP) to (rows * FAV_ROW_DP + 24f)
        }
        is IslandContent.Media -> (t + s.expandedWidthDp) to expandedHeightDpFor(c, s)
        is IslandContent.Call -> (t + s.expandedWidthDp) to expandedHeightDpFor(c, s)
        is IslandContent.ScreenRec -> if (c.phase == RecPhase.Recording) (t + REC_PILL_W_DP) to REC_PILL_H_DP else return null
        is IslandContent.Shazam -> if (c.phase == ShazamPhase.Listening) (t + 300f) to SHAZAM_PILL_H_DP else return null
        else -> return null
    }
    return min(wh.first, maxW) to min(wh.second, maxH)
}

private class LandEntry(val key: String, content: IslandContent) {
    var content by mutableStateOf(content)
    var alive by mutableStateOf(true)
}

class LandscapeController internal constructor(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val ui: StateFlow<IslandUiState>,
    private val lyrics: StateFlow<LyricsState>,
    private val scope: CoroutineScope,
    private val actions: LandActions,
    /** Yatay mod etkin / bitti: servis normal adayı gizler / geri gösterir. */
    private val onActive: (Boolean) -> Unit,
) {
    private val dm = ctx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val handler = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private val geoState = mutableStateOf<LandGeo?>(null)
    private var lastActive = false

    // Pencere, animasyon bitene kadar büyük kalır (geri çekilen baloncuk / küçülen panel kesilmesin)
    private var panelHold = 0L
    private var stripHold = 0L
    private var lastPanelPx: Pair<Int, Int>? = null
    private val refreshRunnable = Runnable { refresh() }

    /** Şu an yatay mod çalışıyor mu (servis bildirimi banner yerine baloncuğa yönlendirmek, animasyonları atlamak için sorar). */
    @Volatile var active = false
        private set

    fun start() {
        if (job != null) return
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                refresh()
                handler.postDelayed({ refresh() }, 250)      // döndürme bitince ölçüler oturur
            }
        }
        runCatching { dm.registerDisplayListener(l, handler) }.onSuccess { displayListener = l }
        job = scope.launch { combine(settings, ui) { s, u -> s to u }.collect { runCatching { refresh() } } }
    }

    fun stop() {
        job?.cancel(); job = null
        handler.removeCallbacksAndMessages(null)
        displayListener?.let { runCatching { dm.unregisterDisplayListener(it) } }
        displayListener = null
        hide()
        if (lastActive) { lastActive = false; active = false; onActive(false) }
    }

    /** Erişilebilirlik servisi bağlanıp kopunca pencere türü değişir: yeniden kur. */
    fun rehost() { hide(); refresh() }

    fun refresh() {
        val s = settings.value
        val rot = runCatching { dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0 }.getOrDefault(Surface.ROTATION_0)
        val land = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val on = s.landscapeMode && land
        if (on != lastActive) { lastActive = on; active = on; onActive(on) }
        if (!on) { handler.removeCallbacks(refreshRunnable); hide(); return }

        val d = ctx.resources.displayMetrics.density
        val m = wm.currentWindowMetrics
        val u = ui.value
        val now = SystemClock.uptimeMillis()
        val hasEvents = landEvents(u).isNotEmpty()
        val pd = landPanelDp(u, s, m.bounds.width() / d, m.bounds.height() / d)
        if (pd != null) { lastPanelPx = (pd.first * d).roundToInt() to (pd.second * d).roundToInt(); panelHold = now + 750 }
        if (hasEvents || pd != null) stripHold = now + 850
        val usePanelPx = if (pd != null || now < panelHold) lastPanelPx else null
        val useStrip = hasEvents || usePanelPx != null || now < stripHold
        // Bekleme süresi sürerken pencereyi sonra küçültmek için yeniden dene
        if (pd == null && !hasEvents && now < max(panelHold, stripHold)) {
            handler.removeCallbacks(refreshRunnable)
            handler.postDelayed(refreshRunnable, 120)
        }
        if (!s.showIdle && !useStrip) { hide(); return }

        val g = geometry(s, rot == Surface.ROTATION_90, useStrip, usePanelPx)
        geoState.value = g
        val cur = view
        if (cur == null) { show(g); return }
        val p = lp ?: return
        p.width = g.winW; p.height = g.winH; p.x = g.winX; p.y = g.winY
        runCatching { (host ?: wm).updateViewLayout(cur, p) }
    }

    private fun geometry(s: IslandSettings, camLeft: Boolean, useStrip: Boolean, panelPx: Pair<Int, Int>?): LandGeo {
        val d = ctx.resources.displayMetrics.density
        val m = wm.currentWindowMetrics
        val sw = m.bounds.width(); val sh = m.bounds.height()
        val thick = (s.compactHeightDp * d).roundToInt().coerceAtLeast(2)
        val len = (s.compactWidthDp * d).roundToInt().coerceAtLeast(thick)
        val bl = (thick * 1.7f).roundToInt()
        val gap = (8f * d).roundToInt()
        val extra = LAND_MAX_BUBBLES * (bl + gap)
        // Kamera deliği: sol kenarda (ROTATION_90) ya da sağ kenarda (ROTATION_270)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val cut = m.windowInsets.displayCutout ?: display?.cutout
        val rect = if (camLeft) cut?.boundingRectLeft else cut?.boundingRectRight
        val cx = if (rect != null && !rect.isEmpty) rect.centerX() else if (camLeft) thick / 2 else sw - thick / 2
        val cy = if (rect != null && !rect.isEmpty) rect.centerY() else sh / 2
        val below = s.landscapeBubbleBelow
        val isX = (cx - thick / 2).coerceIn(0, max(sw - thick, 0))
        val isY = cy - len / 2
        var l = isX
        var t = if (useStrip && !below) isY - extra else isY
        var r = isX + thick
        var b = if (useStrip && below) isY + len + extra else isY + len
        var pxS = 0; var pyS = 0; var pw = 0; var ph = 0
        if (panelPx != null) {
            pw = min(panelPx.first, sw); ph = min(panelPx.second, sh)
            val mg = (8f * d).roundToInt()
            pxS = if (camLeft) 0 else sw - pw
            pyS = (cy - ph / 2).coerceIn(mg, max(sh - ph - mg, mg))
            l = min(l, pxS); t = min(t, pyS); r = max(r, pxS + pw); b = max(b, pyS + ph)
        }
        return LandGeo(l, t, r - l, b - t, isX - l, isY - t, len, thick, bl, gap, below, camLeft, pxS - l, pyS - t, pw, ph)
    }

    private fun show(g: LandGeo) {
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { LandscapeWindowContent(geoState, settings, ui, lyrics, actions) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            g.winW, g.winH, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            x = g.winX; y = g.winY
        }
        var added = false
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val p2 = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(v, p2)
                host = h2; lp = p2; added = true
            }
        }
        if (!added) {
            val p3 = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            added = runCatching { wm.addView(v, p3); host = wm; lp = p3 }.isSuccess
        }
        if (added) view = v else v.disposeComposition()
    }

    private fun hide() {
        val v = view ?: return
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        view = null; host = null; lp = null
    }
}

// ------------------------------------------------------------------------------------------------ Dokunma / kaydırma

/**
 * Normal adadaki hareketlerin yatay karşılığı: dokunma, basılı tutma, kaydırma.
 * Ekranın içine doğru kaydırma = +1 (aç), kenara doğru = -1 (kapat). Kenar boyunca kaydırma: müzikte şarkı değiştirir
 * (yukarı = sonraki), bildirimde yana kaydırma sayılır.
 */
@Composable
private fun Modifier.landGestures(
    camLeft: Boolean,
    cfg: IslandSettings,
    isMedia: Boolean,
    isNotif: Boolean,
    onTap: () -> Unit,
    onLong: () -> Unit,
    onSwipe: (Int) -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onNotifSide: () -> Unit,
): Modifier {
    val cfgNow by rememberUpdatedState(cfg)
    val mediaNow by rememberUpdatedState(isMedia)
    val notifNow by rememberUpdatedState(isNotif)
    val tapNow by rememberUpdatedState(onTap)
    val longNow by rememberUpdatedState(onLong)
    val swipeNow by rememberUpdatedState(onSwipe)
    val cmdNow by rememberUpdatedState(onCommand)
    val sideNow by rememberUpdatedState(onNotifSide)
    return this.pointerInput(camLeft) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val slop = 40.dp.toPx()
            val swipeMin = 18.dp.toPx()
            var outcome = 0      // 0 = basılı tutma, 1 = dokunma, 2 = iptal, 3 = içe / dışa kaydırma, 4 = şarkı değiştir, 5 = bildirim yana
            var inward = 0
            var along = 0
            withTimeoutOrNull(cfgNow.longPressMs.toLong()) {
                while (true) {
                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    if (change == null || change.isConsumed) { outcome = 2; break }
                    if (!change.pressed) { outcome = 1; break }
                    val delta = change.position - down.position
                    val dIn = if (camLeft) delta.x else -delta.x
                    if (abs(delta.x) > swipeMin && abs(delta.x) > abs(delta.y) * 1.2f) {
                        outcome = 3; inward = if (dIn > 0f) 1 else -1; break
                    }
                    if (abs(delta.y) > swipeMin && abs(delta.y) > abs(delta.x) * 1.2f) {
                        if (mediaNow && cfgNow.swipeTrackChange) { outcome = 4; along = if (delta.y < 0f) 1 else -1; break }
                        if (notifNow) { outcome = 5; break }
                    }
                    if (delta.getDistance() > slop) { outcome = 2; break }
                }
            }
            when (outcome) {
                0 -> {
                    longNow()
                    do {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    } while (c != null && c.pressed)
                }
                1 -> tapNow()
                3 -> {
                    swipeNow(inward)
                    do {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    } while (c != null && c.pressed)
                }
                4 -> {
                    cmdNow(if (along > 0) MediaCommand.Next else MediaCommand.Previous)
                    do {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    } while (c != null && c.pressed)
                }
                5 -> {
                    sideNow()
                    do {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                    } while (c != null && c.pressed)
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------------------ Çizim

@Composable
private fun LandscapeWindowContent(
    geoState: androidx.compose.runtime.State<LandGeo?>,
    settings: StateFlow<IslandSettings>,
    ui: StateFlow<IslandUiState>,
    lyricsFlow: StateFlow<LyricsState>,
    actions: LandActions,
) {
    val g = geoState.value ?: return
    val s by settings.collectAsState()
    val u by ui.collectAsState()
    val density = LocalDensity.current
    val events = landEvents(u)
    val panelWanted = landPanelDp(u, s, 100_000f, 100_000f) != null
    val camLeft = g.camLeft

    // Panel kapanırken (küçülme animasyonu) son içeriği çizmeye devam etmek için hatırlanır
    val held = remember { arrayOfNulls<IslandContent>(1) }
    if (panelWanted) held[0] = u.content

    val pAnim = remember { Animatable(0f) }
    LaunchedEffect(panelWanted) {
        pAnim.animateTo(if (panelWanted) 1f else 0f, spring(dampingRatio = 0.82f, stiffness = 320f))
    }
    val pv = pAnim.value
    val panelShowing = pv > 0.01f || panelWanted

    // Gösterilen baloncuklar: yeni olay başa eklenir; biten olay animasyonla geri çekilip listeden kalkar
    val shown = remember { mutableStateListOf<LandEntry>() }
    LaunchedEffect(events, panelShowing) {
        val keys = events.map(::landKey)
        events.forEach { c ->
            val k = landKey(c)
            val ex = shown.firstOrNull { it.key == k }
            if (ex == null) shown.add(0, LandEntry(k, c)) else { ex.content = c; ex.alive = true }
        }
        shown.forEach { if (it.key !in keys) it.alive = false }
        if (panelShowing) shown.removeAll { !it.alive }     // panel açıkken baloncuk çizilmez: biten olayı hemen at
    }

    fun dpOf(px: Int): Dp = with(density) { px.toDp() }
    val thickDp = dpOf(g.thick)
    val centerKey = landKey(u.content)
    val isMedia = u.content is IslandContent.Media
    val isNotif = u.content is IslandContent.Notif

    Box(Modifier.fillMaxSize()) {
        // Ada: kamera kenarında dikey hap (dokununca boştayken favori paneli, doluyken merkez olayın işlemi)
        Box(
            Modifier
                .offset { IntOffset(g.islandX, g.islandY) }
                .size(thickDp, dpOf(g.islandLen))
                .clip(RoundedCornerShape(50))
                .background(Color(s.islandColorArgb))
                .landGestures(camLeft, s, isMedia, isNotif, actions.onTap, actions.onLongPress, actions.onSwipe, actions.onCommand, actions.onNotifSwipeSide)
        )

        if (!panelShowing) {
            shown.toList().forEachIndexed { index, e ->
                key(e.key) {
                    val isCenter = e.key == centerKey
                    val sideIdx = if (isCenter) -1 else u.side.indexOfFirst { landKey(it) == e.key }
                    val gesture = if (isCenter) {
                        Modifier.landGestures(camLeft, s, isMedia, isNotif, actions.onTap, actions.onLongPress, actions.onSwipe, actions.onCommand, actions.onNotifSwipeSide)
                    } else {
                        Modifier.landGestures(camLeft, s, false, false, { if (sideIdx >= 0) actions.onSideTap(sideIdx) }, {}, {}, {}, {})
                    }
                    LandBubble(e, index, g, gesture, onGone = { shown.remove(e) })
                }
            }
        }

        // Genişletilmiş panel: adanın bulunduğu yerden büyür (müzik: yatay geniş, favoriler: dikey uzun)
        val c = held[0]
        if (c != null && g.panelW > 0 && pv > 0.01f) {
            val x = g.islandX + (g.panelX - g.islandX) * pv
            val y = g.islandY + (g.panelY - g.islandY) * pv
            val w = g.thick + (g.panelW - g.thick) * pv
            val h = g.islandLen + (g.panelH - g.islandLen) * pv
            val t = pv.coerceIn(0f, 1f)
            val radius = with(density) { ((g.thick / 2f) + (28.dp.toPx() - g.thick / 2f) * t).toDp() }
            val media = c as? IslandContent.Media
            val body = islandPanelColor(s, media?.artColor ?: 0)
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .requiredSize(dpOf(w.roundToInt().coerceAtLeast(1)), dpOf(h.roundToInt().coerceAtLeast(1)))
                    .clip(RoundedCornerShape(radius))
                    .background(body)
                    .landGestures(camLeft, s, c is IslandContent.Media, false, actions.onTap, actions.onLongPress, actions.onSwipe, actions.onCommand, actions.onNotifSwipeSide),
                contentAlignment = if (camLeft) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                Box(
                    Modifier
                        .requiredSize(dpOf(g.panelW), dpOf(g.panelH))
                        .graphicsLayer { alpha = ((pv - 0.45f) / 0.55f).coerceIn(0f, 1f) }
                        // Kamera kenarındaki boşluk içerikten çıkarılır
                        .padding(start = if (camLeft) thickDp else 0.dp, end = if (camLeft) 0.dp else thickDp)
                ) {
                    LandPanelContent(c, s, lyricsFlow, actions, thickDp, camLeft)
                }
            }
        }
    }
}

@Composable
private fun LandPanelContent(
    c: IslandContent,
    s: IslandSettings,
    lyricsFlow: StateFlow<LyricsState>,
    actions: LandActions,
    thick: Dp,
    camLeft: Boolean,
) {
    val rb = rememberRainbowStyle(s)
    val accent = remember(s.accentArgb) { Color(s.accentArgb) }
    when (c) {
        is IslandContent.Media -> {
            val lyrics by lyricsFlow.collectAsState()
            val lyricsOn = s.lyricsEnabled && (s.lyricsInPanel || s.lyricsCompact)
            val frame = rememberLyricFrame(
                c, lyrics, s.lyricsOffsetMs.toLong(),
                active = lyricsOn && lyrics is LyricsState.Ready, fine = s.lyricsFillEnabled,
            )
            val eq = if (s.eqDynamicColor && c.artColor != 0) Color(c.artColor) else Color(s.eqColorArgb)
            MusicPanel(
                m = c, accent = eq, cfg = s, lyrics = lyrics, frame = frame,
                onCommand = actions.onCommand, onOutput = actions.onOutput,
                onOpenMusic = actions.onOpenMusic,
            )
        }
        is IslandContent.Favorites -> LandFavoritesPanel(c, actions.onLaunch)
        is IslandContent.Call -> CallContent(c, accent, rb, actions.onAnswer, actions.onDecline, actions.onSpeaker)
        is IslandContent.ScreenRec -> ScreenRecContent(c, actions.onRecStop)
        is IslandContent.Shazam -> ShazamContent(c, actions.onShazamStop)
        else -> {}
    }
}

/** Dikey (uzun) favori paneli: 2 sütun, kaydırılabilir; uygulama simgeleri düz durur. */
@Composable
private fun LandFavoritesPanel(c: IslandContent.Favorites, onLaunch: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 12.dp),
    ) {
        c.apps.chunked(LAND_FAV_COLS).forEach { row ->
            Row(Modifier.fillMaxWidth().height(FAV_ROW_DP.dp)) {
                row.forEach { app -> FavCell(app, Modifier.weight(1f), onLaunch) }
                repeat(LAND_FAV_COLS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun LandBubble(e: LandEntry, index: Int, g: LandGeo, gesture: Modifier, onGone: () -> Unit) {
    val density = LocalDensity.current
    val p = remember { Animatable(0f) }
    LaunchedEffect(e.alive) {
        if (e.alive) {
            p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f))
        } else {
            p.animateTo(0f, spring(dampingRatio = 1f, stiffness = 380f))
            onGone()
        }
    }
    // Sıra değişince (üstteki biter) baloncuklar yumuşakça kayar
    val slot by animateFloatAsState(index.toFloat(), spring(dampingRatio = 0.8f, stiffness = 400f), label = "landSlot")
    val step = (g.bubbleLen + g.gap).toFloat()
    val merged = g.islandY + (g.islandLen - g.bubbleLen) / 2f                      // adanın içinde, ortada
    val target = if (g.below) g.islandY + g.islandLen + g.gap + slot * step
                 else g.islandY - g.gap - g.bubbleLen - slot * step
    val pv = p.value
    val y = merged + (target - merged) * pv
    val thickDp = with(density) { g.thick.toDp() }
    val lenDp = with(density) { g.bubbleLen.toDp() }

    // Ada ile baloncuk arasındaki "boyun": ayrılırken uzar, ayrılınca incelip kaybolur (sıvı ayrılma etkisi)
    val neckW = g.thick * 0.62f * (1f - pv.coerceIn(0f, 1f))
    if (neckW > 0.5f) {
        val islandEdge = if (g.below) g.islandY + g.islandLen.toFloat() else g.islandY.toFloat()
        val bubbleEdge = if (g.below) y else y + g.bubbleLen
        val top = min(islandEdge, bubbleEdge) - g.thick * 0.2f
        val bot = max(islandEdge, bubbleEdge) + g.thick * 0.2f
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                Color(0xFF000000), Offset(g.islandX + (g.thick - neckW) / 2f, top), Size(neckW, (bot - top).coerceAtLeast(0f)),
                CornerRadius(neckW / 2f),
            )
        }
    }

    Box(
        Modifier
            .offset { IntOffset(g.islandX, y.roundToInt()) }
            .size(thickDp, lenDp)
            .graphicsLayer {
                val sc = 0.55f + 0.45f * pv.coerceIn(0f, 1.2f)
                scaleX = sc; scaleY = sc
                alpha = (pv * 4f).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(50))
            .background(Color.Black)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        // İçerik hep düz (döndürülmez): yatay modda simgeler ekrana göre dik durur
        LandBubbleContent(e.content, g.thick)
    }
}

@Composable
private fun LandBubbleContent(c: IslandContent, thickPx: Int) {
    val d = LocalDensity.current
    val t = with(d) { thickPx.toDp() }
    val ic = t * 0.62f
    when (c) {
        is IslandContent.Notif -> {
            val bmp = c.icon ?: c.avatar
            if (bmp != null) {
                Image(
                    bmp, null, Modifier.size(ic).clip(RoundedCornerShape(22)),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(Modifier.size(ic).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Text(c.title.take(1).uppercase(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        is IslandContent.Media -> {
            val art = c.art
            if (art != null) Image(art, null, Modifier.size(ic).clip(RoundedCornerShape(30)), contentScale = ContentScale.Crop)
            else Text("♪", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        }
        is IslandContent.Call -> Box(
            Modifier.size(ic).clip(CircleShape).background(if (c.isIncoming) Pal.c("call_answer") else Color(0xFF30D158)),
            contentAlignment = Alignment.Center,
        ) { PhoneIcon(0f, Modifier.size(ic * 0.62f)) }
        is IslandContent.Status -> when (c.kind) {
            StatusKind.Charging -> LandBattery(c, t)
            else -> StatusIcon(c, Modifier.size(ic * 0.9f))
        }
        is IslandContent.ScreenRec ->
            if (c.phase == RecPhase.Processing) RecSpinner(ic * 0.8f) else RecDot(c.phase == RecPhase.Recording, ic * 0.55f)
        is IslandContent.Shazam -> {
            val cv = c.cover
            if (cv != null) Image(cv, null, Modifier.size(ic).clip(RoundedCornerShape(30)), contentScale = ContentScale.Crop)
            else Box(Modifier.size(ic).clip(CircleShape).background(Color(0xFF0A84FF)), contentAlignment = Alignment.Center) {
                Text("S", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        else -> {}
    }
}

/** Dikey pil: yeşil dolgu seviyeyi gösterir; altında önce şimşek, kısa süre sonra yüzde sayısı. */
@Composable
private fun LandBattery(c: IslandContent.Status, t: Dp) {
    var showBolt by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) { delay(1400); showBolt = false }
    val green = Pal.c("status_charging")
    val lvl = c.progress.coerceIn(0f, 1f)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Canvas(Modifier.size(width = t * 0.40f, height = t * 0.66f)) {
            val tipH = size.height * 0.07f
            val bodyTop = tipH + 1f
            val bodyH = size.height - bodyTop
            val r = CornerRadius(size.width * 0.3f)
            drawRoundRect(Color(0x40FFFFFF), Offset(size.width * 0.3f, 0f), Size(size.width * 0.4f, tipH * 1.6f), CornerRadius(tipH))
            drawRoundRect(Color(0x33FFFFFF), Offset(0f, bodyTop), Size(size.width, bodyH), r)
            val fh = (bodyH * lvl).coerceAtLeast(size.width * 0.5f)
            drawRoundRect(green, Offset(0f, bodyTop + bodyH - fh), Size(size.width, fh), r)
        }
        if (showBolt) {
            StatusIcon(c, Modifier.size(t * 0.3f))
        } else {
            Text(c.value.filter { it.isDigit() }, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}
