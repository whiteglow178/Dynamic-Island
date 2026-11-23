package com.example.dynamicisland

import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Bildirim dinleyicisinin okuduğu bayrak (servis ayardan yazar). */
object ClockFlags {
    @Volatile var enabled = true
    @Volatile var voiceRec = true
}

/**
 * Alarm ve zamanlayıcı adası.
 *  - Ada zamanlayıcısı: adadaki cetvelden kurulur; duraklat / devam / iptal / yeniden başlat adada yapılır.
 *    Süre dolunca ada genişler, (ayar açıksa) alarm sesi çalar ve telefon titrer.
 *  - Saat uygulamasının zamanlayıcısı ve çalan alarmı bildirimden okunur; düğmeler bildirimin kendi eylemlerini tetikler.
 */
class ClockController(private val host: IslandHost) {

    // ---- ada zamanlayıcısı
    private var endsAt = 0L
    private var totalMs = 0L
    private var pausedLeft = -1L        // >= 0: duraklatıldı, kalan süre
    private var done = false            // süre doldu (ada açık, çalıyor)
    private var doneJob: Job? = null
    private var doneAutoJob: Job? = null

    // ---- saat uygulaması
    private var sysTimer: IslandEvent.ClockTimer? = null
    private var sysAlarm: IslandEvent.ClockAlarm? = null

    private var ring: Ringtone? = null

    private fun now() = System.currentTimeMillis()

    private val islandRunning: Boolean get() = !done && (pausedLeft >= 0L || endsAt > now())

    /** Adada gösterilecek zamanlayıcı / alarm varsa (arka plan olayı olarak) true. */
    fun active(): Boolean = content() != null

    /** Çalan alarm ya da süresi dolmuş zamanlayıcı: ada açık kalır, başka olaylar araya girmez. */
    val ringing: Boolean get() = done || sysAlarm != null

    /** Şu anki zamanlayıcı / alarm görünümü. */
    fun content(): IslandContent.Status? {
        val a = sysAlarm
        return when {
            a != null && a.timerDone -> IslandContent.Status(StatusKind.Timer, title = a.label.ifBlank { "Zamanlayıcı" }, phase = 2, extra = 1)
            a != null -> IslandContent.Status(StatusKind.Alarm, title = a.label.ifBlank { "Alarm" }, value = a.time, phase = 2, extra = 1)
            done -> IslandContent.Status(StatusKind.Timer, title = "Zamanlayıcı", phase = 2, totalMs = totalMs)
            islandRunning -> IslandContent.Status(
                StatusKind.Timer, title = "Zamanlayıcı",
                endsAt = if (pausedLeft >= 0L) 0L else endsAt, totalMs = totalMs,
                phase = if (pausedLeft >= 0L) 1 else 0, remainMs = pausedLeft.coerceAtLeast(0L),
            )
            sysTimer != null -> sysTimer!!.let { t ->
                if (!t.paused && t.endsAt <= now()) null
                else IslandContent.Status(
                    StatusKind.Timer, title = t.label.ifBlank { "Zamanlayıcı" },
                    endsAt = if (t.paused) 0L else t.endsAt, phase = if (t.paused) 1 else 0, remainMs = t.remainMs, extra = 1,
                )
            }
            else -> null
        }
    }

    /** Bu içerik bu denetleyiciye mi ait? (servis ortadaki olayı tanımak için kullanır) */
    fun owns(c: IslandContent.Status): Boolean =
        (c.kind == StatusKind.Timer || c.kind == StatusKind.Alarm) && c.phase != 3 && content() != null

    // ================================================================ Görünümü yayınla

    /** Ortadaki zamanlayıcı görünümünü yeniler (açık / kapalı durumu korunur). */
    private fun refresh() {
        host.syncBgOrder()
        val c = content()
        host.update { st ->
            val cur = st.content as? IslandContent.Status
            if (cur != null && (cur.kind == StatusKind.Timer || cur.kind == StatusKind.Alarm) && cur.phase != 3) {
                if (c != null) st.copy(content = c) else st
            } else st
        }
        if (c == null) {
            val cur = host.content as? IslandContent.Status
            if (cur != null && (cur.kind == StatusKind.Timer || cur.kind == StatusKind.Alarm) && cur.phase != 3) host.collapseSoon()
        }
    }

    /** Görünümü ortada, kapalı olarak gösterir (yeni başlayan zamanlayıcı). */
    private fun showCompact() {
        host.syncBgOrder()
        val c = content() ?: return
        if (host.blocked) { host.update { it }; return }
        host.cancelCollapse()
        host.update { IslandUiState(c, expanded = false) }
    }

    /** Çalan alarm / dolan zamanlayıcı: ada açılır ve kullanıcı kapatana kadar açık kalır. */
    private fun showRinging() {
        host.syncBgOrder()
        val c = content() ?: return
        if (host.busy) { host.update { it }; return }
        host.cancelCollapse()
        host.update { IslandUiState(c, expanded = true) }
    }

    // ================================================================ Kurma paneli

    fun openSetter() {
        if (host.blocked) return
        host.cancelCollapse()
        host.update { IslandUiState(IslandContent.Status(StatusKind.Timer, title = "Zamanlayıcı", phase = 3), expanded = true) }
        host.scheduleCollapse(20_000L)
    }

    // ================================================================ Düğmeler

    fun start(ms: Long) {
        if (ms <= 0L) return
        stopRing()
        doneJob?.cancel(); doneAutoJob?.cancel()
        done = false
        totalMs = ms
        endsAt = now() + ms
        pausedLeft = -1L
        scheduleDone()
        showCompact()
    }

    private fun scheduleDone() {
        doneJob?.cancel()
        val left = endsAt - now()
        if (left <= 0L || pausedLeft >= 0L) return
        doneJob = host.scope.launch {
            delay(left)
            if (pausedLeft < 0L && !done && endsAt <= now() + 50L) onIslandDone()
        }
    }

    private fun onIslandDone() {
        done = true
        if (host.settings.more.timerSound) startRing()
        showRinging()
        doneAutoJob?.cancel()
        doneAutoJob = host.scope.launch { delay(60_000L); if (done) dismiss() }
    }

    fun pauseResume() {
        val t = sysTimer
        if (islandRunning) {
            if (pausedLeft >= 0L) {
                endsAt = now() + pausedLeft
                pausedLeft = -1L
                scheduleDone()
            } else {
                pausedLeft = (endsAt - now()).coerceAtLeast(0L)
                doneJob?.cancel()
            }
            refresh()
        } else if (t != null) {
            host.send(if (t.paused) t.resume ?: t.pause else t.pause ?: t.resume)
        }
        if (host.expanded) host.scheduleCollapse(5_000L)
    }

    fun cancel() {
        val t = sysTimer
        if (islandRunning || done) {
            doneJob?.cancel(); doneAutoJob?.cancel()
            stopRing()
            done = false; endsAt = 0L; pausedLeft = -1L
        } else if (t != null) {
            host.send(t.cancel)
            sysTimer = null
        }
        finishView()
    }

    fun restart() {
        if (totalMs > 0L && sysAlarm == null) start(totalMs) else dismiss()
    }

    /** Alarm / dolan zamanlayıcı: kapat. */
    fun dismiss() {
        val a = sysAlarm
        if (a != null) {
            host.send(a.dismiss)
            sysAlarm = null
        } else {
            doneAutoJob?.cancel()
            stopRing()
            done = false; endsAt = 0L; pausedLeft = -1L
        }
        finishView()
    }

    fun snooze() {
        val a = sysAlarm ?: return dismiss()
        host.send(a.snooze ?: a.dismiss)
        sysAlarm = null
        finishView()
    }

    private fun finishView() {
        host.syncBgOrder()
        val cur = host.content as? IslandContent.Status
        if (cur != null && (cur.kind == StatusKind.Timer || cur.kind == StatusKind.Alarm)) {
            val next = content()
            if (next != null) host.update { IslandUiState(next, expanded = false) } else host.collapseSoon()
        } else host.update { it }
    }

    /** Kapalı zamanlayıcıya dokunma: açılır (5 sn), açıksa küçülür. Çalarken dokunuş bir şey yapmaz (düğmeler var). */
    fun onTap(expanded: Boolean, c: IslandContent.Status) {
        if (c.phase == 2 || c.phase == 3) return
        if (expanded) host.collapseSoon()
        else { host.cancelCollapse(); host.update { it.copy(expanded = true) }; host.scheduleCollapse(5_000L) }
    }

    // ================================================================ Saat uygulaması (bildirim)

    fun onSysTimer(e: IslandEvent.ClockTimer) {
        val first = sysTimer == null
        sysTimer = e
        if (sysAlarm?.key == e.key) sysAlarm = null
        if (first && !islandRunning && !done) {
            val cur = host.content
            if (!host.expanded || cur is IslandContent.Status) showCompact() else host.update { it }
        } else refresh()
    }

    fun onSysAlarm(e: IslandEvent.ClockAlarm) {
        val same = sysAlarm?.key == e.key
        sysAlarm = e
        if (e.timerDone && sysTimer?.key == e.key) sysTimer = null
        if (!same) showRinging() else refresh()
    }

    fun onSysGone(key: String) {
        var changed = false
        if (sysAlarm?.key == key) { sysAlarm = null; changed = true }
        if (sysTimer?.key == key) { sysTimer = null; changed = true }
        if (changed) finishView()
    }

    // ================================================================ Ses + titreşim

    private fun startRing() {
        val ctx = host.context
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val r = RingtoneManager.getRingtone(ctx, uri)
            r.audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            if (Build.VERSION.SDK_INT >= 28) r.isLooping = true
            r.play()
            ring = r
        }
        runCatching {
            val pattern = longArrayOf(0, 500, 500)
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun stopRing() {
        runCatching { ring?.stop() }
        ring = null
        runCatching {
            val ctx = host.context
            val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
                else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
            v?.cancel()
        }
    }

    fun destroy() {
        doneJob?.cancel(); doneAutoJob?.cancel()
        stopRing()
    }
}
