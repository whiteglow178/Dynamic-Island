package com.example.dynamicisland

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Özel ada tasarımı için sonradan eklenen desenler (7 ve sonrası).
 * Kayıtlı ayarlar bozulmasın diye eski numaralar (0–5 desen, 6 fotoğraf) değişmedi; yeniler sona eklendi.
 * Hepsi kodla çizilir (görsel dosyası yoktur), her boyutta keskin kalır.
 */

/** Toplam tasarım sayısı (fotoğraf dahil). Yeni desen eklenince [DESIGN_NAMES] ile birlikte artırılır. */
const val DESIGN_COUNT = 17

/** Ana Sayfa'da gösterim sırası: önce desenler, en sonda fotoğraf. */
val DESIGN_ORDER: List<Int> = (0 until DESIGN_PHOTO) + (DESIGN_PHOTO + 1 until DESIGN_COUNT) + DESIGN_PHOTO

private const val TWO_PI = (2.0 * PI).toFloat()

/** Sabit tohumlu basit rastgele sayı (her çizimde aynı desen). */
private class PatRng(seed: Int) {
    private var s = seed.toLong() * 2654435761L + 7L
    fun next(): Float { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 33) and 0xFFFFFFL).toFloat() / 16777215f }
}

/** 7 ve sonrası desenleri çizer; bilinmeyen numarada siyah. */
internal fun DrawScope.drawExtraDesign(style: Int, w: Float, h: Float) {
    when (style) {
        7 -> drawAurora(w, h)
        8 -> drawOcean(w, h)
        9 -> drawSynthwave(w, h)
        10 -> drawMarble(w, h)
        11 -> drawCarbon(w, h)
        12 -> drawSakura(w, h)
        13 -> drawGalaxy(w, h)
        14 -> drawHolo(w, h)
        15 -> drawLeopard(w, h)
        16 -> drawArtDeco(w, h)
        else -> drawRect(Color.Black)
    }
}

// ---------------------------------------------------------------- 7 Kuzey ışıkları
private fun DrawScope.drawAurora(w: Float, h: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF020617), Color(0xFF0A1A33), Color(0xFF06101F))))
    val r = PatRng(21)
    val n = (w * h / (9.dp.toPx() * 9.dp.toPx())).toInt().coerceIn(12, 140)
    repeat(n) {
        drawCircle(Color.White.copy(alpha = 0.25f + r.next() * 0.5f), radius = (0.3f + r.next() * 0.7f).dp.toPx(),
            center = Offset(r.next() * w, r.next() * h))
    }
    val cols = listOf(Color(0xFF2CFFA8), Color(0xFF29D3FF), Color(0xFFB067FF))
    val step = max(w / 60f, 2f)
    for (k in cols.indices) {
        val baseY = h * (0.22f + k * 0.16f)
        val amp = h * (0.16f - k * 0.03f)
        val band = h * (0.55f - k * 0.08f)
        val freq = 1.3f + k * 0.7f
        val ph = k * 1.9f
        val p = Path()
        var x = -step
        p.moveTo(x, baseY)
        while (x <= w + step) {
            p.lineTo(x, baseY + amp * sin(x / w * TWO_PI * freq + ph) + amp * 0.4f * sin(x / w * TWO_PI * freq * 2.3f + ph * 1.7f)); x += step
        }
        x = w + step
        while (x >= -step) {
            p.lineTo(x, baseY + band + amp * sin(x / w * TWO_PI * freq + ph)); x -= step
        }
        p.close()
        val c = cols[k]
        drawPath(p, Brush.verticalGradient(
            0f to Color.Transparent, 0.25f to c.copy(alpha = 0.55f), 0.55f to c.copy(alpha = 0.22f), 1f to Color.Transparent,
            startY = baseY - amp, endY = baseY + band + amp,
        ))
    }
}

// ---------------------------------------------------------------- 8 Okyanus dalgaları
private fun DrawScope.drawOcean(w: Float, h: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF7FD4F5), Color(0xFF1B78B8))))
    val layers = listOf(Color(0xFF3FA7DB), Color(0xFF1F86C2), Color(0xFF136AA3), Color(0xFF0B4F80), Color(0xFF06345A))
    val step = max(w / 70f, 2f)
    layers.forEachIndexed { i, c ->
        val base = h * (0.18f + i * 0.19f)
        val amp = h * 0.07f
        val freq = (w / (h * 1.1f)).coerceAtLeast(1f) * (1f + i * 0.15f)
        val ph = i * 2.2f
        val p = Path()
        var x = -step
        p.moveTo(x, h + 2f)
        while (x <= w + step) {
            p.lineTo(x, base + amp * sin(x / w * TWO_PI * freq + ph)); x += step
        }
        p.lineTo(w + step, h + 2f); p.close()
        drawPath(p, c)
        // köpük çizgisi
        val foam = Path()
        x = -step
        foam.moveTo(x, base + amp * sin(x / w * TWO_PI * freq + ph))
        while (x <= w + step) {
            foam.lineTo(x, base + amp * sin(x / w * TWO_PI * freq + ph)); x += step
        }
        drawPath(foam, Color.White.copy(alpha = 0.45f - i * 0.06f), style = Stroke(width = max(h * 0.025f, 0.8f.dp.toPx()), cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------- 9 Neon ızgara (synthwave)
private fun DrawScope.drawSynthwave(w: Float, h: Float) {
    val hy = h * 0.6f
    drawRect(Brush.verticalGradient(0f to Color(0xFF1A0B3B), 0.6f to Color(0xFF8E2DE2), 1f to Color(0xFFFF4E9A), endY = hy), size = Size(w, hy))
    // çizgili güneş
    val c = Offset(w * 0.5f, hy)
    val sr = min(h * 0.46f, w * 0.3f)
    clipRect(0f, 0f, w, hy) {
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFB86B), Color.Transparent), center = c, radius = sr * 1.9f), radius = sr * 1.9f, center = c)
        drawCircle(Brush.verticalGradient(listOf(Color(0xFFFFE66D), Color(0xFFFF6B9A)), startY = c.y - sr, endY = c.y), radius = sr, center = c)
        for (i in 0 until 4) {
            val y = c.y - sr * (0.12f + i * 0.17f)
            drawRect(Color(0xFFC2349A), Offset(c.x - sr, y), Size(sr * 2f, sr * (0.07f - i * 0.012f)))
        }
    }
    drawRect(Color(0xFF14002B), Offset(0f, hy), Size(w, h - hy))
    val grid = Color(0xFFFF2BD6)
    val sw = max(h * 0.018f, 0.7f.dp.toPx())
    drawLine(grid, Offset(0f, hy), Offset(w, hy), strokeWidth = sw * 1.4f)
    for (i in 1..6) {
        val p = i / 6f
        val y = hy + (h - hy) * p * p
        drawLine(grid.copy(alpha = 0.85f), Offset(0f, y), Offset(w, y), strokeWidth = sw)
    }
    val lines = (w / (h * 0.35f)).toInt().coerceIn(6, 40)
    for (i in -lines..lines) {
        val xb = w * 0.5f + i * (w / lines) * 1.6f
        drawLine(grid.copy(alpha = 0.7f), Offset(w * 0.5f + i * (w / lines) * 0.18f, hy), Offset(xb, h), strokeWidth = sw)
    }
}

// ---------------------------------------------------------------- 10 Mermer
private fun DrawScope.drawMarble(w: Float, h: Float) {
    drawRect(Brush.linearGradient(listOf(Color(0xFFF7F5F1), Color(0xFFE4E1DB), Color(0xFFF4F2EE)), start = Offset.Zero, end = Offset(w, h)))
    val r = PatRng(33)
    val veins = (w / (h * 0.8f)).toInt().coerceIn(3, 14)
    repeat(veins) { k ->
        val gold = k % 4 == 1
        val p = Path()
        var x = -w * 0.05f + r.next() * w * 0.3f - w * 0.15f
        var y = r.next() * h
        p.moveTo(x, y)
        val stepX = max(w / 18f, 3f.dp.toPx())
        val slope = (r.next() - 0.5f) * h * 0.35f
        while (x < w * 1.1f) {
            x += stepX * (0.6f + r.next() * 0.8f)
            y += slope * 0.25f + (r.next() - 0.5f) * h * 0.28f
            p.lineTo(x, y)
        }
        val col = if (gold) Color(0xFFC9A24B).copy(alpha = 0.85f) else Color(0xFF8A8A8A).copy(alpha = 0.35f + r.next() * 0.25f)
        drawPath(p, col, style = Stroke(width = (if (gold) 1.1f else 0.6f + r.next() * 0.8f).dp.toPx(), cap = StrokeCap.Round))
        if (!gold) drawPath(p, Color(0xFF9E9E9E).copy(alpha = 0.10f), style = Stroke(width = 4f.dp.toPx(), cap = StrokeCap.Round))
    }
}

// ---------------------------------------------------------------- 11 Karbon fiber
private fun DrawScope.drawCarbon(w: Float, h: Float) {
    drawRect(Color(0xFF101010))
    val t = max(h / 9f, 3.5f.dp.toPx())
    var j = 0
    var y = 0f
    while (y < h) {
        var i = 0
        var x = 0f
        while (x < w) {
            val horiz = (i + j) % 2 == 0
            val brush = if (horiz) Brush.verticalGradient(listOf(Color(0xFF353535), Color(0xFF151515)), startY = y, endY = y + t)
            else Brush.horizontalGradient(listOf(Color(0xFF2A2A2A), Color(0xFF0C0C0C)), startX = x, endX = x + t)
            drawRect(brush, Offset(x + 0.4f, y + 0.4f), Size(t - 0.8f, t - 0.8f))
            x += t; i++
        }
        y += t; j++
    }
    // hafif parlama
    drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.08f), Color.Transparent), start = Offset(0f, 0f), end = Offset(w, h)))
}

// ---------------------------------------------------------------- 12 Kiraz çiçeği
private fun DrawScope.drawBlossom(c: Offset, rad: Float, petal: Color, rot: Float) {
    for (i in 0 until 5) {
        withTransform({ rotate(rot + i * 72f, c) }) {
            drawOval(petal, Offset(c.x - rad * 0.36f, c.y - rad), Size(rad * 0.72f, rad * 0.95f))
        }
    }
    drawCircle(Color(0xFFFFD36E), radius = rad * 0.22f, center = c)
}

private fun DrawScope.drawSakura(w: Float, h: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFFFFEEF3), Color(0xFFFFC7DA))))
    val r = PatRng(45)
    // dal
    val branch = Path().apply {
        moveTo(-w * 0.02f, h * 0.15f)
        cubicTo(w * 0.25f, h * 0.05f, w * 0.45f, h * 0.55f, w * 1.02f, h * 0.35f)
    }
    drawPath(branch, Color(0xFF6B3E3E), style = Stroke(width = max(h * 0.06f, 1.5f.dp.toPx()), cap = StrokeCap.Round))
    val n = (w * h / (14.dp.toPx() * 14.dp.toPx())).toInt().coerceIn(4, 60)
    repeat(n) {
        val x = r.next() * w
        val y = r.next() * h
        val rad = max(h * (0.12f + r.next() * 0.1f), 3.5f.dp.toPx())
        val col = if (r.next() > 0.45f) Color(0xFFFF9EBB) else Color.White
        drawBlossom(Offset(x, y), rad, col, r.next() * 72f)
    }
    // düşen yapraklar
    repeat(n) {
        val x = r.next() * w; val y = r.next() * h
        val s = max(h * 0.05f, 1.6f.dp.toPx())
        withTransform({ rotate(r.next() * 180f, Offset(x, y)) }) {
            drawOval(Color(0xFFFF8FB1).copy(alpha = 0.8f), Offset(x - s, y - s * 0.5f), Size(s * 2f, s))
        }
    }
}

// ---------------------------------------------------------------- 13 Galaksi
private fun DrawScope.drawGalaxy(w: Float, h: Float) {
    drawRect(Color(0xFF03020A))
    val blobs = listOf(
        Triple(Offset(w * 0.25f, h * 0.35f), Color(0x996A11CB), 0.9f),
        Triple(Offset(w * 0.6f, h * 0.6f), Color(0x882575FC), 1.0f),
        Triple(Offset(w * 0.82f, h * 0.3f), Color(0x88FF4ECD), 0.8f),
        Triple(Offset(w * 0.45f, h * 0.5f), Color(0x66FF8A5B), 0.6f),
    )
    for ((c, col, k) in blobs) {
        val rad = max(h, w * 0.25f) * k
        drawCircle(Brush.radialGradient(listOf(col, Color.Transparent), center = c, radius = rad), radius = rad, center = c)
    }
    // parlak çekirdek ve kollar
    val core = Offset(w * 0.5f, h * 0.5f)
    withTransform({ rotate(-18f, core) }) {
        drawOval(Brush.radialGradient(listOf(Color(0xCCFFFFFF), Color(0x55C9B6FF), Color.Transparent), center = core, radius = h * 0.9f),
            Offset(core.x - h * 1.4f, core.y - h * 0.25f), Size(h * 2.8f, h * 0.5f))
    }
    val r = PatRng(57)
    val n = (w * h / (5.dp.toPx() * 5.dp.toPx())).toInt().coerceIn(25, 320)
    repeat(n) {
        drawCircle(Color.White.copy(alpha = 0.3f + r.next() * 0.7f), radius = (0.3f + r.next() * 0.8f).dp.toPx(),
            center = Offset(r.next() * w, r.next() * h))
    }
}

// ---------------------------------------------------------------- 14 Holografik
private fun DrawScope.drawHolo(w: Float, h: Float) {
    drawRect(Brush.linearGradient(
        listOf(Color(0xFFA0E9FF), Color(0xFFC2B8FF), Color(0xFFFFD1F7), Color(0xFFFAFFD1), Color(0xFFA1FFCE), Color(0xFFA0E9FF)),
        start = Offset(0f, 0f), end = Offset(w, h),
    ))
    drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), center = Offset(w * 0.3f, h * 0.2f), radius = max(w, h) * 0.45f))
    // çapraz parlama şeritleri
    val gap = max(h * 0.9f, 10f.dp.toPx())
    var x = -h
    var k = 0
    while (x < w + h) {
        val a = if (k % 3 == 0) 0.35f else 0.14f
        drawLine(Color.White.copy(alpha = a), Offset(x, h), Offset(x + h, 0f), strokeWidth = if (k % 3 == 0) gap * 0.18f else gap * 0.06f)
        x += gap; k++
    }
}

// ---------------------------------------------------------------- 15 Leopar
private fun DrawScope.drawLeopard(w: Float, h: Float) {
    drawRect(Brush.linearGradient(listOf(Color(0xFFE3B26B), Color(0xFFC98A3E)), start = Offset.Zero, end = Offset(w, h)))
    val r = PatRng(69)
    val n = (w * h / (11.dp.toPx() * 11.dp.toPx())).toInt().coerceIn(5, 90)
    val dark = Color(0xFF2B1A0E)
    repeat(n) {
        val c = Offset(r.next() * w, r.next() * h)
        val s = max(h * (0.13f + r.next() * 0.09f), 4f.dp.toPx())
        drawOval(Color(0xFFA8662A), Offset(c.x - s * 0.7f, c.y - s * 0.55f), Size(s * 1.4f, s * 1.1f))
        val sw = s * 0.32f
        var a = r.next() * 360f
        repeat(3 + (r.next() * 2f).toInt()) {
            val sweep = 45f + r.next() * 40f
            drawArc(dark, a, sweep, false, Offset(c.x - s, c.y - s * 0.8f), Size(s * 2f, s * 1.6f), style = Stroke(width = sw, cap = StrokeCap.Round))
            a += sweep + 25f + r.next() * 20f
        }
    }
    // küçük benekler
    repeat(n) {
        drawCircle(dark, radius = max(h * 0.03f, 1f.dp.toPx()) * (0.7f + r.next()), center = Offset(r.next() * w, r.next() * h))
    }
}

// ---------------------------------------------------------------- 16 Art deco (altın yelpaze)
private fun DrawScope.drawArtDeco(w: Float, h: Float) {
    drawRect(Color(0xFF0B0B0D))
    val gold = Brush.verticalGradient(listOf(Color(0xFFF7E08A), Color(0xFFB8862B)))
    val s = max(h * 0.32f, 6f.dp.toPx())
    val sw = max(s * 0.07f, 0.6f.dp.toPx())
    var row = 0
    var cy = 0f
    while (cy < h + s * 1.2f) {
        var cx = if (row % 2 == 0) 0f else s
        cx -= s * 2f
        while (cx < w + s * 2f) {
            val tl = Offset(cx - s, cy - s)
            drawArc(Color(0xFF0B0B0D), 180f, 180f, true, tl, Size(s * 2f, s * 2f))
            for (k in 0 until 3) {
                val rr = s * (1f - k * 0.3f) - sw
                drawArc(gold, 180f, 180f, false, Offset(cx - rr, cy - rr), Size(rr * 2f, rr * 2f), style = Stroke(width = sw))
            }
            cx += s * 2f
        }
        cy += s * 0.62f
        row++
    }
}
