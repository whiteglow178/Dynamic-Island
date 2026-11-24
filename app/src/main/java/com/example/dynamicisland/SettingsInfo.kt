package com.example.dynamicisland

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// ================================================================ ⓘ düğmesi
/** Ayarlarda arama çubuğunun yanındaki bilgi düğmesi: daire içinde "i". */
@Composable
internal fun InfoButton(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(cs.surfaceVariant)
            .clickable(role = Role.Button, onClickLabel = "Bilgi") { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        val ic = cs.onSurfaceVariant
        Canvas(Modifier.size(20.dp)) {
            val w = size.width
            val sw = w * 0.09f
            drawCircle(ic, radius = w / 2f - sw / 2f, style = Stroke(sw))
            drawCircle(ic, radius = w * 0.065f, center = Offset(w * 0.5f, w * 0.29f))
            drawLine(ic, Offset(w * 0.5f, w * 0.44f), Offset(w * 0.5f, w * 0.74f), sw * 1.15f, StrokeCap.Round)
        }
    }
}

// ================================================================ Bilgi sayfası
/** Elle yazılmış rehber: temel kullanım, izinler, gizlilik. Ayar listesinin üstünde görünür. */
private val GUIDE: List<InfoSection> = listOf(
    InfoSection("Başlarken", "Temel hareketler", "Adayla nasıl etkileşilir", listOf(
        InfoItem("Dokun", "Olay varken (müzik, bildirim, zamanlayıcı...) ada genişler; tekrar dokununca küçülür. Ada boşken dokunma, seçtiğin panele (favori uygulamalar, çeviri, zamanlayıcı kurma...) açılır."),
        InfoItem("Basılı tut", "Ayara göre: bildirimde uygulamayı açar ya da küçültür, müzikte müzik uygulamasını açar, gizli bildirimde içeriği gösterir. \"Basılı tutarak aç\" açıksa ada basılı tutunca yavaşça büyür."),
        InfoItem("Kaydır", "Aşağı / yukarı kaydırma ayara göre adayı açar / kapatır. Müzikte sağa / sola kaydırma şarkı değiştirir, bildirimde yana kaydırma bildirimi yan baloncuğa gönderir."),
        InfoItem("Yan baloncuklar", "Aynı anda birden fazla olay varsa eskileri adanın yanında küçük baloncuklara ayrılır. Baloncuğa dokununca o olay ortaya gelir."),
        InfoItem("Canlı önizleme", "Ayarlar ekranının üstündeki önizleme, yaptığın değişikliği hemen gösterir. Göz düğmesiyle gizlenebilir; üç çizgi tüm ayar sayfalarını listeler, arama çubuğu başlık ve açıklamalarda arar."),
    )),
    InfoSection("Başlarken", "İzinler ne için?", "Uygulamanın istediği izinler ve nedenleri", listOf(
        InfoItem("Erişilebilirlik", "Adayı durum çubuğunun üstünde çizmek, ön plandaki uygulamayı bilmek (uygulamaya özel gizleme, müzikten çıkış animasyonu) ve durum çubuğu simgelerinin yerini bulmak için. Ekrandaki yazılar okunup saklanmaz."),
        InfoItem("Bildirim erişimi", "Bildirimleri, müziği, aramayı, zamanlayıcıyı, ekran kaydını ve canlı etkinlikleri (navigasyon, sipariş, skor, doğrulama kodu) adada göstermek için. Bildirim içerikleri yalnızca bellekte tutulur; \"Silinen bildirim geçmişi\" açıksa bile telefon yeniden başlayınca silinir."),
        InfoItem("Diğer uygulamaların üstünde gösterme", "Adanın penceresi için."),
        InfoItem("İnternet", "Yalnızca isteğe bağlı özellikler için: şarkı sözleri (şarkı adı ve sanatçı gönderilir), hava durumu gibi ada olayları. Gizlilik özellikleri internet kullanmaz."),
        InfoItem("Sensörler", "Bilekle gizle, panik sallaması, masada gizle, fiziksel ada gibi özellikler jiroskop / ivmeölçer kullanır; yalnızca ekran açıkken ve ayar açıkken dinlenir, hiçbir veri saklanmaz."),
    )),
    InfoSection("Başlarken", "Gizlilik özeti", "Bildirimler › Gizlilik", listOf(
        InfoItem("Gizleme ne zaman açılır?", "Gizli bildirim modu (her zaman), bilek hareketi, panik sallaması, ekran kaydı / yansıtma, telefon masadayken, kilit ekranında, belirlediğin saatlerde ya da odak zamanlayıcısı sırasında. Hızlı ayarlar kutucuğu gizli modu tek dokunuşla açar / kapatır."),
        InfoItem("Ne gizlenir?", "Seçili uygulamaların (ya da hepsinin) bildirimleri; istersen müzik de. Gizlenen bildirim uygulama adı + \"Yeni bildirim\", bulanık yazı ya da yem metin olarak görünür."),
        InfoItem("Her zaman gizlenenler", "Doğrulama kodları, hassas kelime geçen bildirimler ve seçtiğin kişilerin mesajları gizleme kapalıyken de gizlenir."),
        InfoItem("Gizli içeriği görmek", "Seçtiğin hareketle (basılı tut, sağa-sola çiz ya da iki parmak) içerik görünür, parmak kalkınca yeniden gizlenir. Dokununca uygulama her zamanki gibi açılır, kod doğru kopyalanır."),
        InfoItem("Kayıt tutulmaz", "Gizlilik özellikleri hiçbir şey kaydetmez ve göndermez; yalnızca ekranda gösterilen kopya değiştirilir. Tek istisna, açarsan \"Kamera / mikrofon geçmişi\": yalnızca bellekte tutulur, uygulama kapanınca silinir."),
    )),
    InfoSection("Başlarken", "Şarj ve pil uyarıları", "Ana Sayfa › Birleşik efektler › Şarj ve pil", listOf(
        InfoItem("%100 uyarısı", "Şarj takılıyken pil %100 olunca ada \"Pil doldu · Şarjı çıkar\" der ve kısa titrer. Her şarjda bir kez gösterilir. Açıkken \"Tam doldu\" hapı yerine bu uyarı çıkar."),
        InfoItem("Çok sıcak uyarısı", "Pil sıcaklığı seçtiğin eşiği (38-50 °C, varsayılan 42) geçince ada \"Çok sıcak\" der ve titrer. Sıcaklık eşiğin 3 °C altına inene kadar tekrar uyarmaz. İki uyarı birbirinden bağımsız açılıp kapanır."),
        InfoItem("Şarj hızı (watt)", "\"Şarj süresi tahmini\" açıkken süre bilgisinin yanında anlık şarj gücü (örn. 18 W) yazar. Bazı telefonlar akım bilgisini vermez; o zaman watt görünmez."),
        InfoItem("Şarj süresi ve %80 uyarısı", "Daha önce eklenmişti: şarj tamamlanma süresi tahmini ve %80 uyarısı aynı bölümde durur."),
    )),
    InfoSection("Başlarken", "Güvenlik ve rozet", "Ayarlar › Bildirimler / Kamera-mikrofon", listOf(
        InfoItem("Yeni uygulama uyarısı", "Yeni bir uygulama kurulunca adada adı görünür; bilmediğin bir kurulumu fark edersin. Yalnızca kurulum algılanır, güncelleme ve sonradan istenen izinler algılanmaz."),
        InfoItem("Kamera / mikrofon geçmişi", "Kamera ya da mikrofon ne zaman ve ne kadar süre kullanıldı listeler. Uygulama adı bir tahmindir (kullanım başladığında ön plandaki uygulama). Android bu bilgiyi vermez. Geçmiş temizlenebilir."),
        InfoItem("Okunmamış bildirim sayacı", "Boştaki adanın sağında bekleyen bildirim sayısı kırmızı rozetle görünür (9'dan fazlası \"9+\"). Rozet varken ağ hızının yükleme kısmı gizlenir."),
        InfoItem("Panoyu okuyan uygulamalar", "Android panoya erişimi bildirmediği için bu uyarı yapılamaz. Kısayol şeridi ise \"Ada çekmecesi\" olarak zaten vardır."),
    )),
)

/** Tam ekran bilgi sayfası: rehber + bütün ayarların ayrıntılı açıklamaları (aranabilir, bölümler açılır / kapanır). */
@Composable
internal fun SettingsInfoDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val cs = MaterialTheme.colorScheme
        var query by remember { mutableStateOf("") }
        val open = remember { mutableStateListOf<String>() }
        val tr = remember { java.util.Locale("tr", "TR") }
        val all = remember { GUIDE + SETTINGS_INFO }
        val words = query.trim().lowercase(tr).split(' ').filter { it.isNotEmpty() }
        // Aramada: bölüm adı eşleşirse bölümün tamamı, yoksa yalnızca eşleşen satırlar
        val shown = remember(query) {
            if (words.isEmpty()) all else all.mapNotNull { s ->
                val head = "${s.group} ${s.title} ${s.subtitle}".lowercase(tr)
                if (words.all { head.contains(it) }) s
                else {
                    val its = s.items.filter { i -> !i.header && words.all { w -> "${i.title} ${i.desc}".lowercase(tr).contains(w) } }
                    if (its.isEmpty()) null else InfoSection(s.group, s.title, s.subtitle, its)
                }
            }
        }
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.94f).padding(horizontal = 10.dp)
                .clip(RoundedCornerShape(22.dp)).background(cs.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Bilgi", color = cs.onSurface, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("Bütün ayarlar ve özellikler", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
                Text("Kapat", color = cs.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { onDismiss() }
                        .padding(horizontal = 8.dp, vertical = 6.dp))
            }
            SearchField(query, { query = it }, hint = "Özellik ara")
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text("Sonuç yok", color = cs.onSurfaceVariant, fontSize = 14.sp)
                }
            } else LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var lastGroup = ""
                val rows = shown.map { s -> val g = if (s.group != lastGroup) s.group else ""; lastGroup = s.group; g to s }
                items(rows) { (g, s) ->
                    val key = s.group + "/" + s.title + "/" + s.subtitle
                    // Arama varken bölümler açık gelir
                    val isOpen = words.isNotEmpty() || key in open
                    Column {
                        if (g.isNotEmpty()) {
                            Text(g.uppercase(tr), color = cs.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp))
                        }
                        Column(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surfaceVariant.copy(alpha = 0.55f))
                                .clickable(role = Role.Button) { if (key in open) open.remove(key) else open.add(key) }
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(s.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                    if (s.subtitle.isNotBlank()) Text(s.subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
                                }
                                if (s.items.isNotEmpty()) Text(if (isOpen) "−" else "+", color = cs.onSurfaceVariant, fontSize = 20.sp,
                                    modifier = Modifier.padding(start = 8.dp))
                            }
                            if (isOpen) s.items.forEach { it0 ->
                                if (it0.header) {
                                    Text(it0.title, color = cs.primary, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(top = 6.dp))
                                } else Column(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                                    Text("• " + it0.title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                    if (it0.desc.isNotBlank()) Text(it0.desc, color = cs.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp,
                                        modifier = Modifier.padding(start = 10.dp))
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

