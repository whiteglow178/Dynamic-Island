package com.example.dynamicisland

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * GİZLİ OYUN: "Ada Pong"
 * Ada ekranın üstünde raket olur. Parmağını ekranda sağa sola kaydırarak adayı hareket ettir, topu adaya çarptır.
 * Her vuruşta top hızlanır; top adanın yanından geçip yukarı kaçarsa oyun biter. En iyi skor saklanır.
 * Boştaki adaya hızlıca 5 kez dokununca (ayar açıksa) ya da ayarlardaki düğmeyle açılır.
 */
class IslandGameActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent { IslandPong(getSharedPreferences("island_game", Context.MODE_PRIVATE)) }
    }

    companion object {
        fun open(ctx: Context) {
            runCatching {
                ctx.startActivity(Intent(ctx, IslandGameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

private enum class Phase { Ready, Playing, Over }

@Composable
private fun IslandPong(prefs: android.content.SharedPreferences) {
    val d = LocalDensity.current.density
    val view = LocalView.current
    var size by remember { mutableStateOf(IntSize.Zero) }
    var phase by remember { mutableStateOf(Phase.Ready) }
    var score by remember { mutableIntStateOf(0) }
    var best by remember { mutableIntStateOf(prefs.getInt("best", 0)) }
    var paddleX by remember { mutableFloatStateOf(-1f) }      // adanın merkezi (px); -1 = henüz ortalanmadı
    var targetX by remember { mutableFloatStateOf(-1f) }
    var ballX by remember { mutableFloatStateOf(0f) }
    var ballY by remember { mutableFloatStateOf(0f) }
    var vx by remember { mutableFloatStateOf(0f) }
    var vy by remember { mutableFloatStateOf(0f) }
    var squash by remember { mutableFloatStateOf(0f) }        // vuruşta ada jöle gibi ezilir
    var squashV by remember { mutableFloatStateOf(0f) }
    var frame by remember { mutableLongStateOf(0L) }

    val pw = 120f * d                 // ada (raket) genişliği
    val ph = 34f * d
    val top = 44f * d                 // adanın üst kenarı (ekranın üstünde, gerçek adanın yerine yakın)
    val br = 9f * d                   // top yarıçapı

    fun reset() {
        val w = size.width.toFloat(); val h = size.height.toFloat()
        paddleX = w / 2f; targetX = w / 2f
        ballX = w / 2f; ballY = h * 0.72f
        val ang = (Random.nextFloat() - 0.5f) * 1.0f                 // dikeyden ±~30°
        val sp = 520f * d
        vx = sin(ang) * sp; vy = -cos(ang) * sp
        score = 0; squash = 0f; squashV = 0f
    }

    LaunchedEffect(phase, size) {
        if (size == IntSize.Zero) return@LaunchedEffect
        if (paddleX < 0f) { paddleX = size.width / 2f; targetX = paddleX }
        if (phase != Phase.Playing) return@LaunchedEffect
        var last = 0L
        while (phase == Phase.Playing) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 1f / 60f else ((now - last) / 1e9f).coerceIn(0.001f, 0.033f)
                last = now
                val w = size.width.toFloat(); val h = size.height.toFloat()
                // Ada parmağı yumuşakça izler
                paddleX += (targetX - paddleX) * (1f - kotlin.math.exp(-dt * 22f))
                paddleX = paddleX.coerceIn(pw / 2f, w - pw / 2f)
                // Top
                ballX += vx * dt; ballY += vy * dt
                if (ballX < br) { ballX = br; vx = abs(vx) }
                if (ballX > w - br) { ballX = w - br; vx = -abs(vx) }
                if (ballY > h - br) { ballY = h - br; vy = -abs(vy) }
                // Adaya çarpma
                val bottom = top + ph
                if (vy < 0f && ballY - br <= bottom && ballY - br >= top - br && abs(ballX - paddleX) <= pw / 2f + br * 0.6f) {
                    ballY = bottom + br
                    val off = ((ballX - paddleX) / (pw / 2f)).coerceIn(-1f, 1f)     // kenara yakın vuruş daha yatık gider
                    val sp = hypot(vx, vy) * 1.045f
                    val ang = off * 1.05f
                    vx = sin(ang) * sp; vy = cos(ang) * sp
                    score++
                    squashV += 9f
                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                }
                // Kaçırdı
                if (ballY < top - br * 3f) {
                    phase = Phase.Over
                    if (score > best) { best = score; prefs.edit().putInt("best", score).apply() }
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                }
                // Jöle
                val a = -500f * squash - 10f * squashV
                squashV += a * dt; squash += squashV * dt
                frame = now
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF07070B))
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (phase != Phase.Playing) {
                        reset(); phase = Phase.Playing
                    }
                    targetX = down.position.x
                    while (true) {
                        val ev = awaitPointerEvent()
                        val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        targetX = c.position.x
                        c.consume()
                    }
                }
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (frame < 0L) return@Canvas
            val px = if (paddleX < 0f) size.width / 2f else paddleX
            // Ada (raket): siyah hap + ince ışık kenarı + hafif parıltı
            val sw = pw * (1f + 0.18f * squash); val sh = ph * (1f - 0.16f * squash)
            for (i in 1..4) {
                val g = i * 3f * density
                drawRoundRect(Color(0xFF64D2FF).copy(alpha = 0.10f * (1f - i / 5f)), Offset(px - sw / 2f - g, top - g),
                    Size(sw + 2 * g, sh + 2 * g), CornerRadius(sh / 2f + g), style = Stroke(2.5f * density))
            }
            drawRoundRect(Color.Black, Offset(px - sw / 2f, top), Size(sw, sh), CornerRadius(sh / 2f))
            drawRoundRect(Color.White.copy(alpha = 0.35f), Offset(px - sw / 2f, top), Size(sw, sh), CornerRadius(sh / 2f), style = Stroke(1.2f * density))
            // Top
            if (phase == Phase.Playing) {
                drawCircle(Brush.radialGradient(listOf(Color(0x8864D2FF), Color.Transparent), center = Offset(ballX, ballY), radius = br * 3f), br * 3f, Offset(ballX, ballY))
                drawCircle(Color.White, br, Offset(ballX, ballY))
            }
        }
        // Skor (arkada büyük ve soluk)
        if (phase == Phase.Playing) {
            Text("$score", color = Color.White.copy(alpha = 0.12f), fontSize = 120.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center))
        } else {
            Column(Modifier.align(Alignment.Center).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (phase == Phase.Over) "Kaçırdın!" else "Ada Pong", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                if (phase == Phase.Over) Text("Skor: $score", color = Color.White.copy(alpha = 0.85f), fontSize = 20.sp, modifier = Modifier.padding(top = 10.dp))
                Text("En iyi: $best", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp, modifier = Modifier.padding(top = 6.dp))
                Text("Parmağını sağa sola kaydırarak adayı hareket ettir, topu kaçırma.\nBaşlamak için dokun.",
                    color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp, textAlign = TextAlign.Center, lineHeight = 20.sp,
                    modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}
