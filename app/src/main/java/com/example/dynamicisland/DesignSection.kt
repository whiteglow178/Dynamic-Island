package com.example.dynamicisland

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Ana Sayfa > Özel ada tasarımı (iOS'taki "Custom Design" gibi): boştaki ada seçilen desenle ya da galeriden
 * seçilen fotoğrafla dolar. Fotoğraf seçiliyse boştaki adaya dokununca ada çentiksiz, büyük bir karta açılıp
 * fotoğrafın tamamını gösterir. Ayarlar doğrudan kaydedilir (Ayarlar sekmesine gerek yok).
 */
@Composable
fun CustomDesignSection() {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val scope = rememberCoroutineScope()
    var m by remember { mutableStateOf<MoreSettings?>(null) }
    var idleOn by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        val s = repo.flow.first()
        m = s.more; idleOn = s.showIdle
    }
    // Değişiklikler 250 ms sakinleşince yalnızca tasarım alanları yazılır (diğer ayarlara dokunulmaz)
    LaunchedEffect(m) {
        val v = m ?: return@LaunchedEffect
        delay(250)
        repo.update { s ->
            s.copy(more = s.more.copy(
                designEnabled = v.designEnabled, designStyle = v.designStyle, designPhotoUri = v.designPhotoUri,
                designAlways = v.designAlways, designPhotoExpand = v.designPhotoExpand, designPhotoTrigger = v.designPhotoTrigger,
                designPhotoWDp = v.designPhotoWDp, designPhotoHDp = v.designPhotoHDp, designPhotoSec = v.designPhotoSec,
            ))
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            CustomImages.grant(ctx, uri)
            val u = uri.toString()
            if (CustomImages.load(ctx, CustomImages.Slot.DESIGN, u) != null) {
                val old = m?.designPhotoUri.orEmpty()
                m = m?.copy(designPhotoUri = u, designStyle = DESIGN_PHOTO, designEnabled = true)
                if (old.isNotEmpty() && old != u) {
                    val full = repo.flow.first()
                    CustomImages.releaseUnused(ctx, old, full.copy(more = full.more.copy(designPhotoUri = u)))
                }
            } else {
                Toast.makeText(ctx.applicationContext, "Fotoğraf açılamadı", Toast.LENGTH_SHORT).show()
            }
        }
    }
    val pickPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    SectionCard("Özel ada tasarımı", "Adayı bir desenle ya da kendi fotoğrafınla süsle") {
        val cur = m
        if (cur == null) {
            Text("Yükleniyor…", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            return@SectionCard
        }
        val set: (MoreSettings) -> Unit = { m = it }
        val photo = rememberCustomImage(CustomImages.Slot.DESIGN, cur.designPhotoUri)

        // Önizleme: koyu ekranın üstünde ada (ekran görüntüsündeki gibi)
        Box(
            Modifier.fillMaxWidth().height(86.dp).clip(RoundedCornerShape(22.dp)).background(Color(0xFF101114)),
            contentAlignment = Alignment.TopCenter,
        ) {
            val shape = RoundedCornerShape(50)
            val pillMod = Modifier.padding(top = 14.dp).size(width = 150.dp, height = 42.dp).clip(shape)
            if (cur.designEnabled) DesignFill(cur.designStyle, photo, pillMod)
            else Box(pillMod.background(Color.Black))
            Text("11:27", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 22.dp, top = 24.dp))
        }

        SwitchRow("Özel tasarım", "Boştaki ada seçtiğin desenle ya da fotoğrafla dolar", cur.designEnabled) { set(cur.copy(designEnabled = it)) }

        // Desen seçimi: yuvarlak örnekler + fotoğraf
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0..DESIGN_PHOTO) {
                val sel = cur.designEnabled && cur.designStyle == i
                val ring = if (sel) MaterialTheme.colorScheme.primary else Color.Transparent
                Box(
                    Modifier.size(54.dp).border(2.5.dp, ring, CircleShape).padding(5.dp).clip(CircleShape)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (i == DESIGN_PHOTO && cur.designPhotoUri.isEmpty()) pickPhoto()
                            else set(cur.copy(designStyle = i, designEnabled = true))
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (i == DESIGN_PHOTO && photo == null) {
                        // Henüz fotoğraf yok: "+" düğmesi
                        val plusColor = MaterialTheme.colorScheme.onSurfaceVariant
                        Box(Modifier.size(44.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape))
                        Canvas(Modifier.size(18.dp)) {
                            val w = size.width
                            drawLine(plusColor, Offset(w / 2f, 0f), Offset(w / 2f, w), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
                            drawLine(plusColor, Offset(0f, w / 2f), Offset(w, w / 2f), strokeWidth = 2.4.dp.toPx(), cap = StrokeCap.Round)
                        }
                    } else {
                        DesignFill(i, photo, Modifier.size(44.dp).clip(CircleShape))
                    }
                }
            }
        }
        Text(
            DESIGN_NAMES.getOrElse(cur.designStyle) { "" } + if (!cur.designEnabled) " (kapalı)" else "",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
        )

        SwitchRow("Olaylarda da göster", "Müzik, bildirim gibi bir olay varken de kapalı adanın zemini bu tasarım olur (ada genişleyince normal görünüme geçer)",
            cur.designAlways) { set(cur.copy(designAlways = it)) }

        SectionDivider()
        SubHeader("Fotoğraf")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IslandActionButton(if (cur.designPhotoUri.isEmpty()) "Fotoğraf seç" else "Fotoğrafı değiştir", { pickPhoto() }, Modifier.weight(1f))
            if (cur.designPhotoUri.isNotEmpty()) IslandActionButton("Kaldır", modifier = Modifier.weight(1f), onClick = {
                val old = cur.designPhotoUri
                val next = cur.copy(designPhotoUri = "", designStyle = if (cur.designStyle == DESIGN_PHOTO) 0 else cur.designStyle)
                m = next
                scope.launch {
                    val full = repo.flow.first()
                    CustomImages.releaseUnused(ctx, old, full.copy(more = full.more.copy(designPhotoUri = "")))
                }
            })
        }
        SwitchRow(
            "Dokununca fotoğrafı büyüt",
            "Fotoğraf tasarımı seçiliyken boştaki adaya dokununca ada genişleyip fotoğrafın tamamını gösterir. Genişleyen fotoğrafın üstünde çentik çizilmez. Tekrar dokununca ya da süre dolunca küçülür",
            cur.designPhotoExpand,
        ) { set(cur.copy(designPhotoExpand = it)) }
        if (cur.designPhotoExpand) {
            SegmentedTabs(listOf("Dokununca", "Basılı tutunca"), cur.designPhotoTrigger) { set(cur.copy(designPhotoTrigger = it)) }
            SliderRow("Genişlik", cur.designPhotoWDp, 160f..420f, "${cur.designPhotoWDp.roundToInt()} dp") { set(cur.copy(designPhotoWDp = it)) }
            SliderRow("Yükseklik", cur.designPhotoHDp, 60f..420f, "${cur.designPhotoHDp.roundToInt()} dp") { set(cur.copy(designPhotoHDp = it)) }
            SliderRow("Açık kalma süresi", cur.designPhotoSec, 1f..30f, "${cur.designPhotoSec.roundToInt()} sn") { set(cur.copy(designPhotoSec = it)) }
            // Genişlemiş halin önizlemesi (oranı ayarlardaki boyutla aynı)
            if (photo != null) {
                val ratio = (cur.designPhotoWDp / cur.designPhotoHDp).coerceIn(0.4f, 6f)
                val pw = 240f
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DesignFill(
                        DESIGN_PHOTO, photo,
                        Modifier.width(pw.dp).height((pw / ratio).dp).clip(RoundedCornerShape((36f * pw / cur.designPhotoWDp).coerceIn(12f, 36f).dp)),
                    )
                }
            }
            Text(
                "Favoriler ya da Çeviri de aynı dokunuşla açılacak şekilde ayarlıysa önce fotoğraf açılır." +
                    if (!idleOn) " Not: Ayarlar > Görünürlük > \"Boştayken de göster\" kapalı; özel tasarımın görünmesi için açmalısın." else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}
