package com.example.dynamicisland

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Shazam sonucu: açık ada yüksekliği (dp). Kapak + iki satır yazı sığar. */
const val SHAZAM_PILL_H_DP = 88f

// ---------------------------------------------------------------- Shazam
internal class ShazamHold { var foundAt = 0L }

private class ShazamSpark(val angle: Float, val speed: Float, val rad: Float, val delay: Float, val life: Float)

private val ShazamBlueTop = Color(0xFF33AEFF)
private val ShazamBlueBottom = Color(0xFF0A68FF)

private val ShazamBlue = Color(0xFF0088FF)

/** Resmi Shazam "S" işareti: iki kalın, yuvarlak uçlu kanca; ikincisi birincinin 180° döndürülmüşü. */
private fun shazamGlyphPath(topLeft: Offset, side: Float): Path {
    fun pt(u: Float, v: Float, flip: Boolean) =
        if (flip) Offset(topLeft.x + (1f - u) * side, topLeft.y + (1f - v) * side) else Offset(topLeft.x + u * side, topLeft.y + v * side)
    val r = 0.1759f * side
    val path = Path()
    for (rot in 0..1) {
        val flip = rot == 1
        val p0 = pt(0.4148f, 0.2130f, flip)       // üst uç (yuvarlak)
        val c = pt(0.4148f, 0.4630f, flip)        // kancanın yay merkezi
        val p2 = pt(0.5861f, 0.5398f, flip)       // iç uç (yuvarlak)
        path.moveTo(p0.x, p0.y)
        path.arcTo(Rect(c, r), 225f + rot * 180f, -180f, false)
        path.lineTo(p2.x, p2.y)
    }
    return path
}

private fun DrawScope.drawShazamGlyph(
    topLeft: Offset, side: Float, color: Color, alpha: Float,
    blendMode: androidx.compose.ui.graphics.BlendMode = androidx.compose.ui.graphics.BlendMode.SrcOver,
) {
    drawPath(shazamGlyphPath(topLeft, side), color, alpha = alpha, style = Stroke(width = 0.1157f * side, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = blendMode)
}

/**
 * Küçük adadaki Shazam logosu: mavi "S" işaretinin İÇİNDE dönen turkuaz ışık (yalnızca logonun şekliyle kesilir),
 * ışığın çevresinde çok hafif bir parıltı. Saat yönünde döner.
 */
@Composable
internal fun ShazamGlyphSweep(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "shzGlyph")
    val ang: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "shzGlyphAng",
    )
    Canvas(modifier) {
        val side = size.minDimension
        val c = Offset(size.width / 2f, size.height / 2f)
        val cyan = Color(0xFF3CE6FF)
        val sw = Stroke(width = 0.1157f * side, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // 1) mavi logo
        drawShazamGlyph(Offset.Zero, side, ShazamBlue, 1f)
        // 2) turkuaz süpürme: maske / blend yok; ışık doğrudan logonun çizgisine fırça olarak sürülür,
        //    bu yüzden logonun dışına taşması mümkün değil. Merkez (0,0) olacak şekilde ortalanmış yol çizilir;
        //    yol -ang kadar önceden döndürülür, tuval +ang döner: logo sabit kalır, fırçanın ışığı döner.
        val glyph = shazamGlyphPath(Offset(-side / 2f, -side / 2f), side)
        val unrot = Path().apply { addPath(glyph) }
        unrot.transform(androidx.compose.ui.graphics.Matrix().apply { rotateZ(-ang) })
        withTransform({ translate(c.x, c.y); rotate(ang, Offset.Zero) }) {
            drawPath(
                unrot,
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0f to cyan.copy(alpha = 1f),
                        0.02f to cyan.copy(alpha = 0f),
                        0.62f to cyan.copy(alpha = 0f),
                        0.82f to cyan.copy(alpha = 0.50f),
                        1f to cyan.copy(alpha = 1f),
                    ),
                    center = Offset.Zero,
                ),
                style = sw,
            )
        }
    }
}

/**
 * Shazam logosunun mavi dairesinin içinde dönen turkuaz ışık süpürmesi (logonun ARKASINDA):
 * merkezden kenara doğru güçlenen, ön kenarı keskin, arkası yavaşça sönen bir dilim (saat yönünde döner).
 * Ayrıca kenara yakın yerlerde göz kırpan çok küçük açık mavi parıltılar vardır.
 */
@Composable
private fun ShazamOrbit(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "shzOrbit")
    val ang: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "shzOrbitAng",
    )
    val ph: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "shzTwinkle",
    )
    // açı (derece), yarıçap oranı, titreşim sıklığı (tam sayı: döngü kesintisiz), faz
    val sparks = remember {
        listOf(
            floatArrayOf(205f, 0.80f, 2f, 0.0f), floatArrayOf(250f, 0.86f, 1f, 1.7f), floatArrayOf(300f, 0.78f, 3f, 3.1f),
            floatArrayOf(335f, 0.90f, 2f, 4.4f), floatArrayOf(20f, 0.84f, 1f, 0.9f), floatArrayOf(150f, 0.88f, 3f, 2.3f),
            floatArrayOf(110f, 0.76f, 2f, 5.2f), floatArrayOf(230f, 0.62f, 1f, 3.8f),
        )
    }
    Box(modifier) {
        // Döner dilim: sabit çizilir, katman döndürülür (her karede yeniden çizim yok)
        Canvas(
            Modifier.fillMaxSize().graphicsLayer { rotationZ = ang; alpha = 0.99f },   // alpha < 1: ayrı katman (maske için gerekli)
        ) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            val cyan = Color(0xFF22E0FF)
            drawCircle(
                brush = Brush.sweepGradient(
                    colorStops = arrayOf(
                        0f to cyan.copy(alpha = 0.95f),       // ön kenar (keskin)
                        0.015f to cyan.copy(alpha = 0f),
                        0.70f to cyan.copy(alpha = 0f),
                        0.88f to cyan.copy(alpha = 0.32f),
                        1f to cyan.copy(alpha = 0.95f),
                    ),
                    center = c,
                ),
                radius = r, center = c,
            )
            // Merkezden kenara doğru güçlenen maske: dilim ortada görünmez, kenarda parlar
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0f),
                        0.35f to Color.Black.copy(alpha = 0f),
                        0.75f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Black,
                    ),
                    center = c, radius = r,
                ),
                radius = r, center = c, blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
        // Küçük göz kırpan parıltılar
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2f, size.height / 2f)
            val r = size.minDimension / 2f
            val gr = 3.dp.toPx()
            for (sp in sparks) {
                val tw = 0.5f + 0.5f * sin(ph * sp[2] + sp[3])
                val a = tw * tw * 0.85f
                if (a < 0.03f) continue
                val rad = sp[0] * 0.0174533f
                val p = Offset(c.x + r * sp[1] * cos(rad), c.y + r * sp[1] * sin(rad))
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFF9FE9FF).copy(alpha = a), Color(0xFF9FE9FF).copy(alpha = 0f)), center = p, radius = gr,
                    ),
                    radius = gr, center = p,
                )
                drawCircle(Color.White.copy(alpha = a * 0.9f), radius = 0.7.dp.toPx(), center = p)
            }
        }
    }
}

/** Küçük adadaki Shazam ses dalgası: mavi, hareketli çubuklar. */
@Composable
internal fun ShazamWave(modifier: Modifier = Modifier, bars: Int = 5, flat: Boolean = false) {
    val tr = rememberInfiniteTransition(label = "shzWave")
    val phase: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shzPhase",
    )
    Canvas(modifier) {
        val n = bars
        val gap: Float = size.width / n
        val bw: Float = gap * 0.46f
        for (i in 0 until n) {
            val v: Float = 0.30f + 0.70f * abs(sin(phase + i * 1.15f))
            val h: Float = (size.height * v).coerceAtLeast(bw)
            val x: Float = gap * i + gap / 2f
            val color = if (flat) Color(0xFF1E7BFF) else androidx.compose.ui.graphics.lerp(ShazamBlue, Color(0xFF6BBBFF), i / (n - 1).toFloat())
            drawLine(color, Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), strokeWidth = bw, cap = StrokeCap.Round)
        }
    }
}

private fun shzSmooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

/**
 * Albüm kapağının belirmesi. [progress] 0..1:
 *  0.00-0.30  küçük beyaz ışık patlaması (parlama + ışınlar + kıvılcımlar)
 *  0.14-0.42  parlama büyüyüp SAF BEYAZ yuvarlatılmış kareye dönüşür (pembe yok)
 *  0.45-1.00  beyaz blok yavaşça şeffaflaşır; altındaki kapak bulanıktan netleşir (beyazla yıkanmış halden normale)
 *  Kıvılcımlar 0.72'ye kadar tamamen söner, sonda kenarda hiçbir şey kalmaz.
 */
@Composable
private fun ShazamCoverBurst(cover: ImageBitmap?, progress: () -> Float, modifier: Modifier = Modifier) {
    val sparks = remember {
        val r = kotlin.random.Random(7)
        List(30) {
            ShazamSpark(
                angle = r.nextFloat() * 6.2831855f, speed = 0.5f + r.nextFloat() * 0.8f,
                rad = 0.8f + r.nextFloat() * 1.5f, delay = r.nextFloat() * 0.30f, life = 0.25f + r.nextFloat() * 0.17f,
            )
        }
    }
    // Netleşirken kapağın üstünde görünen ince beyaz tanecikler (konumları sabit)
    val grain = remember {
        val r = kotlin.random.Random(23)
        List(70) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    val coverShape = remember { RoundedCornerShape(percent = 22) }

    Box(modifier, contentAlignment = Alignment.Center) {
        // Katman 0: küçük parlama, ışınlar ve parlamanın beyaz kareye dönüşmesi
        Canvas(Modifier.fillMaxSize()) {
            val t = progress().coerceIn(0f, 1f)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)
            val full = size.width * (60f / 72f)
            val burstP = (t / 0.30f).coerceIn(0f, 1f)
            val bloomP = ((t - 0.14f) / 0.28f).coerceIn(0f, 1f)

            val gr = full * (0.14f + 0.44f * burstP)
            val ga = ((0.45f + 0.55f * burstP) * (1f - bloomP)).coerceIn(0f, 1f)
            if (ga > 0.01f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = ga), Color.White.copy(alpha = ga * 0.5f), Color.Transparent),
                        center = center, radius = gr,
                    ),
                    radius = gr, center = center,
                )
            }
            val rayA = (1f - bloomP) * (0.2f + 0.8f * burstP)
            if (rayA > 0.02f) {
                val n = 22
                for (i in 0 until n) {
                    val a = i * (6.2831855f / n) + 0.15f * sin(i * 2.1f)
                    val k = 0.55f + 0.45f * abs(sin(i * 1.7f + 0.6f))
                    val r0 = full * 0.08f
                    val r1 = r0 + full * (0.10f + 0.34f * burstP) * k
                    drawLine(
                        Color.White.copy(alpha = rayA),
                        Offset(cx + r0 * cos(a), cy + r0 * sin(a)), Offset(cx + r1 * cos(a), cy + r1 * sin(a)),
                        strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
            if (bloomP > 0.01f) {
                val e = shzSmooth(bloomP)
                val side = full * (0.30f + 0.70f * e)
                val rad = side / 2f + (full * 0.22f - side / 2f) * e
                // Parıltı (glow): beyaz bloğun etrafında yumuşak halo; kapak netleştikçe söner
                val sGlow = shzSmooth((t - 0.45f) / 0.55f)
                val glowA = e * (1f - sGlow)
                if (glowA > 0.01f) {
                    val layers = 14
                    for (k in layers downTo 1) {
                        val g = k * 1.1.dp.toPx()
                        drawRoundRect(
                            color = Color.White.copy(alpha = (0.085f * glowA * (1f - k / (layers + 1f))).coerceIn(0f, 1f)),
                            topLeft = Offset(cx - side / 2f - g, cy - side / 2f - g),
                            size = Size(side + 2f * g, side + 2f * g), cornerRadius = CornerRadius(rad + g),
                        )
                    }
                }
                drawRoundRect(
                    color = Color.White.copy(alpha = e), topLeft = Offset(cx - side / 2f, cy - side / 2f),
                    size = Size(side, side), cornerRadius = CornerRadius(rad, rad),
                )
            }
        }

        // Katman 1: kapak + üstündeki beyaz örtü. Birlikte bulanıktan netleşir; dışarıdan yuvarlatılmış kareyle kesilir (kenarlar keskin kalır)
        Box(
            Modifier.size(60.dp)
                .graphicsLayer { alpha = if (progress() < 0.40f) 0f else 1f }
                .clip(coverShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.requiredSize(84.dp).graphicsLayer {
                    val t = progress().coerceIn(0f, 1f)
                    val s = shzSmooth((t - 0.45f) / 0.55f)
                    val blurDp = if (t < 0.45f) 3f else 3f + 6f * (1f - s)   // beyaz blok hafif yumuşak, kapak belirirken bulanık -> net
                    setBlurPx((blurDp * (1f - shzSmooth((s - 0.85f) / 0.15f))).dp.toPx())
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val t = progress().coerceIn(0f, 1f)
                    val s = shzSmooth((t - 0.45f) / 0.55f)
                    val full = 60.dp.toPx()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    if (cover != null) {
                        val side = min(cover.width, cover.height)
                        val sx0 = (cover.width - side) / 2
                        val sy0 = (cover.height - side) / 2
                        val d = (full * (1f + 0.25f * (1f - s))).roundToInt()   // bulanıkken biraz büyük, netleşirken yerine oturur (kenar sızıntısı olmaz)
                        drawImage(
                            cover, IntOffset(sx0, sy0), IntSize(side, side),
                            IntOffset((c.x - d / 2f).roundToInt(), (c.y - d / 2f).roundToInt()), IntSize(d, d),
                            filterQuality = FilterQuality.High,
                        )
                    } else {
                        // Kapak bildirimde yoksa: Shazam logolu mavi kare
                        drawRect(ShazamBlue)
                        drawShazamGlyph(Offset(c.x - full * 0.36f, c.y - full * 0.36f), full * 0.72f, Color.White, 1f)
                    }
                    // Beyaz örtü: kapağı beyazlatır, yavaşça şeffaflaşır
                    drawRect(Color.White.copy(alpha = (1f - s).coerceIn(0f, 1f)))
                }
            }
        }

        // Katman 2: kıvılcımlar ve tanecikler (keskin çizilir, 0.72'de tamamen biter)
        Canvas(Modifier.fillMaxSize()) {
            val t = progress().coerceIn(0f, 1f)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val full = size.width * (60f / 72f)
            for (sp in sparks) {
                val l = (t - sp.delay) / sp.life
                if (l <= 0f || l >= 1f) continue
                val eo = 1f - (1f - l) * (1f - l) * (1f - l)
                val r = full * 0.16f + sp.speed * full * 0.36f * eo
                val pos = Offset(cx + r * cos(sp.angle), cy + r * sin(sp.angle))
                drawCircle(Color.White.copy(alpha = (1f - l) * 0.95f), radius = sp.rad.dp.toPx() * (1f - 0.5f * l), center = pos)
            }
            val s = shzSmooth((t - 0.45f) / 0.55f)
            val ga = 0.8f * sin(3.1415927f * s)
            if (ga > 0.02f) {
                for ((gx, gy, gv) in grain) {
                    val a = ga * (0.35f + 0.65f * gv)
                    drawCircle(
                        Color.White.copy(alpha = a), radius = (0.7f + gv * 0.9f).dp.toPx(),
                        center = Offset(cx - full * 0.45f + gx * full * 0.9f, cy - full * 0.45f + gy * full * 0.9f),
                    )
                }
            }
        }
    }
}

/** Açık ada: [kapak (patlama animasyonu)]  [şarkı adı / sanatçı]. Yazılar bulanıktan netleşir. Apple Music etiketi yok. */
@Composable
internal fun ShazamContent(c: IslandContent.Shazam, onStop: () -> Unit = {}) {
    val found = c.phase == ShazamPhase.Found
    val burst = remember(c.title, c.artist) { Animatable(0f) }
    val reveal = remember(c.title, c.artist) { Animatable(0f) }
    LaunchedEffect(c.title, c.artist, found) {
        burst.snapTo(0f); reveal.snapTo(0f)
        if (!found) return@LaunchedEffect
        launch { delay(120); reveal.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
        delay(280)
        burst.animateTo(1f, tween(1900, easing = LinearEasing))
    }
    if (found) {
        Row(
            Modifier.fillMaxSize().padding(start = 6.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ShazamCoverBurst(c.cover, { burst.value }, Modifier.size(72.dp))
            Column(
                Modifier.weight(1f).graphicsLayer {
                    val r = reveal.value
                    alpha = 0.25f + 0.75f * r
                    setBlurPx((1f - r) * 14.dp.toPx())
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.artist.isNotBlank()) {
                    Text(c.artist, color = Color.White.copy(alpha = 0.66f), fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } else {
        // Dinleme (genişlemiş): [mavi daire + beyaz Shazam logosu]  Listening...  [mavi ses dalgası]  [X düğmesi]
        Row(
            Modifier.fillMaxSize().padding(start = 17.dp, end = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(ShazamBlueTop, ShazamBlueBottom))),
                contentAlignment = Alignment.Center,
            ) {
                ShazamOrbit(Modifier.fillMaxSize())
                Canvas(Modifier.size(46.dp)) { drawShazamGlyph(Offset.Zero, size.minDimension, Color.White, 1f) }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "Dinliyor", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            ShazamWave(Modifier.width(32.dp).height(18.dp), bars = 6, flat = true)
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(Color(0xFF14224A))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(18.dp)) {
                    val w = 2.5.dp.toPx()
                    val m = w / 2f
                    drawLine(Color(0xFF3D7BFF), Offset(m, m), Offset(size.width - m, size.height - m), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(Color(0xFF3D7BFF), Offset(size.width - m, m), Offset(m, size.height - m), strokeWidth = w, cap = StrokeCap.Round)
                }
            }
        }
    }
}
