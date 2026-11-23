package com.example.dynamicisland

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil

/*
 * ŞARJ SÜRESİ TAHMİNİ ve TERS ÇEVİRİNCE SESSİZ (Ana Sayfa > Birleşik efektler).
 */

// ================================================================ Şarj süresi tahmini
/**
 * Şarjdayken tam dolmaya kalan süreyi tahmin eder.
 * Önce sistemin kendi tahmini (Android 9+, şarj hızı ve uyarlanabilir şarjı hesaba katar) kullanılır; sistem bilmiyorsa
 * son ~20 dakikadaki yüzde artışından hız ölçülür. %80'den sonra şarj yavaşladığı için kalan kısım daha yavaş sayılır.
 *
 * Ada şu anlarda kısa bir şarj hapı gösterir: tahmin ilk kez hesaplanınca, şarjdayken kilit açılınca ve tam dolunca.
 */
class ChargeTimeEstimator(
    private val ctx: Context,
    private val settings: StateFlow<IslandSettings>,
    private val show: (IslandContent.Status) -> Unit,
) {
    private val samples = ArrayDeque<Pair<Long, Float>>()     // (geçen süre ms, yüzde)
    private var plugged = false
    private var pct = -1f
    private var announced = false
    private var fullShown = false
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            runCatching {
                when (i?.action) {
                    Intent.ACTION_BATTERY_CHANGED -> onBattery(i)
                    Intent.ACTION_USER_PRESENT -> onUnlock()
                }
            }
        }
    }

    fun start() {
        if (registered) return
        val f = IntentFilter().apply { addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_USER_PRESENT) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(receiver, f)
            registered = true
        }
    }

    fun stop() {
        if (!registered) return
        runCatching { ctx.unregisterReceiver(receiver) }
        registered = false
    }

    private fun on() = settings.value.more.chargeEta

    private fun onBattery(i: Intent) {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (lvl < 0) return
        val p = lvl * 100f / sc
        val nowPlugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (!nowPlugged) {
            plugged = false; samples.clear(); announced = false; fullShown = false; pct = p
            return
        }
        if (!plugged) { plugged = true; samples.clear(); announced = false; fullShown = false }
        pct = p
        val now = SystemClock.elapsedRealtime()
        if (samples.isEmpty() || samples.last().second != p) samples.addLast(now to p)
        while (samples.size > 2 && now - samples.first().first > 20 * 60_000L) samples.removeFirst()
        if (!on()) return
        if (p >= 99.5f) {
            if (!fullShown) { fullShown = true; show(pill("Tam doldu", 100)) }
            return
        }
        if (!announced) {
            val left = remainingMs() ?: return
            announced = true
            show(pill(format(left), p.toInt()))
        }
    }

    /** Şarjdayken kilit açılınca kalan süre kısa süre görünür (tahmin varsa). */
    private fun onUnlock() {
        if (!on() || !plugged || pct < 0f || pct >= 99.5f) return
        val left = remainingMs() ?: return
        show(pill(format(left), pct.toInt()))
    }

    /** Şarj halkası için: kalan süre yazısı ("38 dk kaldı") ya da bilinmiyorsa null. */
    fun currentText(): String? {
        if (!on() || !plugged || pct >= 99.5f) return null
        return remainingMs()?.let { format(it) }
    }

    private fun remainingMs(): Long? {
        if (Build.VERSION.SDK_INT >= 28) {
            val sys = runCatching { ctx.getSystemService(BatteryManager::class.java)?.computeChargeTimeRemaining() ?: -1L }.getOrDefault(-1L)
            if (sys > 0L) return sys
        }
        if (samples.size < 3) return null
        val a = samples.first(); val b = samples.last()
        val dt = b.first - a.first
        val dp = b.second - a.second
        if (dt < 90_000L || dp <= 0f) return null
        val rate = dp / dt                                     // yüzde / ms
        val p = b.second
        val ms = if (p < 80f) (80f - p) / rate + 20f / (rate * 0.45f) else (100f - p) / rate
        return ms.toLong().coerceIn(60_000L, 12 * 3_600_000L)
    }

    private fun format(ms: Long): String {
        val min = ceil(ms / 60_000.0).toInt().coerceAtLeast(1)
        return if (min < 60) "$min dk kaldı" else "${min / 60} sa ${min % 60} dk"
    }

    private fun pill(title: String, p: Int) = IslandContent.Status(
        StatusKind.Charging, title = title, value = "%$p", progress = (p / 100f).coerceIn(0f, 1f),
    )
}

// ================================================================ Ters çevirince sessiz
/**
 * Telefon ekranı aşağı bakacak şekilde düz bir yere konunca Rahatsız Etmeyin açılır (alarmlar ve öncelikli kişiler
 * ayarlarına göre geçer); kaldırınca eski hale döner. Rahatsız Etmeyin zaten açıksa ya da kullanıcı arada elle
 * değiştirdiyse dokunulmaz.
 *
 * Değişiklik bildirim dinleyicisi üzerinden yapılır (ek izin gerekmez). İvme ölçer düşük hızda dinlenir; ekran
 * kapandıktan sonra telefonun hemen uyumaması için kısa (8 sn) bir uyanık kalma kilidi alınır, böylece ekranı kapatıp
 * ters koymak da algılanır. Ekran açılınca telefon ters değilse eski hale dönülür.
 */
class FlipSilence(
    private val ctx: Context,
    private val settings: StateFlow<IslandSettings>,
    private val scope: CoroutineScope,
) {
    private var sm: SensorManager? = null
    private var listening = false
    private var engaged = false              // sessizi biz açtık
    private var appliedFilter = 0
    private var downSince = 0L
    private var upSince = 0L
    private var faceDown = false
    private var receiverOn = false
    private var wake: PowerManager.WakeLock? = null

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            val now = SystemClock.elapsedRealtime()
            val down = z < -8.6f && abs(x) < 2.5f && abs(y) < 2.5f
            faceDown = down
            if (down) {
                upSince = 0L
                if (downSince == 0L) downSince = now
                if (!engaged && now - downSince > 1_200L) engage()
            } else {
                downSince = 0L
                if (z > -5f) {
                    if (upSince == 0L) upSince = now
                    if (engaged && now - upSince > 400L) release()
                }
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> holdAwakeBriefly()
                Intent.ACTION_SCREEN_ON, Intent.ACTION_USER_PRESENT -> if (engaged && !faceDown) release()
            }
        }
    }

    fun start() {
        scope.launch {
            settings.map { it.more.flipSilence }.distinctUntilChanged().collect { on -> if (on) listen() else unlisten() }
        }
    }

    fun stop() {
        unlisten()
    }

    private fun listen() {
        if (listening) return
        val m = ctx.getSystemService(SensorManager::class.java) ?: return
        val s = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm = m
        listening = m.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
        if (!receiverOn) {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(screenReceiver, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(screenReceiver, f)
                receiverOn = true
            }
        }
    }

    private fun unlisten() {
        if (engaged) release()
        if (listening) runCatching { sm?.unregisterListener(listener) }
        listening = false
        if (receiverOn) runCatching { ctx.unregisterReceiver(screenReceiver) }
        receiverOn = false
        runCatching { wake?.let { if (it.isHeld) it.release() } }
        downSince = 0L; upSince = 0L
    }

    private fun holdAwakeBriefly() {
        runCatching {
            val pm = ctx.getSystemService(PowerManager::class.java) ?: return
            val w = wake ?: pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "omniisland:flip").also {
                it.setReferenceCounted(false); wake = it
            }
            w.acquire(8_000L)
        }
    }

    private fun engage() {
        val cur = IslandNotificationListener.currentZen(ctx)
        if (cur != NotificationManager.INTERRUPTION_FILTER_ALL) return      // zaten sessiz: dokunma
        buzz()
        if (IslandNotificationListener.requestZen(NotificationManager.INTERRUPTION_FILTER_PRIORITY, ctx)) {
            engaged = true
            appliedFilter = NotificationManager.INTERRUPTION_FILTER_PRIORITY
        }
    }

    private fun release() {
        engaged = false
        val cur = IslandNotificationListener.currentZen(ctx)
        // Kullanıcı arada elle değiştirdiyse onun seçimine dokunulmaz
        if (cur == appliedFilter) IslandNotificationListener.requestZen(NotificationManager.INTERRUPTION_FILTER_ALL, ctx)
        appliedFilter = 0
    }

    /** Sessiz açılırken kısa çift titreşim: telefona bakmadan anlaşılsın. */
    private fun buzz() {
        runCatching {
            val v = ctx.getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK))
        }
    }
}
