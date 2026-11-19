package com.example.dynamicisland

import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Asistan açılınca ekran kenarlarında Siri / Apple Intelligence tarzı renkli parlama.
 * 1) Sağ kenarda küçük bir renk belirir, 2) büyüyerek dalga gibi tüm ekrana yayılır,
 * 3) renk yalnızca kenarlarda kalır ve kenar boyunca akarak değişmeye devam eder. Asistan kapanınca söner.
 *
 * YAPI (hem görüntü hem performans için):
 *  - Parlama, piksel piksel hesaplanan yumuşak bir düşüşle (ekran kenarından içeri doğru) bir kez, arka planda çizilir.
 *    Renk, ekranın ÇEVRE UZUNLUĞU boyunca verilir (köşeler yay uzunluğuyla), bu yüzden köşelerde çapraz şerit oluşmaz.
 *  - Renk akışı için paletin birkaç kaydırılmış kopyası hazırlanır; kareler arasında yumuşakça birbirine karıştırılır.
 *  - Tüm çizim, ekranın ~%40 çözünürlüğündeki bir katmanda yapılır ve ekrana büyütülür (parlama zaten yumuşak).
 *    Böylece dalga maskesi (DstIn) gibi işlemler çok ucuzdur ve cephe pürüzsüz çıkar.
 */
@Composable
fun ScreenEdgeGlow(active: StateFlow<Boolean>, cornerPx: Float) {
    val on by active.collectAsState()
    val density = LocalDensity.current.density
    var size by remember { mutableStateOf(IntSize.Zero) }
    var glows by remember { mutableStateOf<List<ImageBitmap>?>(null) }
    var warm by remember { mutableStateOf(false) }
    val reveal = remember { Animatable(0f) }   // 0 -> 1: sağdan büyüyen dalga
    val fade = remember { Animatable(0f) }     // genel görünürlük
    val hue = remember { Animatable(0f) }      // renk akışı (yalnızca görünürken çalışır)
    val pulse = remember { Animatable(0.9f) }  // nefes alma (yalnızca görünürken çalışır)

    val lw = max((size.width * RENDER_SCALE).roundToInt(), 2)
    val lh = max((size.height * RENDER_SCALE).roundToInt(), 2)
    val visible by remember { derivedStateOf { on || fade.value > 0.001f } }

    // Parlama resimleri asistan açılmadan ÖNCE (pencere eklenir eklenmez) arka planda hazırlanır
    LaunchedEffect(size, cornerPx, Pal.version) {
        if (size.width > 1 && size.height > 1) {
            glows = withContext(Dispatchers.Default) { buildGlowBitmaps(lw, lh, cornerPx * RENDER_SCALE, density * RENDER_SCALE) }
            warm = true; delay(350); warm = false     // görünmez ısınma çizimi (6 resim + shader + katman)
        }
    }

    LaunchedEffect(on) {
        if (on) {
            reveal.snapTo(0f)
            fade.snapTo(1f)
            reveal.animateTo(1f, tween(1250, easing = CubicBezierEasing(0.12f, 0.5f, 0.2f, 1f)))
        } else {
            fade.animateTo(0f, tween(450))
        }
    }

    // Sonsuz animasyonlar yalnızca parlama görünürken çalışır; görünmezken hiçbir kare üretilmez (pil)
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        hue.snapTo(0f); pulse.snapTo(0.9f)
        coroutineScope {
            launch { hue.animateTo(1f, infiniteRepeatable(tween(9000, easing = LinearEasing))) }
            launch { pulse.animateTo(1f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse)) }
        }
    }

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }, contentAlignment = Alignment.Center) {
        val g = glows
        // Katman, resimler hazır olur olmaz bir kez kurulur ve BELLEKTE KALIR (alpha=0 iken çizilmez, maliyeti yok).
        // Böylece tetik anında katman/kompozisyon oluşturma işi kalmaz.
        if (g != null) {
            val dens = LocalDensity.current
            val wDp = with(dens) { lw.toDp() }
            val hDp = with(dens) { lh.toDp() }
            EdgeGlowLayer(
                glows = g, reveal = reveal, fade = fade, hue = hue, pulse = pulse, warmOnly = warm && !visible,
                sx = size.width / lw.toFloat(), sy = size.height / lh.toFloat(),
                modifier = Modifier.requiredSize(wDp, hDp),
            )
        }
        // Isınma: asistan adasındaki orb'u (path + 9 duraklı gradyan + Plus karışımı) görünmez bir kez çizer.
        // Shader programları aynı süreçte paylaşıldığı için ada penceresi ilk kez çizdiğinde derleme takılması olmaz.
        if (warm && !visible) {
            Box(Modifier.requiredSize(160.dp, 56.dp).graphicsLayer { alpha = 0.02f }) { AssistantOrb(false) }
        }
    }
}

private const val RENDER_SCALE = 0.4f       // çizim katmanının ekrana oranı (büyütülerek gösterilir)
private const val GLOW_DEPTH_DP = 64f       // parlamanın ekran kenarından içeri uzanma derinliği
private const val PALETTE_SHIFTS = 6        // renk akışı için hazırlanan kaydırılmış palet sayısı

/** Parlama paleti (çevre boyunca döngüsel). Saf beyaz yok: ekran kenarı beyaza yıkanmasın. */
private var glowPaletteCache: List<Color>? = null
private var glowPaletteVer = -1
private val GLOW_PALETTE: List<Color>
    get() {
        val v = Pal.version                       // palet değişince önbellek yenilenir (Renk düzenleyici)
        val c = glowPaletteCache
        if (c != null && glowPaletteVer == v) return c
        val n = listOf(
            Pal.c("asst_1"), Pal.c("asst_2"), Pal.c("asst_3"), Pal.c("asst_4"),
            Pal.c("asst_5"), Pal.c("asst_6"), Pal.c("asst_8"), Pal.c("asst_1"),
        )
        glowPaletteCache = n; glowPaletteVer = v
        return n
    }

private fun glowPaletteColor(u: Float): Color {
    val n = GLOW_PALETTE.size - 1
    val x = (((u % 1f) + 1f) % 1f) * n
    val i = x.toInt().coerceIn(0, n - 1)
    return lerp(GLOW_PALETTE[i], GLOW_PALETTE[i + 1], x - i)
}

/**
 * Yuvarlak köşeli dikdörtgenin (ekran) kenarından içeri doğru yumuşakça sönen parlama.
 * Her piksel için: kenara uzaklık -> parlaklık, en yakın kenar noktasının çevre konumu -> renk.
 * PALETTE_SHIFTS adet, renk konumu kaydırılmış resim döndürür.
 */
private fun buildGlowBitmaps(w: Int, h: Int, cornerPx: Float, densityScaled: Float): List<ImageBitmap> {
    val depth = GLOW_DEPTH_DP * densityScaled
    val rc = min(cornerPx, min(w, h) / 2f - 1f).coerceAtLeast(1f)
    val cx = w / 2f; val cy = h / 2f
    val ax = cx - rc; val ay = cy - rc
    val lx = w - 2f * rc; val ly = h - 2f * rc
    val arc = (PI / 2.0 * rc).toFloat()
    val total = 2f * lx + 2f * ly + 4f * arc
    val halfPi = (PI / 2.0).toFloat()
    // Çevre üzerindeki başlangıç konumları (saat yönünde, sol üst yay bitişinden başlar)
    val trStart = lx
    val rightStart = lx + arc
    val brStart = lx + arc + ly
    val bottomStart = lx + 2f * arc + ly
    val blStart = 2f * lx + 2f * arc + ly
    val leftStart = 2f * lx + 3f * arc + ly
    val tlStart = 2f * lx + 3f * arc + 2f * ly

    val n = w * h
    val inten = FloatArray(n)
    val uu = FloatArray(n)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val px = x + 0.5f - cx
            val py = y + 0.5f - cy
            val qx = abs(px) - ax
            val qy = abs(py) - ay
            val dist = hypot(max(qx, 0f), max(qy, 0f)) + min(max(qx, qy), 0f) - rc
            val d = max(-dist, 0f)               // kenardan içeri derinlik (köşe dışında kalan ekran pikselleri = 0, tam parlak)
            val t = d / depth
            val i = y * w + x
            if (t >= 1f) { inten[i] = 0f; continue }
            val m = 1f - t
            inten[i] = min(1f, m.pow(2.2f) + 0.45f * m.pow(14f))   // yumuşak düşüş + kenarda ince parlak çizgi

            val s: Float = if (qx > 0f && qy > 0f) {
                // Köşe bölgesi: konum, köşe merkezi etrafındaki açıdan (yay uzunluğu) gelir => köşede kopukluk yok
                if (px > 0f && py < 0f) trStart + arc * (atan2(qx, qy) / halfPi)
                else if (px > 0f) brStart + arc * (atan2(qy, qx) / halfPi)
                else if (py > 0f) blStart + arc * (atan2(qx, qy) / halfPi)
                else tlStart + arc * (atan2(qy, qx) / halfPi)
            } else if (qx > qy) {
                if (px > 0f) rightStart + (py + ay).coerceIn(0f, ly)
                else leftStart + (ay - py).coerceIn(0f, ly)
            } else {
                if (py < 0f) (px + ax).coerceIn(0f, lx)
                else bottomStart + (ax - px).coerceIn(0f, lx)
            }
            uu[i] = s / total
        }
    }

    val lut = IntArray(512) { glowPaletteColor(it / 512f).toArgb() and 0xFFFFFF }
    return List(PALETTE_SHIFTS) { j ->
        val off = j / PALETTE_SHIFTS.toFloat()
        val pix = IntArray(n)
        for (i in 0 until n) {
            val a = inten[i]
            if (a <= 0f) continue
            val idx = (((uu[i] + off) % 1f) * 512f).toInt() and 511
            pix[i] = ((a * 255f + 0.5f).toInt() shl 24) or lut[idx]
        }
        Bitmap.createBitmap(pix, w, h, Bitmap.Config.ARGB_8888).also { it.prepareToDraw() }.asImageBitmap()   // GPU'ya önceden yükle
    }
}

@Composable
private fun EdgeGlowLayer(
    glows: List<ImageBitmap>, reveal: Animatable<Float, *>, fade: Animatable<Float, *>,
    hue: Animatable<Float, *>, pulse: Animatable<Float, *>, warmOnly: Boolean,
    sx: Float, sy: Float, modifier: Modifier,
) {
    val k = glows.size

    Canvas(
        modifier.graphicsLayer {
            scaleX = sx; scaleY = sy          // düşük çözünürlüklü katman ekrana büyütülür
            compositingStrategy = CompositingStrategy.Offscreen   // dalga maskesi yalnızca bu katmanı etkiler
            alpha = if (warmOnly) 0.02f else fade.value
        },
    ) {
        val w = size.width; val h = size.height
        val p = if (warmOnly) 0.4f else reveal.value
        val a = pulse.value

        // 1) Renk akışı: iki komşu kaydırılmış paleti toplamsal olarak karıştır
        if (warmOnly) {
            // Isınma: 6 resmin hepsini bir kez çiz ki GPU dokuları önceden yüklensin (animasyon sırasında yükleme takılması olmasın)
            for (img in glows) drawImage(img, alpha = 0.05f, blendMode = BlendMode.Plus)
        } else {
            val x = hue.value * k
            val i0 = x.toInt().coerceIn(0, k - 1)
            val tt = x - i0
            val i1 = (i0 + 1) % k
            drawImage(glows[i0], alpha = (1f - tt) * a, blendMode = BlendMode.Plus)
            drawImage(glows[i1], alpha = tt * a, blendMode = BlendMode.Plus)
        }

        if (p < 1f) {
            // 2) Dalga: merkezi sağ kenarın ortası olan büyüyen daire. Yumuşak cephe: içi görünür, dışı gizli
            val wx = w; val wy = h / 2f
            val dMax = hypot(w, h / 2f)
            val soft = dMax * 0.30f
            val r0 = soft * 0.45f
            val r = r0 + (dMax + soft * 1.05f - r0) * p
            val inner = max((r - soft) / r, 0f)
            fun f(s: Float) = inner + (1f - inner) * s
            drawRect(
                Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to Color.Black, inner to Color.Black,
                        f(0.25f) to Color.Black.copy(alpha = 0.844f),
                        f(0.5f) to Color.Black.copy(alpha = 0.5f),
                        f(0.75f) to Color.Black.copy(alpha = 0.156f),
                        1f to Color.Transparent,
                    ),
                    center = Offset(wx, wy), radius = max(r, 1f),
                ),
                blendMode = BlendMode.DstIn,
            )

            // 3) Renk dalgası: sağ kenardan çıkıp TÜM ekranı kaplayarak sola doğru geçen geniş, renkli, yumuşak bir bant.
            //    Cephede turuncu/pembe, arkasında mor/mavi akar; bant geçtikten sonra yalnızca kenar parlaması kalır.
            val wave = 0.62f * (1f - p * p) * (if (p < 0.025f) p / 0.025f else 1f)
            if (wave > 0.01f) {
                val rr = r + soft * 0.1f
                val band = soft * 1.9f
                val s0 = max((rr - band) / rr, 0f)
                fun g(s: Float) = s0 + (1f - s0) * s
                val hv = hue.value
                drawRect(
                    Brush.radialGradient(
                        0f to Color.Transparent, s0 to Color.Transparent,
                        g(0.35f) to glowPaletteColor(hv).copy(alpha = wave * 0.45f),
                        g(0.62f) to glowPaletteColor(hv + 0.12f).copy(alpha = wave * 0.85f),
                        g(0.82f) to glowPaletteColor(hv + 0.24f).copy(alpha = wave),
                        g(0.94f) to glowPaletteColor(hv + 0.36f).copy(alpha = wave * 0.65f),
                        1f to Color.Transparent,
                        center = Offset(wx, wy), radius = max(rr, 1f),
                    ),
                    blendMode = BlendMode.Plus,
                )
            }
        }
    }
}
