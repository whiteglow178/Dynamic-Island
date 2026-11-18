package com.example.dynamicisland

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Ana Sayfa > Dene bölümündeki sahte arama olayının anahtarı. */
const val DEMO_CALL_KEY = "demo_call"

/** Dokunup açılan aktif arama ekranının kendiliğinden küçülme süresi (ms). */
const val CALL_AUTO_COLLAPSE_MS = 5_000L

/**
 * Gelen / aktif arama mantığı (eskiden IslandOverlayService içindeydi).
 * Servisin özel alanlarına dokunmaz; yalnızca [IslandHost] üzerinden konuşur.
 */
class CallController(
    private val host: IslandHost,
    private val side: SideCallOverlay,
) {
    var key: String? = null
        private set
    var incoming = true
        private set
    var speaker = false
        private set

    /** Arama paneli açık mı (aktif aramada dokununca açılır / küçülür). */
    var expanded = true
    /** Aramaya yan baloncuktan dokunulduysa açılış yönü (animasyon için). */
    var fromSide = 0

    private var name = ""
    private var avatar: ImageBitmap? = null
    private var since = 0L
    private var answer: PendingIntent? = null
    private var decline: PendingIntent? = null
    private var tickJob: Job? = null
    private var endJob: Job? = null
    private var collapseJob: Job? = null

    /** Herhangi bir arama var (gelen ya da aktif). */
    val active: Boolean get() = key != null
    /** Gelen arama çalıyor. */
    val ringing: Boolean get() = key != null && incoming
    /** Aktif (cevaplanmış) arama var. */
    val ongoing: Boolean get() = key != null && !incoming

    fun onCall(e: IslandEvent.Call) {
        endJob?.cancel()
        if (e.incoming || !host.isPeeking) host.cancelCollapse()      // baloncuktan açılan panelin kapanma zamanlayıcısı bozulmasın
        val justAnswered = !e.incoming && (key == null || incoming)    // yeni aktif arama / az önce cevaplandı
        key = e.key
        name = e.name
        avatar = e.avatar
        incoming = e.incoming
        answer = e.answer
        decline = e.decline
        tickJob?.cancel()
        if (e.incoming) {
            since = 0L
            expanded = true; collapseJob?.cancel()                    // gelen arama hep açık
        } else {
            val now = System.currentTimeMillis()
            if (since == 0L) since = e.startedAt.takeIf { it in 1..now } ?: now
            tickJob = host.scope.launch { while (true) { delay(1_000); publish() } }
        }
        if (justAnswered) { expanded = false; collapseJob?.cancel() } // cevaplanınca küçük ada
        publish()
        syncSideButtons()
    }

    /** Adada gösterilecek arama içeriği (arama yoksa null). */
    fun content(): IslandContent.Call? =
        if (key == null) null
        else IslandContent.Call(name, incoming, if (incoming) null else duration(), avatar, speaker)

    fun publish() {
        host.syncBgOrder()
        val dur = if (incoming) null else duration()
        // Yan baloncuktan açılan olay ortadayken: açık / kapalı durumunu yalnızca peek yönetir.
        // Saniyelik süre güncellemesi paneli ne kapatır ne de ortayı geri alır; yalnızca süre yenilenir.
        if (!incoming && host.isPeeking) {
            host.update { st ->
                if (st.content is IslandContent.Call) st.copy(content = IslandContent.Call(name, false, dur, avatar, speaker)) else st
            }
            return
        }
        // Aktif arama, daha yeni bir olayın (müzik / ekran kaydı) arkasında kaldıysa ortayı almaz: yalnızca yan baloncuk yenilenir
        if (!incoming && host.content !is IslandContent.Call && host.newerThanCall) {
            host.update { it }
            return
        }
        if (!expanded) fromSide = 0
        host.update {
            IslandUiState(IslandContent.Call(name, incoming, dur, avatar, speaker), expanded = expanded, fromSide = fromSide)
        }
    }

    private fun duration(): String = formatDuration(System.currentTimeMillis() - since)

    private fun formatDuration(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Bildirim kalkınca adayı kapatır. Gelen -> aktif geçişinde bildirim değiştiği için kısa süre beklenir (titreme olmasın). */
    fun onEnded(k: String) {
        if (k != key) return
        endJob?.cancel()
        endJob = host.scope.launch {
            delay(600)
            if (key != k) return@launch
            tickJob?.cancel()
            reset()
            setSpeaker(false)
            expanded = true; collapseJob?.cancel()
            syncSideButtons()
            if (host.content is IslandContent.Call) host.collapseAndRest() else host.update { it }
        }
    }

    fun send(pi: PendingIntent?) {
        if (pi == null) return
        runCatching {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pi.send(host.context, 0, null, null, null, null, opts)
        }
    }

    fun onAnswer() {
        if (key == DEMO_CALL_KEY) {
            // Ana Sayfa > Dene: sahte arama. Cevapla -> aktif aramaya geç
            IslandBus.post(IslandEvent.Call(DEMO_CALL_KEY, "demo", name, false, System.currentTimeMillis(), avatar, null, null))
            return
        }
        send(answer)
    }

    fun onDecline() {
        // Reddet / kapat: bildirimin kalkmasını bekleme, adayı hemen kapat
        if (key != DEMO_CALL_KEY) send(decline)
        endNow()
    }

    fun onSpeaker() {
        setSpeaker(!speaker)
        publish()
        if (expanded) scheduleCollapse()   // düğmeyle uğraşırken küçülmesin
    }

    /** Aktif aramada dokunma: paneli açar / küçültür. */
    fun toggleExpanded() {
        if (expanded) { expanded = false; collapseJob?.cancel(); publish() }
        else { expanded = true; publish(); scheduleCollapse() }
    }

    fun cancelCollapseTimer() { collapseJob?.cancel() }

    /** Açılmış aktif arama ekranı bir süre sonra kendiliğinden küçülür. */
    fun scheduleCollapse(holdMs: Long = CALL_AUTO_COLLAPSE_MS) {
        collapseJob?.cancel()
        collapseJob = host.scope.launch {
            delay(holdMs)
            if (key != null && !incoming) { expanded = false; publish() }
        }
    }

    /** Aramayı yerelde anında bitirir: bekleme yok, ada hemen küçülüp kapanır. */
    private fun endNow() {
        endJob?.cancel(); tickJob?.cancel(); collapseJob?.cancel()
        reset()
        setSpeaker(false)
        expanded = true
        syncSideButtons()
        host.collapseSoon()
    }

    private fun reset() {
        key = null; since = 0L; answer = null; decline = null; avatar = null
    }

    /** Hoparlörü açar / kapatır (aktif arama ekranındaki hoparlör düğmesi). */
    private fun setSpeaker(on: Boolean) {
        speaker = on
        runCatching {
            val am = host.context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    am.availableCommunicationDevices
                        .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        }
    }

    /** Gelen aramada (ve ayar açıksa) kenar Cevapla / Reddet tuşlarını gösterir; aksi halde gizler. */
    fun syncSideButtons() {
        val ctx = host.context
        val p = SideCallStore.flow(ctx.applicationContext).value
        val should = p.enabled && key != null && incoming &&
            android.provider.Settings.canDrawOverlays(ctx)
        if (should) {
            side.show(
                p,
                onAnswer = { side.hide(); onAnswer() },
                onDecline = { side.hide(); onDecline() },
            )
        } else side.hide()
    }

    /** Servis kapanırken zamanlayıcıları ve kenar tuşlarını temizler. */
    fun destroy() {
        tickJob?.cancel(); endJob?.cancel(); collapseJob?.cancel()
        runCatching { side.hide() }
    }
}
