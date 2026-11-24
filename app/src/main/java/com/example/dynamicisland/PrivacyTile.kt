package com.example.dynamicisland

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Hızlı ayarlar kutucuğu: "Gizli bildirim". Dokununca gizli bildirim modu açılır / kapanır (ayar kaydedilir;
 * ada servisi değişikliği hemen görür). Kutucuk açıkken vurgulu görünür.
 */
class PrivacyTile : TileService() {
    private var scope: CoroutineScope? = null
    private val repo by lazy { SettingsRepository(applicationContext) }

    override fun onStartListening() {
        super.onStartListening()
        scope?.cancel()
        val sc = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sc
        sc.launch { repo.flow.collect { render(it.more.notifPrivate) } }
    }

    override fun onStopListening() {
        scope?.cancel(); scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        CoroutineScope(Dispatchers.Main).launch {
            runCatching {
                repo.update { s -> s.copy(more = s.more.copy(notifPrivate = !s.more.notifPrivate)) }
                render(repo.flow.first().more.notifPrivate)
            }
        }
    }

    private fun render(on: Boolean) {
        val t = qsTile ?: return
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Gizli bildirim"
        if (android.os.Build.VERSION.SDK_INT >= 29) t.subtitle = if (on) "Açık" else "Kapalı"
        t.updateTile()
    }
}
