package com.example.dynamicisland

import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.location.GnssStatus
import android.location.LocationManager
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/*
 * HIZLI PANEL + KONUM NOKTASI
 *
 *  - Hızlı panel: kapalı adayı sağa ya da sola kaydırınca ekranın sağ altında küçük, dikey kaydırılabilir bir panel açılır
 *    (iPhone'daki Kamera Düğmesi şeridi gibi). Seçeneklere dokununca ilgili şey çalışır (üstüne gelince değil, basınca).
 *  - Konum noktası: konum açıkken ya da bir uygulama konumu kullanırken adanın içinde mavi nokta (IslandUi.kt çizer).
 */

// ================================================================ Konum noktası
/** Mavi konum noktasının görünüp görünmeyeceği (ayar + algılama sonucu). */
object LocationDot {
    val active = MutableStateFlow(false)
}

/**
 * Konum durumunu izler.
 *  - "Konum açık": LocationManager.isLocationEnabled + MODE_CHANGED yayını (izin gerektirmez).
 *  - "Bir uygulama kullanıyor": GNSS başladı / durdu geri çağrısı (konum izni verildiyse) ve AppOps etkin işlem dinleyicisi
 *    (çoğu telefonda ayrıcalıklı izin ister; olmazsa sessizce atlanır).
 */
class LocationController(private val host: IslandHost) {
    private var receiver: BroadcastReceiver? = null
    private var gnssCb: GnssStatus.Callback? = null
    private var opsCb: AppOpsManager.OnOpActiveListener? = null
    private var opsTried = false
    private var gnssOn = false
    private var gpsBroadcastOn = false
    private val opsActive = HashSet<String>()

    fun start() {
        if (receiver != null) return
        val ctx = host.context
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == GPS_CHANGE) gpsBroadcastOn = intent.getBooleanExtra("enabled", false)
                runCatching { refresh() }
            }
        }
        receiver = r
        runCatching {
            val f = IntentFilter(LocationManager.MODE_CHANGED_ACTION)
            f.addAction(GPS_CHANGE)
            ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        runCatching { refresh() }
    }

    fun stop() {
        val ctx = host.context
        receiver?.let { r -> runCatching { ctx.unregisterReceiver(r) } }
        receiver = null
        gnssCb?.let { cb -> runCatching { ctx.getSystemService(LocationManager::class.java).unregisterGnssStatusCallback(cb) } }
        gnssCb = null
        opsCb?.let { cb -> runCatching { ctx.getSystemService(AppOpsManager::class.java).stopWatchingActive(cb) } }
        opsCb = null; opsTried = false
        opsActive.clear(); gnssOn = false; gpsBroadcastOn = false
        LocationDot.active.value = false
    }

    /** İzin sonradan verilmiş olabilir: eksik geri çağrılar burada (yeniden) kurulur. */
    private fun ensureWatchers() {
        val ctx = host.context
        val main = ContextCompat.getMainExecutor(ctx)
        if (gnssCb == null &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        ) {
            val cb = object : GnssStatus.Callback() {
                override fun onStarted() { gnssOn = true; runCatching { refresh() } }
                override fun onStopped() { gnssOn = false; runCatching { refresh() } }
            }
            val ok = runCatching { ctx.getSystemService(LocationManager::class.java).registerGnssStatusCallback(main, cb) }.getOrDefault(false)
            if (ok) gnssCb = cb
        }
        if (!opsTried) {
            opsTried = true
            val cb = object : AppOpsManager.OnOpActiveListener {
                override fun onOpActiveChanged(op: String, uid: Int, packageName: String, active: Boolean) {
                    if (uid == android.os.Process.myUid()) return
                    val k = "$op|$uid|$packageName"
                    if (active) opsActive.add(k) else opsActive.remove(k)
                    runCatching { refresh() }
                }
            }
            val ok = runCatching {
                ctx.getSystemService(AppOpsManager::class.java).startWatchingActive(
                    arrayOf(AppOpsManager.OPSTR_FINE_LOCATION, AppOpsManager.OPSTR_COARSE_LOCATION), main, cb,
                )
            }.isSuccess
            if (ok) opsCb = cb
        }
    }

    /** Ayar ya da konum durumu değişince çağrılır: mavi noktanın görünüp görünmeyeceğini yeniden hesaplar. */
    fun refresh() {
        val m = host.settings.more
        if (!m.locDot) { LocationDot.active.value = false; return }
        runCatching { ensureWatchers() }
        val enabled = runCatching { host.context.getSystemService(LocationManager::class.java).isLocationEnabled }.getOrDefault(false)
        val using = enabled && (gnssOn || gpsBroadcastOn || opsActive.isNotEmpty())
        LocationDot.active.value = if (m.locDotMode == 0) enabled else using
    }

    private companion object {
        const val GPS_CHANGE = "android.location.GPS_ENABLED_CHANGE"
    }
}

/** Ayarlarda: uygulamanın konumu kullandığını algılayabilmesi için konum izni satırı. */
@Composable
fun LocationPermissionRow() {
    val ctx = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    if (granted) {
        Text(
            "Konum izni verildi: bir uygulama GPS'i kullandığında mavi nokta çıkar.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
        )
    } else {
        Text(
            "Bir uygulamanın konumu kullandığını algılamak için konum izni gerekir. Uygulama konumunu okumaz, kaydetmez ve hiçbir yere göndermez; izin yalnızca \"GPS başladı / durdu\" haberini almak içindir.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
        )
        IslandActionButton("Konum izni ver", { launcher.launch(Manifest.permission.ACCESS_FINE_LOCATION) })
    }
}

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
 * Sağ altta açılan hızlı panel. Tam ekran, dokunuş alan şeffaf bir pencere: panelin dışına dokunmak kapatır.
 * Erişilebilirlik penceresi varsa o (durum çubuğunun üstünde), yoksa "üstte göster" penceresi kullanılır.
 */
class QuickPanelOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
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
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent {
                QuickPanelUi(
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

private val QuickOrange = Color(0xFFFF9F0A)

@Composable
private fun QuickPanelUi(onAction: (Int) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scroll = rememberScrollState()
    var selected by remember { mutableIntStateOf(QuickItem.CAMERA) }
    var taps by remember { mutableIntStateOf(0) }
    var stateTick by remember { mutableIntStateOf(0) }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 320f)) }
    // Dokunulmazsa / kaydırılmazsa 7 sn sonra kapanır
    LaunchedEffect(scroll.value, selected, taps) { delay(7_000L); onClose() }
    // Aç / kapa durumları (fener, sessiz, RE) eylemden kısa süre sonra yeniden okunur
    LaunchedEffect(taps) { if (taps > 0) { delay(350L); stateTick++ } }

    val itemH = 46.dp
    val stripW = 56.dp
    val stripH = itemH * 3.6f          // dördüncü simge yarım görünür: kaydırılabildiği belli olur
    val none = remember { MutableInteractionSource() }

    Box(
        Modifier.fillMaxSize().clickable(interactionSource = none, indication = null) { onClose() },
        contentAlignment = Alignment.BottomEnd,
    ) {
        Row(
            Modifier
                .padding(end = 14.dp, bottom = 96.dp)
                .graphicsLayer {
                    alpha = appear.value.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 1f)
                    val k = 0.82f + 0.18f * appear.value
                    scaleX = k; scaleY = k
                    translationX = (1f - appear.value) * 48.dp.toPx()
                }
                // Panelin üstüne dokunmak dışarıya dokunuş sayılmasın
                .clickable(interactionSource = none, indication = null) { },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Seçili seçeneğin adı (iPhone'daki turuncu yazı gibi)
            val on = remember(stateTick, selected) { QuickItem.isOn(ctx, selected) }
            Text(
                QuickItem.label(selected) + when (on) { true -> " · AÇIK"; false -> " · KAPALI"; null -> "" },
                color = QuickOrange, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1,
                modifier = Modifier
                    .background(Color(0xCC000000), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Box(
                Modifier
                    .width(stripW).height(stripH)
                    .shadow(10.dp, RoundedCornerShape(stripW / 2), clip = false)
                    .clip(RoundedCornerShape(stripW / 2))
                    .background(Color(0xF2050505))
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(stripW / 2)),
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
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
                    Box(Modifier.height(8.dp))
                    QuickItem.ALL.forEach { id ->
                        val sel = id == selected
                        Box(
                            Modifier
                                .width(stripW).height(itemH)
                                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                    selected = id
                                    taps++
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onAction(id)
                                    if (QuickItem.closesPanel(id)) onClose()
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (sel) Box(Modifier.size(40.dp).background(Color(0x33FFFFFF), CircleShape))
                            val activeNow = remember(stateTick, id) { QuickItem.isOn(ctx, id) == true }
                            val tint = when {
                                sel -> QuickOrange
                                activeNow -> Color.White
                                else -> Color(0xCCFFFFFF)
                            }
                            Canvas(Modifier.size(24.dp)) { drawQuickGlyph(id, tint) }
                        }
                    }
                    Box(Modifier.height(8.dp))
                }
            }
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
            else -> { // SoundCapsule: mavi daire içinde S
                drawCircle(Color(0xFF0A84FF), 10f, Offset(12f, 12f))
                val s = Path().apply {
                    moveTo(15.4f, 8.4f)
                    cubicTo(14.6f, 6.8f, 9f, 6.6f, 9f, 9.6f)
                    cubicTo(9f, 12.2f, 15f, 11.6f, 15f, 14.6f)
                    cubicTo(15f, 17.6f, 9.4f, 17.4f, 8.6f, 15.6f)
                }
                drawPath(s, Color.White, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}
