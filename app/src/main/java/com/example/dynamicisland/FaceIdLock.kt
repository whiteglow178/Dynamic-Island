package com.example.dynamicisland

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp as colorLerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * KİLİT EKRANI FACE ID ANİMASYONU
 * Ekran açılıp telefon kilitliyken ada kamera deliğinden yuvarlak köşeli bir kareye dönüşür; içinde
 * Face ID yüzü nabız gibi atar, sonra halka 3B dönmeye başlar. Dönme, ekran kaydındaki (1000022474.mp4)
 * gerçek kareler şeffaf sprite olarak çıkarılıp (res/drawable-nodpi/faceid_tumble.png, 68 kare) birebir oynatılır.
 *  - Kilit ekranından ana ekrana geçildiyse (kilit açıldıysa): düz halka içinde ✓ çizilir, kare hap şekline küçülür.
 *  - Geçilmediyse: yüz griye döner ve tüm kare yatay titreyip (sönümlü ~4 Hz) söner, sonra hap şekline küçülür.
 * Ek izin gerekmez; LockDot ile aynı şekilde erişilebilirlik overlay penceresi kullanılır.
 * Kilit açılırsa (USER_PRESENT) animasyon karar anına hızlı sarılır.
 */

/** Zaman çizelgesi (saniye). T = animasyon başından beri; v = T - EXP (yüz görününce 0). */
object FaceIdTl {
    const val EXP = 0.25f           // hap -> kare açılma süresi
    const val FEAT_FADE0 = 0.70f    // göz / burun / ağız solar
    const val FEAT_FADE1 = 0.90f
    const val SPR0 = 0.85f          // 3B dönme sprite'ı başlar (köşe parantezleri halkaya dönüşüp döner)
    const val SPR_FPS = 60f         // sprite kare hızı: video yavaşlatılmıştı, gerçek hız ~2.5 kat (dönme ~1.8 sn)
    const val SPR_N = 68            // sprite kare sayısı
    const val SPR1 = SPR0 + (SPR_N - 1) / SPR_FPS   // dönme biter: düz halka
    const val DECIDE_V = SPR1 + 0.15f               // karar anı: kilit açıldıysa ✓, açılmadıysa gri titreme
    const val CHECK0 = SPR1 + 0.30f // ✓ çizilmeye başlar (önce orta nokta)
    const val CHECK1 = CHECK0 + 0.45f
    const val FADE0 = CHECK1 + 0.55f   // içerik söner
    const val FADE1 = FADE0 + 0.22f    // kare -> hap küçülme başlar
    const val END_V = FADE1 + 0.28f
    const val END = EXP + END_V
    const val FF_V = DECIDE_V       // kilit açılınca buraya kadar 3 kat hızlı oynatılır
    // başarısız (kilit açılmadı) akış: karar anından (failT) itibaren geçen süre
    const val F_FADE0 = 1.00f
    const val F_FADE1 = 1.25f
    const val F_END = 1.60f
    const val SHAKE_HZ = 4f
    const val SPRITE_SQ = 207f      // videodaki karenin px genişliği (sprite ölçeği)
    const val SPRITE_CELL = 180     // sprite hücre boyutu (px)
    const val SPRITE_COLS = 10
}

/** Çizim ölçüleri (px). Pencere (sq + 2*pad) kare; hap üst kenardan (ters adada alt kenardan) büyür. */
class FaceIdGeo(
    val pillW: Float, val pillH: Float, val sq: Float, val pad: Float, val inverted: Boolean,
) {
    val side: Float get() = sq + 2f * pad
}

/** Adanın ekrandaki konumu (px): merkez x, kapalı adanın merkez y'si, kapalı boyut, ters ada mı. */
class FaceIdAnchor(val cx: Float, val cy: Float, val pillW: Float, val pillH: Float, val inverted: Boolean)

// ------------------------------------------------------------------ küçük matematik yardımcıları
private fun fidSeg(v: Float, a: Float, b: Float): Float = ((v - a) / (b - a)).coerceIn(0f, 1f)
private fun fidSmooth(x: Float): Float = x * x * (3f - 2f * x)
private fun fidOut(x: Float): Float = 1f - (1f - x.coerceIn(0f, 1f)).pow(3)
private fun fidLerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** Yüz nabzı: ekran kaydında ~0.85 sn periyotla parlayıp sönüyor (v=0'da parlak, 0.70 ve 1.55'te en sönük). */
private fun fidPulse(v: Float): Float = 0.625f + 0.375f * cos(2f * PI.toFloat() * (v - 0.275f) / 0.85f)

// ------------------------------------------------------------------ köşe parantezi <-> yay noktaları
private const val FID_N = 28           // her köşe için örnek sayısı
private const val FID_LEG = 0.46f      // parantez kolu uzunluğu (a birimi)
private const val FID_RC = 0.30f       // parantez köşe yarıçapı
private const val FID_RING = 0.84f     // halka yarıçapı (a birimi)

/** Sol üst parantez üzerinde t(0..1) konumu; a=1 birim kutusunda. */
private fun fidBracketTL(t: Float): Offset {
    val l1 = FID_LEG - FID_RC
    val la = (PI.toFloat() / 2f) * FID_RC
    val total = 2f * l1 + la
    val s = t * total
    return when {
        s <= l1 -> Offset(-1f, -1f + FID_LEG - s)
        s <= l1 + la -> {
            val ang = PI.toFloat() + (s - l1) / FID_RC          // 180° -> 270°
            Offset(-1f + FID_RC + FID_RC * cos(ang), -1f + FID_RC + FID_RC * sin(ang))
        }
        else -> Offset(-1f + FID_RC + (s - l1 - la), -1f)
    }
}

/**
 * Dört köşe için noktalar: m=0 parantez, m=1 daire yayı (yarı açı delta). Sonra 3B döndürülüp
 * kameraya izdüşürülür; birim a=1 kutusundan px'e çevrilir.
 */
private fun fidRingPaths(
    cx: Float, cy: Float, a: Float, m: Float, delta: Float, ax: Float, ay: Float, az: Float,
): List<Path> {
    val cax = cos(ax); val sax = sin(ax)
    val cay = cos(ay); val say = sin(ay)
    val caz = cos(az); val saz = sin(az)
    val out = ArrayList<Path>(4)
    val signs = arrayOf(floatArrayOf(-1f, -1f), floatArrayOf(1f, -1f), floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
    for (sg in signs) {
        val sx = sg[0]; val sy = sg[1]
        val path = Path()
        for (i in 0..FID_N) {
            val t = i / FID_N.toFloat()
            val b = fidBracketTL(t)
            val phi = (225f * PI.toFloat() / 180f) - delta + 2f * delta * t
            val cxu = FID_RING * cos(phi); val cyu = FID_RING * sin(phi)
            var x = fidLerp(b.x, cxu, m); var y = fidLerp(b.y, cyu, m)
            x *= -sx; y *= -sy                       // sol üstten diğer köşelere aynala
            // 3B: Rx -> Ry -> Rz
            val z0 = 0f
            val y1 = y * cax - z0 * sax; val z1 = y * sax + z0 * cax
            val x2 = x * cay + z1 * say; val z2 = -x * say + z1 * cay
            val x3 = x2 * caz - y1 * saz; val y3 = x2 * saz + y1 * caz
            val k = 1f / (1f - z2 * 0.16f)           // hafif perspektif
            val px = cx + x3 * k * a; val py = cy + y3 * k * a
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        out.add(path)
    }
    return out
}

private fun DrawScope.fidStroke(p: Path, c: Color, w: Float) =
    drawPath(p, c, style = Stroke(width = w, cap = StrokeCap.Round, join = StrokeJoin.Round))

/** Göz / burun / ağız (parantezler hariç). */
private fun DrawScope.fidFeatures(cx: Float, cy: Float, a: Float, w: Float, c: Color) {
    if (c.alpha <= 0.004f) return
    fun pt(x: Float, y: Float) = Offset(cx + x * a, cy + y * a)
    val eyes = Path().apply {
        moveTo(pt(-0.37f, -0.38f).x, pt(-0.37f, -0.38f).y); lineTo(pt(-0.37f, -0.12f).x, pt(-0.37f, -0.12f).y)
        moveTo(pt(0.37f, -0.38f).x, pt(0.37f, -0.38f).y); lineTo(pt(0.37f, -0.12f).x, pt(0.37f, -0.12f).y)
    }
    val nose = Path().apply {
        moveTo(pt(0.06f, -0.30f).x, pt(0.06f, -0.30f).y)
        lineTo(pt(0.06f, 0.14f).x, pt(0.06f, 0.14f).y)
        val q = pt(0.06f, 0.25f); val e = pt(-0.09f, 0.22f)
        quadraticBezierTo(q.x, q.y, e.x, e.y)
    }
    val mouth = Path().apply {
        val s0 = pt(-0.40f, 0.40f); val q = pt(0f, 0.80f); val e = pt(0.40f, 0.40f)
        moveTo(s0.x, s0.y); quadraticBezierTo(q.x, q.y, e.x, e.y)
    }
    fidStroke(eyes, c, w); fidStroke(nose, c, w); fidStroke(mouth, c, w)
}

/** Yüz: 4 köşe parantezi (m=0) + özellikler. */
private fun DrawScope.fidFace(cx: Float, cy: Float, a: Float, w: Float, bracketC: Color, featC: Color) {
    if (bracketC.alpha > 0.004f) for (p in fidRingPaths(cx, cy, a, 0f, 0f, 0f, 0f, 0f)) fidStroke(p, bracketC, w)
    fidFeatures(cx, cy, a, w, featC)
}

private val FID_GRAY = Color(0xFF9A9AA0)

/**
 * Tüm animasyonu t saniyesinde çizer. Tuval (sq + 2*pad) karelik pencere/alandır.
 * failT >= 0: kilit açılmadı, animasyon o t anında gri titreme akışına geçti.
 */
fun DrawScope.drawFaceId(t: Float, s: IslandSettings, g: FaceIdGeo, sprite: ImageBitmap?, failT: Float = -1f) {
    val failed = failT >= 0f
    val fv = if (failed) t - failT else 0f
    val green = Color(s.faceIdColorArgb)
    val failMix = if (failed) fidSmooth(fidSeg(fv, 0.10f, 0.40f)) else 0f
    val col = colorLerp(green, FID_GRAY, failMix)
    val fadeA = if (failed) failT + FaceIdTl.F_FADE0 else FaceIdTl.EXP + FaceIdTl.FADE0
    val fadeB = if (failed) failT + FaceIdTl.F_FADE1 else FaceIdTl.EXP + FaceIdTl.FADE1
    val endT = if (failed) failT + FaceIdTl.F_END else FaceIdTl.EXP + FaceIdTl.END_V
    // sönümlü yatay titreme (tüm kare)
    val u = max(0f, fv - 0.08f)
    val shake = if (failed && fv < FaceIdTl.F_FADE1) g.sq * 0.05f * sin(2f * PI.toFloat() * FaceIdTl.SHAKE_HZ * u) * exp(-3.2f * u) else 0f
    translate(left = shake, top = 0f) {
        fidBody(t, g, sprite, green, col, failed, fv, fadeA, fadeB, endT)
    }
}

private fun DrawScope.fidBody(
    t: Float, g: FaceIdGeo, sprite: ImageBitmap?, green: Color, col: Color,
    failed: Boolean, fv: Float, fadeA: Float, fadeB: Float, endT: Float,
) {
    val cx = size.width / 2f
    val v = t - FaceIdTl.EXP
    val sq = g.sq

    // ---- 1) kara gövde: hap -> kare (açılma) ... kare -> hap (küçülme)
    val ew: Float; val eh: Float
    if (t < FaceIdTl.EXP) {
        ew = fidOut(t / FaceIdTl.EXP)
        eh = fidOut(fidSeg(t, 0.06f, FaceIdTl.EXP))
    } else if (t > fadeB) {
        val k = fidSeg(t, fadeB, endT)
        ew = 1f - fidOut(k)
        eh = 1f - fidSmooth(k)
    } else { ew = 1f; eh = 1f }
    val w = fidLerp(g.pillW, sq, ew)
    val h = fidLerp(g.pillH, sq, eh)
    val rad = min(fidLerp(g.pillH / 2f, sq * 0.27f, eh), min(w, h) / 2f)
    val left = cx - w / 2f
    val top = if (!g.inverted) g.pad else g.pad + sq - h
    drawRoundRect(Color.Black, Offset(left, top), Size(w, h), CornerRadius(rad))

    // ---- 2) içerik
    val appear = fidSeg(t, 0.12f, FaceIdTl.EXP)
    if (appear <= 0f || t > fadeB + 0.02f) return
    val ccy = g.pad + sq / 2f                // içerik her zaman karenin merkezinde
    val a = sq * 0.228f                      // yüz yarı genişliği
    val sw = sq * 0.029f                     // çizgi kalınlığı
    val ringR = sq * 0.205f                  // videodaki düz halkanın orta çizgi yarıçapı
    val vis = appear * (1f - fidSeg(t, fadeA, fadeB))

    // Kenarlıkta çok silik çizgi
    drawRoundRect(col.copy(alpha = 0.20f * vis), Offset(left, top), Size(w, h), CornerRadius(rad), style = Stroke(width = max(1f, sq * 0.007f)))

    // ---- 2a) Face ID yüzü
    if (!failed) {
        // nabız atan yüz; köşe parantezleri 3B dönme başlarken solar
        val p0 = FaceIdTl.SPR0 - 0.15f
        val pulseA = if (v < p0) fidPulse(v) else fidLerp(fidPulse(p0), 1f, fidSeg(v, p0, FaceIdTl.SPR0))
        val brA = 1f - fidSeg(v, FaceIdTl.SPR0 - 0.05f, FaceIdTl.SPR0 + 0.20f)
        val ftA = 1f - fidSeg(v, FaceIdTl.FEAT_FADE0, FaceIdTl.FEAT_FADE1)
        if (brA > 0.004f) fidFace(cx, ccy, a, sw, green.copy(alpha = pulseA * brA * vis), green.copy(alpha = pulseA * ftA * vis))
    } else {
        // başarısız: yüz geri gelir, griye döner
        val fa = 0.62f * fidSeg(fv, 0.04f, 0.28f) * vis
        fidFace(cx, ccy, a, sw, col.copy(alpha = fa), col.copy(alpha = fa))
    }

    // ---- 2b) 3B dönme sprite'ı (videodan birebir kareler), sonunda düz halka olarak kalır
    if (sprite != null && v >= FaceIdTl.SPR0) {
        val spA = vis * (1f - fidSeg(v, FaceIdTl.SPR1 - 0.30f, FaceIdTl.SPR1)) * (if (failed) 1f - fidSeg(fv, 0f, 0.20f) else 1f)
        if (spA > 0.004f) {
            val n = FaceIdTl.SPR_N
            val pos = ((v - FaceIdTl.SPR0) * FaceIdTl.SPR_FPS).coerceIn(0f, (n - 1).toFloat())
            val i0 = pos.toInt().coerceIn(0, n - 1)
            val i1 = min(i0 + 1, n - 1)
            val f = pos - i0
            val k = sq / FaceIdTl.SPRITE_SQ
            val cell = FaceIdTl.SPRITE_CELL
            withTransform({
                translate(cx - cell * k / 2f, ccy - cell * k / 2f)
                scale(k, k, pivot = Offset.Zero)
            }) {
                fun cellDraw(i: Int, al: Float) = drawImage(
                    sprite,
                    srcOffset = IntOffset((i % FaceIdTl.SPRITE_COLS) * cell, (i / FaceIdTl.SPRITE_COLS) * cell),
                    srcSize = IntSize(cell, cell),
                    dstSize = IntSize(cell, cell),
                    alpha = al,
                    colorFilter = ColorFilter.tint(col, BlendMode.SrcIn),
                    filterQuality = FilterQuality.Low,
                )
                cellDraw(i0, spA)
                if (f > 0.02f && i1 != i0) cellDraw(i1, spA * f)
            }
        }
    }

    // ---- 2b') sprite biterken tam yuvarlak, kameraya dik bakan halkaya yumuşakça geçilir
    if (v >= FaceIdTl.SPR1 - 0.30f) {
        val ra = vis * fidSeg(v, FaceIdTl.SPR1 - 0.30f, FaceIdTl.SPR1) * (if (failed) 1f - fidSeg(fv, 0f, 0.20f) else 1f)
        if (ra > 0.004f) {
            val rw = sq * 0.025f
            val c = Offset(cx, ccy)
            drawCircle(col.copy(alpha = 0.07f * ra), ringR, c, style = Stroke(width = rw * 4.6f))
            drawCircle(col.copy(alpha = 0.16f * ra), ringR, c, style = Stroke(width = rw * 2.5f))
            drawCircle(col.copy(alpha = ra), ringR, c, style = Stroke(width = rw))
        }
    }

    // ---- 2c) ✓ (yalnız kilit açıldıysa): orta noktadan başlayıp iki kola büyür
    if (!failed) {
        val gk = fidOut(fidSeg(v, FaceIdTl.CHECK0, FaceIdTl.CHECK1))
        if (gk > 0f) {
            val ca = vis
            val vx = -0.08f * ringR * gk; val vy = 0.30f * ringR * gk
            if (gk < 0.2f) drawCircle(green.copy(alpha = ca), sw * 0.6f, Offset(cx, ccy))
            val lx = vx + (-0.30f * ringR) * gk; val ly = vy + (-0.30f * ringR) * gk
            val rx = vx + (0.48f * ringR) * gk; val ry = vy + (-0.60f * ringR) * gk
            val ck = Path().apply {
                moveTo(cx + lx, ccy + ly); lineTo(cx + vx, ccy + vy); lineTo(cx + rx, ccy + ry)
            }
            if (gk >= 0.2f) fidStroke(ck, green.copy(alpha = ca), sw)
        }
    }
}

// ================================================================== Pencere (kilit ekranı)

private class FaceIdState {
    val runId = mutableStateOf(0)
    val unlocked = mutableStateOf(false)
    val fail = mutableStateOf(-1f)          // başarısızlığa geçilen t (saniye); -1 = başarılı akış
    val geo = mutableStateOf(FaceIdGeo(100f, 30f, 300f, 40f, false))
}

/** Animasyon saatini ilerletir; bitince onDone çağırır. Kilit açılınca karar anına kadar 3 kat hızlanır. */
@Composable
private fun rememberFaceIdClock(
    runId: Int, speed: State<Float>, unlocked: State<Boolean>, fail: MutableState<Float>, onDone: () -> Unit,
): State<Float> {
    val t = remember(runId) { mutableStateOf(0f) }
    val done = rememberUpdatedState(onDone)
    LaunchedEffect(runId) {
        t.value = 0f
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val dt = min((now - last) / 1_000_000_000f, 0.05f)
                val warp = if (unlocked.value && t.value - FaceIdTl.EXP < FaceIdTl.FF_V) 3f else 1f
                t.value += dt * speed.value.coerceIn(0.25f, 3f) * warp
            }
            last = now
            // karar anı: kilit hâlâ açılmadıysa gri titreme akışı
            if (fail.value < 0f && !unlocked.value && t.value - FaceIdTl.EXP >= FaceIdTl.DECIDE_V) fail.value = t.value
            val endT = if (fail.value >= 0f) fail.value + FaceIdTl.F_END else FaceIdTl.END
            if (t.value >= endT) { done.value(); break }
        }
    }
    return t
}

@Composable
private fun rememberFaceIdSprite(): ImageBitmap? {
    val ctx = LocalContext.current
    return remember {
        runCatching {
            val o = BitmapFactory.Options().apply { inScaled = false }
            BitmapFactory.decodeResource(ctx.resources, R.drawable.faceid_tumble, o).asImageBitmap()
        }.getOrNull()
    }
}

@Composable
private fun FaceIdWindowContent(settings: StateFlow<IslandSettings>, st: FaceIdState, onDone: () -> Unit) {
    val s by settings.collectAsState()
    val speed = rememberUpdatedState(s.faceIdSpeed)
    val sprite = rememberFaceIdSprite()
    val t = rememberFaceIdClock(st.runId.value, speed, st.unlocked, st.fail, onDone)
    val g = st.geo.value
    Canvas(Modifier.fillMaxSize()) { drawFaceId(t.value, s, g, sprite, st.fail.value) }
}

class FaceIdController(
    private val ctx: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val savedOwner: SavedStateRegistryOwner,
    private val wm: WindowManager,
    private val settings: StateFlow<IslandSettings>,
    private val scope: CoroutineScope,
    private val anchor: (IslandSettings) -> FaceIdAnchor,
) {
    /** Animasyon başlayınca true, bitince false: kilit noktasını gizlemek için. */
    var onCover: ((Boolean) -> Unit)? = null

    private val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    private val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager
    private val handler = Handler(Looper.getMainLooper())
    private var receiver: BroadcastReceiver? = null
    private var job: Job? = null
    private val st = FaceIdState()
    private var running = false

    private var view: ComposeView? = null
    private var host: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null

    private val poll = Runnable { evaluate(false) }

    fun start() {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                runCatching {
                    when (i?.action) {
                        Intent.ACTION_SCREEN_OFF -> stopAnim()
                        Intent.ACTION_SCREEN_ON -> {
                            evaluate(true)
                            handler.postDelayed({ evaluate(true) }, 300)    // kilit durumu biraz geç oturabilir
                        }
                        else -> evaluate(false)                              // USER_PRESENT
                    }
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
        }.isSuccess
        if (ok) receiver = r
        job = scope.launch {
            settings.collect {
                runCatching {
                    if (!it.faceIdEnabled) stopAnim() else if (running) relayout(it)
                }
            }
        }
        evaluate(true)
    }

    fun stop() {
        job?.cancel(); job = null
        handler.removeCallbacksAndMessages(null)
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        stopAnim()
    }

    /** Erişilebilirlik servisi bağlanıp kopunca pencere türü değişir: animasyon sürüyorsa yeniden kur. */
    fun rehost() {
        if (!running) return
        removeView()
        show(settings.value)
    }

    private fun evaluate(canStart: Boolean) {
        val s = settings.value
        val interactive = runCatching { pm?.isInteractive == true }.getOrDefault(false)
        val lockedNow = runCatching { interactive && km?.isKeyguardLocked == true }.getOrDefault(false)
        if (!interactive) { stopAnim(); return }
        if (!running) {
            if (canStart && lockedNow && s.faceIdEnabled) startAnim(s)
            return
        }
        if (!lockedNow) st.unlocked.value = true          // kilit açıldı: ✓ aşamasına hızlı sar
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 400)                     // USER_PRESENT kaçarsa da yakalansın
    }

    private fun startAnim(s: IslandSettings) {
        running = true
        st.unlocked.value = false
        st.fail.value = -1f
        st.geo.value = geoFor(s)
        st.runId.value = st.runId.value + 1
        show(s)
        runCatching { onCover?.invoke(true) }
        handler.removeCallbacks(poll)
        handler.postDelayed(poll, 400)
    }

    private fun stopAnim() {
        handler.removeCallbacks(poll)
        val was = running
        running = false
        removeView()
        if (was) runCatching { onCover?.invoke(false) }
    }

    private fun onDone() { stopAnim() }

    private fun geoFor(s: IslandSettings): FaceIdGeo {
        val d = ctx.resources.displayMetrics.density
        val a = anchor(s)
        return FaceIdGeo(a.pillW, a.pillH, s.faceIdSizeDp * d, PAD_DP * d, a.inverted)
    }

    private fun place(s: IslandSettings, p: WindowManager.LayoutParams) {
        val d = ctx.resources.displayMetrics.density
        val a = anchor(s)
        val g = geoFor(s)
        val side = ceil(g.side).toInt().coerceAtLeast(2)
        val off = s.faceIdOffsetYDp * d
        val y = if (!a.inverted) (a.cy - a.pillH / 2f + off) - g.pad
                else (a.cy + a.pillH / 2f - off) - g.sq - g.pad
        p.width = side; p.height = side
        p.x = (a.cx - side / 2f).roundToInt()
        p.y = y.roundToInt()
    }

    private fun relayout(s: IslandSettings) {
        st.geo.value = geoFor(s)
        val cur = view ?: return
        val p = lp ?: return
        place(s, p)
        runCatching { (host ?: wm).updateViewLayout(cur, p) }
    }

    private fun show(s: IslandSettings) {
        if (view != null) { relayout(s); return }
        val v = ComposeView(ctx).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(savedOwner)
            setContent { FaceIdWindowContent(settings, st) { onDone() } }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, type,
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
            place(s, this)
        }
        var added = false
        // Kilit ekranının üstünde görünmesi için erişilebilirlik pencere türü tercih edilir
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            runCatching {
                val h2 = a11y.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val p2 = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(v, p2)
                host = h2; lp = p2; added = true
            }
        }
        if (!added) {
            val p3 = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            added = runCatching { wm.addView(v, p3); host = wm; lp = p3 }.isSuccess
        }
        if (added) view = v else { v.disposeComposition(); running = false; runCatching { onCover?.invoke(false) } }
    }

    private fun removeView() {
        val v = view ?: return
        runCatching { (host ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        view = null; host = null; lp = null
    }

    private companion object { const val PAD_DP = 24f }
}

// ================================================================== Ayarlardaki canlı önizleme

/**
 * Ayarlar önizleme telefonunda, gerçek adanın yerinde döngüyle oynar (başarılı / başarısız sırayla).
 * [pillWpx], [pillHpx]: kapalı ada (px). Bileşenin boyutu pencereyle aynı: (kare + 2 x pay) kare.
 */
@Composable
internal fun FaceIdLoop(s: IslandSettings, pillWpx: Float, pillHpx: Float, inverted: Boolean, modifier: Modifier) {
    val d = LocalDensity.current.density
    val t = remember { mutableStateOf(0f) }
    val failT = remember { mutableStateOf(-1f) }
    val wantFail = remember { mutableStateOf(false) }
    val speed = rememberUpdatedState(s.faceIdSpeed)
    val sprite = rememberFaceIdSprite()
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) t.value += min((now - last) / 1_000_000_000f, 0.05f) * speed.value.coerceIn(0.25f, 3f)
            last = now
            if (wantFail.value && failT.value < 0f && t.value - FaceIdTl.EXP >= FaceIdTl.DECIDE_V) failT.value = t.value
            val endT = if (failT.value >= 0f) failT.value + FaceIdTl.F_END else FaceIdTl.END
            if (t.value > endT + 1.2f) {
                t.value = 0f; failT.value = -1f; wantFail.value = !wantFail.value
            }
        }
    }
    val g = FaceIdGeo(pillWpx, pillHpx, s.faceIdSizeDp * d, FACE_ID_PAD_DP * d, inverted)
    Canvas(modifier) { drawFaceId(t.value, s, g, sprite, failT.value) }
}

/** Face ID penceresinin ada çevresindeki payı (dp); servis penceresiyle aynı. */
internal const val FACE_ID_PAD_DP = 24f

/** Ayar ekranında animasyonu döngüyle oynatır: sırayla başarılı (✓) ve başarısız (gri titreme) akış. */
@Composable
fun FaceIdPreview(s: IslandSettings) {
    val d = LocalDensity.current.density
    val t = remember { mutableStateOf(0f) }
    val failT = remember { mutableStateOf(-1f) }
    val wantFail = remember { mutableStateOf(false) }
    val speed = rememberUpdatedState(s.faceIdSpeed)
    val sprite = rememberFaceIdSprite()
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) t.value += min((now - last) / 1_000_000_000f, 0.05f) * speed.value.coerceIn(0.25f, 3f)
            last = now
            if (wantFail.value && failT.value < 0f && t.value - FaceIdTl.EXP >= FaceIdTl.DECIDE_V) failT.value = t.value
            val endT = if (failT.value >= 0f) failT.value + FaceIdTl.F_END else FaceIdTl.END
            if (t.value > endT + 0.9f) {
                t.value = 0f; failT.value = -1f; wantFail.value = !wantFail.value
            }
        }
    }
    val sq = s.faceIdSizeDp
    val padDp = 16f
    val g = FaceIdGeo(s.compactWidthDp * d, s.compactHeightDp * d, sq * d, padDp * d, false)
    Box(
        Modifier.fillMaxWidth().height((sq + 2 * padDp + 8f).dp).clip(RoundedCornerShape(14.dp)).background(Pal.c("preview_lockbg")),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.size((sq + 2 * padDp).dp)) { drawFaceId(t.value, s, g, sprite, failT.value) }
    }
}
