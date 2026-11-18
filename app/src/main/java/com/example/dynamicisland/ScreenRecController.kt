package com.example.dynamicisland

import android.app.PendingIntent
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Sistem ekran kaydı (kayıt / işleniyor / kaydedildi). Servisten taşındı; davranış aynıdır, izin gerektirmez. */
class ScreenRecController(private val host: IslandHost) {

    var phase: RecPhase? = null
        private set
    var open: PendingIntent? = null
        private set
    private var since = 0L
    private var frozenMs = 0L
    private var count = 0
    private var thumb: ImageBitmap? = null
    private var stop: PendingIntent? = null
    private var timeoutJob: Job? = null
    private var savedJob: Job? = null
    private var thumbJob: Job? = null
    private var viaA11y = false     // kayıt yüzen çubuktaki süre yazısından algılandı (bildirimdeki "bitti" sinyallerini yok say)

    fun content(): IslandContent.ScreenRec? =
        phase?.let { IslandContent.ScreenRec(it, since, frozenMs, count, thumb) }

    private fun publish(expanded: Boolean) {
        val c = content() ?: return
        host.publishScreenRec(c, expanded)
    }

    private fun freeze() {
        if (frozenMs == 0L && since > 0L) frozenMs = (System.currentTimeMillis() - since).coerceAtLeast(0L)
    }

    /** Kayıt bitti ama kaydedildi bildirimi gelmediyse (iptal / hata) spinner sonsuza dek dönmesin. */
    private fun armTimeout() {
        timeoutJob?.cancel()
        timeoutJob = host.scope.launch { delay(20_000); if (phase == RecPhase.Processing) end() }
    }

    fun end() {
        timeoutJob?.cancel(); savedJob?.cancel(); thumbJob?.cancel()
        phase = null; since = 0L; frozenMs = 0L; count = 0
        thumb = null; stop = null; open = null; viaA11y = false
        if (host.content is IslandContent.ScreenRec) host.collapseSoon()
        else host.update { it }      // kayıt yan baloncuktaydı: baloncuk adaya geri birleşir
    }

    fun onScreenRec(e: IslandEvent.ScreenRec) {
        if (!host.settings.screenRecEnabled) return
        if (e.phase != RecPhase.Processing) timeoutJob?.cancel()
        savedJob?.cancel()
        val now = System.currentTimeMillis()
        when (e.phase) {
            RecPhase.Countdown -> {
                phase = RecPhase.Countdown; count = e.count; since = 0L; frozenMs = 0L; thumb = null
                publish(false)
                // Rakam bir yerde takılı kalırsa ada geri sayımda kalmasın
                timeoutJob = host.scope.launch { delay(6_000); if (phase == RecPhase.Countdown) end() }
            }
            RecPhase.Recording -> {
                val first = phase != RecPhase.Recording
                phase = RecPhase.Recording; stop = e.stop
                if (first) {
                    viaA11y = e.key == "a11y"
                    since = e.startedAt.takeIf { it in 1..now } ?: now
                    frozenMs = 0L; thumb = null
                    publish(false)
                } else if (host.content !is IslandContent.ScreenRec) publish(false)
            }
            RecPhase.Processing -> {
                // Kayıt görmediysek (ör. yüzen ada hazır beklerken) işleniyor animasyonu başlatma
                if (phase != RecPhase.Recording && phase != RecPhase.Processing) return
                freeze()
                if (phase != RecPhase.Processing) { phase = RecPhase.Processing; publish(false); armTimeout() }
            }
            RecPhase.Saved -> {
                // Kayıt başlamadan gelen "kaydedildi" bildirimini (eski kayıt vb.) yok say
                val already = phase == RecPhase.Saved
                if (!already && phase != RecPhase.Recording && phase != RecPhase.Processing) return
                freeze()
                open = e.open ?: open
                // Galeriden önizleme beklenirken ikinci bir bildirim gelirse bekleme yeniden başlatılmaz
                if (thumbJob?.isActive == true && e.thumb == null) return
                val th = e.thumb ?: thumb
                if (th == null && !already && canReadGallery()) {
                    // Bildirimde resim yok, galeri izni var: önce işleniyor animasyonu sürer, önizleme bulununca ada genişler
                    if (phase != RecPhase.Processing) { phase = RecPhase.Processing; publish(false) }
                    loadThumbFromGallery()
                    return
                }
                thumbJob?.cancel()
                finishSaved(th)
            }
        }
    }

    /** Bildirimde önizleme yoksa: izin verilmişse galerideki son ekran kaydının ilk karesi alınır (isteğe bağlı özellik). */
    private fun canReadGallery() =
        androidx.core.content.ContextCompat.checkSelfPermission(host.context, recPreviewPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Kaydedildi görünümü: ada genişler (süre sabit, "Kayıt galeriye kaydedildi", sağda önizleme) ve 5 sn sonra kapanır. */
    private fun finishSaved(th: ImageBitmap?) {
        phase = RecPhase.Saved
        if (th != null) thumb = th
        publish(true)
        savedJob?.cancel()
        savedJob = host.scope.launch { delay(5_000); end() }
    }

    private fun loadThumbFromGallery() {
        thumbJob?.cancel()
        val startMs = if (since > 0L) since else System.currentTimeMillis() - 60_000L
        val sinceSec = (startMs / 1000 - 10).coerceAtLeast(0L)
        thumbJob = host.scope.launch {
            // Dosya galeriye biraz geç düşebilir: kısa aralıklarla birkaç kez denenir
            repeat(10) {
                val bmp = withContext(Dispatchers.IO) { queryLatestThumb(sinceSec) }
                if (bmp != null) {
                    if (phase == RecPhase.Processing) finishSaved(bmp.asImageBitmap())
                    return@launch
                }
                delay(400)
            }
            // Bulunamadıysa (yaklaşık 4 sn) yine de kaydedildi görünümüne geç, önizleme yerine gri kutu kalır
            if (phase == RecPhase.Processing) finishSaved(null)
        }
    }

    private fun queryLatestThumb(sinceSec: Long): android.graphics.Bitmap? {
        val resolver = host.context.contentResolver
        val col = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var found: Long? = null
        runCatching {
            val proj = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                android.provider.MediaStore.Video.Media.RELATIVE_PATH,
            )
            resolver.query(
                col, proj, "${android.provider.MediaStore.Video.Media.DATE_ADDED} >= ?", arrayOf(sinceSec.toString()),
                "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                var newest: Long? = null
                var rec: Long? = null
                while (rec == null && c.moveToNext()) {
                    val id = c.getLong(0)
                    val txt = (c.getString(1).orEmpty() + " " + c.getString(2).orEmpty()).lowercase()
                    if (listOf("record", "screen", "ekran").any { txt.contains(it) }) rec = id
                    if (newest == null) newest = id
                }
                found = rec ?: newest
            }
        }
        val id = found ?: return null
        return runCatching {
            resolver.loadThumbnail(android.content.ContentUris.withAppendedId(col, id), android.util.Size(256, 256), null)
        }.getOrNull()
    }

    fun onScreenRecGone(e: IslandEvent.ScreenRecGone) {
        if (!host.settings.screenRecEnabled) return
        // Süre yazısından algılanan kaydı yalnızca yine o yol bitirir (bekleme bildirimi kayıt sürerken de güncellenebilir)
        if (viaA11y && phase == RecPhase.Recording && e.key != "a11y") return
        when {
            // Kayıt bildirimi kalktı: kayıt bitti, işleniyor (beyaz dönen halka) -> kaydedildi bildirimi beklenir
            e.phase == RecPhase.Recording && phase == RecPhase.Recording -> {
                freeze(); phase = RecPhase.Processing; publish(false); armTimeout()
            }
            // Geri sayım bildirimi kalktı: kayıt başlamadıysa (iptal) kapat
            e.phase == RecPhase.Countdown && phase == RecPhase.Countdown -> {
                timeoutJob?.cancel()
                timeoutJob = host.scope.launch { delay(1_500); if (phase == RecPhase.Countdown) end() }
            }
        }
    }

    /** Adadaki durdur düğmesi: sistem kaydının bildirimdeki "Durdur" eylemini tetikler. */
    fun onStop() {
        if (stop != null) host.send(stop) else IslandAccessibilityService.stopScreenRec()
        freeze(); phase = RecPhase.Processing; publish(false); armTimeout()
    }
}
