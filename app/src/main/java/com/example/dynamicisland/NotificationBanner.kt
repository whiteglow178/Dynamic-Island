package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
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
import kotlin.math.max
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Ölçüler (ekran görüntüsündeki orana göre, dp)
private const val BANNER_MARGIN_DP = 10f      // ekranın sağ / sol kenarından boşluk
private const val BANNER_H_DP = 80f           // tam açık banner yüksekliği
private const val BANNER_RADIUS_DP = 34f      // tam açık köşe yuvarlaklığı
private const val START_W_DP = 112f           // başlangıçtaki küçük hap: genişlik
private const val START_H_DP = 23f            // ... yükseklik
private const val START_LEFT_DP = 30f         // ... bannerın sol kenarından uzaklık (saatin altında, sol üstte)
private const val START_TOP_DP = 35f          // ... başta bannerın üst kenarından aşağıda durur, büyürken yukarı çıkar
private const val START_RADIUS_DP = 11.5f
private const val EXIT_MS = 380L              // kapanış animasyonu bitince pencere kaldırılır

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * iOS tarzı bildirim banner'ı: sol üstte, saatin altında küçük ve bulanık bir hap olarak belirir,
 * büyüyerek ekranın üst kısmında tam genişlikte cam bir banner'a dönüşür; süre dolunca küçülüp kaybolur.
 * Ayrı bir pencere olarak çizilir (ada penceresinden bağımsız); yalnızca banner'ın kapladığı alan dokunmayı yakalar.
 */
class NotifBannerOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val registryOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val scope: CoroutineScope,
    private val onOpen: (IslandContent.Notif) -> Unit,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private val current = mutableStateOf<IslandContent.Notif?>(null)
    private val shown = mutableStateOf(false)
    private var hideJob: Job? = null
    private var detachJob: Job? = null

    /** Banner'ı gösterir. Pencere eklenemezse false döner (çağıran bildirimi adada gösterebilir). */
    fun show(n: IslandContent.Notif, holdMs: Long): Boolean {
        hideJob?.cancel(); detachJob?.cancel()
        current.value = n
        shown.value = true
        if (view == null && !attach()) { shown.value = false; current.value = null; return false }
        hideJob = scope.launch { delay(holdMs.coerceAtLeast(1_500L)); hide() }
        return true
    }

    /** Küçülerek kapatır (animasyon bitince pencere kaldırılır). */
    fun hide() {
        hideJob?.cancel()
        if (view == null) return
        shown.value = false
        detachJob?.cancel()
        detachJob = scope.launch { delay(EXIT_MS); if (!shown.value) detach() }
    }

    fun release() {
        hideJob?.cancel(); detachJob?.cancel()
        detach()
    }

    private fun attach(): Boolean {
        val density = ctx.resources.displayMetrics.density
        fun dp(v: Float) = (v * density).roundToInt()
        val metrics = wm.currentWindowMetrics
        val marginPx = dp(BANNER_MARGIN_DP)
        val wPx = metrics.bounds.width() - 2 * marginPx
        val inset = metrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
        val topPx = (if (inset > 0) inset else dp(24f)) + dp(6f)
        val widthDp = wPx / density

        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(registryOwner)
            setContent {
                NotifBannerRoot(
                    n = current.value,
                    shown = shown.value,
                    widthDp = widthDp,
                    onTap = { current.value?.let { n -> hide(); onOpen(n) } },
                    onSwipeUp = { hide() },
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
            x = marginPx; y = topPx
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Ada ile aynı katman: erişilebilirlik overlay'i durum çubuğunun da üstünde çizilir (saatin hemen altından çıkabilsin)
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(v, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                host = h2; added = true
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
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        view = null; host = null
        shown.value = false
        current.value = null
    }
}

// ---------------------------------------------------------------- Arayüz
@Composable
private fun NotifBannerRoot(
    n: IslandContent.Notif?,
    shown: Boolean,
    widthDp: Float,
    onTap: () -> Unit,
    onSwipeUp: () -> Unit,
) {
    val p = remember { Animatable(0f) }       // boyut ilerlemesi: 0 = küçük hap, 1 = tam banner
    val py = remember { Animatable(0f) }      // dikey ilerleme: aşağıdan (saatin altı) yukarı yerine çıkar
    val fade = remember { Animatable(1f) }    // kapanırken genel solma
    LaunchedEffect(shown) {
        if (shown) {
            fade.snapTo(1f)
            if (p.value < 0.05f) py.snapTo(0f)
            launch { py.animateTo(1f, spring(dampingRatio = 0.88f, stiffness = 420f)) }
            p.animateTo(1f, spring(dampingRatio = 0.82f, stiffness = 300f))
        } else {
            launch { fade.animateTo(0f, tween(240, delayMillis = 100)) }
            p.animateTo(0f, tween(300, easing = FastOutSlowInEasing))
        }
    }

    val cur = n ?: return
    val onTapNow by rememberUpdatedState(onTap)
    val onSwipeNow by rememberUpdatedState(onSwipeUp)

    val pc = p.value.coerceIn(0f, 1f)
    val pyc = py.value.coerceIn(0f, 1f)
    val left = lerpF(START_LEFT_DP, 0f, pc)
    val top = lerpF(START_TOP_DP, 0f, pyc)
    val w = lerpF(START_W_DP, widthDp, pc)
    val h = lerpF(START_H_DP, BANNER_H_DP, pc)
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
                .pointerInput(Unit) { detectTapGestures(onTap = { onTapNow() }) }
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
        // Gönderen fotoğrafı (yoksa baş harf) + sağ altta uygulama simgesi
        Box(Modifier.size(54.dp)) {
            val av = n.avatar
            if (av != null) {
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
            n.icon?.let { icon ->
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
