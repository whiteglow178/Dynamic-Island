package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Kamuflaj: boştaki ada, hemen yanındaki ekran renginin (çoğunlukla durum çubuğunun zemini) aynısını alır ve
 * neredeyse kaybolur. Bir olay gelince ada normal rengine bukalemun gibi geçerek ortaya çıkar.
 *
 * Renk, erişilebilirlik servisinin ekran görüntüsü yeteneğiyle (Android 11+) okunur. Ayrı bir izin penceresi yoktur;
 * yalnızca adanın iki yanındaki birkaç piksel okunur, görüntü hiçbir yere kaydedilmez ve hemen silinir.
 */
object CamoState {
    /** Son okunan renk (ARGB); null = henüz yok / okunamadı. */
    var color by mutableStateOf<Int?>(null)
    /** Ekran görüntüsü alınamıyor (yetenek yok / desteklenmiyor): ayarlarda uyarı göstermek için. */
    @Volatile var unsupported = false

    private val worker by lazy { Executors.newSingleThreadExecutor() }

    /**
     * Adanın soluna ve sağına [gap] px uzaklıktaki noktaları okur, ortalama rengi [done] ile verir (arka plan iş parçacığında).
     * [left] / [right]: adanın ekrandaki sol / sağ kenarı, [cy]: dikey merkezi.
     */
    fun sample(a11y: AccessibilityService, left: Int, right: Int, cy: Int, gap: Int, done: (Int?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { unsupported = true; done(null); return }
        val ok = runCatching {
            a11y.takeScreenshot(Display.DEFAULT_DISPLAY, worker, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val c = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return@runCatching null
                        val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        try { average(sw, left, right, cy, gap) } finally { sw.recycle() }
                    }.getOrNull()
                    runCatching { hb.close() }
                    unsupported = false
                    done(c)
                }

                override fun onFailure(errorCode: Int) {
                    // 1 = yetenek yok (erişilebilirlik bir kez kapatılıp açılmalı); diğerleri geçici (çok sık istek, güvenli pencere)
                    if (errorCode == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS) unsupported = true
                    done(null)
                }
            })
        }.isSuccess
        if (!ok) { unsupported = true; done(null) }
    }

    private fun average(b: Bitmap, left: Int, right: Int, cy: Int, gap: Int): Int? {
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0) return null
        val y0 = cy.coerceIn(0, h - 1)
        val xs = intArrayOf(left - gap, left - gap * 2, right + gap, right + gap * 2)
        var r = 0; var g = 0; var bl = 0; var n = 0
        for (x in xs) for (dy in intArrayOf(-gap / 2, 0, gap / 2)) {
            val px = x.coerceIn(0, w - 1); val py = (y0 + dy).coerceIn(0, h - 1)
            val c = b.getPixel(px, py)
            r += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; bl += c and 0xFF; n++
        }
        if (n == 0) return null
        return (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (bl / n)
    }

    /** İki renk gözle ayırt edilemeyecek kadar yakınsa true (gereksiz yeniden çizim olmasın). */
    fun close(a: Int?, b: Int): Boolean {
        if (a == null) return false
        return abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) < 4 &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) < 4 &&
            abs((a and 0xFF) - (b and 0xFF)) < 4
    }
}
