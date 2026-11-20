package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import androidx.core.content.ContextCompat

/**
 * Zil modu göstergesi: telefon sessize / titreşime / sesliye alınınca ada kısa süre hap şeklinde açılır
 * (Zil / Sessiz / Titreşim). İzin gerektirmez; sistemin RINGER_MODE_CHANGED yayınını dinler.
 */
class RingerController(private val host: IslandHost) {

    private var receiver: BroadcastReceiver? = null
    private var lastMode = -1

    fun start() {
        if (receiver != null) return
        val ctx = host.context
        // Servis başlarken mevcut mod hatırlanır: açılışta gösterge çıkmaz, yalnızca değişince çıkar
        lastMode = runCatching { ctx.getSystemService(AudioManager::class.java).ringerMode }.getOrDefault(-1)
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != AudioManager.RINGER_MODE_CHANGED_ACTION) return
                val mode = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                runCatching { onMode(mode) }
            }
        }
        receiver = r
        runCatching {
            ContextCompat.registerReceiver(
                ctx, r, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }

    fun stop() {
        receiver?.let { r -> runCatching { host.context.unregisterReceiver(r) } }
        receiver = null
    }

    private fun onMode(mode: Int) {
        if (mode < 0 || mode == lastMode) return
        lastMode = mode
        if (!host.settings.ringerIndicator) return
        if (host.busy || host.blocked) return      // asistan / arama sürerken gösterme
        val c = when (mode) {
            AudioManager.RINGER_MODE_SILENT -> IslandContent.Status(StatusKind.Ringer, title = "Sessiz", silent = true)
            AudioManager.RINGER_MODE_VIBRATE -> IslandContent.Status(StatusKind.Ringer, title = "Titreşim", vibrate = true)
            else -> IslandContent.Status(StatusKind.Ringer, title = "Zil")
        }
        host.showTemporarily(c, 2_000L)
    }
}
