package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Medya sesi göstergesi: medya sesi artınca / azalınca ada kısa süre hap şeklinde açılır (hoparlör simgesi + seviye çubuğu + yüzde).
 * İzin gerektirmez; sistemin VOLUME_CHANGED_ACTION yayınını dinler. Yalnızca medya (müzik) akışı gösterilir.
 */
class VolumeController(private val host: IslandHost) {

    private var receiver: BroadcastReceiver? = null

    fun start() {
        if (receiver != null) return
        val ctx = host.context
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION) return
                when (intent.getIntExtra(EXTRA_TYPE, -1)) {
                    AudioManager.STREAM_MUSIC -> runCatching { onVolume() }
                    AudioManager.STREAM_RING -> runCatching {
                        onRing(intent.getIntExtra(EXTRA_VALUE, -1), intent.getIntExtra(EXTRA_PREV, -1))
                    }
                }
            }
        }
        receiver = r
        runCatching {
            ContextCompat.registerReceiver(ctx, r, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        }
    }

    fun stop() {
        receiver?.let { r -> runCatching { host.context.unregisterReceiver(r) } }
        receiver = null
    }

    private fun onVolume() {
        if (!host.settings.volumeIndicator) return
        if (host.busy || host.blocked) return      // asistan / arama sürerken gösterme
        val am = host.context.getSystemService(AudioManager::class.java) ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC).coerceIn(0, max)
        val frac = v.toFloat() / max
        host.showTemporarily(
            IslandContent.Status(
                StatusKind.Volume, title = "Ses", value = "%${(frac * 100).roundToInt()}",
                progress = frac, silent = v == 0,
            ),
            1_600L,
        )
    }

    /**
     * Zil sesi seviyesi (iOS 26 ekran kaydındaki gibi): ada geniş hap olur; solda zil + "Zil", sağda ince seviye çubuğu.
     * Yalnızca telefon sesli moddayken ve seviye gerçekten değiştiğinde gösterilir (sessize / titreşime geçişi zil modu göstergesi anlatır).
     */
    private fun onRing(value: Int, prev: Int) {
        val s = host.settings
        if (!s.more.ringVolIndicator) return
        if (host.busy || host.blocked) return
        if (value >= 0 && prev >= 0 && value == prev) return
        val am = host.context.getSystemService(AudioManager::class.java) ?: return
        if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING).coerceAtLeast(1)
        val v = (if (value >= 0) value else am.getStreamVolume(AudioManager.STREAM_RING)).coerceIn(0, max)
        val frac = v.toFloat() / max
        host.showTemporarily(
            IslandContent.Status(StatusKind.RingVolume, title = "Zil", value = "%${(frac * 100).roundToInt()}", progress = frac),
            1_600L,
        )
    }

    private companion object {
        const val ACTION = "android.media.VOLUME_CHANGED_ACTION"
        const val EXTRA_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE"
        const val EXTRA_VALUE = "android.media.EXTRA_VOLUME_STREAM_VALUE"
        const val EXTRA_PREV = "android.media.EXTRA_PREV_VOLUME_STREAM_VALUE"
    }
}
