package com.example.dynamicisland

import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

// ---------------------------------------------------------------- LRC ayrıştırıcı
object LrcParser {
    private val TS = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD_TS = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")

    fun parse(lrc: String): List<LyricLine> {
        val out = ArrayList<LyricLine>()
        for (raw in lrc.lineSequence()) {
            val line = raw.trim()
            if (!line.startsWith("[")) continue
            val stamps = ArrayList<Long>()
            var pos = 0
            while (true) {
                val m = TS.matchAt(line, pos) ?: break
                val min = m.groupValues[1].toLong()
                val sec = m.groupValues[2].toLong()
                val fr = m.groupValues[3]
                val ms = when (fr.length) {
                    0 -> 0L
                    1 -> fr.toLong() * 100
                    2 -> fr.toLong() * 10
                    else -> fr.take(3).toLong()
                }
                stamps.add(min * 60_000 + sec * 1000 + ms)
                pos = m.range.last + 1
            }
            if (stamps.isEmpty()) continue
            val text = WORD_TS.replace(line.substring(pos), "").trim()
            for (t in stamps) out.add(LyricLine(t, text))
        }
        out.sortBy { it.timeMs }
        // art arda gelen boş satırları tek boşluğa indir
        val res = ArrayList<LyricLine>(out.size)
        for (l in out) {
            val last = res.lastOrNull()
            if (last != null && last.text.isEmpty() && l.text.isEmpty()) continue
            res.add(l)
        }
        return res
    }

    /** Senkronsuz (düz) sözleri şarkı süresine eşit aralıklarla yayar. */
    fun spread(plain: String, durationMs: Long): List<LyricLine> {
        val ls = plain.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (ls.isEmpty()) return emptyList()
        val dur = if (durationMs > 0) durationMs else 180_000L
        val start = (dur * 0.06).toLong()
        val end = (dur * 0.94).toLong()
        val step = (end - start).toDouble() / ls.size
        return ls.mapIndexed { i, t -> LyricLine(start + (i * step).toLong(), t) }
    }
}

// ---------------------------------------------------------------- Çevrimiçi arama
object LyricsRepository {
    private const val UA = "DynamicIsland/1.0 (https://github.com/whiteglow178/Dynamic-Island)"
    private val cache = LruCache<String, LyricsState>(40)
    fun cached(key: String): LyricsState? = cache.get(key)

    /** LRCLIB'de aranır. Bulunamazsa NotFound (ağ hatası önbelleğe alınmaz). */
    suspend fun fetch(key: String, title: String, artist: String, durationMs: Long): LyricsState =
        withContext(Dispatchers.IO) {
            val cands = candidates(title, artist)
            var netError = false
            var best: LyricsState.Ready? = null

            for ((t, a) in cands) {
                try {
                    val r = lrclib(t, a, durationMs)
                    if (r != null) {
                        if (r.synced) { best = r; break }
                        if (best == null) best = r
                    }
                } catch (e: IOException) { netError = true } catch (e: Exception) { /* bozuk yanıt: sıradakini dene */ }
            }
            val result: LyricsState = best ?: LyricsState.NotFound
            if (best != null || !netError) cache.put(key, result)
            result
        }

    // ------------------------------------------------------------ LRCLIB
    private fun lrclib(title: String, artist: String, durMs: Long): LyricsState.Ready? {
        val sec = (durMs / 1000).toInt()
        if (artist.isNotBlank()) {
            val url = "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                if (sec > 0) "&duration=$sec" else ""
            httpGet(url)?.let { pickLrclib(JSONObject(it), durMs) }?.let { return it }
        }
        val q = if (artist.isNotBlank()) "track_name=${enc(title)}&artist_name=${enc(artist)}" else "q=${enc(title)}"
        val body = httpGet("https://lrclib.net/api/search?$q") ?: return null
        val arr = JSONArray(body)
        var best: JSONObject? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optBoolean("instrumental", false)) continue
            val hasSynced = o.str("syncedLyrics").isNotBlank()
            if (!hasSynced && o.str("plainLyrics").isBlank()) continue
            val d = if (durMs > 0 && o.has("duration") && !o.isNull("duration"))
                abs(o.optDouble("duration") * 1000 - durMs).toLong() else 0L
            if (durMs > 0 && d > 6000) continue
            val score = d + if (hasSynced) 0L else 4000L
            if (score < bestScore) { bestScore = score; best = o }
        }
        return best?.let { pickLrclib(it, durMs) }
    }

    private fun pickLrclib(o: JSONObject, durMs: Long): LyricsState.Ready? {
        if (o.optBoolean("instrumental", false)) return null
        val synced = o.str("syncedLyrics")
        if (synced.isNotBlank()) {
            val l = LrcParser.parse(synced)
            if (l.isNotEmpty()) return LyricsState.Ready(l, true, "LRCLIB")
        }
        val plain = o.str("plainLyrics")
        if (plain.isNotBlank()) {
            val l = LrcParser.spread(plain, durMs)
            if (l.isNotEmpty()) return LyricsState.Ready(l, false, "LRCLIB")
        }
        return null
    }

    // ------------------------------------------------------------ Yardımcılar
    private val NOISE = Regex(
        """\s*[(\[][^)\]]*(official|video|lyric|audio|remaster|visualizer|clip|resmi|söz|feat|ft\.)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val SUFFIX = Regex("""\s+-\s+(remaster|live|radio edit|single|official).*""", RegexOption.IGNORE_CASE)

    private fun cleanTitle(t: String) = t.replace(NOISE, "").replace(SUFFIX, "").trim()
    private fun firstArtist(a: String) =
        a.split(",", "&", ";", " feat.", " ft.", " x ").first().trim()

    private fun candidates(title: String, artist: String): List<Pair<String, String>> {
        val a0 = artist.replace(" - Topic", "").trim()
        val t1 = cleanTitle(title)
        val a1 = firstArtist(a0)
        return listOf(title.trim() to a0, t1 to a1, t1 to "").filter { it.first.isNotBlank() }.distinct()
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun JSONObject.str(k: String): String = if (isNull(k)) "" else optString(k, "")

    /** 404 -> null, diğer hatalar -> IOException. */
    private fun httpGet(url: String, headers: Map<String, String> = emptyMap()): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6_000
            c.readTimeout = 8_000
            c.setRequestProperty("User-Agent", UA)
            headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
            val code = c.responseCode
            if (code == 404) return null
            if (code !in 200..299) throw IOException("HTTP $code")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
