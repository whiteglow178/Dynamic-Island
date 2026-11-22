package com.example.dynamicisland

import android.graphics.PathMeasure
import android.graphics.RectF
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.min

/** Ada şekli seçenekleri (Ana sayfa > Ada şekli). 0 = normal hap. */
val ISLAND_SHAPE_NAMES = listOf("Hap", "Kalp", "Bulut", "Kedi", "Elmas")

private const val MORPH_POINTS = 96

/**
 * Boştaki ada için şekil: [morph] 0 = normal hap, 1 = seçilen şekil (kalp / bulut / kedi / elmas).
 * İki şeklin kenarı aynı noktadan (üst orta) saat yönünde eşit aralıklı noktalarla örneklenir ve
 * noktalar arasında geçiş yapılır; böylece ada bir şekilden diğerine akarak dönüşür.
 */
class IslandMorphShape(private val style: Int, private val morph: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val w = size.width; val h = size.height
        val r = min(36f * density.density, h / 2f)
        val m = morph.coerceIn(0f, 1f)
        val target = if (w > 1f && h > 1f) shapePath(style, w, h, r) else null
        if (target == null || m <= 0.001f) return Outline.Rounded(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
        val a = sample(pillPath(w, h, r))
        val b = sample(target)
        val out = Path()
        for (i in 0 until MORPH_POINTS) {
            val x = a[i * 2] + (b[i * 2] - a[i * 2]) * m
            val y = a[i * 2 + 1] + (b[i * 2 + 1] - a[i * 2 + 1]) * m
            if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
        }
        out.close()
        return Outline.Generic(out)
    }

    override fun equals(other: Any?) = other is IslandMorphShape && other.style == style && other.morph == morph
    override fun hashCode() = style * 31 + morph.hashCode()
}

private fun sample(p: android.graphics.Path): FloatArray {
    val pm = PathMeasure(p, true)
    val len = pm.length
    val out = FloatArray(MORPH_POINTS * 2)
    val pos = FloatArray(2)
    for (i in 0 until MORPH_POINTS) {
        pm.getPosTan(len * i / MORPH_POINTS, pos, null)
        out[i * 2] = pos[0]; out[i * 2 + 1] = pos[1]
    }
    return out
}

/** Normal hap; üst ortadan başlar, saat yönünde. */
private fun pillPath(w: Float, h: Float, r: Float) = android.graphics.Path().apply {
    moveTo(w / 2f, 0f)
    lineTo(w - r, 0f)
    arcTo(RectF(w - 2 * r, 0f, w, 2 * r), -90f, 90f, false)
    lineTo(w, h - r)
    arcTo(RectF(w - 2 * r, h - 2 * r, w, h), 0f, 90f, false)
    lineTo(r, h)
    arcTo(RectF(0f, h - 2 * r, 2 * r, h), 90f, 90f, false)
    lineTo(0f, r)
    arcTo(RectF(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
    close()
}

/** Seçilen şekil (hepsi üst ortadan başlar, saat yönünde). null = hap. */
private fun shapePath(style: Int, w: Float, h: Float, r: Float): android.graphics.Path? = when (style) {
    1 -> android.graphics.Path().apply {        // Kalp
        moveTo(w / 2f, h * 0.30f)
        cubicTo(w * 0.56f, -h * 0.06f, w, -h * 0.12f, w, h * 0.38f)
        cubicTo(w, h * 0.70f, w * 0.62f, h * 0.80f, w / 2f, h)
        cubicTo(w * 0.38f, h * 0.80f, 0f, h * 0.70f, 0f, h * 0.38f)
        cubicTo(0f, -h * 0.12f, w * 0.44f, -h * 0.06f, w / 2f, h * 0.30f)
        close()
    }
    2 -> android.graphics.Path().apply {        // Bulut
        moveTo(w * 0.5f, 0f)
        quadTo(w * 0.66f, 0f, w * 0.66f, h * 0.28f)
        quadTo(w * 0.80f, h * 0.05f, w * 0.88f, h * 0.40f)
        quadTo(w, h * 0.45f, w, h * 0.70f)
        quadTo(w, h, w * 0.85f, h)
        lineTo(w * 0.15f, h)
        quadTo(0f, h, 0f, h * 0.70f)
        quadTo(0f, h * 0.45f, w * 0.12f, h * 0.40f)
        quadTo(w * 0.20f, h * 0.05f, w * 0.34f, h * 0.28f)
        quadTo(w * 0.34f, 0f, w * 0.5f, 0f)
        close()
    }
    3 -> {                                      // Kedi (kulaklı hap)
        val yt = h * 0.24f
        val rr = (h - yt) / 2f
        if (w < rr * 4.8f) null else android.graphics.Path().apply {
            moveTo(w / 2f, yt)
            lineTo(w - rr * 2.3f, yt)
            lineTo(w - rr * 1.75f, 0f)
            lineTo(w - rr, yt)
            arcTo(RectF(w - 2 * rr, yt, w, h), -90f, 180f, false)
            lineTo(rr, h)
            arcTo(RectF(0f, yt, 2 * rr, h), 90f, 180f, false)
            lineTo(rr * 1.75f, 0f)
            lineTo(rr * 2.3f, yt)
            close()
        }
    }
    4 -> android.graphics.Path().apply {        // Elmas (mücevher kesimi)
        moveTo(w / 2f, 0f)
        lineTo(w * 0.80f, 0f)
        lineTo(w, h * 0.40f)
        lineTo(w / 2f, h)
        lineTo(0f, h * 0.40f)
        lineTo(w * 0.20f, 0f)
        close()
    }
    else -> null
}
