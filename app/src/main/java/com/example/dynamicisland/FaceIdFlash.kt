package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Uygulama açılış animasyonu (Face ID animasyonunun uyarlaması): seçili bir uygulama açılınca adanın yerinde
 * Face ID taraması bir kez oynar ve onay işaretiyle biter. Hiçbir doğrulama istemez, uygulamayı kilitlemez;
 * yalnızca görsel bir efekttir. Dokunuş almayan, geçici bir pencerede çizilir; animasyon bitince pencere kaldırılır.
 */
class FaceIdFlash(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val registryOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    /** Animasyon başlayınca true, bitince false (bu sürede normal ada gizlenir). */
    private val onActive: (Boolean) -> Unit,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private val run = mutableIntStateOf(0)

    /** Animasyonu [a] konumundaki adanın yerinde bir kez oynatır (zaten oynuyorsa baştan başlatır). */
    fun play(a: FaceIdAnchor) {
        val s = settings.value
        val d = ctx.resources.displayMetrics.density
        val g = FaceIdGeo(a.pillW, a.pillH, s.faceIdSizeDp * d, FACE_ID_PAD_DP * d, false)
        val side = g.side.roundToInt()
        if (view == null && !attach(side, (a.cx - side / 2f).roundToInt(), (a.cy - a.pillH / 2f - g.pad).roundToInt(), g)) return
        run.intValue++
        onActive(true)
    }

    fun release() = detach()

    private fun attach(side: Int, x: Int, y: Int, g: FaceIdGeo): Boolean {
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(registryOwner)
            setContent {
                val s by settings.collectAsState()
                val id = run.intValue
                val sprite = rememberFaceIdSprite()
                val t = remember(id) { mutableFloatStateOf(0f) }
                LaunchedEffect(id) {
                    var last = 0L
                    // Uygulama açılırken uzun beklemesin diye ayardaki hızın 1,6 katı oynar
                    val speed = (s.faceIdSpeed * 1.6f).coerceIn(0.4f, 4f)
                    while (t.floatValue < FaceIdTl.END + 0.05f) {
                        val now = withFrameNanos { it }
                        if (last != 0L) t.floatValue += min((now - last) / 1_000_000_000f, 0.05f) * speed
                        last = now
                    }
                    // Kendi kompozisyonunun içinden değil, bir sonraki döngüde kaldırılır
                    android.os.Handler(android.os.Looper.getMainLooper()).post { detach() }
                }
                Canvas(Modifier.fillMaxSize()) { drawFaceId(t.floatValue, s, g, sprite, -1f) }
            }
        }
        val lp = { type: Int ->
            WindowManager.LayoutParams(
                side, side, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x; this.y = y
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        var added = false
        // Erişilebilirlik penceresi durum çubuğunun üstünde çizilir (ada ile aynı katman)
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(v, lp(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)); host = h2; added = true
            }
        }
        if (!added) added = runCatching {
            wm.addView(v, lp(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)); host = wm
        }.isSuccess
        if (!added) { v.disposeComposition(); return false }
        view = v
        return true
    }

    private fun detach() {
        val v = view ?: return
        view = null
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        host = null
        onActive(false)
    }
}
