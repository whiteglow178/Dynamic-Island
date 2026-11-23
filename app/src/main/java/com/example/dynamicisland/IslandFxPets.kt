package com.example.dynamicisland

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay
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
 * Kapak, başlık, söz ve çubuğun GERÇEK şeklinin (piksel taraması) üstünde yürür, zıplar, düşer; seçili ada olayına göre davranır.
 *
 * Alt özellikler (her biri ayrı açılır):
 *  Dokunma        : dokununca türüne göre tepki verir; parmakla tutup fırlatılır, takla atar, çarptığı öğeyi sarsar
 *  Kapalı adada   : ada kapalıyken kenarından sarkar, arkasından bakar; bildirim gelince irkilir (oyuncak ada penceresi)
 *  Büyüme         : müzik dinledikçe aksesuar açılır (atkı, şapka, taç, kanat); gece uykuludur; uzun aradan sonra koşarak gelir
 *  Kendi arasında : havada çarpışır, kardan adamın havucunu çalıp kaçar, uyuyanın yanına kıvrılır
 *  Top oyunu      : telefonu eğerek top yuvarlanır; yıldıza değince puan; hayvanlar topu kovalayıp iter
 */

internal const val P_IDLE = 0
internal const val P_WALK = 1
internal const val P_SIT = 2
internal const val P_SLEEP = 3
internal const val P_GOTO = 4
internal const val P_BUILD = 5
internal const val P_LIE = 6

internal const val E_NONE = 0
internal const val E_BANG = 1
internal const val E_NOTE = 2
internal const val E_HEART = 3
internal const val E_ZZZ = 4
internal const val E_SWEAT = 5
internal const val E_SPARK = 6
internal const val E_DIZZY = 7

/** Hayvan türleri: 0 kedi, 1 tavşan, 2 civciv, 3 penguen, 4 tilki. */
const val FX_PET_KINDS = 5

/** Aksesuarlar: 1 atkı, 2 şapka, 3 taç, 4 kanat (Büyüme). */
private val ACC_MINUTES = intArrayOf(0, 30, 120, 300, 600)
private val ACC_NAMES = arrayOf("", "Atkı", "Şapka", "Taç", "Kanat")

internal class Pet(val kind: Int, val seed: Float) {
    var x = 0f
    var y = 0f                    // ayakların değdiği nokta
    var vx = 0f
    var vy = 0f
    var ground = false
    var groundId: String? = null
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
    var acc = 0                   // aksesuar (Büyüme)
    var carry = 0                 // 1: ağzında havuç
    var dance = 0f
    var bubbleIn = 1f
    // dokunma / fırlatma
    var held = false
    var dragX = 0f
    var dragY = 0f
    var thrown = false
    var spin = 0f                 // derece
    var spinV = 0f
    var purr = 0f
    var slide = 0f
    // hedef türü
    var chaseBall = false
    var cuddle = false
    var swimming = false
    var flap = false
}

private class Snowman(var x: Float, var y: Float, val tid: String?, val ox: Float) {
    var grow = 0f
    var melt = 0f
    var carrot = true
}

private class PetBubble(var x: Float, var y: Float, val r: Float, var life: Float)

private class PetBall {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var rot = 0f
    var held = false
    var dragX = 0f; var dragY = 0f
}

private class PetConfetti(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val color: Color, var rot: Float)

// ================================================================ Büyüme: kalıcı durum
/** Hayvanların kalıcı durumu: toplam müzik dinleme dakikası ve son görülme. Telefon yeniden başlasa da kalır. */
object PetLife {
    @Volatile var minutes = 0f
        private set
    @Volatile var lastSeen = 0L
        private set
    private var prefs: SharedPreferences? = null
    private var lastWrite = 0L

    fun init(ctx: Context) {
        if (prefs != null) return
        val p = ctx.applicationContext.getSharedPreferences("pet_life", Context.MODE_PRIVATE)
        prefs = p
        minutes = p.getFloat("min", 0f)
        lastSeen = p.getLong("seen", 0L)
    }

    fun addMinutes(m: Float) { minutes += m; save() }

    fun seen(now: Long) {
        lastSeen = now
        if (now - lastWrite > 60_000L) save()
    }

    private fun save() {
        prefs?.edit()?.putFloat("min", minutes)?.putLong("seen", lastSeen)?.apply()
        lastWrite = System.currentTimeMillis()
    }

    /** Açılmış aksesuarlar (en yenisi sonda). */
    fun unlocked(): List<Int> = (1 until ACC_MINUTES.size).filter { minutes >= ACC_MINUTES[it] }

    /** Sıradaki aksesuar ve gereken dakika (hepsi açıldıysa null). */
    fun next(): Pair<String, Int>? = (1 until ACC_MINUTES.size).firstOrNull { minutes < ACC_MINUTES[it] }?.let { ACC_NAMES[it] to ACC_MINUTES[it] }

    /** i. hayvanın aksesuarı: en yeni açılan ilk hayvana, sonrakiler sırayla. */
    fun accFor(i: Int): Int {
        val u = unlocked()
        if (u.isEmpty()) return 0
        return u[u.size - 1 - (i % u.size)]
    }
}

// ================================================================ Simülasyon (genişletilmiş ada)
internal class FxPets {
    private val rnd = java.util.Random()
    private fun f(): Float = rnd.nextFloat()
    private val pets = ArrayList<Pet>()
    private val bubbles = ArrayList<PetBubble>()
    private val confetti = ArrayList<PetConfetti>()
    private var snowman: Snowman? = null
    private var ball: PetBall? = null
    private var star: Offset? = null
    private var score = 0
    private var time = 0f
    private var weather = -1
    private var lastImpact = 0L
    private var lastBoom = 0L
    private var lastZap = 0L
    private var wasLightning = false
    private var W = 0f
    private var H = 0f
    private var d = 1f
    private var s = 15f
    private var curTargets: Collection<FxTarget> = emptyList()
    private var curById: Map<String, FxTarget> = emptyMap()
    private var night = false
    private var nightCheck = 0f
    // havuç hırsızlığı
    private var thief: Pet? = null
    private var chaser: Pet? = null
    private var heist = 0                // 0 yok, 1 gidiyor, 2 kaçıyor, 3 geri getiriyor
    private var heistT = 0f
    // ayarlar
    private var social = true
    private var growth = true

    fun clear() {
        pets.clear(); bubbles.clear(); confetti.clear(); snowman = null; ball = null; star = null
        weather = -1; heist = 0; thief = null; chaser = null
    }

    // ---------------------------------------------------------------- Dokunma (FxLayer çağırır)
    fun count(): Int = pets.size

    /** i. hayvanın dokunma alanının sol üst köşesi (32x30 dp). */
    fun hitOrigin(i: Int, d: Float): Offset = pets.getOrNull(i)?.let { Offset(it.x - 16f * d, it.y - 26f * d) } ?: Offset(-9999f, -9999f)

    fun grab(i: Int, pos: Offset) {
        val p = pets.getOrNull(i) ?: return
        p.held = true; p.ground = false; p.state = P_IDLE; p.vx = 0f; p.vy = 0f
        p.dragX = pos.x; p.dragY = pos.y
        p.chaseBall = false; p.cuddle = false
        if (p === thief || p === chaser) endHeist()
        emote(p, E_BANG, 0.6f)
    }

    fun dragTo(i: Int, pos: Offset) { pets.getOrNull(i)?.let { it.dragX = pos.x; it.dragY = pos.y } }

    fun release(i: Int, vx: Float, vy: Float) {
        val p = pets.getOrNull(i) ?: return
        p.held = false
        val lim = 1400f * d
        p.vx = vx.coerceIn(-lim, lim); p.vy = vy.coerceIn(-lim, lim)
        p.thrown = hypot(p.vx, p.vy) > 250f * d
        p.spinV = if (p.thrown) p.vx * 1.1f / d else 0f
        p.timer = 1.5f
    }

    /** Dokununca türüne göre tepki. */
    fun tap(i: Int) {
        val p = pets.getOrNull(i) ?: return
        p.held = false
        p.state = P_IDLE; p.timer = 1.6f
        when (p.kind) {
            0 -> { p.purr = 1.6f; emote(p, E_HEART, 1.6f) }                                       // kedi: mırlar
            1 -> { if (p.ground) { p.vy = -320f * d; p.ground = false }; emote(p, E_HEART, 1.2f) }  // tavşan: zıplar
            2 -> { if (p.ground) { p.vy = -190f * d; p.ground = false }; p.flap = true; emote(p, E_NOTE, 1.2f) }   // civciv: cıvıldar
            3 -> { p.slide = 1.1f; emote(p, E_NOTE, 1.1f) }                                         // penguen: karnı üstünde kayar
            else -> { p.spinV = 720f; p.timer = 1f; emote(p, E_HEART, 1.2f) }                      // tilki: kuyruğunu kovalayıp döner
        }
    }

    fun ballOrigin(d: Float): Offset? = ball?.let { Offset(it.x - 14f * d, it.y - 14f * d) }
    fun grabBall(pos: Offset) { ball?.let { it.held = true; it.dragX = pos.x; it.dragY = pos.y; it.vx = 0f; it.vy = 0f } }
    fun dragBall(pos: Offset) { ball?.let { it.dragX = pos.x; it.dragY = pos.y } }
    fun releaseBall(vx: Float, vy: Float) {
        ball?.let { val lim = 1200f * d; it.held = false; it.vx = vx.coerceIn(-lim, lim); it.vy = vy.coerceIn(-lim, lim) }
    }

    // ---------------------------------------------------------------- Zemin / çarpışma (gerçek şekil)
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

    private fun targetAt(targets: Collection<FxTarget>, x: Float, y: Float): FxTarget? {
        for (t in targets) if (!t.gone && t.solid(x, y)) return t
        return null
    }

    private fun solidAt(targets: Collection<FxTarget>, x: Float, y: Float): Boolean = targetAt(targets, x, y) != null

    private fun covered(targets: Collection<FxTarget>, x: Float, yFeet: Float): Boolean {
        for (t in targets) {
            if (t.gone) continue
            val b = t.botAt(x) ?: continue
            if (b < yFeet - s * 1.05f && b > 0f) return true
        }
        return false
    }

    private fun leeward(targets: Collection<FxTarget>, x: Float, yFeet: Float): Boolean =
        solidAt(targets, x - s * 0.9f, yFeet - s * 0.5f) || solidAt(targets, x - s * 1.5f, yFeet - s * 0.5f)

    private fun surfacesAt(targets: Collection<FxTarget>, x: Float): List<Float> {
        val tops = ArrayList<Float>()
        tops.add(H - 1.5f * d)
        for (t in targets) { if (!t.gone) t.topAt(x)?.let { tops.add(it) } }
        return tops
    }

    private fun findSpot(targets: Collection<FxTarget>, p: Pet, ok: (Float, Float) -> Boolean): Offset? {
        var best: Offset? = null
        var bestScore = Float.MAX_VALUE
        var x = 10f * d
        while (x < W - 10f * d) {
            for (yy in surfacesAt(targets, x)) {
                if (solidAt(targets, x, yy - s * 0.6f)) continue
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
        count: Int, weatherNow: Int, dt: Float, W: Float, H: Float, d: Float,
        targets: Collection<FxTarget>, byId: Map<String, FxTarget>, scenes: FxScenes,
        tilt: Float, shook: Boolean, cue: Int, lightning: Boolean,
        socialOn: Boolean, growthOn: Boolean, ballOn: Boolean, ballTilt: Float,
    ) {
        this.W = W; this.H = H; this.d = d
        curTargets = targets; curById = byId
        social = socialOn; growth = growthOn
        s = 15f * d
        time += dt
        val nowWall = System.currentTimeMillis()
        nightCheck -= dt
        if (nightCheck <= 0f) {
            nightCheck = 30f
            val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
            night = growth && (h >= 23 || h < 6)
        }

        // ---- sayı; uzun aradan sonra koşarak gelirler
        val n = count.coerceIn(1, FX_PET_KINDS)
        val fresh = pets.isEmpty()
        val missed = fresh && growth && PetLife.lastSeen > 0L && nowWall - PetLife.lastSeen > 6 * 3_600_000L
        while (pets.size < n) {
            val k = pets.size
            val p = Pet(k % FX_PET_KINDS, f())
            p.blinkIn = 1f + f() * 3f
            if (missed) {
                val left = k % 2 == 0
                p.x = if (left) 10f * d else W - 10f * d
                p.y = H - 1.5f * d; p.ground = true
                goTo(p, W * (0.35f + 0.3f * (k + 0.5f) / n), H - 1.5f * d, 64f)
                emote(p, E_HEART, 2.2f)
            } else {
                p.x = W * (0.15f + 0.7f * (k + 0.5f) / n) + (f() - 0.5f) * 12f * d
                p.y = -s * (1f + k * 0.6f)
                p.face = if (f() < 0.5f) -1f else 1f
            }
            pets.add(p)
        }
        while (pets.size > n) pets.removeAt(pets.size - 1)
        if (growth) { PetLife.seen(nowWall); for ((i, p) in pets.withIndex()) p.acc = PetLife.accFor(i) } else for (p in pets) p.acc = 0

        if (weatherNow != weather) {
            weather = weatherNow
            for (p in pets) {
                p.prop = when (weather) { FXW_SUN -> 2; FXW_SEA -> 3; else -> 0 }
                if (!p.held) { p.state = P_IDLE; p.timer = f() * 0.8f }
                p.lookUp = false
                if (weather == FXW_SEA) p.ground = false
            }
            endHeist()
        }
        val swim = weather == FXW_SEA

        // ---- sahne olayları
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
            if (sm.tid != null) {
                val t = byId[sm.tid]
                val top = if (t == null || t.gone) null else t.topAt(t.rect.left + sm.ox)
                if (t == null || top == null) snowman = null else { sm.x = t.rect.left + sm.ox; sm.y = top }
            }
            if (weather != 2 && weather != FXW_BLIZZARD) {
                sm.melt += dt / 4f
                if (sm.melt >= 1f) snowman = null
            }
            if (shook) snowman = null
        }
        if (snowman == null && heist != 0) endHeist()

        // ---- top
        if (ballOn) stepBall(dt, targets, ballTilt) else { ball = null; star = null }

        for ((i, p) in pets.withIndex()) {
            p.timer -= dt
            p.jumpCd -= dt
            if (p.emoteT > 0f) { p.emoteT -= dt; if (p.emoteT <= 0f) p.emote = E_NONE }
            if (p.squash > 0f) p.squash = (p.squash - dt * 3f).coerceAtLeast(0f)
            if (p.bristle > 0f) p.bristle = (p.bristle - dt * 0.9f).coerceAtLeast(0f)
            if (p.dizzy > 0f) p.dizzy -= dt
            if (p.startle > 0f) p.startle -= dt
            if (p.cover > 0f) p.cover -= dt
            if (p.dance > 0f) p.dance -= dt
            if (p.purr > 0f) p.purr -= dt
            if (p.slide > 0f) p.slide -= dt
            p.blinkIn -= dt
            if (p.blinkIn <= 0f) { p.blink = 0.12f; p.blinkIn = 2f + f() * 4f }
            if (p.blink > 0f) p.blink -= dt
            p.swimming = swim && !p.held

            if (p.held) { heldStep(p, dt); expression(p); continue }

            // ---- olaylar
            if (shook) {
                p.vy = -(220f + f() * 120f) * d; p.vx = (f() - 0.5f) * 220f * d; p.ground = false
                p.dizzy = 2.5f; emote(p, E_DIZZY, 2.5f); p.state = P_IDLE; p.timer = 2.6f
                p.snowy = 0f; p.wet *= 0.5f; p.thrown = true; p.spinV = p.vx * 0.8f / d
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

            if (swim) swimStep(p, dt, targets, scenes)
            else {
                if (p.timer <= 0f && p.ground && p.startle <= 0f && p.dizzy <= 0f && !isHeistActor(p)) decide(p, i, targets, scenes)
                walkStep(p, dt, targets, scenes, tilt)
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
            expression(p)
            if (p.state == P_SLEEP && p.emote == E_NONE) emote(p, E_ZZZ, 1.5f)
            if (p.wet > 0.6f && p.emote == E_NONE && f() < dt * 0.3f) emote(p, E_SWEAT, 1f)
        }

        // ---- kendi aralarında: havada çarpışma, havuç hırsızlığı
        if (social) {
            for (a in 0 until pets.size) for (b in a + 1 until pets.size) {
                val pa = pets[a]; val pb = pets[b]
                if (pa.ground || pb.ground || pa.held || pb.held || swim) continue
                if (hypot(pa.x - pb.x, pa.y - pb.y) < s * 0.65f && pa.dizzy <= 0f && pb.dizzy <= 0f) {
                    val t = pa.vx; pa.vx = pb.vx - 30f * d; pb.vx = t + 30f * d
                    pa.vy = min(pa.vy, 0f) - 60f * d; pb.vy = min(pb.vy, 0f) - 60f * d
                    pa.dizzy = 1f; pb.dizzy = 1f
                    emote(pa, E_DIZZY, 1f); emote(pb, E_DIZZY, 1f)
                }
            }
            stepHeist(dt)
        }

        // ---- ayrışma
        for (a in pets) for (b in pets) {
            if (a === b || !a.ground || !b.ground || a.state == P_BUILD || b.state == P_BUILD || (a.state == P_SLEEP && b.state == P_SLEEP)) continue
            val dx = a.x - b.x
            if (abs(dx) < s * 0.7f && abs(a.y - b.y) < s * 0.5f) a.x += (if (dx >= 0f) 1f else -1f) * 22f * d * dt
        }

        // ---- kabarcıklar, konfeti
        var k = bubbles.size - 1
        while (k >= 0) {
            val b = bubbles[k]
            b.y -= (25f + b.r / d * 6f) * d * dt
            b.x += sin(time * 5f + b.r) * 6f * d * dt
            b.life -= dt
            if (b.life <= 0f || b.y < -4f * d) bubbles.removeAt(k)
            k--
        }
        k = confetti.size - 1
        while (k >= 0) {
            val c = confetti[k]
            c.vy += 300f * d * dt; c.vx *= (1f - 1.5f * dt)
            c.x += c.vx * dt; c.y += c.vy * dt; c.rot += c.vx * dt
            c.life -= dt
            if (c.life <= 0f) confetti.removeAt(k)
            k--
        }
    }

    private fun expression(p: Pet) {
        p.eyes = when {
            p.dizzy > 0f -> 5
            p.held && p.kind != 0 -> 3
            p.startle > 0f || p.bristle > 0.3f -> 3
            p.state == P_SLEEP || p.blink > 0f -> 1
            p.cover > 0f || p.purr > 0f -> 1
            weather == FXW_SAND && p.lean != 0f -> 4
            p.emote == E_SPARK || (p.lookUp && (weather == FXW_AURORA || weather == FXW_FIREWORKS)) -> 6
            p.dance > 0f || p.emote == E_HEART || p.state == P_LIE || p.held -> 2
            else -> 0
        }
        p.mouth = when {
            p.startle > 0f || (p.held && p.kind != 0) -> 1
            p.lookUp && (weather == 2 || weather == FXW_BLIZZARD) -> 1
            night && p.state == P_SIT && sin(time * 0.7f + p.seed * 9f) > 0.97f -> 1         // esneme
            p.dance > 0f || p.state == P_LIE || p.purr > 0f -> 2
            else -> 0
        }
    }

    private fun emote(p: Pet, e: Int, t: Float) { p.emote = e; p.emoteT = t }

    private fun startle(p: Pet, fromX: Float) {
        if (isHeistActor(p)) endHeist()
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

    private fun sit(p: Pet, t: Float, look: Boolean = false) { p.state = P_SIT; p.timer = t; p.lookUp = look; p.vx = 0f }

    private fun sleep(p: Pet) { p.state = P_SLEEP; p.timer = 6f + f() * 6f; p.vx = 0f }

    /** Uyuyan bir arkadaşın yanına gidip kıvrılır (Kendi arasında). */
    private fun tryCuddle(p: Pet): Boolean {
        if (!social) return false
        val sl = pets.firstOrNull { it !== p && it.state == P_SLEEP && it.ground } ?: return false
        if (f() > 0.3f) return false
        val side = if (p.x < sl.x) -1f else 1f
        goTo(p, (sl.x + side * s * 0.75f).coerceIn(10f * d, W - 10f * d), sl.y, 30f)
        p.cuddle = true
        return true
    }

    private fun decide(p: Pet, i: Int, targets: Collection<FxTarget>, scenes: FxScenes) {
        p.chaseBall = false; p.cuddle = false
        // top oyunu: arada topu kovalar
        val b = ball
        if (b != null && !b.held && f() < 0.35f && weather != 1 && weather != 3) {
            goTo(p, b.x, p.y, 52f); p.chaseBall = true; return
        }
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
                    f() < 0.5f -> sit(p, 2f + f() * 3f, look = true)
                    else -> wander(p)
                }
            }
            FXW_SUN -> if (f() < 0.65f) { p.state = P_LIE; p.timer = 5f + f() * 5f; p.vx = 0f } else wander(p)
            FXW_METEOR, FXW_AURORA, FXW_FIREWORKS -> if (f() < 0.7f) sit(p, 3f + f() * 4f, look = true) else wander(p)
            FXW_FIREFLY -> {
                val near = scenes.fireflies().minByOrNull { abs(it.x - p.x) + abs(it.y - p.y) }
                when {
                    tryCuddle(p) -> {}
                    near != null && f() < 0.6f -> goTo(p, near.x.coerceIn(10f * d, W - 10f * d), p.y, 40f)
                    pets.count { it.state == P_SLEEP } < (pets.size + 1) / 2 && f() < 0.4f -> sleep(p)
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
                val sleepy = if (night) 0.45f else 0.14f
                when {
                    tryCuddle(p) -> {}
                    r < sleepy && pets.count { it.state == P_SLEEP } < (if (night) pets.size else 1) -> sleep(p)
                    r < sleepy + 0.4f -> wander(p)
                    r < sleepy + 0.62f -> sit(p, 2f + f() * 4f)
                    pets.size > 1 && !night -> {
                        val o = pets[(i + 1 + rnd.nextInt(pets.size - 1)) % pets.size]
                        goTo(p, o.x - sign(o.x - p.x) * s * 0.8f, o.y, 48f)
                    }
                    else -> wander(p)
                }
            }
        }
    }

    private fun startSnowman(p: Pet) {
        var x = (p.x + p.face * s * 0.9f).coerceIn(14f * d, W - 14f * d)
        if (abs(groundAt(curTargets, x, p.y - 2f * d, null) - p.y) > 3f * d) x = p.x
        val tid = p.groundId
        val left = tid?.let { curById[it]?.rect?.left } ?: 0f
        snowman = Snowman(x, p.y, tid, x - left)
        p.state = P_BUILD; p.timer = 12f; p.vx = 0f
        p.face = if (x >= p.x) 1f else -1f
    }

    // ---------------------------------------------------------------- Havuç hırsızlığı (Kendi arasında)
    private fun isHeistActor(p: Pet) = heist != 0 && (p === thief || p === chaser)

    private fun endHeist() {
        thief?.carry = 0
        if (heist == 2 || heist == 3) snowman?.carrot = true
        heist = 0; thief = null; chaser = null
    }

    private fun stepHeist(dt: Float) {
        val sm = snowman
        if (heist == 0) {
            if (sm == null || sm.grow < 1f || !sm.carrot || sm.melt > 0f || pets.size < 2) return
            if (f() > dt * 0.05f) return
            val cands = pets.filter { it.ground && it.state != P_BUILD && it.state != P_SLEEP && !it.held }
            if (cands.size < 2) return
            val t = cands[rnd.nextInt(cands.size)]
            val c = cands.filter { it !== t }.minByOrNull { abs(it.x - sm.x) } ?: return
            thief = t; chaser = c; heist = 1; heistT = 8f
            goTo(t, sm.x + (if (t.x < sm.x) -1f else 1f) * s * 0.6f, sm.y, 40f)
            return
        }
        val t = thief ?: run { endHeist(); return }
        val c = chaser ?: run { endHeist(); return }
        if (sm == null) { endHeist(); return }
        heistT -= dt
        when (heist) {
            1 -> {
                t.timer = 5f
                if (t.state != P_GOTO) goTo(t, sm.x + (if (t.x < sm.x) -1f else 1f) * s * 0.6f, sm.y, 40f)
                if (abs(t.x - sm.x) < s * 0.9f && t.ground) {
                    sm.carrot = false; t.carry = 1; heist = 2; heistT = 5f
                    emote(t, E_NOTE, 1f); emote(c, E_BANG, 1f)
                    val away = if (t.x < W / 2f) W - 14f * d else 14f * d
                    goTo(t, away, H - 1.5f * d, 62f)
                }
                if (heistT <= 0f) endHeist()
            }
            2 -> {
                t.timer = 5f; c.timer = 5f
                if (t.state != P_GOTO) { val away = if (t.x < W / 2f) W - 14f * d else 14f * d; goTo(t, away, H - 1.5f * d, 62f) }
                goTo(c, t.x, t.y, 56f)
                if (hypot(c.x - t.x, c.y - t.y) < s * 0.8f || heistT <= 0f) {
                    heist = 3; heistT = 8f
                    emote(t, E_SWEAT, 1f)
                    goTo(t, sm.x + (if (t.x < sm.x) -1f else 1f) * s * 0.6f, sm.y, 34f)
                    sit(c, 2f)
                }
            }
            3 -> {
                t.timer = 5f
                if (t.state != P_GOTO) goTo(t, sm.x + (if (t.x < sm.x) -1f else 1f) * s * 0.6f, sm.y, 34f)
                if ((abs(t.x - sm.x) < s * 0.9f && t.ground) || heistT <= 0f) {
                    sm.carrot = true; t.carry = 0
                    emote(t, E_HEART, 1.5f); emote(c, E_HEART, 1.5f)
                    sit(t, 3f)
                    heist = 0; thief = null; chaser = null
                }
            }
        }
    }

    // ---------------------------------------------------------------- Top oyunu
    private fun placeStar(targets: Collection<FxTarget>) {
        val x = W * (0.1f + 0.8f * f())
        val tops = surfacesAt(targets, x).filter { !solidAt(targets, x, it - 10f * d) }
        val y = (if (tops.isEmpty()) H - 1.5f * d else tops[rnd.nextInt(tops.size)]) - 9f * d
        star = Offset(x, y.coerceIn(10f * d, H - 9f * d))
    }

    private fun stepBall(dt: Float, targets: Collection<FxTarget>, tilt: Float) {
        val r = 4.5f * d
        val b = ball ?: PetBall().also { it.x = W / 2f; it.y = -10f * d; ball = it }
        if (star == null) placeStar(targets)
        if (b.held) {
            val nx = b.x + (b.dragX - b.x) * min(1f, 25f * dt)
            val ny = b.y + (b.dragY - b.y) * min(1f, 25f * dt)
            b.rot += (nx - b.x) / r
            b.x = nx; b.y = ny
        } else {
            b.vy += 700f * d * dt
            b.vx += tilt * 900f * d * dt
            b.vx *= (1f - 0.35f * dt)
            var nx = b.x + b.vx * dt
            var ny = b.y + b.vy * dt
            if (solidAt(targets, nx + sign(b.vx) * r, b.y)) { b.vx = -b.vx * 0.6f; nx = b.x }
            if (b.vy > 0f && solidAt(targets, nx, ny + r)) {
                ny = b.y; b.vy = -b.vy * 0.5f; if (abs(b.vy) < 40f * d) b.vy = 0f
                b.vx *= 0.94f
            } else if (b.vy < 0f && solidAt(targets, nx, ny - r)) { ny = b.y; b.vy = -b.vy * 0.4f }
            if (ny + r > H - 1f * d) { ny = H - 1f * d - r; b.vy = -b.vy * 0.5f; if (abs(b.vy) < 40f * d) b.vy = 0f; b.vx *= 0.94f }
            if (ny - r < 0f) { ny = r; b.vy = abs(b.vy) * 0.5f }
            if (nx < r) { nx = r; b.vx = abs(b.vx) * 0.6f }
            if (nx > W - r) { nx = W - r; b.vx = -abs(b.vx) * 0.6f }
            b.rot += (nx - b.x) / r
            b.x = nx; b.y = ny
        }
        // hayvanlar topa vurur (yarısında yıldıza doğru)
        for (p in pets) {
            if (!p.ground || p.held || !p.chaseBall) continue
            p.gx = b.x
            if (abs(b.x - p.x) < s * 0.7f && abs(b.y - (p.y - s * 0.3f)) < s * 0.9f && !b.held) {
                val st = star
                val dir = if (st != null && f() < 0.55f) sign(st.x - b.x).let { if (it == 0f) p.face else it } else p.face
                b.vx = dir * (150f + f() * 80f) * d; b.vy = -(120f + f() * 120f) * d
                emote(p, E_NOTE, 0.9f)
                p.chaseBall = false; p.state = P_IDLE; p.timer = 0.8f
            }
        }
        val st = star ?: return
        if (hypot(b.x - st.x, b.y - st.y) < r + 7f * d) {
            score++
            val cols = longArrayOf(0xFFFFD43B, 0xFFFF5C8A, 0xFF5CB8FF, 0xFF7ED957, 0xFFC77DFF)
            repeat(22) {
                val a = f() * 2f * PI.toFloat()
                val sp = (60f + f() * 140f) * d
                confetti.add(PetConfetti(st.x, st.y, cos(a) * sp, sin(a) * sp - 80f * d, 1.2f + f() * 0.6f, Color(cols[rnd.nextInt(cols.size)]), f() * 6f))
            }
            for (p in pets) if (!p.held) { emote(p, if (f() < 0.5f) E_HEART else E_NOTE, 1.2f); if (p.ground) { p.vy = -150f * d; p.ground = false } }
            star = null
        }
    }

    // ---------------------------------------------------------------- Tutulma, yürüme, zıplama, düşme
    private fun heldStep(p: Pet, dt: Float) {
        val tx = p.dragX
        val ty = p.dragY + s * 0.55f
        val dx = tx - p.x
        p.x += dx * min(1f, 25f * dt)
        p.y += (ty - p.y) * min(1f, 25f * dt)
        p.lean = (-dx / (40f * d)).coerceIn(-0.6f, 0.6f)          // sallanır
        p.walk += dt * 22f                                          // bacaklar havada çırpınır
        p.ground = false
    }

    private fun walkStep(p: Pet, dt: Float, targets: Collection<FxTarget>, scenes: FxScenes, tilt: Float) {
        val g = 900f * d
        if (p.spinV != 0f) {
            p.spin += p.spinV * dt
            if (p.ground && !p.thrown) { p.spinV *= (1f - 2.5f * dt); if (abs(p.spinV) < 30f) { p.spinV = 0f; p.spin = 0f } }
        }
        if (p.ground) {
            var want = 0f
            when (p.state) {
                P_WALK -> want = p.face * p.speed
                P_GOTO -> {
                    if (p.chaseBall) ball?.let { p.gx = it.x }
                    val dx = p.gx - p.x
                    val dy = p.gy - p.y
                    if (abs(dx) < 3f * d && abs(dy) < 6f * d && !p.chaseBall) {
                        val sm = snowman
                        if (sm != null && sm.grow < 1f && abs(sm.x - p.x) < s * 1.2f && (weather == 2 || weather == FXW_BLIZZARD)) {
                            p.state = P_BUILD; p.timer = 10f; p.face = sign(sm.x - p.x).let { if (it == 0f) 1f else it }
                        } else if (p.cuddle) {
                            p.cuddle = false; sleep(p); emote(p, E_HEART, 1.5f)
                            p.face = pets.firstOrNull { it !== p && it.state == P_SLEEP }?.let { if (it.x >= p.x) 1f else -1f } ?: p.face
                        } else if (p.startle > 0f) {
                            p.state = P_IDLE; p.timer = 0.6f
                        } else if (!isHeistActor(p)) sit(p, 3f + f() * 4f, look = weather == FXW_METEOR || weather == FXW_AURORA || weather == FXW_FIREWORKS)
                    } else if (abs(dy) > 8f * d && abs(dx) < 80f * d && p.jumpCd <= 0f && !p.chaseBall) {
                        jumpTo(p, p.gx, p.gy)
                    } else {
                        p.face = if (dx >= 0f) 1f else -1f
                        want = p.face * p.speed
                    }
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
                        else if (abs(sm.x - p.x) < s * 0.55f) want = -p.face * 24f * d
                        sm.grow = (sm.grow + dt / 9f).coerceAtMost(1f)
                        if (((time * 6f + p.seed * 10f).toInt() % 4) == 0) p.squash = max(p.squash, 0.12f)
                        if (sm.grow >= 1f) { sit(p, 4f); emote(p, E_HEART, 1.6f) }
                    }
                }
                P_IDLE -> if (p.startle > 0f) want = p.face * 60f * d
                else -> {}
            }
            if (p.dance > 0f && p.jumpCd <= 0f) { p.vy = -140f * d; p.ground = false; p.jumpCd = 0.35f; p.face = -p.face }
            if (p.slide > 0f) want = p.face * 110f * d                    // penguen karnı üstünde kayar
            if (p.purr > 0f) want = 0f
            if (abs(tilt) > 0.25f) want += tilt * 140f * d
            if (weather == FXW_SAND && !leeward(targets, p.x, p.y)) want += 14f * d * scenes.windNow

            p.vx = want
            if (want != 0f) {
                val ahead = p.x + sign(want) * s * 0.42f
                if (solidAt(targets, ahead, p.y - s * 0.45f)) {
                    val top = targets.mapNotNull { t -> if (t.gone) null else t.topAt(ahead) }.filter { it < p.y }.maxOrNull()
                    if (top != null && p.y - top < s * 2.2f && p.jumpCd <= 0f) {
                        jumpTo(p, ahead + sign(want) * s * 0.3f, top)
                    } else {
                        p.face = -sign(want); p.vx = 0f; p.slide = 0f
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
                if (p.state == P_WALK && f() < 0.75f) { p.face = -p.face; p.vx = 0f; return }
                p.x = nx; p.ground = false; p.vy = -40f * d
                return
            }
            p.x = nx
            p.y += (gNew - p.y) * min(1f, 18f * dt)
        } else {
            p.vy += g * dt
            val py = p.y
            var nx = p.x + p.vx * dt
            // fırlatılınca yan yüzlere çarpar: öğe sarsılır, hızlıysa sersemler
            if (p.vx != 0f) {
                val hit = targetAt(targets, nx + sign(p.vx) * s * 0.35f, p.y - s * 0.5f)
                if (hit != null) {
                    val sp = abs(p.vx)
                    if (sp > 250f * d) { hit.shake = min(hit.shake + sp / (400f * d) * d, 6f * d); p.dizzy = max(p.dizzy, 1.2f); emote(p, E_DIZZY, 1.2f) }
                    p.vx = -p.vx * 0.4f; nx = p.x
                }
            }
            if (nx < 8f * d || nx > W - 8f * d) { p.vx = -p.vx * 0.5f; nx = nx.coerceIn(8f * d, W - 8f * d) }
            p.x = nx
            p.y += p.vy * dt
            if (p.y < s) { p.y = s; p.vy = abs(p.vy) * 0.3f }
            if (p.vy < 0f) {
                val ceil = targetAt(targets, p.x, p.y - s * 0.95f)
                if (ceil != null) { if (p.thrown && abs(p.vy) > 300f * d) ceil.shake = min(ceil.shake + 2f * d, 6f * d); p.vy = 0f }
            }
            val gNew = groundAt(targets, p.x, py, p)
            if (p.vy >= 0f && p.y >= gNew) {
                val impact = p.vy
                p.y = gNew
                p.squash = min(0.4f, impact / (900f * d))
                if (p.thrown && impact > 380f * d) {
                    p.dizzy = max(p.dizzy, 1.4f); emote(p, E_DIZZY, 1.4f)
                    groundTarget(p)?.let { it.shake = min(it.shake + 2f * d, 6f * d) }
                }
                p.vy = 0f; p.vx = 0f; p.ground = true
                p.thrown = false; p.spin = 0f; p.spinV = 0f; p.flap = false
                if (p.state == P_IDLE && p.timer > 1f) p.timer = 0.3f
            }
        }
    }

    private fun groundTarget(p: Pet): FxTarget? = p.groundId?.let { curById[it] }

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

    private fun swimStep(p: Pet, dt: Float, targets: Collection<FxTarget>, scenes: FxScenes) {
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
        p.vy += sin(time * 2.2f + p.seed * 9f) * 14f * d * dt
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
        star?.let { drawStar(it, d) }
        for (b in bubbles) {
            val c = Offset(b.x, b.y)
            drawCircle(Color.White.copy(alpha = 0.55f), b.r, c, style = Stroke(0.6f * d))
            drawCircle(Color.White.copy(alpha = 0.85f), b.r * 0.3f, Offset(c.x - b.r * 0.35f, c.y - b.r * 0.35f))
        }
        for (p in pets) drawPetWorld(p, this@FxPets.s, d, time, weather == FXW_SEA)
        ball?.let { drawBall(it, d) }
        for (c in confetti) {
            val k = (c.life / 1.2f).coerceIn(0f, 1f)
            withTransform({ rotate(c.rot * 57f, Offset(c.x, c.y)) }) {
                drawRect(c.color.copy(alpha = k), Offset(c.x - 1.5f * d, c.y - 0.8f * d), Size(3f * d, 1.6f * d))
            }
        }
    }

    private fun DrawScope.drawStar(c: Offset, d: Float) {
        val r = 6f * d * (1f + 0.12f * sin(time * 5f))
        val p = Path()
        for (k in 0 until 10) {
            val a = -PI.toFloat() / 2f + k * PI.toFloat() / 5f + time * 0.6f
            val rr = if (k % 2 == 0) r else r * 0.45f
            val x = c.x + cos(a) * rr; val y = c.y + sin(a) * rr
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close()
        drawCircle(Color(0x55FFD43B), r * 1.8f, c)
        drawPath(p, Color(0xFFFFD43B))
    }

    private fun DrawScope.drawBall(b: PetBall, d: Float) {
        val r = 4.5f * d
        val c = Offset(b.x, b.y)
        drawCircle(Color(0xFFFF5A5A), r, c)
        withTransform({ rotate(b.rot * 57.3f, c) }) {
            drawLine(Color.White, Offset(c.x - r, c.y), Offset(c.x + r, c.y), 1.2f * d)
            drawArc(Color(0xFFFFD43B), -60f, 120f, false, Offset(c.x - r * 0.9f, c.y - r * 0.9f), Size(r * 1.8f, r * 1.8f), style = Stroke(1f * d))
        }
        drawCircle(Color.White.copy(alpha = 0.6f), r * 0.3f, Offset(c.x - r * 0.35f, c.y - r * 0.4f))
    }

    private fun DrawScope.drawSnowman(sm: Snowman, d: Float) {
        val g = sm.grow
        if (g <= 0.01f) return
        val k = 1f - sm.melt
        val white = Color(0xFFF7FBFF)
        val shade = Color(0xFFD6E2F0)
        val r1 = 6.5f * d * (g / 0.4f).coerceIn(0f, 1f) * (0.6f + 0.4f * k)
        val r2 = 4.6f * d * ((g - 0.4f) / 0.3f).coerceIn(0f, 1f) * k
        val r3 = 3.4f * d * ((g - 0.7f) / 0.2f).coerceIn(0f, 1f) * k
        val y1 = sm.y - r1 * 0.9f
        val y2 = y1 - r1 * 0.75f - r2 * 0.7f
        val y3 = y2 - r2 * 0.75f - r3 * 0.7f
        if (sm.melt > 0f) drawOval(white.copy(alpha = 0.7f), Offset(sm.x - r1 * (1f + sm.melt), sm.y - 1.5f * d), Size(r1 * 2f * (1f + sm.melt), 2.5f * d))
        drawCircle(white, r1, Offset(sm.x, y1)); drawCircle(shade, r1, Offset(sm.x, y1), style = Stroke(0.6f * d))
        if (r2 > 0.5f) { drawCircle(white, r2, Offset(sm.x, y2)); drawCircle(shade, r2, Offset(sm.x, y2), style = Stroke(0.6f * d)) }
        if (r3 > 0.5f) { drawCircle(white, r3, Offset(sm.x, y3)); drawCircle(shade, r3, Offset(sm.x, y3), style = Stroke(0.6f * d)) }
        if (g > 0.9f && sm.melt < 0.5f) {
            val eye = Color(0xFF1B1B1B)
            drawCircle(eye, 0.45f * d, Offset(sm.x - r3 * 0.35f, y3 - r3 * 0.15f))
            drawCircle(eye, 0.45f * d, Offset(sm.x + r3 * 0.35f, y3 - r3 * 0.15f))
            if (sm.carrot) {
                val nose = Path().apply { moveTo(sm.x, y3); lineTo(sm.x + r3 * 1.1f, y3 + r3 * 0.15f); lineTo(sm.x, y3 + r3 * 0.3f); close() }
                drawPath(nose, Color(0xFFFF8A2B))
            }
            for (bx in floatArrayOf(-1f, 1f)) {
                drawLine(Color(0xFF6B4A2E), Offset(sm.x + bx * r2 * 0.8f, y2), Offset(sm.x + bx * r2 * 2f, y2 - r2 * 0.9f), 0.7f * d, StrokeCap.Round)
            }
            drawCircle(eye, 0.4f * d, Offset(sm.x, y2 - r2 * 0.2f)); drawCircle(eye, 0.4f * d, Offset(sm.x, y2 + r2 * 0.3f))
        }
    }
}

// ================================================================ Ortak çizim (genişletilmiş ve kapalı ada)
/** Hayvanı dünyada çizer: duruş (eğilme, sıkışma, takla, titreme), ifade balonu, ıslaklık damlası. */
internal fun DrawScope.drawPetWorld(p: Pet, s: Float, d: Float, time: Float, swim: Boolean) {
    val lying = p.state == P_SLEEP || p.state == P_LIE || p.slide > 0f
    val shiver = if (p.wet > 0.5f && !lying) sin(time * 60f + p.seed) * 0.6f * d * p.wet else 0f
    val purr = if (p.purr > 0f) sin(time * 45f) * 0.35f * d else 0f
    val bob = if (p.state == P_SIT && p.lookUp) sin(time * 1.5f + p.seed * 6f) * 0.3f * d else 0f
    val dz = if (p.dance > 0f) sin(time * 14f) * 0.12f else 0f
    withTransform({
        translate(p.x + shiver + purr, p.y + bob)
        rotate((p.lean + dz) * 57.3f, Offset.Zero)
        if (p.spin != 0f) rotate(p.spin, Offset(0f, -s * 0.45f))
        scale(p.face * (1f + p.squash * 0.6f) * (if (lying) 1.12f else 1f), (1f - p.squash) * (if (lying) 0.72f else 1f), Offset.Zero)
    }) {
        drawPetFigure(p, s, d, lying, time, swim)
    }
    if (p.emote != E_NONE) drawPetEmote(p.emote, Offset(p.x + p.face * s * 0.15f, p.y - s * (if (lying) 1.05f else 1.45f)), s, d, p, time)
    if (p.wet > 0.35f && !lying) {
        val ph = (time * 1.3f + p.seed) % 1f
        drawCircle(Color(0xFF7FC8FF).copy(alpha = (1f - ph) * p.wet), 0.9f * d, Offset(p.x - p.face * s * 0.35f, p.y - s * 0.35f + ph * s * 0.4f))
    }
}

/** Hayvan figürü (yerel: ayak ortası 0,0; sağa bakar). */
internal fun DrawScope.drawPetFigure(p: Pet, s: Float, d: Float, lying: Boolean, time: Float, swim: Boolean) {
    val moving = (p.ground && abs(p.vx) > 1f && !lying) || p.held
    val legPh = if (moving) sin(p.walk) * s * 0.08f else 0f
    val swimPh = if (swim) sin(p.walk * 1.5f) * s * 0.06f else 0f
    val lp = legPh + swimPh
    val eyeC = Color(0xFF1B1B1B)
    // kanat (Büyüme): gövdenin arkasında
    if (p.acc == 4) {
        val fl = sin(time * (if (p.ground) 3f else 16f) + p.seed * 5f) * 18f
        for (k in 0 until 2) {
            withTransform({ rotate(-20f - fl - k * 12f, Offset(-s * 0.1f, -s * 0.55f)) }) {
                drawOval(Color.White.copy(alpha = 0.92f - k * 0.25f), Offset(-s * 0.55f, -s * 0.78f), Size(s * 0.5f, s * 0.24f))
            }
        }
    }
    val hx: Float
    val hy: Float
    when (p.kind) {
        1 -> {
            val white = Color(0xFFFFFFFF); val pink = Color(0xFFFFA8C5); val sh = Color(0xFFE6E6EE)
            petLegs(sh, s, lp)
            drawOval(white, Offset(-s * 0.42f, -s * 0.55f), Size(s * 0.78f, s * 0.48f))
            drawCircle(white, s * 0.11f, Offset(-s * 0.44f, -s * 0.32f))
            hx = s * 0.2f; hy = -s * 0.68f
            val droop = (p.wet * 0.9f + if (p.lean != 0f && !p.held) 0.4f else 0f).coerceAtMost(1f)
            for ((ex, ew) in listOf(-0.06f to 0.17f, 0.12f to 0.17f)) {
                withTransform({ rotate(-droop * 70f, Offset(hx + s * ex + s * ew / 2f, hy - s * 0.16f)) }) {
                    drawOval(white, Offset(hx + s * ex, hy - s * 0.72f), Size(s * ew, s * 0.58f))
                    drawOval(pink, Offset(hx + s * ex + s * 0.045f, hy - s * 0.62f), Size(s * 0.08f, s * 0.4f))
                }
            }
            drawCircle(white, s * 0.26f, Offset(hx, hy))
            drawCircle(pink.copy(alpha = 0.7f), s * 0.045f, Offset(hx + s * 0.2f, hy + s * 0.07f))
        }
        2 -> {
            val yel = Color(0xFFFFD43B); val dark = Color(0xFFF2B600); val orange = Color(0xFFFF8A00)
            drawLine(orange, Offset(-s * 0.08f + lp, -s * 0.14f), Offset(-s * 0.1f + lp, 0f), s * 0.05f, StrokeCap.Round)
            drawLine(orange, Offset(s * 0.08f - lp, -s * 0.14f), Offset(s * 0.1f - lp, 0f), s * 0.05f, StrokeCap.Round)
            drawCircle(yel, s * 0.32f, Offset(-s * 0.02f, -s * 0.42f))
            val flap = if (!p.ground || p.flap || p.held) sin(time * 30f) * 25f else 0f
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
        3 -> {
            val black = Color(0xFF26303D); val white = Color(0xFFF7F7F7); val orange = Color(0xFFFF9A1F)
            drawOval(orange, Offset(-s * 0.2f + lp, -s * 0.06f), Size(s * 0.18f, s * 0.08f))
            drawOval(orange, Offset(s * 0.04f - lp, -s * 0.06f), Size(s * 0.18f, s * 0.08f))
            drawOval(black, Offset(-s * 0.3f, -s * 1.02f), Size(s * 0.6f, s * 1.0f))
            drawOval(white, Offset(-s * 0.12f, -s * 0.72f), Size(s * 0.38f, s * 0.66f))
            val flap = if (p.dance > 0f || !p.ground || p.held || p.slide > 0f) sin(time * 18f) * 30f else 8f
            withTransform({ rotate(flap, Offset(-s * 0.2f, -s * 0.62f)) }) {
                drawOval(black, Offset(-s * 0.34f, -s * 0.64f), Size(s * 0.14f, s * 0.42f))
            }
            hx = s * 0.08f; hy = -s * 0.8f
            val beak = Path().apply {
                moveTo(hx + s * 0.16f, hy + s * 0.02f); lineTo(hx + s * 0.32f, hy + s * 0.07f); lineTo(hx + s * 0.16f, hy + s * 0.12f); close()
            }
            drawPath(beak, orange)
        }
        4 -> {
            val orange = Color(0xFFF28A3A); val dark = Color(0xFFD9692A); val white = Color(0xFFFFF4E6)
            petLegs(dark, s, lp)
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
        else -> {
            val gray = Color(0xFFB9BCC4); val dark = Color(0xFF9EA2AB); val pink = Color(0xFFFFB3C7)
            petLegs(dark, s, lp)
            val wag = sin(time * (if (p.purr > 0f) 6f else 2.5f) + p.seed * 7f) * s * 0.12f
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
    if (p.kind == 3) { drawCircle(Color.White, s * 0.07f, e1); drawCircle(Color.White, s * 0.07f, e2) }
    drawPetEye(e1, s * 0.045f, p.eyes, p.lookUp, d, time)
    drawPetEye(e2, s * 0.045f, p.eyes, p.lookUp, d, time)
    val m = Offset(hx + s * 0.12f, hy + s * 0.14f)
    when (p.mouth) {
        1 -> drawOval(Color(0xFF5A2330), Offset(m.x - s * 0.035f, m.y - s * 0.03f), Size(s * 0.07f, s * 0.08f))
        2 -> drawArc(eyeC, 20f, 140f, false, Offset(m.x - s * 0.06f, m.y - s * 0.08f), Size(s * 0.12f, s * 0.1f), style = Stroke(0.6f * d, cap = StrokeCap.Round))
    }
    // ---- ağızda havuç (Kendi arasında)
    if (p.carry == 1) {
        val cr = Path().apply { moveTo(m.x, m.y - s * 0.03f); lineTo(m.x + s * 0.3f, m.y + s * 0.02f); lineTo(m.x, m.y + s * 0.06f); close() }
        drawPath(cr, Color(0xFFFF8A2B))
    }
    // ---- aksesuarlar (Büyüme)
    when (p.acc) {
        1 -> { // atkı
            val col = Color(0xFFE5484D)
            drawRoundRect(col, Offset(hx - s * 0.24f, hy + s * 0.17f), Size(s * 0.46f, s * 0.1f), androidx.compose.ui.geometry.CornerRadius(s * 0.05f))
            val sway = sin(time * 3f + p.seed * 4f) * s * 0.04f
            drawRoundRect(col, Offset(hx - s * 0.2f + sway, hy + s * 0.2f), Size(s * 0.09f, s * 0.24f), androidx.compose.ui.geometry.CornerRadius(s * 0.04f))
            drawLine(Color.White.copy(alpha = 0.6f), Offset(hx - s * 0.2f, hy + s * 0.22f), Offset(hx + s * 0.18f, hy + s * 0.22f), 0.5f * d)
        }
        2 -> { // parti şapkası
            val top = Offset(hx - s * 0.02f, hy - s * 0.62f)
            val hat = Path().apply { moveTo(hx - s * 0.18f, hy - s * 0.2f); lineTo(top.x, top.y); lineTo(hx + s * 0.14f, hy - s * 0.22f); close() }
            drawPath(hat, Color(0xFF5CB8FF))
            drawLine(Color(0xFFFFD43B), Offset(hx - s * 0.12f, hy - s * 0.32f), Offset(hx + s * 0.09f, hy - s * 0.34f), 0.8f * d)
            drawCircle(Color(0xFFFF5C8A), s * 0.05f, top)
        }
        3 -> { // taç
            val gold = Color(0xFFFFC83D)
            val by = hy - s * 0.22f
            val crown = Path().apply {
                moveTo(hx - s * 0.17f, by); lineTo(hx - s * 0.17f, by - s * 0.14f); lineTo(hx - s * 0.08f, by - s * 0.06f)
                lineTo(hx + s * 0.01f, by - s * 0.18f); lineTo(hx + s * 0.1f, by - s * 0.06f); lineTo(hx + s * 0.18f, by - s * 0.14f)
                lineTo(hx + s * 0.18f, by); close()
            }
            drawPath(crown, gold)
            drawCircle(Color(0xFFFF4D6D), s * 0.025f, Offset(hx + s * 0.01f, by - s * 0.05f))
            val sp = (sin(time * 4f + p.seed * 9f) * 0.5f + 0.5f)
            drawCircle(Color.White.copy(alpha = sp), s * 0.02f, Offset(hx - s * 0.1f, by - s * 0.03f))
        }
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
    when (p.prop) {
        1 -> {
            val col = when (p.kind) { 1 -> Color(0xFFFF6FA5); 2 -> Color(0xFF4DA3FF); 3 -> Color(0xFFFFC23D); 4 -> Color(0xFF7ED957); else -> Color(0xFFFF5A5A) }
            val top = Offset(s * 0.05f, -s * 1.42f)
            drawLine(Color(0xFF5B4636), Offset(s * 0.05f, -s * 0.45f), top, 0.7f * d, StrokeCap.Round)
            drawArc(col, 180f, 180f, true, Offset(top.x - s * 0.55f, top.y - s * 0.4f), Size(s * 1.1f, s * 0.8f))
            for (k in 0 until 4) drawCircle(col, s * 0.14f, Offset(top.x - s * 0.41f + k * s * 0.275f, top.y))
        }
        2 -> if (p.state != P_SLEEP) {
            val gl = Color(0xFF111111)
            drawOval(gl, Offset(e1.x - s * 0.08f, e1.y - s * 0.05f), Size(s * 0.13f, s * 0.1f))
            drawOval(gl, Offset(e2.x - s * 0.06f, e2.y - s * 0.05f), Size(s * 0.13f, s * 0.1f))
            drawLine(gl, Offset(e1.x + s * 0.04f, e1.y), Offset(e2.x - s * 0.05f, e2.y), 0.6f * d)
            drawLine(Color.White.copy(alpha = 0.5f), Offset(e1.x - s * 0.04f, e1.y - s * 0.02f), Offset(e1.x - s * 0.01f, e1.y - s * 0.035f), 0.5f * d)
        }
        3 -> {
            val c = Offset(hx + s * 0.02f, hy - s * 0.02f)
            drawCircle(Color(0xFFBFEFFF).copy(alpha = 0.18f), s * 0.42f, c)
            drawCircle(Color.White.copy(alpha = 0.7f), s * 0.42f, c, style = Stroke(0.8f * d))
            drawArc(Color.White.copy(alpha = 0.85f), 200f, 50f, false, Offset(c.x - s * 0.32f, c.y - s * 0.32f), Size(s * 0.64f, s * 0.64f), style = Stroke(0.8f * d, cap = StrokeCap.Round))
        }
    }
}

private fun DrawScope.petLegs(c: Color, s: Float, ph: Float) {
    drawLine(c, Offset(-s * 0.22f + ph, -s * 0.2f), Offset(-s * 0.24f + ph, 0f), s * 0.09f, StrokeCap.Round)
    drawLine(c, Offset(s * 0.12f - ph, -s * 0.2f), Offset(s * 0.14f - ph, 0f), s * 0.09f, StrokeCap.Round)
}

private fun DrawScope.drawPetEye(c: Offset, r: Float, mode: Int, up: Boolean, d: Float, time: Float) {
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

internal fun DrawScope.drawPetEmote(e: Int, c: Offset, s: Float, d: Float, p: Pet, time: Float) {
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
            val y = c.y - (1f - (p.emoteT / 1.3f).coerceIn(0f, 1f)) * s * 0.4f
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
            val col = Color.White
            for (q in 0 until 3) {
                val ph = ((time * 0.6f + q / 3f) % 1f)
                val z = s * (0.08f + 0.05f * q)
                val zx = c.x + q * s * 0.14f; val zy = c.y - ph * s * 0.5f
                val zp = Path().apply { moveTo(zx - z, zy - z); lineTo(zx + z, zy - z); lineTo(zx - z, zy + z); lineTo(zx + z, zy + z) }
                drawPath(zp, col.copy(alpha = (1f - ph) * 0.85f), style = Stroke(0.6f * d, cap = StrokeCap.Round))
            }
        }
        E_SWEAT -> {
            val dp = Path().apply {
                moveTo(c.x + s * 0.2f, c.y - s * 0.2f)
                quadraticTo(c.x + s * 0.32f, c.y, c.x + s * 0.2f, c.y + s * 0.05f)
                quadraticTo(c.x + s * 0.08f, c.y, c.x + s * 0.2f, c.y - s * 0.2f)
                close()
            }
            drawPath(dp, Color(0xFF7FC8FF))
        }
        E_SPARK, E_DIZZY -> {
            val col = if (e == E_SPARK) Color(0xFFFFF2A8) else Color(0xFFFFE066)
            for (q in 0 until 3) {
                val a = time * (if (e == E_SPARK) 3f else 6f) + q * 2.094f
                val sc = if (e == E_SPARK) Offset(c.x + cos(a) * s * 0.22f, c.y - s * 0.12f + sin(a) * s * 0.12f)
                else Offset(c.x + cos(a) * s * 0.28f, c.y + s * 0.2f + sin(a) * s * 0.08f)
                val r = s * 0.065f * (if (e == E_SPARK) pop else 1f)
                drawLine(col, Offset(sc.x - r, sc.y), Offset(sc.x + r, sc.y), 0.6f * d, StrokeCap.Round)
                drawLine(col, Offset(sc.x, sc.y - r), Offset(sc.x, sc.y + r), 0.6f * d, StrokeCap.Round)
            }
        }
    }
}

// ================================================================ Kapalı adada (oyuncak ada penceresi)
/**
 * Ada kapalıyken hayvanlar adanın kenarında yaşar. Ada üstteyse alt kenarından tutunup sarkar ve sallanır, biri adanın
 * arkasından arada kafasını çıkarıp bakar; ada alttaysa üst kenarına oturur. Bildirim gelince irkilip sallanır.
 * Gece uyurlar. Pil için saniyede ~20 kare çizilir.
 */
@Composable
internal fun CompactPetsFx(a: PlayAnchor, count: Int, growth: Boolean) {
    val bus = LocalPlayBus.current
    val ctx = LocalContext.current
    val show = a.compactVisible && a.shown && PlayFx.pocket < 0.5f
    val vis = remember { Animatable(0f) }
    LaunchedEffect(show) { vis.animateTo(if (show) 1f else 0f, tween(if (show) 500 else 250)) }
    var alertAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { bus.events.collect { if (it == PlayEvent.Notif) alertAt = SystemClock.uptimeMillis() } }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(show) {
        if (!show) return@LaunchedEffect
        while (true) { delay(50L); frame = SystemClock.uptimeMillis() }
    }
    val n = count.coerceIn(1, FX_PET_KINDS)
    val pets = remember(n) { List(n) { Pet(it % FX_PET_KINDS, it * 0.37f + 0.11f) } }
    LaunchedEffect(growth) { if (growth) PetLife.init(ctx) }
    if (vis.value <= 0.01f) return
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val fr = frame
        val time = SystemClock.uptimeMillis() / 1000f
        val d = density
        val s = 11f * d
        val alert = ((SystemClock.uptimeMillis() - alertAt) / 1000f).let { if (alertAt == 0L || it > 1.4f) 0f else 1f - it / 1.4f }
        val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val sleepy = growth && (h >= 23 || h < 6) && alert == 0f
        val left = a.cx - a.w / 2f
        val fr2 = floatArrayOf(0.28f, 0.72f, 0.5f, 0.16f, 0.84f)
        val al = vis.value
        for ((i, p) in pets.withIndex()) {
            p.acc = if (growth) PetLife.accFor(i) else 0
            p.eyes = when { alert > 0f -> 3; sleepy -> 1; (time * 0.5f + p.seed * 7f) % 4f < 0.12f -> 1; else -> 0 }
            p.mouth = if (alert > 0f) 1 else 0
            p.emote = when { alert > 0.3f -> E_BANG; sleepy -> E_ZZZ; else -> E_NONE }
            p.emoteT = 1f
            p.ground = true; p.vx = 0f
            val peeker = i == 1 && n >= 2
            if (a.inverted) {
                // ada altta: üst kenarına oturur
                p.x = left + a.w * fr2[i]
                p.y = a.top - alert * sin(alert * PI.toFloat()) * 8f * d
                p.face = if (i % 2 == 0) 1f else -1f
                p.state = if (sleepy) P_SLEEP else P_SIT
                drawPetFade(al) { drawPetWorld(p, s, d, time, false) }
            } else if (peeker) {
                // adanın sağ ucunun arkasından kafasını çıkarır (ada üstte olduğu için gövdenin kalanı adanın arkasında kalır)
                val cyc = ((time + p.seed * 10f) % 7f)
                val out = when { alert > 0f -> 1f; sleepy -> 0f; cyc < 0.4f -> cyc / 0.4f; cyc < 2.4f -> 1f; cyc < 2.8f -> 1f - (cyc - 2.4f) / 0.4f; else -> 0f }
                p.face = -1f
                p.x = left + a.w - s * 0.1f + out * s * 0.75f
                p.y = a.top + a.h * 0.5f + s * 0.45f
                p.state = P_SIT
                drawPetFade(al) { drawPetWorld(p, s, d, time, false) }
            } else {
                // alt kenardan tutunup sarkar, sallanır
                val gxp = left + a.w * fr2[i]
                val gyp = a.top + a.h - 1f * d
                val amp = 7f + alert * 26f
                val swing = sin(time * (1.4f + p.seed) + p.seed * 9f) * amp * (if (sleepy) 0.3f else 1f)
                p.face = if (i % 2 == 0) 1f else -1f
                p.state = P_IDLE
                drawPetFade(al) {
                    withTransform({ rotate(swing, Offset(gxp, gyp)) }) {
                        val paw = when (p.kind) { 1 -> Color.White; 2 -> Color(0xFFFFD43B); 3 -> Color(0xFF26303D); 4 -> Color(0xFFF28A3A); else -> Color(0xFFB9BCC4) }
                        drawLine(paw, Offset(gxp - s * 0.14f, gyp), Offset(gxp - s * 0.1f, gyp + s * 0.32f), s * 0.1f, StrokeCap.Round)
                        drawLine(paw, Offset(gxp + s * 0.14f, gyp), Offset(gxp + s * 0.1f, gyp + s * 0.32f), s * 0.1f, StrokeCap.Round)
                        p.x = gxp - p.face * s * 0.2f
                        p.y = gyp + s * 1.12f
                        drawPetWorld(p, s, d, time, false)
                    }
                }
            }
        }
    }
}

private inline fun DrawScope.drawPetFade(alpha: Float, block: DrawScope.() -> Unit) {
    if (alpha >= 0.99f) { block(); return }
    drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height),
        androidx.compose.ui.graphics.Paint().apply { this.alpha = alpha })
    block()
    drawContext.canvas.restore()
}
