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

internal class SideHold { var c: IslandContent? = null }

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
internal fun IslandSides(
    sides: List<IslandContent>, geo: Geo, progress: IslandProgress, cfg: IslandSettings, color: State<Color>, fromSide: Int,
    animExpanded: Boolean, wantExpanded: Boolean, onPress: (Int) -> Unit, onTap: (Int) -> Unit,
) {
    // Üç nokta modu: boştaki yuvalar da siyah nokta olarak görünür (ada genişleyince solar)
    if (cfg.dotMode && cfg.dotMerge) {
        // Birleşik mod: boş yuvalar tek bir hap olarak çizilir; dolu yuva baloncuğu adadan ayrılır (SideSlot, değişmedi)
        val keep = cfg.dotMergeKeep      // olaylar hapın içinde kalır: hap hiç bölünmez
        DotMergePill(!keep && sides.getOrNull(0) != null, !keep && sides.getOrNull(1) != null, geo, progress, cfg, color)
        // Dış nokta yalnızca iç noktası doluyken (hapın dışında kaldığında) tek başına nokta olarak durur
        if (!keep && dotHasOuter(cfg, 1) && sides.getOrNull(0) != null) DotPlaceholder(1, 2, sides.getOrNull(2) != null, geo, progress, cfg, color)
        if (!keep && dotHasOuter(cfg, -1) && sides.getOrNull(1) != null) DotPlaceholder(-1, 2, sides.getOrNull(3) != null, geo, progress, cfg, color)
    } else if (cfg.dotMode) {
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

/**
 * "Noktaları birleştir": ortadaki nokta ile boştaki yan noktalar tek bir hap (ada) olarak görünür.
 * Bir yanın iç yuvası dolunca o yandaki hap ortadaki noktaya doğru çekilir ve baloncuk adadan ayrılır.
 */
@Composable
private fun DotMergePill(rightBusy: Boolean, leftBusy: Boolean, geo: Geo, progress: IslandProgress, cfg: IslandSettings, color: State<Color>) {
    val spec: AnimationSpec<Float> = if (cfg.animEnabled) spring(dampingRatio = 0.72f, stiffness = 150f) else snap()
    val fr by animateFloatAsState(if (rightBusy) 0f else 1f, spec, label = "dotMergeR")
    val fl by animateFloatAsState(if (leftBusy) 0f else 1f, spec, label = "dotMergeL")
    Canvas(Modifier.fillMaxSize()) {
        val k = (1f - progress.mean * 2f).coerceIn(0f, 1f)
        if (k <= 0f) return@Canvas
        val h = geo.ch.toPx()
        val mid = cfg.dotMidDp.dp.toPx()
        val gap = sideGapDp(cfg).dp.toPx()
        val cx = size.width / 2f
        val cy = h / 2f
        val eR = mid / 2f + fr * (gap + dotExtentDp(cfg, 1).dp.toPx())
        val eL = mid / 2f + fl * (gap + dotExtentDp(cfg, -1).dp.toPx())
        val pill = RoundRect(cx - eL, cy - h / 2f, cx + eR, cy + h / 2f, CornerRadius(h / 2f, h / 2f))
        val col = color.value.let { it.copy(alpha = it.alpha * k) }
        if (cfg.glassEnabled) {
            // Cam modunda ortadaki adanın üstüne binip koyulaşmasın: hapın o kısmı çıkarılır
            val hole = Path().apply { addRoundRect(RoundRect(cx - mid / 2f, cy - h / 2f, cx + mid / 2f, cy + h / 2f, CornerRadius(h / 2f, h / 2f))) }
            val shape = Path().apply { addRoundRect(pill) }
            drawPath(Path.combine(PathOperation.Difference, shape, hole), col)
        } else {
            drawPath(Path().apply { addRoundRect(pill) }, col)
        }
        if (cfg.borderEnabled) {
            val bw = cfg.borderWidthDp.dp.toPx()
            drawRoundRect(
                Color(cfg.borderColorArgb).copy(alpha = k), Offset(cx - eL + bw / 2f, cy - h / 2f + bw / 2f),
                Size(eL + eR - bw, h - bw), CornerRadius((h - bw) / 2f, (h - bw) / 2f), style = Stroke(bw),
            )
        }
    }
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
    // Birleşik mod + "olaylar içinde kalsın": gövde / köprü çizilmez, yalnızca simge hapın içinde kendi yerinde görünür
    val inside = cfg.dotMode && cfg.dotMerge && cfg.dotMergeKeep
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
        if (!inside) Canvas(Modifier.fillMaxSize()) {
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
                    alpha = if (hide) 0f else ((s - 0.45f) / 0.45f).coerceIn(0f, 1f) * (if (present && !inside) 1f else if (gone[0] && !inside) 0f else (1f - progress.mean * 2f).coerceIn(0f, 1f))
                }
                // Parmak değdiği anda haber verilir (dokunuşu tüketmez): servis pencereyi önceden büyütür
                .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false); onPress() } }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { if (present) onTap() },
            contentAlignment = Alignment.Center,
        ) { SideGlyph(shown, sizeDp) }
    }
}

@Composable
internal fun SideGlyph(c: IslandContent, ch: Dp) {
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
                ShazamGlyphSweep(Modifier.size(sz))
            }
        }
        is IslandContent.Status -> when (c.kind) {
            // Gizlilik göstergesi: iOS gibi yeşil (kamera) / turuncu (mikrofon) nokta
            StatusKind.Camera -> Box(Modifier.size(ch * 0.34f).background(Color(0xFF34C759), RoundedCornerShape(50)))
            StatusKind.Mic -> Box(Modifier.size(ch * 0.34f).background(Color(0xFFFF9F0A), RoundedCornerShape(50)))
            else -> Text(
                when (c.kind) {
                    StatusKind.Ringer -> if (c.silent) "🔕" else "🔔"
                    StatusKind.Charging -> "⚡"
                    StatusKind.Timer -> "⏱"
                    StatusKind.Alarm -> "⏰"
                    else -> ""
                },
                fontSize = 13.sp,
            )
        }
        else -> Unit
    }
}
