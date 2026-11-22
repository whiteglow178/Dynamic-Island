package com.example.dynamicisland

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.Window
import android.view.WindowManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs

/*
 * ADA YENİLEME HIZI (Ana Sayfa)
 * Adanın animasyonlarının saniyede kaç kare çizileceği: 30 / 60 / 90 / 120 Hz.
 *
 *  - Android 15+: yalnızca adanın görünümü için kare hızı istenir (View.requestedFrameRate). Seçilen değer adaya uygulanır;
 *    diğer uygulamaların yenileme hızına dokunulmaz.
 *  - Android 11–14: bu sürümlerde yalnızca pencere düzeyinde istek yapılabiliyor ve en üstteki pencere (ada) bütün ekranı
 *    etkileyebiliyor. Arkadaki uygulamayı (ör. 120 Hz oyun) yavaşlatmamak için yalnızca YÜKSELTME (90 / 120) istenir;
 *    30 ve 60'ta sistem kendi karar verir.
 * Ek izin gerekmez.
 */

object RefreshRateStore {
    val OPTIONS = listOf(30, 60, 90, 120)
    private const val FILE = "refresh_rate_prefs"
    private const val DEFAULT_HZ = 60
    @Volatile private var state: MutableStateFlow<Int>? = null

    private fun ensure(ctx: Context): MutableStateFlow<Int> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(
                ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getInt("hz", DEFAULT_HZ)
                    .let { if (it in OPTIONS) it else DEFAULT_HZ },
            ).also { state = it }
        }

    fun flow(ctx: Context): StateFlow<Int> = ensure(ctx)

    fun set(ctx: Context, hz: Int) {
        if (hz !in OPTIONS) return
        ensure(ctx).value = hz
        runCatching { ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putInt("hz", hz).apply() }
    }
}

object RefreshRate {
    /** Ekranın desteklediği yenileme hızları (geçerli çözünürlükte), yuvarlanmış ve sıralı. */
    fun supported(ctx: Context): List<Int> = runCatching {
        val d = ctx.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val cur = d.mode
        d.supportedModes
            .filter { it.physicalWidth == cur.physicalWidth && it.physicalHeight == cur.physicalHeight }
            .map { Math.round(it.refreshRate) }
            .distinct().sorted()
    }.getOrDefault(emptyList())

    /** Desteklenen hızlar arasında [hz]'e en yakını (liste boşsa null). */
    fun nearest(supported: List<Int>, hz: Int): Int? = supported.minByOrNull { abs(it - hz) }

    /** Android 11–14'te seçim gerçekte uygulanabiliyor mu (yalnızca yükseltme)? */
    fun legacyLimited(hz: Int): Boolean = Build.VERSION.SDK_INT < 35 && hz <= 60

    /** Ada penceresine ve görünümüne uygular. */
    fun apply(win: Window, views: List<View?>, hz: Int) {
        if (Build.VERSION.SDK_INT >= 35) {
            for (v in views) runCatching { v?.requestedFrameRate = hz.toFloat() }
            // Eski sürümden kalmış pencere isteği varsa temizle
            if (win.attributes.preferredRefreshRate != 0f) setWindowRate(win, 0f)
        } else {
            val supported = supported(win.context)
            val want = if (hz > 60) (nearest(supported, hz) ?: hz).toFloat() else 0f   // 0 = sistem karar verir
            if (win.attributes.preferredRefreshRate != want) setWindowRate(win, want)
        }
    }

    private fun setWindowRate(win: Window, rate: Float) {
        // Yeni nesne: aynı nesneyi geri atamak WindowManager güncellemesini tetiklemez
        val lp = WindowManager.LayoutParams().also { it.copyFrom(win.attributes) }
        lp.preferredRefreshRate = rate
        runCatching { win.attributes = lp }
    }
}
