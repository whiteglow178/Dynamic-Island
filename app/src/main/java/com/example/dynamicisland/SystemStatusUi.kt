package com.example.dynamicisland

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ================================================================ Ölçüler
/** Geniş iOS hapı (ağ, uçak modu, zil sesi, ince düşük pil): ekran genişliğinin ~%84'ü, kapalı adadan biraz yüksek. */
fun sysPillWidthDp(cfg: IslandSettings, screenWDp: Float): Float =
    (screenWDp * 0.84f).coerceIn(cfg.compactWidthDp + 40f, (screenWDp - 16f).coerceAtLeast(cfg.compactWidthDp + 40f))

fun sysPillHeightDp(cfg: IslandSettings): Float = max(cfg.compactHeightDp * 1.3f, cfg.compactHeightDp + 8f)

/** Düşük pil kartı (genişletilmiş görünüm) yüksekliği. Genişlik açık ada genişliğidir. */
const val LOWBATT_CARD_H_DP = 96f

/** Genişleyen fotoğraf adası genişliği: ekrandan taşmaz. */
fun designPhotoWidthDp(cfg: IslandSettings, screenWDp: Float): Float =
    min(cfg.more.designPhotoWDp, (screenWDp - 16f).coerceAtLeast(cfg.compactWidthDp))

// ================================================================ Simgeler (24x24 ızgara)
private const val PLANE_PATH =
    "M21,16v-2l-8,-5V3.5C13,2.67 12.33,2 11.5,2S10,2.67 10,3.5V9l-8,5v2l8,-2.5V19l-2,1.5V22l3.5,-1 3.5,1v-1.5L13,19v-5.5l8,2.5z"
private val PlanePath: Path by lazy { PathParser().parsePathString(PLANE_PATH).toPath() }

internal fun DrawScope.drawWifiGlyph(color: Color, slash: Boolean = false) {
    val c = Offset(12f, 19f)
    drawCircle(color, radius = 1.9f, center = c)
    val st = Stroke(width = 2.4f, cap = StrokeCap.Round)
    for (r in floatArrayOf(5f, 9.5f, 14f)) {
        drawArc(color, -135f, 90f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = st)
    }
    if (slash) drawLine(color, Offset(3.5f, 3.5f), Offset(20.5f, 21f), strokeWidth = 2.4f, cap = StrokeCap.Round)
}

internal fun DrawScope.drawCellGlyph(color: Color) {
    val hs = floatArrayOf(5f, 9f, 13f, 17f)
    for (i in 0..3) {
        val x = 2.5f + i * 5f
        drawRoundRect(color, Offset(x, 21f - hs[i]), Size(3.6f, hs[i]), CornerRadius(1.2f, 1.2f))
    }
}

internal fun DrawScope.drawPlaneGlyph(color: Color) = drawPath(PlanePath, color)

/** Yatay pil: gövde çerçevesi + doluluk + uç. [frac] 0..1. */
internal fun DrawScope.drawBatteryGlyph(color: Color, fill: Color, frac: Float) {
    drawRoundRect(color, Offset(1.5f, 7f), Size(18.5f, 10f), CornerRadius(3f, 3f), style = Stroke(width = 1.6f))
    val w = (15.5f * frac.coerceIn(0f, 1f)).coerceAtLeast(2f)
    drawRoundRect(fill, Offset(3f, 8.5f), Size(w, 7f), CornerRadius(1.6f, 1.6f))
    drawRoundRect(color, Offset(21f, 10f), Size(1.8f, 4f), CornerRadius(0.9f, 0.9f))
}

internal val FOCUS_PURPLE = Color(0xFF7D7AFF)
internal val HOTSPOT_GREEN = Color(0xFF34C759)

/**
 * Hilal (Rahatsız Etmeyin): tam daireden, sağ üste kaydırılmış ikinci daire kesilerek çizilir.
 * Böylece ay simetrik ve düzgün (yamuk değil) olur; 24x24 kutunun ortasında durur.
 */
internal fun DrawScope.drawMoonGlyph(color: Color) {
    val outer = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(11.4f, 12.6f), 8.6f)) }
    val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(16.2f, 7.8f), 7.4f)) }
    val moon = Path().apply { op(outer, cut, androidx.compose.ui.graphics.PathOperation.Difference) }
    drawPath(moon, color)
}

/** Kişisel erişim noktası: ortada nokta, iki yanda yayılan dalgalar. */
internal fun DrawScope.drawHotspotGlyph(color: Color) {
    val c = Offset(12f, 12f)
    drawCircle(color, radius = 2.4f, center = c)
    val st = Stroke(width = 2.2f, cap = StrokeCap.Round)
    for (r in floatArrayOf(5.5f, 9.5f)) {
        drawArc(color, -40f, 80f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = st)
        drawArc(color, 140f, 80f, false, Offset(c.x - r, c.y - r), Size(2 * r, 2 * r), style = st)
    }
}

/** Kulaklık: [wired] = kafa bantlı kulaklık, değilse iki kablosuz kulak içi. */
internal fun DrawScope.drawHeadphoneGlyph(color: Color, wired: Boolean) {
    if (wired) {
        drawArc(color, 180f, 180f, false, Offset(3.5f, 3.5f), Size(17f, 17f), style = Stroke(width = 2.2f, cap = StrokeCap.Round))
        drawRoundRect(color, Offset(2.5f, 12f), Size(5f, 8.5f), CornerRadius(2f, 2f))
        drawRoundRect(color, Offset(16.5f, 12f), Size(5f, 8.5f), CornerRadius(2f, 2f))
    } else {
        for (dir in floatArrayOf(-1f, 1f)) {
            val x = 12f + dir * 5f
            drawCircle(color, radius = 3.6f, center = Offset(x, 7.5f))
            drawRoundRect(color, Offset(x - 1.3f + dir * 1.2f, 8f), Size(2.6f, 12.5f), CornerRadius(1.3f, 1.3f))
        }
    }
}

/** Yeni durum türleri için küçük simge (yan baloncuk / yatay mod / StatusIcon). */
internal fun DrawScope.drawSysGlyph(c: IslandContent.Status, w: Color) {
    when (c.kind) {
        StatusKind.Network -> when (c.extra) {
            1 -> drawCellGlyph(w)
            3 -> drawWifiGlyph(w, slash = true)
            else -> drawWifiGlyph(w)
        }
        StatusKind.Airplane -> drawPlaneGlyph(w)
        StatusKind.RingVolume -> {
            drawPath(BellPath, w)
            if (c.progress <= 0f) drawLine(w, Offset(3.5f, 3.5f), Offset(20.5f, 20.5f), strokeWidth = 2.4f, cap = StrokeCap.Round)
        }
        StatusKind.LowBattery, StatusKind.LowBatteryCard -> drawBatteryGlyph(w, w, c.progress)
        StatusKind.Focus -> drawMoonGlyph(w)
        StatusKind.Hotspot -> drawHotspotGlyph(w)
        StatusKind.Headphones -> drawHeadphoneGlyph(w, c.extra == 1)
        else -> Unit
    }
}

internal fun sysColor(c: IslandContent.Status): Color = when (c.kind) {
    StatusKind.Network -> when (c.extra) { 1 -> Pal.c("status_cell"); 3 -> Pal.c("status_offline"); else -> Pal.c("status_wifi") }
    StatusKind.Airplane -> Pal.c("status_airplane")
    StatusKind.RingVolume -> Pal.c("status_text")
    StatusKind.LowBattery, StatusKind.LowBatteryCard -> if (c.extra == 1) Pal.c("status_power_save") else Pal.c("status_low_batt")
    StatusKind.Focus -> if (c.value == "Açık") FOCUS_PURPLE else Pal.c("status_text_dim")
    StatusKind.Hotspot -> if (c.value == "Açık") HOTSPOT_GREEN else Pal.c("status_text_dim")
    StatusKind.Headphones -> Pal.c("status_text")
    else -> Pal.c("status_text")
}

// ================================================================ Geniş hap içerikleri
/**
 * Ağ / uçak modu hapı (ekran kaydındaki gibi): solda renkli simge + beyaz başlık, sağda renkli durum yazısı.
 * Ortada kamera deliği için boşluk kalır.
 */
@Composable
internal fun SysPillContent(c: IslandContent.Status, heightDp: Float, rb: RainbowStyle?) {
    val accent = sysColor(c)
    val textSp = (heightDp * 0.36f).coerceIn(12f, 17f)
    val icon = (heightDp * 0.50f).coerceIn(14f, 24f)
    Row(
        Modifier.fillMaxSize().padding(start = (heightDp * 0.42f).dp, end = (heightDp * 0.50f).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(icon.dp)) {
            val sc = size.width / 24f
            withTransform({ scale(sc, sc, Offset.Zero) }) { drawSysGlyph(c, accent) }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            c.title, color = Pal.c("status_text"), fontWeight = FontWeight.SemiBold, fontSize = textSp.sp,
            maxLines = 1, softWrap = false, modifier = Modifier.rainbowFill(rb),
        )
        Spacer(Modifier.weight(1f))
        Text(c.value, color = accent, fontWeight = FontWeight.SemiBold, fontSize = textSp.sp, maxLines = 1, softWrap = false)
    }
}

/** Zil sesi seviyesi: solda zil + "Zil", sağda ince beyaz seviye çubuğu (iOS 26 ekran kaydındaki gibi). */
@Composable
internal fun RingVolumeContent(c: IslandContent.Status, heightDp: Float, rb: RainbowStyle?) {
    val frac by animateFloatAsState(c.progress.coerceIn(0f, 1f), tween(160), label = "ringVol")
    val fg = Pal.c("status_text")
    val textSp = (heightDp * 0.36f).coerceIn(12f, 17f)
    val icon = (heightDp * 0.48f).coerceIn(14f, 22f)
    Row(
        Modifier.fillMaxSize().padding(start = (heightDp * 0.42f).dp, end = (heightDp * 0.50f).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(icon.dp)) {
            val sc = size.width / 24f
            withTransform({ scale(sc, sc, Offset.Zero) }) { drawSysGlyph(c, fg) }
        }
        Spacer(Modifier.width(8.dp))
        Text(c.title, color = fg, fontWeight = FontWeight.SemiBold, fontSize = textSp.sp, maxLines = 1, softWrap = false,
            modifier = Modifier.rainbowFill(rb))
        Spacer(Modifier.weight(1f))
        Box(
            Modifier.fillMaxWidth(0.42f).height((heightDp * 0.16f).coerceIn(4f, 7f).dp)
                .clip(RoundedCornerShape(50)).background(Pal.c("status_track")),
        ) {
            Box(Modifier.fillMaxWidth(frac).fillMaxHeight().clip(RoundedCornerShape(50)).background(fg))
        }
    }
}

/** İnce düşük pil hapı (genişlemez): solda "Düşük Pil", sağda kırmızı yüzde + pil. */
@Composable
internal fun LowBatteryPillContent(c: IslandContent.Status, heightDp: Float, rb: RainbowStyle?) {
    val accent = sysColor(c)
    val textSp = (heightDp * 0.36f).coerceIn(12f, 17f)
    Row(
        Modifier.fillMaxSize().padding(start = (heightDp * 0.5f).dp, end = (heightDp * 0.45f).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.title, color = Pal.c("status_text"), fontWeight = FontWeight.SemiBold, fontSize = textSp.sp,
            maxLines = 1, softWrap = false, modifier = Modifier.rainbowFill(rb))
        Spacer(Modifier.weight(1f))
        Text(c.value, color = accent, fontWeight = FontWeight.SemiBold, fontSize = textSp.sp, maxLines = 1, softWrap = false)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = (heightDp * 0.9f).dp, height = (heightDp * 0.45f).dp)) {
            val sx = size.width / 24f
            val sy = size.height / 10f
            withTransform({ scale(sx, sy, Offset.Zero); translate(0f, -7f) }) {
                drawBatteryGlyph(Pal.c("status_batt_body"), accent, c.progress)
            }
        }
    }
}

/**
 * Büyük düşük pil kartı (iOS 26 Beta 5 tarzı): solda "%10 Pil" + alt yazı, sağda renkli kapsül içinde pil.
 * Düşük güç modu açıksa kapsül sarıya döner, alt yazı "Düşük Güç Modu açık" olur.
 */
@Composable
internal fun LowBatteryCardContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val saver = c.extra == 1
    val accent = sysColor(c)
    Row(
        Modifier.fillMaxSize().padding(start = 24.dp, end = 18.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(c.title, color = Pal.c("status_text"), fontWeight = FontWeight.SemiBold, fontSize = 18.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            Text(c.subtitle, color = if (saver) accent else Pal.c("status_text_dim"), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier.size(width = 82.dp, height = 52.dp).clip(RoundedCornerShape(50))
                .background(if (saver) accent else accent.copy(alpha = 0.32f))
                // Pil kapsülüne dokununca düşük güç modu açılır (kartın geri kalanına dokunmak bir şey yapmaz)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { IslandActions.onLowPower() },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(width = 38.dp, height = 18.dp)) {
                val sx = size.width / 24f
                val sy = size.height / 10f
                val ink = if (saver) Color.Black else accent
                withTransform({ scale(sx, sy, Offset.Zero); translate(0f, -7f) }) { drawBatteryGlyph(ink, ink, c.progress) }
            }
        }
    }
}

// ================================================================ Özel ada tasarımı
/** Ana Sayfa'daki seçenek adları (sıra [MoreSettings.designStyle] ile aynı). */
val DESIGN_NAMES = listOf(
    "Siyah", "Yıldızlı gece", "Zebra dalga", "Turkuaz mandala", "Gün batımı", "Sarı mozaik", "Fotoğraf",
    "Kuzey ışıkları", "Okyanus", "Neon ızgara", "Mermer", "Karbon fiber", "Kiraz çiçeği", "Galaksi", "Holografik", "Leopar", "Art deco",
)

/**
 * Seçilen deseni (ya da fotoğrafı) verilen alanı kaplayacak şekilde çizer. Desenler kodla çizilir (görsel dosyası yoktur),
 * her boyutta keskin kalır. Fotoğraf seçili ama henüz yüklenmediyse siyah çizilir.
 */
@Composable
fun DesignFill(style: Int, photo: ImageBitmap?, modifier: Modifier = Modifier) {
    if (style == DESIGN_PHOTO) {
        Box(modifier.background(Color.Black)) {
            if (photo != null) Image(
                bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    Canvas(modifier) { drawDesign(style) }
}

private fun DrawScope.drawDesign(style: Int) {
    val w = size.width
    val h = size.height
    if (w <= 1f || h <= 1f) return
    when (style) {
        1 -> drawStars(w, h)
        2 -> drawZebra(w, h)
        3 -> drawMandala(w, h)
        4 -> drawSunset(w, h)
        5 -> drawMosaic(w, h)
        else -> drawExtraDesign(style, w, h)      // 7 ve sonrası: DesignPatterns.kt
    }
}

/** Sabit tohumlu basit rastgele sayı (her çizimde aynı desen). */
private class Rng(seed: Int) {
    private var s = seed.toLong() * 2654435761L + 1L
    fun next(): Float { s = (s * 6364136223846793005L + 1442695040888963407L); return ((s ushr 33) and 0xFFFFFFL).toFloat() / 16777215f }
}

private fun DrawScope.drawStars(w: Float, h: Float) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF05060C), Color(0xFF0E1020))))
    val r = Rng(7)
    val n = (w * h / (6.dp.toPx() * 6.dp.toPx())).toInt().coerceIn(20, 260)
    repeat(n) {
        val x = r.next() * w; val y = r.next() * h
        val rad = (0.35f + r.next() * 0.9f).dp.toPx()
        val a = 0.35f + r.next() * 0.65f
        drawCircle(Color.White.copy(alpha = a), radius = rad, center = Offset(x, y))
    }
    // birkaç büyük, dört köşeli parıltı
    repeat((n / 25).coerceAtLeast(2)) {
        val x = r.next() * w; val y = r.next() * h
        val l = (2.5f + r.next() * 2.5f).dp.toPx()
        val c = Color.White.copy(alpha = 0.9f)
        drawLine(c, Offset(x - l, y), Offset(x + l, y), strokeWidth = 0.8.dp.toPx(), cap = StrokeCap.Round)
        drawLine(c, Offset(x, y - l), Offset(x, y + l), strokeWidth = 0.8.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(c, radius = 0.9.dp.toPx(), center = Offset(x, y))
    }
}

private fun DrawScope.drawZebra(w: Float, h: Float) {
    drawRect(Color(0xFFF4F4F2))
    val spacing = max(h / 3.2f, 7.dp.toPx())
    val amp = spacing * 0.55f
    val freq = (w / (spacing * 3.4f)).coerceAtLeast(1f)
    val step = 3.dp.toPx()
    var k = -2
    while (k * spacing < h + spacing * 2) {
        val base = k * spacing
        val p = Path()
        var x = 0f
        val ph = k * 1.7f
        while (x <= w + step) {
            val y = base + amp * sin((x / w) * 2f * PI.toFloat() * freq + ph) + amp * 0.35f * sin((x / w) * 6.1f * freq + ph * 2f)
            if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y)
            x += step
        }
        drawPath(p, Color(0xFF111111), style = Stroke(width = spacing * 0.42f, cap = StrokeCap.Round))
        k++
    }
}

private fun DrawScope.drawMandala(w: Float, h: Float) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF0F5A63), Color(0xFF082B3F)), center = Offset(w / 2f, h / 2f), radius = max(w, h) * 0.7f))
    val r = h * 0.62f
    val gap = r * 1.35f
    var cx = w / 2f - gap * kotlin.math.ceil(w / 2f / gap)
    while (cx <= w + r) {
        val c = Offset(cx, h / 2f)
        // dış yapraklar
        for (i in 0 until 12) {
            withTransform({ rotate(i * 30f, c) }) {
                drawOval(Color(0xFF2EC4B6), Offset(c.x - r * 0.13f, c.y - r * 0.95f), Size(r * 0.26f, r * 0.62f))
                drawOval(Color(0xFF0B3D4A), Offset(c.x - r * 0.06f, c.y - r * 0.85f), Size(r * 0.12f, r * 0.40f))
            }
        }
        // orta yapraklar
        for (i in 0 until 8) {
            withTransform({ rotate(i * 45f + 22.5f, c) }) {
                drawOval(Color(0xFF8BE0A4), Offset(c.x - r * 0.12f, c.y - r * 0.58f), Size(r * 0.24f, r * 0.42f))
            }
        }
        drawCircle(Color(0xFF1B6CA8), radius = r * 0.26f, center = c)
        drawCircle(Color(0xFF9CF2E6), radius = r * 0.26f, center = c, style = Stroke(width = r * 0.04f))
        drawCircle(Color(0xFFE8FFF9), radius = r * 0.08f, center = c)
        cx += gap
    }
}

private fun DrawScope.drawSunset(w: Float, h: Float) {
    drawRect(
        Brush.verticalGradient(
            0f to Color(0xFF3B2A6E), 0.38f to Color(0xFF8E4E9B), 0.56f to Color(0xFFF08A5D),
            0.62f to Color(0xFFF6C177), 0.64f to Color(0xFF3B3F78), 1f to Color(0xFF1A1C3D),
        )
    )
    val sun = Offset(w * 0.62f, h * 0.55f)
    val sr = h * 0.22f
    drawCircle(Brush.radialGradient(listOf(Color(0x99FFD27A), Color.Transparent), center = sun, radius = sr * 3f), radius = sr * 3f, center = sun)
    drawCircle(Color(0xFFFFD98E), radius = sr, center = sun)
    // tepeler (siluet)
    val hill = Path().apply {
        moveTo(0f, h * 0.64f)
        var x = 0f
        val step = 4.dp.toPx()
        while (x <= w + step) {
            lineTo(x, h * 0.62f - h * 0.10f * sin(x / w * 5.5f) - h * 0.05f * sin(x / w * 13f + 1f)); x += step
        }
        lineTo(w, h * 0.64f); close()
    }
    drawPath(hill, Color(0xFF2A1E45))
    // sudaki yansıma
    for (i in 0 until 5) {
        val y = h * (0.70f + i * 0.06f)
        val half = sr * (1.1f - i * 0.17f)
        drawLine(Color(0x88FFD98E), Offset(sun.x - half, y), Offset(sun.x + half, y), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
    }
}

private fun DrawScope.drawMosaic(w: Float, h: Float) {
    drawRect(Color(0xFFE6C24A))
    val cols = longArrayOf(0xFFF7DC6F, 0xFFB7932C, 0xFF5E4F18, 0xFFFFF3B0, 0xFFD4A82F)
    val r = Rng(11)
    val n = (w * h / (5.dp.toPx() * 5.dp.toPx())).toInt().coerceIn(30, 320)
    repeat(n) {
        val x = r.next() * w; val y = r.next() * h
        val rad = (1.4f + r.next() * 3.2f).dp.toPx()
        drawCircle(Color(cols[(r.next() * cols.size).toInt().coerceIn(0, cols.size - 1)]), radius = rad, center = Offset(x, y))
    }
}

// ================================================================ Kulaklık kartı
/**
 * Kulaklık bağlandı kartı (AirPods kartı benzeri): solda kulaklık simgesi, ortada adı ve "Bağlandı",
 * sağda (izin verildiyse ve kulaklık bildiriyorsa) pil yüzdesi halkası.
 */
@Composable
internal fun HeadphoneCardContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val pct = c.progress
    // Ekran kaydındaki gibi: solda büyük kulaklık simgesi (zeminsiz), üstte küçük gri "Bağlandı", altında kalın ad
    Row(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 18.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(40.dp)) {
            val sc = size.width / 24f
            withTransform({ scale(sc, sc, Offset.Zero) }) { drawHeadphoneGlyph(Color.White, c.extra == 1) }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(c.subtitle, color = Pal.c("status_text_dim"), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(c.title, color = Pal.c("status_text"), fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
        }
        if (pct >= 0f) {
            val ring = if (pct <= 0.2f) Color(0xFFFF453A) else HOTSPOT_GREEN
            Box(Modifier.size(46.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val sw = 4.dp.toPx()
                    val r = size.minDimension / 2f - sw / 2f
                    val tl = Offset(size.width / 2f - r, size.height / 2f - r)
                    drawCircle(ring.copy(alpha = 0.25f), r, style = Stroke(sw))
                    drawArc(ring, -90f, 360f * pct.coerceIn(0f, 1f), false, tl, Size(r * 2f, r * 2f), style = Stroke(sw, cap = StrokeCap.Round))
                }
                Text(c.value, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}
