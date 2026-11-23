package com.example.dynamicisland

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar

/*
 * AKILLI BİLDİRİM: adadaki bildirimin altında küçük çipler.
 *  - Ertele: bildirim sistemde ertelenir (15 dk / 1 sa / akşam), süre dolunca bildirim panelinde yeniden belirir.
 *  - Akıllı kopyala: yazıdaki IBAN, kargo takip no, telefon, adres ve bağlantı bulunur; tek dokunuşla kopyalanır / açılır.
 *
 * İZİN / GİZLİLİK: ek izin yok (zaten verilmiş bildirim erişimi kullanılır). Yazı yalnızca cihazda, o an taranır;
 * hiçbir şey kaydedilmez ya da gönderilmez. Bağlantı çipinde alan adı yazar, böylece neyin açılacağı görülür.
 */

const val CHIP_DONE = 0
const val CHIP_SNOOZE = 1
const val CHIP_COPY = 2
const val CHIP_DIAL = 3
const val CHIP_MAP = 4
const val CHIP_LINK = 5

/** Çip: [value] ertelemede milisaniye, diğerlerinde kopyalanacak / açılacak yazı. [group] 1 = ertele grubu. */
data class NotifChip(val label: String, val act: Int, val value: String, val group: Int = 0)

/** Çip satırı için adanın ek yüksekliği (dp). */
const val NOTIF_CHIPS_DP = 38f

fun notifChipsExtraDp(c: IslandContent): Float =
    if (c is IslandContent.Notif && c.chips.isNotEmpty()) NOTIF_CHIPS_DP else 0f

/** Adadaki çipe dokunulunca servis işler. */
object NotifChipBus {
    @Volatile var onChip: ((IslandContent.Notif, NotifChip) -> Unit)? = null
}

object NotifSmart {
    private val ibanRx = Regex("""\b([A-Z]{2}\d{2}(?:[ ]?[A-Z0-9]{4}){3,7}(?:[ ]?[A-Z0-9]{1,3})?)\b""")
    private val trackKw = Regex("""(?i)(takip|gönderi|gonderi|kargo|tracking|barkod|shipment|sevkiyat)""")
    private val trackTok = Regex("""\b([A-Z0-9]{8,24})\b""")
    private val phoneRx = Regex("""(?<![\d+])((?:\+90[\s-]?|0)[2-5]\d{2}[\s-]?\d{3}[\s-]?\d{2}[\s-]?\d{2}|\+\d{1,3}[\s-]?\d{2,4}[\s-]?\d{3}[\s-]?\d{2,4}[\s-]?\d{0,4})(?!\d)""")
    private val addrKw = Regex("""(?i)\b(mah\.|mahallesi|mh\.|cad\.|caddesi|cd\.|sok\.|sokak|sokağı|sk\.|bulvarı|blv\.|apt\.|apartmanı)""")
    private val urlRx = Regex("""(?i)\b((?:https?://|www\.)[^\s<>"']{3,200})""")

    fun chips(title: String, text: String, snooze: Boolean, smart: Boolean): List<NotifChip> {
        val out = ArrayList<NotifChip>()
        if (smart) runCatching { out.addAll(smartChips("$title\n$text")) }
        if (snooze) out.addAll(snoozeChips())
        return out
    }

    private fun snoozeChips(): List<NotifChip> {
        val now = Calendar.getInstance()
        val h = now.get(Calendar.HOUR_OF_DAY)
        val target = (now.clone() as Calendar).apply {
            set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (h < 18) set(Calendar.HOUR_OF_DAY, 19) else { add(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 9) }
        }
        val late = (target.timeInMillis - now.timeInMillis).coerceAtLeast(60_000L)
        return listOf(
            NotifChip("15 dk", CHIP_SNOOZE, (15 * 60_000L).toString(), 1),
            NotifChip("1 sa", CHIP_SNOOZE, (60 * 60_000L).toString(), 1),
            NotifChip(if (h < 18) "Akşam" else "Yarın sabah", CHIP_SNOOZE, late.toString(), 1),
        )
    }

    private fun smartChips(all: String): List<NotifChip> {
        val out = ArrayList<NotifChip>()
        var rest = all
        // IBAN (sağlama toplamı doğrulanır: rastgele harf-rakam dizileri alınmaz)
        ibanRx.findAll(all.uppercase()).map { it.groupValues[1] }.firstOrNull { ibanOk(it) }?.let { iban ->
            val clean = iban.replace(" ", "")
            out.add(NotifChip("IBAN kopyala", CHIP_COPY, clean))
            rest = rest.replace(iban, " ", ignoreCase = true)
        }
        // Kargo takip no: "takip / kargo / gönderi" kelimesinden sonraki ilk uzun, rakamlı kod
        trackKw.find(rest)?.let { kw ->
            val after = rest.substring(kw.range.last + 1).take(48)
            trackTok.findAll(after.uppercase()).map { it.groupValues[1] }
                .firstOrNull { t -> t.count { it.isDigit() } >= 6 }
                ?.let { t -> out.add(NotifChip("Takip no kopyala", CHIP_COPY, t)); rest = rest.replace(t, " ", ignoreCase = true) }
        }
        // Telefon: arama ekranında numara hazır açılır (arama izni gerekmez, aramayı sen başlatırsın)
        phoneRx.find(rest)?.let { m ->
            val num = m.groupValues[1].trim()
            if (num.count { it.isDigit() } in 10..15) out.add(NotifChip("Ara", CHIP_DIAL, num))
        }
        // Adres: mahalle / cadde / sokak / no içeren satır haritada aranır
        rest.split('\n').firstOrNull { addrKw.containsMatchIn(it) && it.trim().length in 10..160 }?.let { line ->
            out.add(NotifChip("Haritada aç", CHIP_MAP, line.trim()))
        }
        // Bağlantı: çipte alan adı yazar
        urlRx.find(all)?.let { m ->
            val raw = m.groupValues[1].trimEnd('.', ',', ')', ';', '!', '?')
            val url = if (raw.startsWith("www.", true)) "https://$raw" else raw
            val host = runCatching { Uri.parse(url).host }.getOrNull()?.removePrefix("www.")
            if (!host.isNullOrBlank()) out.add(NotifChip("↗ $host", CHIP_LINK, url))
        }
        return out.take(4)
    }

    /** IBAN sağlama toplamı (mod 97). */
    private fun ibanOk(s: String): Boolean {
        val v = s.replace(" ", "")
        if (v.length !in 15..34) return false
        if (v.startsWith("TR") && v.length != 26) return false
        val r = v.substring(4) + v.substring(0, 4)
        var mod = 0
        for (ch in r) {
            val n = when (ch) { in '0'..'9' -> ch - '0'; in 'A'..'Z' -> ch - 'A' + 10; else -> return false }
            mod = if (n >= 10) (mod * 100 + n) % 97 else (mod * 10 + n) % 97
        }
        return mod == 1
    }

    fun copy(ctx: Context, text: String): Boolean = runCatching {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
        cm.setPrimaryClip(ClipData.newPlainText("Omni Island", text))
        true
    }.getOrDefault(false)

    /** Arama ekranı / harita / tarayıcı açılır. */
    fun open(ctx: Context, c: NotifChip): Boolean = runCatching {
        val i = when (c.act) {
            CHIP_DIAL -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + c.value.filter { it.isDigit() || it == '+' }))
            CHIP_MAP -> Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(c.value)))
            CHIP_LINK -> Intent(Intent.ACTION_VIEW, Uri.parse(c.value))
            else -> return false
        }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrDefault(false)
}

/** Adadaki bildirimin altındaki çip satırı. */
@Composable
internal fun NotifChipRow(c: IslandContent.Notif, modifier: Modifier = Modifier) {
    val fg = Pal.c("island_text")
    val dim = Pal.c("island_text_dim")
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var labeled = false
        for (chip in c.chips) {
            if (chip.group == 1 && !labeled) {
                labeled = true
                Text("Ertele", color = dim, fontSize = 11.5.sp, maxLines = 1, modifier = Modifier.padding(start = 2.dp))
            }
            val src = remember(chip) { MutableInteractionSource() }
            Box(
                Modifier.fillMaxHeight().clip(RoundedCornerShape(50))
                    .background(fg.copy(alpha = if (chip.act == CHIP_DONE) 0.24f else 0.13f))
                    .clickable(interactionSource = src, indication = null, enabled = chip.act != CHIP_DONE) {
                        NotifChipBus.onChip?.invoke(c, chip)
                    }
                    .padding(horizontal = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(chip.label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

// ================================================================ Bildirim testi
/**
 * Ana Sayfa > Bildirimler > Bildirim testi: özellikleri denemek için uygulamanın kendi gönderdiği örnek bildirimler.
 * Dinleyici [EXTRA] işaretli bildirimleri başka bir uygulamadan gelmiş gibi işler. Bildirim izni (Android 13+) gerekir.
 */
object NotifTest {
    const val EXTRA = "omni_test"
    private const val CH = "omni_test"
    private const val CH_LOW = "omni_test_low"
    private const val DELAY = 3_000L

    const val MESSAGE = 0
    const val CODE = 1
    const val SMART = 2
    const val PROGRESS = 3
    const val SCORE = 4
    const val SILENT = 5

    val LABELS = listOf("Mesaj", "Doğrulama kodu", "IBAN ve kargo", "İndirme", "Maç skoru", "Sessiz bildirim")

    private val main = Handler(Looper.getMainLooper())

    private fun channels(nm: NotificationManager) {
        if (nm.getNotificationChannel(CH) == null) {
            nm.createNotificationChannel(NotificationChannel(CH, "Bildirim testi", NotificationManager.IMPORTANCE_DEFAULT).apply {
                setSound(null, null); enableVibration(false)
            })
        }
        if (nm.getNotificationChannel(CH_LOW) == null) {
            nm.createNotificationChannel(NotificationChannel(CH_LOW, "Bildirim testi (sessiz)", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun base(ctx: Context, ch: String = CH): Notification.Builder {
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(ctx, ch)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addExtras(android.os.Bundle().apply { putBoolean(EXTRA, true) })
    }

    /** Seçilen örneği 3 sn sonra gönderir (ana ekrana dönmeye vakit kalsın). false = bildirim izni yok. */
    fun send(ctx: Context, which: Int): Boolean {
        val app = ctx.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        channels(nm)
        main.postDelayed({ runCatching { post(app, nm, which) } }, DELAY)
        return true
    }

    private fun post(ctx: Context, nm: NotificationManager, which: Int) {
        val id = 7700 + which
        when (which) {
            MESSAGE -> {
                val me = android.app.Person.Builder().setName("Sen").build()
                val ayse = android.app.Person.Builder().setName("Ayşe").build()
                val style = Notification.MessagingStyle(me)
                    .addMessage("Akşam 8'de buluşuyor muyuz?", System.currentTimeMillis(), ayse)
                nm.notify(id, base(ctx).setStyle(style).setCategory(Notification.CATEGORY_MESSAGE)
                    .setContentTitle("Ayşe").setContentText("Akşam 8'de buluşuyor muyuz?").build())
            }
            CODE -> nm.notify(id, base(ctx).setContentTitle("Banka")
                .setContentText("Doğrulama kodunuz: 482913. Bu kodu kimseyle paylaşmayın.").build())
            SMART -> {
                val t = "Kargonuz yola çıktı, takip no: 7284019355. Ödeme için IBAN: TR33 0006 1005 1978 6457 8413 26. " +
                    "Kurye: 0532 123 45 67. Ayrıntılar: www.example.com/siparis"
                nm.notify(id, base(ctx).setContentTitle("Örnek Mağaza").setContentText(t)
                    .setStyle(Notification.BigTextStyle().bigText(t)).build())
            }
            PROGRESS -> {
                // 8 adımda dolar, sonra "tamamlandı" olur ve kalkar
                for (i in 0..8) main.postDelayed({
                    runCatching {
                        val done = i == 8
                        nm.notify(id, base(ctx, CH_LOW).setContentTitle(if (done) "İndirme tamamlandı" else "ornek_dosya.zip indiriliyor")
                            .setContentText(if (done) "ornek_dosya.zip" else "${i * 12} MB / 96 MB")
                            .setOngoing(!done).setOnlyAlertOnce(true)
                            .setProgress(8, i, false).build())
                        if (done) main.postDelayed({ nm.cancel(id) }, 3_000L)
                    }
                }, i * 1_200L)
            }
            SCORE -> {
                fun score(a: Int, b: Int, txt: String) = runCatching {
                    nm.notify(id, base(ctx, CH_LOW).setContentTitle("Kartallar $a - $b Martılar").setContentText(txt)
                        .setOngoing(true).setOnlyAlertOnce(true).build())
                }
                score(0, 0, "23' İlk yarı")
                main.postDelayed({ score(1, 0, "31' Gol!") }, 5_000L)
                main.postDelayed({ score(1, 0, "Maç sonu") }, 12_000L)
                main.postDelayed({ nm.cancel(id) }, 16_000L)
            }
            SILENT -> nm.notify(id, base(ctx, CH_LOW).setCategory(Notification.CATEGORY_PROMO)
                .setContentTitle("Kampanya").setContentText("Bu hafta tüm ürünlerde %20 indirim").build())
        }
    }
}
