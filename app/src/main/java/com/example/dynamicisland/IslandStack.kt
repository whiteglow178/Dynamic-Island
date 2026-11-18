package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Yığın (alt alta) görünümü: kartlar arası boşluk, en az kart sayısı, kendiliğinden kapanma süresi. */
const val STACK_GAP_DP = 8f
const val STACK_MIN_ITEMS = 2
const val STACK_HOLD_MS = 9_000L
const val STACK_CLOSE_MS = 520L

fun stackCardWidthDp(c: IslandContent, cfg: IslandSettings, screenWDp: Float): Float = when (c) {
    is IslandContent.ScreenRec -> recExpandedWidthDp(cfg, screenWDp)
    is IslandContent.Status -> if (c.kind == StatusKind.Charging) CHARGE_PILL_W_DP else cfg.expandedWidthDp
    else -> cfg.expandedWidthDp
}

/** Pencere genişliği: en geniş kart kadar. */
fun stackWidthDp(items: List<IslandContent>, cfg: IslandSettings, screenWDp: Float): Float =
    items.maxOfOrNull { stackCardWidthDp(it, cfg, screenWDp) } ?: cfg.expandedWidthDp

/** Pencere yüksekliği: kartların açık hal yükseklikleri + aralarındaki boşluklar. */
fun stackHeightDp(items: List<IslandContent>, cfg: IslandSettings): Float =
    items.sumOf { expandedHeightDpFor(it, cfg).toDouble() }.toFloat() + STACK_GAP_DP * (items.size - 1).coerceAtLeast(0)

private fun stackKeyOf(c: IslandContent): String = when (c) {
    is IslandContent.Media -> "media"
    is IslandContent.ScreenRec -> "rec"
    is IslandContent.Call -> "call"
    is IslandContent.Shazam -> "shazam"
    is IslandContent.Status -> "status-" + c.kind.name
    is IslandContent.Notif -> "notif"
    else -> c::class.java.simpleName
}

/** Kartın kapalı (ada) genişliği: ilk kart adadan bu boyuttan büyüyerek açılır. */
private fun stackCompactWidthDp(c: IslandContent, cfg: IslandSettings): Float = when {
    cfg.dotMode -> cfg.compactWidthDp
    c is IslandContent.Call -> maxOf(cfg.compactWidthDp, CALL_COMPACT_W_DP)
    c is IslandContent.ScreenRec && (c.phase == RecPhase.Countdown || c.phase == RecPhase.Processing) -> maxOf(cfg.compactWidthDp, CALL_COMPACT_W_DP)
    else -> cfg.compactWidthDp
}

/**
 * Birden fazla olay varken adaya basınca açılan görünüm: her olay kendi açık paneliyle (müzik, arama, ekran kaydı...)
 * alt alta bir kart olarak dizilir. İlk (en yeni) kart, adanın kendisi gibi küçük halinden büyüyerek açılır; diğer kartlar
 * onun altından kayarak çıkar. Kapanırken aynı yol tersine, daha yavaş oynar.
 */
@Composable
fun IslandStackPanel(
    items: List<IslandContent>,
    closing: Boolean,
    cfg: IslandSettings,
    lyrics: LyricsState,
    onClose: () -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    onOpenMusic: () -> Unit,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onSpeaker: () -> Unit,
    onRecStop: () -> Unit,
    onShazamStop: () -> Unit,
) {
    val screenWDp = LocalConfiguration.current.screenWidthDp.toFloat()
    val fullProgress = remember { IslandProgress(mutableStateOf(1f), mutableStateOf(1f)) }
    // İlk kartın açılma ilerlemesi: 0 = kapalı ada, 1 = tam kart
    val lead = remember { Animatable(0f) }
    val leadProgress = remember { IslandProgress(lead.asState(), lead.asState()) }
    LaunchedEffect(closing) {
        if (closing) {
            if (cfg.animEnabled) lead.animateTo(0f, tween((STACK_CLOSE_MS - 60L).toInt(), easing = FastOutSlowInEasing))
            else lead.snapTo(0f)
        } else {
            delay(30L)      // pencere önce büyüsün
            if (cfg.animEnabled) lead.animateTo(1f, spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow))
            else lead.snapTo(1f)
        }
    }
    val lyricsOn = cfg.lyricsEnabled && cfg.lyricsInPanel
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(STACK_GAP_DP.dp),
    ) {
        items.forEachIndexed { index, c ->
            key(stackKeyOf(c)) {
                val first = index == 0
                val p = remember { Animatable(if (first) 1f else 0f) }
                if (!first) {
                    LaunchedEffect(closing) {
                        if (closing) p.animateTo(0f, tween(220, easing = FastOutSlowInEasing))
                        else {
                            delay(90L + index * 45L)
                            if (cfg.animEnabled) p.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow))
                            else p.snapTo(1f)
                        }
                    }
                }
                val media = c as? IslandContent.Media
                val frame = rememberLyricFrame(
                    media, lyrics, cfg.lyricsOffsetMs.toLong(),
                    active = lyricsOn && media != null && lyrics is LyricsState.Ready, fine = cfg.lyricsFillEnabled,
                )
                val w = stackCardWidthDp(c, cfg, screenWDp).dp
                val h = expandedHeightDpFor(c, cfg).dp
                val geo = Geo(cw = stackCompactWidthDp(c, cfg).dp, ch = cfg.compactHeightDp.dp, ew = w, eh = h)
                Box(
                    Modifier.graphicsLayer {
                        if (!first) {
                            val v = p.value.coerceIn(0f, 1f)
                            alpha = v
                            val sc = 0.92f + 0.08f * v
                            scaleX = sc; scaleY = sc
                            transformOrigin = TransformOrigin(0.5f, 0f)
                            translationY = -(1f - v) * 28.dp.toPx()
                        }
                    },
                ) {
                    IslandPill(
                        content = c, progress = if (first) leadProgress else fullProgress, geo = geo, cfg = cfg,
                        lyrics = lyrics, frame = frame, compactLyrics = false,
                        onTap = onClose, onCommand = onCommand, onOutput = onOutput,
                        onAnswer = onAnswer, onDecline = onDecline, onSpeaker = onSpeaker,
                        onOpenMusic = onOpenMusic,
                        onSwipe = { dir -> if (dir < 0) onClose() },       // yukarı kaydır = hepsini kapat
                        artFlip = null,
                        onRecStop = onRecStop, onShazamStop = onShazamStop,
                    )
                }
            }
        }
    }
}
