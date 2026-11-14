package com.example.dynamicisland

import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    SettingsContent(init, lyricsPage) { repo.save(it) }
}

@Composable
private fun SettingsContent(init: IslandSettings, lyricsPage: Boolean, onSave: suspend (IslandSettings) -> Unit) {
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
    val remove: (CustomImages.Slot) -> Unit = { slot ->
        val old = draft.imageUri(slot)
        draft = draft.withImageUri(slot, "")
        CustomImages.releaseUnused(ctx, old, draft)
    }

    // Slider sürüklenirken değil, 250 ms sakinleşince kaydet
    LaunchedEffect(draft) { delay(250); onSave(draft) }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // ---- Sabit canlı önizleme (kaydırırken de görünür kalır)
        PreviewCard(draft, mode, { mode = it }, expanded) { expanded = !expanded }

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (lyricsPage) {
                LyricsSections(draft, version) { draft = it }
                IslandActionButton("Söz ayarlarını varsayılana döndür", { draft = draft.withLyricsDefaults(); version++ })
                Spacer(Modifier.height(28.dp))
                return@Column
            }

            // ---- Yerleşim
            GroupLabel("Yerleşim")
            SectionCard("Kapalı ada", "Kamera çevresindeki küçük hal") {
                SliderRow("Genişlik", draft.compactWidthDp, 60f..220f, "${draft.compactWidthDp.toInt()} dp") { draft = draft.copy(compactWidthDp = it) }
                SliderRow("Yükseklik", draft.compactHeightDp, 20f..60f, "${draft.compactHeightDp.toInt()} dp") { draft = draft.copy(compactHeightDp = it) }
            }
            SectionCard("Açık ada", "Dokununca açılan genişletilmiş panel") {
                SliderRow("Genişlik", draft.expandedWidthDp, 260f..400f, "${draft.expandedWidthDp.toInt()} dp") { draft = draft.copy(expandedWidthDp = it) }
                SliderRow("Yükseklik", draft.expandedHeightDp, 72f..120f, "${draft.expandedHeightDp.toInt()} dp") { draft = draft.copy(expandedHeightDp = it) }
            }
            SectionCard("Konum", "Kamera deliğine göre ince ayar") {
                SliderRow("Yatay", draft.offsetXDp, -60f..60f, "${draft.offsetXDp.toInt()} dp") { draft = draft.copy(offsetXDp = it) }
                SliderRow("Dikey", draft.offsetYDp, -60f..40f, "${draft.offsetYDp.toInt()} dp") { draft = draft.copy(offsetYDp = it) }
            }
            SectionCard("Görünürlük", "Ada ne zaman görünsün") {
                SwitchRow("Boştayken de göster", "Müzik ve bildirim yokken ada kamera çevresinde durur", draft.showIdle) { draft = draft.copy(showIdle = it) }
                SwitchRow("Durum çubuğunu gizle", "Şarj görünümü, zil sesi görünümü vb. açıldığında durum çubuğunu gizler. Durum çubuğu bulanıklaşır (cihaz desteklemiyorsa siyah olur)", draft.hideStatusBar) { draft = draft.copy(hideStatusBar = it) }
            }
            FavoritesSection(draft) { draft = it }

            SectionCard("Bildirimler", "Adada görünen bildirime dokununca") {
                SwitchRow("Dokununca uygulamayı aç", "Bildirimin geldiği uygulama açılır (mümkünse ilgili sohbet / ekran)",
                    draft.notifTapOpens) { draft = draft.copy(notifTapOpens = it) }
                if (draft.notifTapOpens) {
                    SwitchRow("Açılış animasyonu", "Adadan siyah bir ekran çıkıp ekranı kaplar, ardından uygulama açılır. Hız, Favori uygulamalar bölümündeki ayardan alınır",
                        draft.notifLaunchAnim) { draft = draft.copy(notifLaunchAnim = it) }
                }
            }

            SectionCard("Google Asistan / Gemini", "Asistan açılınca ada Siri benzeri renkli animasyona dönüşür") {
                SwitchRow("Asistan animasyonu", "Erişilebilirlik servisi açık olmalı", draft.assistantEnabled) { draft = draft.copy(assistantEnabled = it) }
                if (draft.assistantEnabled) {
                    SwitchRow("Tam genişlik animasyon", "Ada kutusu olmadan, renkli animasyon ekranın solundan sağına uzanır",
                        draft.assistantFullWidth) { draft = draft.copy(assistantFullWidth = it) }
                    if (!draft.assistantFullWidth) {
                        SliderRow("Genişlik", draft.assistantWidthDp, 100f..300f, "${draft.assistantWidthDp.toInt()} dp") { draft = draft.copy(assistantWidthDp = it) }
                    }
                    SliderRow("Yükseklik", draft.assistantHeightDp, 60f..180f, "${draft.assistantHeightDp.toInt()} dp") { draft = draft.copy(assistantHeightDp = it) }
                }
            }

            SideCallSettingsSection()

            // ---- Görünüm
            GroupLabel("Görünüm")

            SectionCard("Gökkuşağı modu", "Ada, müzik paneli, mesaj ve şarkı sözleri gökkuşağı renklerinde akar") {
                SwitchRow("Gökkuşağı modu", "Açıkken ilgili öğelerde diğer renk ayarları yerine gökkuşağı kullanılır",
                    draft.rainbowEnabled) { draft = draft.copy(rainbowEnabled = it) }
                if (draft.rainbowEnabled) {
                    SliderRow("Saydamlık", draft.rainbowAlpha, 0.2f..1f, "%${(draft.rainbowAlpha * 100).toInt()}") { draft = draft.copy(rainbowAlpha = it) }
                }
            }
            SectionCard("Renk", "Adanın genel renk davranışı") {
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
            SectionCard("Ekolayzır rengi", "Müzik çalarken adadaki ses çubukları") {
                SwitchRow("Albüm kapağı rengi", "Ekolayzır, çalan şarkının kapağına göre renklenir",
                    draft.eqDynamicColor) { draft = draft.copy(eqDynamicColor = it) }
                SectionDivider()
                SubHeader(if (draft.eqDynamicColor) "Kapak yoksa kullanılacak renk" else "Ekolayzır rengi")
                key(version) { ColorPicker(draft.eqColorArgb) { draft = draft.copy(eqColorArgb = it) } }
            }

            SectionCard("Albüm arka planı", "Müzik çalarken adanın içi kapak görseliyle dolar") {
                SwitchRow("Albüm kapağı arka planı", "Kapak bulanık ve hafif karartılmış olarak adanın içine yayılır",
                    draft.artBgEnabled) { draft = draft.copy(artBgEnabled = it) }
                if (draft.artBgEnabled) {
                    SwitchRow("Kapalı adada da göster", "Kapatırsan arka plan yalnızca açılmış adada görünür",
                        draft.artBgCompact) { draft = draft.copy(artBgCompact = it) }
                }
            }

            SectionCard("Özel arka plan", "Adanın içini galeriden seçtiğin fotoğrafla doldur") {
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
                    SliderRow("Karartma", draft.customBgDim, 0f..0.8f, "%${(draft.customBgDim * 100).toInt()}") { draft = draft.copy(customBgDim = it) }
                    Text("Özel arka plan, albüm kapağı arka planının üstünde görünür.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            SectionCard("Özel albüm kapağı", "Adanın içindeki kapak yerine galeriden fotoğraf göster") {
                SwitchRow("Galeriden kapak fotoğrafı", "Kapalı ve açık adadaki küçük albüm kapağı yerine seçtiğin fotoğraf görünür",
                    draft.customArtEnabled) { draft = draft.copy(customArtEnabled = it) }
                if (draft.customArtEnabled) {
                    ImagePickRow("Kapak fotoğrafı", "Her şarkıda bu fotoğraf gösterilir",
                        CustomImages.Slot.ART, draft.customArtUri, pick, remove)
                    Text("Fotoğraf seçilmezse gerçek albüm kapağı gösterilir. Ada rengi ve parıltılar yine çalan şarkının kapağından alınır.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
            }

            SectionCard("Liquid Glass", "Kapalıyken ada düz, opak siyahtır") {
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

            SectionCard("Parıltı (Glow)", "Renk, çalan şarkının kapağından alınır") {
                SwitchRow("Parıltı efekti", "Kapalı ve açık halde adanın etrafında", draft.glowEnabled) { draft = draft.copy(glowEnabled = it) }
                if (draft.glowEnabled) {
                    SliderRow("Yoğunluk", draft.glowIntensity, 0.2f..1f, "%${(draft.glowIntensity * 100).toInt()}") { draft = draft.copy(glowIntensity = it) }
                }
            }

            SectionCard("İlerleme çubuğu parıltısı", "Açık adadaki müzik panelinde, şarkı çubuğunda") {
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

            SectionCard("Beğeni düğmesi", "Açık adadaki kalp, beğenildiğinde bu renkte olur") {
                SwitchRow("Albüm kapağı rengi", "Kalp, çalan şarkının kapağıyla aynı renkte olur",
                    draft.likeArtColor) { draft = draft.copy(likeArtColor = it) }
                SubHeader(if (draft.likeArtColor) "Kapak yoksa kullanılacak renk" else "Kalp rengi")
                key(version) { ColorPicker(draft.likeColorArgb) { draft = draft.copy(likeColorArgb = it) } }
            }

            SectionCard("Çerçeve", "İstediğin renk ya da albüm kapağı rengi") {
                SwitchRow("Çerçeve çizgisi", null, draft.borderEnabled) { draft = draft.copy(borderEnabled = it) }
                if (draft.borderEnabled) {
                    SliderRow("Kalınlık", draft.borderWidthDp, 0.5f..3f, "${"%.1f".format(draft.borderWidthDp)} dp") { draft = draft.copy(borderWidthDp = it) }
                    SwitchRow("Albüm kapağı rengi", "Çerçeve çizgisi, çalan şarkının kapağına göre renklenir",
                        draft.borderArtColor) { draft = draft.copy(borderArtColor = it) }
                    SubHeader(if (draft.borderArtColor) "Kapak yoksa kullanılacak renk" else "Çerçeve rengi")
                    key(version) { ColorPicker(draft.borderColorArgb) { draft = draft.copy(borderColorArgb = it) } }
                }
            }

            // ---- Hareket
            GroupLabel("Hareket")
            SectionCard("Animasyon", "Açılma ve küçülme geçişi") {
                SwitchRow("Animasyonlar", "Kapatırsan ada anında açılır/kapanır", draft.animEnabled) { draft = draft.copy(animEnabled = it) }
                if (draft.animEnabled) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ANIM_STYLES.forEachIndexed { i, (name, desc) ->
                            RadioRow(name, desc, draft.animStyle == i) { draft = draft.copy(animStyle = i) }
                        }
                    }
                }
            }

            IslandActionButton("Varsayılanlara dön", { CustomImages.releaseAll(ctx, draft); draft = IslandSettings(); version++ })
            Spacer(Modifier.height(28.dp))
        }
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

// ---------------------------------------------------------------- Önizleme
private const val SAMPLE_COLOR = 0xFFFF5F9E.toInt()

private fun sampleArt(): ImageBitmap {
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

@Composable
private fun PreviewCard(cfg: IslandSettings, mode: Int, onMode: (Int) -> Unit, expanded: Boolean, onToggle: () -> Unit) {
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
    val geo = islandGeo(cfg, music, compactLy)
    val boxH by animateDpAsState(if (expanded) geo.eh + 64.dp else 160.dp, tween(320, easing = FastOutSlowInEasing), label = "previewH")
    val showOutside = music && !expanded && cfg.lyricsEnabled && cfg.lyricsOutside
    val shape = RoundedCornerShape(28.dp)

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(
            Modifier.fillMaxWidth().height(boxH).clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xFF1F4E6B), Color(0xFF3A3A78), Color(0xFF5A3470))))
                .border(1.dp, Color.White.copy(alpha = 0.10f), shape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onToggle),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.padding(top = 20.dp)) {
                IslandPill(sample, progress, geo, cfg, SAMPLE_STATE, frame, compactLy, onToggle, {}, {})
            }
            if (showOutside) {
                OutsideLyricText(
                    cfg, SAMPLE_STATE, frame,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 46.dp)
                        .width(cfg.lyricsOutsideWidthDp.dp.coerceAtMost(320.dp)),
                    artColor = SAMPLE_COLOR,
                )
            }
            Text(
                if (expanded) "Küçültmek için dokun" else "Açmak için dokun",
                color = Color.White.copy(alpha = 0.78f), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.30f))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
            )
        }
        SegmentedTabs(listOf("Bildirim", "Müzik"), mode, onMode)
    }
}

// ---------------------------------------------------------------- Şarkı sözü ayarları
@Composable
private fun LyricsSections(draft: IslandSettings, version: Int, set: (IslandSettings) -> Unit) {
    val cs = MaterialTheme.colorScheme
    SectionCard("Şarkı sözleri", "Önce LRCLIB, bulunamazsa NetEase'te aranır") {
        SwitchRow("Şarkı sözleri",
            "Başlık, sanatçı ve süre sunucuya gönderilir. Bildirimi olan her oynatıcıyla çalışır (Spotify, YouTube Music, Yandex Müzik, VK…).",
            draft.lyricsEnabled) { set(draft.copy(lyricsEnabled = it)) }
        if (draft.lyricsEnabled) {
            Text("Senkron söz yoksa düz sözler şarkı süresine yayılır. Aranırken \"Şarkı sözü aranıyor…\", bulunamazsa \"Şarkı sözü bulunamadı\" yazar.",
                color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
    SectionCard("Müzik uygulamasındayken", "Çalan müziğin uygulamasını açıkken adada şarkı gösterilmesin") {
        SwitchRow("Müzik uygulamasında adayı gizle", "Çalan müziğin uygulamasındayken ada müziği göstermez. \"Müzikten çıkış animasyonu\" da açıksa çıkınca önce panel adaya girip yutulur, sonra şarkı görünür",
            draft.hideInMusicApp) { set(draft.copy(hideInMusicApp = it)) }
    }
    SectionCard("Müzikten çıkış", "Müzik uygulamasından çıkınca panel adaya küçülür, ada şarkıyı yutup gösterir") {
        SwitchRow("Müzikten çıkış animasyonu", "Müzik çalarken o uygulamadan çıkınca çalışır",
            draft.musicExitAnim) { set(draft.copy(musicExitAnim = it)) }
        if (draft.musicExitAnim) {
            SliderRow("Hız", draft.musicExitSpeed, 0.5f..3f, "%.1fx".format(draft.musicExitSpeed)) { set(draft.copy(musicExitSpeed = it)) }
            SwitchRow("Ada yutma efekti", "Panel adaya varınca ada kısa süre büyüyüp onu yutar. Kapalıysa ada büyümez",
                draft.musicExitSwallow) { set(draft.copy(musicExitSwallow = it)) }
        }
    }
    if (!draft.lyricsEnabled) return

    SectionCard("Nerede görünsün", "İstediğin kadarını aynı anda açabilirsin") {
        SwitchRow("Genişletilmiş adada", "Şarkı adı ile ilerleme çubuğu arasında", draft.lyricsInPanel) { set(draft.copy(lyricsInPanel = it)) }
        SectionDivider()
        SwitchRow("Kapalı adada (ortada)", "Albüm kapağı ile equalizer arasındaki boşlukta; ada sözler için genişler", draft.lyricsCompact) { set(draft.copy(lyricsCompact = it)) }
        SectionDivider()
        SwitchRow("Adanın dışında", "Adayı açmadan, ekranın üstünde ayrı görünür", draft.lyricsOutside) { set(draft.copy(lyricsOutside = it)) }
    }

    if (draft.lyricsInPanel) SectionCard("Genişletilmiş ada", "Panelin içindeki sözler") {
        SliderRow("Yazı boyutu", draft.lyricsPanelSizeSp, 12f..26f, "${draft.lyricsPanelSizeSp.toInt()} sp") { set(draft.copy(lyricsPanelSizeSp = it)) }
        SliderRow("En fazla satır", draft.lyricsPanelLines.toFloat(), 1f..3f, "${draft.lyricsPanelLines}") { set(draft.copy(lyricsPanelLines = it.roundToInt().coerceIn(1, 3))) }
    }

    if (draft.lyricsCompact) SectionCard("Kapalı ada", "Tek satır; sığmazsa söylendikçe yana kayar") {
        SliderRow("Yazı boyutu", draft.lyricsCompactSizeSp, 8f..18f, "${draft.lyricsCompactSizeSp.toInt()} sp") { set(draft.copy(lyricsCompactSizeSp = it)) }
        SliderRow("Ada genişliği (sözler varken)", draft.lyricsCompactWidthDp, 120f..300f, "${draft.lyricsCompactWidthDp.toInt()} dp") { set(draft.copy(lyricsCompactWidthDp = it)) }
        Text("Not: Kamera deliği adanın tam ortasında olduğu için orta kısımdaki harfler deliğin arkasında kalabilir; genişlik arttıkça okunabilirlik artar.",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }

    if (draft.lyricsOutside) SectionCard("Adanın dışı", "Ayrı, dokunulmaz söz katmanı (ada genişleyince gizlenir)") {
        SliderRow("Yazı boyutu", draft.lyricsOutsideSizeSp, 12f..40f, "${draft.lyricsOutsideSizeSp.toInt()} sp") { set(draft.copy(lyricsOutsideSizeSp = it)) }
        SliderRow("En fazla satır", draft.lyricsOutsideLines.toFloat(), 1f..3f, "${draft.lyricsOutsideLines}") { set(draft.copy(lyricsOutsideLines = it.roundToInt().coerceIn(1, 3))) }
        SliderRow("Genişlik", draft.lyricsOutsideWidthDp, 180f..420f, "${draft.lyricsOutsideWidthDp.toInt()} dp") { set(draft.copy(lyricsOutsideWidthDp = it)) }
        SliderRow("Yükseklik (ekranın üstünden)", draft.lyricsOutsideYDp, 0f..760f, "${draft.lyricsOutsideYDp.toInt()} dp") { set(draft.copy(lyricsOutsideYDp = it)) }
        SliderRow("Yatay konum", draft.lyricsOutsideXDp, -150f..150f, "${draft.lyricsOutsideXDp.toInt()} dp") { set(draft.copy(lyricsOutsideXDp = it)) }
    }

    SectionCard("Renk", "Üç yerde de ortak") {
        SwitchRow("Albüm kapağı rengi", "Söz rengi, çalan şarkının kapağına göre değişir",
            draft.lyricsDynamicColor) { set(draft.copy(lyricsDynamicColor = it)) }
        SectionDivider()
        SubHeader(if (draft.lyricsDynamicColor) "Kapak yoksa kullanılacak renk" else "Söz rengi")
        key(version) { ColorPicker(draft.lyricsColorArgb) { set(draft.copy(lyricsColorArgb = it)) } }
        SliderRow("Söylenmemiş kısım", draft.lyricsDimAlpha, 0.1f..0.8f, "%${(draft.lyricsDimAlpha * 100).toInt()}") { set(draft.copy(lyricsDimAlpha = it)) }
    }

    SectionCard("Dolum ve senkron", "Satır şarkıyla birlikte soldan sağa dolar") {
        SwitchRow("Söylendikçe dolsun", "Kapatırsan satırın tamamı parlak görünür", draft.lyricsFillEnabled) { set(draft.copy(lyricsFillEnabled = it)) }
        SectionDivider()
        val off = draft.lyricsOffsetMs
        SliderRow("Senkron kaydırma", off.toFloat(), -2000f..2000f, (if (off > 0) "+$off" else "$off") + " ms") {
            set(draft.copy(lyricsOffsetMs = (it / 50f).roundToInt() * 50))
        }
        Text("+ değer sözleri daha erken, − değer daha geç gösterir.", color = cs.onSurfaceVariant, fontSize = 12.sp)
    }

    SectionCard("Parıltı (Glow)", "Söylenen kısmın etrafında") {
        SwitchRow("Parıltı efekti", null, draft.lyricsGlowEnabled) { set(draft.copy(lyricsGlowEnabled = it)) }
        if (draft.lyricsGlowEnabled) {
            SliderRow("Seviye", draft.lyricsGlowLevel, 0.1f..1f, "%${(draft.lyricsGlowLevel * 100).toInt()}") { set(draft.copy(lyricsGlowLevel = it)) }
        }
    }

    SectionCard("Satır geçiş animasyonu", "Bir satırdan diğerine geçerken") {
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
