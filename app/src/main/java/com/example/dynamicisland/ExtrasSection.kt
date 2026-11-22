package com.example.dynamicisland

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Ana Sayfa kartları: Portal ada, Başparmak modu, Kamuflaj ve Zaman kapsülü.
 * Ayarlar doğrudan kaydedilir; yalnızca bu kartların alanları yazılır (diğer ayarlara dokunulmaz).
 */
@Composable
fun ExtrasSections() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var m by remember { mutableStateOf<MoreSettings?>(null) }
    LaunchedEffect(Unit) { m = repo.flow.first().more }
    LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        delay(250)
        repo.update { s ->
            s.copy(more = s.more.copy(
                portalEnabled = v.portalEnabled, portalScene = v.portalScene, portalDepth = v.portalDepth,
                thumbEnabled = v.thumbEnabled, camoEnabled = v.camoEnabled, camoSkipPkgs = v.camoSkipPkgs,
                contrastEnabled = v.contrastEnabled,
            ))
        }
    }
    val cur = m ?: return
    val set: (MoreSettings) -> Unit = { m = it }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    // ---------------------------------------------------------------- Portal ada
    SectionCard("Portal ada", "Adanın içinde başka bir dünya: telefonu eğdikçe derinlikli sahne kayar") {
        Box(
            Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF101114)),
            contentAlignment = Alignment.TopCenter,
        ) {
            val shape = RoundedCornerShape(50)
            PortalScene(cur.portalScene, cur.portalDepth, Modifier.padding(top = 14.dp).size(width = 170.dp, height = 46.dp).clip(shape))
        }
        Text("Önizlemeyi görmek için telefonu sağa sola, öne arkaya eğ." +
            if (cur.portalScene == 3) " Gökyüzü saate göre değişir: sabah gün doğar, öğlen mavi gök, akşam gün batımı, gece yıldızlar ve gerçek evresinde ay. İnternet ve konum kullanılmaz." else "",
            color = dim, fontSize = 12.sp, lineHeight = 17.sp)
        SwitchRow("Portal ada", "Boştaki adanın içi pencere gibi olur; arkasında uzak ve yakın katmanlar farklı hızda kayar. Ekran kapalıyken sensör dinlenmez. Açıkken özel tasarım ve kamuflaj boştaki adada görünmez",
            cur.portalEnabled) { set(cur.copy(portalEnabled = it, camoEnabled = if (it) false else cur.camoEnabled, contrastEnabled = if (it) false else cur.contrastEnabled)) }
        SceneChips(PORTAL_NAMES, cur.portalScene) { set(cur.copy(portalScene = it)) }
        SliderRow("Derinlik", cur.portalDepth, 0.2f..2f, "%.1fx".format(cur.portalDepth)) { set(cur.copy(portalDepth = it)) }
    }

    // ---------------------------------------------------------------- Başparmak modu
    SectionCard("Başparmak modu", "Adayı başparmağının altına indir") {
        LaunchedEffect(Unit) { ThumbStore.load(ctx) }
        val down by ThumbStore.active.collectAsState()
        SwitchRow("Başparmak modu", "Boştaki adayı aşağı kaydırınca ada kameranın yanından iner ve ekranın altında yüzen küçük bir ada olur. Sürükleyerek yerini değiştir, dokununca adanın normal işini yapar (favoriler, fotoğraf, müzik paneli), iki kez dokununca kameranın yanına geri döner. Bildirim, arama gibi olaylar yine kameranın yanında açılır; bitince ada aşağı geri iner",
            cur.thumbEnabled) { on ->
            set(cur.copy(thumbEnabled = on))
            if (!on) ThumbStore.set(ctx, false)
        }
        if (cur.thumbEnabled) {
            IslandActionButton(if (down) "Adayı kameranın yanına geri gönder" else "Adayı şimdi aşağı indir", { ThumbStore.set(ctx, !down) }, tonal = down)
        }
    }

    // ---------------------------------------------------------------- Kamuflaj
    SectionCard("Kamuflaj", "Ada çevresinin rengini alıp gözden kaybolur") {
        SwitchRow("Kamuflaj", "Boştaki ada, hemen yanındaki ekran renginin aynısını alır (açık temalı uygulamada beyaz, koyu temada siyah...) ve neredeyse görünmez olur. Bildirim, müzik gibi bir olay gelince bukalemun gibi renk değiştirerek ortaya çıkar. Yalnızca adanın iki yanındaki birkaç nokta okunur; hiçbir görüntü kaydedilmez",
            cur.camoEnabled) { set(cur.copy(camoEnabled = it, contrastEnabled = if (it) false else cur.contrastEnabled, portalEnabled = if (it) false else cur.portalEnabled)) }
        SwitchRow("Kontrast ada", "Kamuflajın tersi: boştaki ada çevresine karışmak yerine hep zıt rengi alır. Açık temalı ekranda koyu, koyu ekranda parlak olur; ada her zaman göze çarpar. Renk aynı yöntemle okunur, aşağıdaki uygulama listesi bunun için de geçerli",
            cur.contrastEnabled) { set(cur.copy(contrastEnabled = it, camoEnabled = if (it) false else cur.camoEnabled, portalEnabled = if (it) false else cur.portalEnabled)) }
        if (cur.camoEnabled || cur.contrastEnabled) {
            SubHeader("Kamuflajın duracağı uygulamalar")
            Text("Bu uygulamalar açıkken ekran görüntüsü hiç alınmaz; ada normal rengine döner. Bankacılık, mesajlaşma, şifre yöneticisi gibi uygulamaları ekleyebilirsin.",
                color = dim, fontSize = 12.sp, lineHeight = 17.sp)
            HiddenAppsPicker(cur.camoSkipList(), title = "Kamuflajın duracağı uygulamalar") { list -> set(cur.copy(camoSkipPkgs = list.joinToString(","))) }
        }
        Text(
            "Android 11 ve üstü gerekir. Güncellemeden sonra ilk kez açıyorsan Ayarlar > Erişilebilirlik'ten Omni Island'ı bir kez kapatıp aç (ekran rengini okuma yeteneği böyle etkinleşir)." +
                if (cur.camoEnabled && CamoState.unsupported) "\n⚠ Ekran rengi şu an okunamıyor: erişilebilirliği kapatıp açmayı dene." else "",
            color = dim, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }

    // ---------------------------------------------------------------- Zaman kapsülü
    TimeCapsuleSection()

    // ---------------------------------------------------------------- Mevcut özelliklerden türetilenler
    RemixSections()
    CreativeSections()
    IosSections()
}

/** Bildirim çevirisi, şarkının havası, ada çekmecesi, çöp kutusu ada, uygulama kilidi. */
@Composable
private fun RemixSections() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var m by remember { mutableStateOf<MoreSettings?>(null) }
    LaunchedEffect(Unit) { m = repo.flow.first().more }
    LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        delay(250)
        repo.update { s ->
            s.copy(more = s.more.copy(
                notifTranslate = v.notifTranslate, musicMood = v.musicMood,
                drawerEnabled = v.drawerEnabled, drawerTrigger = v.drawerTrigger,
                trashIsland = v.trashIsland, appLockEnabled = v.appLockEnabled, appLockPkgs = v.appLockPkgs,
            ))
        }
    }
    val cur = m ?: return
    val set: (MoreSettings) -> Unit = { m = it }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard("Bildirim çevirisi", "Yabancı dildeki bildirim adada Türkçe görünür") {
        SwitchRow("Bildirimleri çevir", "Gelen bildirimin dili telefonda tanınır; Türkçe değilse telefonda çevrilir ve adada (ya da cam bannerda) çevrilmiş haliyle, küçük \"İngilizce · çevrildi\" etiketiyle görünür. Metin hiçbir sunucuya gönderilmez",
            cur.notifTranslate) { set(cur.copy(notifTranslate = it)) }
        Text("Bir dilden ilk bildirim geldiğinde o dilin çeviri paketi (yaklaşık 30 MB) bir kez indirilir; o bildirim orijinal haliyle görünür, sonrakiler çevrilir. Kişi adı gibi kısa başlıklar çevrilmez.",
            color = dim, fontSize = 12.sp, lineHeight = 17.sp)
    }

    SectionCard("Şarkının havası", "Müzik adasındaki hava efektini şarkının kapağı seçsin") {
        SwitchRow("Şarkının havası", "Açık müzik adasındaki hava efekti ayardan değil kapağın renginden gelir: mavi, mor, gri ya da çok koyu kapakta yağmur (çok koyuysa şimşekli), beyazımsı kapakta kar, canlı yeşilde asit yağmuru, sıcak renkli (kırmızı, turuncu, sarı, pembe) kapakta güneş ışığı ve altın parıltılar. Şarkı değişince hava da değişir. Efekt yalnızca ada tam açıkken (müzik paneli açıkken) görünür",
            cur.musicMood) { set(cur.copy(musicMood = it)) }
    }

    SectionCard("Ada çekmecesi", "Favori paneli gibi açılır, uygulama yerine hızlı düğmeler") {
        SwitchRow("Ada çekmecesi", "Boştaki adadan fener, ekran görüntüsü, kilitle, kamera, zil / titreşim, ses, internet ve hızlı ayarlar düğmelerinin olduğu bir panel açılır. Ek izin gerekmez; ekran görüntüsü, kilitle ve hızlı ayarlar erişilebilirlik servisiyle çalışır",
            cur.drawerEnabled) { set(cur.copy(drawerEnabled = it)) }
        if (cur.drawerEnabled) {
            SegmentedTabs(listOf("Dokununca", "Basılı tutunca"), cur.drawerTrigger) { set(cur.copy(drawerTrigger = it)) }
            Text("Aynı dokunuşa başka bir özellik de bağlıysa sıra: özel tasarım fotoğrafı, çekmece, çeviri, favoriler.",
                color = dim, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }

    SectionCard("Çöp kutusu ada", "Silinen bildirim adaya uçar, ada yutar") {
        SwitchRow("Çöp kutusu ada", "Bir bildirimi kaydırarak silince (ya da tümünü temizleyince) uygulamanın simgesi dönerek adaya uçar, ada onu yutkunur. Adadaki bildirimi kaydırıp silince de çalışır",
            cur.trashIsland) { set(cur.copy(trashIsland = it)) }
    }

    SectionCard("Uygulama açılış animasyonu", "Seçtiğin uygulamalar açılınca adada Face ID animasyonu oynar") {
        SwitchRow("Face ID animasyonu", "Seçili bir uygulama açılınca adanın yerinde Face ID taraması bir kez oynar ve onay işaretiyle biter. Yalnızca görsel bir efekttir: yüz ya da parmak izi istemez, uygulamayı kilitlemez. Rengi, boyutu ve hızı Face ID ayarlarından gelir (burada biraz daha hızlı oynar)",
            cur.appLockEnabled) { set(cur.copy(appLockEnabled = it)) }
        if (cur.appLockEnabled) {
            HiddenAppsPicker(cur.appLockList(), title = "Animasyon oynayacak uygulamalar") { list -> set(cur.copy(appLockPkgs = list.joinToString(","))) }
        }
        Text("Hangi uygulamanın açıldığını erişilebilirlik servisi bildirir; servis açık olmalı.", color = dim, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Adadan akan bildirim, yelpaze menü, ada şekli, kapak portalı, şarj halkası, bildirim kuşları. */
@Composable
private fun CreativeSections() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var m by remember { mutableStateOf<MoreSettings?>(null) }
    LaunchedEffect(Unit) { m = repo.flow.first().more }
    LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        delay(250)
        repo.update { s ->
            s.copy(more = s.more.copy(
                notifFlow = v.notifFlow, thumbFan = v.thumbFan, islandShape = v.islandShape,
                coverPortal = v.coverPortal, chargeRing = v.chargeRing, notifBirds = v.notifBirds,
            ))
        }
    }
    val cur = m ?: return
    val set: (MoreSettings) -> Unit = { m = it }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard("Ada şekli", "Boştaki ada hap yerine başka bir şekle bürünür") {
        SegmentedTabs(ISLAND_SHAPE_NAMES, cur.islandShape) { set(cur.copy(islandShape = it)) }
        Text("Ada boştayken seçtiğin şekle akarak dönüşür; bildirim, müzik ya da başka bir şey gelince yeniden hapa döner ve normal açılır. Nokta modunda kullanılmaz.",
            color = dim, fontSize = 12.sp, lineHeight = 17.sp)
    }

    SectionCard("Adadan akan bildirim", "Bildirimin yazısı kelime kelime adadan dökülür") {
        SwitchRow("Adadan akan bildirim", "Bildirim gelince başlık ve metnin ilk kelimeleri adadan sıvı gibi damlayarak ekrana dökülür, birkaç saniye okunur, sonra adaya geri çekilir. Adadaki bildirim her zamanki gibi görünmeye devam eder",
            cur.notifFlow) { set(cur.copy(notifFlow = it)) }
    }

    SectionCard("Bildirim kuşları", "Bildirim gelince adanın yanına bir kuş konar") {
        SwitchRow("Bildirim kuşları", "Bildirim gelince uygulamaya göre bir serçe, kelebek, uğur böceği ya da arı uçup adanın yanına konar ve bekler. Ada boştayken adaya dokununca o bildirim açılır, kuş uçup gider. Bir dakika sonra kendiliğinden uçar",
            cur.notifBirds) { set(cur.copy(notifBirds = it)) }
    }

    SectionCard("Kapak portalı", "Müzik çalarken kısa adada derin albüm kapağı") {
        SwitchRow("Kapak portalı", "Müzik çalarken kısa adanın içi bir pencereye dönüşür ve arkasında albüm kapağı katmanlar halinde durur; telefonu eğdikçe katmanlar farklı hızda kayar, derinlik hissi verir. Kapağı olmayan şarkılarda normal görünür",
            cur.coverPortal) { set(cur.copy(coverPortal = it)) }
    }

    SectionCard("Şarj halkası", "Şarja takınca ada kareye açılır, halka dolar") {
        SwitchRow("Şarj halkası", "Şarja takınca ada kare bir kutuya açılır, yeşil halka pil yüzdesine kadar dolar ve yüzde sayılır; sonra ada eski haline döner. Ekran açıkken ve ada boştayken ya da müzik çalarken oynar",
            cur.chargeRing) { set(cur.copy(chargeRing = it)) }
    }

    SectionCard("Yelpaze menü", "Başparmak adasına basılı tut, favoriler yay gibi açılsın") {
        SwitchRow("Yelpaze menü", "Başparmak modundaki aşağıdaki adaya basılı tutunca favori uygulamalar adanın çevresinde yay şeklinde açılır. Parmağını kaldırmadan bir simgeye kaydırıp bırakınca uygulama açılır; boşta bırakırsan kapanır",
            cur.thumbFan) { set(cur.copy(thumbFan = it)) }
        Text("Başparmak modu açık olmalı. Uygulamalar Favori uygulamalar listesinden gelir.", color = dim, fontSize = 12.sp, lineHeight = 17.sp)
    }
}

/** Kulaklık kartı, navigasyon, Rahatsız Etmeyin, erişim noktası, sipariş takibi. */
@Composable
private fun IosSections() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var m by remember { mutableStateOf<MoreSettings?>(null) }
    LaunchedEffect(Unit) { m = repo.flow.first().more }
    LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        delay(250)
        repo.update { s ->
            s.copy(more = s.more.copy(
                headphoneCard = v.headphoneCard, headphoneBattery = v.headphoneBattery, navIsland = v.navIsland,
                focusIndicator = v.focusIndicator, hotspotIndicator = v.hotspotIndicator, deliveryIsland = v.deliveryIsland,
            ))
        }
    }
    val btPerm = "android.permission.BLUETOOTH_CONNECT"
    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val c = m ?: return@rememberLauncherForActivityResult
        m = c.copy(headphoneBattery = ok)
        if (!ok) Toast.makeText(ctx, "İzin verilmedi; kartta pil yüzdesi gösterilmeyecek", Toast.LENGTH_SHORT).show()
    }
    val cur = m ?: return
    val set: (MoreSettings) -> Unit = { m = it }
    val dim = MaterialTheme.colorScheme.onSurfaceVariant

    SectionCard("Kulaklık kartı", "Kulaklık bağlanınca adada adı ve simgesiyle kart") {
        SwitchRow("Kulaklık kartı", "Bluetooth ya da kablolu kulaklık bağlanınca ada kısa süre kart olarak açılır: kulaklığın adı, simgesi ve \"Bağlandı\". İzin gerekmez",
            cur.headphoneCard) { set(cur.copy(headphoneCard = it)) }
        if (cur.headphoneCard) {
            SwitchRow("Pil yüzdesi", "Kartın sağında kulaklığın pil yüzdesi halka olarak görünür. Bunun için Android \"Yakındaki cihazlar\" iznini ister; izin yalnızca pil yüzdesini okumak için kullanılır",
                cur.headphoneBattery) { on ->
                val granted = android.os.Build.VERSION.SDK_INT < 31 ||
                    androidx.core.content.ContextCompat.checkSelfPermission(ctx, btPerm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (on && !granted) btLauncher.launch(btPerm) else set(cur.copy(headphoneBattery = on))
            }
            Text("Her kulaklık pil bilgisini paylaşmaz; paylaşmayanlarda kart yüzdesiz görünür.", color = dim, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }

    SectionCard("Navigasyon", "Yol tarifinde adada dönüş oku ve mesafe") {
        SwitchRow("Navigasyon adası", "Google Haritalar, Yandex, Waze gibi uygulamalarda yol tarifi başlayınca kapalı adanın solunda dönüş oku, sağında bir sonraki dönüşe kalan mesafe görünür. Dokununca talimat ve varış süresi açılır, bir daha dokununca harita açılır. Bildirim erişimiyle çalışır, ek izin yok",
            cur.navIsland) { set(cur.copy(navIsland = it)) }
    }

    SectionCard("Sipariş takibi", "Yemek, market ve kargo siparişi adada canlı kalır") {
        SwitchRow("Sipariş takibi", "Trendyol, Getir, Yemeksepeti, Migros, Hepsiburada, Amazon, kargo uygulamaları gibi uygulamaların sipariş bildirimleri adada canlı takibe dönüşür: kapalı adada kalan süre ya da aşama, açınca Alındı → Hazırlanıyor → Yolda → Teslim edildi çubuğu. Teslim edilince kendiliğinden kapanır. Kampanya ve indirim bildirimleri alınmaz",
            cur.deliveryIsland) { set(cur.copy(deliveryIsland = it)) }
        Text("Android 16'daki \"canlı güncelleme\" bildirimleri (ör. taksi, yemek) hangi uygulamadan gelirse gelsin burada görünür.", color = dim, fontSize = 12.sp, lineHeight = 17.sp)
    }

    SectionCard("Rahatsız Etmeyin", "Odak modu açılınca / kapanınca geniş hap") {
        SwitchRow("Rahatsız Etmeyin göstergesi", "Rahatsız Etmeyin (ya da uyku / odak modları) açılınca adada mor hilalli \"Rahatsız Etmeyin · Açık\" hapı, kapanınca \"Kapalı\" görünür. İzin gerekmez",
            cur.focusIndicator) { set(cur.copy(focusIndicator = it)) }
    }

    SectionCard("Erişim noktası", "Hotspot açılınca / kapanınca geniş hap") {
        SwitchRow("Erişim noktası göstergesi", "Kişisel erişim noktası (hotspot) açılınca adada yeşil \"Erişim Noktası · Açık\" hapı, kapanınca \"Kapalı\" görünür. İzin penceresi açılmaz",
            cur.hotspotIndicator) { set(cur.copy(hotspotIndicator = it)) }
    }
}

@Composable
private fun TimeCapsuleSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    var text by remember { mutableStateOf("") }
    var photo by remember { mutableStateOf("") }
    var period by remember { mutableIntStateOf(2) }        // 0 1 gün, 1 1 hafta, 2 1 ay, 3 1 yıl, 4 tarih seç
    var customAt by remember { mutableLongStateOf(0L) }
    var items by remember { mutableStateOf(CapsuleStore.list(ctx)) }
    val fmt = remember { SimpleDateFormat("d MMMM yyyy, HH:mm", Locale("tr")) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            CustomImages.grant(ctx, uri)
            val u = uri.toString()
            if (CustomImages.load(ctx, CustomImages.Slot.CAPSULE, u) != null) photo = u
            else Toast.makeText(ctx.applicationContext, "Fotoğraf açılamadı", Toast.LENGTH_SHORT).show()
        }
    }
    val preview = rememberCustomImage(CustomImages.Slot.CAPSULE, photo)

    fun pickDate() {
        val c = Calendar.getInstance().apply { if (customAt > 0L) timeInMillis = customAt else add(Calendar.MONTH, 1) }
        DatePickerDialog(ctx, { _, y, mo, d ->
            TimePickerDialog(ctx, { _, hh, mm ->
                customAt = Calendar.getInstance().apply { set(y, mo, d, hh, mm, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
            }, c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), true).show()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply {
            datePicker.minDate = System.currentTimeMillis()
        }.show()
    }

    fun dueAt(): Long {
        val c = Calendar.getInstance()
        when (period) {
            0 -> c.add(Calendar.DAY_OF_YEAR, 1)
            1 -> c.add(Calendar.WEEK_OF_YEAR, 1)
            2 -> c.add(Calendar.MONTH, 1)
            3 -> c.add(Calendar.YEAR, 1)
            else -> return customAt
        }
        return c.timeInMillis
    }

    fun bury(due: Long) {
        if (text.isBlank() && photo.isEmpty()) {
            Toast.makeText(ctx.applicationContext, "Önce bir not yaz ya da fotoğraf seç", Toast.LENGTH_SHORT).show(); return
        }
        if (due <= System.currentTimeMillis()) {
            Toast.makeText(ctx.applicationContext, "Gelecekte bir tarih seç", Toast.LENGTH_SHORT).show(); return
        }
        val now = System.currentTimeMillis()
        CapsuleStore.add(ctx, Capsule(now, text.trim(), photo, now, due))
        items = CapsuleStore.list(ctx)
        Toast.makeText(ctx.applicationContext, "Kapsül gömüldü: ${fmt.format(due)}", Toast.LENGTH_LONG).show()
        text = ""; photo = ""
    }

    SectionCard("Zaman kapsülü", "Geleceğe bir not bırak, günü gelince ada açılıp sana göstersin") {
        OutlinedTextField(
            value = text, onValueChange = { if (it.length <= 280) text = it },
            label = { Text("Geleceğe notun") }, minLines = 2, maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (preview != null) {
                Image(preview, null, Modifier.size(50.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
            }
            IslandActionButton(if (photo.isEmpty()) "Fotoğraf ekle" else "Fotoğrafı değiştir",
                { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.weight(1f))
            if (photo.isNotEmpty()) IslandActionButton("Kaldır", { photo = "" }, Modifier.weight(0.6f))
        }
        SubHeader("Ne zaman açılsın")
        SegmentedTabs(listOf("1 gün", "1 hafta", "1 ay", "1 yıl", "Tarih"), period) { i ->
            period = i
            if (i == 4) pickDate()
        }
        if (period == 4) {
            Text(if (customAt > 0L) "Açılış: ${fmt.format(customAt)}" else "Tarih seçilmedi", color = dim, fontSize = 13.sp)
            IslandActionButton("Tarih ve saati seç", { pickDate() })
        }
        IslandActionButton("Kapsülü göm", { bury(dueAt()) }, tonal = false)
        IslandActionButton("Dene: 15 saniye sonra açılsın", { bury(System.currentTimeMillis() + 15_000L) })
        Text("Kapsül, günü geldikten sonra telefonun kilidi ilk açıldığında ve ada boştayken açılır. Notun içeriği açılana kadar burada gösterilmez.",
            color = dim, fontSize = 12.sp, lineHeight = 17.sp)

        val pending = items.filter { !it.delivered }.sortedBy { it.dueAt }
        if (pending.isNotEmpty()) {
            SectionDivider()
            SubHeader("Bekleyen kapsüller")
            pending.forEach { c ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("⏳ ${fmt.format(c.dueAt)}", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(if (c.photo.isNotEmpty()) "Not + fotoğraf · ${capsuleAgo(c.createdAt)} gömüldü" else "Not · ${capsuleAgo(c.createdAt)} gömüldü",
                            color = dim, fontSize = 12.sp)
                    }
                    IslandActionButton("Sil", {
                        CapsuleStore.remove(ctx, c.id)
                        items = CapsuleStore.list(ctx)
                        if (c.photo.isNotEmpty()) scope.launch {
                            val full = SettingsRepository(ctx.applicationContext).flow.first()
                            CustomImages.releaseUnused(ctx, c.photo, full)
                        }
                    }, Modifier.weight(0.35f))
                }
            }
        }
    }
}

/** Kaydırılabilir sahne seçici (sekmelere sığmayacak kadar çok seçenek için). */
@Composable
private fun SceneChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val sel = i == selected
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (sel) Pal.c("ui_tab_selected") else cs.surfaceVariant)
                    .clickable(role = Role.Tab) { onSelect(i) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (sel) Pal.c("ui_tab_selected_text") else cs.onSurfaceVariant, fontSize = 13.sp,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
            }
        }
    }
}
