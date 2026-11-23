package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * ADA SÜSLERİ
 *  - Renkli çerçeve: renk geçişi / gökkuşağı (kenar boyunca akar), ateş ve mor alev (titreyen alevler), neon (tek renk,
 *    nabız), elektrik (kenardan çıkan şimşek telleri), ses dalgası (noktalı halka + dışa fışkıran çubuklar).
 *    Adanın kendi şekline çizilir; ada açılınca da büyüyen şekli izler. -> Modifier.islandNeon
 *  - Adanın üstünde oturan karakter (uzanan kedi, kedi / ayı kulakları) ve adadan sarkan süsler (salıncakta civciv,
 *    yıldızlar, yılbaşı süsleri, kalpler) ve adadan akan mürekkep damlası. Adanın ALTINDA duran, dokunuş almayan
 *    tam ekran bir pencerede çizilir (ada üstte kalır). -> DecorOverlay
 *  - Boştaki adanın içinde dolaşan küçük karakter (tavşan, civciv, kedi yavrusu) ve parıltılar. -> IslandPet
 * Tüm karakterler bu uygulamaya özgü, basit şekillerle çizilir.
 */

// ================================================================ Renkli çerçeve (neon / ateş)

private val RainbowPalette = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00), Color(0xFF34C759),
    Color(0xFF00C7FF), Color(0xFF5856D6), Color(0xFFFF2D9A),
)

/** Döngüsel palette [t] (0..1) konumundaki renk. */
private fun cyclic(pal: List<Color>, t: Float): Color {
    if (pal.size == 1) return pal[0]
    val x = ((t % 1f) + 1f) % 1f * pal.size
    val i = x.toInt() % pal.size
    return lerp(pal[i], pal[(i + 1) % pal.size], x - x.toInt())
}

/** Kenar boyunca dönen (akan) açısal renk geçişi. */
private fun flowBrush(pal: List<Color>, phase: Float, center: Offset): Brush =
    Brush.sweepGradient(List(25) { i -> cyclic(pal, i / 24f - phase) }, center)

/**
 * Adanın kenarına renkli çerçeve + dışa vuran ışık. islandSurface'tan ÖNCE (kırpmanın dışında) uygulanmalı.
 * Çizgi içeriğin üstüne, ışık içeriğin altına çizilir.
 */
@Composable
internal fun Modifier.islandNeon(cfg: IslandSettings): Modifier {
    val style = cfg.neonStyle
    if (style == 0) return this
    val tr = rememberInfiniteTransition(label = "neon")
    val dur = (4200f / cfg.neonSpeed.coerceIn(0.2f, 4f)).roundToInt()
    val phase = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(dur, easing = LinearEasing)), label = "neonPhase")
    val c1 = Color(cfg.neonColor1Argb)
    val c2 = Color(cfg.neonColor2Argb)
    // Renk geçişinde iki rengin arası beyaza yakın parlak bir geçişle bağlanır (videodaki pembe → beyaz → mint)
    val mid = lerp(lerp(c1, c2, 0.5f), Color.White, 0.55f)
    val pal = when (style) {
        1 -> listOf(c1, mid, c2, mid)
        2 -> RainbowPalette
        4 -> listOf(c1)
        6 -> listOf(Color(0xFFB26BFF), Color(0xFF7B2CFF), Color(0xFFD9B8FF))
        else -> listOf(Color(0xFFFF6A00), Color(0xFFFFC400), Color(0xFFFF2A00))
    }
    val wDp = cfg.neonWidthDp
    val glow = cfg.neonGlow.coerceIn(0f, 1.5f)
    return this.drawWithContent {
        val p = phase.value
        val r = min(36.dp.toPx(), size.height / 2f)
        val sw = wDp.dp.toPx()
        val pad = NEON_PAD_DP.dp.toPx()
        val pulse = if (style == 4) 0.6f + 0.4f * (0.5f + 0.5f * sin(p * 2f * PI.toFloat() * 2f)) else 1f
        val brush: Brush = when (style) {
            4 -> Brush.linearGradient(listOf(c1, c1))
            5 -> Brush.verticalGradient(listOf(c1, c2), 0f, size.height)       // elektrik: üst kenar 1. renk, alt kenar 2. renk
            7 -> Brush.horizontalGradient(listOf(c1, c2), 0f, size.width)      // ses dalgası: soldan sağa
            else -> flowBrush(pal, p, center)
        }

        if (style == 7) {
            // Noktalı ses dalgası halkası: içerik üstte, halka adanın biraz dışında
            drawContent()
            drawSpectrum(p, r, pad, c1, c2, glow)
            return@drawWithContent
        }
        if (style == 3) {
            drawFire(p, r, pad * glow.coerceAtLeast(0.3f), FireOrange)
        } else if (style == 6) {
            drawFire(p, r, pad * 1.15f * glow.coerceAtLeast(0.3f), FirePurple)
        } else if (glow > 0f) {
            // Dışa vuran yumuşak ışık: kademeli, giderek sönen halkalar
            val rings = 7
            val step = pad * 0.85f / rings
            for (i in 1..rings) {
                val inflate = (i - 0.5f) * step
                val fall = 1f - (i - 1f) / rings
                drawRoundRect(
                    brush = brush, topLeft = Offset(-inflate, -inflate),
                    size = Size(size.width + 2 * inflate, size.height + 2 * inflate),
                    cornerRadius = CornerRadius(r + inflate),
                    alpha = (glow * 0.5f * fall * fall * pulse).coerceIn(0f, 1f),
                    style = Stroke(step),
                )
            }
        }
        drawContent()
        if (style == 5) drawLightning(p, r, pad * glow.coerceAtLeast(0.3f), c1, c2)
        val edge: Brush = if (style == 3 || style == 6) flowBrush(pal, p * 3f, center) else brush
        drawRoundRect(
            brush = edge, topLeft = Offset(sw / 2f, sw / 2f),
            size = Size(size.width - sw, size.height - sw),
            cornerRadius = CornerRadius(max(r - sw / 2f, 0f)),
            alpha = pulse, style = Stroke(sw),
        )
    }
}

/** Yuvarlatılmış dikdörtgenin çevresinde [s] (0..1) noktasındaki konum ve dışa bakan normal. */
private fun DrawScope.perimeterPoint(s: Float, r: Float): Pair<Offset, Offset> {
    val w = size.width; val h = size.height
    val sx = max(w - 2 * r, 0f); val sy = max(h - 2 * r, 0f)
    val arc = (PI / 2).toFloat() * r
    val total = 2 * sx + 2 * sy + 4 * arc
    var d = (((s % 1f) + 1f) % 1f) * total
    fun corner(cx: Float, cy: Float, a0: Float, t: Float): Pair<Offset, Offset> {
        val a = a0 + t * (PI / 2).toFloat()
        val n = Offset(cos(a), sin(a))
        return Offset(cx + n.x * r, cy + n.y * r) to n
    }
    if (d < sx) return Offset(r + d, 0f) to Offset(0f, -1f); d -= sx
    if (d < arc) return corner(w - r, r, -(PI / 2).toFloat(), d / arc); d -= arc
    if (d < sy) return Offset(w, r + d) to Offset(1f, 0f); d -= sy
    if (d < arc) return corner(w - r, h - r, 0f, d / arc); d -= arc
    if (d < sx) return Offset(w - r - d, h) to Offset(0f, 1f); d -= sx
    if (d < arc) return corner(r, h - r, (PI / 2).toFloat(), d / arc); d -= arc
    if (d < sy) return Offset(0f, h - r - d) to Offset(-1f, 0f); d -= sy
    return corner(r, r, PI.toFloat(), (d / arc).coerceIn(0f, 1f))
}

private class FirePal(val core: Color, val body: Color, val tip: Color)
private val FireOrange = FirePal(Color(0xFFFFE07A), Color(0xFFFF8A00), Color(0xFFFF2A00))
private val FirePurple = FirePal(Color(0xFFE9D6FF), Color(0xFF9B4DFF), Color(0xFF4A12B8))

/** 0..1 arası, (i, j) için hep aynı sözde rastgele sayı (titreme her karede yeniden hesaplanır ama sıçramaz). */
private fun hash01(i: Int, j: Int): Float {
    val v = sin(i * 12.9898f + j * 78.233f) * 43758.547f
    return v - kotlin.math.floor(v)
}

/**
 * Elektrik: kenarda ince parlak çizgi; çizgiden dışarı doğru kısa, kırık kırık şimşek telleri hızla belirip söner.
 * Üst kenarda [top] rengi, alt kenarda [bottom] rengi.
 */
private fun DrawScope.drawLightning(p: Float, r: Float, reach: Float, top: Color, bottom: Color) {
    val n = 70
    val step = (p * 140f).toInt()               // ~30 ms'de bir yeni şimşek deseni
    val sw = 1.1.dp.toPx()
    for (i in 0 until n) {
        val h = hash01(i, step)
        if (h > 0.42f) continue
        val (pt, nrm) = perimeterPoint(i / n.toFloat() + hash01(i, step + 7) * 0.01f, r)
        val col = lerp(top, bottom, (pt.y / size.height).coerceIn(0f, 1f))
        val len = reach * (0.25f + 0.75f * hash01(i + 31, step))
        val tang = Offset(-nrm.y, nrm.x)
        val path = Path().apply {
            moveTo(pt.x, pt.y)
            var cur = pt
            val segs = 3
            for (k in 1..segs) {
                val along = len * k / segs
                val jit = (hash01(i * 7 + k, step) - 0.5f) * len * 0.7f
                cur = Offset(pt.x + nrm.x * along + tang.x * jit, pt.y + nrm.y * along + tang.y * jit)
                lineTo(cur.x, cur.y)
            }
        }
        val a = (0.35f + 0.65f * hash01(i + 5, step)).coerceIn(0f, 1f)
        drawPath(path, col.copy(alpha = a * 0.35f), style = Stroke(sw * 3.5f, cap = StrokeCap.Round))     // yumuşak hale
        drawPath(path, lerp(col, Color.White, 0.5f).copy(alpha = a), style = Stroke(sw, cap = StrokeCap.Round))
    }
}

/**
 * Ses dalgası halkası: adanın biraz dışında sık dizilmiş küçük noktalar (soldan sağa [left] → [right] rengi).
 * Halka boyunca gezen "vuruşlar" noktaları dışa doğru kısa çubuklara dönüştürür (ekolayzır gibi).
 */
private fun DrawScope.drawSpectrum(p: Float, r: Float, pad: Float, left: Color, right: Color, glow: Float) {
    val gap = 3.dp.toPx()
    val dotR = 0.9.dp.toPx()
    val sw = size.width; val sh = size.height
    val ro = r + gap
    val per = 2 * (sw - 2 * r).coerceAtLeast(0f) + 2 * (sh - 2 * r).coerceAtLeast(0f) + 2f * PI.toFloat() * ro
    val n = (per / 3.4.dp.toPx()).toInt().coerceIn(40, 400)
    val step = (p * 90f).toInt()
    val tau = 2f * PI.toFloat()
    for (i in 0 until n) {
        val s = i / n.toFloat()
        val (pt, nrm) = perimeterPoint(s, r)
        val base = Offset(pt.x + nrm.x * gap, pt.y + nrm.y * gap)
        val col = lerp(left, right, (base.x / sw).coerceIn(0f, 1f))
        // İki vuruş halka boyunca ters yönlerde gezer; üstüne nokta başına titreme
        fun burst(c: Float, wid: Float): Float {
            var d = abs(s - c); d = min(d, 1f - d)
            return kotlin.math.exp(-(d * d) / (2 * wid * wid))
        }
        val b1 = burst(((p * 1.3f) % 1f), 0.035f) * (0.6f + 0.4f * sin(tau * p * 7f))
        val b2 = burst(((1f - p * 0.9f) % 1f + 1f) % 1f, 0.025f) * (0.6f + 0.4f * cos(tau * p * 5f))
        val amp = ((b1 + b2) * (0.45f + 0.55f * hash01(i, step))).coerceIn(0f, 1f)
        val len = amp * pad * 0.9f * glow.coerceIn(0.4f, 1.5f)
        if (len > dotR * 1.5f) {
            drawLine(col, base, Offset(base.x + nrm.x * len, base.y + nrm.y * len), dotR * 1.5f, StrokeCap.Round)
        } else {
            drawCircle(col.copy(alpha = 0.9f), dotR, base)
        }
    }
}

/**
 * Gerçekçi alev: kenar boyunca sık dizilmiş, sivri uçlu dil biçiminde alevler. Her dil üç katmandır
 * (dış: koyu uç rengi, orta: gövde, iç: sıcak çekirdek); tabandan uca doğru parlaktan şeffafa söner.
 * Alevler rüzgârla yana eğilir, boyları düzensiz titrer, aralarında yukarı süzülen kıvılcımlar uçuşur.
 */
private fun DrawScope.drawFire(p: Float, r: Float, reach: Float, fp: FirePal) {
    val n = 64
    val tau = 2f * PI.toFloat()
    val sw0 = size.width; val sh0 = size.height
    val per = 2 * (sw0 - 2 * r).coerceAtLeast(0f) + 2 * (sh0 - 2 * r).coerceAtLeast(0f) + tau * r
    val wBase = (per / n) * 2.1f
    val tick = (p * 60f).toInt()
    // Tabanda yumuşak sıcak ışıma
    for (i in 0 until n) {
        val s = i / n.toFloat()
        val (pt, nrm) = perimeterPoint(s, r)
        val tang = Offset(-nrm.y, nrm.x)
        // Boy: yavaş dalga + hızlı titreme + alev başına rastgelelik (hepsi döngüsel)
        val slow = 0.5f + 0.5f * sin(tau * (p * 2f + i * 0.173f))
        val fast = 0.5f + 0.5f * sin(tau * (p * 7f + i * 0.619f))
        val rnd = 0.55f + 0.45f * hash01(i, 3)
        val flick = 0.78f + 0.22f * hash01(i, tick)
        val hgt = reach * (0.28f + 0.72f * (0.55f * slow + 0.25f * fast + 0.2f * rnd)) * flick
        val w = wBase * (0.8f + 0.5f * hash01(i, 11))
        // Eğilme: komşu alevlerle birlikte bir yöne akan dalga (rüzgâr)
        val lean = sin(tau * (p * 1.5f - s * 6f)) * w * 0.9f + (hash01(i, tick / 2) - 0.5f) * w * 0.35f
        flameTongue(pt, nrm, tang, w, hgt * 1.0f, lean, 0.9f,
            listOf(fp.body.copy(alpha = 0.85f), fp.tip.copy(alpha = 0.55f), fp.tip.copy(alpha = 0f)))
        flameTongue(pt, nrm, tang, w * 0.72f, hgt * 0.74f, lean * 0.8f, 0.85f,
            listOf(lerp(fp.core, fp.body, 0.35f).copy(alpha = 0.95f), fp.body.copy(alpha = 0.7f), fp.tip.copy(alpha = 0f)))
        flameTongue(pt, nrm, tang, w * 0.42f, hgt * 0.45f, lean * 0.55f, 0.8f,
            listOf(Color.White.copy(alpha = 0.95f), fp.core.copy(alpha = 0.8f), fp.body.copy(alpha = 0f)))
    }
    // Kıvılcımlar: yukarı süzülüp sönen küçük parlak noktalar
    val sparks = 22
    for (k in 0 until sparks) {
        val life = ((p * (1.1f + 0.9f * hash01(k, 5)) + hash01(k, 9)) % 1f)
        val (pt, nrm) = perimeterPoint(hash01(k, 1), r)
        val tang = Offset(-nrm.y, nrm.x)
        val d = reach * (0.35f + 1.1f * life)
        val sway = sin(tau * (life * 2f + hash01(k, 2))) * reach * 0.18f
        val c = Offset(pt.x + nrm.x * d + tang.x * sway, pt.y + nrm.y * d + tang.y * sway)
        val a = (1f - life) * (1f - life)
        val rad = (0.7f + 1.1f * hash01(k, 4)).dp.toPx() * (1f - 0.6f * life)
        drawCircle(lerp(fp.core, fp.body, life).copy(alpha = a), rad, c)
    }
}

/** Tek bir alev dili: tabanı [w] geniş, ucu [h] yüksekte ve [lean] kadar yana eğik, kenarları S biçiminde. */
private fun DrawScope.flameTongue(
    base: Offset, nrm: Offset, tang: Offset, w: Float, h: Float, lean: Float, alpha: Float, stops: List<Color>,
) {
    if (h < 1f) return
    val bl = Offset(base.x - tang.x * w / 2f, base.y - tang.y * w / 2f)
    val br = Offset(base.x + tang.x * w / 2f, base.y + tang.y * w / 2f)
    val tip = Offset(base.x + nrm.x * h + tang.x * lean, base.y + nrm.y * h + tang.y * lean)
    // Kenarlar: önce dışa şişer, uca doğru incelip sivrilir
    val cl = Offset(base.x + nrm.x * h * 0.45f - tang.x * w * 0.62f + tang.x * lean * 0.35f,
                    base.y + nrm.y * h * 0.45f - tang.y * w * 0.62f + tang.y * lean * 0.35f)
    val cr = Offset(base.x + nrm.x * h * 0.45f + tang.x * w * 0.62f + tang.x * lean * 0.35f,
                    base.y + nrm.y * h * 0.45f + tang.y * w * 0.62f + tang.y * lean * 0.35f)
    val path = Path().apply {
        moveTo(bl.x, bl.y)
        cubicTo(cl.x, cl.y, base.x + nrm.x * h * 0.8f + tang.x * lean * 0.8f - tang.x * w * 0.12f,
                base.y + nrm.y * h * 0.8f + tang.y * lean * 0.8f - tang.y * w * 0.12f, tip.x, tip.y)
        cubicTo(base.x + nrm.x * h * 0.8f + tang.x * lean * 0.8f + tang.x * w * 0.12f,
                base.y + nrm.y * h * 0.8f + tang.y * lean * 0.8f + tang.y * w * 0.12f, cr.x, cr.y, br.x, br.y)
        close()
    }
    drawPath(path, Brush.linearGradient(stops, start = base, end = tip), alpha = alpha)
}

// ================================================================ Adanın içinde dolaşan karakter

/** Boştaki adanın sol yarısında (kamera deliğine girmeden) yürüyüp zıplayan küçük karakter + parıltılar. */
@Composable
internal fun IslandPet(kind: Int, modifier: Modifier, startPadFrac: Float = 0f) {
    if (kind == 0) return
    val tr = rememberInfiniteTransition(label = "pet")
    val walk = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "petWalk")
    val hop = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(520, easing = LinearEasing)), label = "petHop")
    val tw = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "petTw")
    Canvas(modifier) {
        val h = size.height
        val sz = h * 0.62f
        val left = size.width * startPadFrac + h * 0.55f
        val right = size.width * 0.42f - h * 0.2f
        val w = walk.value
        val t = if (w < 0.5f) w * 2f else 2f - w * 2f            // gidip gelir
        val facing = if (w < 0.5f) 1f else -1f
        val x = left + (right - left).coerceAtLeast(0f) * t
        val y = h / 2f + sz * 0.08f - abs(sin(hop.value * PI.toFloat())) * h * 0.09f
        // Parıltılar: karakterin çevresinde sırayla yanıp söner
        val star = listOf(Offset(-0.9f, -0.55f), Offset(0.85f, -0.65f), Offset(0.95f, 0.35f), Offset(-0.75f, 0.5f))
        star.forEachIndexed { i, o ->
            val a = (0.5f + 0.5f * sin(2f * PI.toFloat() * (tw.value + i * 0.27f))).coerceIn(0f, 1f)
            sparkle(Offset(x + o.x * sz, y + o.y * sz * 0.8f), sz * 0.13f, Color(0xFFFFD45A).copy(alpha = a))
        }
        translate(x, y) { scale(facing, 1f, Offset.Zero) { drawPet(kind, sz) } }
    }
}

private fun DrawScope.sparkle(c: Offset, r: Float, color: Color) {
    if (color.alpha < 0.03f) return
    val p = Path().apply {
        moveTo(c.x, c.y - r)
        quadraticTo(c.x, c.y, c.x + r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y + r)
        quadraticTo(c.x, c.y, c.x - r, c.y)
        quadraticTo(c.x, c.y, c.x, c.y - r)
        close()
    }
    drawPath(p, color)
}

/** Karakter (merkez 0,0; sağa bakar). [s]: boy. */
private fun DrawScope.drawPet(kind: Int, s: Float) {
    val eye = Color(0xFF1B1B1B)
    when (kind) {
        1 -> { // Tavşan
            val white = Color(0xFFFFFFFF); val pink = Color(0xFFFFA8C5)
            drawOval(white, Offset(-s * 0.32f, -s * 0.95f), Size(s * 0.2f, s * 0.6f))
            drawOval(white, Offset(-s * 0.05f, -s * 1.0f), Size(s * 0.2f, s * 0.62f))
            drawOval(pink, Offset(-s * 0.27f, -s * 0.85f), Size(s * 0.09f, s * 0.4f))
            drawOval(pink, Offset(0f, -s * 0.9f), Size(s * 0.09f, s * 0.42f))
            drawOval(white, Offset(-s * 0.42f, -s * 0.1f), Size(s * 0.8f, s * 0.55f))
            drawCircle(white, s * 0.3f, Offset(s * 0.02f, -s * 0.28f))
            drawCircle(eye, s * 0.045f, Offset(s * 0.13f, -s * 0.3f))
            drawCircle(pink.copy(alpha = 0.8f), s * 0.05f, Offset(s * 0.2f, -s * 0.2f))
            drawCircle(white, s * 0.1f, Offset(-s * 0.45f, s * 0.12f))
        }
        2 -> { // Civciv
            val yel = Color(0xFFFFD43B)
            drawCircle(yel, s * 0.36f, Offset(-s * 0.05f, s * 0.05f))
            drawCircle(yel, s * 0.25f, Offset(s * 0.12f, -s * 0.3f))
            drawOval(Color(0xFFF2B600), Offset(-s * 0.3f, -s * 0.02f), Size(s * 0.3f, s * 0.2f))
            val beak = Path().apply {
                moveTo(s * 0.33f, -s * 0.33f); lineTo(s * 0.5f, -s * 0.27f); lineTo(s * 0.33f, -s * 0.21f); close()
            }
            drawPath(beak, Color(0xFFFF8A00))
            drawCircle(eye, s * 0.045f, Offset(s * 0.2f, -s * 0.36f))
            drawLine(Color(0xFFFF8A00), Offset(-s * 0.1f, s * 0.38f), Offset(-s * 0.12f, s * 0.48f), s * 0.05f, StrokeCap.Round)
            drawLine(Color(0xFFFF8A00), Offset(s * 0.05f, s * 0.38f), Offset(s * 0.07f, s * 0.48f), s * 0.05f, StrokeCap.Round)
        }
        else -> { // Kedi yavrusu
            val gray = Color(0xFFB9BCC4); val pink = Color(0xFFFFB3C7)
            drawOval(gray, Offset(-s * 0.45f, -s * 0.12f), Size(s * 0.78f, s * 0.5f))
            drawLine(gray, Offset(-s * 0.42f, s * 0.05f), Offset(-s * 0.62f, -s * 0.3f), s * 0.1f, StrokeCap.Round)
            drawCircle(gray, s * 0.29f, Offset(s * 0.12f, -s * 0.27f))
            val earL = Path().apply { moveTo(-s * 0.08f, -s * 0.4f); lineTo(-s * 0.02f, -s * 0.7f); lineTo(s * 0.12f, -s * 0.5f); close() }
            val earR = Path().apply { moveTo(s * 0.16f, -s * 0.52f); lineTo(s * 0.34f, -s * 0.7f); lineTo(s * 0.38f, -s * 0.38f); close() }
            drawPath(earL, gray); drawPath(earR, gray)
            drawCircle(eye, s * 0.042f, Offset(s * 0.06f, -s * 0.3f))
            drawCircle(eye, s * 0.042f, Offset(s * 0.24f, -s * 0.3f))
            drawCircle(pink, s * 0.03f, Offset(s * 0.16f, -s * 0.2f))
        }
    }
}

// ================================================================ Adanın çevresindeki süsler (ayrı pencere)

/** Adanın ekrandaki yeri (px) ve süslerin görünüp görünmeyeceği. */
data class DecorAnchor(
    val cx: Float, val top: Float, val w: Float, val h: Float,
    val inverted: Boolean, val visible: Boolean, val screenH: Float,
)

/** Adanın ALTINDA duran, dokunuş almayan tam ekran pencere: üstteki karakter, sarkan süsler, mürekkep damlası. */
class DecorOverlay(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val anchor: StateFlow<DecorAnchor?>,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    private var tries = 0

    /** Herhangi bir süs açık mı? Kapalıysa pencere hiç eklenmez. */
    fun wanted(s: IslandSettings) = s.decorTop != 0 || s.decorHang != 0 || s.dripEnabled

    fun sync() {
        val on = wanted(settings.value)
        if (on && view == null) attach() else if (!on && view != null) detach()
    }

    fun attach() {
        if (view != null || !wanted(settings.value)) return
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { DecorLayer(settings, anchor) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val p2 = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(v, p2); host = h2; lp = p2; added = true
            }
        }
        if (!added) {
            val p3 = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            added = runCatching { wm.addView(v, p3); host = wm; lp = p3 }.isSuccess
        }
        if (!added) { v.disposeComposition(); return }
        view = v
        tries = 0
        v.postDelayed(calibrate, 300)
    }

    /** Pencere ekranın en üstünden başlamıyorsa farkı kadar yukarı çeker (çizim ekran koordinatıyla yapılır). */
    private val calibrate = object : Runnable {
        override fun run() {
            val v = view ?: return
            val params = lp ?: return
            val h = host ?: return
            if (!v.isAttachedToWindow) return
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dy = loc[1]
            if (abs(dy) <= 1 || tries >= 3) return
            tries++
            params.y -= dy
            params.height = h.currentWindowMetrics.bounds.height() + abs(params.y)
            runCatching { h.updateViewLayout(v, params) }
            v.postDelayed(this, 250)
        }
    }

    fun detach() {
        val v = view ?: return
        v.removeCallbacks(calibrate)
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        // Sahipler kaldırılmaz: pencere eklenip hemen kaldırılırsa bekleyen ilk çizim sahibi bulamayıp çöker
        view = null; host = null; lp = null
    }
}

@Composable
internal fun DecorLayer(settings: StateFlow<IslandSettings>, anchorFlow: StateFlow<DecorAnchor?>) {
    val s by settings.collectAsState()
    val cur by anchorFlow.collectAsState()
    // Görünmez olunca son konumda solarak kaybolsun
    val last = remember { arrayOfNulls<DecorAnchor>(1) }
    if (cur != null) last[0] = cur
    val a = last[0] ?: return
    val vis by animateFloatAsState(if (cur?.visible == true) 1f else 0f, tween(if (cur?.visible == true) 380 else 180), label = "decorVis")
    if (vis <= 0.001f) return
    val body = Color(s.islandColorArgb).copy(alpha = 1f)
    Box(Modifier.fillMaxSize()) {
        if (s.decorTop != 0) TopDecor(s.decorTop, a, vis)
        if (s.decorHang != 0) HangDecor(s.decorHang, a, vis)
        if (s.dripEnabled) Drip(a, body, s.dripEverySec, cur?.visible == true)
    }
}

/** Adanın üstüne oturan karakter. Pencere adanın altında olduğu için adanın içine taşan kısım görünmez. */
@Composable
private fun TopDecor(kind: Int, a: DecorAnchor, vis: Float) {
    val tr = rememberInfiniteTransition(label = "top")
    val t = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "topT")
    Canvas(Modifier.fillMaxSize()) {
        val h = a.h
        val top = a.top
        val left = a.cx - a.w / 2f
        val right = a.cx + a.w / 2f
        val tau = 2f * PI.toFloat()
        when (kind) {
            1 -> { // Uzanan kara kedi: gövde adanın üst kenarında, kafa sağda, kuyruk sallanır, gözler kırpışır
                val black = Color(0xFF141414).copy(alpha = vis)
                val bx = a.cx + a.w * 0.08f
                val bodyW = h * 1.5f; val bodyH = h * 0.55f
                val by = top - bodyH * 0.6f
                // kuyruk
                val sway = sin(tau * t.value) * h * 0.25f
                val tail = Path().apply {
                    moveTo(bx - bodyW / 2f + h * 0.1f, by + bodyH * 0.5f)
                    cubicTo(bx - bodyW / 2f - h * 0.4f, by + bodyH * 0.3f, bx - bodyW / 2f - h * 0.5f, by - h * 0.4f + sway,
                        bx - bodyW / 2f - h * 0.2f, by - h * 0.55f + sway)
                }
                drawPath(tail, black, style = Stroke(h * 0.13f, cap = StrokeCap.Round))
                drawOval(black, Offset(bx - bodyW / 2f, by), Size(bodyW, bodyH))
                // kafa
                val hx = bx + bodyW / 2f - h * 0.05f
                val hy = by + bodyH * 0.25f
                val hr = h * 0.36f
                drawCircle(black, hr, Offset(hx, hy))
                val earL = Path().apply { moveTo(hx - hr * 0.85f, hy - hr * 0.3f); lineTo(hx - hr * 0.6f, hy - hr * 1.35f); lineTo(hx - hr * 0.05f, hy - hr * 0.8f); close() }
                val earR = Path().apply { moveTo(hx + hr * 0.05f, hy - hr * 0.8f); lineTo(hx + hr * 0.6f, hy - hr * 1.35f); lineTo(hx + hr * 0.85f, hy - hr * 0.3f); close() }
                drawPath(earL, black); drawPath(earR, black)
                val blink = if ((t.value * 4f) % 1f > 0.93f) 0.15f else 1f
                for (dx in listOf(-0.38f, 0.38f)) {
                    val e = Offset(hx + dx * hr, hy - hr * 0.05f)
                    drawOval(Color.White.copy(alpha = vis), Offset(e.x - hr * 0.22f, e.y - hr * 0.26f * blink), Size(hr * 0.44f, hr * 0.52f * blink))
                    drawCircle(Color(0xFF111111).copy(alpha = vis), hr * 0.12f * blink.coerceAtLeast(0.3f), Offset(e.x + hr * 0.04f, e.y))
                }
                // ön patiler adanın kenarından sarkar
                drawOval(black, Offset(hx - hr * 0.6f, top - h * 0.1f), Size(h * 0.22f, h * 0.3f))
                drawOval(black, Offset(hx + hr * 0.1f, top - h * 0.1f), Size(h * 0.22f, h * 0.3f))
            }
            else -> { // Kulaklar: adanın iki üst köşesinde, ada renginde
                val body = Color(0xFF000000).copy(alpha = vis)
                val inner = Color(0xFFFFA8C5).copy(alpha = vis)
                val wiggle = sin(tau * t.value * 2f) * 0.06f
                for (side in listOf(-1f, 1f)) {
                    val ex = if (side < 0) left + h * 0.55f else right - h * 0.55f
                    if (kind == 2) {
                        rotate(side * (8f + wiggle * 60f), Offset(ex, top + h * 0.15f)) {
                            val ear = Path().apply { moveTo(ex - h * 0.32f, top + h * 0.2f); lineTo(ex + side * h * 0.05f, top - h * 0.5f); lineTo(ex + h * 0.32f, top + h * 0.2f); close() }
                            val ein = Path().apply { moveTo(ex - h * 0.17f, top + h * 0.1f); lineTo(ex + side * h * 0.04f, top - h * 0.3f); lineTo(ex + h * 0.17f, top + h * 0.1f); close() }
                            drawPath(ear, body); drawPath(ein, inner)
                        }
                    } else {
                        drawCircle(body, h * 0.3f, Offset(ex, top - h * 0.02f))
                        drawCircle(inner, h * 0.15f, Offset(ex, top - h * 0.02f))
                    }
                }
            }
        }
    }
}

/** Adadan iplerle sarkan, hafifçe sallanan süsler. Ters adada yukarı doğru uzanır. */
@Composable
private fun HangDecor(kind: Int, a: DecorAnchor, vis: Float) {
    val tr = rememberInfiniteTransition(label = "hang")
    val t = tr.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "hangT")
    Canvas(Modifier.fillMaxSize()) {
        val h = a.h
        val dir = if (a.inverted) -1f else 1f
        val edge = if (a.inverted) a.top else a.top + h
        val tau = 2f * PI.toFloat()
        val rope = Color(0xFF2B2B2B).copy(alpha = vis)
        when (kind) {
            1 -> { // Salıncakta civciv
                // Gerçek salıncak gibi: her ip adadaki KENDİ bağlantı noktasından sallanır (paralelkenar).
                // Eskiden iki ip ortadaki tek nokta etrafında döndürülüyordu; sallanırken bir ipin üst ucu adadan
                // aşağı kopup görünüyordu. Artık üst uçlar adaya sabit, oturak yatay kalır.
                val angDeg = sin(tau * t.value) * 16f
                val rad = angDeg * PI.toFloat() / 180f
                val ropeLen = h * 2.1f
                val sx = h * 0.45f
                val dx = sin(rad) * ropeLen * -dir      // ters adada (yukarı doğru) aynalanır
                val dy = cos(rad) * ropeLen * dir
                val ropeW = h * 0.05f
                for (side in listOf(-1f, 1f)) {
                    val top0 = Offset(a.cx + side * sx, edge)
                    val end = Offset(top0.x + dx, edge + dy)
                    drawLine(rope, top0, end, ropeW, cap = StrokeCap.Round)
                    // Adaya bağlandığı yerde küçük halka: ip ucu kesik görünmesin
                    drawCircle(rope, ropeW * 1.3f, top0)
                }
                val seatX = a.cx + dx
                val seatY = edge + dy
                drawRoundRect(Color(0xFF8B5A2B).copy(alpha = vis), Offset(seatX - sx - h * 0.1f, seatY - h * 0.05f),
                    Size(sx * 2 + h * 0.2f, h * 0.14f), CornerRadius(h * 0.07f))
                // Civciv oturakta: salınımla hafifçe öne arkaya yatar
                rotate(angDeg * 0.35f, Offset(seatX, seatY)) {
                    translate(seatX, seatY - h * 0.38f) { drawPetAlpha(2, h * 0.95f, vis) }
                }
            }
            else -> { // Yıldızlar / yılbaşı topları / kalpler: üç ip, farklı uzunlukta
                val xs = listOf(-0.7f, 0f, 0.7f)
                val ls = listOf(1.3f, 2.1f, 1.6f)
                xs.forEachIndexed { i, fx ->
                    val pivot = Offset(a.cx + fx * h, edge)
                    val ang = sin(tau * (t.value + i * 0.23f)) * 10f
                    val end = Offset(pivot.x, edge + ls[i] * h * dir)
                    rotate(ang, pivot) {
                        drawLine(rope, pivot, end, h * 0.04f)
                        when (kind) {
                            2 -> star(end, h * 0.3f, Color(0xFFFFC83D).copy(alpha = vis))
                            3 -> {
                                val col = listOf(Color(0xFFE53935), Color(0xFF2E7D32), Color(0xFFFFB300))[i].copy(alpha = vis)
                                drawRect(Color(0xFFBDBDBD).copy(alpha = vis), Offset(end.x - h * 0.08f, end.y - h * 0.04f), Size(h * 0.16f, h * 0.1f))
                                drawCircle(col, h * 0.24f, Offset(end.x, end.y + h * 0.28f * dir))
                                drawCircle(Color.White.copy(alpha = 0.5f * vis), h * 0.06f, Offset(end.x - h * 0.08f, end.y + h * 0.2f * dir))
                            }
                            else -> heart(Offset(end.x, end.y + h * 0.18f * dir), h * 0.34f, Color(0xFFFF4F8B).copy(alpha = vis))
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawPetAlpha(kind: Int, s: Float, alpha: Float) {
    if (alpha >= 0.99f) drawPet(kind, s) else {
        // Basit çözüm: solarken küçülerek kaybolur (Canvas katman alfa'sı yerine)
        scale(alpha.coerceIn(0.01f, 1f), alpha.coerceIn(0.01f, 1f), Offset.Zero) { drawPet(kind, s) }
    }
}

private fun DrawScope.star(c: Offset, r: Float, color: Color) {
    val p = Path()
    for (i in 0 until 10) {
        val a = -PI.toFloat() / 2f + i * PI.toFloat() / 5f
        val rr = if (i % 2 == 0) r else r * 0.45f
        val pt = Offset(c.x + cos(a) * rr, c.y + r * 0.9f + sin(a) * rr)
        if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    p.close()
    drawPath(p, color)
}

private fun DrawScope.heart(c: Offset, s: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + s * 0.35f)
        cubicTo(c.x - s * 0.9f, c.y - s * 0.2f, c.x - s * 0.35f, c.y - s * 0.75f, c.x, c.y - s * 0.3f)
        cubicTo(c.x + s * 0.35f, c.y - s * 0.75f, c.x + s * 0.9f, c.y - s * 0.2f, c.x, c.y + s * 0.35f)
        close()
    }
    drawPath(p, color)
}

// ---------------------------------------------------------------- Mürekkep damlası

private class DripSpec(val x: Float, val width: Float, val len: Float, val delay: Float, val long: Boolean)

/**
 * Adanın alt kenarından koyu sıvı damlaları sarkar: kısa olanlar biraz uzayıp geri çekilir, uzun olan ekranın altına
 * kadar akıp ince bir iz bırakır; sonra iz incelip kaybolur. [everySec] aralıkla tekrarlar.
 */
@Composable
private fun Drip(a: DecorAnchor, color: Color, everySec: Float, active: Boolean) {
    val prog = remember { Animatable(0f) }
    var drips by remember { mutableStateOf<List<DripSpec>>(emptyList()) }
    val dirUp = a.inverted
    LaunchedEffect(active, a.w, a.h, everySec) {
        if (!active) { prog.snapTo(0f); drips = emptyList(); return@LaunchedEffect }
        val rnd = kotlin.random.Random(System.nanoTime())
        while (true) {
            val r = a.h / 2f
            val n = 3 + rnd.nextInt(3)
            val span = (a.w - 2 * r).coerceAtLeast(a.h)
            val room = if (dirUp) a.top else a.screenH - (a.top + a.h)
            val longIdx = rnd.nextInt(n)
            drips = List(n) { i ->
                val long = i == longIdx
                DripSpec(
                    x = a.cx - span / 2f + span * (i + 0.2f + rnd.nextFloat() * 0.6f) / n,
                    width = a.h * (0.16f + rnd.nextFloat() * 0.14f) * (if (long) 1.15f else 1f),
                    len = if (long) room else a.h * (0.5f + rnd.nextFloat() * 1.6f),
                    delay = rnd.nextFloat() * 0.18f,
                    long = long,
                )
            }
            prog.snapTo(0f)
            prog.animateTo(1f, tween(4200, easing = LinearEasing))
            drips = emptyList()
            delay((everySec.coerceIn(2f, 60f) * 1000f).toLong())
        }
    }
    if (drips.isEmpty()) return
    val path = remember { Path() }
    Canvas(Modifier.fillMaxSize()) {
        val p = prog.value
        val edge = if (dirUp) a.top else a.top + a.h
        val sgn = if (dirUp) -1f else 1f
        val dark = lerp(color, Color.Black, 0.45f)
        val light = lerp(color, Color.White, 0.22f)

        // Adanın alt yüzünü kaplayan ince sıvı tabakası: damlalar bunun içinden doğar (sıvı adaya yapışık görünür)
        val r = a.h / 2f
        val lipT = (p / 0.25f).coerceIn(0f, 1f) * (1f - ((p - 0.85f) / 0.15f).coerceIn(0f, 1f))
        if (lipT > 0f) {
            val lx0 = a.cx - a.w / 2f + r * 0.55f
            val lx1 = a.cx + a.w / 2f - r * 0.55f
            val th = a.h * 0.07f * lipT
            path.reset()
            path.moveTo(lx0, edge)
            path.cubicTo(lx0 + r * 0.4f, edge, lx0 + r * 0.5f, edge + sgn * th, lx0 + r, edge + sgn * th)
            path.lineTo(lx1 - r, edge + sgn * th)
            path.cubicTo(lx1 - r * 0.5f, edge + sgn * th, lx1 - r * 0.4f, edge, lx1, edge)
            path.close()
            drawPath(path, color.copy(alpha = color.alpha * lipT))
        }

        for ((idx, d) in drips.withIndex()) {
            val local = ((p - d.delay) / (1f - d.delay)).coerceIn(0f, 1f)
            if (local <= 0f) continue
            // 0..0.6: önce yavaş sarkar sonra hızlanır (koyu, yapışkan sıvı).
            // Kısa damla 0.6..1 arasında yüzey geriliminle geri çekilir; uzun damla ekranın altına kadar akar, ardında incelen bir iz bırakır.
            val grow = (local / 0.6f).coerceIn(0f, 1f)
            val ease = grow * grow * (3f - 2f * grow) * grow          // yavaş başlar, hızlanır
            val back = ((local - 0.6f) / 0.4f).coerceIn(0f, 1f)
            val backE = back * back * (3f - 2f * back)
            val len = if (d.long) d.len * ease else d.len * ease * (1f - backE)
            val ws = d.width * (if (d.long) 1f - 0.7f * backE else 1f - 0.3f * backE)   // akış kalınlığı
            var rb = d.width * (0.75f + 0.45f * ease)                                     // uçtaki damla yarıçapı
            if (!d.long) rb *= (1f - 0.55f * backE)
            val alpha = if (d.long) 1f - backE * backE else 1f - ((local - 0.9f) / 0.1f).coerceIn(0f, 1f)
            if (alpha <= 0.01f) continue
            val flare = d.width * 1.9f                                                     // adaya yapıştığı yerde genişleme
            val seed = idx * 1.7f + d.x * 0.013f
            val tip = len.coerceAtLeast(rb * 0.4f)
            val tearTop = tip - rb * 2.6f

            // Profil: her y (adadan uzaklık) için yarım genişlik; akış + adaya yapışma kavisi + damla ucu
            fun half(y: Float): Float {
                val stream = ws / 2f * (1f + 0.10f * sin(y / (a.h * 0.32f) + seed))
                val fillet = (flare - ws) / 2f * kotlin.math.exp(-y / (ws * 1.1f + 0.001f))
                var hw = stream + fillet
                if (y >= tearTop) {
                    val tear = if (y <= tip) {
                        val u = ((y - tearTop) / (tip - tearTop).coerceAtLeast(0.001f)).coerceIn(0f, 1f)
                        rb * Math.pow(sin(u * PI / 2.0), 1.4).toFloat()
                    } else {
                        val v = (y - tip) / rb
                        if (v >= 1f) 0f else rb * kotlin.math.sqrt(1f - v * v)
                    }
                    hw = max(hw, tear)
                }
                return hw
            }

            val bottom = tip + rb
            // Örnek noktaları: akış boyunca seyrek, damla ucunda sık (uç yuvarlak kalsın)
            val splitY = tearTop.coerceIn(0f, bottom)
            val nA = 22
            val nB = 30
            fun sampleY(i: Int): Float = if (i <= nA) splitY * i / nA else splitY + (bottom - splitY) * (i - nA) / nB
            val n = nA + nB
            path.reset()
            // sol kenar: yukarıdan aşağı
            for (i in 0..n) {
                val y = sampleY(i)
                val px = d.x - half(y)
                if (i == 0) path.moveTo(px, edge - sgn * 1f) else path.lineTo(px, edge + sgn * y)
            }
            // sağ kenar: aşağıdan yukarı
            for (i in n downTo 0) {
                val y = sampleY(i)
                path.lineTo(d.x + half(y), if (i == 0) edge - sgn * 1f else edge + sgn * y)
            }
            path.close()

            // Hacim: kenarlar koyu, ortası hafif açık (silindir / küre gibi)
            val span = max(rb, flare / 2f)
            val brush = Brush.horizontalGradient(
                0f to dark.copy(alpha = color.alpha * alpha),
                0.30f to color.copy(alpha = color.alpha * alpha),
                0.42f to light.copy(alpha = color.alpha * alpha),
                0.62f to color.copy(alpha = color.alpha * alpha),
                1f to dark.copy(alpha = color.alpha * alpha),
                startX = d.x - span, endX = d.x + span,
            )
            drawPath(path, brush)

            // Işık yansımaları: damlanın üst-sol tarafında parlak nokta, akışın sol kenarında ince parlama
            if (len > rb * 0.6f) {
                val bc = Offset(d.x - rb * 0.32f, edge + sgn * (tip - rb * 0.25f))
                drawCircle(Color.White.copy(alpha = 0.55f * alpha), rb * 0.22f, bc)
                drawCircle(Color.White.copy(alpha = 0.18f * alpha), rb * 0.42f, bc)
                if (tearTop > ws * 2f) {
                    drawLine(
                        Color.White.copy(alpha = 0.16f * alpha),
                        Offset(d.x - ws * 0.18f, edge + sgn * ws * 1.5f),
                        Offset(d.x - ws * 0.18f, edge + sgn * tearTop),
                        (ws * 0.16f).coerceAtLeast(1f), cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}
