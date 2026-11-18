package com.example.dynamicisland

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

private const val COVER_BLUR_PX = 120      // örtü tam görünürken arka plan bulanıklığı (px)
private const val COVER_GLASS_TINT = 110   // cam modunda koyu ton (0..255)

/**
 * "Durum çubuğunu gizle": durum çubuğunun üstüne dokunmatik olmayan bir pencere çizilir.
 * - Cihaz pencere bulanıklığını destekliyorsa (Android 12+): buzlu cam; üstüne hafif koyu ton biner.
 * - Desteklenmiyorsa: düz siyah.
 * Yalnızca erişilebilirlik servisi bağlıyken çalışır (TYPE_ACCESSIBILITY_OVERLAY durum çubuğunun üstünde durur).
 */
class StatusCover(
    private val svc: IslandOverlayService,
    private val wm: () -> WindowManager,
    private val cutCenterYPx: () -> Int,
    private val dp: (Float) -> Int,
    private val stripInsets: (View) -> Unit,
    /** Pencere yeniden kurulunca örtü hemen görünsün mü? */
    private val wantedNow: () -> Boolean,
) {
    private var dialog: Dialog? = null
    private var view: View? = null
    private var anim: ValueAnimator? = null
    private var t = 0f            // 0 = görünmez, 1 = tam
    private var blurPx = 0        // tam görünürken uygulanacak bulanıklık (0 = düz siyah mod)

    private fun statusBarHeightPx(): Int {
        val id = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (id > 0) svc.resources.getDimensionPixelSize(id) else dp(24f)
        return maxOf(sb, cutCenterYPx() * 2)
    }

    private fun isGlass(): Boolean =
        Build.VERSION.SDK_INT >= 31 && wm().isCrossWindowBlurEnabled

    /** Örtünün rengini ve bulanıklığını moda göre ayarlar (cam: ince koyu ton + bulanıklık, değilse düz siyah). */
    private fun style() {
        val glass = isGlass()
        blurPx = if (glass) COVER_BLUR_PX else 0
        view?.setBackgroundColor(Color.argb(if (glass) COVER_GLASS_TINT else 255, 0, 0, 0))
    }

    private fun render(value: Float) {
        t = value
        view?.alpha = value
        if (Build.VERSION.SDK_INT >= 31) {
            dialog?.window?.setBackgroundBlurRadius((blurPx * value).toInt())
        }
    }

    fun attach() {
        val a11y = IslandAccessibilityService.instance ?: return
        val v = View(svc).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val d = Dialog(ContextThemeWrapper(a11y, android.R.style.Theme_Translucent_NoTitleBar))
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        d.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setGravity(Gravity.TOP or Gravity.START)
            setDecorFitsSystemWindows(false)
            decorView.setPadding(0, 0, 0, 0)
            val lp = WindowManager.LayoutParams().also { it.copyFrom(attributes) }
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = statusBarHeightPx()
            lp.x = 0; lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        d.window?.decorView?.let { stripInsets(it) }
        if (!runCatching { d.show() }.isSuccess) return
        d.window?.decorView?.let { stripInsets(it) }
        dialog = d; view = v
        // Pencere yeniden kurulduysa mevcut duruma göre hemen uygula
        style()
        render(if (wantedNow()) 1f else 0f)
    }

    fun set(show: Boolean) {
        if (view == null) return
        anim?.cancel()
        if (show) style()
        anim = ValueAnimator.ofFloat(t, if (show) 1f else 0f).apply {
            duration = if (show) 200 else 220
            addUpdateListener { render(it.animatedValue as Float) }
            start()
        }
    }

    fun detach() {
        anim?.cancel(); anim = null
        runCatching { dialog?.dismiss() }
        dialog = null; view = null
        t = 0f
    }
}
