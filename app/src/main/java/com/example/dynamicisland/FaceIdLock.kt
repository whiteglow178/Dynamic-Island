package com.example.dynamicisland

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * KİLİT EKRANI FACE ID ANİMASYONU
 * Ekran açılıp telefon kilitliyken ada kamera deliğinden yuvarlak köşeli bir kareye dönüşür; içinde
 * Face ID yüzü nabız gibi atar, dört köşe parantezi halkaya dönüşüp 3B dönerek (hareket bulanıklığıyla)
 * düzleşir, ✓ onay işareti çizilir, yüz geri gelir ve ada tekrar hap şekline küçülür.
 * Zamanlama, ekran kaydındaki (1000022465.mp4 / 1000022461.mp4) kareler tek tek incelenerek çıkarıldı.
 * Ek izin gerekmez; LockDot ile aynı şekilde erişilebilirlik overlay penceresi kullanılır.
 * Kilit açılırsa (USER_PRESENT) animasyon ✓ aşamasına hızlı sarılır, sonra normal biter.
 */

/** Zaman çizelgesi (saniye). T = animasyon başından beri; v = T - EXP (yüz görününce 0). */
object FaceIdTl {
    const val EXP = 0.35f           // hap -> kare açılma süresi
    const val FEAT_FADE0 = 1.50f    // göz / burun / ağız solar
    const val FEAT_FADE1 = 1.70f
    const val MORPH0 = 1.65f        // köşe parantezleri -> halka
    const val MORPH1 = 1.90f
    const val GAP0 = 1.85f          // 4 yay birleşip tam halka olur
    const val GAP1 = 2.15f
    const val TUMBLE0 = 2.00f       // 3B dönüş
    const val TUMBLE1 = 3.50f
    const val CHECK0 = 4.35f        // ✓ çizilmeye başlar (önce orta nokta)
    const val CHECK1 = 5.10f
    const val SWAP0 = 5.30f         // halka + ✓ solar, yüz geri gelir
    const val SWAP1 = 5.55f
    const val FADE0 = 6.50f         // yüz söner
    const val FADE1 = 6.80f
    const val END_V = 7.15f         // kare -> hap küçülme biter
    const val END = EXP + END_V
    const val FF_V = 3.5f           // kilit açılınca buraya kadar 3 kat hızlı oynatılır
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

/**
 * Tüm animasyonu t saniyesinde çizer. Tuval (sq + 2*pad) karelik pencere/alandır.
 * Hap, tuvalin üst kenarına (pad kadar içeride) yaslıdır; ters adada alt kenara.
 */
fun DrawScope.drawFaceId(t: Float, s: IslandSettings, g: FaceIdGeo) {
    val cx = size.width / 2f
    val v = t - FaceIdTl.EXP
    val sq = g.sq
    val green = Color(s.faceIdColorArgb)

    // ---- 1) kara gövde: hap -> kare (açılma) ... kare -> hap (küçülme)
    val ew: Float; val eh: Float
    if (t < FaceIdTl.EXP) {
        ew = fidOut(t / FaceIdTl.EXP)
        eh = fidOut(fidSeg(t, 0.06f, FaceIdTl.EXP))
    } else if (v > FaceIdTl.FADE1) {
        val k = fidSeg(v, FaceIdTl.FADE1, FaceIdTl.END_V)
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
    if (appear <= 0f || v > FaceIdTl.FADE1 + 0.02f) return
    val ccx = cx
    val ccy = g.pad + sq / 2f                // içerik her zaman karenin merkezinde
    val a = sq * 0.228f                      // yüz yarı genişliği
    val sw = sq * 0.029f                     // çizgi kalınlığı
    val ringR = FID_RING * a

    val fadeOut = 1f - fidSeg(v, FaceIdTl.FADE0, FaceIdTl.FADE1)
    val vis = appear * fadeOut

    // Kenarlıkta çok silik yeşil çizgi (ekran kaydında karenin kenarı hafif yeşil)
    drawRoundRect(green.copy(alpha = 0.20f * vis), Offset(left, top), Size(w, h), CornerRadius(rad), style = Stroke(width = max(1f, sq * 0.007f)))

    if (v < FaceIdTl.SWAP1) {
        // ---- 2a) yüz nabzı -> halka -> 3B dönüş -> düz halka -> ✓
        val pulseA = run {
            val p0 = fidPulse(1.6f)
            if (v < 1.6f) fidPulse(v) else fidLerp(p0, 1f, fidSeg(v, 1.6f, 1.75f))
        }
        val ringFade = if (v < FaceIdTl.SWAP0) 1f else 1f - fidSeg(v, FaceIdTl.SWAP0, FaceIdTl.SWAP1)
        val grpA = pulseA * ringFade * vis

        val m = fidSmooth(fidSeg(v, FaceIdTl.MORPH0, FaceIdTl.MORPH1))
        val delta = fidLerp(30f, 45f, fidSmooth(fidSeg(v, FaceIdTl.GAP0, FaceIdTl.GAP1))) * PI.toFloat() / 180f
        val p = fidSeg(v, FaceIdTl.TUMBLE0, FaceIdTl.TUMBLE1)
        val twoPi = 2f * PI.toFloat()
        fun angles(pp: Float): FloatArray {
            val e = fidOut(pp)
            return floatArrayOf(twoPi * 1f * e, twoPi * 2f * e, (PI.toFloat() / 4f) * sin(PI.toFloat() * e))   // başta ve sonda 0: düz
        }

        // hareket bulanıklığı: hızlıyken geçmiş açılarda silik kopyalar
        val speed = (1f - p).pow(2)
        if (p > 0f && p < 1f && speed > 0.03f) {
            for (k in 9 downTo 1) {
                val ang = angles(max(0f, p - k * 0.007f))
                val al = 0.32f * speed * 0.82f.pow(k) * grpA
                val paths = fidRingPaths(ccx, ccy, a, m, delta, ang[0], ang[1], ang[2])
                for (pa in paths) fidStroke(pa, green.copy(alpha = al), sw)
            }
        }
        val ang = angles(p)
        val ring = fidRingPaths(ccx, ccy, a, m, delta, ang[0], ang[1], ang[2])
        // yumuşak parıltı (neon)
        val glowK = if (m > 0.5f) 1f else m * 2f
        for (pa in ring) {
            fidStroke(pa, green.copy(alpha = 0.07f * grpA * glowK), sw * 4.6f)
            fidStroke(pa, green.copy(alpha = 0.16f * grpA * glowK), sw * 2.5f)
        }
        for (pa in ring) fidStroke(pa, green.copy(alpha = grpA), sw)

        // göz / burun / ağız: parantezler halkaya dönerken solar
        val feat = (1f - fidSeg(v, FaceIdTl.FEAT_FADE0, FaceIdTl.FEAT_FADE1)) * pulseA * vis
        fidFeatures(ccx, ccy, a, sw, green.copy(alpha = feat))

        // ---- ✓ : orta noktadan başlayıp iki kola büyür
        val gk = fidOut(fidSeg(v, FaceIdTl.CHECK0, FaceIdTl.CHECK1))
        if (gk > 0f) {
            val ca = ringFade * vis
            val vx = -0.08f * ringR * gk; val vy = 0.30f * ringR * gk
            if (gk < 0.2f) drawCircle(green.copy(alpha = ca), sw * 0.6f, Offset(ccx, ccy))
            val lx = vx + (-0.30f * ringR) * gk; val ly = vy + (-0.30f * ringR) * gk
            val rx = vx + (0.48f * ringR) * gk; val ry = vy + (-0.60f * ringR) * gk
            val ck = Path().apply {
                moveTo(ccx + lx, ccy + ly); lineTo(ccx + vx, ccy + vy); lineTo(ccx + rx, ccy + ry)
            }
            if (gk >= 0.2f) fidStroke(ck, green.copy(alpha = ca), sw)
        }
    }

    if (v >= FaceIdTl.SWAP0) {
        // ---- 2b) yüz geri gelir (parlak başlar, nabız atar), sonra söner
        val k = fidSeg(v, FaceIdTl.SWAP0, FaceIdTl.SWAP1)
        val pul = 0.65f + 0.35f * cos(2f * PI.toFloat() * (v - FaceIdTl.SWAP1) / 0.85f)
        val fa = k * pul * vis
        fidFace(ccx, ccy, a, sw, green.copy(alpha = fa), green.copy(alpha = fa))
    }
}

// ================================================================== Pencere (kilit ekranı)

private class FaceIdState {
    val runId = mutableStateOf(0)
    val unlocked = mutableStateOf(false)
    val geo = mutableStateOf(FaceIdGeo(100f, 30f, 300f, 40f, false))
}

/** Animasyon saatini ilerletir; bitince onDone çağırır. Kilit açılınca v < FF_V iken 3 kat hızlanır. */
@Composable
private fun rememberFaceIdClock(runId: Int, speed: State<Float>, unlocked: State<Boolean>, onDone: () -> Unit): State<Float> {
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
            if (t.value >= FaceIdTl.END) { done.value(); break }
        }
    }
    return t
}

@Composable
private fun FaceIdWindowContent(settings: StateFlow<IslandSettings>, st: FaceIdState, onDone: () -> Unit) {
    val s by settings.collectAsState()
    val speed = rememberUpdatedState(s.faceIdSpeed)
    val t = rememberFaceIdClock(st.runId.value, speed, st.unlocked, onDone)
    val g = st.geo.value
    Canvas(Modifier.fillMaxSize()) { drawFaceId(t.value, s, g) }
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

/** Ayar ekranında animasyonu döngüyle oynatır (gerçek pencereyle aynı çizim kodu). */
@Composable
fun FaceIdPreview(s: IslandSettings) {
    val d = LocalDensity.current.density
    val t = remember { mutableStateOf(0f) }
    val speed = rememberUpdatedState(s.faceIdSpeed)
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) t.value += min((now - last) / 1_000_000_000f, 0.05f) * speed.value.coerceIn(0.25f, 3f)
            last = now
            if (t.value > FaceIdTl.END + 0.9f) t.value = 0f
        }
    }
    val sq = s.faceIdSizeDp
    val padDp = 16f
    val g = FaceIdGeo(s.compactWidthDp * d, s.compactHeightDp * d, sq * d, padDp * d, false)
    Box(
        Modifier.fillMaxWidth().height((sq + 2 * padDp + 8f).dp).clip(RoundedCornerShape(14.dp)).background(Pal.c("preview_lockbg")),
        contentAlignment = Alignment.TopCenter,
    ) {
        Canvas(Modifier.size((sq + 2 * padDp).dp)) { drawFaceId(t.value, s, g) }
    }
}
