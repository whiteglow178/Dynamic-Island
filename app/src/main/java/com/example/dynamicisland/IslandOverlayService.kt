package com.example.dynamicisland

import android.app.Dialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.LruCache
import android.view.ContextThemeWrapper
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

private const val WINDOW_HOLD_MS = 700L   // müzik adası kapandıktan sonra pencerenin küçülmeden önce ek bekleme süresi
private const val RESIZE_HIDE_MS = 80L   // pencere küçülürken içeriğin gizli kalma süresi
private const val MEDIA_BG_GRACE_MS = 1_400L   // müzik "durdu" bildirse de yan baloncuğun kalma süresi (parça geçişi)

class IslandOverlayService : LifecycleService(), SavedStateRegistryOwner {

    private val savedStateController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ui = MutableStateFlow(IslandUiState())
    private lateinit var settings: StateFlow<IslandSettings>

    private lateinit var wm: WindowManager
    private var dialog: Dialog? = null
    private var composeView: ComposeView? = null
    private var collapseJob: Job? = null
    private var mediaGraceJob: Job? = null     // şarkı geçişinde oynatıcı bir an "durdu" / boş bildirirse adayı hemen kapatma
    private var lastPillToggleAt = 0L          // hızlı art arda dokunuşlarda adanın sürekli aç-kapa yapmasını önler
    private var lastMedia: IslandContent.Media? = null
    private val iconCache = LruCache<String, ImageBitmap>(8)

    private var density = 1f
    private var cutCenterXOffsetPx = 0    // kamera deliğinin ekran merkezine göre yatay farkı
    private var cutCenterYPx = 0          // kamera deliğinin dikey merkezi
    private var curW = 1
    private var curH = 1

    // Konum otomatik düzeltme: pencere içeriği sistem tarafından kaydırılırsa ölçüp telafi ederiz
    // Şarkı sözleri
    private val lyrics = MutableStateFlow<LyricsState>(LyricsState.Idle)
    private val mediaState = MutableStateFlow<IslandContent.Media?>(null)
    private var lyricsKey: String? = null
    private var lyricsJob: Job? = null
    private var outsideView: ComposeView? = null
    private var outsideLp: WindowManager.LayoutParams? = null
    private var outsideWm: WindowManager? = null      // pencerenin eklendiği WindowManager (erişilebilirlik ya da servis)
    private var outsideDy = 0                         // sistemin pencereyi kaydırdığı miktar (kalibrasyon)
    private var outsideTries = 0
    private var outsideWantY = 0

    private var wantX = 0
    private var wantY = 0                 // adanın pencere üstünün olması gereken ekran Y'si
    private var calibDy = 0               // ölçülen kayma (px)
    private var calibTries = 0
    private val calibrateRunnable = Runnable { calibrate() }
    private val showAgain = Runnable { composeView?.animate()?.alpha(1f)?.setDuration(120)?.start() }
    private var resizeListener: View.OnLayoutChangeListener? = null

    private data class WinKey(val kind: Int, val expanded: Boolean, val lyr: Boolean, val s: IslandSettings, val swallow: Boolean = false, val side: Int = 0)

    // ---- Çoklu olay: en yeni olay ortada, öncekiler sağ / sol baloncukta
    private enum class Bg { Media, Rec, Call }
    private val bgOrder = ArrayList<Bg>()      // başlama sırası: en eski başta, en yeni sonda
    private var winSide = 0                    // pencerenin şu an yan baloncuklar için ayırdığı pay (px)
    private var mediaBgUntil = 0L              // parça geçişinde "durdu" bildirimi gelse de baloncuk kısa süre kalır
    private var mediaBgJob: Job? = null

    override fun onCreate() {
        savedStateController.performAttach()
        savedStateController.performRestore(null)
        super.onCreate()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        density = resources.displayMetrics.density
        settings = SettingsRepository(applicationContext).flow
            .stateIn(scope, SharingStarted.Eagerly, IslandSettings())
        // Arka plandan (örn. sistem START_STICKY ile yeniden başlatınca) foreground'a geçiş reddedilebilir;
        // istisna yakalanmazsa süreç çöker ve erişilebilirlik servisi "hatalı çalışıyor" olarak işaretlenir.
        if (!runCatching { startAsForeground() }.isSuccess) { stopSelf(); return }
        runCatching { detectCutout() }
        runCatching { attachStatusCover() }      // ada penceresinden ÖNCE eklenir: ada her zaman üstte kalır
        runCatching { attachEdgeGlow() }         // kenar parlaması da adadan ÖNCE eklenir (ada üstte kalır)
        safeAttachOverlay()
        // Erişilebilirlik servisi açılıp kapanınca pencere türünü yeniden seç
        IslandAccessibilityService.onChanged = { runCatching { reattach() } }
        scope.launch { IslandBus.events.collect(::handle) }
        // Durdur → Başlat sonrası: zaten çalan müziği (değişmediği için yeniden gelmeyen) tekrar iste
        IslandNotificationListener.republish(this)
        // Bildirim dinleyicisi sistem tarafından yeniden bağlanırken zaman alabilir (özellikle uzun süre sonra):
        // müzik gelene kadar birkaç kez tekrar iste.
        scope.launch {
            var attempt = 0
            for (wait in longArrayOf(1_500, 3_000, 6_000, 12_000)) {
                delay(wait)
                if (lastMedia != null) break
                IslandNotificationListener.republish(this@IslandOverlayService, force = attempt++ >= 1)
            }
        }
        // Ayar değişince: söz aramasını, kapalı ada genişliğini ve dış söz penceresini güncelle
        scope.launch { settings.collect { s -> updateInMusicApp(); ensureLyrics(); update { it }; refreshMediaVisibility(); syncOutside(s); preloadFavorites(s) } }
        scope.launch { lyrics.collect { update { it } } }
        // Kenar Cevapla / Reddet tuşları ayarı değişince (arama sürerken) güncelle
        scope.launch { SideCallStore.flow(applicationContext).collect { syncSideButtons() } }
        // Durum görünümü (şarj, zil modu...) açıkken durum çubuğunu kapat
        scope.launch {
            combine(ui, settings) { u, s -> s.hideStatusBar && u.content is IslandContent.Status }
                .distinctUntilChanged()
                .collect { setStatusCover(it) }
        }
        // Pencere geometrisi yalnızca durum/ayar DEĞİŞİNCE güncellenir (kare başına değil)
        scope.launch {
            combine(ui, settings) { u, s -> WinKey(kindOf(u.content), u.expanded, u.compactLyrics, s, u.swallow, sideKey(u)) }
                .distinctUntilChanged()
                .collectLatest { syncWindow(it) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY
    }

    private fun dp(v: Float) = (v * density).roundToInt()
    private fun kindOf(c: IslandContent) = when (c) {
        IslandContent.Idle -> 0
        is IslandContent.Notif -> 1
        is IslandContent.Media -> 2
        IslandContent.Assistant -> 3
        is IslandContent.Favorites -> 4
        is IslandContent.Call -> if (c.isIncoming) 5 else 6
        is IslandContent.Status -> if (c.kind == StatusKind.Charging) 8 else 7
        is IslandContent.ScreenRec -> if (c.phase == RecPhase.Countdown || c.phase == RecPhase.Processing) 10 else 9
    }

    // ---------------------------------------------------------------- Olay mantığı
    private fun handle(e: IslandEvent) {
        when (e) {
            is IslandEvent.AssistantChanged -> onAssistant(e.active)
            is IslandEvent.AppSwitched -> {
                if ((swallowView != null || stretchView != null) && isPlayingMediaApp(e.to)) abortExit()   // müzik uygulamasına geri dönüldü
                val wasMusic = inMusicApp
                updateInMusicApp(e.to)          // önce "müzik uygulamasında mıyım" güncellenir
                val started = onAppSwitched(e.from)   // çıkış animasyonu varsa ada yutma durumuna geçer (gizleme atlanır)
                if (started) exitHold = settings.value.hideInMusicApp && wasMusic   // panel sürerken gelen ikinci olay gizlemeyi bozmasın
                refreshMediaVisibility()        // animasyon başlamadıysa müzik göster / gizle
            }
            is IslandEvent.Call -> onCall(e)
            is IslandEvent.CallEnded -> onCallEnded(e.key)
            is IslandEvent.ScreenRec -> onScreenRec(e)
            is IslandEvent.ScreenRecGone -> onScreenRecGone(e)
            is IslandEvent.Status -> if (!assistantActive && callKey == null) showTemporarily(e.content, e.holdMs)
            is IslandEvent.Notification -> if (!assistantActive && callKey == null) scope.launch {
                val icon = iconFor(e.pkg)
                showTemporarily(IslandContent.Notif(e.pkg, e.title, e.text, icon, e.intent, e.key), holdMs = 4_000)
            }
            is IslandEvent.MediaChanged -> onMedia(e.media)
            is IslandEvent.ShowFavorites -> if (!assistantActive && callKey == null && ui.value.content is IslandContent.Idle) openFavorites()
        }
    }

    /** ui'yi günceller ve "kapalı adada söz gösterilsin mi" bayrağını yeniden hesaplar. */
    // ---- "Müzik uygulamasındayken adada müziği gizle"
    private var inMusicApp = false
    private var exitHold = false        // çıkış animasyonu sürerken (panel adaya girene dek) müzik gösterilmez
    private fun hidingMedia() = settings.value.hideInMusicApp && (inMusicApp || exitHold)

    private fun updateInMusicApp(fg: String? = IslandAccessibilityService.currentFg) {
        inMusicApp = settings.value.hideInMusicApp && fg != null && lastMedia?.playing == true && isPlayingMediaApp(fg)
    }

    /** Gizleme durumu değişince ada içeriğini günceller. Yutma sürerken dokunmaz (yutma bitince müzik zaten gösterilir). */
    private fun refreshMediaVisibility() {
        val cur = ui.value
        if (cur.swallow) return
        if (hidingMedia()) {
            if (cur.content is IslandContent.Media) { collapseJob?.cancel(); update { IslandUiState() } }
        } else {
            val m = lastMedia
            if (cur.content is IslandContent.Idle && m != null && m.playing && callKey == null && !assistantActive) {
                update { IslandUiState(m, false) }
            }
        }
    }

    private fun update(f: (IslandUiState) -> IslandUiState) {
        var n = f(ui.value)
        // Müzik uygulamasındayken müzik gösterilmez (yutma efekti hariç: o içerik çizmez)
        if (n.content is IslandContent.Media && !n.swallow && hidingMedia()) n = IslandUiState()
        val s = settings.value
        val want = s.lyricsEnabled && s.lyricsCompact && lyrics.value is LyricsState.Ready &&
            lastMedia?.playing == true && n.content is IslandContent.Media
        ui.value = n.copy(compactLyrics = want && !n.swallow, side = sideFor(n))
        syncRevert(ui.value)
    }

    /** Yan baloncuktan açılan (ortaya kalıcı geçmemiş) olay şu an ortada açık mı? Öyleyse arka plan yayınları ortayı geri almaz. */
    private fun peeking(): Boolean {
        val cur = ui.value
        return peekBg != null && centerBg(cur.content) == peekBg      // küçülme animasyonu sürerken de (rest() çağrılana dek)
    }

    // Genişlemişken yalnızca gerçekten çizilecek baloncuklar pay ayırır; kapalıyken baloncuğa geri küçülme için fromSide de sayılır
    private fun sideKey(u: IslandUiState) =
        if (u.expanded) u.side.size else maxOf(u.side.size, if (u.fromSide != 0) 1 else 0)

    // ---------------------------------------------------------------- Çoklu olay (ortada en yeni, yanlarda öncekiler)
    private fun mediaBgActive(): Boolean {
        val m = lastMedia ?: return false
        return m.playing || android.os.SystemClock.elapsedRealtime() < mediaBgUntil
    }

    private fun bgActive(b: Bg): Boolean = when (b) {
        Bg.Media -> mediaBgActive() && !hidingMedia()
        Bg.Rec -> recPhase.let { it == RecPhase.Countdown || it == RecPhase.Recording || it == RecPhase.Processing }
        Bg.Call -> callKey != null && !callIncoming
    }

    /** Biten olayları listeden çıkarır, yeni başlayanı sona (= en yeni) ekler. */
    private fun syncBgOrder() {
        bgOrder.removeAll { !bgActive(it) }
        for (b in Bg.values()) if (b !in bgOrder && bgActive(b)) bgOrder.add(b)
    }

    private fun bgContent(b: Bg): IslandContent? = when (b) {
        Bg.Media -> lastMedia
        Bg.Rec -> recContent()
        Bg.Call -> if (callKey == null) null else IslandContent.Call(
            callName, callIncoming,
            if (callIncoming) null else formatCallDuration(System.currentTimeMillis() - callSince),
            callAvatar, callSpeaker,
        )
    }

    /** Ortadaki içerik hangi arka plan olayına ait? (bildirim, durum, favoriler vb. için null) */
    private fun centerBg(c: IslandContent): Bg? = when (c) {
        is IslandContent.Media -> Bg.Media
        is IslandContent.ScreenRec -> if (c.phase != RecPhase.Saved) Bg.Rec else null
        is IslandContent.Call -> if (!c.isIncoming) Bg.Call else null
        else -> null
    }

    /**
     * Yan baloncukların yuvaları [sağ, sol] (geçici öne alma sırasında): dokunulan baloncuğun yuvasına önceki ortadaki olay geçer,
     * böylece baloncuklar birbirinin yerine atlamaz. Geri dönüşte / yeniden dinlenmede sıfırlanır.
     */
    private var sideLayout: List<Bg>? = null
    private var sidesHidden = false     // geri dönüşte baloncuklar kısa süre ortadaki adaya karışır (mevcut birleşme animasyonu)

    private fun defaultSides(center: Bg): List<Bg> = bgOrder.asReversed().filter { it != center }.take(2)

    private fun orderedSides(center: Bg): List<Bg> {
        val layout = sideLayout ?: return defaultSides(center)
        val kept = layout.filter { it != center && it in bgOrder }
        return (kept + defaultSides(center).filter { it !in kept }).take(2)
    }

    /**
     * Yan baloncuklar: en yeni olay ortada, sondan ikinci sağda, sondan üçüncü solda.
     * Ada genişlemişken de (ekrana sığıyorsa) baloncuklar genişleyen panelin yanında kalır; böylece hangi olayın
     * hâlâ aktif olduğu görünür.
     */
    private fun sideFor(n: IslandUiState): List<IslandContent> {
        syncBgOrder()
        if (n.swallow || sidesHidden) return emptyList()
        val center = centerBg(n.content) ?: return emptyList()
        if (n.expanded) {
            // Dar ekranda panel + baloncuklar sığmıyorsa genişlemişken baloncuk gösterilmez
            val screenWDp = wm.currentWindowMetrics.bounds.width() / density
            if (!sideFitsWhenExpanded(n.content, settings.value, screenWDp)) return emptyList()
            return orderedSides(center).mapNotNull { bgContent(it) }
        }
        if (n.fromSide != 0) return emptyList()   // fromSide: baloncuğa geri küçülürken yan baloncuk çizilmez
        return orderedSides(center).mapNotNull { bgContent(it) }
    }

    private var assistantActive = false

    /** Asistan/Gemini açılınca adayı büyütüp Siri animasyonunu gösterir; kapanınca eski haline döner. */
    private fun onAssistant(active: Boolean) {
        if (active == assistantActive) return
        if (active && !settings.value.assistantEnabled) return
        if (active && callKey != null) return          // arama sürerken asistan animasyonu araya girmesin
        assistantActive = active
        glowActive.value = active                      // ekran kenarı parlaması: açılırken sağdan sola yayılır, kapanınca söner
        collapseJob?.cancel()
        if (active) {
            update { IslandUiState(IslandContent.Assistant, expanded = true) }
        } else if (ui.value.content is IslandContent.Assistant) {
            collapseJob = scope.launch { collapseAndRest() }
        }
    }

    private fun onMedia(m: IslandContent.Media?) {
        if (m == lastMedia) return
        val wasActive = bgActive(Bg.Media)
        lastMedia = m
        mediaState.value = m
        // Parça geçişinde oynatıcı kısa süre "durdu" bildirse de yan baloncuk birleşip tekrar ayrılmasın
        val nowMs = android.os.SystemClock.elapsedRealtime()
        if (m?.playing == true) mediaBgUntil = nowMs + MEDIA_BG_GRACE_MS
        mediaBgJob?.cancel()
        if (m != null && !m.playing && mediaBgUntil > nowMs) {
            mediaBgJob = scope.launch { delay(mediaBgUntil - nowMs + 60); update { it } }
        }
        updateInMusicApp()
        ensureLyrics()
        val cur = ui.value
        val cc = cur.content
        val nowActive = bgActive(Bg.Media)
        when {
            cc is IslandContent.Call && cc.isIncoming -> update { it }  // gelen arama ortayı hep tutar
            cc is IslandContent.Assistant -> update { it }
            cc is IslandContent.Notif -> update { it }
            cc is IslandContent.Status -> update { it }
            cc is IslandContent.Favorites -> update { it }              // favori paneli açıkken müzik paneline geçme
            cc is IslandContent.ScreenRec && cc.phase == RecPhase.Saved -> update { it }   // "kaydedildi" görünümü bozulmasın
            // Müzik yeni başladı: en yeni olay olarak ortaya geçer, önceki ortadaki olay yan baloncuğa ayrılır
            nowActive && !wasActive && m != null && cc !is IslandContent.Media -> {
                mediaGraceJob?.cancel(); collapseJob?.cancel()
                update { IslandUiState(m, false) }
            }
            cc is IslandContent.Call || cc is IslandContent.ScreenRec -> update { it }   // müzik zaten yan baloncukta (ya da yok)
            m == null || (!m.playing && !cur.expanded) -> {
                val showing = cur.content as? IslandContent.Media
                // Parça geçişinde oynatıcı kısa süre "durdu" / "yükleniyor" ya da boş bildirir (çoğu zaman ESKİ başlıkla):
                // ada kapak + ekolayzırla birlikte kaybolup geri gelmesin; kısa bir süre mevcut görünümde kalır.
                // Gerçekten durduysa / kapandıysa sonra kapanır.
                if (showing != null) {
                    if (m != null) update { it.copy(content = m) }
                    mediaGraceJob?.cancel()
                    mediaGraceJob = scope.launch {
                        delay(1500)
                        if (lastMedia?.playing != true && !ui.value.expanded && ui.value.content is IslandContent.Media) {
                            collapseJob?.cancel(); rest()
                        }
                    }
                } else { mediaGraceJob?.cancel(); collapseJob?.cancel(); rest() }
            }
            else -> { mediaGraceJob?.cancel(); update { it.copy(content = m) } }
        }
    }

    /** Parça (başlık/sanatçı/süre) değişince sözleri arar; özellik kapalıysa hiçbir istek atılmaz. */
    private fun ensureLyrics() {
        val s = settings.value
        val m = lastMedia
        val wanted = s.lyricsEnabled && (s.lyricsInPanel || s.lyricsCompact || s.lyricsOutside)
        if (!wanted || m == null || m.title.isBlank()) {
            lyricsJob?.cancel(); lyricsKey = null
            if (lyrics.value != LyricsState.Idle) lyrics.value = LyricsState.Idle
            return
        }
        val key = "${m.title}|${m.artist}|${m.durationMs / 1000}"
        if (key == lyricsKey) return
        lyricsKey = key
        lyricsJob?.cancel()
        LyricsRepository.cached(key)?.let { lyrics.value = it; return }
        lyrics.value = LyricsState.Loading
        lyricsJob = scope.launch {
            val r = LyricsRepository.fetch(key, m.title, m.artist, m.durationMs)
            if (lyricsKey == key) lyrics.value = r
        }
    }

    private fun showTemporarily(content: IslandContent, holdMs: Long) {
        update { IslandUiState(content, expanded = true) }
        scheduleCollapse(holdMs)
    }

    private fun scheduleCollapse(holdMs: Long) {
        collapseJob?.cancel()
        collapseJob = scope.launch { delay(holdMs); collapseAndRest() }
    }

    /** Yan baloncuğa dokunulup (ortaya kalıcı geçmeden) açılan olay: küçülünce hemen yana geri döner. */
    private var peekBg: Bg? = null

    private suspend fun collapseAndRest() {
        val peek = peekBg != null && centerBg(ui.value.content) == peekBg
        // Yan baloncuktan açılan olay: fromSide korunur; ada küçülürken baloncuğun yerine (daire) geri döner
        update { it.copy(expanded = false, fromSide = if (peek) it.fromSide else 0) }
        val settle = settings.value.settleMs()
        delay(if (peek) settle * 85 / 100 else settle)
        rest()
    }

    private fun rest() {
        peekBg = null
        if (revertOrder == null) sideLayout = null
        syncBgOrder()
        if (callKey != null && callIncoming) { publishCall(); return }     // gelen arama hep ortada
        // Ortaya en yeni etkin olay gelir; öncekiler update() içinde yan baloncuk olur.
        // Duraklatılmış müzik (yalnızca baloncuk tutma süresi içinde) ortayı almaz.
        val top = bgOrder.lastOrNull { it != Bg.Media || lastMedia?.playing == true }
        val c = top?.let { bgContent(it) }
        when {
            c == null -> update { IslandUiState() }
            top == Bg.Call -> update { IslandUiState(c, expanded = callExpanded) }
            else -> update { IslandUiState(c, false) }
        }
    }

    private fun onPillTap() {
        val cur = ui.value
        when (cur.content) {
            is IslandContent.Media -> {
                // Yalnızca titreşimle gelen çift dokunuşu eler (60 ms); gerçek hızlı dokunuşlar anında işlenir.
                // Takılma artık pencere bekletme / panel önden hazırlama ile giderildi.
                val nowT = android.os.SystemClock.elapsedRealtime()
                if (nowT - lastPillToggleAt < 60) return
                lastPillToggleAt = nowT
                val s = settings.value
                if (cur.expanded) {
                    if (s.openExpAny) openMusicApp()
                    else { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
                } else {
                    if (s.openCompTap) openMusicApp()
                    else { update { it.copy(expanded = true) }; scheduleCollapse(8_000) }
                }
            }
            is IslandContent.Notif -> {
                val n = cur.content as IslandContent.Notif
                val s = settings.value
                when {
                    s.notifTapOpens -> openNotification(n)
                    s.notifCollapseTap -> collapseNow()
                    else -> Unit
                }
            }
            is IslandContent.Favorites -> {
                val now = android.os.SystemClock.elapsedRealtime()
                if (!cur.expanded) {
                    // Panel kapanırken tekrar dokunuldu: kapanmasını bekletmeden yeniden aç
                    collapseJob?.cancel()
                    favOpenedAt = now
                    update { it.copy(expanded = true) }
                    scheduleCollapse(7_000)
                } else if (now - favOpenedAt > FAV_CLOSE_GUARD_MS) {
                    collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
                }
                // Açıldıktan hemen sonra gelen hızlı dokunuşlar paneli kapatmaz
            }
            IslandContent.Idle -> {
                val s = settings.value
                if (s.favoritesReady() && s.favoritesTrigger == 0) openFavorites()
            }
            IslandContent.Assistant -> Unit
            is IslandContent.Call -> {
                // Gelen arama hep açık (düğmeler kendi dokunuşunu işler). Aktif aramada dokununca aç / küçült.
                if (!callIncoming && peeking()) collapseNow()          // baloncuktan açılmış arama: dokununca baloncuğa geri küçülür
                else if (!callIncoming) {
                    if (callExpanded) { callExpanded = false; callCollapseJob?.cancel(); publishCall() }
                    else { callExpanded = true; publishCall(); scheduleCallCollapse() }
                }
            }
            is IslandContent.Status -> { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
            is IslandContent.ScreenRec -> {
                val c = cur.content as IslandContent.ScreenRec
                when (c.phase) {
                    RecPhase.Recording ->
                        if (cur.expanded) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
                        else { update { it.copy(expanded = true) }; scheduleCollapse(6_000) }
                    RecPhase.Saved -> { sendCallIntent(recOpen); endRec() }   // kaydı galeride aç
                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------- Ekran kaydı (sistem kaydedici; izin gerektirmez)
    private var recPhase: RecPhase? = null
    private var recSince = 0L
    private var recFrozenMs = 0L
    private var recCount = 0
    private var recThumb: ImageBitmap? = null
    private var recStop: android.app.PendingIntent? = null
    private var recOpen: android.app.PendingIntent? = null
    private var recTimeoutJob: Job? = null
    private var recSavedJob: Job? = null
    private var recThumbJob: Job? = null
    private var recViaA11y = false     // kayıt yüzen çubuktaki süre yazısından algılandı (bildirimdeki "bitti" sinyallerini yok say)

    private fun recContent(): IslandContent.ScreenRec? =
        recPhase?.let { IslandContent.ScreenRec(it, recSince, recFrozenMs, recCount, recThumb) }

    private fun publishRec(expanded: Boolean) {
        val c = recContent() ?: return
        if (assistantActive || (callKey != null && callIncoming)) return
        syncBgOrder()
        // Kayıt, daha yeni bir olayın (müzik / arama) arkasında kaldıysa ortayı almaz: yalnızca yan baloncuğu yenilenir
        if (!expanded && c.phase != RecPhase.Saved && ui.value.content !is IslandContent.ScreenRec &&
            (peeking() || bgOrder.lastOrNull().let { it != null && it != Bg.Rec })) {
            update { it }
            return
        }
        collapseJob?.cancel()
        update { IslandUiState(c, expanded = expanded) }
    }

    private fun freezeRec() {
        if (recFrozenMs == 0L && recSince > 0L) recFrozenMs = (System.currentTimeMillis() - recSince).coerceAtLeast(0L)
    }

    /** Kayıt bitti ama kaydedildi bildirimi gelmediyse (iptal / hata) spinner sonsuza dek dönmesin. */
    private fun armRecTimeout() {
        recTimeoutJob?.cancel()
        recTimeoutJob = scope.launch { delay(20_000); if (recPhase == RecPhase.Processing) endRec() }
    }

    private fun endRec() {
        recTimeoutJob?.cancel(); recSavedJob?.cancel(); recThumbJob?.cancel()
        recPhase = null; recSince = 0L; recFrozenMs = 0L; recCount = 0
        recThumb = null; recStop = null; recOpen = null; recViaA11y = false
        if (ui.value.content is IslandContent.ScreenRec) {
            collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
        } else {
            update { it }      // kayıt yan baloncuktaydı: baloncuk adaya geri birleşir
        }
    }

    private fun onScreenRec(e: IslandEvent.ScreenRec) {
        if (!settings.value.screenRecEnabled) return
        if (e.phase != RecPhase.Processing) recTimeoutJob?.cancel()
        recSavedJob?.cancel()
        val now = System.currentTimeMillis()
        when (e.phase) {
            RecPhase.Countdown -> {
                recPhase = RecPhase.Countdown; recCount = e.count; recSince = 0L; recFrozenMs = 0L; recThumb = null
                publishRec(false)
                // Rakam bir yerde takılı kalırsa ada geri sayımda kalmasın
                recTimeoutJob = scope.launch { delay(6_000); if (recPhase == RecPhase.Countdown) endRec() }
            }
            RecPhase.Recording -> {
                val first = recPhase != RecPhase.Recording
                recPhase = RecPhase.Recording; recStop = e.stop
                if (first) {
                    recViaA11y = e.key == "a11y"
                    recSince = e.startedAt.takeIf { it in 1..now } ?: now
                    recFrozenMs = 0L; recThumb = null
                    publishRec(false)
                } else if (ui.value.content !is IslandContent.ScreenRec) publishRec(false)
            }
            RecPhase.Processing -> {
                // Kayıt görmediysek (ör. yüzen ada hazır beklerken) işleniyor animasyonu başlatma
                if (recPhase != RecPhase.Recording && recPhase != RecPhase.Processing) return
                freezeRec()
                if (recPhase != RecPhase.Processing) { recPhase = RecPhase.Processing; publishRec(false); armRecTimeout() }
            }
            RecPhase.Saved -> {
                // Kayıt başlamadan gelen "kaydedildi" bildirimini (eski kayıt vb.) yok say
                val already = recPhase == RecPhase.Saved
                if (!already && recPhase != RecPhase.Recording && recPhase != RecPhase.Processing) return
                freezeRec()
                recOpen = e.open ?: recOpen
                // Galeriden önizleme beklenirken ikinci bir bildirim gelirse bekleme yeniden başlatılmaz
                if (recThumbJob?.isActive == true && e.thumb == null) return
                val thumb = e.thumb ?: recThumb
                if (thumb == null && !already && canReadGallery()) {
                    // Bildirimde resim yok, galeri izni var: önce işleniyor animasyonu sürer, önizleme bulununca ada genişler
                    if (recPhase != RecPhase.Processing) { recPhase = RecPhase.Processing; publishRec(false) }
                    loadRecThumbFromGallery()
                    return
                }
                recThumbJob?.cancel()
                finishSaved(thumb)
            }
        }
    }

    /** Bildirimde önizleme yoksa: izin verilmişse galerideki son ekran kaydının ilk karesi alınır (isteğe bağlı özellik). */
    private fun canReadGallery() =
        androidx.core.content.ContextCompat.checkSelfPermission(this, recPreviewPermission()) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Kaydedildi görünümü: ada genişler (süre sabit, "Kayıt galeriye kaydedildi", sağda önizleme) ve 5 sn sonra kapanır. */
    private fun finishSaved(thumb: ImageBitmap?) {
        recPhase = RecPhase.Saved
        if (thumb != null) recThumb = thumb
        publishRec(true)
        recSavedJob?.cancel()
        recSavedJob = scope.launch { delay(5_000); endRec() }
    }

    private fun loadRecThumbFromGallery() {
        recThumbJob?.cancel()
        val startMs = if (recSince > 0L) recSince else System.currentTimeMillis() - 60_000L
        val sinceSec = (startMs / 1000 - 10).coerceAtLeast(0L)
        recThumbJob = scope.launch {
            // Dosya galeriye biraz geç düşebilir: kısa aralıklarla birkaç kez denenir
            repeat(10) {
                val bmp = withContext(Dispatchers.IO) { queryLatestRecThumb(sinceSec) }
                if (bmp != null) {
                    if (recPhase == RecPhase.Processing) finishSaved(bmp.asImageBitmap())
                    return@launch
                }
                delay(400)
            }
            // Bulunamadıysa (yaklaşık 4 sn) yine de kaydedildi görünümüne geç, önizleme yerine gri kutu kalır
            if (recPhase == RecPhase.Processing) finishSaved(null)
        }
    }

    private fun queryLatestRecThumb(sinceSec: Long): android.graphics.Bitmap? {
        val col = android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        var found: Long? = null
        runCatching {
            val proj = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                android.provider.MediaStore.Video.Media.RELATIVE_PATH,
            )
            contentResolver.query(
                col, proj, "${android.provider.MediaStore.Video.Media.DATE_ADDED} >= ?", arrayOf(sinceSec.toString()),
                "${android.provider.MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { c ->
                var newest: Long? = null
                var rec: Long? = null
                while (rec == null && c.moveToNext()) {
                    val id = c.getLong(0)
                    val txt = (c.getString(1).orEmpty() + " " + c.getString(2).orEmpty()).lowercase()
                    if (listOf("record", "screen", "ekran").any { txt.contains(it) }) rec = id
                    if (newest == null) newest = id
                }
                found = rec ?: newest
            }
        }
        val id = found ?: return null
        return runCatching {
            contentResolver.loadThumbnail(android.content.ContentUris.withAppendedId(col, id), android.util.Size(256, 256), null)
        }.getOrNull()
    }

    private fun onScreenRecGone(e: IslandEvent.ScreenRecGone) {
        if (!settings.value.screenRecEnabled) return
        // Süre yazısından algılanan kaydı yalnızca yine o yol bitirir (bekleme bildirimi kayıt sürerken de güncellenebilir)
        if (recViaA11y && recPhase == RecPhase.Recording && e.key != "a11y") return
        when {
            // Kayıt bildirimi kalktı: kayıt bitti, işleniyor (beyaz dönen halka) -> kaydedildi bildirimi beklenir
            e.phase == RecPhase.Recording && recPhase == RecPhase.Recording -> {
                freezeRec(); recPhase = RecPhase.Processing; publishRec(false); armRecTimeout()
            }
            // Geri sayım bildirimi kalktı: kayıt başlamadıysa (iptal) kapat
            e.phase == RecPhase.Countdown && recPhase == RecPhase.Countdown -> {
                recTimeoutJob?.cancel()
                recTimeoutJob = scope.launch { delay(1_500); if (recPhase == RecPhase.Countdown) endRec() }
            }
        }
    }

    /** Adadaki durdur düğmesi: sistem kaydının bildirimdeki "Durdur" eylemini tetikler. */
    private fun onRecStop() {
        if (recStop != null) sendCallIntent(recStop) else IslandAccessibilityService.stopScreenRec()
        freezeRec(); recPhase = RecPhase.Processing; publishRec(false); armRecTimeout()
    }

    // ---------------------------------------------------------------- Arama (gelen / aktif)
    private var callKey: String? = null
    private var callName = ""
    private var callAvatar: ImageBitmap? = null
    private var callIncoming = true
    private var callSince = 0L
    private var callAnswer: android.app.PendingIntent? = null
    private var callDecline: android.app.PendingIntent? = null
    private var callTickJob: Job? = null
    private var callEndJob: Job? = null
    private var callSpeaker = false
    private val sideCall by lazy { SideCallOverlay(this, this, this, wm) }
    private var callExpanded = true
    private var callFromSide = 0      // aramaya yan baloncuktan dokunulduysa açılış yönü (animasyon için)
    private var callCollapseJob: Job? = null

    private fun onCall(e: IslandEvent.Call) {
        callEndJob?.cancel()
        if (e.incoming || !peeking()) collapseJob?.cancel()      // baloncuktan açılan panelin kapanma zamanlayıcısı bozulmasın
        val justAnswered = !e.incoming && (callKey == null || callIncoming)   // yeni aktif arama / az önce cevaplandı
        callKey = e.key
        callName = e.name
        callAvatar = e.avatar
        callIncoming = e.incoming
        callAnswer = e.answer
        callDecline = e.decline
        callTickJob?.cancel()
        if (e.incoming) {
            callSince = 0L
            callExpanded = true; callCollapseJob?.cancel()      // gelen arama hep açık
        } else {
            val now = System.currentTimeMillis()
            if (callSince == 0L) callSince = e.startedAt.takeIf { it in 1..now } ?: now
            callTickJob = scope.launch { while (true) { delay(1_000); publishCall() } }
        }
        if (justAnswered) { callExpanded = false; callCollapseJob?.cancel() }   // cevaplanınca küçük ada
        publishCall()
        syncSideButtons()
    }

    private fun publishCall() {
        syncBgOrder()
        // Aktif arama, daha yeni bir olayın (müzik / ekran kaydı) arkasında kaldıysa ortayı almaz: yalnızca yan baloncuğu yenilenir
        val dur = if (callIncoming) null else formatCallDuration(System.currentTimeMillis() - callSince)
        // Yan baloncuktan açılan olay (müzik / kayıt / arama) ortadayken: açık / kapalı durumunu yalnızca peek yönetir.
        // Saniyelik süre güncellemesi paneli ne kapatır ne de ortayı geri alır; arama paneliyse yalnızca süre yenilenir.
        if (!callIncoming && peeking()) {
            update { st ->
                if (st.content is IslandContent.Call) st.copy(content = IslandContent.Call(callName, false, dur, callAvatar, callSpeaker)) else st
            }
            return
        }
        if (!callIncoming && ui.value.content !is IslandContent.Call &&
            bgOrder.lastOrNull().let { it != null && it != Bg.Call }) { update { it }; return }
        if (!callExpanded) callFromSide = 0
        update { IslandUiState(IslandContent.Call(callName, callIncoming, dur, callAvatar, callSpeaker), expanded = callExpanded, fromSide = callFromSide) }
    }

    private fun formatCallDuration(ms: Long): String {
        val t = (ms / 1000).coerceAtLeast(0)
        val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** Bildirim kalkınca adayı kapatır. Gelen -> aktif geçişinde bildirim değiştiği için kısa süre beklenir (titreme olmasın). */
    private fun onCallEnded(key: String) {
        if (key != callKey) return
        callEndJob?.cancel()
        callEndJob = scope.launch {
            delay(600)
            if (callKey != key) return@launch
            callTickJob?.cancel()
            callKey = null; callSince = 0L; callAnswer = null; callDecline = null; callAvatar = null
            setSpeaker(false)
            callExpanded = true; callCollapseJob?.cancel()
            syncSideButtons()
            if (ui.value.content is IslandContent.Call) collapseAndRest() else update { it }
        }
    }

    private fun sendCallIntent(pi: android.app.PendingIntent?) {
        if (pi == null) return
        runCatching {
            val opts = if (Build.VERSION.SDK_INT >= 34) {
                android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else null
            pi.send(this, 0, null, null, null, null, opts)
        }
    }

    private fun onCallAnswer() {
        if (callKey == DEMO_CALL_KEY) {
            // Ana Sayfa > Dene: sahte arama. Cevapla -> aktif aramaya geç
            IslandBus.post(IslandEvent.Call(DEMO_CALL_KEY, "demo", callName, false, System.currentTimeMillis(), callAvatar, null, null))
            return
        }
        sendCallIntent(callAnswer)
    }
    /** Hoparlörü açar / kapatır (aktif arama ekranındaki hoparlör düğmesi). */
    private fun setSpeaker(on: Boolean) {
        callSpeaker = on
        runCatching {
            val am = getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    am.availableCommunicationDevices
                        .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                        ?.let { am.setCommunicationDevice(it) }
                } else am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = on
            }
        }
    }

    private fun onCallSpeaker() {
        setSpeaker(!callSpeaker)
        publishCall()
        if (callExpanded) scheduleCallCollapse()   // düğmeyle uğraşırken küçülmesin
    }

    /** Açılmış aktif arama ekranı bir süre sonra kendiliğinden küçülür. */
    private fun scheduleCallCollapse(holdMs: Long = CALL_AUTO_COLLAPSE_MS) {
        callCollapseJob?.cancel()
        callCollapseJob = scope.launch {
            delay(holdMs)
            if (callKey != null && !callIncoming) { callExpanded = false; publishCall() }
        }
    }

    private fun onCallDecline() {
        // Reddet / kapat: bildirimin kalkmasını bekleme, adayı hemen kapat
        if (callKey != DEMO_CALL_KEY) sendCallIntent(callDecline)
        endCallNow()
    }

    /** Aramayı yerelde anında bitirir: bekleme yok, ada hemen küçülüp kapanır. */
    private fun endCallNow() {
        callEndJob?.cancel(); callTickJob?.cancel(); callCollapseJob?.cancel()
        callKey = null; callSince = 0L; callAnswer = null; callDecline = null; callAvatar = null
        setSpeaker(false)
        callExpanded = true
        syncSideButtons()
        collapseJob?.cancel()
        collapseJob = scope.launch { collapseAndRest() }
    }

    /** Gelen aramada (ve ayar açıksa) kenar Cevapla / Reddet tuşlarını gösterir; aksi halde gizler. */
    private fun syncSideButtons() {
        val p = SideCallStore.flow(applicationContext).value
        val should = p.enabled && callKey != null && callIncoming &&
            android.provider.Settings.canDrawOverlays(this)
        if (should) {
            sideCall.show(
                p,
                onAnswer = { sideCall.hide(); onCallAnswer() },
                onDecline = { sideCall.hide(); onCallDecline() },
            )
        } else sideCall.hide()
    }

    /** Basılı tutma: boştayken (her iki tetikleyici modunda) favorileri açar; diğer hallerde dokunmayla aynı davranır. */
    private fun onPillLongPress() {
        val cur = ui.value
        val s = settings.value
        if (cur.content is IslandContent.Idle) {
            if (s.favoritesReady()) openFavorites()
        } else if (cur.content is IslandContent.Media && !cur.expanded) {
            // Küçük müzik adası: basılı tutunca müzik uygulaması açılır ya da ada genişler
            if (s.openCompLong) openMusicApp()
            else if (s.expandOnLong || !s.openCompTap) { update { it.copy(expanded = true) }; scheduleCollapse(8_000) }
        } else if (cur.content is IslandContent.Notif) {
            // Bildirim: basılı tutunca ayara göre uygulamayı aç ya da küçült (ayar yoksa bir şey yapmaz)
            when {
                s.notifOpenLong -> openNotification(cur.content)
                s.notifCollapseLong -> collapseNow()
                else -> Unit
            }
        } else {
            onPillTap()
        }
    }

    private fun collapseNow() { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }

    /** Bildirimi sistemden de siler ve adayı kapatır. */
    private fun dismissNotif(n: IslandContent.Notif) {
        IslandNotificationListener.dismiss(n.key)
        collapseNow()
    }

    /** Bildirim üstünde sağa / sola kaydırma. */
    private fun onNotifSwipeSide() {
        val c = ui.value.content as? IslandContent.Notif ?: return
        if (settings.value.notifDeleteSwipeSide) dismissNotif(c)
    }

    /** Yan baloncuğa dokunma: o olayın paneli açılır. Ayar açıksa olay kalıcı olarak ortaya geçer. */
    private fun onSideTap(index: Int) {
        val cur = ui.value
        val c = cur.side.getOrNull(index) ?: return
        val b = when (c) {
            is IslandContent.Media -> Bg.Media
            is IslandContent.ScreenRec -> Bg.Rec
            is IslandContent.Call -> Bg.Call
            else -> return
        }
        val from = if (index == 0) 1 else -1          // 0 = sağ baloncuk, 1 = sol
        // Dokunulan olay ortaya geçer ve genişler. Ayar "kalıcı" değilse küçüldükten ~1 sn sonra eski sıra geri gelir
        // (önceki ortadaki olay ortaya döner, dokunulan olay yine baloncuk olur).
        val permanent = settings.value.sideTapPromote
        revertJob?.cancel(); revertJob = null
        sidesHidden = false
        preGrowJob?.cancel(); preGrowJob = null
        if (permanent) revertOrder = null
        else if (revertOrder == null) revertOrder = bgOrder.toList()    // art arda dokunmada ilk (asıl) sıra korunur
        bgOrder.remove(b); bgOrder.add(b)
        peekBg = null
        // Önceki ortadaki olay, dokunulan baloncuğun yuvasına geçer
        val prevBg = centerBg(cur.content)
        val slots = cur.side.mapNotNull { centerBg(it) }.toMutableList()
        sideLayout = if (prevBg != null && index in slots.indices) { slots[index] = prevBg; slots } else null
        collapseJob?.cancel()
        when (b) {
            Bg.Media -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse(8_000) }
            Bg.Rec -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse(6_000) }
            Bg.Call -> { callExpanded = true; callFromSide = from; publishCall(); scheduleCallCollapse() }
        }
    }

    // ---- Geçici öne alma: dokunulan baloncuk küçülünce kısa süre sonra eski yerine (baloncuğa) döner
    private var revertOrder: List<Bg>? = null
    private var revertJob: Job? = null

    private fun syncRevert(n: IslandUiState) {
        if (revertOrder == null) return
        if (n.expanded) { revertJob?.cancel(); revertJob = null; return }   // panel açıkken geri dönmez
        if (revertJob?.isActive == true) return
        revertJob = scope.launch {
            delay(settings.value.settleMs() + 1_000L)       // küçülme animasyonu + 1 sn
            doRevert()
        }
    }

    private fun doRevert() {
        val saved = revertOrder ?: return
        revertOrder = null
        syncBgOrder()
        sideLayout = null
        val n = ui.value
        // Başka bir olay araya girdiyse (bildirim, yeni olay...) sıraya dokunulmaz
        if (n.expanded || centerBg(n.content) != bgOrder.lastOrNull()) { revertJob = null; return }
        // Mevcut baloncuk animasyonları: 1) yandaki baloncuk adaya geri karışır, 2) sıra geri gelir; bu olay adadan ayrılıp baloncuk olur
        revertJob = scope.launch {
            try {
                sidesHidden = true; update { it }
                delay(380)
            } finally { sidesHidden = false }
            val n2 = ui.value
            if (!n2.expanded && centerBg(n2.content) == bgOrder.lastOrNull()) {
                val cur = bgOrder.toList()
                val restored = saved.filter { it in cur } + cur.filter { it !in saved }
                bgOrder.clear(); bgOrder.addAll(restored)
                rest()
            } else update { it }
        }
    }

    /**
     * Yan baloncuğa parmak değdiği anda pencereyi açık boyuta önceden büyütür: dokunma tamamlanınca ada pencereyi beklemeden açılır
     * (ortadaki olayın kısa süre kaybolması azalır). Dokunma gelmezse 1,5 sn sonra pencere eski haline döner.
     */
    private var preGrowJob: Job? = null
    private fun onSidePress(index: Int) {
        val cur = ui.value
        if (cur.expanded) return          // pencere zaten açık boyutta
        val c = cur.side.getOrNull(index) ?: return
        val screenWDp = wm.currentWindowMetrics.bounds.width() / density
        val fits = sideFitsWhenExpanded(c, settings.value, screenWDp)
        val key = WinKey(kindOf(c), true, false, settings.value, false, if (fits) 1 else 0)
        preGrowJob?.cancel()
        preGrowJob = scope.launch {
            syncWindow(key)
            delay(1_500)
            if (!ui.value.expanded) {
                val u = ui.value
                syncWindow(WinKey(kindOf(u.content), u.expanded, u.compactLyrics, settings.value, u.swallow, sideKey(u)))
            }
        }
    }

    /** Çalan müziğin uygulamasını açar (oturumun kendi açılış hedefi, yoksa uygulamanın ana ekranı). */
    private fun openMusicApp() {
        val ctrl = runCatching {
            val msm = getSystemService(android.media.session.MediaSessionManager::class.java)
            val list = msm.getActiveSessions(android.content.ComponentName(this, IslandNotificationListener::class.java))
            list.firstOrNull { it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        }.getOrNull() ?: return
        val open: () -> Unit = {
            val pi = ctrl.sessionActivity
            val sent = pi != null && runCatching {
                val opts = if (Build.VERSION.SDK_INT >= 34) {
                    android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else null
                pi.send(this, 0, null, null, null, null, opts)
            }.isSuccess
            if (!sent) launchApp(ctrl.packageName)
        }
        val s = settings.value
        launchWithAnim(s.musicOpenAnim, pickAtomic(s.launchClassic, s.launchAtomic), s.launchSpeed, open)
    }

    /** Adayı dikey kaydırma: aşağı (+1) genişletir, yukarı (-1) küçültür. */
    private fun onPillSwipe(dir: Int) {
        val cur = ui.value
        val s = settings.value
        if (cur.swallow) return
        when (val c = cur.content) {
            is IslandContent.Media -> {
                if (dir > 0 && !cur.expanded && s.swipeDownExpand) {
                    update { it.copy(expanded = true) }; scheduleCollapse(8_000)
                } else if (dir < 0 && cur.expanded && s.swipeUpCollapse) {
                    collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
                }
            }
            is IslandContent.Notif -> {
                if (dir > 0) { if (s.notifOpenSwipeDown) openNotification(c) }
                else if (s.notifDeleteSwipeUp) dismissNotif(c)           // çakışırsa silme önceliklidir
                else if (s.notifCollapseSwipeUp) collapseNow()
            }
            is IslandContent.ScreenRec -> when {
                // "Kaydedildi" görünümü: herhangi bir dikey kaydırma kapatır (önceden hareketle küçültülemiyordu)
                c.phase == RecPhase.Saved -> endRec()
                c.phase == RecPhase.Recording && dir > 0 && !cur.expanded -> { update { it.copy(expanded = true) }; scheduleCollapse(6_000) }
                c.phase == RecPhase.Recording && dir < 0 && cur.expanded -> collapseNow()
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- Favori uygulamalar
    private val favCache = java.util.concurrent.ConcurrentHashMap<String, FavApp>()
    private var favOpenedAt = 0L
    private val FAV_CLOSE_GUARD_MS = 450L      // açılır açılmaz gelen hızlı dokunuşlar paneli kapatmasın

    private fun favApp(pkg: String): FavApp? = favCache[pkg] ?: runCatching {
        val pm = packageManager
        val label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        val icon = pm.getApplicationIcon(pkg).toBitmap(112, 112).asImageBitmap()
        FavApp(pkg, label, icon)
    }.getOrNull()?.also { favCache[pkg] = it }

    /** Favori uygulamaların simgelerini önceden yükler: ilk açılışta bekleme / takılma olmasın. */
    private fun preloadFavorites(s: IslandSettings) {
        if (!s.favoritesReady()) return
        val pkgs = s.favoritePkgList()
        favCache.keys.retainAll(pkgs.toSet())
        val missing = pkgs.filter { !favCache.containsKey(it) }
        if (missing.isEmpty()) return
        scope.launch(Dispatchers.Default) { missing.forEach { favApp(it) } }
    }

    private fun openFavorites() {
        val s = settings.value
        if (!s.favoritesReady()) return
        val pkgs = s.favoritePkgList()
        val cached = pkgs.mapNotNull { favCache[it] }
        if (cached.size == pkgs.size) {          // hepsi hazır: anında aç
            showFavorites(cached)
            return
        }
        scope.launch { showFavorites(withContext(Dispatchers.Default) { pkgs.mapNotNull { favApp(it) } }) }
    }

    private fun showFavorites(apps: List<FavApp>) {
        // Yüklenirken müzik/bildirim/asistan geldiyse favorileri açma
        if (apps.isEmpty() || ui.value.content !is IslandContent.Idle) return
        favOpenedAt = android.os.SystemClock.elapsedRealtime()
        update { IslandUiState(IslandContent.Favorites(apps), expanded = true) }
        scheduleCollapse(7_000)
    }

    // ---------------------------------------------------------------- Favori açılış animasyonu
    private val LAUNCH_LEAD_MS = 170L        // ada küçülmeye başladıktan kaç ms sonra siyah ekran çıksın
    private var launchView: ComposeView? = null
    private var launchWm: WindowManager? = null
    private var launching = false

    private fun launchApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        if (intent != null) runCatching { startActivity(intent) }
    }

    /** Ada önce açıktan kapalı hale geçer; animasyon (açıksa) bu geçişle birlikte başlar, ekran kapanınca [open] çalışır. */
    private fun launchWithAnim(animOn: Boolean, atomic: Boolean = false, speed: Float = 1f, open: () -> Unit) {
        collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
        if (!animOn) { open(); return }
        if (launching) return
        launching = true
        // Atom ada: kart adanın kendisinden açıldığı için ada küçülmesini beklemeden başlar
        scope.launch { delay(if (atomic) 0L else LAUNCH_LEAD_MS); startLaunchTransition(open, atomic, speed) }
    }

    /** Stil seçimi: yalnızca atom açıksa atom; klasik de açıksa her seferinde rastgele. */
    private fun pickAtomic(classic: Boolean, atomic: Boolean) = atomic && (!classic || kotlin.random.Random.nextBoolean())

    private fun launchFavorite(pkg: String) {
        val s = settings.value
        launchWithAnim(s.favoriteLaunchAnim, pickAtomic(s.favLaunchClassic, s.favLaunchAtomic), s.favoriteLaunchSpeed) { launchApp(pkg) }
    }

    /** Bildirime dokununca: bildirimin kendi hedefi (ör. ilgili sohbet) açılır; yoksa uygulamanın ana ekranı. */
    private fun openNotification(n: IslandContent.Notif) {
        val open: () -> Unit = {
            val pi = n.intent
            val sent = pi != null && runCatching {
                val opts = if (Build.VERSION.SDK_INT >= 34) {
                    android.app.ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else null
                pi.send(this, 0, null, null, null, null, opts)
            }.isSuccess
            if (!sent) launchApp(n.pkg)
        }
        val s = settings.value
        launchWithAnim(s.notifLaunchAnim, pickAtomic(s.launchClassic, s.launchAtomic), s.launchSpeed, open)
    }

    private fun startLaunchTransition(open: () -> Unit, atomic: Boolean = false, speed: Float = 1f) {
        val s = settings.value
        val sw = wm.currentWindowMetrics.bounds.width()
        val chPx = dp(s.compactHeightDp)
        val spec = LaunchSpec(
            cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
            top = ((cutCenterYPx - chPx / 2).coerceAtLeast(0) + dp(s.offsetYDp)).toFloat(),
            w = dp(compactWidthFor(s, false)).toFloat(),
            h = chPx.toFloat(),
            speed = speed,
            color = islandPanelColor(s, (ui.value.content as? IslandContent.Media)?.artColor ?: 0, opaque = true),
        )
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var view: ComposeView
        view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                val onCov = { runCatching { open() }; Unit }                  // ekran tamamen siyah: uygulamayı aç
                val onFin = { handler.post { if (launchView === view) removeLaunchView() }; Unit }
                if (atomic) AtomicLaunch(spec, onCovered = onCov, onFinished = onFin)
                else LaunchTransition(spec, onCovered = onCov, onFinished = onFin)
            }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Ada penceresiyle aynı katman: erişilebilirlik overlay'i durum çubuğunun da üstünde çizilir
        var host: WindowManager = wm
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(WINDOW_SERVICE) as WindowManager
                h2.addView(view, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                host = h2; added = true
            }
        }
        if (!added) added = runCatching { wm.addView(view, params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) }.isSuccess

        if (!added) {                        // pencere eklenemedi: animasyonsuz aç
            view.disposeComposition()
            launching = false
            runCatching { open() }
            return
        }
        launchView = view; launchWm = host
        // Güvenlik: ne olursa olsun siyah katman ekranda kalmasın
        scope.launch { delay(5_000); if (launchView === view) removeLaunchView() }
    }

    // ---------------------------------------------------------------- Müzikten çıkış animasyonu
    private val SWALLOW_SAFE_MS = 1_500L      // güvenlik: panel bitmese de ada bu sürede küçülür
    private val SWALLOW_HOLD_MS = 140L       // ada büyük kaldıktan sonra eski haline dönme süresi
    private var swallowView: ComposeView? = null
    private var swallowWm: WindowManager? = null

    /** Çalan şarkının uygulamasından çıkılınca: siyah panel adaya küçülür, ada şarkıyı gösterir. */
    private fun onAppSwitched(from: String): Boolean {
        val s = settings.value
        if (!s.musicExitAnim || launching || swallowView != null || stretchView != null) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastExitAt < EXIT_MIN_GAP_MS) return false      // hızlı arka arkaya geçişte animasyon atlanır (ana iş parçacığı yorulmasın)
        val m = lastMedia ?: return false
        if (!m.playing || callKey != null || assistantActive) return false
        val c = ui.value.content
        if (c !is IslandContent.Media && c !is IslandContent.Idle) return false
        if (!isPlayingMediaApp(from)) return false
        lastExitAt = now
        startSwallowTransition()        // panel penceresi önce eklenir: ilk kare gecikmeden çizilsin
        if (swallowView == null) return false
        if (s.musicExitStretch) startStretchOverlay() else swallowIsland()   // uzayan ada ekran katmanında çizilir, ada penceresi büyümez
        exitWatchdog?.cancel()
        exitWatchdog = scope.launch { delay(EXIT_WATCHDOG_MS); endExit() }   // ne olursa olsun animasyon durumu takılı kalmasın
        return true
    }

    /** [pkg], şu an çalan bir medya oturumunun uygulaması mı? (Bildirim erişimi açıksa okunabilir.) */
    private fun isPlayingMediaApp(pkg: String): Boolean {
        // Sistem çağrısı (binder) ana iş parçacığında çalışır: hızlı geçişlerde tekrarını önlemek için kısa süre önbellekte tutulur
        val now = android.os.SystemClock.elapsedRealtime()
        if (pkg == mediaCheckPkg && now - mediaCheckAt < 250L) return mediaCheckVal
        val v = runCatching {
            val msm = getSystemService(android.media.session.MediaSessionManager::class.java)
            msm.getActiveSessions(android.content.ComponentName(this, IslandNotificationListener::class.java))
                .any { it.packageName == pkg && it.playbackState?.state == android.media.session.PlaybackState.STATE_PLAYING }
        }.getOrDefault(false)
        mediaCheckPkg = pkg; mediaCheckAt = now; mediaCheckVal = v
        return v
    }
    private var mediaCheckPkg = ""
    private var mediaCheckAt = 0L
    private var mediaCheckVal = false
    private var lastExitAt = 0L
    private var exitWatchdog: Job? = null
    private val EXIT_MIN_GAP_MS = 450L
    private val EXIT_WATCHDOG_MS = 2_500L

    /** Panel adaya varınca ada kısa süre büyüyüp onu yutar (içerik çizilmez), sonra kapalı müzik haline döner. */
    private fun swallowIsland() {
        val sw = settings.value
        if (!sw.musicExitSwallow || sw.musicExitStretch) return   // uzayan ada açıksa o ayrı katmanda oynar
        val m = lastMedia ?: return
        val c = ui.value.content
        if (!m.playing || (c !is IslandContent.Media && c !is IslandContent.Idle)) return
        if (ui.value.swallow && ui.value.expanded) return   // zaten yutma halinde: pencereyi yeniden kurma (kayma olmasın)
        collapseJob?.cancel()
        update { IslandUiState(m, expanded = true, swallow = true) }
        scheduleCollapse(SWALLOW_SAFE_MS)
    }

    private fun startSwallowTransition() {
        val s = settings.value
        // Panel animasyonu seçimi: yalnızca atom açıksa atom; ikisi de açıksa her çıkışta rastgele
        val useAtomic = s.musicExitAtomic && (!s.musicExitClassic || kotlin.random.Random.nextBoolean())
        val fullStart = useAtomic && s.musicExitAtomicFull
        val sw = wm.currentWindowMetrics.bounds.width()
        val chPx = dp(s.compactHeightDp)
        val spec = LaunchSpec(
            cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
            top = ((cutCenterYPx - chPx / 2).coerceAtLeast(0) + dp(s.offsetYDp)).toFloat(),
            w = dp(compactWidthFor(s, false)).toFloat(),
            h = chPx.toFloat(),
            speed = s.musicExitSpeed,
            color = islandPanelColor(s, lastMedia?.artColor ?: 0, opaque = fullStart),   // tam ekranda panel opak (ekranı örtmeli)
        )
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var view: ComposeView
        view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                val onDone = {
                    handler.post {
                        if (swallowView === view) removeSwallowView()
                        // Panel tamamen silindi: ada bir an daha büyük kalıp küçülür (arkası görünmesin)
                        if (ui.value.swallow && ui.value.expanded) scheduleCollapse(SWALLOW_HOLD_MS)
                    }
                    Unit
                }
                if (useAtomic) AtomicToIsland(spec, onSwallow = { runCatching { swallowIsland() } }, onFinished = onDone, fullStart = fullStart)
                else ShrinkToIsland(spec, onSwallow = { runCatching { swallowIsland() } }, onFinished = onDone)
            }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        var host: WindowManager = wm
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(WINDOW_SERVICE) as WindowManager
                h2.addView(view, params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                host = h2; added = true
            }
        }
        if (!added) added = runCatching { wm.addView(view, params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) }.isSuccess
        if (!added) { view.disposeComposition(); return }
        swallowView = view; swallowWm = host
        // Güvenlik: ne olursa olsun siyah katman ekranda kalmasın
        scope.launch { delay(4_000); if (swallowView === view) removeSwallowView() }
    }

    private fun removeSwallowView() {
        val v = swallowView
        if (v != null) {
            runCatching { (swallowWm ?: wm).removeView(v) }
            v.disposeComposition()
            v.setViewTreeLifecycleOwner(null)
            v.setViewTreeSavedStateRegistryOwner(null)
        }
        swallowView = null; swallowWm = null
        if (exitHold) { exitHold = false; refreshMediaVisibility() }   // panel bitti: müzik artık gösterilebilir
    }

    // ---------------------------------------------------------------- Uzayan ada katmanı (yeni yutma animasyonu)
    private var stretchView: ComposeView? = null
    private var stretchWm: WindowManager? = null

    private fun overlayParams(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun startStretchOverlay() {
        if (stretchView != null) return
        val s = settings.value
        val sw = wm.currentWindowMetrics.bounds.width()
        val chPx = dp(s.compactHeightDp)
        val spec = LaunchSpec(
            cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
            top = ((cutCenterYPx - chPx / 2).coerceAtLeast(0) + dp(s.offsetYDp)).toFloat(),
            w = dp(compactWidthFor(s, false)).toFloat(),
            h = chPx.toFloat(),
            speed = s.musicExitSpeed,
            color = islandPanelColor(s, lastMedia?.artColor ?: 0),
        )
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        lateinit var view: ComposeView
        view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                val onDone = { handler.post { if (stretchView === view) removeStretchView() }; Unit }
                IslandStretch(spec, onFinished = onDone)
            }
        }
        var host: WindowManager = wm
        var added = false
        IslandAccessibilityService.instance?.let { a11y ->
            runCatching {
                val h2 = a11y.getSystemService(WINDOW_SERVICE) as WindowManager
                h2.addView(view, overlayParams(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY))
                host = h2; added = true
            }
        }
        if (!added) added = runCatching { wm.addView(view, overlayParams(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)) }.isSuccess
        if (!added) { view.disposeComposition(); return }
        stretchView = view; stretchWm = host
        scope.launch { delay(3_000); if (stretchView === view) removeStretchView() }
    }

    private fun removeStretchView() {
        val v = stretchView
        if (v != null) {
            runCatching { (stretchWm ?: wm).removeView(v) }
            v.disposeComposition()
            v.setViewTreeLifecycleOwner(null)
            v.setViewTreeSavedStateRegistryOwner(null)
        }
        stretchView = null; stretchWm = null
    }

    /** Çıkış animasyonunu tamamen bitirir: katmanlar kalkar, yutma durumu ve gizleme bayrağı sıfırlanır, müzik yeniden gösterilir. */
    private fun endExit() {
        removeStretchView()
        removeSwallowView()               // exitHold'u da sıfırlar
        if (ui.value.swallow) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
        else refreshMediaVisibility()
    }

    private fun abortExit() { exitWatchdog?.cancel(); endExit() }

    private fun removeLaunchView() {
        val v = launchView
        if (v != null) {
            runCatching { (launchWm ?: wm).removeView(v) }
            v.disposeComposition()
            v.setViewTreeLifecycleOwner(null)
            v.setViewTreeSavedStateRegistryOwner(null)
        }
        launchView = null; launchWm = null; launching = false
    }

    private fun onCommand(c: MediaCommand) {
        IslandBus.send(c)
        if (ui.value.expanded) scheduleCollapse(8_000)
    }

    /** Panelin sağ alt köşesindeki yayın simgesi: sistemin ses / medya çıkışı paneli. */
    private fun openOutputPanel() {
        runCatching {
            startActivity(Intent(android.provider.Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (ui.value.expanded) scheduleCollapse(8_000)
    }

    private suspend fun iconFor(pkg: String): ImageBitmap? {
        iconCache.get(pkg)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            runCatching { packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull()
        }
        if (bmp != null) iconCache.put(pkg, bmp)
        return bmp
    }

    // ---------------------------------------------------------------- Pencere
    private fun detectCutout() {
        val metrics = wm.currentWindowMetrics
        val display = getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
        val rect = (metrics.windowInsets.displayCutout ?: display?.cutout)?.boundingRectTop
        if (rect != null && !rect.isEmpty) {
            cutCenterXOffsetPx = rect.centerX() - metrics.bounds.width() / 2
            cutCenterYPx = rect.centerY()
        } else {
            cutCenterXOffsetPx = 0
            cutCenterYPx = dp(22f)
        }
    }

    /**
     * Erişilebilirlik servisi bağlıysa ada TYPE_ACCESSIBILITY_OVERLAY ile çizilir: bu pencere türü
     * durum çubuğunun ÜSTÜNDE durur ve dokunuş alır. Değilse (ya da hata olursa) eski
     * TYPE_APPLICATION_OVERLAY'e döner; o zaman durum çubuğu bölgesinde dokunuş çalışmaz.
     */
    private fun attachOverlay() {
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            try {
                buildOverlay(a11y, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                return
            } catch (e: Exception) {
                detachOverlay()
            }
        }
        buildOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
    }

    /** attachOverlay'i hiçbir hata dışarı sızmayacak şekilde çağırır. */
    private fun safeAttachOverlay() {
        runCatching { attachOverlay() }.onFailure {
            runCatching { detachOverlay() }
            runCatching { buildOverlay(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) }
        }
    }

    private fun reattach() {
        runCatching { detachOverlay() }
        runCatching { detachStatusCover() }
        runCatching { detachEdgeGlow() }
        // Erişilebilirlik bağlantısı değişince dış söz penceresinin türü de değişir: yeniden kur.
        // Önce durum çubuğu örtüsü, sonra söz penceresi, en son ada eklenir; böylece ada her zaman üstte kalır ve sözler adanın "içinden" çıkar.
        runCatching { detachOutside() }
        runCatching { attachStatusCover() }
        runCatching { attachEdgeGlow() }
        runCatching { syncOutside(settings.value) }
        safeAttachOverlay()
        scope.launch { syncWindow(WinKey(kindOf(ui.value.content), ui.value.expanded, ui.value.compactLyrics, settings.value, ui.value.swallow, sideKey(ui.value))) }
    }

    // ---------------------------------------------------------------- Durum çubuğu örtüsü
    /**
     * "Durum çubuğunu gizle": durum çubuğunun üstüne dokunmatik olmayan bir pencere çizilir.
     * - Cihaz pencere bulanıklığını destekliyorsa (Android 12+): buzlu cam. Pencerenin arkası bulanıklaştırılır,
     *   üstüne hafif koyu bir ton biner; durum çubuğu simgeleri okunmaz hale gelir. "Cam efekti" ayarından bağımsızdır.
     * - Desteklenmiyorsa: düz siyah.
     * Yalnızca erişilebilirlik servisi bağlıyken çalışır (TYPE_ACCESSIBILITY_OVERLAY durum çubuğunun üstünde durur).
     */
    private var statusCoverDialog: Dialog? = null
    private var statusCoverView: View? = null
    private var coverAnim: android.animation.ValueAnimator? = null
    private var coverT = 0f            // 0 = görünmez, 1 = tam
    private var coverBlurPx = 0        // tam görünürken uygulanacak bulanıklık (0 = düz siyah mod)

    private fun statusBarHeightPx(): Int {
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val sb = if (id > 0) resources.getDimensionPixelSize(id) else dp(24f)
        return maxOf(sb, cutCenterYPx * 2)
    }

    private fun coverIsGlass(): Boolean =
        Build.VERSION.SDK_INT >= 31 && wm.isCrossWindowBlurEnabled

    /** Örtünün rengini ve bulanıklığını moda göre ayarlar (cam: ince koyu ton + bulanıklık, değilse düz siyah). */
    private fun styleCover() {
        val glass = coverIsGlass()
        coverBlurPx = if (glass) COVER_BLUR_PX else 0
        statusCoverView?.setBackgroundColor(Color.argb(if (glass) COVER_GLASS_TINT else 255, 0, 0, 0))
    }

    private fun applyCover(t: Float) {
        coverT = t
        statusCoverView?.alpha = t
        if (Build.VERSION.SDK_INT >= 31) {
            statusCoverDialog?.window?.setBackgroundBlurRadius((coverBlurPx * t).toInt())
        }
    }

    private fun attachStatusCover() {
        val a11y = IslandAccessibilityService.instance ?: return
        val v = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        val d = Dialog(ContextThemeWrapper(a11y, android.R.style.Theme_Translucent_NoTitleBar))
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        d.setContentView(v, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setGravity(Gravity.TOP or Gravity.START)
            setDecorFitsSystemWindows(false)
            decorView.setPadding(0, 0, 0, 0)
            val lp = WindowManager.LayoutParams().also { it.copyFrom(attributes) }
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = statusBarHeightPx()
            lp.x = 0; lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        d.window?.decorView?.let { stripInsets(it) }
        if (!runCatching { d.show() }.isSuccess) return
        d.window?.decorView?.let { stripInsets(it) }
        statusCoverDialog = d; statusCoverView = v
        // Pencere yeniden kurulduysa mevcut duruma göre hemen uygula
        styleCover()
        applyCover(if (settings.value.hideStatusBar && ui.value.content is IslandContent.Status) 1f else 0f)
    }

    private fun setStatusCover(show: Boolean) {
        if (statusCoverView == null) return
        coverAnim?.cancel()
        if (show) styleCover()
        coverAnim = android.animation.ValueAnimator.ofFloat(coverT, if (show) 1f else 0f).apply {
            duration = if (show) 200 else 220
            addUpdateListener { applyCover(it.animatedValue as Float) }
            start()
        }
    }

    private fun detachStatusCover() {
        coverAnim?.cancel(); coverAnim = null
        runCatching { statusCoverDialog?.dismiss() }
        statusCoverDialog = null; statusCoverView = null
        coverT = 0f
    }

    // ---------------------------------------------------------------- Ekran kenarı parlaması (asistan)
    private val glowActive = MutableStateFlow(false)
    private var glowView: ComposeView? = null
    private var glowLp: WindowManager.LayoutParams? = null
    private var glowWm: WindowManager? = null
    private var glowTries = 0

    /** Ekranın gerçek köşe yarıçapı (px); alınamazsa 36dp. Çok yuvarlak görünmesin diye 40dp ile sınırlı. */
    private fun screenCornerPx(): Float {
        val cap = dp(40f).toFloat()
        val real = if (Build.VERSION.SDK_INT >= 31) {
            runCatching {
                wm.currentWindowMetrics.windowInsets
                    .getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
            }.getOrNull()
        } else null
        return minOf(real ?: dp(36f).toFloat(), cap)
    }

    /**
     * Tam ekran, dokunmatik olmayan, şeffaf pencere (LaunchTransition penceresiyle aynı kurulum):
     * durum çubuğu dahil tüm ekranı kaplar. Sistem pencereyi aşağı iterse ölçüp geri çekeriz.
     */
    private fun attachEdgeGlow() {
        val a11y = IslandAccessibilityService.instance ?: return
        val corner = screenCornerPx()
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent { ScreenEdgeGlow(glowActive, corner) }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val host = a11y.getSystemService(WINDOW_SERVICE) as WindowManager
        if (!runCatching { host.addView(view, lp) }.isSuccess) { view.disposeComposition(); return }
        glowView = view; glowLp = lp; glowWm = host
        glowTries = 0
        view.postDelayed(glowCalibrate, 300)
    }

    /** Pencere ekranın en üstünden başlamıyorsa (durum çubuğu payı) farkı kadar yukarı çeker ve boyunu uzatır. */
    private val glowCalibrate = object : Runnable {
        override fun run() {
            val v = glowView ?: return
            val lp = glowLp ?: return
            val host = glowWm ?: return
            if (!v.isAttachedToWindow) return
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dy = loc[1]
            if (abs(dy) <= 1 || glowTries >= 3) return
            glowTries++
            val screenH = host.currentWindowMetrics.bounds.height()
            lp.y -= dy
            lp.height = screenH + abs(lp.y)
            runCatching { host.updateViewLayout(v, lp) }
            v.postDelayed(this, 250)
        }
    }

    private fun detachEdgeGlow() {
        val v = glowView ?: return
        v.removeCallbacks(glowCalibrate)
        runCatching { (glowWm ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        glowView = null; glowLp = null; glowWm = null
    }

    private fun detachOverlay() {
        composeView?.removeCallbacks(calibrateRunnable)
        composeView?.let { v ->
            v.disposeComposition()
            v.setViewTreeLifecycleOwner(null)
            v.setViewTreeSavedStateRegistryOwner(null)
        }
        runCatching { dialog?.dismiss() }
        dialog = null
        composeView = null
        calibDy = 0; calibTries = 0
        curW = 1; curH = 1
    }

    private fun buildOverlay(host: Context, type: Int) {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                IslandRoot(
                    state = ui, settingsFlow = settings, lyricsFlow = lyrics,
                    onTap = ::onPillTap, onCommand = ::onCommand, onOutput = ::openOutputPanel,
                    onLongPress = ::onPillLongPress, onLaunch = ::launchFavorite,
                    onOpenMusic = ::openMusicApp, onSwipe = ::onPillSwipe,
                    onAnswer = ::onCallAnswer, onDecline = ::onCallDecline, onSpeaker = ::onCallSpeaker,
                    onRecStop = ::onRecStop,
                    onNotifSwipeSide = ::onNotifSwipeSide, onSideTap = ::onSideTap, onSidePress = ::onSidePress,
                )
            }
        }
        composeView = view

        // Dialog penceresi: Window.setBackgroundBlurRadius SADECE pencere sınırlarını bulanıklaştırır
        // (WindowManager FLAG_BLUR_BEHIND ise tüm ekranı bulanıklaştırıyordu).
        val d = Dialog(ContextThemeWrapper(host, android.R.style.Theme_Translucent_NoTitleBar))
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        d.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        d.window?.apply {
            setType(type)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setDecorFitsSystemWindows(false)
            decorView.setPadding(0, 0, 0, 0)
            decorView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            val lp = WindowManager.LayoutParams().also { it.copyFrom(attributes) }
            lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.fitInsetsTypes = 0          // pencere çerçevesi durum çubuğuna göre kaymasın
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        d.window?.decorView?.let { stripInsets(it) }   // show'dan ÖNCE: ilk inset dağıtımı da yutulur
        d.show()
        d.window?.decorView?.let { stripInsets(it) }   // show'dan SONRA: kalan dolguları sıfırla
        dialog = d
    }

    /** Dialog'un decor/içerik katmanlarına eklediği tüm sistem çubuğu dolgularını sıfırlar. */
    private fun stripInsets(dv: View) {
        fun zero() {
            dv.setPadding(0, 0, 0, 0)
            (dv as? ViewGroup)?.let { g ->
                for (i in 0 until g.childCount) g.getChildAt(i).setPadding(0, 0, 0, 0)
            }
            dv.findViewById<View>(android.R.id.content)?.setPadding(0, 0, 0, 0)
        }
        dv.setOnApplyWindowInsetsListener { _, _ ->
            zero()
            android.view.WindowInsets.CONSUMED
        }
        zero()
        dv.requestApplyInsets()
    }

    /**
     * Pencere küçülürken sol kenarı da değiştiği için sistem pencereyi eski konumundan yeni konumuna
     * KAYDIRARAK taşıyabiliyor (ada durum çubuğunun solundan ortaya kayıyormuş gibi görünür).
     * "Taşıma animasyonu" kapatılırsa pencere yeni yerine doğrudan geçer. Üst düzey API yoksa
     * (eski Android) gizli bayrak denenir; hiçbiri çalışmazsa sessizce geçilir.
     */
    private fun disableMoveAnimation(lp: WindowManager.LayoutParams) {
        val viaApi = runCatching {
            WindowManager.LayoutParams::class.java
                .getMethod("setCanPlayMoveAnimation", Boolean::class.javaPrimitiveType)
                .invoke(lp, false)
        }.isSuccess
        if (viaApi) return
        runCatching {
            val f = WindowManager.LayoutParams::class.java.getDeclaredField("privateFlags")
            f.isAccessible = true
            f.setInt(lp, f.getInt(lp) or 0x00000040)   // PRIVATE_FLAG_NO_MOVE_ANIMATION
        }
    }

    private fun setGeometry(w: Int, h: Int, x: Int, y: Int, recalibrate: Boolean = true) {
        val win = dialog?.window ?: return
        val big = w > 1 && h > 1
        wantX = x; wantY = y
        val ay = if (big) y - calibDy else y
        val ah = if (big) h + calibDy else h
        val cur = win.attributes
        if (cur.width == w && cur.height == ah && cur.x == x && cur.y == ay) return
        // Yeni nesne: aynı nesneyi geri atamak WindowManager güncellemesini tetiklemez
        val lp = WindowManager.LayoutParams().also { it.copyFrom(cur) }
        lp.width = w; lp.height = ah; lp.x = x; lp.y = ay
        disableMoveAnimation(lp)
        // Pencere KÜÇÜLÜRKEN ve yatay konumu da DEĞİŞİRKEN sistem eski kareyi bir an yanlış yerde
        // gösterebiliyor (yana kayma): yalnızca o durumda içeriği kısa süre gizleyip geri getiriyoruz.
        // Konum aynıysa (ada pencerenin ortasında kalıyorsa) gizlemeye gerek yok; gizlemek
        // kapanırken adanın bir an kaybolup geri gelmesine (göz kırpma) yol açıyordu.
        if (big && curW > 1 && w < curW && cur.x != x) {
            composeView?.let { v ->
                v.animate().cancel()
                v.removeCallbacks(showAgain)
                resizeListener?.let { v.removeOnLayoutChangeListener(it) }
                v.alpha = 0f
                // Sistem yeni boyutu gerçekten uygulayınca (yerleşim değişince) kısa süre sonra göster;
                // böylece eski/yanlış konumdaki kareler görünmez ve ada yana kayarak gelmez.
                val l = object : View.OnLayoutChangeListener {
                    override fun onLayoutChange(
                        view: View, left: Int, top: Int, right: Int, bottom: Int,
                        oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
                    ) {
                        if (right - left == oldRight - oldLeft) return
                        view.removeOnLayoutChangeListener(this)
                        if (resizeListener === this) resizeListener = null
                        view.removeCallbacks(showAgain)
                        view.postDelayed(showAgain, RESIZE_HIDE_MS)
                    }
                }
                resizeListener = l
                v.addOnLayoutChangeListener(l)
                v.postDelayed(showAgain, 250)      // güvenlik: yerleşim değişmezse yine de göster
            }
        }
        win.attributes = lp
        curW = w; curH = h
        if (big) {
            if (recalibrate) calibTries = 0
            composeView?.let { it.removeCallbacks(calibrateRunnable); it.postDelayed(calibrateRunnable, 150) }
        }
    }

    /**
     * Ada gerçekte olması gereken yerde mi? Değilse (sistem içeriği aşağı kaydırdıysa)
     * pencereyi kayma kadar yukarı alıp yükseltir; böylece ada ne olursa olsun tam görünür.
     */
    private fun calibrate() {
        val v = composeView ?: return
        if (curW <= 1 || curH <= 1 || !v.isAttachedToWindow) return
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val dy = loc[1] - wantY
        if (abs(dy) <= 2 || calibTries >= 3) return
        calibTries++
        calibDy = (calibDy + dy).coerceIn(-300, 600)
        setGeometry(curW, curH, wantX, wantY, recalibrate = false)
    }

    /** Boşta görünen adaya dokunuş yakalamasın: durum çubuğu altından normal çalışsın. */
    private fun setTouchable(on: Boolean) {
        val win = dialog?.window ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val blocked = (win.attributes.flags and flag) != 0
        if (on && blocked) win.clearFlags(flag)
        else if (!on && !blocked) win.addFlags(flag)
    }

    private fun setBlur(on: Boolean, s: IslandSettings) {
        if (Build.VERSION.SDK_INT < 31) return
        val r = if (on && s.glassEnabled && s.blurRadiusPx > 0 && wm.isCrossWindowBlurEnabled) s.blurRadiusPx else 0
        dialog?.window?.setBackgroundBlurRadius(r)
    }

    /**
     * Pencereyi SABİT piksel boyutuna getirir (WRAP_CONTENT yok): konum kayması ve
     * kare başına pencere yeniden ölçümü ortadan kalkar. Blur sadece pencere ile
     * ada aynı boyuttayken (animasyon bittiğinde) açılır; böylece sadece ada bölgesi bulanıklaşır.
     */
    private suspend fun syncWindow(k: WinKey) {
        val s = k.s
        val pad = dp(s.windowPadDp())                    // parıltı / zıplama taşması için pay
        val cw = dp(if (k.kind == 6 || k.kind == 10) maxOf(CALL_COMPACT_W_DP, s.compactWidthDp) else compactWidthFor(s, k.lyr && k.kind == 2)); val ch = dp(s.compactHeightDp)
        val music = k.kind == 2
        val assistant = k.kind == 3
        val ew = dp(
            when {
                assistant -> s.assistantWidthDp
                k.kind == 8 -> maxOf(CHARGE_PILL_W_DP, s.compactWidthDp)     // şarj: kompakt pill
                k.kind == 9 || k.kind == 10 -> recExpandedWidthDp(s, wm.currentWindowMetrics.bounds.width() / density)   // ekran kaydı
                else -> s.expandedWidthDp
            }
        )
        val favN = (ui.value.content as? IslandContent.Favorites)?.apps?.size ?: 0
        val eh = dp(
            when {
                assistant -> s.assistantHeightDp
                k.kind == 5 -> 74f          // gelen arama
                k.kind == 6 -> 88f          // aktif arama
                k.kind == 8 -> chargePillHeightDp(s)   // şarj
                k.kind == 9 || k.kind == 10 -> REC_PILL_H_DP   // ekran kaydı
                k.kind == 4 -> s.favoritesHeightDp(favN)
                else -> s.expandedHeightDp + if (music) s.musicExtraDp() else 0f
            }
        )

        val screenW = wm.currentWindowMetrics.bounds.width()
        val baseX = cutCenterXOffsetPx + dp(s.offsetXDp)
        val y = (cutCenterYPx - ch / 2).coerceAtLeast(0) + dp(s.offsetYDp) - pad
        val maxShift = ((screenW - ew - 2 * pad) / 2).coerceAtLeast(0)
        val xExp = baseX.coerceIn(-maxShift, maxShift)
        val settle = s.settleMs()
        // Yan baloncuklar varsa pencere iki yanda (simetrik) baloncuk payı kadar genişler; ada pencerenin ortasında kalır
        // Genişlemişken de baloncuk varsa pencere iki yanda pay ayırır (baloncuk genişleyen panelin yanında durur)
        val sideExtra = if (k.side > 0 && k.kind != 0) dp(sideExtraDp(s)) else 0
        val blurOk = pad == 0 && sideExtra == 0   // pay / yan baloncuk varsa pencere adadan büyük olur; o zaman blur kapalı tutulur

        // Boştayken de dokunulabilir: favori uygulamalar özelliği açıksa (dokunma / basılı tutma algılanır)
        setTouchable((k.kind != 0 && !assistant) || (k.kind == 0 && s.favoritesReady()))
        when {
            k.kind == 0 && s.showIdle -> {
                // Boşta da görünür: küçük ada boyutunda, dokunuşsuz
                setBlur(false, s)
                if (curW > cw + 2 * pad || curH > ch + 2 * pad) delay(settle)
                setGeometry(cw + 2 * pad, ch + 2 * pad, baseX, y)
                if (blurOk) setBlur(true, s)
            }
            k.kind == 0 -> { setBlur(false, s); setGeometry(1, 1, baseX, y) }
            k.expanded && k.swallow && music -> {
                // Yutma efekti: pencere, büyüyen adanın boyutunda (tam açık panel kadar büyük değil)
                val sz = swallowSizeDp(compactWidthFor(s, false), s.compactHeightDp, s.expandedWidthDp, s.expandedHeightDp + s.musicExtraDp())
                setBlur(false, s)
                val sw2 = dp(sz.first) + 2 * pad
                val maxSh = ((screenW - sw2) / 2).coerceAtLeast(0)
                setGeometry(sw2, dp(sz.second) + 2 * pad, baseX.coerceIn(-maxSh, maxSh), y)
            }
            k.expanded && assistant && s.assistantFullWidth -> {
                // Ada kutusu yok: pencere ekran genişliğinde, ada ile aynı üst hizada
                setBlur(false, s)
                setGeometry(screenW, eh, 0, y + pad)
            }
            k.expanded -> {
                setBlur(false, s)
                val winW = ew + 2 * pad + 2 * sideExtra
                val maxShSide = ((screenW - winW) / 2).coerceAtLeast(0)
                val xWin = if (sideExtra > 0) baseX.coerceIn(-maxShSide, maxShSide) else xExp
                setGeometry(winW, eh + 2 * pad, xWin, y)
                winSide = sideExtra
                if (blurOk && !assistant) { delay(settle); setBlur(true, s) }
            }
            else -> {
                setBlur(false, s)
                // Müzik adasında pencere küçülmeden önce biraz daha beklenir: hızlı aç-kapa'da pencere her seferinde
                // yeniden boyutlanmasın (asıl takılma kaynağı). Bu sürede yeniden açılırsa bu bekleme iptal olur.
                // Yan baloncuklar birleşirken (sıvı animasyonu) pencere de aynı şekilde geç küçülür
                val hold = if ((k.kind == 2 && !k.swallow) || winSide > 0) WINDOW_HOLD_MS else 0L
                val wantW = cw + 2 * pad + 2 * sideExtra
                if (curW > wantW || curH > ch + 2 * pad) delay(settle + hold)
                setGeometry(wantW, ch + 2 * pad, baseX, y)
                winSide = sideExtra
                if (blurOk) setBlur(true, s)
            }
        }
        if (k.kind == 0 || (k.expanded && sideExtra == 0)) winSide = 0
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Dynamic Island", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "island")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Dynamic Island çalışıyor")
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_MIN).build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    // ---------------------------------------------------------------- Temizlik
    // ---------------------------------------------------------------- Adanın dışındaki şarkı sözleri
    /** Ayrı, dokunmatik olmayan bir overlay penceresi: sözler adayı açmadan da görünür. */
    private val outsideOrigin = kotlinx.coroutines.flow.MutableStateFlow(LyricOrigin())

    private fun syncOutside(s: IslandSettings) {
        if (!(s.lyricsEnabled && s.lyricsOutside)) { detachOutside(); return }
        val pad = dp(24f)
        val fs = resources.configuration.fontScale
        val w = dp(s.lyricsOutsideWidthDp) + 2 * pad
        val contentH = dp(s.lyricsOutsideSizeSp * fs * 1.3f * s.lyricsOutsideLines)
        val x = dp(s.lyricsOutsideXDp)
        var y = dp(s.lyricsOutsideYDp) - pad
        var h = contentH + 2 * pad

        // "Adadan çıkış" animasyonları: sözler adadan başlayıp yerine gelir. Pencere adaya kadar yukarı uzatılır
        // (aksi halde pencere sınırında kesilirdi); söz alanı altta ve eski konumunda kalır.
        val islandCy = cutCenterYPx + dp(s.offsetYDp)
        val textCy = dp(s.lyricsOutsideYDp) + contentH / 2
        if (s.lyricsFromIsland()) {
            val wantTop = (islandCy - dp(10f)).coerceAtLeast(0)
            val extra = (y - wantTop).coerceAtLeast(0)
            y -= extra; h += extra
            val screenW = wm.currentWindowMetrics.bounds.width()
            outsideOrigin.value = LyricOrigin(
                dx = (cutCenterXOffsetPx + dp(s.offsetXDp) - x).toFloat(),
                dy = (islandCy - textCy).toFloat(),
                // Adanın ekran üzerindeki merkezi: söz alanının GERÇEK konumu ölçülüp farkı oradan hesaplanır
                ix = screenW / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                iy = islandCy.toFloat(),
            )
        } else {
            outsideOrigin.value = LyricOrigin()
        }

        outsideWantY = y
        val existing = outsideView
        val lp = outsideLp
        if (existing != null && lp != null) {
            val ey = y - outsideDy; val eh = h + outsideDy
            if (lp.width != w || lp.height != eh || lp.x != x || lp.y != ey) {
                lp.width = w; lp.height = eh; lp.x = x; lp.y = ey
                runCatching { (outsideWm ?: wm).updateViewLayout(existing, lp) }
                scheduleOutsideCalibrate()
            }
            return
        }

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent { OutsideLyricsRoot(ui, settings, lyrics, mediaState, outsideOrigin) }
        }
        fun params(type: Int) = WindowManager.LayoutParams(
            w, h, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            this.x = x; this.y = y
            fitInsetsTypes = 0          // durum çubuğu payı kadar aşağı itilmesin
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        // Normal uygulama overlay'i durum çubuğunun ALTINDA kalır: sözler adaya (durum çubuğu hizası) hiç ulaşamazdı.
        // Erişilebilirlik overlay'i ise adanın kendisi gibi durum çubuğunun üstünde çizilir.
        var host: WindowManager = wm
        var lp2 = params(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        var added = false
        val a11y = IslandAccessibilityService.instance
        if (a11y != null) {
            runCatching {
                val h2 = a11y.getSystemService(WINDOW_SERVICE) as WindowManager
                val p2 = params(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
                h2.addView(view, p2)
                host = h2; lp2 = p2; added = true
            }
        }
        if (!added) added = runCatching { wm.addView(view, lp2) }.isSuccess

        if (added) {
            outsideView = view; outsideLp = lp2; outsideWm = host
            outsideDy = 0
            scheduleOutsideCalibrate()
            // Ada (varsa) en üstte kalsın: yeni eklenen söz penceresi adanın üstüne binmesin
            if (composeView != null) bringIslandToFront()
        } else {
            view.disposeComposition()
        }
    }

    /** Adayı söz penceresinden sonra yeniden ekler (ikisi de erişilebilirlik overlay'i ise sonradan eklenen üstte olur). */
    private fun bringIslandToFront() {
        runCatching { detachOverlay() }
        safeAttachOverlay()
        scope.launch { syncWindow(WinKey(kindOf(ui.value.content), ui.value.expanded, ui.value.compactLyrics, settings.value, ui.value.swallow, sideKey(ui.value))) }
    }

    /** Sistem pencereyi istediğimizden farklı bir yere koyduysa (durum çubuğu payı vb.) farkı kadar geri çeker. */
    private val outsideCalibrate = object : Runnable {
        override fun run() {
            val v = outsideView ?: return
            val lp = outsideLp ?: return
            val host = outsideWm ?: return
            if (!v.isAttachedToWindow) return
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val dy = loc[1] - outsideWantY
            if (abs(dy) <= 2 || outsideTries >= 3) return
            outsideTries++
            outsideDy += dy
            lp.y -= dy; lp.height += dy
            runCatching { host.updateViewLayout(v, lp) }
            v.postDelayed(this, 200)
        }
    }

    private fun scheduleOutsideCalibrate() {
        outsideTries = 0
        outsideView?.let { it.removeCallbacks(outsideCalibrate); it.postDelayed(outsideCalibrate, 250) }
    }

    private fun detachOutside() {
        val v = outsideView ?: return
        v.removeCallbacks(outsideCalibrate)
        runCatching { (outsideWm ?: wm).removeView(v) }
        v.disposeComposition()
        v.setViewTreeLifecycleOwner(null)
        v.setViewTreeSavedStateRegistryOwner(null)
        outsideView = null; outsideLp = null; outsideWm = null
        outsideDy = 0
    }

    override fun onDestroy() {
        IslandAccessibilityService.onChanged = null
        lyricsJob?.cancel()
        callTickJob?.cancel(); callEndJob?.cancel()
        runCatching { sideCall.hide() }
        runCatching { removeLaunchView() }
        runCatching { removeSwallowView() }
        runCatching { removeStretchView() }
        exitWatchdog?.cancel()
        runCatching { detachStatusCover() }
        runCatching { detachEdgeGlow() }
        detachOutside()
        collapseJob?.cancel()
        scope.cancel()
        detachOverlay()
        iconCache.evictAll()
        super.onDestroy()
    }

}

/** Ana Sayfa > Dene bölümündeki sahte arama olayının anahtarı. */
const val DEMO_CALL_KEY = "demo_call"

/** Dokunup açılan aktif arama ekranının kendiliğinden küçülme süresi (ms). */
const val CALL_AUTO_COLLAPSE_MS = 5_000L

/** Durum çubuğu örtüsü (bulanık mod): arka plan bulanıklığı (px) ve koyu ton (0..255). */
private const val COVER_BLUR_PX = 120
private const val COVER_GLASS_TINT = 110
