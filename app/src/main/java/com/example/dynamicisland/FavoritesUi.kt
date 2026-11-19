package com.example.dynamicisland

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------- Adanın içindeki favori paneli
@Composable
fun FavoritesPanel(c: IslandContent.Favorites, topInset: Dp, onLaunch: (String) -> Unit) {
    // Üstte kamera bölgesi boş kalır; uygulamalar onun altına dizilir
    Column(
        Modifier.fillMaxSize().padding(top = topInset + 6.dp, start = 12.dp, end = 12.dp, bottom = 8.dp),
    ) {
        c.apps.chunked(FAV_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth().height(FAV_ROW_DP.dp)) {
                row.forEach { app -> FavCell(app, Modifier.weight(1f), onLaunch) }
                repeat(FAV_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun FavCell(app: FavApp, modifier: Modifier, onLaunch: (String) -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable { onLaunch(app.pkg) }.padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val shape = RoundedCornerShape(12.dp)
        val icon = app.icon
        if (icon != null) {
            Image(icon, null, Modifier.size(48.dp).clip(shape), contentScale = ContentScale.Fit)
        } else {
            Box(Modifier.size(48.dp).clip(shape).background(Pal.c("fav_icon_bg")))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            app.label, color = Pal.c("fav_label"), fontSize = 10.5.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

// ---------------------------------------------------------------- Ayarlar bölümü
@Composable
fun FavoritesSection(draft: IslandSettings, set: (IslandSettings) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var showPicker by remember { mutableStateOf(false) }
    val selected = draft.favoritePkgList()

    SectionCard("Favori uygulamalar", "Boştayken ada genişleyip seçtiğin uygulamaları gösterir") {
        SwitchRow(
            "Favori uygulamalar", "Ada boştayken açılır; bir uygulamaya dokununca o uygulama açılır",
            draft.favoritesEnabled,
        ) { set(draft.copy(favoritesEnabled = it)) }

        if (draft.favoritesEnabled) {
            if (!draft.showIdle) {
                Text(
                    "Çalışması için yukarıdaki \"Boştayken de göster\" ayarı açık olmalı.",
                    color = cs.error, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            SubHeader("Ne zaman açılsın?")
            RadioRow("Dokununca", "Adaya kısa dokunuşla açılır", draft.favoritesTrigger == 0) {
                set(draft.copy(favoritesTrigger = 0))
            }
            RadioRow("Basılı tutunca", "Yalnızca adaya basılı tutunca açılır; kısa dokunuş bir şey yapmaz", draft.favoritesTrigger == 1) {
                set(draft.copy(favoritesTrigger = 1))
            }
            SectionDivider()
            SwitchRow(
                "Açılış animasyonu",
                "Uygulamaya dokununca ada animasyonu ekranı kaplar, ardından uygulama açılır",
                draft.favoriteLaunchAnim,
            ) { set(draft.copy(favoriteLaunchAnim = it)) }
            if (draft.favoriteLaunchAnim) {
                SliderRow(
                    "Animasyon hızı", draft.favoriteLaunchSpeed, 0.5f..2.5f,
                    "%.1fx".format(draft.favoriteLaunchSpeed),
                ) { set(draft.copy(favoriteLaunchSpeed = (it * 10f).toInt() / 10f)) }
                SubHeader("Animasyon stili")
                SwitchRow(
                    "Klasik", "Adadan siyah ekran çıkıp ekranı kaplar",
                    draft.favLaunchClassic,
                ) { set(draft.copy(favLaunchClassic = it, favLaunchAtomic = if (!it) true else draft.favLaunchAtomic)) }
                SwitchRow(
                    "Atom ada", "Adadan açılan siyah kart huni gibi genişleyip ekranı kaplar (OriginOS tarzı)",
                    draft.favLaunchAtomic,
                ) { set(draft.copy(favLaunchAtomic = it, favLaunchClassic = if (!it) true else draft.favLaunchClassic)) }
                Text(
                    "İkisi de açıksa her açılışta rastgele biri oynar.",
                    color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            SectionDivider()
            if (selected.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    selected.forEach { pkg -> AppIcon(pkg, 40.dp) }
                }
            }
            IslandActionButton(
                if (selected.isEmpty()) "Uygulama seç" else "Uygulamaları düzenle (${selected.size}/$MAX_FAV)",
                { showPicker = true },
            )
        }
    }

    if (showPicker) {
        FavoritePickerDialog(selected, { showPicker = false }) { set(draft.copy(favoritePkgs = it.joinToString(","))) }
    }
}

@Composable
private fun AppIcon(pkg: String, size: Dp) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, pkg) {
        value = withContext(Dispatchers.Default) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    val shape = RoundedCornerShape(size / 4)
    val i = icon
    if (i != null) Image(i, null, Modifier.size(size).clip(shape))
    else Box(Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.surfaceVariant))
}

private data class AppEntry(val pkg: String, val label: String)

@Composable
private fun FavoritePickerDialog(initial: List<String>, onDismiss: () -> Unit, onDone: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var sel by remember { mutableStateOf(initial) }
    val apps by produceState<List<AppEntry>?>(null) {
        value = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching {
                pm.queryIntentActivities(i, 0)
                    .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                    .distinctBy { it.pkg }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Favori uygulamalar (${sel.size}/$MAX_FAV)", fontWeight = FontWeight.SemiBold) },
        text = {
            val list = apps
            if (list == null) {
                Text("Uygulamalar yükleniyor…", color = cs.onSurfaceVariant)
            } else {
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(list, key = { it.pkg }) { a ->
                        val on = a.pkg in sel
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    sel = if (on) sel - a.pkg else if (sel.size < MAX_FAV) sel + a.pkg else sel
                                }
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            AppIcon(a.pkg, 36.dp)
                            Text(a.label, Modifier.weight(1f), color = cs.onSurface, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(checked = on, onCheckedChange = null)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ onDone(sel); onDismiss() }) { Text("Tamam") } },
        dismissButton = { TextButton(onDismiss) { Text("İptal") } },
    )
}
