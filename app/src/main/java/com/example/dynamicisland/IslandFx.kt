package com.example.dynamicisland

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.*
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * ADA OLAYLARI: yağmur / şimşek / kar (donma) / asit yağmuru (erime) / adayı kırma.
 * Genişletilmiş müzik panelindeki öğeler (kapak, şarkı adı, sözler, ilerleme çubuğu, düğmeler) Modifier.fxTarget ile kaydolur;
 * damlalar ve kar bu öğelerin KONUMUNA göre çarpışır (içlerinden geçmez).
 * Adadan dışarı düşen parçalar, tam ekran dokunmatik olmayan ayrı bir pencerede (IslandFxWindow) çizilir.
 */

val LocalIslandFx = compositionLocalOf<IslandFxState?> { null }

// ---------------------------------------------------------------- Öğe (hedef) durumu
@Stable
class FxTarget(val id: String) {
    var rect by mutableStateOf(Rect.Zero)          // ada içi yerel px
    var freeze by mutableFloatStateOf(0f)
    var taps by mutableIntStateOf(0)
    var melt by mutableFloatStateOf(0f)
    var away by mutableStateOf(false)              // adadan koptu (görünmez)
    var layer: GraphicsLayer? = null
    var coolUntil = 0L
    var meltRestoreAt = 0L
    var awayRestoreAt = 0L
    var snow = 0f
    var blobs = 0

    var dens = 1f
    var iceCrack: Crack? = null                     // donmuş öğede dokundukça açılan çatlaklar (öğe başına sabit)
    var iceCrackW = 0f
    // Portal olayları (IslandFxScenes.kt): sarsıntı, kum / kar yığını, meteor izi, elektrik parıltısı
    var shake = 0f
    var shakeX by mutableFloatStateOf(0f)
    var shakeY by mutableFloatStateOf(0f)
    var pile: FloatArray? = null
    var pileKind = 0
    val scorches = ArrayList<FxScorch>()
    var glow = 0f
    var marksOn = false
    var fxTick by mutableIntStateOf(0)             // iz / parıltı varken öğenin her karede yeniden çizilmesi için

    // ---- Gerçek şekil: öğenin pikselleri taranır; her dar sütunda içeriğin üst ve alt kenarı (yerel px, NaN = boş).
    // Yerleşim kutusu yazının kendisinden çok büyük olabildiği için çarpışmalar kutuya değil bu şekle göre yapılır.
    var shapeTop: FloatArray? = null
    var shapeBot: FloatArray? = null
    var shapeTight = Rect.Zero                     // içeriğin sıkı sınırı (yerel)
    var shapeForW = -1f
    var shapeForH = -1f
    var shapeAt = 0L

    val shapeOk: Boolean get() = shapeTop != null && abs(shapeForW - rect.width) < 1f && abs(shapeForH - rect.height) < 1f
    fun cols(): Int { val st = shapeTop; return if (st != null && shapeOk) st.size else max(1, (rect.width / (2f * dens)).toInt()) }
    fun colW(): Float = rect.width / cols()
    fun colIndex(xAbs: Float): Int {
        val r = rect
        if (r.width <= 0f || xAbs < r.left || xAbs > r.right) return -1
        return ((xAbs - r.left) / colW()).toInt().coerceIn(0, cols() - 1)
    }
    fun colX(i: Int): Float = rect.left + (i + 0.5f) * colW()
    /** Sütundaki içeriğin üst kenarı (ekran-ada koordinatı); sütun boşsa null. Şekil henüz taranmadıysa kutunun üstü. */
    fun colTop(i: Int): Float? {
        val st = shapeTop
        if (st == null || !shapeOk) return rect.top
        val v = st.getOrNull(i) ?: return null
        return if (v.isNaN()) null else rect.top + v
    }
    fun colBot(i: Int): Float? {
        val sb = shapeBot
        if (sb == null || !shapeOk) return rect.bottom
        val v = sb.getOrNull(i) ?: return null
        return if (v.isNaN()) null else rect.top + v
    }
    fun topAt(xAbs: Float): Float? { val i = colIndex(xAbs); return if (i < 0) null else colTop(i) }
    fun botAt(xAbs: Float): Float? { val i = colIndex(xAbs); return if (i < 0) null else colBot(i) }
    /** Nokta öğenin gerçek içeriğinin (boş alan değil) içinde mi? */
    fun solid(xAbs: Float, y: Float): Boolean {
        val i = colIndex(xAbs)
        if (i < 0) return false
        val t = colTop(i) ?: return false
        val b = colBot(i) ?: return false
        return y >= t && y <= b
    }
    val tight: Rect get() = if (shapeOk && shapeTight.width > 0f) shapeTight.translate(rect.left, rect.top) else rect
    /** Damla / kar için yüzey: erime cephesi ile içeriğin üst kenarından alçakta olanı. */
    fun surfaceY(xAbs: Float): Float = max(frontY(xAbs), topAt(xAbs) ?: rect.bottom)
    private val seed = id.hashCode()

    /** Asit öğeyi üstten aşağı doğru yer: erime cephesinin üst kenardan uzaklığı (öğe içi px), tırtıklı. */
    fun frontLocal(xLocal: Float): Float {
        val h = rect.height
        val w = max(1f, rect.width)
        val m = melt.coerceIn(0f, 1f)
        val ramp = (m / 0.2f).coerceIn(0f, 1f)
        val u = xLocal / w
        val s1 = (seed and 255) / 40f
        val s2 = ((seed shr 8) and 255) / 40f
        val s3 = ((seed shr 16) and 255) / 40f
        val n = 0.5f + 0.5f * (0.5f * sin(u * 23f + s1) + 0.3f * sin(u * 51f + s2) + 0.2f * sin(u * 97f + s3))
        return (h * 0.85f * m + 7f * dens * ramp * n).coerceIn(0f, h)
    }
    fun frontY(xAbs: Float): Float = rect.top + frontLocal(xAbs - rect.left)

    /** Cephenin (düz) ortalama üst kenarı: damlalar ve parçacıklar için. */
    val visualTop: Float get() = rect.top + rect.height * 0.85f * melt.coerceIn(0f, 1f)
    val frozen: Boolean get() = freeze >= 1f
    val gone: Boolean get() = away || melt >= 1f
    val blocked: Boolean get() = gone || frozen || melt >= 0.6f

    fun reset() {
        freeze = 0f; taps = 0; melt = 0f; away = false
        coolUntil = 0L; meltRestoreAt = 0L; awayRestoreAt = 0L; snow = 0f; blobs = 0
        shake = 0f; shakeX = 0f; shakeY = 0f; pile = null; pileKind = 0; scorches.clear(); glow = 0f
    }
}

class CrackSeg(val pts: List<Offset>, val w: Float, val delay: Float)
class Crack(val segs: List<CrackSeg>, val bornMs: Long, val center: Offset, val R: Float)

private fun crackPointAt(pts: List<Offset>, dist: Float): Offset? {
    var acc = 0f
    for (i in 1 until pts.size) {
        val a = pts[i - 1]; val b = pts[i]
        val dx = b.x - a.x; val dy = b.y - a.y
        val len = sqrt(dx * dx + dy * dy)
        if (acc + len >= dist) {
            val f = if (len > 0f) (dist - acc) / len else 0f
            return Offset(a.x + dx * f, a.y + dy * f)
        }
        acc += len
    }
    return null
}

/**
 * Gerçekçi cam çatlağı: darbe noktasından düz giden, uca doğru incelen radyal çatlaklar, bunlardan dallanan ince çatlaklar
 * ve radyalleri birbirine bağlayan eşmerkezli (örümcek ağı gibi) halka çatlaklar.
 */
fun buildCrack(rnd: java.util.Random, p: Offset, R: Float, d: Float, nowMs: Long): Crack {
    fun f() = rnd.nextFloat()
    val segs = ArrayList<CrackSeg>()
    val n = 9 + rnd.nextInt(5)
    val step = 6.2832f / n
    val a0 = f() * 6.2832f
    val radials = ArrayList<List<Offset>>()
    for (i in 0 until n) {
        var ang = a0 + step * i + (f() - 0.5f) * step * 0.7f
        val L = R * (0.45f + 0.55f * f())
        val pts = arrayListOf(p)
        var cur = p
        var walked = 0f
        while (walked < L) {
            ang += (f() - 0.5f) * 0.22f                          // neredeyse düz: cam çatlağı kıvrılmaz
            val len = (4f + f() * 6f) * d
            cur = Offset(cur.x + cos(ang) * len, cur.y + sin(ang) * len)
            pts.add(cur); walked += len
        }
        radials.add(pts)
        segs.add(CrackSeg(pts, (1.7f + f() * 0.7f) * d, 0f))
        // dallar: ana çatlaktan dar açıyla ayrılır
        val nb = 1 + rnd.nextInt(3)
        for (b in 0 until nb) {
            val idx = (pts.size * (0.25f + 0.55f * f())).toInt().coerceIn(1, pts.size - 1)
            val st = pts[idx]
            val prev = pts[idx - 1]
            var ba = atan2(st.y - prev.y, st.x - prev.x) + (if (rnd.nextBoolean()) 1f else -1f) * (0.4f + f() * 0.5f)
            val bl = L * (0.15f + 0.3f * f())
            val bp = arrayListOf(st)
            var bc = st
            var bw = 0f
            while (bw < bl) {
                ba += (f() - 0.5f) * 0.25f
                val len = (3f + f() * 5f) * d
                bc = Offset(bc.x + cos(ba) * len, bc.y + sin(ba) * len)
                bp.add(bc); bw += len
            }
            if (bp.size >= 2) segs.add(CrackSeg(bp, (0.9f + f() * 0.4f) * d, (idx.toFloat() / (pts.size - 1)) * 0.85f))
        }
    }
    // halka çatlaklar
    for (rf in floatArrayOf(0.16f, 0.34f, 0.55f)) {
        val rr = R * rf
        val ringPts = radials.map { crackPointAt(it, rr) }
        for (i in 0 until n) {
            val a = ringPts[i] ?: continue
            val b = ringPts[(i + 1) % n] ?: continue
            if (f() > 0.78f) continue
            val mid = Offset((a.x + b.x) / 2f + (f() - 0.5f) * 5f * d, (a.y + b.y) / 2f + (f() - 0.5f) * 5f * d)
            segs.add(CrackSeg(listOf(a, mid, b), (0.8f + f() * 0.4f) * d, (rf * 0.8f + 0.08f).coerceAtMost(0.9f)))
        }
    }
    return Crack(segs, nowMs, p, R)
}

private fun DrawScope.drawCrack(cr: Crack, prog: Float, d: Float, dark: Color, light: Color) {
    val kk = 1f - (1f - prog.coerceIn(0f, 1f)).pow(3f)            // hızlı yayılır, sona doğru yavaşlar
    // darbe noktası: ezilmiş beyaz bölge
    val ia = (kk * 5f).coerceIn(0f, 1f)
    drawCircle(Pal.c("fx_white").copy(alpha = 0.10f * ia), 16f * d, cr.center)
    drawCircle(Pal.c("fx_white").copy(alpha = 0.45f * ia), 3.2f * d, cr.center)
    for (sg in cr.segs) {
        val loc = ((kk - sg.delay) / (1f - sg.delay)).coerceIn(0f, 1f)
        if (loc <= 0f) continue
        val n = sg.pts.size - 1
        val upto = loc * n
        for (s in 0 until n) {
            if (s >= upto) break
            val frac = (upto - s).coerceAtMost(1f)
            val a = sg.pts[s]; val b = sg.pts[s + 1]
            val e = Offset(a.x + (b.x - a.x) * frac, a.y + (b.y - a.y) * frac)
            val taper = 1f - 0.7f * (s.toFloat() / n)               // uca doğru incelir
            drawLine(dark, a, e, sg.w * 1.8f * taper, StrokeCap.Round)
            val o = Offset(0.6f * d, -0.6f * d)                      // cam kenarının ışık yansıması
            drawLine(light, Offset(a.x + o.x, a.y + o.y), Offset(e.x + o.x, e.y + o.y), sg.w * 0.7f * taper, StrokeCap.Round)
        }
    }
}

@Stable
class IslandFxState {
    val targets = mutableStateMapOf<String, FxTarget>()
    var origin: LayoutCoordinates? = null
    var live = false
    val cracks = ArrayList<Crack>()

    fun screenOrigin(): Offset? = origin?.takeIf { it.isAttached }?.positionOnScreen()
}

/** Öğeyi efektlere kaydeder: konumunu ölçer, erirken küçültür, düşerken görüntüsünü yakalar. */
@Composable
fun Modifier.fxTarget(id: String): Modifier {
    val fx = LocalIslandFx.current ?: return this
    val t = remember(fx, id) { FxTarget(id) }
    val layer = rememberGraphicsLayer()
    val dd = LocalDensity.current.density
    SideEffect { t.layer = layer; t.dens = dd }
    DisposableEffect(fx, id) { onDispose { if (fx.targets[id] === t) fx.targets.remove(id) } }
    return this
        .onGloballyPositioned { c ->
            val o = fx.origin
            if (o != null && o.isAttached && c.isAttached) {
                t.rect = o.localBoundingBoxOf(c, false)
                if (fx.targets[id] !== t) fx.targets[id] = t
            }
        }
        .graphicsLayer {
            val m = t.melt.coerceIn(0f, 1f)
            val fade = ((m - 0.9f) / 0.1f).coerceIn(0f, 1f)          // öğe ezilip küçülmez: asit yer, en sonda silinir
            alpha = if (t.away) 0f else (1f - fade)
            translationX = t.shakeX                                  // meteor / elektrik çarpınca sarsılır
            translationY = t.shakeY
            compositingStrategy = CompositingStrategy.Offscreen      // asidin yediği yerler gerçekten şeffaflaşsın
        }
        .drawWithContent {
            layer.record { this@drawWithContent.drawContent() }
            drawLayer(layer)
            @Suppress("UNUSED_VARIABLE") val tk = t.fxTick
            if (!t.away) drawFxMarks(t)
            val m = t.melt.coerceIn(0f, 1f)
            if (m > 0.001f && !t.away) {
                val d = t.dens
                val w = size.width
                val front = Path()
                val fill = Path()
                front.moveTo(-2f * d, t.frontLocal(0f))
                fill.moveTo(-2f * d, -2f * d)
                fill.lineTo(-2f * d, t.frontLocal(0f))
                var x = 0f
                val stepX = 3f * d
                while (x < w) {
                    val y = t.frontLocal(x)
                    front.lineTo(x, y); fill.lineTo(x, y)
                    x += stepX
                }
                val yEnd = t.frontLocal(w)
                front.lineTo(w + 2f * d, yEnd)
                fill.lineTo(w + 2f * d, yEnd)
                fill.lineTo(w + 2f * d, -2f * d)
                fill.close()
                // erime cephesi: kenar yeşilimsi parlar, sonra üstü silinir
                drawPath(front, Pal.c("fx_acid_dark").copy(alpha = 0.55f), style = Stroke(8f * d, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.SrcAtop)
                drawPath(front, Pal.c("fx_acid_light").copy(alpha = 0.9f), style = Stroke(2.4f * d, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.SrcAtop)
                drawPath(fill, Color.Black, blendMode = BlendMode.Clear)
            }
        }
}

// ---------------------------------------------------------------- Adadan dışarı düşen parçalar (tüm ekran penceresi)
class FxPiece(
    val kind: Int,                 // 0 = öğe görüntüsü, 1 = ada gövdesi parçası
    val bmp: ImageBitmap?,
    val poly: List<Offset>?,       // kind 1: köşeler (merkeze göre)
    val color: Color,
    val halfW: Float, val halfH: Float,
    var x: Float, var y: Float,    // ekran merkezi (px)
    val homeX: Float, val homeY: Float,   // ada içi yerel merkez (px)
) {
    var rot = 0f; var vx = 0f; var vy = 0f; var vr = 0f
    var state = 0                  // 0 düşüyor, 1 yatıyor, 2 geri dönüyor, 3 evde
    var restedAt = 0L; var delayMs = 0L
    var sx = 0f; var sy = 0f; var srot = 0f; var t = 0f
    var onHome: (() -> Unit)? = null
}

object FxBridge {
    val pieces = ArrayList<FxPiece>()
    var count by mutableIntStateOf(0)
    var tick by mutableLongStateOf(0L)
    var originX by mutableFloatStateOf(0f)     // adanın (yerel 0,0) ekran konumu
    var originY by mutableFloatStateOf(0f)
    var winX = 0f; var winY = 0f               // düşme penceresinin ekran konumu (kalibrasyon)
    var screenW = 1080f; var screenH = 2400f
    var restoreMs = 5000L
    var islandHidden by mutableStateOf(false)
    var onShardsDone: (() -> Unit)? = null

    fun add(p: FxPiece) { pieces.add(p); count = pieces.size }
    fun clear() { pieces.clear(); count = 0; islandHidden = false; onShardsDone = null }

    private fun ease(t: Float): Float = if (t < 0.5f) 4f * t * t * t else 1f - Math.pow((-2.0 * t + 2.0), 3.0).toFloat() / 2f

    fun step(dt: Float, nowMs: Long, d: Float) {
        if (pieces.isEmpty()) return
        val g = 2700f * d
        val floorY = screenH - 40f * d
        val shardsLeft = pieces.any { it.kind == 1 }
        var i = pieces.size - 1
        while (i >= 0) {
            val p = pieces[i]
            when (p.state) {
                0 -> {
                    p.vy += g * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
                    if (p.x < p.halfW) { p.x = p.halfW; p.vx = -p.vx * 0.5f }
                    else if (p.x > screenW - p.halfW) { p.x = screenW - p.halfW; p.vx = -p.vx * 0.5f }
                    if (p.y + p.halfH > floorY) {
                        p.y = floorY - p.halfH
                        if (abs(p.vy) > 350f * d) { p.vy = -p.vy * 0.32f; p.vx *= 0.7f; p.vr *= 0.6f }
                        else { p.vy = 0f; p.vx = 0f; p.vr = 0f; p.state = 1; p.restedAt = nowMs }
                    }
                }
                1 -> if (nowMs - p.restedAt >= restoreMs + p.delayMs && (p.kind == 1 || !shardsLeft)) {
                    p.sx = p.x; p.sy = p.y
                    var a = p.rot % 360f
                    if (a > 180f) a -= 360f
                    if (a < -180f) a += 360f
                    p.srot = a; p.t = 0f; p.state = 2
                }
                2 -> {
                    p.t += dt / 0.9f
                    val e = ease(p.t.coerceIn(0f, 1f))
                    val hx = originX + p.homeX; val hy = originY + p.homeY
                    p.x = p.sx + (hx - p.sx) * e
                    p.y = p.sy + (hy - p.sy) * e - sin(PI * e).toFloat() * 70f * d
                    p.rot = p.srot * (1f - e)
                    if (p.t >= 1f) {
                        p.state = 3; p.x = hx; p.y = hy; p.rot = 0f
                        if (p.kind == 0) { p.onHome?.invoke(); pieces.removeAt(i) }
                    }
                }
                else -> { p.x = originX + p.homeX; p.y = originY + p.homeY }
            }
            i--
        }
        // Tüm ada parçaları yerine gelince: parçalar kalkar, ada geri görünür
        if (pieces.any { it.kind == 1 } && pieces.none { it.kind == 1 && it.state != 3 }) {
            pieces.removeAll { it.kind == 1 }
            islandHidden = false
            onShardsDone?.invoke(); onShardsDone = null
        }
        count = pieces.size
    }
}

@Composable
fun FxFallRoot() {
    val d = LocalDensity.current.density
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = n
                FxBridge.step(dt, System.currentTimeMillis(), d)
                FxBridge.tick = n
            }
        }
    }
    Canvas(
        Modifier.fillMaxSize().onGloballyPositioned {
            val p = it.positionOnScreen()
            FxBridge.winX = p.x; FxBridge.winY = p.y
            FxBridge.screenW = it.size.width.toFloat() + p.x
            FxBridge.screenH = it.size.height.toFloat() + p.y
        }
    ) {
        @Suppress("UNUSED_VARIABLE") val t = FxBridge.tick
        for (p in FxBridge.pieces) {
            val cx = p.x - FxBridge.winX
            val cy = p.y - FxBridge.winY
            rotate(p.rot, Offset(cx, cy)) {
                if (p.kind == 0 && p.bmp != null) {
                    drawImage(p.bmp, topLeft = Offset(cx - p.halfW, cy - p.halfH))
                } else if (p.poly != null) {
                    val path = Path().apply {
                        p.poly.forEachIndexed { i, o -> if (i == 0) moveTo(cx + o.x, cy + o.y) else lineTo(cx + o.x, cy + o.y) }
                        close()
                    }
                    drawPath(path, p.color)
                    drawPath(path, Pal.c("fx_white").copy(alpha = 0.22f), style = Stroke(width = 1.2f))
                }
            }
        }
    }
}

/** Tam ekran, dokunmatik olmayan pencere. Parça varken servis ekler, yokken kaldırır. */
class IslandFxWindow(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
) {
    private var view: ComposeView? = null
    private var host: WindowManager? = null

    fun attach() {
        if (view != null) return
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { FxFallRoot() }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        var added = false
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                h2.addView(v, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                host = h2; added = true
            }
        }
        if (!added) added = runCatching { wm.addView(v, params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)); host = wm }.isSuccess
        if (added) view = v else v.disposeComposition()
    }

    fun detach() {
        val v = view ?: return
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        // Sahipler kaldırılmaz: pencere eklenip hemen kaldırılırsa bekleyen ilk çizim sahibi bulamayıp çöker
        view = null; host = null
    }
}

// ---------------------------------------------------------------- Parçacık simülasyonu
private const val K_RAIN = 0
private const val K_FLAKE = 1
private const val K_ACID = 2
private const val K_SPLASH = 3
private const val K_BLOB = 4
private const val K_ICE = 5
private const val K_DRIP = 6
private const val K_SUN = 7      // güneş parıltısı (Şarkının havası: sıcak renkli kapak)
private const val SUN_LIFE = 2.4f

private class P(var kind: Int, var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var size: Float) {
    var tid: String? = null
    var ox = 0f
    var rot = 0f
    var vr = 0f
}

private class Bolt(val pts: List<Offset>, var t: Float)

private class FxSim {
    val ps = ArrayList<P>()
    val rnd = java.util.Random()
    var acc = 0f
    var boltIn = 3f
    var bolt: Bolt? = null
    var flash = 0f
    var armed = true
    var autoStart = 0L
    var autoNext = 0L
    var autoCount = 0
    var shattered = false
    val scenes = FxScenes()                         // portal olayları (5 ve sonrası)
    val pets = FxPets()                             // ada sakinleri (IslandFxPets.kt)
    var lastCue = Int.MIN_VALUE                     // söz tepkisi: en son işlenen satır sırası

    fun f(): Float = rnd.nextFloat()

    fun clear() {
        ps.clear(); acc = 0f; boltIn = 3f; bolt = null; flash = 0f
        armed = true; autoStart = 0L; autoNext = 0L; autoCount = 0; shattered = false
        scenes.clear()
        pets.clear()
    }

    private fun hit(targets: Collection<FxTarget>, x: Float, y: Float): FxTarget? {
        var best: FxTarget? = null
        for (t in targets) {
            if (t.gone) continue
            val r = t.rect
            if (x >= r.left && x <= r.right && y >= t.frontY(x) && t.solid(x, y)) {
                if (best == null || t.visualTop < best.visualTop) best = t
            }
        }
        return best
    }

    private fun splash(x: Float, y: Float, d: Float, n: Int) {
        repeat(n) {
            ps.add(P(K_SPLASH, x, y, (f() - 0.5f) * 240f * d, -(80f + f() * 140f) * d, 0.28f, (1f + f()) * d))
        }
    }

    fun iceBurst(t: FxTarget, d: Float) {
        repeat(18) {
            val p = P(K_ICE, t.rect.left + f() * t.rect.width, t.rect.top + f() * t.rect.height,
                (f() - 0.5f) * 520f * d, -(60f + f() * 260f) * d, 0.9f + f() * 0.5f, (3f + f() * 4f) * d)
            p.vr = (f() - 0.5f) * 600f
            ps.add(p)
        }
    }

    fun makeBolt(x: Float, endY: Float, d: Float): Bolt {
        val pts = ArrayList<Offset>()
        val n = 9
        for (k in 0..n) {
            val y = endY * k / n
            val px = if (k == n) x else x + (f() - 0.5f) * 34f * d
            pts.add(Offset(px, y))
        }
        return Bolt(pts, 1f)
    }

    fun makeCrack(p: Offset, d: Float, nowMs: Long): Crack = buildCrack(rnd, p, (80f + f() * 90f) * d, d, nowMs)

    fun step(
        dt: Float, now: Long, W: Float, H: Float, d: Float,
        cfg: IslandSettings, fx: IslandFxState, strike: (FxTarget) -> Unit,
    ) {
        val targets = fx.targets.values
        val weather = cfg.fxWeather
        val inten = cfg.fxIntensity.coerceIn(0.05f, 1f)
        val restoreMs = (cfg.fxRestoreSec * 1000f).toLong()
        // Birleşik özellikler: eğim / sallama ve söz tepkisi
        val tilt = if (cfg.more.fxTilt) FxMotion.tiltX else 0f
        val shook = cfg.more.fxTilt && FxMotion.consumeShake()
        var cue = 0                                  // 0 yok, 1 yeni satır, 2 nakarat
        val cs = FxCues.seq
        if (lastCue == Int.MIN_VALUE) lastCue = cs
        else if (cs != lastCue) { lastCue = cs; if (cfg.more.moodLyrics) cue = if (FxCues.chorus) 2 else 1 }
        if (cue != 0 && weather in 1..FXW_SUN) {
            acc += when (weather) { 1 -> if (cue == 2) 60f else 20f; 2 -> if (cue == 2) 45f else 15f; 4 -> if (cue == 2) 30f else 10f; else -> if (cue == 2) 35f else 12f }
            if (weather == 1 && cue == 2) { bolt = makeBolt((0.1f + f() * 0.8f) * W, H * 0.92f, d); flash = 1f }   // nakaratta şimşek (öğeye çarpmaz)
        }
        if (shook) for (t in targets) {
            if (t.gone) continue
            if (t.snow > 0.2f) iceBurst(t, d)          // biriken kar silkelenir
            t.snow = 0f
            if (t.frozen) t.taps++                     // sallamak buzu çatlatır
            t.shake = min(t.shake + 2.5f * d, 7f * d)
        }

        if (weather in 1..FXW_SUN) {
            val base = when (weather) { 1 -> 90f; 2 -> 28f; 4 -> 16f; else -> 45f }
            acc += base * inten * (W / (340f * d)).coerceIn(0.6f, 1.8f) * dt
            while (acc >= 1f) {
                acc -= 1f
                val x = f() * W
                when (weather) {
                    1 -> ps.add(P(K_RAIN, x, -6f * d, (-30f + tilt * 320f) * d, (650f + f() * 250f) * d, 0f, 1f))
                    2 -> ps.add(P(K_FLAKE, x, -6f * d, ((f() - 0.5f) * 30f + tilt * 70f) * d, (55f + f() * 60f) * d, f() * 6.28f, (1.4f + f() * 1.8f) * d))
                    // Güneş: panelin her yerinde yavaşça süzülen, belirip sönen altın parıltılar (öğelere zarar vermez)
                    4 -> ps.add(P(K_SUN, x, f() * H, (f() - 0.5f) * 14f * d, -(5f + f() * 12f) * d, 0f, (1.6f + f() * 2.4f) * d))
                    else -> ps.add(P(K_ACID, x, -6f * d, (-20f + tilt * 300f) * d, (520f + f() * 200f) * d, 0f, 1f))
                }
            }
        }

        for (t in targets) t.blobs = 0

        var i = ps.size - 1
        while (i >= 0) {
            val p = ps[i]
            var dead = false
            when (p.kind) {
                K_RAIN, K_ACID -> {
                    p.x += p.vx * dt; p.y += p.vy * dt
                    if (p.y > H + 10f * d || p.x < -20f * d) dead = true
                    else {
                        val t = hit(targets, p.x, p.y)
                        if (t != null) {
                            dead = true
                            splash(p.x, t.surfaceY(p.x), d, if (p.kind == K_RAIN) 3 else 2)
                            if (p.kind == K_ACID) {
                                if (t.blobs < 14) {
                                    val b = P(K_BLOB, p.x, t.surfaceY(p.x), 0f, 0f, 0f, (2.2f + f() * 2.6f) * d)
                                    b.tid = t.id
                                    b.ox = ((p.x - t.rect.left) / max(1f, t.rect.width)).coerceIn(0.03f, 0.97f)
                                    ps.add(b); t.blobs++
                                } else {
                                    t.melt = (t.melt + 0.01f * cfg.fxMeltSpeed).coerceAtMost(1f)
                                }
                            }
                        }
                    }
                }
                K_FLAKE -> {
                    p.life += dt * 2f
                    p.x += (p.vx + sin(p.life) * 14f * d) * dt; p.y += p.vy * dt
                    if (p.y > H + 6f * d) dead = true
                    else {
                        val t = hit(targets, p.x, p.y)
                        if (t != null) {
                            dead = true
                            t.snow = (t.snow + 0.04f).coerceAtMost(1f)
                            if (!t.frozen && now >= t.coolUntil) t.freeze = (t.freeze + 0.011f * cfg.fxFreezeSpeed).coerceAtMost(1f)
                        }
                    }
                }
                K_SPLASH -> {
                    p.life -= dt; p.vy += 900f * d * dt; p.x += p.vx * dt; p.y += p.vy * dt
                    if (p.life <= 0f) dead = true
                }
                K_BLOB -> {
                    val t = p.tid?.let { fx.targets[it] }
                    if (t == null || t.gone) {
                        p.kind = K_ACID; p.tid = null; p.vx = 0f; p.vy = 200f * d
                    } else {
                        t.blobs++
                        p.x = t.rect.left + p.ox * t.rect.width
                        p.y = t.surfaceY(p.x) + 1f * d
                        if (t.melt < 1f) {
                            t.melt = (t.melt + dt * 0.045f * cfg.fxMeltSpeed).coerceAtMost(1f)
                            if (t.melt >= 1f) t.meltRestoreAt = now + restoreMs
                        }
                    }
                }
                K_ICE -> {
                    p.life -= dt; p.vy += 1500f * d * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.rot += p.vr * dt
                    if (p.life <= 0f) dead = true
                }
                K_DRIP -> {
                    p.life -= dt; p.vy += 700f * d * dt; p.y += p.vy * dt
                    if (p.life <= 0f) dead = true
                }
                K_SUN -> {
                    p.life += dt; p.x += p.vx * dt; p.y += p.vy * dt
                    if (p.life >= SUN_LIFE) dead = true
                }
            }
            if (dead) ps.removeAt(i)
            i--
        }

        for (t in targets) {
            if (!t.frozen && t.freeze > 0f) t.freeze = (t.freeze - 0.012f * dt).coerceAtLeast(0f)
            if (t.snow > 0f && weather != 2 && !t.frozen) t.snow = (t.snow - 0.05f * dt).coerceAtLeast(0f)
            if (t.snow > 0f && abs(tilt) > 0.35f && !t.frozen) t.snow = (t.snow - abs(tilt) * 0.5f * dt).coerceAtLeast(0f)   // eğilince kar kayar
            if (t.melt >= 1f && t.meltRestoreAt == 0L) t.meltRestoreAt = now + restoreMs
            if (t.melt > 0f) {
                if (t.meltRestoreAt > 0L && now >= t.meltRestoreAt) {
                    t.melt -= 0.6f * dt
                    if (t.melt <= 0f) { t.melt = 0f; t.meltRestoreAt = 0L }
                } else if (t.blobs == 0 && t.melt < 1f) {
                    t.melt = (t.melt - 0.05f * dt).coerceAtLeast(0f)
                }
                if (t.melt in 0.08f..0.99f && f() < 6f * dt * t.melt) {
                    val dx = t.rect.left + f() * t.rect.width
                    val dp = P(K_DRIP, dx, t.frontY(dx) + 2f * d, 0f, 20f * d, 0.9f, 1.8f * d)
                    ps.add(dp)
                }
            }
            if (t.away && t.awayRestoreAt > 0L && now >= t.awayRestoreAt) { t.away = false; t.awayRestoreAt = 0L }
        }

        // Portal olayları kendi parçacıklarını yönetir
        if (weather >= FXW_METEOR) scenes.step(weather, dt, now, W, H, d, inten, targets, fx.targets, tilt, shook, cue)
        else scenes.idle(targets)
        // Ada sakinleri: her olayda (olay kapalıyken de) yaşar
        if (cfg.more.fxPets) pets.step(cfg.more.fxPetCount, weather, dt, now, W, H, d, targets, fx.targets, scenes, tilt, shook, cue, flash > 0.6f)
        else pets.clear()

        if (weather == 1 && cfg.fxLightning) {
            boltIn -= dt
            if (boltIn <= 0f) {
                boltIn = cfg.fxLightningSec * (0.6f + f() * 0.8f)
                val x = (0.08f + f() * 0.84f) * W
                var tgt: FxTarget? = null
                for (t in targets) {
                    if (t.gone || t.frozen) continue
                    val tr = t.tight
                    if (x >= tr.left && x <= tr.right && t.topAt(x) != null && (tgt == null || tr.top < tgt.tight.top)) tgt = t
                }
                val endY = tgt?.topAt(x) ?: (H * 0.92f)
                bolt = makeBolt(x, endY, d); flash = 1f
                if (tgt != null) strike(tgt)
            }
        }
        if (flash > 0f) flash = (flash - dt * 3.2f).coerceAtLeast(0f)
        bolt?.let { it.t -= dt * 3.5f; if (it.t <= 0f) bolt = null }
    }
}

// ---------------------------------------------------------------- Kar yığını ve buz çizimi
/** Öğenin içeriğinin üstünde (harflerin, simgelerin, çubuğun tepesinde) biriken yumuşak kar. Boş alanda kar tutmaz. */
private fun DrawScope.drawSnowMound(t: FxTarget, d: Float) {
    val s = t.snow.coerceIn(0f, 1f)
    val n = t.cols()
    val cw = t.colW()
    val maxH = s * 8f * d
    val sd = t.id.hashCode()
    val a1 = (sd and 7).toFloat(); val a2 = ((sd shr 4) and 7).toFloat()
    val brush = Brush.verticalGradient(listOf(Pal.c("fx_white").copy(alpha = 0.97f), Pal.c("fx_ice_d").copy(alpha = 0.95f)),
        startY = t.tight.top - maxH, endY = t.tight.bottom)
    var i = 0
    while (i < n) {
        if (t.colTop(i) == null) { i++; continue }
        var j = i
        while (j < n && t.colTop(j) != null) j++
        val runW = (j - i) * cw
        val k = (runW / (16f * d)).coerceIn(0.25f, 1f)          // dar yüzeyde (ince harf) daha az kar tutunur
        val path = Path()
        path.moveTo(t.rect.left + i * cw, t.colTop(i)!! + 1f * d)
        for (c in i until j) {
            val u = (c - i + 0.5f) / (j - i)
            val dome = sin(PI.toFloat() * u).pow(0.55f)
            val bump = 1f + 0.18f * sin(c * 0.7f + a1) + 0.10f * sin(c * 1.9f + a2)
            path.lineTo(t.rect.left + (c + 0.5f) * cw, t.colTop(c)!! - maxH * k * dome * bump)
        }
        path.lineTo(t.rect.left + j * cw, t.colTop(j - 1)!! + 1f * d)
        for (c in j - 1 downTo i) path.lineTo(t.rect.left + (c + 0.5f) * cw, t.colTop(c)!! + 1.5f * d)
        path.close()
        drawPath(path, brush)
        i = j
    }
}

/** Don: kenarlardan içeri doğru büyüyen kırağı kristalleri, tamamen donunca parlak buz tabakası, sarkıtlar ve çatlaklar. */
private fun DrawScope.drawFrost(t: FxTarget, d: Float, thawTaps: Int) {
    val r0 = t.tight                                  // yalnızca içeriğin kapladığı alan donar (boş kutu değil)
    val r = Rect(r0.left - 3f * d, r0.top - 3f * d, r0.right + 3f * d, r0.bottom + 3f * d)
    val a = t.freeze.coerceIn(0f, 1f)
    val corner = 10f * d
    val clip = Path().apply { addRoundRect(RoundRect(r, CornerRadius(corner))) }
    val ref = min(r.width, r.height).coerceAtLeast(14f * d)
    clipPath(clip) {
        // buzlu cam gibi hafif mavi tonlama
        drawRect(Pal.c("fx_ice_b").copy(alpha = 0.03f + 0.30f * a * a), r.topLeft, r.size)
        // kenarlarda yoğun kırağı bandı (içe doğru yumuşak)
        drawRoundRect(Pal.c("fx_white").copy(alpha = 0.10f + 0.12f * a), r.topLeft, r.size, CornerRadius(corner), style = Stroke((10f + 44f * a) * d))
        drawRoundRect(Pal.c("fx_white").copy(alpha = 0.18f + 0.30f * a), r.topLeft, r.size, CornerRadius(corner), style = Stroke((3f + 16f * a) * d))
        // dallı kırağı kristalleri (kenardan içeri büyür)
        val rr = java.util.Random(t.id.hashCode().toLong())
        for (k in 0 until 34) {
            val edge = rr.nextInt(4)
            val u = rr.nextFloat()
            val thresh = rr.nextFloat() * 0.75f
            val jit = (rr.nextFloat() - 0.5f) * 0.7f
            val lenF = 0.15f + 0.40f * rr.nextFloat()
            val grow = ((a - thresh) / 0.35f).coerceIn(0f, 1f)
            if (grow <= 0f) continue
            val base: Offset
            val ang: Float
            when (edge) {
                0 -> { base = Offset(r.left + r.width * u, r.top); ang = PI.toFloat() / 2f + jit }
                1 -> { base = Offset(r.left + r.width * u, r.bottom); ang = -PI.toFloat() / 2f + jit }
                2 -> { base = Offset(r.left, r.top + r.height * u); ang = jit }
                else -> { base = Offset(r.right, r.top + r.height * u); ang = PI.toFloat() + jit }
            }
            val len = lenF * ref * 1.6f * grow
            val end = Offset(base.x + cos(ang) * len, base.y + sin(ang) * len)
            val col = Pal.c("fx_white").copy(alpha = 0.45f + 0.4f * grow)
            drawLine(col, base, end, 1.1f * d, StrokeCap.Round)
            for (bf in floatArrayOf(0.4f, 0.7f)) {
                val bp = Offset(base.x + (end.x - base.x) * bf, base.y + (end.y - base.y) * bf)
                val bl = len * 0.35f * (1.1f - bf)
                for (sg in intArrayOf(-1, 1)) {
                    val ba = ang + sg * 0.95f
                    drawLine(col, bp, Offset(bp.x + cos(ba) * bl, bp.y + sin(ba) * bl), 0.8f * d, StrokeCap.Round)
                }
            }
        }
        if (t.frozen) {
            // parlak buz yüzeyi: çapraz yansıma
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Pal.c("fx_white").copy(alpha = 0.30f), Color.Transparent),
                    start = Offset(r.left, r.top), end = Offset(r.left + r.width * 0.6f, r.top + r.height * 1.3f),
                ),
                r.topLeft, r.size,
            )
            // dokundukça yayılan çatlaklar
            if (t.taps > 0) {
                val need = thawTaps.coerceAtLeast(1)
                val cr = t.iceCrack?.takeIf { t.iceCrackW == r.width }
                    ?: buildCrack(java.util.Random(t.id.hashCode().toLong()), r.center, max(r.width, r.height) * 0.7f, d, 0L)
                        .also { t.iceCrack = it; t.iceCrackW = r.width }
                drawCrack(cr, (t.taps.toFloat() / need).coerceIn(0f, 1f), d, Pal.c("fx_crack").copy(alpha = 0.65f), Pal.c("fx_white").copy(alpha = 0.95f))
            }
        }
        drawRoundRect(Pal.c("fx_white").copy(alpha = a * 0.85f), r.topLeft, r.size, CornerRadius(corner), style = Stroke(1.6f * d))
    }
    if (a > 0.55f) {
        // alt kenardan sarkan buz sarkıtları
        val rr = java.util.Random(t.id.hashCode().toLong() + 7L)
        val grow = ((a - 0.55f) / 0.45f).coerceIn(0f, 1f)
        val n = (3 + (r.width / (28f * d)).toInt()).coerceAtMost(8)
        for (i in 0 until n) {
            val x = r.left + r.width * ((i + 0.5f + (rr.nextFloat() - 0.5f) * 0.6f) / n)
            val len = (3f + rr.nextFloat() * 9f) * d * grow
            val tri = Path().apply {
                moveTo(x - 1.9f * d, r.bottom - 1f * d); lineTo(x + 1.9f * d, r.bottom - 1f * d); lineTo(x, r.bottom + len); close()
            }
            drawPath(tri, Pal.c("fx_ice_c").copy(alpha = 0.88f))
            drawLine(Pal.c("fx_white").copy(alpha = 0.8f), Offset(x - 0.5f * d, r.bottom), Offset(x - 0.2f * d, r.bottom + len * 0.7f), 0.7f * d, StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- Efekt katmanı (adanın içinde, içeriğin üstünde)
@Composable
fun FxLayer(
    fx: IslandFxState,
    cfg: IslandSettings,
    progress: IslandProgress,
    live: Boolean,
    bodyColor: () -> Color,
) {
    val dens = LocalDensity.current
    val d = dens.density
    val scope = rememberCoroutineScope()
    val sim = remember { FxSim() }
    val cfgNow by rememberUpdatedState(cfg)
    val bodyNow by rememberUpdatedState(bodyColor)
    var tick by remember { mutableLongStateOf(0L) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val expandedFull by remember { derivedStateOf { progress.mean > 0.97f } }
    val scanBusy = remember { BooleanArray(1) }
    SideEffect { fx.live = live }
    // Eğilen dünya: eğim ve sallama sensörü yalnızca panel tam açıkken dinlenir
    if (cfg.more.fxTilt && expandedFull) FxMotionSensor()

    /** Sıradaki öğenin piksellerini tarar ve gerçek şeklini çıkarır. */
    fun scanNext(now: Long) {
        if (scanBusy[0]) return
        var tgt: FxTarget? = null
        for (t in fx.targets.values) {
            if (t.away || t.rect.width <= 0f || t.layer == null) continue
            val wait = if (t.shapeOk) 1000L else 250L
            if (now - t.shapeAt < wait) continue
            if (tgt == null || t.shapeAt < tgt.shapeAt) tgt = t
        }
        val t = tgt ?: return
        val layer = t.layer ?: return
        val w = t.rect.width; val h = t.rect.height
        t.shapeAt = now
        scanBusy[0] = true
        scope.launch {
            try {
                val bmp = runCatching { layer.toImageBitmap() }.getOrNull()
                if (bmp != null) {
                    val sh = withContext(Dispatchers.Default) { runCatching { scanFxShape(bmp, w, h, 2f * d) }.getOrNull() }
                    if (sh != null) {
                        t.shapeTop = sh.top; t.shapeBot = sh.bot; t.shapeTight = sh.tight
                        t.shapeForW = w; t.shapeForH = h
                    }
                }
            } finally {
                scanBusy[0] = false
            }
        }
    }

    fun resetAll() {
        for (t in fx.targets.values) t.reset()
        sim.clear(); fx.cracks.clear()
        if (fx.live) FxBridge.clear()
    }
    DisposableEffect(Unit) { onDispose { resetAll() } }
    LaunchedEffect(expandedFull) { if (!expandedFull) resetAll() }

    fun detach(t: FxTarget) {
        if (t.gone) return
        val restoreMs = (cfgNow.fxRestoreSec * 1000f).toLong()
        if (!fx.live) {
            t.away = true; t.awayRestoreAt = System.currentTimeMillis() + restoreMs
            return
        }
        val layer = t.layer
        scope.launch {
            val bmp = layer?.let { runCatching { it.toImageBitmap() }.getOrNull() }
            val o = fx.screenOrigin()
            t.away = true
            if (bmp == null || o == null) {
                t.awayRestoreAt = System.currentTimeMillis() + restoreMs
                return@launch
            }
            FxBridge.originX = o.x; FxBridge.originY = o.y
            FxBridge.restoreMs = restoreMs
            val c = t.rect.center
            val p = FxPiece(0, bmp, null, Color.Unspecified, bmp.width / 2f, bmp.height / 2f,
                o.x + c.x, o.y + c.y, c.x, c.y)
            p.vx = (sim.f() - 0.5f) * 220f * d
            p.vy = -(150f + sim.f() * 200f) * d
            p.vr = (sim.f() - 0.5f) * 220f
            p.onHome = { t.away = false }
            FxBridge.add(p)
        }
    }

    fun shatter() {
        val o = fx.screenOrigin() ?: return
        val W = size.width.toFloat(); val H = size.height.toFloat()
        if (W <= 0f || H <= 0f) return
        FxBridge.originX = o.x; FxBridge.originY = o.y
        FxBridge.restoreMs = (cfgNow.fxRestoreSec * 1000f).toLong()
        val cols = 8; val rows = 3
        val vx = Array(rows + 1) { r -> FloatArray(cols + 1) { c ->
            val base = W * c / cols
            if (c == 0 || c == cols) base else base + (sim.f() - 0.5f) * (W / cols) * 0.6f
        } }
        val vy = Array(rows + 1) { r -> FloatArray(cols + 1) { c ->
            val base = H * r / rows
            if (r == 0 || r == rows) base else base + (sim.f() - 0.5f) * (H / rows) * 0.6f
        } }
        val base = bodyNow().copy(alpha = 1f)
        // Adanın gerçek (kavisli) dış hattı: açık adanın köşe yarıçapı (islandCornerPx ile aynı). Üçgenler bu şekle
        // kırpılır; yoksa parçalar geri toplanınca köşeleri sivri bir dikdörtgen oluşuyordu.
        val outline = roundRectPolygon(W, H, min(min(36f * d, H / 2f), W / 2f))
        for (r in 0 until rows) for (c in 0 until cols) {
            val a = Offset(vx[r][c], vy[r][c]); val b = Offset(vx[r][c + 1], vy[r][c + 1])
            val cc = Offset(vx[r + 1][c + 1], vy[r + 1][c + 1]); val dd = Offset(vx[r + 1][c], vy[r + 1][c])
            val tris = if (sim.rnd.nextBoolean()) listOf(listOf(a, b, cc), listOf(a, cc, dd)) else listOf(listOf(a, b, dd), listOf(b, cc, dd))
            for (tri0 in tris) {
                val tri = clipConvex(tri0, outline)
                if (tri.size < 3 || polyArea(tri) < 1f) continue          // tamamen köşenin dışında kalan parça
                val g = Offset(tri.sumOf { it.x.toDouble() }.toFloat() / tri.size, tri.sumOf { it.y.toDouble() }.toFloat() / tri.size)
                val rel = tri.map { Offset(it.x - g.x, it.y - g.y) }
                val rad = rel.maxOf { max(abs(it.x), abs(it.y)) }
                val col = lerp(base, Pal.c("fx_white"), 0.05f + sim.f() * 0.09f)
                val p = FxPiece(1, null, rel, col, rad, rad, o.x + g.x, o.y + g.y, g.x, g.y)
                p.vx = (sim.f() - 0.5f) * 700f * d
                p.vy = -(sim.f() * 500f) * d
                p.vr = (sim.f() - 0.5f) * 500f
                p.delayMs = (sim.f() * 1500f).toLong()
                FxBridge.add(p)
            }
        }
        FxBridge.islandHidden = true
        FxBridge.onShardsDone = { sim.shattered = false; sim.autoStart = 0L; sim.autoCount = 0; fx.cracks.clear() }
    }

    fun shatterIce(t: FxTarget) {
        sim.iceBurst(t, d)
        t.freeze = 0f; t.taps = 0; t.snow = 0f
        t.coolUntil = System.currentTimeMillis() + 6000L
    }

    fun onBlockedTap(t: FxTarget) {
        if (t.frozen) {
            t.taps++
            if (t.taps >= cfgNow.fxThawTaps) shatterIce(t)
        }
    }

    fun onBreakTap(pos: Offset) {
        if (FxBridge.islandHidden) return
        val now = System.currentTimeMillis()
        var hitT: FxTarget? = null
        for (t in fx.targets.values) if (!t.gone && pos in t.rect) hitT = t
        fx.cracks.add(sim.makeCrack(pos, d, now))
        if (fx.cracks.size > 24) fx.cracks.removeAt(0)
        hitT?.let { detach(it) }
    }

    LaunchedEffect(expandedFull, cfg.fxWeather, cfg.fxBreak) {
        if (!expandedFull) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                val dt = if (last == 0L) 0.016f else ((n - last) / 1_000_000_000f).coerceIn(0f, 0.05f)
                last = n
                val now = System.currentTimeMillis()
                val c = cfgNow
                val W = size.width.toFloat(); val H = size.height.toFloat()
                if (W > 0f && H > 0f) {
                    scanNext(now)
                    if (fx.live) fx.screenOrigin()?.let { FxBridge.originX = it.x; FxBridge.originY = it.y }
                    sim.step(dt, now, W, H, d, c, fx) { t -> detach(t) }
                    if (c.fxBreak) {
                        val all = fx.targets.size >= 2 && fx.targets.values.all { it.gone }
                        if (!all) sim.armed = true
                        if (all && sim.armed && sim.autoStart == 0L) { sim.autoStart = now; sim.autoNext = now; sim.autoCount = 0; sim.armed = false }
                        if (sim.autoStart != 0L && !sim.shattered) {
                            if (now >= sim.autoNext && sim.autoCount < 9) {
                                val p = Offset((0.05f + sim.f() * 0.9f) * W, (0.1f + sim.f() * 0.8f) * H)
                                fx.cracks.add(sim.makeCrack(p, d, now))
                                sim.autoCount++; sim.autoNext = now + 330L
                            }
                            if (now - sim.autoStart > 3600L) {
                                if (fx.live) { sim.shattered = true; shatter() }
                                else if (now - sim.autoStart > 5200L) resetAll()   // önizleme: parça düşmez, her şey eski haline döner
                            }
                        }
                    }
                }
                tick = n
            }
        }
    }

    val blockedList by remember {
        derivedStateOf { fx.targets.values.filter { it.blocked } }
    }
    val breakOn = cfg.fxBreak && expandedFull && !FxBridge.islandHidden

    Box(Modifier.fillMaxSize().onSizeChanged { size = it }) {
        Canvas(Modifier.fillMaxSize()) {
            @Suppress("UNUSED_VARIABLE") val tk = tick
            val now = System.currentTimeMillis()

            // ---- öğe üstü görünümler: kar yığını, buz
            for (t in fx.targets.values) {
                val r = t.rect
                if (r.width <= 0f) continue
                if (t.snow > 0.02f && !t.away) drawSnowMound(t, d)
                if (t.freeze > 0.02f && !t.away) drawFrost(t, d, cfg.fxThawTaps)
            }

            // ---- güneş: sağ üst köşeden yayılan, yavaşça nabız gibi atan sıcak ışık
            if (cfg.fxWeather == 4) {
                val pulse = 0.75f + 0.25f * sin(now % 4000L / 4000f * 2f * PI.toFloat())
                val c = Offset(size.width * 0.92f, -size.height * 0.05f)
                val rad = maxOf(size.width, size.height) * 0.75f
                drawCircle(
                    Brush.radialGradient(listOf(Color(0xFFFFD27A).copy(alpha = 0.32f * pulse), Color.Transparent), center = c, radius = rad),
                    radius = rad, center = c,
                )
            }

            // ---- parçacıklar
            for (p in sim.ps) {
                when (p.kind) {
                    K_RAIN -> drawLine(Pal.c("fx_rain").copy(alpha = 0.6f), Offset(p.x, p.y), Offset(p.x - p.vx * 0.022f, p.y - p.vy * 0.022f), 1.4f * d, StrokeCap.Round)
                    K_ACID -> drawLine(Pal.c("fx_acid").copy(alpha = 0.85f), Offset(p.x, p.y), Offset(p.x - p.vx * 0.022f, p.y - p.vy * 0.022f), 1.8f * d, StrokeCap.Round)
                    K_FLAKE -> drawCircle(Pal.c("fx_white").copy(alpha = 0.9f), p.size, Offset(p.x, p.y))
                    K_SPLASH -> drawCircle(Pal.c("fx_white").copy(alpha = (p.life / 0.28f).coerceIn(0f, 1f) * 0.7f), p.size, Offset(p.x, p.y))
                    K_BLOB -> {
                        drawOval(Pal.c("fx_acid_blob").copy(alpha = 0.95f), Offset(p.x - p.size * 0.85f, p.y - p.size * 0.2f), Size(p.size * 1.7f, p.size * 2.3f))
                        drawCircle(Pal.c("fx_white").copy(alpha = 0.4f), p.size * 0.3f, Offset(p.x - p.size * 0.3f, p.y + p.size * 0.35f))
                    }
                    K_ICE -> rotate(p.rot, Offset(p.x, p.y)) {
                        drawRect(Pal.c("fx_ice_a").copy(alpha = (p.life / 0.9f).coerceIn(0f, 1f) * 0.9f), Offset(p.x - p.size / 2f, p.y - p.size * 0.3f), Size(p.size, p.size * 0.6f))
                    }
                    K_DRIP -> drawLine(Pal.c("fx_acid").copy(alpha = (p.life / 0.9f).coerceIn(0f, 1f)), Offset(p.x, p.y - p.vy * 0.03f), Offset(p.x, p.y), p.size * 1.3f, StrokeCap.Round)
                    K_SUN -> {
                        val a = sin(PI.toFloat() * (p.life / SUN_LIFE).coerceIn(0f, 1f))
                        val col = Color(0xFFFFE39A).copy(alpha = a * 0.95f)
                        val l = p.size * (1.6f + a)
                        drawLine(col, Offset(p.x - l, p.y), Offset(p.x + l, p.y), 0.9f * d, StrokeCap.Round)
                        drawLine(col, Offset(p.x, p.y - l), Offset(p.x, p.y + l), 0.9f * d, StrokeCap.Round)
                        drawCircle(col, p.size * 0.55f, Offset(p.x, p.y))
                    }
                }
            }

            // ---- portal olayları (meteor, sualtı, havai fişek, ateşböceği, kum, kutup ışığı, elektrik, tipi)
            with(sim.scenes) { render(d, fx.targets.values) }
            if (cfg.more.fxPets) with(sim.pets) { render(d) }

            // ---- çatlaklar (cam kırığı)
            for (cr in fx.cracks) {
                val k = ((now - cr.bornMs) / 900f).coerceIn(0f, 1f)
                drawCrack(cr, k, d, Pal.c("fx_shade").copy(alpha = 0.6f), Pal.c("fx_white").copy(alpha = 0.8f))
            }

            // ---- şimşek
            sim.bolt?.let { b ->
                val a = b.t.coerceIn(0f, 1f)
                val path = Path().apply { b.pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
                drawPath(path, Pal.c("fx_bolt").copy(alpha = 0.45f * a), style = Stroke(7f * d, cap = StrokeCap.Round))
                drawPath(path, Pal.c("fx_white").copy(alpha = a), style = Stroke(2.4f * d, cap = StrokeCap.Round))
            }
            if (sim.flash > 0f) drawRect(Pal.c("fx_white").copy(alpha = 0.32f * sim.flash))
        }

        // Kırma modu: hızlı dokunuş kırar; kaydırma ve uzun basma adanın kendi işlevine gider
        if (breakOn) {
            Box(
                Modifier.fillMaxSize().pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val up = waitForUpOrCancellation()
                        if (up != null && (up.position - down.position).getDistance() < 24.dp.toPx() &&
                            up.uptimeMillis - down.uptimeMillis < 350L
                        ) {
                            up.consume()
                            onBreakTap(up.position)
                        }
                    }
                }
            )
        }

        // Donmuş / erimiş / kopmuş öğelerin altındaki gerçek düğmeler çalışmaz: dokunuşu bu katman alır
        for (t in blockedList) {
            key(t.id) {
                Box(
                    Modifier
                        .offset { IntOffset(t.rect.left.roundToInt(), t.rect.top.roundToInt()) }
                        .size(with(dens) { t.rect.width.toDp() }, with(dens) { t.rect.height.toDp() })
                        .pointerInput(t) { detectTapGestures { onBlockedTap(t) } }
                )
            }
        }
    }
}


// ---------------------------------------------------------------- Kırılma: parçaları adanın kavisli şekline kırpma
/** (0,0)-(w,h) yuvarlak dikdörtgen, saat yönünde (ekran koordinatı) çokgen olarak. */
internal fun roundRectPolygon(w: Float, h: Float, r: Float, seg: Int = 10): List<Offset> {
    val out = ArrayList<Offset>()
    // köşe merkezleri ve başlangıç açıları: sağ üst, sağ alt, sol alt, sol üst
    val cs = listOf(Offset(w - r, r) to -90f, Offset(w - r, h - r) to 0f, Offset(r, h - r) to 90f, Offset(r, r) to 180f)
    for ((c, a0) in cs) {
        for (i in 0..seg) {
            val a = Math.toRadians((a0 + 90f * i / seg).toDouble())
            out.add(Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        }
    }
    return out
}

private fun cross(a: Offset, b: Offset, p: Offset): Float = (b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)

/** Sutherland–Hodgman: [subject] çokgenini dışbükey [clip] çokgeninin içine kırpar (her ikisi de aynı yönde değilse de çalışır). */
internal fun clipConvex(subject: List<Offset>, clip: List<Offset>): List<Offset> {
    val sign = if (polySigned(clip) >= 0f) 1f else -1f
    var out = subject
    for (i in clip.indices) {
        if (out.isEmpty()) break
        val a = clip[i]; val b = clip[(i + 1) % clip.size]
        val input = out
        val res = ArrayList<Offset>()
        for (j in input.indices) {
            val p = input[j]; val q = input[(j + 1) % input.size]
            val pin = cross(a, b, p) * sign >= 0f
            val qin = cross(a, b, q) * sign >= 0f
            if (pin) res.add(p)
            if (pin != qin) {
                val dp = cross(a, b, p); val dq = cross(a, b, q)
                val t = dp / (dp - dq)
                res.add(Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t))
            }
        }
        out = res
    }
    return out
}

private fun polySigned(p: List<Offset>): Float {
    var s = 0f
    for (i in p.indices) { val a = p[i]; val b = p[(i + 1) % p.size]; s += a.x * b.y - b.x * a.y }
    return s / 2f
}

internal fun polyArea(p: List<Offset>): Float = abs(polySigned(p))


// ---------------------------------------------------------------- Gerçek şekil taraması
internal class FxShape(val top: FloatArray, val bot: FloatArray, val tight: Rect)

/**
 * Öğenin görüntüsünü sütunlara böler; her sütunda saydam olmayan ilk ve son satırı bulur.
 * Sonuç öğenin yerel px ölçüsündedir (w x h). Arka planı saydam yazılar, simgeler ve çubuklar gerçek biçimiyle çıkar.
 */
internal fun scanFxShape(bmp: ImageBitmap, w: Float, h: Float, colPx: Float): FxShape? {
    val ab = bmp.asAndroidBitmap()
    val src = if (ab.config == android.graphics.Bitmap.Config.HARDWARE) (ab.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return null) else ab
    val bw = src.width
    val bh = src.height
    if (bw <= 0 || bh <= 0 || w <= 0f || h <= 0f) return null
    val px = IntArray(bw * bh)
    src.getPixels(px, 0, bw, 0, 0, bw, bh)
    if (src !== ab) src.recycle()
    val cols = max(1, (w / colPx).roundToInt())
    val first = IntArray(cols) { -1 }
    val last = IntArray(cols) { -1 }
    for (y in 0 until bh) {
        val row = y * bw
        for (x in 0 until bw) {
            if ((px[row + x] ushr 24) > 48) {
                val c = (x * cols / bw).coerceIn(0, cols - 1)
                if (first[c] < 0) first[c] = y
                last[c] = y
            }
        }
    }
    val sy = h / bh
    val top = FloatArray(cols) { if (first[it] < 0) Float.NaN else first[it] * sy }
    val bot = FloatArray(cols) { if (last[it] < 0) Float.NaN else (last[it] + 1) * sy }
    var l = -1; var r = -1
    var tp = Float.MAX_VALUE; var bt = 0f
    for (c in 0 until cols) if (first[c] >= 0) {
        if (l < 0) l = c
        r = c
        tp = min(tp, top[c]); bt = max(bt, bot[c])
    }
    val cw = w / cols
    val tight = if (l < 0) Rect.Zero else Rect(l * cw, tp, (r + 1) * cw, bt)
    return FxShape(top, bot, tight)
}
