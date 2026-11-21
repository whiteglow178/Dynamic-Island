package com.example.dynamicisland

import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Oyuncak ada ayar ekranı parçaları: ritim kısayolu kaydedici ve tek uygulama seçici.
 */

private data class PickApp(val pkg: String, val label: String)

/** Paketin görünen adı (yüklü değilse paket adı). */
@Composable
private fun appLabel(pkg: String): String {
    val ctx = LocalContext.current
    return remember(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
    }
}

/**
 * Ritim kısayolları: kayıtlı ritimler (sil düğmesiyle) + yeni ritim kaydı.
 * Kayıt: kutuya ritmi dokun (en az 3 dokunuş), durunca ritim yakalanır, sonra uygulama seçilir.
 */
@Composable
fun RhythmShortcutsEditor(value: String, onChange: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val list = Rhythm.parse(value)
    list.forEach { (pat, pkg) ->
        Row(
            Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(Rhythm.pretty(pat), color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text(appLabel(pkg), color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            TextButton({ onChange(Rhythm.join(list.filterNot { it.first == pat })) }) { Text("Sil") }
        }
    }
    if (list.size >= 6) {
        Text("En fazla 6 kısayol eklenebilir.", color = cs.onSurfaceVariant, fontSize = 12.sp)
        return
    }

    // ---- Yeni ritim kaydı
    val taps = remember { mutableStateListOf<Long>() }
    var captured by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    // Dokunuşlar bitince (0,9 sn ara) ritim yakalanır
    LaunchedEffect(taps.size) {
        if (taps.isEmpty()) return@LaunchedEffect
        delay(900)
        if (taps.size >= 3) captured = Rhythm.classify(taps.zipWithNext { a, b -> b - a })
        taps.clear()
    }
    Box(
        Modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(18.dp)).background(cs.surfaceVariant)
            .pointerInput(Unit) { detectTapGestures { taps.add(SystemClock.uptimeMillis()); captured = null } },
        contentAlignment = Alignment.Center,
    ) {
        val c = captured
        Text(
            when {
                taps.isNotEmpty() -> "●".repeat(taps.size)
                c != null -> Rhythm.pretty(c)
                else -> "Yeni ritim: buraya ritmini dokun"
            },
            color = if (c != null || taps.isNotEmpty()) cs.primary else cs.onSurfaceVariant,
            fontSize = if (c != null || taps.isNotEmpty()) 22.sp else 14.sp, fontWeight = FontWeight.SemiBold,
        )
    }
    val c = captured
    if (c != null) {
        val clash = list.firstOrNull { it.first == c }
        Text(
            if (clash != null) "Bu ritim zaten var: seçeceğin uygulamayla değiştirilir." else "Ritim yakalandı. Şimdi açılacak uygulamayı seç.",
            color = cs.onSurfaceVariant, fontSize = 12.sp,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            IslandActionButton("Uygulama seç", { picking = true }, Modifier.weight(1f), tonal = false)
            IslandActionButton("Yeniden", { captured = null }, Modifier.weight(1f))
        }
    } else {
        Text("Dokunuşlar arasındaki kısa ve uzun aralıklar ritmi oluşturur (en az 3 dokunuş). Örnek: ● ● — ●",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
    if (picking && c != null) {
        SingleAppPicker(onDismiss = { picking = false }) { pkg ->
            onChange(Rhythm.join(list.filterNot { it.first == c } + (c to pkg)))
            captured = null
            picking = false
        }
    }
}

@Composable
private fun SingleAppPicker(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val apps by produceState<List<PickApp>?>(null) {
        value = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            runCatching {
                pm.queryIntentActivities(i, 0)
                    .map { PickApp(it.activityInfo.packageName, it.loadLabel(pm).toString()) }
                    .distinctBy { it.pkg }
                    .filter { it.pkg != ctx.packageName }
                    .sortedBy { it.label.lowercase() }
            }.getOrDefault(emptyList())
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Açılacak uygulama", fontWeight = FontWeight.SemiBold) },
        text = {
            val l = apps
            if (l == null) Text("Uygulamalar yükleniyor…", color = cs.onSurfaceVariant)
            else LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(l, key = { it.pkg }) { a ->
                    Text(
                        a.label, color = cs.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onPick(a.pkg) }
                            .padding(vertical = 10.dp, horizontal = 6.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onDismiss) { Text("İptal") } },
    )
}
