package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Ana Sayfa'daki sahne adları (sıra [MoreSettings.portalScene] ile aynı). */
val PORTAL_NAMES = listOf("Uzay", "Akvaryum", "Gece şehri", "Gökyüzü")

/**
 * Telefonun anlık eğimi (-1..1). Tutuş açısı yavaşça "merkez" kabul edilir: telefonu nasıl tutarsan tut sahne ortalanır,
 * yalnızca hareket ettirince kayar. Değerler yalnızca çizim aşamasında okunur (yeniden kompozisyon olmaz).
 */
object PortalTilt {
    var x by mutableFloatStateOf(0f)
    var y by mutableFloatStateOf(0f)
}

/**
 * Portal ada: adanın içi bir pencere gibi davranır; arkasında derinlikli bir sahne vardır.
 * Uzak katmanlar az, yakın katmanlar çok kayar (paralaks); kenarlardaki iç gölge "delik" hissi verir.
 * Sensör yalnızca bu çizim ekrandayken ve ekran açıkken dinlenir.
 */
@Composable
fun PortalScene(scene: Int, depth: Float, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var bx = Float.NaN
        var bz = Float.NaN
        var fx = 0f
        var fz = 0f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0] / 9.81f
                val az = e.values[2] / 9.81f
                if (bx.isNaN()) { bx = ax; bz = az }
                bx += (ax - bx) * 0.015f; bz += (az - bz) * 0.015f
                fx += ((ax - bx) - fx) * 0.25f; fz += ((az - bz) - fz) * 0.25f
                val nx = (-fx * 2.4f).coerceIn(-1f, 1f)
                val ny = (fz * 2.4f).coerceIn(-1f, 1f)
                if (abs(nx - PortalTilt.x) > 0.004f) PortalTilt.x = nx
                if (abs(ny - PortalTilt.y) > 0.004f) PortalTilt.y = ny
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        var registered = false
        fun listen(on: Boolean) {
            if (sm == null || sensor == null) return
            if (on && !registered) { registered = sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME) }
            else if (!on && registered) { sm.unregisterListener(listener); registered = false }
        }
        listen(ctx.getSystemService(PowerManager::class.java)?.isInteractive != false)
        val screen = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { listen(i.action == Intent.ACTION_SCREEN_ON) }
        }
        runCatching {
            ContextCompat.registerReceiver(
                ctx, screen,
                IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        onDispose {
            listen(false)
            runCatching { ctx.unregisterReceiver(screen) }
        }
    }
    val tr = rememberInfiniteTransition(label = "portal")
    val time by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(24_000, easing = LinearEasing)), label = "portalT")
    val rnd = remember(scene) { PortalRandom(scene + 3) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        if (w <= 1f || h <= 1f) return@Canvas
        val d = depth.coerceIn(0.2f, 2f)
        val sx = PortalTilt.x * d * h * 0.55f        // en yakın katmanın kayması (px)
        val sy = PortalTilt.y * d * h * 0.35f
        when (scene) {
            1 -> drawAquarium(w, h, sx, sy, time, rnd)
            2 -> drawCity(w, h, sx, sy, time, rnd)
            3 -> drawSky(w, h, sx, sy, time, rnd, System.currentTimeMillis())
            else -> drawSpace(w, h, sx, sy, time, rnd)
        }
        // Delik hissi: kenarlarda iç gölge
        drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.70f), 0.28f to Color.Transparent, 0.75f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f)))
        drawRect(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.65f), 0.14f to Color.Transparent, 0.86f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.65f)))
    }
}

/** Sahne için sabit tohumlu rastgele değerler (her karede aynı yerleşim). */
private class PortalRandom(seed: Int) {
    private var s = seed.toLong() * 1103515245L + 12345L
    private fun next(): Float { s = s * 6364136223846793005L + 1442695040888963407L; return ((s ushr 33) and 0xFFFFFFL).toFloat() / 16777215f }
    val a = FloatArray(160) { next() }
}

private const val TAU = (2.0 * PI).toFloat()

/** Bir katmanı derinliğine göre kaydırır (k: 0 = sabit, 1 = en yakın). */
private inline fun DrawScope.layer(k: Float, sx: Float, sy: Float, block: DrawScope.() -> Unit) {
    withTransform({ translate(sx * k, sy * k) }, block)
}

// ---------------------------------------------------------------- Uzay
private fun DrawScope.drawSpace(w: Float, h: Float, sx: Float, sy: Float, t: Float, r: PortalRandom) {
    drawRect(Brush.radialGradient(listOf(Color(0xFF1B1446), Color(0xFF05040F)), center = Offset(w * 0.4f, h * 0.5f), radius = w * 0.8f))
    layer(0.12f, sx, sy) {
        drawCircle(Brush.radialGradient(listOf(Color(0x667B3FE4), Color.Transparent), center = Offset(w * 0.25f, h * 0.4f), radius = h * 1.6f), radius = h * 1.6f, center = Offset(w * 0.25f, h * 0.4f))
        drawCircle(Brush.radialGradient(listOf(Color(0x5522C5D6), Color.Transparent), center = Offset(w * 0.8f, h * 0.7f), radius = h * 1.3f), radius = h * 1.3f, center = Offset(w * 0.8f, h * 0.7f))
    }
    layer(0.25f, sx, sy) {
        for (i in 0 until 60) {
            val x = (r.a[i] * 1.4f - 0.2f) * w
            val y = (r.a[i + 60] * 1.4f - 0.2f) * h
            val tw = 0.55f + 0.45f * sin(TAU * (t * (2f + r.a[i + 100] * 6f) + r.a[i + 30]))
            drawCircle(Color.White.copy(alpha = (0.25f + r.a[i + 90] * 0.5f) * tw), radius = h * (0.012f + r.a[i + 45] * 0.02f), center = Offset(x, y))
        }
    }
    layer(0.6f, sx, sy) {
        for (i in 0 until 14) {
            val x = (r.a[i + 120] * 1.4f - 0.2f) * w
            val y = (r.a[i + 134] * 1.2f - 0.1f) * h
            drawCircle(Color.White.copy(alpha = 0.85f), radius = h * 0.035f, center = Offset(x, y))
        }
    }
    layer(1f, sx, sy) {
        val c = Offset(w * 0.74f, h * 0.62f)
        val pr = h * 0.48f
        drawCircle(Brush.linearGradient(listOf(Color(0xFFFFB86B), Color(0xFFB3478F), Color(0xFF3A1D6E)), start = Offset(c.x - pr, c.y - pr), end = Offset(c.x + pr, c.y + pr)), radius = pr, center = c)
        // halka (gezegeni çevreleyen eğik elips)
        withTransform({ rotate(-14f, c) }) {
            drawOval(Color(0xAAFFE0B0), Offset(c.x - pr * 1.9f, c.y - pr * 0.32f), Size(pr * 3.8f, pr * 0.64f), style = Stroke(width = h * 0.05f))
        }
        // kayan yıldız
        val ph = (t * 3f) % 1f
        if (ph < 0.18f) {
            val p = ph / 0.18f
            val x0 = w * (0.1f + 0.5f * p); val y0 = h * (0.15f + 0.3f * p)
            drawLine(Color.White.copy(alpha = 1f - p), Offset(x0, y0), Offset(x0 - h * 0.6f, y0 - h * 0.35f), strokeWidth = h * 0.025f, cap = StrokeCap.Round)
        }
    }
}

// ---------------------------------------------------------------- Akvaryum
private fun DrawScope.drawFish(c: Offset, len: Float, color: Color, dir: Float) {
    withTransform({ scale(dir, 1f, c) }) {
        drawOval(color, Offset(c.x - len * 0.5f, c.y - len * 0.22f), Size(len * 0.8f, len * 0.44f))
        val tail = Path().apply {
            moveTo(c.x + len * 0.25f, c.y)
            lineTo(c.x + len * 0.55f, c.y - len * 0.22f)
            lineTo(c.x + len * 0.55f, c.y + len * 0.22f)
            close()
        }
        drawPath(tail, color)
        drawCircle(Color.Black.copy(alpha = 0.6f), radius = len * 0.045f, center = Offset(c.x - len * 0.32f, c.y - len * 0.04f))
    }
}

private fun DrawScope.drawAquarium(w: Float, h: Float, sx: Float, sy: Float, t: Float, r: PortalRandom) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF1C8FC4), Color(0xFF0B4F7A), Color(0xFF052238))))
    layer(0.15f, sx, sy) {
        // yukarıdan süzülen ışık huzmeleri
        for (i in 0 until 5) {
            val x = w * (r.a[i] * 1.2f - 0.1f)
            val sway = sin(TAU * (t * 2f + r.a[i + 5])) * h * 0.2f
            val p = Path().apply {
                moveTo(x - h * 0.15f, -h * 0.2f); lineTo(x + h * 0.15f, -h * 0.2f)
                lineTo(x + h * 0.6f + sway, h * 1.2f); lineTo(x + h * 0.1f + sway, h * 1.2f); close()
            }
            drawPath(p, Color.White.copy(alpha = 0.07f))
        }
    }
    layer(0.35f, sx, sy) {
        for (i in 0 until 6) {
            val speed = 0.6f + r.a[i + 10] * 1.2f
            val dir = if (r.a[i + 20] > 0.5f) 1f else -1f
            val p = (r.a[i + 30] + t * speed) % 1f
            val x = if (dir > 0f) w * (1.3f - p * 1.6f) else w * (-0.3f + p * 1.6f)
            val y = h * (0.2f + r.a[i + 40] * 0.6f) + sin(TAU * (t * 8f + r.a[i])) * h * 0.04f
            drawFish(Offset(x, y), h * 0.22f, Color(0xFF0A3550), dir)
        }
    }
    layer(0.6f, sx, sy) {
        for (i in 0 until 16) {
            val p = (r.a[i + 50] + t * (3f + r.a[i + 66] * 4f)) % 1f
            val x = w * (r.a[i + 82] * 1.2f - 0.1f) + sin(TAU * (p * 2f + r.a[i])) * h * 0.06f
            val y = h * 1.15f - p * h * 1.4f
            drawCircle(Color.White.copy(alpha = 0.55f), radius = h * (0.02f + r.a[i + 98] * 0.03f), center = Offset(x, y), style = Stroke(width = h * 0.012f))
        }
    }
    layer(1f, sx, sy) {
        // ön planda sallanan yosunlar
        for (i in 0 until 7) {
            val bx = w * (r.a[i + 114] * 1.3f - 0.15f)
            val sway = sin(TAU * (t * 3f + r.a[i + 121])) * h * 0.18f
            val p = Path().apply {
                moveTo(bx, h * 1.1f)
                quadraticBezierTo(bx + sway, h * 0.65f, bx + sway * 1.6f, h * (0.25f + r.a[i + 128] * 0.3f))
            }
            drawPath(p, Color(0xFF1E7A4A), style = Stroke(width = h * 0.07f, cap = StrokeCap.Round))
        }
        // turuncu balık
        val p = (t * 2.2f) % 1f
        val fy = h * 0.55f + sin(TAU * t * 9f) * h * 0.06f
        drawFish(Offset(w * (-0.2f + p * 1.4f), fy), h * 0.36f, Color(0xFFFF8A3D), -1f)
    }
}

// ---------------------------------------------------------------- Gece şehri
private fun DrawScope.drawCity(w: Float, h: Float, sx: Float, sy: Float, t: Float, r: PortalRandom) {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF0A0D2A), Color(0xFF26205A), Color(0xFF6B3765))))
    layer(0.1f, sx, sy) {
        val mc = Offset(w * 0.18f, h * 0.32f)
        drawCircle(Brush.radialGradient(listOf(Color(0x66FFF3C4), Color.Transparent), center = mc, radius = h * 0.9f), radius = h * 0.9f, center = mc)
        drawCircle(Color(0xFFFFF1C9), radius = h * 0.2f, center = mc)
        for (i in 0 until 30) {
            drawCircle(Color.White.copy(alpha = 0.25f + r.a[i] * 0.4f), radius = h * 0.012f,
                center = Offset((r.a[i + 30] * 1.3f - 0.15f) * w, r.a[i + 60] * h * 0.5f))
        }
    }
    fun buildings(k: Float, count: Int, base: Int, color: Color, minH: Float, maxH: Float, win: Color) = layer(k, sx, sy) {
        var x = -w * 0.2f
        var i = 0
        while (x < w * 1.2f && i < count) {
            val bw = h * (0.35f + r.a[(base + i) % 160] * 0.45f)
            val bh = h * (minH + r.a[(base + i + 7) % 160] * (maxH - minH))
            val top = h - bh
            drawRect(color, Offset(x, top), Size(bw, bh + h * 0.2f))
            // pencereler: bazıları yanar, ara sıra biri söner / yanar
            val cols = (bw / (h * 0.09f)).toInt().coerceAtLeast(1)
            val rows = (bh / (h * 0.11f)).toInt().coerceAtLeast(1)
            for (cx in 0 until cols) for (ry in 0 until rows) {
                val v = r.a[(base + i * 13 + cx * 7 + ry * 3) % 160]
                val flick = ((t * 40f + v * 10f).toInt() % 17) == 0
                if (v > 0.55f && !flick) {
                    drawRect(win, Offset(x + h * 0.04f + cx * h * 0.09f, top + h * 0.05f + ry * h * 0.11f), Size(h * 0.035f, h * 0.05f))
                }
            }
            x += bw + h * 0.04f
            i++
        }
    }
    buildings(0.3f, 30, 0, Color(0xFF1C1A40), 0.35f, 0.75f, Color(0xAAFFD27A))
    buildings(0.8f, 22, 40, Color(0xFF0B0A1C), 0.25f, 0.6f, Color(0xFFFFE09A))
}

// ---------------------------------------------------------------- Canlı gökyüzü (hava durumu yok; yalnızca saat ve tarih)
/**
 * Gün doğumu / batımı internet ya da konum kullanmadan, yılın gününe göre yaklaşık hesaplanır
 * (orta enlemler: yazın gün ~15 saat, kışın ~9 saat; öğle ~12:45). Ayın evresi gerçek takvimden hesaplanır.
 */
private fun lerpC(a: Color, b: Color, t: Float): Color = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

private fun DrawScope.drawSky(w: Float, h: Float, sx: Float, sy: Float, t: Float, r: PortalRandom, nowMs: Long) {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val doy = cal.get(java.util.Calendar.DAY_OF_YEAR)
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY) + cal.get(java.util.Calendar.MINUTE) / 60f + cal.get(java.util.Calendar.SECOND) / 3600f
    val dayLen = 12f + 3f * kotlin.math.cos(TAU * (doy - 172) / 365f)
    val noon = 12.75f
    val rise = noon - dayLen / 2f
    val set = noon + dayLen / 2f
    val isDay = hour in rise..set
    val dayFrac = ((hour - rise) / dayLen).coerceIn(0f, 1f)
    val alt = if (isDay) sin(PI.toFloat() * dayFrac) else 0f
    // Alacakaranlık: gün doğumuna / batımına 1 saatten yakın gece saatleri (1 = tam o an)
    val fromEvent = if (isDay) 0f else minOf(abs(hour - set).let { minOf(it, 24f - it) }, abs(hour - rise).let { minOf(it, 24f - it) })
    val twi = if (isDay) 0f else (1f - fromEvent).coerceIn(0f, 1f)

    val nightTop = Color(0xFF060A22); val nightBot = Color(0xFF151B45)
    val dawnTop = Color(0xFF33306E); val dawnBot = Color(0xFFF2895B)
    val dayTop = Color(0xFF2B78DE); val dayBot = Color(0xFFA6D4FF)
    val top: Color
    val bot: Color
    if (isDay) {
        val low = 1f - (alt / 0.35f).coerceIn(0f, 1f)
        top = lerpC(dayTop, dawnTop, low * 0.6f); bot = lerpC(dayBot, dawnBot, low)
    } else {
        top = lerpC(nightTop, dawnTop, twi); bot = lerpC(nightBot, dawnBot, twi)
    }
    drawRect(Brush.verticalGradient(listOf(top, bot)))

    // Yıldızlar: gece görünür, alacakaranlıkta solar
    val starA = if (isDay) 0f else (1f - twi)
    if (starA > 0.01f) layer(0.2f, sx, sy) {
        for (i in 0 until 50) {
            val tw = 0.6f + 0.4f * sin(TAU * (t * (2f + r.a[i + 100] * 5f) + r.a[i + 30]))
            drawCircle(Color.White.copy(alpha = starA * tw * (0.3f + r.a[i + 90] * 0.6f)), radius = h * (0.012f + r.a[i + 45] * 0.018f),
                center = Offset((r.a[i] * 1.3f - 0.15f) * w, r.a[i + 60] * h * 0.75f))
        }
    }
    layer(0.35f, sx, sy) {
        if (isDay) {
            // Güneş: doğudan (sol) batıya (sağ) yay çizer
            val c = Offset(w * (-0.05f + 1.1f * dayFrac), h * (0.95f - 0.75f * alt))
            val sr = h * 0.17f
            drawCircle(Brush.radialGradient(listOf(Color(0x88FFE7A0), Color.Transparent), center = c, radius = sr * 4f), radius = sr * 4f, center = c)
            drawCircle(lerpC(Color(0xFFFFF4D6), Color(0xFFFFB45E), 1f - alt), radius = sr, center = c)
        } else {
            // Ay: gece boyunca soldan sağa geçer; evresi gerçek takvimden
            val nightLen = 24f - dayLen
            val sinceSet = ((hour - set) + 24f) % 24f
            val nf = (sinceSet / nightLen).coerceIn(0f, 1f)
            val c = Offset(w * (0.05f + 0.9f * nf), h * (0.85f - 0.55f * sin(PI.toFloat() * nf)))
            val mr = h * 0.16f
            drawCircle(Brush.radialGradient(listOf(Color(0x55FFF6D8), Color.Transparent), center = c, radius = mr * 3f), radius = mr * 3f, center = c)
            drawCircle(Color(0xFFF4EFD9), radius = mr, center = c)
            val p = moonPhase(nowMs)        // 0 yeni ay, 0.5 dolunay
            val dx = if (p < 0.5f) -2f * mr * (p / 0.5f) else 2f * mr * ((1f - p) / 0.5f)
            val moon = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, mr)) }
            clipPath(moon) {
                drawCircle(Color(0xE60A0D24), radius = mr * 1.02f, center = Offset(c.x + dx, c.y))
            }
        }
    }
    // Bulutlar: gündüz beyaz, alacakaranlıkta pembe, gece koyu ve soluk
    val cloudCol = when {
        isDay -> lerpC(Color.White, Color(0xFFFFC2A8), 1f - (alt / 0.35f).coerceIn(0f, 1f))
        else -> lerpC(Color(0xFF2A3060), Color(0xFFE59A8A), twi)
    }
    layer(0.6f, sx, sy) {
        for (i in 0 until 4) {
            val x = ((r.a[i + 140] + t * (0.6f + r.a[i + 144] * 0.6f)) % 1.4f - 0.2f) * w
            val y = h * (0.2f + r.a[i + 148] * 0.35f)
            val cw = h * (0.9f + r.a[i + 152] * 0.8f)
            val a = if (isDay) 0.85f else 0.55f
            drawOval(cloudCol.copy(alpha = a), Offset(x, y), Size(cw, h * 0.22f))
            drawOval(cloudCol.copy(alpha = a), Offset(x + cw * 0.2f, y - h * 0.1f), Size(cw * 0.55f, h * 0.24f))
        }
    }
    // Ön planda tepeler: gündüz yeşilimsi, gece koyu
    val light = if (isDay) (0.35f + alt * 0.65f) else twi * 0.3f
    layer(1f, sx, sy) {
        val hill = Path().apply {
            moveTo(-w * 0.2f, h * 1.2f)
            var x = -w * 0.2f
            val step = h * 0.15f
            while (x <= w * 1.2f + step) {
                lineTo(x, h * 0.86f - h * 0.08f * sin(x / w * 7f) - h * 0.05f * sin(x / w * 17f + 2f)); x += step
            }
            lineTo(w * 1.2f, h * 1.2f); close()
        }
        drawPath(hill, lerpC(Color(0xFF0A0D1C), Color(0xFF3E6A42), light))
    }
}

/** Ayın evresi (0 = yeni ay, 0.5 = dolunay, 1 = tekrar yeni ay). Bilinen bir yeni aydan (6 Ocak 2000) sinodik aya göre. */
private fun moonPhase(nowMs: Long): Float {
    val known = 947_182_440_000L          // 2000-01-06 18:14 UTC yeni ay
    val synodic = 29.530588853 * 86_400_000.0
    val p = ((nowMs - known) / synodic) % 1.0
    return (if (p < 0) p + 1.0 else p).toFloat()
}
