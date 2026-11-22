package com.example.dynamicisland

import android.os.SystemClock
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/** Favori uygulamalar paneli: simge önbelleği, açma ve dokunma mantığı (eskiden serviste idi). */
class FavoritesController(private val host: IslandHost) {
    private val cache = ConcurrentHashMap<String, FavApp>()
    private var openedAt = 0L
    private val closeGuardMs = 450L      // açılır açılmaz gelen hızlı dokunuşlar paneli kapatmasın

    private fun favApp(pkg: String): FavApp? = cache[pkg] ?: runCatching {
        val pm = host.context.packageManager
        val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        val icon = pm.getApplicationIcon(pkg).toBitmap(112, 112).asImageBitmap()
        FavApp(pkg, label, icon)
    }.getOrNull()?.also { cache[pkg] = it }

    /** Favori uygulamaların simgelerini önceden yükler: ilk açılışta bekleme / takılma olmasın. */
    fun preload(s: IslandSettings) {
        if (!s.favoritesReady()) return
        val pkgs = s.favoritePkgList()
        cache.keys.retainAll(pkgs.toSet())
        val missing = pkgs.filter { !cache.containsKey(it) }
        if (missing.isEmpty()) return
        host.scope.launch(Dispatchers.Default) { missing.forEach { favApp(it) } }
    }

    /** Başparmak yelpazesi için: favori uygulamalar (önbellekte olmayanların simgesi bu çağrıda yüklenir). */
    fun appsNow(): List<FavApp> {
        val s = host.settings
        if (s.favoritePkgList().isEmpty()) return emptyList()
        return s.favoritePkgList().mapNotNull { favApp(it) }
    }

    fun open() {
        val s = host.settings
        if (!s.favoritesReady()) return
        val pkgs = s.favoritePkgList()
        val cached = pkgs.mapNotNull { cache[it] }
        if (cached.size == pkgs.size) {          // hepsi hazır: anında aç
            show(cached)
            return
        }
        host.scope.launch { show(withContext(Dispatchers.Default) { pkgs.mapNotNull { favApp(it) } }) }
    }

    private fun show(apps: List<FavApp>) {
        // Yüklenirken müzik/bildirim/asistan geldiyse favorileri açma
        if (apps.isEmpty() || host.content !is IslandContent.Idle) return
        openedAt = SystemClock.elapsedRealtime()
        host.showTemporarily(IslandContent.Favorites(apps), 7_000)
    }

    /** Açık favori paneline dokunma. */
    fun onTap() {
        val now = SystemClock.elapsedRealtime()
        if (!host.expanded) {
            // Panel kapanırken tekrar dokunuldu: kapanmasını bekletmeden yeniden aç
            host.cancelCollapse()
            openedAt = now
            host.update { it.copy(expanded = true) }
            host.scheduleCollapse(7_000)
        } else if (now - openedAt > closeGuardMs) {
            host.collapseSoon()
        }
        // Açıldıktan hemen sonra gelen hızlı dokunuşlar paneli kapatmaz
    }
}
