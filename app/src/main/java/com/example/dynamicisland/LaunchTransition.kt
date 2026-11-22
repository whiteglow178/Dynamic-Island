package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Adanın (kapalı halde) ekran üzerindeki konumu (piksel) ve animasyon hızı.
 * speed: 1 = normal, 2 = iki kat hızlı, 0.5 = yarı hızlı (ayarlardan gelir).
 */
class LaunchSpec(val cx: Float, val top: Float, val w: Float, val h: Float, val speed: Float = 1f, val color: Color = Color.Black)

// ---- Ayar noktaları
private const val BASE_MS = 650            // hız 1x iken adanın altından çıkıp ekranı kaplama süresi
private const val HOLD_MS = 350L           // ekran kaplandıktan sonra siyahın bekleme süresi (uygulama açılırken)
private const val FADE_MS = 240            // siyahın kalkması
private const val CORNER_DP = 44f          // büyürken köşe yuvarlaklığı (telefon ekran köşesine yakın)
private const val MARGIN_DP = 20f          // son boyutun ekranı taşma payı (köşe yarıçapının en az ~%30'u olmalı)
private const val START_W_DP = 46f         // adanın altından çıkan küçük şeklin başlangıç genişliği
private const val START_H_DP = 30f         // ... ve yüksekliği

// Yavaş başlar, yaklaştıkça hızlanır, sonda yumuşakça oturur
private val ApproachEasing = CubicBezierEasing(0.55f, 0f, 0.25f, 1f)

/**
 * Adanın altından küçük bir siyah şekil çıkar ve ekrana yaklaşıyormuş gibi gitgide büyüyerek tüm ekranı kaplar.
 * Büyüme üstel (katlanarak) olduğu için başta yavaş, yaklaştıkça hızlı hissedilir.
 * Ekran tamamen kapanınca [onCovered] çağrılır (uygulamayı o an aç); siyah bir süre sonra solar ve [onFinished] gelir.
 */
@Composable
fun LaunchTransition(spec: LaunchSpec, onCovered: () -> Unit, onFinished: () -> Unit) {
    val inPreview = LocalIslandPreview.current      // ayarlar önizlemesi: konumlar yerel (pencere ekranın köşesinde değil)
    val covered by rememberUpdatedState(onCovered)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ms = (BASE_MS / spec.speed.coerceIn(0.25f, 4f)).toInt()

    LaunchedEffect(Unit) {
        a.animateTo(1f, tween(ms, easing = ApproachEasing))
        covered()                                      // ekran tamamen siyah: uygulamayı aç
        delay(HOLD_MS)
        fade.animateTo(0f, tween(FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = if (inPreview) Offset.Zero else it.positionOnScreen() }
            .graphicsLayer { alpha = fade.value }
    ) {
        val d = density
        val m = MARGIN_DP * d                          // ekran dışına taşma payı: yuvarlak köşeler ekranın dışında kalır
        val sw = size.width + 2 * m
        val sh = size.height + 2 * m
        val t = a.value

        // Başlangıç: adanın alt kenarında küçük bir şekil; bitiş: tüm ekran
        val w0 = START_W_DP * d
        val h0 = START_H_DP * d
        val cx0 = spec.cx
        val cy0 = spec.top + spec.h + h0 * 0.15f
        val w = w0 * (sw / w0).pow(t)                  // üstel büyüme: yaklaşma hissi
        val h = h0 * (sh / h0).pow(t)
        val cx = cx0 + (size.width / 2f - cx0) * t
        val cy = cy0 + (size.height / 2f - cy0) * t
        val alpha = (t * 12f).coerceIn(0f, 1f)         // ilk anda yumuşakça belirir
        val radius = minOf(CORNER_DP * d, minOf(w, h) / 2f)   // köşe yarıçapı büyürken sabit kalır: sivrilme olmaz

        translate(-origin.x, -origin.y) {
            if (t >= 1f) {
                drawRect(spec.color, Offset(-m, -m), Size(sw, sh))
            } else {
                drawRoundRect(spec.color.copy(alpha = spec.color.alpha * alpha), Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(radius))
            }
        }
    }
}

// ---- Müzik uygulamasından çıkış: uzun (telefon oranlı) panel adaya küçülür
private const val SHRINK_BASE_MS = 320     // hız 1x iken küçülme süresi
private const val SHRINK_FADE_MS = 70      // adaya varınca silinmesi
private const val SHRINK_APPEAR_MS = 10    // yumuşakça belirme (ani kararma olmasın)
private const val PANEL_DELAY_MS = 0L      // panelin başlama gecikmesi (hız 1x); 0 = panel hemen başlar
private const val SWALLOW_AT = 0.10f       // küçülmenin bu noktasında ada büyüyüp yutmaya başlar
private const val START_W_FRAC = 0.62f     // başlangıç genişliği (ekran genişliğine oranı); yükseklik ekran oranıyla gelir
private const val START_TOP_FRAC = 0.30f   // başlangıçta panelin üst kenarı (ekran yüksekliğine oranı)
private const val END_W_DP = 34f           // adaya girerken ince uzun şekil
private const val END_H_DP = 56f

private val ShrinkEasing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.8f)   // adaya varırken hız kesilmez (duraksama olmasın)

/**
 * Uygulama ekranı gibi uzun (ekranla aynı oranlı) siyah panel: aşağıdan yukarı adaya doğru daralarak küçülür.
 * [onSwallow]: panel adaya yaklaşınca çağrılır (ada büyüyüp yutar). Bitince [onFinished] gelir.
 */
@Composable
fun ShrinkToIsland(spec: LaunchSpec, onSwallow: () -> Unit, onFinished: () -> Unit) {
    val inPreview = LocalIslandPreview.current      // ayarlar önizlemesi: konumlar yerel (pencere ekranın köşesinde değil)
    val swallow by rememberUpdatedState(onSwallow)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }            // 0 = başlangıç paneli, 1 = adanın içinde ince şekil
    val appear = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ms = (SHRINK_BASE_MS / spec.speed.coerceIn(0.25f, 4f)).toInt()

    LaunchedEffect(Unit) {
        delay((PANEL_DELAY_MS / spec.speed.coerceIn(0.25f, 4f)).toLong())   // ada önce açılsın
        launch { appear.animateTo(1f, tween(SHRINK_APPEAR_MS)) }
        var fired = false
        a.animateTo(1f, tween(ms, easing = ShrinkEasing)) {
            if (!fired && value >= SWALLOW_AT) { fired = true; swallow() }
        }
        if (!fired) swallow()
        fade.animateTo(0f, tween(SHRINK_FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = if (inPreview) Offset.Zero else it.positionOnScreen() }
            .graphicsLayer { alpha = appear.value * fade.value }
    ) {
        val d = density
        val t = a.value

        // Başlangıç: ekran oranında uzun panel (ekranı kaplamaz); bitiş: adanın içinde ince uzun şekil
        val w0 = size.width * START_W_FRAC
        val h0 = w0 * (size.height / size.width)
        val top0 = size.height * START_TOP_FRAC
        val cx0 = size.width / 2f
        val w1 = spec.h * 0.5f                         // ince, uzun şekil (eski görünüm) ama adanın İÇİNDE biter:
        val h1 = spec.h * 0.8f                         // adanın altından taşmaz
        val top1 = spec.top + spec.h * 0.1f
        val cx1 = spec.cx
        val w = w0 * (w1 / w0).pow(t)                  // üstel daralma
        val h = h0 * (h1 / h0).pow(t)
        val top = top0 + (top1 - top0) * t             // üst kenar adaya doğru yükselir
        val cx = cx0 + (cx1 - cx0) * t
        val radius = minOf(CORNER_DP * d, minOf(w, h) / 2f)

        translate(-origin.x, -origin.y) {
            drawRoundRect(spec.color, Offset(cx - w / 2f, top), Size(w, h), CornerRadius(radius))
        }
    }
}


// ---- Atom ada (OriginOS tarzı): uygulama kartı eğilir, huni gibi daralıp adaya emilir
private const val ATOM_BASE_MS = 520       // hız 1x iken toplam süre
private const val ATOM_SPLIT = 0.30f       // 0..SPLIT: pencere → eğik kart
private const val ATOM_Q_START = 0.20f     // kartın adaya çekilmesi bu noktada başlar (kart aşamasıyla iç içe: durma olmaz)
private const val ATOM_FUNNEL = 0.5f       // üst kısım adaya alttan ne kadar önce varsın (huni/kuyruk etkisi)
private const val ATOM_SWALLOW_AT = 0.45f
private const val FULL_HOLD_MS = 120L      // tam ekran başlangıçta ekranın kaplı kaldığı süre (hız 1x)

private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/** Köşeleri yuvarlak dikdörtgenin çevresini (saat yönünde) nokta dizisi olarak verir. */
private fun roundedRectPoints(l: Float, t: Float, r: Float, b: Float, rad: Float): List<Offset> {
    val rr = min(rad, min(r - l, b - t) / 2f).coerceAtLeast(0f)
    val out = ArrayList<Offset>(96)
    fun arc(cx: Float, cy: Float, a0: Double) {
        for (i in 0..8) {
            val a = a0 + (PI / 2.0) * i / 8.0
            out.add(Offset(cx + rr * cos(a).toFloat(), cy + rr * sin(a).toFloat()))
        }
    }
    fun edge(x0: Float, y0: Float, x1: Float, y1: Float) {
        for (i in 1 until 20) { val f = i / 20f; out.add(Offset(lerpF(x0, x1, f), lerpF(y0, y1, f))) }
    }
    arc(l + rr, t + rr, PI);            edge(l + rr, t, r - rr, t)
    arc(r - rr, t + rr, 1.5 * PI);      edge(r, t + rr, r, b - rr)
    arc(r - rr, b - rr, 0.0);           edge(r - rr, b, l + rr, b)
    arc(l + rr, b - rr, 0.5 * PI);      edge(l, b - rr, l, t + rr)
    return out
}

/**
 * Atom ada paneli: uygulama penceresi önce sola kayan, perspektifle eğilen bir karta dönüşür;
 * sonra kartın üst kısmı adaya, alt kısmı gecikmeyle çekilerek huni gibi daralır ve ada tarafından yutulur.
 * (Pencere içeriği yerine adanın rengi kullanılır.)
 */
@Composable
fun AtomicToIsland(spec: LaunchSpec, onSwallow: () -> Unit, onFinished: () -> Unit, fullStart: Boolean = false) {
    val inPreview = LocalIslandPreview.current      // ayarlar önizlemesi: konumlar yerel (pencere ekranın köşesinde değil)
    val swallow by rememberUpdatedState(onSwallow)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val appear = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ms = (ATOM_BASE_MS / spec.speed.coerceIn(0.25f, 4f)).toInt()

    LaunchedEffect(Unit) {
        delay((PANEL_DELAY_MS / spec.speed.coerceIn(0.25f, 4f)).toLong())
        launch { appear.animateTo(1f, tween(40)) }
        // Tam ekran başlangıç: siyah panel ekranı kaplı kalır, kısa bir an beklenip sonra küçülmeye başlar
        if (fullStart) delay((FULL_HOLD_MS / spec.speed.coerceIn(0.25f, 4f)).toLong())
        var fired = false
        a.animateTo(1f, tween(ms, easing = LinearEasing)) {
            if (!fired && value >= ATOM_SWALLOW_AT) { fired = true; swallow() }
        }
        if (!fired) swallow()
        fade.animateTo(0f, tween(SHRINK_FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = if (inPreview) Offset.Zero else it.positionOnScreen() }
            .graphicsLayer { alpha = appear.value * fade.value }
    ) {
        val d = density
        val w = size.width
        val h = size.height
        val p = a.value
        val x1 = min(p / ATOM_SPLIT, 1f)
        val p1 = x1 * (1.6f - 0.6f * x1)                                         // sonda hız sıfırlanmaz: duraksama olmasın
        val q = ((p - ATOM_Q_START) / (1f - ATOM_Q_START)).coerceIn(0f, 1f)

        // 1) Pencere → eğik kart (sol tarafa kayar, sağ kenar uzaklaşır, hafif döner)
        // fullStart: başlangıç dikdörtgeni ekranı (köşeler dahil, taşma payıyla) tamamen kaplar
        val mg0 = if (fullStart) MARGIN_DP * d else 0f
        val left = lerpF(if (fullStart) -mg0 else 0.08f * w, 0.40f * w - 0.28f * w, p1)
        val right = lerpF(if (fullStart) w + mg0 else 0.92f * w, 0.40f * w + 0.28f * w, p1)
        val top = lerpF(if (fullStart) -mg0 else 0.06f * h, 0.17f * h, p1)
        val bottom = lerpF(if (fullStart) h + mg0 else 0.94f * h, 0.67f * h, p1)
        val radius = lerpF(44f * d, 38f * d, p1)
        val tilt = (-7.0 * PI / 180.0 * p1).toFloat()
        val persp = 0.14f * p1
        val cxm = (left + right) / 2f
        val cym = (top + bottom) / 2f
        val cardW = right - left
        val cosT = cos(tilt)
        val sinT = sin(tilt)

        val base = roundedRectPoints(left, top, right, bottom, radius).map { pt ->
            val u = (pt.x - left) / cardW
            val y1 = cym + (pt.y - cym) * (1f - persp * u)          // sağ taraf daha kısa: derinlik hissi
            val dx = pt.x - cxm
            val dy = y1 - cym
            Offset(cxm + dx * cosT - dy * sinT, cym + dx * sinT + dy * cosT)
        }

        // 2) Huni: üst noktalar adaya önce varır, alt noktalar gecikmeyle çekilir
        val ix = spec.cx
        val iy = spec.top + spec.h / 2f
        val yMin = base.minOf { it.y }
        val yMax = base.maxOf { it.y }
        val span = (yMax - yMin).coerceAtLeast(1f)
        val path = Path()
        base.forEachIndexed { i, pt ->
            val v = (pt.y - yMin) / span
            val r = (q * (1f + ATOM_FUNNEL) - v * ATOM_FUNNEL).coerceIn(0f, 1f)
            // Her nokta (alt kısım dahil) hemen hareket eder: %35 ortak hız + %65 huni gecikmesi; yavaşlama/duraksama olmaz
            val m = (0.35f * q + 0.65f * r).coerceIn(0f, 1f)
            val e = m * (0.4f + 0.6f * m)                                // sıfır hızla başlamaz, adaya doğru hızlanır
            val x = pt.x + (ix - pt.x) * e
            val y = pt.y + (iy - pt.y) * e
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        translate(-origin.x, -origin.y) {
            drawPath(path, spec.color)
        }
    }
}


// ---- Atom ada açılışı (OriginOS tarzı): adadan açılan siyah kart huni gibi genişleyip tüm ekranı kaplar
private const val ATOM_LAUNCH_MS = 480       // hız 1x iken ekranı kaplama süresi

/**
 * Atom ada paneli ([AtomicToIsland]) ters yönde: ada noktasından başlayan siyah kart, alt kısmı önde gidecek
 * şekilde huni gibi genişler ve tüm ekranı kaplar. Ekran tamamen kapanınca [onCovered] çağrılır (uygulamayı o an aç);
 * siyah kısa süre bekler, sonra solar ve [onFinished] gelir. Renk her zaman siyahtır.
 */
@Composable
fun AtomicLaunch(spec: LaunchSpec, onCovered: () -> Unit, onFinished: () -> Unit) {
    val inPreview = LocalIslandPreview.current      // ayarlar önizlemesi: konumlar yerel (pencere ekranın köşesinde değil)
    val covered by rememberUpdatedState(onCovered)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val sp = spec.speed.coerceIn(0.25f, 4f)

    LaunchedEffect(Unit) {
        a.animateTo(1f, tween((ATOM_LAUNCH_MS / sp).toInt(), easing = LinearEasing))
        covered()
        delay((HOLD_MS / sp).toLong())
        fade.animateTo(0f, tween(FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = if (inPreview) Offset.Zero else it.positionOnScreen() }
            .graphicsLayer { alpha = fade.value }
    ) {
        val d = density
        val w = size.width
        val h = size.height
        val mg = MARGIN_DP * d                       // ekran köşelerinin de kapanması için taşma payı
        val q = 1f - a.value                          // 1 = ada noktası, 0 = tam ekran
        val base = roundedRectPoints(-mg, -mg, w + mg, h + mg, CORNER_DP * d)
        val ix = spec.cx
        val iy = spec.top + spec.h / 2f
        val yMin = -mg
        val span = h + 2f * mg
        val path = Path()
        base.forEachIndexed { i, pt ->
            val v = (pt.y - yMin) / span
            val r = (q * (1f + ATOM_FUNNEL) - v * ATOM_FUNNEL).coerceIn(0f, 1f)   // alt kısım önde, üst kısım adaya yakın kalır
            val m = (0.35f * q + 0.65f * r).coerceIn(0f, 1f)
            val e = m * (0.4f + 0.6f * m)
            val x = pt.x + (ix - pt.x) * e
            val y = pt.y + (iy - pt.y) * e
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        translate(-origin.x, -origin.y) {
            drawPath(path, Color.Black)
        }
    }
}


// ---- Uzayan ada (iOS tarzı yutma): ada yukarı doğru biraz büyür, sonra eski haline döner.
// Ada penceresi yeniden boyutlandırılmaz (titreme/takılma olmasın); şekil tam ekran katmanda çizilir.
const val STRETCH_W_MUL = 1.12f            // büyük genişlik = kapalı ada genişliği x bu değer
const val STRETCH_H_MUL = 1.5f             // büyük yükseklik = kapalı ada yüksekliği x bu değer
private const val STRETCH_OPEN_MS = 320    // hız 1x iken uzama süresi
private const val STRETCH_HOLD_MS = 120L   // en büyük halde bekleme (panel adaya girsin)
private const val STRETCH_CLOSE_MS = 380   // hız 1x iken eski boyuta dönme süresi
private const val STRETCH_APPEAR_MS = 40
private const val STRETCH_FADE_MS = 80
private val StretchEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

@Composable
fun IslandStretch(spec: LaunchSpec, onFinished: () -> Unit) {
    val inPreview = LocalIslandPreview.current      // ayarlar önizlemesi: konumlar yerel (pencere ekranın köşesinde değil)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val appear = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val sp = spec.speed.coerceIn(0.5f, 3f)

    LaunchedEffect(Unit) {
        launch { appear.animateTo(1f, tween(STRETCH_APPEAR_MS)) }
        a.animateTo(1f, tween((STRETCH_OPEN_MS / sp).toInt(), easing = StretchEasing))
        delay((STRETCH_HOLD_MS / sp).toLong())
        a.animateTo(0f, tween((STRETCH_CLOSE_MS / sp).toInt(), easing = StretchEasing))
        fade.animateTo(0f, tween(STRETCH_FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = if (inPreview) Offset.Zero else it.positionOnScreen() }
            .graphicsLayer { alpha = appear.value * fade.value }
    ) {
        val t = a.value
        val extra = spec.h * (STRETCH_H_MUL - 1f)
        val up = minOf(extra, spec.top.coerceAtLeast(0f))      // önce ekranın üstüne kadar yukarı, kalanı aşağı
        val w = spec.w * (1f + (STRETCH_W_MUL - 1f) * t)
        val h = spec.h + extra * t
        val top = spec.top - up * t
        val r = minOf(36f * density, minOf(w, h) / 2f)
        translate(-origin.x, -origin.y) {
            drawRoundRect(spec.color, Offset(spec.cx - w / 2f, top), Size(w, h), CornerRadius(r))
        }
    }
}
