package com.example.dynamicisland

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
 * Alarm, zamanlayıcı ve kronometre adası.
 *  - Ada zamanlayıcısı: adadaki cetvelden kurulur; duraklat / devam / iptal / yeniden başlat adada yapılır.
 *    Süre dolunca ada genişler, (ayar açıksa) alarm sesi çalar ve telefon titrer.
 *  - Saat uygulamasının zamanlayıcısı, kronometresi ve çalan alarmı bildirimden okunur; düğmeler bildirimin kendi
 *    eylemlerini tetikler (eylem bulunamazsa saat uygulaması açılır). Düğmeye basınca ada hemen tepki verir.
 *  - Alarm kurulunca (sistem "sonraki alarm" yayını) ada kısa süre "Alarm 07:30 · kuruldu" gösterir. Alarm saati gelince
 *    bildirim okunamazsa da ada genişleyip alarmı gösterir.
 *
 * İçerik kuralları (IslandContent.Status):
 *  - Zamanlayıcı: kind Timer; extra 0 ada, 1 saat uygulaması; phase 0 çalışıyor, 1 duraklatıldı, 2 süre doldu, 3 kurma paneli.
 *  - Kronometre: kind Timer, extra 2; phase 0 çalışıyor (startedAt), 1 duraklatıldı (remainMs = geçen süre).
 *  - Alarm: kind Alarm; phase 1 kuruldu bilgisi, 2 çalıyor; extra 1 bildirimden, 3 alarm saatinden (yedek).
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
    private var sysTimerTotal = 0L      // görülen en büyük kalan süre: kapalı adadaki halka bunun oranıyla azalır
    private var sysWatch: IslandEvent.ClockStopwatch? = null
    private var sysAlarm: IslandEvent.ClockAlarm? = null

    // ---- alarm saati (AlarmManager)
    private var receiver: BroadcastReceiver? = null
    private var nextTrigger = 0L
    private var fallbackJob: Job? = null
    private var fallbackAlarm: Pair<String, android.app.PendingIntent?>? = null   // (saat, saat uygulamasını açan hedef)
    private var fallbackAutoJob: Job? = null
    private var lastRingAt = 0L
    private var lastToggleAt = 0L       // duraklat / devam düğmesine son basılış: bildirim yeniden kurulurken ada kapanmasın
    private var goneJob: Job? = null
    // Saat uygulaması zamanlayıcıyı sisteme "alarm" olarak da kaydedebiliyor: zamanlayıcı başlayınca / duraklayınca /
    // iptal edilince "sonraki alarm değişti" yayını gelir. Bu yayınlar gerçek alarm sanılıp ada alarm bilgisi göstermesin diye
    // zamanlayıcıyla ilgili son hareketin zamanı ve görülen bitiş zamanları tutulur.
    private var lastTimerActivityAt = 0L
    private val timerEnds = ArrayDeque<Long>()

    private fun noteTimer(end: Long = 0L) {
        lastTimerActivityAt = now()
        if (end > 0L) {
            timerEnds.addLast(end)
            while (timerEnds.size > 8) timerEnds.removeFirst()
        }
    }

    /** Bu alarm zamanı aslında bir zamanlayıcının bitişi mi (ya da zamanlayıcıya az önce dokunuldu mu)? */
    private fun timerLike(trig: Long): Boolean {
        if (now() - lastTimerActivityAt < 6_000L) return true
        val t = sysTimer
        val ends = buildList {
            addAll(timerEnds)
            if (endsAt > 0L) add(endsAt)
            if (t != null && !t.paused && t.endsAt > 0L) add(t.endsAt)
        }
        return ends.any { kotlin.math.abs(it - trig) < 5_000L }
    }

    private var ring: Ringtone? = null

    private fun now() = System.currentTimeMillis()

    private val islandRunning: Boolean get() = !done && (pausedLeft >= 0L || endsAt > now())

    /** Adada gösterilecek zamanlayıcı / kronometre / alarm varsa (arka plan olayı olarak) true. */
    fun active(): Boolean = content() != null

    /** Çalan alarm ya da süresi dolmuş zamanlayıcı: ada açık kalır, başka olaylar araya girmez. */
    val ringing: Boolean get() = done || sysAlarm != null || fallbackAlarm != null

    /** Şu anki zamanlayıcı / kronometre / alarm görünümü. */
    fun content(): IslandContent.Status? {
        val a = sysAlarm
        val fb = fallbackAlarm
        val t = sysTimer
        val w = sysWatch
        return when {
            a != null && a.timerDone -> IslandContent.Status(StatusKind.Timer, title = "Zamanlayıcı", phase = 2, extra = 1)
            a != null -> IslandContent.Status(StatusKind.Alarm, title = a.label.ifBlank { "Alarm" }, value = a.time, phase = 2, extra = 1)
            fb != null -> IslandContent.Status(StatusKind.Alarm, title = "Alarm", value = fb.first, phase = 2, extra = 3)
            done -> IslandContent.Status(StatusKind.Timer, title = "Zamanlayıcı", phase = 2, totalMs = totalMs)
            islandRunning -> IslandContent.Status(
                StatusKind.Timer, title = focusTitle(),
                endsAt = if (pausedLeft >= 0L) 0L else endsAt, totalMs = totalMs,
                phase = if (pausedLeft >= 0L) 1 else 0, remainMs = pausedLeft.coerceAtLeast(0L),
            )
            t != null && (t.paused || t.endsAt > now()) -> IslandContent.Status(
                StatusKind.Timer, title = "Zamanlayıcı",
                endsAt = if (t.paused) 0L else t.endsAt, totalMs = sysTimerTotal,
                phase = if (t.paused) 1 else 0, remainMs = t.remainMs, extra = 1,
            )
            w != null -> IslandContent.Status(
                StatusKind.Timer, title = "Kronometre",
                startedAt = if (w.paused) 0L else w.startedAt, phase = if (w.paused) 1 else 0, remainMs = w.elapsedMs, extra = 2,
            )
            else -> null
        }
    }

    /** Bu içerik bu denetleyiciye mi ait? (servis ortadaki olayı tanımak için kullanır) */
    fun owns(c: IslandContent.Status): Boolean =
        (c.kind == StatusKind.Timer || c.kind == StatusKind.Alarm) && c.phase != 3 && !(c.kind == StatusKind.Alarm && c.phase == 1) &&
            content() != null

    // ================================================================ Başlat / durdur

    /** Alarm kurma yayınını dinlemeye başlar (servis açılınca bir kez). */
    fun start() {
        if (receiver != null) return
        val am = host.context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        nextTrigger = runCatching { am?.nextAlarmClock?.triggerTime ?: 0L }.getOrDefault(0L)
        scheduleFallback(am)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { host.scope.launch { runCatching { onNextAlarmChanged() } } }
        }
        val f = IntentFilter(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) host.context.registerReceiver(r, f, Context.RECEIVER_EXPORTED)
            else host.context.registerReceiver(r, f)
        }.isSuccess
        if (ok) receiver = r
    }

    fun destroy() {
        doneJob?.cancel(); doneAutoJob?.cancel(); fallbackJob?.cancel(); fallbackAutoJob?.cancel()
        receiver?.let { runCatching { host.context.unregisterReceiver(it) } }
        receiver = null
        stopRing()
    }

    // ================================================================ Görünümü yayınla

    private fun isClockView(c: IslandContent?): Boolean {
        val s = c as? IslandContent.Status ?: return false
        return (s.kind == StatusKind.Timer || s.kind == StatusKind.Alarm) && s.phase != 3 && !(s.kind == StatusKind.Alarm && s.phase == 1)
    }

    /** Ortadaki zamanlayıcı görünümünü yeniler (açık / kapalı durumu korunur). */
    private fun refresh() {
        host.syncBgOrder()
        val c = content()
        host.update { st ->
            if (isClockView(st.content)) { if (c != null) st.copy(content = c) else st } else st
        }
        if (c == null && isClockView(host.content)) host.collapseSoon()
    }

    /** Görünümü ortada, kapalı olarak gösterir (yeni başlayan zamanlayıcı / kronometre). */
    private fun showCompact() {
        host.syncBgOrder()
        val c = content() ?: return
        if (host.blocked) { host.update { it }; return }
        host.cancelCollapse()
        host.update { IslandUiState(c, expanded = false) }
    }

    /** Çalan alarm / dolan zamanlayıcı: ada açılır ve kullanıcı kapatana kadar açık kalır. */
    private fun showRinging() {
        lastRingAt = now()
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

    // ================================================================ Odak zamanlayıcısı
    /** 0 = yok, 1 = odak (çalışma), 2 = mola. Odak / mola bitince sıradaki kendiliğinden başlar. */
    var focusPhase = 0
        private set
    private var focusRounds = 0

    private fun focusTitle() = when (focusPhase) { 1 -> "Odak"; 2 -> "Mola"; else -> "Zamanlayıcı" }

    private fun setFocusPhase(p: Int) {
        focusPhase = p
        NotifPrivacy.focusing.value = p == 1
    }

    /** Odak döngüsünü başlatır: çalışma -> kısa mola -> ... (her 4 odakta bir uzun mola). */
    fun startFocus() {
        focusRounds = 0
        setFocusPhase(1)
        startRaw(host.settings.more.focusWorkMin.coerceIn(1, 180) * 60_000L)
    }

    /** Odak / mola bitti: kısa titreşim ve sıradaki aşama (zil çalmaz, ada beklemez). */
    private fun nextFocus() {
        val m = host.settings.more
        if (focusPhase == 1) {
            focusRounds++
            val long = focusRounds % 4 == 0
            setFocusPhase(2)
            startRaw((if (long) m.focusLongMin else m.focusBreakMin).coerceIn(1, 60) * 60_000L)
            buzzFocus(longArrayOf(0, 120, 120, 120))
        } else {
            setFocusPhase(1)
            startRaw(m.focusWorkMin.coerceIn(1, 180) * 60_000L)
            buzzFocus(longArrayOf(0, 300))
        }
        refresh()
        // Aşama değişimi kısa süre açık adada görünür
        if (!host.busy && isClockView(host.content)) {
            host.cancelCollapse(); host.update { it.copy(expanded = true) }; host.scheduleCollapse(3_000L)
        }
    }

    private fun buzzFocus(p: LongArray) {
        runCatching {
            @Suppress("DEPRECATION")
            val v: Vibrator? = if (Build.VERSION.SDK_INT >= 31)
                host.context.getSystemService(VibratorManager::class.java)?.defaultVibrator
            else host.context.getSystemService(Vibrator::class.java)
            v?.vibrate(VibrationEffect.createWaveform(p, -1))
        }
    }

    fun start(ms: Long) {
        if (ms <= 0L) return
        setFocusPhase(0)
        startRaw(ms)
    }

    private fun startRaw(ms: Long) {
        if (ms <= 0L) return
        stopRing()
        doneJob?.cancel(); doneAutoJob?.cancel()
        done = false
        totalMs = ms
        endsAt = now() + ms
        pausedLeft = -1L
        noteTimer(endsAt)
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
        if (focusPhase != 0) { nextFocus(); return }
        done = true
        if (host.settings.more.timerSound) startRing()
        showRinging()
        doneAutoJob?.cancel()
        doneAutoJob = host.scope.launch { delay(60_000L); if (done) dismiss() }
    }

    /** Duraklat / devam. Ada hemen tepki verir; saat uygulamasına da kendi eylemi gönderilir. */
    fun pauseResume() {
        val t = sysTimer
        val w = sysWatch
        noteTimer(t?.endsAt ?: endsAt)
        when {
            islandRunning -> {
                if (pausedLeft >= 0L) {
                    endsAt = now() + pausedLeft
                    pausedLeft = -1L
                    scheduleDone()
                } else {
                    pausedLeft = (endsAt - now()).coerceAtLeast(0L)
                    doneJob?.cancel()
                }
                refresh()
            }
            t != null -> {
                lastToggleAt = now()
                val pi = if (t.paused) t.resume ?: t.pause else t.pause ?: t.resume
                if (pi != null) host.send(pi) else host.send(t.open)
                // Bildirim güncellenene kadar ada hemen yeni durumu gösterir
                sysTimer = if (t.paused) t.copy(paused = false, endsAt = now() + t.remainMs)
                    else t.copy(paused = true, remainMs = (t.endsAt - now()).coerceAtLeast(0L))
                refresh()
            }
            w != null -> {
                lastToggleAt = now()
                val pi = if (w.paused) w.resume ?: w.pause else w.pause ?: w.resume
                if (pi != null) host.send(pi) else host.send(w.open)
                sysWatch = if (w.paused) w.copy(paused = false, startedAt = now() - w.elapsedMs)
                    else w.copy(paused = true, elapsedMs = (now() - w.startedAt).coerceAtLeast(0L))
                refresh()
            }
        }
        if (host.expanded) host.scheduleCollapse(5_000L)
    }

    /** X: zamanlayıcıyı iptal eder / kronometreyi sıfırlar. */
    fun cancel() {
        val t = sysTimer
        val w = sysWatch
        noteTimer(t?.endsAt ?: endsAt)
        when {
            islandRunning || done -> {
                doneJob?.cancel(); doneAutoJob?.cancel()
                stopRing()
                done = false; endsAt = 0L; pausedLeft = -1L
                setFocusPhase(0)
            }
            t != null -> {
                if (t.cancel != null) host.send(t.cancel) else host.send(t.open)
                sysTimer = null
            }
            w != null -> {
                if (w.reset != null) host.send(w.reset) else host.send(w.open)
                sysWatch = null
            }
        }
        finishView()
    }

    fun restart() {
        if (totalMs > 0L && sysAlarm == null && fallbackAlarm == null) start(totalMs) else dismiss()
    }

    /** Alarm / dolan zamanlayıcı: kapat. */
    fun dismiss() {
        val a = sysAlarm
        when {
            a != null -> { host.send(a.dismiss); sysAlarm = null }
            fallbackAlarm != null -> { fallbackAutoJob?.cancel(); fallbackAlarm = null }
            else -> {
                doneAutoJob?.cancel()
                stopRing()
                done = false; endsAt = 0L; pausedLeft = -1L
            }
        }
        finishView()
    }

    /** Alarm: ertele (yedek alarmda saat uygulaması açılır). */
    fun snooze() {
        val a = sysAlarm
        val fb = fallbackAlarm
        when {
            a != null -> { host.send(a.snooze ?: a.dismiss); sysAlarm = null }
            fb != null -> { host.send(fb.second); fallbackAutoJob?.cancel(); fallbackAlarm = null }
            else -> return dismiss()
        }
        finishView()
    }

    private fun finishView() {
        host.syncBgOrder()
        if (isClockView(host.content)) {
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

    fun onSysTimer(ev: IslandEvent.ClockTimer) {
        goneJob?.cancel()
        val prev = sysTimer
        val first = prev?.key != ev.key
        if (first) sysTimerTotal = 0L
        // Duraklatılmış bildirim kalan süreyi yazmıyorsa son bilinen kalan süre korunur
        val e = if (ev.paused && ev.remainMs <= 0L && prev != null && !first) {
            val keep = if (prev.paused) prev.remainMs else (prev.endsAt - now()).coerceAtLeast(0L)
            ev.copy(remainMs = keep, pause = ev.pause ?: prev.pause, resume = ev.resume ?: prev.resume, cancel = ev.cancel ?: prev.cancel)
        } else ev
        val left = if (e.paused) e.remainMs else (e.endsAt - now()).coerceAtLeast(0L)
        sysTimerTotal = maxOf(sysTimerTotal, left)
        if (first || prev?.paused != e.paused) noteTimer(if (e.paused) 0L else e.endsAt)
        else if (!e.paused && e.endsAt > 0L && timerEnds.lastOrNull() != e.endsAt) {
            timerEnds.addLast(e.endsAt)                       // yalnızca bitiş zamanını hatırla (dokunuş sayılmaz)
            while (timerEnds.size > 8) timerEnds.removeFirst()
        }
        sysTimer = e
        if (sysAlarm?.key == e.key) sysAlarm = null
        if (first && !islandRunning && !done) {
            if (!host.expanded || isClockView(host.content)) showCompact() else host.update { it }
        } else refresh()
    }

    fun onSysStopwatch(ev: IslandEvent.ClockStopwatch) {
        goneJob?.cancel()
        val prev = sysWatch
        val first = prev?.key != ev.key
        val e = if (ev.paused && ev.elapsedMs <= 0L && prev != null && !first) {
            ev.copy(elapsedMs = if (prev.paused) prev.elapsedMs else (now() - prev.startedAt).coerceAtLeast(0L))
        } else ev
        sysWatch = e
        if (first && !islandRunning && !done && sysTimer == null) {
            if (!host.expanded || isClockView(host.content)) showCompact() else host.update { it }
        } else refresh()
    }

    fun onSysAlarm(e: IslandEvent.ClockAlarm) {
        // Zamanlayıcı durdurulunca / iptal edilince saat uygulamasının güncellediği bildirim, ertele düğmesi olmadan
        // "alarm" gibi görünebilir: zamanlayıcıya az önce dokunulduysa ve ertele yoksa gerçek alarm sayılmaz
        if (!e.timerDone && e.snooze == null && now() - lastTimerActivityAt < 6_000L) return
        val same = sysAlarm?.key == e.key
        sysAlarm = e
        fallbackAutoJob?.cancel(); fallbackAlarm = null
        if (e.timerDone && sysTimer?.key == e.key) sysTimer = null
        if (!same) showRinging() else refresh()
    }

    fun onSysGone(key: String) {
        // Duraklat / devam basıldıktan hemen sonra saat uygulaması bildirimi silip yeniden kurabilir: kısa süre beklenir
        if (now() - lastToggleAt < 2_500L && (sysTimer?.key == key || sysWatch?.key == key)) {
            goneJob?.cancel()
            goneJob = host.scope.launch { delay(2_500L); removeSys(key) }
            return
        }
        removeSys(key)
    }

    private fun removeSys(key: String) {
        var changed = false
        if (sysAlarm?.key == key) { sysAlarm = null; changed = true }
        if (sysTimer?.key == key) { noteTimer(sysTimer?.endsAt ?: 0L); sysTimer = null; changed = true }
        if (sysWatch?.key == key) { sysWatch = null; changed = true }
        if (changed) finishView()
    }

    // ================================================================ Alarm kuruldu / alarm saati geldi

    private fun hhmm(ms: Long): String = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ms))

    private fun onNextAlarmChanged() {
        val am = host.context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val info = runCatching { am.nextAlarmClock }.getOrNull()
        val trig = info?.triggerTime ?: 0L
        val prev = nextTrigger
        nextTrigger = trig
        scheduleFallback(am)
        if (!host.settings.more.clockIsland) return
        // Zamanlayıcının kendi "alarm" kaydı (başlat / duraklat / iptal): gerçek alarm değil, gösterilmez
        if (trig > 0L && timerLike(trig)) return
        // Yeni bir alarm kuruldu (çalan alarm kapatıldıktan hemen sonraki "sonraki alarm" değişimi sayılmaz)
        if (trig > now() + 30_000L && trig != prev && now() - lastRingAt > 120_000L && !host.blocked && !ringing) {
            val left = trig - now()
            val h = left / 3_600_000L
            val m = (left % 3_600_000L) / 60_000L
            val rel = if (h > 0) "$h sa $m dk sonra" else "$m dk sonra"
            host.showTemporarily(
                IslandContent.Status(StatusKind.Alarm, title = "Alarm", value = hhmm(trig), subtitle = rel, phase = 1),
                2_600L,
            )
        }
    }

    /** Alarm saati gelince bildirim okunamamışsa da ada alarmı gösterir (saat uygulamasının açma hedefiyle). */
    private fun scheduleFallback(am: AlarmManager?) {
        fallbackJob?.cancel()
        val info = runCatching { am?.nextAlarmClock }.getOrNull() ?: return
        val trig = info.triggerTime
        val wait = trig - now()
        if (wait <= 0L) return
        fallbackJob = host.scope.launch {
            delay(wait + 4_000L)
            if (!host.settings.more.clockIsland) return@launch
            if (sysAlarm != null || done || now() - lastRingAt < 30_000L) return@launch
            if (timerLike(trig)) return@launch          // zamanlayıcının bitişi: saat uygulaması kendisi gösterir
            fallbackAlarm = hhmm(trig) to info.showIntent
            showRinging()
            fallbackAutoJob?.cancel()
            fallbackAutoJob = host.scope.launch {
                delay(60_000L)
                if (fallbackAlarm != null) { fallbackAlarm = null; finishView() }
            }
        }
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
            vibrator()?.vibrate(VibrationEffect.createWaveform(pattern, 0))
        }
    }

    private fun vibrator(): Vibrator? {
        val ctx = host.context
        return if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }

    private fun stopRing() {
        runCatching { ring?.stop() }
        ring = null
        runCatching { vibrator()?.cancel() }
    }
}
