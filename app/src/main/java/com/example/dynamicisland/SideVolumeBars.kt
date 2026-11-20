package com.example.dynamicisland

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import androidx.compose.ui.graphics.StrokeCap
import kotlinx.coroutines.launch
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
/** Telefonun kenarındaki ince siyah çubukta medya sesini artıran / azaltan tuşların ayarları. */
@Immutable
data class SideVolumePrefs(
    val enabled: Boolean = false,
    val side: Int = 0,                 // 0 = sol, 1 = sağ, 2 = ikisi de
    val thicknessDp: Float = 26f,      // çubuğun kalınlığı (dp)
    val lengthDp: Float = 120f,        // çubuğun uzunluğu (dp)
    val centerFrac: Float = 0.50f,     // çubuğun dikey ortası (ekran yüksekliğinin oranı; 0 = üst, 1 = alt)
    val transparency: Float = 0f,      // 0 = tam siyah, 1 = tamamen saydam
    val plusOnTop: Boolean = true,     // artır tuşu üstte mi altta mı
    val onlyWithMusic: Boolean = false // yalnızca müzik varken göster
)

/** Ayarlar SharedPreferences'ta saklanır; uygulama ve servis aynı akışı paylaşır. */
object SideVolumeStore {
    private const val FILE = "side_volume_prefs"
    @Volatile private var state: MutableStateFlow<SideVolumePrefs>? = null

    private fun ensure(ctx: Context): MutableStateFlow<SideVolumePrefs> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(load(ctx.applicationContext)).also { state = it }
        }

    fun flow(ctx: Context): StateFlow<SideVolumePrefs> = ensure(ctx)

    fun update(ctx: Context, change: (SideVolumePrefs) -> SideVolumePrefs) {
        val st = ensure(ctx)
        val n = change(st.value)
        st.value = n
        runCatching {
            ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", n.enabled).putInt("side", n.side)
                .putFloat("thickness", n.thicknessDp).putFloat("length", n.lengthDp)
                .putFloat("center", n.centerFrac).putFloat("transparency", n.transparency)
                .putBoolean("plusTop", n.plusOnTop).putBoolean("onlyMusic", n.onlyWithMusic).apply()
        }
    }

    private fun load(ctx: Context): SideVolumePrefs {
        val d = SideVolumePrefs()
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return SideVolumePrefs(
            enabled = sp.getBoolean("enabled", d.enabled),
            side = sp.getInt("side", d.side).coerceIn(0, 2),
            thicknessDp = sp.getFloat("thickness", d.thicknessDp).coerceIn(12f, 48f),
            lengthDp = sp.getFloat("length", d.lengthDp).coerceIn(60f, 400f),
            centerFrac = sp.getFloat("center", d.centerFrac).coerceIn(0f, 1f),
            transparency = sp.getFloat("transparency", d.transparency).coerceIn(0f, 0.9f),
            plusOnTop = sp.getBoolean("plusTop", d.plusOnTop),
            onlyWithMusic = sp.getBoolean("onlyMusic", d.onlyWithMusic),
        )
    }
}

// ---------------------------------------------------------------- Kenar penceresi
/**
 * Ekranın sol / sağ kenarında (ya da ikisinde) dikey, ince siyah bir çubuk gösterir: sesi artır / azalt tuşları. Her kenar için ayrı bir overlay penceresi açılır.
 */
class SideVolumeOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
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
    private var shown: SideVolumePrefs? = null

    fun show(p: SideVolumePrefs) {
        if (shown == p && entries.isNotEmpty()) return        // zaten aynı ayarlarla görünüyor
        hide()
        val lefts = when (p.side) { 0 -> listOf(true); 1 -> listOf(false); else -> listOf(true, false) }
        for (left in lefts) {
            runCatching { build(p, left) }.onSuccess { entries += it }
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

    private fun build(p: SideVolumePrefs, left: Boolean): Entry {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()
        val screenH = wm.currentWindowMetrics.bounds.height()
        val w = dp(p.thicknessDp)
        val h = dp(p.lengthDp).coerceIn(dp(p.thicknessDp * 2f), screenH)
        val y = (screenH * p.centerFrac - h / 2f).roundToInt().coerceIn(0, (screenH - h).coerceAtLeast(0))

        val view = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { SideVolumeStrip(p, left) }
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
@Composable
internal fun SideVolumeStrip(p: SideVolumePrefs, left: Boolean) {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    fun adjust(dir: Int) {
        runCatching { am?.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0) }
    }
    val r = (p.thicknessDp / 2f).dp
    val shape = if (left) RoundedCornerShape(topEnd = r, bottomEnd = r) else RoundedCornerShape(topStart = r, bottomStart = r)
    val btn = p.thicknessDp.dp
    val icon = (p.thicknessDp * 0.50f).dp
    val up = AudioManager.ADJUST_RAISE
    val down = AudioManager.ADJUST_LOWER

    Column(
        Modifier.fillMaxSize().clip(shape)
            .background(Color.Black.copy(alpha = (1f - p.transparency).coerceIn(0f, 1f))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        if (p.plusOnTop) {
            SideVolumeBtn(true, icon, btn) { adjust(up) }
            SideVolumeBtn(false, icon, btn) { adjust(down) }
        } else {
            SideVolumeBtn(false, icon, btn) { adjust(down) }
            SideVolumeBtn(true, icon, btn) { adjust(up) }
        }
    }
}

/** Dokununca bir adım, basılı tutunca sürekli artır / azalt. */
@Composable
private fun SideVolumeBtn(plus: Boolean, icon: Dp, size: Dp, step: () -> Unit) {
    val scope = rememberCoroutineScope()
    val cb by rememberUpdatedState(step)
    Box(
        Modifier.size(size).pointerInput(Unit) {
            detectTapGestures(
                onPress = {
                    cb()
                    val job = scope.launch { delay(400); while (true) { cb(); delay(110) } }
                    tryAwaitRelease()
                    job.cancel()
                },
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(icon)) {
            val sw = size.width * 0.16f
            val m = size.width / 2f
            drawLine(Color.White, Offset(size.width * 0.08f, m), Offset(size.width * 0.92f, m), strokeWidth = sw, cap = StrokeCap.Round)
            if (plus) drawLine(Color.White, Offset(m, size.width * 0.08f), Offset(m, size.width * 0.92f), strokeWidth = sw, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- Ayar bölümü
@Composable
fun SideVolumeSettingsSection() {
    val ctx = LocalContext.current
    val p by SideVolumeStore.flow(ctx).collectAsState()
    fun upd(f: (SideVolumePrefs) -> SideVolumePrefs) = SideVolumeStore.update(ctx, f)
    SectionCard("Kenar ses tuşları", "Telefonun kenarında ince siyah çubuk: sesi artır / azalt tuşları") {
        SwitchRow(
            "Kenar ses tuşları", "Ekranın yan kenarında dikey, medya sesini artıran ve azaltan iki tuş gösterir. Basılı tutunca ses sürekli değişir",
            p.enabled,
        ) { v -> upd { it.copy(enabled = v) } }
        if (p.enabled) {
            SectionDivider()
            SubHeader("Kenar")
            SegmentedTabs(listOf("Sol", "Sağ", "İkisi de"), p.side) { i -> upd { it.copy(side = i) } }
            SubHeader("Artır tuşunun yeri")
            SegmentedTabs(listOf("Üstte", "Altta"), if (p.plusOnTop) 0 else 1) { i -> upd { it.copy(plusOnTop = i == 0) } }
            SwitchRow("Yalnızca müzik varken", "Müzik çalmıyorken çubuk gizlenir", p.onlyWithMusic) { v -> upd { it.copy(onlyWithMusic = v) } }
            SectionDivider()
            SliderRow("Kalınlık", p.thicknessDp, 12f..48f, "${p.thicknessDp.roundToInt()} dp") { v -> upd { it.copy(thicknessDp = v) } }
            SliderRow("Uzunluk", p.lengthDp, 60f..400f, "${p.lengthDp.roundToInt()} dp") { v -> upd { it.copy(lengthDp = v) } }
            SliderRow("Dikey konum", p.centerFrac, 0f..1f, "%${(p.centerFrac * 100).roundToInt()}") { v -> upd { it.copy(centerFrac = v) } }
            SubHeader("Çubuk siyahtır; şeffaflığı ayarla")
            SliderRow("Şeffaflık", p.transparency, 0f..0.9f, "%${(p.transparency * 100).roundToInt()}") { v -> upd { it.copy(transparency = v) } }
            Text(
                "Kenar müzik çubuğuyla aynı kenarda ve aynı yükseklikte olursa üst üste biner; dikey konumlarını ayır. Sesin değişimi adada görünsün istiyorsan Görünürlük bölümündeki \"Medya sesi göstergesi\" açık olmalı.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}
