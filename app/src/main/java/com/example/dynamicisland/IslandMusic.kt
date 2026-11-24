package com.example.dynamicisland

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.composed
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

// ---------------------------------------------------------------- Kayan başlık
/** Sığmayan şarkı adı: bir süre bekler, sonuna kadar yana kayar, geri döner ve tekrarlar. Sığıyorsa sabit durur. */
@Composable
private fun MarqueeText(text: String, modifier: Modifier, color: Color, fontSize: TextUnit, fontWeight: FontWeight) {
    val scroll = rememberScrollState()
    val max = scroll.maxValue
    LaunchedEffect(text, max) {
        scroll.scrollTo(0)
        if (max <= 0) return@LaunchedEffect
        while (true) {
            delay(1500)
            scroll.animateScrollTo(max, tween((max * 22).coerceIn(800, 12000), easing = LinearEasing))
            delay(1200)
            scroll.animateScrollTo(0, tween(600, easing = FastOutSlowInEasing))
        }
    }
    Text(
        text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false,
        modifier = modifier.horizontalScroll(scroll, enabled = false),
    )
}

// ---------------------------------------------------------------- Equalizer (sağ kenar)
@Composable
fun Equalizer(color: Color, height: Dp = 14.dp, barWidth: Dp = 3.dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eq")
    val durations = intArrayOf(380, 520, 300, 460, 410)
    val bars = durations.map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$d")
    }
    Row(Modifier.height(height).rainbowFill(rainbow, 0.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { s ->
            Box(
                Modifier.width(barWidth).fillMaxHeight()
                    .graphicsLayer { scaleY = if (animate) s.value else 0.45f }   // draw fazı: recomposition yok, GPU
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ---------------------------------------------------------------- Halka görünümü (ekolayzırın yerine)
/**
 * Adadaki ses göstergesi: [style] 0 çubuklu ekolayzır, 1 halka ([EqRing]), 2 yörünge ([EqOrbit]),
 * 3 dalga ([EqWave]), 4 piksel ([EqDots]), 5 nabız ([EqPulse]), 6 güneş ([EqSun]), 7 kabarcık ([EqBubbles]),
 * 8 ayna ([EqMirror]), 9 yankı ([EqRipple]).
 * Ayarlar > Ekolayzır > "Görünüm". Halka ve yörünge, çubuklarla aynı yükseklik + 6dp'lik kare bir alana sığar.
 */
@Composable
fun MusicMeter(
    color: Color, style: Int, height: Dp = 14.dp, barWidth: Dp = 3.dp,
    animate: Boolean = true, rainbow: RainbowStyle? = null,
) {
    when (style) {
        1 -> EqRing(color, height + 6.dp, animate, rainbow)
        2 -> EqOrbit(color, height + 6.dp, animate, rainbow)
        3 -> EqWave(color, height, animate, rainbow)
        4 -> EqDots(color, height, animate, rainbow)
        5 -> EqPulse(color, height, animate, rainbow)
        6 -> EqSun(color, height + 6.dp, animate, rainbow)
        7 -> EqBubbles(color, height, animate, rainbow)
        8 -> EqMirror(color, height, animate, rainbow)
        9 -> EqRipple(color, height + 6.dp, animate, rainbow)
        else -> Equalizer(color, height, barWidth, animate, rainbow)
    }
}

/** Rengin tonunu [deg] derece kaydırır (halkanın ikinci rengi için). */
private fun shiftHue(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + deg + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv)).copy(alpha = c.alpha)
}

/**
 * Renkli, hareketli halka: üç dalganın toplamıyla yumuşakça şekil değiştiren, renk geçişli bir çember.
 * Arkasında hafif bir parıltı ve ters yönde dönen ince ikinci bir iz var. Müzik durunca dalgalar söner,
 * düz bir çembere döner. Gerçek sesi dinlemez; çubuklu ekolayzır gibi ritmik bir animasyondur.
 * Animasyon değerleri yalnızca çizim sırasında okunur: her karede yeniden oluşturma (recomposition) yok.
 */
@Composable
fun EqRing(color: Color, size: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqRing")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(4800, easing = LinearEasing)), label = "ringPhase")
    val beatA = t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ringBeatA")
    val beatB = t.animateFloat(0.2f, 1f, infiniteRepeatable(tween(610, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "ringBeatB")
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(450), label = "ringAmp")
    val second = remember(color) { shiftHue(color, 55f) }
    val third = remember(color) { shiftHue(color, -35f) }
    val main = remember { Path() }
    val trace = remember { Path() }
    Canvas(Modifier.size(size).rainbowFill(rainbow, 0.dp)) {
        val tau = (2 * Math.PI).toFloat()
        val p = phase.value
        val a = amp.value
        val bA = beatA.value
        val bB = beatB.value
        val d = this.size.minDimension
        val base = d * 0.33f
        val wob = d * 0.075f * a
        val stroke = d * 0.085f
        fun build(path: Path, off: Float, k: Float) {
            path.reset()
            val n = 64
            for (i in 0..n) {
                val ang = tau * i / n
                val r = base + wob * k * (
                    0.55f * bA * kotlin.math.sin(3 * ang + tau * p + off) +
                    0.35f * bB * kotlin.math.sin(5 * ang - 2 * tau * p + off * 1.7f) +
                    0.20f * kotlin.math.sin(2 * ang + 3 * tau * p)
                )
                val x = center.x + r * kotlin.math.cos(ang)
                val y = center.y + r * kotlin.math.sin(ang)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        build(main, 0f, 1f)
        build(trace, tau / 4f, 1.35f)
        val sweep = Brush.sweepGradient(listOf(color, second, third, color), center)
        withTransform({ rotate(p * 360f, center) }) {
            // yumuşak parıltı
            drawPath(main, color.copy(alpha = 0.22f * (0.6f + 0.4f * a)), style = Stroke(stroke * 2.8f, join = StrokeJoin.Round))
            // ters yönde dönen ince iz
            withTransform({ rotate(-p * 720f, center) }) {
                drawPath(trace, sweep, alpha = 0.5f * a, style = Stroke(stroke * 0.45f, join = StrokeJoin.Round))
            }
            // ana halka
            drawPath(main, sweep, style = Stroke(stroke, join = StrokeJoin.Round))
        }
    }
}

/** Yörünge görünümündeki bir uydu: açı dizisindeki sırası, eğim (radyan), renk, boyut nabzı. */
private class OrbitMoon(val idx: Int, val tilt: Float, val col: Color, val pulse: Float)

/**
 * "Yörünge": ritimle nabız gibi atan parlak bir çekirdek ve çevresinde eğik yörüngelerde dönen iki küçük uydu.
 * Uydular arkalarında kuyruklu yıldız gibi sönen bir iz bırakır; çekirdeğin arkasından geçerken solar.
 * Renkler kapaktan (ya da seçilen renkten) türetilir. Müzik durunca uydular yavaşça durur, çekirdek sakinleşir.
 * Gerçek sesi dinlemez; animasyon değerleri yalnızca çizim sırasında okunur.
 */
@Composable
fun EqOrbit(color: Color, size: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqOrbit")
    val beat = t.animateFloat(0f, 1f, infiniteRepeatable(tween(460, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbitBeat")
    val beat2 = t.animateFloat(0f, 1f, infiniteRepeatable(tween(690, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "orbitBeat2")
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(600), label = "orbitAmp")
    // Müzik durunca uydular olduğu yerde durur: açı, oynarken biriken zamandan hesaplanır
    val angle = remember { floatArrayOf(0f, 0.5f) }   // her uydunun açısı (tur, 0..1)
    val last = remember { longArrayOf(0L) }          // son karenin zamanı (ms)
    val tick = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "orbitTick")
    val moonA = remember(color) { shiftHue(color, 55f) }
    val moonB = remember(color) { shiftHue(color, -40f) }
    Canvas(Modifier.size(size).rainbowFill(rainbow, 0.dp)) {
        tick.value     // her karede yeniden çiz
        val now = SystemClock.uptimeMillis()
        val dt = if (last[0] == 0L) 0f else ((now - last[0]) / 1000f).coerceIn(0f, 0.1f)
        last[0] = now
        val a = amp.value
        // Her uydu kendi hızıyla ilerler (tam hızda ilki ~1,8 sn'de bir tur); müzik durunca yavaşlayıp durur
        angle[0] = (angle[0] + dt * 0.55f * a) % 1f
        angle[1] = (angle[1] + dt * 0.55f * 1.35f * a) % 1f
        val tau = (2 * Math.PI).toFloat()
        val d = this.size.minDimension
        val c = center
        val b = beat.value * a
        val b2 = beat2.value * a
        val coreR = d * (0.14f + 0.05f * b)
        val rx = d * 0.40f
        val ry = d * 0.15f

        fun pos(theta: Float, tilt: Float): Pair<Offset, Float> {
            val lx = rx * kotlin.math.cos(theta)
            val ly = ry * kotlin.math.sin(theta)
            val ct = kotlin.math.cos(tilt); val st = kotlin.math.sin(tilt)
            return Offset(c.x + lx * ct - ly * st, c.y + lx * st + ly * ct) to kotlin.math.sin(theta)  // ikinci değer: >0 önde
        }
        val moons = listOf(
            OrbitMoon(0, 0.52f, moonA, b),
            OrbitMoon(1, -0.52f, moonB, b2),
        )
        fun drawMoon(m: OrbitMoon, front: Boolean) {
            val theta = tau * angle[m.idx]
            val r = d * (0.065f + 0.02f * m.pulse)
            // kuyruk: geride kalan noktalar küçülerek ve solarak
            val steps = 9
            for (k in steps downTo 0) {
                val th = theta - k * 0.16f * (0.35f + 0.65f * a)
                val (p, depth) = pos(th, m.tilt)
                if ((depth >= 0f) != front) continue
                val fade = 1f - k / (steps + 1f)
                val back = if (depth < 0f) 0.45f else 1f
                val alpha = (if (k == 0) 1f else 0.55f * fade * fade * a) * back
                drawCircle(m.col.copy(alpha = alpha.coerceIn(0f, 1f)), r * (0.45f + 0.55f * fade), p)
            }
        }
        // arkadaki yollar -> çekirdek -> öndeki yollar
        moons.forEach { drawMoon(it, front = false) }
        drawCircle(color.copy(alpha = 0.16f + 0.14f * b), coreR * 2.3f, c)          // geniş parıltı
        drawCircle(color.copy(alpha = 0.35f), coreR * 1.45f, c)
        drawCircle(Brush.radialGradient(listOf(Color.White, color), c, coreR), coreR, c)
        moons.forEach { drawMoon(it, front = true) }
    }
}

/** Dalga görünümündeki bir katman: renk, saydamlık, dalga sayısı, akış yönü / hızı (tam sayı: döngü dikişsiz), ritim, faz. */
private class WaveLayer(val col: Color, val alpha: Float, val waves: Float, val speed: Float, val beat: Float, val off: Float)

/**
 * "Dalga": birbirinin içinden akan üç ince ses dalgası (ana renk + iki komşu ton). Dalgalar uçlarda incelip
 * kaybolur, ortada ritimle kabarır; her biri farklı hızda ve yönde akar. Müzik durunca düz bir çizgiye iner.
 * Çubuklu ekolayzırla aynı yükseklikte, biraz daha geniş bir alana çizilir.
 */
@Composable
fun EqWave(color: Color, height: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqWave")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1700, easing = LinearEasing)), label = "wavePhase")
    val beatA = t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(430, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "waveBeatA")
    val beatB = t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "waveBeatB")
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(450), label = "waveAmp")
    val c2 = remember(color) { shiftHue(color, 50f) }
    val c3 = remember(color) { shiftHue(color, -45f) }
    val path = remember { Path() }
    Canvas(Modifier.size(height * 1.75f, height).rainbowFill(rainbow, 0.dp)) {
        val tau = (2 * Math.PI).toFloat()
        val w = this.size.width
        val h = this.size.height
        val cy = h / 2f
        val a = amp.value
        val p = phase.value
        val stroke = (h * 0.11f).coerceAtLeast(1.2.dp.toPx())
        val maxA = h / 2f - stroke
        val layers = listOf(
            WaveLayer(c3, 0.55f, 1.3f, -1f, beatB.value, 1.1f),
            WaveLayer(c2, 0.7f, 2.1f, 2f, beatB.value, 2.3f),
            WaveLayer(color, 1f, 1.6f, 1f, beatA.value, 0f),
        )
        for (l in layers) {
            val col = l.col; val al = l.alpha; val k = l.waves
            val sp = l.speed; val bt = l.beat; val off = l.off
            path.reset()
            val n = 40
            for (i in 0..n) {
                val u = i / n.toFloat()
                val env = kotlin.math.sin(Math.PI.toFloat() * u).let { it * it }      // uçlarda sıfır
                val y = cy + maxA * a * bt * env * kotlin.math.sin(tau * (k * u + sp * p) + off)
                val x = u * w
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, col.copy(alpha = al * (0.45f + 0.55f * a)), style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/**
 * "Piksel": eski müzik setlerindeki gibi beş sütunluk nokta göstergesi. Her sütunda seviyeye kadar noktalar yanar,
 * en üstteki yanan nokta beyaza yakın parlar; sönük noktalar soluk kalır. Müzik durunca her sütunda bir nokta kalır.
 */
@Composable
fun EqDots(color: Color, height: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqDots")
    val durations = intArrayOf(360, 520, 290, 450, 400)
    val levels = durations.map { d ->
        t.animateFloat(0.15f, 1f, infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "dot$d")
    }
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(350), label = "dotsAmp")
    val hot = remember(color) { lerpColor(color, Color.White, 0.55f) }
    val rows = 4
    val cols = 5
    val gapK = 0.45f
    val dot = height / (rows + (rows - 1) * gapK)
    val width = dot * (cols + (cols - 1) * gapK)
    Canvas(Modifier.size(width, height).rainbowFill(rainbow, 0.dp)) {
        val d = this.size.height / (rows + (rows - 1) * gapK)
        val g = d * gapK
        val r = d / 2f
        val a = amp.value
        for (c in 0 until cols) {
            val lv = 0.25f + (levels[c].value - 0.25f) * a                  // durunca tek nokta
            val lit = kotlin.math.ceil(lv * rows).toInt().coerceIn(1, rows)
            val cx = c * (d + g) + r
            for (row in 0 until rows) {
                val cy = this.size.height - (row * (d + g) + r)             // alttan yukarı
                val on = row < lit
                val col = when {
                    !on -> color.copy(alpha = 0.18f)
                    row == lit - 1 -> hot
                    else -> color
                }
                drawCircle(col, r * (if (on) 1f else 0.82f), Offset(cx, cy))
            }
        }
    }
}

/**
 * "Nabız": kalp atışı çizgisi. Düz çizgi üzerinde ritimle bir atış (küçük çukur, yüksek tepe, derin çukur) sağdan
 * sola akar; çizgi sağa doğru parlaklaşır, ucunda parlak bir nokta yanar. Müzik durunca çizgi düzleşir.
 */
@Composable
fun EqPulse(color: Color, height: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqPulse")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "pulsePhase")
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(450), label = "pulseAmp")
    val hot = remember(color) { lerpColor(color, Color.White, 0.5f) }
    Canvas(Modifier.size(height * 1.8f, height).rainbowFill(rainbow, 0.dp)) {
        val w = this.size.width; val h = this.size.height
        val cy = h / 2f
        val a = amp.value
        val p = phase.value
        val stroke = (h * 0.11f).coerceAtLeast(1.2.dp.toPx())
        val maxA = h / 2f - stroke
        // Atış şekli: u (0..1) bir tur; yalnızca 0,45..0,70 arasında hareket var
        fun beat(u: Float): Float = when {
            u < 0.45f -> 0f
            u < 0.50f -> -0.25f * kotlin.math.sin((u - 0.45f) / 0.05f * Math.PI.toFloat())
            u < 0.58f -> kotlin.math.sin((u - 0.50f) / 0.08f * Math.PI.toFloat())
            u < 0.65f -> -0.55f * kotlin.math.sin((u - 0.58f) / 0.07f * Math.PI.toFloat())
            u < 0.70f -> 0.15f * kotlin.math.sin((u - 0.65f) / 0.05f * Math.PI.toFloat())
            else -> 0f
        }
        val n = 48
        var px = 0f; var py = cy
        for (i in 0..n) {
            val f = i / n.toFloat()
            val u = ((f + p) % 1f)
            val x = f * w
            val y = cy - maxA * a * beat(u)
            if (i > 0) drawLine(color.copy(alpha = (0.15f + 0.85f * f) * (0.5f + 0.5f * a)), Offset(px, py), Offset(x, y), stroke, StrokeCap.Round)
            px = x; py = y
        }
        drawCircle(hot.copy(alpha = 0.35f * a), stroke * 2.4f, Offset(px, py))
        drawCircle(hot, stroke * 1.1f, Offset(px, py))
    }
}

/**
 * "Güneş": merkezin çevresinde yavaşça dönen on iki ışın. Her ışın ritimle ayrı ayrı uzayıp kısalır, renkler ışın
 * ışın kayar; ortada küçük parlak bir çekirdek. Müzik durunca ışınlar kısa ve eşit kalır.
 */
@Composable
fun EqSun(color: Color, size: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqSun")
    val spin = t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "sunSpin")
    val flow = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "sunFlow")
    val beat = t.animateFloat(0.4f, 1f, infiniteRepeatable(tween(440, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sunBeat")
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(450), label = "sunAmp")
    val c2 = remember(color) { shiftHue(color, 60f) }
    Canvas(Modifier.size(size).rainbowFill(rainbow, 0.dp)) {
        val tau = (2 * Math.PI).toFloat()
        val d = this.size.minDimension
        val c = center
        val a = amp.value
        val rays = 12
        val r0 = d * 0.17f
        val sw = d * 0.075f
        val rot = spin.value * tau
        for (i in 0 until rays) {
            val ang = rot + tau * i / rays
            val lv = kotlin.math.abs(kotlin.math.sin(i * 1.7f + tau * flow.value)) * beat.value
            val len = d * (0.07f + 0.22f * lv * a)
            val ca = kotlin.math.cos(ang); val sa = kotlin.math.sin(ang)
            val col = lerpColor(color, c2, i / (rays - 1f))
            drawLine(col, Offset(c.x + r0 * ca, c.y + r0 * sa), Offset(c.x + (r0 + len) * ca, c.y + (r0 + len) * sa), sw, StrokeCap.Round)
        }
        drawCircle(color.copy(alpha = 0.3f), r0 * 0.95f, c)
        drawCircle(lerpColor(color, Color.White, 0.5f), r0 * 0.55f, c)
    }
}

/**
 * "Kabarcık": altta doğan, yalpalayarak yükselen ve tepede sönen yedi renkli kabarcık. Yükselirken biraz büyürler;
 * müzik durunca yavaşlayıp solarlar.
 */
@Composable
fun EqBubbles(color: Color, height: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqBubbles")
    val tick = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "bubbleTick")
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(600), label = "bubbleAmp")
    val clock = remember { floatArrayOf(0f) }
    val last = remember { longArrayOf(0L) }
    val c2 = remember(color) { shiftHue(color, 55f) }
    Canvas(Modifier.size(height * 1.5f, height).rainbowFill(rainbow, 0.dp)) {
        tick.value
        val now = SystemClock.uptimeMillis()
        val dt = if (last[0] == 0L) 0f else ((now - last[0]) / 1000f).coerceIn(0f, 0.1f)
        last[0] = now
        val a = amp.value
        clock[0] = (clock[0] + dt * (0.25f + 0.75f * a)) % 1000f
        val w = this.size.width; val h = this.size.height
        val n = 7
        for (i in 0 until n) {
            val speed = 0.55f + 0.12f * (i % 3)
            val f = ((clock[0] * speed + i / n.toFloat() * 1.37f) % 1f)          // 0 altta, 1 tepede
            val lane = (i + 0.5f) / n
            val x = w * lane + kotlin.math.sin(f * 9f + i) * w * 0.05f
            val r = h * (0.07f + 0.07f * f)
            val y = h - r - (h - 2 * r) * f
            val fade = (if (f < 0.15f) f / 0.15f else 1f) * (1f - f * f)
            val col = lerpColor(color, c2, (i % 4) / 3f)
            drawCircle(col.copy(alpha = (fade * (0.35f + 0.65f * a)).coerceIn(0f, 1f)), r, Offset(x, y))
            drawCircle(Color.White.copy(alpha = 0.5f * fade * a), r * 0.32f, Offset(x - r * 0.3f, y - r * 0.3f))   // parıltı
        }
    }
}

/**
 * "Ayna": ortadaki ince çizgiden yukarı ve aşağı simetrik açılan yedi ince çubuk (ses kaydı dalgası gibi). Çubuklar
 * uçlarına doğru ikinci renge geçer; ortadakiler daha yükseğe çıkar. Müzik durunca kısa çizgiler kalır.
 */
@Composable
fun EqMirror(color: Color, height: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqMirror")
    val durations = intArrayOf(470, 330, 560, 290, 510, 380, 440)
    val levels = durations.map { d ->
        t.animateFloat(0.12f, 1f, infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "mir$d")
    }
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(400), label = "mirAmp")
    val c2 = remember(color) { shiftHue(color, 50f) }
    val bars = 7
    val bw = height * 0.16f
    val gap = height * 0.11f
    Canvas(Modifier.size(bw * bars + gap * (bars - 1), height).rainbowFill(rainbow, 0.dp)) {
        val h = this.size.height
        val cy = h / 2f
        val b = bw.toPx(); val g = gap.toPx()
        val a = amp.value
        drawLine(color.copy(alpha = 0.25f), Offset(0f, cy), Offset(this.size.width, cy), (h * 0.04f).coerceAtLeast(1f))
        for (i in 0 until bars) {
            val centerBoost = 1f - kotlin.math.abs(i - (bars - 1) / 2f) / bars        // ortadakiler daha yüksek
            val lv = 0.12f + (levels[i].value * centerBoost - 0.12f).coerceAtLeast(0f) * a
            val half = (h / 2f) * lv.coerceIn(0.12f, 1f)
            val x = i * (b + g)
            drawRoundRect(
                Brush.verticalGradient(listOf(c2, color, c2), cy - half, cy + half),
                Offset(x, cy - half), Size(b, half * 2f), CornerRadius(b / 2f, b / 2f),
            )
        }
    }
}

/**
 * "Yankı": merkezden dışarı yayılıp sönen üç halka (suya düşen damla gibi) ve ritimle atan bir çekirdek.
 * Müzik durunca halkalar yavaşça söner, çekirdek kalır.
 */
@Composable
fun EqRipple(color: Color, size: Dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eqRipple")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "ripplePhase")
    val beat = t.animateFloat(0.6f, 1f, infiniteRepeatable(tween(430, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "rippleBeat")
    val amp = animateFloatAsState(if (animate) 1f else 0f, tween(600), label = "rippleAmp")
    val c2 = remember(color) { shiftHue(color, -45f) }
    Canvas(Modifier.size(size).rainbowFill(rainbow, 0.dp)) {
        val d = this.size.minDimension
        val c = center
        val a = amp.value
        val maxR = d / 2f - d * 0.04f
        val core = d * 0.12f
        for (i in 0 until 3) {
            val f = (phase.value + i / 3f) % 1f
            val r = core + (maxR - core) * f
            val fade = (1f - f).let { it * kotlin.math.sqrt(it) }
            drawCircle(lerpColor(color, c2, f).copy(alpha = (fade * a).coerceIn(0f, 1f)), r, c, style = Stroke(d * (0.07f - 0.04f * f)))
        }
        drawCircle(color.copy(alpha = 0.3f), core * 1.6f * (0.8f + 0.2f * beat.value * a), c)
        drawCircle(lerpColor(color, Color.White, 0.45f), core * (0.85f + 0.25f * beat.value * a), c)
    }
}

/** İki renk arasında doğrusal geçiş. */
private fun lerpColor(a: Color, b: Color, f: Float): Color = Color(
    red = a.red + (b.red - a.red) * f, green = a.green + (b.green - a.green) * f,
    blue = a.blue + (b.blue - a.blue) * f, alpha = a.alpha + (b.alpha - a.alpha) * f,
)

// ---------------------------------------------------------------- Albüm kapağına dokunma alanı
/**
 * Kapağa dokununca müzik uygulamasını açar. [circle] true ise (daire albüm kapağı) yalnızca dairenin içine
 * yapılan dokunuşlar sayılır: köşelerdeki boşluğa dokunmak kapağı açmaz, dokunuş adaya geçer.
 * [enabled] false ise hiçbir şey yapmaz (Ayarlar > kapağa dokununca aç kapalı).
 */
fun Modifier.artTap(enabled: Boolean, circle: Boolean, onClick: () -> Unit): Modifier = if (!enabled) this else composed {
    val cb by rememberUpdatedState(onClick)
    pointerInput(circle) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (circle) {
                val r = kotlin.math.min(size.width, size.height) / 2f
                val dx = down.position.x - size.width / 2f
                val dy = down.position.y - size.height / 2f
                if (dx * dx + dy * dy > r * r) return@awaitEachGesture
            }
            val up = waitForUpOrCancellation()
            if (up != null) { up.consume(); cb() }
        }
    }
}

// ---------------------------------------------------------------- Beğeni (yalnızca bu uygulamada saklanır)
object FavoriteStore {
    private const val FILE = "island_favs"
    private const val KEY = "s"
    fun isFav(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.contains(key) == true

    fun set(ctx: Context, key: String, on: Boolean) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY, emptySet()) ?: emptySet())
        if (on) cur.add(key) else cur.remove(key)
        sp.edit().putStringSet(KEY, cur).apply()
    }
}

// ---------------------------------------------------------------- Beğeni düğmesi (kalp ya da yıldız)
/**
 * Beğeni düğmesi: uygulamada saklanır ve mümkünse çalan oynatıcıda da beğenilir. Ayara göre kalp ya da yıldız çizilir
 * (Ayarlar > Beğeni düğmesi > Yıldız simgesi).
 */
@Composable
private fun FavButton(
    m: IslandContent.Media, cfg: IslandSettings, size: Dp, modifier: Modifier = Modifier,
    rb: RainbowStyle? = null, hit: Dp = 48.dp, fxId: String? = "heart",
) {
    val ctx = LocalContext.current
    val favKey = remember(m.title, m.artist) { "${m.title}|${m.artist}" }
    var fav by remember(favKey) { mutableStateOf(FavoriteStore.isFav(ctx, favKey)) }
    var lastTap by remember { mutableLongStateOf(0L) }
    // Uygulamanın gerçek beğeni durumu biliniyorsa simgeyi ona eşitle (dokunuştan hemen sonra karışma)
    LaunchedEffect(favKey) {
        while (true) {
            if (SystemClock.elapsedRealtime() - lastTap > 2500) {
                PlayerLike.likedState(ctx, m.title)?.let { real ->
                    if (real != fav) { fav = real; FavoriteStore.set(ctx, favKey, real) }
                }
            }
            delay(2000)
        }
    }
    // Beğenilmiş simgenin rengi: kapaktan (açıksa ve kapak varsa), yoksa ayarlardaki renk
    val baseHeart = if (cfg.likeArtColor && m.artColor != 0) glowTone(Color(m.artColor)) else Color(cfg.likeColorArgb)
    val heartColor by animateColorAsState(baseHeart, tween(450), label = "heartColor")
    val star = cfg.more.favStar
    val kind = when {
        star && fav -> Ctl.StarOn
        star -> Ctl.Star
        fav -> Ctl.HeartOn
        else -> Ctl.Heart
    }
    CtlButton(kind, size, modifier, heartColor, rb, fxId = fxId, hit = hit) {
        val want = !fav
        fav = want; lastTap = SystemClock.elapsedRealtime()
        FavoriteStore.set(ctx, favKey, want)
        // Çalan uygulamada da beğen / beğenmekten vazgeç
        if (!PlayerLike.setLiked(ctx, m.title, want)) {
            Toast.makeText(ctx.applicationContext, "Bu oynatıcı dışarıdan beğenmeyi desteklemiyor", Toast.LENGTH_SHORT).show()
        }
    }
}

// ---------------------------------------------------------------- Genişletilmiş müzik paneli
@Composable
fun MusicPanel(
    m: IslandContent.Media,
    accent: Color,
    cfg: IslandSettings,
    lyrics: LyricsState,
    frame: LyricFrame,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    customArt: ImageBitmap? = null,   // galeriden seçilen kapak; doluysa albüm kapağı yerine gösterilir
    onOpenMusic: () -> Unit = {},     // kapağa / şarkı adına basınca çalan müzik uygulamasını aç (ayarlardan)
    artFlip: ArtFlip? = null,         // kapak dönme animasyonu durumu
    artSpin: () -> Float = { 0f },    // ada açılırken / küçülürken kapağın dönüş açısı (derece)
    artVideo: String = "",            // doluysa kapak yerine bu video çalar
) {
    val rb = rememberRainbowStyle(cfg)
    val noRipple = remember { MutableInteractionSource() }

    // "Sözler sağda": kontroller solda, şarkı sözleri sağda (ekran görüntüsündeki düzen)
    if (cfg.lyricsEnabled && cfg.lyricsInPanel && cfg.more.lyricsSide) {
        Row(
            Modifier.fillMaxSize().padding(start = 18.dp, end = 16.dp, top = 24.dp, bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(0.56f).fillMaxHeight(), verticalArrangement = Arrangement.SpaceBetween) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.artTap(cfg.openExpArt, cfg.artCircleExpanded()) { onOpenMusic() }.fxTarget("art")) {
                        FlipAlbumArt(customArt ?: m.art, 50.dp, artFlip, artSpin, if (cfg.artCircleExpanded()) 50.dp / 2 else 11.dp, customArt == null && artVideo.isEmpty(), video = artVideo)
                    }
                    Column(
                        Modifier.weight(1f)
                            .then(if (cfg.openExpTitle) Modifier.clickable(interactionSource = noRipple, indication = null) { onOpenMusic() } else Modifier)
                            .fxTarget("title"),
                    ) {
                        MarqueeText(
                            m.title.ifBlank { "Bilinmeyen parça" }, Modifier.fillMaxWidth().rainbowFill(rb),
                            color = Pal.c("music_title"), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                        )
                        Text(m.artist, color = Pal.c("music_artist"), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().rainbowFill(rb))
                    }
                }
                Box(Modifier.fxTarget("progress")) { MusicProgress(m, cfg, rb) { onCommand(MediaCommand.SeekTo(it)) } }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    FavButton(m, cfg, 19.dp, rb = rb, hit = 36.dp)
                    CtlButton(Ctl.Rew, 20.dp, rb = rb, fxId = "rew", hit = 38.dp) { onCommand(MediaCommand.Previous) }
                    CtlButton(if (m.playing) Ctl.Pause else Ctl.Play, 24.dp, rb = rb, fxId = "play", hit = 40.dp) { onCommand(MediaCommand.PlayPause) }
                    CtlButton(Ctl.Fwd, 20.dp, rb = rb, fxId = "fwd", hit = 38.dp) { onCommand(MediaCommand.Next) }
                    CtlButton(Ctl.Cast, 20.dp, rb = rb, fxId = "cast", hit = 36.dp) { onOutput() }
                }
            }
            Box(Modifier.weight(0.44f).fillMaxHeight().fxTarget("lyrics")) {
                SideLyrics(cfg, lyrics, frame, Modifier.fillMaxSize(), artColor = m.artColor)
            }
        }
        return
    }

    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.artTap(cfg.openExpArt, cfg.artCircleExpanded()) { onOpenMusic() }.fxTarget("art")) {
                FlipAlbumArt(customArt ?: m.art, 60.dp, artFlip, artSpin, if (cfg.artCircleExpanded()) 60.dp / 2 else 12.dp, customArt == null && artVideo.isEmpty(), video = artVideo)   // sol köşe
            }
            // Kapak yüksekliğinde alan: şarkı adı ortada, sanatçı adı kapağın alt kenarıyla hizalı
            Box(
                Modifier.weight(1f).height(60.dp)
                    .then(if (cfg.openExpTitle) Modifier.clickable(interactionSource = noRipple, indication = null) { onOpenMusic() } else Modifier)
                    .fxTarget("title")
            ) {
                MarqueeText(
                    m.title.ifBlank { "Bilinmeyen parça" },
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().rainbowFill(rb),
                    color = Pal.c("music_title"), fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    m.artist, color = Pal.c("music_artist"), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().offset(y = 3.dp).rainbowFill(rb),
                )
            }
            // Kırılabilir öğe: adayı kırma / yağmur / asit efektlerinde ekolayzır da düşer
            Box(Modifier.fxTarget("eq")) { MusicMeter(accent, cfg.eqStyleNow(), height = 26.dp, barWidth = 4.dp, animate = m.playing, rainbow = rb) }
        }

        if (cfg.lyricsEnabled && cfg.lyricsInPanel) Column(Modifier.fxTarget("lyrics")) { PanelLyrics(cfg, lyrics, frame, artColor = m.artColor) }

        Box(Modifier.fxTarget("progress")) { MusicProgress(m, cfg, rb) { onCommand(MediaCommand.SeekTo(it)) } }

        Row(Modifier.fillMaxWidth().offset(y = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FavButton(m, cfg, 22.dp, Modifier.offset(x = (-8).dp), rb)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CtlButton(Ctl.Rew, 24.dp, rb = rb, fxId = "rew") { onCommand(MediaCommand.Previous) }
                CtlButton(if (m.playing) Ctl.Pause else Ctl.Play, 28.dp, rb = rb, fxId = "play") { onCommand(MediaCommand.PlayPause) }
                CtlButton(Ctl.Fwd, 24.dp, rb = rb, fxId = "fwd") { onCommand(MediaCommand.Next) }
            }
            CtlButton(Ctl.Cast, 25.dp, Modifier.offset(x = 5.dp), rb = rb, fxId = "cast") { onOutput() }
        }
    }
}

// ---------------------------------------------------------------- Orta boy müzik adası (iOS tarzı, kapalı hal yerine)
/**
 * Müzik çalarken adanın küçülmeden kaldığı orta boy görünüm: üstte kapak + şarkı / sanatçı + dalga,
 * ortada ilerleme çubuğu, altta beğeni, geri, oynat / duraklat, ileri ve çıkış düğmeleri.
 * Boş yerine dokununca ada tam boy panele açılır.
 */
@Composable
fun MusicMidPanel(
    m: IslandContent.Media,
    accent: Color,
    cfg: IslandSettings,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    customArt: ImageBitmap? = null,
    onOpenMusic: () -> Unit = {},
    artFlip: ArtFlip? = null,
    artSpin: () -> Float = { 0f },
    artVideo: String = "",
) {
    val rb = rememberRainbowStyle(cfg)
    val noRipple = remember { MutableInteractionSource() }
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 14.dp, top = 22.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(Modifier.artTap(cfg.openExpArt, cfg.artCircleExpanded()) { onOpenMusic() }) {
                FlipAlbumArt(customArt ?: m.art, 44.dp, artFlip, artSpin, if (cfg.artCircleExpanded()) 44.dp / 2 else 10.dp, customArt == null && artVideo.isEmpty(), video = artVideo)
            }
            Column(
                Modifier.weight(1f)
                    .then(if (cfg.openExpTitle) Modifier.clickable(interactionSource = noRipple, indication = null) { onOpenMusic() } else Modifier),
            ) {
                MarqueeText(
                    m.title.ifBlank { "Bilinmeyen parça" }, Modifier.fillMaxWidth().rainbowFill(rb),
                    color = Pal.c("music_title"), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
                Text(m.artist, color = Pal.c("music_artist"), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().rainbowFill(rb))
            }
            MusicMeter(accent, cfg.eqStyleNow(), height = 16.dp, barWidth = 3.dp, animate = m.playing, rainbow = rb)
        }
        MusicProgress(m, cfg, rb) { onCommand(MediaCommand.SeekTo(it)) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            FavButton(m, cfg, 19.dp, rb = rb, hit = 38.dp, fxId = null)
            CtlButton(Ctl.Rew, 22.dp, rb = rb, hit = 40.dp) { onCommand(MediaCommand.Previous) }
            CtlButton(if (m.playing) Ctl.Pause else Ctl.Play, 26.dp, rb = rb, hit = 42.dp) { onCommand(MediaCommand.PlayPause) }
            CtlButton(Ctl.Fwd, 22.dp, rb = rb, hit = 40.dp) { onCommand(MediaCommand.Next) }
            CtlButton(Ctl.Cast, 21.dp, rb = rb, hit = 38.dp) { onOutput() }
        }
    }
}

@Composable
fun AlbumArt(art: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(12.dp)
    if (art != null) {
        Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
            modifier = Modifier.size(size).clip(shape).border(1.dp, Pal.c("art_border"), shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(Pal.c("art_placeholder_bg")), contentAlignment = Alignment.Center) {
            Text("♪", color = Pal.c("art_placeholder_note"), fontSize = 22.sp)
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun MusicProgress(m: IslandContent.Media, cfg: IslandSettings, rb: RainbowStyle?, onSeek: (Long) -> Unit) {
    // Parıltı rengi: kapaktan (açıksa ve kapak varsa), yoksa ayarlardaki renk
    val baseGlow = if (cfg.progressGlowArtColor && m.artColor != 0) glowTone(Color(m.artColor)) else Color(cfg.progressGlowColorArgb)
    val glowColor by animateColorAsState(baseGlow, tween(450), label = "progressGlowColor")
    val glow = if (cfg.progressGlowEnabled) ProgressGlow(glowColor, cfg.progressGlowIntensity, cfg.progressGlowTipOnly, cfg.progressGlowInside) else null

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var drag by remember { mutableStateOf<Float?>(null) }

    // Sadece panel görünürken VE müzik çalarken yarım saniyede bir tikler; panel kapanınca coroutine biter.
    LaunchedEffect(m.playing, m.updatedAt, m.positionMs) {
        now = SystemClock.elapsedRealtime()
        while (m.playing) { delay(500); now = SystemClock.elapsedRealtime() }
    }

    val dur = m.durationMs
    val d = drag
    val frac = d ?: if (dur > 0) m.positionAt(now).toFloat() / dur else 0f
    val shownMs = if (d != null) (d * dur).toLong() else m.positionAt(now)
    val gray = Pal.c("music_time")

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(fmt(shownMs), color = gray, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 32.dp).rainbowFill(rb))
        Box(Modifier.weight(1f)) {
            SeekBar(frac.coerceIn(0f, 1f), dur > 0, d != null, glow, rb) { f, commit ->
                if (commit) { drag = null; onSeek((f * dur).toLong()) } else drag = f
            }
        }
        Text(if (dur > 0) "-" + fmt((dur - shownMs).coerceAtLeast(0L)) else "", color = gray, fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 36.dp).rainbowFill(rb))
    }
}

@Immutable
private class ProgressGlow(val color: Color, val intensity: Float, val tipOnly: Boolean, val inside: Boolean) {
    /** Uç modu her zaman, tüm çubuk modu ise "içinde kalsın" açıkken çubuğun içinde çizilir. */
    val contained: Boolean get() = tipOnly || inside
}

/** Kapak rengini parıltı için canlı ve parlak bir tona çeker (koyu kapaklarda da görünür kalsın). */
private fun glowTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.1f).coerceIn(0.25f, 1f)
    hsv[2] = hsv[2].coerceAtLeast(0.9f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Tüm çubuk modu, taşmalı: dolu kısmın arkasına dışa yayılan yumuşak ışık çizer. */
private fun DrawScope.drawOuterGlow(g: ProgressGlow, fillW: Float, cy: Float, th: Float) {
    val k = g.intensity.coerceIn(0f, 1f)
    // Üst üste binen yarı saydam katmanlar: ortası parlak, kenarlara doğru sönen yumuşak ışık
    val layers = 7
    for (i in layers downTo 1) {
        val grow = 16.dp.toPx() * i / layers
        val h = th + grow
        drawRoundRect(
            g.color.copy(alpha = 0.13f * k),
            Offset(-grow / 2f, cy - h / 2f), Size(fillW + grow, h), CornerRadius(h / 2f),
        )
    }
}

/** Parıltıyı dolu çubuğun İÇİNDE çizer (dışarı taşmaz): uç modunda ucu renklenir, tüm çubuk modunda çubuk ışıldar. */
private fun DrawScope.drawInnerGlow(g: ProgressGlow, fillW: Float, cy: Float, th: Float) {
    val k = g.intensity.coerceIn(0f, 1f)
    val top = cy - th / 2f
    val clip = Path().apply { addRoundRect(RoundRect(0f, top, fillW, top + th, CornerRadius(th / 2f))) }
    clipPath(clip) {
        if (g.tipOnly) {
            // Çubuğun son kısmı uca doğru parıltı rengine döner
            val len = minOf(fillW, 40.dp.toPx())
            val x0 = fillW - len
            val brush = Brush.horizontalGradient(
                0f to g.color.copy(alpha = 0f),
                0.55f to g.color.copy(alpha = 0.50f * k),
                1f to g.color.copy(alpha = 0.95f * k),
                startX = x0, endX = fillW,
            )
            drawRect(brush, Offset(x0, top), Size(len, th))
        } else {
            // Neon tüp: kenarlar parıltı rengi, ortada parlak beyaz çekirdek
            drawRect(g.color.copy(alpha = k), Offset(0f, top), Size(fillW, th))
            drawRoundRect(
                Pal.c("music_neon_core"),
                Offset(0f, cy - th * 0.2f), Size(fillW, th * 0.4f), CornerRadius(th * 0.2f),
            )
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, enabled: Boolean, dragging: Boolean, glow: ProgressGlow?, rb: RainbowStyle?, onChange: (Float, Boolean) -> Unit) {
    var w by remember { mutableIntStateOf(1) }
    val thick by animateFloatAsState(if (dragging) 8f else 5f, spring(stiffness = Spring.StiffnessMedium), label = "seekThick")
    val cb by rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxWidth().height(24.dp).rainbowFill(rb)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { cb((it.x / w).coerceIn(0f, 1f), true) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { last = (it.x / w).coerceIn(0f, 1f); cb(last, false) },
                    onDragEnd = { cb(last, true) },
                    onDragCancel = { cb(last, true) },
                ) { change, _ ->
                    change.consume()
                    last = (change.position.x / w).coerceIn(0f, 1f); cb(last, false)
                }
            }
    ) {
        val cy = size.height / 2
        val th = thick.dp.toPx()
        drawRoundRect(Pal.c("music_prog_track"), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        val fillW = (size.width * fraction).coerceAtLeast(0f)
        val glowOn = glow != null && fillW > 0f
        if (glowOn && !glow!!.contained) drawOuterGlow(glow, fillW, cy, th)
        drawRoundRect(Pal.c("music_prog_fill"), Offset(0f, cy - th / 2), Size(fillW, th), CornerRadius(th / 2))
        if (glowOn && glow!!.contained) drawInnerGlow(glow, fillW, cy, th)
    }
}


// ---------------------------------------------------------------- Alt kenar: albüm renginde hareketli ışık
/** Adanın alt kısmında, kapak renginden türeyen 3 yumuşak ışık lekesi yavaşça yana akar. Duraklayınca solar. */
@Composable
fun MusicBottomFx(color: Color, playing: Boolean, modifier: Modifier = Modifier) {
    val tone by animateColorAsState(glowTone(color), tween(450), label = "fxTone")
    val a by animateFloatAsState(if (playing) 1f else 0f, tween(600), label = "fxAlpha")
    if (a < 0.01f) return
    val t = rememberInfiniteTransition(label = "fx")
    val phase = t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "fxPhase")
    val c1 = tone
    val c2 = remember(tone) { hueShift(tone, 40f) }
    val c3 = remember(tone) { hueShift(tone, -40f) }
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        val p = phase.value
        val tau = (2 * Math.PI).toFloat()
        val cols = arrayOf(c1, c2, c3)
        val speed = intArrayOf(1, 2, 1)
        for (i in 0..2) {
            val cx = w * (0.5f + 0.42f * kotlin.math.sin(tau * (p * speed[i] + i / 3f)))
            val r = h * (0.95f + 0.25f * kotlin.math.sin(tau * (p * 2 + i / 3f)))
            val center = Offset(cx, h)
            withTransform({ scale(scaleX = 2.6f, scaleY = 1f, pivot = center) }) {
                drawCircle(
                    Brush.radialGradient(
                        0f to cols[i].copy(alpha = 0.60f * a),
                        1f to cols[i].copy(alpha = 0f),
                        center = center, radius = r,
                    ),
                    radius = r, center = center,
                )
            }
        }
    }
}

private fun hueShift(c: Color, deg: Float): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[0] = (hsv[0] + deg + 360f) % 360f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// ---------------------------------------------------------------- Kontrol ikonları (Canvas; ek ikon kütüphanesi gerekmez)
enum class Ctl { Play, Pause, Next, Prev, Rew, Fwd, Heart, HeartOn, Star, StarOn, Cast }

/** Beş köşeli yıldız (beğeni düğmesinin yıldız seçeneği). */
private fun DrawScope.star(s: Float, filled: Boolean, color: Color) {
    val p = Path()
    val cx = 0.5f * s; val cy = 0.53f * s
    val ro = 0.48f * s; val ri = 0.20f * s
    for (i in 0 until 10) {
        val r = if (i % 2 == 0) ro else ri
        val a = Math.toRadians((-90.0 + i * 36.0))
        val x = cx + (r * kotlin.math.cos(a)).toFloat()
        val y = cy + (r * kotlin.math.sin(a)).toFloat()
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    if (filled) drawPath(p, color) else drawPath(p, color, style = Stroke(width = 0.08f * s, join = StrokeJoin.Round))
}

private fun DrawScope.heart(s: Float, filled: Boolean, color: Color) {
    val p = Path().apply {
        moveTo(0.5f * s, 0.88f * s)
        cubicTo(0.12f * s, 0.62f * s, 0.02f * s, 0.40f * s, 0.14f * s, 0.25f * s)
        cubicTo(0.26f * s, 0.10f * s, 0.45f * s, 0.14f * s, 0.50f * s, 0.30f * s)
        cubicTo(0.55f * s, 0.14f * s, 0.74f * s, 0.10f * s, 0.86f * s, 0.25f * s)
        cubicTo(0.98f * s, 0.40f * s, 0.88f * s, 0.62f * s, 0.50f * s, 0.88f * s)
        close()
    }
    if (filled) drawPath(p, color) else drawPath(p, color, style = Stroke(width = 0.09f * s, join = StrokeJoin.Round))
}

private fun DrawScope.cast(s: Float, color: Color) {
    val c = Offset(0.5f * s, 0.5f * s)
    drawCircle(color, radius = 0.07f * s, center = c)
    for (r in floatArrayOf(0.22f * s, 0.40f * s)) {
        val tl = Offset(c.x - r, c.y - r)
        val sz = Size(2 * r, 2 * r)
        val st = Stroke(width = 0.07f * s, cap = StrokeCap.Round)
        drawArc(color, -40f, 80f, false, tl, sz, style = st)     // sağ dalga
        drawArc(color, 140f, 80f, false, tl, sz, style = st)     // sol dalga
    }
}

@Composable
private fun CtlButton(kind: Ctl, size: Dp, modifier: Modifier = Modifier, heartColor: Color = Color(0xFFFF2D6F), rb: RainbowStyle? = null, fxId: String? = null, hit: Dp = 48.dp, onClick: () -> Unit) {
    val fxMod = if (fxId != null) modifier.fxTarget(fxId) else modifier
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.84f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    Box(
        fxMod.size(hit).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size).rainbowFill(rb, hit)) {
            val s = this.size.width
            fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val p = Path().apply { moveTo(x1 * s, y1 * s); lineTo(x2 * s, y2 * s); lineTo(x3 * s, y3 * s); close() }
                drawPath(p, Pal.c("music_ctl"))
            }
            fun bar(x: Float, w: Float) = drawRoundRect(Pal.c("music_ctl"), Offset(x * s, 0.12f * s), Size(w * s, 0.76f * s), CornerRadius(0.04f * s))
            when (kind) {
                Ctl.Play -> tri(0.22f, 0.10f, 0.22f, 0.90f, 0.90f, 0.50f)
                Ctl.Pause -> { bar(0.20f, 0.22f); bar(0.58f, 0.22f) }
                Ctl.Next -> { tri(0.08f, 0.15f, 0.08f, 0.85f, 0.68f, 0.50f); bar(0.76f, 0.14f) }
                Ctl.Prev -> { tri(0.92f, 0.15f, 0.92f, 0.85f, 0.32f, 0.50f); bar(0.10f, 0.14f) }
                Ctl.Rew -> { tri(0.50f, 0.20f, 0.50f, 0.80f, 0.04f, 0.50f); tri(0.96f, 0.20f, 0.96f, 0.80f, 0.50f, 0.50f) }
                Ctl.Fwd -> { tri(0.04f, 0.20f, 0.04f, 0.80f, 0.50f, 0.50f); tri(0.50f, 0.20f, 0.50f, 0.80f, 0.96f, 0.50f) }
                Ctl.Heart -> heart(s, false, Pal.c("music_ctl_dim"))
                Ctl.HeartOn -> heart(s, true, heartColor)
                Ctl.Star -> star(s, false, Pal.c("music_ctl_dim"))
                Ctl.StarOn -> star(s, true, heartColor)
                Ctl.Cast -> cast(s, Pal.c("music_ctl_dim"))
            }
        }
    }
}

/** Dolu beyaz daire içinde oynat/duraklat; basınca yaylanır. 48 dp dokunma alanı, 44 dp görünür daire. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "playPress")
    Box(
        Modifier.size(48.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).background(Pal.c("music_play_bg"), CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val ink = Pal.c("music_play_ink")
                if (playing) {
                    drawRoundRect(ink, Offset(0.14f * w, 0.08f * w), Size(0.24f * w, 0.84f * w), CornerRadius(0.05f * w))
                    drawRoundRect(ink, Offset(0.62f * w, 0.08f * w), Size(0.24f * w, 0.84f * w), CornerRadius(0.05f * w))
                } else {
                    val p = Path().apply { moveTo(0.22f * w, 0.06f * w); lineTo(0.22f * w, 0.94f * w); lineTo(0.94f * w, 0.5f * w); close() }
                    drawPath(p, ink)
                }
            }
        }
    }
}


// ---------------------------------------------------------------- Hap tasarımı: müzik (küçültülmüş ada, PILL_MUSIC_H_DP yüksekliğinde)
@Composable
private fun PillCtl(kind: Ctl, size: Dp, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.84f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "pillCtlPress")
    Box(
        Modifier.size(34.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val s = this.size.width
            val col = Pal.c("music_ctl")
            fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val p = Path().apply { moveTo(x1 * s, y1 * s); lineTo(x2 * s, y2 * s); lineTo(x3 * s, y3 * s); close() }
                drawPath(p, col)
            }
            fun bar(x: Float, w: Float) = drawRoundRect(col, Offset(x * s, 0.10f * s), Size(w * s, 0.80f * s), CornerRadius(0.08f * s))
            when (kind) {
                Ctl.Play -> tri(0.22f, 0.08f, 0.22f, 0.92f, 0.92f, 0.50f)
                Ctl.Pause -> { bar(0.14f, 0.26f); bar(0.60f, 0.26f) }
                Ctl.Next -> { tri(0.06f, 0.14f, 0.06f, 0.86f, 0.66f, 0.50f); bar(0.74f, 0.20f) }
                Ctl.Prev -> { tri(0.94f, 0.14f, 0.94f, 0.86f, 0.34f, 0.50f); bar(0.06f, 0.20f) }
                else -> Unit
            }
        }
    }
}

/** Hap müzik tasarımı: solda büyük kapak, ortada başlık / sanatçı / renkli dalga / ilerleme, sağda önceki-duraklat-sonraki. */
@Composable
fun MusicPillPanel(
    m: IslandContent.Media,
    cfg: IslandSettings,
    onCommand: (MediaCommand) -> Unit,
    customArt: ImageBitmap? = null,
    onOpenMusic: () -> Unit = {},
    artFlip: ArtFlip? = null,
    artSpin: () -> Float = { 0f },
    artVideo: String = "",
) {
    val rb = rememberRainbowStyle(cfg)
    val noRipple = remember { MutableInteractionSource() }
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var drag by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(m.playing, m.updatedAt, m.positionMs) {
        now = SystemClock.elapsedRealtime()
        while (m.playing) { delay(500); now = SystemClock.elapsedRealtime() }
    }
    val dur = m.durationMs
    val d = drag
    val frac = d ?: if (dur > 0) m.positionAt(now).toFloat() / dur else 0f
    val shownMs = if (d != null) (d * dur).toLong() else m.positionAt(now)
    val gray = Pal.c("music_time")

    // Dalga renkleri: albüm rengi açık ve kapak varsa kapaktan türetilir (orta = kapak rengi, yanlar = ±40° ton kaydırma), yoksa ayarlardaki 3 renk
    val useArt = cfg.pillWaveArtColor && m.artColor != 0
    val artMid = if (useArt) glowTone(Color(m.artColor)) else Color.Unspecified
    val tgtL = if (useArt) hueShift(artMid, -40f) else Color(cfg.pillWaveLeftArgb)
    val tgtM = if (useArt) artMid else Color(cfg.pillWaveMidArgb)
    val tgtR = if (useArt) hueShift(artMid, 40f) else Color(cfg.pillWaveRightArgb)
    val waveL by animateColorAsState(tgtL, tween(450), label = "pillWaveL")
    val waveM by animateColorAsState(tgtM, tween(450), label = "pillWaveM")
    val waveR by animateColorAsState(tgtR, tween(450), label = "pillWaveR")

    Row(
        Modifier.fillMaxSize().padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.artTap(cfg.openExpArt, cfg.artCircleCompact()) { onOpenMusic() }.fxTarget("art")) {
            FlipAlbumArt(customArt ?: m.art, 64.dp, artFlip, artSpin, if (cfg.artCircleCompact()) 64.dp / 2 else 14.dp, customArt == null && artVideo.isEmpty(), video = artVideo)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically)) {
            Box(
                Modifier.fillMaxWidth()
                    .then(if (cfg.openExpTitle) Modifier.clickable(interactionSource = noRipple, indication = null) { onOpenMusic() } else Modifier)
                    .fxTarget("title")
            ) {
                MarqueeText(
                    m.title.ifBlank { "Bilinmeyen parça" }, Modifier.fillMaxWidth().rainbowFill(rb),
                    color = Pal.c("music_title"), fontSize = 16.sp, fontWeight = FontWeight.Bold,
                )
            }
            Text(
                m.artist, color = Pal.c("music_artist"), fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().rainbowFill(rb),
            )
            PillWave(
                listOf(waveL, waveM, waveR),
                Modifier.fillMaxWidth().height(18.dp), active = m.playing,
            )
            Row(Modifier.fxTarget("progress"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(fmt(shownMs), color = gray, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                Box(Modifier.weight(1f).height(16.dp)) {
                    SeekBar(frac.coerceIn(0f, 1f), dur > 0, d != null, null, rb) { f, commit ->
                        if (commit) { drag = null; onCommand(MediaCommand.SeekTo((f * dur).toLong())) } else drag = f
                    }
                }
                Text(if (dur > 0) fmt(dur) else "", color = gray, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
            }
        }
        Row(Modifier.offset(y = 13.dp), verticalAlignment = Alignment.CenterVertically) {   // düğmeler biraz aşağıda: dalga / ilerleme satırıyla hizalı
            PillCtl(Ctl.Prev, 20.dp) { onCommand(MediaCommand.Previous) }
            PillCtl(if (m.playing) Ctl.Pause else Ctl.Play, 28.dp) { onCommand(MediaCommand.PlayPause) }
            PillCtl(Ctl.Next, 20.dp) { onCommand(MediaCommand.Next) }
        }
    }
}
