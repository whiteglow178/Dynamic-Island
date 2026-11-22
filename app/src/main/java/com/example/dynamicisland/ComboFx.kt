package com.example.dynamicisland

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * BİRLEŞİK ÖZELLİKLER (Ana Sayfa > Ada olayları ile / Birleşik efektler): mevcut parçaların birbirine bağlandığı efektler.
 * Eğim / sallama sensörü, söz tepkisi, takvim olayları, genişletilmiş "şarkının havası", dağılan kapak,
 * pil azken kıvılcım ve silinen bildirimin parçalanması burada.
 */

// ================================================================ Eğim ve sallama
/** Telefonun yana eğimi (-1..1, + = sağ taraf aşağıda) ve son sallama. Yalnızca ada olayları açıkken dinlenir. */
object FxMotion {
    @Volatile var tiltX = 0f
    @Volatile private var shakeAt = 0L
    @Volatile private var consumedAt = 0L

    fun markShake(now: Long) { shakeAt = now }

    /** Yeni bir sallama olduysa bir kez true döner. */
    fun consumeShake(): Boolean {
        val s = shakeAt
        if (s == 0L || s == consumedAt) return false
        consumedAt = s
        return true
    }
}

/** İvme ölçeri dinler: yerçekimi yönünden eğim, ani ivmeden sallama. Bileşen ekrandan kalkınca bırakılır. */
@Composable
fun FxMotionSensor() {
    val ctx = LocalContext.current
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var gx = 0f; var gy = 9.81f; var gz = 0f
        var jerks = 0
        var firstJerk = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val ax = e.values[0]; val ay = e.values[1]; val az = e.values[2]
                gx += (ax - gx) * 0.12f; gy += (ay - gy) * 0.12f; gz += (az - gz) * 0.12f
                // sağ kenar aşağıdayken x okuması eksiye gider: "yokuş aşağı" yönü +x
                FxMotion.tiltX = (-gx / 9.81f).coerceIn(-1f, 1f)
                val lx = ax - gx; val ly = ay - gy; val lz = az - gz
                val lin = sqrt(lx * lx + ly * ly + lz * lz)
                if (lin > 11f) {
                    val now = SystemClock.uptimeMillis()
                    if (now - firstJerk > 700L) { firstJerk = now; jerks = 0 }
                    jerks++
                    if (jerks >= 2) { FxMotion.markShake(now); jerks = 0; firstJerk = 0L }   // iki sert hareket = sallama
                }
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        if (sm != null && sensor != null) sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose {
            sm?.unregisterListener(listener)
            FxMotion.tiltX = 0f
        }
    }
}

// ================================================================ Söz tepkisi
/** Şarkı sözü satırı değişince yazılır; ada olayları okur. */
object FxCues {
    @Volatile var seq = 0
    @Volatile var chorus = false

    fun fire(isChorus: Boolean) { chorus = isChorus; seq++ }
}

private fun normLine(s: String) = s.lowercase().filter { it.isLetterOrDigit() || it == ' ' }.trim()

/**
 * Senkronlu sözde satır değişince [FxCues]'i tetikler. Şarkıda iki kez ya da daha çok geçen (yeterince uzun) satır
 * nakarat sayılır.
 */
@Composable
internal fun LyricCue(state: LyricsState.Ready, index: Int) {
    val chorusSet = remember(state) {
        state.lines.map { normLine(it.text) }.filter { it.length >= 8 }
            .groupingBy { it }.eachCount().filterValues { it >= 2 }.keys
    }
    LaunchedEffect(state, index) {
        if (index < 0 || !state.synced) return@LaunchedEffect
        val t = state.lines.getOrNull(index)?.text ?: return@LaunchedEffect
        if (t.isBlank()) return@LaunchedEffect
        FxCues.fire(normLine(t) in chorusSet)
    }
}

// ================================================================ Takvim olayları
private fun moonPhaseAt(nowMs: Long): Float {
    val known = 947_182_440_000L          // 2000-01-06 18:14 UTC yeni ay
    val synodic = 29.530588853 * 86_400_000.0
    val p = ((nowMs - known) / synodic) % 1.0
    return (if (p < 0) p + 1.0 else p).toFloat()
}

/** Özel gün / saat için ada olayı (-1 = yok). */
internal fun calendarWeather(nowMs: Long): Int {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = nowMs }
    val month = cal.get(java.util.Calendar.MONTH) + 1
    val day = cal.get(java.util.Calendar.DAY_OF_MONTH)
    val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
    // Yılbaşı gecesi
    if ((month == 12 && day == 31 && hour >= 20) || (month == 1 && day == 1 && hour < 3)) return FXW_FIREWORKS
    // Resmi bayram akşamları: 23 Nisan, 19 Mayıs, 15 Temmuz, 30 Ağustos, 29 Ekim
    val holidays = setOf(4 * 100 + 23, 5 * 100 + 19, 7 * 100 + 15, 8 * 100 + 30, 10 * 100 + 29)
    if (month * 100 + day in holidays && hour >= 19) return FXW_FIREWORKS
    // Dolunay gecesi (±1 gün)
    val night = hour >= 21 || hour < 5
    if (night && abs(moonPhaseAt(nowMs) - 0.5f) < 0.035f) return FXW_AURORA
    // Gece yarısından sonra
    if (hour in 0..3) return FXW_FIREFLY
    return -1
}

// ================================================================ Şarkıya göre olay (genişletilmiş)
/**
 * Kapak rengine göre olay; eski dört efektin yanında portal olaylarını da seçer.
 * Bembeyaz → tipi, beyazımsı → kar, simsiyah → meteor, koyu → yağmur, kum / bej → kum fırtınası,
 * koyu yeşil → ateşböceği, canlı yeşil → asit, turkuaz → sualtı, gece mavisi → meteor, mavi → sualtı,
 * mor → kutup ışığı, koyu pembe → elektrik, parlak pembe / parlak sıcak → havai fişek, diğer sıcak → güneş.
 */
internal fun moodWeatherExtended(argb: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
    return when {
        s < 0.18f && v > 0.92f -> FXW_BLIZZARD
        s < 0.18f && v > 0.70f -> 2
        v < 0.12f -> FXW_METEOR
        v < 0.22f -> 1
        s < 0.25f -> 1
        h in 25f..50f && s < 0.75f && v in 0.45f..0.9f -> FXW_SAND
        h in 75f..160f && s > 0.45f -> if (v < 0.45f) FXW_FIREFLY else 3
        h in 160f..200f -> FXW_SEA
        h in 200f..250f -> if (v < 0.45f) FXW_METEOR else FXW_SEA
        h in 250f..300f -> FXW_AURORA
        h in 300f..345f -> if (v > 0.6f) FXW_FIREWORKS else FXW_ELECTRIC
        v > 0.75f && s > 0.6f -> FXW_FIREWORKS
        else -> 4
    }
}

// ================================================================ Dağılan kapak
/** Ada arayüzü yazar, kapak bileşeni okur. */
object FxPrefs {
    var coverDissolve by mutableStateOf(false)
}

private fun hrnd(i: Int, j: Int, k: Int): Float {
    val x = sin(i * 12.9898 + j * 78.233 + k * 37.719) * 43758.5453
    return (x - kotlin.math.floor(x)).toFloat()
}

/**
 * Kapak değişince eski kapak soldan sağa doğru toz gibi parçalara ayrılıp savrulur; yeni kapak dağınık
 * parçalardan dönerek birleşir. Görüntü kırpılarak (Crop) kutuyu doldurur.
 */
@Composable
internal fun DissolveArt(art: ImageBitmap?, modifier: Modifier = Modifier) {
    var cur by remember { mutableStateOf<ImageBitmap?>(null) }
    var prev by remember { mutableStateOf<ImageBitmap?>(null) }
    val curSig = remember { arrayOfNulls<IntArray>(1) }
    val prog = remember { Animatable(1f) }
    LaunchedEffect(art) {
        if (art == null || art === cur) return@LaunchedEffect
        val c0 = cur
        if (c0 == null) { cur = art; curSig[0] = artSig(art); return@LaunchedEffect }
        // Medya oturumu aynı kapağı sık sık yeni bir görüntü nesnesi olarak gönderir (oynatma durumu, süre güncellemesi...).
        // Yalnızca görüntünün İÇERİĞİ değiştiyse (gerçekten yeni şarkının kapağı) animasyon oynar; aynı kapaksa sessizce değiştirilir.
        val sig = artSig(art)
        if (sameArt(curSig[0], sig, c0, art)) { cur = art; curSig[0] = sig; return@LaunchedEffect }
        prev = c0
        cur = art
        curSig[0] = sig
        prog.snapTo(0f)
        prog.animateTo(1f, tween(1150, easing = LinearEasing))
        prev = null
    }
    val c = cur
    if (c == null) {
        Box(modifier.background(Pal.c("art_placeholder_bg")), contentAlignment = Alignment.Center) {
            Text("♪", color = Pal.c("art_placeholder_note"), fontSize = 22.sp)
        }
        return
    }
    Canvas(modifier) {
        val t = prog.value
        val p = prev
        if (p == null || t >= 1f) { drawCropped(c, 1f); return@Canvas }
        val n = 12
        val w = size.width; val h = size.height
        val tw = w / n; val th = h / n
        // yeni kapak: parçalar dağınık yerlerden dönerek yerine oturur
        for (i in 0 until n) for (j in 0 until n) {
            val delay = 0.32f + i.toFloat() / n * 0.38f + hrnd(i, j, 1) * 0.1f
            val v = ((t - delay) / 0.3f).coerceIn(0f, 1f)
            if (v <= 0f) continue
            val e = 1f - (1f - v) * (1f - v) * (1f - v)
            val ox = (hrnd(i, j, 2) - 0.5f) * w * 0.9f * (1f - e)
            val oy = (hrnd(i, j, 3) - 0.5f) * h * 0.9f * (1f - e)
            val rot = (hrnd(i, j, 4) - 0.5f) * 220f * (1f - e)
            drawTile(c, i, j, n, tw, th, ox, oy, rot, 0.35f + 0.65f * e, v)
        }
        // eski kapak: soldan sağa toz gibi dağılır (sağa ve yukarı savrulur, küçülür, söner)
        for (i in 0 until n) for (j in 0 until n) {
            val delay = i.toFloat() / n * 0.42f + hrnd(i, j, 5) * 0.12f
            val u = ((t - delay) / 0.4f).coerceIn(0f, 1f)
            if (u >= 1f) continue
            val ox = u * u * w * (0.35f + 0.5f * hrnd(i, j, 6))
            val oy = -u * h * (0.15f + 0.45f * hrnd(i, j, 7)) + u * u * h * 0.1f
            val rot = (hrnd(i, j, 8) - 0.5f) * 180f * u
            drawTile(p, i, j, n, tw, th, ox, oy, rot, 1f - 0.7f * u, 1f - u)
            // tozun ince taneleri
            if (u > 0.05f) {
                val k = (1f - u)
                for (q in 0 until 2) {
                    val dx = ox * (1.3f + q * 0.4f) + (i + 0.5f) * tw
                    val dy = oy * (1.2f + q * 0.5f) + (j + 0.5f) * th
                    drawCircle(Color.White.copy(alpha = 0.35f * k), max(0.8f, tw * 0.08f), Offset(dx, dy))
                }
            }
        }
    }
}

/**
 * Kapağın küçük parmak izi: 5x5 ızgaradaki noktaların rengi (görüntü boyutundan bağımsız, oransal konumlar).
 * Donanım belleğindeki görüntü okunamadığı için gerekirse önce kopyalanır. Okunamazsa null.
 */
private fun artSig(img: ImageBitmap): IntArray? = runCatching {
    val b = img.asAndroidBitmap()
    val src = if (b.config == android.graphics.Bitmap.Config.HARDWARE) b.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else b
    val out = IntArray(25)
    for (j in 0 until 5) for (i in 0 until 5) {
        val x = ((i + 0.5f) / 5f * src.width).toInt().coerceIn(0, src.width - 1)
        val y = ((j + 0.5f) / 5f * src.height).toInt().coerceIn(0, src.height - 1)
        out[j * 5 + i] = src.getPixel(x, y)
    }
    if (src !== b) src.recycle()
    out
}.getOrNull()

/**
 * İki kapak aynı resim mi? Örnek noktaların ortalama renk farkı küçükse aynıdır (farklı çözünürlükte ya da
 * yeniden sıkıştırılmış aynı kapak da aynı sayılır). Parmak izi alınamadıysa boyutlar aynıysa aynı kabul edilir
 * (gereksiz yere oynamaktansa oynamamak yeğlenir).
 */
private fun sameArt(a: IntArray?, b: IntArray?, ia: ImageBitmap, ib: ImageBitmap): Boolean {
    if (a == null || b == null) return ia.width == ib.width && ia.height == ib.height
    var sum = 0
    for (k in 0 until 25) {
        val p = a[k]; val q = b[k]
        sum += abs(((p shr 16) and 0xFF) - ((q shr 16) and 0xFF)) +
            abs(((p shr 8) and 0xFF) - ((q shr 8) and 0xFF)) +
            abs((p and 0xFF) - (q and 0xFF))
    }
    return sum / (25f * 3f) < 22f
}

/** Görüntünün kutuyu dolduracak şekilde kırpılmış kaynak bölgesi: (sx, sy, sw, sh). */
private fun cropOf(img: ImageBitmap, w: Float, h: Float): FloatArray {
    val iw = img.width.toFloat(); val ih = img.height.toFloat()
    val sc = max(w / iw, h / ih)
    val sw = w / sc; val sh = h / sc
    return floatArrayOf((iw - sw) / 2f, (ih - sh) / 2f, sw, sh)
}

private fun DrawScope.drawCropped(img: ImageBitmap, alpha: Float) {
    val cr = cropOf(img, size.width, size.height)
    drawImage(
        img,
        srcOffset = IntOffset(cr[0].roundToInt(), cr[1].roundToInt()),
        srcSize = IntSize(cr[2].roundToInt().coerceAtLeast(1), cr[3].roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset.Zero,
        dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1)),
        alpha = alpha,
    )
}

private fun DrawScope.drawTile(
    img: ImageBitmap, i: Int, j: Int, n: Int, tw: Float, th: Float,
    ox: Float, oy: Float, rot: Float, scale: Float, alpha: Float,
) {
    val cr = cropOf(img, size.width, size.height)
    val stw = cr[2] / n; val sth = cr[3] / n
    val dx = i * tw + ox; val dy = j * th + oy
    val center = Offset(dx + tw / 2f, dy + th / 2f)
    withTransform({
        rotate(rot, center)
        scale(scale, scale, center)
    }) {
        drawImage(
            img,
            srcOffset = IntOffset((cr[0] + i * stw).roundToInt(), (cr[1] + j * sth).roundToInt()),
            srcSize = IntSize(stw.roundToInt().coerceAtLeast(1), sth.roundToInt().coerceAtLeast(1)),
            dstOffset = IntOffset(dx.roundToInt(), dy.roundToInt()),
            dstSize = IntSize((tw + 1f).roundToInt(), (th + 1f).roundToInt()),
            alpha = alpha.coerceIn(0f, 1f),
        )
    }
}

// ================================================================ Pil azken kıvılcım
/**
 * Pil %15'in altında ve şarjda değilken boştaki adanın alt / üst kenarında 3–7 saniyede bir kısa elektrik
 * çatırtısı. Pil durumu 30 sn'de bir okunur; kıvılcım yokken kare çizilmez.
 */
@Composable
internal fun LowBattSparks(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var low by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            val bm = ctx.getSystemService(BatteryManager::class.java)
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            val chg = bm?.isCharging ?: false
            low = pct in 1..14 && !chg
            delay(30_000L)
        }
    }
    if (!low) return
    var burstAt by remember { mutableLongStateOf(0L) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val rnd = java.util.Random()
        while (true) {
            delay(3_000L + rnd.nextInt(4_000))
            burstAt = SystemClock.uptimeMillis()
            while (SystemClock.uptimeMillis() - burstAt < 420L) withFrameNanos { frame = it }
            frame = 0L
        }
    }
    Canvas(modifier) {
        @Suppress("UNUSED_VARIABLE") val f = frame
        val age = SystemClock.uptimeMillis() - burstAt
        if (burstAt == 0L || age > 420L) return@Canvas
        val k = 1f - age / 420f
        val rnd = java.util.Random(frame)
        val w = size.width; val h = size.height
        val d = density
        // kenar boyunca titreyen ark
        repeat(2) { side ->
            val y0 = if (side == 0) h - 1.5f * d else 1.5f * d
            val x0 = w * (0.18f + rnd.nextFloat() * 0.3f)
            val len = w * (0.15f + rnd.nextFloat() * 0.25f)
            val path = Path()
            path.moveTo(x0, y0)
            val segs = 8
            for (q in 1..segs) {
                path.lineTo(x0 + len * q / segs, y0 + (rnd.nextFloat() - 0.5f) * 3.5f * d)
            }
            drawPath(path, Color(0xFF52D6FF).copy(alpha = 0.5f * k), style = Stroke(3.2f * d, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
            drawPath(path, Color.White.copy(alpha = k), style = Stroke(1f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
            repeat(4) {
                val sx = x0 + rnd.nextFloat() * len
                drawCircle(Color(0xFFFFE08A).copy(alpha = k), (0.6f + rnd.nextFloat()) * d, Offset(sx, y0 + (rnd.nextFloat() - 0.5f) * 6f * d))
            }
        }
    }
}

// ================================================================ Silinen bildirim dökülsün
/** Harflerin dönüşeceği malzeme: 0 cam kırığı (varsayılan), 1 kar, 2 kum, 3 kor. */
internal fun shatterStyleFor(fxWeather: Int): Int = when (fxWeather) {
    2, FXW_BLIZZARD -> 1
    FXW_SAND -> 2
    FXW_METEOR, FXW_FIREWORKS -> 3
    else -> 0
}

private class ShLetter(
    val layout: TextLayoutResult, val homeX: Float, val homeY: Float, val delay: Float, val spin: Float, val seed: Float,
)

private class ShBit(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val max: Float, val size: Float)

/**
 * Bildirim silinince yazısı adanın altında belirir, harfler çatlayıp ayrılır ve girdap çizerek adaya çekilir;
 * yolda seçili ada olayının malzemesine dönüşen kırıntılar saçar. Son harf girince ada yutkunur.
 */
@Composable
internal fun NotifShatterFx(a: PlayAnchor) {
    val bus = LocalPlayBus.current
    val tm = rememberTextMeasurer()
    val letters = remember { ArrayList<ShLetter>() }
    val bits = remember { ArrayList<ShBit>() }
    var startAt by remember { mutableLongStateOf(0L) }
    var style by remember { mutableStateOf(0) }
    var frame by remember { mutableLongStateOf(0L) }
    val d = LocalContext.current.resources.displayMetrics.density
    LaunchedEffect(Unit) {
        bus.events.collect { e ->
            if (e !is PlayEvent.Shatter || e.text.isBlank()) return@collect
            val text = e.text.replace('\n', ' ').trim().take(34)
            val st = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            val lays = text.map { ch -> tm.measure(ch.toString(), st) }
            val total = lays.sumOf { it.size.width }.toFloat()
            var x = a.liveCx() - total / 2f
            val y = a.edgeY() + a.dir() * 56f * d
            letters.clear(); bits.clear()
            val rnd = java.util.Random()
            lays.forEachIndexed { i, l ->
                val mid = abs(i - lays.size / 2f) / (lays.size / 2f + 1f)
                letters.add(ShLetter(l, x, y - l.size.height / 2f, 0.25f + mid * 0.45f + rnd.nextFloat() * 0.12f,
                    (rnd.nextFloat() - 0.5f) * 720f, rnd.nextFloat()))
                x += l.size.width
            }
            style = e.style
            startAt = SystemClock.uptimeMillis()
        }
    }
    LaunchedEffect(startAt) {
        if (startAt == 0L) return@LaunchedEffect
        var last = 0L
        var eaten = false
        while (true) {
            val nowN = withFrameNanos { it }
            val dt = if (last == 0L) 0.016f else ((nowN - last) / 1e9f).coerceIn(0f, 0.05f)
            last = nowN
            val t = (SystemClock.uptimeMillis() - startAt) / 1000f
            // kırıntılar
            var i = bits.size - 1
            while (i >= 0) {
                val b = bits[i]
                val g = when (style) { 1 -> 60f; 2 -> 500f; 3 -> 140f; else -> 700f } * d
                b.vy += g * dt * a.dir()
                if (style == 1) b.vx += sin(t * 6f + b.size) * 30f * d * dt
                b.x += b.vx * dt; b.y += b.vy * dt
                b.life -= dt
                if (b.life <= 0f) bits.removeAt(i)
                i--
            }
            if (!eaten && letters.isNotEmpty() && letters.all { t > it.delay + 0.9f }) {
                eaten = true
                bus.emit(PlayEvent.Eaten)
            }
            frame = nowN
            if (eaten && bits.isEmpty()) break
            if (t > 4f) break
        }
        letters.clear(); bits.clear(); startAt = 0L; frame = 0L
    }
    if (startAt == 0L) return
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val f = frame
        val t = (SystemClock.uptimeMillis() - startAt) / 1000f
        val target = Offset(a.liveCx(), a.liveCy())
        val rnd = java.util.Random(f)
        for (l in letters) {
            val appear = (t / 0.15f).coerceIn(0f, 1f)
            val u = ((t - l.delay) / 0.9f).coerceIn(0f, 1f)
            if (u >= 1f) continue
            val w = l.layout.size.width.toFloat(); val hh = l.layout.size.height.toFloat()
            val home = Offset(l.homeX + w / 2f, l.homeY + hh / 2f)
            // çatlama: yola çıkmadan önce titreyip birbirinden ayrılır
            val crack = ((t - l.delay + 0.2f) / 0.2f).coerceIn(0f, 1f) * (1f - u)
            val jx = (rnd.nextFloat() - 0.5f) * 2.5f * d * crack
            val jy = (rnd.nextFloat() - 0.5f) * 2.5f * d * crack
            // girdap: yarıçap küçülürken açı artar
            val rel = home - target
            val r0 = rel.getDistance()
            val a0 = kotlin.math.atan2(rel.y, rel.x)
            val e = u * u * (3f - 2f * u)
            val ang = a0 + e * (if (l.seed > 0.5f) 2.6f else -2.6f)
            val rr = r0 * (1f - e)
            val pos = Offset(target.x + cos(ang) * rr + jx, target.y + sin(ang) * rr + jy)
            val sc = 1f - 0.85f * e
            val al = appear * (1f - ((u - 0.8f) / 0.2f).coerceIn(0f, 1f))
            withTransform({
                rotate(l.spin * e, pos)
                scale(sc, sc, pos)
            }) {
                val tint = when (style) {
                    1 -> Color(0xFFE6F4FF)
                    2 -> Color(0xFFF0D3A0)
                    3 -> androidx.compose.ui.graphics.lerp(Color.White, Color(0xFFFF8A3D), e)
                    else -> Color.White
                }
                if (style == 3) drawCircle(Color(0xFFFF8A3D).copy(alpha = 0.3f * al * e), w, pos, blendMode = BlendMode.Plus)
                drawText(l.layout, color = tint, topLeft = Offset(pos.x - w / 2f, pos.y - hh / 2f), alpha = al)
            }
            // yolda kırıntı saçar (çizim sırasında eklenir; güncelleme döngüsü taşır)
            if (u in 0.02f..0.95f && rnd.nextFloat() < 0.35f && bits.size < 220) {
                val sp = (20f + rnd.nextFloat() * 60f) * d
                val ba = rnd.nextFloat() * 2f * PI.toFloat()
                bits.add(ShBit(pos.x, pos.y, cos(ba) * sp, sin(ba) * sp - (if (style == 3) 30f * d else 0f),
                    0.5f + rnd.nextFloat() * 0.6f, 1.1f, (0.6f + rnd.nextFloat() * 1.2f) * d))
            }
        }
        for (b in bits) {
            val k = (b.life / b.max).coerceIn(0f, 1f)
            val c = Offset(b.x, b.y)
            when (style) {
                1 -> drawCircle(Color.White.copy(alpha = 0.85f * k), b.size, c)
                2 -> drawLine(Color(0xFFD2A35C).copy(alpha = 0.85f * k), c, Offset(c.x - b.vx * 0.02f, c.y - b.vy * 0.02f), b.size, StrokeCap.Round)
                3 -> {
                    drawCircle(Color(0xFFFFB25C).copy(alpha = 0.35f * k), b.size * 2.5f, c, blendMode = BlendMode.Plus)
                    drawCircle(Color(0xFFFFE1A8).copy(alpha = k), b.size * 0.8f, c)
                }
                else -> drawLine(Color.White.copy(alpha = 0.8f * k), c, Offset(c.x + b.size * 2f, c.y - b.size), b.size * 0.6f, StrokeCap.Round)
            }
        }
    }
}

/** Yüzey eğimini ve sallamayı yığınlara uygulamak için kullanılan yardımcı (IslandFxScenes). */
internal fun tiltBias(tilt: Float): Float = if (abs(tilt) < 0.08f) 0f else (tilt - 0.08f * kotlin.math.sign(tilt))
