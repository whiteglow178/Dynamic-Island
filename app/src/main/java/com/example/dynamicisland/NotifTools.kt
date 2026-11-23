package com.example.dynamicisland

import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.service.notification.StatusBarNotification
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.Locale

/*
 * BİLDİRİM ARAÇLARI (Ana Sayfa > Bildirim araçları). Hiçbiri ek izin istemez: zaten verilmiş olan bildirim erişimini kullanır.
 *
 * GİZLİLİK:
 *  - Doğrulama kodları HİÇBİR yerde saklanmaz; yalnızca kart açıkken bellekte durur, kopyalanınca panoya "hassas" olarak konur.
 *  - Silinen bildirimler ve sessiz bildirimler YALNIZCA bellekte (RAM) tutulur: diske yazılmaz, internete gönderilmez,
 *    24 saatten eskisi silinir, telefon / uygulama yeniden başlayınca tamamen kaybolur. Doğrulama kodu içerenler ve
 *    "gizli" işaretli bildirimler hiç tutulmaz. Kart üzerinden tek dokunuşla temizlenebilir.
 */

/** Dinleyicinin okuduğu ayarlar (servis yazar). */
object NotifFlags {
    @Volatile var otp = false
    @Volatile var history = false
    @Volatile var remind = false
    @Volatile var important = false
    @Volatile var names: List<String> = emptyList()
    @Volatile var digest = false
}

// ================================================================ 1) Doğrulama kodu
object OtpParse {
    // Anahtar kelime şart: yalnızca sayı içeren her bildirim (sipariş no, tutar) kod sanılmasın
    private val kw = Regex(
        "(kod|code|doğrulama|dogrulama|şifre|sifre|parola|otp|verification|verify|tek kullanımlık|tek kullanimlik|passcode|2fa|\\bpin\\b|sms onay)",
        RegexOption.IGNORE_CASE,
    )
    // 4–8 hane ya da "123 456" / "123-456"; önünde / arkasında para birimi, yüzde, saat, tarih olmayanlar
    private val cand = Regex(
        "(?<![\\d.,:/₺$€])(?:[A-Z]-)?(\\d{3}[ -]\\d{3}|\\d{4,8})(?![\\d.,:/%])(?!\\s?(?:tl|₺|try|usd|eur|gb|mb|dk|dakika|sn|saniye|min)\\b)",
        RegexOption.IGNORE_CASE,
    )

    /** Bildirimdeki doğrulama kodu (yoksa null). 6 haneli kod önceliklidir. */
    fun find(title: String, text: String): String? {
        val all = "$title\n$text"
        if (!kw.containsMatchIn(all)) return null
        val ms = cand.findAll(all).map { it.groupValues[1].replace(" ", "").replace("-", "") }.filter { it.length in 4..8 }.toList()
        if (ms.isEmpty()) return null
        return ms.firstOrNull { it.length == 6 } ?: ms.first()
    }

    fun of(sbn: StatusBarNotification): String? {
        val ex = sbn.notification?.extras ?: return null
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        return find(title, text)
    }

    /** Kodu panoya "hassas" olarak koyar (Android 13+ pano önizlemesinde görünmez). */
    fun copy(ctx: Context, code: String): Boolean = runCatching {
        val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
        val clip = ClipData.newPlainText("Doğrulama kodu", code)
        clip.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
        cm.setPrimaryClip(clip)
        true
    }.getOrDefault(false)
}

// ================================================================ 2) ve 5) Bellekteki kasa
/** Silinen ve sessize alınan bildirimler: yalnızca bellekte, en fazla 24 saat. */
object NotifVault {
    class Entry(val pkg: String, val title: String, val text: String, val time: Long, val intent: PendingIntent?)

    private val removed = ArrayDeque<Entry>()
    private val silent = ArrayDeque<Entry>()
    @Volatile var silentUnseen = 0
        private set
    /** Arayüz yeniden çizilsin diye her değişiklikte artar. */
    val version = mutableIntStateOf(0)

    private const val DAY = 24 * 3_600_000L

    @Synchronized fun addRemoved(e: Entry) {
        removed.addFirst(e)
        while (removed.size > 40) removed.removeLast()
        bump()
    }

    @Synchronized fun addSilent(e: Entry) {
        silent.addFirst(e)
        while (silent.size > 60) silent.removeLast()
        silentUnseen++
        bump()
    }

    @Synchronized fun removedList(): List<Entry> { prune(); return removed.toList() }
    @Synchronized fun silentList(): List<Entry> { prune(); return silent.toList() }
    @Synchronized fun clearRemoved() { removed.clear(); bump() }
    @Synchronized fun clearSilent() { silent.clear(); silentUnseen = 0; bump() }
    @Synchronized fun markSilentSeen() { silentUnseen = 0; bump() }

    /** Özet için: görülmemiş sessiz bildirimlerin uygulamaları (en çok 4 ad için paket listesi). */
    @Synchronized fun unseenPkgs(): List<String> = silent.take(silentUnseen).map { it.pkg }.distinct()

    private fun prune() {
        val cut = System.currentTimeMillis() - DAY
        while (removed.isNotEmpty() && removed.last().time < cut) removed.removeLast()
        while (silent.isNotEmpty() && silent.last().time < cut) silent.removeLast()
        silentUnseen = silentUnseen.coerceAtMost(silent.size)
    }

    private fun bump() { android.os.Handler(android.os.Looper.getMainLooper()).post { version.intValue++ } }

    /** Kasaya girmemesi gerekenler: doğrulama kodu içerenler ve "gizli" işaretli bildirimler. */
    fun sensitive(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return true
        if (n.visibility == Notification.VISIBILITY_SECRET) return true
        return OtpParse.of(sbn) != null
    }
}

// ================================================================ 4) Önemli kişiler
object ImportantMatch {
    private val tr = Locale("tr", "TR")

    fun parse(raw: String): List<String> = raw.split(',', '\n', ';').map { it.trim() }.filter { it.length >= 2 }

    /** Bildirim seçili kişilerden birinden mi? Başlık (gönderen / sohbet adı) ve son mesajın göndereni karşılaştırılır. */
    fun matches(names: List<String>, title: String, sbn: StatusBarNotification): Boolean {
        if (names.isEmpty()) return false
        val ex = sbn.notification?.extras
        val conv = ex?.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString().orEmpty()
        val sender = runCatching {
            val msgs = ex?.getParcelableArray(Notification.EXTRA_MESSAGES)
            (msgs?.lastOrNull() as? android.os.Bundle)?.getCharSequence("sender")?.toString()
        }.getOrNull().orEmpty()
        val hay = "$title|$conv|$sender".lowercase(tr)
        return names.any { hay.contains(it.lowercase(tr)) }
    }
}

// ================================================================ 3) Okunmamış mesaj hatırlatıcısı
/**
 * Sohbet bildirimi 30 dakika boyunca kaldırılmazsa (okunmazsa) bir kez hatırlatılır. Kişi başına (bildirim anahtarı) bir kez.
 * Okununca uygulama bildirimi kaldırır, hatırlatma da iptal olur. Yalnızca bellekte tutulur.
 */
class UnreadReminder(private val post: (pkg: String, title: String, intent: PendingIntent?, key: String) -> Unit) {
    private class Item(val pkg: String, var title: String, val since: Long, var intent: PendingIntent?, var done: Boolean = false)
    private val items = HashMap<String, Item>()
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var running = false
    private val waitMs = 30 * 60_000L

    private val tick = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            for ((k, it) in items) {
                if (!it.done && now - it.since >= waitMs) { it.done = true; post(it.pkg, it.title, it.intent, k) }
            }
            if (items.values.any { !it.done }) handler.postDelayed(this, 60_000L) else running = false
        }
    }

    fun onPosted(key: String, pkg: String, title: String, intent: PendingIntent?) {
        val cur = items[key]
        if (cur == null) items[key] = Item(pkg, title, System.currentTimeMillis(), intent)
        else { cur.title = title; cur.intent = intent }          // yeni mesaj: süre ilk okunmamıştan sayılır
        if (!running) { running = true; handler.postDelayed(tick, 60_000L) }
    }

    fun onRemoved(key: String) { items.remove(key) }

    fun clear() { items.clear(); handler.removeCallbacks(tick); running = false }
}

// ================================================================ Ana Sayfa kartı
@Composable
fun NotifToolsSections() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var m by remember { mutableStateOf<MoreSettings?>(null) }
    LaunchedEffect(Unit) { m = repo.flow.first().more }
    LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        delay(300)
        repo.update { s ->
            s.copy(more = s.more.copy(
                otpIsland = v.otpIsland, notifHistory = v.notifHistory, unreadRemind = v.unreadRemind,
                importantOnly = v.importantOnly, importantNames = v.importantNames, silentDigest = v.silentDigest,
            ))
        }
    }
    val cur = m ?: return
    val set: (MoreSettings) -> Unit = { m = it }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    @Suppress("UNUSED_VARIABLE") val ver = NotifVault.version.intValue

    SectionCard("Bildirim araçları", "Ek izin gerekmez. Kodlar hiç saklanmaz; listeler yalnızca bellekte, en fazla 24 saat tutulur") {
        SwitchRow("Doğrulama kodu", "SMS ya da bildirimle gelen doğrulama kodu adada büyük gösterilir; adaya dokununca kod kopyalanır. Kod hiçbir yere kaydedilmez, panoya hassas olarak konur, kart 90 saniyede kapanır",
            cur.otpIsland) { set(cur.copy(otpIsland = it)) }
        SwitchRow("Okunmamış mesaj hatırlatıcısı", "Bir sohbet bildirimi 30 dakika açılmadan kalırsa ada bir kez hatırlatır. Okuyunca (bildirim kalkınca) hatırlatma iptal olur",
            cur.unreadRemind) { set(cur.copy(unreadRemind = it)) }
        SwitchRow("Önemli kişiler", "Açıkken yalnızca aşağıya yazdığın kişilerin mesajları adada görünür; diğer sohbetler sessizce \"Sessiz bildirimler\" listesine gider (bildirim panelinde yine durur)",
            cur.importantOnly) { set(cur.copy(importantOnly = it)) }
        if (cur.importantOnly) {
            OutlinedTextField(
                value = cur.importantNames, onValueChange = { if (it.length <= 400) set(cur.copy(importantNames = it)) },
                label = { Text("Kişiler (virgülle ayır)") }, placeholder = { Text("Annem, Ayşe, İş Grubu") },
                minLines = 1, maxLines = 3, modifier = Modifier.fillMaxWidth(),
            )
            Text("Bildirimde görünen ad ya da grup adının bir parçası yeterli.", color = dim, fontSize = 12.sp)
        }
        SwitchRow("Sessiz bildirim özeti", "Düşük öncelikli bildirimler (kampanya, öneri, sessiz kanallar) adayı tek tek açmaz; saatte bir \"N sessiz bildirim\" özeti gösterilir",
            cur.silentDigest) { set(cur.copy(silentDigest = it)) }
        SwitchRow("Silinen bildirim geçmişi", "Kaydırıp sildiğin bildirimler 24 saat boyunca aşağıda listelenir; dokununca ilgili uygulamada açılır. Yalnızca bellekte tutulur, telefon yeniden başlayınca silinir",
            cur.notifHistory) { set(cur.copy(notifHistory = it)) }
    }

    if (cur.silentDigest || cur.importantOnly) {
        val list = NotifVault.silentList()
        LaunchedEffect(Unit) { NotifVault.markSilentSeen() }
        SectionCard("Sessiz bildirimler", if (list.isEmpty()) "Son 24 saatte sessize alınan bildirim yok" else "${list.size} bildirim · dokun, uygulamada aç") {
            VaultList(ctx, list)
            if (list.isNotEmpty()) IslandActionButton("Listeyi temizle", { NotifVault.clearSilent() }, Modifier.fillMaxWidth())
        }
    }
    if (cur.notifHistory) {
        val list = NotifVault.removedList()
        SectionCard("Silinen bildirimler", if (list.isEmpty()) "Son 24 saatte silinen bildirim yok" else "${list.size} bildirim · dokun, uygulamada aç") {
            VaultList(ctx, list)
            if (list.isNotEmpty()) IslandActionButton("Geçmişi temizle", { NotifVault.clearRemoved() }, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun VaultList(ctx: Context, list: List<NotifVault.Entry>) {
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    val pm = ctx.packageManager
    val fmt = remember { java.text.SimpleDateFormat("HH:mm", Locale("tr", "TR")) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (e in list.take(30)) {
            val app = remember(e.pkg) { runCatching { pm.getApplicationLabel(pm.getApplicationInfo(e.pkg, 0)).toString() }.getOrDefault(e.pkg) }
            Row(
                Modifier.fillMaxWidth().clickable { openEntry(ctx, e) }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("$app · ${e.title}", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (e.text.isNotBlank()) Text(e.text, color = dim, fontSize = 12.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Text(fmt.format(java.util.Date(e.time)), color = dim, fontSize = 12.sp)
            }
        }
    }
}

private fun openEntry(ctx: Context, e: NotifVault.Entry) {
    val pi = e.intent
    val ok = pi != null && runCatching { pi.send(); true }.getOrDefault(false)
    if (!ok) runCatching {
        ctx.packageManager.getLaunchIntentForPackage(e.pkg)?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
