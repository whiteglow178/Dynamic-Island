package com.example.dynamicisland

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * HIZLI PANEL
 *
 *  Kapalı adayı sağa ya da sola kaydırınca ekranın KENARINA yapışık, dikey kaydırılabilir bir panel açılır
 *  (iPhone'daki Kamera Düğmesi şeridi gibi): kenara bitişik yarım hap, uçları solar, seçili seçeneğin adı
 *  panelin yanında arka plansız turuncu yazıyla görünür. Seçeneklere dokununca ilgili şey çalışır.
 *  Hangi kenarda (sol / sağ), ekranda ne kadar yukarıda, boyu ve genişliği Ayarlar > Hızlı panel'den ayarlanır.
 */

// ================================================================ Hızlı panel: seçenekler ve eylemleri
object QuickItem {
    const val CAMERA = 0
    const val SILENT = 1
    const val TORCH = 2
    const val TRANSLATE = 3
    const val DND = 4
    const val RECORDER = 5
    const val CAPSULE = 6

    /** Paneldeki sıra (yukarıdan aşağıya). */
    val ALL = listOf(CAMERA, SILENT, TORCH, TRANSLATE, DND, RECORDER, CAPSULE)

    fun label(id: Int): String = when (id) {
        CAMERA -> "KAMERA"
        SILENT -> "SESSİZ MOD"
        TORCH -> "FENER"
        TRANSLATE -> "ÇEVİRİ"
        DND -> "RAHATSIZ ETME"
        RECORDER -> "SES KAYDEDİCİ"
        else -> "SOUNDCAPSULE"
    }

    /** Panelin ortasına gelince (seçilince) simgenin ve yazının rengi: her seçeneğin kendine ait rengi var. */
    fun color(id: Int): Color = when (id) {
        CAMERA -> Color(0xFF64D2FF)      // açık mavi / camgöbeği
        SILENT -> Color(0xFFFF9F0A)      // turuncu
        TORCH -> Color(0xFFFFD60A)       // sarı
        TRANSLATE -> Color(0xFF30D158)   // yeşil
        DND -> Color(0xFF8E7BFF)         // mor
        RECORDER -> Color(0xFFFF453A)    // kırmızı
        else -> Color(0xFF0A84FF)        // SoundCapsule: mavi
    }

    /** Aç / kapa türü seçeneklerde şu anki durum; diğerlerinde null. */
    fun isOn(ctx: Context, id: Int): Boolean? = when (id) {
        SILENT -> runCatching { ctx.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL }.getOrNull()
        TORCH -> FlashToggle.isOn()
        DND -> runCatching {
            ctx.getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
                it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            }
        }.getOrNull()
        else -> null
    }

    /** Panel kapanınca tekrar açılmaya gerek olmayan (bir uygulama / panel açan) seçenekler. */
    fun closesPanel(id: Int): Boolean = id == CAMERA || id == TRANSLATE || id == RECORDER || id == CAPSULE

    private val capsulePkgs: List<String> by lazy {
        val w = "mazahs".reversed()
        listOf("com.$w.android", "com.$w.encore.android")
    }

    /** Servis gerektirmeyen eylemler. Çeviri serviste ([IslandOverlayService]) işlenir. */
    fun run(ctx: Context, id: Int) {
        when (id) {
            CAMERA -> GestureAction.runSimple(ctx, GestureAction.CAMERA)
            TORCH -> FlashToggle.toggle(ctx)
            SILENT -> toggleSilent(ctx)
            DND -> toggleDnd(ctx)
            RECORDER -> runCatching {
                ctx.startActivity(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { toast(ctx, "Ses kaydedici uygulaması bulunamadı") }
            CAPSULE -> {
                val pm = ctx.packageManager
                val intent = capsulePkgs.firstNotNullOfOrNull { runCatching { pm.getLaunchIntentForPackage(it) }.getOrNull() }
                if (intent != null) runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else toast(ctx, "SoundCapsule yüklü değil")
            }
        }
    }

    private fun toast(ctx: Context, msg: String) {
        Toast.makeText(ctx.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    /** Sesli -> sessiz (izin yoksa titreşim), sessiz / titreşim -> sesli. */
    private fun toggleSilent(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        try {
            if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
                try { am.ringerMode = AudioManager.RINGER_MODE_SILENT }
                catch (e: SecurityException) { am.ringerMode = AudioManager.RINGER_MODE_VIBRATE }
            } else {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
            }
        } catch (e: SecurityException) {
            toast(ctx, "Sesi değiştirmek için Rahatsız Etmeyin erişimi gerekli")
            runCatching { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    private fun toggleDnd(ctx: Context) {
        val on = isOn(ctx, DND) == true
        val ok = IslandNotificationListener.setFocus(!on)
        if (!ok) {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

// ================================================================ Hızlı panel penceresi
/**
 * Kenarda açılan hızlı panel. Tam ekran, dokunuş alan şeffaf bir pencere: panelin dışına dokunmak kapatır.
 * Erişilebilirlik penceresi varsa o (durum çubuğunun üstünde), yoksa "üstte göster" penceresi kullanılır.
 * [cfgProvider]: panel her açılışta güncel ayarları (kenar, konum, boy, genişlik) buradan okur.
 */
class QuickPanelOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val cfgProvider: () -> MoreSettings,
    private val onAction: (Int) -> Unit,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())

    val isShown: Boolean get() = view != null

    fun canShow(): Boolean = IslandAccessibilityService.instance != null || Settings.canDrawOverlays(ctx)

    private fun params(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun toggle() { if (view != null) dismiss() else show() }

    fun show() {
        if (view != null) return
        FlashToggle.warmUp(ctx)        // fener açık mı bilinsin
        val cfg = cfgProvider()
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent {
                QuickPanelUi(
                    cfg = cfg,
                    onAction = { id -> onAction(id) },
                    onClose = { handler.post { dismiss() } },
                )
            }
        }
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(v, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)); host = h2; added = true
            }
        }
        if (!added && Settings.canDrawOverlays(ctx)) {
            added = runCatching { wm.addView(v, params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)); host = wm }.isSuccess
        }
        if (!added) {
            v.disposeComposition()
            Toast.makeText(ctx, "Hızlı panel için erişilebilirlik ya da 'üstte göster' izni gerekli", Toast.LENGTH_LONG).show()
            return
        }
        view = v
    }

    fun dismiss() {
        val v = view ?: return
        view = null
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        host = null
    }
}

/**
 * Kenara yapışık panel gövdesi. Kenar tarafı düz (ekran kenarına oturur), iç tarafı yarım hap gibi yuvarlak;
 * alt ve üstte kenara doğru küçük içbükey kavisler vardır (görseldeki gibi kenardan "büyümüş" görünür).
 * Sağ kenar için çizilir, sol kenar için aynalanır.
 */
private class DockShape(val right: Boolean, val fillet: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width
        val h = size.height
        val f = min(fillet, h / 3f)
        fun mx(x: Float) = if (right) x else w - x
        // Kenardan ince bir dilim olarak çıkar, uzun ve yumuşak bir S eğrisiyle gövdeye genişler (kenar tarafında içbükey,
        // gövde tarafında dışbükey); gövdenin yan yüzü düz iner, aşağıda aynı eğriyle kenara geri döner.
        val p = Path().apply {
            moveTo(mx(w), 0f)
            cubicTo(mx(w), f * 0.62f, mx(0f), f * 0.38f, mx(0f), f)
            lineTo(mx(0f), h - f)
            cubicTo(mx(0f), h - f * 0.38f, mx(w), h - f * 0.62f, mx(w), h)
            close()
        }
        return Outline.Generic(p)
    }
}

@Composable
private fun QuickPanelUi(cfg: MoreSettings, onAction: (Int) -> Unit, onClose: () -> Unit) {
    // Sol / sağ aynalamayı kendimiz yapıyoruz: yerleşim yönü ne olursa olsun aynı sonuç çıksın
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        QuickPanelContent(cfg, onAction, onClose)
    }
}

@Composable
private fun QuickPanelContent(cfg: MoreSettings, onAction: (Int) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    var taps by remember { mutableIntStateOf(0) }
    var stateTick by remember { mutableIntStateOf(0) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.78f, stiffness = 320f)) }

    val right = cfg.qpRight
    val itemH = 46.dp
    val fillet = 44.dp                 // üst / alt S eğrisinin boyu
    val padV = fillet / 2              // simge alanının üst / alt payı
    val edgePad = 6.dp                 // kenara değen tarafta boşluk
    val stripW = cfg.qpWidthDp.dp
    val panelH = cfg.qpHeightDp.dp
    val shape = remember(right, density) { DockShape(right, with(density) { fillet.toPx() }) }
    val none = remember { MutableInteractionSource() }

    val itemPx = with(density) { itemH.toPx() }
    val viewPx = with(density) { (panelH - padV * 2).toPx() }
    val spacerDp = with(density) { ((viewPx - itemPx) / 2f).coerceAtLeast(0f).toDp() }
    val count = QuickItem.ALL.size

    // Panelin tam ortasında duran seçenek seçilidir (kaydırdıkça değişir). Başta ve sonda boşluk olduğu için ilk ve son
    // seçenek de ortaya gelebilir; kaydırma bitince en yakın seçeneğe oturur.
    val selIdx by remember { derivedStateOf { (scroll.value / itemPx).roundToInt().coerceIn(0, count - 1) } }
    val selected = QuickItem.ALL[selIdx]

    // Dokunulmazsa / kaydırılmazsa 7 sn sonra kapanır
    LaunchedEffect(scroll.value, selected, taps) { delay(7_000L); onClose() }
    // Aç / kapa durumları (fener, sessiz, RE) eylemden kısa süre sonra yeniden okunur
    LaunchedEffect(taps) { if (taps > 0) { delay(350L); stateTick++ } }
    // Parmak çekilince / kaydırma bitince en yakın seçeneği ortaya oturt
    LaunchedEffect(itemPx) {
        snapshotFlow { scroll.isScrollInProgress }.collect { moving ->
            if (!moving) {
                val target = ((scroll.value / itemPx).roundToInt() * itemPx).roundToInt().coerceIn(0, scroll.maxValue)
                if (abs(scroll.value - target) > 1) scroll.animateScrollTo(target, spring(dampingRatio = 0.85f, stiffness = 500f))
            }
        }
    }

    BoxWithConstraints(
        Modifier.fillMaxSize().clickable(interactionSource = none, indication = null) { onClose() },
    ) {
        // Panelin ortası ekran yüksekliğinin qpYPct'inde; ekrandan taşmaz
        val top = (maxHeight * (cfg.qpYPct / 100f) - panelH / 2).coerceIn(0.dp, maxOf(0.dp, maxHeight - panelH))

        Row(
            Modifier
                .align(if (right) Alignment.TopEnd else Alignment.TopStart)
                .offset(y = top)
                .height(panelH)
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(if (right) 1f else 0f, 0.5f)
                    val k = 0.86f + 0.14f * appear.value
                    scaleX = k; scaleY = k
                    translationX = (1f - appear.value) * 56.dp.toPx() * (if (right) 1f else -1f)
                }
                // Panelin üstüne dokunmak dışarıya dokunuş sayılmasın
                .clickable(interactionSource = none, indication = null) { },
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // ---- Seçili seçeneğin adı: panelin ortasında, seçili simgeyle aynı hizada, arka plansız, simgenin renginde yazı.
            //      Ayarlardan kapatılabilir.
            val label = @Composable {
                if (cfg.qpLabel) {
                    Box(Modifier.fillMaxHeight()) {
                        val on = remember(stateTick, selected) { QuickItem.isOn(ctx, selected) }
                        val labelColor by animateColorAsState(QuickItem.color(selected), label = "qpLabelColor")
                        Text(
                            QuickItem.label(selected) + when (on) { true -> " · AÇIK"; false -> " · KAPALI"; null -> "" },
                            color = labelColor,
                            style = TextStyle(
                                fontWeight = FontWeight.Bold, fontSize = 13.sp,
                                shadow = Shadow(Color(0xB3000000), Offset(0f, 1f), 8f),
                            ),
                            maxLines = 1,
                            modifier = Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart),
                        )
                    }
                }
            }
            if (right) label()

            // ---- Panel gövdesi
            Box(
                Modifier
                    .width(stripW).height(panelH)
                    .shadow(10.dp, shape, clip = false)
                    .clip(shape)
                    .background(Color(0xFA050505))
                    .border(1.dp, Color(0x22FFFFFF), shape),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(top = padV, bottom = padV, start = if (right) 0.dp else edgePad, end = if (right) edgePad else 0.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            // Alt ve üst uçlar solar: devamı olduğu belli olsun
                            drawRect(
                                Brush.verticalGradient(
                                    0f to Color.Transparent, 0.16f to Color.Black, 0.84f to Color.Black, 1f to Color.Transparent,
                                ),
                                blendMode = BlendMode.DstIn,
                            )
                        }
                        .verticalScroll(scroll),
                ) {
                    Box(Modifier.height(spacerDp))
                    QuickItem.ALL.forEachIndexed { index, id ->
                        val sel = id == selected
                        Box(
                            Modifier
                                .width(stripW - edgePad).height(itemH)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    taps++
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    // Dokunulan seçenek ortaya gelir (kendi rengiyle yanar) ve çalışır
                                    scope.launch { scroll.animateScrollTo((index * itemPx).roundToInt().coerceIn(0, scroll.maxValue)) }
                                    onAction(id)
                                    if (QuickItem.closesPanel(id)) onClose()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            val activeNow = remember(stateTick, id) { QuickItem.isOn(ctx, id) == true }
                            val target = when {
                                sel -> QuickItem.color(id)
                                activeNow -> Color.White
                                else -> Color(0xB3FFFFFF)
                            }
                            val tint by animateColorAsState(target, label = "qpTint")
                            Canvas(Modifier.size(if (sel) 28.dp else 24.dp)) { drawQuickGlyph(id, tint) }
                        }
                    }
                    Box(Modifier.height(spacerDp))
                }
            }

            if (!right) label()
        }
    }
}

// ---------------------------------------------------------------- Simgeler (24 birimlik ızgarada çizilir)
private fun DrawScope.drawQuickGlyph(id: Int, c: Color) {
    val k = size.width / 24f
    scale(k, k, Offset.Zero) {
        val st = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (id) {
            QuickItem.CAMERA -> {
                drawRoundRect(c, Offset(3f, 7f), Size(18f, 12f), CornerRadius(3f, 3f), style = st)
                drawRoundRect(c, Offset(8.5f, 4.2f), Size(7f, 3f), CornerRadius(1f, 1f))
                drawCircle(c, 3.3f, Offset(12f, 13f), style = st)
            }
            QuickItem.SILENT -> {
                val bell = Path().apply {
                    moveTo(5.5f, 17f); lineTo(18.5f, 17f); lineTo(17f, 15f); lineTo(17f, 10.5f)
                    cubicTo(17f, 7.6f, 14.8f, 5.6f, 12f, 5.6f)
                    cubicTo(9.2f, 5.6f, 7f, 7.6f, 7f, 10.5f); lineTo(7f, 15f); close()
                }
                drawPath(bell, c, style = st)
                drawLine(c, Offset(10.4f, 19.6f), Offset(13.6f, 19.6f), 1.8f, StrokeCap.Round)
                drawLine(c, Offset(3.8f, 3.8f), Offset(20.2f, 20.2f), 2f, StrokeCap.Round)
            }
            QuickItem.TORCH -> {
                val head = Path().apply { moveTo(7.8f, 2.8f); lineTo(16.2f, 2.8f); lineTo(15f, 8f); lineTo(9f, 8f); close() }
                drawPath(head, c)
                drawRoundRect(c, Offset(9f, 8f), Size(6f, 13f), CornerRadius(1.6f, 1.6f), style = st)
                drawCircle(c, 1.2f, Offset(12f, 13f))
            }
            QuickItem.TRANSLATE -> {
                drawRoundRect(c, Offset(2.5f, 3.5f), Size(11.5f, 11.5f), CornerRadius(2.5f, 2.5f), style = st)
                val a = Path().apply { moveTo(5.1f, 12.2f); lineTo(8.25f, 5.9f); lineTo(11.4f, 12.2f) }
                drawPath(a, c, style = Stroke(1.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawLine(c, Offset(6.1f, 10.3f), Offset(10.4f, 10.3f), 1.4f, StrokeCap.Round)
                drawRoundRect(c, Offset(10f, 9f), Size(11.5f, 11.5f), CornerRadius(2.5f, 2.5f), style = st)
                drawLine(c, Offset(12.6f, 13.6f), Offset(18.9f, 13.6f), 1.5f, StrokeCap.Round)
                drawLine(c, Offset(15.75f, 12f), Offset(15.75f, 13.6f), 1.5f, StrokeCap.Round)
                drawLine(c, Offset(13.2f, 17.9f), Offset(18.3f, 14.4f), 1.4f, StrokeCap.Round)
                drawLine(c, Offset(18.3f, 17.9f), Offset(13.2f, 14.4f), 1.4f, StrokeCap.Round)
            }
            QuickItem.DND -> {
                val full = Path().apply { addOval(Rect(Offset(12f, 12f), 8.2f)) }
                val cut = Path().apply { addOval(Rect(Offset(16.6f, 8.6f), 7f)) }
                drawPath(Path.combine(PathOperation.Difference, full, cut), c)
            }
            QuickItem.RECORDER -> {
                drawRoundRect(c, Offset(9f, 2.8f), Size(6f, 11f), CornerRadius(3f, 3f), style = st)
                drawArc(c, 0f, 180f, false, Offset(5.5f, 6f), Size(13f, 12f), style = st)
                drawLine(c, Offset(12f, 18f), Offset(12f, 21.5f), 1.8f, StrokeCap.Round)
            }
            else -> { // SoundCapsule simgesi: eğik kapsül + üç ses çubuğu (adadaki logoyla aynı), seçilince mavi
                drawPath(soundCapsuleGlyphPath(Offset.Zero, 24f), c, style = Stroke(2.3f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
