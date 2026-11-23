package com.example.dynamicisland

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val ANIM_STYLES = listOf(
    "Yaylı" to "Dengeli, hafif yaylanan geçiş",
    "Yumuşak" to "Yavaş ve akıcı kayma",
    "Hızlı" to "Kısa ve seri geçiş",
    "Zıplamalı" to "Açılırken hafifçe zıplar",
    "Sıvı" to "Önce genişlik, sonra yükseklik",
)

private val LYRIC_ANIMS = listOf(
    "Solma" to "Eski satır silinir, yenisi belirir",
    "Yukarı kayma" to "Satırlar yukarı doğru kayar",
    "Yakınlaşma" to "Satır büyüyerek gelir",
    "Yaylı zıplama" to "Yeni satır alttan yaylanarak gelir",
    "Yatay kayma" to "Satırlar yandan kayar",
    "Anında" to "Geçiş animasyonu yok",
    "Adadan çıkış" to "Satırın tamamı adanın içinden küçük çıkar, büyüyerek yerine gelir; eski satır adaya geri girer",
    "Adadan tek tek" to "Kelimeler şarkıda sırası gelince tek tek adadan küçük çıkıp büyüyerek yerine gelir",
    "Soldan açılma" to "Satır soldan sağa açılarak belirir",
    "Çevirme (3D)" to "Eski satır yatay eksende döner, yenisi dönerek gelir",
    "Odak çekme" to "Yeni satır büyük başlayıp netleşerek yerine oturur",
)

@Composable
fun SettingsScreen(lyricsPage: Boolean = false) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    var initial by remember { mutableStateOf<IslandSettings?>(null) }
    LaunchedEffect(Unit) { initial = repo.flow.first() }
    val init = initial ?: return
    SettingsContent(init, lyricsPage, repo) { repo.save(it) }
}

@Composable
private fun SettingsContent(init: IslandSettings, lyricsPage: Boolean, repo: SettingsRepository, onSave: suspend (IslandSettings) -> Unit) {
    val ctx = LocalContext.current
    var draft by remember { mutableStateOf(init) }
    var version by remember { mutableIntStateOf(0) }
    var mode by remember { mutableIntStateOf(1) }            // 0 bildirim, 1 müzik
    var expanded by remember { mutableStateOf(lyricsPage) }

    // Galeriden fotoğraf seçici (hangi alan için seçildiği ekran döndürülse de korunur)
    val scope = rememberCoroutineScope()
    var pendingSlot by rememberSaveable { mutableStateOf(-1) }
    // Fotoğraf kopyalanmaz: sistemin fotoğraf seçicisi (galeri / Google Fotoğraflar) kullanılır; yalnızca adres ve kalıcı okuma izni alınır
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val slot = CustomImages.Slot.values().getOrNull(pendingSlot)
        pendingSlot = -1
        if (uri != null && slot != null) scope.launch {
            CustomImages.grant(ctx, uri)
            val u = uri.toString()
            if (CustomImages.load(ctx, slot, u) != null) {
                val old = draft.imageUri(slot)
                draft = draft.withImageUri(slot, u)
                if (old != u) CustomImages.releaseUnused(ctx, old, draft)
            } else {
                Toast.makeText(ctx.applicationContext, "Fotoğraf açılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pick: (CustomImages.Slot) -> Unit = { slot -> pendingSlot = slot.ordinal; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
    // Video da kopyalanmaz: sistemin seçicisi kullanılır, yalnızca adres ve kalıcı okuma izni alınır (depolama izni gerekmez)
    val videoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            CustomVideo.grant(ctx, uri)
            val u = uri.toString()
            val old = draft.customBgVideoUri
            draft = draft.copy(customBgVideoUri = u)
            if (old != u) CustomVideo.releaseUnused(ctx, old, draft)
        }
    }
    // Kapak videosu: aynı seçici mantığı, ayrı adres
    val artVideoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            CustomVideo.grant(ctx, uri)
            val u = uri.toString()
            val old = draft.customArtVideoUri
            draft = draft.copy(customArtVideoUri = u)
            if (old != u) CustomVideo.releaseUnused(ctx, old, draft)
        }
    }
    val pickArtVideo: () -> Unit = { artVideoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val removeArtVideo: () -> Unit = {
        val old = draft.customArtVideoUri
        draft = draft.copy(customArtVideoUri = "")
        CustomVideo.releaseUnused(ctx, old, draft)
    }
    val pickVideo: () -> Unit = { videoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)) }
    val removeVideo: () -> Unit = {
        val old = draft.customBgVideoUri
        draft = draft.copy(customBgVideoUri = "")
        CustomVideo.releaseUnused(ctx, old, draft)
    }
    // Ses efektleri: dosya kopyalanmaz; sistemin belge seçicisiyle yalnızca adres ve kalıcı okuma izni alınır (depolama izni gerekmez)
    var pendingSfx by rememberSaveable { mutableStateOf(-1) }
    val sfxPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ev = SoundEvent.values().getOrNull(pendingSfx)
        pendingSfx = -1
        if (uri != null && ev != null) scope.launch {
            IslandSounds.grant(ctx, uri)
            val loaded = IslandSounds.load(ctx, uri, draft.cue(ev))
            if (loaded != null) {
                val old = draft.cue(ev).uri
                draft = draft.withCue(ev, loaded)
                IslandSounds.releaseGrant(ctx, old, draft.sfxUris())
            } else {
                IslandSounds.releaseGrant(ctx, uri.toString(), draft.sfxUris())
                Toast.makeText(ctx.applicationContext, "Ses dosyası açılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickSfx: (SoundEvent) -> Unit = { ev -> pendingSfx = ev.ordinal; sfxPicker.launch(arrayOf("audio/*")) }
    val removeSfx: (SoundEvent) -> Unit = { ev ->
        val old = draft.cue(ev).uri
        draft = draft.withCue(ev, SoundCue())
        IslandSounds.releaseGrant(ctx, old, draft.sfxUris())
    }
    val remove: (CustomImages.Slot) -> Unit = { slot ->
        val old = draft.imageUri(slot)
        draft = draft.withImageUri(slot, "")
        CustomImages.releaseUnused(ctx, old, draft)
    }

    SideEffect { UiHaptics.enabled = draft.uiHaptics }
    // Renk düzenleyici: taslaktaki renkler anında uygulanır (kayıt 250 ms sonra; ada da kayıttan aynı renkleri alır)
    SideEffect { Pal.sync(draft.colorEditorEnabled, draft.colorOverrides) }

    // Yedekleme: JSON dosyasına aktar / dosyadan geri yükle
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                repo.save(draft)
                val txt = repo.exportJson()
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(txt.toByteArray(Charsets.UTF_8)) } ?: error("açılamadı")
            }.isSuccess
            Toast.makeText(ctx.applicationContext, if (ok) "Ayarlar dışa aktarıldı" else "Dışa aktarılamadı", Toast.LENGTH_SHORT).show()
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val ok = runCatching {
                val txt = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                repo.importJson(txt)
            }.getOrDefault(false)
            if (ok) { draft = repo.flow.first(); version++ }
            Toast.makeText(ctx.applicationContext, if (ok) "Ayarlar geri yüklendi" else "Geçerli bir yedek dosyası değil", Toast.LENGTH_SHORT).show()
        }
    }

    // Slider sürüklenirken değil, 250 ms sakinleşince kaydet
    LaunchedEffect(draft) { delay(250); onSave(draft) }

    // ---- Ayar sayfaları: her kart tek bir "sayfa"dır; sayfalar arası yapışarak (snap) geçilir
    val pages = if (lyricsPage) lyricsPages(draft, version, { draft = it }) { draft = draft.withLyricsDefaults(); version++ } else buildPages {
        // ---- Yerleşim
        pendingGroup = "Yerleşim"
        sec("Kapalı ada", "Kamera çevresindeki küçük hal") {
            SliderRow("Genişlik", draft.compactWidthDp, 60f..220f, "${draft.compactWidthDp.toInt()} dp") { draft = draft.copy(compactWidthDp = it) }
            SliderRow("Yükseklik", draft.compactHeightDp, 20f..60f, "${draft.compactHeightDp.toInt()} dp") { draft = draft.copy(compactHeightDp = it) }
        }
        sec("Açık ada", "Dokununca açılan genişletilmiş panel") {
            SliderRow("Genişlik", draft.expandedWidthDp, 260f..400f, "${draft.expandedWidthDp.toInt()} dp") { draft = draft.copy(expandedWidthDp = it) }
            SliderRow("Yükseklik", draft.expandedHeightDp, 72f..120f, "${draft.expandedHeightDp.toInt()} dp") { draft = draft.copy(expandedHeightDp = it) }
        }
        sec("Konum", "Kamera deliğine göre ince ayar") {
            SliderRow("Yatay", draft.offsetXDp, -60f..60f, "${draft.offsetXDp.toInt()} dp") { draft = draft.copy(offsetXDp = it) }
            SliderRow("Dikey", draft.offsetYDp, -60f..40f, "${draft.offsetYDp.toInt()} dp") { draft = draft.copy(offsetYDp = it) }
            SwitchRow("Ters ada", "Ada ekranın üstünde değil en altında durur. Olaylar ve bildirimler düz görünür; adaya basınca panel aşağı değil yukarı doğru açılır. Dikey konum ayarı alttan ölçülür", draft.invertedIsland) { draft = draft.copy(invertedIsland = it) }
            if (draft.invertedIsland) {
                SwitchRow("Ters adaya özel konum ve boyut", "Açıkken ters ada, üstteki Kapalı ada ve Konum ayarlarını değil aşağıdaki kendi ayarlarını kullanır (ekranın altında farklı bir boyut / yer isteyebilirsin)", draft.invCustom) { draft = draft.copy(invCustom = it) }
                if (draft.invCustom) {
                    SubHeader("Ters ada boyutu")
                    SliderRow("Genişlik", draft.invWidthDp, 60f..220f, "${draft.invWidthDp.toInt()} dp") { draft = draft.copy(invWidthDp = it) }
                    SliderRow("Yükseklik", draft.invHeightDp, 20f..60f, "${draft.invHeightDp.toInt()} dp") { draft = draft.copy(invHeightDp = it) }
                    SubHeader("Ters ada konumu")
                    SliderRow("Yatay", draft.invOffsetXDp, -160f..160f, "${draft.invOffsetXDp.toInt()} dp") { draft = draft.copy(invOffsetXDp = it) }
                    SliderRow("Alttan uzaklık", draft.invOffsetYDp, -20f..200f, "${draft.invOffsetYDp.toInt()} dp") { draft = draft.copy(invOffsetYDp = it) }
                    IslandActionButton("Normal adanın ölçülerini kopyala", {
                        draft = draft.copy(invWidthDp = draft.compactWidthDp, invHeightDp = draft.compactHeightDp, invOffsetXDp = draft.offsetXDp, invOffsetYDp = draft.offsetYDp)
                    })
                }
            }
            SwitchRow("Yatay mod", "Telefonu yan çevirince ada kamera kenarında dikey bir hap olur. Gelen bildirim, şarj, arama gibi olaylar adanın yanında, simgesi düz duran küçük baloncuk olarak animasyonla ayrılır. Bildirimde uygulamanın simgesi görünür", draft.landscapeMode) { draft = draft.copy(landscapeMode = it) }
            if (draft.landscapeMode) {
                SwitchRow("Baloncuklar adanın altında", "Açıkken baloncuklar adanın altına, kapalıyken üstüne dizilir", draft.landscapeBubbleBelow) { draft = draft.copy(landscapeBubbleBelow = it) }
                SwitchRow("Olaylar adada da görünsün", "Açıkken olay (bildirim, şarj, müzik...) adanın içinde de gösterilir. Adada zaten bir olay varsa gelen yeni olaylar baloncuk olur; aynı anda en çok 2 baloncuk çıkar. Kapalıyken olaylar yalnızca baloncukta görünür. Adanın içinde çizilen özellikler (adada bildirim yazısı, kapalı adada şarkı sözü, arama / müzik hapı, iPhone modu dairesi) açıksa bu ayar kendiliğinden açık sayılır", draft.landscapeEventsInIsland) { draft = draft.copy(landscapeEventsInIsland = it) }
            }
        }
        sec("Görünürlük", "Ada ne zaman görünsün") {
            SwitchRow("Boştayken de göster", "Müzik ve bildirim yokken ada kamera çevresinde durur", draft.showIdle) { draft = draft.copy(showIdle = it) }
            SwitchRow("Zil / sessiz göstergesi", "Telefonu sessize, titreşime ya da sesliye alınca ada kısa süre Zil, Sessiz ya da Titreşim yazısıyla açılır", draft.ringerIndicator) { draft = draft.copy(ringerIndicator = it) }
            SwitchRow("Zil göstergesi genişlesin", "Açıkken ada, ekran kaydındaki gibi yana doğru genişleyip solda zil simgesi, sağda Zil / Sessiz / Titreşim yazısı gösterir. Kapalıyken ada boyutunu değiştirmez, yalnızca solda simge görünür", draft.ringerExpand) { draft = draft.copy(ringerExpand = it) }
            if (draft.ringerExpand) {
                SliderRow("Zil hapı genişliği", draft.ringerWidthPct, 40f..98f, "%${draft.ringerWidthPct.toInt()} (ekran genişliği)") { draft = draft.copy(ringerWidthPct = it) }
                SliderRow("Zil hapı yüksekliği", draft.ringerHeightPct, 100f..220f, "%${draft.ringerHeightPct.toInt()} (kapalı adaya göre)") { draft = draft.copy(ringerHeightPct = it) }
            }
            SwitchRow("Medya sesi göstergesi", "Ses tuşlarıyla (ya da kenar ses tuşlarıyla) medya sesini artırınca / azaltınca ada kısa süre hoparlör simgesi, seviye çubuğu ve yüzdeyle açılır", draft.volumeIndicator) { draft = draft.copy(volumeIndicator = it) }
            SwitchRow("Zil sesi seviyesi göstergesi", "Zil sesini (ses tuşları ya da ayarlar) değiştirince ada iOS gibi yana genişler: solda zil simgesi ve \"Zil\", sağda ince seviye çubuğu. Telefon sesli moddayken çalışır",
                draft.more.ringVolIndicator) { draft = draft.copy(more = draft.more.copy(ringVolIndicator = it)) }
            SwitchRow("İnternet göstergesi", "İnternete bağlanınca ada iOS tarzı geniş hap olur: solda Wi-Fi / mobil veri simgesi, sağda \"Bağlandı\". Bağlantı kesilince \"Bağlantı kesildi\" yazar. Wi-Fi'den mobil veriye geçişte yanıp sönmez",
                draft.more.netIndicator) { draft = draft.copy(more = draft.more.copy(netIndicator = it)) }
            SwitchRow("Uçak modu göstergesi", "Uçak modu açılınca / kapanınca ada turuncu uçak simgesi ve \"Açık\" / \"Kapalı\" yazısıyla haber verir",
                draft.more.airplaneIndicator) { draft = draft.copy(more = draft.more.copy(airplaneIndicator = it)) }
            SwitchRow("Düşük pil göstergesi", "Şarjda değilken pil %20'ye ve %10'a düşünce ada uyarır. Dokununca düşük güç modu açılır (izin yoksa pil tasarrufu ayarı açılır). Düşük güç modu açılınca sarıya döner",
                draft.more.lowBattIndicator) { draft = draft.copy(more = draft.more.copy(lowBattIndicator = it)) }
            if (draft.more.lowBattIndicator) {
                SwitchRow("Düşük pil uyarısı genişlesin", "Açıkken ada büyük bir karta açılır: solda \"%10 Pil\" ve \"Düşük Güç Modu için dokun\", sağda kırmızı kapsül içinde pil. Kapalıyken ada genişlemez; ince geniş hapta \"Düşük Pil\" ve kırmızı yüzde + pil görünür",
                    draft.more.lowBattExpand) { draft = draft.copy(more = draft.more.copy(lowBattExpand = it)) }
            }
            SwitchRow("Durum çubuğunu gizle", "Şarj görünümü, zil sesi görünümü vb. açıldığında durum çubuğunu gizler. Durum çubuğu bulanıklaşır (cihaz desteklemiyorsa siyah olur)", draft.hideStatusBar) { draft = draft.copy(hideStatusBar = it) }
        }
        sec("Kamera / mikrofon göstergesi", "Bir uygulama kamerayı ya da mikrofonu kullanırken ada haber verir", PreviewKind.Privacy) {
            SwitchRow(
                "Gösterge",
                "Kamera ya da mikrofon açılınca ada kısa süre açılır, sonra yeşil (kamera) / turuncu (mikrofon) nokta olarak kalır. Nokta modunda ve normal adada çalışır. Hangi uygulamanın kullandığı Android tarafından verilmez",
                draft.privacyIndicator,
            ) { draft = draft.copy(privacyIndicator = it) }
            if (draft.privacyIndicator) {
                SwitchRow(
                    "Açılınca genişlesin",
                    "Açıkken kamera / mikrofon başlayınca ada 4 saniye genişleyip sonra küçülür. Kapalıyken hiç genişlemez, doğrudan küçük gösterge olarak görünür",
                    draft.privacyExpand,
                ) { draft = draft.copy(privacyExpand = it) }
            }
        }
        sec("Nokta modu", "Ada yerine 3, 4 ya da 5 nokta; olaylar nokta (baloncuk) olarak görünür") {
            SwitchRow(
                "Nokta modu",
                "Ortadaki nokta kameranın üstünde durur. Yeni olay ortadaki noktada, öncekiler yan noktalarda görünür; dokununca ada gibi genişler",
                draft.dotMode,
            ) { draft = draft.copy(dotMode = it, iphoneMode = if (it) false else draft.iphoneMode) }
            if (draft.dotMode) {
                SegmentedTabs(listOf("3 nokta", "4 nokta", "5 nokta"), draft.dotCount - 3) { draft = draft.copy(dotCount = it + 3) }
                SliderRow("Orta nokta (kamera)", draft.dotMidDp, 12f..48f, "${draft.dotMidDp.toInt()} dp") { draft = draft.copy(dotMidDp = it) }
                SliderRow("Sol nokta", draft.dotLeftDp, 8f..48f, "${draft.dotLeftDp.toInt()} dp") { draft = draft.copy(dotLeftDp = it) }
                SliderRow("Sağ nokta", draft.dotRightDp, 8f..48f, "${draft.dotRightDp.toInt()} dp") { draft = draft.copy(dotRightDp = it) }
                if (draft.dotCount >= 5) SliderRow("Sol dış nokta", draft.dotLeft2Dp, 8f..48f, "${draft.dotLeft2Dp.toInt()} dp") { draft = draft.copy(dotLeft2Dp = it) }
                if (draft.dotCount >= 4) SliderRow("Sağ dış nokta", draft.dotRight2Dp, 8f..48f, "${draft.dotRight2Dp.toInt()} dp") { draft = draft.copy(dotRight2Dp = it) }
                SwitchRow(
                    "Noktaları birleştir",
                    "Boştayken noktalar birleşip tek bir dinamik ada gibi görünür. Olay gelince ilgili nokta adadan ayrılır; olayların görünümü aynı kalır",
                    draft.dotMerge,
                ) { draft = draft.copy(dotMerge = it) }
                if (draft.dotMerge) SwitchRow(
                    "Olaylar adanın içinde kalsın",
                    "Olay gelince nokta adadan ayrılmaz; olay simgesi birleşik adanın içinde, kendi yerinde görünür",
                    draft.dotMergeKeep,
                ) { draft = draft.copy(dotMergeKeep = it) }
                SliderRow("Noktalar arası boşluk", draft.dotGapDp, 2f..30f, "${draft.dotGapDp.toInt()} dp") { draft = draft.copy(dotGapDp = it) }
                SliderRow("Konum: yatay (sağ +)", draft.dotOffsetXDp, -120f..120f, "${draft.dotOffsetXDp.toInt()} dp") { draft = draft.copy(dotOffsetXDp = it) }
                SliderRow("Konum: dikey (aşağı +)", draft.dotOffsetYDp, -60f..80f, "${draft.dotOffsetYDp.toInt()} dp") { draft = draft.copy(dotOffsetYDp = it) }
            }
        }
        sec("iPhone modu", "iPhone 14 Pro gibi: hap şeklinde ada ve yanında ayrı siyah daire") {
            SwitchRow(
                "iPhone modu",
                "Ada hap şeklinde olur, yanında siyah bir daire durur. Nokta modu ile birlikte çalışmaz (biri açılınca öteki kapanır)",
                draft.iphoneMode,
            ) { draft = draft.copy(iphoneMode = it, dotMode = if (it) false else draft.dotMode) }
            if (draft.iphoneMode) {
                SwitchRow("Siyah daire", "Adanın yanındaki ayrı siyah daire (iPhone'daki kamera deliği gibi)", draft.iphoneCircle) { draft = draft.copy(iphoneCircle = it) }
                if (draft.iphoneCircle) {
                    SegmentedTabs(listOf("Daire solda", "Daire sağda"), draft.iphoneCircleSide) { draft = draft.copy(iphoneCircleSide = it) }
                    SwitchRow(
                        "Olaylar dairede görünsün",
                        "İkinci bir olay başlayınca (müzik, ekran kaydı, arama, kamera / mikrofon...) öncekisi dairede görünür; dokununca ada gibi açılır. Kapalıysa daire süs olarak kalır",
                        draft.iphoneEvents,
                    ) { draft = draft.copy(iphoneEvents = it) }
                    SliderRow("Daire boyutu (en çok ada yüksekliği)", draft.iphoneCircleDp, 16f..48f, "${draft.iphoneCircleDp.toInt()} dp") { draft = draft.copy(iphoneCircleDp = it) }
                    SliderRow("Ada ile daire arası", draft.iphoneGapDp, 2f..30f, "${draft.iphoneGapDp.toInt()} dp") { draft = draft.copy(iphoneGapDp = it) }
                    SwitchRow(
                        "İkiliyi ortala",
                        "Ada + daire birlikte kameranın ortasında durur (iPhone gibi). Kapalıysa ada kameranın üstünde kalır, daire yanına gelir",
                        draft.iphoneCenterGroup,
                    ) { draft = draft.copy(iphoneCenterGroup = it) }
                }
                SliderRow("Ada genişliği", draft.iphonePillWDp, 60f..220f, "${draft.iphonePillWDp.toInt()} dp") { draft = draft.copy(iphonePillWDp = it) }
                SliderRow("Ada yüksekliği", draft.iphonePillHDp, 20f..56f, "${draft.iphonePillHDp.toInt()} dp") { draft = draft.copy(iphonePillHDp = it) }
                SliderRow("Konum: yatay (sağ +)", draft.iphoneOffsetXDp, -120f..120f, "${draft.iphoneOffsetXDp.toInt()} dp") { draft = draft.copy(iphoneOffsetXDp = it) }
                SliderRow("Konum: dikey (aşağı +)", draft.iphoneOffsetYDp, -60f..80f, "${draft.iphoneOffsetYDp.toInt()} dp") { draft = draft.copy(iphoneOffsetYDp = it) }
            }
        }
        page("Favori uygulamalar", PreviewKind.Favorites, "Boştayken adaya dokununca açılan uygulama paneli") { FavoritesSection(draft) { draft = it } }
        page("Çeviri", subtitle = "Boştayken adaya dokununca açılan çeviri paneli") { TranslateSection(draft) { draft = it } }

        sec("Bildirimler", "Adada görünen bildirim için hareketler") {
            SubHeader("Bildirim türü")
            SwitchRow("iOS tarzı cam banner", "Bildirimler adada değil, iOS'taki gibi ekranın üstünde tam genişlikte cam banner olarak görünür (geliş / gidiş animasyonu aşağıdan seçilir). Dokununca uygulama açılır, yukarı kaydırınca kapanır. Süre: Genişletilmiş kalma süresi",
                draft.notifBanner) { draft = draft.copy(notifBanner = it, notifInline = if (it) false else draft.notifInline) }
            SwitchRow("Adayı genişletme (yazı adanın içinde)", "Bildirim gelince ada büyümez. Gönderen, bildirim yazısı ve uygulamanın simgesi küçük adanın içine sığdırılarak gösterilir. Süre: Genişletilmiş + Küçültülmüş kalma süresi toplamı. Nokta modunda etkisizdir. İstersen Ada genişliğini artırarak yazıya daha çok yer açabilirsin.",
                draft.notifInline) { draft = draft.copy(notifInline = it, notifBanner = if (it) false else draft.notifBanner) }
            if (draft.notifBanner) {
                SubHeader("Cam banner: animasyon")
                RadioRow("Klasik", "Saatin altındaki küçük haptan büyüyerek açılır, kapanırken aynı yoldan o hapa küçülür", !draft.bannerSlideAnim) {
                    draft = draft.copy(bannerSlideAnim = false)
                }
                RadioRow("Üstten kayma", "Ekranın üst kenarının altından kayarak iner ve yaylanarak oturur; kapanırken yukarı kayar", draft.bannerSlideAnim) {
                    draft = draft.copy(bannerSlideAnim = true)
                }
                SubHeader("Cam banner: menü ve simge")
                SwitchRow("Bildirim menüsü", "Banner'a basınca Cevapla, Kopyala ve İlet seçenekleri çıkar. Kopyalama için herhangi bir izin gerekmez.",
                    draft.bannerMenuEnabled) { draft = draft.copy(bannerMenuEnabled = it) }
                if (draft.bannerMenuEnabled) {
                    RadioRow("Basılı tutunca", "Banner'a basılı tutunca menü çıkar, kısa dokunuş uygulamayı açar", !draft.bannerMenuTap) {
                        draft = draft.copy(bannerMenuTap = false)
                    }
                    RadioRow("Dokununca", "Banner'a kısa dokununca menü çıkar; uygulamayı açmak için simgeye dokun", draft.bannerMenuTap) {
                        draft = draft.copy(bannerMenuTap = true)
                    }
                }
                SwitchRow("Simgeye dokununca uygulamayı aç", "Kapalıysa banner'daki simgeye (gönderen fotoğrafı / uygulama simgesi) dokunmak uygulamayı açmaz",
                    draft.bannerIconOpens) { draft = draft.copy(bannerIconOpens = it) }
            }
            SectionDivider()
            Text("Aynı hareket hem açmaya / küçültmeye hem silmeye atanırsa, en son açtığın seçenek kalır; çakışan diğeri otomatik kapanır.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            SubHeader("Süreler")
            SliderRow("Genişletilmiş kalma süresi", draft.notifExpandedSec, 2f..20f, "${draft.notifExpandedSec.toInt()} sn") { draft = draft.copy(notifExpandedSec = kotlin.math.round(it)) }
            SliderRow("Küçültülmüş kalma süresi", draft.notifCompactSec, 0f..60f, if (draft.notifCompactSec < 1f) "Hemen gitsin" else "${draft.notifCompactSec.toInt()} sn") { draft = draft.copy(notifCompactSec = kotlin.math.round(it)) }
            Text("Önce bildirim genişletilmiş görünür, ardından küçülüp bu süre kadar adada (nokta modunda nokta olarak) kalır. Dokunarak açabilir ya da kaydırarak silebilirsin.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            SwitchRow("Küçükken dokununca genişlesin", "Küçülmüş (ya da \"ada genişlemesin\" modunda kapalı adada duran) bildirime dokununca bildirim yeniden genişler. Genişken dokununca aşağıdaki ayar geçerli olur",
                draft.notifExpandTap) { draft = draft.copy(notifExpandTap = it) }
            SubHeader("Uygulamayı aç")
            SwitchRow("Dokununca", "Bildirimin geldiği uygulama açılır (mümkünse ilgili sohbet / ekran)",
                draft.notifTapOpens) { on -> draft = draft.copy(notifTapOpens = on, notifCollapseTap = if (on) false else draft.notifCollapseTap) }
            SwitchRow("Basılı tutunca", null,
                draft.notifOpenLong) { on -> draft = draft.copy(notifOpenLong = on, notifCollapseLong = if (on) false else draft.notifCollapseLong) }
            SwitchRow("Aşağı kaydırınca", null,
                draft.notifOpenSwipeDown) { draft = draft.copy(notifOpenSwipeDown = it) }
            if (draft.notifTapOpens || draft.notifOpenLong || draft.notifOpenSwipeDown) {
                SwitchRow("Açılış animasyonu", "Adadan siyah bir ekran çıkıp ekranı kaplar, ardından uygulama açılır. Hız ve stil: Hareket bölümü",
                    draft.notifLaunchAnim) { draft = draft.copy(notifLaunchAnim = it) }
            }
            SectionDivider()
            SubHeader("Bildirimi küçült")
            SwitchRow("Dokununca", "Bildirim açılmadan adaya küçülür",
                draft.notifCollapseTap) { on -> draft = draft.copy(notifCollapseTap = on, notifTapOpens = if (on) false else draft.notifTapOpens) }
            SwitchRow("Basılı tutunca", null,
                draft.notifCollapseLong) { on -> draft = draft.copy(notifCollapseLong = on, notifOpenLong = if (on) false else draft.notifOpenLong) }
            SwitchRow("Yukarı kaydırınca", null,
                draft.notifCollapseSwipeUp) { on -> draft = draft.copy(notifCollapseSwipeUp = on, notifDeleteSwipeUp = if (on) false else draft.notifDeleteSwipeUp) }
            SectionDivider()
            SubHeader("Bildirimi sil")
            SwitchRow("Sağa veya sola kaydırınca", "Bildirim hem adadan hem bildirim panelinden silinir (bildirim erişimi açık olmalı)",
                draft.notifDeleteSwipeSide) { draft = draft.copy(notifDeleteSwipeSide = it) }
            SwitchRow("Yukarı kaydırınca", null,
                draft.notifDeleteSwipeUp) { on -> draft = draft.copy(notifDeleteSwipeUp = on, notifCollapseSwipeUp = if (on) false else draft.notifCollapseSwipeUp) }
        }

        sec("Yan baloncuklar", "Adanın yanında ayrılan baloncuklar (müzik, ekran kaydı, arama)", PreviewKind.SideBubbles) {
            SwitchRow("Olayları alt alta aç", "Birden fazla olay varken adaya dokununca (ya da aşağı kaydırınca) hepsi genişleyip alt alta kartlar halinde sıralanır. Karta dokununca ya da yukarı kaydırınca kapanır",
                draft.stackExpand) { draft = draft.copy(stackExpand = it) }
            SectionDivider()
            SwitchRow("Dokununca olay ortaya geçsin", "Kapalıyken baloncuğa dokununca o uygulamanın paneli genişler ama olay ortaya geçmez; panel kapanınca eski düzen döner. Açıkken olay kalıcı olarak ortaya geçer",
                draft.sideTapPromote) { draft = draft.copy(sideTapPromote = it) }
        }

        sec("Ekran kaydı", "Telefonun kendi ekran kaydı adada görünür (ek izin istemez)", PreviewKind.ScreenRec) {
            SwitchRow("Ekran kaydı adası", "Bildirim erişimi açık olmalı. Kayıt sırasında kırmızı nokta, dokununca süre ve durdur düğmesi", draft.screenRecEnabled) { draft = draft.copy(screenRecEnabled = it) }
            if (draft.screenRecEnabled) {
                SubHeader("Açık ada genişliği")
                SegmentedTabs(listOf("Normal", "Tam genişlik"), if (draft.screenRecWide) 1 else 0) {
                    draft = draft.copy(screenRecWide = it == 1)
                }
                Text("Tam genişlikte ada durum çubuğunun sağ ve soluna kadar uzanır, köşelere değmez. Kayda dokununca ve kayıt bitince bu boyut kullanılır.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                RecPreviewPermissionRow()
            }
        }

        sec("Google Asistan / Gemini", "Asistan açılınca ada Siri benzeri renkli animasyona dönüşür", PreviewKind.Assistant) {
            SwitchRow("Asistan animasyonu", "Erişilebilirlik servisi açık olmalı", draft.assistantEnabled) { draft = draft.copy(assistantEnabled = it) }
            if (draft.assistantEnabled) {
                SwitchRow("Tam genişlik animasyon", "Ada kutusu olmadan, renkli animasyon ekranın solundan sağına uzanır",
                    draft.assistantFullWidth) { draft = draft.copy(assistantFullWidth = it) }
                if (!draft.assistantFullWidth) {
                    SubHeader("Animasyon stili")
                    SegmentedTabs(listOf("Klasik", "Cam oval"), draft.assistantStyle) { draft = draft.copy(assistantStyle = it) }
                    Text("Cam oval: siyah camsı bir oval içinde, ortada renkli (mavi, mor, kırmızı, turuncu, beyaz) akan Siri dalgası. En iyi görünüm için genişlik 150, yükseklik 112 dp civarında olsun.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                    SliderRow("Genişlik", draft.assistantWidthDp, 100f..300f, "${draft.assistantWidthDp.toInt()} dp") { draft = draft.copy(assistantWidthDp = it) }
                }
                SliderRow("Yükseklik", draft.assistantHeightDp, 60f..180f, "${draft.assistantHeightDp.toInt()} dp") { draft = draft.copy(assistantHeightDp = it) }
            }
        }

        page("Kenar tuşları", PreviewKind.SideCall, "Cevapla / Reddet kenar tuşları") { SideCallSettingsSection() }
        page("Kenar ses tuşları", PreviewKind.SideCall, "Telefonun kenarında ses artır / azalt tuşları") { SideVolumeSettingsSection() }
        page("Kenar müzik çubukları", PreviewKind.PillStyle, "Dikey müzik tuşları ve ilerleme çubuğu") { SideMusicSettingsSection() }
        page("Çizerek komut", PreviewKind.Island, "Adaya basılı tut, harf çiz: kamera, müzik, fener...") { GestureSettingsSection() }

        // ---- Görünüm
        pendingGroup = "Görünüm"

        sec("Gökkuşağı modu", "Ada, müzik paneli, mesaj ve şarkı sözleri gökkuşağı renklerinde akar") {
            SwitchRow("Gökkuşağı modu", "Açıkken ilgili öğelerde diğer renk ayarları yerine gökkuşağı kullanılır",
                draft.rainbowEnabled) { draft = draft.copy(rainbowEnabled = it) }
            if (draft.rainbowEnabled) {
                SliderRow("Saydamlık", draft.rainbowAlpha, 0.2f..1f, "%${(draft.rainbowAlpha * 100).toInt()}") { draft = draft.copy(rainbowAlpha = it) }
            }
        }
        sec("Renk", "Adanın genel renk davranışı") {
            SwitchRow("Albüm kapağı rengi", "Ada rengi, çalan şarkının kapağına göre değişir",
                draft.dynamicColor) { draft = draft.copy(dynamicColor = it) }
            SectionDivider()
            SubHeader("Ada rengi / cam rengi")
            Text("Albüm kapağı rengi kapalıyken (ya da kapak yokken) ada bu renge boyanır; panel animasyonu da aynı rengi kullanır. Varsayılan: siyah.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            key(version) { ColorPicker(draft.islandColorArgb) { draft = draft.copy(islandColorArgb = it) } }
            SectionDivider()
            SubHeader("Vurgu rengi")
            key(version) { ColorPicker(draft.accentArgb) { draft = draft.copy(accentArgb = it) } }
        }
        sec("Ekolayzır rengi", "Müzik çalarken adadaki ses çubukları") {
            SwitchRow("Albüm kapağı rengi", "Ekolayzır, çalan şarkının kapağına göre renklenir",
                draft.eqDynamicColor) { draft = draft.copy(eqDynamicColor = it) }
            SectionDivider()
            SubHeader(if (draft.eqDynamicColor) "Kapak yoksa kullanılacak renk" else "Ekolayzır rengi")
            key(version) { ColorPicker(draft.eqColorArgb) { draft = draft.copy(eqColorArgb = it) } }
        }

        sec("Albüm arka planı", "Müzik çalarken adanın içi kapak görseliyle dolar") {
            SwitchRow("Albüm kapağı arka planı", "Kapak bulanık ve hafif karartılmış olarak adanın içine yayılır",
                draft.artBgEnabled) { draft = draft.copy(artBgEnabled = it) }
            if (draft.artBgEnabled) {
                SwitchRow("Kapalı adada da göster", "Kapatırsan arka plan yalnızca açılmış adada görünür",
                    draft.artBgCompact) { draft = draft.copy(artBgCompact = it) }
            }
        }

        sec("Müzik efekti", "Müzik çalarken adanın alt kısmında hareketli renk") {
            SwitchRow("Alt kenarda renkli ışık", "Albüm kapağının rengine göre adanın altında yavaşça akan ışık; duraklatınca söner",
                draft.musicBottomFx) { draft = draft.copy(musicBottomFx = it) }
            SwitchRow("Bildirimde de renkli ışık", "Bildirim gelince adanın (ve iOS tarzı cam bannerın) alt kenarında, bildirimi gönderen uygulamanın simge renginde akan ışık",
                draft.notifBottomFx) { draft = draft.copy(notifBottomFx = it) }
        }

        sec("Özel arka plan", "Adanın içini galeriden seçtiğin fotoğraf veya videoyla doldur") {
            SwitchRow("Galeriden arka plan", "Fotoğraf adanın içini kaplar; yazıların okunması için hafif karartılır",
                draft.customBgEnabled) { draft = draft.copy(customBgEnabled = it) }
            if (draft.customBgEnabled) {
                SubHeader("Fotoğraf nereye uygulansın")
                SegmentedTabs(listOf("İkisine de aynı", "Ayrı fotoğraflar"), if (draft.customBgSeparate) 1 else 0) {
                    draft = draft.copy(customBgSeparate = it == 1)
                }
                SectionDivider()
                if (!draft.customBgSeparate) {
                    ImagePickRow("Kapalı ve açık ada", "Aynı fotoğraf iki halde de görünür",
                        CustomImages.Slot.BG_BOTH, draft.customBgBothUri, pick, remove)
                } else {
                    ImagePickRow("Kapalı ada", "Seçmezsen kapalı adada özel arka plan olmaz",
                        CustomImages.Slot.BG_COMPACT, draft.customBgCompactUri, pick, remove)
                    SectionDivider()
                    ImagePickRow("Açık ada", "Seçmezsen açık adada özel arka plan olmaz",
                        CustomImages.Slot.BG_EXPANDED, draft.customBgExpandedUri, pick, remove)
                }
                SectionDivider()
                VideoPickRow(draft.customBgVideoUri, pickVideo, removeVideo)
                SectionDivider()
                SliderRow("Karartma", draft.customBgDim, 0f..0.8f, "%${(draft.customBgDim * 100).toInt()}") { draft = draft.copy(customBgDim = it) }
                Text("Özel arka plan, albüm kapağı arka planının üstünde görünür.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }

        sec("Ada olayları", "Genişletilmiş müzik adasında hava durumu, portal sahneleri ve kırılma efektleri") {
            // Aynı ayarlar Ana Sayfa > Ada olayları'nda da var (ComboSection.kt / FxEventsControls)
            FxEventsControls(draft) { draft = it }
        }

        sec("Özel albüm kapağı", "Adanın içindeki kapak yerine galeriden fotoğraf göster") {
            SwitchRow("Galeriden kapak fotoğrafı", "Kapalı ve açık adadaki küçük albüm kapağı yerine seçtiğin fotoğraf görünür",
                draft.customArtEnabled) { draft = draft.copy(customArtEnabled = it) }
            if (draft.customArtEnabled) {
                ImagePickRow("Kapak fotoğrafı", "Her şarkıda bu fotoğraf gösterilir",
                    CustomImages.Slot.ART, draft.customArtUri, pick, remove)
                SectionDivider()
                VideoPickRow(draft.customArtVideoUri, pickArtVideo, removeArtVideo,
                    title = "Kapak videosu", hint = "Seçersen kapak fotoğrafının yerine video çalar (sessiz, döngülü).")
                Text("Fotoğraf seçilmezse gerçek albüm kapağı gösterilir. Ada rengi ve parıltılar yine çalan şarkının kapağından alınır.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }

        sec("Liquid Glass", "Kapalıyken ada düz, opak siyahtır") {
            SwitchRow("Cam efekti", null, draft.glassEnabled) { draft = draft.copy(glassEnabled = it) }
            if (draft.glassEnabled) {
                SliderRow("Şeffaflık", draft.glassAlpha, 0.10f..0.90f, "%${(draft.glassAlpha * 100).toInt()}") { draft = draft.copy(glassAlpha = it) }
                SliderRow("Arka plan bulanıklığı", draft.blurRadiusPx.toFloat(), 0f..80f, "${draft.blurRadiusPx} px") { draft = draft.copy(blurRadiusPx = it.toInt()) }
                SectionDivider()
                SwitchRow("Sıvı cam (kırılma)", "Kenarlarda içeriği büken, renk ayıran ve derinlik / parlama ekleyen gerçekçi cam",
                    draft.liquidEnabled) { draft = draft.copy(liquidEnabled = it) }
                if (draft.liquidEnabled) {
                    if (liquidRefractionSupported()) {
                        SliderRow("Kırılma", draft.liquidRefraction, 0f..1.5f, "%${(draft.liquidRefraction * 100).toInt()}") { draft = draft.copy(liquidRefraction = it) }
                        SliderRow("Renk ayrışması", draft.liquidDispersion, 0f..1f, "%${(draft.liquidDispersion * 100).toInt()}") { draft = draft.copy(liquidDispersion = it) }
                        Text("Gerçek zamanlı kırılma için ekran yakalama izni gerekir (Android her açılışta sorar; \"Tüm ekran\" seç).",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                        if (!LiquidCapture.running) {
                            Button(onClick = {
                                ctx.startActivity(android.content.Intent(ctx, CapturePermissionActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }) { Text("Gerçek zamanlı modu başlat") }
                        } else {
                            OutlinedButton(onClick = {
                                ctx.startService(android.content.Intent(ctx, ScreenCaptureService::class.java)
                                    .setAction(ScreenCaptureService.ACTION_STOP))
                            }) { Text("Gerçek zamanlı modu durdur") }
                        }
                    } else {
                        Text("Kırılma shader'ı Android 13+ gerektirir; bu cihazda yalnızca derinlik ve parlama çizilir.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                    }
                    SliderRow("Parlama", draft.liquidSpecular, 0f..1.5f, "%${(draft.liquidSpecular * 100).toInt()}") { draft = draft.copy(liquidSpecular = it) }
                }
                if (draft.windowPadDp() > 0f) {
                    Text("Not: Parıltı veya Zıplamalı animasyon açıkken bulanıklık devre dışı kalır.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }
        }

        sec("Parıltı (Glow)", "Renk, çalan şarkının kapağından alınır") {
            SwitchRow("Parıltı efekti", "Kapalı ve açık halde adanın etrafında", draft.glowEnabled) { draft = draft.copy(glowEnabled = it) }
            if (draft.glowEnabled) {
                SliderRow("Yoğunluk", draft.glowIntensity, 0.2f..1f, "%${(draft.glowIntensity * 100).toInt()}") { draft = draft.copy(glowIntensity = it) }
            }
        }

        sec("İlerleme çubuğu parıltısı", "Açık adadaki müzik panelinde, şarkı çubuğunda") {
            SwitchRow("Parıltı efekti", "Şarkı ilerleme çubuğu ışık yayar", draft.progressGlowEnabled) { draft = draft.copy(progressGlowEnabled = it) }
            if (draft.progressGlowEnabled) {
                SubHeader("Parıltı nerede olsun")
                SegmentedTabs(listOf("Tüm çubuk", "Çubuğun ucu"), if (draft.progressGlowTipOnly) 1 else 0) {
                    draft = draft.copy(progressGlowTipOnly = it == 1)
                }
                if (draft.progressGlowTipOnly) {
                    Text("Uç parıltısı her zaman çubuğun içinde kalır, dışarı taşmaz.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                } else {
                    SwitchRow("Parıltı çubuğun içinde kalsın", "Kapalıyken ışık çubuğun dışına yayılır; açıkken dışarı taşmaz",
                        draft.progressGlowInside) { draft = draft.copy(progressGlowInside = it) }
                }
                SliderRow("Yoğunluk", draft.progressGlowIntensity, 0.2f..1f, "%${(draft.progressGlowIntensity * 100).toInt()}") { draft = draft.copy(progressGlowIntensity = it) }
                SectionDivider()
                SwitchRow("Albüm kapağı rengi", "Parıltı, çalan şarkının kapağına göre değişir",
                    draft.progressGlowArtColor) { draft = draft.copy(progressGlowArtColor = it) }
                SubHeader(if (draft.progressGlowArtColor) "Kapak yoksa kullanılacak renk" else "Parıltı rengi")
                key(version) { ColorPicker(draft.progressGlowColorArgb) { draft = draft.copy(progressGlowColorArgb = it) } }
            }
        }

        sec("Beğeni düğmesi", "Açık adadaki kalp, beğenildiğinde bu renkte olur") {
            SwitchRow("Yıldız simgesi", "Müzik çalarken ada genişleyince favorilere ekleme düğmesi kalp yerine yıldız şeklinde olur (beğenince içi dolar)",
                draft.more.favStar) { draft = draft.copy(more = draft.more.copy(favStar = it)) }
            SectionDivider()
            SwitchRow("Albüm kapağı rengi", "Kalp, çalan şarkının kapağıyla aynı renkte olur",
                draft.likeArtColor) { draft = draft.copy(likeArtColor = it) }
            SubHeader(if (draft.likeArtColor) "Kapak yoksa kullanılacak renk" else "Kalp rengi")
            key(version) { ColorPicker(draft.likeColorArgb) { draft = draft.copy(likeColorArgb = it) } }
        }

        sec("Çerçeve", "İstediğin renk ya da albüm kapağı rengi") {
            SwitchRow("Çerçeve çizgisi", null, draft.borderEnabled) { draft = draft.copy(borderEnabled = it) }
            if (draft.borderEnabled) {
                SliderRow("Kalınlık", draft.borderWidthDp, 0.5f..3f, "${"%.1f".format(draft.borderWidthDp)} dp") { draft = draft.copy(borderWidthDp = it) }
                SwitchRow("Albüm kapağı rengi", "Çerçeve çizgisi, çalan şarkının kapağına göre renklenir",
                    draft.borderArtColor) { draft = draft.copy(borderArtColor = it) }
                SubHeader(if (draft.borderArtColor) "Kapak yoksa kullanılacak renk" else "Çerçeve rengi")
                key(version) { ColorPicker(draft.borderColorArgb) { draft = draft.copy(borderColorArgb = it) } }
            }
        }

        // ---- Ada süsleri -> IslandDecor.kt
        pendingGroup = "Ada süsleri"
        sec("Renkli çerçeve", "Adanın kenarında parlayan, akan renkler") {
            DecorChoices(listOf(
                "Renk geçişi" to "Seçtiğin iki renk adanın kenarında dönerek akar",
                "Gökkuşağı" to "Tüm renkler kenar boyunca akar",
                "Ateş" to "Adanın çevresinde titreyen turuncu alevler",
                "Neon" to "Tek renk, yavaşça nabız gibi parlar",
                "Elektrik" to "Kenardan kırık kırık şimşek telleri çıkar; üst kenar 1. renk, alt kenar 2. renk",
                "Mor alev" to "Adanın çevresinde dumanlı mor alevler",
                "Ses dalgası" to "Adanın çevresinde noktalı halka; noktalar ekolayzır gibi dışa doğru fışkırır",
            ), draft.neonStyle) { st ->
                // Seçilen stilin önerilen renkleri uygulanır (sonra istediğin gibi değiştirebilirsin)
                draft = when (st) {
                    1 -> draft.copy(neonStyle = st, neonColor1Argb = 0xFFFF3DCB.toInt(), neonColor2Argb = 0xFF3DFFC2.toInt())
                    4 -> draft.copy(neonStyle = st, neonColor1Argb = 0xFF8FD3FF.toInt())
                    5 -> draft.copy(neonStyle = st, neonColor1Argb = 0xFF4FA8FF.toInt(), neonColor2Argb = 0xFF9B4DFF.toInt())
                    7 -> draft.copy(neonStyle = st, neonColor1Argb = 0xFFFF5A2A.toInt(), neonColor2Argb = 0xFFC03CFF.toInt())
                    else -> draft.copy(neonStyle = st)
                }
                version++
            }
            if (draft.neonStyle != 0) {
                SliderRow("Çizgi kalınlığı", draft.neonWidthDp, 1f..6f, "${"%.1f".format(draft.neonWidthDp)} dp") { draft = draft.copy(neonWidthDp = it) }
                SliderRow("Işık şiddeti", draft.neonGlow, 0f..1.5f, "${(draft.neonGlow * 100).toInt()}%") { draft = draft.copy(neonGlow = it) }
                SliderRow("Hız", draft.neonSpeed, 0.25f..3f, "%.1fx".format(draft.neonSpeed)) { draft = draft.copy(neonSpeed = it) }
                val two = draft.neonStyle == 1 || draft.neonStyle == 5 || draft.neonStyle == 7
                if (two || draft.neonStyle == 4) {
                    SubHeader(when (draft.neonStyle) { 4 -> "Renk"; 5 -> "Üst kenar rengi"; 7 -> "Sol taraf rengi"; else -> "Birinci renk" })
                    key(version) { ColorPicker(draft.neonColor1Argb) { draft = draft.copy(neonColor1Argb = it) } }
                }
                if (two) {
                    SubHeader(when (draft.neonStyle) { 5 -> "Alt kenar rengi"; 7 -> "Sağ taraf rengi"; else -> "İkinci renk" })
                    key(version) { ColorPicker(draft.neonColor2Argb) { draft = draft.copy(neonColor2Argb = it) } }
                }
            }
        }
        sec("Adanın üstünde", "Adaya oturan küçük karakter") {
            DecorChoices(listOf(
                "Uzanan kedi" to "Kara bir kedi adanın üstüne uzanır; kuyruğunu sallar, göz kırpar",
                "Kedi kulakları" to "Adanın iki köşesinden kulaklar çıkar",
                "Ayı kulakları" to "Adanın iki köşesinde yuvarlak kulaklar",
            ), draft.decorTop) { draft = draft.copy(decorTop = it) }
        }
        sec("Adadan sarkan süs", "İplerle adanın altına asılır, hafifçe sallanır") {
            DecorChoices(listOf(
                "Salıncakta civciv" to "Adaya bağlı salıncakta sallanan civciv",
                "Yıldızlar" to null,
                "Yılbaşı süsleri" to null,
                "Kalpler" to null,
            ), draft.decorHang) { draft = draft.copy(decorHang = it) }
        }
        sec("Adanın içinde", "Boştaki adada dolaşan karakter") {
            DecorChoices(listOf(
                "Tavşan" to "Adanın içinde zıplayarak gidip gelir, çevresinde parıltılar",
                "Civciv" to null,
                "Kedi yavrusu" to null,
            ), draft.islandPet) { draft = draft.copy(islandPet = it) }
        }
        sec("Mürekkep damlası", "Adadan ekrana doğru akan koyu sıvı") {
            SwitchRow("Adadan damlasın", "Ada renginde damlalar sarkar; biri ekranın altına kadar akıp iz bırakır, sonra kaybolur", draft.dripEnabled) { draft = draft.copy(dripEnabled = it) }
            if (draft.dripEnabled) {
                SliderRow("Aralık", draft.dripEverySec, 2f..30f, "${draft.dripEverySec.toInt()} sn") { draft = draft.copy(dripEverySec = it) }
            }
        }
        sec("Ekran kenarı ışığı", "Ekranın çevresinde sürekli akan renkler") {
            SwitchRow("Her zaman açık", "Asistan animasyonundaki renkli kenar ışığı sürekli görünür", draft.edgeGlowAlways) { draft = draft.copy(edgeGlowAlways = it) }
        }

        // ---- Oyuncak ada (PlayFx.kt): her özellik ayrı sayfa, ayrı ayar
        pendingGroup = "Oyuncak ada"
        sec("Fiziksel ada", "Telefonu eğince ada sıvı gibi yana kayar") {
            SwitchRow("Fiziksel ada", "Telefonu sağa sola eğince ada o yöne kayar, kenara çarpınca hafifçe ezilip seker. Ekran kapalıyken sensör dinlenmez. Açıkken cam bulanıklığı kapanır (ada kayabilsin diye pencere büyür)",
                draft.playPhysics) { draft = draft.copy(play = draft.play.copy(playPhysics = it)) }
            if (draft.playPhysics) {
                SliderRow("En fazla kayma", draft.playPhysicsRangeDp, 4f..30f, "${draft.playPhysicsRangeDp.toInt()} dp") { draft = draft.copy(play = draft.play.copy(playPhysicsRangeDp = it)) }
                SwitchRow("Sallayınca jöle gibi titresin", "Telefonu sallayınca ada jöle gibi sarsılır", draft.playJelly) { draft = draft.copy(play = draft.play.copy(playJelly = it)) }
            }
        }
        sec("Ada evcili", "Adanın altından sarkan küçük yaratık") {
            SwitchRow("Ada evcili", "Adanın kenarına tutunup sarkar. Şarj olurken ve gece uyur, müzik çalarken dans eder, uzun süre dokunmazsan sıkılıp esner, bildirim gelince heyecanla aşağı sarkar, adaya dokununca savrulur. Ada açılınca adanın içine saklanır",
                draft.playPet) { draft = draft.copy(play = draft.play.copy(playPet = it)) }
            if (draft.playPet) {
                SwitchRow("Konuşsun", "Yanında küçük konuşma baloncukları çıkar: sabah \"Günaydın!\", pil azken \"Acıktım…\", müzikte notalar, gece \"Uyku vakti…\", bildirimde \"Yeni bildirim!\", sulayınca \"Teşekkürler!\". Ara sıra kendi kendine de konuşur",
                    draft.playPetTalk) { draft = draft.copy(play = draft.play.copy(playPetTalk = it)) }
            }
        }
        sec("Mıknatıs", "Şarj ve kulaklık bağlanınca") {
            SwitchRow("Şarj kablosunu yakalasın", "Şarja takınca ada, kabloya doğru aşağı sıvı bir damla uzatıp geri çeker",
                draft.playMagnet) { draft = draft.copy(play = draft.play.copy(playMagnet = it)) }
            if (draft.playMagnet) {
                SwitchRow("Kulaklığı çeksin", "Kulaklık bağlanınca iki yandan iki damla mıknatıs gibi hızlanarak gelip adaya yapışır", draft.playMagnetBuds) { draft = draft.copy(play = draft.play.copy(playMagnetBuds = it)) }
            }
        }
        sec("Dokununca savrulma", "Adaya dokununca rüzgâr yemiş gibi") {
            SwitchRow("Dokununca savrulma", "Adaya her dokunuşta ada rüzgâr yemiş gibi yassılaşıp titrer, kenarından toz uçuşur, evcil savrulur, gözler kısılır. Dokunmanın normal işi (müziği açmak vb.) aynen çalışır",
                draft.playPuff) { draft = draft.copy(play = draft.play.copy(playPuff = it)) }
            if (draft.playPuff) {
                SliderRow("Şiddet", draft.playPuffStrength, 0f..1f, "%${(draft.playPuffStrength * 100).toInt()}") { draft = draft.copy(play = draft.play.copy(playPuffStrength = it)) }
            }
        }
        sec("Ada yüzü", "Boştaki adada gözler") {
            SwitchRow("Ada yüzü", "Boştaki adada iki göz: göz kırpar, etrafa bakınır, telefonu eğince o yöne bakar. Şarjda ve gece uyur, pil azken yorgun görünür, bildirimden sonra sevinir, sallayınca başı döner, dokununca gözlerini kısar. \"Boştayken de göster\" açık olmalı",
                draft.playFace) { draft = draft.copy(play = draft.play.copy(playFace = it)) }
        }
        sec("Dalga", "Adadan ekrana yayılan su halkaları") {
            SwitchRow("Bildirimde dalga", "Bildirim gelince adadan ekrana halka halka su dalgası yayılır",
                draft.playRipple) { draft = draft.copy(play = draft.play.copy(playRipple = it)) }
            if (draft.playRipple) {
                SwitchRow("Dokununca da dalga", "Adaya dokununca da dalga yayılır", draft.playRippleTap) { draft = draft.copy(play = draft.play.copy(playRippleTap = it)) }
            }
        }
        sec("Pil sıvısı", "Boştaki ada pil kadar dolu bir cam kap") {
            SwitchRow("Pil sıvısı", "Boştaki adanın içi pil seviyesi kadar sıvıyla dolar (yeşil / sarı / kırmızı). Telefonu eğince sıvı çalkalanıp yana toplanır, şarjdayken kabarcıklar yükselir. \"Boştayken de göster\" açık olmalı",
                draft.playLiquid) { draft = draft.copy(play = draft.play.copy(playLiquid = it)) }
        }
        sec("Bildirim yiyen ada", "Bildirim baloncuğu uçup adaya girer") {
            SwitchRow("Bildirim yiyen ada", "Bildirim gelince ekranın kenarından renkli bir baloncuk süzülüp adaya çekilir, ada onu yutkunarak yer",
                draft.playEater) { draft = draft.copy(play = draft.play.copy(playEater = it)) }
            if (draft.playEater) {
                SwitchRow("Yedikçe doysun", "Her yediği bildirimle ada biraz şişer (en fazla 4). Adaya dokununca ya da 20 dakika sonra eski haline döner", draft.playEaterFull) { draft = draft.copy(play = draft.play.copy(playEaterFull = it)) }
            }
        }
        sec("Çekiştirme", "Adayı çekip bırak, lastik gibi fırlasın") {
            SwitchRow("Çekiştirme", "Adayı parmağınla aşağı çekince lastik gibi uzar, bırakınca geri fırlayıp sallanır. Boştaki ada da dokunuş alır: adanın hemen çevresine dokunmak durum çubuğuna geçmez",
                draft.playPull) { draft = draft.copy(play = draft.play.copy(playPull = it)) }
        }
        sec("Gün ışığı rengi", "Adanın çevresindeki hale saate göre renk değiştirir") {
            SwitchRow("Gün ışığı rengi", "Şafakta turuncu, gündüz beyaz, gün batımında pembe ve mor, gece lacivert bir hale adayı sarar. Renk dakika dakika yumuşakça geçer",
                draft.playDaylight) { draft = draft.copy(play = draft.play.copy(playDaylight = it)) }
            if (draft.playDaylight) {
                SliderRow("Parlaklık", draft.playDaylightAmt, 0.1f..1f, "%${(draft.playDaylightAmt * 100).toInt()}") { draft = draft.copy(play = draft.play.copy(playDaylightAmt = it)) }
            }
        }
        sec("Gizli oyun", "Ada Pong") {
            SwitchRow("Gizli oyun", "Boştaki adaya hızlıca 5 kez dokununca \"Ada Pong\" açılır: ada raket olur, parmağınla sağa sola kaydırıp topu kaçırmamaya çalışırsın. Adaya tek dokunuşun başka bir işi varsa (favoriler, çeviri) bu kısayol çalışmaz",
                draft.playGame) { draft = draft.copy(play = draft.play.copy(playGame = it)) }
            IslandActionButton("Oyunu şimdi aç", { IslandGameActivity.open(ctx) })
        }
        sec("Adayı fırlat", "Adayı yana savur, ekranda seksin") {
            SwitchRow("Adayı fırlat", "Boştaki adayı parmağınla hızlıca sağa ya da sola savur: ada ekrana fırlar, kenarlara çarpıp seker, yerçekimiyle düşer, sonra yaylanarak yerine döner. \"Boştayken de göster\" açık olmalı",
                draft.playFling) { draft = draft.copy(play = draft.play.copy(playFling = it)) }
        }
        sec("Ada saksısı", "Adadan sarkan, her gün sulanan bitki") {
            SwitchRow("Ada saksısı", "Adanın kenarından küçük bir sarmaşık sarkar. Her gün adaya ilk dokunuşun onu sular ve bir boy büyür. Bir gün sulamazsan yaprakları düşer, iki gün sulamazsan solar ve küçülmeye başlar. Yedi gün üst üste bakarsan çiçek açar",
                draft.playPlant) { draft = draft.copy(play = draft.play.copy(playPlant = it)) }
            if (draft.playPlant) {
                LaunchedEffect(Unit) { PlantStore.load(ctx) }
                val st = when (PlantStore.thirst) { 0 -> "bugün sulandı"; 1 -> "susadı, adaya dokun"; else -> "soldu, hemen sula" }
                Text("Boy: ${PlantStore.growth}/14 · $st", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            }
        }
        sec("Mevsim modu", "Kar, çiçek, güneş, yaprak") {
            SwitchRow("Mevsim modu", "Kışın adanın kenarında kar birikir ve kar taneleri süzülür, ilkbaharda kenarında çiçekler açar ve yapraklar uçuşur, yazın çevresinde ışıltılar parlar, sonbaharda adadan sarı-turuncu yapraklar dökülür",
                draft.playSeason) { draft = draft.copy(play = draft.play.copy(playSeason = it)) }
            if (draft.playSeason) {
                SegmentedTabs(listOf("Otomatik", "Kış", "Bahar", "Yaz", "Güz"), draft.playSeasonMode) { draft = draft.copy(play = draft.play.copy(playSeasonMode = it)) }
            }
        }
        sec("Ritim kısayolları", "Adaya ritimle dokun, uygulama açılsın") {
            SwitchRow("Ritim kısayolları", "Boştaki adaya kendi belirlediğin ritimle dokununca seçtiğin uygulama açılır. Adaya tek dokunuşun başka bir işi varsa (favoriler, çeviri) o da çalışmaya devam eder",
                draft.playRhythm) { draft = draft.copy(play = draft.play.copy(playRhythm = it)) }
            if (draft.playRhythm) {
                RhythmShortcutsEditor(draft.playRhythms) { draft = draft.copy(play = draft.play.copy(playRhythms = it)) }
            }
        }
        sec("Kutlama anları", "Konfeti zamanı") {
            SwitchRow("Kutlama anları", "Şarjda pil %100 olunca, doğum gününde ve yılbaşında adadan konfeti fışkırır, evcil dans eder, yüz sevinir",
                draft.playParty) { draft = draft.copy(play = draft.play.copy(playParty = it)) }
            if (draft.playParty) {
                val months = listOf("Ocak", "Şubat", "Mart", "Nisan", "Mayıs", "Haziran", "Temmuz", "Ağustos", "Eylül", "Ekim", "Kasım", "Aralık")
                SliderRow("Doğum günü: gün", draft.playBirthDay.toFloat(), 0f..31f, if (draft.playBirthDay == 0) "Yok" else "${draft.playBirthDay}") {
                    draft = draft.copy(play = draft.play.copy(playBirthDay = kotlin.math.round(it).toInt()))
                }
                if (draft.playBirthDay > 0) {
                    SliderRow("Doğum günü: ay", draft.playBirthMonth.toFloat(), 1f..12f, months[(draft.playBirthMonth - 1).coerceIn(0, 11)]) {
                        draft = draft.copy(play = draft.play.copy(playBirthMonth = kotlin.math.round(it).toInt().coerceIn(1, 12)))
                    }
                }
                IslandActionButton("Şimdi dene", { PlayFx.emit(PlayEvent.Party) })
            }
        }
        sec("Karalama tahtası", "Adaya kendi çizimin") {
            SwitchRow("Karalama tahtası", "Boştaki adayı basılı tutunca çizim tahtası açılır. Çizdiğin şey boştaki adada küçük bir çıkartma olarak durur. Basılı tutmanın başka bir işi varsa (favoriler, çeviri) aşağıdaki düğmeyi kullan",
                draft.playDoodle) { draft = draft.copy(play = draft.play.copy(playDoodle = it)) }
            if (draft.playDoodle) {
                LaunchedEffect(Unit) { DoodleStore.load(ctx) }
                IslandActionButton("Çiz", { IslandDoodleActivity.open(ctx) })
                if (DoodleStore.strokes.isNotEmpty()) IslandActionButton("Çizimi sil", { DoodleStore.save(ctx, emptyList()) })
            }
        }
        sec("Ada ziyaretçileri", "Gelen misafirleri yakala, albümü doldur") {
            SwitchRow("Ada ziyaretçileri", "Günde birkaç kez adanın yanına bir misafir gelir: kelebek, serçe, uğur böceği, arı; gece ateş böceği ve kayan yıldız; çok nadiren UFO ya da altın kelebek. Yaklaşık 25 saniye kalır. O sırada adaya dokunursan yakalarsın",
                draft.playVisitors) { draft = draft.copy(play = draft.play.copy(playVisitors = it)) }
            if (draft.playVisitors) {
                IslandActionButton("Şimdi bir ziyaretçi çağır", { VisitorStore.maybeVisit(ctx, force = true) })
                VisitorAlbum()
            }
        }
        sec("Salla, zar at", "Telefonu iki kez salla") {
            SwitchRow("Salla, zar at", "Telefonu kısa aralıkla iki kez sallayınca adanın altına zar düşer, yuvarlanıp sonucu gösterir. Evcil sonucu söyler",
                draft.playDice) { draft = draft.copy(play = draft.play.copy(playDice = it)) }
            if (draft.playDice) {
                SegmentedTabs(listOf("Tek zar", "İki zar", "Yazı-tura"), draft.playDiceMode) { draft = draft.copy(play = draft.play.copy(playDiceMode = it)) }
                IslandActionButton("Şimdi dene", { PlayFx.emit(PlayEvent.Dice) })
            }
        }
        sec("Nefes egzersizi", "Ada seninle nefes alır") {
            SwitchRow("Nefes egzersizi", "Başlatınca ada yavaşça şişip söner, altında \"Nefes al / Tut / Nefes ver\" yazar. Evcil de seninle nefes alır. Boştaki adayı basılı tutarak da başlatabilirsin (basılı tutmanın başka işi yoksa). Bitirmek için adaya dokun",
                draft.playBreath) { draft = draft.copy(play = draft.play.copy(playBreath = it)) }
            if (draft.playBreath) {
                SegmentedTabs(listOf("Sakin 4-4-4", "Uyku 4-7-8", "Kutu 4-4-4-4"), draft.playBreathPattern) { draft = draft.copy(play = draft.play.copy(playBreathPattern = it)) }
                SliderRow("Süre", draft.playBreathMin, 1f..10f, "${kotlin.math.round(draft.playBreathMin).toInt()} dk") {
                    draft = draft.copy(play = draft.play.copy(playBreathMin = kotlin.math.round(it)))
                }
                IslandActionButton(if (BreathSession.active) "Durdur" else "Şimdi başlat", {
                    if (BreathSession.active) BreathSession.stop() else BreathSession.start()
                }, tonal = false)
            }
        }
        sec("Gece lambası", "Gece şarjda sıcak ışık ve büyük saat") {
            SwitchRow("Gece lambası", "Saat 22:00 ile 07:00 arasında telefon şarjdayken ve ekran açıkken adanın çevresi sıcak bir ışıkla parlar, altında büyük bir saat, gün ve şarj yüzdesi görünür",
                draft.playNightLight) { draft = draft.copy(play = draft.play.copy(playNightLight = it)) }
            if (draft.playNightLight) {
                SliderRow("Parlaklık", draft.playNightAmt, 0.1f..1f, "%${(draft.playNightAmt * 100).toInt()}") { draft = draft.copy(play = draft.play.copy(playNightAmt = it)) }
            }
        }
        sec("Ada gölgesi", "Ada ekranın üstünde yüzüyormuş gibi") {
            SwitchRow("Ada gölgesi", "Adanın altında yumuşak bir gölge olur. Telefonu eğdikçe gölge kayar, ada ekranın üstünde yüzüyormuş gibi derinlik kazanır. Koyu duvar kağıtlarında daha az fark edilir",
                draft.playShadow) { draft = draft.copy(play = draft.play.copy(playShadow = it)) }
        }
        sec("Cep hissi", "Ekran açılınca ada yerine düşer") {
            SwitchRow("Cep hissi", "Ekran açılınca ada kamera deliğinden düşüp yaylanarak yerine oturur. Uzun süre boş kalınca küçülüp kamera deliğine girer, bir olay gelince fırlayıp çıkar",
                draft.playPocket) { draft = draft.copy(play = draft.play.copy(playPocket = it)) }
            if (draft.playPocket) {
                SliderRow("Deliğe girme süresi", draft.playPocketIdleSec, 4f..60f, "${draft.playPocketIdleSec.toInt()} sn") { draft = draft.copy(play = draft.play.copy(playPocketIdleSec = it)) }
            }
        }

        // ---- Hareket
        pendingGroup = "Hareket"
        sec("Açılış animasyonu stili", "Bildirime ya da müzik uygulamasına dokununca") {
            SwitchRow("Klasik", "Adadan siyah ekran çıkıp ekranı kaplar",
                draft.launchClassic) { draft = draft.copy(launchClassic = it, launchAtomic = if (!it) true else draft.launchAtomic) }
            SwitchRow("Atom ada", "Adadan açılan siyah kart huni gibi genişleyip ekranı kaplar, ardından uygulama açılır (OriginOS tarzı)",
                draft.launchAtomic) { draft = draft.copy(launchAtomic = it, launchClassic = if (!it) true else draft.launchClassic) }
            SliderRow("Hız", draft.launchSpeed, 0.5f..3f, "%.1fx".format(draft.launchSpeed)) { draft = draft.copy(launchSpeed = it) }
            Text("İkisi de açıksa her açılışta rastgele biri oynar. Hız, hem klasik hem atom ada için geçerlidir (bildirim ve müzik uygulaması açılışı). Bildirim animasyonunun açık olması gerekir.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }

        sec("Hap tasarımları", "Arama ve müzik adası için alternatif görünüm", PreviewKind.PillStyle) {
            SwitchRow("Arama hapı", "KÜÇÜLTÜLMÜŞ adada: solda yeşil, sağda kırmızı yuvarlak düğme; ortada süre / isim ve hareketli ses dalgası. Genişletilmiş görünüm değişmez. Aktif aramada yeşil düğme hoparlörü açıp kapatır",
                draft.pillCallStyle) { draft = draft.copy(pillCallStyle = it) }
            SwitchRow("Müzik hapı", "KÜÇÜLTÜLMÜŞ adada: solda albüm kapağı, ortada şarkı adı, renkli dalga ve ilerleme çubuğu, sağda önceki / duraklat / sonraki düğmeleri. Genişletilmiş panel değişmez",
                draft.pillMusicStyle) { draft = draft.copy(pillMusicStyle = it) }
            SwitchRow("Orta boy müzik adası", "Müzik çalarken ada küçülmez; iOS'taki gibi ne çok küçük ne çok büyük, orta boyda açık kalır: üstte kapak, şarkı adı ve dalga, ortada ilerleme çubuğu, altta beğeni, geri, oynat / duraklat, ileri ve çıkış düğmeleri. Boş yerine dokununca tam boy panel açılır. Açıkken müzik hapı yerine bu görünüm kullanılır",
                draft.more.musicMid) { draft = draft.copy(more = draft.more.copy(musicMid = it)) }
            if (draft.more.musicMid) {
                SliderRow("Orta boy genişlik", draft.more.musicMidWDp, 260f..400f, "${draft.more.musicMidWDp.toInt()} dp") { draft = draft.copy(more = draft.more.copy(musicMidWDp = it)) }
                SliderRow("Orta boy yükseklik", draft.more.musicMidHDp, 140f..220f, "${draft.more.musicMidHDp.toInt()} dp") { draft = draft.copy(more = draft.more.copy(musicMidHDp = it)) }
                SectionDivider()
            }
            SwitchRow("Adanın boyutuna uydur", "Açıkken ada büyümez: hap tasarımları (arama ve müzik) ayarladığın küçültülmüş ada boyutuna sığacak şekilde ölçeklenir. Kapalıyken ada hap boyutuna (müzikte ~344×100 dp) büyür. Kapalı ada küçükse (varsayılan 120×32) yazılar küçük kalır; okunaklı olması için adanın genişlik / yüksekliğini artır",
                draft.pillFitIsland) { draft = draft.copy(pillFitIsland = it) }
            if (draft.pillMusicStyle) {
                SectionDivider()
                SubHeader("Müzik hapı boyutu")
                if (draft.pillFitIsland) {
                    Text("Bu boyutlar \"Adanın boyutuna uydur\" kapalıyken geçerlidir. Açıkken hap, ayarladığın küçültülmüş ada boyutuna sığar.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
                SliderRow("Genişlik", draft.pillMusicWDp, 220f..400f, "${draft.pillMusicWDp.toInt()} dp") { draft = draft.copy(pillMusicWDp = it) }
                SliderRow("Yükseklik", draft.pillMusicHDp, 70f..200f, "${draft.pillMusicHDp.toInt()} dp") { draft = draft.copy(pillMusicHDp = it) }
                SectionDivider()
                SwitchRow("Albüm kapağına göre renk", "Dalga renkleri çalan şarkının kapağından türetilir; şarkı değişince renk de değişir",
                    draft.pillWaveArtColor) { draft = draft.copy(pillWaveArtColor = it) }
                if (draft.pillWaveArtColor) SubHeader("Kapak yoksa kullanılacak renkler")
                SubHeader("Sol dalga")
                key(version) { ColorPicker(draft.pillWaveLeftArgb) { draft = draft.copy(pillWaveLeftArgb = it) } }
                SubHeader("Orta dalga")
                key(version) { ColorPicker(draft.pillWaveMidArgb) { draft = draft.copy(pillWaveMidArgb = it) } }
                SubHeader("Sağ dalga")
                key(version) { ColorPicker(draft.pillWaveRightArgb) { draft = draft.copy(pillWaveRightArgb = it) } }
            }
        }

        sec("Animasyon", "Açılma ve küçülme geçişi") {
            SwitchRow("Animasyonlar", "Kapatırsan ada anında açılır/kapanır", draft.animEnabled) { draft = draft.copy(animEnabled = it) }
            if (draft.animEnabled) {
                SwitchRow("Müzik adası animasyonu", "Müzik çalarken ada genişlerken içerik bulanıktan netleşerek belirir; küçülürken bunun tersi olur (iOS tarzı)",
                    draft.musicBlurAnim) { draft = draft.copy(musicBlurAnim = it) }
                SwitchRow("Albüm kapağı dönme animasyonu", "Ada açılırken kapak bir kez 360° döner, küçülürken ters yöne döner. Şarkı değişince kapak dönerek değişir (sonraki: bir yöne, önceki: ters yöne)",
                    draft.musicArtFlip) { draft = draft.copy(musicArtFlip = it) }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ANIM_STYLES.forEachIndexed { i, (name, desc) ->
                        RadioRow(name, desc, draft.animStyle == i) { draft = draft.copy(animStyle = i) }
                    }
                }
            }
        }

        sec("Kilit ekranı noktası", "Telefon kilitliyken adanın yanında yanıp sönen renkli nokta") {
            SwitchRow("Kilit ekranı noktası", "Yalnızca ekran açık ve kilitliyken görünür; kilit açılınca ya da ekran kapanınca kaybolur",
                draft.lockDotEnabled) { draft = draft.copy(lockDotEnabled = it) }
            if (draft.lockDotEnabled) {
                LockDotPreview(draft)
                SubHeader("Nokta")
                key(version) { ColorPicker(draft.lockDotColorArgb) { draft = draft.copy(lockDotColorArgb = it) } }
                SliderRow("Boyut", draft.lockDotSizeDp, 4f..30f, "${draft.lockDotSizeDp.toInt()} dp") { draft = draft.copy(lockDotSizeDp = kotlin.math.round(it)) }
                SliderRow("Adaya uzaklık (sol, eksi = adanın içi)", draft.lockDotGapDp, -60f..80f, "${draft.lockDotGapDp.toInt()} dp") { draft = draft.copy(lockDotGapDp = kotlin.math.round(it)) }
                SliderRow("Dikey konum", draft.lockDotOffsetYDp, -20f..20f, "${draft.lockDotOffsetYDp.toInt()} dp") { draft = draft.copy(lockDotOffsetYDp = kotlin.math.round(it)) }
                SectionDivider()
                SubHeader("Parıltı (glow)")
                SwitchRow("Parıltı", "Noktanın çevresinde yumuşak ışık halesi", draft.lockDotGlow) { draft = draft.copy(lockDotGlow = it) }
                if (draft.lockDotGlow) {
                    SliderRow("Parıltı büyüklüğü", draft.lockDotGlowDp, 2f..40f, "${draft.lockDotGlowDp.toInt()} dp") { draft = draft.copy(lockDotGlowDp = kotlin.math.round(it)) }
                    SliderRow("Parıltı şiddeti", draft.lockDotGlowAlpha, 0.1f..1f, "%${(draft.lockDotGlowAlpha * 100).toInt()}") { draft = draft.copy(lockDotGlowAlpha = it) }
                }
                SectionDivider()
                SubHeader("Yanıp sönme")
                SwitchRow("Yanıp sönsün", "Kapalıysa nokta sabit yanar", draft.lockDotBlink) { draft = draft.copy(lockDotBlink = it) }
                if (draft.lockDotBlink) {
                    SegmentedTabs(listOf("Yumuşak", "Sert"), draft.lockDotBlinkStyle) { draft = draft.copy(lockDotBlinkStyle = it) }
                    SliderRow("Tur süresi (küçük = hızlı)", draft.lockDotBlinkMs.toFloat(), 150f..2000f, "${draft.lockDotBlinkMs} ms") {
                        draft = draft.copy(lockDotBlinkMs = (kotlin.math.round(it / 10f) * 10f).toInt())
                    }
                    SliderRow("Sönükken parlaklık", draft.lockDotMinAlpha, 0f..0.8f, "%${(draft.lockDotMinAlpha * 100).toInt()}") { draft = draft.copy(lockDotMinAlpha = it) }
                }
                Text("Nokta, erişilebilirlik servisi açıkken kilit ekranının üstünde görünür. Servis kapalıysa bazı telefonlarda kilit ekranında görünmeyebilir.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }

        sec("Fener (flaş) göstergesi", "Fener açılınca adanın solunda simge çıkar; dokununca ışık kartı açılır") {
            SwitchRow("Fener göstergesi", "Simgeye dokun: kart açılır. Yukarı/aşağı kaydır: parlaklık (telefon kademeli flaşı destekliyorsa gerçek flaş seviyesi de değişir). Sağa/sola kaydır: ışık huzmesi genişliği. Alttaki fener simgesine dokun: feneri kapatır",
                draft.torchIndicator) { draft = draft.copy(torchIndicator = it) }
        }

        sec("Kilit ekranı Face ID animasyonu", "Telefon kilitliyken ada kareye dönüşüp Face ID taraması ve ✓ onayı oynatır") {
            SwitchRow("Face ID animasyonu", "Ekran açılıp telefon kilitliyken bir kez oynar: ada kare olur, yüz atar, halka 3B döner, ✓ çizilir, ada hap şekline döner. Kilit açılırsa ✓ aşamasına hızlanır. Animasyon sırasında kilit noktası gizlenir",
                draft.faceIdEnabled) { draft = draft.copy(faceIdEnabled = it) }
            if (draft.faceIdEnabled) {
                FaceIdPreview(draft)
                SubHeader("Renk")
                key(version) { ColorPicker(draft.faceIdColorArgb) { draft = draft.copy(faceIdColorArgb = it) } }
                SubHeader("Boyut ve konum")
                SliderRow("Kare boyutu", draft.faceIdSizeDp, 90f..180f, "${draft.faceIdSizeDp.toInt()} dp") { draft = draft.copy(faceIdSizeDp = kotlin.math.round(it)) }
                SliderRow("Dikey konum", draft.faceIdOffsetYDp, -20f..40f, "${draft.faceIdOffsetYDp.toInt()} dp") { draft = draft.copy(faceIdOffsetYDp = kotlin.math.round(it)) }
                SubHeader("Hız")
                SliderRow("Animasyon hızı (1x = ekran kaydı)", draft.faceIdSpeed, 0.5f..2f, "%.1fx".format(draft.faceIdSpeed)) { draft = draft.copy(faceIdSpeed = kotlin.math.round(it * 10f) / 10f) }
                Text("Ada, erişilebilirlik servisi açıkken kilit ekranının üstünde görünür. Servis kapalıysa bazı telefonlarda kilit ekranında görünmeyebilir.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }

        // ---- Renk düzenleyici: uygulamadaki her rengi tek tek ayarlama
        pendingGroup = "Renk düzenleyici"
        sec("Renk düzenleyici", "Uygulamadaki her şeyin rengini tek tek ayarla") {
            ColorEditorMain(draft) { draft = it }
        }
        if (draft.colorEditorEnabled) {
            sec("Renk: Ada ve vurgu renkleri", "Ayarlarda zaten bulunan renkler: ada gövdesi, vurgu, ekolayzır, kalp, çerçeve, kilit noktası, sözler") {
                ColorFieldEditor(draft) { draft = it }
            }
            ColorGroups.all.forEach { g ->
                sec("Renk: " + g.title, g.subtitle) { ColorGroupEditor(g, draft) { draft = it } }
            }
        }

        // ---- Ses
        pendingGroup = "Ses"
        sec("Ses efektleri", "Adada olaylara göre kendi seçtiğin sesler çalsın") {
            SwitchRow("Ses efektleri", "Kapalıyken hiçbir ses çalmaz; seçtiğin sesler ve ayarlar silinmez",
                draft.sfxEnabled) { draft = draft.copy(sfxEnabled = it) }
            Text("Her olay için ayrı bir sayfa var: ses seç, yüksekliğini ve çalacak bölümünü ayarla. Ses dosyaları uygulamaya kopyalanmaz, yalnızca adresi tutulur; izin istenmez, kayıt (log) tutulmaz. Dosyayı silersen ya da taşırsan o ses çalmaz. Sesler medya ses düzeyiyle çalar, çalan müziği durdurmaz.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
        SoundEvent.values().forEach { ev ->
            sec(ev.title, ev.hint) { SoundCueEditor(ev, draft.cue(ev), pickSfx, removeSfx) { draft = draft.withCue(ev, it) } }
        }

        // ---- Uygulama
        pendingGroup = "Uygulama"
        sec("Uygulamaya özel gizleme", "Seçtiğin uygulamalar ön plandayken ada gizlenir") {
            SwitchRow("Seçili uygulamalarda gizle", "Oyun, kamera ya da video uygulaması gibi adanın görünmesini istemediğin uygulamaları seç. Ada gizliyken dokunuşları da engellemez",
                draft.hideInApps) { draft = draft.copy(hideInApps = it) }
            if (draft.hideInApps) {
                HiddenAppsPicker(draft.hiddenAppList()) { draft = draft.copy(hiddenAppPkgs = it.joinToString(",")) }
            }
        }
        sec("Yedekleme", "Ayarlarını dosyaya kaydet ya da dosyadan geri yükle") {
            IslandActionButton("Ayarları dışa aktar", { exportLauncher.launch("omni-island-ayarlar.json") })
            IslandActionButton("Yedekten geri yükle", { importLauncher.launch(arrayOf("*/*")) })
            Text("Galeriden seçilen fotoğraflar (özel arka plan / kapak) yedeğe girmez; yeni cihazda yeniden seçmen gerekir.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
        sec("Dokunsal geri bildirim", "Ayar ekranındaki anahtar ve kaydırıcılar") {
            SwitchRow("Titreşim", "Anahtarı çevirince ve kaydırıcıyı sürüklerken hafif titreşim verir", draft.uiHaptics) { draft = draft.copy(uiHaptics = it) }
        }

        page("Sıfırla", subtitle = "Tüm ayarları başlangıç değerine döndür") {
            SectionCard("Sıfırla", "Tüm ayarları başlangıç değerine döndür") {
                IslandActionButton("Varsayılanlara dön", { CustomImages.releaseAll(ctx, draft); draft = IslandSettings(); version++ })
            }
        }
    }

    // Pager durumu burada tutulur: görünen sayfanın türüne göre üstteki önizleme değişir
    val pager = rememberPagerState { pages.size }
    val pageKind = if (lyricsPage) PreviewKind.Island else (pages.getOrNull(pager.currentPage)?.kind ?: PreviewKind.Island)
    // Sayfaya göre önizleme otomatik doğru hale gelir (örn. ilerleme çubuğu için müzik + açık ada); sonra dokunarak değiştirilebilir
    val curTitle = pages.getOrNull(pager.currentPage)?.title
    // Özellik sahneleri (FeaturePreviews.kt): sayfadaki özelliğin kendisi telefonda oynar.
    // Aynı sayfada birden çok özellik varsa (ör. Konum: ters ada + yatay mod) en son açılan gösterilir.
    var featFocus by remember { mutableStateOf<Feat?>(null) }
    var prevDraft by remember { mutableStateOf(draft) }
    LaunchedEffect(draft) {
        justEnabledFeature(curTitle, prevDraft, draft)?.let { featFocus = it }
        prevDraft = draft
    }
    LaunchedEffect(pager.currentPage) { featFocus = null }
    val feat = resolveFeature(curTitle, draft, featFocus)     // Sözler sekmesinde de (ör. Müzikten çıkış)
    val kind = if (feat != null) PreviewKind.Feature else pageKind
    LaunchedEffect(pager.currentPage, lyricsPage) {
        if (lyricsPage) return@LaunchedEffect
        previewFocusFor(curTitle)?.let { f ->
            f.mode?.let { mode = it }
            f.expanded?.let { expanded = it }
        }
    }
    val sidePrefs by SideCallStore.flow(ctx).collectAsState()
    // Geniş ayarlar (tam genişlik asistan, kenar tuşları) için tam telefon gerekir
    val needsBig = when (kind) {
        PreviewKind.Assistant -> draft.assistantEnabled && draft.assistantFullWidth
        PreviewKind.SideCall -> sidePrefs.enabled
        PreviewKind.Feature -> feat?.big == true
        else -> false
    }
    var bigDismissed by remember { mutableStateOf(false) }
    LaunchedEffect(kind, feat) { bigDismissed = false }
    val showBig = needsBig && !bigDismissed
    val hint = if (feat != null) featureHint(feat) else previewHint(kind, draft, bigDismissed)

    // Tam telefon gerektiren ayarlarda önizleme kartı telefonun tamamını gösterecek şekilde büyür
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // ---- Sabit canlı önizleme (kaydırırken de görünür kalır); arama satırındaki göz tuşuyla gizlenebilir
            val uiPrefs = remember { ctx.getSharedPreferences("settings_ui", android.content.Context.MODE_PRIVATE) }
            var previewHidden by remember { mutableStateOf(uiPrefs.getBoolean("preview_hidden", false)) }
            if (!previewHidden) PreviewCard(
                draft.effective(), mode, { mode = it }, expanded, { expanded = !expanded },
                kind = kind, hint = hint, showBig = showBig, feat = feat,
                onSmallTap = { if (needsBig) bigDismissed = false }, onBigTap = { bigDismissed = true },
            )

            // ---- Ayar arama: başlık / açıklama / grup adına göre sayfa bulur, dokununca o sayfaya gider
            var query by remember { mutableStateOf("") }
            var goTo by remember { mutableIntStateOf(-1) }
            // Sağ üstteki üç çizgi: tüm ayar sayfalarını alt alta listeler
            var menuOpen by remember { mutableStateOf(false) }
            val cs0 = MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it; if (it.isNotEmpty()) menuOpen = false }, Modifier.weight(1f))
                // Önizlemeyi gizle / göster
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (previewHidden) cs0.primary else cs0.surfaceVariant)
                        .clickable(role = Role.Button) {
                            previewHidden = !previewHidden
                            uiPrefs.edit().putBoolean("preview_hidden", previewHidden).apply()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val ic = if (previewHidden) cs0.onPrimary else cs0.onSurfaceVariant
                    androidx.compose.foundation.Canvas(Modifier.size(20.dp)) {
                        val w = size.width; val h = size.height
                        val st = androidx.compose.ui.graphics.drawscope.Stroke(w * 0.09f, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                        val eye = androidx.compose.ui.graphics.Path().apply {
                            moveTo(w * 0.04f, h * 0.5f)
                            quadraticBezierTo(w * 0.5f, h * 0.02f, w * 0.96f, h * 0.5f)
                            quadraticBezierTo(w * 0.5f, h * 0.98f, w * 0.04f, h * 0.5f)
                            close()
                        }
                        drawPath(eye, ic, style = st)
                        drawCircle(ic, w * 0.13f, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f))
                        // Gizliyken üstü çizili göz
                        if (previewHidden) drawLine(ic, androidx.compose.ui.geometry.Offset(w * 0.12f, h * 0.1f), androidx.compose.ui.geometry.Offset(w * 0.88f, h * 0.9f), w * 0.09f, androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                }
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (menuOpen) cs0.primary else cs0.surfaceVariant)
                        .clickable(role = Role.Button) { menuOpen = !menuOpen; query = "" },
                    contentAlignment = Alignment.Center,
                ) {
                    val bar = if (menuOpen) cs0.onPrimary else cs0.onSurfaceVariant
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        repeat(3) { Box(Modifier.width(18.dp).height(2.dp).clip(RoundedCornerShape(1.dp)).background(bar)) }
                    }
                }
            }
            if (query.isNotBlank()) SearchResults(pages, query, Modifier.weight(1f)) { idx -> goTo = idx; query = "" }
            else if (menuOpen) SearchResults(pages, "", Modifier.weight(1f)) { idx -> goTo = idx; menuOpen = false }
            else SnapSettingsPager(pages, pager, Modifier.weight(1f))
            LaunchedEffect(goTo, query) {
                if (goTo >= 0 && query.isBlank()) { pager.scrollToPage(goTo); goTo = -1 }
            }
        }
    }
}

// ---------------------------------------------------------------- Sayfaya göre önizleme odağı
/** [mode]: 0 bildirim, 1 müzik; [expanded]: ada açık mı. null = olduğu gibi bırak. */
private class PreviewFocus(val mode: Int? = null, val expanded: Boolean? = null)

private fun previewFocusFor(title: String?): PreviewFocus? = when (title) {
    "Kapalı ada", "Konum", "Görünürlük", "Nokta modu" -> PreviewFocus(expanded = false)
    "Açık ada" -> PreviewFocus(mode = 1, expanded = true)
    "Bildirimler" -> PreviewFocus(mode = 0, expanded = true)
    "Gökkuşağı modu", "Albüm arka planı", "Özel arka plan", "Özel albüm kapağı", "Liquid Glass",
    "İlerleme çubuğu parıltısı", "Beğeni düğmesi", "Çerçeve" -> PreviewFocus(mode = 1, expanded = true)
    "Renk", "Ekolayzır rengi", "Parıltı (Glow)" -> PreviewFocus(mode = 1, expanded = false)
    else -> if (title != null && title.startsWith("Renk: ")) PreviewFocus(mode = if (title.contains("banner")) 0 else 1, expanded = true) else null
}

// ---------------------------------------------------------------- Yapışan (snap) ayar sayfaları
private class SettingsPage(val title: String, val group: String?, val kind: PreviewKind, val subtitle: String?, val body: @Composable () -> Unit)

private class PageBuilder {
    val list = mutableListOf<SettingsPage>()
    var pendingGroup: String? = null
    fun page(title: String, kind: PreviewKind = PreviewKind.Island, subtitle: String? = null, body: @Composable () -> Unit) {
        list.add(SettingsPage(title, pendingGroup, kind, subtitle, body)); pendingGroup = null
    }
    fun sec(title: String, subtitle: String? = null, kind: PreviewKind = PreviewKind.Island, content: @Composable ColumnScope.() -> Unit) =
        page(title, kind, subtitle) { SectionCard(title, subtitle, content) }
}

private inline fun buildPages(block: PageBuilder.() -> Unit): List<SettingsPage> = PageBuilder().apply(block).list

/** Arama sonuçları: eşleşen ayar sayfaları; dokununca ilgili sayfaya gidilir. */
@Composable
private fun SearchResults(pages: List<SettingsPage>, query: String, modifier: Modifier, onGo: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tr = remember { java.util.Locale("tr", "TR") }
    val words = query.trim().lowercase(tr).split(' ').filter { it.isNotEmpty() }
    // Grup adı yalnızca grubun ilk sayfasında kayıtlı: sonraki sayfalara taşınır
    var group = ""
    val rows = pages.mapIndexedNotNull { i, p ->
        if (p.group != null) group = p.group
        val hay = "${p.title} ${p.subtitle.orEmpty()} $group".lowercase(tr)
        if (words.all { hay.contains(it) }) Triple(i, p, group) else null
    }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (rows.isEmpty()) {
            Text("Sonuç yok", color = cs.onSurfaceVariant, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
        rows.forEach { (i, p, g) ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(cs.surface)
                    .clickable(role = Role.Button) { onGo(i) }.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (g.isNotEmpty()) Text(g, color = cs.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(p.title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (!p.subtitle.isNullOrBlank()) Text(p.subtitle, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp, maxLines = 2)
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

/** Üstte/altta komşu ayarların yalnızca başlığı görünür. Sayfa değiştirmek için biraz daha fazla kaydırmak gerekir. */
@Composable
private fun SnapSettingsPager(pages: List<SettingsPage>, pager: PagerState, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // Eşik: sayfanın %50'si (zorlaştırmak için 0.6f, kolaylaştırmak için 0.4f yap)
    val fling = PagerDefaults.flingBehavior(
        state = pager,
        pagerSnapDistance = PagerSnapDistance.atMost(1),
        snapPositionalThreshold = 0.5f,
    )
    val cur = pager.currentPage

    @Composable
    fun Neighbor(index: Int, alignBottom: Boolean) {
        Box(
            Modifier.fillMaxWidth().height(44.dp)
                .clickable(enabled = index in pages.indices, role = Role.Button) {
                    scope.launch { pager.animateScrollToPage(index) }
                }
                .padding(horizontal = 8.dp),
            contentAlignment = if (alignBottom) Alignment.BottomStart else Alignment.TopStart,
        ) {
            if (index in pages.indices) {
                Text(
                    pages[index].title, color = cs.onSurfaceVariant.copy(alpha = 0.7f),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
            }
        }
    }

    Column(modifier) {
        Crossfade(cur - 1, animationSpec = tween(180), label = "prevTitle") { Neighbor(it, alignBottom = true) }
        VerticalPager(
            state = pager,
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds(),
            flingBehavior = fling,
            beyondViewportPageCount = 0,
            key = { it },
        ) { i ->
            val page = pages[i]
            // Sayfa ekrandan uzunsa kendi içinde kayar; sonuna gelince bir sonraki sayfaya geçilir
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                page.group?.let { GroupLabel(it) }
                page.body()
                Spacer(Modifier.height(8.dp))
            }
        }
        Crossfade(cur + 1, animationSpec = tween(180), label = "nextTitle") { Neighbor(it, alignBottom = false) }
    }
}


// ---------------------------------------------------------------- Fotoğraf seçme satırı
@Composable
private fun ImagePickRow(
    title: String, desc: String, slot: CustomImages.Slot, uri: String,
    onPick: (CustomImages.Slot) -> Unit, onRemove: (CustomImages.Slot) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val img = rememberCustomImage(slot, uri)
    val has = uri.isNotEmpty()
    val thumbShape = RoundedCornerShape(12.dp)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                Modifier.size(width = 72.dp, height = 56.dp).clip(thumbShape)
                    .background(cs.surfaceVariant).border(1.dp, cs.outlineVariant, thumbShape),
                contentAlignment = Alignment.Center,
            ) {
                if (img != null) {
                    Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text("Yok", color = cs.onSurfaceVariant, fontSize = 12.sp)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(if (!has) desc else if (img == null) "Fotoğraf açılamıyor (silinmiş veya taşınmış olabilir)" else "Seçildi", color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = { onPick(slot) }, modifier = Modifier.weight(1f)) { Text(if (has) "Değiştir" else "Galeriden seç") }
            if (has) OutlinedButton(onClick = { onRemove(slot) }, modifier = Modifier.weight(1f)) { Text("Kaldır") }
        }
    }
}

// ---------------------------------------------------------------- Video seçme satırı
@Composable
private fun VideoPickRow(
    uri: String, onPick: () -> Unit, onRemove: () -> Unit,
    title: String = "Video arka plan", hint: String = "Seçersen fotoğraf yerine video çalar. Pil tüketimini artırabilir.",
) {
    val cs = MaterialTheme.colorScheme
    val has = uri.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(
                if (has) "Video seçildi (sessiz, döngülü). Silinir veya taşınırsa kaybolur." else hint,
                color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onPick, modifier = Modifier.weight(1f)) { Text(if (has) "Videoyu değiştir" else "Video seç") }
            if (has) OutlinedButton(onClick = onRemove, modifier = Modifier.weight(1f)) { Text("Kaldır") }
        }
    }
}

// ---------------------------------------------------------------- Önizleme
internal const val SAMPLE_COLOR = 0xFFFF5F9E.toInt()

internal fun sampleArt(): ImageBitmap {
    val bmp = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bmp)
    val paint = android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 64f, 64f, SAMPLE_COLOR, 0xFF7B5CFF.toInt(), android.graphics.Shader.TileMode.CLAMP)
    }
    canvas.drawRect(0f, 0f, 64f, 64f, paint)
    return bmp.asImageBitmap()
}

private val SAMPLE_LINES = listOf(
    LyricLine(0, "Gecenin sessizliğinde yürürken"),
    LyricLine(3500, "Yıldızlar yolumu aydınlatıyor"),
    LyricLine(7000, "Her adımda biraz daha yaklaşıyorum"),
    LyricLine(10500, "Bu şarkı hiç bitmesin istiyorum"),
)
private val SAMPLE_STATE = LyricsState.Ready(SAMPLE_LINES, true, "örnek")
private const val SAMPLE_LOOP = 14_000L

// Telefon maketi ölçüleri. İçerik 412 dp genişliğinde "sanal" bir ekranda gerçek dp değerleriyle çizilir,
// sonra yoğunluk (density) küçültülerek maket ekrana sığdırılır; böylece ada ayarları birebir orantılı görünür.
internal const val PHONE_W_DP = 236f
internal const val PHONE_BEZEL_DP = 6f
internal const val PHONE_VIRTUAL_W_DP = 412f
internal const val PHONE_BASE_TOP_DP = 14f      // sanal ekranda adanın varsayılan üst boşluğu
private val PHONE_SCALE = (PHONE_W_DP - 2f * PHONE_BEZEL_DP) / PHONE_VIRTUAL_W_DP

@Composable
private fun PreviewCard(
    cfg: IslandSettings, mode: Int, onMode: (Int) -> Unit, expanded: Boolean, onToggle: () -> Unit,
    kind: PreviewKind = PreviewKind.Island, hint: String = "", showBig: Boolean = false,
    onSmallTap: () -> Unit = {}, onBigTap: () -> Unit = {},
    feat: Feat? = null,
) {
    val art = remember { sampleArt() }
    val music = mode == 1
    val sample = remember(mode) {
        if (mode == 0) IslandContent.Notif("x", "Ayşe", "Akşam yemeğe geliyor musun?")
        else IslandContent.Media("Örnek Şarkı", "Sanatçı", true, 210_000, 75_000, 1f, SystemClock.elapsedRealtime(), art = art, artColor = SAMPLE_COLOR)
    }
    val compactLy = music && cfg.lyricsEnabled && cfg.lyricsCompact
    val frame = rememberLyricFrame(
        sample as? IslandContent.Media, SAMPLE_STATE, cfg.lyricsOffsetMs.toLong(),
        active = music && cfg.lyricsEnabled, fine = cfg.lyricsFillEnabled, loopMs = SAMPLE_LOOP,
    )
    val progress = rememberIslandProgress(cfg, expanded)
    // Hap tasarımı açıksa küçültülmüş ada ekran görüntüsündeki gibi büyük hap olur (genişletilmiş panel değişmez)
    val geo = islandGeo(cfg, music, compactLy).let { g ->
        pillCompactSizeDp(cfg, sample)?.let { (w, h) -> g.copy(cw = w.dp, ch = h.dp) } ?: g
    }
    val showOutside = music && !expanded && cfg.lyricsEnabled && cfg.lyricsOutside

    // Konum ayarları (Yatay / Dikey) telefondaki adaya da uygulanır
    val islandTopV = (PHONE_BASE_TOP_DP + cfg.offsetYDp).coerceAtLeast(0f)
    val ehForKind = when (kind) {
        PreviewKind.Island -> geo.eh.value
        PreviewKind.Assistant -> cfg.assistantHeightDp
        PreviewKind.Favorites -> cfg.favoritesHeightDp(cfg.favoritePkgList().size)
        PreviewKind.SideCall -> 64f
        PreviewKind.Privacy -> 60f
        PreviewKind.ScreenRec -> 74f
        PreviewKind.SideBubbles -> cfg.expandedHeightDp
        PreviewKind.PillStyle -> cfg.pillMusicHDp
        PreviewKind.Feature -> if (feat != null) featureNeedDp(feat, cfg) else geo.eh.value
    }
    // 3 boyutlu telefon: normal modda büyük çizilip üst kısmı gösterilir, tam modda tamamı karta sığar
    // Yatay mod: telefon 90° döner (kamera solda); yan yatınca karta sığacak boyda çizilir
    val land = feat?.rotated() == true
    val phoneWDp = if (land) LAND_PHONE_W_DP else if (showBig) p3dFullWidthDp() else P3D_W_DP
    val phoneTopDp = if (land) 0f else if (showBig) P3D_FULL_PAD_DP else P3D_TOP_DP
    val openNeedDp = (islandTopV + ehForKind + 14f) * p3dScale(P3D_W_DP) + P3D_TOP_DP + p3dScreenInsetDp(P3D_W_DP) + 12f
    val boxH by animateDpAsState(
        (if (showBig || land) P3D_FULL_H_DP
        else (if (expanded || kind != PreviewKind.Island) openNeedDp else 150f).coerceAtLeast(150f)).dp,
        tween(320, easing = FastOutSlowInEasing), label = "previewH",
    )
    val cardShape = RoundedCornerShape(28.dp)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(boxH).clip(cardShape)
                .background(Brush.linearGradient(listOf(Pal.c("preview_card_a"), Pal.c("preview_card_b"), Pal.c("preview_card_c"))))
                .border(1.dp, Pal.c("art_border"), cardShape)
                .then(
                    // Asistan / Favoriler / Kenar tuşları: telefona dokununca tam <-> üst kısım geçişi
                    if (kind != PreviewKind.Island) {
                        Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (showBig) onBigTap() else onSmallTap()
                        }
                    } else Modifier,
                ),
            contentAlignment = Alignment.TopCenter,
        ) {
            Phone3DPreview(
                phoneWDp, landscape = land, modifier =
                Modifier.padding(top = phoneTopDp.dp).then(
                    if (land) Modifier.graphicsLayer {
                        rotationZ = -90f
                        // Döndürme kartın ortasından olur; telefonun boyu karttan uzun olduğu için yatayda ortalanır
                        translationX = -((phoneWDp * PHONE3D_ASPECT - P3D_FULL_H_DP) / 2f).dp.toPx()
                    } else Modifier,
                ),
            ) {
                if (kind == PreviewKind.Feature && feat != null) {
                    FeatureScene(feat, cfg)
                } else if (kind != PreviewKind.Island) {
                    // Telefon o ayarın sahnesini oynatır (gerçek ada / asistan / favoriler / kenar tuşları)
                    PreviewScene(kind, cfg)
                } else {
                    Box(Modifier.fillMaxSize()) {
                        if (showOutside) {
                            OutsideLyricText(
                                cfg, SAMPLE_STATE, frame,
                                Modifier.align(Alignment.TopCenter)
                                    .offset(x = cfg.offsetXDp.dp, y = (islandTopV + geo.ch.value + 10f).dp)
                                    .width(cfg.lyricsOutsideWidthDp.dp.coerceAtMost(320.dp)),
                                artColor = SAMPLE_COLOR,
                            )
                        }
                        // Üç nokta modu önizlemesi: yan noktalar (ortadaki nokta aşağıdaki ada)
                        if (cfg.dotMode && cfg.dotMerge && !expanded) {
                            // Birleşik mod: tüm noktalar tek bir hap olarak çizilir
                            val eL = cfg.dotMidDp / 2f + cfg.dotGapDp + dotExtentDp(cfg, -1)
                            val eR = cfg.dotMidDp / 2f + cfg.dotGapDp + dotExtentDp(cfg, 1)
                            Box(
                                Modifier.align(Alignment.TopCenter)
                                    .offset(x = (cfg.offsetXDp + (eR - eL) / 2f).dp, y = islandTopV.dp)
                                    .size((eL + eR).dp, cfg.dotMidDp.dp).clip(CircleShape).background(Color(cfg.islandColorArgb)),
                            )
                        } else if (cfg.dotMode && !expanded) {
                            for (dir in intArrayOf(-1, 1)) {
                                val d = if (dir > 0) cfg.dotRightDp else cfg.dotLeftDp
                                val dx = dir * (cfg.dotMidDp / 2f + cfg.dotGapDp + d / 2f)
                                Box(
                                    Modifier.align(Alignment.TopCenter)
                                        .offset(x = (cfg.offsetXDp + dx).dp, y = (islandTopV + (cfg.dotMidDp - d) / 2f).dp)
                                        .size(d.dp).clip(CircleShape).background(Color(cfg.islandColorArgb)),
                                )
                                if (dotHasOuter(cfg, dir)) {   // 4 / 5 nokta: dış nokta
                                    val o = dotOuterSizeDp(cfg, dir)
                                    val ox = dx + dir * (d / 2f + cfg.dotGapDp + o / 2f)
                                    Box(
                                        Modifier.align(Alignment.TopCenter)
                                            .offset(x = (cfg.offsetXDp + ox).dp, y = (islandTopV + (cfg.dotMidDp - o) / 2f).dp)
                                            .size(o.dp).clip(CircleShape).background(Color(cfg.islandColorArgb)),
                                    )
                                }
                            }
                        }
                        // iPhone modu önizlemesi: adanın yanındaki siyah daire
                        if (cfg.iphoneCircleOn() && !expanded) {
                            val d = cfg.iphoneCircleSizeDp()
                            val dx = cfg.iphoneDir() * (geo.cw.value / 2f + cfg.iphoneGapDp + d / 2f)
                            Box(
                                Modifier.align(Alignment.TopCenter)
                                    .offset(x = (cfg.offsetXDp + dx).dp, y = (islandTopV + (geo.ch.value - d) / 2f).dp)
                                    .size(d.dp).clip(CircleShape).background(Color(cfg.islandColorArgb)),
                            )
                        }
                        // Telefonun içindeki ada: dokununca açılır / kapanır
                        Box(Modifier.align(Alignment.TopCenter).offset(x = cfg.offsetXDp.dp, y = islandTopV.dp)) {
                            IslandPill(sample, progress, geo, cfg, SAMPLE_STATE, frame, compactLy, onToggle, {}, {}, fxMode = 1)
                        }
                    }
                }
            }
        }
        if (kind == PreviewKind.Island) {
            SegmentedTabs(listOf("Bildirim", "Müzik"), mode, onMode)
        } else {
            Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.Center) {
                Text(hint, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** Telefon maketinin durum çubuğu (saat, sinyal, pil). Sanal ekran dp'siyle çizilir. */
@Composable
internal fun MockStatusBar() {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 30.dp).padding(top = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("9:41", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
            listOf(4, 6, 8, 10).forEach { h ->
                Box(Modifier.width(3.dp).height(h.dp).clip(RoundedCornerShape(1.dp)).background(Color.White))
            }
        }
        Spacer(Modifier.width(7.dp))
        Box(
            Modifier.width(22.dp).height(11.dp)
                .border(1.dp, Color.White.copy(alpha = 0.65f), RoundedCornerShape(3.dp)).padding(1.5.dp),
        ) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.75f).clip(RoundedCornerShape(1.5.dp)).background(Color.White))
        }
    }
}

// ---------------------------------------------------------------- Şarkı sözü ayarları
@Composable
private fun lyricsPages(draft: IslandSettings, version: Int, set: (IslandSettings) -> Unit, onReset: () -> Unit): List<SettingsPage> {
    val cs = MaterialTheme.colorScheme
    return buildPages {
    sec("Şarkı sözleri", "LRCLIB'de aranır (şarkı adı ve sanatçı gönderilir)") {
        SwitchRow("Şarkı sözleri",
            "Başlık, sanatçı ve süre sunucuya gönderilir. Bildirimi olan her oynatıcıyla çalışır (Spotify, YouTube Music, Yandex Müzik, VK…).",
            draft.lyricsEnabled) { set(draft.copy(lyricsEnabled = it)) }
        if (draft.lyricsEnabled) {
            Text("Senkron söz yoksa düz sözler şarkı süresine yayılır. Aranırken \"Şarkı sözü aranıyor…\", bulunamazsa \"Şarkı sözü bulunamadı\" yazar.",
                color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    sec("Müzik uygulamasını aç", "Ada üzerinden çalan müziğin uygulamasına geç") {
        SubHeader("Ada genişletilmişken")
        SwitchRow("Albüm kapağına basınca", null, draft.openExpArt) { set(draft.copy(openExpArt = it)) }
        SwitchRow("Şarkı ismine basınca", null, draft.openExpTitle) { set(draft.copy(openExpTitle = it)) }
        SwitchRow("Adanın herhangi bir yerine basınca", "Düğmeler hariç; açıkken ada küçülmez, müzik uygulaması açılır", draft.openExpAny) { set(draft.copy(openExpAny = it)) }
        SectionDivider()
        SubHeader("Ada küçültülmüşken")
        SwitchRow("Albüm kapağına basınca", null, draft.openCompArt) { set(draft.copy(openCompArt = it)) }
        SwitchRow("Adaya basınca", "Açıksa adayı genişletmek için aşağıdaki seçenekleri kullan", draft.openCompTap) { set(draft.copy(openCompTap = it)) }
        SwitchRow("Adaya basılı tutunca", null, draft.openCompLong) { set(draft.copy(openCompLong = it)) }
        if (draft.openCompTap && !draft.openCompLong) {
            SwitchRow("Basılı tutunca ada genişlesin", "Adaya basınca müzik uygulaması açıldığı için", draft.expandOnLong) { set(draft.copy(expandOnLong = it)) }
        }
        SectionDivider()
        SwitchRow("Açılış animasyonu", "Müzik uygulaması açılırken ada animasyonu oynar (stil: Hareket > Açılış animasyonu stili)",
            draft.musicOpenAnim) { set(draft.copy(musicOpenAnim = it)) }
        SectionDivider()
        SliderRow("Basılı tutma süresi", draft.longPressMs.toFloat(), 250f..1500f, "${draft.longPressMs} ms") {
            set(draft.copy(longPressMs = (it / 50f).toInt() * 50))
        }
    }
    sec("Açık kalma süresi", "Müzik adasını genişletince (dokunarak, kaydırarak ya da yan baloncuktan)") {
        SliderRow("Genişletilmiş kalma süresi", draft.musicExpandedSec, 0f..60f,
            if (draft.musicExpandedSec < 1f) "Kapanmasın" else "${draft.musicExpandedSec.toInt()} sn") { set(draft.copy(musicExpandedSec = kotlin.math.round(it))) }
        Text("Süre dolunca ada kendiliğinden küçülür. En sola çekersen ada sen kapatana kadar (dokun / yukarı kaydır) açık kalır.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
    sec("Kaydırma hareketleri", "Müzik adada görünürken") {
        SwitchRow("Aşağı kaydırınca genişlesin", "Küçük adayı aşağı çekince açılır", draft.swipeDownExpand) { set(draft.copy(swipeDownExpand = it)) }
        SwitchRow("Yukarı kaydırınca küçülsün", "Açık adayı yukarı kaydırınca küçülür", draft.swipeUpCollapse) { set(draft.copy(swipeUpCollapse = it)) }
        SwitchRow("Yana kaydırınca şarkı değişsin", "Müzik çalarken ada küçükken de açıkken de: sağdan sola kaydır = sonraki şarkı, soldan sağa = önceki şarkı", draft.swipeTrackChange) { set(draft.copy(swipeTrackChange = it)) }
    }
    sec("Müzik uygulamasındayken", "Çalan müziğin uygulamasını açıkken adada şarkı gösterilmesin") {
        SwitchRow("Müzik uygulamasında adayı gizle", "Çalan müziğin uygulamasındayken ada müziği göstermez. \"Müzikten çıkış animasyonu\" da açıksa çıkınca önce panel adaya girip yutulur, sonra şarkı görünür",
            draft.hideInMusicApp) { set(draft.copy(hideInMusicApp = it)) }
    }
    sec("Müzikten çıkış", "Müzik uygulamasından çıkınca panel adaya küçülür, ada şarkıyı yutup gösterir") {
        SwitchRow("Müzikten çıkış animasyonu", "Müzik çalarken o uygulamadan çıkınca çalışır",
            draft.musicExitAnim) { set(draft.copy(musicExitAnim = it)) }
        if (draft.musicExitAnim) {
            SliderRow("Hız", draft.musicExitSpeed, 0.5f..3f, "%.1fx".format(draft.musicExitSpeed)) { set(draft.copy(musicExitSpeed = it)) }
            SwitchRow("Akıcı yutma", "Uygulama penceresi bulanıklaşarak küçülür ve adanın altına girer, ada aşağı doğru şişip yutar ve yaylanarak eski haline döner. Müzik uygulamasını adadan açarken de tersi oynar: ada şişer, pencere altından bulanık çıkıp ekranı kaplar. Açıksa aşağıdaki yutma ve panel seçenekleri kullanılmaz",
                draft.musicFluidAnim) { set(draft.copy(musicFluidAnim = it, musicExitStretch = if (it) false else draft.musicExitStretch)) }
            if (!draft.musicFluidAnim) {
            SwitchRow("Ada yutma efekti", "Panel adaya varınca ada kısa süre büyüyüp onu yutar. Kapalıysa ada büyümez (Uzayan ada açıksa onun yerine o oynar)",
                draft.musicExitSwallow) { set(draft.copy(musicExitSwallow = it)) }
            SwitchRow("Uzayan ada (iOS tarzı)", "Ada yukarı doğru (ekran kenarına kadar) yumuşakça büyür, sonra normal boyutuna döner. Açıksa kısa büyüme efekti yerine bu oynar; hız yukarıdaki ayardan alınır. Bu sırada cam / çerçeve efektleri düz renkle çizilir",
                draft.musicExitStretch) { set(draft.copy(musicExitStretch = it, musicFluidAnim = if (it) false else draft.musicFluidAnim)) }
            SectionDivider()
            SubHeader("Panel animasyonu")
            SwitchRow("Klasik panel", "Uzun panel küçülerek adaya girer",
                draft.musicExitClassic) { set(draft.copy(musicExitClassic = it, musicExitAtomic = if (!it) true else draft.musicExitAtomic)) }
            SwitchRow("Atom ada", "Uygulama kartı eğilip huni gibi adaya çekilir (OriginOS tarzı)",
                draft.musicExitAtomic) { set(draft.copy(musicExitAtomic = it, musicExitClassic = if (!it) true else draft.musicExitClassic)) }
            if (draft.musicExitAtomic) {
                SwitchRow("Atom ada: tam ekran başlangıç", "Müzik uygulamasından çıkarken önce siyah panel tüm ekranı kaplar, sonra atom ada animasyonuyla küçülüp adaya girer",
                    draft.musicExitAtomicFull) { set(draft.copy(musicExitAtomicFull = it)) }
            }
            Text("İkisi de açıksa her çıkışta rastgele biri oynar.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
            }
        }
    }
    if (!draft.lyricsEnabled) { sec("Sıfırla", "Söz ayarlarını başlangıç değerine döndür") { IslandActionButton("Söz ayarlarını varsayılana döndür", onReset) }; return@buildPages }

    sec("Nerede görünsün", "İstediğin kadarını aynı anda açabilirsin") {
        SwitchRow("Genişletilmiş adada", "Şarkı adı ile ilerleme çubuğu arasında", draft.lyricsInPanel) { set(draft.copy(lyricsInPanel = it)) }
        SectionDivider()
        SwitchRow("Kapalı adada (ortada)", "Albüm kapağı ile equalizer arasındaki boşlukta; ada sözler için genişler", draft.lyricsCompact) { set(draft.copy(lyricsCompact = it)) }
        SectionDivider()
        SwitchRow("Adanın dışında", "Adayı açmadan, ekranın üstünde ayrı görünür", draft.lyricsOutside) { set(draft.copy(lyricsOutside = it)) }
    }

    if (draft.lyricsInPanel) sec("Genişletilmiş ada", "Panelin içindeki sözler") {
        SwitchRow(
            "Sözler sağda, kontroller solda",
            "Ada genişleyince müzik kontrolleri (kapak, şarkı adı, ilerleme çubuğu, düğmeler) sol tarafta, şarkı sözleri sağ tarafta görünür. O an söylenen satırın altında sıradaki satır soluk olarak durur. Panel sözler için uzamaz",
            draft.more.lyricsSide,
        ) { set(draft.copy(more = draft.more.copy(lyricsSide = it))) }
        SectionDivider()
        SliderRow("Yazı boyutu", draft.lyricsPanelSizeSp, 12f..26f, "${draft.lyricsPanelSizeSp.toInt()} sp") { set(draft.copy(lyricsPanelSizeSp = it)) }
        SliderRow("En fazla satır", draft.lyricsPanelLines.toFloat(), 1f..3f, "${draft.lyricsPanelLines}") { set(draft.copy(lyricsPanelLines = it.roundToInt().coerceIn(1, 3))) }
    }

    if (draft.lyricsCompact) sec("Kapalı ada", "Tek satır; sığmazsa söylendikçe yana kayar") {
        SliderRow("Yazı boyutu", draft.lyricsCompactSizeSp, 8f..18f, "${draft.lyricsCompactSizeSp.toInt()} sp") { set(draft.copy(lyricsCompactSizeSp = it)) }
        SliderRow("Ada genişliği (sözler varken)", draft.lyricsCompactWidthDp, 120f..300f, "${draft.lyricsCompactWidthDp.toInt()} dp") { set(draft.copy(lyricsCompactWidthDp = it)) }
        Text("Not: Kamera deliği adanın tam ortasında olduğu için orta kısımdaki harfler deliğin arkasında kalabilir; genişlik arttıkça okunabilirlik artar.",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }

    if (draft.lyricsOutside) sec("Adanın dışı", "Ayrı, dokunulmaz söz katmanı (ada genişleyince gizlenir)") {
        SliderRow("Yazı boyutu", draft.lyricsOutsideSizeSp, 12f..40f, "${draft.lyricsOutsideSizeSp.toInt()} sp") { set(draft.copy(lyricsOutsideSizeSp = it)) }
        SliderRow("En fazla satır", draft.lyricsOutsideLines.toFloat(), 1f..3f, "${draft.lyricsOutsideLines}") { set(draft.copy(lyricsOutsideLines = it.roundToInt().coerceIn(1, 3))) }
        SliderRow("Genişlik", draft.lyricsOutsideWidthDp, 180f..420f, "${draft.lyricsOutsideWidthDp.toInt()} dp") { set(draft.copy(lyricsOutsideWidthDp = it)) }
        SliderRow("Yükseklik (ekranın üstünden)", draft.lyricsOutsideYDp, 0f..760f, "${draft.lyricsOutsideYDp.toInt()} dp") { set(draft.copy(lyricsOutsideYDp = it)) }
        SliderRow("Yatay konum", draft.lyricsOutsideXDp, -150f..150f, "${draft.lyricsOutsideXDp.toInt()} dp") { set(draft.copy(lyricsOutsideXDp = it)) }
    }

    sec("Renk", "Üç yerde de ortak") {
        SwitchRow("Albüm kapağı rengi", "Söz rengi, çalan şarkının kapağına göre değişir",
            draft.lyricsDynamicColor) { set(draft.copy(lyricsDynamicColor = it)) }
        SectionDivider()
        SubHeader(if (draft.lyricsDynamicColor) "Kapak yoksa kullanılacak renk" else "Söz rengi")
        key(version) { ColorPicker(draft.lyricsColorArgb) { set(draft.copy(lyricsColorArgb = it)) } }
        SliderRow("Söylenmemiş kısım", draft.lyricsDimAlpha, 0.1f..0.8f, "%${(draft.lyricsDimAlpha * 100).toInt()}") { set(draft.copy(lyricsDimAlpha = it)) }
    }

    sec("Dolum ve senkron", "Satır şarkıyla birlikte soldan sağa dolar") {
        SwitchRow("Söylendikçe dolsun", "Kapatırsan satırın tamamı parlak görünür", draft.lyricsFillEnabled) { set(draft.copy(lyricsFillEnabled = it)) }
        SectionDivider()
        val off = draft.lyricsOffsetMs
        SliderRow("Senkron kaydırma", off.toFloat(), -2000f..2000f, (if (off > 0) "+$off" else "$off") + " ms") {
            set(draft.copy(lyricsOffsetMs = (it / 50f).roundToInt() * 50))
        }
        Text("+ değer sözleri daha erken, − değer daha geç gösterir.", color = cs.onSurfaceVariant, fontSize = 12.sp)
    }

    sec("Parıltı (Glow)", "Söylenen kısmın etrafında") {
        SwitchRow("Parıltı efekti", null, draft.lyricsGlowEnabled) { set(draft.copy(lyricsGlowEnabled = it)) }
        if (draft.lyricsGlowEnabled) {
            SliderRow("Seviye", draft.lyricsGlowLevel, 0.1f..1f, "%${(draft.lyricsGlowLevel * 100).toInt()}") { set(draft.copy(lyricsGlowLevel = it)) }
        }
    }

    sec("Satır geçiş animasyonu", "Bir satırdan diğerine geçerken") {
        SwitchRow("Geçiş animasyonları", "Kapatırsan satırlar anında değişir", draft.lyricsAnimEnabled) { set(draft.copy(lyricsAnimEnabled = it)) }
        if (draft.lyricsAnimEnabled) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LYRIC_ANIMS.forEachIndexed { i, (name, desc) ->
                    RadioRow(name, desc, draft.lyricsAnimStyle == i) { set(draft.copy(lyricsAnimStyle = i)) }
                }
            }
            if (draft.lyricsAnimStyle == 6 || draft.lyricsAnimStyle == 7) {
                Text(
                    "Not: Sözler, \"Adanın dışında\" açıkken adanın içinden çıkar. Panel ve kapalı adada yerinde küçülüp büyür.",
                    color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            if (draft.lyricsAnimStyle != 5) {
                SliderRow("Süre", draft.lyricsAnimMs.toFloat(), 120f..900f, "${draft.lyricsAnimMs} ms") { set(draft.copy(lyricsAnimMs = it.toInt())) }
            }
        }
    }
    sec("Sıfırla", "Söz ayarlarını başlangıç değerine döndür") { IslandActionButton("Söz ayarlarını varsayılana döndür", onReset) }
    }
}

// ---------------------------------------------------------------- Renk seçici (HSV)
@Composable
fun ColorPicker(argb: Int, onColor: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hsv0 = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(argb, it) } }
    var h by remember { mutableFloatStateOf(hsv0[0]) }
    var s by remember { mutableFloatStateOf(hsv0[1]) }
    var v by remember { mutableFloatStateOf(hsv0[2]) }
    fun emit() = onColor(Color.hsv(h, s, v).toArgb())

    val presets = remember { listOf(0xFF0A84FF, 0xFF30D158, 0xFFFF453A, 0xFFFF9F0A, 0xFFBF5AF2, 0xFFFFFFFF).map { Color(it) } }
    val current = Color.hsv(h, s, v)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            presets.forEach { c ->
                val picked = c.toArgb() == current.toArgb()
                Box(
                    Modifier.size(34.dp)
                        .border(if (picked) 2.dp else 0.dp, if (picked) cs.primary else Color.Transparent, CircleShape)
                        .padding(3.dp)
                        .clip(CircleShape).background(c)
                        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                        .clickable(role = Role.Button) {
                            val t = FloatArray(3); android.graphics.Color.colorToHSV(c.toArgb(), t)
                            h = t[0]; s = t[1]; v = t[2]; emit()
                        }
                )
            }
            Spacer(Modifier.weight(1f))
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).background(current).border(1.dp, cs.outline, RoundedCornerShape(10.dp)))
        }
        HueBar(h) { h = it; emit() }
        SliderRow("Doygunluk", s, 0f..1f, "%${(s * 100).toInt()}") { s = it; emit() }
        SliderRow("Parlaklık", v, 0f..1f, "%${(v * 100).toInt()}") { v = it; emit() }
    }
}

@Composable
private fun HueBar(hue: Float, onHue: (Float) -> Unit) {
    val colors = remember { (0..360 step 30).map { Color.hsv(it.toFloat(), 1f, 1f) } }
    var w by remember { mutableIntStateOf(1) }
    val cb by rememberUpdatedState(onHue)
    Box(
        Modifier.fillMaxWidth().height(30.dp).clip(RoundedCornerShape(15.dp))
            .background(Brush.horizontalGradient(colors))
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(Unit) { detectTapGestures { cb((it.x / w * 360f).coerceIn(0f, 360f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume(); cb((change.position.x / w * 360f).coerceIn(0f, 360f))
                }
            }
    ) {
        Box(
            Modifier.align(Alignment.CenterStart)
                .offset {
                    val thumb = 24.dp.toPx()
                    IntOffset((hue / 360f * w - thumb / 2f).toInt().coerceIn(0, (w - thumb.toInt()).coerceAtLeast(0)), 0)
                }
                .size(24.dp).border(2.5.dp, Color.White, CircleShape).background(Color.hsv(hue, 1f, 1f), CircleShape)
        )
    }
}

/** İsteğe bağlı: kayıt bitince önizleme resmi için galeriden video okuma izni. */
@Composable
private fun RecPreviewPermissionRow() {
    val ctx = LocalContext.current
    val perm = recPreviewPermission()
    var granted by remember {
        mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED)
    }
    var denied by remember { mutableStateOf(false) }
    // Sistem ayarlarından değiştirilip uygulamaya dönülürse durum yenilensin
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
        granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        denied = !ok
    }
    SubHeader("Kayıt önizlemesi (isteğe bağlı)")
    Text(
        "Bazı telefonlar ekran kaydı bitince bildirime önizleme resmi koymaz. Bu izin verilirse önizleme, kaydedilen videonun ilk karesinden alınır. " +
            "İzin vermezsen her şey çalışır, sadece önizleme yerine gri kutu görünür. Yalnızca en son ekran kaydına bakılır, hiçbir şey saklanmaz veya paylaşılmaz.",
        color = Pal.c("ui_warn_text"), fontSize = 12.sp, lineHeight = 17.sp,
    )
    if (granted) {
        Text("İzin verildi", color = Pal.c("ui_success_text"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
    } else {
        Button(onClick = { launcher.launch(perm) }, modifier = Modifier.fillMaxWidth()) { Text("Galeri iznini ver") }
        if (denied) {
            // Android aynı izni birkaç reddedişten sonra tekrar sormaz: izin uygulama ayarlarından verilmeli
            Text("İzin verilmedi. Pencere çıkmıyorsa uygulama ayarlarından Fotoğraflar ve videolar iznini aç.",
                color = Pal.c("ui_warn_text"), fontSize = 12.sp, lineHeight = 17.sp)
            OutlinedButton(
                onClick = {
                    ctx.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            android.net.Uri.parse("package:${ctx.packageName}")),
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Uygulama ayarlarını aç") }
        }
    }
}


// ---------------------------------------------------------------- Ses efekti düzenleyici (bir olay için)
private fun fmtSec(ms: Int): String = "%.1f sn".format(ms / 1000f)

@Composable
private fun ColumnScope.SoundCueEditor(
    ev: SoundEvent, cue: SoundCue,
    onPick: (SoundEvent) -> Unit, onRemove: (SoundEvent) -> Unit, onChange: (SoundCue) -> Unit,
) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val has = cue.uri.isNotEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text("Ses efekti", color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            if (has) "${cue.name} · ${fmtSec(cue.durMs)}" else "Henüz ses seçilmedi. Dosya kopyalanmaz, yalnızca adresi saklanır. Süresi fark etmez; istersen yalnızca bir kısmını kullanırsın.",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { onPick(ev) }, modifier = Modifier.weight(1f)) { Text(if (has) "Sesi değiştir" else "Ses seç") }
        if (has) OutlinedButton(onClick = { onRemove(ev) }, modifier = Modifier.weight(1f)) { Text("Kaldır") }
    }
    if (!has) return
    SwitchRow("Bu olayda çal", "Kapalıyken ses seçili kalır ama çalmaz", cue.enabled) { onChange(cue.copy(enabled = it)) }
    SliderRow("Ses yüksekliği", cue.volume, 0f..1f, "%${(cue.volume * 100).toInt()}") { onChange(cue.copy(volume = it)) }
    SwitchRow("Yalnızca bir kısmını çal", "Kapalıyken sesin tamamı çalar. Açıkken başlangıç ve bitişi sen seçersin",
        cue.partial) { onChange(cue.copy(partial = it)) }
    if (cue.partial) {
        val dur = cue.durMs.coerceAtLeast(1000)
        val endEff = (if (cue.endMs in 1..dur) cue.endMs else dur)
        val startEff = cue.startMs.coerceIn(0, (endEff - 100).coerceAtLeast(0))
        val maxS = dur / 1000f
        SliderRow("Başlangıç", startEff / 1000f, 0f..maxS, fmtSec(startEff)) { v ->
            val ms = (kotlin.math.round(v * 10f) * 100f).toInt().coerceIn(0, (endEff - 100).coerceAtLeast(0))
            onChange(cue.copy(startMs = ms, endMs = if (cue.endMs <= 0) 0 else endEff))
        }
        SliderRow("Bitiş", endEff / 1000f, 0f..maxS, fmtSec(endEff)) { v ->
            val ms = (kotlin.math.round(v * 10f) * 100f).toInt().coerceIn((startEff + 100).coerceAtMost(dur), dur)
            onChange(cue.copy(startMs = startEff, endMs = ms))
        }
        Text("Çalacak bölüm: ${fmtSec(startEff)} – ${fmtSec(endEff)} (${fmtSec(endEff - startEff)})",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = { IslandSounds.play(ctx, cue.copy(enabled = true), "preview") }, modifier = Modifier.weight(1f)) { Text("Dinle") }
        OutlinedButton(onClick = { IslandSounds.stopAll() }, modifier = Modifier.weight(1f)) { Text("Durdur") }
    }
}


// ---------------------------------------------------------------- Kilit ekranı noktası önizlemesi
@Composable
private fun LockDotPreview(s: IslandSettings) {
    val glow = if (s.lockDotGlow) s.lockDotGlowDp else 0f
    val total = s.lockDotSizeDp + 2f * glow
    val cw = 120f
    Box(
        Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(14.dp)).background(Pal.c("preview_lockbg")),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(cw.dp).height(32.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black))
        Box(
            Modifier.offset(x = (-(cw / 2f + s.lockDotGapDp + s.lockDotSizeDp / 2f)).dp, y = s.lockDotOffsetYDp.dp).size(total.dp),
        ) { LockDotView(s) }
    }
}

/** Süs seçenekleri: biri açılınca diğerleri kapanır; açık olan kapatılırsa süs kapanır (0). Seçenekler 1'den başlar. */
@Composable
private fun DecorChoices(options: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit) {
    options.forEachIndexed { i, (title, desc) ->
        SwitchRow(title, desc, selected == i + 1) { on -> onSelect(if (on) i + 1 else 0) }
    }
}
