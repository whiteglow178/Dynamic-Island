package com.example.dynamicisland

import android.content.Context
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.tanh
import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------- Dene ekranı giriş animasyonu
// Dene'ye basınca önce referanstaki iPhone 15 Pro (doğal titanyum) tam olarak görünür; sonra telefon 3 boyutlu
// dönerek sol kenardaki en üstteki küçük tuşa (Eylem Düğmesi) yaklaşır. Yakınlaşma bitince kamera tam yandan bakar:
// gri metal bant = telefonun yan duvarı, hap = Eylem Düğmesi. Buradan mevcut Dene ekranına yumuşakça geçilir.
// Ölçüler gerçek mm cinsindendir (iPhone 15 Pro: 70.6 x 146.6 x 8.25).

private const val PH_W = 70.6f
private const val PH_H = 146.6f
private const val PH_D = 8.25f
private const val PH_R = 11.2f
private const val BTN_T = 3.45f                       // tuşun kalınlığı (hapın genişliği); uzunluk 7 mm => 2.03 oran, Dene ekranındaki hapla aynı
private const val ACTION_LEN = 7.0f
private const val ACTION_Y = -PH_H / 2f + 31.5f       // Eylem Düğmesi merkezi (telefon merkezine göre, y aşağı)
private const val ACTION_PROT = 0.55f

// Işık (kamera uzayında, birim vektör): sol-üst-ön
private const val LX = -0.45f
private const val LY = -0.50f
private const val LZ = 0.74f

private class V3(val x: Float, val y: Float, val z: Float)

private class PhoneCam(
    val yaw: Float, val pitch: Float, val pivot: V3, val s: Float, val dist: Float, val cx: Float, val cy: Float,
) {
    private val cY = cos(yaw * PI / 180.0).toFloat()
    private val sY = sin(yaw * PI / 180.0).toFloat()
    private val cP = cos(pitch * PI / 180.0).toFloat()
    private val sP = sin(pitch * PI / 180.0).toFloat()

    /** Yaw: sol kenar kameraya doğru gelir. Pitch: üst kenar kameraya doğru gelir. */
    fun dir(x: Float, y: Float, z: Float): V3 {
        val x1 = x * cY + z * sY
        val z1 = -x * sY + z * cY
        return V3(x1, y * cP + z1 * sP, -y * sP + z1 * cP)
    }

    fun pt(x: Float, y: Float, z: Float): V3 = dir(x - pivot.x, y - pivot.y, z - pivot.z)

    fun scr(c: V3): Offset {
        val sc = s * dist / (dist - c.z)
        return Offset(cx + c.x * sc, cy + c.y * sc)
    }

    fun scr(x: Float, y: Float, z: Float): Offset = scr(pt(x, y, z))

    /** Yüzey normali [n], yüzeyin kamera uzayındaki noktası [p]: kameraya bakıyor mu? */
    fun visible(n: V3, p: V3): Boolean = n.x * (-p.x) + n.y * (-p.y) + n.z * (dist - p.z) > 0f
}

// ---------------------------------------------------------------- Gövde çizgisi (yuvarlatılmış dikdörtgen)
private class OutPt(val x: Float, val y: Float)

private val outline: List<OutPt> by lazy {
    val pts = ArrayList<OutPt>()
    val hw = PH_W / 2f
    val hh = PH_H / 2f
    val r = PH_R
    val cx = hw - r
    val cy = hh - r
    fun arc(ccx: Float, ccy: Float, a0: Float, a1: Float) {
        for (i in 0..10) {
            val a = (a0 + (a1 - a0) * i / 10f) * PI / 180.0
            pts.add(OutPt(ccx + r * cos(a).toFloat(), ccy + r * sin(a).toFloat()))
        }
    }
    fun line(x0: Float, y0: Float, x1: Float, y1: Float, n: Int) {
        for (i in 1 until n) pts.add(OutPt(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n))
    }
    arc(-cx, -cy, 180f, 270f); line(-cx, -hh, cx, -hh, 6)
    arc(cx, -cy, 270f, 360f); line(hw, -cy, hw, cy, 14)
    arc(cx, cy, 0f, 90f); line(cx, hh, -cx, hh, 6)
    arc(-cx, cy, 90f, 180f); line(-hw, cy, -hw, -cy, 14)
    pts
}

/** Her kenar parçasının dışa bakan birim normali (x, y). */
private val segNormals: List<FloatArray> by lazy {
    val n = outline.size
    List(n) { i ->
        val a = outline[i]
        val b = outline[(i + 1) % n]
        var nx = b.y - a.y
        var ny = -(b.x - a.x)
        val mx = (a.x + b.x) / 2f
        val my = (a.y + b.y) / 2f
        if (nx * mx + ny * my < 0f) { nx = -nx; ny = -ny }
        val l = sqrt(nx * nx + ny * ny).coerceAtLeast(1e-6f)
        floatArrayOf(nx / l, ny / l)
    }
}

// Yan duvarın kalınlık boyunca şeritleri (ince ve çok sayıda: pürüzsüz, yuvarlak kenar hissi)
private val STRIP_T = floatArrayOf(
    0f, 0.015f, 0.03f, 0.045f, 0.065f, 0.10f, 0.20f, 0.35f, 0.50f, 0.65f, 0.80f, 0.90f, 0.935f, 0.955f, 0.97f, 0.985f, 1f,
)
private const val SIDE_SAMPLES = 48

// Dene ekranındaki gri metal bandın rengi (TryScreen.drawMetalBand ile aynı tonlar).
// Anahtarlar: Eylem Düğmesi merkezine göre dikey konum, "hap boyu" (ACTION_LEN) cinsinden.
private val BAND_KEYS = floatArrayOf(-1.05f, -0.04f, 0.66f, 1.28f, 1.90f)
private val BAND_COLORS = arrayOf(
    Color(0xFF5E5E65), Color(0xFF8A8A90), Color(0xFFA2A2A8), Color(0xFF68686E), Color(0xFF505056),
)

private fun bandBase(yMm: Float): Color {
    val u = (yMm - ACTION_Y) / ACTION_LEN
    if (u <= BAND_KEYS[0]) return BAND_COLORS[0]
    for (i in 1 until BAND_KEYS.size) {
        if (u <= BAND_KEYS[i]) {
            val f = (u - BAND_KEYS[i - 1]) / (BAND_KEYS[i] - BAND_KEYS[i - 1])
            return lerp(BAND_COLORS[i - 1], BAND_COLORS[i], f)
        }
    }
    return BAND_COLORS[BAND_COLORS.size - 1]
}

/** Düz yan duvarın dikey renk örnekleri (her karede yeniden hesaplanmasın). */
private val WALL_BASE: Array<Color> by lazy {
    val yT = -(PH_H / 2f - PH_R)
    val yB = -yT
    Array(SIDE_SAMPLES) { j -> bandBase(yT + (yB - yT) * (j / (SIDE_SAMPLES - 1f))) }
}

private fun shade(c: Color, k: Float) =
    Color((c.red * k).coerceIn(0f, 1f), (c.green * k).coerceIn(0f, 1f), (c.blue * k).coerceIn(0f, 1f))

/** Yandan bakışta (yüzey normali kameraya dönük, l ≈ 0.74) 1.0 verir; eğik açılarda hafifçe değişir. */
private fun lightFactor(l: Float): Float =
    (0.55f + 0.45f * ((0.28f + 0.90f * l.coerceIn(0f, 1f)) / 0.946f)).coerceIn(0.4f, 1.15f)

// Dene ekranındaki gri bantla aynı kenar: dış %7'lik kısım siyaha söner, %4'te ince parlak çizgi, çevresinde hafif ışık.
private const val EDGE_FADE = 0.07f
private const val EDGE_LINE = 0.04f

private fun tri(d: Float, r: Float) = max(0f, 1f - d / r)
private fun edgeOf(u: Float) = min(u, 1f - u)

/** Duvarın kalınlığı boyunca parlaklık (köşe yayları için): kenarlarda siyaha söner. */
private fun wallProfile(u: Float): Float {
    val ramp = (edgeOf(u) / EDGE_FADE).coerceIn(0f, 1f)
    val glow = 1f + 0.08f * tri(abs(edgeOf(u) - EDGE_LINE), 0.03f)
    return ramp * glow
}

// ---------------------------------------------------------------- Tuşlar
private class Btn(val side: Float, val yc: Float, val len: Float, val prot: Float)

private val BTNS = listOf(
    Btn(-1f, ACTION_Y, ACTION_LEN, ACTION_PROT),                 // Eylem Düğmesi (en üstte, küçük)
    Btn(-1f, -PH_H / 2f + 44.5f, 11f, 0.50f),                    // ses +
    Btn(-1f, -PH_H / 2f + 59.5f, 11f, 0.50f),                    // ses -
    Btn(1f, -PH_H / 2f + 57.0f, 19f, 0.50f),                     // güç (sağ)
)

/** Hap (kapsül) çevresi: (y, z) noktaları; merkez (yc, 0). [g] merkez etrafında büyütme. */
private fun pillPoints(yc: Float, len: Float, g: Float = 1f): List<FloatArray> {
    val r = BTN_T / 2f
    val half = len / 2f - r
    val out = ArrayList<FloatArray>()
    for (j in 0..23) {                                        // üst yarım daire
        val a = PI + j * PI / 23.0
        out.add(floatArrayOf(((yc - half + r * sin(a).toFloat()) - yc) * g + yc, r * cos(a).toFloat() * g))
    }
    for (j in 0..23) {                                        // alt yarım daire
        val a = j * PI / 23.0
        out.add(floatArrayOf(((yc + half + r * sin(a).toFloat()) - yc) * g + yc, r * cos(a).toFloat() * g))
    }
    return out
}

private fun DrawScope.fillPoly(path: Path, pts: List<Offset>, color: Color, edge: Boolean = true) {
    path.reset()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
    path.close()
    drawPath(path, color)
    if (edge) drawPath(path, color, style = Stroke(0.7f))     // komşu parçalar arasında ince çizgi kalmasın
}

private fun my0(b: Btn, p: FloatArray, q: FloatArray): Float = (p[0] + q[0]) / 2f

private fun DrawScope.drawButton(cam: PhoneCam, b: Btn, path: Path) {
    val base = pillPoints(b.yc, b.len)
    val xw = b.side * (PH_W / 2f)
    val xo = b.side * (PH_W / 2f + b.prot)

    val wallN = cam.dir(b.side, 0f, 0f)
    val wallVisible = cam.visible(wallN, cam.pt(xw, b.yc, 0f))
    val wl = (wallN.x * LX + wallN.y * LY + wallN.z * LZ)

    // Tuşun oturduğu koyu yuva
    if (wallVisible) {
        val rec = pillPoints(b.yc, b.len, 1.05f).map { cam.scr(xw, it[0], it[1]) }
        fillPoly(path, rec, Color(0xFF050507), edge = false)
    }
    // Yan yüzler
    val n = base.size
    for (i in 0 until n) {
        val p = base[i]
        val q = base[(i + 1) % n]
        var ny = q[1] - p[1]
        var nz = -(q[0] - p[0])
        val my = (p[0] + q[0]) / 2f
        val mz = (p[1] + q[1]) / 2f
        if (ny * (my - b.yc) + nz * mz < 0f) { ny = -ny; nz = -nz }
        val l = sqrt(ny * ny + nz * nz).coerceAtLeast(1e-6f)
        val nc = cam.dir(0f, ny / l, nz / l)
        if (!cam.visible(nc, cam.pt(xw, my, mz))) continue
        val light = nc.x * LX + nc.y * LY + nc.z * LZ
        fillPoly(
            path,
            listOf(cam.scr(xw, p[0], p[1]), cam.scr(xw, q[0], q[1]), cam.scr(xo, q[0], q[1]), cam.scr(xo, p[0], p[1])),
            shade(bandBase(my0(b, p, q)), 0.9f * lightFactor(light)),
        )
    }
    // Dış yüz
    if (wallVisible) {
        val cap = base.map { cam.scr(xo, it[0], it[1]) }
        path.reset()
        path.moveTo(cap[0].x, cap[0].y)
        for (i in 1 until cap.size) path.lineTo(cap[i].x, cap[i].y)
        path.close()
        val bounds = path.getBounds()
        val lfc = lightFactor(wl)
        drawPath(
            path,
            Brush.verticalGradient(
                listOf(
                    shade(bandBase(b.yc - b.len / 2f), 1.02f * lfc),
                    shade(bandBase(b.yc + b.len / 2f), 0.98f * lfc),
                ),
                startY = bounds.top, endY = bounds.bottom,
            ),
        )
    }
}

private val WALL_U = floatArrayOf(0f, 0.01f, 0.0355f, 0.04f, 0.0445f, 0.07f, 0.93f, 0.9555f, 0.96f, 0.9645f, 0.99f, 1f)
private val EDGE_DARK = Color(0xFF050507)

private fun DrawScope.fillPolyBrush(path: Path, pts: List<Offset>, brush: Brush) {
    path.reset()
    path.moveTo(pts[0].x, pts[0].y)
    for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
    path.close()
    drawPath(path, brush)
}

/**
 * Düz yan duvar (sol: s = -1, sağ: s = +1). Üç katman: dikey renk geçişli gri dolgu, kenar çizgisi/ışık, kenarlarda siyaha sönme.
 * Kalınlık boyunca geçişler duvarın dikey kenarlarına paralel çizilir (perspektifte de doğru).
 */
private fun DrawScope.drawSideWall(cam: PhoneCam, s: Float, path: Path) {
    val hz = PH_D / 2f
    val x = s * PH_W / 2f
    val nc = cam.dir(s, 0f, 0f)
    if (!cam.visible(nc, cam.pt(x, 0f, 0f))) return
    val lf = lightFactor(nc.x * LX + nc.y * LY + nc.z * LZ)
    val yT = -(PH_H / 2f - PH_R)
    val yB = -yT
    val a0 = cam.scr(x, yT, hz)
    val a1 = cam.scr(x, yT, -hz)
    val b1 = cam.scr(x, yB, -hz)
    val b0 = cam.scr(x, yB, hz)
    val quad = listOf(a0, a1, b1, b0)
    val st = Offset((a0.x + a1.x) / 2f, (a0.y + a1.y) / 2f)
    val en = Offset((b0.x + b1.x) / 2f, (b0.y + b1.y) / 2f)
    if (abs(en.x - st.x) + abs(en.y - st.y) < 1f) return

    // 1) Dikey renk geçişli gri dolgu
    val stops = Array(SIDE_SAMPLES) { j -> (j / (SIDE_SAMPLES - 1f)) to shade(WALL_BASE[j], lf) }
    fillPolyBrush(path, quad, Brush.linearGradient(colorStops = stops, start = st, end = en))

    // Kalınlık yönü: duvarın dikey kenarına dik
    val ex = b0.x - a0.x
    val ey = b0.y - a0.y
    val el = sqrt(ex * ex + ey * ey)
    if (el < 1f) return
    val gx = -ey / el
    val gy = ex / el
    val proj = (a1.x - a0.x) * gx + (a1.y - a0.y) * gy
    if (abs(proj) < 1f) return
    val gs = a0
    val ge = Offset(a0.x + gx * proj, a0.y + gy * proj)

    // 2) Kenarda ince parlak çizgi + hafif ışık
    val light = Array(WALL_U.size) { i ->
        val d = abs(edgeOf(WALL_U[i]) - EDGE_LINE)
        val a = min(0.7f, 0.14f * tri(d, 0.03f) + 0.55f * tri(d, 0.0045f))
        WALL_U[i] to Color.White.copy(alpha = a)
    }
    fillPolyBrush(path, quad, Brush.linearGradient(colorStops = light, start = gs, end = ge))

    // 3) İki kenarda siyaha sönme
    val clear = EDGE_DARK.copy(alpha = 0f)
    fillPolyBrush(
        path, quad,
        Brush.linearGradient(
            colorStops = arrayOf(0f to EDGE_DARK, EDGE_FADE to clear, 1f - EDGE_FADE to clear, 1f to EDGE_DARK),
            start = gs, end = ge,
        ),
    )
}

// ---------------------------------------------------------------- Telefon (3 boyutlu çizim)
private fun DrawScope.drawPhone3D(
    cam: PhoneCam, face: ImageBitmap, paint: android.graphics.Paint, path: Path, matrix: android.graphics.Matrix,
) {
    val hz = PH_D / 2f
    val n = outline.size

    // 1) Yan duvarlar. Düz sol/sağ duvarlar tek parça, dikey renk geçişli (Dene ekranındaki gri bant gibi pürüzsüz);
    //    köşe yayları kısa parçalarla çizilir.
    val hw = PH_W / 2f
    for (i in 0 until n) {
        val a = outline[i]
        val b = outline[(i + 1) % n]
        if (abs(abs(a.x) - hw) < 1e-3f && abs(abs(b.x) - hw) < 1e-3f) continue
        val sn = segNormals[i]
        val my = (a.y + b.y) / 2f
        val nc = cam.dir(sn[0], sn[1], 0f)
        if (!cam.visible(nc, cam.pt((a.x + b.x) / 2f, my, 0f))) continue
        val lf = lightFactor(nc.x * LX + nc.y * LY + nc.z * LZ)
        val base = bandBase(my)
        val pa = Array(STRIP_T.size) { cam.scr(a.x, a.y, hz - PH_D * STRIP_T[it]) }
        val pb = Array(STRIP_T.size) { cam.scr(b.x, b.y, hz - PH_D * STRIP_T[it]) }
        for (k in 0 until STRIP_T.size - 1) {
            val pm = wallProfile((STRIP_T[k] + STRIP_T[k + 1]) / 2f)
            fillPoly(path, listOf(pa[k], pb[k], pb[k + 1], pa[k + 1]), shade(base, pm * lf))
        }
    }
    drawSideWall(cam, -1f, path)
    drawSideWall(cam, 1f, path)

    // 2) Tuşlar
    for (b in BTNS) drawButton(cam, b, path)

    // 3) Ön yüz (ekran + çerçeve): önceden çizilmiş görüntü, perspektifle 4 köşeye oturtulur
    val nF = cam.dir(0f, 0f, 1f)
    if (cam.visible(nF, cam.pt(0f, 0f, hz))) {
        val c0 = cam.scr(-PH_W / 2f, -PH_H / 2f, hz)
        val c1 = cam.scr(PH_W / 2f, -PH_H / 2f, hz)
        val c2 = cam.scr(PH_W / 2f, PH_H / 2f, hz)
        val c3 = cam.scr(-PH_W / 2f, PH_H / 2f, hz)
        val m = FACE_MARGIN
        val bw = face.width.toFloat()
        val bh = face.height.toFloat()
        val src = floatArrayOf(m, m, bw - m, m, bw - m, bh - m, m, bh - m)
        val dst = floatArrayOf(c0.x, c0.y, c1.x, c1.y, c2.x, c2.y, c3.x, c3.y)
        if (matrix.setPolyToPoly(src, 0, dst, 0, 4)) {
            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                nc.save()
                nc.concat(matrix)
                nc.drawBitmap(face.asAndroidBitmap(), 0f, 0f, paint)
                nc.restore()
            }
        }
    }
}

// ---------------------------------------------------------------- Ön yüz görüntüsü (ekran, çerçeve, duvar kağıdı, kilit ekranı)
private const val FACE_K = 9f           // piksel / mm
private const val FACE_MARGIN = 3f      // görüntü kenarındaki şeffaf pay (piksel)

private fun buildPhoneFace(withIsland: Boolean): ImageBitmap {
    val bw = (PH_W * FACE_K + 2 * FACE_MARGIN).roundToInt()
    val bh = (PH_H * FACE_K + 2 * FACE_MARGIN).roundToInt()
    val bmp = ImageBitmap(bw, bh)
    val canvas = androidx.compose.ui.graphics.Canvas(bmp)
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(bw.toFloat(), bh.toFloat())) {
        withTransform({ translate(FACE_MARGIN, FACE_MARGIN); scale(FACE_K, FACE_K, Offset.Zero) }) { drawFaceMm(withIsland) }
    }
    return bmp
}

/** Tüm koordinatlar mm; başlangıç = telefonun sol-üst köşesi. */
private fun DrawScope.drawFaceMm(withIsland: Boolean) {
    val bodyR = PH_R
    // Titanyum ön kenar
    drawRoundRect(
        Brush.linearGradient(
            0f to Color(0xFFC9C9CF), 0.5f to Color(0xFFA2A2A8), 1f to Color(0xFF6E6E74),
            start = Offset(0f, 0f), end = Offset(PH_W, PH_H),
        ),
        size = Size(PH_W, PH_H), cornerRadius = CornerRadius(bodyR),
    )
    // Siyah cam kenarlığı
    drawRoundRect(
        Color(0xFF040405), Offset(0.8f, 0.8f), Size(PH_W - 1.6f, PH_H - 1.6f), CornerRadius(bodyR - 0.8f),
    )
    // Ekran
    val sx = 2.6f
    val sy = 2.6f
    val sw = PH_W - 2f * sx
    val sh = PH_H - 2f * sy
    val clip = Path().apply { addRoundRect(RoundRect(sx, sy, sx + sw, sy + sh, CornerRadius(8.4f))) }
    clipPath(clip) {
        drawWallpaper(sx, sy, sw, sh)
        drawLockScreen(sx, sy, sw, sh, withIsland)
        // Camdaki yansıma
        drawRect(
            Brush.linearGradient(
                0f to Color.White.copy(alpha = 0.10f), 0.35f to Color.Transparent, 1f to Color.Transparent,
                start = Offset(sx, sy), end = Offset(sx + sw * 0.9f, sy + sh * 0.45f),
            ),
            Offset(sx, sy), Size(sw, sh),
        )
    }
}

private fun DrawScope.drawWallpaper(sx: Float, sy: Float, sw: Float, sh: Float) {
    fun P(u: Float, v: Float) = Offset(sx + u * sw, sy + v * sh)
    drawRect(
        Brush.linearGradient(
            0f to Color(0xFF6A635A), 1f to Color(0xFF8A8377), start = P(0f, 0f), end = P(1f, 1f),
        ),
        Offset(sx, sy), Size(sw, sh),
    )
    // Sol ortada parlak, gri-beyaz alan
    drawRect(
        Brush.radialGradient(listOf(Color(0xCCC4C1BC), Color.Transparent), center = P(0f, 0.55f), radius = sw * 0.85f),
        Offset(sx, sy), Size(sw, sh),
    )
    // Koyu "S" şerit
    val s = Path().apply {
        moveTo(P(-0.15f, -0.02f).x, P(-0.15f, -0.02f).y)
        cubicTo(P(0.55f, 0.06f).x, P(0.55f, 0.06f).y, P(1.12f, 0.26f).x, P(1.12f, 0.26f).y, P(0.86f, 0.50f).x, P(0.86f, 0.50f).y)
        cubicTo(P(0.62f, 0.70f).x, P(0.62f, 0.70f).y, P(0.02f, 0.60f).x, P(0.02f, 0.60f).y, P(0.08f, 0.84f).x, P(0.08f, 0.84f).y)
        cubicTo(P(0.12f, 0.98f).x, P(0.12f, 0.98f).y, P(0.35f, 1.00f).x, P(0.35f, 1.00f).y, P(0.50f, 1.12f).x, P(0.50f, 1.12f).y)
    }
    val dark = Color(0xFF0F0D0A)
    val widths = floatArrayOf(0.46f, 0.40f, 0.34f, 0.28f)
    val alphas = floatArrayOf(0.18f, 0.30f, 0.50f, 0.95f)
    for (i in widths.indices) {
        drawPath(s, dark.copy(alpha = alphas[i]), style = Stroke(width = widths[i] * sw, cap = StrokeCap.Round))
    }
    // Sağ üst köşe koyu
    drawRect(
        Brush.radialGradient(listOf(Color(0x99000000), Color.Transparent), center = P(1f, 0.10f), radius = sw * 0.9f),
        Offset(sx, sy), Size(sw, sh),
    )
    // Parıltı taneleri (kum gibi): rastgele + şeridin kenarlarında yoğun
    val rnd = kotlin.random.Random(5)
    val pm = PathMeasure().apply { setPath(s, false) }
    val total = pm.length
    val buckets = Array(3) { ArrayList<Offset>() }
    repeat(700) {
        buckets[rnd.nextInt(3)].add(Offset(sx + rnd.nextFloat() * sw, sy + rnd.nextFloat() * sh))
    }
    repeat(700) {
        val c = pm.getPosition(rnd.nextFloat() * total)
        val ang = rnd.nextFloat() * 6.2831855f
        val d = (0.12f + rnd.nextFloat() * 0.16f) * sw
        buckets[rnd.nextInt(3)].add(Offset(c.x + d * cos(ang), c.y + d * sin(ang)))
    }
    val gl = Color(0xFFEADFCB)
    drawPoints(buckets[0], PointMode.Points, gl.copy(alpha = 0.18f), 0.32f, StrokeCap.Round)
    drawPoints(buckets[1], PointMode.Points, gl.copy(alpha = 0.34f), 0.26f, StrokeCap.Round)
    drawPoints(buckets[2], PointMode.Points, gl.copy(alpha = 0.55f), 0.20f, StrokeCap.Round)
}

private fun DrawScope.drawLockScreen(sx: Float, sy: Float, sw: Float, sh: Float, withIsland: Boolean) {
    val cx = sx + sw / 2f
    // Dynamic Island
    val iw = 20.8f
    val ih = 6.2f
    val itop = sy + 2.1f
    // Ayarlar önizlemesinde (withIsland = false) telefonun kendi adası çizilmez; yerine gerçek ada gelir
    if (withIsland) {
        drawRoundRect(Color.Black, Offset(cx - iw / 2f, itop), Size(iw, ih), CornerRadius(ih / 2f))
        drawCircle(Color(0xFF0B1022), 1.0f, Offset(cx + 7.2f, itop + ih / 2f))
        drawCircle(Color(0xFF2B3A66).copy(alpha = 0.8f), 0.38f, Offset(cx + 7.0f, itop + ih / 2f - 0.25f))
    }

    val white = Color.White.copy(alpha = 0.92f)

    // Tarih ve saat: yalnızca 3 boyutlu tanıtım telefonunda (ayarlar önizlemesinde ekran boş kalır)
    if (withIsland) drawIntoCanvas { c ->
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.textAlign = android.graphics.Paint.Align.CENTER
        p.color = android.graphics.Color.argb(225, 255, 255, 255)
        p.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        p.textSize = 4.4f
        c.nativeCanvas.drawText("Pazartesi, 9 Kasım", cx, sy + 25f, p)
        p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        p.color = android.graphics.Color.argb(235, 255, 255, 255)
        p.textSize = 23f
        c.nativeCanvas.drawText("10:43", cx, sy + 47f, p)
    }

    // Alttaki fener / kamera düğmeleri ve ana ekran çubuğu
    val by = sy + sh - 11.5f
    for (bxc in floatArrayOf(sx + 9.5f, sx + sw - 9.5f)) {
        drawCircle(Color(0xFF1D1D1F).copy(alpha = 0.75f), 5.0f, Offset(bxc, by))
    }
    drawRoundRect(white, Offset(sx + 9.5f - 1.2f, by - 1.9f), Size(2.4f, 3.8f), CornerRadius(0.5f))
    drawRoundRect(white, Offset(sx + sw - 9.5f - 2.0f, by - 1.4f), Size(4.0f, 2.8f), CornerRadius(0.7f), style = Stroke(0.4f))
    drawCircle(white, 0.8f, Offset(sx + sw - 9.5f, by), style = Stroke(0.4f))
    drawRoundRect(white.copy(alpha = 0.85f), Offset(cx - 10.3f, sy + sh - 2.6f), Size(20.6f, 0.9f), CornerRadius(0.45f))
}

// ---------------------------------------------------------------- Animasyon
private fun seg(p: Float, a: Float, b: Float) = ((p - a) / (b - a)).coerceIn(0f, 1f)
private fun smooth(t: Float) = t * t * (3f - 2f * t)
private fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t

/**
 * Dene ekranının üstüne biner. Tam telefon belirir -> 3 boyutlu döner -> sol üstteki Eylem Düğmesi'ne yaklaşır
 * -> yandan bakış (gri metal bant + hap) -> altındaki Dene ekranına geçer. Dokunursan atlanır.
 * [pillWpx] Dene ekranındaki hapın piksel genişliği: yakınlaşma bittiğinde tuş tam o boyda olur.
 * [glowY] Dene ekranındaki hapın dikey merkezi.
 */
@Composable
internal fun TryPhoneIntro(pillWpx: Float, glowY: () -> Float, onDone: () -> Unit) {
    var face by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(Unit) { face = obtainPhoneFace() }

    val p = remember { Animatable(0f) }
    val cover = remember { Animatable(1f) }
    var skip by remember { mutableStateOf(false) }
    LaunchedEffect(face, skip) {
        if (face == null) return@LaunchedEffect
        if (skip) p.animateTo(1f, tween(420)) else p.animateTo(1f, tween(4200, easing = LinearEasing))
        cover.animateTo(0f, tween(420))
        onDone()
    }

    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val path = remember { Path() }
    val matrix = remember { android.graphics.Matrix() }

    Box(
        Modifier.fillMaxSize()
            .clipToBounds()
            .graphicsLayer { alpha = cover.value }
            .pointerInput(Unit) { detectTapGestures(onTap = { skip = true }) },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF050507))
            val img = face ?: return@Canvas
            val t = p.value
            val w = size.width
            val h = size.height

            val a = seg(t, 0.15f, 0.96f)
            val ye = smooth(a)
            val zoomT = smooth(seg(t, 0.28f, 1f))
            val pivT = smooth(seg(t, 0.20f, 1f))
            val appear = smooth(seg(t, 0f, 0.10f))

            val s0 = min2(h * 0.70f / PH_H, w * 0.78f / PH_W) * (0.90f + 0.10f * appear)
            val s1 = pillWpx / BTN_T
            val s = s0 * (s1 / s0).pow(zoomT)
            val gy = glowY().let { if (it > 0f) it else h * 0.4f }
            val cy = lerpF(h * 0.46f, gy, pivT)
            val pivot = V3(
                lerpF(0f, -(PH_W / 2f + ACTION_PROT), pivT),
                lerpF(0f, ACTION_Y, pivT),
                0f,
            )
            val cam = PhoneCam(
                yaw = 90f * ye,
                pitch = 15f * sin(PI * ye).toFloat(),
                pivot = pivot, s = s, dist = lerpF(330f, 150f, zoomT), cx = w / 2f, cy = cy,
            )

            // Yumuşak ışık hâlesi (yakınlaştıkça söner)
            val glowA = 0.10f * appear * (1f - zoomT)
            if (glowA > 0.001f) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = glowA), Color.Transparent),
                        center = Offset(w / 2f, h * 0.46f), radius = w * 0.85f,
                    ),
                )
            }

            drawIntoCanvas { c ->
                val nc = c.nativeCanvas
                // Başlangıçta yumuşak belirme: tüm telefon tek katmanda solar
                val layer = nc.saveLayerAlpha(0f, 0f, w, h, (appear * 255f).roundToInt().coerceIn(0, 255))
                paint.alpha = 255
                drawPhone3D(cam, img, paint, path, matrix)
                nc.restoreToCount(layer)
            }

            // Sona doğru: Dene ekranındaki gri bant gibi üstte ve altta siyaha sol
            val fe = smooth(seg(t, 0.78f, 1f))
            if (fe > 0.001f) {
                val ph = pillWpx * 2.03f
                fun f(y: Float) = (y / h).coerceIn(0f, 1f)
                val st = floatArrayOf(f(gy - 1.6f * ph), f(gy - 0.9f * ph), f(gy + 1.2f * ph), f(gy + 2.0f * ph))
                for (i in 1 until 4) if (st[i] < st[i - 1]) st[i] = st[i - 1]
                drawRect(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = fe),
                            st[0] to Color.Black.copy(alpha = fe),
                            st[1] to Color.Transparent,
                            st[2] to Color.Transparent,
                            st[3] to Color.Black.copy(alpha = fe),
                            1f to Color.Black.copy(alpha = fe),
                        ),
                    ),
                )
            }
        }
    }
}

private fun min2(a: Float, b: Float) = if (a < b) a else b


// ---------------------------------------------------------------- Dene: olay gösterimi
// Dene ekranında hapa basınca: kamera yandan (Eylem Düğmesi) geri çekilir, telefon 3 boyutlu dönüp ekranı bize bakar,
// olay telefonun KENDİ Dynamic Island'ında oynar (uygulamadaki gerçek IslandRoot / IslandPill, ayarlarla birebir),
// bitince telefon tekrar yana dönüp Dene ekranına geçilir. Telefona dokunursan gösterim erken biter.

/** Gösterimin ne oynatacağı: [script] ada durumunu (içerik + açık/kapalı) sırayla değiştirir, bitince geri dönülür. */
internal class ShowcaseRun(val id: Int, val script: suspend (MutableStateFlow<IslandUiState>, IslandSettings) -> Unit)

private var phoneFaceCache: ImageBitmap? = null
private var phoneFaceNoIslandCache: ImageBitmap? = null

private suspend fun obtainPhoneFace(withIsland: Boolean = true): ImageBitmap =
    if (withIsland) {
        phoneFaceCache ?: withContext(Dispatchers.Default) { buildPhoneFace(true) }.also { phoneFaceCache = it }
    } else {
        phoneFaceNoIslandCache ?: withContext(Dispatchers.Default) { buildPhoneFace(false) }.also { phoneFaceNoIslandCache = it }
    }

/** Animasyon zamanı: bu değerde telefon tam karşıdan görünür (giriş animasyonunun 0.10 anı). */
private const val FRONT_T = 0.10f
private const val SCREEN_INSET_MM = 2.6f      // buildPhoneFace içindeki ekran payı ile aynı
private const val SCREEN_CORNER_MM = 8.4f

/** Telefon tam karşıdayken kamera (giriş animasyonunun t = FRONT_T karesiyle aynı). */
private fun frontCam(w: Float, h: Float, yaw: Float = 0f): PhoneCam =
    PhoneCam(yaw, 0f, V3(0f, 0f, 0f), min2(h * 0.70f / PH_H, w * 0.78f / PH_W), 330f, w / 2f, h * 0.46f)

// ---------------------------------------------------------------- Gösterim sırasında telefonu çevirerek 3B telefonu döndürme
/** 3B telefonun sağa/sola en fazla dönebileceği açı (derece). Yan duvarlar bu açıda rahat görünür. */
private const val TILT_MAX_DEG = 40f
/** Gerçek telefonun 1 derecelik dönüşü 3B telefonda kaç derece olsun (1 = birebir). Büyük = daha az çevirmen yeter. */
private const val TILT_GAIN = 2.5f
/** Dönüş yönü: 1 = 3B telefon senin elindeki telefonla aynı yöne döner, -1 = ters yöne (sabit duran nesne gibi). */
private const val TILT_SIGN = 1f
/** Yumuşatma zaman sabiti (saniye): küçük = daha çevik, büyük = daha yumuşak. Kare hızından bağımsızdır. */
private const val TILT_TAU_S = 0.07f

/** Gyro açısı en fazla bu kadar birikir (derece); sınırın ötesine çevirsen bile geri dönüş hemen başlar. */
private const val TILT_RAW_LIMIT_DEG = 30f

/**
 * Telefonun uzun ekseni (dikey eksen) etrafındaki dönüşünü ölçer ve [yaw]'a (derece) yazar.
 * Ana yöntem: jiroskobun (TYPE_GYROSCOPE) y ekseni dönüş hızı zamanla toplanır. Başlangıç tam olarak 0'dır;
 * sensör füzyonunun "yerleşmesini" beklemek gerekmez, o yüzden telefon düz dururken 3B telefon da düz durur.
 * Jiroskop yoksa game rotation vector kullanılır (ilk 0.4 sn yerleşme için atlanır).
 */
@Composable
private fun TiltSensorEffect(enabled: Boolean, yaw: MutableState<Float>) {
    val ctx = LocalContext.current
    val target = remember { FloatArray(1) }

    // Yumuşatma sensör olaylarında değil, EKRAN KARELERİNDE yapılır: hareket her karede akıcı ilerler
    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) {
                    val dt = (now - last) / 1_000_000_000f
                    yaw.value += (target[0] - yaw.value) * (1f - exp(-dt / TILT_TAU_S))
                }
                last = now
            }
        }
    }

    DisposableEffect(enabled) {
        yaw.value = 0f
        target[0] = 0f
        if (!enabled) return@DisposableEffect onDispose { }
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return@DisposableEffect onDispose { }

        fun publish(deg: Float) {
            target[0] = TILT_MAX_DEG * tanh(TILT_SIGN * deg * TILT_GAIN / TILT_MAX_DEG)
        }

        val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val listener: SensorEventListener
        val sensor: Sensor
        if (gyro != null) {
            sensor = gyro
            listener = object : SensorEventListener {
                private var lastNs = 0L
                private var angle = 0f
                override fun onSensorChanged(e: SensorEvent) {
                    if (lastNs != 0L) {
                        val dt = (e.timestamp - lastNs) / 1_000_000_000f
                        var w = e.values[1]                       // rad/s, cihazın y (uzun) ekseni etrafında
                        if (abs(w) < 0.012f) w = 0f               // sensör sapmasını (bias) yok say
                        if (dt in 0f..0.2f) {
                            angle = (angle + w * dt * (180f / PI.toFloat()))
                                .coerceIn(-TILT_RAW_LIMIT_DEG, TILT_RAW_LIMIT_DEG)
                            publish(angle)
                        }
                    }
                    lastNs = e.timestamp
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
        } else {
            sensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
                ?: return@DisposableEffect onDispose { }
            listener = object : SensorEventListener {
                private val r = FloatArray(9)
                private var ref: FloatArray? = null
                private var startNs = 0L
                override fun onSensorChanged(e: SensorEvent) {
                    if (startNs == 0L) startNs = e.timestamp
                    if (e.timestamp - startNs < 400_000_000L) return       // füzyon yerleşsin
                    SensorManager.getRotationMatrixFromVector(r, e.values)
                    val r0 = ref ?: r.copyOf().also { ref = it }
                    var x = 0f
                    var z = 0f
                    for (k in 0..2) {
                        x += r0[k * 3] * r[k * 3 + 2]
                        z += r0[k * 3 + 2] * r[k * 3 + 2]
                    }
                    publish((atan2(x, z) * 180.0 / PI).toFloat())
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
            }
        }
        sm.registerListener(listener, sensor, 10_000)      // ~100 Hz
        onDispose { sm.unregisterListener(listener) }
    }
}

// ---------------------------------------------------------------- Şarj kablosu (Dene > Şarj gösterimi)
// Beyaz USB-C kablo ekranın altından yükselir, telefonun alt kenarındaki şarj girişine takılır. Kablo düz ve yuvarlaktır
// (silindir gibi gölgelenir); telefon uzayında çizildiği için telefonla birlikte 3 boyutlu döner.

private const val CABLE_R = 1.75f                  // kablo yarıçapı, mm
private const val CABLE_LEN = 140f                 // konnektörden aşağı; çoğu ekran dışında kalır
private const val BOOT_R = 2.5f                    // konnektör çıkışındaki kauçuk boğaz
private const val BOOT_LEN = 9f
private const val HOUSING_LEN = 17f                // beyaz konnektör gövdesi
private const val HOUSING_W = 12.5f
private const val HOUSING_D = 6.4f
private const val METAL_W = 8.4f                   // USB-C metal uç
private const val METAL_D = 2.6f
private const val METAL_LEN = 6.8f
private const val PORT_Y = PH_H / 2f               // telefonun alt kenarı
private const val PLUG_GAP_END = 0.7f              // takılıyken gövde ile telefon arası
private const val PLUG_START_GAP = 80f             // takılmadan önce gövde ekranın altında bu kadar aşağıda başlar

/** Konnektör gövdesinin telefona bakan ucunun dikey konumu. [e]: takılma ilerlemesi (0 = aşağıda, 1 = takılı). */
private fun plugTopY(e: Float): Float = PORT_Y + PLUG_GAP_END + (1f - e) * PLUG_START_GAP

private fun roundedPts(x0: Float, y0: Float, x1: Float, y1: Float, r: Float): List<FloatArray> {
    val out = ArrayList<FloatArray>()
    fun arc(cx: Float, cy: Float, a0: Float) {
        for (i in 0..5) {
            val a = (a0 + 90f * i / 5f) * PI / 180.0
            out.add(floatArrayOf(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat()))
        }
    }
    arc(x1 - r, y0 + r, 270f); arc(x1 - r, y1 - r, 0f); arc(x0 + r, y1 - r, 90f); arc(x0 + r, y0 + r, 180f)
    return out
}

/** Silindir gibi gölgelenen beyaz yüzey: soldan ışık, sağda gölge. */
private fun cableBrush(left: Float, width: Float): Brush = Brush.horizontalGradient(
    colorStops = arrayOf(
        0f to Color(0xFFB4B4BC), 0.22f to Color(0xFFF3F3F6), 0.42f to Color.White,
        0.72f to Color(0xFFD9D9DF), 1f to Color(0xFF9E9EA7),
    ),
    startX = left, endX = left + width,
)

/** Kablo + konnektör. Telefondan sonra çizilir (kablo telefonun altında kalır, telefonun üstüne binmez). */
private fun DrawScope.drawCable(cam: PhoneCam, e: Float, path: Path) {
    val hw = HOUSING_W / 2f
    val hd = HOUSING_D / 2f
    val yTop = plugTopY(e)
    val yBot = yTop + HOUSING_LEN

    // 1) Kablo: telefonun dikey ekseninde olduğu için ekranda dikey ve sabit genişlikli bir silindir
    val c = cam.pt(0f, yBot, 0f)
    val sc = cam.s * cam.dist / (cam.dist - c.z)
    val cx = cam.scr(0f, yBot, 0f).x
    val yA = cam.scr(0f, yBot - 1f, 0f).y
    val yB = cam.scr(0f, yBot + CABLE_LEN, 0f).y
    val cw = 2f * CABLE_R * sc
    drawRect(cableBrush(cx - cw / 2f, cw), Offset(cx - cw / 2f, yA), Size(cw, yB - yA))
    // Kauçuk boğaz: kablodan biraz kalın, alt ucu yuvarlak
    val bw = 2f * BOOT_R * sc
    val bTop = cam.scr(0f, yBot - 2f, 0f).y
    val bBot = cam.scr(0f, yBot + BOOT_LEN, 0f).y
    drawRoundRect(
        cableBrush(cx - bw / 2f, bw), Offset(cx - bw / 2f, bTop), Size(bw, bBot - bTop), CornerRadius(bw / 2f),
    )

    // Yuvarlak (stadyum) kesitin ekrandaki sol/sağ sınırı: dönünce genişlik gerçekçi değişir
    fun span(a: Float, b: Float, y: Float): Pair<Float, Float> {
        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        val cx0 = a - b
        for (k in 0 until 24) {
            val ang = k * 2.0 * PI / 24.0
            val ca = cos(ang).toFloat()
            val px = (if (ca >= 0f) cx0 else -cx0) + b * ca
            val pz = b * sin(ang).toFloat()
            val sx = cam.scr(px, y, pz).x
            if (sx < lo) lo = sx
            if (sx > hi) hi = sx
        }
        return lo to hi
    }

    // 2) USB-C metal uç (yalnızca telefonun dışında kalan kısmı): yuvarlak uçlu, silindir gibi gölgeli
    val yM0 = max(PORT_Y, yTop - METAL_LEN)
    if (yTop - yM0 > 0.02f) {
        val (ml, mr) = span(METAL_W / 2f, METAL_D / 2f, yTop)
        val mt = cam.scr(0f, yM0, 0f).y
        val mb = cam.scr(0f, yTop, 0f).y
        drawRoundRect(
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0f to Color(0xFF7E8087), 0.2f to Color(0xFFC9CBD2), 0.5f to Color(0xFFE2E4E9),
                    0.8f to Color(0xFFB4B6BD), 1f to Color(0xFF7B7D84),
                ),
                startX = ml, endX = mr,
            ),
            Offset(ml, mt), Size(mr - ml, mb - mt), CornerRadius((mr - ml) * 0.1f),
        )
    }

    // 3) Beyaz konnektör gövdesi: yuvarlak köşeli, kenarları silindir gibi yumuşakça gölgeli (kare kutu değil)
    val (hl, hr) = span(hw, hd, (yTop + yBot) / 2f)
    val hTop = cam.scr(0f, yTop, 0f).y
    val hBot = cam.scr(0f, yBot, 0f).y
    val hwid = hr - hl
    val rt = min(1.8f * sc, hwid / 2f)
    val rb = min(5.5f * sc, hwid / 2f)
    path.reset()
    path.addRoundRect(RoundRect(hl, hTop, hr, hBot, CornerRadius(rt), CornerRadius(rt), CornerRadius(rb), CornerRadius(rb)))
    drawPath(
        path,
        Brush.horizontalGradient(
            colorStops = arrayOf(
                0f to Color(0xFFB9B9C1), 0.14f to Color(0xFFEDEDF1), 0.26f to Color(0xFFFBFBFD),
                0.62f to Color(0xFFF6F6F8), 0.80f to Color(0xFFE0E0E5), 1f to Color(0xFFA7A7B0),
            ),
            startX = hl, endX = hr,
        ),
    )
    drawPath(path, Color(0x66808088), style = Stroke(0.8f))
}

@Composable
internal fun TryPhoneShowcase(pillWpx: Float, glowY: () -> Float, run: ShowcaseRun, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var face by remember { mutableStateOf<ImageBitmap?>(phoneFaceCache) }
    LaunchedEffect(Unit) { if (face == null) face = obtainPhoneFace() }

    // Gerçek ada ayarları (renk, şekil, animasyon stili, konum ...)
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val cfg by repo.flow.collectAsState(initial = IslandSettings())
    val cfgFlow = remember { MutableStateFlow(cfg.effective()) }
    LaunchedEffect(cfg) { cfgFlow.value = cfg.effective() }
    val uiState = remember { MutableStateFlow(IslandUiState()) }
    val lyricsFlow = remember { MutableStateFlow<LyricsState>(LyricsState.Idle) }

    val p = remember { Animatable(1f) }          // 1 = yandan (Eylem Düğmesi), FRONT_T = karşıdan
    val cover = remember { Animatable(0f) }
    val islandA = remember { Animatable(0f) }
    val edge = remember { Animatable(1f) }       // üst/alt siyaha sönme (1 = Dene ekranındaki gibi karanlık)
    var skip by remember { mutableStateOf(false) }

    // Telefonu çevirince 3B telefon da sağa/sola döner (yalnızca ekran bize dönükken)
    val tiltYaw = remember { mutableStateOf(0f) }
    val tiltGain = remember { Animatable(0f) }
    var tiltOn by remember { mutableStateOf(false) }
    TiltSensorEffect(tiltOn, tiltYaw)

    // Şarj gösterimi (id 3): kablo telefon dönerken gelir, takılır; sonra ada animasyonu oynar
    val isCharge = run.id == 3
    val plug = remember { Animatable(0f) }

    LaunchedEffect(face) {
        if (face == null) return@LaunchedEffect
        cover.animateTo(1f, tween(220))
        launch { edge.animateTo(0f, tween(350, easing = FastOutSlowInEasing)) }   // uzaklaşırken karanlık hemen kalkar
        if (isCharge) {
            coroutineScope {
                launch { p.animateTo(FRONT_T, tween(1700, easing = FastOutSlowInEasing)) }     // ekran bize döner
                launch {
                    snapshotFlow { p.value }.first { it <= 0.45f }       // telefon neredeyse dönünce kablo hemen gelir
                    plug.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
                }
            }
        } else {
            p.animateTo(FRONT_T, tween(1700, easing = FastOutSlowInEasing))      // ekran bize döner
        }
        tiltOn = true
        launch { tiltGain.animateTo(1f, tween(500)) }
        islandA.animateTo(1f, tween(200))
        coroutineScope {
            val job = launch { run.script(uiState, cfgFlow.value) }
            val watcher = launch { snapshotFlow { skip }.first { it }; job.cancel() }
            job.join()
            watcher.cancel()
        }
        coroutineScope {
            launch { islandA.animateTo(0f, tween(220)) }
            if (isCharge) launch { plug.animateTo(0f, tween(800, easing = FastOutSlowInEasing)) }   // kablo çekilir
            tiltGain.animateTo(0f, tween(350))                                // önce düz karşıya dön
        }
        tiltOn = false
        launch { delay(520); edge.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }   // yana dönünce tekrar kararır
        p.animateTo(1f, tween(1200, easing = FastOutSlowInEasing))           // tekrar yana
        cover.animateTo(0f, tween(320))
        onDone()
    }

    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val path = remember { Path() }
    val matrix = remember { android.graphics.Matrix() }
    val ovMatrix = remember { android.graphics.Matrix() }

    BoxWithConstraints(
        Modifier.fillMaxSize()
            .clipToBounds()
            .graphicsLayer { alpha = cover.value }
            .pointerInput(Unit) { detectTapGestures(onTap = { skip = true }) },
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()

        Canvas(Modifier.fillMaxSize()) {
            drawRect(Color(0xFF050507))
            val img = face ?: return@Canvas
            val t = p.value
            val a = seg(t, 0.15f, 0.96f)
            val ye = smooth(a)
            val zoomT = smooth(seg(t, 0.28f, 1f))
            val pivT = smooth(seg(t, 0.20f, 1f))
            val appear = smooth(seg(t, 0f, 0.10f))

            val s0 = min2(h * 0.70f / PH_H, w * 0.78f / PH_W) * (0.90f + 0.10f * appear)
            val s1 = pillWpx / BTN_T
            val s = s0 * (s1 / s0).pow(zoomT)
            val gy = glowY().let { if (it > 0f) it else h * 0.4f }
            val cy = lerpF(h * 0.46f, gy, pivT)
            val pivot = V3(lerpF(0f, -(PH_W / 2f + ACTION_PROT), pivT), lerpF(0f, ACTION_Y, pivT), 0f)
            val cam = PhoneCam(
                yaw = 90f * ye + tiltYaw.value * tiltGain.value, pitch = 15f * sin(PI * ye).toFloat(),
                pivot = pivot, s = s, dist = lerpF(330f, 150f, zoomT), cx = w / 2f, cy = cy,
            )
            val glowA = 0.10f * appear * (1f - zoomT)
            if (glowA > 0.001f) {
                drawRect(
                    Brush.radialGradient(
                        listOf(Color.White.copy(alpha = glowA), Color.Transparent),
                        center = Offset(w / 2f, h * 0.46f), radius = w * 0.85f,
                    ),
                )
            }
            drawPhone3D(cam, img, paint, path, matrix)
            if (isCharge && plug.value > 0.001f) drawCable(cam, plug.value, path)

            // Yandayken: Dene ekranındaki gibi üstte ve altta siyaha sol
            val fe = edge.value
            if (fe > 0.001f) {
                val ph = pillWpx * 2.03f
                fun f(y: Float) = (y / h).coerceIn(0f, 1f)
                val st = floatArrayOf(f(gy - 1.6f * ph), f(gy - 0.9f * ph), f(gy + 1.2f * ph), f(gy + 2.0f * ph))
                for (i in 1 until 4) if (st[i] < st[i - 1]) st[i] = st[i - 1]
                drawRect(
                    Brush.verticalGradient(
                        colorStops = arrayOf(
                            0f to Color.Black.copy(alpha = fe), st[0] to Color.Black.copy(alpha = fe),
                            st[1] to Color.Transparent, st[2] to Color.Transparent,
                            st[3] to Color.Black.copy(alpha = fe), 1f to Color.Black.copy(alpha = fe),
                        ),
                    ),
                )
            }
        }

        // Telefon karşıdayken: ekranın üstüne gerçek ada (412 dp'lik sanal ekran, PhoneMock ile aynı yöntem)
        if (face != null) {
            val dens = LocalDensity.current
            val cam = remember(w, h) { frontCam(w, h) }
            val hz = PH_D / 2f
            val c0 = cam.scr(-PH_W / 2f + SCREEN_INSET_MM, -PH_H / 2f + SCREEN_INSET_MM, hz)
            val c2 = cam.scr(PH_W / 2f - SCREEN_INSET_MM, PH_H / 2f - SCREEN_INSET_MM, hz)
            val sw = c2.x - c0.x
            val sh = c2.y - c0.y
            val pxPerMm = sw / (PH_W - 2f * SCREEN_INSET_MM)
            val k = sw / (PHONE_VIRTUAL_W_DP * dens.density)
            val c = cfg.effective()
            val topV = (PHONE_BASE_TOP_DP + c.offsetYDp).coerceAtLeast(0f)
            Box(
                Modifier
                    .size(with(dens) { sw.toDp() }, with(dens) { sh.toDp() })
                    // Ekran döndükçe adayı da aynı perspektifle ekranın 4 köşesine oturt
                    .drawWithContent {
                        val tc = frontCam(w, h, tiltYaw.value * tiltGain.value)
                        val i = SCREEN_INSET_MM
                        val q0 = tc.scr(-PH_W / 2f + i, -PH_H / 2f + i, hz)
                        val q1 = tc.scr(PH_W / 2f - i, -PH_H / 2f + i, hz)
                        val q2 = tc.scr(PH_W / 2f - i, PH_H / 2f - i, hz)
                        val q3 = tc.scr(-PH_W / 2f + i, PH_H / 2f - i, hz)
                        val src = floatArrayOf(0f, 0f, sw, 0f, sw, sh, 0f, sh)
                        val dst = floatArrayOf(q0.x, q0.y, q1.x, q1.y, q2.x, q2.y, q3.x, q3.y)
                        if (ovMatrix.setPolyToPoly(src, 0, dst, 0, 4)) {
                            val nc = drawContext.canvas.nativeCanvas
                            nc.save()
                            nc.concat(ovMatrix)
                            drawContent()
                            nc.restore()
                        }
                    }
                    .graphicsLayer { alpha = islandA.value }
                    .clip(RoundedCornerShape(with(dens) { (SCREEN_CORNER_MM * pxPerMm).toDp() })),
            ) {
                CompositionLocalProvider(LocalDensity provides Density(dens.density * k, dens.fontScale)) {
                    // Asistan gösterimi: gerçek servisteki gibi ekran kenarlarında renkli parlama (adanın altında kalır)
                    if (run.id == 8) {
                        val uiNow by uiState.collectAsState()
                        val glowOn = remember { MutableStateFlow(false) }
                        LaunchedEffect(uiNow.content, uiNow.expanded) {
                            glowOn.value = uiNow.content is IslandContent.Assistant && uiNow.expanded
                        }
                        ScreenEdgeGlow(glowOn, SCREEN_CORNER_MM * pxPerMm)
                    }
                    Box(Modifier.fillMaxSize().offset(x = c.offsetXDp.dp, y = (topV - c.windowPadDp()).dp)) {
                        IslandRoot(
                            state = uiState, settingsFlow = cfgFlow, lyricsFlow = lyricsFlow,
                            onTap = {}, onCommand = {}, onOutput = {},
                        )
                    }
                }
            }
        }
    }
}


// ---------------------------------------------------------------- Ayarlar önizlemesi (3 boyutlu telefon)
// Ayarlar ekranının üstündeki önizleme kartı bu telefonu kullanır. Telefon tam karşıdan görünür; ekranın üstüne
// gerçek ada çizilir (TryPhoneShowcase ile aynı yöntem). İki kullanım:
//  - Normal: telefon büyük çizilir, kart yalnızca üst kısmı (ada bölgesi) gösterir, alt kısım kartta kesilir.
//  - Tam: telefonun tamamı karta sığacak şekilde küçültülür (tam genişlik asistan, kenar tuşları gibi ayarlar).

/** Normal (üst kısım) önizlemede telefon genişliği, dp. */
internal const val P3D_W_DP = 236f
/** Normal önizlemede telefonun karttaki üst boşluğu, dp. */
internal const val P3D_TOP_DP = 16f
/** Tam telefon önizlemesinde kart yüksekliği, dp. Normal önizleme kartıyla aynıdır (kart büyümez); telefonun tamamı buna sığar. */
internal const val P3D_FULL_H_DP = 150f
/** Tam telefon önizlemesinde telefonun üst/alt boşluğu, dp. */
internal const val P3D_FULL_PAD_DP = 8f

/** Ekranın telefon kenarından içeri payı (dp), telefon genişliği [wDp] iken. */
internal fun p3dScreenInsetDp(wDp: Float): Float = SCREEN_INSET_MM * wDp / PH_W

/** 412 dp'lik sanal ekranı maket ekrana sığdıran yoğunluk çarpanı. */
internal fun p3dScale(wDp: Float): Float = (wDp - 2f * p3dScreenInsetDp(wDp)) / PHONE_VIRTUAL_W_DP

/** Tam telefon önizlemesinde telefonun genişliği (dp): yükseklik [P3D_FULL_H_DP] - boşluklara sığar. */
internal fun p3dFullWidthDp(): Float = (P3D_FULL_H_DP - 2f * P3D_FULL_PAD_DP) * PH_W / PH_H

/**
 * [phoneWDp] genişliğinde 3 boyutlu telefon (karşıdan). Boyutu telefonun tamamıdır (en-boy oranı korunur); üst kapsayıcı
 * kısa ise alt kısım kapsayıcıda kesilir. [content] ekranın içine, 412 dp genişliğinde sanal ekran olarak çizilir.
 */
@Composable
internal fun Phone3DPreview(phoneWDp: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    // Telefonun kendi dinamik adası çizilmez: önizlemedeki ada yalnızca uygulamanın gerçek adasıdır
    var face by remember { mutableStateOf<ImageBitmap?>(phoneFaceNoIslandCache) }
    LaunchedEffect(Unit) { if (face == null) face = obtainPhoneFace(false) }

    val paint = remember { android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG) }
    val path = remember { Path() }
    val matrix = remember { android.graphics.Matrix() }
    val dens = LocalDensity.current

    val phoneHDp = phoneWDp * PH_H / PH_W
    val insetDp = p3dScreenInsetDp(phoneWDp)
    val k = p3dScale(phoneWDp)

    // unbounded + Top: üst kapsayıcı kısa olsa da telefon tam boyunda ölçülür, ÜSTTEN hizalanır (taşan alt kısım kapsayıcıda kesilir)
    Box(modifier.wrapContentHeight(Alignment.Top, unbounded = true).size(phoneWDp.dp, phoneHDp.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val img = face ?: return@Canvas
            val dist = 330f
            val hz = PH_D / 2f
            // Ön yüz tam olarak tuvale oturur: mm başına piksel = genişlik / PH_W
            val pxPerMm = size.width / PH_W
            val cam = PhoneCam(0f, 0f, V3(0f, 0f, 0f), pxPerMm * (dist - hz) / dist, dist, size.width / 2f, size.height / 2f)
            drawPhone3D(cam, img, paint, path, matrix)
        }

        if (face != null) {
            Box(
                Modifier
                    .offset(insetDp.dp, insetDp.dp)
                    .size((phoneWDp - 2f * insetDp).dp, (phoneHDp - 2f * insetDp).dp)
                    .clip(RoundedCornerShape((SCREEN_CORNER_MM * phoneWDp / PH_W).dp)),
            ) {
                CompositionLocalProvider(LocalDensity provides Density(dens.density * k, dens.fontScale)) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}
