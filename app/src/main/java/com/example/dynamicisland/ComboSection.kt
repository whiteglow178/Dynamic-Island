package com.example.dynamicisland

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * Ana Sayfa > Birleşik özellikler: uygulamadaki mevcut özelliklerin birleşiminden doğan eklemeler.
 * Hepsi ayrı ayrı açılıp kapatılır; ayarlar doğrudan kaydedilir (yalnızca bu kartın alanları yazılır).
 */
@Composable
fun ComboSections() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var m by remember { mutableStateOf<MoreSettings?>(null) }
    LaunchedEffect(Unit) { m = repo.flow.first().more }
    LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        delay(250)
        repo.update { s ->
            s.copy(more = s.more.copy(
                fxTilt = v.fxTilt, moodExtended = v.moodExtended, moodLyrics = v.moodLyrics, coverDissolve = v.coverDissolve,
                calendarFx = v.calendarFx, chargeArcs = v.chargeArcs, lowBattSparks = v.lowBattSparks, notifShatter = v.notifShatter,
                progressIsland = v.progressIsland, scoreIsland = v.scoreIsland,
                chargeEta = v.chargeEta, flipSilence = v.flipSilence,
            ))
        }
    }
    val cur = m ?: return
    val set: (MoreSettings) -> Unit = { m = it }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard("Ada olayları ile", "Ayarlar > Ada olayları'nda seçilen efektle birlikte çalışır") {
        SwitchRow("Eğilen dünya", "Telefonu eğince kar ve kum yığınları o yöne kayıp dökülür, kabarcıklar eğik yükselir, düşen parçacıklar yana savrulur. Telefonu sallayınca birikenler silkelenir, donmuş öğelerin buzu çatlar",
            cur.fxTilt) { set(cur.copy(fxTilt = it)) }
        SwitchRow("Şarkıya göre olay", "Şarkının havası açıkken kapak rengi yeni olayları da seçebilir: koyu mavi sualtı, mor kutup ışığı, sıcak ve parlak havai fişek, gece mavisi meteor, kum rengi kum fırtınası",
            cur.moodExtended) { set(cur.copy(moodExtended = it)) }
        SwitchRow("Söze tepki", "Senkronlu şarkı sözü varsa olay her yeni satırda kısa bir tepki verir; nakarat satırlarında (şarkıda tekrar eden) daha büyük bir patlama olur",
            cur.moodLyrics) { set(cur.copy(moodLyrics = it)) }
        SwitchRow("Takvim olayları", "Özel gün ve saatlerde olay kendiliğinden açılır: yılbaşı gecesi ve resmi bayram akşamlarında havai fişek, dolunay gecelerinde kutup ışığı, gece yarısından sonra ateşböceği",
            cur.calendarFx) { set(cur.copy(calendarFx = it)) }
        if (!cur.musicMood && (cur.moodExtended)) {
            Text("\"Şarkıya göre olay\" için Ana Sayfa'daki \"Şarkının havası\" da açık olmalı.", color = dim, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }

    SectionCard("Birleşik efektler", "Mevcut özelliklerin birbirine bağlandığı efektler") {
        SwitchRow("Dağılan kapak", "Şarkı değişince eski kapak toz gibi parçacıklara ayrılıp savrulur, yeni kapak parçacıklardan birleşerek oluşur (kapak çevirme animasyonunun yerine)",
            cur.coverDissolve) { set(cur.copy(coverDissolve = it)) }
        SwitchRow("Şarjda elektrik", "Şarja takınca halkaya kenarlardan elektrik arkları akar ve dolan ucun çevresinde kıvılcım saçar. Şarj halkası kapalı olsa da bu açıkken halka gösterilir",
            cur.chargeArcs) { set(cur.copy(chargeArcs = it)) }
        SwitchRow("Pil azken kıvılcım", "Pil %15'in altına düşünce (şarjda değilken) boştaki adanın alt ve üst kenarında arada bir kısa elektrik çatırtısı ve kıvılcım çakar",
            cur.lowBattSparks) { set(cur.copy(lowBattSparks = it)) }
        SwitchRow("Şarj süresi tahmini", "Şarjdayken tam dolmaya kalan süre gösterilir: tahmin ilk hesaplanınca, şarjdayken kilit açılınca ve şarj halkasında. Tam dolunca ada \"Tam doldu\" der. Sistemin kendi tahmini varsa o kullanılır, yoksa şarj hızı ölçülür (ilk birkaç dakika tahmin olmayabilir)",
            cur.chargeEta) { set(cur.copy(chargeEta = it)) }
        SwitchRow("Ters çevirince sessiz", "Telefonu ekranı aşağı bakacak şekilde düz bir yere koyunca kısa bir çift titreşimle Rahatsız Etmeyin açılır; kaldırınca kapanır. Rahatsız Etmeyin zaten açıksa ya da arada elle değiştirirsen ona dokunulmaz. Bildirim erişimi açık olmalı",
            cur.flipSilence) { set(cur.copy(flipSilence = it)) }
        SwitchRow("Silinen bildirim dökülsün", "Bildirimi silince yazısı harf harf parçalanıp girdap gibi adaya çekilir. Ada olaylarında kar ya da tipi seçiliyse harfler kar tanesine, kum fırtınasıysa kuma, havai fişek ya da meteorsa kora dönüşür",
            cur.notifShatter) { set(cur.copy(notifShatter = it)) }
    }

    SectionCard("Canlı etkinlik: yeni türler", "Bildirimlerden ayrıştırılan, adada kalan canlı kartlar") {
        SwitchRow("İndirme ve yükleme", "İlerleme çubuklu bildirimler (indirme, yükleme, yedekleme, dosya aktarımı) adada yüzdeyle canlı ilerleme olur; bitince kısa süre \"Tamamlandı\" görünür",
            cur.progressIsland) { set(cur.copy(progressIsland = it)) }
        SwitchRow("Maç skoru", "Spor uygulamalarının (Maçkolik, Sofascore, FotMob, Flashscore, beIN, ESPN...) canlı skor bildirimleri adada skor kartı olur. Gol olunca ada kısa süre açılır, maç bitince kart kapanır",
            cur.scoreIsland) { set(cur.copy(scoreIsland = it)) }
        Text("Navigasyon önceliklidir; aynı anda birden çok canlı etkinlik varsa sıra: navigasyon, maç, sipariş, indirme.",
            color = dim, fontSize = 12.sp, lineHeight = 17.sp)
    }
}
