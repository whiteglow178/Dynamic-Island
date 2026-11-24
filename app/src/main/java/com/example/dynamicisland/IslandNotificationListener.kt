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
import kotlinx.coroutines.withTimeoutOrNull

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

        /** Ayar açılınca ya da kapanınca okunmamış sayacını hemen yeniler. */
        fun refreshUnread() { instance?.refreshUnreadCount() }

        /** Şu anki Rahatsız Etmeyin süzgeci (NotificationManager.INTERRUPTION_FILTER_*). */
        fun currentZen(): Int = runCatching {
            instance?.currentInterruptionFilter?.takeIf { it != android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN }
        }.getOrNull() ?: android.app.NotificationManager.INTERRUPTION_FILTER_ALL

        /** Dinleyici bağlı değilse sistemden okunur. */
        fun currentZen(ctx: android.content.Context): Int {
            if (instance != null) return currentZen()
            val f = runCatching { ctx.getSystemService(android.app.NotificationManager::class.java)?.currentInterruptionFilter }.getOrNull()
            return if (f == null || f == android.app.NotificationManager.INTERRUPTION_FILTER_UNKNOWN) android.app.NotificationManager.INTERRUPTION_FILTER_ALL else f
        }

        /**
         * Rahatsız Etmeyin süzgecini değiştirir (Ters çevirince sessiz). Bildirim dinleyicisi bunu ek izin olmadan isteyebilir;
         * dinleyici bağlı değilse "Rahatsız Etmeyin erişimi" verilmişse sistem üzerinden denenir.
         */
        fun requestZen(filter: Int, ctx: android.content.Context? = null): Boolean {
            val l = instance
            if (l != null && runCatching { l.requestInterruptionFilter(filter) }.isSuccess) return true
            return runCatching {
                val nm = ctx?.getSystemService(android.app.NotificationManager::class.java) ?: return false
                if (!nm.isNotificationPolicyAccessGranted) return false
                nm.setInterruptionFilter(filter)
            }.isSuccess
        }

        /** Adadaki bildirimi sistemden de siler (bildirim panelinden de kalkar). Anahtar boşsa ya da dinleyici bağlı değilse bir şey yapmaz. */
        /** Bildirimi sistemde ertele: [ms] sonra bildirim panelinde yeniden belirir (ek izin gerekmez). */
        fun snooze(key: String, ms: Long): Boolean {
            val i = instance ?: return false
            return runCatching { i.snoozeNotification(key, ms); true }.getOrDefault(false)
        }

        /** Bu uygulamanın "Bildirim testi" kartından gönderilen deneme bildirimi mi? (normalde kendi bildirimlerimiz yok sayılır) */
        fun isTest(sbn: StatusBarNotification, own: String): Boolean =
            sbn.packageName == own && runCatching { sbn.notification.extras.getBoolean(NotifTest.EXTRA, false) }.getOrDefault(false)

        fun dismiss(key: String) {
            if (key.isBlank()) return
            runCatching { instance?.cancelNotification(key) }
        }

        /** Bildirimin satır içi cevap (RemoteInput) eylemi: mesajlaşma uygulamalarının "Yanıtla" düğmesi. Yoksa null. */
        private fun replyActionOf(key: String): Pair<android.app.Notification.Action, android.app.RemoteInput>? {
            if (key.isBlank()) return null
            val sbn = instance?.activeNotifications?.firstOrNull { it.key == key } ?: return null
            for (act in sbn.notification.actions.orEmpty()) {
                if (act.actionIntent == null) continue
                val ri = act.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: continue
                return act to ri
            }
            return null
        }

        /** Bu bildirime adadan / bannerdan cevap yazılabilir mi? */
        fun canReply(key: String): Boolean = runCatching { replyActionOf(key) != null }.getOrDefault(false)

        /** Bildirimin cevap eylemini [text] ile tetikler (uygulama açılmaz). Başarılıysa true. */
        fun sendReply(key: String, text: String): Boolean {
            val i = instance ?: return false
            return runCatching {
                val (act, ri) = replyActionOf(key) ?: return false
                val fill = android.content.Intent()
                val bundle = android.os.Bundle().apply { putCharSequence(ri.resultKey, text) }
                android.app.RemoteInput.addResultsToIntent(arrayOf(ri), fill, bundle)
                act.actionIntent.send(i, 0, fill)
                true
            }.getOrDefault(false)
        }

        /**
         * Rahatsız Etmeyin'i açar (öncelikli) ya da kapatır. Bildirim dinleyicisi buna ek izin olmadan yetkilidir.
         * Dinleyici bağlı değilse false.
         */
        fun setFocus(on: Boolean): Boolean {
            val i = instance ?: return false
            return runCatching {
                i.requestInterruptionFilter(if (on) NotificationListenerService.INTERRUPTION_FILTER_PRIORITY else NotificationListenerService.INTERRUPTION_FILTER_ALL)
                true
            }.getOrDefault(false)
        }

        /** SoundCapsule'ın "dinleniyor" bildirimini kapatır: varsa iptal / durdur eylemini tetikler, yoksa bildirimi siler. */
        fun stopSoundCapsuleListening() {
            val i = instance ?: return
            var done = false
            runCatching {
                val key = i.soundCapsuleListenKey
                // Kapatılan dinleme bildirimi (güncellense bile) adayı yeniden açmasın
                i.soundCapsuleDismissedKey = key
                i.soundCapsuleDismissedAt = SystemClock.elapsedRealtime()
                i.soundCapsuleListenKey = null
                i.soundCapsuleListenAt = 0L
                val byKey = if (key != null) i.activeNotifications?.firstOrNull { it.key == key } else null
                val sbn = byKey ?: i.activeNotifications?.firstOrNull { it.packageName in i.soundCapsulePkgs && it.isOngoing }
                if (sbn != null) {
                    val hints = listOf("iptal", "cancel", "stop", "durdur", "kapat", "dismiss", "bitir", "vazgeç", "close", "end")
                    val acts = sbn.notification.actions
                    // 1) İptal / durdur eylemi; tek eylem varsa o (dinleme bildiriminde genelde tek düğme "iptal"dir)
                    val act = acts?.firstOrNull { a ->
                        val t = a.title?.toString()?.lowercase().orEmpty()
                        hints.any { h -> t.contains(h) }
                    } ?: acts?.singleOrNull()
                    if (act != null) done = runCatching { act.actionIntent.send(); true }.getOrDefault(false)
                    // 2) Bildirimin silme hedefi (kaydırarak silmeyle aynı etki)
                    if (!done) sbn.notification.deleteIntent?.let { pi -> done = runCatching { pi.send(); true }.getOrDefault(false) }
                    // 3) Bildirimi kaldırmayı dene (ön plan servisi bildirimlerinde etkisiz olabilir)
                    runCatching { i.cancelNotification(sbn.key) }
                }
            }
            // 4) Uygulamanın kendi ekranı / açılır penceresi görünüyorsa iptal düğmesine basılır; bulunamazsa
            //    ve uygulama öndeyse geri tuşu gönderilir (tanıma ekranında geri = dinlemeyi bırak)
            runCatching { IslandAccessibilityService.stopListening(i.soundCapsulePkgs, back = !done) }
        }

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
        refreshUnreadCount()
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

    // ---------------------------------------------------------------- SoundCapsule (bildirimden tanıma)
    // SoundCapsule iki bildirim yayınlar: (1) dinlerken "devam eden" bildirim, (2) şarkıyı bulunca sonuç bildirimi
    // (başlık = şarkı adı, metin = sanatçı, büyük simge = albüm kapağı). Bildirim dili / sürümü değişebildiği için
    // sınıflandırma birkaç ipucuna bakar.
    // Tanıma uygulamasının paket adı kaynakta düz yazı olarak tutulmaz (ters çevrilmiş halde saklanır)
    private val capsuleWord = "mazahs".reversed()
    private val soundCapsulePkgs = setOf("com.$capsuleWord.android", "com.$capsuleWord.encore.android")
    private val soundCapsuleListenHints = listOf(
        "dinle", "dinli", "listening", "tagging", "identifying", "recognizing", "tanımlan", "tanimlan", "aranıyor",
        "écoute", "escuchando", "hört zu", "in ascolto", "ouvindo", "слушаю", "聆听", "듣고",
    )
    private val soundCapsuleAutoHints = listOf("auto $capsuleWord", "otomatik $capsuleWord", "auto$capsuleWord", "pop-up", "pop up", "açılır")
    private val soundCapsuleNoMatchHints = listOf(
        "no result", "no match", "couldn't", "could not", "not found", "try again", "sonuç yok", "bulunamadı", "bulunamadi",
        "eşleşme yok", "eşleşme bulunamad", "tekrar dene", "aucun résultat", "sin resultado", "kein ergebnis",
    )
    private val soundCapsuleSkipChannels = listOf("promo", "news", "chart", "marketing", "discover", "social", "recommend")

    private enum class SoundCapsuleKind { Listening, Result, NoMatch, None }

    private var soundCapsuleListenKey: String? = null
    private var soundCapsuleListenAt = 0L          // son "dinliyor" bildiriminin zamanı (elapsedRealtime)
    private var soundCapsuleDismissedKey: String? = null   // adadaki X ile kapatılan dinleme bildirimi
    private var soundCapsuleDismissedAt = 0L
    private var lastSoundCapsuleSig = ""
    private var lastSoundCapsuleAt = 0L

    private fun classifySoundCapsule(sbn: StatusBarNotification, now: Long): SoundCapsuleKind {
        val n = sbn.notification ?: return SoundCapsuleKind.None
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return SoundCapsuleKind.None
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val all = "$title $text".lowercase()
        val ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        if (ongoing) {
            // "Otomatik SoundCapsule açık" gibi kalıcı bildirimler dinleme animasyonu başlatmaz
            if (soundCapsuleAutoHints.any { all.contains(it) }) return SoundCapsuleKind.None
            val indeterminate = ex.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)
            return if (indeterminate || soundCapsuleListenHints.any { all.contains(it) }) SoundCapsuleKind.Listening else SoundCapsuleKind.None
        }
        if (title.isBlank() && text.isBlank()) return SoundCapsuleKind.None
        val recent = now - soundCapsuleListenAt < 40_000L
        if (soundCapsuleNoMatchHints.any { all.contains(it) }) return if (recent) SoundCapsuleKind.NoMatch else SoundCapsuleKind.None
        val cat = n.category.orEmpty()
        if (cat == Notification.CATEGORY_PROMO || cat == Notification.CATEGORY_RECOMMENDATION || cat == Notification.CATEGORY_SOCIAL) return SoundCapsuleKind.None
        val ch = (if (android.os.Build.VERSION.SDK_INT >= 26) n.channelId else null).orEmpty().lowercase()
        if (soundCapsuleSkipChannels.any { ch.contains(it) }) return SoundCapsuleKind.None
        val hasCover = n.getLargeIcon() != null || ex.containsKey(Notification.EXTRA_PICTURE)
        // Sonuç bildirimi: kapağı var ya da az önce dinleme başlamıştı (arka plan / otomatik SoundCapsule sonuçları da kapaklıdır)
        return if (hasCover || recent) SoundCapsuleKind.Result else SoundCapsuleKind.None
    }

    /** SoundCapsule bildirimiyse adaya olay gönderir ve true döner (bildirim normal bildirim olarak gösterilmez). */
    private fun handleSoundCapsule(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in soundCapsulePkgs) return false
        val n = sbn.notification ?: return false
        val now = SystemClock.elapsedRealtime()
        when (classifySoundCapsule(sbn, now)) {
            SoundCapsuleKind.Listening -> {
                // Kullanıcı adadan kapattı: aynı dinleme bildiriminin güncellemeleri adayı yeniden açmasın (2 dk)
                if (sbn.key == soundCapsuleDismissedKey && now - soundCapsuleDismissedAt < 120_000L) return true
                // Aynı bildirimin güncellemeleri dinlemeyi yeniden başlatmasın
                if (sbn.key == soundCapsuleListenKey && now - soundCapsuleListenAt < 60_000L) return true
                soundCapsuleListenKey = sbn.key; soundCapsuleListenAt = now
                IslandBus.post(IslandEvent.SoundCapsuleListening(sbn.key))
                return true
            }
            SoundCapsuleKind.Result -> {
                val ex = n.extras
                val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
                val artist = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
                    .ifBlank { ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim() }
                    .ifBlank { ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty().trim() }
                val sig = "$title|$artist"
                if (sig == lastSoundCapsuleSig && now - lastSoundCapsuleAt < 15_000L) return true     // bildirim güncellemesi: tekrar gösterme
                lastSoundCapsuleSig = sig; lastSoundCapsuleAt = now
                soundCapsuleListenAt = 0L
                val notifCover = recThumbOf(n)
                val open = n.contentIntent
                val key = sbn.key
                IslandBus.post(IslandEvent.SoundCapsuleFound(title, artist, notifCover, open, key))
                return true
            }
            SoundCapsuleKind.NoMatch -> { IslandBus.post(IslandEvent.SoundCapsuleGone); return true }
            SoundCapsuleKind.None -> return false
        }
        @Suppress("UNREACHABLE_CODE")
        return false
    }

    // ---------------------------------------------------------------- Bildirimler
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching { handleNotification(sbn) }
        refreshUnreadCount()
    }

    /** Okunmamış sayacı için bekleyen bildirim sayısı: devam eden, silinemeyen ve grup özeti bildirimleri sayılmaz. */
    private fun refreshUnreadCount(exclude: String? = null) {
        if (!UnreadBadge.enabled) return
        UnreadBadge.count.value = runCatching {
            activeNotifications?.count {
                it.key != exclude && !it.isOngoing && it.isClearable && it.packageName != packageName &&
                    (it.notification.flags and Notification.FLAG_GROUP_SUMMARY) == 0
            } ?: 0
        }.getOrDefault(0)
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
        "screen record", "screenrecord", "ekran kayd", "kayıt yapıldı", "запись экрана", "bildschirmaufnahme", "enregistrement de l",
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
        if (pkg.contains("systemui") || pkg.contains("miui")) {
            val ex = n.extras
            val t = (ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty() + " " +
                ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()).lowercase()
            if (recTextHints.any { t.contains(it) }) return true
        }
        return false
    }

    /** null = kayıt henüz başlamadı (yüzen ada / arka planda hazır bekleme bildirimi): adada gösterilmez. */
    private fun recPhaseOf(sbn: StatusBarNotification): RecPhase? {
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
            ongoing -> null          // süre sayacı yok = gerçek kayıt değil (hazır bekleme bildirimi)
            else -> RecPhase.Saved
        }
    }

    /** Kaydedildi bildirimindeki önizleme: Android sürümüne göre bitmap ya da Icon olarak gelebilir. */
    private fun recThumbOf(n: Notification): ImageBitmap? = runCatching {
        val ex = n.extras
        fun raw(key: String): Any? = runCatching { @Suppress("DEPRECATION") ex.get(key) }.getOrNull()
        fun toBmp(o: Any?): Bitmap? = when (o) {
            is Bitmap -> o
            is android.graphics.drawable.Icon -> runCatching {
                o.loadDrawable(this)?.let { d ->
                    if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) d.toBitmap() else d.toBitmap(256, 256)
                }
            }.getOrNull()
            else -> null
        }
        val bmp = toBmp(raw(Notification.EXTRA_PICTURE))
            ?: (if (android.os.Build.VERSION.SDK_INT >= 31) toBmp(raw(Notification.EXTRA_PICTURE_ICON)) else null)
            ?: toBmp(raw(Notification.EXTRA_LARGE_ICON_BIG))
            ?: toBmp(raw(Notification.EXTRA_LARGE_ICON))
            ?: toBmp(n.getLargeIcon())
        bmp?.scaledMax(256)?.asImageBitmap()
    }.getOrNull()

    private fun handleScreenRec(sbn: StatusBarNotification) {
        val n = sbn.notification
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        val ex = n.extras
        val phase = recPhaseOf(sbn)
        if (phase == null) {
            // Bekleme bildirimine dönüldüyse (kayıt bitti, yüzen ada açık kaldı) bitiş sinyali gönder; kayıt yoksa yok sayılır
            IslandBus.post(IslandEvent.ScreenRecGone(sbn.key, RecPhase.Recording))
            return
        }
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
        val thumb = if (phase == RecPhase.Saved) recThumbOf(n) else null
        val started = if (chrono) n.`when` else 0L
        IslandBus.post(IslandEvent.ScreenRec(phase, sbn.key, started, count, stop, n.contentIntent, thumb))
    }

    /** Kullanıcı bildirimi kaydırarak sildiyse (ya da tümünü temizlediyse) çöp kutusu ada animasyonu için haber verilir. */
    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap, reason: Int) {
        super.onNotificationRemoved(sbn, rankingMap, reason)
        refreshUnreadCount(sbn.key)
        runCatching { reminder.onRemoved(sbn.key) }
        runCatching {
            val n = sbn.notification
            val userGone = reason == NotificationListenerService.REASON_CANCEL || reason == NotificationListenerService.REASON_CANCEL_ALL
            // Silinen bildirim geçmişi: yalnızca bellekte; kod içerenler ve gizli bildirimler tutulmaz
            if (NotifFlags.history && userGone && (n.flags and Notification.FLAG_ONGOING_EVENT) == 0 &&
                (n.flags and Notification.FLAG_GROUP_SUMMARY) == 0 && (sbn.packageName != packageName || isTest(sbn, packageName)) && !NotifVault.sensitive(sbn)
            ) {
                val t = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val x = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                if (t.isNotBlank() || x.isNotBlank()) NotifVault.addRemoved(NotifVault.Entry(sbn.packageName, t, x, System.currentTimeMillis(), n.contentIntent))
            }
            val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val summary = (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0
            if (userGone && !ongoing && !summary && (sbn.packageName != packageName || isTest(sbn, packageName))) {
                val ttl = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val txt = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
                IslandBus.post(IslandEvent.NotifTrashed(sbn.packageName, ttl.ifBlank { txt }))
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        refreshUnreadCount(sbn.key)
        if (liveKeys.remove(sbn.key)) IslandBus.post(IslandEvent.LiveGone(sbn.key))
        timerKeys.remove(sbn.key); watchKeys.remove(sbn.key)
        if (clockKeys.remove(sbn.key)) IslandBus.post(IslandEvent.ClockGone(sbn.key))
        if (voiceKeys.remove(sbn.key)) IslandBus.post(IslandEvent.VoiceRecGone(sbn.key))
        if (sbn.packageName in soundCapsulePkgs) {
            // Dinleme bildirimi kalktı: sonuç gelmezse ada kapanır (servis kısa bir tolerans bekler)
            val kind = runCatching { classifySoundCapsule(sbn, SystemClock.elapsedRealtime()) }.getOrNull()
            if (sbn.key == soundCapsuleDismissedKey) soundCapsuleDismissedKey = null
            if (kind == SoundCapsuleKind.Listening) { soundCapsuleListenKey = null; IslandBus.post(IslandEvent.SoundCapsuleGone) }
            return
        }
        if (runCatching { isScreenRec(sbn) }.getOrDefault(false)) {
            // Hazır bekleme bildirimi kalkarsa da "kayıt bitti" say: kayıt yoksa servis zaten yok sayar
            runCatching { IslandBus.post(IslandEvent.ScreenRecGone(sbn.key, recPhaseOf(sbn) ?: RecPhase.Recording)) }
            return
        }
        if (sbn.notification?.category == Notification.CATEGORY_CALL) {
            IslandBus.post(IslandEvent.CallEnded(sbn.key))
        }
    }

    // ---------------------------------------------------------------- Saat uygulaması: alarm / zamanlayıcı
    private val clockKeys = HashSet<String>()
    private val clockPkgHints = listOf(
        "deskclock", "clockpackage", "alarmclock", "timeweather", ".clock", "clock.", "alarm",
    )
    private val snoozeWords = listOf("ertele", "snooze", "erteleme", "отложить", "später", "posponer")
    private val dismissWords = listOf("kapat", "dismiss", "stop", "durdur", "bitir", "tamam", "sil", "sıfırla", "reset", "delete", "iptal", "cancel", "выключить", "остановить")
    private val pauseWords = listOf("duraklat", "durdur", "pause", "stop", "пауза", "остановить")
    private val resumeWords = listOf("devam", "sürdür", "surdur", "resume", "continue", "başlat", "start", "play", "oynat", "продолжить")
    private val pausedTextWords = listOf("duraklat", "durdur", "paused", "stopped", "пауза", "приостановлен")
    /** Bir kez zamanlayıcı / kronometre olarak tanınan bildirimler: duraklatılınca metni değişse de aynı tür sayılır. */
    private val timerKeys = HashSet<String>()
    private val watchKeys = HashSet<String>()
    private val cancelWords = listOf("sıfırla", "reset", "sil", "delete", "iptal", "cancel", "kaldır", "remove", "сброс", "удалить")
    private val timerWords = listOf("zamanlayıcı", "timer", "geri sayım", "таймер")
    private val watchWords = listOf("kronometre", "stopwatch", "секундомер", "tur", "lap")
    private val lapWords = listOf("tur", "lap", "круг")
    private val timesUpWords = listOf("süre doldu", "süresi doldu", "time's up", "times up", "time is up", "время истекло", "bitti")
    private val mmssRx = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?::(\d{2}))?(?!\d)""")
    private val hhmmRx = Regex("""\b\d{1,2}[:.]\d{2}\b""")

    /**
     * Türkçe büyük harfler de doğru eşleşsin: "İptal".lowercase() kök yerelde "i̇ptal" (noktalı i) olur ve "iptal" ile
     * eşleşmez. Birleşik nokta atılır, ı -> i yapılır; aranan kelimeler de aynı biçime getirilir.
     */
    private fun normAct(s: String): String = s.lowercase(java.util.Locale.ROOT).replace("\u0307", "").replace('ı', 'i')

    private fun actionBy(n: Notification, words: List<String>): android.app.PendingIntent? =
        n.actions?.firstOrNull { a ->
            val t = normAct(a.title?.toString().orEmpty())
            a.actionIntent != null && words.any { t.contains(normAct(it)) }
        }?.actionIntent

    /** Eylem sırası yedeği: saat uygulamalarında genelde ilk düğme duraklat / devam, son düğme sıfırla / iptaldir. */
    private fun actionAt(n: Notification, first: Boolean): android.app.PendingIntent? {
        val acts = n.actions?.filter { it.actionIntent != null }.orEmpty()
        if (acts.isEmpty()) return null
        fun isCancel(a: Notification.Action): Boolean {
            val t = normAct(a.title?.toString().orEmpty())
            return (cancelWords + listOf("kapat", "dismiss", "bitir")).any { t.contains(normAct(it)) }
        }
        fun isExtra(a: Notification.Action): Boolean {
            val t = a.title?.toString().orEmpty()
            return t.contains("+") || lapWords.any { normAct(t).contains(it) }          // "+1:00", "Tur"
        }
        // Duraklat / devam yerine asla iptal düğmesi gönderilmez (bazı saat uygulamalarında İptal ilk sıradadır)
        return if (first) acts.firstOrNull { !isCancel(it) && !isExtra(it) }?.actionIntent
        else (acts.lastOrNull { isCancel(it) } ?: acts.takeIf { it.size >= 2 }?.lastOrNull { !isExtra(it) && it != acts.firstOrNull { a -> !isCancel(a) && !isExtra(a) } })?.actionIntent
    }

    /** "4:32" / "1:04:09" -> ms */
    private fun mmssMs(m: MatchResult): Long {
        val a = m.groupValues[1].toLong(); val b = m.groupValues[2].toLong(); val c = m.groupValues[3]
        return if (c.isNotEmpty()) (a * 3600 + b * 60 + c.toLong()) * 1000 else (a * 60 + b) * 1000
    }

    /** Saat uygulamasının bildirimiyse adaya olay gönderir ve true döner (bildirim adada ayrıca gösterilmez). */
    private fun handleClock(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        if (sbn.packageName == packageName) return false
        val pkg = sbn.packageName.lowercase()
        val clockApp = clockPkgHints.any { pkg.contains(it) }
        val cat = n.category.orEmpty()
        if (!clockApp && !(cat == Notification.CATEGORY_ALARM && n.fullScreenIntent != null)) return false
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val sub = ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty().trim()
        val all = "$title $text $sub".lowercase()
        val ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val countDown = ex.getBoolean(Notification.EXTRA_CHRONOMETER_COUNT_DOWN)
        val key = sbn.key
        val isWatch = watchKeys.contains(key) || (!countDown && clockApp && !timerKeys.contains(key) &&
            (listOf("kronometre", "stopwatch", "секундомер").any { all.contains(it) } ||
            (chrono && timerWords.none { all.contains(it) } && cat != Notification.CATEGORY_ALARM)))
        val isTimer = !isWatch && (timerKeys.contains(key) || timerWords.any { all.contains(it) } || countDown)
        // Daha önce tanınmış bir zamanlayıcı / kronometre: duraklatılınca bildirim "devam eden" olmaktan çıkabilir
        val known = timerKeys.contains(key) || watchKeys.contains(key)
        val live = ongoing || known
        val snooze = actionBy(n, snoozeWords)
        val dismiss = actionBy(n, dismissWords)
        // 0) Kronometre (ileri sayan): duraklat / devam / sıfırla
        if (isWatch && live) {
            val resume = actionBy(n, resumeWords)
            val paused = !chrono && (resume != null || pausedTextWords.any { normAct(all).contains(normAct(it)) } ||
                (known && actionBy(n, pauseWords) == null))
            val elapsed = mmssRx.find("$title $text")?.let { mmssMs(it) } ?: 0L
            val started = if (chrono && !paused) n.`when` else 0L
            if (started <= 0L && !paused && elapsed <= 0L) return clockKeys.contains(sbn.key)
            clockKeys.add(sbn.key); watchKeys.add(key)
            val pause = actionBy(n, pauseWords)?.takeIf { it != resume }
            val reset = actionBy(n, cancelWords)?.takeIf { pi -> n.actions?.none { a -> a.actionIntent == pi && lapWords.any { normAct(a.title?.toString().orEmpty()).contains(it) } } != false }
            IslandBus.post(IslandEvent.ClockStopwatch(
                sbn.key, if (started > 0L) started else System.currentTimeMillis() - elapsed, elapsed, paused,
                pause ?: if (!paused) actionAt(n, true) else null, resume ?: if (paused) actionAt(n, true) else null,
                reset ?: actionAt(n, false), n.contentIntent,
            ))
            return true
        }
        // 1) Süresi dolan zamanlayıcı
        if (isTimer && (timesUpWords.any { all.contains(it) } || (cat == Notification.CATEGORY_ALARM && !chrono && n.fullScreenIntent != null))) {
            clockKeys.add(sbn.key)
            IslandBus.post(IslandEvent.ClockAlarm(sbn.key, "Zamanlayıcı", "", true, null, dismiss ?: n.contentIntent))
            return true
        }
        // 2) Geri sayan / duraklatılmış zamanlayıcı
        if (isTimer && live) {
            val resume = actionBy(n, resumeWords)
            // Geri sayım göstermeyen bir zamanlayıcı bildirimi = duraklatılmış (düğme adı ne olursa olsun)
            val paused = !(chrono && countDown) && (resume != null || pausedTextWords.any { normAct(all).contains(normAct(it)) } ||
                (known && actionBy(n, pauseWords) == null))
            val ends = if (chrono && countDown) n.`when` else 0L
            val left = mmssRx.find("$title $text $sub")?.let { mmssMs(it) } ?: 0L   // 0 = bilinmiyor, ada son değeri korur
            if (ends <= 0L && !paused) return clockKeys.contains(sbn.key)        // geri sayım bilgisi yok: normal bildirim
            clockKeys.add(sbn.key); timerKeys.add(key)
            val pause = actionBy(n, pauseWords)?.takeIf { it != resume }
            IslandBus.post(IslandEvent.ClockTimer(
                sbn.key, ends, if (paused) left else (ends - System.currentTimeMillis()).coerceAtLeast(0L), paused, "Zamanlayıcı",
                pause ?: if (!paused) actionAt(n, true) else null, resume ?: if (paused) actionAt(n, true) else null,
                actionBy(n, cancelWords) ?: actionAt(n, false), n.contentIntent,
            ))
            return true
        }
        // 3) Çalan alarm: ertele düğmesi ya da tam ekran hedefi olan alarm bildirimi
        if (!chrono && (snooze != null || (cat == Notification.CATEGORY_ALARM && n.fullScreenIntent != null))) {
            val time = hhmmRx.find("$title $text")?.value
                ?: java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val label = listOf(title, text).firstOrNull { it.isNotBlank() && !it.contains(time) && it.length <= 24 } ?: "Alarm"
            clockKeys.add(sbn.key)
            IslandBus.post(IslandEvent.ClockAlarm(sbn.key, label, time, false, snooze, dismiss))
            return true
        }
        if (known) return true                                               // tanınan kayıt: yalnızca bildirim kalkınca biter
        if (clockKeys.remove(sbn.key)) IslandBus.post(IslandEvent.ClockGone(sbn.key))
        return false
    }

    // ---------------------------------------------------------------- Ses kaydı
    private val voiceKeys = HashSet<String>()
    private val voicePkgHints = listOf("voicenote", "soundrecorder", "audiorecorder", "voicerecorder", "apps.recorder", "recorder")
    private val voiceStopWords = listOf("durdur", "stop", "kaydet", "save", "bitir", "finish", "done", "стоп", "сохранить")
    private val voicePauseWords = listOf("duraklat", "pause", "devam", "resume", "пауза")

    /** Ses kaydedici uygulamada kayıt sürüyorsa adaya olay gönderir ve true döner. */
    private fun handleVoiceRec(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return false
        val pkg = sbn.packageName.lowercase()
        if (sbn.packageName == packageName || voicePkgHints.none { pkg.contains(it) }) return false
        if (pkg.contains("screen")) return false                          // ekran kaydedici ayrı yoldan gider
        val ongoing = sbn.isOngoing || (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val ex = n.extras
        val chrono = ex.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER)
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().trim()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty().trim()
        val all = "$title $text".lowercase()
        val mmss = mmssRx.find("$title $text")
        if (!ongoing || (!chrono && mmss == null && !all.contains("kayıt") && !all.contains("record"))) {
            if (voiceKeys.remove(sbn.key)) IslandBus.post(IslandEvent.VoiceRecGone(sbn.key))
            return false
        }
        val paused = all.contains("duraklat") || all.contains("paused") || all.contains("пауза")
        val elapsed = mmss?.let { mmssMs(it) } ?: 0L
        val started = if (chrono && !paused) n.`when` else 0L
        voiceKeys.add(sbn.key)
        IslandBus.post(IslandEvent.VoiceRec(
            sbn.key, sbn.packageName, started, elapsed, paused,
            actionBy(n, voiceStopWords), actionBy(n, voicePauseWords), n.contentIntent,
        ))
        return true
    }

    // ---------------------------------------------------------------- Canlı etkinlik (navigasyon / sipariş)
    private val liveKeys = HashSet<String>()

    private fun handleLive(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        val icon = runCatching { n.getLargeIcon()?.loadDrawable(this)?.toBitmap(128, 128)?.asImageBitmap() }.getOrNull()
        val live = (if (LiveFlags.nav) LiveParse.nav(sbn, icon) else null)
            ?: (if (LiveFlags.score) LiveParse2.score(sbn, icon) else null)
            ?: (if (LiveFlags.delivery) LiveParse.delivery(sbn, icon) else null)
            ?: (if (LiveFlags.progress) LiveParse2.progress(sbn, icon) else null)
        if (live == null) {
            // Önceden canlı olan bildirim artık uymuyor (ör. navigasyon bitti): adadan kalksın
            if (liveKeys.remove(sbn.key)) IslandBus.post(IslandEvent.LiveGone(sbn.key))
            return false
        }
        liveKeys.add(sbn.key)
        IslandBus.post(IslandEvent.LiveUpdate(live))
        return true
    }

    private fun handleNotification(sbn: StatusBarNotification) {
        val n = sbn.notification
        // Deneme bildirimi (Ana Sayfa > Bildirimler > Bildirim testi): başka bir uygulamadan gelmiş gibi işlenir
        val other = sbn.packageName != packageName || isTest(sbn, packageName)
        // Saat uygulaması: çalan alarm / zamanlayıcı adada (ertele, kapat, duraklat düğmeleriyle)
        if (ClockFlags.enabled && runCatching { handleClock(sbn) }.getOrDefault(false)) return
        // Ses kaydedici: kayıt sürerken adada dalga + süre
        if (ClockFlags.voiceRec && runCatching { handleVoiceRec(sbn) }.getOrDefault(false)) return
        // SoundCapsule: dinleme / sonuç bildirimleri adada özel animasyonla gösterilir
        if (handleSoundCapsule(sbn)) return
        // Sistem ekran kaydı bildirimi (kayıt / işleniyor / kaydedildi) ayrı yoldan gider
        if (isScreenRec(sbn)) { handleScreenRec(sbn); return }
        // Arama bildirimleri (gelen / aktif) ayrı yoldan gider; "devam eden" filtresine takılmaz
        if (sbn.packageName != packageName && n.category == Notification.CATEGORY_CALL) {
            handleCall(sbn)
            return
        }
        // Doğrulama kodu: kod kartı olarak gösterilir (kod hiçbir yere kaydedilmez)
        if (NotifFlags.otp && other && !sbn.isOngoing) {
            val code = OtpParse.of(sbn)
            if (code != null) {
                val icon = runCatching { packageManager.getApplicationIcon(sbn.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                IslandBus.post(IslandEvent.LiveUpdate(IslandContent.Live(
                    kind = LiveKind.Code, pkg = sbn.packageName, key = sbn.key, big = code,
                    title = "Doğrulama kodu", text = "Dokun, kopyala", icon = icon, intent = n.contentIntent,
                )))
                return
            }
        }
        // Navigasyon / sipariş takibi: adada canlı etkinlik olarak kalır (özellik kapalıysa normal yoldan gider)
        if (other && LiveFlags.any && handleLive(sbn)) return
        if (!other || sbn.isOngoing ||
            n.flags and Notification.FLAG_GROUP_SUMMARY != 0 ||
            n.category == Notification.CATEGORY_TRANSPORT ||
            n.category == Notification.CATEGORY_PROGRESS
        ) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val avatar = runCatching {
            n.getLargeIcon()?.loadDrawable(this)?.toBitmap(128, 128)?.asImageBitmap()
        }.getOrNull()
        // Sohbet bildirimi mi? (WhatsApp, Telegram, SMS... MessagingStyle kullanır ya da mesaj kategorisindedir)
        val messaging = n.category == Notification.CATEGORY_MESSAGE ||
            ex.containsKey(Notification.EXTRA_MESSAGES) ||
            (ex.getString(Notification.EXTRA_TEMPLATE)?.contains("MessagingStyle") == true)
        // Önemli kişiler: seçili olmayan sohbetler sessiz listeye gider
        if (NotifFlags.important && messaging && !ImportantMatch.matches(NotifFlags.names, title, sbn)) {
            NotifVault.addSilent(NotifVault.Entry(sbn.packageName, title, text, System.currentTimeMillis(), n.contentIntent))
            return
        }
        // Sessiz bildirim özeti: düşük öncelikli bildirimler adayı açmaz
        if (NotifFlags.digest && !messaging && isLowPriority(sbn)) {
            NotifVault.addSilent(NotifVault.Entry(sbn.packageName, title, text, System.currentTimeMillis(), n.contentIntent))
            return
        }
        // Okunmamış mesaj hatırlatıcısı
        if (NotifFlags.remind && messaging) reminder.onPosted(sbn.key, sbn.packageName, title, n.contentIntent)
        IslandBus.post(IslandEvent.Notification(sbn.packageName, title, text, n.contentIntent, sbn.key, avatar, messaging))
    }

    /** Düşük öncelikli mi? Kanal önemi "düşük" ya da altı, veya kampanya / öneri kategorisi. */
    private fun isLowPriority(sbn: StatusBarNotification): Boolean {
        val cat = sbn.notification?.category
        if (cat == Notification.CATEGORY_PROMO || cat == Notification.CATEGORY_RECOMMENDATION) return true
        return runCatching {
            val r = NotificationListenerService.Ranking()
            currentRanking?.getRanking(sbn.key, r) == true && r.importance in 1..android.app.NotificationManager.IMPORTANCE_LOW
        }.getOrDefault(false)
    }

    private val reminder = UnreadReminder { pkg, title, intent, key ->
        if (!NotifFlags.remind) return@UnreadReminder
        if (NotifFlags.important && !NotifFlags.names.any { title.lowercase().contains(it.lowercase()) }) return@UnreadReminder
        IslandBus.post(IslandEvent.Notification(pkg, title, "30 dakikadır yanıt bekliyor", intent, key, null, true))
    }
}
