package com.example.dynamicisland

import android.app.PendingIntent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** SoundCapsule: dinleme animasyonu + bulunan şarkı. Servisten taşındı; davranış aynıdır. */
class SoundCapsuleController(private val host: IslandHost) {

    var phase: SoundCapsulePhase? = null
        private set
    var open: PendingIntent? = null
        private set
    private var job: Job? = null

    private companion object {
        const val LISTEN_MAX_MS = 25_000L     // sonuç gelmezse dinleme görünümü en fazla bu kadar kalır
        const val GONE_GRACE_MS = 1_500L      // dinleme bildirimi kalkınca sonuç bildiriminin gelmesi için tolerans
        const val HOLD_MS = 7_000L            // bulunan şarkı panelinin açık kalma süresi
    }

    /** SoundCapsule dinlemeye başladı: ada kapalı halde kalır, solda SoundCapsule logosu + sağda ses dalgası gösterilir. */
    fun onListening() {
        if (host.blocked) return
        phase = SoundCapsulePhase.Listening
        open = null
        host.cancelCollapse()
        host.update { IslandUiState(IslandContent.SoundCapsule(SoundCapsulePhase.Listening), expanded = false) }
        job?.cancel()
        job = host.scope.launch { delay(LISTEN_MAX_MS); if (phase == SoundCapsulePhase.Listening) end() }
    }

    /** Şarkı bulundu: ada genişler, kapak patlama animasyonuyla belirir; şarkı adı + sanatçı gösterilir. */
    fun onFound(e: IslandEvent.SoundCapsuleFound) {
        if (host.blocked) return
        if (e.title.isBlank() && e.artist.isBlank()) return
        phase = SoundCapsulePhase.Found
        open = e.open
        host.cancelCollapse()
        // Dinleme kapalı adadaysa aynı içerik türü: ada yerinde büyür (logo + dalga bulanıp kaybolur)
        host.update { IslandUiState(IslandContent.SoundCapsule(SoundCapsulePhase.Found, e.title, e.artist, e.cover), expanded = true) }
        job?.cancel()
        job = host.scope.launch { delay(HOLD_MS); if (phase == SoundCapsulePhase.Found) end() }
    }

    /** Dinleme bildirimi kalktı / eşleşme yok: kısa bir tolerans sonra ada eski haline döner. */
    fun onGone() {
        if (phase != SoundCapsulePhase.Listening) return
        job?.cancel()
        job = host.scope.launch { delay(GONE_GRACE_MS); if (phase == SoundCapsulePhase.Listening) end() }
    }

    /** Genişlemiş dinleme panelindeki X: SoundCapsule'ın dinleme bildirimini kapatmayı dener ve adayı kapatır. */
    fun onStop() {
        IslandNotificationListener.stopSoundCapsuleListening()
        end()
    }

    fun end() {
        job?.cancel(); job = null
        phase = null; open = null
        if (host.content is IslandContent.SoundCapsule) host.collapseSoon()
        else host.update { it }      // yan noktadaki SoundCapsule dinleme olayı kalksın
    }
}
