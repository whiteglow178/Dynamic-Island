package com.example.dynamicisland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.max
import kotlin.math.min

/*
 * ÖZEL TASARIM: "Mürekkep damlaları"
 * Ada simsiyah bir mürekkep lekesi gibi durur: alt kenarından farklı uzunlukta ince damlalar sarkar, kenarlarında
 * minik hayvan silüetleri (kedi, kuşlar) durur. Damlalar adanın dışına taştığı için ada penceresinin ALTINDAki
 * süs penceresinde ([DecorOverlay]) çizilir; ada içindeki dolgu düz siyahtır. Ana Sayfa'daki seçenek örneği
 * ise [drawInkSwatch] ile açık zeminde çizilir.
 */

/** Özel tasarım numarası (DESIGN_NAMES'te son). */
const val DESIGN_INK = 17

/** Sabit tohumlu rastgele sayı: damlalar her çizimde aynı yerde olur. */
private class InkRng(seed: Int) {
    private var s = seed.toLong() * 2654435761L + 11L
    fun next(): Float { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 33) and 0xFFFFFFL).toFloat() / 16777215f }
}

/** Adanın altından sarkan damlalar + kenardaki hayvanlar. [vis] 0..1: belirirken damlalar uzar. */
@Composable
internal fun InkDecor(a: DecorAnchor, body: Color, vis: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val ink = if (body.alpha <= 0f || body == Color.Transparent) Color.Black else body
        val k = (vis * vis * (3f - 2f * vis)).coerceIn(0f, 1f)
        drawInk(
            cx = a.cx, edgeY = if (a.inverted) a.top else a.top + a.h, width = a.w, unit = a.h,
            dir = if (a.inverted) -1f else 1f, color = ink, grow = k, animals = true,
        )
    }
}

/**
 * [edgeY]: adanın damlaların çıktığı kenarı, [dir] +1 aşağı / -1 yukarı, [unit] ada yüksekliği (her şey buna göre ölçeklenir).
 * Damlalar kenarın biraz içinden başlar (ada pencerenin üstünde olduğundan birleşme yeri görünmez).
 */
private fun DrawScope.drawInk(
    cx: Float, edgeY: Float, width: Float, unit: Float, dir: Float, color: Color, grow: Float, animals: Boolean,
) {
    val r = InkRng(5)
    val left = cx - width / 2f + unit * 0.45f
    val right = cx + width / 2f - unit * 0.45f
    val span = (right - left).coerceAtLeast(unit)
    val n = (span / (unit * 0.2f)).toInt().coerceIn(8, 26)
    // Adanın alt kenarında ince bir mürekkep tabakası: damlalar bundan sarkar
    val lip = Path().apply {
        moveTo(left - unit * 0.1f, edgeY - dir * unit * 0.3f)
        lineTo(right + unit * 0.1f, edgeY - dir * unit * 0.3f)
        lineTo(right + unit * 0.1f, edgeY + dir * unit * 0.04f)
        lineTo(left - unit * 0.1f, edgeY + dir * unit * 0.04f)
        close()
    }
    drawPath(lip, color)
    for (i in 0 until n) {
        val x = left + span * (i + 0.15f + r.next() * 0.7f) / n
        // Çoğu kısa, birkaçı uzun: ekran görüntüsündeki gibi düzensiz bir ızgara
        val roll = r.next()
        val len = unit * when {
            roll > 0.86f -> 0.95f + r.next() * 0.6f
            roll > 0.45f -> 0.45f + r.next() * 0.4f
            else -> 0.18f + r.next() * 0.25f
        } * grow
        val w = unit * (0.045f + r.next() * 0.05f) * (if (roll > 0.86f) 1.3f else 1f)
        val rb = w * (0.75f + r.next() * 0.35f)           // ucundaki damla yarıçapı
        val y0 = edgeY - dir * unit * 0.2f
        val y1 = edgeY + dir * len
        // ince akış
        drawLine(color, Offset(x, y0), Offset(x, y1), strokeWidth = w, cap = StrokeCap.Round)
        // adaya yapıştığı yerde hafif genişleme
        drawCircle(color, w * 1.15f, Offset(x, edgeY + dir * w * 0.4f))
        // uçtaki damla
        if (len > unit * 0.12f) drawCircle(color, rb, Offset(x, y1))
    }
    // Birkaç kalın leke: akışın ortasında yavaşlayan mürekkep topları
    repeat(3) {
        val x = left + span * (0.12f + r.next() * 0.76f)
        val y = edgeY + dir * unit * (0.35f + r.next() * 0.5f) * grow
        drawCircle(color, unit * (0.05f + r.next() * 0.03f), Offset(x, y))
    }
    if (animals) {
        val top = edgeY - dir * unit            // adanın karşı kenarı (üstü)
        val ux = unit * 0.62f
        // Üst kenarda: oturan kedi ve iki kuş
        drawCat(Offset(cx - width * 0.22f, top + dir * unit * 0.02f), ux, color, dir)
        drawBird(Offset(cx + width * 0.05f, top + dir * unit * 0.02f), ux * 0.62f, color, dir, 1f)
        drawBird(Offset(cx + width * 0.22f, top + dir * unit * 0.02f), ux * 0.5f, color, dir, -1f)
        // Sol kenarda: köşede küçük bir kuş
        drawBird(Offset(cx - width / 2f - unit * 0.06f, edgeY - dir * unit * 0.18f), ux * 0.48f, color, dir, 1f)
    }
}

/** Oturan kedi silüeti. [base] ayakların bastığı nokta; [dir] -1 ise baş aşağı (ters ada) çizilir. */
private fun DrawScope.drawCat(base: Offset, u: Float, c: Color, dir: Float) {
    val s = -dir                                    // yukarı yön (dir=+1 iken ekranda -y)
    fun p(x: Float, y: Float) = Offset(base.x + x * u, base.y + s * y * u)
    // gövde
    drawOval(c, topLeft = p(-0.32f, 0.55f).let { Offset(it.x, min(it.y, p(-0.32f, 0.0f).y)) }, size = Size(0.62f * u, 0.55f * u))
    // kafa
    drawCircle(c, 0.17f * u, p(0.1f, 0.68f))
    // kulaklar
    val earL = Path().apply { moveTo(p(0.0f, 0.74f).x, p(0.0f, 0.74f).y); lineTo(p(0.02f, 0.95f).x, p(0.02f, 0.95f).y); lineTo(p(0.1f, 0.82f).x, p(0.1f, 0.82f).y); close() }
    val earR = Path().apply { moveTo(p(0.1f, 0.82f).x, p(0.1f, 0.82f).y); lineTo(p(0.19f, 0.95f).x, p(0.19f, 0.95f).y); lineTo(p(0.24f, 0.72f).x, p(0.24f, 0.72f).y); close() }
    drawPath(earL, c); drawPath(earR, c)
    // kuyruk
    val tail = Path().apply {
        moveTo(p(-0.3f, 0.12f).x, p(-0.3f, 0.12f).y)
        cubicTo(p(-0.55f, 0.1f).x, p(-0.55f, 0.1f).y, p(-0.55f, 0.45f).x, p(-0.55f, 0.45f).y, p(-0.42f, 0.52f).x, p(-0.42f, 0.52f).y)
    }
    drawPath(tail, c, style = Stroke(0.07f * u, cap = StrokeCap.Round))
}

/** Tüneyen küçük kuş silüeti. [face] +1 sağa, -1 sola bakar. */
private fun DrawScope.drawBird(base: Offset, u: Float, c: Color, dir: Float, face: Float) {
    val s = -dir
    fun p(x: Float, y: Float) = Offset(base.x + face * x * u, base.y + s * y * u)
    // bacaklar
    drawLine(c, p(-0.05f, 0.0f), p(-0.05f, 0.22f), strokeWidth = 0.05f * u, cap = StrokeCap.Round)
    drawLine(c, p(0.1f, 0.0f), p(0.1f, 0.22f), strokeWidth = 0.05f * u, cap = StrokeCap.Round)
    // gövde
    val bodyTL = p(-0.28f, 0.7f)
    val bodyBR = p(0.28f, 0.2f)
    drawOval(c, Offset(min(bodyTL.x, bodyBR.x), min(bodyTL.y, bodyBR.y)), Size(kotlin.math.abs(bodyBR.x - bodyTL.x), kotlin.math.abs(bodyBR.y - bodyTL.y)))
    // kafa
    drawCircle(c, 0.16f * u, p(0.26f, 0.72f))
    // gaga
    val beak = Path().apply {
        moveTo(p(0.38f, 0.76f).x, p(0.38f, 0.76f).y); lineTo(p(0.55f, 0.7f).x, p(0.55f, 0.7f).y); lineTo(p(0.38f, 0.66f).x, p(0.38f, 0.66f).y); close()
    }
    drawPath(beak, c)
    // kuyruk
    val tail = Path().apply {
        moveTo(p(-0.22f, 0.5f).x, p(-0.22f, 0.5f).y); lineTo(p(-0.62f, 0.42f).x, p(-0.62f, 0.42f).y); lineTo(p(-0.5f, 0.3f).x, p(-0.5f, 0.3f).y); close()
    }
    drawPath(tail, c)
}

/**
 * Ana Sayfa'daki küçük daire örneği: açık gri zeminde siyah leke, altından damlalar, üstünde hayvanlar.
 * (Gerçek adada zemin düz siyahtır; damlalar [InkDecor] ile adanın dışına çizilir.)
 */
internal fun DrawScope.drawInkSwatch(w: Float, h: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFE2E2E2), Color(0xFFB5B5B5))))
    val bh = h * 0.26f
    val bw = w * 0.74f
    val y = h * 0.3f
    drawRoundRect(Color(0xFF0A0A0A), Offset((w - bw) / 2f, y), Size(bw, bh), CornerRadius(bh / 2f, bh / 2f))
    drawInk(cx = w / 2f, edgeY = y + bh, width = bw, unit = max(bh, 1f), dir = 1f, color = Color(0xFF0A0A0A), grow = 1f, animals = false)
    // minik kedi: örnekte de tasarımın kimliği belli olsun
    drawCat(Offset(w * 0.36f, y + bh * 0.04f), bh * 0.62f, Color(0xFF0A0A0A), 1f)
}

/** Ana Sayfa'daki ada önizlemesi: adanın alanıyla aynı boyutta verilir, damlalar ve hayvanlar dışına taşar (ada üstüne çizilir). */
@Composable
internal fun InkPreviewDrips(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawInk(
            cx = size.width / 2f, edgeY = size.height, width = size.width, unit = size.height,
            dir = 1f, color = Color.Black, grow = 1f, animals = true,
        )
    }
}
