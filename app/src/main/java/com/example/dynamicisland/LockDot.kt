package com.example.dynamicisland

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * KİLİT EKRANI NOKTASI
 * Ekran açık ve kilitliyken adanın sol yanında yanıp sönen renkli nokta (parıltılı). Kilit açılınca / ekran kapanınca kaldırılır.
 * Ek izin gerekmez (KeyguardManager.isKeyguardLocked). Dokunmatik olmayan küçük bir pencere; log tutmaz.
 */

/** Noktayı çizer: kendi alanının ortasına. Hem kilit ekranı penceresinde hem ayar önizlemesinde kullanılır. */
@Composable
fun LockDotView(s: IslandSettings, modifier: Modifier = Modifier.fillMaxSize()) {
    val d = LocalDensity.current.density
    val blink = lockDotAlpha(s)
    Canvas(modifier) {
        val a = blink.value
        val c = center
        val r = s.lockDotSizeDp * d / 2f
        val glowR = if (s.lockDotGlow) s.lockDotGlowDp * d else 0f
        val col = Color(s.lockDotColorArgb)
        if (glowR > 0.5f) {
            val big = r + glowR
            val ga = (s.lockDotGlowAlpha.coerceIn(0f, 1f) * a)
            drawCircle(
                brush = Brush.radialGradient(
                    0f to col.copy(alpha = ga),
                    (r / big).coerceIn(0f, 0.95f) to col.copy(alpha = ga * 0.85f),
                    1f to col.copy(alpha = 0f),
                    center = c, radius = big,
                ),
                radius = big, center = c,
            )
        }
        drawCircle(col.copy(alpha = a), r, c)
        drawCircle(Pal.c("lock_dot_shine").copy(alpha = 0.28f * a), r * 0.38f, androidx.compose.ui.geometry.Offset(c.x - r * 0.28f, c.y - r * 0.28f))
    }
}

@Composable
private fun lockDotAlpha(s: IslandSettings): State<Float> {
    if (!s.lockDotBlink) return remember { mutableStateOf(1f) }
    val period = s.lockDotBlinkMs.coerceAtLeast(120)
    val minA = s.lockDotMinAlpha.coerceIn(0f, 1f)
    return if (s.lockDotBlinkStyle == 0) {
        // Yumuşak: parlaklık sinüs gibi iner çıkar
        val tr = rememberInfiniteTransition(label = "lockDot")
        tr.animateFloat(minA, 1f, infiniteRepeatable(tween(period / 2, easing = LinearEasing), RepeatMode.Reverse), label = "lockDotA")
    } else {
        // Sert: ani açık / kapalı
        val st = remember { mutableStateOf(1f) }
        LaunchedEffect(period, minA) {
            while (true) {
                st.value = 1f; delay(period / 2L)
                st.value = minA; delay(period / 2L)
            }
        }
        st
    }
}

@Composable
private fun LockDotWindowContent(settings: StateFlow<IslandSettings>) {
    val s by settings.collectAsState()
    LockDotView(s)
}

class LockDotController(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val scope: CoroutineScope,
    /** Adanın (merkez x, merkez y, kapalı yarı genişlik) ekran konumu (px). */
    private val anchor: (IslandSettings) -> Triple<Float, Float, Float>,
) {
    private val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var job: Job? = null
    private var locked = false

    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    private val poll = Runnable { evaluate() }

    /** Face ID animasyonu adanın yanını kapladığı sürece nokta gizlenir. */
    @Volatile var suppress = false
    fun recheck() { runCatching { refresh() } }

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                runCatching {
                    evaluate()
                    if (i?.action == Intent.ACTION_SCREEN_ON) handler.postDelayed({ evaluate() }, 300)
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
        }.isSuccess
        if (ok) receiver = r
        job = scope.launch { settings.collect { runCatching { refresh() } } }
        evaluate()
    }

    fun stop() {
        job?.cancel(); job = null
        handler.removeCallbacksAndMessages(null)
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        hide()
    }

    /** Erişilebilirlik servisi bağlanıp kopunca pencere türü değişir: pencereyi yeniden kur. */
    fun rehost() { hide(); refresh() }

    private fun evaluate() {
        locked = runCatching { pm?.isInteractive == true && km?.isKeyguardLocked == true }.getOrDefault(false)
        refresh()
        handler.removeCallbacks(poll)
        if (locked) handler.postDelayed(poll, 1000)     // olay kaçarsa da kilit açılınca kaybolsun
    }

    private fun refresh() {
        val s = settings.value
        if (locked && s.lockDotEnabled && !suppress) show(s) else hide()
    }

    private fun place(s: IslandSettings, p: WindowManager.LayoutParams) {
        val d = ctx.resources.displayMetrics.density
        val r = s.lockDotSizeDp * d / 2f
        val glow = if (s.lockDotGlow) s.lockDotGlowDp * d else 0f
        val side = ceil(2f * (r + glow)).toInt().coerceAtLeast(2)
        val (cx, cy, half) = anchor(s)
        val dotX = cx - half - s.lockDotGapDp * d - r
        val dotY = cy + s.lockDotOffsetYDp * d
        p.width = side; p.height = side
        p.x = (dotX - side / 2f).roundToInt()
        p.y = (dotY - side / 2f).roundToInt()
    }

    private fun show(s: IslandSettings) {
        val cur = view
        if (cur != null) {
            val p = lp ?: return
            place(s, p)
            runCatching { (host ?: wm).updateViewLayout(cur, p) }
            return
        }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { LockDotWindowContent(settings) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            place(s, this)
        }
        var added = false
        // Kilit ekranının üstünde görünmesi için erişilebilirlik pencere türü tercih edilir
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
