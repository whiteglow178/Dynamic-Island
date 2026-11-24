package com.example.dynamicisland

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "Hakkında" sayfası içeriği. Ana Sayfa'daki "Hakkında" kartına basınca açılır (MainActivity.kt).
 *
 * Play Store'a yüklemeden önce aşağıdaki sabitleri kendi bilgilerinle güncelle.
 */
private const val DEVELOPER_NAME = "WhiteGlow"
private const val CONTACT_EMAIL = ""                      // örn. "destek@ornek.com" (boşsa satır gizlenir)
private const val PRIVACY_URL = ""                        // gizlilik politikası bağlantısı (boşsa satır gizlenir)
private const val SOURCE_URL = ""                         // GitHub bağlantısı: Play sürümünde boş bırak, GitHub sürümünde doldur

private fun Context.openLink(url: String) {
    runCatching {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun Context.sendMail(to: String) {
    runCatching {
        startActivity(
            Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to"))
                .putExtra(Intent.EXTRA_SUBJECT, "Omni Island geri bildirim")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

@Composable
fun AboutContent() {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val version = remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "-"
    }

    SectionCard("Omni Island", "Standartların çok ötesinde") {
        Text(
            "Omni Island, Android telefonunuzun üst kısmına " +
                "canlı ve akıcı bir \"ada\" ekler. Müzik, arama, şarj, bildirim, navigasyon ve daha fazlası " +
                "ada üzerinde şık animasyonlarla görünür; adayı kendi tarzınıza göre tasarlayabilirsiniz.",
            color = cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp,
        )
        AboutRow("Sürüm", version)
        AboutRow("Geliştirici", DEVELOPER_NAME)
    }

    SectionCard("Neler yapabilir?") {
        listOf(
            "Müzik, arama, sesli not, şarj ve bildirimleri adada gösterir",
            "Navigasyon, sipariş, maç ve indirme gibi canlı etkinlikleri takip eder",
            "Özel ada tasarımı, portal, şekil ve kamuflaj seçenekleri sunar",
            "Doğrulama kodlarını algılar, bildirimleri erteleme ve akıllı kopyalama araçları sağlar",
            "Şarj halkası, süre tahmini ve %80 uyarısı ile pil durumunu gösterir",
            "Şarkı sözleri ve kapak efektleriyle müziği daha canlı yapar",
        ).forEach { Bullet(it) }
    }

    SectionCard("İzinler neden isteniyor?", "Hepsi yalnızca özelliklerin çalışması için kullanılır") {
        Perm("Diğer uygulamaların üzerinde göster", "Adanın ekranın üstünde çizilebilmesi için gerekir.")
        Perm("Bildirim erişimi", "Müzik, bildirim ve canlı etkinlikleri adada göstermek için bildirimleri okur.")
        Perm("Erişilebilirlik hizmeti", "Adaya dokunma hareketleri, klavye ve tam ekran durumunu algılamak için kullanılır.")
        Perm("Bildirim gönderme", "Hizmetin çalıştığını ve önemli durumları size bildirmek için kullanılır.")
        Perm("Pil optimizasyonu muafiyeti", "Adanın arka planda sistem tarafından kapatılmaması için önerilir.")
        Text(
            "İzinleri istediğiniz zaman telefonunuzun ayarlarından kapatabilirsiniz; ilgili özellikler çalışmayı durdurur.",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }

    SectionCard("Gizlilik") {
        Text(
            "Bildirimleriniz, ekran içeriğiniz ve kişisel verileriniz sunucularımıza gönderilmez ve " +
                "bizimle paylaşılmaz; ada ile ilgili işlemler telefonunuzda gerçekleşir. Şarkı sözleri, " +
                "maç skorları ve güncelleme denetimi gibi bazı özellikler yalnızca gerekli arama bilgisini " +
                "ilgili hizmete (ör. LRCLIB, TheSportsDB, iTunes, GitHub) gönderir.",
            color = cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp,
        )
    }

    if (CONTACT_EMAIL.isNotBlank() || PRIVACY_URL.isNotBlank() || SOURCE_URL.isNotBlank()) {
        SectionCard("Bağlantılar ve destek") {
            if (CONTACT_EMAIL.isNotBlank()) LinkRow("Geri bildirim / destek", CONTACT_EMAIL) { ctx.sendMail(CONTACT_EMAIL) }
            if (PRIVACY_URL.isNotBlank()) LinkRow("Gizlilik politikası", "Tarayıcıda aç") { ctx.openLink(PRIVACY_URL) }
            if (SOURCE_URL.isNotBlank()) LinkRow("Proje sayfası", "GitHub") { ctx.openLink(SOURCE_URL) }
        }
    }

    Text(
        "© ${java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)} $DEVELOPER_NAME. Tüm hakları saklıdır.",
        color = cs.onSurfaceVariant.copy(alpha = 0.8f), fontSize = 11.sp,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun AboutRow(label: String, value: String) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = cs.onSurface, fontSize = 15.sp)
        Text(value, color = cs.onSurfaceVariant, fontSize = 15.sp)
    }
}

@Composable
private fun Bullet(text: String) {
    val cs = MaterialTheme.colorScheme
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", color = cs.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(text, color = cs.onSurface, fontSize = 14.sp, lineHeight = 20.sp)
    }
}

@Composable
private fun Perm(title: String, desc: String) {
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(desc, color = cs.onSurfaceVariant, fontSize = 12.5.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun LinkRow(label: String, value: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = cs.onSurface, fontSize = 15.sp)
        Text(value, color = cs.primary, fontSize = 15.sp)
    }
}
