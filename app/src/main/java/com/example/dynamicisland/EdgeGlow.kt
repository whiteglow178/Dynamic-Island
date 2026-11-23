package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/** Asistan açılınca ekran kenarında yayılan parlama (eskiden IslandOverlayService içindeydi). */
class EdgeGlow(
    private val svc: IslandOverlayService,
    private val wm: () -> WindowManager,
    private val dp: (Float) -> Int,
) {
    private val active = MutableStateFlow(false)
    private var view: ComposeView? = null
    private var lp: WindowManager.LayoutParams? = null
    private var host: WindowManager? = null
    private var tries = 0

    /** Parlamayı açar (sağdan sola yayılır) ya da kapatır (söner). */
    fun setActive(on: Boolean) { active.value = on }

    /** Ekranın gerçek köşe yarıçapı (px); alınamazsa 36dp. Çok yuvarlak görünmesin diye 40dp ile sınırlı. */
    private fun screenCornerPx(): Float {
        val cap = dp(40f).toFloat()
        val real = if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                wm().currentWindowMetrics.windowInsets
                    .getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
            }.getOrNull()
        } else null
        return minOf(real ?: dp(36f).toFloat(), cap)
    }

    /**
     * Tam ekran, dokunmatik olmayan, şeffaf pencere: durum çubuğu dahil tüm ekranı kaplar.
     * Sistem pencereyi aşağı iterse ölçüp geri çekeriz.
     */
    fun attach() {
        val a11y = IslandAccessibilityService.instance ?: return
        val corner = screenCornerPx()
        val v = ComposeView(svc).apply {
            setViewTreeLifecycleOwner(svc)
            setViewTreeSavedStateRegistryOwner(svc)
            setContent { ScreenEdgeGlow(active, corner) }
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
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
        val h = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!runCatching { h.addView(v, params) }.isSuccess) { v.disposeComposition(); return }
        view = v; lp = params; host = h
        tries = 0
        v.postDelayed(calibrate, 300)
    }

    /** Pencere ekranın en üstünden başlamıyorsa (durum çubuğu payı) farkı kadar yukarı çeker ve boyunu uzatır. */
    private val calibrate = object : Runnable {
        override fun run() {
            val v = view ?: return
            val params = lp ?: return
            val h = host ?: return
            if (!v.isAttachedToWindow) return
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dy = loc[1]
            if (abs(dy) <= 1 || tries >= 3) return
            tries++
            val screenH = h.currentWindowMetrics.bounds.height()
            params.y -= dy
            params.height = screenH + abs(params.y)
            runCatching { h.updateViewLayout(v, params) }
            v.postDelayed(this, 250)
        }
    }

    fun detach() {
        val v = view ?: return
        v.removeCallbacks(calibrate)
        runCatching { (host ?: wm()).removeView(v) }
        v.disposeComposition()
        // Sahipler kaldırılmaz: pencere eklenip hemen kaldırılırsa bekleyen ilk çizim sahibi bulamayıp çöker
        view = null; lp = null; host = null
    }
}
