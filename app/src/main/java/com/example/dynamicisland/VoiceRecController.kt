package com.example.dynamicisland

import android.app.PendingIntent

/**
 * Ses kaydı adası: ses kaydedici uygulamanın "kaydediliyor" bildirimi geldikçe kapalı adada kırmızı dalga + süre,
 * açınca dalga, süre ve durdur düğmesi. Kayıt bitince (bildirim kalkınca) ada eski haline döner.
 */
class VoiceRecController(private val host: IslandHost) {
    private var key: String? = null
    private var pkg = ""
    private var startedAt = 0L
    private var elapsed = 0L
    private var paused = false
    private var stop: PendingIntent? = null
    private var open: PendingIntent? = null

    val active: Boolean get() = key != null

    fun content(): IslandContent.Status? =
        if (key == null) null
        else IslandContent.Status(
            StatusKind.VoiceRec, title = "Ses kaydı", startedAt = if (paused) 0L else startedAt,
            phase = if (paused) 1 else 0, remainMs = elapsed,
        )

    fun owns(c: IslandContent.Status): Boolean = c.kind == StatusKind.VoiceRec && key != null

    fun onRec(e: IslandEvent.VoiceRec) {
        val first = key == null
        key = e.key; pkg = e.pkg
        paused = e.paused
        elapsed = e.elapsedMs
        startedAt = if (e.startedAt > 0L) e.startedAt else System.currentTimeMillis() - e.elapsedMs
        stop = e.stop
        open = e.open
        host.syncBgOrder()
        val c = content() ?: return
        if (first) {
            if (host.blocked) { host.update { it }; return }
            val cur = host.content
            // Açık bir panel varken araya girmez; yan baloncukta görünür
            if (host.expanded && cur !is IslandContent.Status) { host.update { it }; return }
            host.cancelCollapse()
            host.update { IslandUiState(c, expanded = false) }
        } else {
            host.update { st ->
                val cur = st.content as? IslandContent.Status
                if (cur != null && cur.kind == StatusKind.VoiceRec) st.copy(content = c) else st
            }
        }
    }

    fun onGone(k: String) {
        if (k != key) return
        key = null; stop = null; open = null
        host.syncBgOrder()
        val cur = host.content as? IslandContent.Status
        if (cur != null && cur.kind == StatusKind.VoiceRec) host.collapseSoon() else host.update { it }
    }

    /** Durdur düğmesi: uygulamanın durdur eylemi; yoksa uygulama açılır. */
    fun onStop() {
        val s = stop
        if (s != null) host.send(s) else host.send(open)
        if (host.expanded) host.collapseSoon()
    }

    /** Kapalı adaya dokunma: açılır (5 sn), açıksa küçülür. */
    fun onTap(expanded: Boolean) {
        if (expanded) host.collapseSoon()
        else { host.cancelCollapse(); host.update { it.copy(expanded = true) }; host.scheduleCollapse(6_000L) }
    }
}
