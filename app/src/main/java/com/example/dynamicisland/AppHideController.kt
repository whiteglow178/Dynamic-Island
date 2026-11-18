package com.example.dynamicisland

/** Uygulamaya özel gizleme: seçili uygulama ön plandayken ada gizlenir. Servisten taşındı; davranış aynıdır. */
class AppHideController(private val host: IslandHost) {

    var hidden = false
        private set

    /** Ön plandaki uygulama gizleme listesindeyse ada tamamen gizlenir ve dokunuşu geçirir; çıkınca geri gelir. */
    fun update(fg: String? = IslandAccessibilityService.currentFg) {
        val s = host.settings
        val hide = s.hideInApps && fg != null && fg in s.hiddenAppList()
        if (hide == hidden) return
        hidden = hide
        host.setIslandHidden(hide)
    }
}
