package com.example.dynamicisland

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.roundToInt

/*
 * YATAY MOD
 * Telefon yan çevrilince (ayar açıksa) normal ada gizlenir; kamera kenarında DİKEY bir hap çizilir.
 * Olaylar (bildirim, şarj, zil modu, arama, müzik...) adanın yanında, simgesi DÜZ duran küçük baloncuk olarak
 * adadan ayrılarak (yaylı animasyonla) çıkar; bitince adanın içine geri çekilir.
 * Ayrı, küçük bir pencere kullanır; ana ada penceresine dokunmaz. Olay verisi servisin `ui` akışından gelir.
 */

private const val LAND_MAX_BUBBLES = 3

/** Yatay modda pencere + çizim ölçüleri (px). Pencere yalnızca adayı ve baloncukları kapsar. */
internal data class LandGeo(
    val winX: Int, val winY: Int, val winW: Int, val winH: Int,
    val islandTop: Int,      // pencerenin üstüne göre adanın üst kenarı
    val islandLen: Int,      // adanın uzun kenarı (dikey)
    val thick: Int,          // adanın / baloncuğun kalınlığı (yatay)
    val bubbleLen: Int,
    val gap: Int,
    val below: Boolean,
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

private class LandEntry(val key: String, content: IslandContent) {
    var content by mutableStateOf(content)
    var alive by mutableStateOf(true)
}

class LandscapeController(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val ui: StateFlow<IslandUiState>,
    private val scope: CoroutineScope,
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
    private var lastTouchable = false

    /** Şu an yatay mod çalışıyor mu (servis bildirimi banner yerine baloncuğa yönlendirmek için sorar). */
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
        if (!on) { hide(); return }
        val g = geometry(s, rot == Surface.ROTATION_90)
        geoState.value = g
        val touchable = landEvents(ui.value).isNotEmpty()
        val cur = view
        if (cur == null) { show(g, touchable); return }
        val p = lp ?: return
        applyGeo(p, g, touchable)
        runCatching { (host ?: wm).updateViewLayout(cur, p) }
        lastTouchable = touchable
    }

    private fun geometry(s: IslandSettings, camLeft: Boolean): LandGeo {
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
        val h = len + extra
        val below = s.landscapeBubbleBelow
        val rawY = if (below) cy - len / 2 else cy - len / 2 - extra
        val y0 = rawY.coerceIn(0, (sh - h).coerceAtLeast(0))
        val x0 = (cx - thick / 2).coerceIn(0, (sw - thick).coerceAtLeast(0))
        return LandGeo(x0, y0, thick, h, (cy - len / 2) - y0, len, thick, bl, gap, below)
    }

    private fun applyGeo(p: WindowManager.LayoutParams, g: LandGeo, touchable: Boolean) {
        p.width = g.winW; p.height = g.winH; p.x = g.winX; p.y = g.winY
        val nt = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (touchable) p.flags and nt.inv() else p.flags or nt
    }

    private fun show(g: LandGeo, touchable: Boolean) {
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { LandscapeWindowContent(geoState, settings, ui, ctx) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, type,
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
            applyGeo(this, g, touchable)
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
        if (added) { view = v; lastTouchable = touchable } else v.disposeComposition()
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

// ------------------------------------------------------------------------------------------------ Çizim

@Composable
private fun LandscapeWindowContent(
    geoState: androidx.compose.runtime.State<LandGeo?>,
    settings: StateFlow<IslandSettings>,
    ui: StateFlow<IslandUiState>,
    ctx: Context,
) {
    val g = geoState.value ?: return
    val s by settings.collectAsState()
    val u by ui.collectAsState()
    val density = LocalDensity.current
    val events = landEvents(u)

    // Gösterilen baloncuklar: yeni olay başa eklenir; biten olay animasyonla geri çekilip listeden kalkar
    val shown = remember { mutableStateListOf<LandEntry>() }
    LaunchedEffect(events.map(::landKey), events) {
        val keys = events.map(::landKey)
        events.forEach { c ->
            val k = landKey(c)
            val ex = shown.firstOrNull { it.key == k }
            if (ex == null) shown.add(0, LandEntry(k, c)) else { ex.content = c; ex.alive = true }
        }
        shown.forEach { if (it.key !in keys) it.alive = false }
    }

    Box(Modifier.fillMaxSize()) {
        val thickDp = with(density) { g.thick.toDp() }
        val lenDp = with(density) { g.islandLen.toDp() }
        // Ada: kamera kenarında dikey hap
        Box(
            Modifier
                .offset { IntOffset(0, g.islandTop) }
                .size(thickDp, lenDp)
                .clip(RoundedCornerShape(50))
                .background(Color(s.islandColorArgb))
        )
        shown.toList().forEachIndexed { index, e ->
            LandBubble(e, index, g, onGone = { shown.remove(e) }, ctx = ctx)
        }
    }
}

@Composable
private fun LandBubble(e: LandEntry, index: Int, g: LandGeo, onGone: () -> Unit, ctx: Context) {
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
    val merged = g.islandTop + (g.islandLen - g.bubbleLen) / 2f                      // adanın içinde, ortada
    val target = if (g.below) g.islandTop + g.islandLen + g.gap + slot * step
                 else g.islandTop - g.gap - g.bubbleLen - slot * step
    val pv = p.value
    val y = merged + (target - merged) * pv
    val thickDp = with(density) { g.thick.toDp() }
    val lenDp = with(density) { g.bubbleLen.toDp() }

    // Ada ile baloncuk arasındaki "boyun": ayrılırken uzar, ayrılınca incelip kaybolur (sıvı ayrılma etkisi)
    val neckW = g.thick * 0.62f * (1f - pv.coerceIn(0f, 1f))
    if (neckW > 0.5f) {
        val islandEdge = if (g.below) g.islandTop + g.islandLen.toFloat() else g.islandTop.toFloat()
        val bubbleEdge = if (g.below) y else y + g.bubbleLen
        val top = minOf(islandEdge, bubbleEdge) - g.thick * 0.2f
        val bot = maxOf(islandEdge, bubbleEdge) + g.thick * 0.2f
        Canvas(Modifier.fillMaxSize()) {
            drawRoundRect(
                Color(0xFF000000), Offset((size.width - neckW) / 2f, top), Size(neckW, (bot - top).coerceAtLeast(0f)),
                CornerRadius(neckW / 2f),
            )
        }
    }

    Box(
        Modifier
            .offset { IntOffset(0, y.roundToInt()) }
            .size(thickDp, lenDp)
            .graphicsLayer {
                val sc = 0.55f + 0.45f * pv.coerceIn(0f, 1.2f)
                scaleX = sc; scaleY = sc
                alpha = (pv * 4f).coerceIn(0f, 1f)
            }
            .clip(RoundedCornerShape(50))
            .background(Color.Black)
            .clickable(remember { MutableInteractionSource() }, null) { openEvent(ctx, e.content) },
        contentAlignment = Alignment.Center,
    ) {
        // İçerik hep düz (döndürülmez): yatay modda simgeler ekrana göre dik durur
        LandBubbleContent(e.content, g.thick)
    }
}

private fun openEvent(ctx: Context, c: IslandContent) {
    runCatching {
        when (c) {
            is IslandContent.Notif -> {
                val pi: PendingIntent? = c.intent
                if (pi != null) pi.send()
                else ctx.packageManager.getLaunchIntentForPackage(c.pkg)?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); ctx.startActivity(it)
                }
            }
            else -> {}
        }
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
private fun LandBattery(c: IslandContent.Status, t: androidx.compose.ui.unit.Dp) {
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
