package com.example.dynamicisland

import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Adadaki bildirimin yaşam döngüsü: genişletilmiş süre (varsayılan 5 sn) + küçültülmüş süre (varsayılan 10 sn).
 * Servisten taşındı; davranış aynıdır.
 */
class NotifController(private val host: IslandHost) {

    /** Nokta modunda küçültülmüş süre boyunca noktada tutulan (en son) bildirim. */
    var bg: IslandContent.Notif? = null
        private set
    private var job: Job? = null

    fun show(n: IslandContent.Notif) {
        val s = host.settings
        host.cancelCollapse()
        job?.cancel()
        bg = if (s.dotMode) n else null
        if (s.notifInline && !s.dotMode) {
            // Ada genişlemez: bildirim kapalı adanın içinde (simge + gönderen + yazı) toplam süre boyunca görünür
            host.update { IslandUiState(n, expanded = false) }
            job = host.scope.launch {
                delay(((s.notifExpandedSec + s.notifCompactSec) * 1000f).toLong())
                finish()
            }
            return
        }
        host.update { IslandUiState(n, expanded = true) }
        job = host.scope.launch {
            delay((s.notifExpandedSec * 1000f).toLong())
            compact(n)
        }
    }

    /** Genişletilmiş süre bitti ya da kullanıcı küçülttü: bildirim küçültülmüş halde (nokta modunda nokta) bir süre daha kalır. */
    private suspend fun compact(n: IslandContent.Notif) {
        val s = host.settings
        if (host.content == n && host.expanded) {
            host.update { it.copy(expanded = false) }
            delay(s.settleMs())
        }
        delay((s.notifCompactSec * 1000f).toLong())
        finish()
    }

    /** Küçülmüş bildirime dokunuldu: yeniden genişler, genişletilmiş süre baştan sayılır, sonra yine küçülür. */
    fun expandAgain(n: IslandContent.Notif) {
        val s = host.settings
        host.cancelCollapse()
        job?.cancel()
        host.update { it.copy(content = n, expanded = true) }
        job = host.scope.launch {
            delay((s.notifExpandedSec * 1000f).toLong())
            compact(n)
        }
    }

    /** Bildirimi hemen küçültülmüş hale geçirir (açıkken küçültme hareketi). */
    fun compactNow(n: IslandContent.Notif) {
        job?.cancel()
        host.cancelCollapse()
        job = host.scope.launch { compact(n) }
    }

    fun clear() { job?.cancel(); job = null; bg = null }

    /** Bildirim süresi doldu / bildirim silindi ya da açıldı: ada bir sonraki olaya (ya da boşa) döner. */
    fun finish() {
        bg = null
        val cur = host.content
        when {
            cur !is IslandContent.Notif -> host.update { it }
            host.expanded -> host.collapseSoon()
            else -> host.rest()
        }
    }
}
