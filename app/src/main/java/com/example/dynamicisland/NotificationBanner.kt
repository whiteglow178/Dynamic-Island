package com.example.dynamicisland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Ölçüler (ekran görüntüsündeki orana göre, dp)
private const val BANNER_MARGIN_DP = 10f      // ekranın sağ / sol kenarından boşluk
private const val BANNER_H_DP = 80f           // tam açık banner yüksekliği
private const val BANNER_RADIUS_DP = 34f      // tam açık köşe yuvarlaklığı
// Giriş animasyonu (iOS 27 ekran kaydından ölçüldü): ekran genişliğinin ~%10'u kadar minik, bulanık bir çizgi/hap olarak
// sol-orta tarafta (bannerın son konumunun hizasında) belirir; ~0,8 sn'de genişleyip büyüyerek tam banner olur.
// Büyürken önce hafifçe aşağı iner (DIP), sonra yerine oturur. İçerik küçükken bulanık, büyüdükçe netleşir.
private const val START_W_DP = 40f            // başlangıçtaki küçük hap: genişlik
private const val START_H_DP = 7f             // ... yükseklik
private const val START_LEFT_DP = 52f         // ... bannerın sol kenarından uzaklık (ekran genişliğinin ~%16'sı)
private const val START_RADIUS_DP = 4f
private const val DIP_DP = 14f                // büyürken aşağı sarkma miktarı
private const val ENTER_MS = 820              // giriş süresi
private const val MENU_GAP_DP = 8f            // banner ile menü arası
private const val MENU_H_DP = 46f             // menü / cevap satırı yüksekliği
private const val MENU_EXTRA_DP = MENU_GAP_DP + MENU_H_DP + 6f   // menü açılınca pencerenin uzaması
private const val ICON_ZONE_DP = 72f          // bannerın solundaki "simge" bölgesi (dokunma ayrımı için)
private val EnterEase = CubicBezierEasing(0.30f, 0.30f, 0.15f, 1f)
private const val EXIT_MS = 380L              // kapanış animasyonu bitince pencere kaldırılır

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * iOS tarzı bildirim banner'ı: sol üstte, saatin altında küçük ve bulanık bir hap olarak belirir,
 * büyüyerek ekranın üst kısmında tam genişlikte cam bir banner'a dönüşür; süre dolunca küçülüp kaybolur.
 * Banner'a basılı tutunca (ya da ayara göre dokununca) altında Cevapla / Kopyala / İlet menüsü açılır.
 * Ayrı bir pencere olarak çizilir (ada penceresinden bağımsız); yalnızca banner'ın kapladığı alan dokunmayı yakalar.
 */
class NotifBannerOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val registryOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val scope: CoroutineScope,
    private val settings: () -> IslandSettings,
    private val onOpen: (IslandContent.Notif) -> Unit,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private val current = mutableStateOf<IslandContent.Notif?>(null)
    private val shown = mutableStateOf(false)
    private val mode = mutableStateOf(0)      // 0 = yalnızca banner, 1 = Cevapla / Kopyala / İlet menüsü, 2 = cevap yazma
    private var holdMs = 3_000L
    private var hideJob: Job? = null
    private var detachJob: Job? = null

    private fun px(v: Float) = (v * ctx.resources.displayMetrics.density).roundToInt()

    private fun scheduleHide(ms: Long) {
        hideJob?.cancel()
        hideJob = scope.launch { delay(ms.coerceAtLeast(1_500L)); hide() }
    }

    /** Banner'ı gösterir. Pencere eklenemezse false döner (çağıran bildirimi adada gösterebilir). */
    fun show(n: IslandContent.Notif, holdMs: Long): Boolean {
        // Cevap yazılırken gelen yeni bildirim yazıyı bozmasın
        if (view != null && shown.value && mode.value == 2) return true
        hideJob?.cancel(); detachJob?.cancel()
        this.holdMs = holdMs
        if (mode.value != 0) applyWindow(0)
        current.value = n
        shown.value = true
        if (view == null && !attach()) { shown.value = false; current.value = null; return false }
        scheduleHide(holdMs)
        return true
    }

    /** Küçülerek kapatır (animasyon bitince pencere kaldırılır). */
    fun hide() {
        hideJob?.cancel()
        if (view == null) return
        if (mode.value != 0) applyWindow(0)
        shown.value = false
        detachJob?.cancel()
        detachJob = scope.launch { delay(EXIT_MS); if (!shown.value) detach() }
    }

    fun release() {
        hideJob?.cancel(); detachJob?.cancel()
        detach()
    }

    // ---------------------------------------------------------------- Menü / pencere durumu
    /** Pencere boyutunu ve odak durumunu moda göre ayarlar (süre ayarlamaz). */
    private fun applyWindow(m: Int) {
        val old = mode.value
        mode.value = m
        val v = view ?: return
        val p = lp ?: return
        p.height = px(BANNER_H_DP + if (m == 0) 0f else MENU_EXTRA_DP)
        if (m == 2) {
            // Klavye için pencere odak almalı; pencere yeniden boyutlanmasın
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        } else {
            p.flags = p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (old == 2) runCatching {
                (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
            }
        }
        runCatching { (host ?: wm).updateViewLayout(v, p) }
    }

    private fun setMode(m: Int) {
        applyWindow(m)
        when (m) {
            0 -> scheduleHide(holdMs)
            1 -> scheduleHide(8_000L)       // menü açıkken banner kapanmaz (son dokunuştan 8 sn)
            else -> scheduleHide(60_000L)   // cevap yazarken
        }
    }

    // ---------------------------------------------------------------- Dokunma
    private fun handleTap(onIcon: Boolean) {
        val n = current.value ?: return
        val s = settings()
        if (mode.value != 0) { setMode(0); return }       // menü açıkken dokunuş menüyü kapatır
        val menuOnTap = s.bannerMenuEnabled && s.bannerMenuTap
        when {
            onIcon && s.bannerIconOpens -> { hide(); onOpen(n) }
            menuOnTap -> setMode(1)
            onIcon -> Unit                                 // simgeye dokununca uygulama açma kapalı
            else -> { hide(); onOpen(n) }
        }
    }

    private fun handleLong() {
        val s = settings()
        if (mode.value == 0 && s.bannerMenuEnabled && !s.bannerMenuTap) setMode(1)
    }

    // ---------------------------------------------------------------- Menü eylemleri
    private fun toast(t: String) { runCatching { Toast.makeText(ctx, t, Toast.LENGTH_SHORT).show() } }

    /** Mesaj metnini panoya kopyalar. Panoya yazmak izin gerektirmez (yalnızca okumak kısıtlıdır). */
    private fun doCopy() {
        val n = current.value ?: return
        val text = n.text.ifBlank { n.title }
        runCatching {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("Bildirim", text))
        }
        toast("Kopyalandı")
        hide()
    }

    /** Bildirim metnini paylaşma penceresiyle iletir. */
    private fun doForward() {
        val n = current.value ?: return
        val text = if (n.title.isBlank() || n.text.isBlank()) n.title.ifBlank { n.text } else "${n.title}: ${n.text}"
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
        hide()
        runCatching { ctx.startActivity(Intent.createChooser(send, "İlet").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Cevapla: bildirimin satır içi cevap eylemi varsa yazma satırı açılır; yoksa uygulama açılır. */
    private fun doReply() {
        val n = current.value ?: return
        if (IslandNotificationListener.canReply(n.key)) setMode(2) else { hide(); onOpen(n) }
    }

    private fun doSend(text: String) {
        val n = current.value ?: return
        val t = text.trim()
        if (t.isEmpty()) return
        if (IslandNotificationListener.sendReply(n.key, t)) { toast("Gönderildi"); hide() }
        else toast("Cevap gönderilemedi")
    }

    // ---------------------------------------------------------------- Pencere
    private fun attach(): Boolean {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()
        val metrics = wm.currentWindowMetrics
        val marginPx = dp(BANNER_MARGIN_DP)
        val screenW = metrics.bounds.width()
        // Yatay mod: banner yine ÜSTTEN gelir; ekranın tamamına yayılmaz (en çok 480 dp) ve ortalanır
        val landscape = screenW > metrics.bounds.height()
        val wPx = if (landscape) minOf(screenW - 2 * marginPx, dp(480f)) else screenW - 2 * marginPx
        val xPx = if (landscape) (screenW - wPx) / 2 else marginPx
        val inset = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val topPx = (if (inset > 0) inset else dp(24f)) + dp(6f)
        val widthDp = wPx / density

        mode.value = 0
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(registryOwner)
            setContent {
                NotifBannerRoot(
                    n = current.value,
                    shown = shown.value,
                    widthDp = widthDp,
                    mode = mode.value,
                    onTap = { b -> handleTap(b) },
                    onLong = { handleLong() },
                    onSwipeUp = { hide() },
                    onCopy = { doCopy() },
                    onForward = { doForward() },
                    onReply = { doReply() },
                    onSend = { t -> doSend(t) },
                )
            }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            wPx, dp(BANNER_H_DP), type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = xPx; y = topPx
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Ada ile aynı katman: erişilebilirlik overlay'i durum çubuğunun da üstünde çizilir (saatin hemen altından çıkabilsin)
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val p = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(v, p)
                host = h2; lp = p; added = true
            }
        }
        if (!added) added = runCatching {
            val p = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            wm.addView(v, p); host = wm; lp = p
        }.isSuccess
        if (!added) { v.disposeComposition(); return false }
        view = v
        return true
    }

    private fun detach() {
        val v = view ?: return
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        view = null; host = null; lp = null
        shown.value = false
        mode.value = 0
        current.value = null
    }
}

// ---------------------------------------------------------------- Arayüz
@Composable
internal fun NotifBannerRoot(
    n: IslandContent.Notif?,
    shown: Boolean,
    widthDp: Float,
    mode: Int,
    onTap: (Boolean) -> Unit,
    onLong: () -> Unit,
    onSwipeUp: () -> Unit,
    onCopy: () -> Unit,
    onForward: () -> Unit,
    onReply: () -> Unit,
    onSend: (String) -> Unit,
) {
    val p = remember { Animatable(0f) }       // boyut ilerlemesi: 0 = minik hap, 1 = tam banner
    val py = remember { Animatable(0f) }      // aşağı sarkma evresi (yalnızca girişte oynar)
    val fade = remember { Animatable(1f) }    // kapanırken genel solma
    LaunchedEffect(shown) {
        if (shown) {
            fade.snapTo(1f)
            if (p.value < 0.05f) py.snapTo(0f)
            launch { py.animateTo(1f, tween(ENTER_MS, easing = EnterEase)) }
            p.animateTo(1f, tween(ENTER_MS, easing = EnterEase))
        } else {
            launch { fade.animateTo(0f, tween(240, delayMillis = 100)) }
            p.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
        }
    }

    val cur = n ?: return
    val onTapNow by rememberUpdatedState(onTap)
    val onLongNow by rememberUpdatedState(onLong)
    val onSwipeNow by rememberUpdatedState(onSwipeUp)
    val view = LocalView.current

    val pc = p.value.coerceIn(0f, 1f)
    val pyc = py.value.coerceIn(0f, 1f)
    val left = lerpF(START_LEFT_DP, 0f, pc)
    val w = lerpF(START_W_DP, widthDp, pc)
    val h = lerpF(START_H_DP, BANNER_H_DP, pc)
    // Giriş: hap, bannerın son merkez hizasından başlar, büyürken hafifçe aşağı sarkıp yerine oturur. Çıkış: yukarı (saate doğru) küçülür.
    val centerTop = BANNER_H_DP / 2f - h / 2f
    val dip = DIP_DP * sin(PI.toFloat() * pyc.pow(0.6f))
    val top = if (shown) centerTop + dip else lerpF(0f, centerTop, pc)
    val radius = lerpF(START_RADIUS_DP, BANNER_RADIUS_DP, pc)
    val blurDp = (1f - pc) * 14f              // küçükken içerik bulanık, büyüdükçe netleşir

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .offset(left.dp, top.dp)
                .size(w.dp, h.dp)
                .graphicsLayer { alpha = fade.value }
                .clip(RoundedCornerShape(radius.dp))
                .drawBehind { drawBannerGlass(radius.dp.toPx()) }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { off -> onTapNow(off.x < ICON_ZONE_DP.dp.toPx()) },
                        onLongPress = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            onLongNow()
                        },
                    )
                }
                .pointerInput(Unit) {
                    var acc = 0f
                    var fired = false
                    detectVerticalDragGestures(
                        onDragStart = { acc = 0f; fired = false },
                        onDragEnd = {}, onDragCancel = {},
                        onVerticalDrag = { change, dy ->
                            change.consume()
                            acc += dy
                            if (!fired && acc < -20.dp.toPx()) { fired = true; onSwipeNow() }
                        },
                    )
                },
        ) {
            // Bildirimde renkli ışık: uygulamanın renginde alt kenarda akan ışık (ayar açıksa accent dolu gelir)
            if (cur.accent != 0) MusicBottomFx(Color(cur.accent), true)
            // İçerik her zaman tam boyutta yerleşir; küçükken hapın içine ölçeklenerek sığar (yeniden yerleşim / sıçrama olmaz)
            Box(
                Modifier
                    .wrapContentSize(Alignment.TopStart, unbounded = true)
                    .size(widthDp.dp, BANNER_H_DP.dp)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        scaleX = w / widthDp
                        scaleY = h / BANNER_H_DP
                        alpha = ((pc - 0.10f) / 0.45f).coerceIn(0f, 1f)
                    }
                    .then(if (blurDp > 0.4f) Modifier.blur(blurDp.dp) else Modifier),
            ) { BannerContent(cur) }
        }

        // Cevapla / Kopyala / İlet menüsü ya da cevap yazma satırı (bannerın altında)
        if (shown && mode != 0) {
            Box(
                Modifier
                    .offset(0.dp, (BANNER_H_DP + MENU_GAP_DP).dp)
                    .size(widthDp.dp, MENU_H_DP.dp),
            ) {
                if (mode == 1) BannerMenu(onReply, onCopy, onForward)
                else ReplyBar(onSend)
            }
        }
    }
}

/** Cam zeminli tek bir düğme. */
@Composable
private fun GlassPill(modifier: Modifier, onClick: () -> Unit, content: @Composable () -> Unit) {
    val click by rememberUpdatedState(onClick)
    val r = (MENU_H_DP / 2f).dp
    Box(
        modifier
            .clip(RoundedCornerShape(r))
            .drawBehind { drawBannerGlass(r.toPx()) }
            .pointerInput(Unit) { detectTapGestures(onTap = { click() }) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun BannerMenu(onReply: () -> Unit, onCopy: () -> Unit, onForward: () -> Unit) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(240, easing = FastOutSlowInEasing)) }
    Row(
        Modifier.fillMaxSize().graphicsLayer {
            alpha = a.value
            translationY = (1f - a.value) * -10.dp.toPx()
            scaleX = 0.94f + 0.06f * a.value; scaleY = 0.94f + 0.06f * a.value
            transformOrigin = TransformOrigin(0.5f, 0f)
        },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((label, act) in listOf("Cevapla" to onReply, "Kopyala" to onCopy, "İlet" to onForward)) {
            GlassPill(Modifier.weight(1f).fillMaxSize(), act) {
                Text(label, color = Pal.c("banner_title"), fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ReplyBar(onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val sendNow by rememberUpdatedState(onSend)
    // Pencere odak alınca klavye açılır (odak değişimi için kısa süre beklenir)
    LaunchedEffect(Unit) {
        delay(160)
        var tries = 0
        while (!view.hasWindowFocus() && tries < 15) { delay(100); tries++ }
        runCatching { focus.requestFocus() }
        kb?.show()
        delay(200)
        kb?.show()
    }
    val titleColor = Pal.c("banner_title")
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        GlassPill(Modifier.weight(1f).fillMaxSize(), {}) {
            Box(Modifier.fillMaxSize().padding(horizontal = 18.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text("Cevap yaz…", color = Pal.c("banner_time"), fontSize = 15.sp, maxLines = 1)
                BasicTextField(
                    value = text, onValueChange = { text = it },
                    textStyle = TextStyle(color = titleColor, fontSize = 15.sp),
                    cursorBrush = SolidColor(titleColor),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendNow(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        }
        GlassPill(Modifier.width(84.dp).fillMaxSize(), { sendNow(text) }) {
            Text("Gönder", color = Pal.c("banner_title").copy(alpha = if (text.isBlank()) 0.4f else 1f),
                fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1)
        }
    }
}

@Composable
private fun BannerContent(n: IslandContent.Notif) {
    val nowLabel = remember { if (java.util.Locale.getDefault().language == "tr") "şimdi" else "now" }
    val titleText = n.title.ifBlank { n.text }
    val bodyText = if (n.title.isBlank()) "" else n.text
    Row(
        Modifier.fillMaxSize().padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Sohbet bildirimi: gönderen fotoğrafı (yoksa baş harf) + sağ altta küçük uygulama simgesi.
        // Diğer uygulamalar (mesaj olmayan): uygulamanın simgesi büyük alanda görünür.
        val bigIcon = if (!n.messaging) n.icon else null
        Box(Modifier.size(54.dp)) {
            val av = n.avatar
            if (bigIcon != null) {
                Image(
                    bitmap = bigIcon, contentDescription = null, filterQuality = FilterQuality.High,
                    modifier = Modifier.align(Alignment.CenterStart).size(48.dp).clip(RoundedCornerShape(12.dp)),
                )
            } else if (av != null) {
                Image(
                    bitmap = av, contentDescription = null, contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(CircleShape),
                )
            } else {
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(Pal.c("banner_icon_bg")),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        titleText.trim().take(1).uppercase().ifEmpty { "•" },
                        color = Pal.c("banner_icon_letter"), fontSize = 22.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            if (bigIcon == null) n.icon?.let { icon ->
                val s = RoundedCornerShape(7.dp)
                Image(
                    bitmap = icon, contentDescription = null,
                    modifier = Modifier.align(Alignment.BottomEnd).size(22.dp).clip(s).border(1.5.dp, Pal.c("banner_icon_border"), s),
                )
            }
        }
        Column(
            Modifier.weight(1f).padding(start = 12.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    titleText, color = Pal.c("banner_title"), fontWeight = FontWeight.Bold, fontSize = 16.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Text(nowLabel, color = Pal.c("banner_time"), fontSize = 13.sp, maxLines = 1)
            }
            if (bodyText.isNotBlank()) {
                Text(
                    bodyText, color = Pal.c("banner_body").copy(alpha = 0.85f), fontSize = 15.sp, lineHeight = 19.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Süt beyazı cam gövde + üstten ışık + sol üstte parlama + ince kenar ışığı (iOS Liquid Glass benzeri). */
private fun DrawScope.drawBannerGlass(r: Float) {
    val w = size.width
    val h = size.height
    if (w <= 1f || h <= 1f) return
    drawRect(Pal.c("banner_bg"))
    drawRect(
        Brush.verticalGradient(
            0f to Pal.c("banner_sheen").copy(0.34f),
            0.5f to Pal.c("banner_sheen").copy(0.06f),
            1f to Pal.c("banner_sheen").copy(0.20f),
        )
    )
    drawRect(
        Brush.radialGradient(
            0f to Pal.c("banner_sheen").copy(0.30f),
            1f to Color.Transparent,
            center = Offset(w * 0.20f, 0f),
            radius = max(w * 0.60f, h),
        )
    )
    val sw = 1.2.dp.toPx()
    drawRoundRect(
        brush = Brush.linearGradient(
            0f to Pal.c("banner_sheen").copy(0.95f),
            0.30f to Pal.c("banner_sheen").copy(0.25f),
            0.65f to Pal.c("banner_sheen").copy(0.15f),
            1f to Pal.c("banner_sheen").copy(0.75f),
            start = Offset.Zero, end = Offset(w, h),
        ),
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
        style = Stroke(width = sw),
    )
}
