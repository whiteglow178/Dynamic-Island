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

// ---------------------------------------------------------------- Arama (gelen / aktif)
private const val PHONE_PATH =
    "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 " +
    "1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 " +
    "0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"

private val PhonePath: Path by lazy { PathParser().parsePathString(PHONE_PATH).toPath() }

internal val CallGreen: Color get() = Pal.c("call_answer")
private val CallRed: Color get() = Pal.c("call_decline")

/** Telefon ahizesi simgesi. [rotation] 135 verilirse "kapat" simgesi olur. */
@Composable
internal fun PhoneIcon(rotation: Float, modifier: Modifier = Modifier, tint: Color = Pal.c("call_icon")) {
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({
            rotate(rotation, Offset(size.width / 2f, size.height / 2f))
            scale(sc, sc, Offset.Zero)
        }) {
            // Dolgu + aynı renkte yuvarlak kontur: ahize kalınlaşır
            drawPath(PhonePath, tint)
            drawPath(PhonePath, tint, style = Stroke(width = PHONE_THICKEN, join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
    }
}

/** Ahize simgesinin ek kalınlığı (24x24'lük simge birimi). Artırınca daha kalın olur. */
private const val PHONE_THICKEN = 1.9f

// Cevapla / Reddet düğmeleri: iOS tarzı düz (gradyansız) kırmızı ve yeşil daireler
private val AnswerColor: Color get() = Pal.c("call_answer")
private val DeclineColor: Color get() = Pal.c("call_decline")
private val SpeakerOff: Color get() = Pal.c("call_speaker_off")

@Composable
private fun CallButton(color: Color, size: Dp, onClick: () -> Unit, content: @Composable (Dp) -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content(size * 0.50f) }
}

// Hoparlör simgesi (ses dalgalı); kapalıyken dış dalga yok
@Composable
private fun SpeakerIcon(on: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({ scale(sc, sc, Offset.Zero) }) {
            val body = Path().apply {
                moveTo(3f, 9.5f); lineTo(7f, 9.5f); lineTo(12f, 5f)
                lineTo(12f, 19f); lineTo(7f, 14.5f); lineTo(3f, 14.5f); close()
            }
            drawPath(body, tint)
            drawPath(body, tint, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
            val st = Stroke(width = 2f, cap = StrokeCap.Round)
            drawArc(tint, -45f, 90f, false, Offset(8.5f, 7.5f), Size(9f, 9f), style = st)
            if (on) drawArc(tint, -50f, 100f, false, Offset(5f, 3.5f), Size(16f, 17f), style = st)
        }
    }
}


/** Küçük adadaki ses dalgası: yeşil -> beyaz -> turuncu renk geçişli, hareketli çubuklar. */
@Composable
internal fun CallWave(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "wave")
    val phase: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "wavePhase",
    )
    val green = Pal.c("call_wave_a")
    val orange = Pal.c("call_wave_b")
    Canvas(modifier) {
        val n = 9
        val gap: Float = size.width / n
        val bw: Float = gap * 0.52f
        for (i in 0 until n) {
            val t: Float = i / (n - 1).toFloat()
            val env: Float = 1f - kotlin.math.abs(t - 0.5f) * 1.1f                   // ortada yüksek, kenarda alçak
            val v: Float = 0.30f + 0.70f * kotlin.math.abs(kotlin.math.sin(phase + i * 0.85f))
            val h: Float = (size.height * env * v).coerceAtLeast(bw)
            val color: Color =
                if (t < 0.5f) androidx.compose.ui.graphics.lerp(green, Pal.c("call_wave_mid"), t * 2f)
                else androidx.compose.ui.graphics.lerp(Pal.c("call_wave_mid"), orange, (t - 0.5f) * 2f)
            val x: Float = gap * i + gap / 2f
            val y1: Float = (size.height - h) / 2f
            val y2: Float = (size.height + h) / 2f
            drawLine(color, Offset(x, y1), Offset(x, y2), strokeWidth = bw, cap = StrokeCap.Round)
        }
    }
}

@Composable
internal fun CallContent(
    c: IslandContent.Call,
    accent: Color,
    rb: RainbowStyle?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onSpeaker: () -> Unit,
) {
    val incoming = c.isIncoming
    val avatarSize = if (incoming) 52.dp else 48.dp
    Row(
        Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Avatar: fotoğraf varsa o, yoksa baş harf (gelen aramada renk geçişli, aktif aramada koyu gri)
        val avatar = c.avatar
        if (avatar != null) {
            Image(
                bitmap = avatar, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(avatarSize).clip(CircleShape),
            )
        } else {
            val bg = if (incoming) {
                Modifier.background(
                    Brush.linearGradient(listOf(Pal.c("call_avatar_a"), Pal.c("call_avatar_b"))), CircleShape,
                )
            } else {
                Modifier.background(Pal.c("call_avatar_bg"), CircleShape)
            }
            Box(Modifier.size(avatarSize).then(bg), contentAlignment = Alignment.Center) {
                Text(
                    text = c.name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = Pal.c("call_text"), fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    modifier = Modifier.rainbowFill(rb, 0.dp),
                )
            }
        }

        // İsim + durum
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (incoming) {
                Text(
                    "Gelen arama", color = Pal.c("call_text_dim"), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    c.name, color = Pal.c("call_text"), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb),
                )
            } else {
                Text(
                    c.duration ?: "Aktif arama", color = CallGreen, fontWeight = FontWeight.Medium,
                    fontSize = 15.sp, maxLines = 1,
                )
                Text(
                    c.name, color = Pal.c("call_text"), fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb),
                )
            }
        }

        // Butonlar: gelen aramada kırmızı + yeşil; aktif aramada hoparlör + kırmızı
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!incoming) {
                CallButton(if (c.speaker) Pal.c("call_btn_active_bg") else SpeakerOff, 50.dp, onSpeaker) { sz ->
                    SpeakerIcon(c.speaker, if (c.speaker) Pal.c("call_btn_active_icon") else Pal.c("call_icon"), Modifier.size(sz + 4.dp))
                }
            }
            CallButton(DeclineColor, if (incoming) 52.dp else 50.dp, onDecline) { sz -> PhoneIcon(135f, Modifier.size(sz)) }
            if (incoming) CallButton(AnswerColor, 52.dp, onAnswer) { sz -> PhoneIcon(0f, Modifier.size(sz)) }
        }
    }
}
