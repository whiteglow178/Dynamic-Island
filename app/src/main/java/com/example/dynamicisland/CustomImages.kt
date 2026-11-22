package com.example.dynamicisland

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Galeriden seçilen fotoğraflar KOPYALANMAZ ve diske yazılmaz.
 * Yalnızca fotoğrafın adresi (URI) ayarlara kaydedilir; fotoğraf gerektiğinde galerideki yerinden
 * okunur, küçültülür ve sadece bellekte (RAM) tutulur. Uygulama kapanınca bellekteki kopya da gider.
 * Ağ erişimi yoktur; fotoğraflar uygulamanın dışına gönderilmez.
 *
 * Erişim, sistemin belge seçicisinin verdiği kalıcı okuma izniyle sağlanır (depolama izni gerekmez).
 */
object CustomImages {
    enum class Slot(val maxPx: Int) {
        BG_BOTH(1280),      // tek fotoğraf: kapalı + açık ada
        BG_COMPACT(1280),   // yalnızca kapalı ada
        BG_EXPANDED(1280),  // yalnızca açık ada
        ART(512),           // albüm kapağı yerine
        DESIGN(1280),       // Ana Sayfa > Özel ada tasarımı: fotoğraf
    }

    // Her slot için tek giriş: (uri, bitmap). Yalnızca bellekte.
    private val cache = HashMap<Slot, Pair<String, ImageBitmap>>()

    fun peek(slot: Slot, uri: String): ImageBitmap? =
        synchronized(cache) { cache[slot]?.takeIf { it.first == uri }?.second }

    /** Seçiciden dönen adrese kalıcı OKUMA izni alır (yalnızca bu fotoğraf için). */
    fun grant(ctx: Context, uri: Uri) {
        try {
            ctx.applicationContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) { /* kalıcı izin verilmeyen kaynak: oturum boyunca yine okunabilir */ }
    }

    /** Fotoğraf kaldırılınca/değişince, başka yerde kullanılmıyorsa verilen izni geri bırakır. */
    fun releaseUnused(ctx: Context, old: String, now: IslandSettings) {
        if (old.isEmpty()) return
        val used = Slot.values().any { now.imageUri(it) == old } || now.customBgVideoUri == old || now.customArtVideoUri == old
        if (used) return
        try {
            ctx.applicationContext.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) { }
        synchronized(cache) { cache.entries.removeAll { it.value.first == old } }
    }

    fun releaseAll(ctx: Context, s: IslandSettings) {
        (Slot.values().map { s.imageUri(it) } + s.customBgVideoUri + s.customArtVideoUri).filter { it.isNotEmpty() }.distinct().forEach { u ->
            try {
                ctx.applicationContext.contentResolver.releasePersistableUriPermission(Uri.parse(u), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Throwable) { }
        }
        synchronized(cache) { cache.clear() }
    }

    /** Adresten okuyup küçültür (yalnızca bellek). Okunamazsa (silinmiş, izin yok) null. */
    suspend fun load(ctx: Context, slot: Slot, uriStr: String): ImageBitmap? = withContext(Dispatchers.IO) {
        if (uriStr.isEmpty()) return@withContext null
        peek(slot, uriStr)?.let { return@withContext it }
        try {
            val uri = Uri.parse(uriStr)
            val cr = ctx.applicationContext.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= slot.maxPx) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            var bmp: Bitmap = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@withContext null

            val rot = readRotation(cr, uri)
            val longSide = max(bmp.width, bmp.height)
            val scale = if (longSide > slot.maxPx) slot.maxPx.toFloat() / longSide else 1f
            if (rot != 0 || scale < 1f) {
                val m = Matrix().apply { if (rot != 0) postRotate(rot.toFloat()); if (scale < 1f) postScale(scale, scale) }
                val t = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                if (t !== bmp) bmp.recycle()
                bmp = t
            }
            val img = bmp.asImageBitmap()
            synchronized(cache) { cache[slot] = uriStr to img }
            img
        } catch (_: Throwable) {
            null
        }
    }

    private fun readRotation(cr: ContentResolver, uri: Uri): Int {
        if (Build.VERSION.SDK_INT < 24) return 0
        return try {
            cr.openInputStream(uri)?.use { s ->
                when (android.media.ExifInterface(s).getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL,
                )) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (_: Throwable) { 0 }
    }
}

fun IslandSettings.imageUri(slot: CustomImages.Slot): String = when (slot) {
    CustomImages.Slot.BG_BOTH -> customBgBothUri
    CustomImages.Slot.BG_COMPACT -> customBgCompactUri
    CustomImages.Slot.BG_EXPANDED -> customBgExpandedUri
    CustomImages.Slot.ART -> customArtUri
    CustomImages.Slot.DESIGN -> more.designPhotoUri
}

fun IslandSettings.withImageUri(slot: CustomImages.Slot, v: String): IslandSettings = when (slot) {
    CustomImages.Slot.BG_BOTH -> copy(customBgBothUri = v)
    CustomImages.Slot.BG_COMPACT -> copy(customBgCompactUri = v)
    CustomImages.Slot.BG_EXPANDED -> copy(customBgExpandedUri = v)
    CustomImages.Slot.ART -> copy(customArtUri = v)
    CustomImages.Slot.DESIGN -> copy(more = more.copy(designPhotoUri = v))
}

/** Adres boşsa null; değilse galeriden (arka planda) okunmuş görsel. Adres değişince yeniden yüklenir. */
@Composable
fun rememberCustomImage(slot: CustomImages.Slot, uri: String): ImageBitmap? {
    val ctx = LocalContext.current.applicationContext
    var bmp by remember(slot, uri) {
        mutableStateOf(if (uri.isEmpty()) null else CustomImages.peek(slot, uri))
    }
    LaunchedEffect(slot, uri) {
        bmp = if (uri.isEmpty()) null else CustomImages.load(ctx, slot, uri)
    }
    return bmp
}
