package com.example.dynamicisland

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * KARALAMA TAHTASI: adanın şeklinde büyük bir tahta. Parmağınla çizip "Kaydet"e basınca çizim boştaki adada görünür.
 * Boştaki adayı basılı tutunca (ayar açıksa) ya da ayarlardaki düğmeyle açılır.
 */
class IslandDoodleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DoodleStore.load(this)
        setContent { IslandTheme { DoodleBoard { finish() } } }
    }

    companion object {
        fun open(ctx: Context) {
            runCatching {
                ctx.startActivity(Intent(ctx, IslandDoodleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

@Composable
private fun DoodleBoard(onDone: () -> Unit) {
    val ctx = LocalContext.current
    // Çizgiler 0..1 aralığında (tahtanın genişliği / yüksekliği): adada aynı oranla küçültülerek çizilir
    val strokes = remember { mutableStateListOf<List<Offset>>().apply { addAll(DoodleStore.strokes) } }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0E0E12)).padding(horizontal = 20.dp, vertical = 48.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Adaya çiz", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Siyah hapın içine parmağınla çiz. Kaydedince çizimin boştaki adada küçük bir çıkartma olarak durur.",
            color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        // Ada oranında tahta (kapalı ada ~3,4 : 1)
        Box(
            Modifier.fillMaxWidth().aspectRatio(3.4f).clip(RoundedCornerShape(percent = 50)).background(Color.Black)
                .onSizeChanged { size = it }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        fun norm(o: Offset) = Offset(
                            (o.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f),
                            (o.y / size.height.coerceAtLeast(1)).coerceIn(0f, 1f),
                        )
                        var pts = listOf(norm(down.position))
                        current = pts
                        while (true) {
                            val ev = awaitPointerEvent()
                            val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (!c.pressed) break
                            val n = norm(c.position)
                            // Çok yakın noktaları atla (dosya küçük kalsın)
                            if ((n - pts.last()).getDistance() > 0.004f) { pts = pts + n; current = pts }
                            c.consume()
                        }
                        if (strokes.size < 60) strokes.add(pts)
                        current = emptyList()
                    }
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width; val h = size.height
                val sw = h * 0.07f
                fun draw(st: List<Offset>) {
                    if (st.size == 1) { drawCircle(Color.White, sw * 0.6f, Offset(st[0].x * w, st[0].y * h)); return }
                    val p = Path()
                    st.forEachIndexed { i, o -> if (i == 0) p.moveTo(o.x * w, o.y * h) else p.lineTo(o.x * w, o.y * h) }
                    drawPath(p, Color.White, style = Stroke(sw, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                strokes.forEach { draw(it) }
                if (current.isNotEmpty()) draw(current)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IslandActionButton("Geri al", { if (strokes.isNotEmpty()) strokes.removeAt(strokes.size - 1) }, Modifier.weight(1f))
            IslandActionButton("Temizle", { strokes.clear() }, Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IslandActionButton("Vazgeç", onDone, Modifier.weight(1f))
            IslandActionButton("Kaydet", { DoodleStore.save(ctx, strokes.toList()); onDone() }, Modifier.weight(1f), tonal = false)
        }
    }
}
