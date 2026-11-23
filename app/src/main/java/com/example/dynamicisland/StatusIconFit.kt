package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "Bildirim simgelerine yer aç": durum çubuğunun solundaki (saat + bildirim simgeleri) ve sağındaki
 * (alarm, bluetooth, sinyal, pil) içeriğin kameraya en yakın kenarlarını bulur.
 *
 * Erişilebilirlik servisi sistem arayüzünün durum çubuğu penceresini okur (izin zaten var): görünür, küçük
 * yaprak öğelerin (saat yazısı, simgeler) ekran konumlarına bakar. Kameranın solundakilerin en sağ kenarı
 * [Edges.left], sağındakilerin en sol kenarı [Edges.right] olur. Durum çubuğu bulunamazsa (tam ekran uygulama,
 * açık bildirim paneli) birkaç denemeden sonra ikisi de -1 olur ve ada normal boyutuna döner.
 *
 * Servis bu değerlerle kapalı adanın sol ucunu simgelerin ötesine çeker ve aynı miktarda sağa uzatır
 * (IslandOverlayService.fitStatusIcons). Ada her zaman kamera deliğini örter.
 */
object StatusIcons {
    /** Ekran pikseli. -1 = bilinmiyor. */
    data class Edges(val left: Int = -1, val right: Int = -1)

    val edges = MutableStateFlow(Edges())

    /** Ayar açık mı (servis ayarlardan yazar). Kapalıyken tarama yapılmaz. */
    @Volatile var enabled = false

    /** Kamera deliğinin ekran X'i (servis yazar); bilinmiyorsa ekranın ortası kullanılır. */
    @Volatile var cameraX = -1

    private const val SYSTEMUI = "com.android.systemui"
    private var misses = 0

    /** Erişilebilirlik servisinin arka plan thread'inden çağrılır. */
    fun scan(svc: AccessibilityService) {
        if (!enabled) { misses = 0; if (edges.value != Edges()) edges.value = Edges(); return }
        val dm = svc.resources.displayMetrics
        val sw = dm.widthPixels
        val density = dm.density
        if (sw <= 0 || sw > dm.heightPixels) { reset(); return }      // yatay ekranda ada başka düzende
        val sbId = svc.resources.getIdentifier("status_bar_height", "dimen", "android")
        val sbH = if (sbId > 0) svc.resources.getDimensionPixelSize(sbId) else (24f * density).toInt()
        val cam = if (cameraX > 0) cameraX else sw / 2
        val nearCam = (10f * density).toInt()

        var found = false
        var left = -1
        var right = Int.MAX_VALUE
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
            val r = Rect()
            var count = 0
            fun walk(n: AccessibilityNodeInfo, depth: Int) {
                if (depth > 24 || count > 500) return
                count++
                if (!n.isVisibleToUser) return
                n.getBoundsInScreen(r)
                if (r.bottom <= 0 || r.top >= sbH * 1.2f) return
                if (n.childCount == 0) {
                    val small = r.width() > 0 && r.height() > 0 && r.width() < sw * 0.3f && r.height() < sbH * 1.5f
                    val cls = n.className?.toString().orEmpty()
                    val meaningful = !n.text.isNullOrEmpty() || !n.contentDescription.isNullOrEmpty() ||
                        cls.contains("Image") || cls.contains("Icon")
                    if (small && meaningful) {
                        when {
                            r.centerX() < cam - nearCam -> left = max(left, r.right)
                            r.centerX() > cam + nearCam -> right = min(right, r.left)
                        }
                    }
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
            if (++misses >= 3) reset()
            return
        }
        misses = 0
        val next = Edges(left, if (right == Int.MAX_VALUE) -1 else right)
        val cur = edges.value
        val tol = (2f * density).toInt()
        // Küçük oynamalarda adayı yeniden yerleştirme
        if (abs(next.left - cur.left) > tol || abs(next.right - cur.right) > tol) edges.value = next
    }

    private fun reset() {
        misses = 0
        if (edges.value != Edges()) edges.value = Edges()
    }
}
