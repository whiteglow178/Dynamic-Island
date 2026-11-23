package com.example.dynamicisland

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * ADA SAKİNLERİ (Ana Sayfa > Ada olayları ile): genişletilmiş müzik adasında yaşayan birkaç küçük hayvan.
 * Kapak, başlık, söz ve çubuğun GERÇEK şeklinin (piksel taraması) üstünde yürür, zıplar, düşer; seçili ada olayına göre davranır:
 *  yağmur / asit : kapağın ya da bir öğenin altına sığınır, yer yoksa şemsiye açar; açıkta kalan ıslanıp titrer
 *  kar / tipi    : biri kardan adam yapar, diğerleri yardım eder ya da dilini çıkarıp kar tanesi yakalar; üstlerine kar birikir
 *  güneş         : güneş gözlüğüyle uzanıp güneşlenir
 *  meteor        : oturup gökyüzüne bakar; yakına düşen meteordan korkup kaçar
 *  sualtı        : dalış fanusuyla yüzer, ağzından kabarcık çıkar, balık kovalar
 *  havai fişek   : oturup izler; yakındaki patlamada kulaklarını kapatır
 *  ateşböceği    : ateşböceklerini kovalayıp zıplar; bazıları uyur
 *  kum fırtınası : gözlerini kısar, rüzgâra karşı eğilir, bir öğenin rüzgâraltına saklanır
 *  kutup ışığı   : oturup hayranlıkla izler
 *  elektrik      : durduğu öğeye ark çarparsa tüyleri diken diken olur
 * Ortak: söz satırında zıplayıp nakaratta dans eder; telefon eğilince kayar, sallanınca savrulup sersemler.
 */

private const val P_IDLE = 0
private const val P_WALK = 1
private const val P_SIT = 2
private const val P_SLEEP = 3
private const val P_GOTO = 4
private const val P_BUILD = 5
private const val P_LIE = 6

private const val E_NONE = 0
private const val E_BANG = 1
private const val E_NOTE = 2
private const val E_HEART = 3
private const val E_ZZZ = 4
private const val E_SWEAT = 5
private const val E_SPARK = 6
private const val E_DIZZY = 7

/** Hayvan türleri: 0 kedi, 1 tavşan, 2 civciv, 3 penguen, 4 tilki. */
const val FX_PET_KINDS = 5

private class Pet(val kind: Int, val seed: Float) {
    var x = 0f
    var y = 0f                    // ayakların değdiği nokta
    var vx = 0f
    var vy = 0f
    var ground = false
    var groundId: String? = null  // üstünde durduğu öğe (null = zemin)
    var face = 1f
    var state = P_IDLE
    var timer = 0f
    var gx = 0f
    var gy = 0f
    var speed = 0f
    var jumpCd = 0f
    var walk = 0f
    var blinkIn = 2f
    var blink = 0f
    var emote = E_NONE
    var emoteT = 0f
    var eyes = 0                  // 0 açık, 1 kapalı, 2 mutlu, 3 iri, 4 kısık, 5 sersem, 6 parıltılı
    var mouth = 0                 // 0 küçük, 1 açık, 2 gülümseme
    var squash = 0f
    var lean = 0f
    var bristle = 0f
    var dizzy = 0f
    var wet = 0f
    var snowy = 0f
    var startle = 0f
    var cover = 0f
    var lookUp = false
    var prop = 0                  // 0 yok, 1 şemsiye, 2 güneş gözlüğü, 3 dalış fanusu
    var dance = 0f
    var bubbleIn = 1f
}

private class Snowman(var x: Float, var y: Float, val tid: String?, val ox: Float) {
    var grow = 0f
    var melt = 0f
}

private class PetBubble(var x: Float, var y: Float, val r: Float, var life: Float)

internal class FxPets {
    private val rnd = java.util.Random()
    private fun f(): Float = rnd.nextFloat()
    private val pets = ArrayList<Pet>()
    private val bubbles = ArrayList<PetBubble>()
    private var snowman: Snowman? = null
    private var time = 0f
    private var weather = -1
    private var lastImpact = 0L
    private var lastBoom = 0L
    private var lastZap = 0L
    private var wasLightning = false
    private var W = 0f
    private var H = 0f
    private var d = 1f
    private var s = 15f              // hayvan boyu (px)

    fun clear() { pets.clear(); bubbles.clear(); snowman = null; weather = -1 }

    // ================================================================ Zemin / çarpışma (gerçek şekil)
    /** Ayak hizasından (yFeet) aşağıdaki en yüksek yüzey: öğelerin içerik tepesi ya da zemin. Gövde genişliği boyunca bakılır. */
    private fun groundAt(targets: Collection<FxTarget>, x: Float, yFeet: Float, p: Pet?): Float {
        var g = H - 1.5f * d
        var gid: String? = null
        val hw = s * 0.32f
        for (sx in floatArrayOf(x - hw, x, x + hw)) for (t in targets) {
            if (t.gone) continue
            val top = t.topAt(sx) ?: continue
            if (top >= yFeet - 3f * d && top < g) { g = top; gid = t.id }
        }
        if (p != null) p.groundId = gid
        return g
    }

    private fun solidAt(targets: Collection<FxTarget>, x: Float, y: Float): Boolean {
        for (t in targets) if (!t.gone && t.solid(x, y)) return true
        return false
    }

    /** Bu noktanın üstü bir öğeyle örtülü mü (yağmurdan korunur)? Başın üstünde yeterli boşluk da olmalı. */
    private fun covered(targets: Collection<FxTarget>, x: Float, yFeet: Float): Boolean {
        for (t in targets) {
            if (t.gone) continue
            val b = t.botAt(x) ?: continue
            if (b < yFeet - s * 1.05f && b > 0f) return true
        }
        return false
    }

    /** Solunda (rüzgârüstünde) gövde yüksekliğinde bir öğe var mı (kum rüzgârından korunur)? */
    private fun leeward(targets: Collection<FxTarget>, x: Float, yFeet: Float): Boolean =
        solidAt(targets, x - s * 0.9f, yFeet - s * 0.5f) || solidAt(targets, x - s * 1.5f, yFeet - s * 0.5f)

    /**
     * Panelde koşulu sağlayan en yakın duruş noktası: her dikey şeritte öğelerin üstleri ve zemin yüzey adayıdır.
     * Diğer hayvanların hedeflerine yakın noktalar cezalandırılır (üst üste binmesinler).
     */
    private fun findSpot(targets: Collection<FxTarget>, p: Pet, ok: (Float, Float) -> Boolean): Offset? {
        var best: Offset? = null
        var bestScore = Float.MAX_VALUE
        var x = 10f * d
        while (x < W - 10f * d) {
            val tops = ArrayList<Float>()
            tops.add(H - 1.5f * d)
            for (t in targets) { if (!t.gone) t.topAt(x)?.let { tops.add(it) } }
            for (yy in tops) {
                if (solidAt(targets, x, yy - s * 0.6f)) continue        // başın yeri yok
                if (!ok(x, yy)) continue
                var score = abs(x - p.x) + abs(yy - p.y) * 0.6f
                for (o in pets) if (o !== p && (o.state == P_GOTO || o.state == P_SIT || o.state == P_BUILD)) {
                    if (abs(o.gx - x) < s * 0.8f && abs(o.gy - yy) < s) score += 400f * d
                }
                if (score < bestScore) { bestScore = score; best = Offset(x, yy) }
            }
            x += 6f * d
        }
        return best
    }

    // ================================================================ Adım
    fun step(
        count: Int, weatherNow: Int, dt: Float, now: Long, W: Float, H: Float, d: Float,
        targets: Collection<FxTarget>, byId: Map<String, FxTarget>, scenes: FxScenes,
        tilt: Float, shook: Boolean, cue: Int, lightning: Boolean,
    ) {
        this.W = W; this.H = H; this.d = d
        curTargets = targets; curById = byId
        s = 15f * d
        time += dt
        val n = count.coerceIn(1, FX_PET_KINDS)
        while (pets.size < n) {
            val p = Pet(pets.size % FX_PET_KINDS, f())
            p.x = W * (0.15f + 0.7f * (pets.size + 0.5f) / n) + (f() - 0.5f) * 12f * d
            p.y = -s * (1f + pets.size * 0.6f)                                  // yukarıdan düşerek gelir
            p.face = if (f() < 0.5f) -1f else 1f
            p.blinkIn = 1f + f() * 3f
            pets.add(p)
        }
        while (pets.size > n) pets.removeAt(pets.size - 1)

        if (weatherNow != weather) {
            weather = weatherNow
            for (p in pets) {
                p.prop = when (weather) { FXW_SUN -> 2; FXW_SEA -> 3; else -> 0 }
                p.state = P_IDLE; p.timer = f() * 0.8f; p.lookUp = false
                if (weather != FXW_SEA && !p.ground) p.vy = 0f
                if (weather == FXW_SEA) p.ground = false
            }
        }
        val swim = weather == FXW_SEA

        // ---- sahneden gelen olaylar
        val impactNew = scenes.impactAt != 0L && scenes.impactAt != lastImpact
        if (impactNew) lastImpact = scenes.impactAt
        val boomNew = scenes.boomAt != 0L && scenes.boomAt != lastBoom
        if (boomNew) lastBoom = scenes.boomAt
        val zapNew = scenes.zapAt != 0L && scenes.zapAt != lastZap
        if (zapNew) lastZap = scenes.zapAt
        val boltNew = lightning && !wasLightning
        wasLightning = lightning

        // ---- kardan adam
        snowman?.let { sm ->
            val t = sm.tid?.let { byId[it] }
            if (sm.tid != null) {
                val top = if (t == null || t.gone) null else t.topAt(t.rect.left + sm.ox)
                if (top == null) snowman = null else { sm.x = t!!.rect.left + sm.ox; sm.y = top }
            }
            if (weather != 2 && weather != FXW_BLIZZARD) {
                sm.melt += dt / 4f
                if (sm.melt >= 1f) snowman = null
            }
            if (shook) snowman = null
        }

        for ((i, p) in pets.withIndex()) {
            // ---- zamanlayıcılar
            p.timer -= dt
            p.jumpCd -= dt
            if (p.emoteT > 0f) { p.emoteT -= dt; if (p.emoteT <= 0f) p.emote = E_NONE }
            if (p.squash > 0f) p.squash = (p.squash - dt * 3f).coerceAtLeast(0f)
            if (p.bristle > 0f) p.bristle = (p.bristle - dt * 0.9f).coerceAtLeast(0f)
            if (p.dizzy > 0f) p.dizzy -= dt
            if (p.startle > 0f) p.startle -= dt
            if (p.cover > 0f) p.cover -= dt
            if (p.dance > 0f) p.dance -= dt
            p.blinkIn -= dt
            if (p.blinkIn <= 0f) { p.blink = 0.12f; p.blinkIn = 2f + f() * 4f }
            if (p.blink > 0f) p.blink -= dt

            // ---- olaylara tepki
            if (shook) {
                p.vy = -(220f + f() * 120f) * d; p.vx = (f() - 0.5f) * 220f * d; p.ground = false
                p.dizzy = 2.5f; emote(p, E_DIZZY, 2.5f); p.state = P_IDLE; p.timer = 2.6f
                p.snowy = 0f; p.wet *= 0.5f
            }
            if (cue != 0 && (p.ground || swim) && p.state != P_SLEEP && p.startle <= 0f) {
                if (!swim) { p.vy = -(if (cue == 2) 250f else 150f) * d; p.ground = false } else p.vy -= 60f * d
                emote(p, E_NOTE, 1.3f)
                if (cue == 2) p.dance = 2.6f
            }
            if (impactNew && hypot(p.x - scenes.impactX, p.y - scenes.impactY) < (if (scenes.impactBig) 130f else 80f) * d) startle(p, scenes.impactX)
            if (boomNew) {
                if (hypot(p.x - scenes.boomX, p.y - scenes.boomY) < 95f * d) { p.cover = 0.8f; emote(p, E_BANG, 0.8f) }
                else if (p.lookUp) emote(p, E_SPARK, 0.9f)
            }
            if (zapNew && p.ground && p.groundId != null && (p.groundId == scenes.zapA || p.groundId == scenes.zapB)) {
                p.bristle = 1f; startle(p, p.x - p.face)
            }
            if (boltNew) { p.startle = max(p.startle, 0.6f); emote(p, E_BANG, 0.7f) }

            // ---- karar
            if (swim) swimStep(p, i, dt, targets, scenes)
            else {
                if (p.timer <= 0f && p.ground && p.startle <= 0f && p.dizzy <= 0f) decide(p, i, targets, scenes)
                walkStep(p, i, dt, targets, scenes, tilt)
            }

            // ---- hava etkileri
            val open = !covered(targets, p.x, p.y)
            when (weather) {
                1, 3 -> {
                    if (open && p.prop != 1) p.wet = (p.wet + dt * 0.35f).coerceAtMost(1f)
                    else p.wet = (p.wet - dt * 0.15f).coerceAtLeast(0f)
                    if (!open && p.prop == 1) p.prop = 0
                }
                2, FXW_BLIZZARD -> { if (open && p.state != P_BUILD) p.snowy = (p.snowy + dt * 0.05f).coerceAtMost(1f) }
                else -> { p.wet = (p.wet - dt * 0.2f).coerceAtLeast(0f); p.snowy = (p.snowy - dt * 0.1f).coerceAtLeast(0f) }
            }
            p.lean = when {
                weather == FXW_SAND && p.ground && !leeward(targets, p.x, p.y) -> -0.28f * scenes.windNow.coerceAtMost(1.4f)
                weather == FXW_BLIZZARD && p.ground && open -> -0.16f * scenes.windNow.coerceAtMost(1.4f)
                swim -> (p.vx / (60f * d)).coerceIn(-0.4f, 0.4f)
                abs(tilt) > 0.2f && p.ground -> tilt * 0.45f
                else -> 0f
            }

            // ---- ifade
            p.eyes = when {
                p.dizzy > 0f -> 5
                p.startle > 0f || p.bristle > 0.3f -> 3
                p.state == P_SLEEP || p.blink > 0f -> 1
                p.cover > 0f -> 1
                weather == FXW_SAND && p.lean != 0f -> 4
                p.emote == E_SPARK || (p.lookUp && (weather == FXW_AURORA || weather == FXW_FIREWORKS)) -> 6
                p.dance > 0f || p.emote == E_HEART || p.state == P_LIE -> 2
                else -> 0
            }
            p.mouth = when {
                p.startle > 0f -> 1
                p.lookUp && (weather == 2 || weather == FXW_BLIZZARD) -> 1        // kar tanesi yakalamak için ağız açık
                p.dance > 0f || p.state == P_LIE -> 2
                else -> 0
            }
            if (p.state == P_SLEEP && p.emote == E_NONE) emote(p, E_ZZZ, 1.5f)
            if (p.wet > 0.6f && p.emote == E_NONE && f() < dt * 0.3f) emote(p, E_SWEAT, 1f)
        }

        // ---- ayrışma: üst üste binmesinler
        for (a in pets) for (b in pets) {
            if (a === b || !a.ground || !b.ground || a.state == P_BUILD || b.state == P_BUILD) continue
            val dx = a.x - b.x
            if (abs(dx) < s * 0.7f && abs(a.y - b.y) < s * 0.5f) a.x += (if (dx >= 0f) 1f else -1f) * 22f * d * dt
        }

        // ---- kabarcıklar
        var k = bubbles.size - 1
        while (k >= 0) {
            val b = bubbles[k]
            b.y -= (25f + b.r / d * 6f) * d * dt
            b.x += sin(time * 5f + b.r) * 6f * d * dt
            b.life -= dt
            if (b.life <= 0f || b.y < -4f * d) bubbles.removeAt(k)
            k--
        }
    }

    private fun emote(p: Pet, e: Int, t: Float) { p.emote = e; p.emoteT = t }

    private fun startle(p: Pet, fromX: Float) {
        p.startle = 1.3f
        emote(p, E_BANG, 1f)
        p.face = if (p.x >= fromX) 1f else -1f
        if (p.ground) { p.vy = -200f * d; p.ground = false }
        p.vx = p.face * 70f * d
        p.state = P_GOTO
        p.gx = (p.x + p.face * 70f * d).coerceIn(10f * d, W - 10f * d)
        p.gy = p.y
        p.speed = 70f * d
        p.timer = 2.5f
        p.lookUp = false
        p.snowy = 0f
    }

    private fun goTo(p: Pet, x: Float, y: Float, speed: Float = 34f) {
        p.state = P_GOTO; p.gx = x; p.gy = y; p.speed = speed * d; p.timer = 7f; p.lookUp = false
    }

    private fun wander(p: Pet) {
        p.state = P_WALK; p.face = if (f() < 0.5f) -1f else 1f; p.speed = (18f + f() * 10f) * d
        p.timer = 2f + f() * 3f; p.lookUp = false
    }

    private fun sit(p: Pet, t: Float, look: Boolean = false) {
        p.state = P_SIT; p.timer = t; p.lookUp = look; p.vx = 0f
    }

    /** Hava durumuna göre bir sonraki davranış. */
    private fun decide(p: Pet, i: Int, targets: Collection<FxTarget>, scenes: FxScenes) {
        when (weather) {
            1, 3 -> {
                if (!covered(targets, p.x, p.y)) {
                    val spot = findSpot(targets, p) { x, y -> covered(targets, x, y) }
                    if (spot != null) goTo(p, spot.x, spot.y, 46f) else { p.prop = 1; wander(p) }
                } else sit(p, 3f + f() * 4f)
            }
            2, FXW_BLIZZARD -> {
                val sm = snowman
                when {
                    sm == null && i == 0 -> startSnowman(p)
                    sm != null && sm.grow < 1f && sm.melt == 0f && f() < 0.5f -> {
                        val side = if (p.x < sm.x) -1f else 1f
                        goTo(p, sm.x + side * s * 0.75f, sm.y)
                    }
                    f() < 0.5f -> sit(p, 2f + f() * 3f, look = true)        // dilini çıkarıp kar tanesi yakalar
                    else -> wander(p)
                }
            }
            FXW_SUN -> if (f() < 0.65f) { p.state = P_LIE; p.timer = 5f + f() * 5f; p.vx = 0f } else wander(p)
            FXW_METEOR, FXW_AURORA, FXW_FIREWORKS -> if (f() < 0.7f) sit(p, 3f + f() * 4f, look = true) else wander(p)
            FXW_FIREFLY -> {
                val ffs = scenes.fireflies()
                val near = ffs.minByOrNull { abs(it.x - p.x) + abs(it.y - p.y) }
                when {
                    near != null && f() < 0.6f -> goTo(p, near.x.coerceIn(10f * d, W - 10f * d), p.y, 40f)
                    pets.count { it.state == P_SLEEP } < (pets.size + 1) / 2 && f() < 0.4f -> { p.state = P_SLEEP; p.timer = 6f + f() * 6f; p.vx = 0f }
                    else -> wander(p)
                }
            }
            FXW_SAND -> {
                if (!leeward(targets, p.x, p.y)) {
                    val spot = findSpot(targets, p) { x, y -> leeward(targets, x, y) }
                    if (spot != null) goTo(p, spot.x, spot.y, 26f) else wander(p)
                } else sit(p, 3f + f() * 3f)
            }
            else -> {
                val r = f()
                when {
                    r < 0.42f -> wander(p)
                    r < 0.66f -> sit(p, 2f + f() * 4f)
                    r < 0.8f && pets.count { it.state == P_SLEEP } < 1 -> { p.state = P_SLEEP; p.timer = 5f + f() * 6f; p.vx = 0f }
                    pets.size > 1 -> {                                      // oyun: bir arkadaşını kovalar
                        val o = pets[(i + 1 + rnd.nextInt(pets.size - 1)) % pets.size]
                        goTo(p, o.x - sign(o.x - p.x) * s * 0.8f, o.y, 48f)
                    }
                    else -> wander(p)
                }
            }
        }
    }

    /** Hayvanın önünde, aynı yüzeyde kardan adama başlar (yüzey yoksa hayvanın durduğu yerde). */
    private fun startSnowman(p: Pet) {
        var x = (p.x + p.face * s * 0.9f).coerceIn(14f * d, W - 14f * d)
        if (abs(groundAt(curTargets, x, p.y - 2f * d, null) - p.y) > 3f * d) x = p.x
        val tid = p.groundId
        val left = tid?.let { curById[it]?.rect?.left } ?: 0f
        snowman = Snowman(x, p.y, tid, x - left)
        p.state = P_BUILD; p.timer = 12f; p.vx = 0f
        p.face = if (x >= p.x) 1f else -1f
    }

    private var curTargets: Collection<FxTarget> = emptyList()
    private var curById: Map<String, FxTarget> = emptyMap()

    // ---------------------------------------------------------------- Yürüme, zıplama, düşme
    private fun walkStep(p: Pet, i: Int, dt: Float, targets: Collection<FxTarget>, scenes: FxScenes, tilt: Float) {
        val g = 900f * d
        if (p.ground) {
            var want = 0f
            when (p.state) {
                P_WALK -> want = p.face * p.speed
                P_GOTO -> {
                    val dx = p.gx - p.x
                    val dy = p.gy - p.y
                    if (abs(dx) < 3f * d && abs(dy) < 6f * d) {
                        val sm = snowman
                        if (sm != null && sm.grow < 1f && abs(sm.x - p.x) < s * 1.2f && (weather == 2 || weather == FXW_BLIZZARD)) {
                            p.state = P_BUILD; p.timer = 10f; p.face = sign(sm.x - p.x).let { if (it == 0f) 1f else it }
                        } else if (p.startle > 0f) {
                            p.state = P_IDLE; p.timer = 0.6f
                        } else sit(p, 3f + f() * 4f, look = weather == FXW_METEOR || weather == FXW_AURORA || weather == FXW_FIREWORKS)
                    } else if (abs(dy) > 8f * d && abs(dx) < 80f * d && p.jumpCd <= 0f) {
                        jumpTo(p, p.gx, p.gy)
                    } else {
                        p.face = if (dx >= 0f) 1f else -1f
                        want = p.face * p.speed
                    }
                    // ateşböceği kovalama: altına gelince zıplar
                    if (weather == FXW_FIREFLY && p.ground) {
                        val ff = scenes.fireflies().minByOrNull { abs(it.x - p.x) }
                        if (ff != null && abs(ff.x - p.x) < 14f * d && ff.y < p.y && p.y - ff.y < 70f * d && p.jumpCd <= 0f) {
                            p.vy = -sqrt(2f * g * (p.y - ff.y + 4f * d)); p.vx = (ff.x - p.x) * 2f; p.ground = false; p.jumpCd = 1.2f
                            emote(p, E_SPARK, 0.8f)
                        }
                    }
                }
                P_BUILD -> {
                    val sm = snowman
                    if (sm == null || sm.melt > 0f) { p.state = P_IDLE; p.timer = 0.5f }
                    else {
                        p.face = if (sm.x >= p.x) 1f else -1f
                        if (abs(sm.x - p.x) > s * 0.95f) want = p.face * 24f * d
                        else if (abs(sm.x - p.x) < s * 0.55f) want = -p.face * 24f * d       // çok yakın: bir adım geri
                        sm.grow = (sm.grow + dt / 9f).coerceAtMost(1f)
                        if (((time * 6f + p.seed * 10f).toInt() % 4) == 0) p.squash = max(p.squash, 0.12f)   // kar tapıştırma
                        if (sm.grow >= 1f) { sit(p, 4f); emote(p, E_HEART, 1.6f) }
                    }
                }
                P_IDLE -> if (p.startle > 0f) want = p.face * 60f * d
                else -> {}
            }
            if (p.dance > 0f && p.jumpCd <= 0f) { p.vy = -140f * d; p.ground = false; p.jumpCd = 0.35f; p.face = -p.face }

            // eğim: kayar
            if (abs(tilt) > 0.25f) want += tilt * 140f * d
            // kum rüzgârı itişi
            if (weather == FXW_SAND && !leeward(targets, p.x, p.y)) want += 14f * d * scenes.windNow

            p.vx = want
            if (want != 0f) {
                // önde duvar (harf, kapak): alçaksa üstüne çıkar, değilse geri döner
                val ahead = p.x + sign(want) * s * 0.42f
                if (solidAt(targets, ahead, p.y - s * 0.45f)) {
                    val top = targets.mapNotNull { t -> if (t.gone) null else t.topAt(ahead) }.filter { it < p.y }.maxOrNull()
                    if (top != null && p.y - top < s * 2.2f && p.jumpCd <= 0f) {
                        jumpTo(p, ahead + sign(want) * s * 0.3f, top)
                    } else {
                        p.face = -sign(want); p.vx = 0f
                        if (p.state == P_WALK) p.timer = min(p.timer, 0.8f)
                    }
                    return
                }
                p.walk += dt * abs(want) / (s * 0.18f)
            }
            val nx = (p.x + p.vx * dt).coerceIn(8f * d, W - 8f * d)
            if (nx == 8f * d || nx == W - 8f * d) p.face = if (nx < W / 2f) 1f else -1f
            val gNew = groundAt(targets, nx, p.y, p)
            if (gNew > p.y + 6f * d) {
                // kenar: gezinirken çoğunlukla geri döner, bir yere giderken atlar
                if (p.state == P_WALK && f() < 0.75f) { p.face = -p.face; p.vx = 0f; return }
                p.x = nx; p.ground = false; p.vy = -40f * d
                return
            }
            p.x = nx
            p.y += (gNew - p.y) * min(1f, 18f * dt)
        } else {
            p.vy += g * dt
            val py = p.y
            p.x = (p.x + p.vx * dt).coerceIn(8f * d, W - 8f * d)
            p.y += p.vy * dt
            if (p.vy < 0f && solidAt(targets, p.x, p.y - s * 0.95f)) p.vy = 0f      // başı tavana çarptı
            val gNew = groundAt(targets, p.x, py, p)
            if (p.vy >= 0f && p.y >= gNew) {
                p.y = gNew
                p.squash = min(0.35f, p.vy / (900f * d))
                p.vy = 0f; p.vx = 0f; p.ground = true
                if (p.state == P_IDLE && p.timer > 1f) p.timer = 0.3f
            }
        }
    }

    private fun jumpTo(p: Pet, tx: Float, ty: Float) {
        val g = 900f * d
        val apex = min(p.y, ty) - s * 0.9f
        val rise = (p.y - apex).coerceAtLeast(4f * d)
        val vy0 = -sqrt(2f * g * rise)
        val t1 = -vy0 / g
        val fall = (ty - apex).coerceAtLeast(1f)
        val t2 = sqrt(2f * fall / g)
        p.vy = vy0
        p.vx = ((tx - p.x) / (t1 + t2)).coerceIn(-220f * d, 220f * d)
        p.face = if (tx >= p.x) 1f else -1f
        p.ground = false
        p.jumpCd = 0.6f
    }

    // ---------------------------------------------------------------- Yüzme (sualtı)
    private fun swimStep(p: Pet, i: Int, dt: Float, targets: Collection<FxTarget>, scenes: FxScenes) {
        p.ground = false
        if (p.timer <= 0f || hypot(p.gx - p.x, p.gy - p.y) < 10f * d) {
            val fish = scenes.fishes()
            if (fish.isNotEmpty() && f() < 0.35f) { val fp = fish[rnd.nextInt(fish.size)]; p.gx = fp.x; p.gy = fp.y }
            else { p.gx = W * (0.08f + 0.84f * f()); p.gy = H * (0.15f + 0.7f * f()) }
            p.gx = p.gx.coerceIn(10f * d, W - 10f * d); p.gy = p.gy.coerceIn(s, H - 4f * d)
            p.timer = 3f + f() * 4f
            p.state = P_IDLE
        }
        val dx = p.gx - p.x; val dy = p.gy - p.y
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val sp = 26f * d
        p.vx += (dx / dist * sp - p.vx) * min(1f, 1.5f * dt)
        p.vy += (dy / dist * sp * 0.7f - p.vy) * min(1f, 1.5f * dt)
        p.vy += sin(time * 2.2f + p.seed * 9f) * 14f * d * dt                     // süzülme
        val nx = p.x + p.vx * dt
        val ny = p.y + p.vy * dt
        if (solidAt(targets, nx, ny - s * 0.45f)) { p.vx = -p.vx * 0.5f; p.vy = -p.vy * 0.5f; p.timer = 0f }
        else { p.x = nx.coerceIn(8f * d, W - 8f * d); p.y = ny.coerceIn(s * 0.8f, H - 1.5f * d) }
        if (abs(p.vx) > 2f * d) p.face = if (p.vx > 0f) 1f else -1f
        p.walk += dt * 6f
        p.bubbleIn -= dt
        if (p.bubbleIn <= 0f) {
            p.bubbleIn = 0.7f + f() * 1.2f
            bubbles.add(PetBubble(p.x + p.face * s * 0.35f, p.y - s * 0.7f, (0.8f + f() * 1.3f) * d, 3f))
        }
    }

    // ================================================================ Çizim
    fun DrawScope.render(d: Float) {
        if (pets.isEmpty()) return
        snowman?.let { drawSnowman(it, d) }
        for (b in bubbles) {
            val c = Offset(b.x, b.y)
            drawCircle(Color.White.copy(alpha = 0.55f), b.r, c, style = Stroke(0.6f * d))
            drawCircle(Color.White.copy(alpha = 0.85f), b.r * 0.3f, Offset(c.x - b.r * 0.35f, c.y - b.r * 0.35f))
        }
        for (p in pets) drawPet(p, d)
    }

    private fun DrawScope.drawSnowman(sm: Snowman, d: Float) {
        val g = sm.grow
        if (g <= 0.01f) return
        val melt = sm.melt
        val k = 1f - melt
        val white = Color(0xFFF7FBFF)
        val shade = Color(0xFFD6E2F0)
        val r1 = 6.5f * d * (g / 0.4f).coerceIn(0f, 1f) * (0.6f + 0.4f * k)
        val r2 = 4.6f * d * ((g - 0.4f) / 0.3f).coerceIn(0f, 1f) * k
        val r3 = 3.4f * d * ((g - 0.7f) / 0.2f).coerceIn(0f, 1f) * k
        val y1 = sm.y - r1 * 0.9f
        val y2 = y1 - r1 * 0.75f - r2 * 0.7f
        val y3 = y2 - r2 * 0.75f - r3 * 0.7f
        if (melt > 0f) drawOval(white.copy(alpha = 0.7f), Offset(sm.x - r1 * (1f + melt), sm.y - 1.5f * d), Size(r1 * 2f * (1f + melt), 2.5f * d))
        drawCircle(white, r1, Offset(sm.x, y1)); drawCircle(shade, r1, Offset(sm.x, y1), style = Stroke(0.6f * d))
        if (r2 > 0.5f) { drawCircle(white, r2, Offset(sm.x, y2)); drawCircle(shade, r2, Offset(sm.x, y2), style = Stroke(0.6f * d)) }
        if (r3 > 0.5f) { drawCircle(white, r3, Offset(sm.x, y3)); drawCircle(shade, r3, Offset(sm.x, y3), style = Stroke(0.6f * d)) }
        if (g > 0.9f && melt < 0.5f) {
            val eye = Color(0xFF1B1B1B)
            drawCircle(eye, 0.45f * d, Offset(sm.x - r3 * 0.35f, y3 - r3 * 0.15f))
            drawCircle(eye, 0.45f * d, Offset(sm.x + r3 * 0.35f, y3 - r3 * 0.15f))
            val nose = Path().apply { moveTo(sm.x, y3); lineTo(sm.x + r3 * 1.1f, y3 + r3 * 0.15f); lineTo(sm.x, y3 + r3 * 0.3f); close() }
            drawPath(nose, Color(0xFFFF8A2B))
            for (bx in floatArrayOf(-1f, 1f)) {
                drawLine(Color(0xFF6B4A2E), Offset(sm.x + bx * r2 * 0.8f, y2), Offset(sm.x + bx * r2 * 2f, y2 - r2 * 0.9f), 0.7f * d, StrokeCap.Round)
            }
            drawCircle(eye, 0.4f * d, Offset(sm.x, y2 - r2 * 0.2f)); drawCircle(eye, 0.4f * d, Offset(sm.x, y2 + r2 * 0.3f))
        }
    }

    private fun DrawScope.drawPet(p: Pet, d: Float) {
        val s = this@FxPets.s
        val lying = p.state == P_SLEEP || p.state == P_LIE
        val shiver = if (p.wet > 0.5f && !lying) sin(time * 60f + p.seed) * 0.6f * d * p.wet else 0f
        val bob = if (p.state == P_SIT && p.lookUp) sin(time * 1.5f + p.seed * 6f) * 0.3f * d else 0f
        val dz = if (p.dance > 0f) sin(time * 14f) * 0.12f else 0f
        withTransform({
            translate(p.x + shiver, p.y + bob)
            rotate((p.lean + dz) * 57.3f, Offset.Zero)
            scale(p.face * (1f + p.squash * 0.6f) * (if (lying) 1.12f else 1f), (1f - p.squash) * (if (lying) 0.72f else 1f), Offset.Zero)
        }) {
            drawCritter(p, s, d, lying)
        }
        // ---- ifade balonu (aynalanmaz)
        if (p.emote != E_NONE) drawEmote(p.emote, Offset(p.x + p.face * s * 0.15f, p.y - s * (if (lying) 1.05f else 1.45f)), s, d, p)
        // ---- ıslaklık damlaları
        if (p.wet > 0.35f && !lying) {
            val ph = (time * 1.3f + p.seed) % 1f
            drawCircle(Color(0xFF7FC8FF).copy(alpha = (1f - ph) * p.wet), 0.9f * d, Offset(p.x - p.face * s * 0.35f, p.y - s * 0.35f + ph * s * 0.4f))
        }
    }

    /** Hayvan (yerel: ayak ortası 0,0; sağa bakar). */
    private fun DrawScope.drawCritter(p: Pet, s: Float, d: Float, lying: Boolean) {
        val legPh = if (p.ground && abs(p.vx) > 1f && !lying) sin(p.walk) * s * 0.08f else 0f
        val swimPh = if (!p.ground && weather == FXW_SEA) sin(p.walk * 1.5f) * s * 0.06f else 0f
        val lp = legPh + swimPh
        val eyeC = Color(0xFF1B1B1B)
        val hx: Float
        val hy: Float
        when (p.kind) {
            1 -> { // Tavşan
                val white = Color(0xFFFFFFFF); val pink = Color(0xFFFFA8C5); val sh = Color(0xFFE6E6EE)
                legs(sh, s, lp)
                drawOval(white, Offset(-s * 0.42f, -s * 0.55f), Size(s * 0.78f, s * 0.48f))
                drawCircle(white, s * 0.11f, Offset(-s * 0.44f, -s * 0.32f))
                hx = s * 0.2f; hy = -s * 0.68f
                val droop = (p.wet * 0.9f + if (p.lean != 0f) 0.4f else 0f).coerceAtMost(1f)
                for ((ex, ew) in listOf(-0.06f to 0.17f, 0.12f to 0.17f)) {
                    withTransform({ rotate(-droop * 70f, Offset(hx + s * ex + s * ew / 2f, hy - s * 0.16f)) }) {
                        drawOval(white, Offset(hx + s * ex, hy - s * 0.72f), Size(s * ew, s * 0.58f))
                        drawOval(pink, Offset(hx + s * ex + s * 0.045f, hy - s * 0.62f), Size(s * 0.08f, s * 0.4f))
                    }
                }
                drawCircle(white, s * 0.26f, Offset(hx, hy))
                drawCircle(pink.copy(alpha = 0.7f), s * 0.045f, Offset(hx + s * 0.2f, hy + s * 0.07f))
            }
            2 -> { // Civciv
                val yel = Color(0xFFFFD43B); val dark = Color(0xFFF2B600); val orange = Color(0xFFFF8A00)
                drawLine(orange, Offset(-s * 0.08f + lp, -s * 0.14f), Offset(-s * 0.1f + lp, 0f), s * 0.05f, StrokeCap.Round)
                drawLine(orange, Offset(s * 0.08f - lp, -s * 0.14f), Offset(s * 0.1f - lp, 0f), s * 0.05f, StrokeCap.Round)
                drawCircle(yel, s * 0.32f, Offset(-s * 0.02f, -s * 0.42f))
                val flap = if (!p.ground) sin(time * 30f) * 25f else 0f
                withTransform({ rotate(-flap, Offset(-s * 0.05f, -s * 0.45f)) }) {
                    drawOval(dark, Offset(-s * 0.3f, -s * 0.5f), Size(s * 0.3f, s * 0.2f))
                }
                hx = s * 0.14f; hy = -s * 0.78f
                drawCircle(yel, s * 0.22f, Offset(hx, hy))
                val beak = Path().apply {
                    moveTo(hx + s * 0.19f, hy - s * 0.02f); lineTo(hx + s * 0.36f, hy + s * 0.04f); lineTo(hx + s * 0.19f, hy + s * 0.1f); close()
                }
                drawPath(beak, orange)
                drawLine(dark, Offset(hx - s * 0.02f, hy - s * 0.22f), Offset(hx + s * 0.04f, hy - s * 0.3f), s * 0.04f, StrokeCap.Round)
            }
            3 -> { // Penguen
                val black = Color(0xFF26303D); val white = Color(0xFFF7F7F7); val orange = Color(0xFFFF9A1F)
                drawOval(orange, Offset(-s * 0.2f + lp, -s * 0.06f), Size(s * 0.18f, s * 0.08f))
                drawOval(orange, Offset(s * 0.04f - lp, -s * 0.06f), Size(s * 0.18f, s * 0.08f))
                drawOval(black, Offset(-s * 0.3f, -s * 1.02f), Size(s * 0.6f, s * 1.0f))
                drawOval(white, Offset(-s * 0.12f, -s * 0.72f), Size(s * 0.38f, s * 0.66f))
                val flap = if (p.dance > 0f || !p.ground) sin(time * 18f) * 30f else 8f
                withTransform({ rotate(flap, Offset(-s * 0.2f, -s * 0.62f)) }) {
                    drawOval(black, Offset(-s * 0.34f, -s * 0.64f), Size(s * 0.14f, s * 0.42f))
                }
                hx = s * 0.08f; hy = -s * 0.8f
                val beak = Path().apply {
                    moveTo(hx + s * 0.16f, hy + s * 0.02f); lineTo(hx + s * 0.32f, hy + s * 0.07f); lineTo(hx + s * 0.16f, hy + s * 0.12f); close()
                }
                drawPath(beak, orange)
            }
            4 -> { // Tilki
                val orange = Color(0xFFF28A3A); val dark = Color(0xFFD9692A); val white = Color(0xFFFFF4E6)
                legs(dark, s, lp)
                val wag = sin(time * 3f + p.seed * 7f) * s * 0.08f
                val tail = Path().apply {
                    moveTo(-s * 0.36f, -s * 0.35f)
                    quadraticTo(-s * 0.75f, -s * 0.45f + wag, -s * 0.78f, -s * 0.78f + wag)
                    quadraticTo(-s * 0.55f, -s * 0.58f, -s * 0.3f, -s * 0.48f)
                    close()
                }
                drawPath(tail, orange)
                drawCircle(white, s * 0.09f, Offset(-s * 0.76f, -s * 0.74f + wag))
                drawOval(orange, Offset(-s * 0.42f, -s * 0.56f), Size(s * 0.76f, s * 0.44f))
                drawOval(white, Offset(s * 0.02f, -s * 0.48f), Size(s * 0.28f, s * 0.3f))
                hx = s * 0.2f; hy = -s * 0.7f
                for (ex in floatArrayOf(-0.16f, 0.08f)) {
                    val ear = Path().apply { moveTo(hx + s * ex, hy - s * 0.12f); lineTo(hx + s * (ex + 0.06f), hy - s * 0.46f); lineTo(hx + s * (ex + 0.16f), hy - s * 0.16f); close() }
                    drawPath(ear, orange)
                    val tip = Path().apply { moveTo(hx + s * (ex + 0.03f), hy - s * 0.34f); lineTo(hx + s * (ex + 0.06f), hy - s * 0.46f); lineTo(hx + s * (ex + 0.1f), hy - s * 0.35f); close() }
                    drawPath(tip, Color(0xFF3A2A22))
                }
                drawCircle(orange, s * 0.25f, Offset(hx, hy))
                val snout = Path().apply { moveTo(hx + s * 0.08f, hy - s * 0.02f); lineTo(hx + s * 0.4f, hy + s * 0.08f); lineTo(hx + s * 0.08f, hy + s * 0.2f); close() }
                drawPath(snout, white)
                drawCircle(eyeC, s * 0.035f, Offset(hx + s * 0.39f, hy + s * 0.08f))
            }
            else -> { // Kedi
                val gray = Color(0xFFB9BCC4); val dark = Color(0xFF9EA2AB); val pink = Color(0xFFFFB3C7)
                legs(dark, s, lp)
                val wag = sin(time * 2.5f + p.seed * 7f) * s * 0.12f
                val tail = Path().apply { moveTo(-s * 0.36f, -s * 0.4f); quadraticTo(-s * 0.7f, -s * 0.45f + wag, -s * 0.62f, -s * 0.85f + wag) }
                drawPath(tail, gray, style = Stroke(s * 0.1f, cap = StrokeCap.Round))
                drawOval(gray, Offset(-s * 0.42f, -s * 0.55f), Size(s * 0.76f, s * 0.44f))
                hx = s * 0.2f; hy = -s * 0.7f
                val earL = Path().apply { moveTo(hx - s * 0.2f, hy - s * 0.1f); lineTo(hx - s * 0.14f, hy - s * 0.42f); lineTo(hx - s * 0.0f, hy - s * 0.2f); close() }
                val earR = Path().apply { moveTo(hx + s * 0.04f, hy - s * 0.22f); lineTo(hx + s * 0.2f, hy - s * 0.42f); lineTo(hx + s * 0.25f, hy - s * 0.08f); close() }
                drawPath(earL, gray); drawPath(earR, gray)
                drawCircle(gray, s * 0.27f, Offset(hx, hy))
                drawCircle(pink, s * 0.03f, Offset(hx + s * 0.16f, hy + s * 0.08f))
            }
        }
        // ---- gözler, ağız
        val lookDy = if (p.lookUp) -s * 0.05f else 0f
        val e1 = Offset(hx + s * 0.04f, hy - s * 0.03f + lookDy)
        val e2 = Offset(hx + s * 0.17f, hy - s * 0.03f + lookDy)
        if (p.kind == 3) {
            drawCircle(Color.White, s * 0.07f, e1); drawCircle(Color.White, s * 0.07f, e2)
        }
        drawEye(e1, s * 0.045f, p.eyes, p.lookUp, d)
        drawEye(e2, s * 0.045f, p.eyes, p.lookUp, d)
        val m = Offset(hx + s * 0.12f, hy + s * 0.14f)
        when (p.mouth) {
            1 -> drawOval(Color(0xFF5A2330), Offset(m.x - s * 0.035f, m.y - s * 0.03f), Size(s * 0.07f, s * 0.08f))
            2 -> drawArc(eyeC, 20f, 140f, false, Offset(m.x - s * 0.06f, m.y - s * 0.08f), Size(s * 0.12f, s * 0.1f), style = Stroke(0.6f * d, cap = StrokeCap.Round))
        }
        // ---- karlanma, tüy kabarması, kulak kapama
        if (p.snowy > 0.1f) {
            val sw = Color.White.copy(alpha = p.snowy)
            drawOval(sw, Offset(hx - s * 0.18f, hy - s * 0.3f), Size(s * 0.36f, s * 0.12f * p.snowy + s * 0.04f))
            drawOval(sw, Offset(-s * 0.3f, -s * 0.58f), Size(s * 0.42f, s * 0.08f * p.snowy + s * 0.03f))
        }
        if (p.bristle > 0.05f) {
            val bc = Color(0xFFFFE08A).copy(alpha = p.bristle)
            for (k in 0 until 9) {
                val a = PI.toFloat() * (0.95f + k * 0.13f)
                val cx = -s * 0.05f; val cy = -s * 0.42f
                val r0 = s * 0.38f; val r1 = r0 + s * 0.16f * p.bristle
                drawLine(bc, Offset(cx + cos(a) * r0, cy + sin(a) * r0 * 0.7f), Offset(cx + cos(a) * r1, cy + sin(a) * r1 * 0.7f), 0.7f * d, StrokeCap.Round)
            }
        }
        if (p.cover > 0f) {
            val paw = when (p.kind) { 1 -> Color.White; 2 -> Color(0xFFF2B600); 3 -> Color(0xFF26303D); 4 -> Color(0xFFF28A3A); else -> Color(0xFFB9BCC4) }
            drawCircle(paw, s * 0.08f, Offset(hx - s * 0.2f, hy - s * 0.05f))
            drawCircle(paw, s * 0.08f, Offset(hx + s * 0.26f, hy - s * 0.05f))
        }
        // ---- aksesuarlar
        when (p.prop) {
            1 -> { // şemsiye
                val col = when (p.kind) { 1 -> Color(0xFFFF6FA5); 2 -> Color(0xFF4DA3FF); 3 -> Color(0xFFFFC23D); 4 -> Color(0xFF7ED957); else -> Color(0xFFFF5A5A) }
                val top = Offset(s * 0.05f, -s * 1.42f)
                drawLine(Color(0xFF5B4636), Offset(s * 0.05f, -s * 0.45f), top, 0.7f * d, StrokeCap.Round)
                drawArc(col, 180f, 180f, true, Offset(top.x - s * 0.55f, top.y - s * 0.4f), Size(s * 1.1f, s * 0.8f))
                for (k in 0 until 4) drawCircle(col, s * 0.14f, Offset(top.x - s * 0.41f + k * s * 0.275f, top.y))
            }
            2 -> if (!(p.state == P_SLEEP)) { // güneş gözlüğü
                val gl = Color(0xFF111111)
                drawOval(gl, Offset(e1.x - s * 0.08f, e1.y - s * 0.05f), Size(s * 0.13f, s * 0.1f))
                drawOval(gl, Offset(e2.x - s * 0.06f, e2.y - s * 0.05f), Size(s * 0.13f, s * 0.1f))
                drawLine(gl, Offset(e1.x + s * 0.04f, e1.y), Offset(e2.x - s * 0.05f, e2.y), 0.6f * d)
                drawLine(Color.White.copy(alpha = 0.5f), Offset(e1.x - s * 0.04f, e1.y - s * 0.02f), Offset(e1.x - s * 0.01f, e1.y - s * 0.035f), 0.5f * d)
            }
            3 -> { // dalış fanusu
                val c = Offset(hx + s * 0.02f, hy - s * 0.02f)
                drawCircle(Color(0xFFBFEFFF).copy(alpha = 0.18f), s * 0.42f, c)
                drawCircle(Color.White.copy(alpha = 0.7f), s * 0.42f, c, style = Stroke(0.8f * d))
                drawArc(Color.White.copy(alpha = 0.85f), 200f, 50f, false, Offset(c.x - s * 0.32f, c.y - s * 0.32f), Size(s * 0.64f, s * 0.64f), style = Stroke(0.8f * d, cap = StrokeCap.Round))
            }
        }
    }

    private fun DrawScope.legs(c: Color, s: Float, ph: Float) {
        drawLine(c, Offset(-s * 0.22f + ph, -s * 0.2f), Offset(-s * 0.24f + ph, 0f), s * 0.09f, StrokeCap.Round)
        drawLine(c, Offset(s * 0.12f - ph, -s * 0.2f), Offset(s * 0.14f - ph, 0f), s * 0.09f, StrokeCap.Round)
    }

    private fun DrawScope.drawEye(c: Offset, r: Float, mode: Int, up: Boolean, d: Float) {
        val ink = Color(0xFF1B1B1B)
        when (mode) {
            1 -> drawArc(ink, 10f, 160f, false, Offset(c.x - r * 1.2f, c.y - r * 1.2f), Size(r * 2.4f, r * 1.8f), style = Stroke(0.6f * d, cap = StrokeCap.Round))
            2 -> drawArc(ink, 200f, 140f, false, Offset(c.x - r * 1.2f, c.y - r * 0.4f), Size(r * 2.4f, r * 2f), style = Stroke(0.6f * d, cap = StrokeCap.Round))
            3 -> { drawCircle(Color.White, r * 1.5f, c); drawCircle(ink, r * 1.5f, c, style = Stroke(0.4f * d)); drawCircle(ink, r * 0.55f, c) }
            4 -> drawLine(ink, Offset(c.x - r * 1.1f, c.y), Offset(c.x + r * 1.1f, c.y + r * 0.2f), 0.6f * d, StrokeCap.Round)
            5 -> {
                drawCircle(ink, r * 1.1f, c, style = Stroke(0.45f * d))
                drawCircle(ink, r * 0.35f, Offset(c.x + cos(time * 9f) * r * 0.5f, c.y + sin(time * 9f) * r * 0.5f))
            }
            6 -> {
                drawCircle(ink, r * 1.15f, c)
                drawCircle(Color.White, r * 0.45f, Offset(c.x - r * 0.35f, c.y - r * 0.4f))
                drawCircle(Color.White, r * 0.22f, Offset(c.x + r * 0.4f, c.y + r * 0.35f))
            }
            else -> {
                drawCircle(ink, r, Offset(c.x, c.y + if (up) -r * 0.3f else 0f))
                drawCircle(Color.White, r * 0.35f, Offset(c.x - r * 0.3f, c.y - r * 0.35f + if (up) -r * 0.3f else 0f))
            }
        }
    }

    private fun DrawScope.drawEmote(e: Int, c: Offset, s: Float, d: Float, p: Pet) {
        val k = p.emoteT.coerceIn(0f, 0.3f) / 0.3f
        val pop = 0.7f + 0.3f * k
        when (e) {
            E_BANG -> {
                val col = Color(0xFFFF4D4D)
                drawLine(col, Offset(c.x, c.y - s * 0.38f * pop), Offset(c.x, c.y - s * 0.08f), 1.6f * d, StrokeCap.Round)
                drawCircle(col, 0.9f * d, Offset(c.x, c.y + s * 0.06f))
            }
            E_NOTE -> {
                val col = Color(0xFFFFE066)
                val y = c.y - (1f - p.emoteT / 1.3f) * s * 0.4f
                drawOval(col, Offset(c.x - s * 0.12f, y - s * 0.06f), Size(s * 0.14f, s * 0.1f))
                drawLine(col, Offset(c.x + s * 0.01f, y - s * 0.01f), Offset(c.x + s * 0.01f, y - s * 0.34f), 0.8f * d, StrokeCap.Round)
                drawLine(col, Offset(c.x + s * 0.01f, y - s * 0.34f), Offset(c.x + s * 0.13f, y - s * 0.27f), 0.8f * d, StrokeCap.Round)
            }
            E_HEART -> {
                val col = Color(0xFFFF5C8A)
                val r = s * 0.1f * pop
                drawCircle(col, r, Offset(c.x - r * 0.8f, c.y - r))
                drawCircle(col, r, Offset(c.x + r * 0.8f, c.y - r))
                val tri = Path().apply { moveTo(c.x - r * 1.75f, c.y - r * 0.7f); lineTo(c.x + r * 1.75f, c.y - r * 0.7f); lineTo(c.x, c.y + r * 1.3f); close() }
                drawPath(tri, col)
            }
            E_ZZZ -> {
                val col = Color.White.copy(alpha = 0.85f)
                for (q in 0 until 3) {
                    val ph = ((time * 0.6f + q / 3f) % 1f)
                    val z = s * (0.08f + 0.05f * q)
                    val zx = c.x + q * s * 0.14f; val zy = c.y - ph * s * 0.5f
                    val a = 1f - ph
                    val zp = Path().apply { moveTo(zx - z, zy - z); lineTo(zx + z, zy - z); lineTo(zx - z, zy + z); lineTo(zx + z, zy + z) }
                    drawPath(zp, col.copy(alpha = a * 0.85f), style = Stroke(0.6f * d, cap = StrokeCap.Round))
                }
            }
            E_SWEAT -> {
                val col = Color(0xFF7FC8FF)
                val dp = Path().apply {
                    moveTo(c.x + s * 0.2f, c.y - s * 0.2f)
                    quadraticTo(c.x + s * 0.32f, c.y, c.x + s * 0.2f, c.y + s * 0.05f)
                    quadraticTo(c.x + s * 0.08f, c.y, c.x + s * 0.2f, c.y - s * 0.2f)
                    close()
                }
                drawPath(dp, col)
            }
            E_SPARK -> {
                val col = Color(0xFFFFF2A8)
                for (q in 0 until 3) {
                    val a = time * 3f + q * 2.1f
                    val sc = Offset(c.x + cos(a) * s * 0.22f, c.y - s * 0.12f + sin(a) * s * 0.12f)
                    val r = s * 0.07f * pop
                    drawLine(col, Offset(sc.x - r, sc.y), Offset(sc.x + r, sc.y), 0.6f * d, StrokeCap.Round)
                    drawLine(col, Offset(sc.x, sc.y - r), Offset(sc.x, sc.y + r), 0.6f * d, StrokeCap.Round)
                }
            }
            E_DIZZY -> {
                val col = Color(0xFFFFE066)
                for (q in 0 until 3) {
                    val a = time * 6f + q * 2.094f
                    val sc = Offset(c.x + cos(a) * s * 0.28f, c.y + s * 0.2f + sin(a) * s * 0.08f)
                    val r = s * 0.06f
                    drawLine(col, Offset(sc.x - r, sc.y), Offset(sc.x + r, sc.y), 0.6f * d, StrokeCap.Round)
                    drawLine(col, Offset(sc.x, sc.y - r), Offset(sc.x, sc.y + r), 0.6f * d, StrokeCap.Round)
                }
            }
        }
    }
}
