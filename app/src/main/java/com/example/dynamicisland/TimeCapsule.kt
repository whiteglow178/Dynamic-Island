package com.example.dynamicisland

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

/** Bir zaman kapsülü: yazılan not (ve isteğe bağlı fotoğraf), [dueAt] zamanı gelince adada açılır. */
data class Capsule(
    val id: Long,
    val text: String,
    val photo: String,        // galeri adresi (kopyalanmaz); boş = fotoğraf yok
    val createdAt: Long,
    val dueAt: Long,
    val delivered: Boolean = false,
)

/** Kapsüller cihazda (yalnızca bu uygulamanın ayarlarında) saklanır. */
object CapsuleStore {
    private const val FILE = "island_capsules"
    private const val KEY = "list"

    fun list(ctx: Context): List<Capsule> {
        val raw = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            (0 until a.length()).map { i ->
                val o = a.getJSONObject(i)
                Capsule(
                    o.getLong("id"), o.optString("text"), o.optString("photo"),
                    o.optLong("created"), o.optLong("due"), o.optBoolean("done"),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun write(ctx: Context, items: List<Capsule>) {
        val a = JSONArray()
        items.forEach { c ->
            a.put(JSONObject().put("id", c.id).put("text", c.text).put("photo", c.photo)
                .put("created", c.createdAt).put("due", c.dueAt).put("done", c.delivered))
        }
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
    }

    @Synchronized fun add(ctx: Context, c: Capsule) = write(ctx, list(ctx) + c)
    @Synchronized fun remove(ctx: Context, id: Long) = write(ctx, list(ctx).filter { it.id != id })
    @Synchronized fun markDelivered(ctx: Context, id: Long) = write(ctx, list(ctx).map { if (it.id == id) it.copy(delivered = true) else it })

    /** Zamanı gelmiş ve henüz açılmamış en eski kapsül. */
    fun nextDue(ctx: Context, now: Long): Capsule? = list(ctx).filter { !it.delivered && it.dueAt <= now }.minByOrNull { it.dueAt }

    /** Bekleyen kapsüllerin fotoğraf adresleri (bu fotoğrafların okuma izni bırakılmasın). */
    fun uris(ctx: Context): Set<String> = list(ctx).filter { !it.delivered && it.photo.isNotEmpty() }.map { it.photo }.toSet()
}

/** Kapsülün adadaki kart yüksekliği (dp): fotoğraf varsa daha uzun. */
fun capsuleHeightDp(c: IslandContent.Capsule): Float = if (c.photo.isNotEmpty()) 252f else 142f

/** "3 ay önce" gibi göreli süre. */
fun capsuleAgo(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    val days = ((now - createdAt) / 86_400_000L).coerceAtLeast(0L)
    return when {
        days < 1L -> "bugün"
        days < 30L -> "$days gün önce"
        days < 365L -> "${days / 30L} ay önce"
        else -> "${days / 365L} yıl önce"
    }
}

/** Adada açılan kapsül kartı: kum saati + "Geçmişten bir mesaj", ne zaman yazıldığı, varsa fotoğraf ve not. */
@Composable
internal fun CapsuleContent(c: IslandContent.Capsule) {
    val photo = rememberCustomImage(CustomImages.Slot.CAPSULE, c.photo)
    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 30.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val gold = Color(0xFFFFC857)
                val glass = Path().apply {
                    moveTo(w * 0.2f, w * 0.08f); lineTo(w * 0.8f, w * 0.08f); lineTo(w * 0.5f, w * 0.5f)
                    lineTo(w * 0.8f, w * 0.92f); lineTo(w * 0.2f, w * 0.92f); lineTo(w * 0.5f, w * 0.5f); close()
                }
                drawPath(glass, gold, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round))
                drawLine(gold, Offset(w * 0.32f, w * 0.82f), Offset(w * 0.68f, w * 0.82f), strokeWidth = w * 0.14f, cap = StrokeCap.Round)
            }
            Spacer(Modifier.size(8.dp))
            Text("Geçmişten bir mesaj", color = Pal.c("island_text"), fontWeight = FontWeight.Bold, fontSize = 15.sp,
                maxLines = 1, modifier = Modifier.weight(1f))
            Text(capsuleAgo(c.createdAt), color = Pal.c("island_text_dim"), fontSize = 13.sp, maxLines = 1)
        }
        if (c.photo.isNotEmpty()) {
            if (photo != null) {
                Image(
                    bitmap = photo, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
                    modifier = Modifier.fillMaxWidth().height(104.dp).clip(RoundedCornerShape(14.dp)),
                )
            } else Spacer(Modifier.height(104.dp))
        }
        if (c.text.isNotBlank()) {
            Text(c.text, color = Pal.c("island_text"), fontSize = 15.sp, lineHeight = 20.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}
