package com.example.dynamicisland

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Ayarlar
/** Telefonun kenarında beliren Cevapla / Reddet düğmeleri ayarları. */
@Immutable
data class SideCallPrefs(
    val enabled: Boolean = false,
    val side: Int = 1,               // 0 = sol, 1 = sağ, 2 = ikisi de
    val transparency: Float = 0.25f, // 0 = tam siyah, 1 = tamamen saydam
)

/** Ayarlar SharedPreferences'ta saklanır; uygulama ve servis aynı akışı paylaşır. */
object SideCallStore {
    private const val FILE = "side_call_prefs"
    @Volatile private var state: MutableStateFlow<SideCallPrefs>? = null

    private fun ensure(ctx: Context): MutableStateFlow<SideCallPrefs> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(load(ctx.applicationContext)).also { state = it }
        }

    fun flow(ctx: Context): StateFlow<SideCallPrefs> = ensure(ctx)

    fun update(ctx: Context, change: (SideCallPrefs) -> SideCallPrefs) {
        val st = ensure(ctx)
        val n = change(st.value)
        st.value = n
        runCatching {
            ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean("enabled", n.enabled).putInt("side", n.side)
                .putFloat("transparency", n.transparency).apply()
        }
    }

    private fun load(ctx: Context): SideCallPrefs {
        val d = SideCallPrefs()
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return SideCallPrefs(
            enabled = sp.getBoolean("enabled", d.enabled),
            side = sp.getInt("side", d.side).coerceIn(0, 2),
            transparency = sp.getFloat("transparency", d.transparency).coerceIn(0f, 1f),
        )
    }
}

// ---------------------------------------------------------------- Kenar penceresi
/**
 * Gelen aramada ekranın sol / sağ kenarında (ya da ikisinde) dikey Cevapla / Reddet kapsülü gösterir.
 * Her kenar için ayrı küçük bir overlay penceresi açılır.
 */
class SideCallOverlay(
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
    private var shownStamp = 0
    private var shown: SideCallPrefs? = null

    fun show(p: SideCallPrefs, onAnswer: () -> Unit, onDecline: () -> Unit) {
        if (shown == p && shownStamp == LandscapeZone.stamp && entries.isNotEmpty()) return        // zaten aynı ayarlarla ve aynı dönüşte görünüyor
        hide()
        val lefts = when (p.side) { 0 -> listOf(true); 1 -> listOf(false); else -> listOf(true, false) }
        for (left in lefts) {
            runCatching { build(p, left, onAnswer, onDecline) }.onSuccess { entries += it }
        }
        shown = p
        shownStamp = LandscapeZone.stamp
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

    private fun build(p: SideCallPrefs, left: Boolean, onAnswer: () -> Unit, onDecline: () -> Unit): Entry {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()
        val w = dp(SIDE_W_DP); val h = dp(SIDE_H_DP)
        val screenH = wm.currentWindowMetrics.bounds.height()
        // Yatay modda kamera kenarındaki tuşlar adanın bölgesine binmesin
        val y = LandscapeZone.fit(left, (screenH * SIDE_Y_FRACTION).toInt() - h / 2, h, screenH, dp(8f), shrink = false).first

        val view = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { SideCallPill(p, onAnswer, onDecline) }
        }
        val dlg = Dialog(ContextThemeWrapper(ctx, android.R.style.Theme_Translucent_NoTitleBar))
        dlg.setCancelable(false)
        dlg.setCanceledOnTouchOutside(false)
        dlg.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dlg.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            setBackgroundDrawable(GradientDrawable().apply {
                setColor(android.graphics.Color.TRANSPARENT)
                cornerRadius = dp(SIDE_W_DP / 2f).toFloat()
            })
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
        val e = Entry(dlg, view, w, h, dp(SIDE_MARGIN_DP), y)
        applyGeometry(e)
        scheduleCalibrate(e)
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

    /** Sistem içeriği aşağı kaydırdıysa (içerik alttan kırpılıyordu) farkı ölçüp pencereyi düzeltir. */
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

/** Kapsülün boyutu, kenardan boşluğu ve ekrandaki dikey konumu (ekran yüksekliğinin oranı). */
internal const val SIDE_W_DP = 58f
internal const val SIDE_H_DP = 116f
internal const val SIDE_MARGIN_DP = 6f
internal const val SIDE_Y_FRACTION = 0.58f

private val SideGreen: Color get() = Pal.c("call_answer")
private val SideRed: Color get() = Pal.c("call_decline")

@Composable
internal fun SideCallPill(p: SideCallPrefs, onAnswer: () -> Unit, onDecline: () -> Unit) {
    Column(
        Modifier.fillMaxSize()
            .clip(RoundedCornerShape(50))
            .background(Pal.c("side_pill_bg").copy(alpha = (1f - p.transparency).coerceIn(0f, 1f))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        SideBtn(SideGreen, onAnswer) { PhoneIcon(0f, Modifier.size(23.dp)) }
        SideBtn(SideRed, onDecline) { PhoneIcon(135f, Modifier.size(23.dp)) }
    }
}

@Composable
private fun SideBtn(color: Color, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).background(color, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { icon() }
}

// ---------------------------------------------------------------- Ayar bölümü
/** Ayarlar sayfasına eklenen kartlar (kendi kaydırması yok; üst sütunun içinde çağrılır). */
@Composable
fun SideCallSettingsSection() {
    val ctx = LocalContext.current
    val p by SideCallStore.flow(ctx).collectAsState()
    SectionCard("Kenar tuşları", "Arama gelince telefonun kenarında Cevapla / Reddet tuşları çıksın") {
        SwitchRow(
            "Kenardan cevapla / reddet", "Tek elle kullanım için ekranın kenarında dikey tuşlar gösterir",
            p.enabled,
        ) { v -> SideCallStore.update(ctx) { it.copy(enabled = v) } }
        if (p.enabled) {
            SectionDivider()
            SubHeader("Konum")
            SegmentedTabs(listOf("Sol", "Sağ", "İkisi de"), p.side) { i -> SideCallStore.update(ctx) { it.copy(side = i) } }
            SectionDivider()
            SubHeader("Arka plan siyahtır; şeffaflığı ayarla")
            SliderRow("Şeffaflık", p.transparency, 0f..1f, "%${(p.transparency * 100).roundToInt()}") { v ->
                SideCallStore.update(ctx) { it.copy(transparency = v) }
            }
        }
    }
}
