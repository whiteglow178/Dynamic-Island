package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tam ekran animasyon katmanlarından (uygulama açılışı, müzikten çıkış paneli, uzayan ada) birinin yuvası.
 * [safetyMs]: ne olursa olsun katmanın ekranda kalabileceği en uzun süre. [onRemoved]: katman kalkınca çalışır.
 */
class OverlaySlot(val safetyMs: Long, val onRemoved: () -> Unit = {}) {
    internal var view: ComposeView? = null
    internal var wm: WindowManager? = null
    /** Katman şu an ekranda mı? */
    val shown: Boolean get() = view != null
}

/** Tam ekran, dokunuşu geçiren Compose katmanlarını ekleyip kaldırır (eskiden servisin içinde üç kez tekrarlanıyordu). */
class TransitionOverlays(
    private val svc: IslandOverlayService,
    private val defaultWm: () -> WindowManager,
    private val scope: CoroutineScope,
) {
    private fun params(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
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
    }

    /**
     * [content]'e verilen `onFinished` çağrılınca katman (hâlâ aynıysa) kaldırılır, ardından [afterFinished] çalışır.
     * Pencere eklenemezse false döner.
     */
    fun show(
        slot: OverlaySlot,
        afterFinished: () -> Unit = {},
        content: @Composable (onFinished: () -> Unit) -> Unit,
    ): Boolean {
        val handler = Handler(Looper.getMainLooper())
        lateinit var view: ComposeView
        view = ComposeView(svc).apply {
            setViewTreeLifecycleOwner(svc)
            setViewTreeSavedStateRegistryOwner(svc)
            setContent {
                val onFin = { handler.post { if (slot.view === view) remove(slot); afterFinished() }; Unit }
                content(onFin)
            }
        }
        // Ada penceresiyle aynı katman: erişilebilirlik overlay'i durum çubuğunun da üstünde çizilir
        var host: WindowManager = defaultWm()
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(view, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                host = h2; added = true
            }
        }
        if (!added) added = runCatching { host.addView(view, params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) }.isSuccess
        if (!added) { view.disposeComposition(); return false }
        slot.view = view; slot.wm = host
        // Güvenlik: ne olursa olsun katman ekranda kalmasın
        scope.launch { delay(slot.safetyMs); if (slot.view === view) remove(slot) }
        return true
    }

    fun remove(slot: OverlaySlot) {
        val v = slot.view
        if (v != null) {
            runCatching { (slot.wm ?: defaultWm()).removeView(v) }
            v.disposeComposition()
            // Sahipler kaldırılmaz: pencere eklenip hemen kaldırılırsa bekleyen ilk çizim sahibi bulamayıp çöker
        }
        slot.view = null; slot.wm = null
        slot.onRemoved()
    }
}
