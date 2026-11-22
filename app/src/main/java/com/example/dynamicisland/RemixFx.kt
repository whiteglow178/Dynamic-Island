package com.example.dynamicisland

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.sin

// ================================================================ Adadan akan bildirim

private const val FLOW_IN_MS = 650L        // kelimelerin çıkışı (kademeli)
private const val FLOW_STAGGER_MS = 55L
private const val FLOW_HOLD_MS = 2600L
private const val FLOW_OUT_MS = 520L

private class FlowWord(val layout: TextLayoutResult, val x: Float, val y: Float, val idx: Int, val bold: Boolean)
private class FlowMsg(val start: Long, val words: List<FlowWord>) {
    val end: Long get() = start + FLOW_IN_MS + words.size * FLOW_STAGGER_MS + FLOW_HOLD_MS + FLOW_OUT_MS
}

/** Bildirim gelince başlık + metin kelime kelime adadan dökülür, okunur, sonra adaya geri çekilir. */
@Composable
internal fun NotifFlowFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val measurer = rememberTextMeasurer()
    var msg by remember { mutableStateOf<FlowMsg?>(null) }
    var now by remember { mutableLongStateOf(0L) }
    val anchorNow = androidx.compose.runtime.rememberUpdatedState(a)
    LaunchedEffect(Unit) {
        bus.events.collect { e ->
            if (e !is PlayEvent.NotifText) return@collect
            val an = anchorNow.value
            val all = (e.title.trim() + " " + e.text.trim()).split(Regex("\\s+")).filter { it.isNotBlank() }
            if (all.isEmpty()) return@collect
            val titleCount = e.title.trim().split(Regex("\\s+")).count { it.isNotBlank() }
            val words = all.take(12).let { if (all.size > 12) it.dropLast(1) + (it.last() + "…") else it }
            val d = an.h / 37f
            val padX = 8f * d; val gap = 5f * d; val lineGap = 6f * d
            val maxW = an.screenW * 0.82f
            val measured = words.mapIndexed { i, w ->
                measurer.measure(
                    w,
                    TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = if (i < titleCount) FontWeight.SemiBold else FontWeight.Normal),
                )
            }
            // Satırlara böl
            val lines = mutableListOf<MutableList<Int>>()
            var cur = mutableListOf<Int>(); var curW = 0f
            measured.forEachIndexed { i, l ->
                val ww = l.size.width + padX * 2f
                if (cur.isNotEmpty() && curW + gap + ww > maxW) { lines.add(cur); cur = mutableListOf(); curW = 0f }
                curW += (if (cur.isEmpty()) 0f else gap) + ww
                cur.add(i)
            }
            if (cur.isNotEmpty()) lines.add(cur)
            val lineH = (measured.maxOf { it.size.height } + 6f * d)
            val out = mutableListOf<FlowWord>()
            val dir = an.dir()
            val baseY = an.edgeY() + dir * 104f * d      // açılan bildirim kartının altından görünsün
            lines.forEachIndexed { li, idxs ->
                val total = idxs.sumOf { (measured[it].size.width + padX * 2f).toDouble() }.toFloat() + gap * (idxs.size - 1)
                var x = an.liveCx() - total / 2f
                val rowTop = if (dir > 0f) baseY + li * (lineH + lineGap) else baseY - (li + 1) * (lineH + lineGap) + lineGap
                for (i in idxs) {
                    val ww = measured[i].size.width + padX * 2f
                    out.add(FlowWord(measured[i], x + ww / 2f, rowTop + lineH / 2f, i, i < titleCount))
                    x += ww + gap
                }
            }
            msg = FlowMsg(SystemClock.uptimeMillis(), out)
        }
    }
    val m = msg
    LaunchedEffect(m) {
        if (m == null) return@LaunchedEffect
        while (SystemClock.uptimeMillis() < m.end) withFrameNanos { now = SystemClock.uptimeMillis() }
        if (msg === m) msg = null
    }
    if (m == null || !a.shown) return
    Canvas(Modifier.fillMaxSize()) {
        val d = a.h / 37f
        val origin = Offset(a.liveCx(), a.liveCy())
        val el = now - m.start
        val outStart = FLOW_IN_MS + m.words.size * FLOW_STAGGER_MS + FLOW_HOLD_MS
        for (w in m.words) {
            val tin = ((el - w.idx * FLOW_STAGGER_MS) / FLOW_IN_MS.toFloat()).coerceIn(0f, 1f)
            if (tin <= 0f) continue
            // Çıkış: geri sıçrayan (overshoot) yumuşak eğri
            val c = 1.70158f
            val ein = 1f + (c + 1f) * (tin - 1f).let { it * it * it } + c * (tin - 1f).let { it * it }
            // Geri çekiliş: son kelimeden başa doğru
            val rev = m.words.size - 1 - w.idx
            val tout = ((el - outStart - rev * 25L) / FLOW_OUT_MS.toFloat()).coerceIn(0f, 1f)
            val eout = tout * tout
            val k = (ein * (1f - eout))
            val p = origin + (Offset(w.x, w.y) - origin) * k
            val sc = (0.25f + 0.75f * k).coerceAtLeast(0.05f)
            val alpha = (tin * 3f).coerceAtMost(1f) * (1f - eout * 0.9f)
            val tw = w.layout.size.width.toFloat(); val th = w.layout.size.height.toFloat()
            val bw = tw + 16f * d; val bh = th + 6f * d
            scale(sc, sc, p) {
                drawRoundRect(
                    (if (w.bold) Color(0xFF1C1C1E) else Color(0xFF000000)).copy(alpha = 0.78f * alpha),
                    Offset(p.x - bw / 2f, p.y - bh / 2f), Size(bw, bh), CornerRadius(bh / 2f),
                )
                drawText(w.layout, topLeft = Offset(p.x - tw / 2f, p.y - th / 2f), alpha = alpha.coerceIn(0f, 1f))
            }
            // Adaya bağlı ince sıvı köprü (çıkarken ve çekilirken)
            val bridge = if (tin < 1f) 1f - tin else eout
            if (bridge > 0.02f) drawLine(a.color.copy(alpha = 0.9f), origin, p, 6f * d * bridge, androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

// ================================================================ Bildirim kuşları

private const val BIRD_FLY_MS = 1100L
private const val BIRD_STAY_MS = 60_000L

/**
 * Bildirim gelince bir kuş/kelebek uçup adanın yanına konar ve bekler. Adaya dokununca bildirim açılır,
 * kuş uçup gider (servis BirdAway yollar). 1 dakika sonra kendiliğinden gider.
 */
@Composable
internal fun NotifBirdFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    var kind by remember { mutableStateOf<VisitorKind?>(null) }
    var landAt by remember { mutableLongStateOf(0L) }
    var leaveAt by remember { mutableLongStateOf(0L) }
    var fromLeft by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        bus.events.collect { e ->
            when (e) {
                is PlayEvent.BirdLand -> {
                    val t = SystemClock.uptimeMillis()
                    if (kind == null || leaveAt != 0L) { landAt = t; fromLeft = kotlin.random.Random.nextBoolean() }
                    kind = e.kind; leaveAt = 0L
                }
                PlayEvent.BirdAway -> if (kind != null && leaveAt == 0L) leaveAt = SystemClock.uptimeMillis()
                else -> Unit
            }
        }
    }
    val k = kind
    LaunchedEffect(k, leaveAt) {
        if (k == null) return@LaunchedEffect
        while (true) {
            withFrameNanos { now = SystemClock.uptimeMillis() }
            if (leaveAt == 0L && now - landAt > BIRD_STAY_MS) leaveAt = now
            if (leaveAt != 0L && now - leaveAt > BIRD_FLY_MS) { kind = null; leaveAt = 0L; break }
            // Konduktan sonra kare hızını düşür (pil dostu): kanat çırpma yavaş
            if (leaveAt == 0L && now - landAt > BIRD_FLY_MS) delay(33L)
        }
    }
    if (k == null || !a.shown) return
    val t = rememberClock(true, 30)
    Canvas(Modifier.fillMaxSize()) {
        val d = a.h / 37f
        val side = if (fromLeft) -1f else 1f
        val perch = Offset(a.liveCx() + side * (a.w / 2f + 12f * d), a.liveCy() - 2f * d)
        val far = Offset(a.liveCx() + side * a.screenW * 0.6f, a.liveCy() + a.dir() * a.screenH * 0.18f)
        val away = Offset(a.liveCx() - side * a.screenW * 0.6f, a.liveCy() + a.dir() * a.screenH * 0.12f)
        val p: Offset
        val flying: Boolean
        if (leaveAt != 0L) {
            val u = ((now - leaveAt) / BIRD_FLY_MS.toFloat()).coerceIn(0f, 1f)
            val e = u * u
            p = perch + (away - perch) * e + Offset(0f, -sin(u * 3.1416f) * 40f * d)
            flying = true
        } else {
            val u = ((now - landAt) / BIRD_FLY_MS.toFloat()).coerceIn(0f, 1f)
            val e = 1f - (1f - u) * (1f - u)
            p = far + (perch - far) * e + Offset(0f, -sin(u * 3.1416f) * 30f * d)
            flying = u < 1f
        }
        // Konunca: hafif nefes alma, kanat sadece ara sıra çırpar
        val tt = if (flying) t else (if ((t % 4f) < 0.5f) t else 0.02f)
        val bob = if (flying) 0f else abs(sin(t * 2.2f)) * 1.2f * d
        val facing = if (leaveAt != 0L) side else -side    // konarken adaya bakar, giderken uzaklaşır
        val pp = Offset(p.x, p.y - bob)
        scale(facing, 1f, pp) { drawVisitor(k, pp, d, tt) }
        // Küçük bildirim noktası
        if (!flying) drawCircle(Color(0xFFFF453A), 2.6f * d, Offset(pp.x - facing * 7f * d, pp.y - 9f * d))
    }
}
