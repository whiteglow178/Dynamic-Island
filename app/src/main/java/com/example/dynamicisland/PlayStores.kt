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

// ================================================================ Ada ziyaretçileri

/** Ziyaretçi türleri. [weight] ne kadar sık geldiği; [night] yalnızca gece gelenler. */
enum class VisitorKind(val title: String, val weight: Int, val night: Boolean = false) {
    Butterfly("Kelebek", 30), Bird("Serçe", 25), Ladybug("Uğur böceği", 20), Bee("Arı", 14),
    Firefly("Ateş böceği", 12, night = true), Star("Kayan yıldız", 4, night = true), Ufo("UFO", 2), GoldButterfly("Altın kelebek", 1);

    val rarity: String get() = when { weight >= 20 -> "Sık"; weight >= 7 -> "Ara sıra"; weight >= 3 -> "Nadir"; else -> "Efsanevi" }
}

/**
 * Günde birkaç kez (2,5–7 saatte bir, ekran açıkken) bir ziyaretçi gelir, ~25 sn adanın çevresinde dolaşır.
 * O sırada adaya dokunursan yakalanır ve albüme eklenir.
 */
object VisitorStore {
    private const val PREF = "island_visitors"
    var current by mutableStateOf<VisitorKind?>(null)          // şu an ekrandaki ziyaretçi
    var arrivedAt = 0L
    var counts by mutableStateOf<Map<VisitorKind, Int>>(emptyMap())
        private set
    private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        loaded = true
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        counts = VisitorKind.entries.associateWith { p.getInt(it.name, 0) }.filterValues { it > 0 }
        if (!p.contains("next")) p.edit().putLong("next", System.currentTimeMillis() + (5..30).random() * 60_000L).apply()
    }

    /** Sırası geldiyse bir ziyaretçi gönderir (ekran açık ve ayar açıkken çağrılır). */
    fun maybeVisit(ctx: Context, force: Boolean = false) {
        load(ctx)
        if (current != null) return
        val p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now < p.getLong("next", 0L)) return
        p.edit().putLong("next", now + (150..420).random() * 60_000L).apply()
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        val night = hour >= 20 || hour < 6
        val pool = VisitorKind.entries.filter { !it.night || night }
        var r = (0 until pool.sumOf { it.weight }).random()
        val kind = pool.first { r -= it.weight; r < 0 }
        arrivedAt = System.currentTimeMillis()
        current = kind
    }

    /** Ekrandaki ziyaretçiyi yakalar. */
    fun catchNow(ctx: Context): VisitorKind? {
        val k = current ?: return null
        load(ctx)
        val n = (counts[k] ?: 0) + 1
        counts = counts + (k to n)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putInt(k.name, n).apply()
        current = null
        return k
    }
}

// ================================================================ Nefes egzersizi

object BreathSession {
    var active by mutableStateOf(false)
        private set
    var startedAt = 0L
        private set

    fun start() { startedAt = android.os.SystemClock.uptimeMillis(); active = true }
    fun stop() { active = false }

    /** Desen: (al, tut, ver, tut) saniye. */
    fun pattern(i: Int): FloatArray = when (i) {
        1 -> floatArrayOf(4f, 7f, 8f, 0f)       // uyku (4-7-8)
        2 -> floatArrayOf(4f, 4f, 4f, 4f)       // kutu nefesi
        else -> floatArrayOf(4f, 4f, 4f, 0f)    // sakin
    }
}
