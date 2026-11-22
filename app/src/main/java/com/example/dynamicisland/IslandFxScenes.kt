package com.example.dynamicisland

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * ADA OLAYLARI – Portal sahnelerinden uyarlanan olaylar (5 ve sonrası).
 * Hepsi genişletilmiş müzik adasındaki öğelerle (kapak, yazılar, düğmeler, çubuk) fiziksel olarak etkileşir:
 *  5 Meteor yağmuru   : tek bir radyanttan yayılır; çarptığı öğede kor + yanık izi bırakır, öğeyi sarsar
 *  6 Sualtı           : kabarcıklar öğelerin altına takılır, birleşir, kenardan kaçar; balıklar öğelerden kaçınır
 *  7 Havai fişek      : roket balistiği, farklı patlama türleri, duman; düşen kıvılcımlar öğelerin üstünde söner
 *  8 Ateşböcekleri    : öğelere konup kalkar; yanıp sönmeleri komşularıyla zamanla eşzamanlanır
 *  9 Kum fırtınası    : rüzgâr hamleleri; kum öğelerin üstünde yığılma açısıyla birikir, rüzgâr yönünde kayıp dökülür
 * 10 Kutup ışığı      : yüksekliğe göre renk değiştiren ışın perdeleri; öğelerin üst kenarı ışığı yansıtır; buz kristalleri
 * 11 Elektrik         : öğeler arasında dallanan, titreyen arklar; kıvılcım ve neon parlama
 * 12 Tipi             : derinlikli, rüzgârlı kar; öğelerin üstünde rüzgâr yönüne kayan kar kümeleri
 */

const val FXW_SUN = 4
const val FXW_METEOR = 5
const val FXW_SEA = 6
const val FXW_FIREWORKS = 7
const val FXW_FIREFLY = 8
const val FXW_SAND = 9
const val FXW_AURORA = 10
const val FXW_ELECTRIC = 11
const val FXW_BLIZZARD = 12

/** Ayarlar ekranındaki seçenekler: (fxWeather değeri, ad). */
val FX_WEATHER_OPTIONS: List<Pair<Int, String>> = listOf(
    0 to "Kapalı", 1 to "Yağmur", 2 to "Kar", 3 to "Asit", FXW_SUN to "Güneş",
    FXW_METEOR to "Meteor", FXW_SEA to "Sualtı", FXW_FIREWORKS to "Havai fişek", FXW_FIREFLY to "Ateşböceği",
    FXW_SAND to "Kum fırtınası", FXW_AURORA to "Kutup ışığı", FXW_ELECTRIC to "Elektrik", FXW_BLIZZARD to "Tipi",
)

/** Meteorun öğe üstünde bıraktığı kor / yanık izi (öğeye göre oransal konum). */
class FxScorch(val rx: Float, val ry: Float, val r: Float, val born: Long)

// ---------------------------------------------------------------- Parçacık türleri
private const val S_METEOR = 0
private const val S_EMBER = 1
private const val S_SPARK = 2
private const val S_BUBBLE = 3
private const val S_BUBBLE_STUCK = 4
private const val S_MOTE = 5
private const val S_ROCKET = 6
private const val S_STAR = 7
private const val S_SMOKE = 8
private const val S_LANDED = 9
private const val S_GRAIN = 10
private const val S_FALL = 11
private const val S_DUST = 12
private const val S_FLAKE = 13
private const val S_GLITTER = 14

private const val PILE_SAND = 1
private const val PILE_SNOW = 2
private const val PILE_BINS = 28
private const val SCORCH_LIFE = 7f
private const val MAX_PS = 650          // aynı anda en çok parçacık (akıcılık için)

private const val FX_TAU = (2.0 * PI).toFloat()

private class SP(var kind: Int, var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, var size: Float) {
    var max = life
    var px = x
    var py = y
    var color: Color = Color.White
    var tid: String? = null
    var ox = 0f
    var z = 1f
    var a = 0f
    var b = 0f
}

private class FxFlash(val x: Float, val y: Float, val r: Float, val max: Float, val color: Color, val ring: Boolean) {
    var life = max
}

/** Balık ya da ateşböceği. */
private class Swimmer(var x: Float, var y: Float, val size: Float, val kind: Int, val seed: Float) {
    var vx = 0f
    var vy = 0f
    var heading = 0f
    var speed = 0f
    var phase = 0f
    var period = 2f
    var timer = 0f
    var state = 0          // ateşböceği: 0 uçuyor, 1 konmaya gidiyor, 2 konmuş
    var tid: String? = null
    var ox = 0f
}

private class ZapArc(val a: Offset, val b: Offset, val max: Float, val small: Boolean, val ta: String?, val tb: String?) {
    var life = max
    var regen = 0f
    var bright = 1f
    var pts: List<Offset> = emptyList()
    var branches: List<List<Offset>> = emptyList()
}

/** Yumuşak, tekrarsız görünen 1B gürültü (-1..1). */
private fun fxNoise(x: Float): Float = 0.5f * sin(x) + 0.3f * sin(x * 2.13f + 1.7f) + 0.2f * sin(x * 4.77f + 4.1f)

/** Ateşböceği ışık eğrisi: kısa, yumuşak bir parlama, sonra karanlık. */
private fun fireflyLight(phase: Float): Float =
    if (phase < 0.16f) sin(PI.toFloat() * phase / 0.16f).let { it * it } else 0f

internal class FxScenes {
    private val rnd = java.util.Random()
    private fun f(): Float = rnd.nextFloat()
    private val seeds = FloatArray(256) { rnd.nextFloat() }

    private val ps = ArrayList<SP>()
    private val flashes = ArrayList<FxFlash>()
    private val swimmers = ArrayList<Swimmer>()
    private val arcs = ArrayList<ZapArc>()
    private var scene = -1
    private var time = 0f
    private var acc = 0f
    private var nextEvent = 0f
    private var gust = 0.6f
    private var flash = 0f
    private var flashColor = Color.White
    private var inten = 0.6f
    private var nowMs = 0L

    fun clear() {
        ps.clear(); flashes.clear(); swimmers.clear(); arcs.clear()
        scene = -1; acc = 0f; nextEvent = 0f; flash = 0f; time = 0f
    }

    /** Portal olayı seçili değilken: öğelerdeki iz / yığın / sarsıntıyı temizler. */
    fun idle(targets: Collection<FxTarget>) {
        if (scene == -1) return
        for (t in targets) clearExtras(t)
        clear()
    }

    private fun clearExtras(t: FxTarget) {
        t.pile = null; t.pileKind = 0; t.scorches.clear(); t.glow = 0f
        t.shake = 0f; t.shakeX = 0f; t.shakeY = 0f
    }

    /** Noktada gerçek içerik (yazı, simge, çubuk, kapak) olan öğe; boş alan çarpışmaz. */
    private fun solidAt(targets: Collection<FxTarget>, x: Float, y: Float): FxTarget? {
        for (t in targets) {
            if (t.gone) continue
            if (t.solid(x, y)) return t
        }
        return null
    }

    /** İçeriğin sıkı sınırına (pay ile) giren öğe: balıkların kaçınması için. */
    private fun tightAt(targets: Collection<FxTarget>, x: Float, y: Float, pad: Float): FxTarget? {
        for (t in targets) {
            if (t.gone) continue
            val r = t.tight
            if (r.width > 0f && x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) return t
        }
        return null
    }

    private var hx = 0f
    private var hy = 0f

    /**
     * Önceki konumdan yeni konuma giden yolu ~2 dp adımlarla tarar (hızlı parçacık ince harften geçip gitmesin).
     * Çarpınca [hx], [hy] çarpma noktasıdır; üstten geldiyse tam yüzeye oturtulur.
     */
    private fun sweep(targets: Collection<FxTarget>, x0: Float, y0: Float, x1: Float, y1: Float, d: Float): FxTarget? {
        val dx = x1 - x0; val dy = y1 - y0
        val n = (sqrt(dx * dx + dy * dy) / (2f * d)).toInt().coerceIn(1, 24)
        var py = y0
        for (k in 1..n) {
            val u = k.toFloat() / n
            val x = x0 + dx * u
            val y = y0 + dy * u
            val t = solidAt(targets, x, y)
            if (t != null) {
                hx = x; hy = y
                val top = t.topAt(x)
                if (top != null && py <= top + 0.5f * d) hy = top
                return t
            }
            py = y
        }
        return null
    }

    /** Parçacık bu öğenin yüzeyine üstten mi geldi? */
    private fun fromAbove(t: FxTarget, x: Float, prevY: Float, d: Float): Boolean {
        val top = t.topAt(x) ?: return false
        return prevY <= top + 2f * d
    }

    // ================================================================ Adım
    fun step(
        weather: Int, dt: Float, now: Long, W: Float, H: Float, d: Float, intensity: Float,
        targets: Collection<FxTarget>, byId: Map<String, FxTarget>,
    ) {
        if (scene != weather) {
            ps.clear(); flashes.clear(); swimmers.clear(); arcs.clear()
            acc = 0f; nextEvent = 0.3f; flash = 0f
            for (t in targets) clearExtras(t)
            scene = weather
        }
        nowMs = now
        inten = intensity
        time += dt
        gust = 0.55f + 0.45f * (0.5f + 0.5f * fxNoise(time * 0.45f + 3f))
        val widthK = (W / (340f * d)).coerceIn(0.6f, 1.8f)

        when (weather) {
            FXW_METEOR -> {
                acc += (0.7f + 2.4f * inten) * widthK * dt
                while (acc >= 1f) { acc -= 1f; spawnMeteor(W, H, d) }
            }
            FXW_SEA -> {
                acc += (4f + 20f * inten) * widthK * dt
                while (acc >= 1f) { acc -= 1f; spawnBubble(W, H, d) }
                if (ps.count { it.kind == S_MOTE } < 26) spawnMote(W, H, d)
                stepFish(dt, W, H, d, targets)
            }
            FXW_FIREWORKS -> {
                nextEvent -= dt
                if (nextEvent <= 0f) {
                    nextEvent = (0.45f + f() * 1.3f) / (0.4f + inten)
                    launchRocket(W, H, d)
                    if (inten > 0.7f && f() < 0.3f) launchRocket(W, H, d)
                }
            }
            FXW_FIREFLY -> stepFireflies(dt, W, H, d, targets, byId)
            FXW_SAND -> {
                val k = (H / (160f * d)).coerceIn(0.6f, 1.8f)
                acc += (90f + 420f * inten) * gust * k * dt
                while (acc >= 1f) { acc -= 1f; spawnGrain(W, H, d) }
            }
            FXW_AURORA -> {
                acc += (3f + 12f * inten) * widthK * dt
                while (acc >= 1f) {
                    acc -= 1f
                    val p = SP(S_DUST, f() * W, f() * H * 0.9f, (f() - 0.5f) * 6f * d, (3f + f() * 6f) * d, 3f + f() * 3f, (0.5f + f() * 0.9f) * d)
                    p.a = f() * FX_TAU
                    ps.add(p)
                }
            }
            FXW_ELECTRIC -> stepElectric(dt, W, H, d, targets)
            FXW_BLIZZARD -> {
                acc += (70f + 300f * inten) * widthK * dt
                while (acc >= 1f) { acc -= 1f; spawnFlake(W, H, d) }
            }
        }

        updateParticles(dt, W, H, d, targets, byId)

        // Yığınlar (kum / kar): yığılma açısı, rüzgârla kayma, kenardan dökülme
        for (t in targets) {
            if (t.pile == null || t.gone) continue
            when (t.pileKind) {
                PILE_SAND -> relaxPile(t, dt, d, 0.6f, 0.22f * gust, 0.03f * d) { x, y, m -> spill(x, y, m, d, true) }
                PILE_SNOW -> relaxPile(t, dt, d, 0.95f, 0.10f * gust, 0.015f * d) { x, y, m -> spill(x, y, m, d, false) }
            }
        }

        // Flaşlar, sarsıntı, parıltı, izler
        var i = flashes.size - 1
        while (i >= 0) { val fl = flashes[i]; fl.life -= dt; if (fl.life <= 0f) flashes.removeAt(i); i-- }
        if (flash > 0f) flash = (flash - dt * 2.6f).coerceAtLeast(0f)
        for (t in targets) {
            if (t.shake > 0.05f * d) {
                t.shake *= exp(-7f * dt)
                t.shakeX = t.shake * sin(time * 93f + t.id.hashCode() * 0.1f)
                t.shakeY = t.shake * 0.6f * cos(time * 71f)
            } else if (t.shake != 0f || t.shakeX != 0f || t.shakeY != 0f) {
                t.shake = 0f; t.shakeX = 0f; t.shakeY = 0f
            }
            if (t.glow > 0f) t.glow = (t.glow - dt * 2.2f).coerceAtLeast(0f)
            if (t.scorches.isNotEmpty()) t.scorches.removeAll { (now - it.born) / 1000f > SCORCH_LIFE }
            val marks = t.glow > 0f || t.scorches.isNotEmpty()
            if (marks || t.marksOn) { t.fxTick++; t.marksOn = marks }
        }
    }

    // ---------------------------------------------------------------- Ortak parçacık güncellemesi
    private fun updateParticles(dt: Float, W: Float, H: Float, d: Float, targets: Collection<FxTarget>, byId: Map<String, FxTarget>) {
        val windSand = (170f + 280f * inten) * d * gust
        val windSnow = (80f + 240f * inten) * d * gust
        var i = ps.size - 1
        while (i >= 0) {
            val p = ps[i]
            p.px = p.x; p.py = p.y
            var dead = false
            when (p.kind) {
                S_METEOR -> {
                    p.life -= dt
                    p.x += p.vx * dt; p.y += p.vy * dt
                    if (f() < dt * (if (p.a > 0f) 80f else 28f)) {
                        val e = SP(S_EMBER, p.x, p.y, p.vx * 0.04f + (f() - 0.5f) * 30f * d, p.vy * 0.04f + (f() - 0.5f) * 30f * d,
                            0.3f + f() * 0.6f, (0.5f + f() * 0.7f) * d)
                        e.color = if (f() < 0.5f) Color(0xFFFFB25C) else Color(0xFFFFE1A8)
                        ps.add(e)
                    }
                    val t = if (p.y > 0f) sweep(targets, p.px, p.py, p.x, p.y, d) else null
                    if (t != null) {
                        impact(t, hx, hy, p.a > 0f, d, p.color)
                        dead = true
                    } else if (p.life <= 0f) {
                        flashes.add(FxFlash(p.x, p.y, p.size * 5f, 0.25f, p.color, false))
                        dead = true
                    } else if (p.y > H + 30f * d || p.x > W + 60f * d || p.x < -60f * d) dead = true
                }
                S_EMBER -> {
                    val damp = exp(-2f * dt)
                    p.vx *= damp; p.vy = p.vy * damp + 60f * d * dt
                    p.x += p.vx * dt; p.y += p.vy * dt
                    p.life -= dt
                    if (p.life <= 0f) dead = true
                }
                S_SPARK -> {
                    p.vy += 900f * d * dt
                    p.vx *= (1f - 0.8f * dt)
                    p.x += p.vx * dt; p.y += p.vy * dt
                    p.life -= dt
                    if (p.vy > 0f) {
                        val t = sweep(targets, p.px, p.py, p.x, p.y, d)
                        if (t != null && fromAbove(t, hx, p.py, d)) {
                            p.x = hx; p.y = hy - 0.3f * d
                            p.vy = if (p.vy > 60f * d) -p.vy * 0.35f else 0f
                            p.vx *= 0.6f
                        }
                    }
                    if (p.life <= 0f || p.y > H + 10f * d) dead = true
                }
                S_BUBBLE -> {
                    val rr = p.size / d
                    val term = (34f + 22f * sqrt(rr)) * d
                    p.vy += (-term - p.vy) * min(1f, 3f * dt)
                    val w = 5f + rr * 1.3f
                    p.a += dt * w
                    val vx = rr * 0.6f * d * cos(p.a) * w
                    p.x += vx * dt; p.y += p.vy * dt
                    val t = solidAt(targets, p.x, p.y - p.size)
                    if (t != null) {
                        val bot = t.botAt(p.x)
                        if (bot != null && p.py - p.size >= bot - 3f * d) {
                            p.kind = S_BUBBLE_STUCK; p.tid = t.id
                            p.ox = (p.x - t.rect.left).coerceIn(0f, t.rect.width)
                            p.vx = 0f; p.vy = 0f
                            val tr = t.tight
                            p.b = if (p.x < tr.center.x) -1f else 1f        // yakın kenara doğru kayar
                            p.y = bot + p.size * 0.9f
                        } else {
                            p.x = p.px                                      // yandan çarptı: yatay salınım engellenir
                        }
                    }
                    if (p.y < -p.size * 2f) dead = true
                }
                S_BUBBLE_STUCK -> {
                    val t = p.tid?.let { byId[it] }
                    if (t == null || t.gone) {
                        p.kind = S_BUBBLE; p.tid = null; p.vy = -20f * d
                    } else {
                        // tavanın altında kenara doğru yavaşça kayar (büyük kabarcık daha hızlı)
                        p.vx += 12f * d * dt * (0.5f + p.size / d * 0.3f)
                        p.ox += p.b * p.vx * dt
                        p.x = t.rect.left + p.ox
                        val bot = t.botAt(p.x)
                        if (bot == null) {
                            // altında içerik kalmadı (harf / simge bitti): serbest kalıp yükselir
                            p.kind = S_BUBBLE; p.tid = null; p.vy = -15f * d
                        } else {
                            p.y += (bot + p.size * 0.9f - p.y) * min(1f, 12f * dt)
                        }
                    }
                }
                S_MOTE -> {
                    p.x += (p.vx + sin(time * 0.7f + p.a) * 3f * d) * dt; p.y += p.vy * dt
                    p.life -= dt
                    if (p.life <= 0f || p.y > H + 4f * d) dead = true
                }
                S_ROCKET -> {
                    p.vy += 520f * d * dt
                    p.x += p.vx * dt; p.y += p.vy * dt
                    if (f() < 90f * dt) {
                        val e = SP(S_EMBER, p.x, p.y, (f() - 0.5f) * 20f * d, (30f + f() * 40f) * d, 0.25f + f() * 0.3f, (0.5f + f() * 0.5f) * d)
                        e.color = Color(0xFFFFC879)
                        ps.add(e)
                    }
                    if (p.vy >= -25f * d) { explode(p.x, p.y, p.color, p.a.toInt(), d); dead = true }
                }
                S_STAR -> {
                    val damp = exp(-p.a * dt)
                    p.vx *= damp; p.vy *= damp
                    p.vy += (if (p.b == 1f) 70f else 110f) * d * dt
                    p.x += p.vx * dt; p.y += p.vy * dt
                    p.life -= dt
                    if (p.b == 1f && f() < 26f * dt) {
                        val e = SP(S_EMBER, p.x, p.y, 0f, 10f * d, 0.5f + f() * 0.4f, 0.6f * d)
                        e.color = Color(0xFFE8B04A)
                        ps.add(e)
                    }
                    if (p.life <= 0f) {
                        if (p.b == 3f) ps.add(SP(S_GLITTER, p.x, p.y, 0f, 0f, 0.12f, 2.4f * d).also { it.color = Color.White })
                        dead = true
                    } else if (p.vy > 0f && p.life > 0.15f) {
                        val t = sweep(targets, p.px, p.py, p.x, p.y, d)
                        if (t != null && fromAbove(t, hx, p.py, d)) {
                            p.kind = S_LANDED; p.tid = t.id
                            p.ox = hx - t.rect.left
                            p.x = hx; p.y = hy; p.vx = 0f; p.vy = 0f
                            p.life = min(p.life, 0.9f); p.max = p.life
                        }
                    }
                }
                S_SMOKE -> {
                    p.x += p.vx * dt; p.y += p.vy * dt
                    p.size += 12f * d * dt
                    p.life -= dt
                    if (p.life <= 0f) dead = true
                }
                S_LANDED -> {
                    val t = p.tid?.let { byId[it] }
                    val top = if (t == null || t.gone) null else t.topAt(t.rect.left + p.ox)
                    if (t == null || top == null) { p.kind = S_SPARK; p.tid = null; p.vy = 0f }
                    else { p.x = t.rect.left + p.ox; p.y = top }
                    p.life -= dt
                    if (p.life <= 0f) dead = true
                }
                S_GRAIN -> {
                    val wl = windSand * p.z
                    p.vx += (wl - p.vx) * min(1f, 2.5f * dt)
                    p.vy += (fxNoise(p.x * 0.02f / d + time * 2.6f + p.z * 9f) * 90f * d - p.vy * 1.2f + 18f * d) * dt
                    p.x += p.vx * dt; p.y += p.vy * dt
                    if (p.x > W + 10f * d || p.y > H + 10f * d || p.y < -20f * d) dead = true
                    else {
                        val t = sweep(targets, p.px, p.py, p.x, p.y, d)
                        if (t != null) {
                            if (fromAbove(t, hx, p.py, d)) {
                                if (f() < 0.55f) deposit(t, PILE_SAND, hx, 0.16f * p.size, 10f * d)
                                dead = true
                            } else if (f() < 0.15f) {
                                dead = true
                            } else {
                                // rüzgâra bakan yüze (harfin, simgenin yanına) çarpar, yukarı tırmanır
                                p.x = p.px
                                p.vy = -abs(p.vx) * (0.25f + 0.3f * f())
                                p.vx *= 0.25f
                            }
                        }
                    }
                }
                S_FALL -> {
                    p.vy += 600f * d * dt
                    p.vx += ((if (scene == FXW_SAND) windSand else windSnow) * 0.4f - p.vx) * min(1f, 2f * dt)
                    p.x += p.vx * dt; p.y += p.vy * dt
                    p.life -= dt
                    if (p.life <= 0f || p.y > H + 5f * d) dead = true
                }
                S_DUST -> {
                    p.x += (p.vx + sin(time * 0.6f + p.a) * 2f * d) * dt; p.y += p.vy * dt
                    p.life -= dt
                    if (p.life <= 0f) dead = true
                }
                S_FLAKE -> {
                    val tx = windSnow * p.z * (1f + 0.5f * fxNoise(p.y * 0.03f / d + time * 1.7f + p.a))
                    val ty = (18f + 42f * p.z) * d * (1f + 0.6f * fxNoise(p.x * 0.025f / d - time * 1.3f + p.a * 0.5f))
                    p.vx += (tx - p.vx) * min(1f, 3f * dt); p.vy += (ty - p.vy) * min(1f, 3f * dt)
                    p.x += p.vx * dt; p.y += p.vy * dt
                    if (p.x > W + 12f * d || p.y > H + 12f * d) dead = true
                    else if (p.z > 0.55f) {
                        val t = sweep(targets, p.px, p.py, p.x, p.y, d)
                        if (t != null) {
                            if (fromAbove(t, hx, p.py, d)) deposit(t, PILE_SNOW, hx, 0.10f * p.size, 8f * d)
                            dead = true
                        }
                    }
                }
                S_GLITTER -> { p.life -= dt; if (p.life <= 0f) dead = true }
            }
            if (dead) ps.removeAt(i)
            i--
        }
        if (scene == FXW_SEA) mergeStuckBubbles()
    }

    // ================================================================ 5 Meteor yağmuru
    private fun spawnMeteor(W: Float, H: Float, d: Float) {
        if (ps.size >= MAX_PS) return
        val big = f() < 0.16f
        val sx = (f() * 1.3f - 0.05f) * W
        val sy = -f() * H * 0.25f - 6f * d
        // radyant: sol üstte ekran dışında; tüm meteorlar aynı noktadan yayılıyormuş gibi görünür (gerçek yağmurlarda olduğu gibi)
        val rx = -W * 0.35f; val ry = -H * 1.4f
        var dx = sx - rx; var dy = sy - ry
        val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        dx /= len; dy /= len
        val sp = (if (big) 380f + f() * 220f else 520f + f() * 480f) * d
        val p = SP(S_METEOR, sx, sy, dx * sp, dy * sp, if (big) 9f else 0.25f + f() * 0.55f,
            (if (big) 2.4f + f() * 1.0f else 0.8f + f() * 0.8f) * d)
        val c = f()
        p.color = when {
            c < 0.62f -> Color(0xFFFFF4E0)
            c < 0.82f -> Color(0xFFB9FFD2)     // magnezyum: yeşilimsi
            else -> Color(0xFFFFC27A)          // sodyum: turuncu
        }
        p.a = if (big) 1f else 0f
        ps.add(p)
    }

    private fun impact(t: FxTarget, x: Float, y: Float, big: Boolean, d: Float, col: Color) {
        val room = ps.size < MAX_PS
        flashes.add(FxFlash(x, y, (if (big) 26f else 10f) * d, 0.35f, col, false))
        flashes.add(FxFlash(x, y, (if (big) 40f else 16f) * d, 0.45f, col, true))
        if (room) repeat(if (big) 22 else 8) {
            val s = SP(S_SPARK, x, y - 1f * d, (f() - 0.5f) * (if (big) 420f else 220f) * d,
                -(60f + f() * (if (big) 300f else 160f)) * d, 0.45f + f() * 0.6f, (0.7f + f() * 0.9f) * d)
            s.color = if (f() < 0.5f) Color(0xFFFFD59A) else Color(0xFFFF8A3D)
            ps.add(s)
        }
        if (big) repeat(3) {
            val s = SP(S_SMOKE, x + (f() - 0.5f) * 10f * d, y - 4f * d, (f() - 0.5f) * 10f * d, -(6f + f() * 8f) * d, 2.2f + f(), (6f + f() * 6f) * d)
            s.color = Color(0xFF8C8178)
            ps.add(s)
        }
        if (t.scorches.size > 10) t.scorches.removeAt(0)
        t.scorches.add(FxScorch(
            ((x - t.rect.left) / max(1f, t.rect.width)).coerceIn(0f, 1f),
            ((y - t.rect.top) / max(1f, t.rect.height)).coerceIn(0f, 1f),
            (if (big) 8f else 4f) * d, nowMs,
        ))
        t.shake = min(t.shake + (if (big) 5f else 1.2f) * d, 8f * d)
        if (big) { flash = max(flash, 0.5f); flashColor = Color(0xFFFFE2B8) }
    }

    // ================================================================ 6 Sualtı
    private fun spawnBubble(W: Float, H: Float, d: Float) {
        if (ps.size >= MAX_PS) return
        val stream = f() < 0.45f
        val x = if (stream) W * (if (f() < 0.5f) 0.14f else 0.83f) + (f() - 0.5f) * 6f * d else f() * W
        val r = (0.9f + f() * f() * 4.6f) * d
        val p = SP(S_BUBBLE, x, H + r + f() * 6f * d, 0f, -30f * d, 0f, r)
        p.a = f() * FX_TAU
        ps.add(p)
    }

    private fun spawnMote(W: Float, H: Float, d: Float) {
        val p = SP(S_MOTE, f() * W, f() * H, (f() - 0.5f) * 4f * d, (2f + f() * 4f) * d, 6f + f() * 6f, (0.4f + f() * 0.7f) * d)
        p.a = f() * FX_TAU
        ps.add(p)
    }

    /** Aynı öğenin altına takılan ve birbirine değen kabarcıklar hacmi koruyarak birleşir. */
    private fun mergeStuckBubbles() {
        var i = 0
        while (i < ps.size) {
            val a = ps[i]
            if (a.kind == S_BUBBLE_STUCK) {
                var j = i + 1
                while (j < ps.size) {
                    val b = ps[j]
                    if (b.kind == S_BUBBLE_STUCK && b.tid == a.tid && abs(a.ox - b.ox) < (a.size + b.size) * 0.8f) {
                        val va = a.size * a.size * a.size; val vb = b.size * b.size * b.size
                        a.ox = (a.ox * va + b.ox * vb) / (va + vb)
                        a.size = cbrt(va + vb)
                        a.vx = max(a.vx, b.vx)
                        ps.removeAt(j)
                    } else j++
                }
            }
            i++
        }
    }

    private fun stepFish(dt: Float, W: Float, H: Float, d: Float, targets: Collection<FxTarget>) {
        val want = 1 + (inten * 2.5f).roundToInt()
        while (swimmers.size < want) {
            val kind = swimmers.size % 3
            val size = when (kind) { 0 -> 14f + f() * 4f; 1 -> 16f + f() * 4f; else -> 9f + f() * 2f } * d
            val left = f() < 0.5f
            val s = Swimmer(if (left) -size * 2f else W + size * 2f, H * (0.15f + 0.7f * f()), size, kind, f() * 100f)
            s.speed = (18f + f() * 18f) * d * (if (kind == 2) 1.3f else 1f)
            s.vx = if (left) s.speed else -s.speed
            swimmers.add(s)
        }
        while (swimmers.size > want) swimmers.removeAt(swimmers.size - 1)
        for (s in swimmers) {
            val dirX = if (s.vx >= 0f) 1f else -1f
            var dx = dirX * s.speed * (0.8f + 0.3f * fxNoise(time * 0.5f + s.seed))
            var dy = fxNoise(time * 0.7f + s.seed * 1.3f) * s.speed * 0.35f
            // önündeki öğeden kaçın: üstünden ya da altından dolaş
            val lx = s.x + s.vx * 0.7f + dirX * s.size * 0.5f
            val ly = s.y + s.vy * 0.7f
            val t = tightAt(targets, lx, ly, 4f * d)
            if (t != null) {
                val tr = t.tight
                val up = (s.y < tr.center.y && tr.top > H * 0.12f) || tr.bottom > H * 0.88f
                dy += if (up) -s.speed * 1.6f else s.speed * 1.6f
                dx *= 0.5f
            }
            if (s.y < H * 0.08f) dy += s.speed
            if (s.y > H * 0.92f) dy -= s.speed
            val k = min(1f, 2.2f * dt)
            s.vx += (dx - s.vx) * k; s.vy += (dy - s.vy) * k
            s.x += s.vx * dt; s.y += s.vy * dt
            val v = sqrt(s.vx * s.vx + s.vy * s.vy)
            s.phase += dt * (5f + v / s.size * 2.5f)
            // ekrandan çıkınca bir süre sonra geri döner
            if (s.x < -s.size * 4f || s.x > W + s.size * 4f) {
                s.vx = -s.vx
                s.x = s.x.coerceIn(-s.size * 3.9f, W + s.size * 3.9f)
                s.y = H * (0.15f + 0.7f * f())
            }
        }
    }

    // ================================================================ 7 Havai fişek
    private val fireColors = longArrayOf(0xFFFFD36E, 0xFFFF5A5A, 0xFF5CFF8F, 0xFF5CB8FF, 0xFFC77DFF, 0xFFF4F4FF, 0xFFFF9F43)

    private fun launchRocket(W: Float, H: Float, d: Float) {
        if (ps.size >= MAX_PS) return
        val x = W * (0.12f + 0.76f * f())
        val apex = H * (0.12f + 0.3f * f())
        val g = 520f * d
        val vy = -sqrt(2f * g * (H - apex))
        val p = SP(S_ROCKET, x, H + 2f * d, (f() - 0.5f) * 30f * d, vy, 6f, 1.4f * d)
        p.color = Color(fireColors[rnd.nextInt(fireColors.size)])
        p.a = rnd.nextInt(5).toFloat()          // 0 şakayık, 1 söğüt, 2 halka, 3 çıtırtı, 4 iki renk
        ps.add(p)
    }

    private fun explode(x: Float, y: Float, col: Color, type: Int, d: Float) {
        val n = ((45f + 70f * inten) * (if (type == 2) 0.6f else 1f)).toInt()
        val sp0 = (95f + f() * 55f) * d
        val col2 = Color(fireColors[rnd.nextInt(fireColors.size)])
        val gold = Color(0xFFFFC65C)
        val tilt = f() * PI.toFloat()
        val ct = cos(tilt); val st = sin(tilt)
        for (i in 0 until n) {
            var vx: Float; var vy: Float
            if (type == 2) {
                val ang = i.toFloat() / n * FX_TAU
                val ex = cos(ang) * sp0; val ey = sin(ang) * sp0 * 0.45f
                vx = ex * ct - ey * st; vy = ex * st + ey * ct
            } else {
                // küre üzerinde düzgün dağılım (2B izdüşüm): gerçek kabuk gibi ortası yoğun görünür
                val z = 2f * f() - 1f
                val th = FX_TAU * f()
                val rr = sqrt(1f - z * z)
                val s = sp0 * (0.9f + 0.1f * f())
                vx = rr * cos(th) * s; vy = rr * sin(th) * s
            }
            val willow = type == 1
            val star = SP(S_STAR, x, y, vx, vy, if (willow) 2.3f + f() * 0.8f else 1.1f + f() * 0.7f, (1.0f + f() * 0.6f) * d)
            star.color = when {
                willow -> gold
                type == 4 && i % 2 == 0 -> col2
                else -> lerp(col, Color.White, f() * 0.15f)
            }
            star.a = if (willow) 1.5f else 2.4f
            star.b = type.toFloat()
            ps.add(star)
        }
        flashes.add(FxFlash(x, y, 70f * d, 0.35f, col, false))
        flash = max(flash, 0.35f); flashColor = col
        val sm = SP(S_SMOKE, x, y, (f() - 0.5f) * 8f * d, -4f * d, 3.5f, 18f * d)
        sm.color = Color(0xFF9A9AA5)
        ps.add(sm)
    }

    // ================================================================ 8 Ateşböcekleri
    private fun stepFireflies(dt: Float, W: Float, H: Float, d: Float, targets: Collection<FxTarget>, byId: Map<String, FxTarget>) {
        val want = 5 + (inten * 11f).roundToInt()
        while (swimmers.size < want) {
            val s = Swimmer(f() * W, H * (0.1f + 0.8f * f()), 1f, 0, f() * 100f)
            s.heading = f() * FX_TAU
            s.speed = (10f + f() * 14f) * d
            s.phase = f()
            s.period = 1.8f + f() * 0.8f
            s.timer = 2f + f() * 6f
            swimmers.add(s)
        }
        while (swimmers.size > want) swimmers.removeAt(swimmers.size - 1)
        val fired = ArrayList<Swimmer>()
        for (s in swimmers) {
            s.phase += dt / s.period
            if (s.phase >= 1f) { s.phase -= 1f; fired.add(s) }
            when (s.state) {
                0 -> {
                    s.heading += (f() - 0.5f) * 5f * dt + fxNoise(time * 0.8f + s.seed) * 0.8f * dt
                    var dvx = cos(s.heading) * s.speed
                    var dvy = sin(s.heading) * s.speed * 0.7f
                    if (s.x < 12f * d) dvx += s.speed
                    if (s.x > W - 12f * d) dvx -= s.speed
                    if (s.y < 10f * d) dvy += s.speed
                    if (s.y > H - 10f * d) dvy -= s.speed
                    val k = min(1f, 1.5f * dt)
                    s.vx += (dvx - s.vx) * k; s.vy += (dvy - s.vy) * k
                    s.x += s.vx * dt; s.y += s.vy * dt
                    s.timer -= dt
                    if (s.timer <= 0f) {
                        val live = targets.filter { !it.gone && it.tight.width > 6f * d }
                        val t = if (live.isNotEmpty() && f() < 0.6f) live[rnd.nextInt(live.size)] else null
                        val col = if (t != null) nearestCol(t, t.rect.left + f() * t.rect.width) else -1
                        if (t != null && col >= 0) { s.tid = t.id; s.ox = col.toFloat(); s.state = 1 }
                        else s.timer = 2f + f() * 5f
                    }
                }
                1 -> {
                    val t = s.tid?.let { byId[it] }
                    val top = if (t == null || t.gone) null else t.colTop(s.ox.toInt())
                    if (t == null || top == null) { s.state = 0; s.timer = 3f }
                    else {
                        val tx = t.colX(s.ox.toInt())
                        val ty = top - 1.2f * d
                        val dx = tx - s.x; val dy = ty - s.y
                        val dist = sqrt(dx * dx + dy * dy)
                        if (dist < 1.5f * d) { s.state = 2; s.timer = 3f + f() * 6f; s.vx = 0f; s.vy = 0f }
                        else {
                            val sp = min(s.speed, dist * 2f)
                            val k = min(1f, 3f * dt)
                            s.vx += (dx / dist * sp - s.vx) * k; s.vy += (dy / dist * sp - s.vy) * k
                            s.x += s.vx * dt; s.y += s.vy * dt
                        }
                    }
                }
                else -> {
                    val t = s.tid?.let { byId[it] }
                    val top = if (t == null || t.gone) null else t.colTop(s.ox.toInt())
                    if (t == null || top == null) { s.state = 0; s.timer = 3f }
                    else {
                        s.x = t.colX(s.ox.toInt())
                        s.y = top - 1.2f * d
                        s.timer -= dt
                        if (s.timer <= 0f) { s.state = 0; s.vy = -s.speed; s.heading = -PI.toFloat() / 2f; s.timer = 4f + f() * 8f }
                    }
                }
            }
        }
        // Darbe bağlaşımı: yanan ateşböceği yakınındakilerin saatini biraz ileri iter → zamanla birlikte yanıp sönerler
        val reach = 90f * d
        for (src in fired) for (o in swimmers) {
            if (o === src) continue
            val dx = o.x - src.x; val dy = o.y - src.y
            if (dx * dx + dy * dy < reach * reach) o.phase = min(0.999f, o.phase + 0.07f * o.phase + 0.01f)
        }
    }

    // ================================================================ 9 Kum fırtınası
    private val sandColors = longArrayOf(0xFFE3C07F, 0xFFD2A35C, 0xFFB98546, 0xFFF0D9A8)

    private fun spawnGrain(W: Float, H: Float, d: Float) {
        if (ps.size >= MAX_PS) return
        val wind = (170f + 280f * inten) * d * gust
        val p = SP(S_GRAIN, -4f * d - f() * 20f * d, f() * H, wind * (0.7f + 0.6f * f()), (f() - 0.5f) * 25f * d, 0f, (0.5f + f() * 1.1f) * d)
        p.color = Color(sandColors[rnd.nextInt(sandColors.size)])
        p.z = 0.6f + f() * 0.6f
        ps.add(p)
    }

    private fun spill(x: Float, y: Float, m: Float, d: Float, sand: Boolean) {
        val n = (m / (0.12f * d)).toInt().coerceIn(0, 3)
        repeat(n) {
            val p = SP(S_FALL, x, y - f() * 2f * d, (f() - 0.2f) * 30f * d, -f() * 20f * d, 1.5f, (if (sand) 0.6f + f() * 0.6f else 0.8f + f() * 0.8f) * d)
            p.color = if (sand) Color(sandColors[rnd.nextInt(sandColors.size)]) else Color.White
            ps.add(p)
        }
    }

    // ---------------------------------------------------------------- Yığın (kum / kar)
    // Yığın öğenin şekil sütunlarıyla aynı çözünürlükte tutulur: her sütunun zemini içeriğin o noktadaki üst kenarıdır
    // (harfin, simgenin, çubuğun tepesi). Boş sütuna düşen malzeme tutunmaz, aşağı dökülür.
    private fun deposit(t: FxTarget, kind: Int, x: Float, amount: Float, maxH: Float) {
        val i = t.colIndex(x)
        if (i < 0 || t.colTop(i) == null) return
        val n = t.cols()
        var p = t.pile
        if (p == null || p.size != n || t.pileKind != kind) { p = FloatArray(n); t.pile = p; t.pileKind = kind }
        p[i] = (p[i] + amount).coerceAtMost(maxH)
    }

    /**
     * Yüzey eğimi yığılma açısını aşınca malzeme alçak komşuya akar (zemin yüksekliği farklı olsa da: harfin tepesinden
     * yanındaki alçak harfe). Rüzgâr yüzeyi yavaşça iter. Boşluğa ya da kenara gelen fazlalık dökülür.
     */
    private inline fun relaxPile(t: FxTarget, dt: Float, d: Float, repose: Float, creep: Float, erode: Float, spillAt: (Float, Float, Float) -> Unit) {
        val p = t.pile ?: return
        val n = p.size
        if (n != t.cols()) { t.pile = null; t.pileKind = 0; return }
        val cw = t.colW()
        val maxDiff = cw * repose
        // zemin yoksa (içerik değişti, sütun boşaldı) üstündeki dökülür
        for (i in 0 until n) if (p[i] > 0f && t.colTop(i) == null) { spillAt(t.colX(i), t.tight.top, p[i]); p[i] = 0f }
        repeat(2) {
            for (i in 0 until n - 1) {
                val bi = t.colTop(i) ?: continue
                val bj = t.colTop(i + 1)
                if (bj == null) {
                    // sağ komşu boşluk: kenarda en çok bir eğim basamağı kalır
                    if (p[i] > maxDiff) { val m = p[i] - maxDiff; p[i] = maxDiff; spillAt(t.colX(i) + cw * 0.5f, bi - maxDiff, m) }
                    continue
                }
                val si = bi - p[i]
                val sj = bj - p[i + 1]
                val diff = sj - si                       // > 0: i daha yüksek
                if (diff > maxDiff) { val m = min((diff - maxDiff) * 0.5f, p[i]); p[i] -= m; p[i + 1] += m }
                else if (-diff > maxDiff) { val m = min((-diff - maxDiff) * 0.5f, p[i + 1]); p[i + 1] -= m; p[i] += m }
            }
            for (i in 1 until n) {
                val bi = t.colTop(i) ?: continue
                if (t.colTop(i - 1) == null && p[i] > maxDiff) {
                    val m = p[i] - maxDiff; p[i] = maxDiff; spillAt(t.colX(i) - cw * 0.5f, bi - maxDiff, m)
                }
            }
        }
        if (t.colTop(0) != null && p[0] > maxDiff) { val m = p[0] - maxDiff; p[0] = maxDiff; spillAt(t.rect.left, t.colTop(0)!! - maxDiff, m) }
        if (t.colTop(n - 1) != null && p[n - 1] > maxDiff) { val m = p[n - 1] - maxDiff; p[n - 1] = maxDiff; spillAt(t.rect.right, t.colTop(n - 1)!! - maxDiff, m) }
        if (creep > 0f) for (i in n - 2 downTo 0) {
            if (p[i] <= 0f || t.colTop(i + 1) == null) continue
            val m = p[i] * creep * dt; p[i] -= m; p[i + 1] += m
        }
        var total = 0f
        for (i in 0 until n) { p[i] = (p[i] - erode * dt).coerceAtLeast(0f); total += p[i] }
        if (total < 0.05f * d) { t.pile = null; t.pileKind = 0 }
    }

    /** Öğenin içeriğinde, verilen noktaya en yakın dolu sütun (yoksa -1). */
    private fun nearestCol(t: FxTarget, x: Float): Int {
        val n = t.cols()
        val i0 = ((x - t.rect.left) / t.colW()).toInt().coerceIn(0, n - 1)
        for (k in 0 until n) {
            if (i0 - k >= 0 && t.colTop(i0 - k) != null) return i0 - k
            if (i0 + k < n && t.colTop(i0 + k) != null) return i0 + k
        }
        return -1
    }

    /** İçeriğin, bir noktaya bakan kenarındaki gerçek nokta (ark uçları için). */
    private fun edgePoint(t: FxTarget, toward: Offset): Offset? {
        val tr = t.tight
        val i = nearestCol(t, toward.x.coerceIn(tr.left, tr.right))
        if (i < 0) return null
        val top = t.colTop(i) ?: return null
        val bot = t.colBot(i) ?: return null
        val x = t.colX(i)
        val y = when {
            toward.y < top -> top
            toward.y > bot -> bot
            toward.y - top < bot - toward.y -> top
            else -> bot
        }
        return Offset(x, y)
    }

    // ================================================================ 11 Elektrik
    private fun boltPath(a: Offset, b: Offset, rough: Float, levels: Int): List<Offset> {
        var pts: List<Offset> = listOf(a, b)
        val dx0 = b.x - a.x; val dy0 = b.y - a.y
        var off = sqrt(dx0 * dx0 + dy0 * dy0) * rough
        repeat(levels) {
            val nx = ArrayList<Offset>(pts.size * 2)
            for (i in 0 until pts.size - 1) {
                val p = pts[i]; val q = pts[i + 1]
                nx.add(p)
                val dx = q.x - p.x; val dy = q.y - p.y
                val l = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
                val o = (f() - 0.5f) * 2f * off
                nx.add(Offset((p.x + q.x) / 2f - dy / l * o, (p.y + q.y) / 2f + dx / l * o))
            }
            nx.add(pts.last())
            pts = nx
            off *= 0.52f
        }
        return pts
    }

    private fun regenArc(arc: ZapArc) {
        arc.pts = boltPath(arc.a, arc.b, if (arc.small) 0.35f else 0.2f, if (arc.small) 3 else 5)
        if (arc.small) { arc.branches = emptyList(); arc.bright = 0.5f + 0.5f * f(); return }
        val br = ArrayList<List<Offset>>()
        val dx = arc.b.x - arc.a.x; val dy = arc.b.y - arc.a.y
        val len = sqrt(dx * dx + dy * dy)
        repeat(1 + rnd.nextInt(3)) {
            val idx = (arc.pts.size * (0.2f + 0.6f * f())).toInt().coerceIn(0, arc.pts.size - 1)
            val s = arc.pts[idx]
            val ang = atan2(dy, dx) + (if (f() < 0.5f) 1f else -1f) * (0.5f + 0.4f * f())
            val bl = len * (0.18f + 0.22f * f())
            br.add(boltPath(s, Offset(s.x + cos(ang) * bl, s.y + sin(ang) * bl), 0.25f, 4))
        }
        arc.branches = br
        arc.bright = 0.6f + 0.4f * f()
    }

    private fun zapSparks(p: Offset, n: Int, d: Float) {
        repeat(n) {
            val s = SP(S_SPARK, p.x, p.y, (f() - 0.5f) * 300f * d, -(40f + f() * 200f) * d, 0.3f + f() * 0.5f, (0.5f + f() * 0.7f) * d)
            s.color = if (f() < 0.6f) Color(0xFFE6FBFF) else Color(0xFFFFC86E)
            ps.add(s)
        }
    }

    private fun stepElectric(dt: Float, W: Float, H: Float, d: Float, targets: Collection<FxTarget>) {
        nextEvent -= dt
        val live = targets.filter { !it.gone && it.tight.width > 4f * d }
        if (nextEvent <= 0f && live.isNotEmpty()) {
            nextEvent = (0.4f + f() * 1.6f) / (0.3f + inten)
            val ta = live[rnd.nextInt(live.size)]
            var tb: FxTarget? = null
            var pa: Offset? = null
            var pb: Offset? = null
            if (live.size >= 2 && f() < 0.8f) {
                var b = live[rnd.nextInt(live.size)]
                if (b === ta) b = live[(live.indexOf(ta) + 1) % live.size]
                tb = b
                val bc = b.tight.center
                pa = edgePoint(ta, Offset(bc.x + (f() - 0.5f) * 24f * d, bc.y))
                pb = pa?.let { edgePoint(b, it) }
            } else {
                // panel kenarına (topraklama) atlayan ark: içeriğin üstünden yukarı ya da altından aşağı
                val tr = ta.tight
                val up = f() < 0.5f
                pa = edgePoint(ta, Offset(tr.left + f() * tr.width, if (up) -1f else H + 1f))
                pb = pa?.let { Offset(it.x + (f() - 0.5f) * 30f * d, if (up) 0f else H) }
            }
            if (pa != null && pb != null) {
                val arc = ZapArc(pa, pb, 0.18f + f() * 0.4f, false, ta.id, tb?.id)
                regenArc(arc)
                arcs.add(arc)
                zapSparks(pa, 10, d); zapSparks(pb, 10, d)
                ta.glow = 1f; ta.shake = min(ta.shake + 1.6f * d, 6f * d)
                tb?.let { it.glow = 1f; it.shake = min(it.shake + 1.6f * d, 6f * d) }
                flash = max(flash, 0.28f); flashColor = Color(0xFFBFEFFF)
            }
        }
        // içeriğin üst yüzeyinde gezinen küçük deşarjlar (Aziz Elmo ateşi): harflerin, simgelerin tepesinden atlar
        if (live.isNotEmpty() && f() < 3f * inten * dt) {
            val t = live[rnd.nextInt(live.size)]
            val n = t.cols()
            val i = nearestCol(t, t.rect.left + f() * t.rect.width)
            if (i >= 0) {
                val step = (3 + rnd.nextInt(5)) * (if (f() < 0.5f) 1 else -1)
                val j = nearestCol(t, t.colX((i + step).coerceIn(0, n - 1)))
                val ya = t.colTop(i)
                val yb = if (j >= 0) t.colTop(j) else null
                if (j >= 0 && j != i && ya != null && yb != null) {
                    val arc = ZapArc(Offset(t.colX(i), ya), Offset(t.colX(j), yb), 0.08f + f() * 0.08f, true, t.id, null)
                    regenArc(arc)
                    arcs.add(arc)
                }
            }
        }
        var i = arcs.size - 1
        while (i >= 0) {
            val arc = arcs[i]
            arc.life -= dt
            arc.regen -= dt
            if (arc.regen <= 0f) { arc.regen = 0.045f; regenArc(arc) }
            if (!arc.small && f() < 40f * dt) zapSparks(if (f() < 0.5f) arc.a else arc.b, 1, d)
            if (arc.life <= 0f) arcs.removeAt(i)
            i--
        }
    }

    // ================================================================ 12 Tipi
    private fun spawnFlake(W: Float, H: Float, d: Float) {
        if (ps.size >= MAX_PS) return
        val wind = (80f + 240f * inten) * d * gust
        val z = 0.25f + 0.75f * f()
        val fromLeft = f() < 0.55f
        val x = if (fromLeft) -6f * d else f() * W * 1.1f - W * 0.1f
        val y = if (fromLeft) f() * H else -6f * d
        val p = SP(S_FLAKE, x, y, wind * z, (20f + 40f * z) * d, 0f, (0.6f + 2.4f * z) * d)
        p.z = z
        p.a = f() * 100f
        ps.add(p)
    }

    // ================================================================ Çizim
    fun DrawScope.render(d: Float, targets: Collection<FxTarget>) {
        if (scene == -1) return
        val W = size.width
        val H = size.height
        if (W <= 1f || H <= 1f) return

        // ---- 1) sahne atmosferi (içeriğin üstünde, yarı saydam)
        when (scene) {
            FXW_METEOR -> drawStarfield(W, H, d)
            FXW_SEA -> drawUnderwater(W, H, d)
            FXW_FIREFLY -> drawForestNight(W, H, d)
            FXW_SAND -> drawSandHaze(W, H, d)
            FXW_AURORA -> drawAurora(W, H, d, targets)
            FXW_BLIZZARD -> drawWhiteout(W, H, d)
        }

        // ---- 2) içeriğin üstünde biriken kum / kar (yanık izi ve elektrik parıltısı öğenin kendi çiziminde: drawFxMarks)
        for (t in targets) {
            if (t.rect.width <= 0f || t.gone || t.pile == null) continue
            if (t.pileKind == PILE_SAND) drawPile(t, d, Color(0xFFF0D3A0), Color(0xFFC08E4E), Color(0xFFFFEACB))
            else drawPile(t, d, Color(0xFFFFFFFF), Color(0xFFCFDDF0), Color(0xFFFFFFFF))
        }

        // ---- 3) parçacıklar
        for (p in ps) drawParticle(p, d)

        // ---- 4) canlılar
        if (scene == FXW_SEA) for (s in swimmers) drawFish(s)
        if (scene == FXW_FIREFLY) for (s in swimmers) drawFirefly(s, d, targets)

        // ---- 5) arklar
        for (arc in arcs) drawZap(arc, d)

        // ---- 6) flaşlar
        for (fl in flashes) {
            val k = (fl.life / fl.max).coerceIn(0f, 1f)
            val c = Offset(fl.x, fl.y)
            if (fl.ring) {
                val rr = fl.r * (1f - k * k)
                drawCircle(fl.color.copy(alpha = 0.55f * k), rr, c, style = Stroke(1.6f * d * (0.5f + k)))
            } else {
                val rr = fl.r * (0.6f + 0.4f * (1f - k))
                drawCircle(Brush.radialGradient(listOf(fl.color.copy(alpha = 0.75f * k), Color.Transparent), c, rr), rr, c, blendMode = BlendMode.Plus)
            }
        }
        if (flash > 0f) drawRect(flashColor.copy(alpha = 0.10f * flash))
    }

    // ---------------------------------------------------------------- Parçacık çizimi
    private fun DrawScope.drawParticle(p: SP, d: Float) {
        val c = Offset(p.x, p.y)
        when (p.kind) {
            S_METEOR -> {
                val big = p.a > 0f
                val tailK = if (big) 0.10f else 0.065f
                val tail = Offset(p.x - p.vx * tailK, p.y - p.vy * tailK)
                val fade = if (big) 1f else (p.life / p.max).coerceIn(0f, 1f).let { 0.35f + 0.65f * it }
                drawLine(
                    Brush.linearGradient(listOf(Color.Transparent, p.color.copy(alpha = 0.55f * fade), Color.White.copy(alpha = fade)), tail, c),
                    tail, c, p.size * 1.1f, StrokeCap.Round,
                )
                drawCircle(Brush.radialGradient(listOf(p.color.copy(alpha = 0.6f * fade), Color.Transparent), c, p.size * 5f), p.size * 5f, c, blendMode = BlendMode.Plus)
                drawCircle(Color.White.copy(alpha = fade), p.size * 0.8f, c)
            }
            S_EMBER -> {
                val k = (p.life / p.max).coerceIn(0f, 1f)
                drawCircle(p.color.copy(alpha = 0.9f * k), p.size * (0.6f + 0.4f * k), c)
            }
            S_SPARK -> {
                val k = (p.life / p.max).coerceIn(0f, 1f)
                val tail = Offset(p.x - p.vx * 0.02f, p.y - p.vy * 0.02f)
                drawLine(p.color.copy(alpha = k), tail, c, p.size, StrokeCap.Round)
                drawCircle(p.color.copy(alpha = 0.3f * k), p.size * 2.5f, c, blendMode = BlendMode.Plus)
            }
            S_BUBBLE, S_BUBBLE_STUCK -> drawBubble(p, d)
            S_MOTE -> {
                val k = sin(PI.toFloat() * (1f - p.life / p.max).coerceIn(0f, 1f))
                drawCircle(Color(0xFFDDF6FF).copy(alpha = 0.35f * k), p.size, c)
            }
            S_ROCKET -> {
                drawCircle(Color(0xFFFFF1C9), p.size, c)
                drawCircle(Brush.radialGradient(listOf(Color(0x99FFC879), Color.Transparent), c, p.size * 5f), p.size * 5f, c, blendMode = BlendMode.Plus)
            }
            S_STAR -> {
                val k = (p.life / p.max).coerceIn(0f, 1f)
                var al = k.pow(0.6f)
                if (p.b == 3f && k < 0.5f) al *= if (((time * 26f + p.x * 0.13f).toInt() % 3) == 0) 1f else 0.2f
                val tailK = if (p.b == 1f) 0.16f else 0.05f
                val tail = Offset(p.x - p.vx * tailK, p.y - p.vy * tailK)
                drawLine(p.color.copy(alpha = al * 0.55f), tail, c, p.size * 1.1f, StrokeCap.Round)
                drawCircle(p.color.copy(alpha = al * 0.25f), p.size * 3.2f, c, blendMode = BlendMode.Plus)
                drawCircle(lerp(p.color, Color.White, 0.6f).copy(alpha = al), p.size, c)
            }
            S_SMOKE -> {
                val k = (p.life / p.max).coerceIn(0f, 1f)
                drawCircle(Brush.radialGradient(listOf(p.color.copy(alpha = 0.14f * k), Color.Transparent), c, p.size), p.size, c)
            }
            S_LANDED -> {
                val k = (p.life / p.max).coerceIn(0f, 1f)
                drawCircle(p.color.copy(alpha = 0.35f * k), p.size * 3f, c, blendMode = BlendMode.Plus)
                drawCircle(lerp(p.color, Color(0xFFFF6A2B), 1f - k).copy(alpha = k), p.size * 0.9f, c)
            }
            S_GRAIN -> {
                val tail = Offset(p.x - p.vx * 0.025f, p.y - p.vy * 0.025f)
                drawLine(p.color.copy(alpha = 0.75f), tail, c, p.size, StrokeCap.Round)
            }
            S_FALL -> drawCircle(p.color.copy(alpha = 0.85f), p.size * 0.6f, c)
            S_DUST -> {
                val env = sin(PI.toFloat() * (1f - p.life / p.max).coerceIn(0f, 1f))
                val sp = max(0f, sin(time * 7f + p.a)).pow(8f)
                drawCircle(Color(0xFFE8F6FF).copy(alpha = 0.55f * env), p.size * 0.6f, c)
                if (sp > 0.3f) {
                    val l = p.size * 4f * sp
                    val col = Color.White.copy(alpha = env * sp)
                    drawLine(col, Offset(p.x - l, p.y), Offset(p.x + l, p.y), 0.6f * d, StrokeCap.Round)
                    drawLine(col, Offset(p.x, p.y - l), Offset(p.x, p.y + l), 0.6f * d, StrokeCap.Round)
                }
            }
            S_FLAKE -> {
                val al = 0.35f + 0.6f * p.z
                val speed = sqrt(p.vx * p.vx + p.vy * p.vy)
                if (p.z > 0.85f) {
                    // objektife yakın tane: yumuşak, odak dışı (bokeh)
                    drawCircle(Color.White.copy(alpha = 0.10f * al), p.size * 1.9f, c)
                    drawCircle(Color.White.copy(alpha = 0.18f * al), p.size * 1.25f, c)
                }
                if (speed > 140f * d && p.z > 0.5f) {
                    drawLine(Color.White.copy(alpha = al), Offset(p.x - p.vx * 0.028f, p.y - p.vy * 0.028f), c, p.size * 0.9f, StrokeCap.Round)
                } else {
                    drawCircle(Color.White.copy(alpha = al), p.size * 0.6f, c)
                }
            }
            S_GLITTER -> {
                val k = (p.life / p.max).coerceIn(0f, 1f)
                drawCircle(Color.White.copy(alpha = k), p.size * (1.5f - k), c, blendMode = BlendMode.Plus)
            }
        }
    }

    /** Gerçekçi kabarcık: kenarda parlayan ince zar (Fresnel), üstte keskin yansıma, altta zayıf ikincil yansıma. */
    private fun DrawScope.drawBubble(p: SP, d: Float) {
        val r = p.size
        val stuck = p.kind == S_BUBBLE_STUCK
        val c = Offset(p.x, p.y)
        val wob = if (stuck) 0f else sin(p.a * 2f) * 0.08f * (r / d / 5f).coerceAtMost(1f)
        val rx = r * (if (stuck) 1.12f else 1f + wob)
        val ry = r * (if (stuck) 0.85f else 1f - wob)
        val tl = Offset(c.x - rx, c.y - ry)
        val sz = Size(rx * 2f, ry * 2f)
        drawOval(Brush.radialGradient(0f to Color.Transparent, 0.72f to Color.White.copy(alpha = 0.04f), 1f to Color(0xFFBFEFFF).copy(alpha = 0.28f), center = c, radius = max(rx, ry)), tl, sz)
        drawOval(Color.White.copy(alpha = 0.55f), tl, sz, style = Stroke(max(0.6f * d, r * 0.08f)))
        drawOval(Color.White.copy(alpha = 0.9f), Offset(c.x - rx * 0.58f, c.y - ry * 0.64f), Size(rx * 0.48f, ry * 0.3f))
        drawCircle(Color.White.copy(alpha = 0.28f), r * 0.13f, Offset(c.x + rx * 0.42f, c.y + ry * 0.45f))
    }

    // ---------------------------------------------------------------- Balık
    private fun DrawScope.drawFish(s: Swimmer) {
        val L = s.size
        val h = atan2(s.vy, s.vx)
        val flip = cos(h) < 0f
        val a = if (flip) atan2(sin(h), -cos(h)) else h
        val deg = a * 180f / PI.toFloat()
        val main: Color; val top: Color; val belly: Color; val fin: Color
        when (s.kind) {
            0 -> { main = Color(0xFFFF7A1A); top = Color(0xFFD9500A); belly = Color(0xFFFFB46E); fin = Color(0xFFFF8C2A) }
            1 -> { main = Color(0xFF2B6FFF); top = Color(0xFF1A3A9E); belly = Color(0xFF8DB8FF); fin = Color(0xFFFFD93D) }
            else -> { main = Color(0xFFC8D6E0); top = Color(0xFF5E7787); belly = Color(0xFFF4F8FA); fin = Color(0x99C8D6E0) }
        }
        val tailA = sin(s.phase) * 22f
        withTransform({
            translate(s.x, s.y)
            scale(if (flip) -1f else 1f, 1f, Offset.Zero)
            rotate(deg, Offset.Zero)
        }) {
            // kuyruk (gövdeyle birlikte kıvrılır)
            withTransform({ rotate(tailA, Offset(-L * 0.28f, 0f)) }) {
                val tail = Path().apply {
                    moveTo(-L * 0.26f, 0f)
                    quadraticBezierTo(-L * 0.42f, -L * 0.04f, -L * 0.58f, -L * 0.21f)
                    lineTo(-L * 0.5f, 0f)
                    lineTo(-L * 0.58f, L * 0.21f)
                    quadraticBezierTo(-L * 0.42f, L * 0.04f, -L * 0.26f, 0f)
                    close()
                }
                drawPath(tail, fin)
            }
            // sırt yüzgeci
            val dorsal = Path().apply {
                moveTo(L * 0.14f, -L * 0.2f)
                quadraticBezierTo(-L * 0.02f, -L * 0.36f, -L * 0.2f, -L * 0.14f)
                close()
            }
            drawPath(dorsal, fin)
            // gövde
            val body = Path().apply {
                moveTo(L * 0.5f, 0f)
                cubicTo(L * 0.38f, -L * 0.25f, -L * 0.1f, -L * 0.27f, -L * 0.3f, -L * 0.05f)
                lineTo(-L * 0.3f, L * 0.05f)
                cubicTo(-L * 0.1f, L * 0.25f, L * 0.38f, L * 0.23f, L * 0.5f, 0f)
                close()
            }
            drawPath(body, Brush.verticalGradient(listOf(top, main, belly), startY = -L * 0.25f, endY = L * 0.25f))
            clipPath(body) {
                when (s.kind) {
                    0 -> for (bx in floatArrayOf(0.24f, -0.06f)) {
                        drawRect(Color(0xFF1A1A1A), Offset(L * (bx - 0.065f), -L * 0.3f), Size(L * 0.13f, L * 0.6f))
                        drawRect(Color.White, Offset(L * (bx - 0.045f), -L * 0.3f), Size(L * 0.09f, L * 0.6f))
                    }
                    1 -> drawPath(Path().apply {
                        moveTo(L * 0.3f, -L * 0.12f); quadraticBezierTo(L * 0.0f, -L * 0.02f, -L * 0.25f, -L * 0.12f)
                        quadraticBezierTo(L * 0.0f, L * 0.06f, L * 0.3f, -L * 0.12f)
                    }, Color(0xFF0B1A4A).copy(alpha = 0.8f))
                    else -> {
                        drawRect(Color(0xFF3FD0FF).copy(alpha = 0.9f), Offset(-L * 0.25f, -L * 0.05f), Size(L * 0.6f, L * 0.05f))
                        drawRect(Color(0xFFFF3B4A).copy(alpha = 0.85f), Offset(-L * 0.25f, L * 0.0f), Size(L * 0.32f, L * 0.08f))
                    }
                }
                // ıslak parlaklık
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), startY = -L * 0.25f, endY = 0f),
                    Offset(-L * 0.3f, -L * 0.25f), Size(L * 0.8f, L * 0.25f))
            }
            // göğüs yüzgeci (çırpar)
            withTransform({ rotate(20f + sin(s.phase * 1.3f) * 25f, Offset(L * 0.18f, L * 0.06f)) }) {
                drawOval(fin.copy(alpha = 0.8f), Offset(L * 0.02f, L * 0.04f), Size(L * 0.16f, L * 0.07f))
            }
            // solungaç ve göz
            drawArc(top.copy(alpha = 0.6f), -60f, 120f, false, Offset(L * 0.22f, -L * 0.13f), Size(L * 0.1f, L * 0.26f), style = Stroke(L * 0.02f))
            val e = Offset(L * 0.33f, -L * 0.05f)
            drawCircle(Color.White, L * 0.058f, e)
            drawCircle(Color(0xFF111111), L * 0.037f, Offset(e.x + L * 0.008f, e.y))
            drawCircle(Color.White.copy(alpha = 0.9f), L * 0.012f, Offset(e.x - L * 0.005f, e.y - L * 0.015f))
        }
    }

    // ---------------------------------------------------------------- Ateşböceği
    private fun DrawScope.drawFirefly(s: Swimmer, d: Float, targets: Collection<FxTarget>) {
        val L = fireflyLight(s.phase)
        val c = Offset(s.x, s.y)
        if (L > 0.01f) {
            val gr = 18f * d
            drawCircle(Brush.radialGradient(listOf(Color(0xFFD7FF5A).copy(alpha = 0.5f * L), Color.Transparent), c, gr), gr, c, blendMode = BlendMode.Plus)
            if (s.state == 2) {
                // konduğu yüzeye düşen ışık havuzu
                val pr = 26f * d
                val pc = Offset(s.x, s.y + 1.2f * d)
                drawOval(
                    Brush.radialGradient(listOf(Color(0xFFD7FF5A).copy(alpha = 0.35f * L), Color.Transparent), pc, pr),
                    Offset(pc.x - pr, pc.y - pr * 0.28f), Size(pr * 2f, pr * 0.56f), blendMode = BlendMode.Plus,
                )
            }
            drawCircle(Color(0xFFF6FFC2).copy(alpha = L), 1.7f * d, c)
        }
        drawOval(Color(0xFF2A2A1A).copy(alpha = 0.75f), Offset(s.x - 1.3f * d, s.y - 0.8f * d), Size(2.6f * d, 1.6f * d))
        if (s.state != 2) {
            val fl = abs(sin(time * 60f + s.seed))
            drawOval(Color.White.copy(alpha = 0.2f * fl), Offset(s.x - 2.4f * d, s.y - 2.2f * d), Size(2.2f * d, 1.4f * d))
            drawOval(Color.White.copy(alpha = 0.2f * fl), Offset(s.x + 0.2f * d, s.y - 2.2f * d), Size(2.2f * d, 1.4f * d))
        }
    }

    // ---------------------------------------------------------------- Ark
    private fun DrawScope.drawZap(arc: ZapArc, d: Float) {
        if (arc.pts.size < 2) return
        val k = (arc.life / arc.max).coerceIn(0f, 1f)
        val al = arc.bright * (0.35f + 0.65f * k)
        val w = if (arc.small) 0.5f else 1f
        fun poly(pts: List<Offset>) = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        val main = poly(arc.pts)
        drawPath(main, Color(0xFF7B61FF).copy(alpha = 0.18f * al), style = Stroke(12f * d * w, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        drawPath(main, Color(0xFF52D6FF).copy(alpha = 0.55f * al), style = Stroke(4f * d * w, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
        drawPath(main, Color.White.copy(alpha = al), style = Stroke(1.3f * d * w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        for (b in arc.branches) {
            val bp = poly(b)
            drawPath(bp, Color(0xFF52D6FF).copy(alpha = 0.4f * al), style = Stroke(2.6f * d, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
            drawPath(bp, Color.White.copy(alpha = 0.8f * al), style = Stroke(0.8f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        if (!arc.small) for (e in arrayOf(arc.a, arc.b)) {
            val r = 9f * d
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = al), Color(0xFF52D6FF).copy(alpha = 0.5f * al), Color.Transparent), e, r), r, e, blendMode = BlendMode.Plus)
        }
    }

    // ---------------------------------------------------------------- Öğe üstü izler
    private fun DrawScope.drawPile(t: FxTarget, d: Float, topC: Color, botC: Color, edgeC: Color) {
        val p = t.pile ?: return
        val n = p.size
        if (n != t.cols()) return
        val cw = t.colW()
        var mx = 0f
        for (v in p) mx = max(mx, v)
        if (mx < 0.3f * d) return
        val brush = Brush.verticalGradient(listOf(topC, botC), startY = t.tight.top - mx, endY = t.tight.bottom)
        val edge = edgeC.copy(alpha = 0.75f)
        val grain = botC.copy(alpha = 0.5f)
        var i = 0
        while (i < n) {
            if (t.colTop(i) == null || p[i] < 0.25f * d) { i++; continue }
            var j = i
            while (j < n && t.colTop(j) != null && p[j] >= 0.25f * d) j++
            // [i, j): kesintisiz yığın; üst yüzey yumuşatılır, alt kenar içeriğin üstünü izler
            val surf = Path()
            val fill = Path()
            val x0 = t.rect.left + i * cw
            fill.moveTo(x0, t.colTop(i)!! + 0.8f * d)
            var prevX = x0
            var prevY = t.colTop(i)!! - p[i] * 0.5f
            surf.moveTo(prevX, prevY); fill.lineTo(prevX, prevY)
            for (c in i until j) {
                val x = t.colX(c)
                val y = t.colTop(c)!! - p[c]
                surf.quadraticBezierTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
                fill.quadraticBezierTo(prevX, prevY, (prevX + x) / 2f, (prevY + y) / 2f)
                prevX = x; prevY = y
            }
            val x1 = t.rect.left + j * cw
            val yEnd = t.colTop(j - 1)!! - p[j - 1] * 0.5f
            surf.quadraticBezierTo(prevX, prevY, x1, yEnd); fill.quadraticBezierTo(prevX, prevY, x1, yEnd)
            for (c in j - 1 downTo i) fill.lineTo(t.rect.left + (c + if (c == j - 1) 1f else 0.5f) * cw, t.colTop(c)!! + 0.8f * d)
            fill.lineTo(x0, t.colTop(i)!! + 0.8f * d)
            fill.close()
            drawPath(fill, brush)
            drawPath(surf, edge, style = Stroke(0.8f * d, cap = StrokeCap.Round))
            // yüzeydeki doku (tane / kristal)
            for (c in i until j) {
                if (p[c] > 1.2f * d && ((c * 7 + t.id.hashCode()) and 3) == 0) {
                    drawCircle(grain, 0.45f * d, Offset(t.colX(c), t.colTop(c)!! - p[c] * 0.5f))
                }
            }
            i = j
        }
    }

    // ---------------------------------------------------------------- Atmosferler
    private fun DrawScope.drawStarfield(W: Float, H: Float, d: Float) {
        drawRect(Brush.verticalGradient(listOf(Color(0x33060A24), Color.Transparent), endY = H * 0.6f))
        for (i in 0 until 40) {
            val x = seeds[i] * W
            val y = seeds[i + 40] * H * 0.8f
            val tw = 0.5f + 0.5f * sin(time * (1.5f + seeds[i + 80] * 3f) + seeds[i + 120] * 10f)
            drawCircle(Color.White.copy(alpha = 0.35f * tw * (0.4f + seeds[i + 160] * 0.6f)), (0.5f + seeds[i + 200] * 0.6f) * d, Offset(x, y))
        }
    }

    private fun DrawScope.drawUnderwater(W: Float, H: Float, d: Float) {
        drawRect(Brush.verticalGradient(listOf(Color(0x2235C6D8), Color(0x330A4A6E))))
        // yukarıdan süzülen ışık huzmeleri
        for (k in 0 until 3) {
            val cx = W * (0.2f + 0.3f * k) + sin(time * 0.3f + k * 2f) * 20f * d
            val sw = sin(time * 0.5f + k) * 30f * d
            val shaft = Path().apply {
                moveTo(cx - 7f * d, 0f); lineTo(cx + 7f * d, 0f)
                lineTo(cx + 28f * d + sw, H); lineTo(cx - 28f * d + sw, H); close()
            }
            drawPath(shaft, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.08f), Color.Transparent), endY = H))
        }
        // kostik ağı: dalgalı yüzeyin kırdığı ışık
        val step = 6f * d
        val stroke = Stroke(1.4f * d, cap = StrokeCap.Round)
        for (k in 0 until 7) {
            val y0 = H * k / 6f
            val path = Path()
            var x = 0f
            path.moveTo(0f, y0 + 7f * d * sin(time * 1.3f + k * 1.9f))
            while (x <= W + step) {
                path.lineTo(x, y0 + 7f * d * sin(x * 0.045f / d + time * 1.3f + k * 1.9f) + 4f * d * sin(x * 0.11f / d - time * 0.9f + k))
                x += step
            }
            drawPath(path, Color.White.copy(alpha = 0.06f), style = stroke)
        }
        val cols = (W / (40f * d)).toInt().coerceIn(4, 14)
        for (k in 0..cols) {
            val x0 = W * k / cols
            val path = Path()
            var y = 0f
            path.moveTo(x0 + 7f * d * sin(time * 1.1f + k * 2.3f), 0f)
            while (y <= H + step) {
                path.lineTo(x0 + 7f * d * sin(y * 0.05f / d + time * 1.1f + k * 2.3f) + 4f * d * sin(y * 0.13f / d - time * 0.8f + k), y)
                y += step
            }
            drawPath(path, Color.White.copy(alpha = 0.05f), style = stroke)
        }
    }

    private fun DrawScope.drawForestNight(W: Float, H: Float, d: Float) {
        drawRect(Brush.verticalGradient(listOf(Color(0x2E041A10), Color(0x1F020A06))))
        for (i in 0 until 18) {
            val x = (seeds[i] * W + sin(time * 0.2f + seeds[i + 18] * 6f) * 10f * d)
            val y = (seeds[i + 36] * H + time * (2f + seeds[i + 54] * 3f) * d) % H
            drawCircle(Color(0xFFE8F5D0).copy(alpha = 0.15f), (0.4f + seeds[i + 72] * 0.5f) * d, Offset(x, y))
        }
    }

    private fun DrawScope.drawSandHaze(W: Float, H: Float, d: Float) {
        val a = (0.06f + 0.14f * gust) * (0.4f + 0.6f * inten)
        drawRect(Color(0xFFC89A55).copy(alpha = a))
        val bw = W * 0.9f
        for (k in 0 until 4) {
            val bh = H * (0.25f + seeds[k + 10] * 0.2f)
            val by = seeds[k + 20] * H - bh / 2f + sin(time * 0.4f + k) * 8f * d
            val span = W + bw
            val bx = ((seeds[k + 30] * span + time * (60f + 50f * seeds[k + 40]) * d * gust) % span) - bw
            drawOval(
                Brush.radialGradient(listOf(Color(0xFFD9AE6A).copy(alpha = 0.18f * gust), Color.Transparent), Offset(bx + bw / 2f, by + bh / 2f), bw / 2f),
                Offset(bx, by), Size(bw, bh),
            )
        }
    }

    private fun auroraBright(c: Int, x: Float, W: Float): Float =
        (0.5f + 0.5f * fxNoise(x / W * 9f - time * 1.1f * (1f + c * 0.35f) + c * 2.3f)).let { it * it }

    private fun auroraBase(c: Int, x: Float, W: Float, H: Float): Float =
        H * (0.2f + c * 0.08f) + H * 0.09f * fxNoise(x / W * 2.6f + time * 0.12f * (c + 1) + c * 5f) + H * 0.03f * sin(x / W * 13f + time * 0.6f + c)

    private fun DrawScope.drawAurora(W: Float, H: Float, d: Float, targets: Collection<FxTarget>) {
        drawRect(Brush.verticalGradient(0f to Color(0x55020817), 0.65f to Color.Transparent, endY = H))
        val step = 2.4f * d
        val strength = 0.5f + 0.5f * inten
        for (c in 0 until 3) {
            val mid = H * (0.2f + c * 0.08f)
            val top = mid - H * 0.12f - H * 0.34f
            val bottom = mid + H * 0.12f
            // renk yüksekliğe bağlı: alt kenar parlak yeşil, yukarısı mora / kızıla döner (oksijen / azot)
            val brush = Brush.verticalGradient(
                0f to Color(0x00B05CFF), 0.35f to Color(0xFFB05CFF), 0.68f to Color(0xFF39FF9C), 0.9f to Color(0xFF8DFFC8), 1f to Color(0xFFE8FFF2),
                startY = top, endY = bottom,
            )
            var x = 0f
            while (x <= W) {
                val b = auroraBright(c, x, W)
                val base = auroraBase(c, x, W, H)
                val len = H * (0.22f + 0.1f * fxNoise(x / W * 6f + time * 0.3f + c * 3f)) * (0.6f + 0.4f * b)
                drawLine(brush, Offset(x, base), Offset(x, base - len), 2.6f * d, alpha = (0.04f + 0.24f * b) * strength, blendMode = BlendMode.Plus)
                x += step
            }
            val edge = Path()
            x = 0f
            edge.moveTo(0f, auroraBase(c, 0f, W, H))
            while (x <= W + step) { edge.lineTo(x, auroraBase(c, x, W, H)); x += step * 2f }
            drawPath(edge, Color(0xFFCFFFE4).copy(alpha = 0.12f * strength), style = Stroke(2.2f * d, cap = StrokeCap.Round), blendMode = BlendMode.Plus)
        }
        // içeriğin (harflerin, simgelerin, çubuğun) üst kenarı gökteki ışığı yansıtır
        for (t in targets) {
            if (t.gone || t.tight.width < 4f * d) continue
            val n = t.cols()
            val cw = t.colW()
            var i = 0
            while (i < n) {
                if (t.colTop(i) == null) { i++; continue }
                var j = i
                while (j < n && t.colTop(j) != null) j++
                val cx = t.colX((i + j) / 2)
                val b = (auroraBright(0, cx, W) + auroraBright(1, cx, W)) * 0.5f * strength
                val rim = Path()
                rim.moveTo(t.rect.left + i * cw + 0.5f * d, t.colTop(i)!! + 0.5f * d)
                for (c in i until j) rim.lineTo(t.colX(c), t.colTop(c)!! + 0.5f * d)
                rim.lineTo(t.rect.left + j * cw - 0.5f * d, t.colTop(j - 1)!! + 0.5f * d)
                drawPath(rim, Color(0xFF5CFFB0).copy(alpha = 0.25f * b), style = Stroke(3.5f * d, cap = StrokeCap.Round, join = StrokeJoin.Round), blendMode = BlendMode.Plus)
                drawPath(rim, Color(0xFFBFFFE0).copy(alpha = 0.6f * b), style = Stroke(1f * d, cap = StrokeCap.Round, join = StrokeJoin.Round))
                i = j
            }
        }
    }

    private fun DrawScope.drawWhiteout(W: Float, H: Float, d: Float) {
        drawRect(Color(0xFFEAF2FF).copy(alpha = (0.03f + 0.13f * gust * gust) * (0.4f + 0.6f * inten)))
        // rüzgâr hamlesi çizgileri
        for (k in 0 until 3) {
            val len = W * (0.25f + seeds[k + 50] * 0.2f)
            val span = W + len
            val x = ((seeds[k + 60] * span + time * 260f * d * gust) % span) - len
            val y = seeds[k + 70] * H
            drawLine(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.18f * gust), Color.Transparent), x, x + len),
                Offset(x, y), Offset(x + len, y + 4f * d), 1f * d, StrokeCap.Round)
        }
    }
}

/**
 * Öğenin kendi çiziminde (yerel koordinat) çağrılır: meteor yanığı ve elektrik parıltısı yalnızca içeriğin
 * pikselleri üstüne boyanır (SrcAtop); yazının, simgenin çevresindeki boş alana taşmaz.
 */
internal fun DrawScope.drawFxMarks(t: FxTarget) {
    if (t.glow > 0.01f) {
        drawRect(Color(0xFF8CEBFF).copy(alpha = 0.65f * t.glow), blendMode = BlendMode.SrcAtop)
    }
    if (t.scorches.isEmpty()) return
    val now = System.currentTimeMillis()
    val w = size.width
    val h = size.height
    for (s in t.scorches) {
        val age = ((now - s.born) / 1000f).coerceAtLeast(0f)
        val fade = (1f - age / SCORCH_LIFE).coerceIn(0f, 1f)
        if (fade <= 0f) continue
        val hot = exp(-age * 1.4f)
        val c = Offset(s.rx * w, s.ry * h)
        val rr = s.r * 1.8f
        drawCircle(Brush.radialGradient(
            0f to Color(0xFF1A0E06).copy(alpha = 0.75f * fade), 0.55f to Color(0xFF3A2414).copy(alpha = 0.45f * fade), 1f to Color.Transparent,
            center = c, radius = rr,
        ), rr, c, blendMode = BlendMode.SrcAtop)
        if (hot > 0.02f) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD27A).copy(alpha = hot), Color(0xFFFF5A1F).copy(alpha = 0.6f * hot), Color.Transparent), c, s.r),
                s.r, c, blendMode = BlendMode.SrcAtop)
        }
    }
}
