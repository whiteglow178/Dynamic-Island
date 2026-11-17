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

const val MUSIC_EXTRA_DP = 100f

// ---------------------------------------------------------------- Müzik adası: bulanık açılış / ters yönde kapanış
/** Bulanıklığı uygular (Android 12+); desteklenmeyen cihazlarda yalnızca solma + ölçek çalışır. */
private fun GraphicsLayerScope.setBlurPx(px: Float) {
    // Yarıçap 3 px'lik basamaklara yuvarlanır: aynı efekt art arda karelerde yeniden üretilmez (hızlı aç-kapa'da takılmayı azaltır)
    val q = Math.round(px / 3f) * 3f
    renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && q > 0.5f) BlurEffect(q, q, TileMode.Decal) else null
}

/**
 * Genişletilmiş müzik panelinin içeriği: ada büyüdükçe küçük ve bulanık halden netleşip yerine oturur.
 * [t] ada ilerlemesidir (0 = kapalı, 1 = açık). Küçülürken t azaldığı için aynı eğri tam tersi oynar.
 */
private fun GraphicsLayerScope.musicReveal(t: Float) {
    val r = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
    alpha = (r * 1.2f).coerceAtMost(1f)
    val sc = 0.60f + 0.40f * r * r * r
    scaleX = sc; scaleY = sc
    transformOrigin = TransformOrigin(0.5f, 0.2f)
    setBlurPx((1f - r) * 32.dp.toPx())
}

/** Kapalı adadaki içerik (kapak, equalizer, sözler): ada açılırken hafifçe bulanıp küçülerek kaybolur, küçülürken geri gelir. */
private fun GraphicsLayerScope.musicCompactBlur(c: Float) {
    val k = 1f - c
    val sc = 1f - 0.25f * k
    scaleX = sc; scaleY = sc
    setBlurPx(k * 10.dp.toPx())
}

/** Aktif aramada küçültülmüş adanın genişliği (telefon simgesi + süre + ses dalgası sığsın). */
const val CALL_COMPACT_W_DP = 160f

// ---------------------------------------------------------------- Yan baloncuklar (iki / üç olay)
/** Ada ile yan baloncuk arasındaki son boşluk (dp). */
const val SIDE_GAP_DP = 8f

/** Yan baloncuklar için pencerenin her iki yanda ayıracağı pay (dp): boşluk + baloncuk çapı + yay taşması. Servis kullanır. */
fun sideExtraDp(cfg: IslandSettings): Float = sideGapDp(cfg) + max(dotExtentDp(cfg, 1), dotExtentDp(cfg, -1)) + 6f

/** Üç / dört / beş nokta modunda bu yanda dış nokta var mı? (4 nokta: yalnızca sağda, 5 nokta: iki yanda) */
fun dotHasOuter(cfg: IslandSettings, dir: Int): Boolean =
    cfg.dotMode && (if (dir > 0) cfg.dotCount >= 4 else cfg.dotCount >= 5)

/** Yuvanın çapı (dp): level 1 = iç nokta (normal yan baloncuk), level 2 = dış nokta. */
fun slotSizeDp(cfg: IslandSettings, dir: Int, level: Int): Float =
    if (level == 2) dotOuterSizeDp(cfg, dir) else sideSizeDp(cfg, dir)

/** Dış noktanın çapı (dp). */
fun dotOuterSizeDp(cfg: IslandSettings, dir: Int): Float = if (dir > 0) cfg.dotRight2Dp else cfg.dotLeft2Dp

/** Adanın bir yanındaki tüm noktaların kapladığı genişlik (iç nokta + varsa boşluk + dış nokta), dp. */
fun dotExtentDp(cfg: IslandSettings, dir: Int): Float =
    sideSizeDp(cfg, dir) + if (dotHasOuter(cfg, dir)) sideGapDp(cfg) + dotOuterSizeDp(cfg, dir) else 0f

/** Ada ile yan baloncuk arasındaki boşluk (dp). Üç nokta modunda ayardan gelir. */
fun sideGapDp(cfg: IslandSettings): Float = if (cfg.dotMode) cfg.dotGapDp else SIDE_GAP_DP

/** Yan baloncuğun çapı (dp). [dir]: +1 sağ, -1 sol. Üç nokta modunda sağ / sol nokta ayrı ayrı ayarlanır. */
fun sideSizeDp(cfg: IslandSettings, dir: Int): Float =
    if (cfg.dotMode) (if (dir > 0) cfg.dotRightDp else cfg.dotLeftDp) else min(72f, cfg.compactHeightDp)

/** Açık haldeki ada genişliği (dp). Servis, genişleyen panelin yanına baloncuk sığıp sığmadığını buradan hesaplar. */
fun expandedWidthDpFor(c: IslandContent, cfg: IslandSettings, screenWDp: Float): Float = when (c) {
    is IslandContent.ScreenRec -> recExpandedWidthDp(cfg, screenWDp)
    else -> cfg.expandedWidthDp
}

/** Ada genişlemişken iki yanına baloncuk (çap + boşluk) sığıyor mu? Dar ekranda sığmazsa baloncuk gösterilmez. */
fun sideFitsWhenExpanded(c: IslandContent, cfg: IslandSettings, screenWDp: Float): Boolean =
    expandedWidthDpFor(c, cfg, screenWDp) + 2f * (sideGapDp(cfg) + max(dotExtentDp(cfg, 1), dotExtentDp(cfg, -1)) + 4f) <= screenWDp

private class SideHold { var c: IslandContent? = null }

/**
 * İki daireyi sıvı gibi birleştiren köprü yolu (metaball). Daireler [maxDist]'ten uzaksa ya da biri
 * ötekinin içindeyse null döner: köprü kopmuş / henüz oluşmamış demektir.
 */
private fun metaballPath(c1: Offset, r1: Float, c2: Offset, r2: Float, maxDist: Float): Path? {
    val dx = c2.x - c1.x
    val dy = c2.y - c1.y
    val d = sqrt(dx * dx + dy * dy)
    if (r1 <= 0f || r2 <= 0f || d <= abs(r1 - r2) || d >= maxDist) return null
    val v = 0.5f
    val handle = 2.4f
    val pi = Math.PI.toFloat()
    val u1: Float
    val u2: Float
    if (d < r1 + r2) {
        u1 = acos(((r1 * r1 + d * d - r2 * r2) / (2f * r1 * d)).coerceIn(-1f, 1f))
        u2 = acos(((r2 * r2 + d * d - r1 * r1) / (2f * r2 * d)).coerceIn(-1f, 1f))
    } else { u1 = 0f; u2 = 0f }
    val ang = atan2(dy, dx)
    val maxSpread = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
    val a1 = ang + u1 + (maxSpread - u1) * v
    val a2 = ang - u1 - (maxSpread - u1) * v
    val a3 = ang + pi - u2 - (pi - u2 - maxSpread) * v
    val a4 = ang - pi + u2 + (pi - u2 - maxSpread) * v
    fun pt(c: Offset, r: Float, a: Float, len: Float = r) = Offset(c.x + len * cos(a), c.y + len * sin(a))
    val p1a = pt(c1, r1, a1); val p1b = pt(c1, r1, a2)
    val p2a = pt(c2, r2, a3); val p2b = pt(c2, r2, a4)
    val half = pi / 2f
    val total = r1 + r2
    val gapAB = sqrt((p1a.x - p2a.x) * (p1a.x - p2a.x) + (p1a.y - p2a.y) * (p1a.y - p2a.y))
    val d2 = min(v * handle, gapAB / total) * min(1f, d * 2f / total)
    val h1 = r1 * d2
    val h2 = r2 * d2
    return Path().apply {
        moveTo(p1a.x, p1a.y)
        val k1 = pt(p1a, 0f, a1 - half, h1); val k2 = pt(p2a, 0f, a3 + half, h2)
        cubicTo(k1.x, k1.y, k2.x, k2.y, p2a.x, p2a.y)
        lineTo(p2b.x, p2b.y)
        val k3 = pt(p2b, 0f, a4 - half, h2); val k4 = pt(p1b, 0f, a2 + half, h1)
        cubicTo(k3.x, k3.y, k4.x, k4.y, p1b.x, p1b.y)
        close()
    }
}

/**
 * Baloncuk merkezinin pencere ortasına göre yatay konumu: sep = 0 adanın ucunun içinde, sep = 1 son konumunda.
 * [rs] baloncuğun yarıçapı, [gap] adayla arasındaki boşluk (px).
 */
private fun Density.sideCenterX(dir: Int, geo: Geo, progress: IslandProgress, sep: Float, rs: Float, gap: Float, skip: Float = 0f): Float {
    val pillW = lerp(geo.cw, geo.ew, progress.w.value).toPx()
    val capX = pillW / 2f - rs
    // skip: dış nokta için iç noktanın kapladığı yer (iç nokta çapı + boşluk)
    return dir * (capX + (2f * rs + gap + skip) * sep.coerceAtLeast(0f))
}

/** Ortadaki adanın yanındaki baloncuklar: [0] sağda, [1] solda. Her biri adadan sıvı gibi bölünüp ayrılır, birleşirken geri girer. */
@Composable
private fun IslandSides(
    sides: List<IslandContent>, geo: Geo, progress: IslandProgress, cfg: IslandSettings, color: State<Color>, fromSide: Int,
    animExpanded: Boolean, wantExpanded: Boolean, onPress: (Int) -> Unit, onTap: (Int) -> Unit,
) {
    // Üç nokta modu: boştaki yuvalar da siyah nokta olarak görünür (ada genişleyince solar)
    if (cfg.dotMode) {
        DotPlaceholder(1, 1, sides.getOrNull(0) != null, geo, progress, cfg, color)
        DotPlaceholder(-1, 1, sides.getOrNull(1) != null, geo, progress, cfg, color)
        if (dotHasOuter(cfg, 1)) DotPlaceholder(1, 2, sides.getOrNull(2) != null, geo, progress, cfg, color)     // 4 ve 5 nokta: sağ dış nokta
        if (dotHasOuter(cfg, -1)) DotPlaceholder(-1, 2, sides.getOrNull(3) != null, geo, progress, cfg, color)   // 5 nokta: sol dış nokta
    }
    SideSlot(sides.getOrNull(0), 1, 1, geo, progress, cfg, color, fromSide == 1, animExpanded, wantExpanded, { onPress(0) }) { onTap(0) }
    SideSlot(sides.getOrNull(1), -1, 1, geo, progress, cfg, color, fromSide == -1, animExpanded, wantExpanded, { onPress(1) }) { onTap(1) }
    // 4 ve 5 nokta: dış yuvalar (sağ dış = 3. olay, sol dış = 4. olay); fromSide ±2 dış yuvadan açılışı belirtir
    if (dotHasOuter(cfg, 1)) SideSlot(sides.getOrNull(2), 1, 2, geo, progress, cfg, color, fromSide == 2, animExpanded, wantExpanded, { onPress(2) }) { onTap(2) }
    if (dotHasOuter(cfg, -1)) SideSlot(sides.getOrNull(3), -1, 2, geo, progress, cfg, color, fromSide == -2, animExpanded, wantExpanded, { onPress(3) }) { onTap(3) }
}

/** Üç nokta modunda yan noktanın boş hali (olay yokken de nokta görünür). */
@Composable
private fun DotPlaceholder(dir: Int, level: Int, hasTarget: Boolean, geo: Geo, progress: IslandProgress, cfg: IslandSettings, color: State<Color>) {
    Canvas(Modifier.fillMaxSize()) {
        val k = (1f - progress.mean * 2f).coerceIn(0f, 1f)
        if (k <= 0f) return@Canvas
        if (hasTarget && cfg.glassEnabled) return@Canvas      // cam modunda üst üste binen yarı saydam daire koyulaşmasın
        val rs = sideSizeDp(cfg, dir).dp.toPx() / 2f
        val gap = sideGapDp(cfg).dp.toPx()
        var cxd = size.width / 2f + sideCenterX(dir, geo, progress, 1f, rs, gap)    // iç nokta
        var r = rs
        if (level == 2) {                                                          // dış nokta: iç noktanın dışında, aynı boşlukla
            val ro = dotOuterSizeDp(cfg, dir).dp.toPx() / 2f
            cxd += dir * (rs + gap + ro)
            r = ro
        }
        val c = Offset(cxd, geo.ch.toPx() / 2f)
        drawCircle(color.value.let { it.copy(alpha = it.alpha * k) }, r, c)
        if (cfg.borderEnabled) {
            val bw = cfg.borderWidthDp.dp.toPx()
            drawCircle(Color(cfg.borderColorArgb).copy(alpha = k), r - bw / 2f, c, style = Stroke(bw))
        }
    }
}

@Composable
private fun SideSlot(
    target: IslandContent?, dir: Int, level: Int, geo: Geo, progress: IslandProgress, cfg: IslandSettings, color: State<Color>, tappedOpen: Boolean,
    animExpanded: Boolean, wantExpanded: Boolean, onPress: () -> Unit, onTap: () -> Unit,
) {
    val hold = remember { SideHold() }
    // Ada küçülürken yeni belirecek baloncuk, ada hâlâ büyükken (panelin köşesinden) çıkmaz:
    // genişlik VE yükseklik neredeyse kapalı hâle gelince kendi yerinde adadan ayrılır.
    // Ada açıkken gelen baloncuk eskisi gibi hemen çıkar. (Eşik, ortalama değil max: "Sıvı" stilinde biri geç kalabilir.)
    val gateOpenState = remember { BooleanArray(1) }
    val nearCompact by remember(progress) { derivedStateOf { max(progress.w.value, progress.h.value) < 0.06f } }
    val gateOpen = gateOpenState[0] || nearCompact || (wantExpanded && animExpanded)
    val gone = remember { BooleanArray(1) }       // kaldırılan baloncuk ada genişlemesiyle bir kez tamamen solduysa, ada küçülürken geri belirmez
    if (target != null) gone[0] = false
    if (target != null) hold.c = target          // birleşme animasyonu sürerken son içerik çizilmeye devam eder
    val present = target != null
    // Dokunulup açılan baloncuk küçülüp kendi yerine (daire olarak) döndü: yeniden adadan ayrılmaz, anında yerine oturur
    val tapHold = remember { BooleanArray(1) }
    if (tappedOpen) tapHold[0] = true
    val snapIn = tapHold[0] && !tappedOpen && present
    SideEffect { if (snapIn) tapHold[0] = false }
    val spec: AnimationSpec<Float> = if (cfg.animEnabled && !snapIn) spring(dampingRatio = 0.72f, stiffness = 150f) else snap()
    val sep = animateFloatAsState(if (target != null && gateOpen) 1f else 0f, spec, label = "sideSep")
    if (target == null) gateOpenState[0] = false
    else if (gateOpen || sep.value > 0.5f) gateOpenState[0] = true
    // Dokunulan baloncuk ortadaki adaya dönüşüp büyüyor: eski baloncuk anında çizilmeyi bırakır (çift görüntü olmasın)
    if (!present && tappedOpen) { hold.c = null; return }
    val alive by remember(present, gateOpen) { derivedStateOf { (present && gateOpen) || sep.value > 0.004f } }
    if (!alive) { hold.c = null; return }
    val shown = hold.c ?: return

    Box(Modifier.fillMaxSize()) {
        // Köprü + baloncuk gövdesi (ada ile aynı renk)
        Canvas(Modifier.fillMaxSize()) {
            // Baloncuk hâlâ aktifse (genişleyen panelin yanında kalıyorsa) tam görünür; yalnızca kaldırılırken ada genişlemesiyle solar
            val k = if (present) 1f else if (gone[0]) 0f else (1f - progress.mean * 2f).coerceIn(0f, 1f)
            if (!present && k <= 0f) gone[0] = true
            if (k <= 0f) return@Canvas
            val ch = geo.ch.toPx()
            val rm = min(36.dp.toPx(), ch / 2f)                      // ortadaki adanın uç yarıçapı
            val rs = slotSizeDp(cfg, dir, level).dp.toPx() / 2f      // bu baloncuğun yarıçapı (nokta modunda ayrı ayarlı)
            val gap = sideGapDp(cfg).dp.toPx()
            val skip = if (level == 2) sideSizeDp(cfg, dir).dp.toPx() + gap else 0f   // dış nokta: iç noktanın yerini atlar
            val cx = size.width / 2f
            val cy = ch / 2f
            val pillHalf = lerp(geo.cw, geo.ew, progress.w.value).toPx() / 2f
            val a = Offset(cx + dir * (pillHalf - rm), cy)           // adanın bu yandaki ucu (yuvarlak kısım)
            val b = Offset(cx + sideCenterX(dir, geo, progress, sep.value, rs, gap, skip), cy)     // baloncuk
            val col = color.value.let { it.copy(alpha = it.alpha * k) }
            drawCircle(col, rs, b)
            // Neck: daireler birbirinden uzaklaştıkça incelir, belli bir noktada kopar
            metaballPath(a, rm, b, rs, rm + rs + gap * 0.55f)?.let { drawPath(it, col) }
            if (cfg.borderEnabled) {
                val bw = cfg.borderWidthDp.dp.toPx()
                drawCircle(Color(cfg.borderColorArgb).copy(alpha = k), rs - bw / 2f, b, style = Stroke(bw))
            }
        }
        // İçerik: baloncuk ayrıldıkça görünür (albüm kapağı / kırmızı nokta / telefon simgesi)
        val sizeDp = slotSizeDp(cfg, dir, level).dp
        Box(
            Modifier.align(Alignment.TopCenter)
                .offset { IntOffset(0, ((geo.ch.toPx() - sizeDp.toPx()) / 2f).roundToInt()) }
                .requiredSize(sizeDp)
                .graphicsLayer {
                    val s = sep.value
                    val g = sideGapDp(cfg).dp.toPx()
                    translationX = sideCenterX(dir, geo, progress, s, sizeDp.toPx() / 2f, g, if (level == 2) sideSizeDp(cfg, dir).dp.toPx() + g else 0f)
                    // Dokunulan baloncuğun yerinde, ada genişlemeye başlayana kadar yeni (genişleyen) adanın dairesi durur: altındaki simge görünmesin
                    val hide = tappedOpen && !animExpanded
                    alpha = if (hide) 0f else ((s - 0.45f) / 0.45f).coerceIn(0f, 1f) * (if (present) 1f else if (gone[0]) 0f else (1f - progress.mean * 2f).coerceIn(0f, 1f))
                }
                // Parmak değdiği anda haber verilir (dokunuşu tüketmez): servis pencereyi önceden büyütür
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onPress() } }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (present) onTap() },
            contentAlignment = Alignment.Center,
        ) { SideGlyph(shown, sizeDp) }
    }
}

@Composable
private fun SideGlyph(c: IslandContent, ch: Dp) {
    when (c) {
        is IslandContent.Media -> {
            val side = ch * 0.64f
            val art = c.art
            if (art != null) {
                Image(
                    bitmap = art, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                    modifier = Modifier.size(side).clip(RoundedCornerShape(side * 0.28f)),
                )
            } else {
                Text("♪", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
            }
        }
        is IslandContent.ScreenRec -> when (c.phase) {
            RecPhase.Processing -> RecSpinner(14.dp)
            RecPhase.Countdown -> Text(c.count.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            else -> RecDot(true, 10.dp)
        }
        is IslandContent.Call -> PhoneIcon(0f, Modifier.size(14.dp), CallGreen)
        // Aşağıdakiler üç nokta modunda ortadaki noktada görünür (kapalı ada daire olduğu için)
        is IslandContent.Notif -> {
            val sz = ch * 0.62f
            val ic = c.icon
            if (ic != null) {
                Image(
                    bitmap = ic, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                    modifier = Modifier.size(sz).clip(RoundedCornerShape(sz * 0.28f)),
                )
            } else {
                Text(c.title.trim().take(1).uppercase().ifEmpty { "•" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }
        is IslandContent.Shazam -> {
            val sz = ch * 0.60f
            val cover = c.cover
            if (c.phase == ShazamPhase.Found && cover != null) {
                Image(
                    bitmap = cover, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                    modifier = Modifier.size(sz).clip(RoundedCornerShape(sz * 0.28f)),
                )
            } else {
                Canvas(Modifier.size(sz)) { drawShazamGlyph(Offset.Zero, size.minDimension, ShazamBlue, 1f) }
            }
        }
        is IslandContent.Status -> Text(
            when (c.kind) {
                StatusKind.Ringer -> if (c.silent) "🔕" else "🔔"
                StatusKind.Charging -> "⚡"
                StatusKind.Timer -> "⏱"
                StatusKind.Alarm -> "⏰"
            },
            fontSize = 13.sp,
        )
        else -> Unit
    }
}

// ---------------------------------------------------------------- Albüm kapağı dönme (Y ekseninde)
/** Kapak dönme durumu: şarkı değişiminde yarım tur (180°), yön sonraki / önceki şarkıya göre değişir. */
@Stable
class ArtFlip {
    var dir by mutableIntStateOf(1)        // bir sonraki değişim için istenen yön: +1 sonraki, -1 önceki
    var flipDir by mutableIntStateOf(1)    // oynayan dönüşün yönü
    var from by mutableStateOf<ImageBitmap?>(null)   // dönüşün ilk yarısında görünen eski kapak
    var held by mutableStateOf<ImageBitmap?>(null)   // yeni şarkının kapağı yüklenene kadar eski kapak burada tutulur (kapak yok olmasın)
    var trackDeg by mutableFloatStateOf(0f)   // şarkı değişimi dönüşü: 0..180 derece (büyüklük)
    val spin = Animatable(0f)              // ada açılırken 0 -> 360, küçülürken 360 -> 0
}

private class TrackHolder { var key: String? = null; var art: ImageBitmap? = null }

/** Açıyı -90..90 arasına katlar: kart arka yüzü görününce ayna görüntüsü yerine düz görünür (kapak 90°'de değişir). */
private fun foldDeg(a: Float): Float = ((a + 90f) % 180f + 180f) % 180f - 90f

/**
 * Dönen albüm kapağı. [spin] ada geçişinin dönüşü, [flip] şarkı değişiminin dönüşüdür; ikisi toplanır.
 * Kenar-üstüne yaklaştıkça kart kararır ve hafif bulanır (perspektifli, iOS tarzı).
 */
@Composable
fun FlipAlbumArt(
    art: ImageBitmap?, size: Dp, flip: ArtFlip?, spin: () -> Float, corner: Dp,
    allowOld: Boolean = true, modifier: Modifier = Modifier,
) {
    val showOld by remember(flip) { derivedStateOf { flip != null && flip.from != null && flip.trackDeg < 90f } }
    val old = flip?.from
    // İlk yarıda eski kapak; ikinci yarıda yenisi (henüz yüklenmediyse eskisi kalır, boşluk / ♪ görünmez)
    val shown = when { !allowOld || old == null -> art; showOld -> old; else -> art ?: old }
    val shape = RoundedCornerShape(corner)
    fun angle(): Float = spin() + if (flip != null) flip.flipDir * flip.trackDeg else 0f
    Box(
        modifier.size(size)
            .graphicsLayer {
                val f = foldDeg(angle())
                rotationY = f
                // Kamera, kartın boyutundan uzakta olmalı; yakın olursa dönerken kart kamerayın arkasına geçip kaybolur
                cameraDistance = maxOf(this.size.width, this.size.height) * 3f   // 'size' parametresi (Dp) ile karışmasın
                setBlurPx(abs(f) / 90f * 5.dp.toPx())
            }
            .clip(shape)
            .drawWithContent {
                drawContent()
                val k = abs(foldDeg(angle())) / 90f
                if (k > 0.01f) drawRect(Color.Black.copy(alpha = (k * k * 0.75f).coerceIn(0f, 0.85f)))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (shown != null) {
            Image(
                bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text("♪", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp)
            }
        }
    }
}

@Immutable
data class Geo(val cw: Dp, val ch: Dp, val ew: Dp, val eh: Dp)

fun compactWidthFor(cfg: IslandSettings, compactLyrics: Boolean): Float =
    if (compactLyrics) maxOf(cfg.compactWidthDp, cfg.lyricsCompactWidthDp) else cfg.compactWidthDp

fun islandGeo(cfg: IslandSettings, music: Boolean, compactLyrics: Boolean = false) = Geo(
    cw = compactWidthFor(cfg, compactLyrics && music).dp,
    ch = cfg.compactHeightDp.dp,
    ew = cfg.expandedWidthDp.dp,
    eh = (cfg.expandedHeightDp + if (music) cfg.musicExtraDp() else 0f).dp,
)

/**
 * Müzikten çıkış "yutma" efektinde adanın büyüdüğü boyut (dp): kapalı adadan biraz geniş, yaklaşık iki kat yüksek;
 * açık ada boyutunu aşmaz. Hem arayüz hem pencere boyutu aynı formülü kullanır.
 */
fun swallowSizeDp(cw: Float, ch: Float, ew: Float, eh: Float): Pair<Float, Float> =
    minOf(ew, cw * 1.15f).coerceAtLeast(cw) to minOf(eh, ch * 2.1f).coerceAtLeast(ch)

// ---------------------------------------------------------------- Animasyon (5 stil + kapalı)
/** Genişlik ve yükseklik ilerlemesi ayrı: "Sıvı" stilinde farklı zamanlanır, diğerlerinde aynıdır. */
@Stable
class IslandProgress(val w: State<Float>, val h: State<Float>) {
    val mean: Float get() = (w.value + h.value) / 2f
}

private fun specFor(cfg: IslandSettings, expanding: Boolean, isWidth: Boolean): FiniteAnimationSpec<Float> {
    if (!cfg.animEnabled) return snap()
    return when (cfg.animStyle) {
        0 -> spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)           // Yaylı
        1 -> tween(520, easing = FastOutSlowInEasing)                                      // Yumuşak
        2 -> tween(220, easing = LinearOutSlowInEasing)                                    // Hızlı
        3 -> spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)           // Zıplamalı
        else -> {                                                                          // Sıvı
            val first = if (expanding) isWidth else !isWidth       // açılırken önce genişlik, kapanırken önce yükseklik
            tween(durationMillis = 340, delayMillis = if (first) 0 else 140, easing = FastOutSlowInEasing)
        }
    }
}

@Composable
fun rememberIslandProgress(cfg: IslandSettings, expanded: Boolean, fast: Boolean = false): IslandProgress {
    val target = if (expanded) 1f else 0f
    // Yutma efekti: ada panel adaya varmadan büyümüş olsun diye ayardaki stil yerine kısa, düz bir animasyon
    val fastSpec: FiniteAnimationSpec<Float> = if (cfg.animEnabled) tween(160, easing = LinearOutSlowInEasing) else snap()
    val pw = animateFloatAsState(target, if (fast) fastSpec else specFor(cfg, expanded, true), label = "pw")
    val ph = animateFloatAsState(target, if (fast) fastSpec else specFor(cfg, expanded, false), label = "ph")
    return remember(pw, ph) { IslandProgress(pw, ph) }
}

private const val EXPAND_LEAD_MS = 24L          // pencere büyüdükten sonra ek bekleme (yaklaşık 1,5 kare)
private const val EXPAND_WAIT_MAX_MS = 220L    // pencere büyümezse en fazla bu kadar beklenir (güvenlik)

/** Servisteki syncWindow ile aynı hesap: açık haldeki ada yüksekliği (dp), pay hariç. */
private fun expandedHeightDpFor(c: IslandContent, cfg: IslandSettings): Float = when (c) {
    IslandContent.Assistant -> cfg.assistantHeightDp
    is IslandContent.Favorites -> cfg.favoritesHeightDp(c.apps.size)
    is IslandContent.Call -> if (c.isIncoming) 74f else 88f
    is IslandContent.Status -> if (c.kind == StatusKind.Charging) chargePillHeightDp(cfg) else cfg.expandedHeightDp
    is IslandContent.ScreenRec -> REC_PILL_H_DP
    is IslandContent.Shazam -> SHAZAM_PILL_H_DP
    is IslandContent.Media -> cfg.expandedHeightDp + cfg.musicExtraDp()
    else -> cfg.expandedHeightDp
}

@Composable
fun IslandRoot(
    state: StateFlow<IslandUiState>,
    settingsFlow: StateFlow<IslandSettings>,
    lyricsFlow: StateFlow<LyricsState>,
    onTap: () -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    onLongPress: () -> Unit = {},
    onLaunch: (String) -> Unit = {},
    onAnswer: () -> Unit = {},
    onDecline: () -> Unit = {},
    onSpeaker: () -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onSwipe: (Int) -> Unit = {},     // +1 = aşağı, -1 = yukarı
    onRecStop: () -> Unit = {},
    onShazamStop: () -> Unit = {},       // genişlemiş Shazam "Listening..." panelindeki X düğmesi
    onNotifSwipeSide: () -> Unit = {},   // bildirim üstünde sağa / sola kaydırma
    onSideTap: (Int) -> Unit = {},       // yan baloncuğa dokunma: 0 = sağ, 1 = sol
    onSidePress: (Int) -> Unit = {},     // yan baloncuğa parmak değdi (henüz dokunma tamamlanmadı): pencere önceden büyütülür
) {
    val s by state.collectAsState()
    val cfg by settingsFlow.collectAsState()
    val lyrics by lyricsFlow.collectAsState()
    val content = s.content
    val media = content as? IslandContent.Media

    // Kapak dönme: şarkı değişince eski kapak yarım tur dönüp yenisiyle değişir (yön: sonraki / önceki)
    val artFlip = remember { ArtFlip() }
    val trackHolder = remember { TrackHolder() }
    val trackKey = media?.let { it.title + "|" + it.artist }
    val flipOn = cfg.animEnabled && cfg.musicArtFlip
    SideEffect {
        if (trackKey == trackHolder.key) trackHolder.art = media?.art
        if (media?.art != null && artFlip.held != null) artFlip.held = null   // yeni kapak geldi: tutulan eskisi bırakılır
    }
    // Şarkı değişimi, yeni şarkının İLK karesinde eski kapak görünsün diye kompozisyon sırasında hazırlanır (kapak yanıp sönmesin)
    remember(trackKey) {
        val old = trackHolder.art
        val prev = trackHolder.key
        artFlip.trackDeg = 0f
        artFlip.from = if (flipOn && trackKey != null && prev != null && trackKey != prev && old != null) old else null
        if (artFlip.from != null) { artFlip.flipDir = artFlip.dir; artFlip.dir = 1 }
        // Yeni şarkının kapağı henüz yüklenmediyse (art == null) eski kapak ekranda kalır; aksi halde kapak bir an yok olurdu
        artFlip.held = if (trackKey != null && prev != null && trackKey != prev && media?.art == null) old else null
        trackHolder.key = trackKey; trackHolder.art = media?.art
        0
    }
    LaunchedEffect(trackKey) {
        if (artFlip.from != null) {
            animate(0f, 180f, animationSpec = tween(600, easing = FastOutSlowInEasing)) { v, _ -> artFlip.trackDeg = v }
            artFlip.from = null
            artFlip.trackDeg = 0f
        }
    }
    // Yeni şarkının kapağı hiç gelmezse (kapaksız uygulama) eski kapak sonsuza dek kalmasın
    LaunchedEffect(trackKey) { if (artFlip.held != null) { delay(2_500); artFlip.held = null } }
    val cmd: (MediaCommand) -> Unit = { c ->
        when (c) { MediaCommand.Next -> artFlip.dir = 1; MediaCommand.Previous -> artFlip.dir = -1; else -> Unit }
        onCommand(c)
    }
    // Açılırken animasyonu çok kısa geciktir: pencere önce büyüsün, animasyonun ilk (en hızlı) kareleri
    // küçük pencereye çizilip kırpılmasın ("yaylı" animasyonda açılışta takılma). Kapanış gecikmesizdir.
    val wantExpanded = s.expanded   // arama: açık/küçük durumunu servis belirler (gelen arama hep açık)
    var animExpanded by remember { mutableStateOf(wantExpanded) }
    // Pencerenin ölçülen yüksekliği: servis pencereyi büyütünce (sistem uygulayınca) değişir
    var winH by remember { mutableIntStateOf(0) }
    val densityNow = androidx.compose.ui.platform.LocalDensity.current
    val contentNow by rememberUpdatedState(content)
    val cfgLatest by rememberUpdatedState(cfg)
    LaunchedEffect(wantExpanded) {
        if (wantExpanded && cfg.animEnabled && !s.swallow) {
            // Sabit 70 ms yerine: pencere açık boyuta ulaşana kadar bekle (en çok EXPAND_WAIT_MAX_MS).
            // Yay animasyonunun ilk (çok hızlı) kareleri küçük pencerede kırpılırsa ada "pat diye" açılmış gibi görünür.
            val c = cfgLatest
            val needPx = with(densityNow) { (expandedHeightDpFor(contentNow, c) + 2f * c.windowPadDp()).dp.roundToPx() }
            if (winH < needPx - 2) {
                kotlinx.coroutines.withTimeoutOrNull(EXPAND_WAIT_MAX_MS) { snapshotFlow { winH }.first { it >= needPx - 2 } }
                delay(EXPAND_LEAD_MS)       // pencere yüzeyi yeni boyutla en az bir kare çizsin
            }
        }
        animExpanded = wantExpanded
    }
    val progress = rememberIslandProgress(cfg, animExpanded, s.swallow)
    val prevCw = remember { mutableFloatStateOf(cfg.compactWidthDp) }
    val popIn = remember { Animatable(1f) }
    val popLatch = remember { mutableStateOf(false) }
    val ghostHold = remember { SideHold() }
    val ghostProgress = remember { IslandProgress(mutableStateOf(0f), mutableStateOf(0f)) }
    val popScope = rememberCoroutineScope()
    val fromKind = remember { arrayOfNulls<Any>(1) }
    // Genişletilmiş kapak dönüşü zamana bağlı (yavaş): açılırken 0 -> 360, küçülürken ters yönde 360 -> 0
    LaunchedEffect(animExpanded, flipOn) {
        if (!flipOn) { artFlip.spin.snapTo(0f); return@LaunchedEffect }
        if (animExpanded) { artFlip.spin.snapTo(0f); artFlip.spin.animateTo(360f, tween(1100, easing = FastOutSlowInEasing)) }
        else artFlip.spin.animateTo(0f, tween(750, easing = FastOutSlowInEasing))   // küçülürken kapalı adadaki kapak da döner (artSpin)
    }
    val lyricsOn = cfg.lyricsEnabled && (cfg.lyricsInPanel || cfg.lyricsCompact)
    val frame = rememberLyricFrame(
        media, lyrics, cfg.lyricsOffsetMs.toLong(),
        active = lyricsOn && lyrics is LyricsState.Ready, fine = cfg.lyricsFillEnabled,
    )

    // Yan baloncukların rengi: ortadaki adanın gövde rengiyle aynı (kapak rengi açıksa ve müzik ortadaysa kapaktan)
    val sideArtColor = media?.artColor?.takeIf { it != 0 }?.let { Color(it) }
    val sideBody = animateColorAsState(bodyColorFor(cfg, sideArtColor, Color(cfg.accentArgb)), tween(450), label = "sideBody")

    // Tam genişlik asistan: ada kutusu yok, pencere ekran genişliğinde; animasyon doğrudan çizilir
    if (content is IslandContent.Assistant && cfg.assistantFullWidth) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(s.expanded) { shown = s.expanded }
        val barAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 350 else 250), label = "orbBar")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = barAlpha }) { AssistantOrb(fullWidth = true) }
        return
    }

    // Pencere, ada + parıltı payı kadar sabit boyutlu; ada üst-ortada büyür/küçülür
    Box(
        Modifier.fillMaxSize().onSizeChanged { winH = it.height }.padding(cfg.windowPadDp().dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Boştayken de göster açıksa Idle içerikte de (boş) ada çizilir; kapalıysa hiçbir şey çizilmez.
        if (content !is IslandContent.Idle || cfg.showIdle) {
            val music = content is IslandContent.Media
            // Sözler görününce kapalı ada yumuşakça genişler / sözler bitince daralır
            // Yutma efektinde ada, sözler açık olsa bile VARSAYILAN kapalı genişlikten büyür; efekt bitince ayara göre genişler
            val cwSpec: AnimationSpec<Dp> = if (cfg.animEnabled) tween<Dp>(if (s.swallow) 140 else 380, easing = FastOutSlowInEasing) else snap<Dp>()
            val cw by animateDpAsState(compactWidthFor(cfg, s.compactLyrics && music && !s.swallow).dp, cwSpec, label = "cw")
            var geo = islandGeo(cfg, music, s.compactLyrics).copy(cw = cw)
            if (content is IslandContent.Assistant) geo = geo.copy(ew = cfg.assistantWidthDp.dp, eh = cfg.assistantHeightDp.dp)
            if (content is IslandContent.Favorites) geo = geo.copy(eh = cfg.favoritesHeightDp(content.apps.size).dp)
            if (content is IslandContent.Call) geo = geo.copy(cw = if (cfg.dotMode) cw else maxOf(cw, CALL_COMPACT_W_DP.dp), eh = if (content.isIncoming) 74.dp else 88.dp)
            if (content is IslandContent.Status && content.kind == StatusKind.Charging) {
                geo = geo.copy(ew = CHARGE_PILL_W_DP.dp, eh = chargePillHeightDp(cfg).dp)
            }
            // Ekran kaydı: geri sayım / işleniyor aşamasında kapalı ada biraz genişler (sağda rakam / halka için)
            val recWide = !cfg.dotMode && content is IslandContent.ScreenRec &&
                (content.phase == RecPhase.Countdown || content.phase == RecPhase.Processing)
            val recCw by animateDpAsState(
                if (recWide) maxOf(cw, CALL_COMPACT_W_DP.dp) else cw,
                tween(240, easing = FastOutSlowInEasing), label = "recCw",
            )
            if (content is IslandContent.Shazam) geo = geo.copy(eh = SHAZAM_PILL_H_DP.dp)
            if (content is IslandContent.ScreenRec) {
                val screenWDp = LocalConfiguration.current.screenWidthDp.toFloat()
                geo = geo.copy(cw = recCw, ew = recExpandedWidthDp(cfg, screenWDp).dp, eh = REC_PILL_H_DP.dp)
            }
            if (s.swallow && content is IslandContent.Media) {
                val sz = swallowSizeDp(geo.cw.value, geo.ch.value, geo.ew.value, geo.eh.value)
                geo = geo.copy(ew = sz.first.dp, eh = sz.second.dp)
            }
            // Yan baloncuklar adanın ARKASINA çizilir (ada üstte kalır); köprü adanın ucundan uzanır
            // Önceki ortadaki adanın kapalı genişliği: yan baloncuğun konumu buna göre hesaplanmıştı (animasyonun çıkış noktası)
            if (s.fromSide == 0 && !s.expanded) SideEffect { prevCw.value = geo.cw.value }
            val fromX = if (s.fromSide == 0) 0.dp else run {
                val fd = if (s.fromSide > 0) 1 else -1                  // yön
                val lvl = abs(s.fromSide)                                // 1 = iç yuva, 2 = dış yuva
                val r = slotSizeDp(cfg, fd, lvl) / 2f
                val skip = if (lvl == 2) sideSizeDp(cfg, fd) + sideGapDp(cfg) else 0f
                (fd * (prevCw.value / 2f + skip + sideGapDp(cfg) + r)).dp
            }
            // Eski ortadaki olayın son görüntüsü: baloncuğa basılınca, yeni ada genişlemeye başlayana kadar ortada kalır (boşluk olmasın)
            val ghostWanted = s.fromSide != 0 && s.expanded && !animExpanded
            if (s.fromSide == 0 && !s.expanded) ghostHold.c = content
            val ghostFade = remember { Animatable(0f) }
            LaunchedEffect(ghostWanted) { if (ghostWanted) ghostFade.snapTo(1f) else ghostFade.animateTo(0f, tween(180)) }
            // Baloncuktan açılırken yeni içeriğin kapalı genişliği (cw) animasyonla değişir; baloncuklar buna değil,
            // baloncuğun çıkış noktasını belirleyen sabit genişliğe bağlanır: yerlerinde kayıp oynamazlar.
            val sideGeo = if (s.fromSide != 0) geo.copy(cw = prevCw.value.dp) else geo
            IslandSides(s.side, sideGeo, progress, cfg, sideBody, s.fromSide, animExpanded, s.expanded, onSidePress, onSideTap)
            val ghostC = ghostHold.c
            if (ghostC != null && (ghostWanted || ghostFade.value > 0.01f)) {
                val ghostGeo = islandGeo(cfg, ghostC is IslandContent.Media).copy(cw = prevCw.value.dp)
                Box(Modifier.graphicsLayer { alpha = if (ghostWanted) 1f else ghostFade.value }) {
                    IslandPill(ghostC, ghostProgress, ghostGeo, cfg, lyrics, frame, false, {}, {}, {})
                }
            }
            // Baloncuğa küçülüp yerine oturduktan sonra ortadaki ada (önceki olay) yumuşakça belirir
            val trigger = fromKind[0] != null && s.fromSide == 0 && !s.expanded && fromKind[0] != content::class
            if (s.fromSide != 0) fromKind[0] = content::class
            else if (!trigger && (s.expanded || fromKind[0] == content::class)) fromKind[0] = null
            // Ada, tetikten animasyon başlayana kadar gizli kalır (bir kare görünüp yanıp sönmesin)
            val hidden = trigger || popLatch.value
            LaunchedEffect(trigger) {
                if (trigger) {
                    popLatch.value = true
                    fromKind[0] = null
                    // Animasyon kendi scope'unda çalışır: anahtar değişince iptal olup adayı şeffaf bırakmaz
                    popScope.launch {
                        try {
                            popIn.snapTo(0f)
                            popLatch.value = false
                            popIn.animateTo(1f, tween(240))
                        } finally { popLatch.value = false }
                    }
                }
            }
            // Ada açılınca (ya da başka olay gelince) her zaman tam görünür
            LaunchedEffect(s.expanded) { if (s.expanded) popIn.snapTo(1f) }
            Box(Modifier.graphicsLayer { alpha = if (hidden) 0f else popIn.value }) {
            IslandPill(content, progress, geo, cfg, lyrics, frame, s.compactLyrics, onTap, cmd, onOutput, onLongPress, onLaunch, onAnswer, onDecline, onSpeaker, swallow = s.swallow, onOpenMusic = onOpenMusic, onSwipe = onSwipe, artFlip = artFlip, onRecStop = onRecStop, onShazamStop = onShazamStop, onNotifSwipeSide = onNotifSwipeSide, fromX = fromX)
            }
        }
    }
}

// ---------------------------------------------------------------- Renk / yüzey / parıltı
private val PillShape = RoundedCornerShape(36.dp)
private val GlassMaxRadius = 36.dp

private fun darkTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    // Eskiden çok koyuydu (0.26): artık kapak rengi daha canlı görünür, beyaz yazı yine rahat okunur.
    // Gri/siyah kapaklar kırmızıya boyanmasın diye gri kalır.
    val gray = hsv[1] < 0.12f
    hsv[1] = if (gray) hsv[1] else hsv[1].coerceIn(0.55f, 0.95f)
    hsv[2] = if (gray) 0.30f else 0.50f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Ekolayzır için: albüm rengi aynı tonda ama en parlak ve biraz pastel; koyu ada gövdesinden net ayrılır. */
private fun brightTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.85f).coerceIn(0f, 0.8f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

private fun bodyColorFor(cfg: IslandSettings, art: Color?, accent: Color): Color {
    val tint: Color? = if (cfg.dynamicColor) art else null
    val base = Color(cfg.islandColorArgb)   // albüm rengi kapalı / kapak yok: ada seçilen renge boyanır (varsayılan siyah)
    return when {
        cfg.glassEnabled -> (tint ?: base).copy(alpha = cfg.glassAlpha)
        tint != null -> darkTone(tint)
        else -> base
    }
}

/** Panel animasyonlarının rengi = adanın o anki gövde rengi (cam açıksa aynı şeffaflıkla). */
fun islandPanelColor(cfg: IslandSettings, artArgb: Int, opaque: Boolean = false): Color {
    val art = if (artArgb != 0) Color(artArgb) else null
    val c = bodyColorFor(cfg, art, Color(cfg.accentArgb))
    // Cam açıkken panel de adanın şeffaflığını taşır; ekranı örtmesi gereken geçişlerde (opaque) opak karşılığı kullanılır
    return if (opaque && c.alpha < 1f) androidx.compose.ui.graphics.lerp(Color.Black, c.copy(alpha = 1f), c.alpha) else c
}

// ---------------------------------------------------------------- Liquid Glass katmanları
/**
 * Liquid Glass — içeriğin ARKASINA çizilen katmanlar:
 *  1) üstten alta ışık geçişi (camın kalınlığı), alt kenarda hafif ışık toplanması
 *  2) sol üstte yumuşak speküler parlama
 *  3) kenarlara doğru incelen iç ışık halkaları (lens / kırılma hissi)
 */
private fun DrawScope.drawGlassBase(cornerT: Float = -1f) {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val r = islandCornerPx(w, h, density, cornerT)

    drawRect(
        Brush.verticalGradient(
            0f to Color.White.copy(0.17f),
            0.40f to Color.White.copy(0.04f),
            0.72f to Color.Transparent,
            1f to Color.White.copy(0.09f),
        )
    )
    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.22f),
            0.6f to Color.White.copy(0.05f),
            1f to Color.Transparent,
            center = Offset(w * 0.20f, h * 0.02f),
            radius = max(w * 0.60f, h),
        )
    )
    // Alt-sağ köşede çok hafif ikincil parlama (ışığın cam içinden geçip toplandığı yer)
    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.08f),
            1f to Color.Transparent,
            center = Offset(w * 0.88f, h * 1.0f),
            radius = max(w * 0.35f, h * 0.9f),
        )
    )

    val rings = 6
    val step = 1.4.dp.toPx()
    for (i in 0 until rings) {
        val inset = i * step + step / 2f
        val a = 0.12f * (1f - i / rings.toFloat())
        drawRoundRect(
            color = Color.White.copy(alpha = a),
            topLeft = Offset(inset, inset),
            size = Size((w - 2 * inset).coerceAtLeast(0f), (h - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
            style = Stroke(width = step),
        )
    }
}

/**
 * Liquid Glass — içeriğin ÜSTÜNE çizilen ince kenar ışığı (specular rim):
 * sol-üstte parlak, ortada sönük, sağ-altta tekrar parlak; iOS'taki cam kenarı gibi.
 */
private fun DrawScope.drawGlassRim(cornerT: Float = -1f) {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val r = islandCornerPx(w, h, density, cornerT)

    val sw = 1.3.dp.toPx()
    val rim = Brush.linearGradient(
        0f to Color.White.copy(0.90f),
        0.28f to Color.White.copy(0.20f),
        0.62f to Color.White.copy(0.07f),
        1f to Color.White.copy(0.55f),
        start = Offset.Zero, end = Offset(w, h),
    )
    drawRoundRect(
        brush = rim,
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
        style = Stroke(width = sw),
    )

    // İç ikinci halka: kenarın "kalınlığını" verir
    val sw2 = 0.7.dp.toPx()
    val off = sw + sw2 / 2f
    drawRoundRect(
        color = Color.White.copy(alpha = 0.10f),
        topLeft = Offset(off, off),
        size = Size((w - 2 * off).coerceAtLeast(0f), (h - 2 * off).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((r - off).coerceAtLeast(0f)),
        style = Stroke(width = sw2),
    )

    // Üst kenarda ortası parlak, uçları kaybolan speküler çizgi
    if (w > 2.2f * r) {
        val y = 1.8.dp.toPx()
        val x0 = r * 0.9f; val x1 = w - r * 0.9f
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.35f to Color.White.copy(0.55f),
                0.65f to Color.White.copy(0.30f),
                1f to Color.Transparent,
                startX = x0, endX = x1,
            ),
            start = Offset(x0, y), end = Offset(x1, y),
            strokeWidth = 1.1.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

/** Renk State olarak taşınır, yalnızca çizim fazında okunur => animasyonda recomposition yok. */
@Composable
private fun Modifier.islandSurface(cfg: IslandSettings, body: State<Color>, shape: Shape = PillShape, cornerT: Float = -1f, borderTint: Color? = null): Modifier {
    val liquid = cfg.glassEnabled && cfg.liquidEnabled
    var m = this.clip(shape).drawBehind { drawRect(body.value) }
    if (liquid) m = m.drawBehind { drawLiquidBase(cfg.liquidSpecular, cornerT) }
    if (liquid) m = m.liquidBackdrop(cfg.liquidRefraction, cfg.liquidDispersion, cornerT)
    else if (cfg.glassEnabled) m = m.drawBehind { drawGlassBase(cornerT) }
    if (cfg.borderEnabled) {
        val rbBorder = rememberRainbowStyle(cfg)
        m = if (rbBorder == null) m.border(cfg.borderWidthDp.dp, borderTint ?: Color(cfg.borderColorArgb), shape)
        else m.drawWithContent {
            drawContent()
            // Şekil kırpıldığı için çizginin yalnızca iç yarısı görünür; bu yüzden 2x kalınlık
            val w = cfg.borderWidthDp.dp.toPx() * 2f
            val brush = rainbowBrush(rbBorder.phase.value, size.width, rbBorder.alpha)
            val r = minOf(36.dp.toPx(), size.height / 2f)
            drawRoundRect(brush, cornerRadius = CornerRadius(r, r), style = Stroke(width = w))
        }
    }
    // Kenar ışığı, içeriğin (yazı/kapak) üstüne çizilir; kullanıcı çerçevesi yine de en üstte kalır
    if (liquid) m = m.drawWithContent { drawContent(); drawLiquidRim(cfg.liquidSpecular, cornerT) }
    else if (cfg.glassEnabled) m = m.drawWithContent { drawContent(); drawGlassRim(cornerT) }
    // En içte: yalnızca iç içerik (kapak, yazı, butonlar) kenarlarda bükülür (Android 13+)
    if (liquid && !LiquidCapture.running) m = m.liquidRefraction(cfg.liquidRefraction, cfg.liquidDispersion, cornerT)
    return m
}

/** Blur filtresi yerine kademeli (azalan opaklıkta) halkalar: GPU dostu, her cihazda çalışır. */
private fun Modifier.islandGlow(enabled: Boolean, color: State<Color>, intensity: Float): Modifier =
    if (!enabled) this else this.drawBehind {
        val rings = 6
        val step = GLOW_PAD_DP.dp.toPx() / rings
        val base = min(36.dp.toPx(), size.height / 2f)
        val c = color.value
        for (i in 1..rings) {
            val inflate = (i - 0.5f) * step
            val fall = 1f - (i - 1f) / rings
            val a = (intensity * 0.6f * fall * fall).coerceIn(0f, 1f)
            drawRoundRect(
                color = c.copy(alpha = a),
                topLeft = Offset(-inflate, -inflate),
                size = Size(size.width + 2 * inflate, size.height + 2 * inflate),
                cornerRadius = CornerRadius(base + inflate),
                style = Stroke(width = step),
            )
        }
    }

@Composable
fun IslandPill(
    content: IslandContent,
    progress: IslandProgress,
    geo: Geo,
    cfg: IslandSettings,
    lyrics: LyricsState,
    frame: LyricFrame,
    compactLyrics: Boolean,
    onTap: () -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    onLongPress: () -> Unit = {},
    onLaunch: (String) -> Unit = {},
    onAnswer: () -> Unit = {},
    onDecline: () -> Unit = {},
    onSpeaker: () -> Unit = {},
    swallow: Boolean = false,      // yutma efekti: ada büyür ama içerik çizilmez
    onOpenMusic: () -> Unit = {},
    onSwipe: (Int) -> Unit = {},
    artFlip: ArtFlip? = null,
    onRecStop: () -> Unit = {},
    onShazamStop: () -> Unit = {},
    onNotifSwipeSide: () -> Unit = {},
    fromX: Dp = 0.dp,       // yan baloncuktan açılıyorsa baloncuğun ada merkezine göre yatay konumu (0 = normal açılış)
) {
    val fromNow by rememberUpdatedState(fromX)
    // Dokunma ve basılı tutma: lambdalar güncellense de hareket algılayıcısı yeniden kurulmaz
    val tapNow by rememberUpdatedState(onTap)
    val longPressNow by rememberUpdatedState(onLongPress)
    val openMusicNow by rememberUpdatedState(onOpenMusic)
    val swipeNow by rememberUpdatedState(onSwipe)
    val commandNow by rememberUpdatedState(onCommand)
    // Asistan modunda: kendi şekli; cam ve sıvı cam ayarları uygulanır. Çerçeve / dış parıltı kapalı (şekle uymaz).
    val assistant = content is IslandContent.Assistant
    val cfg = if (assistant) cfg.copy(borderEnabled = false, glowEnabled = false) else cfg
    val cornerT = if (assistant) progress.mean else -1f
    val cfgNow by rememberUpdatedState(cfg)
    val isMediaNow by rememberUpdatedState(content is IslandContent.Media)
    val isNotifNow by rememberUpdatedState(content is IslandContent.Notif)
    val notifSideNow by rememberUpdatedState(onNotifSwipeSide)
    val shape: Shape = if (assistant) AssistantShape(cornerT) else PillShape
    val accent = remember(cfg.accentArgb) { Color(cfg.accentArgb) }
    val rb = rememberRainbowStyle(cfg)
    val media = content as? IslandContent.Media
    val artColor = if (media != null && media.artColor != 0) Color(media.artColor) else null
    val highlight = if (cfg.dynamicColor && artColor != null) artColor else accent
    // Ekolayzır: kendi ayarı. Kapak rengi açıksa (ve kapak varsa) kapaktan, değilse seçilen renkten
    val eqFromArt = cfg.eqDynamicColor && artColor != null
    val eqColor = remember(eqFromArt, artColor, cfg.eqColorArgb) {
        if (eqFromArt) brightTone(artColor!!) else Color(cfg.eqColorArgb)
    }

    // Galeriden seçilen özel görseller (ayar kapalıysa boş adres verilir => null). Diske kopyalanmaz.
    val bgOn = cfg.customBgEnabled
    val bgBoth = rememberCustomImage(CustomImages.Slot.BG_BOTH, if (bgOn && !cfg.customBgSeparate) cfg.customBgBothUri else "")
    val bgCompact = rememberCustomImage(CustomImages.Slot.BG_COMPACT, if (bgOn && cfg.customBgSeparate) cfg.customBgCompactUri else "")
    val bgExpanded = rememberCustomImage(CustomImages.Slot.BG_EXPANDED, if (bgOn && cfg.customBgSeparate) cfg.customBgExpandedUri else "")
    val customArt = rememberCustomImage(CustomImages.Slot.ART, if (cfg.customArtEnabled) cfg.customArtUri else "")

    // Asistanda cam gövde en az %60 koyu kalır: renk bantları koyu zeminde okunur
    val bodyTarget = if (assistant && cfg.glassEnabled) Color.Black.copy(alpha = max(cfg.glassAlpha, 0.6f)) else bodyColorFor(cfg, artColor, accent)
    // Yutma efektinde ada rengi anında hedef renge geçer: panel animasyonuyla (aynı renk) uyumlu olsun
    val bodyAnim = animateColorAsState(bodyTarget, if (swallow) snap() else tween(450), label = "body")
    val body = remember(swallow, bodyTarget, bodyAnim) { derivedStateOf { if (swallow) bodyTarget else bodyAnim.value } }
    val glow = animateColorAsState(artColor ?: accent, tween(450), label = "glow")

    val panelVisible by remember(progress) { derivedStateOf { max(progress.w.value, progress.h.value) > 0.01f } }
    // Panel önceden hazırlama: parmak adaya değince müzik paneli görünmeden kompoze edilir; açılış animasyonunun
    // ilk karelerinde ağır kompozisyon (takılma) olmaz. 1,5 sn içinde açılmazsa bırakılır.
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(warm) { if (warm) { delay(1_500); warm = false } }
    val compactVisible by remember(progress) { derivedStateOf { progress.mean < 0.5f && fromNow == 0.dp } }

    Box(
        modifier = Modifier
            // Yan baloncuktan açılış: ada baloncuğun yerinden (daire) başlar, büyürken ortaya kayar
            .graphicsLayer { if (fromNow != 0.dp) translationX = fromNow.toPx() * (1f - progress.w.value).coerceIn(0f, 1f) }
            .layout { measurable, _ ->
                val w = lerp(if (fromNow != 0.dp) geo.ch else geo.cw, geo.ew, progress.w.value).roundToPx().coerceAtLeast(1)
                val h = lerp(geo.ch, geo.eh, progress.h.value).roundToPx().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .islandGlow(cfg.glowEnabled, glow, cfg.glowIntensity)
            .islandSurface(cfg, body, shape, cornerT, if (cfg.borderArtColor && artColor != null) brightTone(artColor) else null)
            .pointerInput(Unit) {
                // Dokunma + basılı tutma. Ada küçük olduğu için hızlı dokunuşta parmak kenardan kayıp çıkabiliyordu
                // ve standart algılayıcı bunu iptal ediyordu; burada parmak ada dışına çıksa da ~40 dp'ye kadar
                // dokunuş sayılır. Daha fazla sürüklenirse iptal edilir.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (isMediaNow) warm = true
                    val slop = 40.dp.toPx()
                    val swipeMin = 18.dp.toPx()
                    var outcome = 0      // 0 = basılı tutma, 1 = dokunma, 2 = iptal, 3 = dikey kaydırma
                    var swipeDir = 0
                    var swipeDirX = 0
                    withTimeoutOrNull(cfgNow.longPressMs.toLong()) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed) { outcome = 2; break }
                            if (!change.pressed) { outcome = 1; break }
                            val delta = change.position - down.position
                            if (abs(delta.y) > swipeMin && abs(delta.y) > abs(delta.x) * 1.2f) {
                                outcome = 3; swipeDir = if (delta.y > 0f) 1 else -1; break
                            }
                            if (isMediaNow && cfgNow.swipeTrackChange && abs(delta.x) > swipeMin && abs(delta.x) > abs(delta.y) * 1.2f) {
                                outcome = 4; swipeDirX = if (delta.x > 0f) 1 else -1; break
                            }
                            if (isNotifNow && abs(delta.x) > swipeMin && abs(delta.x) > abs(delta.y) * 1.2f) {
                                outcome = 5; break      // bildirim: sağa / sola kaydırma
                            }
                            if (delta.getDistance() > slop) { outcome = 2; break }
                        }
                    }
                    when (outcome) {
                        0 -> {
                            longPressNow()
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                        1 -> {
                            val c = cfgNow
                            val compact = progress.mean < 0.5f
                            val x = down.position.x
                            val artZone = min(size.height * 1.15f, size.width * 0.4f)
                            // Küçük adada albüm kapağı bölgesi; açık adada kapak / şarkı adı panelin kendi dokunuşunu işler
                            if (isMediaNow && compact && c.openCompArt && x <= artZone) openMusicNow() else tapNow()
                        }
                        4 -> {
                            // Sağdan sola = sonraki şarkı, soldan sağa = önceki şarkı
                            commandNow(if (swipeDirX < 0) MediaCommand.Next else MediaCommand.Previous)
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                        5 -> {
                            notifSideNow()
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                        3 -> {
                            swipeNow(swipeDir)
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Albüm kapağı arka planı: ada içini kaplar, yazıların okunması için hafif karartılır
        val backdropArt = media?.art
        if (cfg.artBgEnabled && backdropArt != null && bgBoth == null && !swallow) {
            ArtBackdrop(backdropArt, cfg.artBgCompact, progress)
        }
        // Galeriden seçilen arka plan: albüm arka planının üstünde, içeriğin altında
        if (bgBoth != null || bgCompact != null || bgExpanded != null) {
            CustomBackdrop(bgBoth, bgCompact, bgExpanded, cfg.customBgDim, geo, progress)
        }

        val musicBlur = cfg.animEnabled && ((cfg.musicBlurAnim && content is IslandContent.Media) || content is IslandContent.Shazam)
        val artSpin: () -> Float = { artFlip?.spin?.value ?: 0f }
        if ((panelVisible || (warm && content is IslandContent.Media)) && !swallow) {
            Box(
                Modifier
                    .requiredSize(geo.ew, geo.eh)
                    .graphicsLayer {
                        if (!panelVisible) alpha = 0f      // yalnızca hazırlık: çizilmez
                        else if (musicBlur) musicReveal(progress.mean)
                        else alpha = ((progress.mean - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    }
            ) {
                when (content) {
                    is IslandContent.Notif -> NotifContent(content, accent, rb)
                    is IslandContent.Call -> CallContent(content, accent, rb, onAnswer, onDecline, onSpeaker)
                    is IslandContent.Status ->
                        if (content.kind == StatusKind.Charging) ChargeContent(content, rb) else StatusContent(content, rb)
                    is IslandContent.Media -> MusicPanel(content, eqColor, cfg, lyrics, frame, onCommand, onOutput, customArt, onOpenMusic, artFlip, artSpin)
                    IslandContent.Assistant -> AssistantOrb()
                    is IslandContent.Favorites -> FavoritesPanel(content, cfg.compactHeightDp.dp, onLaunch)
                    is IslandContent.ScreenRec -> ScreenRecContent(content, onRecStop)
                    is IslandContent.Shazam -> ShazamContent(content, onShazamStop)
                    IslandContent.Idle -> Unit
                }
            }
        }

        // Baloncuktan açılırken: baloncuğun simgesi (kapak / kırmızı nokta / telefon) ada büyürken solar
        if (fromX != 0.dp && progress.mean < 0.35f) {
            Box(
                Modifier.requiredSize(geo.ch).align(Alignment.TopCenter)
                    .graphicsLayer { alpha = (1f - progress.mean * 3f).coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) { SideGlyph(content, geo.ch) }
        }

        if (cfg.dotMode && compactVisible && !swallow && content !is IslandContent.Idle) {
            // Üç nokta modu: kapalı hal bir daire (nokta); olay simgesi ortada, ada açılırken solar
            Box(
                Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)
                    .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) { SideGlyph(content, geo.ch) }
        }

        if (content is IslandContent.Call && !content.isIncoming && compactVisible && !cfg.dotMode) {
            // Küçültülmüş aktif arama: solda yeşil telefon + süre, sağda renkli ses dalgası
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val fade = Modifier.graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) }
                Row(
                    Modifier.fillMaxSize().padding(start = 9.dp, end = 14.dp).then(fade),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhoneIcon(0f, Modifier.size(12.dp), CallGreen)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        content.duration ?: "0:00", color = CallGreen, fontWeight = FontWeight.Medium,
                        fontSize = 12.sp, maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    CallWave(Modifier.width(34.dp).height((geo.ch - 16.dp).coerceAtLeast(10.dp)))
                }
            }
        }

        if (content is IslandContent.Notif && compactVisible && !cfg.dotMode) {
            // Küçültülmüş bildirim: solda uygulamanın simgesi (küçülürken görünür)
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val sz = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                val ic = content.icon
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(sz)
                        .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (ic != null) {
                        Image(bitmap = ic, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(sz * 0.28f)))
                    } else {
                        Box(Modifier.fillMaxSize().background(accent, RoundedCornerShape(sz * 0.28f)), contentAlignment = Alignment.Center) {
                            Text(content.title.trim().take(1).uppercase().ifEmpty { "•" }, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (content is IslandContent.ScreenRec && compactVisible && !cfg.dotMode) {
            // Kapalı ada: kayıtta solda yavaşça yanıp sönen kırmızı nokta; geri sayımda halka + rakam; işlenirken dönen halka
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val fade = Modifier.graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) }
                ScreenRecCompact(content, Modifier.fillMaxSize().then(fade))
            }
        }

        if (content is IslandContent.Shazam && compactVisible && !cfg.dotMode) {
            // Kapalı ada: solda Shazam logosu, sağda mavi ses dalgası. Ada genişlerken ikisi de bulanıp kaybolur.
            // Bulundu görünümü küçülürken (açıldıktan >1,5 sn sonra) logo yerine şarkının kapağı görünür.
            val shz = remember { ShazamHold() }
            if (content.phase == ShazamPhase.Listening) shz.foundAt = 0L
            else if (shz.foundAt == 0L) shz.foundAt = android.os.SystemClock.elapsedRealtime()
            val expandingNow = content.phase == ShazamPhase.Listening ||
                android.os.SystemClock.elapsedRealtime() - shz.foundAt < 1_500L
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val fade = Modifier.graphicsLayer {
                    val c = (1f - progress.mean * 2f).coerceIn(0f, 1f)
                    alpha = c
                    if (cfg.animEnabled) musicCompactBlur(c)
                }
                val sz = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                val cover = content.cover
                if (expandingNow || cover == null) {
                    Canvas(Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(sz).then(fade)) {
                        drawShazamGlyph(Offset.Zero, size.minDimension, ShazamBlue, 1f)
                    }
                } else {
                    Image(
                        bitmap = cover, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(sz)
                            .clip(RoundedCornerShape(sz * 0.28f)).then(fade),
                    )
                }
                if (expandingNow) {
                    ShazamWave(
                        Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            .width(22.dp).height((geo.ch - 16.dp).coerceAtLeast(10.dp)).then(fade),
                    )
                }
            }
        }

        // Yutma efekti sırasında kapalı adanın içeriği (kapak, equalizer, sözler) görünmez; efekt bitince yumuşakça gelir
        val swallowAlpha = animateFloatAsState(
            if (swallow) 0f else 1f,
            if (swallow) snap() else tween(240, delayMillis = 60),
            label = "swallowAlpha",
        )
        if (content is IslandContent.Media && compactVisible && !swallow && !cfg.dotMode) {
            // Sabit (kapalı ada) genişliğinde kutu: ada küçülürken kapak ve equalizer kenarlarla birlikte kaymaz
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val fade = Modifier.graphicsLayer {
                    val c = (1f - progress.mean * 2f).coerceIn(0f, 1f)
                    alpha = if (swallow) 0f else c * swallowAlpha.value
                    if (musicBlur) musicCompactBlur(c)
                }
                val artSize = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                val shownArt = customArt ?: content.art ?: artFlip?.held
                shownArt?.let { art ->
                    FlipAlbumArt(
                        art, artSize, artFlip, artSpin, 7.dp, customArt == null,
                        Modifier.align(Alignment.CenterStart).padding(start = 8.dp).then(fade),
                    )
                }
                if (compactLyrics && cfg.lyricsEnabled && cfg.lyricsCompact && lyrics is LyricsState.Ready) {
                    // Albüm kapağı ile equalizer arasındaki orta boşluk
                    val startPad = if (shownArt != null) 8.dp + artSize + 8.dp else 14.dp
                    val endPad = 10.dp + 23.dp + 8.dp
                    CompactLyrics(
                        cfg, lyrics, frame,
                        Modifier.align(Alignment.TopCenter).padding(start = startPad, end = endPad)
                            .fillMaxWidth().height(geo.ch).then(fade),
                        artColor = content.artColor,
                    )
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).then(fade)) {
                    if (content.playing) Equalizer(eqColor, rainbow = rb)
                    else Box(Modifier.size(6.dp).background(Color.White.copy(alpha = 0.40f), CircleShape))
                }

            }
        }
    }
}

/** Kapağı ada içine net (bulanıklaştırılmamış) çizer; yazılar okunsun diye üstüne koyu katman biner. */
@Composable
private fun ArtBackdrop(art: ImageBitmap, showInCompact: Boolean, progress: IslandProgress) {
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // Kapalı adada kapalıysa: ada açılırken belirir, küçülürken solar
            alpha = if (showInCompact) 1f else ((progress.mean - 0.25f) / 0.6f).coerceIn(0f, 1f)
        }
    ) {
        Image(
            bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.50f)))
    }
}

/**
 * Galeriden seçilen fotoğraf(lar) ada içini kaplar.
 * - Tek fotoğraf: ada büyürken/küçülürken kesintisiz ölçeklenir.
 * - Ayrı fotoğraflar: kapalı hal ve açık hal kendi boyutunda çizilir, geçişte çapraz solar.
 *   Bir hal için fotoğraf seçilmemişse o halde özel arka plan çizilmez.
 */
@Composable
private fun CustomBackdrop(
    both: ImageBitmap?, compact: ImageBitmap?, expanded: ImageBitmap?,
    dim: Float, geo: Geo, progress: IslandProgress,
) {
    @Composable
    fun Pic(img: ImageBitmap, modifier: Modifier) {
        Box(modifier) {
            Image(
                bitmap = img, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.9f))))
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (both != null) {
            Pic(both, Modifier.fillMaxSize())
        } else {
            if (compact != null) {
                Pic(
                    compact,
                    Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).graphicsLayer {
                        // Açık ada için fotoğraf varsa altta sabit kalır (üstüne açık fotoğraf biner), yoksa açılırken solar
                        alpha = if (expanded != null) 1f else (1f - progress.mean * 2f).coerceIn(0f, 1f)
                    },
                )
            }
            if (expanded != null) {
                Pic(
                    expanded,
                    Modifier.requiredSize(geo.ew, geo.eh).align(Alignment.TopCenter).graphicsLayer {
                        alpha = (progress.mean / 0.6f).coerceIn(0f, 1f)
                    },
                )
            }
        }
    }
}

@Composable
private fun NotifContent(c: IslandContent.Notif, accent: Color, rb: RainbowStyle?) {
    val iconShape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val icon = c.icon
        if (icon != null) {
            Image(
                bitmap = icon, contentDescription = null,
                modifier = Modifier.size(46.dp).clip(iconShape)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), iconShape),
            )
        } else {
            Box(Modifier.size(46.dp).background(accent, iconShape), contentAlignment = Alignment.Center) {
                Text(
                    c.title.trim().take(1).uppercase().ifEmpty { "•" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.rainbowFill(rb, 0.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (c.title.isNotBlank()) {
                Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
            if (c.text.isNotBlank()) {
                Text(c.text, color = Color.White.copy(alpha = 0.70f), fontSize = 13.5.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
        }
    }
}

// ---------------------------------------------------------------- Durum (zil modu / şarj / zamanlayıcı / alarm)
private const val BELL_PATH =
    "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5" +
    "s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
private val BellPath: Path by lazy { PathParser().parsePathString(BELL_PATH).toPath() }

private fun statusColor(c: IslandContent.Status): Color = when (c.kind) {
    StatusKind.Ringer -> if (c.silent) Color(0xFFFF3B30) else Color(0xFFFF9500)
    StatusKind.Charging -> Color(0xFF34C759)
    StatusKind.Timer -> Color(0xFF0A84FF)
    StatusKind.Alarm -> Color(0xFFFF9500)
}

@Composable
private fun StatusIcon(c: IslandContent.Status, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({ scale(sc, sc, Offset.Zero) }) {
            val w = Color.White
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
            }
        }
    }
}

// ---------------------------------------------------------------- Ekran kaydı
private val RecRed = Color(0xFFFF3B30)

/** Kırmızı kayıt noktası; blink açıksa yavaşça yanıp söner, dim açıksa soluk durur. */
@Composable
private fun RecDot(blink: Boolean, size: Dp, modifier: Modifier = Modifier, dim: Boolean = false) {
    val t = rememberInfiniteTransition(label = "recBlink")
    val pulse = t.animateFloat(
        1f, 0.25f,
        infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "recPulse",
    )
    Box(
        modifier.size(size)
            .graphicsLayer { alpha = if (blink) pulse.value else if (dim) 0.35f else 1f }
            .background(RecRed, CircleShape)
    )
}

@Composable
private fun RecSpinner(size: Dp, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "recSpin")
    val rot = t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "recRot")
    Canvas(modifier.size(size).graphicsLayer { rotationZ = rot.value }) {
        val sw = 2.5.dp.toPx()
        drawArc(
            Color.White, startAngle = -90f, sweepAngle = 290f, useCenter = false,
            topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun RecCountRing(count: Int, size: Dp, modifier: Modifier = Modifier) {
    // Her rakamda halka dolu başlar, saniye boyunca boşalır
    val sweep = remember(count) { Animatable(360f) }
    LaunchedEffect(count) { sweep.snapTo(360f); sweep.animateTo(0f, tween(1000, easing = LinearEasing)) }
    Canvas(modifier.size(size)) {
        val sw = 2.5.dp.toPx()
        drawArc(
            Color.White, startAngle = -90f, sweepAngle = sweep.value, useCenter = false,
            topLeft = Offset(sw / 2, sw / 2), size = Size(this.size.width - sw, this.size.height - sw),
            style = Stroke(sw, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun ScreenRecCompact(c: IslandContent.ScreenRec, modifier: Modifier = Modifier) {
    Box(modifier) {
        when (c.phase) {
            RecPhase.Recording -> RecDot(true, 9.dp, Modifier.align(Alignment.CenterStart).padding(start = 14.dp))
            RecPhase.Saved -> RecDot(false, 9.dp, Modifier.align(Alignment.CenterStart).padding(start = 14.dp))
            RecPhase.Countdown -> {
                RecCountRing(c.count, 17.dp, Modifier.align(Alignment.CenterStart).padding(start = 12.dp))
                Text(
                    c.count.toString(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp),
                )
            }
            RecPhase.Processing -> {
                RecDot(false, 9.dp, Modifier.align(Alignment.CenterStart).padding(start = 14.dp), dim = true)
                RecSpinner(17.dp, Modifier.align(Alignment.CenterEnd).padding(end = 14.dp))
            }
        }
    }
}

private fun formatRecTime(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val sec = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

/** Açık ada: [nokta + süre / Ekran Kaydediliyor]  ...  [durdur düğmesi]  ya da  [küçük resim] (kaydedildi). */
@Composable
private fun ScreenRecContent(c: IslandContent.ScreenRec, onStop: () -> Unit) {
    val rec = c.phase == RecPhase.Recording
    var now by remember(c.startedAt) { mutableLongStateOf(System.currentTimeMillis()) }
    if (rec) LaunchedEffect(c.startedAt) { while (true) { now = System.currentTimeMillis(); delay(250) } }
    val ms = if (rec) (now - c.startedAt).coerceAtLeast(0L) else c.frozenMs
    Row(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RecDot(blink = rec, size = 10.dp)
                if (rec || ms > 0L) {
                    Spacer(Modifier.width(8.dp))
                    Text(formatRecTime(ms), color = RecRed, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
                }
            }
            Text(
                if (c.phase == RecPhase.Saved) "Kayıt galeriye kaydedildi" else "Ekran Kaydediliyor",
                color = Color.White, fontWeight = FontWeight.Medium, fontSize = 15.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (c.phase == RecPhase.Saved) {
            val th = c.thumb
            if (th != null) {
                Image(th, null, Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)), contentScale = ContentScale.Crop)
            } else {
                // Önizleme bildirimden gelmediyse boş kalmasın
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White.copy(alpha = 0.75f), fontSize = 18.sp)
                }
            }
        } else if (rec) {
            Box(
                Modifier.size(46.dp).clip(CircleShape)
                    .border(2.5.dp, Color.White, CircleShape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStop),
                contentAlignment = Alignment.Center,
            ) { Box(Modifier.size(20.dp).background(RecRed, RoundedCornerShape(6.dp))) }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val t = ((ms + 999) / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Şarj görünümü: kapalı adadan biraz geniş, tek satır. Solda yazı, sağda yeşil yüzde + pil. */
const val CHARGE_PILL_W_DP = 220f
const val REC_PILL_W_DP = 280f      // ekran kaydı: açık hal genişliği
const val REC_PILL_H_DP = 72f       // ekran kaydı: açık hal yüksekliği
const val REC_WIDE_MARGIN_DP = 10f  // ekran kaydı tam genişlik: ekranın sağ/sol kenarından boşluk

/** Ekran kaydı açık ada genişliği (dp). Servis ve arayüz aynı hesabı kullanır. */
fun recExpandedWidthDp(cfg: IslandSettings, screenWDp: Float): Float =
    if (cfg.screenRecWide) (screenWDp - 2f * REC_WIDE_MARGIN_DP).coerceAtLeast(REC_PILL_W_DP)
    else maxOf(REC_PILL_W_DP, cfg.compactWidthDp)
fun chargePillHeightDp(cfg: IslandSettings): Float = cfg.compactHeightDp + 4f

@Composable
private fun ChargeContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val green = Color(0xFF34C759)
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
        Spacer(Modifier.weight(1f))      // ortada kamera deliği için boşluk kalır
        Text(c.value, color = green, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
            val tipW = 1.8.dp.toPx()
            val bodyW = size.width - tipW - 1.dp.toPx()
            val r = CornerRadius(4.dp.toPx())
            drawRoundRect(Color.White.copy(alpha = 0.28f), size = Size(bodyW, size.height), cornerRadius = r)
            val fillW = (bodyW * c.progress.coerceIn(0f, 1f)).coerceAtLeast(size.height * 0.5f)
            drawRoundRect(green, size = Size(fillW, size.height), cornerRadius = r)
            drawRoundRect(
                Color.White.copy(alpha = 0.40f),
                topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.3f),
                size = Size(tipW, size.height * 0.4f),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun StatusContent(c: IslandContent.Status, rb: RainbowStyle?) {
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
            Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            if (c.subtitle.isNotBlank()) {
                Text(c.subtitle, color = Color.White.copy(alpha = 0.70f), fontSize = 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
            if (frac >= 0f) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (value.isNotBlank()) {
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                maxLines = 1, modifier = Modifier.rainbowFill(rb))
        }
    }
}

// ---------------------------------------------------------------- Arama (gelen / aktif)
private const val PHONE_PATH =
    "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 " +
    "1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 " +
    "0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"

private val PhonePath: Path by lazy { PathParser().parsePathString(PHONE_PATH).toPath() }

private val CallGreen = Color(0xFF34C759)
private val CallRed = Color(0xFFFF3B30)

/** Telefon ahizesi simgesi. [rotation] 135 verilirse "kapat" simgesi olur. */
@Composable
internal fun PhoneIcon(rotation: Float, modifier: Modifier = Modifier, tint: Color = Color.White) {
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
private val AnswerColor = Color(0xFF34C759)
private val DeclineColor = Color(0xFFFF3B30)
private val SpeakerOff = Color.White.copy(alpha = 0.16f)

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

/** Shazam sonucu: açık ada yüksekliği (dp). Kapak + iki satır yazı sığar. */
const val SHAZAM_PILL_H_DP = 88f

// ---------------------------------------------------------------- Shazam
private class ShazamHold { var foundAt = 0L }

private class ShazamSpark(val angle: Float, val speed: Float, val rad: Float, val delay: Float, val life: Float)

private val ShazamBlueTop = Color(0xFF33AEFF)
private val ShazamBlueBottom = Color(0xFF0A68FF)

private val ShazamBlue = Color(0xFF0088FF)

/**
 * Resmi Shazam "S" işareti (gönderdiğin logodan ölçülen geometri): iki kalın, yuvarlak uçlu kanca;
 * ikincisi birincinin 180° döndürülmüşü. [color] mavi ya da beyaz olabilir; arka daire yok.
 */
private fun DrawScope.drawShazamGlyph(topLeft: Offset, side: Float, color: Color, alpha: Float) {
    fun pt(u: Float, v: Float, flip: Boolean) =
        if (flip) Offset(topLeft.x + (1f - u) * side, topLeft.y + (1f - v) * side) else Offset(topLeft.x + u * side, topLeft.y + v * side)
    val r = 0.1759f * side
    val path = Path()
    for (rot in 0..1) {
        val flip = rot == 1
        val p0 = pt(0.4148f, 0.2130f, flip)       // üst uç (yuvarlak)
        val c = pt(0.4148f, 0.4630f, flip)        // kancanın yay merkezi
        val p2 = pt(0.5861f, 0.5398f, flip)       // iç uç (yuvarlak)
        path.moveTo(p0.x, p0.y)
        path.arcTo(Rect(c, r), 225f + rot * 180f, -180f, false)   // çizgi + yay (yayın başlangıcına düz çizgiyle inilir)
        path.lineTo(p2.x, p2.y)
    }
    drawPath(path, color, alpha = alpha, style = Stroke(width = 0.1157f * side, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Logonun etrafında dönen çok hafif beyazımsı çizgi: arkasında sönen bir iz ve uçta küçük bir parıltı noktası. */
@Composable
private fun ShazamOrbit(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "shzOrbit")
    val ang: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "shzOrbitAng",
    )
    Canvas(modifier) {
        val sw = 1.6.dp.toPx()
        val r = size.minDimension / 2f - sw
        val c = Offset(size.width / 2f, size.height / 2f)
        val tl = Offset(c.x - r, c.y - r)
        val sz = Size(r * 2f, r * 2f)
        val trail = 110f
        val steps = 36
        for (i in 0 until steps) {
            val t = i / steps.toFloat()          // 0 = iz sonu (görünmez), 1 = uç
            drawArc(
                Color.White.copy(alpha = 0.30f * t * t), ang - trail + trail * t, trail / steps + 1f, false,
                tl, sz, style = Stroke(width = sw, cap = StrokeCap.Butt),
            )
        }
        val rad = ang * 0.0174533f
        val head = Offset(c.x + r * cos(rad), c.y + r * sin(rad))
        val gr = 5.dp.toPx()
        drawCircle(
            brush = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.40f), Color.Transparent), center = head, radius = gr),
            radius = gr, center = head,
        )
        drawCircle(Color.White.copy(alpha = 0.85f), radius = 1.4.dp.toPx(), center = head)
    }
}

/** Küçük adadaki Shazam ses dalgası: mavi, hareketli çubuklar. */
@Composable
private fun ShazamWave(modifier: Modifier = Modifier, bars: Int = 5, flat: Boolean = false) {
    val tr = rememberInfiniteTransition(label = "shzWave")
    val phase: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "shzPhase",
    )
    Canvas(modifier) {
        val n = bars
        val gap: Float = size.width / n
        val bw: Float = gap * 0.46f
        for (i in 0 until n) {
            val v: Float = 0.30f + 0.70f * abs(sin(phase + i * 1.15f))
            val h: Float = (size.height * v).coerceAtLeast(bw)
            val x: Float = gap * i + gap / 2f
            val color = if (flat) Color(0xFF1E7BFF) else androidx.compose.ui.graphics.lerp(ShazamBlue, Color(0xFF6BBBFF), i / (n - 1).toFloat())
            drawLine(color, Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f), strokeWidth = bw, cap = StrokeCap.Round)
        }
    }
}

private fun shzSmooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

/**
 * Albüm kapağının belirmesi. [progress] 0..1:
 *  0.00-0.30  küçük beyaz ışık patlaması (parlama + ışınlar + kıvılcımlar)
 *  0.14-0.42  parlama büyüyüp SAF BEYAZ yuvarlatılmış kareye dönüşür (pembe yok)
 *  0.45-1.00  beyaz blok yavaşça şeffaflaşır; altındaki kapak bulanıktan netleşir (beyazla yıkanmış halden normale)
 *  Kıvılcımlar 0.72'ye kadar tamamen söner, sonda kenarda hiçbir şey kalmaz.
 */
@Composable
private fun ShazamCoverBurst(cover: ImageBitmap?, progress: () -> Float, modifier: Modifier = Modifier) {
    val sparks = remember {
        val r = kotlin.random.Random(7)
        List(30) {
            ShazamSpark(
                angle = r.nextFloat() * 6.2831855f, speed = 0.5f + r.nextFloat() * 0.8f,
                rad = 0.8f + r.nextFloat() * 1.5f, delay = r.nextFloat() * 0.30f, life = 0.25f + r.nextFloat() * 0.17f,
            )
        }
    }
    // Netleşirken kapağın üstünde görünen ince beyaz tanecikler (konumları sabit)
    val grain = remember {
        val r = kotlin.random.Random(23)
        List(70) { Triple(r.nextFloat(), r.nextFloat(), r.nextFloat()) }
    }
    val coverShape = remember { RoundedCornerShape(percent = 22) }

    Box(modifier, contentAlignment = Alignment.Center) {
        // Katman 0: küçük parlama, ışınlar ve parlamanın beyaz kareye dönüşmesi
        Canvas(Modifier.fillMaxSize()) {
            val t = progress().coerceIn(0f, 1f)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val center = Offset(cx, cy)
            val full = size.width * (60f / 72f)
            val burstP = (t / 0.30f).coerceIn(0f, 1f)
            val bloomP = ((t - 0.14f) / 0.28f).coerceIn(0f, 1f)

            val gr = full * (0.14f + 0.44f * burstP)
            val ga = ((0.45f + 0.55f * burstP) * (1f - bloomP)).coerceIn(0f, 1f)
            if (ga > 0.01f) {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color.White.copy(alpha = ga), Color.White.copy(alpha = ga * 0.5f), Color.Transparent),
                        center = center, radius = gr,
                    ),
                    radius = gr, center = center,
                )
            }
            val rayA = (1f - bloomP) * (0.2f + 0.8f * burstP)
            if (rayA > 0.02f) {
                val n = 22
                for (i in 0 until n) {
                    val a = i * (6.2831855f / n) + 0.15f * sin(i * 2.1f)
                    val k = 0.55f + 0.45f * abs(sin(i * 1.7f + 0.6f))
                    val r0 = full * 0.08f
                    val r1 = r0 + full * (0.10f + 0.34f * burstP) * k
                    drawLine(
                        Color.White.copy(alpha = rayA),
                        Offset(cx + r0 * cos(a), cy + r0 * sin(a)), Offset(cx + r1 * cos(a), cy + r1 * sin(a)),
                        strokeWidth = 1.3.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
            }
            if (bloomP > 0.01f) {
                val e = shzSmooth(bloomP)
                val side = full * (0.30f + 0.70f * e)
                val rad = side / 2f + (full * 0.22f - side / 2f) * e
                // Parıltı (glow): beyaz bloğun etrafında yumuşak halo; kapak netleştikçe söner
                val sGlow = shzSmooth((t - 0.45f) / 0.55f)
                val glowA = e * (1f - sGlow)
                if (glowA > 0.01f) {
                    val layers = 14
                    for (k in layers downTo 1) {
                        val g = k * 1.1.dp.toPx()
                        drawRoundRect(
                            color = Color.White.copy(alpha = (0.085f * glowA * (1f - k / (layers + 1f))).coerceIn(0f, 1f)),
                            topLeft = Offset(cx - side / 2f - g, cy - side / 2f - g),
                            size = Size(side + 2f * g, side + 2f * g), cornerRadius = CornerRadius(rad + g),
                        )
                    }
                }
                drawRoundRect(
                    color = Color.White.copy(alpha = e), topLeft = Offset(cx - side / 2f, cy - side / 2f),
                    size = Size(side, side), cornerRadius = CornerRadius(rad, rad),
                )
            }
        }

        // Katman 1: kapak + üstündeki beyaz örtü. Birlikte bulanıktan netleşir; dışarıdan yuvarlatılmış kareyle kesilir (kenarlar keskin kalır)
        Box(
            Modifier.size(60.dp)
                .graphicsLayer { alpha = if (progress() < 0.40f) 0f else 1f }
                .clip(coverShape),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.requiredSize(84.dp).graphicsLayer {
                    val t = progress().coerceIn(0f, 1f)
                    val s = shzSmooth((t - 0.45f) / 0.55f)
                    val blurDp = if (t < 0.45f) 3f else 3f + 6f * (1f - s)   // beyaz blok hafif yumuşak, kapak belirirken bulanık -> net
                    setBlurPx((blurDp * (1f - shzSmooth((s - 0.85f) / 0.15f))).dp.toPx())
                },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val t = progress().coerceIn(0f, 1f)
                    val s = shzSmooth((t - 0.45f) / 0.55f)
                    val full = 60.dp.toPx()
                    val c = Offset(size.width / 2f, size.height / 2f)
                    if (cover != null) {
                        val side = min(cover.width, cover.height)
                        val sx0 = (cover.width - side) / 2
                        val sy0 = (cover.height - side) / 2
                        val d = (full * (1f + 0.25f * (1f - s))).roundToInt()   // bulanıkken biraz büyük, netleşirken yerine oturur (kenar sızıntısı olmaz)
                        drawImage(
                            cover, IntOffset(sx0, sy0), IntSize(side, side),
                            IntOffset((c.x - d / 2f).roundToInt(), (c.y - d / 2f).roundToInt()), IntSize(d, d),
                            filterQuality = FilterQuality.High,
                        )
                    } else {
                        // Kapak bildirimde yoksa: Shazam logolu mavi kare
                        drawRect(ShazamBlue)
                        drawShazamGlyph(Offset(c.x - full * 0.36f, c.y - full * 0.36f), full * 0.72f, Color.White, 1f)
                    }
                    // Beyaz örtü: kapağı beyazlatır, yavaşça şeffaflaşır
                    drawRect(Color.White.copy(alpha = (1f - s).coerceIn(0f, 1f)))
                }
            }
        }

        // Katman 2: kıvılcımlar ve tanecikler (keskin çizilir, 0.72'de tamamen biter)
        Canvas(Modifier.fillMaxSize()) {
            val t = progress().coerceIn(0f, 1f)
            val cx = size.width / 2f
            val cy = size.height / 2f
            val full = size.width * (60f / 72f)
            for (sp in sparks) {
                val l = (t - sp.delay) / sp.life
                if (l <= 0f || l >= 1f) continue
                val eo = 1f - (1f - l) * (1f - l) * (1f - l)
                val r = full * 0.16f + sp.speed * full * 0.36f * eo
                val pos = Offset(cx + r * cos(sp.angle), cy + r * sin(sp.angle))
                drawCircle(Color.White.copy(alpha = (1f - l) * 0.95f), radius = sp.rad.dp.toPx() * (1f - 0.5f * l), center = pos)
            }
            val s = shzSmooth((t - 0.45f) / 0.55f)
            val ga = 0.8f * sin(3.1415927f * s)
            if (ga > 0.02f) {
                for ((gx, gy, gv) in grain) {
                    val a = ga * (0.35f + 0.65f * gv)
                    drawCircle(
                        Color.White.copy(alpha = a), radius = (0.7f + gv * 0.9f).dp.toPx(),
                        center = Offset(cx - full * 0.45f + gx * full * 0.9f, cy - full * 0.45f + gy * full * 0.9f),
                    )
                }
            }
        }
    }
}

/** Açık ada: [kapak (patlama animasyonu)]  [şarkı adı / sanatçı]. Yazılar bulanıktan netleşir. Apple Music etiketi yok. */
@Composable
private fun ShazamContent(c: IslandContent.Shazam, onStop: () -> Unit = {}) {
    val found = c.phase == ShazamPhase.Found
    val burst = remember(c.title, c.artist) { Animatable(0f) }
    val reveal = remember(c.title, c.artist) { Animatable(0f) }
    LaunchedEffect(c.title, c.artist, found) {
        burst.snapTo(0f); reveal.snapTo(0f)
        if (!found) return@LaunchedEffect
        launch { delay(120); reveal.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }
        delay(280)
        burst.animateTo(1f, tween(1900, easing = LinearEasing))
    }
    if (found) {
        Row(
            Modifier.fillMaxSize().padding(start = 6.dp, end = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            ShazamCoverBurst(c.cover, { burst.value }, Modifier.size(72.dp))
            Column(
                Modifier.weight(1f).graphicsLayer {
                    val r = reveal.value
                    alpha = 0.25f + 0.75f * r
                    setBlurPx((1f - r) * 14.dp.toPx())
                },
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (c.artist.isNotBlank()) {
                    Text(c.artist, color = Color.White.copy(alpha = 0.66f), fontWeight = FontWeight.Medium, fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } else {
        // Dinleme (genişlemiş): [mavi daire + beyaz Shazam logosu]  Listening...  [mavi ses dalgası]  [X düğmesi]
        Row(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(ShazamBlueTop, ShazamBlueBottom))),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(34.dp)) { drawShazamGlyph(Offset.Zero, size.minDimension, Color.White, 1f) }
                }
                ShazamOrbit(Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "Dinliyor", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 20.sp,
                maxLines = 1, modifier = Modifier.weight(1f),
            )
            ShazamWave(Modifier.width(32.dp).height(18.dp), bars = 6, flat = true)
            Spacer(Modifier.width(14.dp))
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF14224A))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onStop),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(20.dp)) {
                    val w = 2.6.dp.toPx()
                    val m = w / 2f
                    drawLine(Color(0xFF3D7BFF), Offset(m, m), Offset(size.width - m, size.height - m), strokeWidth = w, cap = StrokeCap.Round)
                    drawLine(Color(0xFF3D7BFF), Offset(size.width - m, m), Offset(m, size.height - m), strokeWidth = w, cap = StrokeCap.Round)
                }
            }
        }
    }
}

/** Küçük adadaki ses dalgası: yeşil -> beyaz -> turuncu renk geçişli, hareketli çubuklar. */
@Composable
private fun CallWave(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "wave")
    val phase: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "wavePhase",
    )
    val green = Color(0xFF4CE06A)
    val orange = Color(0xFFFF9248)
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
                if (t < 0.5f) androidx.compose.ui.graphics.lerp(green, Color.White, t * 2f)
                else androidx.compose.ui.graphics.lerp(Color.White, orange, (t - 0.5f) * 2f)
            val x: Float = gap * i + gap / 2f
            val y1: Float = (size.height - h) / 2f
            val y2: Float = (size.height + h) / 2f
            drawLine(color, Offset(x, y1), Offset(x, y2), strokeWidth = bw, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun CallContent(
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
                    Brush.linearGradient(listOf(Color(0xFFFF6B9D), Color(0xFFFF9A5A))), CircleShape,
                )
            } else {
                Modifier.background(Color(0xFF3A3A3C), CircleShape)
            }
            Box(Modifier.size(avatarSize).then(bg), contentAlignment = Alignment.Center) {
                Text(
                    text = c.name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    modifier = Modifier.rainbowFill(rb, 0.dp),
                )
            }
        }

        // İsim + durum
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (incoming) {
                Text(
                    "Gelen arama", color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    c.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb),
                )
            } else {
                Text(
                    c.duration ?: "Aktif arama", color = CallGreen, fontWeight = FontWeight.Medium,
                    fontSize = 15.sp, maxLines = 1,
                )
                Text(
                    c.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb),
                )
            }
        }

        // Butonlar: gelen aramada kırmızı + yeşil; aktif aramada hoparlör + kırmızı
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!incoming) {
                CallButton(if (c.speaker) Color.White else SpeakerOff, 50.dp, onSpeaker) { sz ->
                    SpeakerIcon(c.speaker, if (c.speaker) Color.Black else Color.White, Modifier.size(sz + 4.dp))
                }
            }
            CallButton(DeclineColor, if (incoming) 52.dp else 50.dp, onDecline) { sz -> PhoneIcon(135f, Modifier.size(sz)) }
            if (incoming) CallButton(AnswerColor, 52.dp, onAnswer) { sz -> PhoneIcon(0f, Modifier.size(sz)) }
        }
    }
}
