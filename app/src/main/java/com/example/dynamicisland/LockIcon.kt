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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
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
 * KİLİT SİMGESİ
 * Ekran açık ve kilitliyken adanın sol ucunda beyaz, kapalı kilit görünür. Kilit açılınca (ana ekrana geçilince)
 * kilit açık görünüme geçer (kancası kalkıp yana kayar), kısa süre sonra söner.
 * Ek izin gerekmez (KeyguardManager.isKeyguardLocked); kilit ekranında erişilebilirlik penceresiyle çizilir.
 */

/**
 * Asma kilit simgesi. [c] simgenin merkezi, [h] toplam yüksekliği; [open] 0 = kapalı, 1 = açık
 * (kanca yukarı kalkıp sağa kayar, sağ ayağı gövdeden çıkar).
 */
internal fun DrawScope.drawLockGlyph(c: Offset, h: Float, color: Color, open: Float) {
    val bw = h * 0.78f
    val bh = h * 0.58f
    val bodyTop = c.y + h / 2f - bh
    drawRoundRect(color, Offset(c.x - bw / 2f, bodyTop), Size(bw, bh), CornerRadius(h * 0.13f))
    val sw = h * 0.13f
    val sr = bw * 0.30f                         // kanca yarıçapı
    val o = open.coerceIn(0f, 1f)
    val dx = o * bw * 0.36f
    val dy = -o * h * 0.10f
    val leftX = c.x - sr + dx
    val rightX = c.x + sr + dx
    val legBottom = bodyTop + sw * 0.6f + dy
    val arcCy = bodyTop - h * 0.12f + dy
    val shackle = Path().apply {
        moveTo(leftX, legBottom + o * h * 0.06f)   // açıkken sol ayak gövdeden kısalır
        lineTo(leftX, arcCy)
        arcTo(androidx.compose.ui.geometry.Rect(Offset(c.x + dx, arcCy), sr), 180f, 180f, false)
        lineTo(rightX, legBottom)
    }
    drawPath(shackle, color, style = Stroke(width = sw, cap = StrokeCap.Round))
}

private class LockIconState {
    val run = mutableIntStateOf(0)
    val unlocking = mutableStateOf(false)
    val geo = mutableStateOf(floatArrayOf(30f, 30f))      // [pillH, pad] px
}

@Composable
private fun LockIconWindowContent(st: LockIconState, onDone: () -> Unit) {
    val run = st.run.intValue
    val openA = remember(run) { Animatable(0f) }
    val alphaA = remember(run) { Animatable(0f) }
    val unlocking = st.unlocking.value
    LaunchedEffect(run) { alphaA.animateTo(1f, tween(220)) }
    LaunchedEffect(run, unlocking) {
        if (unlocking) {
            openA.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
            delay(900)
            alphaA.animateTo(0f, tween(260))
            onDone()
        }
    }
    Canvas(Modifier.fillMaxSize()) {
        val g = st.geo.value
        val ph = g[0]
        val c = Offset(size.width / 2f, size.height / 2f)
        // Adanın sol ucu: kendi siyah yarım dairesi (ada boştayken görünmez dikiş olur; ada yoksa da simge okunur)
        drawCircle(Color.Black, ph / 2f, c)
        drawLockGlyph(Offset(c.x, c.y), ph * 0.46f, Color.White.copy(alpha = alphaA.value), openA.value)
    }
}

class LockIconController(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val scope: CoroutineScope,
    /** Ada boştaysa (içinde başka bir şey çizilmiyorsa) true. */
    private val canShow: () -> Boolean,
    private val anchor: (IslandSettings) -> FaceIdAnchor,
) {
    private val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var job: Job? = null
    private var locked = false
    private val st = LockIconState()

    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    /** Face ID animasyonu sürerken kilit gizlenir (küçük Face ID kendi kilidini çizer). */
    @Volatile var suppress = false
    private var suppressedUnlock = false

    private val poll = Runnable { evaluate() }

    fun recheck() {
        runCatching {
            // Face ID başladı: kilit açılırsa açık kilidi Face ID gösterir. Face ID bitti ama kilit hâlâ kapalı: sıfırla.
            if (suppress) suppressedUnlock = true
            else if (runCatching { km?.isKeyguardLocked == true }.getOrDefault(false)) suppressedUnlock = false
            refresh()
        }
    }

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

    fun rehost() { hide(); refresh() }

    private fun evaluate() {
        val interactive = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        val nowLocked = runCatching { interactive && km?.isKeyguardLocked == true }.getOrDefault(false)
        val wasLocked = locked
        locked = nowLocked
        if (!interactive) { st.unlocking.value = false; hide() }
        else if (wasLocked && !nowLocked) onUnlocked()
        else refresh()
        handler.removeCallbacks(poll)
        if (locked) handler.postDelayed(poll, 700)
    }

    /** Kilit açıldı: kilit açık görünüme geçer, sonra söner. Face ID kendi açık kilidini zaten gösterdiyse atlanır. */
    private fun onUnlocked() {
        val s = settings.value
        if (!s.more.lockIcon || view == null || suppress || suppressedUnlock) { suppressedUnlock = false; hide(); return }
        st.unlocking.value = true
    }

    private fun refresh() {
        val s = settings.value
        if (st.unlocking.value) return                    // açılma animasyonu sürüyor
        if (locked && s.more.lockIcon && !suppress && canShow()) show(s) else hide()
        if (!suppress && !locked) suppressedUnlock = false
    }

    private fun place(s: IslandSettings, p: WindowManager.LayoutParams) {
        val a = anchor(s)
        val pad = a.pillH * 0.15f
        val side = ceil(a.pillH + 2f * pad).toInt().coerceAtLeast(2)
        st.geo.value = floatArrayOf(a.pillH, pad)
        // Adanın sol ucundaki yarım dairenin merkezi
        val cx = a.cx - a.pillW / 2f + a.pillH / 2f
        p.width = side; p.height = side
        p.x = (cx - side / 2f).roundToInt()
        p.y = (a.cy - side / 2f).roundToInt()
    }

    private fun show(s: IslandSettings) {
        val cur = view
        if (cur != null) {
            val p = lp ?: return
            place(s, p)
            runCatching { (host ?: wm).updateViewLayout(cur, p) }
            return
        }
        st.unlocking.value = false
        st.run.intValue = st.run.intValue + 1
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { LockIconWindowContent(st) { handler.post { st.unlocking.value = false; hide() } } }
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
        view = null; host = null; lp = null
    }
}
