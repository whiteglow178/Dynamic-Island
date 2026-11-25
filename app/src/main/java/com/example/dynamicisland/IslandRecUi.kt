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

// ---------------------------------------------------------------- Ekran kaydı
private val RecRed: Color get() = Pal.c("rec_red")

/** Kırmızı kayıt noktası; blink açıksa yavaşça yanıp söner, dim açıksa soluk durur. */
@Composable
internal fun RecDot(blink: Boolean, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false) {
    val t = rememberInfiniteTransition(label = "recBlink")
    val pulse = t.animateFloat(
        1f, 0.25f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "recPulse",
    )
    Box(
        modifier.size(size)
            .graphicsLayer { alpha = if (blink) pulse.value else if (dim) 0.35f else 1f }
            .background(RecRed, CircleShape)
    )
}

@Composable
internal fun RecSpinner(size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "recSpin")
    val rot = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "recRot")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = rot.value }) {
        val sw = 2.5.dp.toPx()
        drawArc(
            Pal.c("rec_ring"), startAngle = -90f, sweepAngle = 290f, useCenter = false,
            topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun RecCountRing(count: Int, size: Dp, modifier: Modifier = Modifier) {
    // Her rakamda halka dolu başlar, saniye boyunca boşalır
    val sweep = remember(count) { Animatable(360f) }
    LaunchedEffect(count) { sweep.snapTo(360f); sweep.animateTo(0f, tween(1000, easing = LinearEasing)) }
    Canvas(modifier.size(size)) {
        val sw = 2.5.dp.toPx()
        drawArc(
            Pal.c("rec_ring"), startAngle = -90f, sweepAngle = sweep.value, useCenter = false,
            topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

@Composable
internal fun ScreenRecCompact(c: IslandContent.ScreenRec, modifier: Modifier = Modifier, iosDot: Boolean = false) {
    Box(modifier) {
        when (c.phase) {
            // iPhone gibi kırmızı nokta açıksa nokta adanın içinde sağda çizilir (IslandUi.kt), burada çizilmez
            RecPhase.Recording -> if (!iosDot) RecDot(true, 9.dp, Modifier.align(Alignment.CenterStart).padding(start = 14.dp))
            RecPhase.Saved -> RecDot(false, 9.dp, Modifier.align(Alignment.CenterStart).padding(start = 14.dp))
            RecPhase.Countdown -> {
                RecCountRing(c.count, 17.dp, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                Text(
                    c.count.toString(), color = Pal.c("rec_text"), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                )
            }
            RecPhase.Processing -> {
                RecDot(false, 9.dp, Modifier.align(Alignment.CenterStart).padding(start = 14.dp), dim = true)
                RecSpinner(17.dp, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
            }
        }
    }
}

private fun formatRecTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Açık ada: [nokta + süre / Ekran Kaydediliyor]  ...  [durdur düğmesi]  ya da  [küçük resim] (kaydedildi). */
@Composable
internal fun ScreenRecContent(c: IslandContent.ScreenRec, onStop: () -> Unit) {
    val rec = c.phase == RecPhase.Recording
    var now by remember(c.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    if (rec) LaunchedEffect(c.startedAt) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val ms = if (rec) (now - c.startedAt).coerceAtLeast(0L) else c.frozenMs
    Row(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecDot(blink = rec, size = 10.dp)
                if (rec || ms > 0L) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatRecTime(ms), color = RecRed, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                }
            }
            Text(
                if (c.phase == RecPhase.Saved) "Kayıt galeriye kaydedildi" else "Ekran Kaydediliyor",
                color = Pal.c("rec_text"), fontWeight = FontWeight.Medium, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.phase == RecPhase.Saved) {
            val th = c.thumb
            if (th != null) {
                Image(th, null, Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
            } else {
                // Önizleme bildirimden gelmediyse boş kalmasın
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Pal.c("rec_play_bg")), contentAlignment = Alignment.Center) {
                    Text("▶", color = Pal.c("rec_play_icon"), fontSize = 18.sp)
                }
            }
        } else if (rec) {
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .border(2.5.dp, Pal.c("rec_ring"), CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStop),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(20.dp).background(RecRed, RoundedCornerShape(6.dp))) }
        }
    }
}
