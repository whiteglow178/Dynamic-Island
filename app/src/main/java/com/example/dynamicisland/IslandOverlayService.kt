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
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
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
    private val fxWindow by lazy { IslandFxWindow(this, this, this, wm) }   // adadan düşen efekt parçaları (tam ekran, dokunmatik olmayan)
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
    private var dotWin = false     // nokta modu açık mı (pencere küçülürken gizleme eşiği için)

    private data class WinKey(val kind: Int, val expanded: Boolean, val lyr: Boolean, val s: IslandSettings, val swallow: Boolean = false, val side: Int = 0, val stackW: Int = 0, val stackH: Int = 0, val extraH: Int = 0)

    // ---- Çoklu olay: en yeni olay ortada, öncekiler sağ / sol baloncukta
    private enum class Bg { Media, Rec, Call, Shazam, Timer, Notif, Privacy }   // Notif: yalnızca nokta modunda; Shazam / Timer: 4 ya da 5 nokta modunda   // Shazam / Timer yalnızca 4 ya da 5 nokta modunda arka plan olayı sayılır
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
        // Üç nokta modunda kapalı ada, ortadaki noktanın çapında daire olur (kaydedilen ayarlar değişmez, yalnızca bu kopya)
        settings = SettingsRepository(applicationContext).flow
            .map { it.effective() }
            .stateIn(scope, SharingStarted.Eagerly, IslandSettings())
        // Çeviri paneli: panelden kapatma isteği + panelde seçilen dilleri ayarlara kaydetme
        TranslateSession.closeRequest = ::closeTranslate
        TranslateSession.onLangsChanged = { src, tgt -> scope.launch { runCatching { saveTranslateLangs(src, tgt) } } }
        // Arka plandan (örn. sistem START_STICKY ile yeniden başlatınca) foreground'a geçiş reddedilebilir;
        // istisna yakalanmazsa süreç çöker ve erişilebilirlik servisi "hatalı çalışıyor" olarak işaretlenir.
        if (!runCatching { startAsForeground() }.isSuccess) { stopSelf(); return }
        runCatching { detectCutout() }
        scope.launch {
            androidx.compose.runtime.snapshotFlow { FxBridge.count > 0 }.distinctUntilChanged().collect { on ->
                runCatching { if (on) fxWindow.attach() else fxWindow.detach() }
            }
        }
        runCatching { statusCover.attach() }      // ada penceresinden ÖNCE eklenir: ada her zaman üstte kalır
        runCatching { edgeGlow.attach() }         // kenar parlaması da adadan ÖNCE eklenir (ada üstte kalır)
        safeAttachOverlay()
        // Erişilebilirlik servisi açılıp kapanınca pencere türünü yeniden seç
        IslandAccessibilityService.onChanged = { runCatching { reattach() } }
        scope.launch { IslandBus.events.collect(::handle) }
        runCatching { privacy.start() }
        runCatching { ringer.start() }
        runCatching { sfxTriggers.start() }     // şarja takma / çıkarma / sessize alma sesleri
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
        scope.launch { settings.collect { s -> appHide.update(); privacy.refresh(); updateInMusicApp(); ensureLyrics(); update { it }; refreshMediaVisibility(); syncOutside(s); favorites.preload(s) } }
        scope.launch { lyrics.collect { update { it } } }
        // Kenar Cevapla / Reddet tuşları ayarı değişince (arama sürerken) güncelle
        scope.launch { SideCallStore.flow(applicationContext).collect { call.syncSideButtons() } }
        // Durum görünümü (şarj, zil modu...) açıkken durum çubuğunu kapat
        scope.launch {
            combine(ui, settings) { u, s -> s.hideStatusBar && u.content is IslandContent.Status }
                .distinctUntilChanged()
                .collect { statusCover.set(it) }
        }
        // Pencere geometrisi yalnızca durum/ayar DEĞİŞİNCE güncellenir (kare başına değil)
        scope.launch {
            combine(ui, settings, TranslateSession.bodyDp) { u, s, _ -> winKey(u, s) }
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
        IslandContent.Translate -> 12
        is IslandContent.Call -> if (c.isIncoming) 5 else 6
        is IslandContent.Status -> if (c.isPill()) 8 else 7
        is IslandContent.ScreenRec -> if (c.phase == RecPhase.Countdown || c.phase == RecPhase.Processing) 10 else 9
        is IslandContent.Shazam -> 11
    }

    // ---------------------------------------------------------------- Olay mantığı
    private fun handle(e: IslandEvent) {
        when (e) {
            is IslandEvent.AssistantChanged -> onAssistant(e.active)
            is IslandEvent.AppSwitched -> {
                if ((swallowSlot.shown || stretchSlot.shown) && isPlayingMediaApp(e.to)) abortExit()   // müzik uygulamasına geri dönüldü
                val wasMusic = inMusicApp
                appHide.update(e.to)           // seçili uygulamada ada gizlenir
                updateInMusicApp(e.to)          // önce "müzik uygulamasında mıyım" güncellenir
                val started = onAppSwitched(e.from)   // çıkış animasyonu varsa ada yutma durumuna geçer (gizleme atlanır)
                if (started) exitHold = settings.value.hideInMusicApp && wasMusic   // panel sürerken gelen ikinci olay gizlemeyi bozmasın
                refreshMediaVisibility()        // animasyon başlamadıysa müzik göster / gizle
            }
            is IslandEvent.Call -> { call.onCall(e); onCallSfx() }
            is IslandEvent.CallEnded -> { call.onEnded(e.key); onCallSfx() }
            is IslandEvent.ScreenRec -> rec.onScreenRec(e)
            is IslandEvent.ScreenRecGone -> rec.onScreenRecGone(e)
            is IslandEvent.Status -> if (!assistantActive && !call.active && ui.value.content !is IslandContent.Translate) {
                (e.content as? IslandContent.Status)?.let { onTimerStatus(it) }
                showTemporarily(e.content, e.holdMs)
            }
            is IslandEvent.Notification -> if (!assistantActive && !call.active && !appHide.hidden && ui.value.content !is IslandContent.Translate) scope.launch {
                playSfx(SoundEvent.Notif)
                val icon = iconFor(e.pkg)
                val n = IslandContent.Notif(e.pkg, e.title, e.text, icon, e.intent, e.key, e.avatar)
                val st = settings.value
                // iOS tarzı banner açıksa bildirim adada değil cam banner olarak gösterilir; pencere eklenemezse adaya düşer
                if (!(st.notifBanner && notifBanner.show(n, (st.notifExpandedSec * 1000f).toLong()))) notif.show(n)
            }
            is IslandEvent.MediaChanged -> onMedia(e.media)
            is IslandEvent.ShazamListening -> shazam.onListening()
            is IslandEvent.ShazamFound -> shazam.onFound(e)
            is IslandEvent.ShazamGone -> shazam.onGone()
            is IslandEvent.ShowFavorites -> if (!assistantActive && !call.active && ui.value.content is IslandContent.Idle) favorites.open()
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
            if (cur.content is IslandContent.Idle && m != null && m.playing && !call.active && !assistantActive) {
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
        // Yığın açıkken içerik canlı güncellenir; olay sayısı 2'nin altına inerse ya da başka bir panel açılırsa yığın kapanır
        var st: List<IslandContent> = emptyList()
        if (stackOpen) {
            val live = stackItems()
            if (n.expanded || n.swallow || !s.stackExpand) {
                stackOpen = false; stackClosing = false; stackJob?.cancel()
            } else {
                // Olay sayısı 2'nin altına inerse yığın ani kaybolmaz: son görüntüsüyle animasyonlu kapanır
                if (live.size < STACK_MIN_ITEMS && !stackClosing) beginStackClose()
                st = if (live.size >= STACK_MIN_ITEMS) live else lastStack
                if (live.size >= STACK_MIN_ITEMS) lastStack = live
            }
        }
        ui.value = n.copy(
            compactLyrics = want && !n.swallow,
            side = if (st.isEmpty()) sideFor(n) else emptyList(),
            stack = st, stackClosing = stackClosing && st.isNotEmpty(),
        )
        syncRevert(ui.value)
        // Çeviri paneli başka bir içerikle değişti ya da kapandı: bekleyen çeviri iptal, yazı temizlenir
        if (translateOpen && ui.value.content !is IslandContent.Translate) { translateOpen = false; TranslateSession.close() }
    }

    /** Yan baloncuktan açılan (ortaya kalıcı geçmemiş) olay şu an ortada açık mı? Öyleyse arka plan yayınları ortayı geri almaz. */
    private fun peeking(): Boolean {
        val cur = ui.value
        return peekBg != null && centerBg(cur.content) == peekBg      // küçülme animasyonu sürerken de (rest() çağrılana dek)
    }

    private fun winKey(u: IslandUiState, s: IslandSettings = settings.value): WinKey {
        val stacked = u.stack.isNotEmpty()
        val screenWDp = wm.currentWindowMetrics.bounds.width() / density
        return WinKey(
            kindOf(u.content), u.expanded, u.compactLyrics, s, u.swallow, sideKey(u),
            stackW = if (stacked) kotlin.math.ceil(stackWidthDp(u.stack, s, screenWDp)).toInt() else 0,
            stackH = if (stacked) kotlin.math.ceil(stackHeightDp(u.stack, s)).toInt() else 0,
            extraH = if (u.content is IslandContent.Translate) TranslateSession.bodyDp.value else 0,
        )
    }

    // ---------------------------------------------------------------- Yığın görünümü (birden fazla olay alt alta)
    private var stackOpen = false
    private var stackClosing = false
    private var stackJob: Job? = null

    /** Şu an etkin tüm arka plan olayları (en yeni başta). */
    private fun stackItems(): List<IslandContent> {
        syncBgOrder()
        return bgOrder.asReversed().mapNotNull { bgContent(it) }.take(maxSides() + 1)
    }

    /** Ayar açık, ada kapalı ve en az 2 olay varsa yığını açar; açtıysa true döner. */
    private fun openStack(): Boolean {
        val cur = ui.value
        if (stackOpen) { if (stackClosing) { reopenStack(); return true }; return false }
        if (!settings.value.stackExpand || cur.expanded || cur.swallow || cur.fromSide != 0) return false
        if (centerBg(cur.content) == null || stackItems().size < STACK_MIN_ITEMS) return false
        collapseJob?.cancel()
        stackOpen = true; stackClosing = false; lastStack = emptyList()
        update { it.copy(expanded = false) }
        if (!stackOpen) return false
        stackJob?.cancel()
        stackJob = scope.launch { delay(STACK_HOLD_MS); closeStack() }
        return true
    }

    private var lastStack: List<IslandContent> = emptyList()

    private fun beginStackClose() {
        stackClosing = true
        stackJob?.cancel()
        stackJob = scope.launch {
            delay(STACK_CLOSE_MS)
            stackOpen = false; stackClosing = false
            update { it }
        }
    }

    private fun closeStack() {
        if (!stackOpen || stackClosing) return
        beginStackClose()
        update { it }
    }

    /** Kapanış animasyonu sürerken yeniden dokunulursa kapanış durur, kartlar bulundukları yerden geri açılır. */
    private fun reopenStack() {
        if (!stackOpen || !stackClosing) return
        stackClosing = false
        stackJob?.cancel()
        update { it }
        if (!stackOpen) return
        stackJob = scope.launch { delay(STACK_HOLD_MS); closeStack() }
    }

    /** Yığındaki karta dokunma / yukarı kaydırma: açıksa kapatır, kapanıyorsa geri açar. */
    private fun onStackTap() { if (stackClosing) reopenStack() else closeStack() }

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
        Bg.Rec -> rec.phase.let { it == RecPhase.Countdown || it == RecPhase.Recording || it == RecPhase.Processing }
        Bg.Call -> call.ongoing
        Bg.Shazam -> extraBg() && shazam.phase == ShazamPhase.Listening
        Bg.Timer -> extraBg() && (timerContent?.endsAt ?: 0L) > System.currentTimeMillis()
        Bg.Notif -> settings.value.dotMode && notif.bg != null
        Bg.Privacy -> privacy.content != null
    }

    /** Biten olayları listeden çıkarır, yeni başlayanı sona (= en yeni) ekler. */
    private fun syncBgOrder() {
        bgOrder.removeAll { !bgActive(it) }
        for (b in Bg.values()) if (b !in bgOrder && bgActive(b)) bgOrder.add(b)
    }

    private fun bgContent(b: Bg): IslandContent? = when (b) {
        Bg.Media -> lastMedia
        Bg.Rec -> rec.content()
        Bg.Shazam -> IslandContent.Shazam(ShazamPhase.Listening)
        Bg.Timer -> timerContent
        Bg.Notif -> notif.bg
        Bg.Privacy -> privacy.content
        Bg.Call -> call.content()
    }

    /** Ortadaki içerik hangi arka plan olayına ait? (bildirim, durum, favoriler vb. için null) */
    private fun centerBg(c: IslandContent): Bg? = when (c) {
        is IslandContent.Media -> Bg.Media
        is IslandContent.ScreenRec -> if (c.phase != RecPhase.Saved) Bg.Rec else null
        is IslandContent.Call -> if (!c.isIncoming) Bg.Call else null
        is IslandContent.Shazam -> if (extraBg() && c.phase == ShazamPhase.Listening) Bg.Shazam else null
        is IslandContent.Notif -> if (settings.value.dotMode && notif.bg == c) Bg.Notif else null
        is IslandContent.Status -> when {
            extraBg() && c.kind == StatusKind.Timer && c.endsAt > System.currentTimeMillis() && timerContent?.endsAt == c.endsAt -> Bg.Timer
            (c.kind == StatusKind.Camera || c.kind == StatusKind.Mic) && privacy.content == c -> Bg.Privacy
            else -> null
        }
        else -> null
    }

    /**
     * Yan baloncukların yuvaları [sağ, sol] (geçici öne alma sırasında): dokunulan baloncuğun yuvasına önceki ortadaki olay geçer,
     * böylece baloncuklar birbirinin yerine atlamaz. Geri dönüşte / yeniden dinlenmede sıfırlanır.
     */
    private var sideLayout: List<Bg>? = null
    private var sidesHidden = false     // geri dönüşte baloncuklar kısa süre ortadaki adaya karışır (mevcut birleşme animasyonu)

    /** 4 / 5 nokta modu açık mı? Açıkken Shazam dinleme ve zamanlayıcı da yan noktalarda görünen arka plan olayıdır. */
    private fun extraBg(): Boolean = settings.value.let { it.dotMode && it.dotCount >= 4 }

    /** Yan noktaların sayısı: 3 nokta = 2, 4 nokta = 3, 5 nokta = 4; nokta modu kapalıyken 2 yan baloncuk. */
    private fun maxSides(): Int = settings.value.let { if (it.dotMode) (it.dotCount - 1).coerceIn(2, 4) else 2 }

    // Çalışan zamanlayıcı (4 / 5 nokta modunda bitene kadar yan noktada kalır)
    private var timerContent: IslandContent.Status? = null
    private var timerJob: Job? = null

    private fun onTimerStatus(c: IslandContent.Status) {
        if (c.kind != StatusKind.Timer) return
        val left = c.endsAt - System.currentTimeMillis()
        timerJob?.cancel()
        if (!extraBg() || left <= 0L) { timerContent = null; return }
        timerContent = c
        timerJob = scope.launch {
            delay(left)
            timerContent = null
            val cur = ui.value
            if (cur.content is IslandContent.Status && !cur.expanded) rest() else update { it }
        }
    }

    private fun defaultSides(center: Bg): List<Bg> = bgOrder.asReversed().filter { it != center }.take(maxSides())

    private fun orderedSides(center: Bg): List<Bg> {
        val layout = sideLayout ?: return defaultSides(center)
        val kept = layout.filter { it != center && it in bgOrder }
        return (kept + defaultSides(center).filter { it !in kept }).take(maxSides())
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
        if (active && call.active) return          // arama sürerken asistan animasyonu araya girmesin
        assistantActive = active
        edgeGlow.setActive(active)                      // ekran kenarı parlaması: açılırken sağdan sola yayılır, kapanınca söner
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
            cc is IslandContent.Translate -> update { it }              // çeviri yazılırken müzik paneline geçme
            cc is IslandContent.Shazam -> update { it }                 // Shazam dinlerken / sonucu gösterirken müzik ortayı almaz
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

    /** Müzik adası genişletilince açık kalma süresi (ayardan); 0 = kendiliğinden kapanmasın. */
    private fun scheduleMusicCollapse() {
        val ms = (settings.value.musicExpandedSec * 1000f).toLong()
        if (ms <= 0L) collapseJob?.cancel() else scheduleCollapse(ms)
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
        if (call.ringing) { call.publish(); return }     // gelen arama hep ortada
        // Shazam hâlâ dinliyorsa (araya bildirim vb. girdiyse) dinleme görünümüne dön
        if (!extraBg() && shazam.phase == ShazamPhase.Listening) {
            update { IslandUiState(IslandContent.Shazam(ShazamPhase.Listening), expanded = false) }
            return
        }
        // Ortaya en yeni etkin olay gelir; öncekiler update() içinde yan baloncuk olur.
        // Duraklatılmış müzik (yalnızca baloncuk tutma süresi içinde) ortayı almaz.
        val top = bgOrder.lastOrNull { it != Bg.Media || lastMedia?.playing == true }
        val c = top?.let { bgContent(it) }
        when {
            c == null -> update { IslandUiState() }
            top == Bg.Call -> update { IslandUiState(c, expanded = call.expanded) }
            else -> update { IslandUiState(c, false) }
        }
    }

    private fun onPillTap() {
        playSfx(SoundEvent.Tap)
        if (openStack()) return          // birden fazla olay varsa hepsi alt alta açılır
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
                    else { update { it.copy(expanded = true) }; scheduleMusicCollapse() }
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
            is IslandContent.Favorites -> favorites.onTap()
            IslandContent.Translate -> Unit      // panelin içi kendi dokunuşlarını işler (yazı alanı, dil, kapat)
            IslandContent.Idle -> {
                val s = settings.value
                if (s.translateReady() && s.translateTrigger == 0) openTranslate()
                else if (s.favoritesReady() && s.favoritesTrigger == 0) favorites.open()
            }
            IslandContent.Assistant -> Unit
            is IslandContent.Call -> {
                // Gelen arama hep açık (düğmeler kendi dokunuşunu işler). Aktif aramada dokununca aç / küçült.
                if (!call.incoming && peeking()) collapseNow()          // baloncuktan açılmış arama: dokununca baloncuğa geri küçülür
                else if (!call.incoming) call.toggleExpanded()
            }
            is IslandContent.Status -> { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
            is IslandContent.Shazam -> {
                // Bulunan şarkıya dokununca Shazam açılır; dinlerken dokunma "Listening..." panelini açar / küçültür
                if ((cur.content as IslandContent.Shazam).phase == ShazamPhase.Found) { call.send(shazam.open); shazam.end() }
                else if (cur.expanded) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
                else { collapseJob?.cancel(); update { it.copy(expanded = true) } }
            }
            is IslandContent.ScreenRec -> {
                val c = cur.content as IslandContent.ScreenRec
                when (c.phase) {
                    RecPhase.Recording ->
                        if (cur.expanded) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
                        else { update { it.copy(expanded = true) }; scheduleCollapse(6_000) }
                    RecPhase.Saved -> { call.send(rec.open); rec.end() }   // kaydı galeride aç
                    else -> Unit
                }
            }
        }
    }

    // ---------------------------------------------------------------- Ayrı dosyalara taşınan olaylar
    // Shazam -> ShazamController.kt, ekran kaydı -> ScreenRecController.kt. Servisle konuşmaları IslandHost üzerinden olur.
    private val host: IslandHost = object : IslandHost {
        override val scope: kotlinx.coroutines.CoroutineScope get() = this@IslandOverlayService.scope
        override val context: android.content.Context get() = this@IslandOverlayService
        override val settings: IslandSettings get() = this@IslandOverlayService.settings.value
        override val content: IslandContent get() = ui.value.content
        override val blocked: Boolean get() = assistantActive || (call.ringing)
        override val expanded: Boolean get() = ui.value.expanded
        override val busy: Boolean get() = assistantActive || call.active
        override fun showTemporarily(c: IslandContent, holdMs: Long) = this@IslandOverlayService.showTemporarily(c, holdMs)
        override fun rest() = this@IslandOverlayService.rest()
        override fun setIslandHidden(hidden: Boolean) {
            composeView?.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
            if (hidden) setTouchable(false)
            else this@IslandOverlayService.scope.launch { syncWindow(this@IslandOverlayService.winKey(ui.value)) }
        }
        override fun update(f: (IslandUiState) -> IslandUiState) = this@IslandOverlayService.update(f)
        override fun cancelCollapse() { collapseJob?.cancel() }
        override fun scheduleCollapse(holdMs: Long) = this@IslandOverlayService.scheduleCollapse(holdMs)
        override fun collapseSoon() { collapseJob?.cancel(); collapseJob = this@IslandOverlayService.scope.launch { collapseAndRest() } }
        override fun send(pi: android.app.PendingIntent?) = call.send(pi)
        override val isPeeking: Boolean get() = peeking()
        override val newerThanCall: Boolean get() = bgOrder.lastOrNull().let { it != null && it != Bg.Call }
        override fun syncBgOrder() = this@IslandOverlayService.syncBgOrder()
        override suspend fun collapseAndRest() = this@IslandOverlayService.collapseAndRest()
        override fun publishScreenRec(c: IslandContent.ScreenRec, expanded: Boolean) = publishRec(c, expanded)
    }
    private val shazam: ShazamController by lazy { ShazamController(host) }
    private val rec: ScreenRecController by lazy { ScreenRecController(host) }
    private val privacy: PrivacyController by lazy { PrivacyController(host) }
    private val ringer: RingerController by lazy { RingerController(host) }

    // ---- Ses efektleri
    private val sfxTriggers by lazy { SoundTriggers(applicationContext) { playSfx(it) } }
    private var sfxRinging = false
    private fun playSfx(e: SoundEvent) {
        val s = settings.value
        if (!s.sfxEnabled) return
        IslandSounds.play(applicationContext, s.cue(e), e.name)
    }
    /** Gelen arama ada üzerinde yeni belirdiğinde bir kez çalar (aynı aramada tekrarlamaz). */
    private fun onCallSfx() {
        val ringing = call.active && call.incoming
        if (ringing && !sfxRinging) playSfx(SoundEvent.Call)
        sfxRinging = ringing
    }
    private val appHide: AppHideController by lazy { AppHideController(host) }
    private val notif: NotifController by lazy { NotifController(host) }

    /** Ekran kaydı içeriğini adaya yayınlar; kayıt daha yeni bir olayın arkasındaysa yalnızca yan baloncuğu yenilenir. */
    private fun publishRec(c: IslandContent.ScreenRec, expanded: Boolean) {
        if (assistantActive || (call.ringing)) return
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

    // ---------------------------------------------------------------- Arama (gelen / aktif) -> CallController.kt
    private val sideCall: SideCallOverlay by lazy { SideCallOverlay(this, this, this, wm) }
    private val notifBanner by lazy { NotifBannerOverlay(this, this, this, wm, scope) { n -> openNotification(n) } }
    private val call: CallController by lazy { CallController(host, sideCall) }

    /** Basılı tutma: boştayken (her iki tetikleyici modunda) favorileri açar; diğer hallerde dokunmayla aynı davranır. */
    private fun onPillLongPress() {
        val cur = ui.value
        val s = settings.value
        if (cur.content is IslandContent.Idle) {
            if (s.translateReady() && s.translateTrigger == 1) openTranslate()
            else if (s.favoritesReady()) favorites.open()
        } else if (cur.content is IslandContent.Media && !cur.expanded) {
            // Küçük müzik adası: basılı tutunca müzik uygulaması açılır ya da ada genişler
            if (s.openCompLong) openMusicApp()
            else if (s.expandOnLong || !s.openCompTap) { update { it.copy(expanded = true) }; scheduleMusicCollapse() }
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

    private fun collapseNow() {
        val cur = ui.value
        val n = cur.content as? IslandContent.Notif
        if (n != null) {
            // Bildirim: açıksa küçültülmüş süreye geçer; zaten küçültülmüşse (dokunma vb.) hemen kapanır
            if (cur.expanded) { notif.compactNow(n) }
            else { notif.clear(); notif.finish() }
            return
        }
        collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
    }

    /** Bildirimi sistemden de siler ve adayı kapatır. */
    private fun dismissNotif(n: IslandContent.Notif) {
        IslandNotificationListener.dismiss(n.key)
        notif.clear(); notif.finish()
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
            is IslandContent.Status -> when (c.kind) {
                StatusKind.Timer -> Bg.Timer
                StatusKind.Camera, StatusKind.Mic -> Bg.Privacy
                else -> return
            }
            is IslandContent.Notif -> Bg.Notif
            else -> return      // Shazam dinleme noktası dokununca açılmaz
        }
        // 0 = sağ iç, 1 = sol iç, 2 = sağ dış, 3 = sol dış (dış yuvalar ±2: animasyon dış noktadan başlar)
        val from = when (index) { 0 -> 1; 1 -> -1; 2 -> 2; else -> -2 }
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
            Bg.Media -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleMusicCollapse() }
            Bg.Rec -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse(6_000) }
            Bg.Call -> { call.expanded = true; call.fromSide = from; call.publish(); call.scheduleCollapse() }
            Bg.Timer -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse(6_000) }
            Bg.Notif -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse((settings.value.notifExpandedSec * 1000f).toLong()) }
            Bg.Privacy -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse(4_000) }
            Bg.Shazam -> Unit
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
                syncWindow(winKey(u))
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
        if (dir > 0 && openStack()) return
        when (val c = cur.content) {
            // Yukarı kaydırma yalnızca yazı yokken kapatır (yazarken kazara kaydırınca yazdığın kaybolmasın)
            IslandContent.Translate -> { if (dir < 0 && cur.expanded && TranslateSession.state.value.input.isBlank()) closeTranslate() }
            is IslandContent.Media -> {
                if (dir > 0 && !cur.expanded && s.swipeDownExpand) {
                    update { it.copy(expanded = true) }; scheduleMusicCollapse()
                } else if (dir < 0 && cur.expanded && s.swipeUpCollapse) {
                    collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
                }
            }
            is IslandContent.Notif -> {
                if (dir > 0) { if (s.notifOpenSwipeDown) openNotification(c) }
                else if (s.notifDeleteSwipeUp) dismissNotif(c)           // çakışırsa silme önceliklidir
                else if (s.notifCollapseSwipeUp) collapseNow()
            }
            is IslandContent.Shazam -> when {
                c.phase == ShazamPhase.Found -> shazam.end()
                dir > 0 && !cur.expanded -> { collapseJob?.cancel(); update { it.copy(expanded = true) } }
                dir < 0 && cur.expanded -> { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
            }
            is IslandContent.ScreenRec -> when {
                // "Kaydedildi" görünümü: herhangi bir dikey kaydırma kapatır (önceden hareketle küçültülemiyordu)
                c.phase == RecPhase.Saved -> rec.end()
                c.phase == RecPhase.Recording && dir > 0 && !cur.expanded -> { update { it.copy(expanded = true) }; scheduleCollapse(6_000) }
                c.phase == RecPhase.Recording && dir < 0 && cur.expanded -> collapseNow()
            }
            else -> Unit
        }
    }

    // ---------------------------------------------------------------- Çeviri paneli
    private var translateOpen = false
    private var translateHadFocus = false

    /** Sonuç alanında bir satıra sığan yaklaşık harf sayısı (panel yüksekliği buna göre hesaplanır). */
    private fun translateCharsPerLine(s: IslandSettings): Int =
        ((s.expandedWidthDp - 28f - 24f - 64f) / 8.4f).toInt().coerceIn(12, 60)

    /** Adayı çeviri paneli olarak açar: gerektiği kadar genişler, pencere klavye için odaklanabilir olur ([syncWindow]). */
    private fun openTranslate() {
        if (assistantActive || call.active) return
        val s = settings.value
        collapseJob?.cancel()
        translateOpen = true
        translateHadFocus = false
        TranslateSession.open(s.translateSrc, s.translateTgt, translateCharsPerLine(s))
        update { IslandUiState(IslandContent.Translate, expanded = true) }     // süre dolunca kapanmaz: kapat tuşu / geri / yukarı kaydırma
    }

    /** Klavyeyi ve odağı bırakır, adayı küçültür. */
    private fun closeTranslate() {
        if (ui.value.content !is IslandContent.Translate) return
        setFocusable(false)
        collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
    }

    /**
     * Çeviri açıkken pencere odak alabilir (klavye açılsın); diğer zamanlarda NOT_FOCUSABLE kalır ki ada,
     * alttaki uygulamanın klavyesini / odağını çalmasın.
     */
    private fun setFocusable(on: Boolean) {
        val win = dialog?.window ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        val blocked = (win.attributes.flags and flag) != 0
        if (on && blocked) {
            // Klavye pencereyi yeniden boyutlandırmasın / kaydırmasın: panel üstte, klavye altta açılır
            win.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED)
            win.clearFlags(flag)
        } else if (!on && !blocked) {
            composeView?.let { v ->
                runCatching { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0) }
            }
            win.addFlags(flag)
        }
    }

    /** Pencere odağı değişti: çeviri açıkken kullanıcı başka yere dokunup odağı alırsa (örn. uygulamaya geçti) panel kapanır. */
    private fun onOverlayFocus(has: Boolean) {
        val cur = ui.value
        if (cur.content !is IslandContent.Translate || !cur.expanded) { translateHadFocus = false; return }
        if (has) { translateHadFocus = true; return }
        if (!translateHadFocus) return
        // Klavye / sistem penceresi kısa süre odağı alıp bırakabilir: biraz bekleyip hâlâ odak yoksa kapat
        composeView?.postDelayed({
            val c = ui.value
            if (composeView?.hasWindowFocus() != true && c.content is IslandContent.Translate && c.expanded) closeTranslate()
        }, 450)
    }

    /** Panelde seçilen dil çiftini ayarlara yazar (ayar kopyası değil, kayıtlı asıl ayar üzerinden). */
    private suspend fun saveTranslateLangs(src: String, tgt: String) {
        val repo = SettingsRepository(applicationContext)
        val raw = repo.flow.first()
        if (raw.translateSrc != src || raw.translateTgt != tgt) repo.save(raw.copy(translateSrc = src, translateTgt = tgt))
    }

    // ---------------------------------------------------------------- Favori uygulamalar
    private val favorites: FavoritesController by lazy { FavoritesController(host) }

    // ---------------------------------------------------------------- Favori açılış animasyonu
    private val LAUNCH_LEAD_MS = 170L        // ada küçülmeye başladıktan kaç ms sonra siyah ekran çıksın
    private var launching = false
    private val overlays: TransitionOverlays by lazy { TransitionOverlays(this, { wm }, scope) }
    private val launchSlot = OverlaySlot(5_000L) { launching = false }     // güvenlik: siyah katman en geç 5 sn sonra kalkar

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
        notif.clear()      // açılan bildirim adada kalmaz
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
        val added = overlays.show(launchSlot) { onFin ->
            val onCov = { runCatching { open() }; Unit }                  // ekran tamamen siyah: uygulamayı aç
            if (atomic) AtomicLaunch(spec, onCovered = onCov, onFinished = onFin)
            else LaunchTransition(spec, onCovered = onCov, onFinished = onFin)
        }
        if (!added) {                        // pencere eklenemedi: animasyonsuz aç
            launching = false
            runCatching { open() }
        }
    }

    // ---------------------------------------------------------------- Müzikten çıkış animasyonu
    private val SWALLOW_SAFE_MS = 1_500L      // güvenlik: panel bitmese de ada bu sürede küçülür
    private val SWALLOW_HOLD_MS = 140L       // ada büyük kaldıktan sonra eski haline dönme süresi
    private val swallowSlot = OverlaySlot(4_000L) {
        if (exitHold) { exitHold = false; refreshMediaVisibility() }   // panel bitti: müzik artık gösterilebilir
    }

    /** Çalan şarkının uygulamasından çıkılınca: siyah panel adaya küçülür, ada şarkıyı gösterir. */
    private fun onAppSwitched(from: String): Boolean {
        val s = settings.value
        if (!s.musicExitAnim || launching || swallowSlot.shown || stretchSlot.shown) return false
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastExitAt < EXIT_MIN_GAP_MS) return false      // hızlı arka arkaya geçişte animasyon atlanır (ana iş parçacığı yorulmasın)
        val m = lastMedia ?: return false
        if (!m.playing || call.active || assistantActive) return false
        val c = ui.value.content
        if (c !is IslandContent.Media && c !is IslandContent.Idle) return false
        if (!isPlayingMediaApp(from)) return false
        lastExitAt = now
        startSwallowTransition()        // panel penceresi önce eklenir: ilk kare gecikmeden çizilsin
        if (!swallowSlot.shown) return false
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
        overlays.show(
            swallowSlot,
            // Panel tamamen silindi: ada bir an daha büyük kalıp küçülür (arkası görünmesin)
            afterFinished = { if (ui.value.swallow && ui.value.expanded) scheduleCollapse(SWALLOW_HOLD_MS) },
        ) { onDone ->
            if (useAtomic) AtomicToIsland(spec, onSwallow = { runCatching { swallowIsland() } }, onFinished = onDone, fullStart = fullStart)
            else ShrinkToIsland(spec, onSwallow = { runCatching { swallowIsland() } }, onFinished = onDone)
        }
    }

    private fun removeSwallowView() = overlays.remove(swallowSlot)

    // ---------------------------------------------------------------- Uzayan ada katmanı (yeni yutma animasyonu)
    private val stretchSlot = OverlaySlot(3_000L)

    private fun startStretchOverlay() {
        if (stretchSlot.shown) return
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
        overlays.show(stretchSlot) { onDone -> IslandStretch(spec, onFinished = onDone) }
    }

    private fun removeStretchView() = overlays.remove(stretchSlot)

    /** Çıkış animasyonunu tamamen bitirir: katmanlar kalkar, yutma durumu ve gizleme bayrağı sıfırlanır, müzik yeniden gösterilir. */
    private fun endExit() {
        removeStretchView()
        removeSwallowView()               // exitHold'u da sıfırlar
        if (ui.value.swallow) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
        else refreshMediaVisibility()
    }

    private fun abortExit() { exitWatchdog?.cancel(); endExit() }

    private fun removeLaunchView() = overlays.remove(launchSlot)

    private fun onCommand(c: MediaCommand) {
        IslandBus.send(c)
        if (ui.value.expanded) scheduleMusicCollapse()
    }

    /** Panelin sağ alt köşesindeki yayın simgesi: sistemin ses / medya çıkışı paneli. */
    private fun openOutputPanel() {
        runCatching {
            startActivity(Intent(android.provider.Settings.Panel.ACTION_VOLUME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        if (ui.value.expanded) scheduleMusicCollapse()
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
        runCatching { statusCover.detach() }
        runCatching { edgeGlow.detach() }
        // Erişilebilirlik bağlantısı değişince dış söz penceresinin türü de değişir: yeniden kur.
        // Önce durum çubuğu örtüsü, sonra söz penceresi, en son ada eklenir; böylece ada her zaman üstte kalır ve sözler adanın "içinden" çıkar.
        runCatching { detachOutside() }
        runCatching { statusCover.attach() }
        runCatching { edgeGlow.attach() }
        runCatching { syncOutside(settings.value) }
        safeAttachOverlay()
        composeView?.visibility = if (appHide.hidden) View.INVISIBLE else View.VISIBLE
        scope.launch { syncWindow(winKey(ui.value)) }
    }

    // ---------------------------------------------------------------- Durum çubuğu örtüsü
    /**
     * "Durum çubuğunu gizle": durum çubuğunun üstüne dokunmatik olmayan bir pencere çizilir.
     * - Cihaz pencere bulanıklığını destekliyorsa (Android 12+): buzlu cam. Pencerenin arkası bulanıklaştırılır,
     *   üstüne hafif koyu bir ton biner; durum çubuğu simgeleri okunmaz hale gelir. "Cam efekti" ayarından bağımsızdır.
     * - Desteklenmiyorsa: düz siyah.
     * Yalnızca erişilebilirlik servisi bağlıyken çalışır (TYPE_ACCESSIBILITY_OVERLAY durum çubuğunun üstünde durur).
     */
    private val statusCover: StatusCover by lazy {
        StatusCover(
            this, { wm }, { cutCenterYPx }, { dp(it) }, { stripInsets(it) },
            { settings.value.hideStatusBar && ui.value.content is IslandContent.Status },
        )
    }

    // ---------------------------------------------------------------- Ekran kenarı parlaması (asistan) -> EdgeGlow.kt
    private val edgeGlow: EdgeGlow by lazy { EdgeGlow(this, { wm }, { dp(it) }) }

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
                    onAnswer = call::onAnswer, onDecline = call::onDecline, onSpeaker = call::onSpeaker,
                    onRecStop = rec::onStop, onShazamStop = shazam::onStop,
                    onNotifSwipeSide = ::onNotifSwipeSide, onSideTap = ::onSideTap, onSidePress = ::onSidePress,
                    onStackClose = ::onStackTap,
                )
            }
        }
        composeView = view
        startXWatch(view)
        view.viewTreeObserver.addOnWindowFocusChangeListener { has -> onOverlayFocus(has) }

        // Dialog penceresi: Window.setBackgroundBlurRadius SADECE pencere sınırlarını bulanıklaştırır
        // (WindowManager FLAG_BLUR_BEHIND ise tüm ekranı bulanıklaştırıyordu).
        val d = Dialog(ContextThemeWrapper(host, android.R.style.Theme_Translucent_NoTitleBar))
        d.setCancelable(false)
        d.setCanceledOnTouchOutside(false)
        // Çeviri açıkken Geri tuşu (klavye kapalıysa) paneli kapatır; klavye açıkken ilk Geri klavyeyi kapatır
        d.setOnKeyListener { _, code, ev ->
            if (code == KeyEvent.KEYCODE_BACK && ui.value.content is IslandContent.Translate) {
                if (ev.action == KeyEvent.ACTION_UP) closeTranslate()
                true
            } else false
        }
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

    private fun setGeometry(w: Int, h: Int, x: Int, y: Int, recalibrate: Boolean = true, force: Boolean = false) {
        val win = dialog?.window ?: return
        val big = w > 1 && h > 1
        wantX = x; wantY = y
        lastGeoAt = android.os.SystemClock.uptimeMillis()
        val ay = if (big) y - calibDy else y
        val ah = if (big) h + calibDy else h
        val cur = win.attributes
        if (!force && cur.width == w && cur.height == ah && cur.x == x && cur.y == ay) return
        // Yeni nesne: aynı nesneyi geri atamak WindowManager güncellemesini tetiklemez
        val lp = WindowManager.LayoutParams().also { it.copyFrom(cur) }
        lp.width = w; lp.height = ah; lp.x = x; lp.y = ay
        disableMoveAnimation(lp)
        // Pencere KÜÇÜLÜRKEN ve yatay konumu da DEĞİŞİRKEN sistem eski kareyi bir an yanlış yerde
        // gösterebiliyor (yana kayma): yalnızca o durumda içeriği kısa süre gizleyip geri getiriyoruz.
        // Konum aynıysa (ada pencerenin ortasında kalıyorsa) gizlemeye gerek yok; gizlemek
        // kapanırken adanın bir an kaybolup geri gelmesine (göz kırpma) yol açıyordu.
        // Nokta modunda küçük yatay fark (birkaç dp) gizlemeye değmez: gizlemek adanın göz kırpmasına yol açıyordu
        val moved = if (dotWin) abs(cur.x - x) > dp(12f) else cur.x != x
        if (big && curW > 1 && w < curW && moved) {
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

    private var lastGeoAt = 0L
    private var xFixing = false

    /**
     * Sistem pencereyi (özellikle küçülürken / hızlı aç-kapa sırasında) yana kaydırıp orada bırakabiliyor; durum
     * değişene kadar ada yanda kalıyordu. Pencerenin gerçek yatay konumu düzenli aralıkla ölçülür, yanlışsa yeniden uygulanır.
     */
    private fun startXWatch(view: View) {
        val r = object : Runnable {
            override fun run() {
                if (view !== composeView) return
                if (view.isAttachedToWindow && curW > 1 && curH > 1 && !xFixing &&
                    android.os.SystemClock.uptimeMillis() - lastGeoAt > 250L
                ) {
                    val sw = wm.currentWindowMetrics.bounds.width()
                    if (curW < sw) {
                        val loc = IntArray(2)
                        view.getLocationOnScreen(loc)
                        val dx = loc[0] - ((sw - curW) / 2 + wantX)
                        if (abs(dx) > 2) {
                            xFixing = true
                            val ox = wantX
                            setGeometry(curW, curH, ox + 1, wantY, recalibrate = false, force = true)
                            view.postDelayed({
                                // Bu arada başka bir geometri uygulandıysa (wantX değiştiyse) ona dokunma
                                if (wantX == ox + 1) setGeometry(curW, curH, ox, wantY, recalibrate = false)
                                xFixing = false
                            }, 32)
                        }
                    }
                }
                view.postDelayed(this, 250L)
            }
        }
        view.postDelayed(r, 300L)
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
        // Yatay konum: pencere ortalı (gravity) + x kadar kaymış olmalı. Sistem pencereyi küçülürken yana kaydırıp
        // orada bırakırsa (ada ekranın bir yanında kalır) konum yeniden uygulanır.
        val screenW = wm.currentWindowMetrics.bounds.width()
        val dx = if (curW < screenW) loc[0] - ((screenW - curW) / 2 + wantX) else 0
        if ((abs(dy) <= 2 && abs(dx) <= 2) || calibTries >= 3) return
        calibTries++
        if (abs(dy) > 2) calibDy = (calibDy + dy).coerceIn(-300, 600)
        if (abs(dx) > 2) {
            // Aynı değerleri yeniden atamak sistemde işlem yapmayabilir: 1 px kaydırıp hemen gerçek konuma getir
            setGeometry(curW, curH, wantX + 1, wantY, recalibrate = false, force = true)
            v.postDelayed({ setGeometry(curW, curH, wantX - 1, wantY, recalibrate = false) }, 32)
        } else setGeometry(curW, curH, wantX, wantY, recalibrate = false)
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
        dotWin = s.dotMode
        setFocusable(k.kind == 12 && k.expanded)          // çeviri paneli: klavye için odak
        val pad = dp(s.windowPadDp())                    // parıltı / zıplama taşması için pay
        val cw = dp(if (s.dotMode) s.compactWidthDp else if (k.kind == 6 || k.kind == 10) maxOf(CALL_COMPACT_W_DP, s.compactWidthDp) else compactWidthFor(s, k.lyr && k.kind == 2)); val ch = dp(s.compactHeightDp)
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
                k.kind == 11 -> SHAZAM_PILL_H_DP        // Shazam sonucu
                k.kind == 4 -> s.favoritesHeightDp(favN)
                k.kind == 12 -> s.translateHeightDp(k.extraH)   // çeviri paneli: sonuç uzunluğuna göre
                else -> s.expandedHeightDp + if (music) s.musicExtraDp() else 0f
            }
        )

        val screenW = wm.currentWindowMetrics.bounds.width()
        val baseX = cutCenterXOffsetPx + dp(s.offsetXDp)
        val y = (cutCenterYPx - ch / 2).coerceAtLeast(0) + dp(s.offsetYDp) - pad
        val maxShift = ((screenW - ew - 2 * pad) / 2).coerceAtLeast(0)
        // Shazam (kind 11): pencere genişleyip küçülürken yatay konum DEĞİŞMEZ (kısıtlama konumu kaydırıp küçülürken yana kaymaya yol açıyordu)
        val xExp = if (k.kind == 11) baseX else baseX.coerceIn(-maxShift, maxShift)
        val settle = s.settleMs()
        // Yan baloncuklar varsa pencere iki yanda (simetrik) baloncuk payı kadar genişler; ada pencerenin ortasında kalır
        // Genişlemişken de baloncuk varsa pencere iki yanda pay ayırır (baloncuk genişleyen panelin yanında durur)
        // Üç nokta modunda yan noktalar her zaman görünür: pencere iki yanda her zaman pay ayırır (boştayken de)
        val sideExtra = if (s.dotMode || (k.side > 0 && k.kind != 0)) dp(sideExtraDp(s)) else 0
        val blurOk = pad == 0 && sideExtra == 0   // pay / yan baloncuk varsa pencere adadan büyük olur; o zaman blur kapalı tutulur

        // Boştayken de dokunulabilir: favori uygulamalar özelliği açıksa (dokunma / basılı tutma algılanır)
        setTouchable(((k.kind != 0 && !assistant) || (k.kind == 0 && (s.favoritesReady() || s.translateReady()))) && !appHide.hidden)
        when {
            k.stackH > 0 -> {
                // Yığın görünümü: pencere kartların toplam boyutunda
                setBlur(false, s)
                val sw = dp(k.stackW.toFloat()) + 2 * pad
                val sh = dp(k.stackH.toFloat()) + 2 * pad
                val maxSh = ((screenW - sw) / 2).coerceAtLeast(0)
                setGeometry(sw, sh, baseX.coerceIn(-maxSh, maxSh), y)
                winSide = 0
            }
            k.kind == 0 && s.showIdle -> {
                // Boşta da görünür: küçük ada boyutunda, dokunuşsuz
                setBlur(false, s)
                if (curW > cw + 2 * pad + 2 * sideExtra || curH > ch + 2 * pad) delay(settle)
                setGeometry(cw + 2 * pad + 2 * sideExtra, ch + 2 * pad, baseX, y)
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
                // Nokta modunda genişlemişken yalnızca gerçekten çizilen yan baloncuklar pay ayırır (boş noktalar solar).
                // Aksi halde pencere ekrandan geniş olur, yatay konum 0'a zorlanır ve küçülürken ada göz kırpar.
                val sideExp = if (s.dotMode && k.side == 0) 0 else sideExtra
                val winW = ew + 2 * pad + 2 * sideExp
                val maxShSide = ((screenW - winW) / 2).coerceAtLeast(0)
                val xWin = if (k.kind == 11) baseX else if (sideExp > 0) baseX.coerceIn(-maxShSide, maxShSide) else xExp
                // Çeviri sonucu kısalınca pencere, panel küçülme animasyonunu bitirene kadar küçülmez (içerik kırpılmasın)
                if (k.kind == 12 && eh + 2 * pad < curH - 2) delay(240)
                setGeometry(winW, eh + 2 * pad, xWin, y)
                winSide = sideExp
                if (blurOk && !assistant && k.kind != 12) { delay(settle); setBlur(true, s) }
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
        if ((k.kind == 0 && !s.dotMode) || (k.expanded && sideExtra == 0)) winSide = 0
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
        composeView?.visibility = if (appHide.hidden) View.INVISIBLE else View.VISIBLE
        scope.launch { syncWindow(winKey(ui.value)) }
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
        TranslateSession.closeRequest = null
        TranslateSession.onLangsChanged = null
        runCatching { TranslateSession.release() }
        runCatching { privacy.stop() }
        runCatching { ringer.stop() }
        runCatching { sfxTriggers.stop() }
        IslandSounds.stopAll()
        lyricsJob?.cancel()
        runCatching { fxWindow.detach() }
        FxBridge.clear()
        runCatching { call.destroy() }
        runCatching { notifBanner.release() }
        runCatching { removeLaunchView() }
        runCatching { removeSwallowView() }
        runCatching { removeStretchView() }
        exitWatchdog?.cancel()
        runCatching { statusCover.detach() }
        runCatching { edgeGlow.detach() }
        detachOutside()
        collapseJob?.cancel()
        scope.cancel()
        detachOverlay()
        iconCache.evictAll()
        super.onDestroy()
    }

}


