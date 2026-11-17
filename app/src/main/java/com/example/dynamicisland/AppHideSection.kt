package com.example.dynamicisland

import android.content.Intent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private class InstalledApp(val pkg: String, val label: String, val icon: ImageBitmap?)

/** Başlatılabilir (launcher'da görünen) uygulamaları ada göre sıralı yükler. Kendi uygulamamız listeye girmez. */
private suspend fun loadInstalledApps(ctx: android.content.Context): List<InstalledApp> = withContext(Dispatchers.Default) {
    val pm = ctx.packageManager
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        .asSequence()
        .map { it.activityInfo.packageName }
        .distinct()
        .filter { it != ctx.packageName }
        .mapNotNull { pkg ->
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                InstalledApp(
                    pkg, pm.getApplicationLabel(info).toString(),
                    runCatching { pm.getApplicationIcon(info).toBitmap(72, 72).asImageBitmap() }.getOrNull(),
                )
            }.getOrNull()
        }
        .sortedBy { it.label.lowercase(Locale("tr", "TR")) }
        .toList()
}

/**
 * "Uygulamaya özel gizleme" için uygulama seçici: seçilenlerin özeti + tam ekran seçim penceresi (arama kutulu).
 * Seçili uygulamalar ön plandayken ada gizlenir.
 */
@Composable
fun HiddenAppsPicker(selected: List<String>, onChange: (List<String>) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    val apps by produceState<List<InstalledApp>?>(null) { value = loadInstalledApps(ctx) }

    val names = remember(selected, apps) {
        selected.map { pkg -> apps?.firstOrNull { it.pkg == pkg }?.label ?: pkg }
    }
    Text(
        if (selected.isEmpty()) "Henüz uygulama seçilmedi" else names.joinToString(", "),
        color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
    )
    IslandActionButton(if (selected.isEmpty()) "Uygulama seç" else "Uygulamaları düzenle (${selected.size})", { open = true })

    if (open) {
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var query by remember { mutableStateOf("") }
            val list = apps
            val q = query.trim().lowercase(Locale("tr", "TR"))
            Column(
                Modifier.fillMaxWidth().fillMaxHeight(0.88f).padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(20.dp)).background(cs.surface).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Ada gizlenecek uygulamalar", color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text("Bitti", color = cs.primary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { open = false }.padding(horizontal = 8.dp, vertical = 6.dp))
                }
                SearchField(query, { query = it }, hint = "Uygulama ara")
                if (list == null) {
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("Yükleniyor…", color = cs.onSurfaceVariant, fontSize = 14.sp)
                    }
                } else {
                    val shown = list.filter { q.isEmpty() || it.label.lowercase(Locale("tr", "TR")).contains(q) }
                    if (shown.isEmpty()) {
                        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            Text(if (list.isEmpty()) "Uygulama listesi alınamadı" else "Sonuç yok", color = cs.onSurfaceVariant, fontSize = 14.sp)
                        }
                    } else LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                        items(shown, key = { it.pkg }) { app ->
                            val on = app.pkg in selected
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                    .clickable(role = Role.Checkbox) {
                                        onChange(if (on) selected - app.pkg else selected + app.pkg)
                                    }
                                    .padding(vertical = 8.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val ic = app.icon
                                if (ic != null) Image(ic, null, Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)))
                                else Box(Modifier.size(36.dp).clip(RoundedCornerShape(9.dp)).background(cs.surfaceVariant))
                                Text(app.label, color = cs.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                Box(
                                    Modifier.size(22.dp).clip(CircleShape)
                                        .background(if (on) cs.primary else Color.Transparent)
                                        .then(if (on) Modifier else Modifier.background(cs.surfaceVariant)),
                                    contentAlignment = Alignment.Center,
                                ) { if (on) Text("✓", color = cs.onPrimary, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                            }
                        }
                    }
                }
            }
        }
    }
}
