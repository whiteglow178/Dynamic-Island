package com.example.dynamicisland

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.ui.graphics.ImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class IslandNotificationListener : NotificationListenerService() {

    private val msm by lazy { getSystemService(MediaSessionManager::class.java) }
    private var controller: MediaController? = null
    private var lastPublished: IslandContent.Media? = null
    private var scope: CoroutineScope? = null

    // Albüm kapağı önbelleği: aynı parça için tekrar tekrar ölçeklenmez
    private var artKey: String? = null
    private var art: ImageBitmap? = null
    private val ART_MAX = 512        // kapak bu boyuta kadar tam çözünürlükte tutulur (eskiden 160: pikselliydi)
    private val SHARP_MIN = 300      // bundan küçük bitmap yeterince net sayılmaz, URI varsa o yüklenir
    private var artColor: Int = 0

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { runCatching { publishMedia() } }
        override fun onPlaybackStateChanged(state: PlaybackState?) { runCatching { publishMedia() } }
        override fun onSessionDestroyed() { runCatching { bind(null) } }
    }
    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { runCatching { bind(it) } }

    companion object {
        @Volatile private var instance: IslandNotificationListener? = null

        /**
         * Ada servisi (yeniden) başlayınca çağrılır: o an çalan müziği tekrar yayınlar.
         * Dinleyici hiç bağlı değilse sistemden yeniden bağlanmasını ister.
         */
        fun republish(ctx: android.content.Context, force: Boolean = false) {
            val i = instance
            if (i != null) {
                i.lastPublished = null
                runCatching { i.bind(i.msm.getActiveSessions(ComponentName(i, IslandNotificationListener::class.java))) }
                    .onFailure { i.publishMedia() }
            } else {
                val cn = ComponentName(ctx, IslandNotificationListener::class.java)
                runCatching { NotificationListenerService.requestRebind(cn) }
                if (force) {
                    // Bazı cihazlar requestRebind'ı yok sayar (süreç öldürüldükten sonra): bileşeni kapatıp açmak yeniden bağlanmayı zorlar
                    runCatching {
                        val pm = ctx.packageManager
                        pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                        pm.setComponentEnabledSetting(cn, android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED, android.content.pm.PackageManager.DONT_KILL_APP)
                    }
                }
            }
        }
    }

    override fun onListenerConnected() {
        instance = this
        scope?.cancel()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { sc ->
            sc.launch { IslandBus.commands.collect(::handleCommand) }
        }
        val cn = ComponentName(this, IslandNotificationListener::class.java)
        // Bu çağrılar SecurityException fırlatabilir; yakalanmazsa süreç çöker ve erişilebilirlik servisi de "hatalı" olur
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        runCatching { msm.addOnActiveSessionsChangedListener(sessionsListener, cn) }
        runCatching { bind(msm.getActiveSessions(cn)) }
    }

    override fun onListenerDisconnected() = release()
    override fun onDestroy() { release(); super.onDestroy() }

    private fun release() {
        if (instance === this) instance = null
        scope?.cancel(); scope = null
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(controllerCallback)
        controller = null
        lastPublished = null
        artKey = null; art = null; artColor = 0
    }

    private fun handleCommand(cmd: MediaCommand) {
        val c = controller ?: return
        val tc = c.transportControls
        when (cmd) {
            MediaCommand.PlayPause ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) tc.pause() else tc.play()
            MediaCommand.Next -> tc.skipToNext()
            MediaCommand.Previous -> tc.skipToPrevious()
            is MediaCommand.SeekTo -> tc.seekTo(cmd.ms)
        }
    }

    private fun bind(list: List<MediaController>?) {
        val next = list?.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list?.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = next
            next?.registerCallback(controllerCallback)
        }
        publishMedia()
    }

    private fun publishMedia() {
        val c = controller
        val md = c?.metadata
        val ps = c?.playbackState
        val media = if (c == null || md == null) null else IslandContent.Media(
            title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(),
            artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty(),
            playing = ps?.state == PlaybackState.STATE_PLAYING,
            durationMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION).coerceAtLeast(0L),
            positionMs = ps?.position?.coerceAtLeast(0L) ?: 0L,
            speed = ps?.playbackSpeed ?: 1f,
            updatedAt = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            art = resolveArt(md),
            artColor = artColor,
        )
        if (media == lastPublished) return
        lastPublished = media
        IslandBus.post(IslandEvent.MediaChanged(media))
    }

    // ---------------------------------------------------------------- Albüm kapağı
    private fun resolveArt(md: MediaMetadata): ImageBitmap? {
        // 1) Doğrudan bitmap veren uygulamalar (Spotify, YouTube Music vb.): üçünden EN BÜYÜĞÜNÜ seç
        //    (DISPLAY_ICON çoğu zaman küçük bir önizlemedir, önceki sürüm onu da kullanıyordu).
        val bmp = listOfNotNull(
            md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART),
            md.getBitmap(MediaMetadata.METADATA_KEY_ART),
            md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON),
        ).maxByOrNull { it.width * it.height }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        val bmpIsSharp = bmp != null && maxOf(bmp.width, bmp.height) >= SHARP_MIN
        if (bmp != null && (bmpIsSharp || uri == null)) {
            val key = "b${System.identityHashCode(bmp)}"
            if (key != artKey) {
                artKey = key
                val small = bmp.scaledMax(ART_MAX)
                art = small.asImageBitmap()
                artColor = dominantColor(small)
            }
            return art
        }
        // 2) URI veren uygulamalar (ya da bitmap'i küçük olanlar): daha büyük kapağı arka planda yükle,
        //    gelene kadar eldeki küçük bitmap'i göster
        if (uri != null) {
            if (uri != artKey) {
                artKey = uri
                val placeholder = bmp?.scaledMax(ART_MAX)
                art = placeholder?.asImageBitmap()
                artColor = placeholder?.let { dominantColor(it) } ?: 0
                loadArtAsync(uri)
            }
            return art
        }
        artKey = null; art = null; artColor = 0
        return null
    }

    private fun loadArtAsync(uri: String) {
        scope?.launch {
            val decoded = withContext(Dispatchers.IO) { decodeUri(uri)?.let { it to dominantColor(it) } }
            if (decoded != null && artKey == uri) {
                art = decoded.first.asImageBitmap()
                artColor = decoded.second
                publishMedia()
            }
        }
    }

    /** content / file / resource / http(s) adreslerinden kapağı okur; ART_MAX piksele kadar tam çözünürlükte tutar. */
    private fun decodeUri(uri: String): Bitmap? = runCatching {
        val u = Uri.parse(uri)
        val bytes: ByteArray? = when (u.scheme) {
            "content", "file", "android.resource" -> contentResolver.openInputStream(u)?.use { it.readBytes() }
            "http", "https" -> java.net.URL(uri).openConnection().apply {
                connectTimeout = 5_000; readTimeout = 5_000
            }.getInputStream().use { it.readBytes() }
            else -> null
        }
        if (bytes == null || bytes.size > 12_000_000) return@runCatching null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        // Sonuç en az ART_MAX piksel kalacak şekilde yalnızca gerektiği kadar küçült
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ART_MAX) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?.scaledMax(ART_MAX)
    }.getOrNull()

    /** Kapaktaki en baskın CANLI rengi bulur (ton kovalarına doygunluk x parlaklık ağırlığıyla oy verilir). 0 = bulunamadı. */
    private fun dominantColor(src: Bitmap): Int {
        val soft = runCatching { src.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() ?: return 0
        val n = 24
        val bmp = runCatching { Bitmap.createScaledBitmap(soft, n, n, true) }.getOrNull() ?: return 0
        val px = IntArray(n * n)
        bmp.getPixels(px, 0, n, 0, 0, n, n)
        val wSum = FloatArray(12); val hSum = FloatArray(12); val sSum = FloatArray(12); val vSum = FloatArray(12)
        val hsv = FloatArray(3)
        for (c in px) {
            android.graphics.Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.2f || hsv[2] < 0.2f) continue          // gri / çok koyu pikselleri atla
            val w = hsv[1] * hsv[2]
            val b = (hsv[0] / 30f).toInt().coerceIn(0, 11)
            wSum[b] += w; hSum[b] += w * hsv[0]; sSum[b] += w * hsv[1]; vSum[b] += w * hsv[2]
        }
        var best = -1; var bw = 0f
        for (i in 0 until 12) if (wSum[i] > bw) { bw = wSum[i]; best = i }
        if (best < 0 || bw < 1.5f) return 0
        val out = floatArrayOf(
            hSum[best] / bw,
            (sSum[best] / bw).coerceIn(0.45f, 1f),
            (vSum[best] / bw).coerceIn(0.6f, 1f),
        )
        return android.graphics.Color.HSVToColor(out)
    }

    private fun Bitmap.scaledMax(max: Int): Bitmap {
        val m = maxOf(width, height)
        if (m <= max) return this
        val f = max.toFloat() / m
        return runCatching {
            Bitmap.createScaledBitmap(this, (width * f).toInt().coerceAtLeast(1), (height * f).toInt().coerceAtLeast(1), true)
        }.getOrDefault(this)
    }

    // ---------------------------------------------------------------- Bildirimler
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handleNotification(sbn) }
    }

    // ---------------------------------------------------------------- Arama bildirimleri
    private val answerWords = listOf("cevapla", "yanıtla", "yanitla", "kabul", "answer", "accept", "pick up")
    private val declineWords = listOf("reddet", "decline", "reject", "kapat", "hang up", "hangup", "end call", "sonlan", "bitir")

    private fun handleCall(sbn: StatusBarNotification) {
        val n = sbn.notification
        val ex = n.extras
        val name = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf { it.isNotBlank() }
            ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val acts = n.actions?.filter { it.actionIntent != null } ?: emptyList()
        // Android 12+ CallStyle: 1 = gelen, 2 = aktif, 3 = filtreleme (gelen gibi gösterilir)
        val type = ex.getInt("android.callType", 0)
        val incoming = when (type) {
            1, 3 -> true
            2 -> false
            else -> n.fullScreenIntent != null ||
                (!ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER) && acts.size >= 2)
        }
        fun pick(words: List<String>) = acts.firstOrNull { a ->
            val t = a.title?.toString()?.lowercase().orEmpty()
            words.any { t.contains(it) }
        }?.actionIntent
        var decline = pick(declineWords)
        var answer = if (incoming) pick(answerWords) else null
        // Düğme adları tanınmazsa sıraya bak: önce Reddet, sonra Cevapla
        val rest = acts.map { it.actionIntent }.filter { it !== decline && it !== answer }
        if (decline == null) decline = rest.firstOrNull()
        if (incoming && answer == null) answer = rest.firstOrNull { it !== decline }
        val avatar = runCatching {
            n.getLargeIcon()?.loadDrawable(this)?.toBitmap(128, 128)?.asImageBitmap()
        }.getOrNull()
        val started = if (!incoming && ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)) n.`when` else 0L
        IslandBus.post(IslandEvent.Call(sbn.key, sbn.packageName, name, incoming, started, avatar, answer, decline))
    }


    // ---------------------------------------------------------------- Ekran kaydı (sistem kaydedicinin bildirimi)
    // Dile bağlı olmayan ipuçları öncelikli: bildirim kanalı ve paket adı. Metin yalnızca SystemUI için yedek.
    private val recChannelHints = listOf("screen_record", "screenrecord", "screen-record", "screen_rec", "screenrec", "scrrec")
    private val recPkgHints = listOf("screenrecord", "screen_record", "screenrec", "scrrecord")
    private val recTextHints = listOf(
        "screen record", "screenrecord", "ekran kayd", "запись экрана", "bildschirmaufnahme", "enregistrement de l",
        "grabación de pantalla", "gravação de tela", "registrazione schermo", "录屏", "屏幕录制", "画面録画", "화면 녹화", "تسجيل الشاشة",
    )
    private val recStopWords = listOf("durdur", "bitir", "dur", "stop", "finish", "end", "остановить", "стоп", "停止", "结束")

    private fun isScreenRec(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification ?: return false
        val pkg = sbn.packageName.lowercase()
        val ch = (if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId else null).orEmpty().lowercase()
        if (recChannelHints.any { ch.contains(it) }) return true
        if (recPkgHints.any { pkg.contains(it) }) return true
        if (pkg.contains("systemui")) {
            val ex = n.extras
            val t = (ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() + " " +
                ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).lowercase()
            if (recTextHints.any { t.contains(it) }) return true
        }
        return false
    }

    private fun recPhaseOf(sbn: StatusBarNotification): RecPhase {
        val n = sbn.notification
        val ex = n.extras
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val hasProg = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE) || ex.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0
        val ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val digit = listOf(title, text).any { it.length in 1..2 && it.all(Char::isDigit) }
        return when {
            digit && !chrono -> RecPhase.Countdown
            ongoing && chrono -> RecPhase.Recording
            ongoing && hasProg -> RecPhase.Processing
            ongoing -> RecPhase.Recording
            else -> RecPhase.Saved
        }
    }

    private fun handleScreenRec(sbn: StatusBarNotification) {
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        val phase = recPhaseOf(sbn)
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val count = listOf(
            ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim(),
            ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim(),
        ).firstOrNull { it.length in 1..2 && it.all(Char::isDigit) }?.toIntOrNull() ?: 0
        val acts = n.actions?.filter { it.actionIntent != null } ?: emptyList()
        val stop = if (phase == RecPhase.Recording) {
            acts.firstOrNull { a ->
                val t = a.title?.toString()?.trim()?.lowercase().orEmpty()
                recStopWords.any { t.startsWith(it) }
            }?.actionIntent ?: acts.firstOrNull()?.actionIntent ?: n.contentIntent
        } else null
        val thumb = if (phase == RecPhase.Saved) runCatching {
            @Suppress("DEPRECATION")
            val pic = ex.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
            val bmp = pic ?: n.getLargeIcon()?.loadDrawable(this)?.toBitmap(192, 192)
            bmp?.scaledMax(256)?.asImageBitmap()
        }.getOrNull() else null
        val started = if (chrono) n.`when` else 0L
        IslandBus.post(IslandEvent.ScreenRec(phase, sbn.key, started, count, stop, n.contentIntent, thumb))
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (runCatching { isScreenRec(sbn) }.getOrDefault(false)) {
            runCatching { IslandBus.post(IslandEvent.ScreenRecGone(sbn.key, recPhaseOf(sbn))) }
            return
        }
        if (sbn.notification?.category == Notification.CATEGORY_CALL) {
            IslandBus.post(IslandEvent.CallEnded(sbn.key))
        }
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val n = sbn.notification
        // Sistem ekran kaydı bildirimi (kayıt / işleniyor / kaydedildi) ayrı yoldan gider
        if (isScreenRec(sbn)) { handleScreenRec(sbn); return }
        // Arama bildirimleri (gelen / aktif) ayrı yoldan gider; "devam eden" filtresine takılmaz
        if (sbn.packageName != packageName && n.category == Notification.CATEGORY_CALL) {
            handleCall(sbn)
            return
        }
        if (sbn.packageName == packageName || sbn.isOngoing ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            n.category == Notification.CATEGORY_TRANSPORT ||
            n.category == Notification.CATEGORY_PROGRESS
        ) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        IslandBus.post(IslandEvent.Notification(sbn.packageName, title, text, n.contentIntent))
    }
}
