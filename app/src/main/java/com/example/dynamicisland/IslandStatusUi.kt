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

// ---------------------------------------------------------------- Durum (zil modu / şarj / zamanlayıcı / alarm)
private const val BELL_PATH =
    "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5" +
    "s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
internal val BellPath: Path by lazy { PathParser().parsePathString(BELL_PATH).toPath() }

internal fun statusColor(c: IslandContent.Status): Color = when (c.kind) {
    StatusKind.Ringer -> if (c.silent) Pal.c("status_ringer_silent") else Pal.c("status_ringer_vibrate")
    StatusKind.Charging -> Pal.c("status_charging")
    StatusKind.Timer -> Pal.c("status_timer")
    StatusKind.Alarm -> Pal.c("status_alarm")
    StatusKind.Camera -> Pal.c("status_camera")
    StatusKind.Mic -> Pal.c("status_mic")
    StatusKind.Volume -> Pal.c("status_text")
    StatusKind.Network, StatusKind.Airplane, StatusKind.RingVolume, StatusKind.LowBattery, StatusKind.LowBatteryCard -> sysColor(c)
}

@Composable
internal fun StatusIcon(c: IslandContent.Status, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({ scale(sc, sc, Offset.Zero) }) {
            val w = Pal.c("status_icon")
            val st = Stroke(width = 2f, cap = StrokeCap.Round)
            when (c.kind) {
                StatusKind.Ringer -> {
                    drawPath(BellPath, w)
                    if (c.silent) drawLine(w, Offset(3.5f, 3.5f), Offset(20.5f, 20.5f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
                StatusKind.Charging -> {
                    val bolt = Path().apply {
                        moveTo(13.5f, 2f); lineTo(5f, 13.5f); lineTo(11f, 13.5f)
                        lineTo(10f, 22f); lineTo(19f, 9.5f); lineTo(13f, 9.5f); close()
                    }
                    drawPath(bolt, w)
                }
                StatusKind.Timer -> {
                    drawCircle(w, radius = 8f, center = Offset(12f, 13.5f), style = st)
                    drawLine(w, Offset(12f, 13.5f), Offset(12f, 9f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(w, Offset(9.5f, 2.5f), Offset(14.5f, 2.5f), strokeWidth = 2f, cap = StrokeCap.Round)
                }
                StatusKind.Alarm -> {
                    drawCircle(w, radius = 7.5f, center = Offset(12f, 13f), style = st)
                    drawLine(w, Offset(12f, 13f), Offset(12f, 9.2f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(w, Offset(12f, 13f), Offset(14.6f, 14.6f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawCircle(w, radius = 2.4f, center = Offset(4.6f, 4.8f))
                    drawCircle(w, radius = 2.4f, center = Offset(19.4f, 4.8f))
                }
                StatusKind.Camera -> {
                    drawRoundRect(w, Offset(2.5f, 6f), Size(13f, 12f), CornerRadius(3f, 3f))
                    val lens = Path().apply { moveTo(16.5f, 10.5f); lineTo(21.5f, 7.5f); lineTo(21.5f, 16.5f); lineTo(16.5f, 13.5f); close() }
                    drawPath(lens, w)
                }
                StatusKind.Volume -> drawSpeaker(w, c.progress, c.silent)
                StatusKind.Network, StatusKind.Airplane, StatusKind.RingVolume, StatusKind.LowBattery, StatusKind.LowBatteryCard ->
                    drawSysGlyph(c, w)
                StatusKind.Mic -> {
                    drawRoundRect(w, Offset(9f, 2.5f), Size(6f, 11f), CornerRadius(3f, 3f))
                    drawArc(w, 0f, 180f, false, Offset(5.5f, 6f), Size(13f, 12f), style = st)
                    drawLine(w, Offset(12f, 18f), Offset(12f, 21.5f), strokeWidth = 2f, cap = StrokeCap.Round)
                }
            }
        }
    }
}


@Composable
internal fun ChargeContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val green = Pal.c("status_charging")
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.title, color = Pal.c("status_text"), fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
        Spacer(Modifier.weight(1f))      // ortada kamera deliği için boşluk kalır
        Text(c.value, color = green, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
            val tipW = 1.8.dp.toPx()
            val bodyW = size.width - tipW - 1.dp.toPx()
            val r = CornerRadius(4.dp.toPx())
            drawRoundRect(Pal.c("status_batt_body"), size = Size(bodyW, size.height), cornerRadius = r)
            val fillW = (bodyW * c.progress.coerceIn(0f, 1f)).coerceAtLeast(size.height * 0.5f)
            drawRoundRect(green, size = Size(fillW, size.height), cornerRadius = r)
            drawRoundRect(
                Pal.c("status_batt_tip"),
                topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.3f),
                size = Size(tipW, size.height * 0.4f),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Composable
internal fun StatusContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val color = statusColor(c)
    val timer = c.kind == StatusKind.Timer && c.endsAt > 0L
    var now by remember(c.endsAt) { mutableLongStateOf(System.currentTimeMillis()) }
    if (timer) LaunchedEffect(c.endsAt) {
        while (true) { now = System.currentTimeMillis(); delay(200) }
    }
    val remaining = if (timer) (c.endsAt - now).coerceAtLeast(0L) else 0L
    val value = if (timer) formatRemaining(remaining) else c.value
    val frac = when {
        timer && c.totalMs > 0L -> remaining.toFloat() / c.totalMs
        c.kind == StatusKind.Charging -> c.progress
        else -> -1f
    }.let { if (it >= 0f) it.coerceIn(0f, 1f) else it }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).background(color, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            StatusIcon(c, Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.title, color = Pal.c("status_text"), fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            if (c.subtitle.isNotBlank()) {
                Text(c.subtitle, color = Pal.c("status_text_dim"), fontSize = 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
            if (frac >= 0f) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Pal.c("status_track"))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (value.isNotBlank()) {
            Text(value, color = Pal.c("status_text"), fontWeight = FontWeight.Bold, fontSize = 22.sp,
                maxLines = 1, modifier = Modifier.rainbowFill(rb))
        }
    }
}


// ---------------------------------------------------------------- Zil modu: Zil / Sessiz / Titreşim (iOS tarzı hap)
/**
 * Solda simge, sağda yazı (Zil / Sessiz / Titreşim). Ekran kaydındaki gibi: sessizde kırmızı (titreşimde turuncu) kapsül
 * içinde beyaz çizgili zil, yazı kapsül renginde; zilde kapsülsüz beyaz zil ve beyaz yazı.
 * [compact]: ada genişlemiyorsa aynı düzen küçük yazı / simgeyle kapalı adanın içine sığar. Ortada kamera deliği için boşluk kalır.
 * [heightDp]: hap yüksekliği; simge, kapsül ve yazı boyutu buna göre ölçeklenir.
 */
@Composable
internal fun RingerContent(c: IslandContent.Status, rb: RainbowStyle?, compact: Boolean = false, heightDp: Float = 36f) {
    val accent = when {
        c.silent -> Pal.c("status_ringer_silent")      // sessiz: kırmızı
        c.vibrate -> Pal.c("status_ringer_vibrate")     // titreşim: turuncu
        else -> Pal.c("status_ringer_normal")                // zil: beyaz
    }
    val capsule = c.silent || c.vibrate
    val iconColor = if (capsule) Color.White else accent
    val capH = (heightDp * 0.62f).dp
    val capW = (heightDp * 0.62f * 1.9f).dp
    val glyphSize = (heightDp * (if (capsule) 0.40f else 0.50f)).dp
    val textSp = if (compact) 10.sp else (heightDp * 0.34f).coerceIn(11f, 22f).sp
    val padStart = if (compact) 5.dp else (heightDp * 0.17f).dp
    val padEnd = if (compact) 8.dp else (heightDp * 0.43f).dp
    Row(
        Modifier.fillMaxSize().padding(start = padStart, end = padEnd),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (capsule) {
            Box(
                Modifier.size(width = if (compact) capW * 0.85f else capW, height = if (compact) capH * 0.85f else capH)
                    .clip(RoundedCornerShape(50)).background(accent),
                contentAlignment = Alignment.Center,
            ) { RingerGlyph(c, iconColor, accent, Modifier.size(if (compact) glyphSize * 0.9f else glyphSize)) }
        } else {
            Box(Modifier.padding(start = 3.dp), contentAlignment = Alignment.Center) {
                RingerGlyph(c, iconColor, Color.Black, Modifier.size(glyphSize))
            }
        }
        Spacer(Modifier.weight(1f))
        Text(
            c.title, color = accent, fontWeight = FontWeight.Bold, fontSize = textSp, maxLines = 1, softWrap = false,
            modifier = if (capsule) Modifier else Modifier.rainbowFill(rb),
        )
    }
}

@Composable
private fun RingerGlyph(c: IslandContent.Status, color: Color, gap: Color, modifier: Modifier = Modifier) {
    val swing = remember(c.title) { Animatable(0f) }
    val slash = remember(c.title) { Animatable(if (c.silent) 0f else 1f) }
    LaunchedEffect(c.title) {
        when {
            c.silent -> { delay(140); slash.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) }
            c.vibrate -> {
                repeat(7) { i ->
                    val a = 1.3f * (1f - i / 7f)
                    swing.animateTo(a, tween(45)); swing.animateTo(-a, tween(45))
                }
                swing.animateTo(0f, tween(45))
            }
            else -> for (a in floatArrayOf(22f, -18f, 13f, -9f, 5f, 0f)) {
                swing.animateTo(a, tween(95, easing = FastOutSlowInEasing))
            }
        }
    }
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({ scale(sc, sc, Offset.Zero) }) {
            if (c.vibrate) {
                withTransform({ translate(swing.value, 0f) }) { drawVibratePhone(color) }
            } else {
                withTransform({ rotate(swing.value, Offset(12f, 3f)) }) { drawPath(BellPath, color) }
                val p = slash.value
                if (c.silent && p > 0.01f) {
                    val end = Offset(3.5f + 17f * p, 3.5f + 17f * p)
                    drawLine(gap, Offset(3.5f, 3.5f), end, strokeWidth = 5f, cap = StrokeCap.Round)   // çizginin kenarında boşluk (kapsül rengi)
                    drawLine(color, Offset(3.5f, 3.5f), end, strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
            }
        }
    }
}

private fun DrawScope.drawVibratePhone(color: Color) {
    drawRoundRect(color, Offset(8f, 3.5f), Size(8f, 17f), CornerRadius(2.2f, 2.2f), style = Stroke(width = 2f))
    for ((x, y0, y1) in listOf(
        Triple(4.5f, 8f, 16f), Triple(1.8f, 10f, 14f), Triple(19.5f, 8f, 16f), Triple(22.2f, 10f, 14f),
    )) drawLine(color, Offset(x, y0), Offset(x, y1), strokeWidth = 2f, cap = StrokeCap.Round)
}


// ---------------------------------------------------------------- Medya sesi: hoparlör + seviye çubuğu + yüzde (hap)
/** 24x24 ızgarada hoparlör: seviyeye göre 0 / 1 / 2 ses dalgası, sessizde çapraz çizgi. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSpeaker(w: Color, level: Float, mute: Boolean) {
    val body = Path().apply {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 4.5f); lineTo(12f, 19.5f); lineTo(7f, 15f); lineTo(3f, 15f); close()
    }
    drawPath(body, w)
    val st = Stroke(width = 2f, cap = StrokeCap.Round)
    if (mute) {
        drawLine(w, Offset(15f, 9.5f), Offset(21f, 14.5f), strokeWidth = 2f, cap = StrokeCap.Round)
        drawLine(w, Offset(21f, 9.5f), Offset(15f, 14.5f), strokeWidth = 2f, cap = StrokeCap.Round)
        return
    }
    if (level > 0f) drawArc(w, -45f, 90f, false, Offset(7f, 7f), Size(10f, 10f), style = st)
    if (level > 0.5f) drawArc(w, -45f, 90f, false, Offset(4f, 4f), Size(16f, 16f), style = st)
}

/** Solda hoparlör, sağda seviye çubuğu ve yüzde. Ortada kamera deliği için boşluk kalır. */
@Composable
internal fun VolumeContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val frac by androidx.compose.animation.core.animateFloatAsState(
        c.progress.coerceIn(0f, 1f), androidx.compose.animation.core.tween(160), label = "volFrac",
    )
    val fg = Pal.c("status_text")
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(20.dp)) {
            val sc = size.width / 24f
            withTransform({ scale(sc, sc, Offset.Zero) }) { drawSpeaker(fg, c.progress, c.silent) }
        }
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.width(44.dp).height(5.dp).clip(RoundedCornerShape(3.dp)).background(Pal.c("status_track")),
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(fg, RoundedCornerShape(3.dp)))
        }
        Spacer(Modifier.width(8.dp))
        Text(c.value, color = fg, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1,
            modifier = Modifier.rainbowFill(rb))
    }
}
