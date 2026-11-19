package com.example.dynamicisland

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ---------------------------------------------------------------- Gökkuşağı modu
// Tüm ada parçaları aynı saatten beslenir; bu yüzden renkler birbiriyle senkron akar.

private var rainbowCache: List<Color>? = null
private var rainbowVer = -1
private val RAINBOW_STOPS: List<Color>
    get() {
        val v = Pal.version                       // palet değişince önbellek yenilenir (Renk düzenleyici)
        val c = rainbowCache
        if (c != null && rainbowVer == v) return c
        val n = listOf(
            Pal.c("rb_1"), Pal.c("rb_2"), Pal.c("rb_3"), Pal.c("rb_4"),
            Pal.c("rb_5"), Pal.c("rb_6"), Pal.c("rb_7"), Pal.c("rb_1"),   // başa dönüşte kesik olmasın
        )
        rainbowCache = n; rainbowVer = v
        return n
    }

/** Bir gökkuşağı görünümü: akış fazı (0..1, yalnızca çizim fazında okunur) + saydamlık. */
@Stable
class RainbowStyle(val phase: State<Float>, val alpha: Float)

/** Ortak faz saati: tüm örnekler aynı kare zamanından hesapladığı için senkron kalır. Kapalıyken hiçbir şey çalışmaz. */
@Composable
fun rememberRainbowPhase(enabled: Boolean, periodMs: Long = 5000L): State<Float> {
    val phase = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameMillis { ms ->
                if (ms - last >= 33) {            // ~30 fps yeter
                    last = ms
                    phase.floatValue = (ms % periodMs).toFloat() / periodMs
                }
            }
        }
    }
    return phase
}

/** Ayarlarda gökkuşağı kapalıysa null döner; böylece çağıran yerler "null ise normal renk" diyebilir. */
@Composable
fun rememberRainbowStyle(cfg: IslandSettings): RainbowStyle? {
    val phase = rememberRainbowPhase(cfg.rainbowEnabled)
    return if (cfg.rainbowEnabled) remember(phase, cfg.rainbowAlpha) { RainbowStyle(phase, cfg.rainbowAlpha) } else null
}

/** t (0..1, döngüsel) noktasındaki gökkuşağı rengi. */
fun rainbowColorAt(t: Float, alpha: Float = 1f): Color {
    val n = RAINBOW_STOPS.size - 1
    val x = (((t % 1f) + 1f) % 1f) * n
    val i = x.toInt().coerceIn(0, n - 1)
    return lerp(RAINBOW_STOPS[i], RAINBOW_STOPS[i + 1], x - i).copy(alpha = alpha)
}

/** Yatayda akan, kesintisiz tekrarlanan gökkuşağı fırçası. periodPx = bir tam gökkuşağının genişliği. */
fun rainbowBrush(phase: Float, periodPx: Float, alpha: Float = 1f): Brush {
    val p = periodPx.coerceAtLeast(1f)
    val shift = (((phase % 1f) + 1f) % 1f) * p
    return Brush.horizontalGradient(
        colors = RAINBOW_STOPS.map { it.copy(alpha = alpha) },
        startX = shift, endX = shift + p, tileMode = TileMode.Repeated,
    )
}

/**
 * Elemanın çizdiği her şeyi (yazı, ikon, çubuk) gökkuşağına boyar; şeklini ve kendi saydamlığını korur.
 * style == null ise hiçbir şey yapmaz.
 */
fun Modifier.rainbowFill(style: RainbowStyle?, minPeriod: Dp = 120.dp): Modifier =
    if (style == null) this else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawRect(
                rainbowBrush(style.phase.value, maxOf(size.width, minPeriod.toPx()), style.alpha),
                blendMode = BlendMode.SrcIn,
            )
        }
