package com.example.dynamicisland

import android.app.Notification
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Canlı etkinlik kartının (açık ada) yüksekliği. */
const val LIVE_H_DP = 128f

private val LIVE_GREEN = Color(0xFF30D158)
private val LIVE_STAGES = listOf("Alındı", "Hazırlanıyor", "Yolda", "Teslim edildi")

/** Servis ayarlardan yazar, bildirim dinleyicisi okur: özellik kapalıysa bildirim normal yoldan gider. */
object LiveFlags {
    @Volatile var nav = false
    @Volatile var delivery = false
    @Volatile var progress = false
    @Volatile var score = false
    val any: Boolean get() = nav || delivery || progress || score
}

// ================================================================ Bildirimden ayrıştırma

object LiveParse {
    private val navPkgs = setOf(
        "com.google.android.apps.maps", "com.google.android.apps.mapslite", "com.waze",
        "ru.yandex.yandexnavi", "ru.yandex.yandexmaps", "com.here.app.maps", "com.sygic.aura",
        "net.osmand", "net.osmand.plus", "com.mapfactor.navigator", "cz.seznam.mapy", "com.tomtom.gplay.navapp",
    )
    private val deliveryPkgHints = listOf(
        "trendyol", "getir", "yemeksepeti", "migros", "hepsiburada", "amazon", "ubereats", "uber.eats", "glovo",
        "deliveroo", "doordash", "wolt", "aliexpress", "temu", "a101", "carrefour", "istegelsin", "banabi",
        "yurtici", "araskargo", "aras.kargo", "mngkargo", "suratkargo", "ptt", "kargo", "dhl", "ups.mobile", "fedex",
        "n11", "ciceksepeti", "pazarama", "shein", "macrocenter", "sokmarket", "bim", "tazedirekt",
    )
    private val stageWords = listOf(
        // 3 teslim edildi
        listOf("teslim edildi", "teslim edilmiştir", "teslim aldın", "teslim alındı", "delivered", "afiyet olsun", "enjoy your meal"),
        // 2 yolda
        listOf("yolda", "kurye", "on the way", "on its way", "dağıtıma", "dagitima", "out for delivery", "picked up", "yola çıktı",
            "kargoya verildi", "kargoya teslim", "transfer", "dağıtım", "courier", "en route", "yaklaşıyor", "kapında", "arriving"),
        // 1 hazırlanıyor
        listOf("hazırlan", "hazirlan", "preparing", "paketlen", "being prepared", "restoran onayladı", "toplanıyor"),
        // 0 alındı
        listOf("sipariş alındı", "siparişin alındı", "siparişiniz alındı", "onaylandı", "order received", "order confirmed", "order placed"),
    )
    private val promoWords = listOf("indirim", "kampanya", "kupon", "fırsat", "firsat", "kazan", "% ", "promo", "discount", "deal", "sale", "ücretsiz teslimat")
    private val distRx = Regex("""(\d+(?:[.,]\d+)?)\s?(km|m|mi|ft|yd)\b""", RegexOption.IGNORE_CASE)
    private val minRx = Regex("""(\d{1,3})(?:\s*[-–]\s*(\d{1,3}))?\s*(dk|dakika|min|mins|minutes|dakikada)\b""", RegexOption.IGNORE_CASE)

    private fun str(n: Notification, key: String) = n.extras.getCharSequence(key)?.toString().orEmpty().trim()

    private fun ongoing(sbn: StatusBarNotification) =
        sbn.isOngoing || (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT) != 0

    private fun progressOf(n: Notification): Float {
        val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0) return -1f
        return (n.extras.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
    }

    /** Navigasyon bildirimi mi? (Haritalar, Yandex, Waze... ya da "navigation" kategorisindeki devam eden bildirim) */
    fun nav(sbn: StatusBarNotification, icon: ImageBitmap?): IslandContent.Live? {
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val catNav = n.category == "navigation"
        if (!ongoing(sbn) || (!catNav && sbn.packageName !in navPkgs)) return null
        val title = str(n, Notification.EXTRA_TITLE)
        val text = str(n, Notification.EXTRA_TEXT).ifBlank { str(n, Notification.EXTRA_BIG_TEXT) }
        val sub = str(n, Notification.EXTRA_SUB_TEXT)
        if (title.isBlank() && text.isBlank()) return null
        val dist = distRx.find(title) ?: distRx.find(text)
        // Navigasyon değil (ör. "Konum paylaşılıyor"): mesafe ya da dönüş oku yoksa alma
        if (!catNav && dist == null && icon == null) return null
        val big = dist?.value?.replace(" ", " ") ?: ""
        // Talimat: mesafeyi çıkarınca kalan en uzun yazı
        val t1 = title.replace(dist?.value ?: "\u0000", "").trim(' ', '·', '-', ',')
        val instr = listOf(t1, text).filter { it.isNotBlank() }.maxByOrNull { it.length }.orEmpty()
        val other = listOf(t1, text).firstOrNull { it.isNotBlank() && it != instr }.orEmpty()
        return IslandContent.Live(
            kind = LiveKind.Nav, pkg = sbn.packageName, key = sbn.key,
            big = big.ifBlank { title.take(8) }, title = instr, text = other, sub = sub,
            icon = icon, progress = progressOf(n), intent = n.contentIntent,
        )
    }

    /** Sipariş / kargo bildirimi mi? Reklam ve kampanya bildirimleri alınmaz. */
    fun delivery(sbn: StatusBarNotification, icon: ImageBitmap?): IslandContent.Live? {
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val pkg = sbn.packageName.lowercase()
        // Android 16 "canlı güncelleme" (öne çıkarılmış devam eden bildirim) her uygulamadan kabul edilir
        val promoted = (n.flags and 0x00040000) != 0
        if (!promoted && deliveryPkgHints.none { pkg.contains(it) }) return null
        val cat = n.category.orEmpty()
        if (cat == Notification.CATEGORY_PROMO || cat == Notification.CATEGORY_RECOMMENDATION) return null
        val title = str(n, Notification.EXTRA_TITLE)
        val text = str(n, Notification.EXTRA_TEXT).ifBlank { str(n, Notification.EXTRA_BIG_TEXT) }
        val sub = str(n, Notification.EXTRA_SUB_TEXT)
        if (title.isBlank() && text.isBlank()) return null
        val all = "$title $text $sub".lowercase()
        if (!promoted && promoWords.any { all.contains(it) }) return null
        var stage = -1
        for (i in stageWords.indices) if (stageWords[i].any { all.contains(it) }) { stage = 3 - i; break }
        val prog = progressOf(n)
        if (stage < 0 && !(promoted || (ongoing(sbn) && prog >= 0f))) return null
        val mins = minRx.find(all)
        val big = when {
            stage == 3 -> "Teslim"
            mins != null -> (mins.groupValues[2].ifBlank { mins.groupValues[1] }) + " dk"
            stage >= 0 -> LIVE_STAGES[stage]
            prog >= 0f -> "%" + (prog * 100).toInt()
            else -> ""
        }
        return IslandContent.Live(
            kind = LiveKind.Delivery, pkg = sbn.packageName, key = sbn.key,
            big = big, title = title.ifBlank { text }, text = if (title.isBlank()) "" else text, sub = sub,
            icon = icon, stage = stage, progress = prog, intent = n.contentIntent,
        )
    }
}

// ================================================================ İlerleme ve maç skoru (Birleşik özellikler)
object LiveParse2 {
    private fun str(n: Notification, key: String) = n.extras.getCharSequence(key)?.toString().orEmpty().trim()

    /**
     * İlerleme çubuklu, devam eden bildirim: indirme, yükleme, yedekleme, dosya aktarımı.
     * Belirsiz (dönen) ilerleme, medya oynatıcı, arama ve navigasyon bildirimleri alınmaz.
     */
    fun progress(sbn: StatusBarNotification, icon: ImageBitmap?): IslandContent.Live? {
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val ex = n.extras
        val max = ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        if (max <= 0 || ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return null
        val cat = n.category.orEmpty()
        if (cat == Notification.CATEGORY_CALL || cat == Notification.CATEGORY_TRANSPORT || cat == "navigation") return null
        if (ex.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true) return null
        val prog = (ex.getInt(Notification.EXTRA_PROGRESS, 0).toFloat() / max).coerceIn(0f, 1f)
        val title = str(n, Notification.EXTRA_TITLE)
        val text = str(n, Notification.EXTRA_TEXT).ifBlank { str(n, Notification.EXTRA_SUB_TEXT) }
        if (title.isBlank() && text.isBlank()) return null
        return IslandContent.Live(
            kind = LiveKind.Progress, pkg = sbn.packageName, key = sbn.key,
            big = if (prog >= 0.999f) "Tamam" else "%" + (prog * 100).toInt(),
            title = title.ifBlank { text }, text = if (title.isBlank()) "" else text,
            icon = icon, progress = prog, intent = n.contentIntent,
        )
    }

    private val sportsPkgHints = listOf(
        "mackolik", "sofascore", "fotmob", "flashscore", "livescore", "onefootball", "bein", "espn", "sporx", "fanatik",
        "forza", "365scores", "scores365", "nesine", "bilyoner", "iddaa", "tff", "uefa", "fifa", "nba", "thescore",
        "yahoo.sports", "sports", "futbol", "football", "soccer", "aspor", "trtspor", "tivibu", "exxen",
    )
    // "Galatasaray 2 - 1 Fenerbahçe"
    private val inlineRx = Regex("""^\s*(.{2,40}?)\s+(\d{1,2})\s*[-–:]\s*(\d{1,2})\s+(.{2,40}?)\s*$""")
    // "Galatasaray - Fenerbahçe" / "Galatasaray vs Fenerbahçe"
    private val teamsRx = Regex("""^\s*(.{2,40}?)\s+(?:-|–|vs\.?|v)\s+(.{2,40}?)\s*$""", RegexOption.IGNORE_CASE)
    private val scoreRx = Regex("""(?<![\d:])(\d{1,2})\s*[-–:]\s*(\d{1,2})(?![\d:])""")
    private val minuteRx = Regex("""(\d{1,3}(?:\+\d{1,2})?)\s*(?:'|’|′|\.?\s*dk\b|\.?\s*dakika)""", RegexOption.IGNORE_CASE)
    private val htRx = Regex("""devre arası|ilk yarı sonu|ilk yarı sona erdi|half[- ]?time|\bHT\b|\bİY\b""", RegexOption.IGNORE_CASE)
    private val ftRx = Regex("""maç sonu|maç bitti|maç sona erdi|maç tamamlandı|full[- ]?time|final score|\bFT\b|\bMS\b|match ended""", RegexOption.IGNORE_CASE)
    private val goalRx = Regex("""\bgo+l+\b|\bgoal\b|⚽""", RegexOption.IGNORE_CASE)
    private val junkRx = Regex("""^(?:go+l+!*|goal!*|⚽|🔴|canlı|live)[\s:!-]*""", RegexOption.IGNORE_CASE)

    private fun cleanTeam(s: String): String = s.replace(junkRx, "").replace(minuteRx, "")
        .replace(Regex("""\(.*?\)"""), "").trim(' ', '-', '–', ':', '|', '·', ',', '!')

    /**
     * Canlı maç skoru: takımlar ve skor başlıktan ya da yazıdan çıkarılır. Spor uygulaması değilse yalnızca
     * "Takım 2 - 1 Takım" kalıbı ve devam eden / gol bildirimi kabul edilir (sohbetlerdeki rastgele sayılar alınmasın).
     */
    fun score(sbn: StatusBarNotification, icon: ImageBitmap?): IslandContent.Live? {
        val n = sbn.notification ?: return null
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        val cat = n.category.orEmpty()
        if (cat == Notification.CATEGORY_MESSAGE || cat == Notification.CATEGORY_EMAIL || cat == Notification.CATEGORY_PROMO) return null
        val pkg = sbn.packageName.lowercase()
        val sportsApp = sportsPkgHints.any { pkg.contains(it) }
        val title = str(n, Notification.EXTRA_TITLE)
        val text = str(n, Notification.EXTRA_TEXT).ifBlank { str(n, Notification.EXTRA_BIG_TEXT) }
        val sub = str(n, Notification.EXTRA_SUB_TEXT)
        val all = "$title $text $sub"
        var a = ""; var b = ""; var sa = -1; var sb = -1
        for (line in listOf(title, text, sub).flatMap { it.split('\n') }) {
            val m = inlineRx.find(line.replace(junkRx, "").replace(Regex("""\s*\(.*?\)\s*$"""), "")) ?: continue
            a = cleanTeam(m.groupValues[1]); b = cleanTeam(m.groupValues[4])
            sa = m.groupValues[2].toInt(); sb = m.groupValues[3].toInt()
            if (a.isNotBlank() && b.isNotBlank()) break
        }
        if (a.isBlank() || b.isBlank()) {
            if (!sportsApp) return null
            val tm = teamsRx.find(title.replace(junkRx, "")) ?: return null
            a = cleanTeam(tm.groupValues[1]); b = cleanTeam(tm.groupValues[2])
            val sc = scoreRx.find(text) ?: scoreRx.find(sub) ?: return null
            sa = sc.groupValues[1].toInt(); sb = sc.groupValues[2].toInt()
        }
        if (a.isBlank() || b.isBlank() || a.any { it.isDigit() } && a.length < 3) return null
        val ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (!sportsApp && !(ongoing || goalRx.containsMatchIn(all))) return null
        val phase = when { ftRx.containsMatchIn(all) -> 2; htRx.containsMatchIn(all) -> 1; else -> 0 }
        val minute = minuteRx.find(all)?.groupValues?.get(1)?.let { "$it'" }.orEmpty()
        return IslandContent.Live(
            kind = LiveKind.Score, pkg = sbn.packageName, key = sbn.key,
            big = "$sa - $sb", title = "$a - $b", text = "", sub = minute,
            icon = icon, intent = n.contentIntent,
            teamA = a, teamB = b, scoreA = sa, scoreB = sb, minute = minute, phase = phase,
        )
    }
}

// ================================================================ Görünüm

@Composable
private fun LiveIcon(c: IslandContent.Live, size: Dp, preferApp: Boolean) {
    val img = if (c.kind == LiveKind.Nav && !preferApp) c.icon ?: c.appIcon else c.appIcon ?: c.icon
    if (img != null) {
        Image(
            bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.Medium,
            modifier = Modifier.size(size).clip(RoundedCornerShape(size * (if (c.kind == LiveKind.Nav && !preferApp) 0.12f else 0.26f))),
        )
    } else {
        Canvas(Modifier.size(size)) {
            val sc = this.size.width / 24f
            when (c.kind) {
                LiveKind.Nav -> drawTurnArrow(LIVE_GREEN, sc)
                LiveKind.Score -> drawBallGlyph(sc)
                LiveKind.Progress -> drawDownGlyph(LIVE_GREEN, sc)
                LiveKind.Delivery -> drawBoxGlyph(LIVE_GREEN, sc)
            }
        }
    }
}

private fun DrawScope.drawTurnArrow(color: Color, sc: Float) {
    val p = Path().apply {
        moveTo(12f * sc, 2f * sc); lineTo(20f * sc, 11f * sc); lineTo(15f * sc, 11f * sc)
        lineTo(15f * sc, 22f * sc); lineTo(9f * sc, 22f * sc); lineTo(9f * sc, 11f * sc); lineTo(4f * sc, 11f * sc); close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawBallGlyph(sc: Float) {
    val c = Offset(12f * sc, 12f * sc)
    drawCircle(Color.White, 10f * sc, c)
    val pent = Path().apply {
        for (k in 0 until 5) {
            val a = Math.toRadians(-90.0 + k * 72.0)
            val x = c.x + kotlin.math.cos(a).toFloat() * 4f * sc
            val y = c.y + kotlin.math.sin(a).toFloat() * 4f * sc
            if (k == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
    drawPath(pent, Color(0xFF1C1C1E))
}

private fun DrawScope.drawDownGlyph(color: Color, sc: Float) {
    val p = Path().apply {
        moveTo(9f * sc, 3f * sc); lineTo(15f * sc, 3f * sc); lineTo(15f * sc, 11f * sc); lineTo(19f * sc, 11f * sc)
        lineTo(12f * sc, 18f * sc); lineTo(5f * sc, 11f * sc); lineTo(9f * sc, 11f * sc); close()
    }
    drawPath(p, color)
    drawRoundRect(color, Offset(4f * sc, 19.5f * sc), Size(16f * sc, 2.5f * sc), CornerRadius(1.2f * sc))
}

private fun DrawScope.drawBoxGlyph(color: Color, sc: Float) {
    drawRoundRect(color, Offset(3f * sc, 7f * sc), Size(18f * sc, 14f * sc), CornerRadius(2.5f * sc))
    drawRoundRect(color.copy(alpha = 0.7f), Offset(2f * sc, 3.5f * sc), Size(20f * sc, 5f * sc), CornerRadius(2f * sc))
    drawRect(Color.Black.copy(alpha = 0.35f), Offset(10.5f * sc, 3.5f * sc), Size(3f * sc, 17.5f * sc))
}

/** Kapalı ada: solda ok / uygulama simgesi, sağda mesafe / kalan süre (ortada kamera deliği boşluğu kalır). */
@Composable
internal fun LiveCompact(c: IslandContent.Live, modifier: Modifier = Modifier) {
    Box(modifier) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveIcon(c, 22.dp, preferApp = false)
            Spacer(Modifier.weight(1f))
            Text(
                c.big, color = if (c.kind == LiveKind.Delivery || c.kind == LiveKind.Progress || c.goal) LIVE_GREEN else Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, softWrap = false,
            )
        }
    }
}

/** Açık ada: navigasyonda büyük ok + mesafe + talimat; siparişte uygulama, başlık, kalan süre ve aşama çubuğu. */
@Composable
internal fun LivePanel(c: IslandContent.Live, rb: RainbowStyle?) {
    if (c.kind == LiveKind.Score) { ScorePanel(c, rb); return }
    if (c.kind == LiveKind.Nav) {
        Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                LiveIcon(c, 56.dp, preferApp = false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (c.big.isNotBlank()) Text(c.big, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 26.sp, maxLines = 1,
                        modifier = Modifier.rainbowFill(rb))
                    Text(c.title, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(listOf(c.sub, c.text).firstOrNull { it.isNotBlank() }.orEmpty(), color = LIVE_GREEN, fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (c.appIcon != null) LiveIcon(c, 18.dp, preferApp = true)
            }
        }
    } else {
        Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                LiveIcon(c, 44.dp, preferApp = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(c.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
                    val second = listOf(c.text, c.sub).firstOrNull { it.isNotBlank() }.orEmpty()
                    if (second.isNotBlank()) Text(second, color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 16.sp)
                }
                if (c.big.isNotBlank()) {
                    Spacer(Modifier.width(10.dp))
                    Text(c.big, color = LIVE_GREEN, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1)
                }
            }
            Spacer(Modifier.height(10.dp))
            DeliveryTrack(c)
        }
    }
}

/** Sipariş aşaması: dört duraklı çubuk (aşama biliniyorsa) ya da düz ilerleme çubuğu. */
@Composable
private fun DeliveryTrack(c: IslandContent.Live) {
    val frac = when {
        c.stage >= 0 -> c.stage / 3f
        c.progress >= 0f -> c.progress
        else -> return
    }
    Canvas(Modifier.fillMaxWidth().height(10.dp)) {
        val h = 4.dp.toPx()
        val y = size.height / 2f
        drawRoundRect(Color.White.copy(alpha = 0.18f), Offset(0f, y - h / 2f), Size(size.width, h), CornerRadius(h / 2f))
        drawRoundRect(LIVE_GREEN, Offset(0f, y - h / 2f), Size((size.width * frac).coerceAtLeast(h), h), CornerRadius(h / 2f))
        if (c.stage >= 0) for (i in 0..3) {
            val x = (size.width - 10.dp.toPx()) * i / 3f + 5.dp.toPx()
            val done = i <= c.stage
            drawCircle(if (done) LIVE_GREEN else Color(0xFF3A3A3C), 5.dp.toPx(), Offset(x, y))
            if (done) drawCircle(Color.Black, 2.dp.toPx(), Offset(x, y))
        }
    }
    if (c.stage >= 0) {
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            LIVE_STAGES.forEachIndexed { i, s ->
                Text(s, color = if (i <= c.stage) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.35f), fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

/** Maç skoru kartı: iki takım, ortada büyük skor; altta dakika / devre arası / maç sonu ve canlı noktası. Golde skor yeşil. */
@Composable
private fun ScorePanel(c: IslandContent.Live, rb: RainbowStyle?) {
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Text(c.teamA, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End, lineHeight = 18.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(14.dp))
            Text(
                "${c.scoreA}  -  ${c.scoreB}", color = if (c.goal) LIVE_GREEN else Color.White,
                fontSize = 30.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                modifier = if (c.goal) Modifier else Modifier.rainbowFill(rb),
            )
            Spacer(Modifier.width(14.dp))
            Text(c.teamB, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start, lineHeight = 18.sp, modifier = Modifier.weight(1f))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.appIcon != null) { LiveIcon(c, 18.dp, preferApp = true); Spacer(Modifier.width(8.dp)) }
            val status = when {
                c.goal -> "GOL!"
                c.phase == 2 -> "Maç sonu"
                c.phase == 1 -> "Devre arası"
                c.minute.isNotBlank() -> c.minute
                else -> "Canlı"
            }
            Text(status, color = if (c.phase == 2) Color.White.copy(alpha = 0.6f) else LIVE_GREEN, fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.weight(1f))
            if (c.phase == 0) Box(Modifier.size(7.dp).clip(CircleShape).background(Color(0xFFFF453A)))
        }
    }
}
