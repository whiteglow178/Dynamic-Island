package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Bildirim simgelerine yer aç": durum çubuğunun solundaki içeriğin (saat + bildirim simgeleri + "…")
 * gerçekte nerede bittiğini bulur.
 *
 * 1) Erişilebilirlik ağacından saatin yeri ve rengi okunur (durum çubuğu penceresi, sistem arayüzü).
 * 2) Ekran görüntüsünde saatin hemen sağından başlayıp, satır satır saat rengindeki (simge rengi) pikseller
 *    aranır. Simgeler arasındaki küçük boşluklar atlanır; uzun bir boşluk gelince (simgeler bitti) durulur.
 *    Böylece sistemin görünmez tuttuğu ya da kameraya kadar uzanan boş "simge kutusu" simge sanılmaz.
 * 3) Simgeler adanın kenarına kadar geliyorsa altında devamı olabilir: ada biraz daha çekilip yeniden bakılır.
 * Ekran görüntüsü alınamazsa yalnızca tek tek simge öğelerine (kutulara değil) bakılır.
 *
 * Servis bu kenarla kapalı adanın sol ucunu geri çeker; sağ uç yerinde kalır (IslandOverlayService.fitStatusIcons).
 */
object StatusIcons {
    /** Ekran pikseli. -1 = bilinmiyor / simge yok. [left]: soldaki içeriğin en sağ kenarı. */
    data class Edges(val left: Int = -1)

    val edges = MutableStateFlow(Edges())

    /** Ayar açık mı (servis ayarlardan yazar). Kapalıyken tarama yapılmaz. */
    @Volatile var enabled = false

    /** Ada açıkken (durum çubuğunu kapatıyor) tarama yapılmaz, son değer korunur. */
    @Volatile var paused = false

    /** Kamera deliğinin ekran X'i (servis yazar); bilinmiyorsa ekranın ortası kullanılır. */
    @Volatile var cameraX = -1

    /** Kapalı adanın şu anki sol kenarı (ekran px, servis yazar): ekran görüntüsünde adanın altı okunmaz. */
    @Volatile var islandLeft = -1

    private const val SYSTEMUI = "com.android.systemui"
    private val TIME_RX = Regex("^\\s*\\d{1,2}[:.]\\d{2}")
    private var misses = 0
    private var lastShotAt = 0L
    @Volatile private var shotBusy = false

    private class Clock(val rect: Rect)

    /** Erişilebilirlik servisinin arka plan thread'inden çağrılır. [force]: ekran görüntüsünü beklemeden al. */
    fun scan(svc: AccessibilityService, exec: Executor, force: Boolean) {
        if (!enabled) { misses = 0; publish(Edges(), 0); return }
        if (paused) return
        val dm = svc.resources.displayMetrics
        val sw = dm.widthPixels
        val density = dm.density
        if (sw <= 0 || sw > dm.heightPixels) { publish(Edges(), 0); return }       // yatay ekranda ada başka düzende
        val sbId = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbH = if (sbId > 0) svc.resources.getDimensionPixelSize(sbId) else (24f * density).toInt()
        val cam = if (cameraX > 0) cameraX else sw / 2
        val nearCam = (10f * density).toInt()

        var found = false
        var clock: Clock? = null
        var nodeLeft = -1
        val ws = runCatching { svc.windows }.getOrNull().orEmpty()
        val wr = Rect()
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_SYSTEM) continue
            w.getBoundsInScreen(wr)
            // Durum çubuğu: ekranın en üstünde, ince bir şerit. Bildirim paneli açıkken pencere uzar: sayılmaz.
            if (wr.top > 0 || wr.height() <= 0 || wr.height() > sbH * 2) continue
            val root = runCatching { w.root }.getOrNull() ?: continue
            if (root.packageName?.toString() != SYSTEMUI) continue
            found = true
            var count = 0
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 24 || count > 500) return
                count++
                if (!n.isVisibleToUser) return
                val r = Rect()
                n.getBoundsInScreen(r)
                if (r.bottom <= 0 || r.top >= sbH * 1.2f) return
                val txt = n.text?.toString().orEmpty()
                if (clock == null && r.centerX() < cam && TIME_RX.containsMatchIn(txt)) clock = Clock(r)
                if (n.childCount == 0) {
                    val cls = n.className?.toString().orEmpty()
                    // Kutular (simge kapları, düzenler) sayılmaz: kameraya kadar uzanıp boşken de "dolu" görünürler
                    val box = cls.contains("Container") || cls.contains("Layout") || cls.contains("Group")
                    val single = r.width() > 0 && r.height() > 0 && r.width() < 40f * density && r.height() < sbH * 1.5f
                    val meaningful = txt.isNotEmpty() || !n.contentDescription.isNullOrEmpty() || cls.contains("Image")
                    if (!box && single && meaningful && r.centerX() < cam - nearCam) nodeLeft = max(nodeLeft, r.right)
                    return
                }
                for (i in 0 until n.childCount) {
                    val ch = runCatching { n.getChild(i) }.getOrNull() ?: continue
                    walk(ch, depth + 1)
                }
            }
            runCatching { walk(root, 0) }
        }
        if (!found) {
            // Pencere listesi bazen bir an boş gelir: hemen sıfırlanırsa ada titrer
            if (++misses >= 3) publish(Edges(), 0)
            return
        }
        misses = 0
        val tol = (2f * density).toInt()
        val c = clock
        val now = SystemClock.uptimeMillis()
        if (c == null || Build.VERSION.SDK_INT < 30) { publish(Edges(nodeLeft), tol); return }
        if (shotBusy || (!force && now - lastShotAt < 2_500L)) return
        lastShotAt = now
        shotBusy = true
        val ok = runCatching {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, exec, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                    val hb = result.hardwareBuffer
                    val ink = runCatching {
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace) ?: return@runCatching -1
                        val bmp = hw.copy(Bitmap.Config.ARGB_8888, false)
                        hw.recycle()
                        try { inkEdge(bmp, c.rect, cam, sbH, density) } finally { bmp.recycle() }
                    }.getOrDefault(-1)
                    runCatching { hb.close() }
                    shotBusy = false
                    publish(Edges(if (ink >= 0) ink else nodeLeft), tol)
                }

                override fun onFailure(errorCode: Int) {
                    shotBusy = false
                    publish(Edges(nodeLeft), tol)
                }
            })
        }.isSuccess
        if (!ok) { shotBusy = false; publish(Edges(nodeLeft), tol) }
    }

    /**
     * Saatin sağından kameraya doğru, saat rengindeki piksellerin oluşturduğu kesintisiz şeridin sonu.
     * Simgeler arası boşluk 16dp'yi geçerse şerit biter. Adanın altına kadar uzanıyorsa adanın
     * kenarının biraz ötesi döner (ada çekilip yeniden bakılır). -1: okunamadı.
     */
    private fun inkEdge(b: Bitmap, clock: Rect, cam: Int, sbH: Int, density: Float): Int {
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0 || clock.width() <= 0) return -1
        val y0 = clock.top.coerceIn(0, h - 1)
        val y1 = clock.bottom.coerceIn(y0 + 1, h)
        val bandTop = max(0, (sbH * 0.2f).toInt())
        val bandBot = min(h, max(bandTop + 1, (sbH * 0.85f).toInt()))
        // Saatin rengi: saat kutusunda, kutunun kenarındaki renkten (zemin) en uzak piksel
        val bg = b.getPixel(clock.left.coerceIn(0, w - 1), ((y0 + y1) / 2).coerceIn(0, h - 1))
        var fg = bg; var best = -1
        for (y in y0 until y1 step 2) for (x in clock.left.coerceAtLeast(0) until clock.right.coerceAtMost(w) step 2) {
            val p = b.getPixel(x, y)
            val dd = dist(p, bg)
            if (dd > best) { best = dd; fg = p }
        }
        if (best < 80) return -1                       // saat zeminden ayırt edilemiyor
        val near = 60
        val isl = islandLeft
        val limit = min(cam - (10f * density).toInt(), if (isl > 0) isl - 1 else cam)
        val start = clock.right.coerceIn(0, w - 1)
        if (limit <= start) return clock.right
        val gapMax = (16f * density).toInt()
        var last = start
        var x = start
        while (x <= limit && x < w) {
            var ink = false
            var y = bandTop
            while (y < bandBot) { if (dist(b.getPixel(x, y), fg) < near) { ink = true; break }; y++ }
            if (ink) last = x else if (x - last > gapMax) break
            x++
        }
        // Simgeler adanın kenarına dayanmış: altında devamı olabilir, ada biraz daha çekilsin
        if (isl > 0 && last >= isl - (3f * density).toInt()) return min(isl + (18f * density).toInt(), cam)
        return last
    }

    private fun dist(a: Int, b: Int): Int =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) + abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) + abs((a and 0xFF) - (b and 0xFF))

    private fun publish(next: Edges, tol: Int) {
        val cur = edges.value
        if (cur == next) return
        if (cur.left >= 0 && next.left >= 0 && abs(next.left - cur.left) <= tol) return     // küçük oynamalarda yerinde kal
        edges.value = next
    }
}
