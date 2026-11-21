package com.example.dynamicisland

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import java.io.File
import java.time.LocalDate

/*
 * Oyuncak ada: günler boyunca saklanan küçük veriler (bitki, çizim, kutlama günü, ritim kısayolları).
 * Uygulama ve ada servisi aynı süreçte çalışır; değişiklik anında iki tarafa da yansır.
 */

private fun today(): Long = LocalDate.now().toEpochDay()

// ================================================================ Ada saksısı

/**
 * Adadan sarkan bitki. Her gün adaya ilk dokunuş onu sular.
 *  - Bugün sulandı: canlı, büyür (günde 1 adım, en fazla 14). 7 adımdan sonra çiçek açar.
 *  - Dün sulandı: susamış, yaprakları hafifçe düşük.
 *  - 2+ gündür sulanmadı: solar (kahverengileşir), sulanmayan her gün bir adım küçülür.
 */
object PlantStore {
    private const val PREF = "island_plant"
    var growth by mutableIntStateOf(1)            // 1..14
        private set
    var lastWater by mutableLongStateOf(0L)       // epoch günü
        private set
    var loaded = false
        private set

    fun load(ctx: Context) {
        if (loaded) return
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        growth = p.getInt("growth", 1)
        lastWater = p.getLong("last", today())
        loaded = true
        decay(ctx)
    }

    /** Sulanmayan günler kadar küçülür (uygulama günlerce açılmasa da doğru hesaplanır). */
    fun decay(ctx: Context) {
        val missed = (today() - lastWater - 1).coerceAtLeast(0L)
        if (missed > 0) {
            val g = (growth - missed.toInt()).coerceAtLeast(1)
            if (g != growth) { growth = g; save(ctx) }
        }
    }

    /** 0 = bugün sulandı (canlı), 1 = susadı, 2 = soldu. */
    val thirst: Int get() = when ((today() - lastWater).coerceAtLeast(0L)) { 0L -> 0; 1L -> 1; else -> 2 }

    /** Bugün henüz sulanmadıysa sular ve true döner. */
    fun water(ctx: Context): Boolean {
        load(ctx)
        if (lastWater == today()) return false
        growth = (growth + 1).coerceAtMost(14)
        lastWater = today()
        save(ctx)
        return true
    }

    private fun save(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("growth", growth).putLong("last", lastWater).apply()
    }
}

// ================================================================ Karalama tahtası

/** Adaya çizilen resim: çizgiler 0..1 aralığında (adanın genişliği / yüksekliği) saklanır. */
object DoodleStore {
    private const val FILE = "island_doodle.txt"
    var strokes by mutableStateOf<List<List<Offset>>>(emptyList())
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        strokes = runCatching {
            File(ctx.filesDir, FILE).takeIf { it.exists() }?.readLines().orEmpty().mapNotNull { line ->
                val pts = line.split(' ').mapNotNull { p ->
                    val xy = p.split(',')
                    if (xy.size == 2) Offset(xy[0].toFloat(), xy[1].toFloat()) else null
                }
                pts.takeIf { it.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, s: List<List<Offset>>) {
        strokes = s
        loaded = true
        runCatching {
            File(ctx.filesDir, FILE).writeText(s.joinToString("\n") { st -> st.joinToString(" ") { "%.4f,%.4f".format(java.util.Locale.US, it.x, it.y) } })
        }
    }
}

// ================================================================ Kutlama

object PartyStore {
    private const val PREF = "island_party"
    /** Bugün henüz tarih kutlaması (doğum günü / yılbaşı) yapılmadıysa işaretler ve true döner. */
    fun claimToday(ctx: Context): Boolean {
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val t = today()
        if (p.getLong("day", -1L) == t) return false
        p.edit().putLong("day", t).apply()
        return true
    }
}

// ================================================================ Ritim kısayolları

/** Ritim: dokunuşlar arasındaki aralıklar. S = kısa ara, L = uzun ara (en kısa aranın ~1,7 katından uzun). */
object Rhythm {
    fun classify(gapsMs: List<Long>): String {
        if (gapsMs.isEmpty()) return ""
        val minGap = gapsMs.min().coerceAtLeast(60L)
        return gapsMs.joinToString("") { if (it > minGap * 1.7f) "L" else "S" }
    }

    /** "SSL=com.a;LS=com.b" -> [("SSL","com.a"), ("LS","com.b")] */
    fun parse(s: String): List<Pair<String, String>> =
        s.split(';').mapNotNull { e ->
            val i = e.indexOf('=')
            if (i <= 0 || i == e.length - 1) null else e.substring(0, i) to e.substring(i + 1)
        }

    fun join(list: List<Pair<String, String>>): String = list.joinToString(";") { "${it.first}=${it.second}" }

    /** Ekranda gösterilecek hali: S -> "•", L -> "—" (dokunuşlar arası) */
    fun pretty(p: String): String = "●" + p.map { if (it == 'L') " — ●" else " ●" }.joinToString("")
}
