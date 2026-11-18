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
const val STACK_CLOSE_MS = 260L

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

/**
 * Birden fazla olay varken adaya basınca açılan görünüm: her olay kendi açık paneliyle (müzik, arama, ekran kaydı...)
 * alt alta bir kart olarak dizilir. Kartlar mevcut IslandPill ile çizilir (ilerleme sabit = açık), yani düğmeler çalışır.
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
    val openProgress = remember { IslandProgress(mutableStateOf(1f), mutableStateOf(1f)) }
    val lyricsOn = cfg.lyricsEnabled && cfg.lyricsInPanel
    Column(
        Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(STACK_GAP_DP.dp),
    ) {
        items.forEachIndexed { index, c ->
            key(stackKeyOf(c)) {
                val p = remember { Animatable(0f) }
                LaunchedEffect(closing) {
                    if (closing) {
                        p.animateTo(0f, tween(200, easing = FastOutSlowInEasing))
                    } else {
                        // Pencere önce büyüsün; kartlar sırayla adadan aşağı doğru açılır
                        delay(60L + index * 50L)
                        if (cfg.animEnabled) p.animateTo(1f, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow))
                        else p.snapTo(1f)
                    }
                }
                val media = c as? IslandContent.Media
                val frame = rememberLyricFrame(
                    media, lyrics, cfg.lyricsOffsetMs.toLong(),
                    active = lyricsOn && media != null && lyrics is LyricsState.Ready, fine = cfg.lyricsFillEnabled,
                )
                val w = stackCardWidthDp(c, cfg, screenWDp).dp
                val h = expandedHeightDpFor(c, cfg).dp
                val geo = Geo(cw = cfg.compactWidthDp.dp, ch = cfg.compactHeightDp.dp, ew = w, eh = h)
                Box(
                    Modifier.graphicsLayer {
                        val v = p.value
                        alpha = v.coerceIn(0f, 1f)
                        val sc = 0.55f + 0.45f * v.coerceIn(0f, 1f)
                        scaleX = sc; scaleY = sc
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationY = -(1f - v) * (index * 80.dp.toPx())
                    },
                ) {
                    IslandPill(
                        content = c, progress = openProgress, geo = geo, cfg = cfg, lyrics = lyrics, frame = frame,
                        compactLyrics = false,
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
