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
                chargeEta = v.chargeEta, flipSilence = v.flipSilence, charge80 = v.charge80, charge80Night = v.charge80Night,
                fxPets = v.fxPets, fxPetCount = v.fxPetCount,
                petTouch = v.petTouch, petCompact = v.petCompact, petGrowth = v.petGrowth, petSocial = v.petSocial, petBall = v.petBall,
            ))
        }
    }
    FxEventsHomeCard()        // Ada olayları (Ayarlar'dakiyle aynı)
    val cur = m ?: return
    val set: (MoreSettings) -> Unit = { m = it }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard("Ada sakinleri", "Genişletilmiş müzik adasında yaşayan küçük hayvanlar") {
        SwitchRow("Ada sakinleri", "Genişletilmiş müzik adasında kedi, tavşan, civciv, penguen ve tilki yaşar. Kapağın, yazıların ve çubuğun üstünde yürür, zıplar; seçili olaya göre davranır: yağmurda kapağın altına sığınır ya da şemsiye açar, karda kardan adam yapar, güneşte gözlükle uzanır, sualtında fanusla yüzer, havai fişekte kulaklarını kapatır, ateşböceği kovalar, kum fırtınasında rüzgâraltına saklanır. Söze göre dans eder, sallanınca sersemler",
            cur.fxPets) { set(cur.copy(fxPets = it)) }
        if (cur.fxPets) {
            Text("Kaç hayvan olsun?", color = dim, fontSize = 13.sp)
            ChipPicker((1..FX_PET_KINDS).map { it.toString() }, cur.fxPetCount - 1) { set(cur.copy(fxPetCount = it + 1)) }
            SwitchRow("Dokunma", "Dokununca türüne göre tepki verir (kedi mırlar, tavşan zıplar, civciv cıvıldar, penguen karnı üstünde kayar, tilki kuyruğunu kovalar). Parmakla tutup fırlatabilirsin; takla atar, çarptığı öğeyi sarsar, sert düşerse sersemler",
                cur.petTouch) { set(cur.copy(petTouch = it)) }
            SwitchRow("Kapalı adada", "Ada kapalıyken hayvanlar kenarından tutunup sarkar ve sallanır, biri adanın arkasından arada kafasını çıkarır (ada alttaysa üstüne oturur). Bildirim gelince irkilirler",
                cur.petCompact) { set(cur.copy(petCompact = it)) }
            SwitchRow("Büyüme", "Müzik dinledikçe aksesuar açılır: 30 dk atkı, 2 sa şapka, 5 sa taç, 10 sa kanat. Gece 23.00'ten sonra uykulu olurlar; 6 saatten uzun görmezlerse adayı açınca sana koşarlar",
                cur.petGrowth) { set(cur.copy(petGrowth = it)) }
            if (cur.petGrowth) {
                val ctx2 = LocalContext.current
                LaunchedEffect(Unit) { PetLife.init(ctx2) }
                val mins = PetLife.minutes.toInt()
                val nx = PetLife.next()
                Text("Dinleme: ${mins / 60} sa ${mins % 60} dk" + (nx?.let { " · Sıradaki: ${it.first} (${it.second / 60} sa ${it.second % 60} dk)" } ?: " · Tüm aksesuarlar açıldı"),
                    color = dim, fontSize = 12.sp, lineHeight = 17.sp)
            }
            SwitchRow("Kendi aralarında", "Havada çarpışıp sersemlerler; biri kardan adamın havucunu çalıp kaçar, diğeri kovalar ve sonunda geri getirir; uyuyan arkadaşının yanına gidip kıvrılırlar",
                cur.petSocial) { set(cur.copy(petSocial = it)) }
            SwitchRow("Top oyunu", "Genişletilmiş adada bir top belirir: telefonu eğerek yuvarlarsın, parmakla da fırlatabilirsin. Yıldıza değince konfeti patlar, hayvanlar sevinir. Hayvanlar da topu kovalayıp iter, bazen yıldıza doğru",
                cur.petBall) { set(cur.copy(petBall = it)) }
        }
    }

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
            Text("\"Şarkıya göre olay\" için Ana Sayfa > Müzik'teki \"Şarkının havası\" da açık olmalı.", color = dim, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }

    SectionCard("Müzik ve kapak", "Şarkı değişirken kapağın davranışı") {
        SwitchRow("Dağılan kapak", "Şarkı değişince eski kapak toz gibi parçacıklara ayrılıp savrulur, yeni kapak parçacıklardan birleşerek oluşur (kapak çevirme animasyonunun yerine)",
            cur.coverDissolve) { set(cur.copy(coverDissolve = it)) }
    }

    SectionCard("Şarj ve pil", "Şarj halkasıyla birlikte çalışan eklemeler") {
        SwitchRow("Şarjda elektrik", "Şarja takınca halkaya kenarlardan elektrik arkları akar ve dolan ucun çevresinde kıvılcım saçar. Şarj halkası kapalı olsa da bu açıkken halka gösterilir",
            cur.chargeArcs) { set(cur.copy(chargeArcs = it)) }
        SwitchRow("Şarj süresi tahmini", "Şarjdayken tam dolmaya kalan süre gösterilir: tahmin ilk hesaplanınca, şarjdayken kilit açılınca ve şarj halkasında. Tam dolunca ada \"Tam doldu\" der. Sistemin kendi tahmini varsa o kullanılır, yoksa şarj hızı ölçülür (ilk birkaç dakika tahmin olmayabilir)",
            cur.chargeEta) { set(cur.copy(chargeEta = it)) }
        SwitchRow("%80 uyarısı", "Şarjdayken pil %80'e ulaşınca ada kısa bir titreşimle \"Şarjı çıkarabilirsin\" der. Pil ömrünü uzatmak için. Her şarjda bir kez uyarır; şarjdan çıkarıp yeniden takınca tekrar kurulur",
            cur.charge80) { set(cur.copy(charge80 = it)) }
        if (cur.charge80) {
            SwitchRow("Gece sessiz", "23.00 – 07.00 arası uyarı titreşimsiz ve sade gelir (gece şarjda bırakılan telefon uyandırmasın)",
                cur.charge80Night) { set(cur.copy(charge80Night = it)) }
        }
        SwitchRow("Pil azken kıvılcım", "Pil %15'in altına düşünce (şarjda değilken) boştaki adanın alt ve üst kenarında arada bir kısa elektrik çatırtısı ve kıvılcım çakar",
            cur.lowBattSparks) { set(cur.copy(lowBattSparks = it)) }
    }

    SectionCard("Ters çevirince sessiz", "Telefonu yüzüstü koy, Rahatsız Etmeyin açılsın") {
        SwitchRow("Ters çevirince sessiz", "Telefonu ekranı aşağı bakacak şekilde düz bir yere koyunca kısa bir çift titreşimle Rahatsız Etmeyin açılır; kaldırınca kapanır. Rahatsız Etmeyin zaten açıksa ya da arada elle değiştirirsen ona dokunulmaz. Bildirim erişimi açık olmalı",
            cur.flipSilence) { set(cur.copy(flipSilence = it)) }
    }

    SectionCard("Bildirim efektleri", "Bildirim silinirken oynayan efekt") {
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

/** Ada olayları ayarları: Ayarlar > Ada olayları ve Ana Sayfa > Ada olayları aynı denetimleri kullanır. */
@Composable
internal fun FxEventsControls(s: IslandSettings, set: (IslandSettings) -> Unit) {
    SubHeader("Olay")
    val fxSel = FX_WEATHER_OPTIONS.indexOfFirst { it.first == s.fxWeather }.coerceAtLeast(0)
    ChipPicker(FX_WEATHER_OPTIONS.map { it.second }, fxSel) { set(s.copy(fxWeather = FX_WEATHER_OPTIONS[it].first)) }
    val fxInfo = when (s.fxWeather) {
        FXW_SUN -> "Panelde yavaşça süzülen altın parıltılar ve köşeden yayılan sıcak ışık. Öğelere zarar vermez."
        FXW_METEOR -> "Meteorlar gökte tek bir noktadan yayılır. Büyük olanlar öğeye çarpınca kıvılcım saçar, öğeyi sarsar ve üstünde soğuyan bir kor izi bırakır."
        FXW_SEA -> "Kabarcıklar alttan yükselir, öğelerin altına takılıp birleşir ve kenardan kaçar. Balıklar öğelerin üstünden ya da altından dolaşır."
        FXW_FIREWORKS -> "Roketler yükselip farklı biçimlerde patlar (şakayık, söğüt, halka, çıtırtı). Düşen kıvılcımlar öğelerin üstüne konup söner."
        FXW_FIREFLY -> "Ateşböcekleri dolaşır, öğelerin üstüne konup kalkar. Yanıp sönmeleri komşularıyla zamanla eşzamanlanır."
        FXW_SAND -> "Rüzgâr hamlelerle eser. Kum öğelerin üstünde birikir, rüzgâr yönünde kayar ve kenardan dökülür."
        FXW_AURORA -> "Kutup ışığı perdeleri dalgalanır; renk yüksekliğe göre yeşilden mora döner. Öğelerin üst kenarı ışığı yansıtır."
        FXW_ELECTRIC -> "Öğeler arasında dallanan elektrik arkları atlar; çarptığı öğe parlar ve sarsılır. Kenarlarda küçük deşarjlar gezinir."
        FXW_BLIZZARD -> "Rüzgârlı, derinlikli kar fırtınası. Öğelerin üstünde rüzgâr yönüne kayan kar kümeleri oluşur."
        else -> null
    }
    if (fxInfo != null) Text(fxInfo, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    if (s.fxWeather != 0) {
        SliderRow("Yoğunluk", s.fxIntensity, 0.1f..1f, "%${(s.fxIntensity * 100).toInt()}") { set(s.copy(fxIntensity = it)) }
    }
    if (s.fxWeather == 1) {
        SectionDivider()
        SwitchRow("Şimşek", "Şimşeğin düştüğü yerdeki öğe (kapak, düğme, çubuk...) adadan kopup ekranın altına düşer",
            s.fxLightning) { set(s.copy(fxLightning = it)) }
        if (s.fxLightning) {
            SliderRow("Şimşek aralığı", s.fxLightningSec, 3f..30f, "${s.fxLightningSec.toInt()} sn") { set(s.copy(fxLightningSec = it)) }
        }
    }
    if (s.fxWeather == 2) {
        SliderRow("Donma hızı", s.fxFreezeSpeed, 0.3f..3f, "x${"%.1f".format(s.fxFreezeSpeed)}") { set(s.copy(fxFreezeSpeed = it)) }
        SliderRow("Buzu kırmak için dokunma", s.fxThawTaps.toFloat(), 2f..12f, "${s.fxThawTaps}") { set(s.copy(fxThawTaps = (it + 0.5f).toInt())) }
        Text("Çok kar yağan öğe yavaşça donar ve çalışmayı bırakır. Üstüne dokundukça buz çatlar, sonunda kırılır.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
    if (s.fxWeather == 3) {
        SliderRow("Erime hızı", s.fxMeltSpeed, 0.3f..3f, "x${"%.1f".format(s.fxMeltSpeed)}") { set(s.copy(fxMeltSpeed = it)) }
        Text("Asit damlaları öğenin üstünde kalıp onu eritir; eriyince altındaki öğeye geçer.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
    SectionDivider()
    SwitchRow("Adayı kırma", "Açıkken genişletilmiş adada hızlı dokunduğun yer kırılıp düşer; her şey kırılınca ada parçalanır. Uzun basma ve kaydırma normal çalışır",
        s.fxBreak) { set(s.copy(fxBreak = it)) }
    if (s.fxWeather != 0 || s.fxBreak) {
        SliderRow("Geri toplanma süresi", s.fxRestoreSec, 2f..20f, "${s.fxRestoreSec.toInt()} sn") { set(s.copy(fxRestoreSec = it)) }
    }
    Text("Önizlemede efektler yalnızca ada içinde görünür; adadan dışarı düşen parçalar gerçek adada çalışır. Efektler pili tüketir.",
        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
}

/** Ana Sayfa > Ada olayları: ayarlar ekranındaki "Ada olayları" ile aynı (yalnızca olay alanları kaydedilir). */
@Composable
internal fun FxEventsHomeCard() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var st by remember { mutableStateOf<IslandSettings?>(null) }
    LaunchedEffect(Unit) { st = repo.flow.first() }
    LaunchedEffect(st) {
        val v = st ?: return@LaunchedEffect
        delay(250)
        repo.update { s ->
            s.copy(
                fxWeather = v.fxWeather, fxIntensity = v.fxIntensity, fxLightning = v.fxLightning, fxLightningSec = v.fxLightningSec,
                fxFreezeSpeed = v.fxFreezeSpeed, fxThawTaps = v.fxThawTaps, fxMeltSpeed = v.fxMeltSpeed,
                fxBreak = v.fxBreak, fxRestoreSec = v.fxRestoreSec,
            )
        }
    }
    val cur = st ?: return
    SectionCard("Ada olayları", "Genişletilmiş müzik adasında hava durumu, portal sahneleri ve kırılma efektleri. Ayarlar > Ada olayları ile aynı") {
        FxEventsControls(cur) { st = it }
    }
}
