package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * GÜVENLİK VE PİL UYARILARI
 *  - Yeni uygulama yüklendi uyarısı ([AppInstallWatch])
 *  - Pil %100 / telefon çok sıcak uyarıları ([BatteryCare])
 *  - Okunmamış bildirim sayacı ([UnreadBadge])
 * Hiçbiri kayıt dosyası yazmaz, internete veri göndermez ve ek izin istemez.
 */

internal val APP_BLUE = Color(0xFF0A84FF)
internal val HOT_ORANGE = Color(0xFFFF6B35)

/** Kısa titreşim deseni (ms: bekle, titre, bekle, titre...). */
internal fun buzzPattern(ctx: Context, p: LongArray) {
    runCatching {
        @Suppress("DEPRECATION")
        val v: Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31)
            ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else ctx.getSystemService(Vibrator::class.java)
        v?.vibrate(VibrationEffect.createWaveform(p, -1))
    }
}

// ================================================================ Simgeler (24x24 kutu)
/** Yeni uygulama: köşeleri yuvarlak kutu içinde artı. */
internal fun DrawScope.drawNewAppGlyph(color: Color) {
    drawRoundRect(color, Offset(3f, 3f), Size(18f, 18f), CornerRadius(5f, 5f), style = Stroke(2.2f))
    drawLine(color, Offset(12f, 7.5f), Offset(12f, 16.5f), 2.4f, StrokeCap.Round)
    drawLine(color, Offset(7.5f, 12f), Offset(16.5f, 12f), 2.4f, StrokeCap.Round)
}

/** Termometre: uzun gövde, altta yuvarlak hazne. */
internal fun DrawScope.drawThermoGlyph(color: Color) {
    drawRoundRect(color, Offset(9.6f, 2.5f), Size(4.8f, 12.5f), CornerRadius(2.4f, 2.4f), style = Stroke(2.2f))
    drawCircle(color, radius = 4.4f, center = Offset(12f, 17.6f))
    drawLine(color, Offset(12f, 8f), Offset(12f, 16f), 2.2f, StrokeCap.Round)
}

// ================================================================ Yeni uygulama yüklendi
/**
 * Yeni bir uygulama kurulunca [onNew] çağrılır (güncellemeler sayılmaz). Sistem yayınıdır, izin gerektirmez.
 * Not: bir uygulamanın sonradan yetki (izin) istemesini Android uygulamalara bildirmez, o yüzden yalnızca kurulum algılanır.
 */
class AppInstallWatch(private val ctx: Context, private val onNew: (pkg: String, label: String) -> Unit) {
    private var rx: BroadcastReceiver? = null

    fun start() {
        if (rx != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                runCatching {
                    if (i?.action != Intent.ACTION_PACKAGE_ADDED) return
                    if (i.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
                    val pkg = i.data?.schemeSpecificPart ?: return
                    if (pkg == ctx.packageName) return
                    val pm = ctx.packageManager
                    val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }
                        .getOrDefault(pkg.substringAfterLast('.'))
                    onNew(pkg, label)
                }
            }
        }
        rx = r
        runCatching {
            val f = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
            ContextCompat.registerReceiver(ctx, r, f, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    fun stop() {
        rx?.let { runCatching { ctx.unregisterReceiver(it) } }
        rx = null
    }
}

// ================================================================ Pil ömrü uyarıları
/**
 * İki bağımsız uyarı (ayarlardan ayrı ayrı açılır):
 *  - Şarjdayken pil %100'e ulaşınca "Pil doldu · Şarjı çıkar" (her şarjda bir kez).
 *  - Pil sıcaklığı eşiği geçince "Çok sıcak" (eşiğin 3 °C altına inene kadar tekrarlanmaz).
 * Pil sıcaklığı telefonun iç sıcaklığına yakın bir ölçümdür; ekran / işlemci sıcaklığını ayrıca vermez.
 */
class BatteryCare(
    private val ctx: Context,
    private val settings: () -> IslandSettings,
    private val show: (IslandContent.Status, Long) -> Unit,
) {
    private var rx: BroadcastReceiver? = null
    private var startedAt = 0L
    private var fullDone = false
    private var hotArmed = true

    fun start() {
        if (rx != null) return
        startedAt = SystemClock.elapsedRealtime()
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { if (i != null) runCatching { onBattery(i) } }
        }
        rx = r
        runCatching { ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    fun stop() {
        rx?.let { runCatching { ctx.unregisterReceiver(it) } }
        rx = null
    }

    private fun onBattery(i: Intent) {
        val m = settings().more
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        if (lvl < 0) return
        val pct = (lvl * 100f / sc).roundToInt()
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f
        val thr = m.battHotC.coerceIn(38, 50)
        val full = plugged && pct >= 100
        val hot = tempC >= thr
        if (!plugged) fullDone = false
        if (!hot && tempC <= thr - 3f) hotArmed = true
        // Servis başlarken var olan durum için uyarı verilmez (yalnızca kaydedilir)
        if (SystemClock.elapsedRealtime() - startedAt < 2_500L) {
            if (full) fullDone = true
            if (hot) hotArmed = false
            return
        }
        if (m.battFullAlert && full && !fullDone) {
            fullDone = true
            buzzPattern(ctx, longArrayOf(0, 60, 110, 60))
            show(IslandContent.Status(StatusKind.Network, title = "Pil doldu", value = "Şarjı çıkar", extra = 8), 5_000L)
        }
        if (m.battHotAlert && hot && hotArmed) {
            hotArmed = false
            buzzPattern(ctx, longArrayOf(0, 200, 100, 200))
            show(IslandContent.Status(StatusKind.Network, title = "Çok sıcak", value = "${tempC.roundToInt()}°C", extra = 9), 6_000L)
        }
    }
}

/** Şarj hızı (watt): sistemin anlık akım ölçümü × pil gerilimi. Ölçülemezse null. */
internal fun chargeWatts(ctx: Context, voltageMv: Int): String? {
    val bm = ctx.getSystemService(BatteryManager::class.java) ?: return null
    var cur = abs(runCatching { bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0L))
    if (cur == 0L) return null
    if (cur < 20_000L) cur *= 1000L                 // bazı cihazlar mA verir, µA'ya çevrilir
    val mv = if (voltageMv in 1..100) voltageMv * 1000 else voltageMv
    if (mv <= 0) return null
    val w = cur / 1_000_000.0 * mv / 1000.0
    if (w < 1.0 || w > 240.0) return null
    return if (w < 10.0) "%.1f W".format(java.util.Locale.US, w) else "${w.roundToInt()} W"
}

// ================================================================ Kamera / mikrofon geçmişi
// ================================================================ Okunmamış bildirim sayacı
/** Boştaki adanın köşesindeki rozet için bekleyen (silinmemiş) bildirim sayısı. Dinleyici hesaplar. */
object UnreadBadge {
    val count = MutableStateFlow(0)
    @Volatile var enabled = false
}
