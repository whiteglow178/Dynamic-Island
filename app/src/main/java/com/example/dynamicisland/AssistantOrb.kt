package com.example.dynamicisland

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Normal adadan videodaki yumuşak köşeli şekle akıcı geçiş.
 * t = 0 -> normal ada, t = 1 -> asistan şekli.
 */
class AssistantShape(private val t: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = islandCornerPx(size.width, size.height, density.density, t)
        return Outline.Rounded(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r)))
    }
}

private const val ASSISTANT_CORNER = 0.42f   // köşe yarıçapı / yükseklik (0.5 = hap şekli, küçüldükçe daha köşeli)

/** "Cam oval" asistan stili açıkken şekil tam yuvarlak köşeli (oval) olur. IslandRoot her kompozisyonda ayarlar. */
@Volatile internal var assistantGlassShape = false

/**
 * Adanın o anki köşe yarıçapı (px). cornerT < 0: normal ada (36dp'lik hap).
 * 0..1: normal adadan asistan şekline geçiş. Şekil, cam / sıvı cam çizimleri ve shader'lar hep bunu kullanır,
 * böylece hepsi aynı kenarı izler.
 */
fun islandCornerPx(w: Float, h: Float, density: Float, cornerT: Float): Float {
    val base = min(36f * density, h / 2f)
    if (cornerT < 0f) return base
    val target = min(h * (if (assistantGlassShape) 0.5f else ASSISTANT_CORNER), w / 2f)
    return base + (target - base) * cornerT.coerceIn(0f, 1f)
}

// Videodaki renk geçişi: mavi -> mor -> pembe/kırmızı -> turuncu -> sarı -> beyaz -> cyan -> mavi (döngüsel)
private var siriCache: Array<Color>? = null
private var siriVer = -1
private val SIRI: Array<Color>
    get() {
        val v = Pal.version                       // palet değişince önbellek yenilenir (Renk düzenleyici)
        val c = siriCache
        if (c != null && siriVer == v) return c
        val n = arrayOf(
            Pal.c("asst_1"), Pal.c("asst_2"), Pal.c("asst_3"), Pal.c("asst_4"),
            Pal.c("asst_5"), Pal.c("asst_6"), Pal.c("asst_7"), Pal.c("asst_8"), Pal.c("asst_1"),
        )
        siriCache = n; siriVer = v
        return n
    }

private fun siriColor(t: Float, alpha: Float = 1f): Color {
    val n = SIRI.size - 1
    val x = (((t % 1f) + 1f) % 1f) * n
    val i = x.toInt().coerceIn(0, n - 1)
    return lerp(SIRI[i], SIRI[i + 1], x - i).copy(alpha = alpha)
}

private const val STEPS = 48
private const val SAMPLES = 9

/**
 * Siri / Gemini benzeri asistan animasyonu. Çizginin KENDİSİ animasyondur: ortada kalın, dalgalı,
 * uçlara doğru sivrilen bir bant; rengi (mavi/mor/kırmızı/turuncu/sarı/beyaz/cyan) boyunca akar ve
 * bant sallanırken eğimi / kalınlığı değişir. Altında gümüş gri ikinci bir dalga ve alt kenarda
 * mat gri bir geçiş var (videodaki cam yansıması). Değerler yalnızca çizim fazında okunur.
 */
@Composable
fun AssistantOrb(fullWidth: Boolean = false) {
    val t = rememberInfiniteTransition(label = "orb")
    // Dalga fazı ve renk fazı: tam döngüde başa sorunsuz döner (zamanla çarpanlar tam sayı)
    val wave = t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = LinearEasing)), label = "wave")
    val hue = t.animateFloat(0f, 1f, infiniteRepeatable(tween(8000, easing = LinearEasing)), label = "hue")
    val breathe = t.animateFloat(
        0.85f, 1.15f,
        infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "br",
    )
    val path = remember { Path() }
    val yc = remember { FloatArray(STEPS + 1) }
    val th = remember { FloatArray(STEPS + 1) }
    val y2 = remember { FloatArray(STEPS + 1) }
    val th2 = remember { FloatArray(STEPS + 1) }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val p = wave.value
        val hp = hue.value
        val b = breathe.value
        val tau = (2 * PI).toFloat()
        val pi = PI.toFloat()

        val cy = h * 0.60f
        // Tam genişlikte animasyon ekranın iki kenarına kadar uzanır; ada kutusuna ait cam efektleri çizilmez
        val x0 = if (fullWidth) 0f else w * 0.03f
        val span = if (fullWidth) w else w * 0.94f

        // 1) Alt kenarda mat gri geçiş (cam yansıması)
        if (!fullWidth) drawRect(
            Brush.verticalGradient(
                colorStops = arrayOf(0.60f to Color.Transparent, 1f to Pal.c("orb_rim").copy(alpha = 0.62f)),
            ),
        )

        // 2) Ana bant: merkez çizgisi ve kalınlığı hesapla
        for (i in 0..STEPS) {
            val u = i / STEPS.toFloat()
            val env = sin(pi * u).coerceAtLeast(0f).pow(0.7f)
            // Hareket zarfı: kenarlara yaklaşırken çok daha hızlı sönümlenir, böylece sol/sağ uçlar ve
            // onlara yakın bölge sabit kalır; dalgalanma ortada yoğunlaşır
            val mv = sin(pi * u).coerceAtLeast(0f).pow(2.5f)
            val tilt = (u - 0.5f) * h * 0.18f * sin(tau * p + 0.4f) * mv
            yc[i] = cy + tilt +
                mv * h * 0.055f * b * sin(tau * (u * 1.25f - p)) +
                mv * h * 0.025f * sin(tau * (u * 2.6f + p * 2f))
            // Kalınlık da kenarlarda oynamasın: dalgalı kısım sadece ortada, uçlarda sabit
            th[i] = h * 0.145f * (1f + (b - 1f) * mv) * (0.65f + 0.4f * sin(tau * (u * 1.7f + p)) * mv) * env
        }
        val cols = List(SAMPLES) { k ->
            val s = k / (SAMPLES - 1).toFloat()
            // Uçlar tamamen kaybolmasın: sabit kenarlar adanın içinde belli olsun
            val a = 0.3f + 0.7f * sin(pi * s).coerceAtLeast(0f).pow(0.5f)
            siriColor(s * 0.85f + hp, a)
        }
        val ribbon = Brush.horizontalGradient(cols, startX = x0, endX = x0 + span)
        val scales = floatArrayOf(1.9f, 1.4f, 1.0f, 0.6f)
        val alphas = floatArrayOf(0.16f, 0.26f, 0.46f, 0.95f)
        scales.forEachIndexed { li, sc ->
            path.reset()
            for (i in 0..STEPS) {
                val x = x0 + span * i / STEPS
                val y = yc[i] - th[i] * sc / 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in STEPS downTo 0) path.lineTo(x0 + span * i / STEPS, yc[i] + th[i] * sc / 2f)
            path.close()
            drawPath(path, ribbon, alpha = alphas[li], blendMode = BlendMode.Plus)
        }

        // 3) Bandın üst kenarında parlak beyaz çizgi (tepe ışığı)
        path.reset()
        for (i in 0..STEPS) {
            val x = x0 + span * i / STEPS
            val y = yc[i] - th[i] * 0.32f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(
            path,
            Brush.horizontalGradient(
                0f to Pal.c("orb_sweep").copy(alpha = 0f), 0.30f to Pal.c("orb_sweep").copy(alpha = 0.667f), 0.5f to Pal.c("orb_sweep"),
                0.70f to Pal.c("orb_sweep").copy(alpha = 0.667f), 1f to Pal.c("orb_sweep").copy(alpha = 0f), startX = x0, endX = x0 + span,
            ),
            alpha = 0.85f,
            style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            blendMode = BlendMode.Plus,
        )

        // 4) Ana bandın yansıması: aynı dalga ve kalınlıktan türetilir, bu yüzden birebir senkron hareket eder
        for (i in 0..STEPS) {
            y2[i] = yc[i] + th[i] * 0.5f + h * 0.03f
            th2[i] = th[i] * 0.22f
        }
        val silver = Brush.horizontalGradient(
            0f to Pal.c("orb_sweep2_edge").copy(alpha = 0f), 0.25f to Pal.c("orb_sweep2_mid"), 0.5f to Pal.c("orb_sweep"),
            0.75f to Pal.c("orb_sweep2_mid"), 1f to Pal.c("orb_sweep2_edge").copy(alpha = 0f), startX = x0, endX = x0 + span,
        )
        floatArrayOf(2.2f, 1.0f, 0.4f).forEachIndexed { li, sc ->
            path.reset()
            for (i in 0..STEPS) {
                val x = x0 + span * i / STEPS
                val y = y2[i] - th2[i] * sc / 2f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            for (i in STEPS downTo 0) path.lineTo(x0 + span * i / STEPS, y2[i] + th2[i] * sc / 2f)
            path.close()
            drawPath(path, silver, alpha = floatArrayOf(0.10f, 0.22f, 0.45f)[li], blendMode = BlendMode.Plus)
        }

        // 5) Cam hissi: sol üstte hafif parlama
        if (!fullWidth) drawRect(
            Brush.radialGradient(
                0f to Pal.c("orb_sweep").copy(alpha = 0.12f), 1f to Color.Transparent,
                center = Offset(w * 0.30f, h * 0.08f), radius = w * 0.30f,
            ),
        )
    }
}


/**
 * "Cam oval" asistan animasyonu (ekran kaydındaki tasarım): siyah camsı oval, ince beyaz çerçeve (kenarlarda hafif renk kayması),
 * ortada akan renkli Siri bandı. Bandın üst yarısı ve alt yarısı farklı renk fazında akar; merkez çizgisi beyaz-sıcak parlar.
 */
@Composable
fun AssistantGlassOrb() {
    val t = rememberInfiniteTransition(label = "glassOrb")
    val wave = t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "gWave")
    val hue = t.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "gHue")
    val pulse = t.animateFloat(
        0.9f, 1.1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "gPulse",
    )
    val path = remember { Path() }
    val clip = remember { Path() }
    val yc = remember { FloatArray(STEPS + 1) }
    val up = remember { FloatArray(STEPS + 1) }
    val lo = remember { FloatArray(STEPS + 1) }

    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val p = wave.value; val hp = hue.value; val b = pulse.value
        val tau = (2 * PI).toFloat(); val pi = PI.toFloat()
        val r = min(h, w) / 2f
        val dp = density

        clip.reset()
        clip.addRoundRect(RoundRect(0f, 0f, w, h, CornerRadius(r, r)))
        clipPath(clip) {
            // 1) Zemin: üstte siyah, altta hafif gri cam yansıması
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Black, 0.55f to Color.Black, 1f to Color(0xFF30333C),
                ),
            )
            drawRect(
                Brush.radialGradient(
                    0f to Color.White.copy(alpha = 0.16f), 1f to Color.Transparent,
                    center = Offset(w * 0.5f, h * 1.08f), radius = w * 0.55f,
                ),
            )

            // 2) Bant: dalgalı merkez çizgisi; üstte ve altta ayrı kalınlık
            val cy = h * 0.48f
            val x0 = w * 0.04f
            val span = w * 0.92f
            for (i in 0..STEPS) {
                val u = i / STEPS.toFloat()
                val env = sin(pi * u).coerceAtLeast(0f)
                val mv = env.pow(1.4f)
                yc[i] = cy + mv * h * 0.06f * b * sin(tau * (u * 1.1f - p)) +
                    mv * h * 0.03f * sin(tau * (u * 2.3f + p * 2f))
                up[i] = h * 0.20f * (0.70f + 0.30f * sin(tau * (u * 1.3f + p))) * env.pow(0.8f) * b
                lo[i] = h * 0.14f * (0.70f + 0.30f * sin(tau * (u * 1.7f - p + 0.3f))) * env.pow(0.8f)
            }
            fun ribbonBrush(shift: Float): Brush {
                val cols = List(SAMPLES) { k ->
                    val s = k / (SAMPLES - 1).toFloat()
                    val a = 0.25f + 0.75f * sin(pi * s).coerceAtLeast(0f).pow(0.5f)
                    siriColor(s * 0.8f + hp + shift, a)
                }
                return Brush.horizontalGradient(cols, startX = x0, endX = x0 + span)
            }
            val upBrush = ribbonBrush(0f)
            val loBrush = ribbonBrush(0.45f)
            val scales = floatArrayOf(1.0f, 0.72f, 0.46f, 0.24f)
            val alphas = floatArrayOf(0.22f, 0.34f, 0.55f, 0.90f)
            scales.forEachIndexed { li, sc ->
                // üst yarı
                path.reset()
                for (i in 0..STEPS) {
                    val x = x0 + span * i / STEPS
                    val y = yc[i] - up[i] * sc
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                for (i in STEPS downTo 0) path.lineTo(x0 + span * i / STEPS, yc[i])
                path.close()
                drawPath(path, upBrush, alpha = alphas[li], blendMode = BlendMode.Plus)
                // alt yarı
                path.reset()
                for (i in 0..STEPS) {
                    val x = x0 + span * i / STEPS
                    val y = yc[i] + lo[i] * sc
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                for (i in STEPS downTo 0) path.lineTo(x0 + span * i / STEPS, yc[i])
                path.close()
                drawPath(path, loBrush, alpha = alphas[li], blendMode = BlendMode.Plus)
            }

            // 3) Merkez çizgisi: beyaz-sıcak parlama
            path.reset()
            for (i in 0..STEPS) {
                val x = x0 + span * i / STEPS
                if (i == 0) path.moveTo(x, yc[i]) else path.lineTo(x, yc[i])
            }
            val core = Brush.horizontalGradient(
                0f to Color.White.copy(alpha = 0f), 0.25f to Color.White.copy(alpha = 0.75f), 0.5f to Color.White,
                0.75f to Color.White.copy(alpha = 0.75f), 1f to Color.White.copy(alpha = 0f), startX = x0, endX = x0 + span,
            )
            drawPath(path, core, alpha = 0.30f, style = Stroke(width = 9f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
            drawPath(path, core, alpha = 0.95f, style = Stroke(width = 2.4f * dp, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        }

        // 4) Cam çerçeve: kırmızı / mavi kayma + ince beyaz çizgi (altta ve yanlarda daha parlak)
        val inset = 0.8f * dp
        val rs = Size(w - 2 * inset, h - 2 * inset)
        val rc = CornerRadius((r - inset).coerceAtLeast(1f))
        val fringe = 0.9f * dp
        drawRoundRect(
            Color(0xFFFF4060).copy(alpha = 0.30f), Offset(inset + fringe, inset), rs, rc,
            style = Stroke(1.2f * dp), blendMode = BlendMode.Plus,
        )
        drawRoundRect(
            Color(0xFF4080FF).copy(alpha = 0.30f), Offset(inset - fringe, inset), rs, rc,
            style = Stroke(1.2f * dp), blendMode = BlendMode.Plus,
        )
        drawRoundRect(
            Color.White.copy(alpha = 0.14f), Offset(inset, inset), rs, rc, style = Stroke(4f * dp),
        )
        drawRoundRect(
            Brush.sweepGradient(
                0f to Color.White.copy(alpha = 0.80f), 0.25f to Color.White.copy(alpha = 0.95f),
                0.5f to Color.White.copy(alpha = 0.80f), 0.75f to Color.White.copy(alpha = 0.50f),
                1f to Color.White.copy(alpha = 0.80f), center = Offset(w / 2f, h / 2f),
            ),
            Offset(inset, inset), rs, rc, style = Stroke(1.2f * dp),
        )
    }
}
