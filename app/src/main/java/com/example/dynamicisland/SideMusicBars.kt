package com.example.dynamicisland

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Ayarlar
/** Müzik çalarken telefonun yan kenarlarını kaplayan ince siyah çubuk(lar)ın ayarları. */
@Immutable
data class SideMusicPrefs(
    val enabled: Boolean = false,
    val side: Int = 1,                 // 0 = sol, 1 = sağ, 2 = ikisi de
    val split: Boolean = false,        // yalnızca "ikisi de" iken: sol kenarda tuşlar, sağ kenarda ilerleme çubuğu
    val thicknessDp: Float = 26f,      // çubuğun kalınlığı (dp)
    val lengthFrac: Float = 0.60f,     // çubuğun ekran yüksekliğine oranı (1 = tüm kenar)
    val centerFrac: Float = 0.50f,     // çubuğun dikey ortası (ekran yüksekliğinin oranı; 0 = üst, 1 = alt)
    val transparency: Float = 0f,      // 0 = tam siyah, 1 = tamamen saydam
    val showControls: Boolean = true,  // önceki / duraklat / sonraki
    val showSeek: Boolean = true,      // dikey ilerleme çubuğu
    val controlsAtTop: Boolean = true, // tuşlar çubuğun üstünde mi altında mı
)

/** Ayarlar SharedPreferences'ta saklanır; uygulama ve servis aynı akışı paylaşır. */
object SideMusicStore {
    private const val FILE = "side_music_prefs"
    @Volatile private var state: MutableStateFlow<SideMusicPrefs>? = null

    private fun ensure(ctx: Context): MutableStateFlow<SideMusicPrefs> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(load(ctx.applicationContext)).also { state = it }
        }

    fun flow(ctx: Context): StateFlow<SideMusicPrefs> = ensure(ctx)

    fun update(ctx: Context, change: (SideMusicPrefs) -> SideMusicPrefs) {
        val st = ensure(ctx)
        val n = change(st.value)
        st.value = n
        runCatching {
            ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", n.enabled).putInt("side", n.side).putBoolean("split", n.split)
                .putFloat("thickness", n.thicknessDp).putFloat("length", n.lengthFrac)
                .putFloat("center", n.centerFrac).putFloat("transparency", n.transparency)
                .putBoolean("controls", n.showControls).putBoolean("seek", n.showSeek)
                .putBoolean("controlsTop", n.controlsAtTop).apply()
        }
    }

    private fun load(ctx: Context): SideMusicPrefs {
        val d = SideMusicPrefs()
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return SideMusicPrefs(
            enabled = sp.getBoolean("enabled", d.enabled),
            side = sp.getInt("side", d.side).coerceIn(0, 2),
            split = sp.getBoolean("split", d.split),
            thicknessDp = sp.getFloat("thickness", d.thicknessDp).coerceIn(12f, 48f),
            lengthFrac = sp.getFloat("length", d.lengthFrac).coerceIn(0.2f, 1f),
            centerFrac = sp.getFloat("center", d.centerFrac).coerceIn(0f, 1f),
            transparency = sp.getFloat("transparency", d.transparency).coerceIn(0f, 0.9f),
            showControls = sp.getBoolean("controls", d.showControls),
            showSeek = sp.getBoolean("seek", d.showSeek),
            controlsAtTop = sp.getBoolean("controlsTop", d.controlsAtTop),
        )
    }
}

// ---------------------------------------------------------------- Kenar penceresi
/**
 * Müzik varken ekranın sol / sağ kenarında (ya da ikisinde) dikey, ince siyah bir çubuk gösterir:
 * önceki / duraklat / sonraki tuşları ve dikey ilerleme çubuğu. Her kenar için ayrı bir overlay penceresi açılır.
 */
class SideMusicOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val media: StateFlow<IslandContent.Media?>,
    private val onCommand: (MediaCommand) -> Unit,
) {
    private class Entry(
        val dialog: Dialog, val view: ComposeView,
        val w: Int, val h: Int, val x: Int, val wantY: Int,
    ) {
        var calib = 0          // sistemin içeriği aşağı kaydırdığı miktar (px)
        var tries = 0
        var check: Runnable? = null
    }

    private val entries = mutableListOf<Entry>()
    private var shown: SideMusicPrefs? = null

    fun show(p: SideMusicPrefs) {
        if (shown == p && entries.isNotEmpty()) return        // zaten aynı ayarlarla görünüyor
        hide()
        val lefts = when (p.side) { 0 -> listOf(true); 1 -> listOf(false); else -> listOf(true, false) }
        for (left in lefts) {
            // "Kenarlara ayır": sol kenar yalnızca tuşlar, sağ kenar yalnızca ilerleme çubuğu
            val split = p.side == 2 && p.split
            val controls = p.showControls && (!split || left)
            val seek = p.showSeek && (!split || !left)
            if (!controls && !seek) continue
            runCatching { build(p, left, controls, seek) }.onSuccess { entries += it }
        }
        shown = p
    }

    fun hide() {
        for (e in entries) {
            runCatching {
                e.check?.let { r -> e.view.removeCallbacks(r) }
                e.view.disposeComposition()
                e.view.setViewTreeLifecycleOwner(null)
                e.view.setViewTreeSavedStateRegistryOwner(null)
            }
            runCatching { e.dialog.dismiss() }
        }
        entries.clear()
        shown = null
    }

    private fun build(p: SideMusicPrefs, left: Boolean, controls: Boolean, seek: Boolean): Entry {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()
        val screenH = wm.currentWindowMetrics.bounds.height()
        val w = dp(p.thicknessDp)
        val h = (screenH * p.lengthFrac).roundToInt().coerceIn(dp(p.thicknessDp * 3f), screenH)
        val y = (screenH * p.centerFrac - h / 2f).roundToInt().coerceIn(0, (screenH - h).coerceAtLeast(0))

        val view = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { SideMusicStrip(p, left, controls, seek, media, onCommand) }
        }
        val dlg = Dialog(ContextThemeWrapper(ctx, android.R.style.Theme_Translucent_NoTitleBar))
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        dlg.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dlg.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setBackgroundDrawable(GradientDrawable().apply { setColor(android.graphics.Color.TRANSPARENT) })
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setGravity(Gravity.TOP or (if (left) Gravity.START else Gravity.END))
            setDecorFitsSystemWindows(false)
            decorView.setPadding(0, 0, 0, 0)
            val lp = WindowManager.LayoutParams().also { it.copyFrom(attributes) }
            lp.width = 1; lp.height = 1          // önce küçük aç, boyut / konum gösterildikten sonra verilir (ada ile aynı yöntem)
            lp.x = 0; lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        dlg.window?.decorView?.let { stripInsets(it) }
        dlg.show()
        dlg.window?.decorView?.let { stripInsets(it) }
        val e = Entry(dlg, view, w, h, 0, y)
        applyGeometry(e)
        scheduleCalibrate(e)
        // Kenardan kaydırma (geri hareketi) çubuğa dokunmayı çalmasın diye çubuk alanını hareket dışı bırakmayı dene (sistem 200 dp ile sınırlar)
        view.post { runCatching { view.systemGestureExclusionRects = listOf(android.graphics.Rect(0, 0, e.w, e.h)) } }
        return e
    }

    /** Pencereyi gerçek boyut ve konumuna getirir; [Entry.calib] kadar yukarı alıp o kadar yükseltir. */
    private fun applyGeometry(e: Entry) {
        val win = e.dialog.window ?: return
        // Yeni nesne: aynı nesneyi geri atamak WindowManager güncellemesini tetiklemez
        val lp = WindowManager.LayoutParams().also { it.copyFrom(win.attributes) }
        lp.width = e.w; lp.height = e.h + e.calib
        lp.x = e.x; lp.y = e.wantY - e.calib
        win.attributes = lp
    }

    /** Sistem içeriği aşağı kaydırdıysa farkı ölçüp pencereyi düzeltir. */
    private fun scheduleCalibrate(e: Entry) {
        val r = Runnable {
            if (!e.view.isAttachedToWindow || e.tries >= 3) return@Runnable
            val loc = IntArray(2)
            e.view.getLocationOnScreen(loc)
            val dy = loc[1] - e.wantY
            if (abs(dy) > 2) {
                e.tries++
                e.calib = (e.calib + dy).coerceIn(-300, 600)
                applyGeometry(e)
                scheduleCalibrate(e)
            }
        }
        e.check = r
        e.view.postDelayed(r, 150)
    }

    private fun stripInsets(dv: View) {
        fun zero() {
            dv.setPadding(0, 0, 0, 0)
            (dv as? ViewGroup)?.let { g -> for (i in 0 until g.childCount) g.getChildAt(i).setPadding(0, 0, 0, 0) }
            dv.findViewById<View>(android.R.id.content)?.setPadding(0, 0, 0, 0)
        }
        dv.setOnApplyWindowInsetsListener { _, _ -> zero(); android.view.WindowInsets.CONSUMED }
        zero()
        dv.requestApplyInsets()
    }
}

// ---------------------------------------------------------------- Çubuk arayüzü
private fun fmtMs(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

@Composable
internal fun SideMusicStrip(
    p: SideMusicPrefs,
    left: Boolean,
    showControls: Boolean,
    showSeek: Boolean,
    media: StateFlow<IslandContent.Media?>,
    onCommand: (MediaCommand) -> Unit,
) {
    val m by media.collectAsState()
    val mm = m ?: return
    val full = p.lengthFrac >= 0.995f
    val r = if (full) 0.dp else (p.thicknessDp / 2f).dp
    val shape = if (left) RoundedCornerShape(topEnd = r, bottomEnd = r) else RoundedCornerShape(topStart = r, bottomStart = r)

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(mm.playing, mm.updatedAt, mm.positionMs) {
        now = SystemClock.elapsedRealtime()
        while (mm.playing) { delay(300); now = SystemClock.elapsedRealtime() }
    }
    val dur = mm.durationMs
    val d = drag
    val frac = d ?: if (dur > 0) mm.positionAt(now).toFloat() / dur else 0f

    val btn = p.thicknessDp.dp
    val icon = (p.thicknessDp * 0.52f).dp

    @Composable
    fun Controls() {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            SideMusicBtn(SideCtl.Prev, icon, btn) { onCommand(MediaCommand.Previous) }
            SideMusicBtn(if (mm.playing) SideCtl.Pause else SideCtl.Play, icon, btn) { onCommand(MediaCommand.PlayPause) }
            SideMusicBtn(SideCtl.Next, icon, btn) { onCommand(MediaCommand.Next) }
        }
    }

    @Composable
    fun Seek(mod: Modifier) {
        VerticalSeek(frac.coerceIn(0f, 1f), dur > 0, d != null, p.thicknessDp.dp, mod) { f, commit ->
            if (commit) { drag = null; onCommand(MediaCommand.SeekTo((f * dur).toLong())) } else drag = f
        }
    }

    Column(
        Modifier.fillMaxSize().clip(shape)
            .background(Color.Black.copy(alpha = (1f - p.transparency).coerceIn(0f, 1f))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (showControls && p.controlsAtTop) { Spacer(Modifier.height(6.dp)); Controls() }
        if (showSeek) Seek(Modifier.weight(1f).fillMaxWidth().padding(vertical = 8.dp))
        if (showControls && !p.controlsAtTop) { Controls(); Spacer(Modifier.height(6.dp)) }
    }
}

private enum class SideCtl { Play, Pause, Next, Prev }

@Composable
private fun SideMusicBtn(kind: SideCtl, icon: Dp, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(icon)) {
            val s = this.size.width
            val col = Color.White
            fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val path = Path().apply { moveTo(x1 * s, y1 * s); lineTo(x2 * s, y2 * s); lineTo(x3 * s, y3 * s); close() }
                drawPath(path, col)
            }
            fun bar(x: Float, w: Float) = drawRoundRect(col, Offset(x * s, 0.10f * s), Size(w * s, 0.80f * s), CornerRadius(0.08f * s))
            when (kind) {
                SideCtl.Play -> tri(0.22f, 0.08f, 0.22f, 0.92f, 0.92f, 0.50f)
                SideCtl.Pause -> { bar(0.14f, 0.26f); bar(0.60f, 0.26f) }
                SideCtl.Next -> { tri(0.06f, 0.14f, 0.06f, 0.86f, 0.66f, 0.50f); bar(0.74f, 0.20f) }
                SideCtl.Prev -> { tri(0.94f, 0.14f, 0.94f, 0.86f, 0.34f, 0.50f); bar(0.06f, 0.20f) }
            }
        }
    }
}

/** Dikey ilerleme çubuğu: yukarıdan aşağı dolar; dokununca / sürükleyince o konuma sarar. */
@Composable
private fun VerticalSeek(
    fraction: Float, enabled: Boolean, dragging: Boolean, thickness: Dp, modifier: Modifier,
    onChange: (Float, Boolean) -> Unit,
) {
    var h by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onChange)
    Canvas(
        modifier
            .onSizeChanged { h = it.height.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { cb((it.y / h).coerceIn(0f, 1f), true) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectDragGestures(
                    onDragStart = { last = (it.y / h).coerceIn(0f, 1f); cb(last, false) },
                    onDragEnd = { cb(last, true) },
                    onDragCancel = { cb(last, true) },
                ) { change, _ ->
                    change.consume()
                    last = (change.position.y / h).coerceIn(0f, 1f); cb(last, false)
                }
            }
    ) {
        val th = (if (dragging) thickness.toPx() * 0.30f else thickness.toPx() * 0.20f).coerceAtLeast(3.dp.toPx())
        val x = (size.width - th) / 2f
        drawRoundRect(Color.White.copy(alpha = 0.22f), Offset(x, 0f), Size(th, size.height), CornerRadius(th / 2))
        val fillH = (size.height * fraction).coerceAtLeast(0f)
        if (fillH > 0f) drawRoundRect(Color.White, Offset(x, 0f), Size(th, fillH), CornerRadius(th / 2))
    }
}

// ---------------------------------------------------------------- Ayar bölümü
/** Ayarlar sayfasına eklenen kart (kendi kaydırması yok; üst sütunun içinde çağrılır). */
@Composable
fun SideMusicSettingsSection() {
    val ctx = LocalContext.current
    val p by SideMusicStore.flow(ctx).collectAsState()
    fun upd(f: (SideMusicPrefs) -> SideMusicPrefs) = SideMusicStore.update(ctx, f)
    SectionCard("Kenar müzik çubukları", "Müzik çalarken ekranın kenarında ince siyah çubuk: dikey tuşlar ve ilerleme çubuğu") {
        SwitchRow(
            "Kenar müzik çubuğu", "Müzik varken (çalarken ya da duraklatılmışken) telefonun yan kenarında dikey önceki / duraklat / sonraki tuşları ve ilerleme çubuğu gösterir",
            p.enabled,
        ) { v -> upd { it.copy(enabled = v) } }
        if (p.enabled) {
            SectionDivider()
            SubHeader("Kenar")
            SegmentedTabs(listOf("Sol", "Sağ", "İkisi de"), p.side) { i -> upd { it.copy(side = i) } }
            if (p.side == 2) {
                SwitchRow("Kenarlara ayır", "Sol kenarda yalnızca tuşlar, sağ kenarda yalnızca ilerleme çubuğu olur. Kapalıyken iki kenarda da ikisi birden bulunur",
                    p.split) { v -> upd { it.copy(split = v) } }
            }
            SectionDivider()
            SwitchRow("Önceki / duraklat / sonraki tuşları", null, p.showControls) { v -> upd { it.copy(showControls = v) } }
            SwitchRow("İlerleme çubuğu", "Dikey; dokunarak ya da sürükleyerek şarkıyı ilerlet / geri sar", p.showSeek) { v -> upd { it.copy(showSeek = v) } }
            if (p.showControls && p.showSeek && !(p.side == 2 && p.split)) {
                SubHeader("Tuşların yeri")
                SegmentedTabs(listOf("Üstte", "Altta"), if (p.controlsAtTop) 0 else 1) { i -> upd { it.copy(controlsAtTop = i == 0) } }
            }
            SectionDivider()
            SliderRow("Kalınlık", p.thicknessDp, 12f..48f, "${p.thicknessDp.roundToInt()} dp") { v -> upd { it.copy(thicknessDp = v) } }
            SliderRow("Uzunluk", p.lengthFrac, 0.2f..1f, "%${(p.lengthFrac * 100).roundToInt()}") { v -> upd { it.copy(lengthFrac = v) } }
            if (p.lengthFrac < 0.995f) {
                SliderRow("Dikey konum", p.centerFrac, 0f..1f, "%${(p.centerFrac * 100).roundToInt()}") { v -> upd { it.copy(centerFrac = v) } }
            }
            SubHeader("Çubuk siyahtır; şeffaflığı ayarla")
            SliderRow("Şeffaflık", p.transparency, 0f..0.9f, "%${(p.transparency * 100).roundToInt()}") { v -> upd { it.copy(transparency = v) } }
            Text(
                "Not: Telefonda \"hareketle gezinme\" açıksa kenardan kaydırmak geri hareketi sayılabilir; ilerleme çubuğunu sürüklerken sorun olursa kalınlığı artırıp çubuğun ortasına dokunarak sürükle.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}
