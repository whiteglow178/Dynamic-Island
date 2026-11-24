package com.example.dynamicisland

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TrafficStats
import android.os.SystemClock
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

// ================================================================ VPN göstergesi
/**
 * VPN açık mı ([on]). Ağ değişimlerinden izlenir (izin gerektirmez; ACCESS_NETWORK_STATE zaten var).
 * Açılınca / kapanınca [onChange] çağrılır: [unexpected] = kullanıcı kapatmadan koptu sayılır (servis kısa bir
 * süre içinde yeniden bağlanmazsa uyarır).
 */
class VpnWatch(private val ctx: Context, private val onChange: (on: Boolean) -> Unit) {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private val nets = HashSet<Network>()
    private var cb: ConnectivityManager.NetworkCallback? = null

    fun start() {
        if (cb != null || cm == null) return
        val c = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val was = nets.isNotEmpty()
                nets.add(network)
                if (!was) { on.value = true; onChange(true) }
            }
            override fun onLost(network: Network) {
                nets.remove(network)
                if (nets.isEmpty() && on.value) { on.value = false; onChange(false) }
            }
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        runCatching { cm.registerNetworkCallback(req, c, android.os.Handler(android.os.Looper.getMainLooper())); cb = c }
    }

    fun stop() {
        cb?.let { c -> runCatching { cm?.unregisterNetworkCallback(c) } }
        cb = null
        nets.clear()
        on.value = false
    }

    companion object {
        /** VPN şu an açık mı (adadaki kalkan için). */
        val on = MutableStateFlow(false)
    }
}

/** Kalkan simgesi (24x24 kutu). [broken] = üstü çizili (VPN koptu). */
internal fun DrawScope.drawShieldGlyph(color: Color, broken: Boolean = false) {
    val p = Path().apply {
        moveTo(12f, 2.5f)
        lineTo(20f, 5.5f)
        lineTo(20f, 11.5f)
        cubicTo(20f, 16.5f, 16.5f, 20f, 12f, 21.8f)
        cubicTo(7.5f, 20f, 4f, 16.5f, 4f, 11.5f)
        lineTo(4f, 5.5f)
        close()
    }
    if (broken) {
        drawPath(p, color, style = Stroke(width = 2f))
        drawLine(color, Offset(4f, 4f), Offset(20.5f, 20.5f), strokeWidth = 2.2f, cap = StrokeCap.Round)
    } else {
        drawPath(p, color)
        // içte tik
        val tick = Path().apply { moveTo(8.4f, 12f); lineTo(11f, 14.6f); lineTo(15.8f, 9.4f) }
        drawPath(tick, Color.Black.copy(alpha = 0.75f), style = Stroke(width = 2f, cap = StrokeCap.Round))
    }
}

internal val VPN_GREEN = Color(0xFF34C759)
internal val VPN_RED = Color(0xFFFF453A)

// ================================================================ Ağ hızı göstergesi
/**
 * Anlık indirme / yükleme hızı (bayt/sn), saniyede bir ölçülür ([TrafficStats], izin gerektirmez).
 * Yalnızca ekran açıkken ve ayar açıkken ölçülür.
 */
object NetSpeed {
    /** (indirme, yükleme) bayt/sn. */
    val rate = MutableStateFlow(0L to 0L)
    private var job: Job? = null

    fun start(ctx: Context, scope: CoroutineScope) {
        if (job?.isActive == true) return
        val pm = ctx.getSystemService(android.os.PowerManager::class.java)
        job = scope.launch {
            var lastRx = TrafficStats.getTotalRxBytes()
            var lastTx = TrafficStats.getTotalTxBytes()
            var lastT = SystemClock.elapsedRealtime()
            while (isActive) {
                delay(1_000L)
                if (pm?.isInteractive == false) {
                    // Ekran kapalı: ölçme, pil harcama; açılınca sayaç yeniden başlar
                    rate.value = 0L to 0L
                    while (isActive && pm.isInteractive == false) delay(3_000L)
                    lastRx = TrafficStats.getTotalRxBytes(); lastTx = TrafficStats.getTotalTxBytes()
                    lastT = SystemClock.elapsedRealtime()
                    continue
                }
                val rx = TrafficStats.getTotalRxBytes()
                val tx = TrafficStats.getTotalTxBytes()
                val t = SystemClock.elapsedRealtime()
                val dt = (t - lastT).coerceAtLeast(1L)
                if (rx >= 0 && tx >= 0 && lastRx >= 0 && lastTx >= 0) {
                    rate.value = ((rx - lastRx).coerceAtLeast(0L) * 1000L / dt) to ((tx - lastTx).coerceAtLeast(0L) * 1000L / dt)
                }
                lastRx = rx; lastTx = tx; lastT = t
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        rate.value = 0L to 0L
    }

    /** Kısa biçim: "850K", "1.2M", "12M" (bayt/sn). */
    fun short(b: Long): String = when {
        b < 1_000L -> "${b}B"
        b < 1_000_000L -> "${b / 1_000L}K"
        b < 10_000_000L -> "%.1fM".format(java.util.Locale.US, b / 1_000_000f)
        else -> "${b / 1_000_000L}M"
    }

    /** "Yalnızca aktarım varken" modunda gösterim eşiği: 30 KB/sn. */
    const val ACTIVE_MIN = 30_000L
}

// ================================================================ Boştaki adada rozetler
/**
 * Boştaki kapalı adanın içinde: solda VPN kalkanı (VPN açıksa), iki yanda ağ hızı (solda ↓ indirme, sağda ↑ yükleme).
 * Ortadaki kamera deliği boş kalır. "Yalnızca aktarım varken" modunda hız, aktarım 30 KB/sn'yi geçince görünür ve
 * düştükten 3 sn sonra kaybolur.
 */
@androidx.compose.runtime.Composable
internal fun IdleNetBadges(cfg: IslandSettings, modifier: androidx.compose.ui.Modifier) {
    val vpnOn by VpnWatch.on.collectAsState()
    val rate by NetSpeed.rate.collectAsState()
    val mode = cfg.more.netSpeedMode
    // Son aktarım zamanı durum değil düz tutucu: yazmak yeniden çizim döngüsü başlatmasın
    val lastActive = remember { LongArray(1) }
    var tick by remember { mutableLongStateOf(0L) }
    val active = rate.first + rate.second >= NetSpeed.ACTIVE_MIN
    val now = SystemClock.elapsedRealtime()
    if (active) lastActive[0] = now
    // Aktarım bitince (hız değişmese de) 3 sn sonra bir kez daha çizilir ve gizlenir
    androidx.compose.runtime.LaunchedEffect(active) { if (!active) { delay(3_100L); tick = SystemClock.elapsedRealtime() } }
    tick.let { }
    val showSpeed = mode == 2 || (mode == 1 && (active || now - lastActive[0] < 3_000L))
    val showVpn = cfg.more.vpnIndicator && vpnOn
    if (!showSpeed && !showVpn) return
    val txt = Pal.c("island_text")
    androidx.compose.foundation.layout.Box(modifier.padding(horizontal = 9.dp)) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.CenterStart),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            if (showVpn) {
                androidx.compose.foundation.Canvas(androidx.compose.ui.Modifier.size(12.dp)) {
                    val sc = size.width / 24f
                    scale(sc, sc, Offset.Zero) { drawShieldGlyph(VPN_GREEN) }
                }
                if (showSpeed) androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(3.dp))
            }
            if (showSpeed) {
                androidx.compose.material3.Text(
                    "↓" + NetSpeed.short(rate.first), color = txt, fontSize = 9.5.sp, maxLines = 1, softWrap = false,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
        }
        if (showSpeed) {
            androidx.compose.material3.Text(
                "↑" + NetSpeed.short(rate.second), color = txt.copy(alpha = 0.75f), fontSize = 9.5.sp, maxLines = 1, softWrap = false,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                modifier = androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
            )
        }
    }
}
