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
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
 *  - "Olaylar adada da görünsün" ayarı açıkken ilk olay adanın İÇİNDE gösterilir; ada doluyken gelen yeni olaylar baloncuk olur
 *    (en çok 2 baloncuk). Adadaki olay bitince sıradaki en eski baloncuk adaya geri çekilir.
 * Ayrı, küçük bir pencere kullanır; ana ada penceresine dokunmaz. Olay verisi servisin `ui` akışından gelir.
 */

private const val LAND_MAX_BUBBLES = 3
private const val LAND_MAX_BUBBLES_IN_ISLAND = 2     // "Olaylar adada da görünsün" açıkken: 1 olay adada + en çok 2 baloncuk
private const val LAND_FAV_COLS = 2

/**
 * Olay adanın içinde de gösterilsin mi? Kullanıcı ayarı ya da adanın İÇİNDE çizilen özellikler
 * (bildirim yazısı, şarkı sözü, arama / müzik hapı, iPhone dairesi) açıkken olay hep adaya yerleşir.
 */
private fun landInIsland(s: IslandSettings): Boolean =
    s.landscapeEventsInIsland || s.iphoneCircleOn() || (!s.dotMode && (s.notifInline || s.pillCallStyle || s.pillMusicStyle ||
        (s.lyricsEnabled && s.lyricsCompact)))

/** Aynı anda en çok kaç baloncuk: iPhone modunda 1 daire, nokta modunda nokta sayısı - 1, ada doluyken 2, değilse 3. */
private fun landMaxBubbles(s: IslandSettings): Int = when {
    s.iphoneCircleOn() -> if (s.iphoneEvents) 1 else 0
    s.dotMode -> (s.dotCount - 1).coerceIn(1, 4)
    landInIsland(s) -> LAND_MAX_BUBBLES_IN_ISLAND
    else -> LAND_MAX_BUBBLES
}

/** Olay olmasa da çizilen boş yuva sayısı (iPhone dairesi, boştaki noktalar). */
private fun landSlots(s: IslandSettings): Int = when {
    s.iphoneCircleOn() -> 1
    s.dotMode && !s.dotMerge -> (s.dotCount - 1).coerceIn(1, 4)
    else -> 0
}

/** iPhone dairesi adanın altında mı? Dikeyde: portredeki "sol", kamera solda iken alt, sağda iken üsttür. */
private fun landIphoneBelow(s: IslandSettings, camLeft: Boolean): Boolean = (s.iphoneCircleSide == 0) == camLeft

/**
 * Normal adanın kapalı hâldeki yatay (geniş) görünümü olan olaylar: hap tasarımları, adadaki bildirim yazısı, şarkı sözü.
 * Yatay modda ada bu olaylarda kameradan ekranın İÇİNE doğru uzanan yatay bir hap olur. Boyut (dp) ya da null.
 */
private fun landWideDp(c: IslandContent?, s: IslandSettings, compactLyrics: Boolean): Pair<Float, Float>? {
    if (c == null || s.dotMode) return null
    val own = s.compactWidthDp to s.compactHeightDp
    return when (c) {
        is IslandContent.Media -> when {
            s.pillMusicStyle -> pillCompactSizeDp(s, c) ?: own
            compactLyrics && s.lyricsEnabled && s.lyricsCompact -> compactWidthFor(s, true) to s.compactHeightDp
            else -> null
        }
        is IslandContent.Call -> if (s.pillCallStyle) (pillCompactSizeDp(s, c) ?: own) else null
        is IslandContent.Notif -> if (s.notifInline) own else null
        else -> null
    }
}

/** Pencerenin hazır tutması gereken en büyük geniş ada boyutu (dp) ya da null. */
private fun landWideMaxDp(s: IslandSettings): Pair<Float, Float>? {
    if (s.dotMode) return null
    val own = s.compactWidthDp to s.compactHeightDp
    var w = 0f; var h = 0f; var any = false
    fun add(p: Pair<Float, Float>) { w = max(w, p.first); h = max(h, p.second); any = true }
    if (s.pillMusicStyle) add(pillCompactSizeForKind(s, 2) ?: own)
    if (s.pillCallStyle) add(pillCompactSizeForKind(s, 6) ?: own)
    if (s.lyricsEnabled && s.lyricsCompact) add(compactWidthFor(s, true) to s.compactHeightDp)
    if (s.notifInline) add(own)
    return if (any) w to h else null
}

/**
 * Yatay moddaki adanın ekrandaki dikey aralığı: kenar çubukları (ses, müzik, arama tuşları) kamera kenarında bu aralığa
 * binmesin diye uyarlanır. Dönüş (rotation) değişince de çubuklar yeniden kurulur ([stamp]).
 */
object LandscapeZone {
    @Volatile var active = false
    @Volatile var camLeft = false
    @Volatile var top = 0
    @Volatile var bottom = 0
    @Volatile var stamp = 0
        private set

    /** Değişti mi? (true ise kenar çubukları yeniden kurulmalı) */
    fun set(rotation: Int, active: Boolean, camLeft: Boolean, top: Int, bottom: Int): Boolean {
        val st = (rotation + 1) * 1_000_003 + (if (active) 17 else 0) + top * 31 + bottom * 7
        if (st == stamp) return false
        this.active = active; this.camLeft = camLeft; this.top = top; this.bottom = bottom; stamp = st
        return true
    }

    /**
     * [left] kenarındaki bir çubuğun (y, h) değerini ada bölgesinden uzaklaştırır. Kamera kenarı değilse ya da yatay mod yoksa değişmez.
     * [shrink] açıkken sığmıyorsa çubuk kısalır; kapalıyken yalnızca kaydırılır.
     */
    fun fit(left: Boolean, y: Int, h: Int, screenH: Int, gap: Int, shrink: Boolean): Pair<Int, Int> {
        if (!active || left != camLeft) return y to h
        val zt = (top - gap).coerceAtLeast(0)
        val zb = (bottom + gap).coerceAtMost(screenH)
        if (y + h <= zt || y >= zb) return y to h
        val above = zt
        val below = screenH - zb
        return when {
            below >= h -> zb to h
            above >= h -> (zt - h) to h
            shrink && below >= above -> zb to below.coerceAtLeast(1)
            shrink -> 0 to above.coerceAtLeast(1)
            below >= above -> zb.coerceAtMost((screenH - h).coerceAtLeast(0)) to h
            else -> 0 to h
        }
    }
}
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
    val bThick: Int = thick,   // baloncuğun kalınlığı (nokta / iPhone modunda daire çapı)
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
private fun landEventsAll(u: IslandUiState): List<IslandContent> =
    (listOf(u.content) + u.side).filter(::landShows).distinctBy(::landKey)

private fun landEvents(u: IslandUiState): List<IslandContent> = landEventsAll(u).take(LAND_MAX_BUBBLES)

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
    /** Ada bölgesi / dönüş değişti: servis kenar çubuklarını (ses, müzik, arama tuşları) yeniden kurar. */
    private val onZoneChanged: () -> Unit = {},
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
    private var wasPanel = false
    private var wasStrip = false
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

    @Volatile private var userHidden = false
    /** "Seçili uygulamalarda gizle": yatay moddaki ada da gizlenir (dokunuşu da geçirir). */
    fun setHidden(h: Boolean) {
        userHidden = h
        view?.visibility = if (h) android.view.View.INVISIBLE else android.view.View.VISIBLE
    }

    /** Erişilebilirlik servisi bağlanıp kopunca pencere türü değişir: yeniden kur. */
    fun rehost() { hide(); refresh() }

    fun refresh() {
        val s = settings.value
        val rot = runCatching { dm.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0 }.getOrDefault(Surface.ROTATION_0)
        val land = rot == Surface.ROTATION_90 || rot == Surface.ROTATION_270
        val on = s.landscapeMode && land
        if (on != lastActive) { lastActive = on; active = on; onActive(on) }
        val span = if (on) islandSpan(s, rot == Surface.ROTATION_90) else null
        if (LandscapeZone.set(rot, on, rot == Surface.ROTATION_90, span?.first ?: 0, span?.second ?: 0)) runCatching { onZoneChanged() }
        if (!on) { handler.removeCallbacks(refreshRunnable); hide(); return }

        val d = ctx.resources.displayMetrics.density
        val m = wm.currentWindowMetrics
        val u = ui.value
        val now = SystemClock.uptimeMillis()
        val hasEvents = landEvents(u).isNotEmpty()
        val pd = landPanelDp(u, s, m.bounds.width() / d, m.bounds.height() / d)
        // Bekleme süresi, panel / baloncuk KAPANDIĞI andan başlar (eskiden son güncellemeden sayılıyordu:
        // panel uzun süre açık kaldıysa pencere hemen küçülür, küçülme animasyonu hiç görünmezdi)
        val slow = s.animEnabled && s.animStyle == 3
        if (pd != null) { lastPanelPx = (pd.first * d).roundToInt() to (pd.second * d).roundToInt(); wasPanel = true }
        else if (wasPanel) { wasPanel = false; panelHold = now + (if (slow) 1400L else 850L) }
        if (hasEvents || pd != null) wasStrip = true
        else if (wasStrip) { wasStrip = false; stripHold = now + (if (slow) 1400L else 950L) }
        val usePanelPx = if (pd != null || now < panelHold) lastPanelPx else null
        // Boş yuvalar (iPhone dairesi, noktalar) hep çizilir: pencere her zaman şeritli kalır
        val useStrip = hasEvents || usePanelPx != null || now < stripHold || landSlots(s) > 0
        // Bekleme süresi sürerken pencereyi sonra küçültmek için yeniden dene
        if (pd == null && now < max(panelHold, stripHold)) {
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

    /** Adanın (ekranda, mutlak) dikey aralığı: kenar çubukları buna binmesin. */
    private fun islandSpan(s: IslandSettings, camLeft: Boolean): Pair<Int, Int> {
        val d = ctx.resources.displayMetrics.density
        val m = wm.currentWindowMetrics
        val sh = m.bounds.height()
        val thick = (s.compactHeightDp * d).roundToInt().coerceAtLeast(2)
        val len = (s.compactWidthDp * d).roundToInt().coerceAtLeast(thick)
        val cy = anchorCy(s, camLeft, sh, thick)
        return (cy - len / 2) to (cy + len / 2)
    }

    private fun anchorCy(s: IslandSettings, camLeft: Boolean, sh: Int, thick: Int): Int {
        val d = ctx.resources.displayMetrics.density
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val cut = wm.currentWindowMetrics.windowInsets.displayCutout ?: display?.cutout
        val rect = if (camLeft) cut?.boundingRectLeft else cut?.boundingRectRight
        val cy = if (rect != null && !rect.isEmpty) rect.centerY() else sh / 2
        // iPhone modu "ortala": ada + daire ikilisi kameranın ortasında durur
        val shift = if (s.iphoneCircleOn() && s.iphoneCenterGroup) {
            val sp = ((s.iphoneCircleSizeDp() + s.iphoneGapDp) / 2f * d).roundToInt()
            if (landIphoneBelow(s, camLeft)) -sp else sp
        } else 0
        return cy + shift
    }

    private fun geometry(s: IslandSettings, camLeft: Boolean, useStrip: Boolean, panelPx: Pair<Int, Int>?): LandGeo {
        val d = ctx.resources.displayMetrics.density
        val m = wm.currentWindowMetrics
        val sw = m.bounds.width(); val sh = m.bounds.height()
        val thick = (s.compactHeightDp * d).roundToInt().coerceAtLeast(2)
        val len = (s.compactWidthDp * d).roundToInt().coerceAtLeast(thick)
        val circle = s.iphoneCircleOn()
        val bThick = when {
            circle -> (s.iphoneCircleSizeDp() * d).roundToInt()
            s.dotMode -> (s.dotLeftDp * d).roundToInt()
            else -> thick
        }.coerceAtLeast(2)
        val bl = if (circle || s.dotMode) bThick else (thick * 1.7f).roundToInt()
        val gap = ((if (circle) s.iphoneGapDp else if (s.dotMode) s.dotGapDp else 8f) * d).roundToInt()
        val extra = max(landMaxBubbles(s), landSlots(s)) * (bl + gap)
        // Kamera deliği: sol kenarda (ROTATION_90) ya da sağ kenarda (ROTATION_270)
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY)
        val cut = m.windowInsets.displayCutout ?: display?.cutout
        val rect = if (camLeft) cut?.boundingRectLeft else cut?.boundingRectRight
        val cx = if (rect != null && !rect.isEmpty) rect.centerX() else if (camLeft) thick / 2 else sw - thick / 2
        val cy = anchorCy(s, camLeft, sh, thick)
        val below = if (circle) landIphoneBelow(s, camLeft) else s.landscapeBubbleBelow
        val isX = (cx - thick / 2).coerceIn(0, max(sw - thick, 0))
        val isY = cy - len / 2
        var l = isX
        var t = if (useStrip && !below) isY - extra else isY
        var r = isX + thick
        var b = if (useStrip && below) isY + len + extra else isY + len
        // Geniş ada (hap tasarımları, bildirim yazısı, şarkı sözü): kameradan ekranın içine doğru uzanır
        val wide = landWideMaxDp(s)
        if (useStrip && wide != null) {
            val ww = max((wide.first * d).roundToInt(), thick); val wh = (wide.second * d).roundToInt()
            if (camLeft) r = max(r, isX + ww) else l = min(l, isX + thick - ww)
            t = min(t, cy - wh / 2); b = max(b, cy + wh / 2)
        }
        var pxS = 0; var pyS = 0; var pw = 0; var ph = 0
        if (panelPx != null) {
            pw = min(panelPx.first, sw); ph = min(panelPx.second, sh)
            val mg = (8f * d).roundToInt()
            pxS = if (camLeft) 0 else sw - pw
            pyS = (cy - ph / 2).coerceIn(mg, max(sh - ph - mg, mg))
            l = min(l, pxS); t = min(t, pyS); r = max(r, pxS + pw); b = max(b, pyS + ph)
        }
        // Parıltı / zıplamalı animasyon taşması için pay
        val pad = (s.windowPadDp() * d).roundToInt()
        l -= pad; t -= pad; r += pad; b += pad
        return LandGeo(l, t, r - l, b - t, isX - l, isY - t, len, thick, bl, gap, below, camLeft, pxS - l, pyS - t, pw, ph, bThick)
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
        if (added) { view = v; v.visibility = if (userHidden) android.view.View.INVISIBLE else android.view.View.VISIBLE } else v.disposeComposition()
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
    val lyrics by lyricsFlow.collectAsState()
    val density = LocalDensity.current
    val inIsland = landInIsland(s)
    val all = landEventsAll(u)
    val centerKey = landKey(u.content)
    val panelWanted = landPanelDp(u, s, 100_000f, 100_000f) != null
    val camLeft = g.camLeft

    // Panel kapanırken (küçülme animasyonu) son içeriği çizmeye devam etmek için hatırlanır
    val held = remember { arrayOfNulls<IslandContent>(1) }
    if (panelWanted) held[0] = u.content

    val pAnim = remember { Animatable(0f) }
    LaunchedEffect(panelWanted) {
        pAnim.animateTo(if (panelWanted) 1f else 0f, specFor(s, panelWanted, true))
    }
    val pState = remember { derivedStateOf { pAnim.value } }
    val prog = remember { IslandProgress(pState, pState) }
    val pv = pAnim.value
    val panelShowing = pv > 0.01f || panelWanted

    // Adadaki olay: ada dolu kaldığı sürece aynı olayı tutar (yeni olay baloncuk olur);
    // olay bitince sıradaki en eski olay adaya geçer.
    val occ = remember { arrayOfNulls<String>(1) }
    val occKey: String? = if (!inIsland) null else {
        val prev = occ[0]
        (all.firstOrNull { landKey(it) == prev } ?: all.lastOrNull())?.let(::landKey)
    }
    occ[0] = occKey
    val occContent = if (occKey == null) null else all.firstOrNull { landKey(it) == occKey }
    val maxB = landMaxBubbles(s)
    val events = if (inIsland) all.filter { landKey(it) != occKey }.take(maxB) else all.take(maxB)

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
    val accent = remember(s.accentArgb) { Color(s.accentArgb) }
    // Ada gövdesi: normal adadaki gibi (albüm rengi, cam, çerçeve, parıltı, gökkuşağı ayarları)
    val islandMedia = (occContent ?: u.content) as? IslandContent.Media
    val islandArt = if (islandMedia != null && islandMedia.artColor != 0) Color(islandMedia.artColor) else null
    val islandBody = animateColorAsState(bodyColorFor(s, islandArt, accent), if (s.animEnabled) tween(450) else snap(), label = "landBody")
    val islandGlowColor = animateColorAsState(islandArt ?: accent, if (s.animEnabled) tween(450) else snap(), label = "landGlow")
    val islandBorderTint = if (s.borderArtColor && islandArt != null) brightTone(islandArt) else null
    val islandShape = RoundedCornerShape(50)
    val isMedia = u.content is IslandContent.Media
    val isNotif = u.content is IslandContent.Notif

    // Geniş ada: hap tasarımları, adadaki bildirim yazısı, kapalı adada şarkı sözü. Ada kameradan ekranın içine doğru yatay uzanır.
    val occMedia = occContent as? IslandContent.Media
    val compactLy = u.compactLyrics && occMedia != null
    val wideDp = landWideDp(occContent, s, compactLy)
    val lyricFrame = rememberLyricFrame(
        occMedia, lyrics, s.lyricsOffsetMs.toLong(),
        active = compactLy && lyrics is LyricsState.Ready, fine = s.lyricsFillEnabled,
    )
    val wideWpx = if (wideDp != null) max((wideDp.first * density.density).roundToInt(), g.thick) else g.thick
    val wideHpx = if (wideDp != null) (wideDp.second * density.density).roundToInt().coerceAtLeast(g.thick) else g.islandLen
    val morph = if (s.animEnabled) spring<Float>(dampingRatio = 0.8f, stiffness = 300f) else snap<Float>()
    val iw by animateFloatAsState(wideWpx.toFloat(), morph, label = "landIw")
    val ih by animateFloatAsState(wideHpx.toFloat(), morph, label = "landIh")
    val cyPx = g.islandY + g.islandLen / 2f
    val ix = if (camLeft) g.islandX.toFloat() else g.islandX + g.thick - iw
    val iy = cyPx - ih / 2f
    // Baloncuklar adanın o anki (genişlemiş / daralmış) ölçüsüne göre dizilir
    val gNow = g.copy(islandY = iy.roundToInt(), islandLen = ih.roundToInt())

    Box(Modifier.fillMaxSize()) {
        // Ada: kamera kenarında dikey hap (dokununca boştayken favori paneli, doluyken merkez olayın işlemi)
        // Adadaki olay yan listedeyse (merkez değilse) dokunma o olayın baloncuk işlemine gider
        val occSide = if (occKey != null && occKey != centerKey) u.side.indexOfFirst { landKey(it) == occKey } else -1
        val islandGesture = if (occSide >= 0) {
            Modifier.landGestures(camLeft, s, false, false, { actions.onSideTap(occSide) }, {}, {}, {}, {})
        } else {
            Modifier.landGestures(camLeft, s, isMedia, isNotif, actions.onTap, actions.onLongPress, actions.onSwipe, actions.onCommand, actions.onNotifSwipeSide)
        }
        Box(
            Modifier
                .offset { IntOffset(ix.roundToInt(), iy.roundToInt()) }
                .size(dpOf(iw.roundToInt()), dpOf(ih.roundToInt()))
                .islandGlow(s.glowEnabled, islandGlowColor, s.glowIntensity)
                .islandSurface(s, islandBody, islandShape, -1f, islandBorderTint)
                .then(islandGesture),
        ) {
            if (occContent != null && !panelShowing && wideDp == null) {
                // Simge adanın ortasında değil ucunda durur (normal adadaki gibi kenarda): baloncukların dizildiği uçta
                key(occKey) {
                    val a = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { a.animateTo(1f, tween(220)) }
                    val slotH = if (occContent is IslandContent.Status && occContent.kind == StatusKind.Charging) g.bubbleLen else g.thick
                    Box(
                        Modifier.align(if (g.below) Alignment.BottomCenter else Alignment.TopCenter)
                            .size(thickDp, dpOf(slotH)).graphicsLayer { alpha = a.value },
                        contentAlignment = Alignment.Center,
                    ) {
                        LandBubbleContent(occContent, g.thick)
                    }
                }
            }
            if (occContent != null && !panelShowing && wideDp != null) {
                key(occKey, "wide") {
                    val a = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { a.animateTo(1f, tween(260)) }
                    // Kamera deliğinin olduğu uç boş kalır; içerik ekranın içine doğru olan kısımda durur
                    val cw = (wideWpx - g.thick).coerceAtLeast(g.thick)
                    Box(
                        Modifier.align(if (camLeft) Alignment.CenterEnd else Alignment.CenterStart)
                            .requiredSize(dpOf(cw), dpOf(wideHpx)).graphicsLayer { alpha = a.value },
                    ) {
                        LandWideContent(occContent, dpOf(cw), dpOf(wideHpx), s, accent, lyrics, lyricFrame, actions)
                    }
                }
            }
        }

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
                    LandBubble(e, index, gNow, s, accent, gesture, onGone = { shown.remove(e) })
                }
            }
            // Boş yuvalar: iPhone modunda yandaki siyah daire, nokta modunda boştaki noktalar
            for (i in shown.size until landSlots(s)) key("slot$i") { LandSlot(i, gNow, s, accent) }
        }

        // Genişletilmiş panel: adanın bulunduğu yerden büyür (müzik: yatay geniş, favoriler: dikey uzun)
        val c = held[0]
        if (c != null && g.panelW > 0 && pv > 0.01f) {
            val x = ix + (g.panelX - ix) * pv
            val y = iy + (g.panelY - iy) * pv
            val w = iw + (g.panelW - iw) * pv
            val h = ih + (g.panelH - ih) * pv
            val t = pv.coerceIn(0f, 1f)
            val r0 = min(iw, ih) / 2f
            val radius = with(density) { (r0 + (28.dp.toPx() - r0) * t).toDp() }
            val media = c as? IslandContent.Media
            val pArt = if (media != null && media.artColor != 0) Color(media.artColor) else null
            val panelBody = animateColorAsState(bodyColorFor(s, pArt, accent), if (s.animEnabled) tween(450) else snap(), label = "landPanelBody")
            val panelGlow = rememberUpdatedState(pArt ?: accent)
            val panelTint = if (s.borderArtColor && pArt != null) brightTone(pArt) else null
            Box(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .requiredSize(dpOf(w.roundToInt().coerceAtLeast(1)), dpOf(h.roundToInt().coerceAtLeast(1)))
                    .islandGlow(s.glowEnabled, panelGlow, s.glowIntensity)
                    .islandSurface(s, panelBody, RoundedCornerShape(radius), -1f, panelTint)
                    .landGestures(camLeft, s, c is IslandContent.Media, false, actions.onTap, actions.onLongPress, actions.onSwipe, actions.onCommand, actions.onNotifSwipeSide),
                contentAlignment = if (camLeft) Alignment.CenterStart else Alignment.CenterEnd,
            ) {
                LandPanelBackdrops(media, s, accent, prog)
                Box(
                    Modifier
                        .requiredSize(dpOf(g.panelW), dpOf(g.panelH))
                        .graphicsLayer { alpha = ((pv - 0.45f) / 0.55f).coerceIn(0f, 1f) }
                        // Kamera kenarındaki boşluk içerikten çıkarılır
                        .padding(start = if (camLeft) thickDp else 0.dp, end = if (camLeft) 0.dp else thickDp)
                ) {
                    LandPanelContent(c, s, lyricsFlow, actions, thickDp, camLeft, pv)
                }
            }
        }
    }
}

/** Geniş ada içeriği: hap tasarımları, adadaki bildirim yazısı ve kapalı adada şarkı sözü (normal adadakinin aynısı, düz yazı). */
@Composable
private fun LandWideContent(
    c: IslandContent, w: Dp, h: Dp, s: IslandSettings, accent: Color,
    lyrics: LyricsState, frame: LyricFrame, actions: LandActions,
) {
    val rb = rememberRainbowStyle(s)
    Box(Modifier.requiredSize(w, h)) {
        when (c) {
            is IslandContent.Media -> {
                if (s.pillMusicStyle) {
                    val customArt = rememberCustomImage(CustomImages.Slot.ART, if (s.customArtEnabled) s.customArtUri else "")
                    val artVideo = if (s.customArtEnabled) s.customArtVideoUri else ""
                    PillScaled(w, h, PILL_MUSIC_H_DP.dp, PILL_MUSIC_MIN_W_DP.dp) {
                        MusicPillPanel(c, s, actions.onCommand, customArt, actions.onOpenMusic, null, { 0f }, artVideo)
                    }
                } else {
                    // Albüm kapağı + şarkı sözü + ekolayzır (kapalı adadaki şarkı sözü görünümü)
                    val eq = if (s.eqDynamicColor && c.artColor != 0) brightTone(Color(c.artColor)) else Color(s.eqColorArgb)
                    val artSize = (h - 12.dp).coerceAtLeast(14.dp)
                    val art = c.art
                    Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (art != null) {
                            Image(art, null, Modifier.size(artSize).clip(RoundedCornerShape(7.dp)), contentScale = ContentScale.Crop)
                            Spacer(Modifier.size(8.dp))
                        }
                        CompactLyrics(s, lyrics, frame, Modifier.weight(1f).height(h), artColor = c.artColor)
                        Spacer(Modifier.size(8.dp))
                        if (c.playing) Equalizer(eq, rainbow = rb)
                        else Box(Modifier.size(6.dp).background(Pal.c("island_dot_idle"), CircleShape))
                    }
                }
            }
            is IslandContent.Call ->
                PillScaled(w, h, PILL_CALL_H_DP.dp, PILL_CALL_MIN_W_DP.dp) {
                    CallPillContent(c, rb, actions.onAnswer, actions.onDecline, actions.onSpeaker)
                }
            is IslandContent.Notif -> {
                // "Adayı genişletme": solda uygulama simgesi, yanında gönderen (kalın) + bildirim yazısı
                val sz = (h - 10.dp).coerceAtLeast(14.dp)
                val titleSp = (h.value * 0.31f).coerceIn(7f, 12f)
                val textSp = (titleSp - 1f).coerceAtLeast(6.5f)
                val ic = c.icon
                Row(
                    Modifier.fillMaxSize().padding(start = 7.dp, end = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (ic != null) {
                        Image(ic, null, Modifier.size(sz).clip(RoundedCornerShape(sz * 0.26f)), contentScale = ContentScale.Crop)
                    } else {
                        Box(Modifier.size(sz).background(accent, RoundedCornerShape(sz * 0.26f)), contentAlignment = Alignment.Center) {
                            Text(c.title.trim().take(1).uppercase().ifEmpty { "•" }, color = Pal.c("island_letter"),
                                fontWeight = FontWeight.Bold, fontSize = (titleSp + 1f).sp)
                        }
                    }
                    val hasTitle = c.title.isNotBlank()
                    val hasText = c.text.isNotBlank()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        if (hasTitle) {
                            Text(c.title, color = Pal.c("island_text"), fontWeight = FontWeight.Bold, fontSize = titleSp.sp,
                                lineHeight = (titleSp + 1.5f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.rainbowFill(rb))
                        }
                        if (hasText) {
                            Text(c.text.replace('\n', ' '), color = Pal.c("island_text_dim"), fontSize = textSp.sp,
                                lineHeight = (textSp + 1.5f).sp, maxLines = if (hasTitle) 1 else 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.rainbowFill(rb))
                        }
                    }
                }
            }
            else -> {}
        }
    }
}

/** Boş yuva: iPhone modundaki siyah daire / nokta modundaki boş nokta. Baloncukla aynı konum formülünü kullanır. */
@Composable
private fun LandSlot(slot: Int, g: LandGeo, s: IslandSettings, accent: Color) {
    val density = LocalDensity.current
    val step = (g.bubbleLen + g.gap).toFloat()
    val y = if (g.below) g.islandY + g.islandLen + g.gap + slot * step else g.islandY - g.gap - g.bubbleLen - slot * step
    val x = g.islandX + (g.thick - g.bThick) / 2f
    val body = rememberUpdatedState(bodyColorFor(s, null, accent))
    val glow = rememberUpdatedState(accent)
    Box(
        Modifier
            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
            .size(with(density) { g.bThick.toDp() }, with(density) { g.bubbleLen.toDp() })
            .islandGlow(s.glowEnabled, glow, s.glowIntensity)
            .islandSurface(s, body, RoundedCornerShape(50), -1f, null)
    )
}

@Composable
private fun LandPanelContent(
    c: IslandContent,
    s: IslandSettings,
    lyricsFlow: StateFlow<LyricsState>,
    actions: LandActions,
    thick: Dp,
    camLeft: Boolean,
    pv: Float = 1f,
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
            val eq = if (s.eqDynamicColor && c.artColor != 0) brightTone(Color(c.artColor)) else Color(s.eqColorArgb)
            val customArt = rememberCustomImage(CustomImages.Slot.ART, if (s.customArtEnabled) s.customArtUri else "")
            val artVideo = if (s.customArtEnabled) s.customArtVideoUri else ""
            MusicPanel(
                m = c, accent = eq, cfg = s, lyrics = lyrics, frame = frame,
                onCommand = actions.onCommand, onOutput = actions.onOutput,
                customArt = customArt, onOpenMusic = actions.onOpenMusic, artVideo = artVideo,
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
private fun LandBubble(e: LandEntry, index: Int, g: LandGeo, s: IslandSettings, accent: Color, gesture: Modifier, onGone: () -> Unit) {
    val density = LocalDensity.current
    val p = remember { Animatable(0f) }
    val animOn = s.animEnabled
    LaunchedEffect(e.alive) {
        if (e.alive) {
            p.animateTo(1f, if (animOn) spring(dampingRatio = 0.55f, stiffness = 260f) else snap())
        } else {
            p.animateTo(0f, if (animOn) spring(dampingRatio = 1f, stiffness = 380f) else snap())
            onGone()
        }
    }
    // Sıra değişince (üstteki biter) baloncuklar yumuşakça kayar
    val slot by animateFloatAsState(index.toFloat(), if (animOn) spring(dampingRatio = 0.8f, stiffness = 400f) else snap(), label = "landSlot")
    // Baloncuk gövdesi de adayla aynı görünüm ayarlarını kullanır
    val bm = e.content as? IslandContent.Media
    val bArt = if (bm != null && bm.artColor != 0) Color(bm.artColor) else null
    val bodyTarget = bodyColorFor(s, bArt, accent)
    val bodyState = rememberUpdatedState(bodyTarget)
    val glowState = rememberUpdatedState(bArt ?: accent)
    val borderTint = if (s.borderArtColor && bArt != null) brightTone(bArt) else null
    val step = (g.bubbleLen + g.gap).toFloat()
    val merged = g.islandY + (g.islandLen - g.bubbleLen) / 2f                      // adanın içinde, ortada
    val target = if (g.below) g.islandY + g.islandLen + g.gap + slot * step
                 else g.islandY - g.gap - g.bubbleLen - slot * step
    val pv = p.value
    val y = merged + (target - merged) * pv
    val thickDp = with(density) { g.bThick.toDp() }
    val lenDp = with(density) { g.bubbleLen.toDp() }
    val bx = g.islandX + (g.thick - g.bThick) / 2f

    // Ada ile baloncuk arasındaki "boyun": ayrılırken uzar, ayrılınca incelip kaybolur (sıvı ayrılma etkisi)
    val neckW = g.thick * 0.62f * (1f - pv.coerceIn(0f, 1f))
    if (neckW > 0.5f) {
        val islandEdge = if (g.below) g.islandY + g.islandLen.toFloat() else g.islandY.toFloat()
        val bubbleEdge = if (g.below) y else y + g.bubbleLen
        val top = min(islandEdge, bubbleEdge) - g.thick * 0.2f
        val bot = max(islandEdge, bubbleEdge) + g.thick * 0.2f
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                bodyTarget.copy(alpha = 1f), Offset(g.islandX + (g.thick - neckW) / 2f, top), Size(neckW, (bot - top).coerceAtLeast(0f)),
                CornerRadius(neckW / 2f),
            )
        }
    }

    Box(
        Modifier
            .offset { IntOffset(bx.roundToInt(), y.roundToInt()) }
            .size(thickDp, lenDp)
            .graphicsLayer {
                val sc = 0.55f + 0.45f * pv.coerceIn(0f, 1.2f)
                scaleX = sc; scaleY = sc
                alpha = (pv * 4f).coerceIn(0f, 1f)
            }
            .islandGlow(s.glowEnabled, glowState, s.glowIntensity)
            .islandSurface(s, bodyState, RoundedCornerShape(50), -1f, borderTint)
            .then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        // İçerik hep düz (döndürülmez): yatay modda simgeler ekrana göre dik durur
        LandBubbleContent(e.content, g.bThick)
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

/**
 * Genişletilmiş panelin arka planı: albüm kapağı arka planı, galeriden fotoğraf / video ve alt kenardaki hareketli ışık.
 * Normal adadaki "Albüm kapağı arka planı", "Özel arka plan" ve "Müzik ışığı" ayarlarının yatay mod karşılığı.
 */
@Composable
private fun LandPanelBackdrops(media: IslandContent.Media?, s: IslandSettings, accent: Color, prog: IslandProgress) {
    val bgOn = s.customBgEnabled
    // Yatay panel tek parça olduğu için ayrı fotoğraf seçiliyse açık hâl fotoğrafı (yoksa kapalı hâl) kullanılır
    val uri = if (!bgOn) "" else if (!s.customBgSeparate) s.customBgBothUri else s.customBgExpandedUri.ifEmpty { s.customBgCompactUri }
    val slot = if (!s.customBgSeparate) CustomImages.Slot.BG_BOTH
               else if (s.customBgExpandedUri.isNotEmpty()) CustomImages.Slot.BG_EXPANDED else CustomImages.Slot.BG_COMPACT
    val bg = rememberCustomImage(slot, uri)
    val bgVideo = if (bgOn) s.customBgVideoUri else ""
    val art = media?.art
    if (s.artBgEnabled && art != null && bg == null && bgVideo.isEmpty()) ArtBackdrop(art, true, prog)
    if (bgVideo.isNotEmpty()) {
        VideoBackdrop(bgVideo, s.customBgDim)
    } else if (bg != null) {
        Box(Modifier.fillMaxSize()) {
            Image(bitmap = bg, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Pal.c("island_scrim").copy(alpha = s.customBgDim.coerceIn(0f, 0.9f))))
        }
    }
    if (media != null && s.musicBottomFx) {
        val artColor = if (media.artColor != 0) Color(media.artColor) else null
        MusicBottomFx(artColor ?: accent, media.playing)
    }
}
