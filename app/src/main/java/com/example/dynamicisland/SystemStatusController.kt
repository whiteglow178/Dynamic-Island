package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.NotificationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * iOS tarzı sistem göstergeleri (izin istemez):
 *  - Düşük pil: şarjda değilken pil %20 ve %10'a inince ada uyarır (ince hap ya da büyük kart, ayara göre).
 *  - İnternet: bağlanınca (Wi-Fi / mobil veri) ve bağlantı kesilince geniş hap.
 *  - Uçak modu: açılınca / kapanınca geniş hap.
 * Zil sesi seviyesi göstergesi VolumeController'dadır.
 */
private const val HOTSPOT_ACTION = "android.net.wifi.WIFI_AP_STATE_CHANGED"

class SystemStatusController(private val host: IslandHost) {

    private var receiver: BroadcastReceiver? = null
    private var netCb: ConnectivityManager.NetworkCallback? = null
    private var startedAt = 0L

    // ---- pil
    private var lastPct = -1
    private var plugged = false

    // ---- ağ
    private var curNet: Network? = null
    private var curTransport = -1          // 0 Wi-Fi, 1 mobil, 2 diğer
    private var shownNet: Network? = null  // en son "bağlandı" gösterilen ağ (aynı ağ için tekrar gösterme)
    private var netJob: Job? = null
    private var airplaneAt = 0L

    fun start() {
        if (receiver != null) return
        val ctx = host.context
        startedAt = SystemClock.elapsedRealtime()
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                runCatching {
                    when (intent.action) {
                        Intent.ACTION_BATTERY_CHANGED -> onBattery(intent)
                        PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> onPowerSave()
                        Intent.ACTION_AIRPLANE_MODE_CHANGED -> onAirplane(intent.getBooleanExtra("state", false))
                        NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED -> onZen()
                        HOTSPOT_ACTION -> if (!isInitialStickyBroadcast) onHotspot(intent.getIntExtra("wifi_state", -1))
                    }
                }
            }
        }
        receiver = r
        runCatching {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
                addAction(HOTSPOT_ACTION)
            }
            ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        runCatching { startNetwork(ctx) }
        zenOn = runCatching { zenActive() }.getOrNull()
    }

    fun stop() {
        receiver?.let { r -> runCatching { host.context.unregisterReceiver(r) } }
        receiver = null
        netCb?.let { cb -> runCatching { host.context.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
        netCb = null
        netJob?.cancel()
    }

    private fun canShow(): Boolean = !host.busy && !host.blocked

    // ================================================================ Düşük pil
    private fun onBattery(i: Intent) {
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (lvl < 0) return
        val pct = (lvl * 100f / sc).roundToInt()
        plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val prev = lastPct
        lastPct = pct
        if (prev < 0 || plugged) return                // ilk okuma ya da şarjda: uyarı yok
        val crossed = (prev > 20 && pct <= 20) || (prev > 10 && pct <= 10)
        if (crossed) showLowBattery(pct)
    }

    private fun onPowerSave() {
        // Düşük pil uyarısı açıkken / pil azken düşük güç modu açılırsa ada sarı "açık" görünümüyle haber verir
        if (lastPct in 0..20 && !plugged && powerSaveOn()) showLowBattery(lastPct)
    }

    private fun powerSaveOn(): Boolean =
        runCatching { host.context.getSystemService(PowerManager::class.java).isPowerSaveMode }.getOrDefault(false)

    private fun showLowBattery(pct: Int) {
        val s = host.settings
        if (!s.more.lowBattIndicator || !canShow()) return
        val saver = powerSaveOn()
        val c = if (s.more.lowBattExpand) IslandContent.Status(
            StatusKind.LowBatteryCard, title = "%$pct Pil",
            subtitle = if (saver) "Düşük Güç Modu açık" else "Düşük Güç Modu için dokun",
            progress = pct / 100f, extra = if (saver) 1 else 0,
        ) else IslandContent.Status(
            StatusKind.LowBattery, title = "Düşük Pil", value = "%$pct",
            progress = pct / 100f, extra = if (saver) 1 else 0,
        )
        host.showTemporarily(c, if (s.more.lowBattExpand) 6_000L else 4_000L)
    }

    /**
     * Düşük pil görünümüne dokunulunca: düşük güç modu açılmaya çalışılır (WRITE_SECURE_SETTINGS adb ile verildiyse çalışır);
     * olmazsa sistemin pil tasarrufu ayarı açılır. true dönerse mod açıldı (ada sarı görünüme geçer).
     */
    fun onLowBatteryTap(c: IslandContent.Status): Boolean {
        val ctx = host.context
        if (c.extra == 1) return false
        val ok = runCatching { Settings.Global.putInt(ctx.contentResolver, "low_power", 1) }.getOrDefault(false) &&
            runCatching { SystemClock.sleep(120); powerSaveOn() }.getOrDefault(false)
        if (ok) {
            val pct = if (lastPct >= 0) lastPct else (c.progress * 100f).roundToInt()
            val n = if (c.kind == StatusKind.LowBatteryCard) c.copy(subtitle = "Düşük Güç Modu açık", extra = 1, title = "%$pct Pil")
            else c.copy(extra = 1)
            host.showTemporarily(n, 2_500L)
            return true
        }
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        return false
    }

    // ================================================================ Rahatsız Etmeyin / erişim noktası
    private var zenOn: Boolean? = null
    private var apOn: Boolean? = null

    private fun zenActive(): Boolean {
        val f = host.context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter
            ?: NotificationManager.INTERRUPTION_FILTER_ALL
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    /** Adadan açılıp kapatıldı: yayın gelince aynı durum için hap yeniden gösterilmesin. */
    fun markFocus(on: Boolean) { zenOn = on }

    private fun onZen() {
        val on = zenActive()
        if (zenOn == on) return
        zenOn = on
        if (!host.settings.more.focusIndicator || !canShow() || warmingUp()) return
        host.showTemporarily(
            IslandContent.Status(StatusKind.Focus, title = "Rahatsız Etmeyin", value = if (on) "Açık" else "Kapalı", extra = if (on) 1 else 0),
            2_200L,
        )
    }

    /** 13 = açık, 11 = kapalı (WifiManager.WIFI_AP_STATE_*). Ara durumlar (açılıyor / kapanıyor) yok sayılır. */
    private fun onHotspot(state: Int) {
        val on = when (state) { 13 -> true; 11 -> false; else -> return }
        if (apOn == on) return
        apOn = on
        if (!host.settings.more.hotspotIndicator || !canShow() || warmingUp()) return
        host.showTemporarily(
            IslandContent.Status(StatusKind.Hotspot, title = "Erişim Noktası", value = if (on) "Açık" else "Kapalı", extra = if (on) 1 else 0),
            2_200L,
        )
    }

    // ================================================================ Uçak modu
    private fun onAirplane(on: Boolean) {
        airplaneAt = SystemClock.elapsedRealtime()
        netJob?.cancel()                                // uçak moduyla gelen bağlantı kesildi / bağlandı göstergeleri bastırılır
        val s = host.settings
        if (!s.more.airplaneIndicator || !canShow()) return
        host.showTemporarily(
            IslandContent.Status(StatusKind.Airplane, title = "Uçak Modu", value = if (on) "Açık" else "Kapalı", extra = if (on) 1 else 0),
            2_200L,
        )
    }

    // ================================================================ İnternet
    private fun startNetwork(ctx: Context) {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                // VPN ağları ayrı izlenir (VpnWatch): VPN açılınca "İnternet bağlandı" hapı çıkmasın
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return
                val t = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
                    else -> 2
                }
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                host.scope.launch { onNetwork(network, t, usable) }
            }

            override fun onLost(network: Network) {
                host.scope.launch { onNetLost(network) }
            }
        }
        netCb = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun warmingUp(): Boolean = SystemClock.elapsedRealtime() - startedAt < 2_500L
    private fun nearAirplane(): Boolean = SystemClock.elapsedRealtime() - airplaneAt < 5_000L

    private fun onNetwork(network: Network, transport: Int, usable: Boolean) {
        if (!usable) return
        val changed = network != curNet || transport != curTransport
        curNet = network; curTransport = transport
        if (warmingUp()) { shownNet = network; return }   // servis başlarken mevcut bağlantı gösterilmez
        if (!changed && network == shownNet) return
        netJob?.cancel()
        // Kısa bekleme: Wi-Fi -> mobil geçişinde "kesildi" ve "bağlandı" art arda yanıp sönmesin
        netJob = host.scope.launch {
            delay(700)
            if (curNet != network || shownNet == network) return@launch
            shownNet = network
            val s = host.settings
            if (!s.more.netIndicator || nearAirplane() || !canShow()) return@launch
            val title = when (transport) { 0 -> "Wi-Fi"; 1 -> "Mobil Veri"; else -> "İnternet" }
            host.showTemporarily(IslandContent.Status(StatusKind.Network, title = title, value = "Bağlandı", extra = transport), 2_200L)
        }
    }

    private fun onNetLost(network: Network) {
        if (network != curNet) return
        val lostTransport = curTransport
        curNet = null
        if (warmingUp()) return
        netJob?.cancel()
        netJob = host.scope.launch {
            delay(1_600)                                // yeni ağ gelirse (geçiş) "kesildi" gösterilmez
            if (curNet != null) return@launch
            shownNet = null
            val s = host.settings
            if (!s.more.netIndicator || nearAirplane() || !canShow()) return@launch
            val title = when (lostTransport) { 0 -> "Wi-Fi"; 1 -> "Mobil Veri"; else -> "İnternet" }
            host.showTemporarily(IslandContent.Status(StatusKind.Network, title = title, value = "Bağlantı kesildi", extra = 3), 2_600L)
        }
    }
}
