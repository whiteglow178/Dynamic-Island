package com.example.dynamicisland

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
        Text("Önizlemeyi görmek için telefonu sağa sola, öne arkaya eğ.", color = dim, fontSize = 12.sp)
        SwitchRow("Portal ada", "Boştaki adanın içi pencere gibi olur; arkasında uzak ve yakın katmanlar farklı hızda kayar. Ekran kapalıyken sensör dinlenmez. Açıkken özel tasarım ve kamuflaj boştaki adada görünmez",
            cur.portalEnabled) { set(cur.copy(portalEnabled = it, camoEnabled = if (it) false else cur.camoEnabled)) }
        SegmentedTabs(PORTAL_NAMES, cur.portalScene) { set(cur.copy(portalScene = it)) }
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
            cur.camoEnabled) { set(cur.copy(camoEnabled = it, portalEnabled = if (it) false else cur.portalEnabled)) }
        if (cur.camoEnabled) {
            SubHeader("Kamuflajın duracağı uygulamalar")
            Text("Bu uygulamalar açıkken ekran görüntüsü hiç alınmaz; ada normal rengine döner. Bankacılık, mesajlaşma, şifre yöneticisi gibi uygulamaları ekleyebilirsin.",
                color = dim, fontSize = 12.sp, lineHeight = 17.sp)
            HiddenAppsPicker(cur.camoSkipList(), { list -> set(cur.copy(camoSkipPkgs = list.joinToString(","))) }, title = "Kamuflajın duracağı uygulamalar")
        }
        Text(
            "Android 11 ve üstü gerekir. Güncellemeden sonra ilk kez açıyorsan Ayarlar > Erişilebilirlik'ten Omni Island'ı bir kez kapatıp aç (ekran rengini okuma yeteneği böyle etkinleşir)." +
                if (cur.camoEnabled && CamoState.unsupported) "\n⚠ Ekran rengi şu an okunamıyor: erişilebilirliği kapatıp açmayı dene." else "",
            color = dim, fontSize = 12.sp, lineHeight = 17.sp,
        )
    }

    // ---------------------------------------------------------------- Zaman kapsülü
    TimeCapsuleSection()
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
