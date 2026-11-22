package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.sp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val CR_SIZE_DP = 112f        // kare kutunun kenarı
private const val CR_PAD_DP = 6f
private const val CR_EXPAND = 0.35f        // saniye
private const val CR_SWEEP_END = 1.6f
private const val CR_HOLD_END = 2.4f
private const val CR_END = 2.8f
private val CR_GREEN = Color(0xFF34C759)

/**
 * Şarj halkası: şarja takılınca ada kare bir kutuya açılır, yeşil halka pil yüzdesine kadar dolar,
 * kısa bir süre durur ve yeniden adaya dönüşür. Dokunuş almayan geçici pencerede çizilir; bu sürede
 * normal ada gizlenir ([onActive]).
 */
class ChargeRingFlash(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val registryOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val onActive: (Boolean) -> Unit,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private val run = mutableIntStateOf(0)
    private val pct = mutableIntStateOf(0)
    @Volatile private var arcs = false

    /** [withArcs]: Şarjda elektrik (Birleşik efektler) – halkaya kenarlardan elektrik arkları akar. */
    fun play(a: FaceIdAnchor, percent: Int, withArcs: Boolean = false) {
        arcs = withArcs
        pct.intValue = percent.coerceIn(0, 100)
        if (view == null && !attach(a)) return
        run.intValue++
        onActive(true)
    }

    fun release() = detach()

    private fun attach(a: FaceIdAnchor): Boolean {
        val d = ctx.resources.displayMetrics.density
        val side = CR_SIZE_DP * d
        val pad = CR_PAD_DP * d
        val winW = (max(a.pillW, side) + pad * 2f).roundToInt()
        val winH = (max(a.pillH, side) + pad * 2f).roundToInt()
        val x = (a.cx - winW / 2f).roundToInt()
        val y = if (a.inverted) (a.cy + a.pillH / 2f - side - pad).roundToInt() else (a.cy - a.pillH / 2f - pad).roundToInt()
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(registryOwner)
            setContent {
                val id = run.intValue
                val p = pct.intValue
                val tm = rememberTextMeasurer()
                val t = remember(id) { mutableFloatStateOf(0f) }
                LaunchedEffect(id) {
                    var last = 0L
                    while (t.floatValue < CR_END) {
                        val now = withFrameNanos { it }
                        if (last != 0L) t.floatValue += min((now - last) / 1_000_000_000f, 0.05f)
                        last = now
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post { detach() }
                }
                Canvas(Modifier.fillMaxSize()) {
                    val tt = t.floatValue
                    fun seg(a0: Float, b0: Float) = ((tt - a0) / (b0 - a0)).coerceIn(0f, 1f)
                    fun ease(u: Float) = u * u * (3f - 2f * u)
                    val e = ease(seg(0f, CR_EXPAND)) * (1f - ease(seg(CR_HOLD_END, CR_END)))
                    val bw = a.pillW + (side - a.pillW) * e
                    val bh = a.pillH + (side - a.pillH) * e
                    val corner = (a.pillH / 2f) + (side * 0.3f - a.pillH / 2f) * e
                    val left = size.width / 2f - bw / 2f
                    val top = if (a.inverted) size.height - pad - bh else pad
                    drawRoundRect(Color.Black, Offset(left, top), Size(bw, bh), CornerRadius(corner))
                    val ca = seg(CR_EXPAND * 0.6f, CR_EXPAND + 0.15f) * (1f - seg(CR_HOLD_END - 0.1f, CR_HOLD_END + 0.15f))
                    if (ca <= 0f) return@Canvas
                    val c = Offset(size.width / 2f, top + bh / 2f)
                    val r = side * 0.33f
                    val sw = 7f * density
                    val fill = p / 100f * ease(seg(CR_EXPAND, CR_SWEEP_END))
                    drawCircle(CR_GREEN.copy(alpha = 0.22f * ca), r, c, style = Stroke(sw))
                    if (fill > 0.002f) {
                        drawArc(
                            CR_GREEN.copy(alpha = ca), -90f, 360f * fill, false,
                            Offset(c.x - r, c.y - r), Size(r * 2f, r * 2f), style = Stroke(sw, cap = StrokeCap.Round),
                        )
                    }
                    // Yüzde (halka dolarken sayar) ve şimşek
                    val shown = (p * ease(seg(CR_EXPAND, CR_SWEEP_END))).roundToInt()
                    val txt = tm.measure("%$shown", TextStyle(color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold))
                    drawText(txt, topLeft = Offset(c.x - txt.size.width / 2f, c.y - txt.size.height / 2f + 6f * density), alpha = ca)
                    val bs = 7f * density
                    val by = c.y - 14f * density
                    val bolt = Path().apply {
                        moveTo(c.x + bs * 0.15f, by - bs)
                        lineTo(c.x - bs * 0.55f, by + bs * 0.12f)
                        lineTo(c.x - bs * 0.02f, by + bs * 0.12f)
                        lineTo(c.x - bs * 0.2f, by + bs)
                        lineTo(c.x + bs * 0.55f, by - bs * 0.15f)
                        lineTo(c.x + bs * 0.02f, by - bs * 0.15f)
                        close()
                    }
                    drawPath(bolt, CR_GREEN.copy(alpha = ca))
                    if (arcs) drawChargeArcs(c, r, fill, tt, ca, density, Offset(left, top), Size(bw, bh))
                }
            }
        }
        val lp = { type: Int ->
            WindowManager.LayoutParams(
                winW, winH, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                this.x = x; this.y = y
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(v, lp(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)); host = h2; added = true
            }
        }
        if (!added) added = runCatching {
            wm.addView(v, lp(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)); host = wm
        }.isSuccess
        if (!added) { v.disposeComposition(); return false }
        view = v
        return true
    }

    private fun detach() {
        val v = view ?: return
        view = null
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        host = null
        onActive(false)
    }
}

// ---------------------------------------------------------------- Şarjda elektrik
/** Kırık çizgili ark (orta nokta kaydırma). */
private fun crArc(a: Offset, b: Offset, rnd: java.util.Random, rough: Float): List<Offset> {
    var pts = listOf(a, b)
    val dx0 = b.x - a.x; val dy0 = b.y - a.y
    var off = kotlin.math.sqrt(dx0 * dx0 + dy0 * dy0) * rough
    repeat(4) {
        val nx = ArrayList<Offset>(pts.size * 2)
        for (i in 0 until pts.size - 1) {
            val p = pts[i]; val q = pts[i + 1]
            nx.add(p)
            val dx = q.x - p.x; val dy = q.y - p.y
            val l = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
            val o = (rnd.nextFloat() - 0.5f) * 2f * off
            nx.add(Offset((p.x + q.x) / 2f - dy / l * o, (p.y + q.y) / 2f + dx / l * o))
        }
        nx.add(pts.last())
        pts = nx
        off *= 0.5f
    }
    return pts
}

/**
 * Halka dolarken kutunun kenarlarından dolan uca doğru titreyen elektrik arkları ve uçta kıvılcım.
 * Her karede yeniden üretilir (titreme); dolum bitince arklar seyrelir.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChargeArcs(
    c: Offset, r: Float, fill: Float, tt: Float, ca: Float, d: Float, boxTL: Offset, boxSz: Size,
) {
    if (ca <= 0.01f || fill <= 0.002f) return
    val rnd = java.util.Random((tt * 60f).toLong())
    val ang = Math.toRadians((-90f + 360f * fill).toDouble())
    val head = Offset(c.x + r * kotlin.math.cos(ang).toFloat(), c.y + r * kotlin.math.sin(ang).toFloat())
    val filling = tt < CR_SWEEP_END
    val n = if (filling) 3 else 1
    if (!filling && rnd.nextFloat() > 0.35f) return
    val glow = Color(0xFF52D6FF)
    repeat(n) {
        // kutunun kenarında rastgele bir nokta
        val side = rnd.nextInt(4)
        val u = rnd.nextFloat()
        val inset = 4f * d
        val src = when (side) {
            0 -> Offset(boxTL.x + inset + u * (boxSz.width - inset * 2f), boxTL.y + inset)
            1 -> Offset(boxTL.x + boxSz.width - inset, boxTL.y + inset + u * (boxSz.height - inset * 2f))
            2 -> Offset(boxTL.x + inset + u * (boxSz.width - inset * 2f), boxTL.y + boxSz.height - inset)
            else -> Offset(boxTL.x + inset, boxTL.y + inset + u * (boxSz.height - inset * 2f))
        }
        val pts = crArc(src, head, rnd, 0.22f)
        val path = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(path, glow.copy(alpha = 0.35f * ca), style = Stroke(5f * d, cap = StrokeCap.Round), blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
        drawPath(path, Color.White.copy(alpha = 0.9f * ca), style = Stroke(1.2f * d, cap = StrokeCap.Round))
    }
    // dolan uçta kıvılcım
    drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(Color.White.copy(alpha = ca), glow.copy(alpha = 0.5f * ca), Color.Transparent), head, 10f * d),
        10f * d, head, blendMode = androidx.compose.ui.graphics.BlendMode.Plus)
    repeat(if (filling) 6 else 2) {
        val a2 = rnd.nextFloat() * 6.2832f
        val l = (3f + rnd.nextFloat() * 7f) * d
        drawLine(Color(0xFFFFE08A).copy(alpha = ca), head, Offset(head.x + kotlin.math.cos(a2) * l, head.y + kotlin.math.sin(a2) * l), 1f * d, StrokeCap.Round)
    }
}
