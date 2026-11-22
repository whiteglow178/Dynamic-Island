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
            if (c.kind == LiveKind.Nav) drawTurnArrow(LIVE_GREEN, sc) else drawBoxGlyph(LIVE_GREEN, sc)
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
                c.big, color = if (c.kind == LiveKind.Delivery) LIVE_GREEN else Color.White,
                fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, softWrap = false,
            )
        }
    }
}

/** Açık ada: navigasyonda büyük ok + mesafe + talimat; siparişte uygulama, başlık, kalan süre ve aşama çubuğu. */
@Composable
internal fun LivePanel(c: IslandContent.Live, rb: RainbowStyle?) {
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
