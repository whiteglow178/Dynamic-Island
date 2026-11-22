package com.example.dynamicisland

import android.graphics.Bitmap

/**
 * Uygulama simgesinin baskın (en doygun ve parlak) rengi; bildirimde renkli ışık için.
 * Albüm kapağı rengiyle aynı yöntem (IslandNotificationListener.dominantColor). Renk bulunamazsa 0.
 */
fun appAccentColor(src: Bitmap): Int {
    val soft = runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return 0
    val n = 24
    val bmp = runCatching { Bitmap.createScaledBitmap(soft, n, n, true) }.getOrNull() ?: return 0
    val px = IntArray(n * n)
    bmp.getPixels(px, 0, n, 0, 0, n, n)
    val wSum = FloatArray(12); val hSum = FloatArray(12); val sSum = FloatArray(12); val vSum = FloatArray(12)
    val hsv = FloatArray(3)
    for (c in px) {
        if (android.graphics.Color.alpha(c) < 128) continue        // simgenin saydam kenarları
        android.graphics.Color.colorToHSV(c, hsv)
        if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue                // gri / çok koyu pikselleri atla
        val w = hsv[1] * hsv[2]
        val b = (hsv[0] / 30f).toInt().coerceIn(0, 11)
        wSum[b] += w; hSum[b] += w * hsv[0]; sSum[b] += w * hsv[1]; vSum[b] += w * hsv[2]
    }
    var best = -1; var bw = 0f
    for (i in 0 until 12) if (wSum[i] > bw) { bw = wSum[i]; best = i }
    if (best < 0 || bw < 1.5f) return 0
    return android.graphics.Color.HSVToColor(
        floatArrayOf(hSum[best] / bw, (sSum[best] / bw).coerceIn(0.45f, 1f), (vSum[best] / bw).coerceIn(0.6f, 1f)),
    )
}
