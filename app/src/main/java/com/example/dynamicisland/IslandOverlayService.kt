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
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.Constraints
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
private const val SETTLE_POKE_MS = 650L   // pencere boyutu değiştikten sonra taşıma animasyonu bitince yüzeyi oturtma gecikmesi
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
    /**
     * Yana kayma çözümü (kökten): görüntü penceresi ([dialog]) ekran genişliğinde, yatayda HİÇ oynamaz ve dokunuş
     * almaz; yalnızca boyu değişir. Adanın "mantıksal" penceresinin genişliği ve yatay kayması bu durumda tutulur ve
     * içerik bu kutuya yerleştirilir (IslandRoot sarmalayıcısı). Sistem (MIUI / HyperOS) pencere sol kenarı değişince
     * görüntüyü eski yerde bırakabiliyordu; sol kenar hiç değişmediği için bu artık olamaz.
     * Dokunuşları ve cam bulanıklığını mantıksal pencere boyutundaki görünmez [touchDialog] alır ve görüntüye iletir:
     * kaysa bile görünmez ve dokunma alanı (pencere çerçevesi) hep doğru yerdedir.
     */
    private class LogicalBox(val w: Int, val x: Int)
    private val logicalBox = androidx.compose.runtime.mutableStateOf(LogicalBox(1, 0))
    private var touchDialog: Dialog? = null
    private var touchView: View? = null
    /**
     * Cam bulanıklığı penceresi: tam adanın gövdesi boyunda ve yerinde (pay, parıltı, yan baloncuk dahil değil),
     * köşeleri adanınkiyle aynı. Böylece bulanıklık parıltı / neon / yan baloncuk / çentik açıkken de çalışır.
     * En altta durur, dokunuş almaz.
     */
    private var blurDialog: Dialog? = null
    private var blurCorner = -1f
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
    private var wantYIn = 0               // setGeometry'ye verilen (ters adada dönüştürülmemiş) üst konum
    private var calibDy = 0               // ölçülen kayma (px)
    private var calibTries = 0
    private val calibrateRunnable = Runnable { calibrate() }
    private val showAgain = Runnable { composeView?.animate()?.alpha(1f)?.setDuration(120)?.start() }
    private var resizeListener: View.OnLayoutChangeListener? = null
    private var dotWin = false     // nokta modu açık mı (pencere küçülürken gizleme eşiği için)

    private data class WinKey(val kind: Int, val expanded: Boolean, val lyr: Boolean, val s: IslandSettings, val swallow: Boolean = false, val side: Int = 0, val stackW: Int = 0, val stackH: Int = 0, val extraH: Int = 0, val gesture: Boolean = false,
                              val xcw: Float = 0f, val xew: Float = 0f, val xeh: Float = 0f)   // yeni tasarımların ölçüleri (IslandExtrasUi.kt), 0 = yok

    // ---- Çoklu olay: en yeni olay ortada, öncekiler sağ / sol baloncukta
    private enum class Bg { Media, Rec, Call, SoundCapsule, Timer, Notif, Privacy, Live, Voice }   // Notif: yalnızca nokta modunda; SoundCapsule / Timer: 4 ya da 5 nokta modunda   // SoundCapsule / Timer yalnızca 4 ya da 5 nokta modunda arka plan olayı sayılır
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
        // Çentik modu kamera deliğinin yerine göre ölçülür: ayarlar okunmadan önce delik bulunmalı
        runCatching { detectCutout() }
        // Bildirim simgelerine yer aç: durum çubuğu simgeleri değişince kapalı ada soldan kısalır (yalnızca bu kopya)
        settings = combine(SettingsRepository(applicationContext).flow, StatusIcons.edges) { raw, edges ->
            fitStatusIcons(fitNotch(raw.effective()), edges)
        }.stateIn(scope, SharingStarted.Eagerly, IslandSettings())
        // Çeviri paneli: panelden kapatma isteği + panelde seçilen dilleri ayarlara kaydetme
        TranslateSession.closeRequest = ::closeTranslate
        TranslateSession.onLangsChanged = { src, tgt -> scope.launch { runCatching { saveTranslateLangs(src, tgt) } } }
        // Arka plandan (örn. sistem START_STICKY ile yeniden başlatınca) foreground'a geçiş reddedilebilir;
        // istisna yakalanmazsa süreç çöker ve erişilebilirlik servisi "hatalı çalışıyor" olarak işaretlenir.
        if (!runCatching { startAsForeground() }.isSuccess) { stopSelf(); return }
        runCatching { detectCutout() }
        // Ada açıkken durum çubuğunun üstünü kapatır: simge taraması o sırada bekler (son değer korunur)
        scope.launch { ui.map { it.expanded }.distinctUntilChanged().collect { StatusIcons.paused = it } }
        // Bildirim gizliliği: gizli mod, bilek hareketiyle inen perde ya da ekran kaydı / yansıtma
        scope.launch {
            val triggers = combine(
                NotifPrivacy.curtain, NotifPrivacy.recording, NotifPrivacy.casting,
                NotifPrivacy.still, NotifPrivacy.locked,
            ) { c, r, k, st, lk -> booleanArrayOf(c, r || k, st, lk) }
            combine(settings, triggers, NotifPrivacy.scheduled, NotifPrivacy.focusing) { s, t, sc, fo ->
                val m = s.more
                m.notifPrivate || ((m.notifTwist || m.privPanic) && t[0]) || (m.notifPrivRec && t[1]) || (m.privStill && t[2]) ||
                    (m.privLocked && t[3]) || (m.privSched && sc) || (m.focusHide && fo)
            }.distinctUntilChanged().collect { NotifPrivacy.active.value = it; if (!it) NotifPrivacy.peek.value = false }
        }
        scope.launch {
            settings.map {
                fun list(v: String) = v.split(',').map { p -> p.trim() }.filter { p -> p.isNotEmpty() }
                val m = it.more
                NotifPrivacy.Rule(
                    apps = list(m.notifPrivApps).toSet(), music = m.musicPrivate, blur = m.notifBlurPeek,
                    words = if (m.privWordsOn) list(m.privWords) else emptyList(),
                    people = list(m.privPeople), otp = m.privOtp, decoy = m.privDecoy, reveal = m.privReveal,
                )
            }.distinctUntilChanged().collect { NotifPrivacy.rule.value = it }
        }
        scope.launch {
            settings.map { it.more.notifPrivRec }.distinctUntilChanged().collect { on ->
                runCatching { if (on) castWatch.start() else castWatch.stop() }
            }
        }
        scope.launch { NotifPrivacy.peek.collect { runCatching { onPeek(it) } } }
        scope.launch {
            settings.map { it.more.privPanic }.distinctUntilChanged().collect { on ->
                runCatching { if (on) shakeWatch.start() else shakeWatch.stop() }
            }
        }
        // VPN göstergesi (NetTools.kt)
        scope.launch {
            settings.map { it.more.vpnIndicator }.distinctUntilChanged().collect { on ->
                runCatching { if (on) vpnWatch.start() else vpnWatch.stop() }
            }
        }
        // Ağ hızı: ayar açıkken saniyede bir ölçülür (ekran kapalıyken ölçüm durur)
        scope.launch {
            settings.map { it.more.netSpeedMode != 0 }.distinctUntilChanged().collect { on ->
                if (on) NetSpeed.start(this@IslandOverlayService, scope) else NetSpeed.stop()
            }
        }
        // Ekran görüntüsünde görünmeyen ada: bildirim (ya da kod) adadayken pencereler FLAG_SECURE olur
        scope.launch {
            combine(settings, ui) { s, u -> s.more.privSecure && privSensitive(u) }.distinctUntilChanged().collect { on ->
                runCatching { applySecure(on) }
            }
        }
        // Banner yalnızca bildirim gösterir: ayar açıksa hep kayıt dışı
        scope.launch { settings.map { it.more.privSecure }.distinctUntilChanged().collect { runCatching { notifBanner.secure = it } } }
        scope.launch {
            settings.map { it.more.privStill }.distinctUntilChanged().collect { on ->
                runCatching { if (on) stillWatch.start() else stillWatch.stop() }
            }
        }
        scope.launch {
            settings.map { it.more.privLocked }.distinctUntilChanged().collect { on ->
                runCatching { if (on) lockWatch.start() else lockWatch.stop() }
            }
        }
        // Saate göre gizleme: dakika başlarında kontrol edilir
        scope.launch {
            settings.map { Triple(it.more.privSched, it.more.privFromMin, it.more.privToMin) }.distinctUntilChanged().collectLatest { (on, from, to) ->
                if (!on) { NotifPrivacy.scheduled.value = false; return@collectLatest }
                while (true) {
                    NotifPrivacy.scheduled.value = inPrivacyHours(from, to)
                    delay(60_000L - System.currentTimeMillis() % 60_000L + 200L)
                }
            }
        }
        scope.launch {
            settings.map { it.more.notifTwist }.distinctUntilChanged().collect { on ->
                runCatching { if (on) twist.start() else twist.stop() }
            }
        }
        scope.launch {
            androidx.compose.runtime.snapshotFlow { FxBridge.count > 0 }.distinctUntilChanged().collect { on ->
                runCatching { if (on) fxWindow.attach() else fxWindow.detach() }
            }
        }
        runCatching { statusCover.attach() }      // ada penceresinden ÖNCE eklenir: ada her zaman üstte kalır
        runCatching { edgeGlow.attach() }         // kenar parlaması da adadan ÖNCE eklenir (ada üstte kalır)
        runCatching { decor.attach() }            // ada süsleri (kedi, salıncak, damla) adanın ALTINDA kalır
        runCatching { play.attach() }             // oyuncak ada efektleri de adanın ALTINDA kalır
        safeAttachOverlay()
        // Erişilebilirlik servisi açılıp kapanınca pencere türünü yeniden seç
        IslandAccessibilityService.onChanged = { runCatching { reattach() }; runCatching { lockDot.rehost() }; runCatching { lockIcon.rehost() }; runCatching { faceId.rehost() }; runCatching { torch.rehost() }; runCatching { landscape.rehost() }; runCatching { thumb.rehost() } }
        scope.launch { IslandBus.events.collect(::handle) }
        runCatching { privacy.start() }
        runCatching { ringer.start() }
        runCatching { volume.start() }
        runCatching { sysStatus.start() }       // düşük pil, internet, uçak modu
        runCatching { chargeEta.start() }       // şarj süresi tahmini (ayar kapalıysa sessiz kalır)
        runCatching { flipSilence.start() }     // ters çevirince sessiz (ayar açılınca sensör dinlenir)
        startExtras()                           // başparmak modu, zaman kapsülü, kamuflaj
        DrawerBridge.onKey = { k -> runCatching { onDrawerKey(k) } }
        NotifChipBus.onChip = { n, chip -> runCatching { onNotifChip(n, chip) } }
        wireIslandActions()                     // yeni ada düğmeleri (zamanlayıcı, alarm, düşük güç, Rahatsız Etmeyin...)
        startAppLock()                          // uygulama açılış animasyonu (ek kurulum gerekmez)
        runCatching { lockDot.start() }         // kilit ekranında adanın yanında yanıp sönen nokta
        runCatching { faceId.start() }          // kilit ekranında Face ID animasyonu
        runCatching { lockIcon.start() }        // kilit ekranında adanın solunda kilit simgesi
        runCatching { clock.start() }           // alarm kuruldu / alarm saati geldi
        runCatching { torch.start() }           // fener açılınca ada göstergesi + ışık kartı
        runCatching { landscape.start() }       // yatay mod: dikey ada + yan baloncuklar
        runCatching { sfxTriggers.start() }     // şarja takma / çıkarma / sessize alma sesleri
        runCatching { playCtl.start() }         // oyuncak ada: sensör, şarj, kulaklık, ekran olayları
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
        // Kenar müzik çubukları: ayar, müzik ya da ada içeriği (arama vb.) değişince göster / gizle
        scope.launch { SideMusicStore.flow(applicationContext).collect { syncSideMusic() } }
        scope.launch { SideVolumeStore.flow(applicationContext).collect { syncSideMusic() } }
        scope.launch { combine(ui, mediaState) { _, _ -> Unit }.collect { syncSideMusic() } }
        // Durum görünümü (şarj, zil modu...) açıkken durum çubuğunu kapat
        scope.launch {
            combine(ui, settings) { u, s -> s.hideStatusBar && u.content is IslandContent.Status }
                .distinctUntilChanged()
                .collect { statusCover.set(it) }
        }
        // Pencere geometrisi yalnızca durum/ayar DEĞİŞİNCE güncellenir (kare başına değil)
        scope.launch {
            combine(ui, settings, TranslateSession.bodyDp, GestureStore.flow(applicationContext)) { u, s, _, _ -> winKey(u, s) }
                .distinctUntilChanged()
                .collectLatest { syncWindow(it) }
        }
        // ---- Ada yenileme hızı (Ana Sayfa) -> RefreshRate.kt
        scope.launch { RefreshRateStore.flow(applicationContext).collect { applyRefreshRate() } }
        // ---- Ada süsleri -> IslandDecor.kt
        scope.launch { combine(ui, settings) { _, _ -> Unit }.collect { refreshDecor() } }
        scope.launch {
            // Süs açılıp kapanınca pencereler yeniden sıralanır (süs penceresi adanın altında kalmalı)
            settings.map { decor.wanted(it) }.distinctUntilChanged().collect { on ->
                if (on != decorOn) { decorOn = on; runCatching { reattach() } }
            }
        }
        scope.launch {
            settings.map { it.edgeGlowAlways }.distinctUntilChanged().collect { edgeGlow.setActive(assistantActive || it) }
        }
        // ---- Oyuncak ada
        scope.launch {
            // Efekt penceresi açılıp kapanınca pencereler yeniden sıralanır (efekt penceresi adanın altında kalmalı)
            settings.map { play.wanted(it) }.distinctUntilChanged().collect { on ->
                if (on != playOn) { playOn = on; runCatching { reattach() } }
            }
        }
        scope.launch {
            // Yeni bildirim: evcil kafasını çıkarır, yüz sevinir, dalga yayılır
            ui.map { it.content as? IslandContent.Notif }.distinctUntilChanged().collect { n ->
                if (n != null) {
                    PlayFx.emit(PlayEvent.Notif)
                    if (settings.value.playRipple) PlayFx.emit(PlayEvent.Ripple)
                }
            }
        }
        scope.launch { mediaState.collect { PlayFx.musicPlaying = it?.playing == true } }
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
        is IslandContent.Status -> when {
            c.kind == StatusKind.Ringer -> 13
            c.isSysPill() -> 14                                   // ağ / uçak / zil sesi / ince düşük pil: geniş iOS hapı
            c.isCard() -> 15                                     // düşük pil kartı / kulaklık kartı
            c.isPill() -> 8
            else -> 7
        }
        IslandContent.Photo -> 16                                // özel tasarım fotoğrafı (genişlemiş)
        is IslandContent.Capsule -> if (c.photo.isNotEmpty()) 18 else 17   // zaman kapsülü (fotoğraflı / fotoğrafsız)
        IslandContent.Drawer -> 19                               // ada çekmecesi
        is IslandContent.Live -> 20                              // navigasyon / sipariş takibi
        is IslandContent.ScreenRec -> if (c.phase == RecPhase.Countdown || c.phase == RecPhase.Processing) 10 else 9
        is IslandContent.SoundCapsule -> 11
    }

    // ---------------------------------------------------------------- Olay mantığı
    private fun handle(e: IslandEvent) {
        when (e) {
            is IslandEvent.AssistantChanged -> onAssistant(e.active)
            is IslandEvent.AppSwitched -> {
                if ((swallowSlot.shown || stretchSlot.shown) && isPlayingMediaApp(e.to)) abortExit()   // müzik uygulamasına geri dönüldü
                val wasMusic = inMusicApp
                appHide.update(e.to)           // seçili uygulamada ada gizlenir
                runCatching { appLockOnSwitch(e.from, e.to) }   // uygulama kilidi
                runCatching { privacy.onForeground(e.to) }   // kamera uygulaması açık: fotoğraf modunda da yeşil nokta
                updateInMusicApp(e.to)          // önce "müzik uygulamasında mıyım" güncellenir
                val started = onAppSwitched(e.from)   // çıkış animasyonu varsa ada yutma durumuna geçer (gizleme atlanır)
                if (started) exitHold = settings.value.hideInMusicApp && wasMusic   // panel sürerken gelen ikinci olay gizlemeyi bozmasın
                refreshMediaVisibility()        // animasyon başlamadıysa müzik göster / gizle
            }
            is IslandEvent.Call -> { call.onCall(e); onCallSfx() }
            is IslandEvent.CallEnded -> { call.onEnded(e.key); onCallSfx() }
            is IslandEvent.ScreenRec -> { notePrivRec(e.phase, e.key, false); rec.onScreenRec(e) }
            is IslandEvent.ScreenRecGone -> { notePrivRec(e.phase, e.key, true); rec.onScreenRecGone(e) }
            is IslandEvent.Status -> if (!assistantActive && !call.active && ui.value.content !is IslandContent.Translate) {
                (e.content as? IslandContent.Status)?.let { onTimerStatus(it) }
                showTemporarily(e.content, e.holdMs)
            }
            is IslandEvent.Notification -> if (!assistantActive && !call.active && !appHide.hidden && ui.value.content !is IslandContent.Translate) scope.launch {
                playSfx(SoundEvent.Notif)
                val icon = iconFor(e.pkg)
                val st = settings.value
                privBuzz(e.title, e.key, st)
                // Bildirimde renkli ışık: uygulama simgesinin rengi (ayar kapalıysa 0 = ışık yok)
                val accent = if (st.notifBottomFx && icon != null) accentFor(e.pkg, icon) else 0
                // Bildirim çevirisi: yabancı dildeyse cihazda çevrilir (en çok ~1,5 sn beklenir; olmazsa orijinali gösterilir)
                val tr = if (st.more.notifTranslate) runCatching { NotifTranslator.translate(e.title, e.text, st.more.notifTranslateTgt) }.getOrNull() else null
                val n = IslandContent.Notif(
                    e.pkg, tr?.first ?: e.title, tr?.second ?: e.text, icon, e.intent, e.key, e.avatar, e.messaging, accent,
                    translatedFrom = tr?.third.orEmpty(),
                    // Akıllı bildirim: ertele ve IBAN / kargo no / telefon / adres / bağlantı çipleri (asıl yazıdan çıkarılır)
                    chips = if (e.key.isNotEmpty() && (st.more.notifSnooze || st.more.smartCopy))
                        NotifSmart.chips(e.title, e.text, st.more.notifSnooze, st.more.smartCopy) else emptyList(),
                )
                // Adadan akan bildirim: kelimeler adadan dökülür (efekt penceresi)
                if (st.more.notifFlow) {
                    val shown = NotifPrivacy.display(n, false, ::appLabel)
                    PlayFx.emit(PlayEvent.NotifText(shown.title, shown.text))
                }
                // Bildirim kuşları: bir kuş adanın yanına konar; adaya dokununca bu bildirim açılır
                if (st.more.notifBirds) perchBird(n)
                // iOS tarzı banner açıksa bildirim adada değil cam banner olarak gösterilir; pencere eklenemezse adaya düşer
                // Gizlilik açıksa banner kendi içinde gizli gösterir (NotificationBanner.kt); basılı tutunca içerik görünür
                if (!(st.notifBanner && notifBanner.show(n, (st.notifExpandedSec * 1000f).toLong()))) notif.show(n)
            }
            is IslandEvent.MediaChanged -> onMedia(e.media)
            is IslandEvent.SoundCapsuleListening -> soundCapsule.onListening()
            is IslandEvent.SoundCapsuleFound -> soundCapsule.onFound(e)
            is IslandEvent.SoundCapsuleGone -> soundCapsule.onGone()
            is IslandEvent.NotifTrashed -> {
                val st = settings.value
                // Çöp kutusu ada: silinen bildirimin simgesi adaya uçar, ada yutar (efekt penceresinde çizilir)
                if (st.more.trashIsland) scope.launch { PlayFx.emit(PlayEvent.Trashed(iconFor(e.pkg))) }
                // Silinen bildirim dökülsün: yazısı harf harf parçalanıp adaya çekilir
                if (st.more.notifShatter && e.text.isNotBlank()) PlayFx.emit(PlayEvent.Shatter(e.text, shatterStyleFor(st.fxWeather)))
            }
            is IslandEvent.LiveUpdate -> scope.launch { onLive(e.live) }
            is IslandEvent.LiveGone -> if (liveContent?.key == e.key) endLive()
            is IslandEvent.ShowFavorites -> if (!assistantActive && !call.active && ui.value.content is IslandContent.Idle) favorites.open()
            is IslandEvent.ClockTimer -> clock.onSysTimer(e)
            is IslandEvent.ClockAlarm -> clock.onSysAlarm(e)
            is IslandEvent.ClockGone -> clock.onSysGone(e.key)
            is IslandEvent.ClockStopwatch -> clock.onSysStopwatch(e)
            is IslandEvent.VoiceRec -> voice.onRec(e)
            is IslandEvent.VoiceRecGone -> voice.onGone(e.key)
        }
    }

    /** ui'yi günceller ve "kapalı adada söz gösterilsin mi" bayrağını yeniden hesaplar. */
    // ---- "Müzik uygulamasındayken adada müziği gizle"
    private var inMusicApp = false
    private var exitHold = false        // çıkış animasyonu sürerken (panel adaya girene dek) müzik gösterilmez
    private fun hidingMedia() = settings.value.hideInMusicApp && (inMusicApp || exitHold)

    private fun updateInMusicApp(fg: String? = IslandAccessibilityService.currentFg) {
        inMusicApp = settings.value.hideInMusicApp && fg != null && lastMedia?.playing == true && isPlayingMediaApp(fg)
        syncSideMusic()
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
        guardStuckFromSide()
        // Fener simgesi yalnızca ada boş ve kapalıyken adanın içinde görünür
        runCatching { ui.value.let { u -> torch.onIslandState(u.content is IslandContent.Idle && !u.expanded && !u.swallow && u.stack.isEmpty()) } }
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
            extraH = if (u.content is IslandContent.Translate) TranslateSession.bodyDp.value
                else notifChipsExtraDp(u.content).toInt(),
            gesture = GestureStore.flow(applicationContext).value.enabled,
            xcw = xCompactWidthDp(u.content, s) ?: 0f,
            xew = xExpandedSizeDp(u.content, s, screenWDp)?.first ?: 0f,
            xeh = xExpandedSizeDp(u.content, s, screenWDp)?.second ?: 0f,
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
        Bg.SoundCapsule -> extraBg() && soundCapsule.phase == SoundCapsulePhase.Listening
        Bg.Timer -> clock.active() || (extraBg() && (timerContent?.endsAt ?: 0L) > System.currentTimeMillis())
        Bg.Notif -> settings.value.dotMode && notif.bg != null
        Bg.Privacy -> privacy.content != null
        Bg.Live -> liveContent != null
        Bg.Voice -> voice.active
    }

    /** Biten olayları listeden çıkarır, yeni başlayanı sona (= en yeni) ekler. */
    private fun syncBgOrder() {
        bgOrder.removeAll { !bgActive(it) }
        for (b in Bg.values()) if (b !in bgOrder && bgActive(b)) bgOrder.add(b)
    }

    private fun bgContent(b: Bg): IslandContent? = when (b) {
        Bg.Media -> lastMedia
        Bg.Rec -> rec.content()
        Bg.SoundCapsule -> IslandContent.SoundCapsule(SoundCapsulePhase.Listening)
        Bg.Timer -> clock.content() ?: timerContent
        Bg.Notif -> notif.bg
        Bg.Privacy -> privacy.content
        Bg.Call -> call.content()
        Bg.Live -> liveContent
        Bg.Voice -> voice.content()
    }

    /** Ortadaki içerik hangi arka plan olayına ait? (bildirim, durum, favoriler vb. için null) */
    private fun centerBg(c: IslandContent): Bg? = when (c) {
        is IslandContent.Media -> Bg.Media
        is IslandContent.ScreenRec -> if (c.phase != RecPhase.Saved) Bg.Rec else null
        is IslandContent.Call -> if (!c.isIncoming) Bg.Call else null
        is IslandContent.SoundCapsule -> if (extraBg() && c.phase == SoundCapsulePhase.Listening) Bg.SoundCapsule else null
        is IslandContent.Notif -> if (settings.value.dotMode && notif.bg == c) Bg.Notif else null
        is IslandContent.Live -> if (liveContent?.key == c.key) Bg.Live else null
        is IslandContent.Status -> when {
            clock.owns(c) -> Bg.Timer
            voice.owns(c) -> Bg.Voice
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

    /** 4 / 5 nokta modu açık mı? Açıkken SoundCapsule dinleme ve zamanlayıcı da yan noktalarda görünen arka plan olayıdır. */
    private fun extraBg(): Boolean = settings.value.let { it.dotMode && it.dotCount >= 4 }

    /** Yan noktaların sayısı: 3 nokta = 2, 4 nokta = 3, 5 nokta = 4; nokta modu kapalıyken 2 yan baloncuk. */
    private fun maxSides(): Int = settings.value.let {
        if (it.dotMode) (it.dotCount - 1).coerceIn(2, 4)
        else if (it.iphoneCircleOn()) (if (it.iphoneEvents) 1 else 0)     // iPhone modu: tek daire (olaylar kapalıysa baloncuk yok)
        else 2
    }

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

    private fun defaultSides(center: Bg): List<Bg> {
        val list = bgOrder.asReversed().filter { it != center }
        // Mikrofon / kamera noktası sağdaki (ilk) baloncukta
        val ordered = if (settings.value.more.micSideRight && Bg.Privacy in list) listOf(Bg.Privacy) + list.filter { it != Bg.Privacy } else list
        return ordered.take(maxSides())
    }

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
        edgeGlow.setActive(active || settings.value.edgeGlowAlways)   // ekran kenarı parlaması: açılırken sağdan sola yayılır, kapanınca söner
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
            cc is IslandContent.Photo -> update { it }                  // fotoğraf açıkken müzik ortayı almaz (kapanınca gelir)
            cc is IslandContent.Capsule -> update { it }                // zaman kapsülü okunurken müzik araya girmez
            cc is IslandContent.Drawer -> update { it }                 // çekmece açıkken müzik araya girmez
            cc is IslandContent.Translate -> update { it }              // çeviri yazılırken müzik paneline geçme
            cc is IslandContent.SoundCapsule -> update { it }                 // SoundCapsule dinlerken / sonucu gösterirken müzik ortayı almaz
            cc is IslandContent.ScreenRec && cc.phase == RecPhase.Saved -> update { it }   // "kaydedildi" görünümü bozulmasın
            // Müzik yeni başladı: en yeni olay olarak ortaya geçer, önceki ortadaki olay yan baloncuğa ayrılır
            nowActive && !wasActive && m != null && cc !is IslandContent.Media -> {
                mediaGraceJob?.cancel(); collapseJob?.cancel()
                update { IslandUiState(m, false) }
            }
            cc is IslandContent.Call || cc is IslandContent.ScreenRec || cc is IslandContent.Live -> update { it }   // müzik zaten yan baloncukta (ya da yok)
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

    /**
     * Yana kayma düzeltmesi: yan baloncuktan açılan olay küçülürken fromSide bilerek korunur (ada baloncuğun yerine döner)
     * ve kısa süre sonra rest() onu sıfırlar. Bu aralıkta collapseJob başka bir olay / müzik güncellemesi / bildirim
     * yüzünden iptal edilirse rest() hiç çalışmıyordu: ada kapalı ama fromSide != 0 kalıyor, ada baloncuğun yerinde
     * (en sağda / en solda / biraz kaymış) çiziliyordu; dokununca açılıp kapanınca düzeliyordu.
     * Burada, bu durum normal küçülme süresinden uzun sürerse rest() zorla çağrılır.
     */
    private var fromSideGuard: Job? = null
    private fun guardStuckFromSide() {
        val u = ui.value
        if (!u.expanded && u.fromSide != 0) {
            if (fromSideGuard?.isActive == true) return
            fromSideGuard = scope.launch {
                delay(settings.value.settleMs() + 400L)
                val c = ui.value
                if (!c.expanded && c.fromSide != 0) rest()
            }
        } else {
            fromSideGuard?.cancel(); fromSideGuard = null
        }
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
        // Çalan alarm / süresi dolan zamanlayıcı: kapatılana kadar açık kalır
        if (clock.ringing) { clock.content()?.let { c -> update { IslandUiState(c, expanded = true) }; return } }
        // SoundCapsule hâlâ dinliyorsa (araya bildirim vb. girdiyse) dinleme görünümüne dön
        if (!extraBg() && soundCapsule.phase == SoundCapsulePhase.Listening) {
            update { IslandUiState(IslandContent.SoundCapsule(SoundCapsulePhase.Listening), expanded = false) }
            return
        }
        // Ortaya en yeni etkin olay gelir; öncekiler update() içinde yan baloncuk olur.
        // Duraklatılmış müzik (yalnızca baloncuk tutma süresi içinde) ortayı almaz.
        // Mikrofon / kamera noktası sağda: başka etkin olay varsa ortayı almaz, sağdaki baloncukta kalır
        val micRight = settings.value.more.micSideRight
        val others = bgOrder.count { it != Bg.Privacy && (it != Bg.Media || lastMedia?.playing == true) }
        val top = bgOrder.lastOrNull { (it != Bg.Media || lastMedia?.playing == true) && !(micRight && it == Bg.Privacy && others > 0) }
        val c = top?.let { bgContent(it) }
        when {
            c == null -> update { IslandUiState() }
            top == Bg.Call -> update { IslandUiState(c, expanded = call.expanded) }
            else -> update { IslandUiState(c, false) }
        }
    }

    private fun onPillTap() {
        playSfx(SoundEvent.Tap)
        PlayFx.lastActivityMs = System.currentTimeMillis()      // evcil: dokunulunca sıkılmaz
        PlayFx.fullness = 0f                                    // bildirim yiyen ada: dokununca "tokluk" geçer
        // Nefes egzersizi sürerken dokunuş onu bitirir
        if (BreathSession.active) {
            BreathSession.stop()
            if (settings.value.playBreath) { PlayFx.emit(PlayEvent.Say("Tamam, bitti.")); return }
        }
        // Ziyaretçi varken dokunuş onu yakalar
        if (settings.value.playVisitors) VisitorStore.catchNow(this)?.let { k ->
            PlayFx.emit(PlayEvent.Caught(k))
            if (k.weight <= 3) PlayFx.emit(PlayEvent.Party)                  // nadir yakalama: konfeti
        }
        if (secretGameTap()) return
        rhythmTap()
        // Ada saksısı: günün ilk dokunuşu bitkiyi sular
        if (settings.value.playPlant && PlantStore.water(this)) PlayFx.emit(PlayEvent.Water)
        if (settings.value.playRipple && settings.value.playRippleTap) PlayFx.emit(PlayEvent.Ripple)
        if (settings.value.playPuff) PlayFx.emit(PlayEvent.Puff)
        if (!landscape.active && openStack()) return          // birden fazla olay varsa hepsi alt alta açılır (yatay modda baloncuklar var)
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
                    !cur.expanded && s.notifExpandTap -> notif.expandAgain(n)
                    s.notifTapOpens -> openNotification(n)
                    s.notifCollapseTap -> collapseNow()
                    else -> Unit
                }
            }
            is IslandContent.Favorites -> favorites.onTap()
            IslandContent.Translate -> Unit      // panelin içi kendi dokunuşlarını işler (yazı alanı, dil, kapat)
            is IslandContent.Capsule -> { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
            IslandContent.Drawer -> {
                // Düğmelerin dışına dokununca çekmece kapanır (açılır açılmaz gelen ikinci dokunuş hariç)
                if (android.os.SystemClock.elapsedRealtime() - drawerOpenedAt < 400L) return
                collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
            }
            is IslandContent.Live -> {
                // Kapalıyken dokununca kart açılır; açıkken dokununca uygulama (Haritalar / sipariş ekranı) açılır
                val lv = cur.content as IslandContent.Live
                if (lv.kind == LiveKind.Code) {
                    // Doğrulama kodu: açık ya da kapalı, dokununca kopyalanır; kart kısa süre sonra kapanır
                    val ok = OtpParse.copy(this, lv.big)
                    val done = lv.copy(text = if (ok) "Kopyalandı ✓" else "Kopyalanamadı")
                    liveContent = done
                    update { it.copy(content = done, expanded = true) }
                    liveEndJob?.cancel()
                    liveEndJob = scope.launch { delay(1_400L); endLive() }
                    return
                }
                if (cur.expanded) {
                    call.send(lv.intent ?: packageManager.getLaunchIntentForPackage(lv.pkg)?.let {
                        android.app.PendingIntent.getActivity(this, 0, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
                    })
                    collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
                } else {
                    collapseJob?.cancel()
                    update { it.copy(expanded = true) }
                    scheduleCollapse(8_000)
                }
            }
            IslandContent.Photo -> {
                // Açılır açılmaz gelen titreşimli ikinci dokunuş fotoğrafı hemen kapatmasın
                if (android.os.SystemClock.elapsedRealtime() - photoOpenedAt < 400L) return
                collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
            }
            IslandContent.Idle -> {
                val s = settings.value
                val bird = perchedNotif
                if (s.more.notifBirds && bird != null) { birdAway(); openNotification(bird) }
                else if (s.showIdle && s.more.timerIdleTrigger == 1) clock.openSetter()     // zamanlayıcı kurma paneli
                else if (s.designPhotoReady() && s.more.designPhotoTrigger == 0) openPhoto()
                else if (s.more.drawerEnabled && s.showIdle && s.more.drawerTrigger == 0) openDrawer()
                else if (s.translateReady() && s.translateTrigger == 0) openTranslate()
                else if (s.favoritesReady() && s.favoritesTrigger == 0) favorites.open()
            }
            IslandContent.Assistant -> Unit
            is IslandContent.Call -> {
                // Gelen arama hep açık (düğmeler kendi dokunuşunu işler). Aktif aramada dokununca aç / küçült.
                if (!call.incoming && peeking()) collapseNow()          // baloncuktan açılmış arama: dokununca baloncuğa geri küçülür
                else if (!call.incoming) call.toggleExpanded()
                else if (!cur.expanded) call.expandIncoming()             // küçültülmüş gelen arama: yeniden açılır
            }
            is IslandContent.Status -> {
                val st = cur.content as IslandContent.Status
                val more = settings.value.more
                // Zamanlayıcı / alarm: kapalıysa açılır (düğmelerle), açıksa küçülür; kurma panelinde dokunuş bir şey yapmaz
                if ((st.kind == StatusKind.Timer || st.kind == StatusKind.Alarm) && (clock.owns(st) || st.phase == 3)) {
                    if (st.phase != 3) clock.onTap(cur.expanded, st)
                    return
                }
                // Ses kaydı: aç / küçült
                if (st.kind == StatusKind.VoiceRec && voice.owns(st)) { voice.onTap(cur.expanded); return }
                // Kulaklık: küçük adaya dokununca kart açılır
                if (st.kind == StatusKind.Headphones && !cur.expanded) {
                    collapseJob?.cancel(); update { it.copy(expanded = true) }; scheduleCollapse(5_000L); return
                }
                // Rahatsız Etmeyin hapı: dokununca genişler (aya dokunarak aç / kapat)
                if (st.kind == StatusKind.Focus && st.phase == 0 && more.focusTapExpand) {
                    collapseJob?.cancel()
                    update { it.copy(content = st.copy(phase = 1, title = "Rahatsız Etmeyin"), expanded = true) }
                    scheduleCollapse(5_000L); return
                }
                if (st.kind == StatusKind.Focus && st.phase == 1) return          // kartın kendisine dokunma: aya dokunulmalı
                // Düşük pil: genişlemeyen hapa dokununca (ayar açıksa) büyük kart açılır; kartta pil kapsülü düşük güç modunu açar
                if (st.kind == StatusKind.LowBattery && more.lowBattTapExpand) {
                    collapseJob?.cancel()
                    val pct = (st.progress * 100f).toInt()
                    val saver = st.extra == 1
                    update { it.copy(content = st.copy(
                        kind = StatusKind.LowBatteryCard, title = "%$pct Pil",
                        subtitle = if (saver) "Düşük Güç Modu açık" else "Düşük Güç Modu için dokun",
                    ), expanded = true) }
                    scheduleCollapse(6_000L); return
                }
                if (st.kind == StatusKind.LowBatteryCard) return                  // kartta yalnızca pil kapsülü düşük güç modunu açar
                if (st.kind == StatusKind.LowBattery) {
                    if (sysStatus.onLowBatteryTap(st)) return
                }
                collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
            }
            is IslandContent.SoundCapsule -> {
                // Bulunan şarkıya dokununca SoundCapsule açılır; dinlerken dokunma "Listening..." panelini açar / küçültür
                if ((cur.content as IslandContent.SoundCapsule).phase == SoundCapsulePhase.Found) { call.send(soundCapsule.open); soundCapsule.end() }
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

    // ---------------------------------------------------------------- Bildirim gizliliği (NotifPrivacy.kt)
    // Ekrana giden kopya: gizlilik açıkken bildirimler maskelenir. Servisin mantığı her zaman gerçek [ui] ile çalışır.
    private val uiShown: StateFlow<IslandUiState> by lazy {
        combine(ui, NotifPrivacy.active, NotifPrivacy.peek, NotifPrivacy.rule) { u, a, p, r ->
            if (NotifPrivacy.anyRule(a, r)) NotifPrivacy.mask(u, a, r, p, ::appLabel) else u
        }.stateIn(scope, SharingStarted.Eagerly, ui.value)
    }
    // Gizli müzik: şarkı sözleri de ekrana gitmez
    private val lyricsShown: StateFlow<LyricsState> by lazy {
        combine(lyrics, NotifPrivacy.active, NotifPrivacy.rule) { l, a, r -> if (a && r.music) LyricsState.Idle else l }
            .stateIn(scope, SharingStarted.Eagerly, lyrics.value)
    }
    private val castWatch by lazy { CastWatch(this) }
    private val stillWatch by lazy { StillWatch(this) }
    private var vpnArmedAt = 0L
    private var vpnLostJob: Job? = null
    private val vpnWatch by lazy {
        vpnArmedAt = android.os.SystemClock.elapsedRealtime()
        VpnWatch(this) { on -> scope.launch { runCatching { onVpn(on) } } }
    }
    /** VPN bağlandı: yeşil kalkan hapı. Koptu: 3 sn içinde geri gelmezse kırmızı uyarı + titreşim. */
    private fun onVpn(on: Boolean) {
        if (android.os.SystemClock.elapsedRealtime() - vpnArmedAt < 2_500L) return   // açılışta mevcut durum gösterilmez
        vpnLostJob?.cancel()
        if (on) {
            if (canShowSys()) showTemporarily(IslandContent.Status(StatusKind.Network, title = "VPN", value = "Açık", extra = 4), 2_200L)
        } else {
            vpnLostJob = scope.launch {
                delay(3_000L)                       // sunucu değiştirirken kısa kopmalar uyarı vermesin
                if (VpnWatch.on.value) return@launch
                twistBuzz(true); delay(150); twistBuzz(true)
                if (canShowSys()) showTemporarily(IslandContent.Status(StatusKind.Network, title = "VPN", value = "Koptu", extra = 5), 4_000L)
            }
        }
    }
    private fun canShowSys(): Boolean = !host.busy && !host.blocked && !appHide.hidden && ui.value.content !is IslandContent.Translate
    private val shakeWatch by lazy { ShakeWatch(this) { runCatching { onPanic() } } }

    /** Panik sallaması: ada küçülür, bildirim kapanır, banner gizlenir, müzik durur ve gizleme açılır. */
    private fun onPanic() {
        NotifPrivacy.peek.value = false
        NotifPrivacy.curtain.value = true
        runCatching { notifBanner.hide() }
        val cur = ui.value
        if (cur.content is IslandContent.Notif) { notif.clear(); notif.finish() }
        else if (cur.expanded) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
        if (mediaState.value?.playing == true) onCommand(MediaCommand.PlayPause)
        twistBuzz(true); scope.launch { delay(120); twistBuzz(true) }
    }

    // Titreşim imzası: aynı bildirim güncellenince (ör. "yazıyor") tekrar titremesin
    private val buzzSeen = HashMap<String, Long>()
    private fun privBuzz(title: String, key: String, st: IslandSettings) {
        val m = st.more
        val lists = listOf(m.privBuzz1, m.privBuzz2, m.privBuzz3, m.privBuzz4)
        if (lists.all { it.isBlank() }) return
        val i = BuzzSign.match(title, lists)
        if (i < 0) return
        val now = android.os.SystemClock.uptimeMillis()
        val k = key.ifEmpty { title }
        if (now - (buzzSeen[k] ?: 0L) < 4_000L) return
        buzzSeen[k] = now
        if (buzzSeen.size > 64) buzzSeen.entries.removeAll { now - it.value > 60_000L }
        BuzzSign.play(this, i)
    }

    /** Adada (ortada, yanda ya da yığında) bildirim / doğrulama kodu var mı. */
    private fun privSensitive(u: IslandUiState): Boolean {
        fun sens(c: IslandContent) = c is IslandContent.Notif || (c is IslandContent.Live && c.kind == LiveKind.Code)
        return sens(u.content) || u.side.any { sens(it) } || u.stack.any { sens(it) }
    }
    private var secureOn = false
    /** Ada pencerelerini ekran görüntüsü / kayıt dışı bırakır (FLAG_SECURE). Banner da aynı şekilde. */
    private fun applySecure(on: Boolean) {
        secureOn = on
        val f = WindowManager.LayoutParams.FLAG_SECURE
        for (d in listOf(dialog, blurDialog)) {
            val w = d?.window ?: continue
            if (on) w.addFlags(f) else w.clearFlags(f)
        }
    }
    private val lockWatch by lazy { LockWatch(this) }
    // Ekran kaydı takibi (gizlilik için; ada ekran kaydı ayarından bağımsız)
    private var privRecKey = ""
    private var privRecLive = false     // kayıt gerçekten başladı (geri sayım değil)
    private var privRecJob: Job? = null
    private fun notePrivRec(phase: RecPhase, key: String, gone: Boolean) {
        privRecJob?.cancel()
        when {
            !gone && (phase == RecPhase.Countdown || phase == RecPhase.Recording) -> {
                NotifPrivacy.recording.value = true; privRecKey = key
                if (phase == RecPhase.Recording) privRecLive = true
                // Geri sayım iptal edildiyse gizleme takılı kalmasın
                if (phase == RecPhase.Countdown) privRecJob = scope.launch { delay(8_000); NotifPrivacy.recording.value = false }
            }
            !gone -> { NotifPrivacy.recording.value = false; privRecLive = false }   // işleniyor / kaydedildi: kayıt bitti
            phase == RecPhase.Recording && key == privRecKey -> { NotifPrivacy.recording.value = false; privRecLive = false }
            phase == RecPhase.Countdown && !privRecLive ->
                privRecJob = scope.launch { delay(1_500); if (!privRecLive) NotifPrivacy.recording.value = false }
        }
    }
    private fun appLabel(pkg: String): String = NotifPrivacy.label(this, pkg)
    // Küçük adadaki bildirimde yalnızca simge görünür: basılı tutunca ada geçici olarak açılır, bırakınca geri küçülür
    private var peekOpened = false
    private fun onPeek(on: Boolean) {
        val cur = ui.value
        if (on) {
            val c = cur.content
            if ((c is IslandContent.Notif || (c is IslandContent.Live && c.kind == LiveKind.Code)) && !cur.expanded && cur.stack.isEmpty()) {
                peekOpened = true
                collapseJob?.cancel()
                update { it.copy(expanded = true) }
            }
        } else if (peekOpened) {
            peekOpened = false
            val c = cur.content
            if ((c is IslandContent.Notif || c is IslandContent.Live) && cur.expanded) update { it.copy(expanded = false) }
        }
    }
    private val twist by lazy {
        TwistDetector(this) {
            NotifPrivacy.curtain.value = !NotifPrivacy.curtain.value
            twistBuzz(NotifPrivacy.curtain.value)
        }
    }
    private fun twistBuzz(hidden: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            val v: android.os.Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31)
                getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else getSystemService(android.os.Vibrator::class.java)
            // gizlenince tek kısa, görününce iki kısa titreşim
            val fx = if (hidden) android.os.VibrationEffect.createOneShot(35L, 180)
            else android.os.VibrationEffect.createWaveform(longArrayOf(0L, 25L, 70L, 25L), -1)
            v?.vibrate(fx)
        }
    }

    // ---------------------------------------------------------------- Başparmak modu / zaman kapsülü / kamuflaj
    private var thumbHidden = false          // başparmak modunda ada aşağıda: kameranın yanındaki ada gizli
    private val thumb by lazy {
        ThumbIsland(this, this, this, wm, settings, uiShown,
            onTap = { onPillTap() },
            onReturn = { ThumbStore.set(this, false) },
            favApps = { favorites.appsNow() },
            onLaunch = { pkg -> launchFavorite(pkg) })
    }

    private fun startExtras() {
        ThumbStore.load(this)
        // Başparmak modu: ada boşken (ya da müzik küçükken) aşağıdaki yüzen ada görünür, kameranın yanındaki gizlenir.
        // Bir olay açılınca hemen yukarıda görünür; ada kapanınca küçülme animasyonu bittikten sonra aşağı iner.
        scope.launch {
            combine(ui, settings, ThumbStore.active) { u, s, a ->
                a && s.more.thumbEnabled && u.stack.isEmpty() && !u.expanded && !u.swallow && u.fromSide == 0 &&
                    ((u.content is IslandContent.Idle && s.showIdle) || u.content is IslandContent.Media)
            }.distinctUntilChanged().collectLatest { on ->
                val show = on && !landscape.active
                if (show) delay(settings.value.settleMs() + 150L)
                setThumb(show)
            }
        }
        // Zaman kapsülü: zamanı gelen kapsül, telefon açık ve kilitsizken, ada boştayken açılır
        scope.launch { while (true) { delay(10_000L); runCatching { checkCapsule() } } }
        // Kamuflaj: boştaki adanın yanındaki rengi düzenli aralıklarla oku
        scope.launch { while (true) { delay(1_500L); runCatching { sampleCamo() } } }
        // Canlı etkinlik: bildirim dinleyicisi ayarları buradan okur
        scope.launch { settings.collect {
            LiveFlags.nav = it.more.navIsland; LiveFlags.delivery = it.more.deliveryIsland
            LiveFlags.progress = it.more.progressIsland; LiveFlags.score = it.more.scoreIsland
            LiveFlags.logos = it.more.scoreLogos
            ClockFlags.enabled = it.more.clockIsland; ClockFlags.voiceRec = it.more.voiceRecIsland
            NotifFlags.otp = it.more.otpIsland; NotifFlags.history = it.more.notifHistory; NotifFlags.remind = it.more.unreadRemind
            NotifFlags.important = it.more.importantOnly; NotifFlags.names = ImportantMatch.parse(it.more.importantNames)
            NotifFlags.digest = it.more.silentDigest
            if (!it.more.notifHistory) NotifVault.clearRemoved()
        } }
        // Sessiz bildirim özeti: saatte bir, görülmemiş sessiz bildirim varsa adada kısa bir özet
        scope.launch {
            while (true) {
                delay(60 * 60_000L)
                val st = settings.value
                if (!(st.more.silentDigest || st.more.importantOnly)) continue
                val cnt = NotifVault.silentUnseen
                if (cnt <= 0 || assistantActive || call.active || appHide.hidden) continue
                val apps = NotifVault.unseenPkgs().take(4).map { p ->
                    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
                }
                val open = packageManager.getLaunchIntentForPackage(packageName)?.let {
                    android.app.PendingIntent.getActivity(this@IslandOverlayService, 77, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), android.app.PendingIntent.FLAG_IMMUTABLE)
                }
                notif.show(IslandContent.Notif(packageName, "$cnt sessiz bildirim", apps.joinToString(", "), iconFor(packageName), open, ""))
                NotifVault.markSilentSeen()
            }
        }
        scope.launch { settings.map { listOf(it.more.navIsland, it.more.deliveryIsland, it.more.progressIsland, it.more.scoreIsland, it.more.otpIsland) }.distinctUntilChanged().collect { f ->
            val lv = liveContent ?: return@collect
            val on = when (lv.kind) { LiveKind.Nav -> f[0]; LiveKind.Delivery -> f[1]; LiveKind.Progress -> f[2]; LiveKind.Score -> f[3]; LiveKind.Code -> f[4] }
            if (!on) endLive()
        } }
        // Kulaklık kartı
        runCatching { startHeadphones() }
        // Şarj halkası: şarja takılınca
        runCatching {
            val f = android.content.IntentFilter(Intent.ACTION_POWER_CONNECTED)
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(chargeReceiver, f, Context.RECEIVER_EXPORTED) else registerReceiver(chargeReceiver, f)
        }
    }

    private fun setThumb(on: Boolean) {
        if (on == thumbHidden) { if (on) runCatching { thumb.setShown(true) }; return }
        thumbHidden = on
        runCatching { thumb.setShown(on) }
        applyMainHidden()
        scope.launch { syncWindow(winKey(ui.value)) }
    }

    private fun checkCapsule() {
        val u = ui.value
        if (u.content !is IslandContent.Idle || u.expanded || assistantActive || call.active) return
        if (getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return
        if (getSystemService(android.app.KeyguardManager::class.java)?.isKeyguardLocked == true) return
        val c = CapsuleStore.nextDue(this, System.currentTimeMillis()) ?: return
        CapsuleStore.markDelivered(this, c.id)
        playSfx(SoundEvent.Notif)
        showTemporarily(IslandContent.Capsule(c.id, c.text, c.photo, c.createdAt), 15_000L)
    }

    private var camoBusy = false
    private fun sampleCamo() {
        val s = settings.value
        if (!(s.more.camoEnabled || s.more.contrastEnabled) || s.more.portalEnabled || camoBusy) return
        val u = ui.value
        if (u.content !is IslandContent.Idle || u.expanded || mainHidden || thumbHidden || appHide.hidden) return
        if (getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return
        val a11y = IslandAccessibilityService.instance ?: return
        // Seçilen uygulamalar (bankacılık, mesajlaşma...) açıkken ekran görüntüsü HİÇ alınmaz; ada normal rengine döner
        val fg = IslandAccessibilityService.currentFg
        if (fg != null && fg in s.more.camoSkipList()) { CamoState.color = null; return }
        val sw = wm.currentWindowMetrics.bounds.width()
        val cx = sw / 2 + cutCenterXOffsetPx + dp(s.offsetXDp)
        val half = dp(compactWidthFor(s, false)) / 2
        camoBusy = true
        CamoState.sample(a11y, cx - half, cx + half, islandCenterYPx(s), dp(8f + s.windowPadDp())) { c ->
            scope.launch {
                camoBusy = false
                // Okuma sürerken seçili bir uygulamaya geçildiyse renk uygulanmaz
                val now = IslandAccessibilityService.currentFg
                if (now != null && now in settings.value.more.camoSkipList()) { CamoState.color = null; return@launch }
                if (c != null && !CamoState.close(CamoState.color, c)) CamoState.color = c
            }
        }
    }

    // ---------------------------------------------------------------- Uygulama açılış animasyonu
    private var faceFlashHidden = false
    private var faceLockHidden = false     // kilit ekranı Face ID animasyonu oynarken
    private var faceFlashAt = 0L
    private val faceFlash by lazy {
        FaceIdFlash(this, this, this, wm, settings) { on ->
            faceFlashHidden = on
            applyMainHidden()
        }
    }

    private fun startAppLock() = Unit

    /** Seçili bir uygulama öne gelince adanın yerinde Face ID animasyonu bir kez oynar (doğrulama yok, kilit yok). */
    private fun appLockOnSwitch(from: String, to: String) {
        val s = settings.value
        if (!s.more.appLockEnabled || from == to || to !in s.more.appLockList()) return
        if (landscape.active || appHide.hidden) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - faceFlashAt < 1_500L) return
        faceFlashAt = now
        val sw = wm.currentWindowMetrics.bounds.width()
        faceFlash.play(
            FaceIdAnchor(
                cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                cy = islandCenterYPx(s).toFloat(),
                pillW = dp(compactWidthFor(s, false)).toFloat(),
                pillH = dp(s.compactHeightDp).toFloat(),
                inverted = s.invertedIsland,
            )
        )
    }

    // ---------------------------------------------------------------- Canlı etkinlik: navigasyon / sipariş takibi
    private var liveContent: IslandContent.Live? = null
    private var liveJob: Job? = null
    private var liveEndJob: Job? = null
    private val liveAppIcons = HashMap<String, ImageBitmap?>()
    private var liveGoalJob: Job? = null
    private var liveLastAt = 0L

    private suspend fun onLive(raw: IslandContent.Live) {
        val s = settings.value
        val on = when (raw.kind) {
            LiveKind.Nav -> s.more.navIsland
            LiveKind.Delivery -> s.more.deliveryIsland
            LiveKind.Progress -> s.more.progressIsland
            LiveKind.Score -> s.more.scoreIsland
            LiveKind.Code -> s.more.otpIsland
        }
        if (!on || appHide.hidden) return
        val app = if (liveAppIcons.containsKey(raw.pkg)) liveAppIcons[raw.pkg] else iconFor(raw.pkg).also { liveAppIcons[raw.pkg] = it }
        var c = raw.copy(appIcon = app)
        val prev = liveContent
        // Öncelik: navigasyon > maç > sipariş > indirme. Düşük öncelikli yeni etkinlik, sürenin yerini almaz
        fun rank(k: LiveKind) = when (k) { LiveKind.Code -> 4; LiveKind.Nav -> 3; LiveKind.Score -> 2; LiveKind.Delivery -> 1; LiveKind.Progress -> 0 }
        // Maç: spor uygulamaları her gol için ayrı bildirim atabilir; aynı takımlarsa aynı etkinlik sayılır
        val sameMatch = prev != null && prev.kind == LiveKind.Score && c.kind == LiveKind.Score &&
            prev.teamA.equals(c.teamA, true) && prev.teamB.equals(c.teamB, true)
        if (prev != null && prev.key != c.key && !sameMatch && rank(c.kind) < rank(prev.kind)) return
        val goalNow = sameMatch && prev != null && c.scoreA + c.scoreB > prev.scoreA + prev.scoreB
        if (c.kind == LiveKind.Score) c = c.copy(goal = goalNow, sub = if (goalNow) "GOL!" else c.minute)
        val first = prev == null || (prev.key != c.key && !sameMatch)
        if (!first && prev == c) return
        // İndirme bildirimleri çok sık güncellenir: %1'den küçük değişiklik yarım saniyede bir yansıtılır
        if (!first && prev != null && c.kind == LiveKind.Progress && prev.kind == LiveKind.Progress &&
            kotlin.math.abs(c.progress - prev.progress) < 0.01f && c.title == prev.title &&
            android.os.SystemClock.uptimeMillis() - liveLastAt < 500L) return
        liveLastAt = android.os.SystemClock.uptimeMillis()
        liveContent = c
        // Bildirim kaldırılmadan kalırsa (ör. tek seferlik sipariş bildirimi) belirli bir süre sonra kendiliğinden biter
        liveJob?.cancel()
        liveJob = scope.launch {
            delay(when (c.kind) { LiveKind.Nav -> 15 * 60_000L; LiveKind.Progress -> 20 * 60_000L; LiveKind.Score -> 150 * 60_000L; LiveKind.Code -> 90_000L; else -> 90 * 60_000L })
            endLive()
        }
        // Doğrulama kodu: ada hemen açılıp kodu gösterir
        if (c.kind == LiveKind.Code && first && !assistantActive && !call.active) {
            syncBgOrder(); collapseJob?.cancel()
            update { IslandUiState(c, expanded = true) }
            scheduleCollapse(15_000)
            return
        }
        // İndirme bitti: kısa süre "Tamam" görünür, sonra biter
        if (c.kind == LiveKind.Progress && c.progress >= 0.999f && (prev == null || prev.progress < 0.999f)) {
            liveEndJob?.cancel()
            liveEndJob = scope.launch { delay(3_000L); endLive() }
        }
        // Maç bitti: kart kısa süre açık görünür, sonra biter
        if (c.kind == LiveKind.Score && c.phase == 2 && prev?.phase != 2) {
            liveEndJob?.cancel()
            liveEndJob = scope.launch { delay(8_000L); endLive() }
            if (!assistantActive && !call.active) { syncBgOrder(); collapseJob?.cancel(); update { IslandUiState(c, expanded = true) } }
            return
        }
        // Gol: ada birkaç saniye açılıp skoru gösterir, sonra kapalı skora döner
        if (goalNow && !assistantActive && !call.active) {
            syncBgOrder(); collapseJob?.cancel()
            update { IslandUiState(c, expanded = true) }
            liveGoalJob?.cancel()
            liveGoalJob = scope.launch {
                delay(5_000L)
                val cur = ui.value.content
                if (cur is IslandContent.Live && cur.kind == LiveKind.Score) {
                    liveContent = liveContent?.copy(goal = false, sub = liveContent?.minute.orEmpty())
                    liveContent?.let { lc -> update { it.copy(content = lc, expanded = false) } }
                }
            }
            return
        }
        if (c.kind == LiveKind.Delivery && c.stage == 3 && prev?.stage != 3) {
            // Teslim edildi: kart kısa süre açık görünür, sonra canlı etkinlik biter
            liveEndJob?.cancel()
            liveEndJob = scope.launch { delay(6_000L); endLive() }
            if (!assistantActive && !call.active) { syncBgOrder(); collapseJob?.cancel(); update { IslandUiState(c, expanded = true) } }
            return
        }
        if (assistantActive || call.ringing) return
        syncBgOrder()
        val cur = ui.value
        when {
            cur.content is IslandContent.Live -> update { it.copy(content = c) }          // güncelleme: açık / kapalı hali korunur
            first && !cur.expanded && (cur.content is IslandContent.Idle || centerBg(cur.content) != null) &&
                bgOrder.lastOrNull() == Bg.Live -> { collapseJob?.cancel(); update { IslandUiState(c, expanded = false) } }
            else -> update { it }                                                       // yan baloncuk yenilenir
        }
    }

    private fun endLive() {
        if (liveContent == null) return
        liveContent = null
        liveJob?.cancel(); liveEndJob?.cancel()
        if (ui.value.content is IslandContent.Live) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
        else update { it }
    }

    // ---------------------------------------------------------------- Kulaklık kartı
    private var hpStartedAt = 0L
    private var hpLast = ""
    private var hpLastAt = 0L
    private val hpTypes = setOf(
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET,
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES, android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
        23 /* TYPE_HEARING_AID */, 26 /* TYPE_BLE_HEADSET (API 31) */,
    )
    private val hpWired = setOf(
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET, android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    private val hpCallback = object : android.media.AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out android.media.AudioDeviceInfo>?) {
            // Kayıt sırasında mevcut cihazlar da bildirilir: servis başlarken bağlı olan kulaklık için kart açılmaz
            if (android.os.SystemClock.elapsedRealtime() - hpStartedAt < 3_000L) return
            val d = added?.firstOrNull { it.isSink && it.type in hpTypes } ?: return
            runCatching { onHeadphones(d) }
        }
    }

    private fun startHeadphones() {
        hpStartedAt = android.os.SystemClock.elapsedRealtime()
        getSystemService(android.media.AudioManager::class.java)
            ?.registerAudioDeviceCallback(hpCallback, android.os.Handler(android.os.Looper.getMainLooper()))
    }

    private fun onHeadphones(d: android.media.AudioDeviceInfo) {
        val s = settings.value
        if (!s.more.headphoneCard) return
        val wired = d.type in hpWired
        val pn = d.productName?.toString()?.trim().orEmpty()
        val name = if (pn.isBlank() || pn == Build.MODEL) (if (wired) "Kablolu kulaklık" else "Kulaklık") else pn
        val now = android.os.SystemClock.elapsedRealtime()
        if (name == hpLast && now - hpLastAt < 15_000L) return          // aynı kulaklık birden çok ses yolu bildirir
        hpLast = name; hpLastAt = now
        scope.launch {
            delay(1_200L)                                                  // pil bilgisi bağlantıdan biraz sonra gelir
            val pct = if (!wired && s.more.headphoneBattery) withContext(Dispatchers.Default) { btBattery(name, d) } else -1
            if (assistantActive || call.active || appHide.hidden || landscape.active) return@launch
            val cur = ui.value
            if (cur.expanded && cur.content !is IslandContent.Status) return@launch    // açık panel varken araya girmez
            val hp = IslandContent.Status(
                StatusKind.Headphones, title = name, subtitle = "Bağlandı",
                value = if (pct >= 0) "$pct" else "", progress = if (pct >= 0) pct / 100f else -1f, extra = if (wired) 1 else 0,
            )
            if (s.more.headphoneCompact && !s.dotMode) {
                // Önce küçük ada (simge + pil halkası); dokununca kart açılır
                collapseJob?.cancel()
                update { IslandUiState(hp, expanded = false) }
                scheduleCollapse(4_000L)
            } else showTemporarily(hp, 3_500L)
        }
    }

    /** Bluetooth kulaklığın pil yüzdesi (Yakındaki cihazlar izni verildiyse); bilinmiyorsa -1. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun btBattery(name: String, d: android.media.AudioDeviceInfo): Int = runCatching {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission("android.permission.BLUETOOTH_CONNECT") != android.content.pm.PackageManager.PERMISSION_GRANTED) return -1
        val bm = getSystemService(android.bluetooth.BluetoothManager::class.java) ?: return -1
        val devs = bm.adapter?.bondedDevices ?: return -1
        val addr = d.address.orEmpty()
        val dev = devs.firstOrNull { addr.isNotBlank() && it.address.equals(addr, ignoreCase = true) }
            ?: devs.firstOrNull { it.name == name } ?: return -1
        val lvl = dev.javaClass.getMethod("getBatteryLevel").invoke(dev) as? Int ?: -1
        if (lvl in 0..100) lvl else -1
    }.getOrDefault(-1)

    // ---------------------------------------------------------------- Bildirim kuşları
    private var perchedNotif: IslandContent.Notif? = null
    private var birdJob: Job? = null

    private fun birdKindFor(pkg: String): VisitorKind {
        val p = pkg.lowercase()
        return when {
            "whatsapp" in p || "telegram" in p || "messag" in p || "sms" in p || "signal" in p || "mms" in p -> VisitorKind.Bird
            "mail" in p || "gm" == p.substringAfterLast('.') || "outlook" in p -> VisitorKind.Butterfly
            "instagram" in p -> VisitorKind.Ladybug
            else -> listOf(VisitorKind.Bird, VisitorKind.Butterfly, VisitorKind.Ladybug, VisitorKind.Bee)[(pkg.hashCode() and 0x7fffffff) % 4]
        }
    }

    private fun perchBird(n: IslandContent.Notif) {
        perchedNotif = n
        PlayFx.emit(PlayEvent.BirdLand(birdKindFor(n.pkg)))
        scope.launch { syncWindow(winKey(ui.value)) }      // boştaki adaya dokunulabilsin
        birdJob?.cancel()
        birdJob = scope.launch { delay(60_000L); perchedNotif = null }
    }

    private fun birdAway() {
        perchedNotif = null
        birdJob?.cancel()
        PlayFx.emit(PlayEvent.BirdAway)
    }

    // ---------------------------------------------------------------- Şarj halkası
    private var chargeRingHidden = false
    private val chargeRing by lazy {
        ChargeRingFlash(this, this, this, wm) { on ->
            chargeRingHidden = on
            applyMainHidden()
        }
    }
    private val chargeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != Intent.ACTION_POWER_CONNECTED) return
            val s = settings.value
            if (!(s.more.chargeRing || s.more.chargeArcs) || landscape.active || appHide.hidden || assistantActive || call.active) return
            if (ui.value.content !is IslandContent.Idle && ui.value.content !is IslandContent.Media) return
            if (getSystemService(android.os.PowerManager::class.java)?.isInteractive != true) return
            val pct = getSystemService(android.os.BatteryManager::class.java)
                ?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return
            val sw = wm.currentWindowMetrics.bounds.width()
            chargeRing.play(
                FaceIdAnchor(
                    cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                    cy = islandCenterYPx(s).toFloat(),
                    pillW = dp(compactWidthFor(s, false)).toFloat(),
                    pillH = dp(s.compactHeightDp).toFloat(),
                    inverted = s.invertedIsland,
                ),
                pct,
                withArcs = s.more.chargeArcs,
                sub = chargeEta.currentText(),
            )
        }
    }

    // ---------------------------------------------------------------- Ada çekmecesi
    private var drawerOpenedAt = 0L

    private fun openDrawer() {
        if (assistantActive || call.active) return
        drawerOpenedAt = android.os.SystemClock.elapsedRealtime()
        FlashToggle.warmUp(this)
        showTemporarily(IslandContent.Drawer, 6_000L)
    }

    private fun onDrawerKey(k: DrawerKey) {
        playSfx(SoundEvent.Tap)
        if (DrawerActions.closesPanel(k)) {
            collapseJob?.cancel()
            collapseJob = scope.launch {
                collapseAndRest()
                // Ekran görüntüsünde çekmece görünmesin: ada küçüldükten sonra çekilir
                if (k == DrawerKey.Shot) delay(250L)
                DrawerActions.run(this@IslandOverlayService, k)
            }
        } else {
            DrawerActions.run(this, k)
            scheduleCollapse(5_000L)
        }
    }

    // ---------------------------------------------------------------- Özel tasarım fotoğrafı
    private var photoOpenedAt = 0L

    /** Boştaki adaya dokununca (ya da basılı tutunca) ada çentiksiz büyük bir karta açılıp seçilen fotoğrafı gösterir. */
    private fun openPhoto() {
        val s = settings.value
        if (!s.designPhotoReady() || assistantActive || call.active) return
        photoOpenedAt = android.os.SystemClock.elapsedRealtime()
        showTemporarily(IslandContent.Photo, (s.more.designPhotoSec * 1000f).toLong())
    }

    // ---------------------------------------------------------------- Ayrı dosyalara taşınan olaylar
    // SoundCapsule -> SoundCapsuleController.kt, ekran kaydı -> ScreenRecController.kt. Servisle konuşmaları IslandHost üzerinden olur.
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
            runCatching { landscape.setHidden(hidden) }
            this@IslandOverlayService.scope.launch { refreshDecor() }      // gizleme bayrağı ayarlandıktan sonra      // yatay moddaki ada da aynı gizleme kuralına uyar
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
    private val soundCapsule: SoundCapsuleController by lazy { SoundCapsuleController(host) }
    private val rec: ScreenRecController by lazy { ScreenRecController(host) }
    private val privacy: PrivacyController by lazy { PrivacyController(host) }
    private val ringer: RingerController by lazy { RingerController(host) }
    private val volume: VolumeController by lazy { VolumeController(host) }
    private val sysStatus: SystemStatusController by lazy { SystemStatusController(host) }
    private val clock: ClockController by lazy { ClockController(host) }
    private val voice: VoiceRecController by lazy { VoiceRecController(host) }
    private val chargeEta by lazy { ChargeTimeEstimator(this, settings) { IslandBus.post(IslandEvent.Status(it, 4_000L)) } }
    private val flipSilence by lazy { FlipSilence(this, settings, scope) }

    // ---- Kilit ekranı noktası
    private val lockDot by lazy {
        LockDotController(this, this, this, wm, settings, scope) { s ->
            val sw = wm.currentWindowMetrics.bounds.width()
            Triple(
                sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                islandCenterYPx(s).toFloat(),
                // iPhone modunda daire adanın solundaysa kilit noktası dairenin de soluna geçer
                dp(compactWidthFor(s, false)) / 2f + (if (s.iphoneCircleOn() && s.iphoneDir() < 0) dp(s.iphoneCircleSizeDp() + s.iphoneGapDp) else 0),
            )
        }
    }

    // ---- Kilit ekranı Face ID animasyonu
    private val faceId by lazy {
        FaceIdController(this, this, this, wm, settings, scope) { s ->
            val sw = wm.currentWindowMetrics.bounds.width()
            FaceIdAnchor(
                cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                cy = islandCenterYPx(s).toFloat(),
                pillW = dp(compactWidthFor(s, false)).toFloat(),
                pillH = dp(s.compactHeightDp).toFloat(),
                inverted = s.invertedIsland,
            )
        }.also { c ->
            c.onCover = { on ->
                lockDot.suppress = on; lockDot.recheck()
                runCatching { lockIcon.suppress = on; lockIcon.recheck() }
                // Face ID animasyonu adanın yerinde oynar: o sürece asıl ada gizlenir (üst üste iki ada görünmesin)
                faceLockHidden = on
                applyMainHidden()
            }
        }
    }

    // ---- Kilit ekranında adanın solunda kilit simgesi (açılınca açık kilit) -> LockIcon.kt
    private val lockIcon by lazy {
        LockIconController(this, this, this, wm, settings, scope,
            canShow = { ui.value.content is IslandContent.Idle && !settings.value.dotMode },
        ) { s ->
            val sw = wm.currentWindowMetrics.bounds.width()
            FaceIdAnchor(
                cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                cy = islandCenterYPx(s).toFloat(),
                pillW = dp(compactWidthFor(s, false)).toFloat(),
                pillH = dp(s.compactHeightDp).toFloat(),
                inverted = s.invertedIsland,
            )
        }
    }

    // ---------------------------------------------------------------- Yeni ada düğmeleri (IslandActions)
    private fun wireIslandActions() {
        IslandActions.serviceLive = true
        IslandActions.onLowPower = { scope.launch { runCatching { onLowPowerButton() } } }
        IslandActions.onTimerPause = { scope.launch { runCatching { clock.pauseResume() } } }
        IslandActions.onTimerCancel = { scope.launch { runCatching { clock.cancel() } } }
        IslandActions.onTimerRestart = { scope.launch { runCatching { clock.restart() } } }
        IslandActions.onTimerStart = { ms -> scope.launch { runCatching { clock.start(ms) } } }
        IslandActions.onOpenTimerSetter = { scope.launch { runCatching { clock.openSetter() } } }
        IslandActions.onFocusStart = { scope.launch { runCatching { clock.startFocus() } } }
        IslandActions.onAlarmSnooze = { scope.launch { runCatching { clock.snooze() } } }
        IslandActions.onAlarmDismiss = { scope.launch { runCatching { clock.dismiss() } } }
        IslandActions.onFocusToggle = { scope.launch { runCatching { toggleFocus() } } }
        IslandActions.onVoiceStop = { scope.launch { runCatching { voice.onStop() } } }
        IslandActions.onHoldExpand = { scope.launch { runCatching { onHoldExpand() } } }
        IslandActions.onHoldPress = { on -> scope.launch { runCatching { onHoldPress(on) } } }
    }

    /** Düşük pil kartındaki pil kapsülü: düşük güç modunu açar (açıksa kapatır). */
    private fun onLowPowerButton() {
        val st = ui.value.content as? IslandContent.Status ?: return
        if (st.kind != StatusKind.LowBattery && st.kind != StatusKind.LowBatteryCard) return
        sysStatus.onLowBatteryTap(st)
        scheduleCollapse(4_000L)
    }

    /** Genişlemiş Rahatsız Etmeyin kartında aya dokunma: modu açar / kapatır (bildirim erişimi yeterli). */
    private fun toggleFocus() {
        val st = ui.value.content as? IslandContent.Status ?: return
        if (st.kind != StatusKind.Focus) return
        val on = st.extra != 1
        val ok = IslandNotificationListener.setFocus(on)
        if (!ok) {
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        sysStatus.markFocus(on)
        update { u -> if (u.content is IslandContent.Status && (u.content as IslandContent.Status).kind == StatusKind.Focus)
            u.copy(content = st.copy(value = if (on) "Açık" else "Kapalı", extra = if (on) 1 else 0)) else u }
        scheduleCollapse(3_000L)
    }

    // ---------------------------------------------------------------- Basılı tutarak açma
    private var holdGrowJob: Job? = null

    /** Parmak olaylı adaya değdi: pencere açık boyuta önceden büyür (ada basılı tutarken büyüyebilsin). */
    private fun onHoldPress(on: Boolean) {
        val cur = ui.value
        if (cur.expanded) return
        holdGrowJob?.cancel()
        if (!on) {
            holdGrowJob = scope.launch { delay(260); if (!ui.value.expanded) syncWindow(winKey(ui.value)) }
            return
        }
        val key = winKey(cur.copy(expanded = true))
        holdGrowJob = scope.launch { syncWindow(key) }
    }

    /** Basılı tutma süresi doldu: ada açılır (müzik dahil). */
    private fun onHoldExpand() {
        holdGrowJob?.cancel()
        val cur = ui.value
        if (cur.expanded) return
        when (val c = cur.content) {
            IslandContent.Idle -> Unit
            is IslandContent.Media -> { collapseJob?.cancel(); update { it.copy(expanded = true) }; scheduleMusicCollapse() }
            is IslandContent.Call -> if (c.isIncoming) call.expandIncoming() else call.toggleExpanded()
            else -> onPillTap()
        }
    }

    // ---- Yatay mod: telefon yan çevrilince dikey ada + simgesi düz yan baloncuklar -> LandscapeIsland.kt
    private val landscape by lazy {
        LandscapeController(
            this, this, this, wm, settings, uiShown, lyricsShown, scope,
            LandActions(
                onTap = ::onLandTap, onLongPress = ::onLandLong, onSwipe = ::onPillSwipe,
                onCommand = ::onCommand, onOutput = ::openOutputPanel, onLaunch = ::launchFavorite,
                onOpenMusic = ::openMusicApp, onSideTap = ::onSideTap, onNotifSwipeSide = ::onNotifSwipeSide,
                onAnswer = call::onAnswer, onDecline = call::onDecline, onSpeaker = call::onSpeaker,
                onRecStop = rec::onStop, onSoundCapsuleStop = soundCapsule::onStop,
            ),
            onActive = { on -> setMainIslandHidden(on) },
            onZoneChanged = { syncSideMusic(); call.syncSideButtons() },      // dönüş / ada bölgesi değişti: kenar çubukları yeniden kurulur
        )
    }

    /** Yatay mod: boştayken dokununca yalnızca favori paneli açılır (çeviri paneli yatay modda yok); dolu adada normal işlem. */
    private fun onLandTap() {
        if (ui.value.content is IslandContent.Idle) {
            val s = settings.value
            if (s.favoritesReady() && s.favoritesTrigger == 0) { playSfx(SoundEvent.Tap); favorites.open() }
        } else onPillTap()
    }
    private fun onLandLong() {
        if (ui.value.content is IslandContent.Idle) {
            val s = settings.value
            if (s.favoritesReady() && s.favoritesTrigger == 1) favorites.open()
        } else onPillLongPress()
    }
    private var mainHidden = false
    /** Yatay mod açıkken normal ada penceresi görünmez olur (çizilmez, dokunuş almaz); kapanınca geri gelir. */
    private fun setMainIslandHidden(h: Boolean) {
        mainHidden = h
        applyMainHidden()
    }
    private fun applyMainHidden() {
        val vis = if (mainHidden || thumbHidden || faceFlashHidden || faceLockHidden || chargeRingHidden) View.INVISIBLE else View.VISIBLE
        runCatching { dialog?.window?.decorView?.visibility = vis }
        runCatching { touchDialog?.window?.decorView?.visibility = vis }     // gizliyken dokunuşa tepki vermesin
        runCatching { blurDialog?.window?.decorView?.visibility = vis }
        refreshDecor()
    }

    // ---- Ada süsleri: adanın üstünde karakter, sarkan süsler, mürekkep damlası -> IslandDecor.kt
    private val decorAnchor = MutableStateFlow<DecorAnchor?>(null)
    private val decor by lazy { DecorOverlay(this, this, this, wm, settings, decorAnchor) }
    // ---- Oyuncak ada (PlayFx.kt / PlayOverlay.kt / PlayIsland.kt)
    private val playAnchor = MutableStateFlow<PlayAnchor?>(null)
    private val play by lazy { PlayOverlay(this, this, this, wm, settings, playAnchor) }
    private val playCtl by lazy { PlayController(this, settings, scope) }
    private var playOn = false
    private val gameTaps = ArrayDeque<Long>()
    private val rhythmTaps = ArrayList<Long>()
    private var rhythmJob: Job? = null

    /** Ritim kısayolları: boştaki adaya yapılan dokunuşlar biriktirilir, 0,8 sn ara verilince ritim karşılaştırılır. */
    private fun rhythmTap() {
        val s = settings.value
        if (!s.playRhythm || ui.value.content !is IslandContent.Idle) { rhythmTaps.clear(); return }
        val now = android.os.SystemClock.uptimeMillis()
        if (rhythmTaps.isNotEmpty() && now - rhythmTaps.last() > 1_500L) rhythmTaps.clear()
        rhythmTaps.add(now)
        rhythmJob?.cancel()
        rhythmJob = scope.launch {
            delay(800L)
            val taps = rhythmTaps.toList()
            rhythmTaps.clear()
            if (taps.size < 3) return@launch                       // tek / çift dokunuş kısayol sayılmaz
            val pat = Rhythm.classify(taps.zipWithNext { a, b -> b - a })
            val hit = Rhythm.parse(settings.value.playRhythms).firstOrNull { it.first == pat } ?: return@launch
            PlayFx.kick(3f)
            launchFavorite(hit.second)
        }
    }

    /** Gizli oyun: boştaki adaya 1,8 sn içinde 5 kez dokununca "Ada Pong" açılır. */
    private fun secretGameTap(): Boolean {
        val s = settings.value
        if (!s.playGame || ui.value.content !is IslandContent.Idle) { gameTaps.clear(); return false }
        val now = android.os.SystemClock.uptimeMillis()
        gameTaps.addLast(now)
        while (gameTaps.isNotEmpty() && now - gameTaps.first() > 1_800L) gameTaps.removeFirst()
        if (gameTaps.size < 5) return false
        gameTaps.clear()
        PlayFx.kick(5f)
        IslandGameActivity.open(this)
        return true
    }
    private var decorOn = false

    /** Süslerin dayanacağı kapalı adanın ekrandaki yeri ve süslerin görünüp görünmeyeceği. */
    private fun refreshDecor() {
        val s = settings.value
        val dOn = decor.wanted(s)
        val pOn = play.wanted(s)
        if (!dOn && !pOn) { decorAnchor.value = null; playAnchor.value = null; return }
        val u = ui.value
        val c = u.content
        val pill = pillCompactSizeDp(s, c)
        val wDp = when {
            pill != null -> pill.first
            c is IslandContent.Call && !c.isIncoming -> maxOf(CALL_COMPACT_W_DP, s.compactWidthDp)
            else -> compactWidthFor(s, u.compactLyrics && c is IslandContent.Media)
        }
        val hPx = dp(pill?.second ?: s.compactHeightDp)
        val sw = wm.currentWindowMetrics.bounds.width()
        val visible = !u.expanded && !u.swallow && u.stack.isEmpty() && !mainHidden && !thumbHidden && !appHide.hidden &&
            c !is IslandContent.Assistant && (s.showIdle || c !is IslandContent.Idle) && !s.dotMode
        val anchor = DecorAnchor(
            cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
            top = islandTopPx(s, hPx).toFloat(),
            w = dp(wDp).toFloat(), h = hPx.toFloat(),
            inverted = s.invertedIsland, visible = visible,
            screenH = screenHPx().toFloat(),
        )
        decorAnchor.value = if (dOn) anchor else null
        playAnchor.value = if (!pOn) null else PlayAnchor(
            cx = anchor.cx, top = anchor.top, w = anchor.w, h = anchor.h,
            inverted = anchor.inverted, compactVisible = visible,
            shown = !mainHidden && !thumbHidden && !appHide.hidden && !landscape.active,
            screenW = sw.toFloat(), screenH = anchor.screenH,
            color = islandPanelColor(s, (c as? IslandContent.Media)?.artColor ?: lastMedia?.artColor ?: 0, opaque = true),
        )
    }

    // ---- Fener (flaş) göstergesi -> TorchOverlay.kt
    private val torch by lazy {
        TorchController(this, this, this, wm, settings, scope) { s ->
            val sw = wm.currentWindowMetrics.bounds.width()
            val h = dp(s.compactHeightDp).toFloat()
            TorchAnchor(
                cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
                top = islandCenterYPx(s) - h / 2f,
                halfW = dp(compactWidthFor(s, false)) / 2f,
                h = h,
                inverted = s.invertedIsland,
            )
        }
    }

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
    private val sideVolume: SideVolumeOverlay by lazy { SideVolumeOverlay(this, this, this, wm) }
    private val sideMusic: SideMusicOverlay by lazy { SideMusicOverlay(this, this, this, wm, mediaState) { c -> IslandBus.send(c) } }

    /** Müzik varsa (ve ayar açıksa) kenar çubuklarını gösterir; müzik uygulamasındayken, gelen aramada ya da müzik yokken gizler. */
    private fun syncSideMusic() {
        val p = SideMusicStore.flow(applicationContext).value
        val c = ui.value.content
        val should = p.enabled && lastMedia != null && !hidingMedia() &&
            !(c is IslandContent.Call && c.isIncoming) &&
            android.provider.Settings.canDrawOverlays(this)
        if (should) sideMusic.show(p) else sideMusic.hide()

        // Kenar ses tuşları (müzik çubuğundan bağımsız)
        val v = SideVolumeStore.flow(applicationContext).value
        val showVol = v.enabled && (!v.onlyWithMusic || lastMedia != null) &&
            !(c is IslandContent.Call && c.isIncoming) &&
            android.provider.Settings.canDrawOverlays(this)
        if (showVol) sideVolume.show(v) else sideVolume.hide()
    }
    private val notifBanner by lazy { NotifBannerOverlay(this, this, this, wm, scope, { settings.value }) { n -> openNotification(n) } }
    private val call: CallController by lazy { CallController(host, sideCall) }

    // ---- Çizerek komut -> GestureCmd.kt
    private val gesturePad: GesturePad by lazy { GesturePad(this) { a -> runGestureAction(a) } }

    /** Harfe atanan komutu çalıştırır; servis gerektirenler burada, diğerleri GestureAction.runSimple'da. */
    private fun runGestureAction(a: Int) {
        val s = settings.value
        when (a) {
            GestureAction.TRANSLATE ->
                if (s.translateReady()) openTranslate()
                else android.widget.Toast.makeText(this, "Önce ayarlardan Çeviri'yi aç", android.widget.Toast.LENGTH_SHORT).show()
            GestureAction.FAVORITES ->
                if (s.favoritesReady()) favorites.open()
                else android.widget.Toast.makeText(this, "Önce ayarlardan Favoriler'i aç ve uygulama seç", android.widget.Toast.LENGTH_SHORT).show()
            GestureAction.DOODLE -> IslandDoodleActivity.open(this)
            else -> GestureAction.runSimple(this, a)
        }
    }

    /** Basılı tutma: boştayken (her iki tetikleyici modunda) favorileri açar; diğer hallerde dokunmayla aynı davranır. */
    private fun onPillLongPress() {
        val cur = ui.value
        val s = settings.value
        if (cur.content is IslandContent.Idle) {
            if (GestureStore.flow(applicationContext).value.enabled) {                                              // çizerek komut
                if (gesturePad.canShow()) gesturePad.show()
                else android.widget.Toast.makeText(this, "Çizerek komut için erişilebilirlik ya da 'üstte göster' izni gerekli", android.widget.Toast.LENGTH_LONG).show()
            }
            else if (s.showIdle && s.more.timerIdleTrigger == 2) clock.openSetter()        // zamanlayıcı kurma paneli
            else if (s.designPhotoReady() && s.more.designPhotoTrigger == 1) openPhoto()
            else if (s.more.drawerEnabled && s.showIdle && s.more.drawerTrigger == 1) openDrawer()
            else if (s.translateReady() && s.translateTrigger == 1) openTranslate()
            else if (s.favoritesReady()) favorites.open()
            else if (s.playDoodle) IslandDoodleActivity.open(this)          // karalama tahtası
            else if (s.playBreath && !BreathSession.active) BreathSession.start()   // nefes egzersizi
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
        if (settings.value.more.trashIsland) PlayFx.emit(PlayEvent.Trashed(n.icon))     // çöp kutusu ada
        if (settings.value.more.notifShatter) PlayFx.emit(PlayEvent.Shatter(n.title.ifBlank { n.text }, shatterStyleFor(settings.value.fxWeather)))
        notif.clear(); notif.finish()
    }

    /** Akıllı bildirim çipi: ertele / kopyala / ara / harita / bağlantı. */
    private fun onNotifChip(n: IslandContent.Notif, chip: NotifChip) {
        val cur = ui.value.content as? IslandContent.Notif ?: return
        if (cur.key != n.key || n.key.isEmpty()) return
        when (chip.act) {
            CHIP_SNOOZE -> {
                val ms = chip.value.toLongOrNull() ?: return
                val ok = IslandNotificationListener.snooze(n.key, ms)
                if (ok && perchedNotif?.key == n.key) birdAway()
                notifChipDone(cur, chip, if (ok) "Ertelendi ✓" else "Ertelenemedi")
            }
            CHIP_COPY -> notifChipDone(cur, chip, if (NotifSmart.copy(this, chip.value)) "Kopyalandı ✓" else "Kopyalanamadı")
            CHIP_DIAL, CHIP_MAP, CHIP_LINK -> {
                notif.clear(); notif.finish()
                NotifSmart.open(this, chip)
            }
        }
    }

    /** Çipin yazısı kısa süre sonucu gösterir, sonra bildirim adadan kalkar. */
    private fun notifChipDone(n: IslandContent.Notif, chip: NotifChip, label: String) {
        val done = n.copy(chips = n.chips.map { if (it == chip) it.copy(label = label, act = CHIP_DONE) else it })
        notif.clear()
        update { it.copy(content = done) }
        scope.launch {
            delay(1_100L)
            if ((ui.value.content as? IslandContent.Notif)?.key == n.key) notif.finish()
        }
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
                StatusKind.Timer, StatusKind.Alarm -> Bg.Timer
                StatusKind.VoiceRec -> Bg.Voice
                StatusKind.Camera, StatusKind.Mic -> Bg.Privacy
                else -> return
            }
            is IslandContent.Notif -> Bg.Notif
            is IslandContent.Live -> Bg.Live
            else -> return      // SoundCapsule dinleme noktası dokununca açılmaz
        }
        // 0 = sağ iç, 1 = sol iç, 2 = sağ dış, 3 = sol dış (dış yuvalar ±2: animasyon dış noktadan başlar)
        val from = if (settings.value.iphoneCircleOn()) settings.value.iphoneDir() else when (index) { 0 -> 1; 1 -> -1; 2 -> 2; else -> -2 }
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
            Bg.SoundCapsule -> Unit
            Bg.Live -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse(8_000) }
            Bg.Voice -> { update { IslandUiState(c, expanded = true, fromSide = from) }; scheduleCollapse(6_000) }
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
        val xs = xExpandedSizeDp(c, settings.value, screenWDp)
        val key = WinKey(kindOf(c), true, false, settings.value, false, if (fits) 1 else 0,
            xcw = xCompactWidthDp(c, settings.value) ?: 0f, xew = xs?.first ?: 0f, xeh = xs?.second ?: 0f)
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
        launchWithAnim(s.musicOpenAnim, pickAtomic(s.launchClassic, s.launchAtomic), s.launchSpeed, fluid = s.musicFluidAnim && s.musicExitAnim, open = open)
    }

    /** Adayı dikey kaydırma: aşağı (+1) genişletir, yukarı (-1) küçültür. */
    private fun onPillSwipe(rawDir: Int) {
        val cur = ui.value
        val s = settings.value
        val dir = if (s.invertedIsland && !landscape.active) -rawDir else rawDir      // ters adada yön aynalanır: yukarı kaydırma açar, aşağı kaydırma kapatır
        if (cur.swallow) return
        if (dir > 0 && !landscape.active && openStack()) return
        // Gelen arama: yukarı kaydırınca küçülür (cevaplamadan / reddetmeden), aşağı kaydırınca yeniden açılır
        val cc = cur.content
        if (cc is IslandContent.Call && cc.isIncoming && s.more.incomingMinimize) {
            if (dir < 0 && cur.expanded) call.minimizeIncoming() else if (dir > 0 && !cur.expanded) call.expandIncoming()
            return
        }
        // Zamanlayıcı / alarm / ses kaydı / kurma paneli: yukarı kaydırınca küçülür (çalan alarm hariç), aşağı kaydırınca açılır
        if (cc is IslandContent.Status && (cc.kind == StatusKind.Timer || cc.kind == StatusKind.Alarm || cc.kind == StatusKind.VoiceRec)) {
            if (cc.phase == 2) return
            if (dir < 0 && cur.expanded) { collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() } }
            else if (dir > 0 && !cur.expanded) onPillTap()
            return
        }
        when (val c = cur.content) {
            // Başparmak modu: boştaki adayı aşağı kaydırınca ada başparmağın altına iner
            IslandContent.Idle -> if (dir > 0 && s.more.thumbEnabled && s.showIdle && !landscape.active) ThumbStore.set(this, true)
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
            is IslandContent.SoundCapsule -> when {
                c.phase == SoundCapsulePhase.Found -> soundCapsule.end()
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
    private fun launchWithAnim(animOn: Boolean, atomic: Boolean = false, speed: Float = 1f, fluid: Boolean = false, open: () -> Unit) {
        collapseJob?.cancel(); collapseJob = scope.launch { collapseAndRest() }
        if (!animOn || landscape.active) { open(); return }
        if (launching) return
        launching = true
        // Atom ada: kart adanın kendisinden açıldığı için ada küçülmesini beklemeden başlar
        scope.launch { delay(if (atomic || fluid) 0L else LAUNCH_LEAD_MS); startLaunchTransition(open, atomic, speed, fluid) }
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
        if (perchedNotif?.key == n.key && n.key.isNotEmpty()) birdAway()      // konan kuş da uçar
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
        launchWithAnim(s.notifLaunchAnim, pickAtomic(s.launchClassic, s.launchAtomic), s.launchSpeed, open = open)
    }

    private fun startLaunchTransition(open: () -> Unit, atomic: Boolean = false, speed: Float = 1f, fluid: Boolean = false) {
        val s = settings.value
        val sw = wm.currentWindowMetrics.bounds.width()
        val chPx = dp(s.compactHeightDp)
        val spec = LaunchSpec(
            cx = sw / 2f + cutCenterXOffsetPx + dp(s.offsetXDp),
            top = islandTopPx(s, chPx).toFloat(),
            w = dp(compactWidthFor(s, false)).toFloat(),
            h = chPx.toFloat(),
            speed = speed,
            color = islandPanelColor(s, (ui.value.content as? IslandContent.Media)?.artColor ?: 0, opaque = true),
        )
        val added = overlays.show(launchSlot) { onFin ->
            val onCov = { runCatching { open() }; Unit }                  // ekran tamamen siyah: uygulamayı aç
            if (fluid) FluidLaunchFromIsland(spec, onCovered = onCov, onFinished = onFin)
            else if (atomic) AtomicLaunch(spec, onCovered = onCov, onFinished = onFin)
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
        if (s.musicFluidAnim) Unit                       // Akıcı yutma: pencere + şişen ada aynı katmanda çizilir
        else if (s.musicExitStretch) startStretchOverlay() else swallowIsland()   // uzayan ada ekran katmanında çizilir, ada penceresi büyümez
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
        if (!sw.musicExitSwallow || sw.musicExitStretch || sw.musicFluidAnim) return   // uzayan ada / akıcı yutma ayrı katmanda oynar
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
            top = islandTopPx(s, chPx).toFloat(),
            w = dp(compactWidthFor(s, false)).toFloat(),
            h = chPx.toFloat(),
            speed = s.musicExitSpeed,
            color = islandPanelColor(s, lastMedia?.artColor ?: 0, opaque = fullStart || s.musicFluidAnim),   // tam ekranda panel opak (ekranı örtmeli)
        )
        if (s.musicFluidAnim) {
            overlays.show(swallowSlot) { onDone -> FluidSwallowToIsland(spec, onFinished = onDone) }
            return
        }
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
            top = islandTopPx(s, chPx).toFloat(),
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

    private val accentCache = HashMap<String, Int>()

    /** Uygulama simgesinin baskın rengi (önbellekli). */
    private fun accentFor(pkg: String, icon: ImageBitmap): Int =
        accentCache.getOrPut(pkg) { runCatching { appAccentColor(icon.asAndroidBitmap()) }.getOrDefault(0) }

    private suspend fun iconFor(pkg: String): ImageBitmap? {
        iconCache.get(pkg)?.let { return it }
        val bmp = withContext(Dispatchers.Default) {
            runCatching { packageManager.getApplicationIcon(pkg).toBitmap(72, 72).asImageBitmap() }.getOrNull()
        }
        if (bmp != null) iconCache.put(pkg, bmp)
        return bmp
    }

    // ---------------------------------------------------------------- Pencere
    /**
     * "Bildirim simgelerine yer aç": durum çubuğunun solundaki simgeler kapalı adanın sol ucuna yaklaşınca
     * adanın sol ucu simgelerin ötesine çekilir, sağ ucu yerinde kalır (ada soldan kısalır, kaymaz).
     * Ada kamera deliğini her zaman örter: sol uç kameranın merkezinden ada yüksekliğinin yarısından fazla
     * sağa geçmez. Kaydedilen ayarlar değişmez; yalnızca servisin kullandığı kopya.
     */
    /**
     * Çentik modu: kapalı ada ekranın en tepesinden başlar (üst kenarı ekrana yapışık), kamera deliğini örter ve
     * deliğin altına ada yüksekliğinin yarısı + ayardaki ek derinlik kadar iner. Genişlik çentik genişliği olur.
     * Yatay ekranda uygulanmaz (yatay ada kendi düzenini kullanır). Kaydedilen ayarlar değişmez.
     */
    private fun fitNotch(s: IslandSettings): IslandSettings {
        if (!s.more.notchMode) return s
        val b = runCatching { wm.currentWindowMetrics.bounds }.getOrNull() ?: return s
        if (b.width() > b.height()) return s
        val d = density
        val cy = cutCenterYPx / d
        val h = maxOf(cy + 10f, cy + s.compactHeightDp / 2f + s.more.notchDepthDp, 16f)
        // Pencere konumuyla aynı tam sayı hesabı: üst kenar ekranın 1 px üstünde kalır (arada boşluk olmasın)
        val base = (cutCenterYPx - dp(h) / 2).coerceAtLeast(0)
        return s.copy(compactWidthDp = s.more.notchWidthDp, compactHeightDp = h, offsetYDp = -(base + 1) / d)
    }

    private fun fitStatusIcons(s: IslandSettings, e: StatusIcons.Edges): IslandSettings {
        StatusIcons.enabled = s.more.avoidIcons && !s.dotMode && !s.iphoneMode && !s.invertedIsland
        val sw = runCatching { wm.currentWindowMetrics.bounds.width() }.getOrDefault(0)
        if (sw <= 0) return s
        val d = density
        val cam = sw / 2f + cutCenterXOffsetPx
        StatusIcons.cameraX = cam.toInt()        // tarama kameranın solunu buna göre ayırır
        val cx = cam + s.offsetXDp * d
        val w = s.compactWidthDp * d
        val l0 = cx - w / 2f
        val r0 = cx + w / 2f
        StatusIcons.islandLeft = l0.toInt()
        if (!StatusIcons.enabled || e.left < 0) return s
        val want = e.left + 6f * d
        if (want <= l0) return s
        val maxLeft = cam - s.compactHeightDp * d * 0.55f      // kamera deliği açıkta kalmasın
        val newL = minOf(want, maxLeft)
        if (newL <= l0) return s
        StatusIcons.islandLeft = newL.toInt()
        // Yalnızca sol uç çekilir; sağ uç olduğu yerde kalır (ada kaymaz, soldan kısalır)
        return s.copy(compactWidthDp = (r0 - newL) / d, offsetXDp = ((newL + r0) / 2f - cam) / d)
    }

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

    // ---- Ters ada: ada ekranın altında; kamera deliğinin ekranın üstüne olan uzaklığı, ekranın altına göre aynalanır
    private fun screenHPx(): Int = wm.currentWindowMetrics.bounds.height()

    /** Kapalı adanın üst kenarının ekran Y'si (normalde üstten, ters adada alttan ölçülür). */
    private fun islandTopPx(s: IslandSettings, chPx: Int): Int {
        val t = (cutCenterYPx - chPx / 2).coerceAtLeast(0) + dp(s.offsetYDp)
        return if (s.invertedIsland) screenHPx() - t - chPx else t
    }

    /** Kapalı adanın dikey merkezinin ekran Y'si. */
    private fun islandCenterYPx(s: IslandSettings): Int {
        val c = cutCenterYPx + dp(s.offsetYDp)
        return if (s.invertedIsland) screenHPx() - c else c
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
        runCatching { decor.detach() }
        runCatching { play.detach() }
        // Erişilebilirlik bağlantısı değişince dış söz penceresinin türü de değişir: yeniden kur.
        // Önce durum çubuğu örtüsü, sonra söz penceresi, en son ada eklenir; böylece ada her zaman üstte kalır ve sözler adanın "içinden" çıkar.
        runCatching { detachOutside() }
        runCatching { statusCover.attach() }
        runCatching { edgeGlow.attach() }
        runCatching { decor.attach() }
        runCatching { play.attach() }
        runCatching { syncOutside(settings.value) }
        safeAttachOverlay()
        composeView?.visibility = if (appHide.hidden) View.INVISIBLE else View.VISIBLE
        scope.launch { syncWindow(winKey(ui.value)) }
        runCatching { lockDot.rehost() }     // kilit noktası adanın içinde durabilir: ada yeniden eklenince nokta yine üstte kalsın
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
            // Sahipler kaldırılmaz: pencere eklenip hemen kaldırılırsa bekleyen ilk çizim sahibi bulamayıp çöker
        }
        runCatching { dialog?.dismiss() }
        runCatching { touchDialog?.dismiss() }
        runCatching { blurDialog?.dismiss() }
        dialog = null
        touchDialog = null
        blurDialog = null
        blurCorner = -1f
        touchView = null
        logicalBox.value = LogicalBox(1, 0)
        composeView = null
        calibDy = 0; calibTries = 0
        curW = 1; curH = 1
    }

    private fun buildOverlay(host: Context, type: Int) {
        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@IslandOverlayService)
            setViewTreeSavedStateRegistryOwner(this@IslandOverlayService)
            setContent {
                // Mantıksal pencere kutusu: genişlik = eski pencere genişliği, yatay konum = eski pencerenin yeri.
                // Yalnızca yerleşimde okunur: kutu değişince yeniden oluşturma değil, yalnızca yeniden yerleşim olur.
                Box(
                    Modifier.layout { m, c ->
                        val box = logicalBox.value
                        val w = box.w.coerceAtLeast(1)
                        val h = c.maxHeight.coerceAtLeast(1)
                        val p = m.measure(Constraints.fixed(w, h))
                        layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - w) / 2 + box.x, 0) }
                    }
                ) {
                // Eskiden pencere kenarı adanın dışına taşanları kırpıyordu: aynı kırpma mantıksal kutuda yapılır
                Box(Modifier.fillMaxSize().clipToBounds()) {
                PlayIslandHost(settings, uiShown) {
                IslandRoot(
                    state = uiShown, settingsFlow = settings, lyricsFlow = lyricsShown,
                    onTap = ::onPillTap, onCommand = ::onCommand, onOutput = ::openOutputPanel,
                    onLongPress = ::onPillLongPress, onLaunch = ::launchFavorite,
                    onOpenMusic = ::openMusicApp, onSwipe = ::onPillSwipe,
                    onAnswer = call::onAnswer, onDecline = call::onDecline, onSpeaker = call::onSpeaker,
                    onRecStop = rec::onStop, onSoundCapsuleStop = soundCapsule::onStop,
                    onNotifSwipeSide = ::onNotifSwipeSide, onSideTap = ::onSideTap, onSidePress = ::onSidePress,
                    onStackClose = ::onStackTap,
                )
                }
                }
                }
            }
        }
        composeView = view
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
        d.window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)   // görüntü penceresi dokunuş almaz (touchDialog alır)
        buildBlurWindow(host, type)         // en altta: bulanıklık
        buildTouchWindow(host, type)        // görüntünün ALTINDA: önce eklenir
        d.window?.decorView?.let { stripInsets(it) }   // show'dan ÖNCE: ilk inset dağıtımı da yutulur
        d.show()
        d.window?.decorView?.let { stripInsets(it) }   // show'dan SONRA: kalan dolguları sıfırla
        dialog = d
        if (secureOn) applySecure(true)     // pencere yeniden kurulduysa kayıt dışı bayrağı korunur
        applyMainHidden()
        applyRefreshRate()
    }

    private fun buildBlurWindow(host: Context, type: Int) {
        val bd = Dialog(ContextThemeWrapper(host, android.R.style.Theme_Translucent_NoTitleBar))
        bd.setCancelable(false)
        bd.setContentView(View(this), ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bd.window?.apply {
            setType(type)
            setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setDecorFitsSystemWindows(false)
            decorView.setPadding(0, 0, 0, 0)
            val lp = WindowManager.LayoutParams().also { it.copyFrom(attributes) }
            lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        bd.window?.decorView?.let { stripInsets(it) }
        bd.show()
        bd.window?.decorView?.let { stripInsets(it) }
        blurDialog = bd
    }

    /**
     * Görünmez dokunma penceresi: adanın eski (mantıksal) pencere boyutu ve yerinde durur, dokunuşları görüntü
     * penceresindeki içeriğe aynı ekran konumuyla iletir. Cam bulanıklığı da bu pencerede (yalnızca ada bölgesi).
     */
    private fun buildTouchWindow(host: Context, type: Int) {
        val tv = object : View(this) {
            private val a = IntArray(2)
            private val b = IntArray(2)
            override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
                val cv = composeView ?: return true
                getLocationOnScreen(a)
                cv.getLocationOnScreen(b)
                val e = android.view.MotionEvent.obtain(ev)
                e.offsetLocation((a[0] - b[0]).toFloat(), (a[1] - b[1]).toFloat())
                try { cv.dispatchTouchEvent(e) } finally { e.recycle() }
                return true      // pencere dokunuşu yutar (eskiden olduğu gibi altına geçmez)
            }
        }
        val td = Dialog(ContextThemeWrapper(host, android.R.style.Theme_Translucent_NoTitleBar))
        td.setCancelable(false)
        td.setCanceledOnTouchOutside(false)
        td.setContentView(tv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        td.window?.apply {
            setType(type)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            setGravity(Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setDecorFitsSystemWindows(false)
            decorView.setPadding(0, 0, 0, 0)
            val lp = WindowManager.LayoutParams().also { it.copyFrom(attributes) }
            lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
            lp.format = PixelFormat.TRANSLUCENT
            lp.fitInsetsTypes = 0
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            attributes = lp
        }
        td.window?.decorView?.let { stripInsets(it) }
        td.show()
        td.window?.decorView?.let { stripInsets(it) }
        touchDialog = td
        touchView = tv
    }

    /** Ana Sayfa > Ada yenileme hızı: ada penceresine / görünümüne uygulanır (RefreshRate.kt). */
    private fun applyRefreshRate() {
        val win = dialog?.window ?: return
        runCatching { RefreshRate.apply(win, listOf(composeView, win.decorView), RefreshRateStore.flow(applicationContext).value) }
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

    private fun setGeometry(w: Int, h: Int, x: Int, yIn: Int, recalibrate: Boolean = true, force: Boolean = false) {
        val win = dialog?.window ?: return
        val big = w > 1 && h > 1
        // Ters ada: pencerenin ALT kenarı sabit kalır, pencere yukarı doğru büyür (yIn, normal moddaki üst konumdur)
        val y = if (settings.value.invertedIsland) screenHPx() - yIn - h else yIn
        wantYIn = yIn
        wantX = x; wantY = y
        lastGeoAt = android.os.SystemClock.uptimeMillis()
        val ay = if (big) y - calibDy else y
        val ah = if (big) h + calibDy else h

        // 1) İçerik: mantıksal kutu (genişlik + yatay konum). Görüntü penceresi yatayda oynamadığı için ada
        //    ekranda her zaman hesaplanan yerde çizilir.
        val box = logicalBox.value
        if (box.w != w || box.x != x) logicalBox.value = LogicalBox(w, x)

        // 2) Görüntü penceresi: ekran genişliğinde, x = 0 (sol kenarı hiç değişmez); yalnızca yükseklik / üst konum
        val sw = runCatching { wm.currentWindowMetrics.bounds.width() }.getOrDefault(w).coerceAtLeast(1)
        val cur = win.attributes
        if (force || cur.width != sw || cur.height != ah || cur.x != 0 || cur.y != ay) {
            // Yeni nesne: aynı nesneyi geri atamak WindowManager güncellemesini tetiklemez
            val lp = WindowManager.LayoutParams().also { it.copyFrom(cur) }
            lp.width = sw; lp.height = ah; lp.x = 0; lp.y = ay
            disableMoveAnimation(lp)
            win.attributes = lp
        }

        // 3) Dokunma penceresi: eskiden görüntü penceresinin olduğu boyut ve yer (görünmez; kaysa da fark edilmez)
        touchDialog?.window?.let { tw ->
            val tc = tw.attributes
            if (force || tc.width != w || tc.height != ah || tc.x != x || tc.y != ay) {
                val lp = WindowManager.LayoutParams().also { it.copyFrom(tc) }
                lp.width = w; lp.height = ah; lp.x = x; lp.y = ay
                disableMoveAnimation(lp)
                tw.attributes = lp
            }
        }

        curW = w; curH = h
        if (big) {
            if (recalibrate) calibTries = 0
            composeView?.let { it.removeCallbacks(calibrateRunnable); it.postDelayed(calibrateRunnable, 150) }
        }
    }

    private var lastGeoAt = 0L

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
        // Yatay konum artık ölçülmez: görüntü penceresi yatayda hiç oynamaz (sol kenarı hep 0)
        if (abs(dy) <= 2 || calibTries >= 3) return
        calibTries++
        calibDy = (calibDy + dy).coerceIn(-300, 600)
        setGeometry(curW, curH, wantX, wantYIn, recalibrate = false)
    }


    /** Boşta görünen adaya dokunuş yakalamasın: durum çubuğu altından normal çalışsın. */
    private fun setTouchable(on: Boolean) {
        // Dokunuşu yalnızca görünmez dokunma penceresi alır; görüntü penceresi hep dokunulmaz kalır
        val win = touchDialog?.window ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val blocked = (win.attributes.flags and flag) != 0
        if (on && blocked) win.clearFlags(flag)
        else if (!on && !blocked) win.addFlags(flag)
    }


    /**
     * Cam bulanıklığı: [on] ise bulanıklık penceresi adanın gövdesine ([bw] x [bh] px, yatay kayma [bx],
     * üst kenar [by]) oturtulur ve köşeleri adanınkiyle aynı olur. Çentikte üst kenar ekranın dışına uzatılır:
     * yalnızca alt köşeler yuvarlak görünür. Kapalıyken pencere 1x1 ve bulanıklık 0.
     */
    private fun setBlur(on: Boolean, s: IslandSettings, bw: Int = 0, bh: Int = 0, bx: Int = 0, by: Int = 0) {
        if (Build.VERSION.SDK_INT < 31) return
        val win = blurDialog?.window ?: return
        val r = if (on && bw > 1 && bh > 1 && s.glassEnabled && s.blurRadiusPx > 0 && wm.isCrossWindowBlurEnabled) s.blurRadiusPx else 0
        val lp = WindowManager.LayoutParams().also { it.copyFrom(win.attributes) }
        if (r > 0) {
            val notch = s.more.notchMode && screenHPx() > (runCatching { wm.currentWindowMetrics.bounds.width() }.getOrDefault(0))
            val corner = if (notch) notchBottomRadius(bw.toFloat(), bh.toFloat(), density)
                else islandCornerPx(bw.toFloat(), bh.toFloat(), density, -1f)
            val up = if (notch) corner.toInt() + 2 else 0          // çentik: üst köşeler ekranın dışında kalsın
            if (abs(blurCorner - corner) > 0.5f) {
                // Bulanıklığın köşeleri pencere arka planının köşelerini izler: yarıçap değişince arka plan yenilenir
                blurCorner = corner
                win.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply { setColor(Color.TRANSPARENT); cornerRadius = corner })
            }
            lp.width = bw; lp.height = bh + up; lp.x = bx; lp.y = by - up - calibDy
        } else {
            lp.width = 1; lp.height = 1; lp.x = 0; lp.y = 0
        }
        val c = win.attributes
        if (c.width != lp.width || c.height != lp.height || c.x != lp.x || c.y != lp.y) {
            disableMoveAnimation(lp)
            win.attributes = lp
        }
        win.setBackgroundBlurRadius(r)
    }


    /**
     * Pencereyi SABİT piksel boyutuna getirir (WRAP_CONTENT yok): konum kayması ve
     * kare başına pencere yeniden ölçümü ortadan kalkar. Blur sadece pencere ile
     * ada aynı boyuttayken (animasyon bittiğinde) açılır; böylece sadece ada bölgesi bulanıklaşır.
     */
    private suspend fun syncWindow(k: WinKey) {
        val s = k.s
        val persistSides = s.dotMode || s.iphoneCircleOn()     // yan noktalar / iPhone dairesi boştayken de görünür: pencere iki yanda hep pay ayırır
        dotWin = persistSides
        setFocusable(k.kind == 12 && k.expanded)          // çeviri paneli: klavye için odak
        val pad = dp(s.windowPadDp())                    // parıltı / zıplama taşması için pay
        val pillC = pillCompactSizeForKind(s, k.kind)      // hap tasarımı: kapalı ada daha büyük
        val cw = dp(if (k.xcw > 0f && pillC == null) k.xcw else if (pillC != null) pillC.first else if (s.dotMode) s.compactWidthDp else if (k.kind == 5 || k.kind == 6 || k.kind == 10) maxOf(CALL_COMPACT_W_DP, s.compactWidthDp) else compactWidthFor(s, k.lyr && k.kind == 2)); val ch = dp(s.compactHeightDp)
        val chWin = if (pillC != null) dp(pillC.second) else ch     // kapalı halde pencere yüksekliği (konum hesabı ch ile kalır: ada kameradan aşağı büyür)
        val music = k.kind == 2
        val assistant = k.kind == 3
        val ew = dp(
            when {
                k.xew > 0f -> k.xew                                           // yeni tasarımlar (zamanlayıcı, Rahatsız Etmeyin kartı...)
                assistant -> s.assistantWidthDp
                k.kind == 8 -> chargePillWidthDp(s)                              // şarj: kompakt pill (genişlik ayarlanabilir)
                k.kind == 13 -> ringerPillWidthDp(s, true, wm.currentWindowMetrics.bounds.width() / density)   // zil / sessiz (en geniş hal)
                k.kind == 14 -> sysPillWidthDp(s, wm.currentWindowMetrics.bounds.width() / density)           // ağ / uçak / zil sesi / düşük pil hapı
                k.kind == 16 -> designPhotoWidthDp(s, wm.currentWindowMetrics.bounds.width() / density)       // özel tasarım fotoğrafı
                k.kind == 9 || k.kind == 10 -> recExpandedWidthDp(s, wm.currentWindowMetrics.bounds.width() / density)   // ekran kaydı
                else -> s.expandedWidthDp
            }
        )
        val favN = (ui.value.content as? IslandContent.Favorites)?.apps?.size ?: 0
        val eh = dp(
            when {
                k.xeh > 0f -> k.xeh
                assistant -> s.assistantHeightDp
                k.kind == 5 -> 74f          // gelen arama
                k.kind == 6 -> 88f          // aktif arama
                k.kind == 8 -> chargePillHeightDp(s)   // şarj
                k.kind == 13 -> ringerPillHeightDp(s)  // zil / sessiz
                k.kind == 14 -> sysPillHeightDp(s)     // geniş iOS hapı
                k.kind == 15 -> LOWBATT_CARD_H_DP      // düşük pil kartı
                k.kind == 16 -> s.more.designPhotoHDp  // özel tasarım fotoğrafı
                k.kind == 17 -> 142f                   // zaman kapsülü (fotoğrafsız)
                k.kind == 18 -> 252f                   // zaman kapsülü (fotoğraflı)
                k.kind == 19 -> s.drawerHeightDp()     // ada çekmecesi
                k.kind == 20 -> LIVE_H_DP              // navigasyon / sipariş
                k.kind == 9 || k.kind == 10 -> REC_PILL_H_DP   // ekran kaydı
                k.kind == 11 -> SOUND_CAPSULE_PILL_H_DP        // SoundCapsule sonucu
                k.kind == 4 -> s.favoritesHeightDp(favN)
                k.kind == 12 -> s.translateHeightDp(k.extraH)   // çeviri paneli: sonuç uzunluğuna göre
                else -> s.expandedHeightDp + (if (music) s.musicExtraDp() else 0f) + (if (k.kind == 1) k.extraH.toFloat() else 0f)
            }
        )

        val screenW = wm.currentWindowMetrics.bounds.width()
        val baseX = cutCenterXOffsetPx + dp(s.offsetXDp)
        val y = (cutCenterYPx - ch / 2).coerceAtLeast(0) + dp(s.offsetYDp) - pad
        val maxShift = ((screenW - ew - 2 * pad) / 2).coerceAtLeast(0)
        // SoundCapsule (kind 11): pencere genişleyip küçülürken yatay konum DEĞİŞMEZ (kısıtlama konumu kaydırıp küçülürken yana kaymaya yol açıyordu)
        val xExp = if (k.kind == 11) baseX else baseX.coerceIn(-maxShift, maxShift)
        val settle = s.settleMs()
        // Yan baloncuklar varsa pencere iki yanda (simetrik) baloncuk payı kadar genişler; ada pencerenin ortasında kalır
        // Genişlemişken de baloncuk varsa pencere iki yanda pay ayırır (baloncuk genişleyen panelin yanında durur)
        // Üç nokta modunda yan noktalar her zaman görünür: pencere iki yanda her zaman pay ayırır (boştayken de)
        val sideExtra = if (persistSides || (k.side > 0 && k.kind != 0)) dp(sideExtraDp(s)) else 0
        // Bulanıklık artık ayrı pencerede, adanın gövdesi boyunda: pay / parıltı / yan baloncuk / çentik engel değil

        // Boştayken de dokunulabilir: favori uygulamalar özelliği açıksa (dokunma / basılı tutma algılanır)
        // Çekiştirme / gizli oyun açıksa boştaki ada da dokunuş alır
        val playTouch = k.kind == 0 && s.showIdle &&
            (s.playPull || s.playGame || s.playFling || s.playRhythm || s.playDoodle || s.playPlant || s.playVisitors || s.playBreath)
        setTouchable(((k.kind != 0 && !assistant) || (k.kind == 0 && (s.favoritesReady() || s.translateReady() || s.designPhotoReady() || (s.showIdle && (s.more.thumbEnabled || s.more.drawerEnabled || s.more.notifBirds || s.more.timerIdleTrigger != 0)) || (s.showIdle && GestureStore.flow(applicationContext).value.enabled))) || playTouch) && !appHide.hidden && !thumbHidden)
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
                setBlur(true, s, cw, ch, baseX, y + pad)
            }
            k.kind == 0 -> { setBlur(false, s); setGeometry(1, 1, baseX, y) }
            k.expanded && k.swallow && music -> {
                // Yutma efekti: pencere, büyüyen adanın boyutunda (tam açık panel kadar büyük değil)
                val sz = swallowSizeDp(pillC?.first ?: compactWidthFor(s, false), pillC?.second ?: s.compactHeightDp, s.expandedWidthDp, s.expandedHeightDp + s.musicExtraDp())
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
                val sideExp = if (persistSides && k.side == 0) 0 else sideExtra
                val winW = ew + 2 * pad + 2 * sideExp
                val maxShSide = ((screenW - winW) / 2).coerceAtLeast(0)
                val xWin = if (k.kind == 11) baseX else if (sideExp > 0) baseX.coerceIn(-maxShSide, maxShSide) else xExp
                // Çeviri sonucu kısalınca pencere, panel küçülme animasyonunu bitirene kadar küçülmez (içerik kırpılmasın)
                if (k.kind == 12 && eh + 2 * pad < curH - 2) delay(240)
                setGeometry(winW, eh + 2 * pad, xWin, y)
                winSide = sideExp
                if (!assistant && k.kind != 12) { delay(settle); setBlur(true, s, ew, eh, xWin, y + pad) }
            }
            else -> {
                setBlur(false, s)
                // Müzik adasında pencere küçülmeden önce biraz daha beklenir: hızlı aç-kapa'da pencere her seferinde
                // yeniden boyutlanmasın (asıl takılma kaynağı). Bu sürede yeniden açılırsa bu bekleme iptal olur.
                // Yan baloncuklar birleşirken (sıvı animasyonu) pencere de aynı şekilde geç küçülür
                val hold = if ((k.kind == 2 && !k.swallow) || winSide > 0) WINDOW_HOLD_MS else 0L
                val wantW = cw + 2 * pad + 2 * sideExtra
                if (curW > wantW || curH > chWin + 2 * pad) delay(settle + hold)
                setGeometry(wantW, chWin + 2 * pad, baseX, y)
                winSide = sideExtra
                setBlur(true, s, cw, chWin, baseX, y + pad)
            }
        }
        if ((k.kind == 0 && !persistSides) || (k.expanded && sideExtra == 0)) winSide = 0
    }

    private fun startAsForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("island", "Omni Island", NotificationManager.IMPORTANCE_MIN))
        val n = NotificationCompat.Builder(this, "island")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Omni Island çalışıyor")
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
        val islandCy = islandCenterYPx(s)
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
            setContent { OutsideLyricsRoot(uiShown, settings, lyricsShown, mediaState, outsideOrigin) }
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
        runCatching { lockDot.rehost() }     // kilit noktası adanın içinde durabilir: ada yeniden eklenince nokta yine üstte kalsın
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
        outsideView = null; outsideLp = null; outsideWm = null
        outsideDy = 0
    }

    override fun onDestroy() {
        runCatching { twist.stop() }
        runCatching { castWatch.stop() }
        runCatching { stillWatch.stop() }
        runCatching { vpnWatch.stop() }
        NetSpeed.stop()
        runCatching { shakeWatch.stop() }
        runCatching { lockWatch.stop() }
        NotifPrivacy.active.value = false; NotifPrivacy.peek.value = false
        IslandAccessibilityService.onChanged = null
        DrawerBridge.onKey = null
        runCatching { faceFlash.release() }
        runCatching { chargeRing.release() }
        runCatching { unregisterReceiver(chargeReceiver) }
        runCatching { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioDeviceCallback(hpCallback) }
        runCatching { thumb.detach() }
        TranslateSession.closeRequest = null
        TranslateSession.onLangsChanged = null
        runCatching { TranslateSession.release() }
        runCatching { privacy.stop() }
        runCatching { ringer.stop() }
        runCatching { volume.stop() }
        runCatching { sysStatus.stop() }
        runCatching { chargeEta.stop() }
        runCatching { flipSilence.stop() }
        runCatching { sideVolume.hide() }
        runCatching { sfxTriggers.stop() }
        runCatching { lockDot.stop() }
        runCatching { faceId.stop() }
        runCatching { lockIcon.stop() }
        runCatching { clock.destroy() }
        IslandActions.onHoldPress = {}; IslandActions.onHoldExpand = {}; IslandActions.serviceLive = false
        runCatching { torch.stop() }
        runCatching { gesturePad.dismiss() }
        runCatching { landscape.stop() }
        IslandSounds.stopAll()
        lyricsJob?.cancel()
        runCatching { fxWindow.detach() }
        FxBridge.clear()
        runCatching { call.destroy() }
        runCatching { sideMusic.hide() }
        runCatching { notifBanner.release() }
        runCatching { removeLaunchView() }
        runCatching { removeSwallowView() }
        runCatching { removeStretchView() }
        exitWatchdog?.cancel()
        runCatching { statusCover.detach() }
        runCatching { edgeGlow.detach() }
        runCatching { decor.detach() }
        runCatching { play.detach() }
        runCatching { playCtl.stop() }
        detachOutside()
        collapseJob?.cancel()
        scope.cancel()
        detachOverlay()
        iconCache.evictAll()
        super.onDestroy()
    }

}


