package com.example.dynamicisland

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.ceil

const val GLOW_PAD_DP = 14f     // parıltı için pencere kenar boşluğu
const val BOUNCE_PAD_DP = 16f
const val NEON_PAD_DP = 18f     // renkli çerçevenin (neon / ateş) dışa taşan ışığı için pay   // "Zıplamalı" animasyonun taşma payı
const val FAV_COLUMNS = 4       // favori panelinde bir satırdaki uygulama sayısı
const val FAV_ROW_DP = 78f      // favori panelinde bir satırın yüksekliği
const val MAX_FAV = 8           // en fazla seçilebilecek favori uygulama

@Immutable
data class IslandSettings(
    // Kapalı (küçük) hal
    val compactWidthDp: Float = 120f,
    val compactHeightDp: Float = 32f,
    // Açık hal
    val expandedWidthDp: Float = 372f,
    val expandedHeightDp: Float = 84f,
    // Konum ince ayarı
    val offsetXDp: Float = 0f,
    val offsetYDp: Float = 5f,
    // Renk
    val accentArgb: Int = 0xFF0A84FF.toInt(),
    val islandColorArgb: Int = 0xFF000000.toInt(),   // ada gövde rengi (albüm kapağı rengi kapalıyken); varsayılan siyah
    val dynamicColor: Boolean = false,       // albüm kapağından renk
    val eqDynamicColor: Boolean = true,      // ekolayzır rengi albüm kapağından gelsin (kapalıysa eqColorArgb)
    val eqColorArgb: Int = 0xFF0A84FF.toInt(), // ekolayzırın kendi rengi
    // Albüm kapağı arka planı (müzik çalarken adanın içi)
    val artBgEnabled: Boolean = false,
    val artBgCompact: Boolean = true,        // kapalı adada da; kapalıysa yalnızca açık adada
    // Özel arka plan (galeriden): adanın içi seçilen fotoğrafla dolar
    val customBgEnabled: Boolean = false,
    val customBgSeparate: Boolean = false,   // false: tek fotoğraf kapalı + açık ada; true: her hal için ayrı fotoğraf
    val customBgDim: Float = 0.30f,          // yazıların okunması için karartma
    val customBgBothUri: String = "",        // boş = fotoğraf seçilmedi; aksi halde galerideki fotoğrafın adresi (kopyalanmaz)
    val customBgCompactUri: String = "",
    val customBgExpandedUri: String = "",
    // Özel albüm kapağı (galeriden): adadaki kapak yerine bu fotoğraf gösterilir
    val customBgVideoUri: String = "",       // boş = video seçilmedi; doluysa videonun adresi (kopyalanmaz). Doluysa fotoğrafın yerine çalar
    val notifBottomFx: Boolean = false,      // bildirim gelince (ada ve cam banner) alt kenarda uygulamanın renginde ışık
    val musicBottomFx: Boolean = false,      // müzik çalarken adanın altında albüm renginde hareketli ışık
    // Ada olayları (genişletilmiş müzik adasında): hava durumu, şimşek, donma, erime, kırılma
    val fxWeather: Int = 0,                  // 0 kapalı, 1 yağmur, 2 kar, 3 asit yağmuru
    val fxIntensity: Float = 0.6f,
    val fxLightning: Boolean = false,        // yağmurda şimşek
    val fxLightningSec: Float = 8f,          // ortalama şimşek aralığı (sn)
    val fxFreezeSpeed: Float = 1f,
    val fxThawTaps: Int = 5,                 // donan öğeyi kırmak için dokunma sayısı
    val fxMeltSpeed: Float = 1f,
    val fxBreak: Boolean = false,            // dokunarak kırma modu
    val fxRestoreSec: Float = 5f,            // düşen / eriyen parçaların geri gelme süresi
    val customArtEnabled: Boolean = false,
    val customArtUri: String = "",
    val customArtVideoUri: String = "",      // boş = video yok; doluysa kapak yerine video çalar (kopyalanmaz)
    // Liquid Glass
    val glassEnabled: Boolean = false,
    val glassAlpha: Float = 0.55f,
    val blurRadiusPx: Int = 40,
    // Sıvı cam (kırılma shader'ı + derinlik + speküler); cam efekti açıkken seçilebilir
    val liquidEnabled: Boolean = false,
    val liquidRefraction: Float = 0.6f,      // kenarda içeriğin bükülme miktarı
    val liquidDispersion: Float = 0.5f,      // kenarda renk ayrışması
    val liquidSpecular: Float = 0.8f,        // parlama / yansıma şiddeti
    // Parıltı
    val glowEnabled: Boolean = false,
    val glowIntensity: Float = 0.6f,
    // İlerleme çubuğu parıltısı (genişletilmiş müzik paneli)
    val progressGlowEnabled: Boolean = false,
    val progressGlowArtColor: Boolean = true,     // renk albüm kapağından alınsın (kapak yoksa progressGlowColorArgb)
    val progressGlowColorArgb: Int = 0xFF0A84FF.toInt(),
    val progressGlowIntensity: Float = 0.7f,
    val progressGlowTipOnly: Boolean = false,     // false: çubuğun tamamında, true: yalnızca ucunda (her zaman çubuğun içinde)
    val progressGlowInside: Boolean = false,      // tüm çubuk modunda: parıltı çubuğun dışına taşmasın, içinde kalsın
    // Beğeni (kalp) düğmesi
    val likeArtColor: Boolean = false,            // beğenilince kalp albüm kapağı renginde olsun (kapak yoksa likeColorArgb)
    val likeColorArgb: Int = 0xFFFF2D6F.toInt(),
    // Çerçeve
    val borderEnabled: Boolean = false,
    val borderColorArgb: Int = 0xFFFFFFFF.toInt(),
    val borderArtColor: Boolean = false,     // çerçeve çizgisi albüm kapağı renginde olsun (kapak yoksa borderColorArgb)
    val borderWidthDp: Float = 1f,
    // Animasyon
    val animEnabled: Boolean = true,
    val animStyle: Int = 0,                  // 0 Yaylı, 1 Yumuşak, 2 Hızlı, 3 Zıplamalı, 4 Sıvı
    val musicArtFlip: Boolean = true,        // albüm kapağı: açılırken 360° döner, küçülürken ters döner, şarkı değişince dönerek değişir
    val swipeTrackChange: Boolean = true,    // müzik adasında sağdan sola kaydır = sonraki, soldan sağa = önceki şarkı
    val pillCallStyle: Boolean = false,      // arama adası: yeşil / kırmızı yuvarlak düğmeler + ortada ses dalgası (hap tasarımı)
    val pillMusicStyle: Boolean = false,     // müzik adası: solda büyük kapak, ortada dalga + ilerleme, sağda kontrol düğmeleri (hap tasarımı)
    val pillFitIsland: Boolean = true,       // hap tasarımları: true = ada BÜYÜMEZ, tasarım ayarlı küçültülmüş ada boyutuna sığacak şekilde ölçeklenir; false = ada hap boyutuna büyür
    val pillMusicWDp: Float = 344f,          // müzik hapı genişliği (yalnızca "Adanın boyutuna uydur" kapalıyken)
    val pillMusicHDp: Float = 100f,          // müzik hapı yüksekliği (yalnızca "Adanın boyutuna uydur" kapalıyken)
    val pillWaveArtColor: Boolean = false,   // müzik hapı dalgası: renkler çalan şarkının albüm kapağından türetilsin (kapak yoksa aşağıdaki 3 renk)
    val pillWaveLeftArgb: Int = 0xFFFF5AC8.toInt(),   // sol dalga rengi
    val pillWaveMidArgb: Int = 0xFFA06BFF.toInt(),    // orta dalga rengi
    val pillWaveRightArgb: Int = 0xFF4C9BFF.toInt(),  // sağ dalga rengi
    val musicBlurAnim: Boolean = true,       // müzik adası: içerik bulanıktan netleşerek açılır, tersi yönde küçülür (iOS tarzı)

    // Görünürlük
    val showIdle: Boolean = true,            // müzik/bildirim yokken de ada görünsün

    // Favori uygulamalar (boştayken ada genişleyip seçilen uygulamaları gösterir)
    val favoritesEnabled: Boolean = false,
    val favoritesTrigger: Int = 0,           // 0 = dokununca, 1 = basılı tutunca
    val favoritePkgs: String = "",           // paket adları, virgülle ayrılmış (sıra korunur)
    val favoriteLaunchSpeed: Float = 1f,     // açılış animasyonu hızı (1 = normal, 2 = iki kat hızlı)
    val notifBanner: Boolean = false,        // bildirimler adada değil, iOS tarzı cam banner olarak (sol üstte saatin altından küçük çıkıp büyüyerek) görünsün
    val notifInline: Boolean = false,        // bildirim gelince ada GENİŞLEMEZ; yazı, gönderen ve uygulama simgesi kapalı adanın içinde görünür
    val bannerMenuEnabled: Boolean = true,   // cam bannerda Cevapla / Kopyala / İlet menüsü
    val bannerMenuTap: Boolean = false,      // menü: false = basılı tutunca, true = dokununca (dokununca açılırsa uygulama simgesinden açılır)
    val bannerIconOpens: Boolean = true,     // cam bannerdaki simgeye dokununca bildirimin uygulaması açılsın
    val notifTapOpens: Boolean = true,       // adadaki bildirime dokununca uygulama açılsın
    // Bildirim hareketleri (varsayılan: yalnızca dokununca aç; diğerleri kapalı)
    val notifOpenLong: Boolean = false,      // basılı tutunca bildirimin uygulaması açılsın
    val notifOpenSwipeDown: Boolean = false, // aşağı kaydırınca bildirimin uygulaması açılsın
    val notifCollapseTap: Boolean = false,   // dokununca bildirim küçülsün
    val notifCollapseLong: Boolean = false,  // basılı tutunca bildirim küçülsün
    val notifCollapseSwipeUp: Boolean = false, // yukarı kaydırınca bildirim küçülsün
    val notifDeleteSwipeSide: Boolean = false, // sağa / sola kaydırınca bildirim silinsin
    val notifDeleteSwipeUp: Boolean = false,   // yukarı kaydırınca bildirim silinsin
    // Yan baloncuk
    val notifExpandedSec: Float = 5f,        // bildirim genişletilmiş halde kalma süresi (sn)
    val notifCompactSec: Float = 10f,        // sonra küçültülmüş halde (nokta modunda nokta olarak) kalma süresi (sn); 0 = hemen gider
    // Kilit ekranı noktası: kilitliyken adanın sol yanında yanıp sönen parıltılı nokta
    val lockDotEnabled: Boolean = true,
    val lockDotColorArgb: Int = 0xFF9B4DFF.toInt(),   // mor
    val lockDotSizeDp: Float = 9f,
    val lockDotGapDp: Float = 10f,           // adanın sol kenarından noktaya uzaklık (negatif = adanın üstüne doğru)
    val lockDotOffsetYDp: Float = 0f,
    val lockDotGlow: Boolean = true,
    val lockDotGlowDp: Float = 14f,          // parıltının noktanın dışına taşma miktarı
    val lockDotGlowAlpha: Float = 0.85f,
    val lockDotBlink: Boolean = true,
    val lockDotBlinkMs: Int = 400,           // bir yanıp sönme turunun süresi (ms)
    val lockDotMinAlpha: Float = 0.08f,      // "sönük" anındaki parlaklık
    val lockDotBlinkStyle: Int = 0,          // 0 yumuşak, 1 sert (ani aç / kapa)
    // Kilit ekranı Face ID animasyonu: kilitliyken ada kare olur, Face ID taraması + onay işareti oynar
    val torchIndicator: Boolean = true,      // fener (flaş) açılınca adada simge + ışık kartı (kademeli flaşı destekleyen telefonda gerçek seviye)
    val faceIdEnabled: Boolean = true,
    val faceIdColorArgb: Int = 0xFF98FF98.toInt(),    // açık yeşil (ekran kaydındaki renk)
    val faceIdSizeDp: Float = 120f,                   // açık haldeki kare boyutu
    val faceIdOffsetYDp: Float = 0f,
    val faceIdSpeed: Float = 1f,                      // 1 = ekran kaydıyla birebir hız
    // Ses efektleri (dosya kopyalanmaz; yalnızca adres tutulur)
    val sfxEnabled: Boolean = true,
    val sfxTap: SoundCue = SoundCue(),
    val sfxCharge: SoundCue = SoundCue(),
    val sfxUnplug: SoundCue = SoundCue(),
    val sfxNotif: SoundCue = SoundCue(),
    val sfxSilent: SoundCue = SoundCue(),
    val sfxCall: SoundCue = SoundCue(),
    val musicExpandedSec: Float = 8f,        // müzik adası genişletilince açık kalma süresi (sn); 0 = kendiliğinden kapanmasın
    val sideTapPromote: Boolean = false,     // baloncuğa dokununca o olay kalıcı olarak ortaya geçsin (kapalıysa yalnızca paneli açılır)
    val invertedIsland: Boolean = false,
    // Ters adaya özel konum ve boyut (açıksa ters adada normal ada ölçüleri yerine bunlar kullanılır)
    val invCustom: Boolean = false,
    val invWidthDp: Float = 120f,
    val invHeightDp: Float = 32f,
    val invOffsetXDp: Float = 0f,
    val invOffsetYDp: Float = 5f,     // ters ada: ada ekranın ÜSTÜNDE değil ALTINDA durur; içerik düz kalır, panel yukarı doğru açılır
    val landscapeMode: Boolean = false,      // yatay mod: telefon yan çevrilince ada kenarda DİKEY hap olur; olaylar (bildirim, şarj...) simgesi düz duran yan baloncuk olarak animasyonla çıkar
    val landscapeBubbleBelow: Boolean = false, // yatay mod: baloncuklar adanın altında (false: üstünde) sıralansın
    val landscapeEventsInIsland: Boolean = false, // yatay mod: olay adanın içinde de görünsün; ada doluysa gelen yeni olaylar baloncuk olur (en çok 2 baloncuk)
    val hideStatusBar: Boolean = false,      // şarj / zil modu gibi durum görünümleri açıkken durum çubuğunu gizle
    val notifLaunchAnim: Boolean = true,     // ... ve açılırken adadan siyah ekran animasyonu oynasın
    val favoriteLaunchAnim: Boolean = true,  // uygulamaya dokununca adadan siyah ekran çıkıp ekranı kaplasın

    // Google Asistan / Gemini animasyonu
    val screenRecEnabled: Boolean = true,    // sistem ekran kaydı adada gösterilsin (ek izin istemez)
    val screenRecWide: Boolean = true,       // ekran kaydı açık adası: true = durum çubuğu genişliği (köşelere değmez), false = normal boy
    val assistantEnabled: Boolean = true,
    val assistantWidthDp: Float = 150f,
    val assistantHeightDp: Float = 112f,
    val musicExitClassic: Boolean = true,     // panel animasyonu: klasik küçülen panel
    val musicExitAtomic: Boolean = false,     // panel animasyonu: atom ada (eğilen kart adaya emilir); ikisi açıksa rastgele
    val musicExitAtomicFull: Boolean = false, // atom ada: panel önce tüm ekranı kaplar, sonra küçülüp adaya girer
    val musicExitSwallow: Boolean = true,     // panel adaya varınca ada büyüyüp onu yutar
    val musicExitSpeed: Float = 1f,           // müzikten çıkış animasyonu hızı (1 = normal)
    val musicExitStretch: Boolean = false,    // yeni yutma: ada aşağı doğru uzun bir kapsüle uzanıp yumuşakça eski haline döner
    val musicFluidAnim: Boolean = false,      // Akıcı yutma: pencere bulanıklaşarak adaya küçülür, ada şişip yutar; müzik açılışı da aynı tarzda
    // Oyuncak ada ayarları ayrı sınıfta (bkz. PlaySettings): bu sınıf JVM'in 255 parametre sınırına yaklaştı
    val play: PlaySettings = PlaySettings(),
    // Yeni eklenen ayarlar (özel ada tasarımı, sistem göstergeleri, orta boy müzik...) yine ayrı sınıfta: bkz. MoreSettings
    val more: MoreSettings = MoreSettings(),
    // Müzik uygulamasını açma + hareketler
    val musicOpenAnim: Boolean = true,        // adadan müzik uygulamasını açarken açılış animasyonu
    val launchClassic: Boolean = false,       // bildirim + müzik uygulaması açılışı: klasik büyüyen ekran
    val launchAtomic: Boolean = true,         // ... atom ada (adadan açılan kart); ikisi açıksa rastgele
    val launchSpeed: Float = 1f,              // bildirim + müzik uygulaması açılış animasyonu hızı (1 = normal, 2 = iki kat hızlı)
    val favLaunchClassic: Boolean = true,     // favori uygulamalar açılışı: klasik
    val favLaunchAtomic: Boolean = false,     // favori uygulamalar açılışı: atom ada
    val openExpArt: Boolean = false,          // açık adada albüm kapağına basınca müzik uygulaması açılsın
    val openExpTitle: Boolean = false,        // açık adada şarkı ismine basınca
    val openExpAny: Boolean = false,          // açık adada herhangi bir yerine (düğmeler hariç) basınca
    val openCompArt: Boolean = false,         // küçük adada albüm kapağına basınca
    val openCompLong: Boolean = false,        // küçük adada basılı tutunca
    val openCompTap: Boolean = false,         // küçük adaya basınca
    val expandOnLong: Boolean = true,         // küçük adaya basınca müzik uygulaması açılıyorsa: basılı tutunca ada genişlesin
    val swipeDownExpand: Boolean = true,      // adayı aşağı kaydırınca genişlesin
    val swipeUpCollapse: Boolean = true,      // açık adayı yukarı kaydırınca küçülsün
    val longPressMs: Int = 500,               // basılı tutma süresi (ms)
    val hideInMusicApp: Boolean = false,      // müzik uygulamasındayken adada çalan müzik görünmez
    val musicExitAnim: Boolean = true,        // müzik uygulamasından çıkınca panel adaya küçülür, ada şarkıyı gösterir
    val assistantStyle: Int = 0,              // asistan animasyonu stili: 0 = klasik bant, 1 = cam oval (siyah cam içinde renkli Siri dalgası)
    val assistantFullWidth: Boolean = false,  // ada kutusu yok; renkli animasyon ekranın solundan sağına uzanır

    // Gökkuşağı modu
    val rainbowEnabled: Boolean = false,
    val rainbowAlpha: Float = 0.85f,         // gökkuşağının saydamlığı (1 = tam opak)

    // ---------------------------------------------------------- Şarkı sözleri
    val lyricsEnabled: Boolean = true,
    val lyricsInPanel: Boolean = true,       // genişletilmiş adanın içinde
    val lyricsCompact: Boolean = false,       // kapalı adanın içinde (ortada)
    val lyricsOutside: Boolean = false,      // adanın dışında, adayı açmadan
    // Boyutlar
    val lyricsPanelSizeSp: Float = 17f,
    val lyricsPanelLines: Int = 2,
    val lyricsCompactSizeSp: Float = 12f,
    val lyricsCompactWidthDp: Float = 210f,  // sözler görünürken kapalı adanın genişliği
    val lyricsOutsideSizeSp: Float = 20f,
    val lyricsOutsideLines: Int = 2,
    val lyricsOutsideWidthDp: Float = 320f,
    val lyricsOutsideXDp: Float = 0f,
    val lyricsOutsideYDp: Float = 96f,       // ekranın üstünden uzaklık
    // Renk / dolum / senkron
    val lyricsColorArgb: Int = 0xFFFFFFFF.toInt(),
    val lyricsDynamicColor: Boolean = true,  // söz rengi albüm kapağından alınsın (kapak yoksa lyricsColorArgb)
    val lyricsDimAlpha: Float = 0.38f,       // henüz söylenmemiş kısmın opaklığı
    val lyricsFillEnabled: Boolean = true,   // söylendikçe dolsun
    val lyricsOffsetMs: Int = 0,             // + ise sözler daha erken görünür
    // Parıltı
    val lyricsGlowEnabled: Boolean = false,
    val lyricsGlowLevel: Float = 0.5f,
    // Satır geçiş animasyonu
    val lyricsAnimEnabled: Boolean = true,
    val lyricsAnimStyle: Int = 0,            // 0 Solma, 1 Yukarı kayma, 2 Yakınlaşma, 3 Yaylı zıplama, 4 Yatay kayma, 5 Anında, 6 Adadan çıkış, 7 Adadan tek tek, 8 Soldan açılma, 9 Çevirme, 10 Odak çekme
    val lyricsAnimMs: Int = 380,

    // ---------------------------------------------------------- Üç nokta modu (HarmonyOS tarzı)
    val dotMode: Boolean = false,            // ada yerine üç nokta; olaylar nokta (baloncuk) olarak görünür
    val dotMidDp: Float = 28f,               // ortadaki nokta (kameranın üstünde) çapı
    val dotLeftDp: Float = 24f,              // soldaki nokta çapı
    val dotRightDp: Float = 24f,             // sağdaki nokta çapı
    val dotGapDp: Float = 10f,               // noktalar arası boşluk
    val dotCount: Int = 3,                   // nokta sayısı: 3, 4 (fazladan nokta sağda) ya da 5
    val dotLeft2Dp: Float = 20f,             // soldaki dış nokta çapı (5 nokta)
    val dotRight2Dp: Float = 20f,            // sağdaki dış nokta çapı (4 ve 5 nokta)
    val dotOffsetXDp: Float = 0f,            // üç nokta konumu: normal Konum ayarına eklenir (+ sağa)
    val dotOffsetYDp: Float = 0f,            // (+ aşağı)
    val volumeIndicator: Boolean = true,           // medya sesi artınca / azalınca ada hap şeklinde seviyeyi gösterir
    val ringerIndicator: Boolean = true,           // zil / sessiz / titreşim modu değişince ada haber verir
    val ringerWidthPct: Float = 68f,               // genişlerken hap genişliği: ekran genişliğinin yüzdesi
    val ringerHeightPct: Float = 143f,             // genişlerken hap yüksekliği: kapalı ada yüksekliğinin yüzdesi
    val ringerExpand: Boolean = true,              // true: ada ekran kaydındaki gibi yana genişler; false: boyutu değişmez, yalnızca solda simge
    val privacyIndicator: Boolean = false,         // nokta modu: kamera / mikrofon kullanılırken yeşil / turuncu nokta olayı
    val hideInApps: Boolean = false,         // seçili uygulamalar ön plandayken ada gizlensin
    val hiddenAppPkgs: String = "",          // gizlenecek uygulamaların paket adları (virgülle ayrılmış)
    val uiHaptics: Boolean = true,           // ayar ekranında anahtar / kaydırıcı titreşimi
    val dotMergeKeep: Boolean = false,       // birleşik modda olaylar adadan ayrılmaz, hapın içinde kalır
    val dotMerge: Boolean = false,           // boştaki noktalar birleşip tek bir ada gibi görünür; olay görünümü değişmez
    // ---------------------------------------------------------- iPhone modu (hap şeklinde ada + yanında siyah daire)
    val iphoneMode: Boolean = false,         // iPhone 14 Pro tarzı: hap ada + yanında ayrı siyah daire
    val iphoneCircle: Boolean = true,        // yandaki siyah daire görünsün
    val iphoneEvents: Boolean = true,        // olaylar (ikinci olay: müzik, kayıt, arama...) dairede görünsün
    val iphoneCircleSide: Int = 0,           // 0 = daire adanın solunda, 1 = sağında
    val iphonePillWDp: Float = 126f,         // kapalı ada (hap) genişliği
    val iphonePillHDp: Float = 37f,          // kapalı ada (hap) yüksekliği
    val iphoneCircleDp: Float = 37f,         // daire çapı (en çok ada yüksekliği kadar)
    val iphoneGapDp: Float = 10f,            // ada ile daire arası boşluk
    val iphoneCenterGroup: Boolean = true,   // ada + daire ikilisi kameranın ortasında dursun (kapalıysa ada kameranın üstünde, daire yanında)
    val iphoneOffsetXDp: Float = 0f,         // iPhone modu konumu: normal Konum ayarına eklenir (+ sağa)
    val iphoneOffsetYDp: Float = 0f,         // (+ aşağı)
    val stackExpand: Boolean = true,         // birden fazla olay varken adaya basınca hepsi alt alta kartlar halinde açılsın

    // Çeviri paneli (boştayken adaya dokununca / basılı tutunca açılır)
    val translateEnabled: Boolean = false,
    val translateTrigger: Int = 0,           // 0 = dokununca, 1 = basılı tutunca
    val translateSrc: String = "tr",         // son kullanılan kaynak dil (ML Kit dil kodu)
    val translateTgt: String = "en",         // son kullanılan hedef dil

    // ---------------------------------------------------------- Renk düzenleyici
    val colorEditorEnabled: Boolean = false, // açıkken aşağıdaki özel renkler uygulanır; kapalıyken varsayılan renkler
    val colorOverrides: String = "",

    // ---------------------------------------------------------- Ada süsleri
    val neonStyle: Int = 0,                  // renkli çerçeve: 0 kapalı, 1 renk geçişi, 2 gökkuşağı, 3 ateş, 4 neon, 5 elektrik, 6 mor alev, 7 ses dalgası
    val neonColor1Argb: Int = 0xFFFF3DCB.toInt(),
    val neonColor2Argb: Int = 0xFF3DFFC2.toInt(),
    val neonWidthDp: Float = 2.5f,           // çizgi kalınlığı
    val neonGlow: Float = 0.8f,              // dışa vuran ışığın şiddeti
    val neonSpeed: Float = 1f,               // renk akışı / alev hızı
    val decorTop: Int = 0,                   // adanın üstünde: 0 yok, 1 uzanan kedi, 2 kedi kulakları, 3 ayı kulakları
    val decorHang: Int = 0,                  // adadan sarkan: 0 yok, 1 salıncakta civciv, 2 yıldızlar, 3 yılbaşı süsleri, 4 kalpler
    val islandPet: Int = 0,                  // boştaki adanın içinde dolaşan: 0 yok, 1 tavşan, 2 civciv, 3 kedi yavrusu
    val dripEnabled: Boolean = false,        // adadan ekranın altına doğru mürekkep damlası akar
    val dripEverySec: Float = 8f,            // damlama aralığı (sn)
    val edgeGlowAlways: Boolean = false,     // ekran kenarında sürekli akan gökkuşağı ışık         // "anahtar:AARRGGBB,..." biçiminde değiştirilmiş renkler (bkz. ColorEditor.kt)
) {
    /**
     * Servisin / arayüzün kullandığı etkin ayarlar. Üç nokta modunda kapalı ada, ortadaki noktanın çapında bir daire olur;
     * kullanıcının kaydedilmiş ada boyutları değişmez (yalnızca bu kopya değişir).
     */
    fun effective(): IslandSettings {
        // Ters ada + özel ölçüler: kapalı ada boyutu ve konumu ters adanın kendi ayarlarından gelir
        val base = if (invertedIsland && invCustom) copy(
            compactWidthDp = invWidthDp, compactHeightDp = invHeightDp,
            offsetXDp = invOffsetXDp, offsetYDp = invOffsetYDp,
        ) else this
        return base.effectiveModes()
    }

    private fun effectiveModes(): IslandSettings = when {
        dotMode -> copy(
            compactWidthDp = dotMidDp, compactHeightDp = dotMidDp,
            offsetXDp = offsetXDp + dotOffsetXDp, offsetYDp = offsetYDp + dotOffsetYDp,
            showIdle = true, lyricsCompact = false, lyricsOutside = lyricsOutside,
        )
        // iPhone modu: kapalı ada hap boyutunda; daire açıksa ve "ortala" seçiliyse ada + daire ikilisi kameranın ortasına oturur
        iphoneMode -> {
            val shift = if (iphoneCircle && iphoneCenterGroup) -iphoneDir() * (iphoneCircleSizeDp() + iphoneGapDp) / 2f else 0f
            copy(
                compactWidthDp = iphonePillWDp, compactHeightDp = iphonePillHDp,
                offsetXDp = offsetXDp + iphoneOffsetXDp + shift, offsetYDp = offsetYDp + iphoneOffsetYDp,
                showIdle = true,
            )
        }
        else -> this
    }

    /** iPhone modunda dairenin adaya göre yönü: +1 sağ, -1 sol. */
    fun iphoneDir(): Int = if (iphoneCircleSide == 1) 1 else -1

    /** Daire çapı (dp): pencere yüksekliği ada yüksekliği olduğu için en çok o kadar. */
    fun iphoneCircleSizeDp(): Float = kotlin.math.min(iphoneCircleDp, iphonePillHDp)

    /** iPhone modu açık ve yandaki siyah daire görünür mü? (Nokta modu açıkken iPhone modu çalışmaz.) */
    fun iphoneCircleOn(): Boolean = iphoneMode && !dotMode && iphoneCircle

    /** Pencerenin ada etrafında bırakacağı pay (parıltı / zıplama taşması için). */
    fun windowPadDp(): Float = maxOf(
        if (glowEnabled) GLOW_PAD_DP else 0f,
        if (animEnabled && animStyle == 3) BOUNCE_PAD_DP else 0f,
        if (neonStyle != 0) NEON_PAD_DP else 0f,
        if (playPhysics) playPhysicsRangeDp + 8f else 0f,          // kayan / titreyen ada kesilmesin
        if (playMagnet || playPocket || playPuff || playEater || more.trashIsland) 8f else 0f,
        if (playPull) 24f else 0f,                                  // çekilen ada aşağı uzayabilsin
    )

    /** Adanın gizleneceği uygulamaların paket adları. */
    fun hiddenAppList(): List<String> = hiddenAppPkgs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    /** Seçili favori uygulamaların paket adları (en fazla [MAX_FAV], tekrarsız). */
    fun favoritePkgList(): List<String> = favoritePkgs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_FAV)

    /** Özellik gerçekten çalışabilir mi: açık, ada boştayken görünür ve en az bir uygulama seçili. */
    fun favoritesReady(): Boolean = favoritesEnabled && showIdle && favoritePkgList().isNotEmpty()

    /** Favori panelinin yüksekliği: kamera bölgesi + uygulama satırları. */
    fun favoritesHeightDp(count: Int): Float {
        val rows = maxOf(1, ceil(count / FAV_COLUMNS.toFloat()).toInt())
        return compactHeightDp + 6f + rows * FAV_ROW_DP + 8f
    }

    /** Özellik gerçekten çalışabilir mi: açık ve ada boştayken görünür. */
    fun translateReady(): Boolean = translateEnabled && showIdle

    /** Çeviri panelinin yüksekliği: kamera bölgesi + dil satırı + gövde (yazı alanı, sonuç ya da dil listesi). */
    fun translateHeightDp(bodyDp: Int): Float = compactHeightDp + 6f + TR_ROW_DP + 8f + bodyDp + 14f

    /** Geçiş animasyonunun yaklaşık bitiş süresi (pencere boyutunu ona göre değiştiririz). */
    fun settleMs(): Long = if (!animEnabled) 60L else when (animStyle) {
        0 -> 520L; 1 -> 560L; 2 -> 300L; 3 -> 750L; else -> 650L
    }

    /** Genişletilmiş panelde şarkı sözü satırına ayrılan sabit yükseklik. */
    fun lyricsBlockDp(): Float = ceil(lyricsPanelSizeSp * 1.3f * lyricsPanelLines) + 10f

    /**
     * Müzik panelinin temel yüksekliğine eklenen miktar (sözler açıksa söz alanı dahil).
     * "Sözler sağda" düzeninde sözler kontrollerin yanında durduğu için ek yükseklik gerekmez.
     */
    fun musicExtraDp(): Float = MUSIC_EXTRA_DP + if (lyricsEnabled && lyricsInPanel && !more.lyricsSide) lyricsBlockDp() else 0f

    /** Yalnızca şarkı sözü ayarlarını varsayılana döndürür. */
    fun withLyricsDefaults(): IslandSettings {
        val d = IslandSettings()
        return copy(
            lyricsEnabled = d.lyricsEnabled, lyricsInPanel = d.lyricsInPanel, lyricsCompact = d.lyricsCompact,
            lyricsOutside = d.lyricsOutside, lyricsPanelSizeSp = d.lyricsPanelSizeSp, lyricsPanelLines = d.lyricsPanelLines,
            lyricsCompactSizeSp = d.lyricsCompactSizeSp, lyricsCompactWidthDp = d.lyricsCompactWidthDp,
            lyricsOutsideSizeSp = d.lyricsOutsideSizeSp, lyricsOutsideLines = d.lyricsOutsideLines,
            lyricsOutsideWidthDp = d.lyricsOutsideWidthDp, lyricsOutsideXDp = d.lyricsOutsideXDp,
            lyricsOutsideYDp = d.lyricsOutsideYDp, lyricsColorArgb = d.lyricsColorArgb, lyricsDynamicColor = d.lyricsDynamicColor,
            lyricsDimAlpha = d.lyricsDimAlpha,
            lyricsFillEnabled = d.lyricsFillEnabled, lyricsOffsetMs = d.lyricsOffsetMs,
            lyricsGlowEnabled = d.lyricsGlowEnabled, lyricsGlowLevel = d.lyricsGlowLevel,
            lyricsAnimEnabled = d.lyricsAnimEnabled, lyricsAnimStyle = d.lyricsAnimStyle, lyricsAnimMs = d.lyricsAnimMs,
            more = more.copy(lyricsSide = d.more.lyricsSide),
        )
    }
}

private val Context.islandStore by preferencesDataStore(name = "island_settings")

/**
 * Oyuncak ada ayarları (bkz. PlayFx.kt). IslandSettings içinde tek alan olarak durur; ana sınıf çok büyüdüğü için
 * (JVM bir fonksiyonda en fazla 255 parametreye izin verir, varsayılan değerli kopya fonksiyonu bunu aşınca uygulama
 * açılırken çöker) ayrı tutulur. Okurken s.playPhysics gibi doğrudan erişilir (aşağıdaki uzantılar).
 */
@androidx.compose.runtime.Immutable
data class PlaySettings(
    val playPhysics: Boolean = false,         // telefonu eğince ada yana kayar
    val playPhysicsRangeDp: Float = 14f,      // en fazla kayma (dp)
    val playJelly: Boolean = true,            // sallayınca / kenara çarpınca jöle gibi titrer
    val playPet: Boolean = false,             // adanın altından sarkan ruh halli evcil
    val playMagnet: Boolean = false,          // şarja takınca ada kabloya doğru damla uzatır
    val playMagnetBuds: Boolean = true,       // kulaklık bağlanınca iki yandan damla çeker
    val playPuff: Boolean = false,            // adaya dokununca ada rüzgâr yemiş gibi savrulur, toz uçuşur
    val playPuffStrength: Float = 0.7f,       // savrulma şiddeti 0..1
    val playFace: Boolean = false,            // boştaki adada gözler
    val playRipple: Boolean = false,          // bildirimde adadan dalga yayılır
    val playRippleTap: Boolean = true,        // adaya dokununca da dalga
    val playPocket: Boolean = false,          // ekran açılınca ada düşüp oturur, boşta kamera deliğine girer
    val playPocketIdleSec: Float = 12f,       // kamera deliğine girmeden önce boşta bekleme (sn)
    val playLiquid: Boolean = false,          // boştaki ada pil seviyesi kadar dolu sıvı kap
    val playEater: Boolean = false,           // bildirim baloncuğu uçup adaya girer, ada yutar
    val playEaterFull: Boolean = true,        // yedikçe ada hafifçe şişer (dokununca geçer)
    val playPull: Boolean = false,            // adayı aşağı çekip bırakınca lastik gibi geri fırlar
    val playDaylight: Boolean = false,        // adanın çevresinde günün saatine göre renk değiştiren hale
    val playDaylightAmt: Float = 0.6f,        // hale parlaklığı 0..1
    val playGame: Boolean = false,            // boştaki adaya hızlıca 5 kez dokununca gizli oyun
    val playFling: Boolean = false,           // adayı yana savurunca ekranda seker, sonra yerine döner
    val playPetTalk: Boolean = true,          // evcil konuşma baloncukları (evcil açıkken)
    val playPlant: Boolean = false,           // adadan sarkan, her gün dokunarak sulanan bitki
    val playSeason: Boolean = false,          // mevsime göre kar / çiçek / parıltı / yaprak
    val playSeasonMode: Int = 0,              // 0 otomatik (tarihe göre), 1 kış, 2 ilkbahar, 3 yaz, 4 sonbahar
    val playRhythm: Boolean = false,          // boştaki adaya ritimle dokununca uygulama açılır
    val playRhythms: String = "",             // "SSL=paket;LS=paket" (S kısa ara, L uzun ara)
    val playParty: Boolean = false,           // pil %100, doğum günü, yılbaşı: konfeti
    val playBirthDay: Int = 0,                // doğum günü (gün, 0 = yok)
    val playBirthMonth: Int = 1,              // doğum günü (ay)
    val playDoodle: Boolean = false,          // boştaki adada kendi çizimin
    val playVisitors: Boolean = false,        // günde birkaç kez gelen ziyaretçiler; dokununca yakalanır, albüme eklenir
    val playDice: Boolean = false,            // telefonu iki kez sallayınca zar / yazı-tura
    val playDiceMode: Int = 0,                // 0 tek zar, 1 iki zar, 2 yazı-tura
    val playBreath: Boolean = false,          // nefes egzersizi
    val playBreathPattern: Int = 0,           // 0 sakin 4-4-4, 1 uyku 4-7-8, 2 kutu 4-4-4-4
    val playBreathMin: Float = 2f,            // egzersiz süresi (dakika)
    val playNightLight: Boolean = false,      // gece şarjdayken sıcak ışık + büyük saat
    val playNightAmt: Float = 0.7f,           // gece lambası parlaklığı
    val playShadow: Boolean = false,          // telefonu eğdikçe kayan gölge
)

val IslandSettings.playPhysics get() = play.playPhysics
val IslandSettings.playPhysicsRangeDp get() = play.playPhysicsRangeDp
val IslandSettings.playJelly get() = play.playJelly
val IslandSettings.playPet get() = play.playPet
val IslandSettings.playMagnet get() = play.playMagnet
val IslandSettings.playMagnetBuds get() = play.playMagnetBuds
val IslandSettings.playPuff get() = play.playPuff
val IslandSettings.playPuffStrength get() = play.playPuffStrength
val IslandSettings.playFace get() = play.playFace
val IslandSettings.playRipple get() = play.playRipple
val IslandSettings.playRippleTap get() = play.playRippleTap
val IslandSettings.playPocket get() = play.playPocket
val IslandSettings.playPocketIdleSec get() = play.playPocketIdleSec
val IslandSettings.playLiquid get() = play.playLiquid
val IslandSettings.playEater get() = play.playEater
val IslandSettings.playEaterFull get() = play.playEaterFull
val IslandSettings.playPull get() = play.playPull
val IslandSettings.playDaylight get() = play.playDaylight
val IslandSettings.playDaylightAmt get() = play.playDaylightAmt
val IslandSettings.playGame get() = play.playGame
val IslandSettings.playFling get() = play.playFling
val IslandSettings.playPetTalk get() = play.playPetTalk
val IslandSettings.playPlant get() = play.playPlant
val IslandSettings.playSeason get() = play.playSeason
val IslandSettings.playSeasonMode get() = play.playSeasonMode
val IslandSettings.playRhythm get() = play.playRhythm
val IslandSettings.playRhythms get() = play.playRhythms
val IslandSettings.playParty get() = play.playParty
val IslandSettings.playBirthDay get() = play.playBirthDay
val IslandSettings.playBirthMonth get() = play.playBirthMonth
val IslandSettings.playDoodle get() = play.playDoodle
val IslandSettings.playVisitors get() = play.playVisitors
val IslandSettings.playDice get() = play.playDice
val IslandSettings.playDiceMode get() = play.playDiceMode
val IslandSettings.playBreath get() = play.playBreath
val IslandSettings.playBreathPattern get() = play.playBreathPattern
val IslandSettings.playBreathMin get() = play.playBreathMin
val IslandSettings.playNightLight get() = play.playNightLight
val IslandSettings.playNightAmt get() = play.playNightAmt
val IslandSettings.playShadow get() = play.playShadow

/**
 * Sonradan eklenen ayarlar. Ana sınıf JVM'in parametre sınırına yaklaştığı için ayrı tutulur (bkz. PlaySettings).
 */
@Immutable
data class MoreSettings(
    // ---- Özel ada tasarımı (Ana Sayfa): boştaki ada seçilen desenle ya da fotoğrafla dolar
    val designEnabled: Boolean = false,
    val designStyle: Int = 0,               // 0 siyah, 1 yıldızlı gece, 2 zebra dalga, 3 turkuaz mandala, 4 gün batımı, 5 sarı mozaik, 6 fotoğraf, 7+ ek desenler (bkz. DESIGN_NAMES)
    val designPhotoUri: String = "",        // boş = fotoğraf seçilmedi (galerideki adres; kopyalanmaz)
    val designAlways: Boolean = false,      // olay varken de (kapalı adada) desen görünsün
    val designPhotoExpand: Boolean = true,  // fotoğraf tasarımında: boştaki adaya dokununca ada genişleyip fotoğrafı gösterir
    val designPhotoTrigger: Int = 0,        // 0 dokununca, 1 basılı tutunca
    val designPhotoWDp: Float = 340f,       // genişleyen fotoğraf adasının boyutu
    val designPhotoHDp: Float = 170f,
    val designPhotoSec: Float = 5f,         // açık kalma süresi (sn)
    // ---- Orta boy müzik adası (Hap tasarımları): müzik çalarken ada küçülmez, orta boyda açık kalır
    val musicMid: Boolean = false,
    val musicMidWDp: Float = 350f,
    val musicMidHDp: Float = 150f,
    // ---- Sistem göstergeleri
    val lowBattIndicator: Boolean = true,   // pil %20 ve %10'a düşünce adada uyarı
    val lowBattExpand: Boolean = false,     // false: ince geniş hap (genişlemez), true: büyük kart (Düşük Güç Modu düğmesiyle)
    val netIndicator: Boolean = true,       // internet bağlanınca / kesilince adada iOS tarzı hap
    val airplaneIndicator: Boolean = true,  // uçak modu açılınca / kapanınca
    val ringVolIndicator: Boolean = true,   // zil sesi seviyesi değişince "Zil" + seviye çubuğu
    // ---- Müzik paneli
    val favStar: Boolean = false,           // beğeni düğmesi kalp yerine yıldız
    val lyricsSide: Boolean = false,        // açık müzik adası: kontroller solda, şarkı sözleri sağda
    // ---- Portal ada: boştaki adanın içinde, telefonu eğdikçe kayan derinlikli sahne
    val portalEnabled: Boolean = false,
    val portalScene: Int = 0,               // 0 uzay, 1 akvaryum, 2 gece şehri, 3 canlı gökyüzü (saate göre), 4+ ek sahneler (bkz. PORTAL_NAMES)
    val portalDepth: Float = 1f,            // eğime tepki (derinlik) çarpanı
    // ---- Başparmak modu: boştaki ada aşağı çekilince başparmağın altına iner
    val thumbEnabled: Boolean = false,
    // ---- Kamuflaj: boştaki ada yanındaki ekran rengini alıp kaybolur
    val camoEnabled: Boolean = false,
    val camoSkipPkgs: String = "",          // kamuflajın tamamen durduğu uygulamalar (virgülle ayrılmış paket adları)
    // ---- Mevcut özelliklerden türetilenler
    val notifTranslate: Boolean = false,    // yabancı dildeki bildirim adada çevrilmiş görünsün (cihazda çeviri)
    val notifTranslateTgt: String = "tr",   // çeviri hedef dili
    val contrastEnabled: Boolean = false,   // kamuflajın tersi: boştaki ada çevresinin zıt rengini alır
    val musicMood: Boolean = false,         // müzik adasındaki hava efekti şarkının kapak rengine göre kendiliğinden seçilir
    val drawerEnabled: Boolean = false,     // ada çekmecesi: hızlı düğmeler paneli
    val drawerTrigger: Int = 1,             // 0 dokununca, 1 basılı tutunca
    val trashIsland: Boolean = false,       // silinen bildirim adaya uçar, ada yutar
    val appLockEnabled: Boolean = false,    // seçili uygulamalar açılınca adada Face ID animasyonu (yalnızca görsel; doğrulama yok)
    val appLockPkgs: String = "",
    // ---- İkinci türetme turu
    val notifFlow: Boolean = false,         // bildirim yazısı kelime kelime adadan dökülür, sonra geri çekilir
    val thumbFan: Boolean = false,          // başparmak adasına basılı tutunca favoriler yelpaze gibi açılır
    val islandShape: Int = 0,               // boştaki ada şekli: 0 hap, 1 kalp, 2 bulut, 3 kedi, 4 elmas
    val coverPortal: Boolean = false,       // küçük müzik adasında albüm kapağı eğimle kayan derinlikli zemin olur
    val chargeRing: Boolean = false,        // şarja takınca adada dolan yeşil şarj halkası
    val notifBirds: Boolean = false,        // bildirim gelince uygulamanın ziyaretçisi adaya konar; dokununca bildirim açılır
    // ---- iOS turu
    val headphoneCard: Boolean = false,     // kulaklık bağlanınca adı + simgesiyle kart
    val headphoneBattery: Boolean = false,  // kartta pil yüzdesi (Yakındaki cihazlar izni ister)
    val navIsland: Boolean = false,         // navigasyon (Haritalar, Yandex, Waze...): adada dönüş oku + mesafe
    val focusIndicator: Boolean = false,    // Rahatsız Etmeyin açılınca / kapanınca geniş hap
    val hotspotIndicator: Boolean = false,  // kişisel erişim noktası açılınca / kapanınca geniş hap
    val deliveryIsland: Boolean = false,    // sipariş / kargo bildirimleri adada canlı takip olarak kalır
    // ---- Birleşik özellikler (ComboSection.kt)
    val fxTilt: Boolean = false,            // ada olaylarında eğim ve sallama: yığınlar kayar, kabarcıklar eğik yükselir
    val moodExtended: Boolean = false,      // şarkının havası portal olaylarını da seçebilir (sualtı, kutup ışığı, havai fişek...)
    val moodLyrics: Boolean = false,        // olay şarkı sözüne tepki verir: yeni satırda küçük, nakaratta büyük patlama
    val coverDissolve: Boolean = false,     // şarkı değişince kapak parçacıklara ayrılır, yenisi parçacıklardan birleşir
    val calendarFx: Boolean = false,        // özel gün / saatte olay kendiliğinden açılır (yılbaşı, 29 Ekim, dolunay...)
    val chargeArcs: Boolean = false,        // şarja takınca halkaya elektrik arkları akar
    val lowBattSparks: Boolean = false,     // pil azken boştaki ada arada bir kıvılcım çakar
    val notifShatter: Boolean = false,      // silinen bildirimin yazısı harf harf parçalanıp adaya çekilir
    val progressIsland: Boolean = false,    // indirme / yükleme gibi ilerleme bildirimleri adada canlı ilerleme olur
    val scoreIsland: Boolean = false,       // canlı maç skoru bildirimleri adada skor kartı olur
    val chargeEta: Boolean = false,         // şarjda tam dolmaya kalan süre (ChargeFlip.kt)
    val flipSilence: Boolean = false,       // telefon ters konunca Rahatsız Etmeyin (ChargeFlip.kt)
)

/** Özel tasarımda fotoğraf seçili ve dokununca genişleme açık mı? */
fun IslandSettings.designPhotoReady(): Boolean =
    more.designEnabled && more.designStyle == DESIGN_PHOTO && more.designPhotoUri.isNotEmpty() && more.designPhotoExpand && showIdle

const val DESIGN_PHOTO = 6

/** Uygulama kilidiyle korunan uygulamaların paket adları. */
fun MoreSettings.appLockList(): List<String> = appLockPkgs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

/** Kamuflajın tamamen durduğu uygulamaların paket adları. */
fun MoreSettings.camoSkipList(): List<String> = camoSkipPkgs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

class SettingsRepository(context: Context) {
    private val store = context.applicationContext.islandStore
    private object K {
        val CW = floatPreferencesKey("cw"); val CH = floatPreferencesKey("chh")
        val W = floatPreferencesKey("w"); val H = floatPreferencesKey("h")
        val OX = floatPreferencesKey("ox"); val OY = floatPreferencesKey("oy")
        val COLOR = intPreferencesKey("color"); val DYN = booleanPreferencesKey("dyn"); val EQ_DYN = booleanPreferencesKey("eq_dyn"); val EQ_COLOR = intPreferencesKey("eq_color")
        val ART_BG = booleanPreferencesKey("art_bg"); val ART_BG_C = booleanPreferencesKey("art_bg_c")
        val CB_ON = booleanPreferencesKey("cb_on"); val CB_SEP = booleanPreferencesKey("cb_sep"); val CB_DIM = floatPreferencesKey("cb_dim")
        val CB_U_BOTH = stringPreferencesKey("cb_u_both"); val CB_U_C = stringPreferencesKey("cb_u_c"); val CB_U_E = stringPreferencesKey("cb_u_e")
        val FX_W = intPreferencesKey("fx_w"); val FX_I = floatPreferencesKey("fx_i"); val FX_L = booleanPreferencesKey("fx_l"); val FX_LS = floatPreferencesKey("fx_ls")
        val FX_FS = floatPreferencesKey("fx_fs"); val FX_TT = intPreferencesKey("fx_tt"); val FX_MS = floatPreferencesKey("fx_ms"); val FX_B = booleanPreferencesKey("fx_b"); val FX_RS = floatPreferencesKey("fx_rs")
        val CB_V = stringPreferencesKey("cb_v"); val CA_V = stringPreferencesKey("ca_v"); val MB_FX = booleanPreferencesKey("mb_fx"); val NB_FX = booleanPreferencesKey("nb_fx")
        val CA_ON = booleanPreferencesKey("ca_on"); val CA_U = stringPreferencesKey("ca_u")
        val GLASS = booleanPreferencesKey("glass"); val ALPHA = floatPreferencesKey("alpha"); val BLUR = intPreferencesKey("blur")
        val LQ_ON = booleanPreferencesKey("lq_on"); val LQ_R = floatPreferencesKey("lq_r")
        val LQ_D = floatPreferencesKey("lq_d"); val LQ_S = floatPreferencesKey("lq_s")
        val GLOW = booleanPreferencesKey("glow"); val GLOW_I = floatPreferencesKey("glowI")
        val PG_ON = booleanPreferencesKey("pg_on"); val PG_ART = booleanPreferencesKey("pg_art")
        val PG_COLOR = intPreferencesKey("pg_color"); val PG_I = floatPreferencesKey("pg_i"); val PG_TIP = booleanPreferencesKey("pg_tip"); val PG_IN = booleanPreferencesKey("pg_in")
        val LIKE_ART = booleanPreferencesKey("like_art"); val LIKE_C = intPreferencesKey("like_c")
        val BORDER = booleanPreferencesKey("border"); val BORDER_C = intPreferencesKey("borderC"); val BORDER_ART = booleanPreferencesKey("border_art"); val ISL_C = intPreferencesKey("island_c"); val BORDER_W = floatPreferencesKey("borderW")
        val ANIM = booleanPreferencesKey("animOn"); val ANIM_S = intPreferencesKey("animStyle"); val MX_BLUR = booleanPreferencesKey("mx_blur"); val MX_FLIP = booleanPreferencesKey("mx_flip"); val SW_TRK = booleanPreferencesKey("sw_trk")

        val IDLE = booleanPreferencesKey("idle")
        val PILL_CALL = booleanPreferencesKey("pill_call"); val PILL_MUSIC = booleanPreferencesKey("pill_music")
        val PILL_FIT = booleanPreferencesKey("pill_fit"); val VOL_IND = booleanPreferencesKey("volume_ind"); val PILL_MW = floatPreferencesKey("pill_music_w"); val PILL_MH = floatPreferencesKey("pill_music_h")
        val PILL_ART = booleanPreferencesKey("pill_art"); val PILL_WL = intPreferencesKey("pill_wl")
        val PILL_WM = intPreferencesKey("pill_wm"); val PILL_WR = intPreferencesKey("pill_wr")
        val FAV_ANIM = booleanPreferencesKey("fav_anim"); val FAV_SPD = floatPreferencesKey("fav_spd")
        val NT_BANNER = booleanPreferencesKey("nt_banner"); val NT_INLINE = booleanPreferencesKey("nt_inline"); val NT_BMENU = booleanPreferencesKey("nt_bmenu"); val NT_BMENU_TAP = booleanPreferencesKey("nt_bmenu_tap"); val NT_BICON = booleanPreferencesKey("nt_bicon"); val NT_OPEN = booleanPreferencesKey("nt_open"); val NT_OPEN_LONG = booleanPreferencesKey("nt_open_long"); val NT_OPEN_DN = booleanPreferencesKey("nt_open_dn"); val NT_COL_TAP = booleanPreferencesKey("nt_col_tap"); val NT_COL_LONG = booleanPreferencesKey("nt_col_long"); val NT_COL_UP = booleanPreferencesKey("nt_col_up"); val NT_DEL_SIDE = booleanPreferencesKey("nt_del_side"); val NT_DEL_UP = booleanPreferencesKey("nt_del_up"); val SIDE_PROMOTE = booleanPreferencesKey("side_promote"); val NT_ANIM = booleanPreferencesKey("nt_anim"); val MX_ANIM = booleanPreferencesKey("mx_anim"); val MX_SPD = floatPreferencesKey("mx_spd"); val MX_SW = booleanPreferencesKey("mx_sw"); val MX_STR = booleanPreferencesKey("mx_str"); val LA_SPD = floatPreferencesKey("la_spd"); val MO_ANIM = booleanPreferencesKey("mo_anim"); val LA_CL = booleanPreferencesKey("la_cl"); val LA_AT = booleanPreferencesKey("la_at"); val FLA_CL = booleanPreferencesKey("fla_cl"); val FLA_AT = booleanPreferencesKey("fla_at"); val OP_EA = booleanPreferencesKey("op_ea"); val OP_ET = booleanPreferencesKey("op_et"); val OP_EX = booleanPreferencesKey("op_ex"); val OP_CA = booleanPreferencesKey("op_ca"); val OP_CL = booleanPreferencesKey("op_cl"); val OP_CT = booleanPreferencesKey("op_ct"); val EXP_LONG = booleanPreferencesKey("exp_long"); val SW_DN = booleanPreferencesKey("sw_dn"); val SW_UP = booleanPreferencesKey("sw_up"); val LP_MS = intPreferencesKey("lp_ms"); val MX_CLASSIC = booleanPreferencesKey("mx_classic"); val MX_ATOMIC = booleanPreferencesKey("mx_atomic"); val HIDE_MUSIC = booleanPreferencesKey("hide_music_in_app"); val MX_ATOMIC_FULL = booleanPreferencesKey("mx_atomic_full"); val MX_FLUID = booleanPreferencesKey("mx_fluid")
        val PL_PHY = booleanPreferencesKey("pl_phy"); val PL_PHY_R = floatPreferencesKey("pl_phy_r"); val PL_JELLY = booleanPreferencesKey("pl_jelly")
        val PL_PET = booleanPreferencesKey("pl_pet"); val PL_MAG = booleanPreferencesKey("pl_mag"); val PL_MAG_B = booleanPreferencesKey("pl_mag_b")
        val PL_PUFF = booleanPreferencesKey("pl_puff"); val PL_PUFF_S = floatPreferencesKey("pl_puff_s"); val PL_FACE = booleanPreferencesKey("pl_face")
        val PL_RIP = booleanPreferencesKey("pl_rip"); val PL_RIP_T = booleanPreferencesKey("pl_rip_t"); val PL_POC = booleanPreferencesKey("pl_poc"); val PL_POC_S = floatPreferencesKey("pl_poc_s")
        val PL_LIQ = booleanPreferencesKey("pl_liq"); val PL_EAT = booleanPreferencesKey("pl_eat"); val PL_EAT_F = booleanPreferencesKey("pl_eat_f"); val PL_PULL = booleanPreferencesKey("pl_pull")
        val PL_DAY = booleanPreferencesKey("pl_day"); val PL_DAY_A = floatPreferencesKey("pl_day_a"); val PL_GAME = booleanPreferencesKey("pl_game")
        val PL_FLING = booleanPreferencesKey("pl_fling"); val PL_TALK = booleanPreferencesKey("pl_talk"); val PL_PLANT = booleanPreferencesKey("pl_plant")
        val PL_SEASON = booleanPreferencesKey("pl_season"); val PL_SEASON_M = intPreferencesKey("pl_season_m"); val PL_RHY = booleanPreferencesKey("pl_rhy"); val PL_RHYS = stringPreferencesKey("pl_rhys")
        val PL_PARTY = booleanPreferencesKey("pl_party"); val PL_BD = intPreferencesKey("pl_bd"); val PL_BM = intPreferencesKey("pl_bm"); val PL_DOODLE = booleanPreferencesKey("pl_doodle")
        val PL_VIS = booleanPreferencesKey("pl_vis"); val PL_DICE = booleanPreferencesKey("pl_dice"); val PL_DICE_M = intPreferencesKey("pl_dice_m")
        val PL_BREATH = booleanPreferencesKey("pl_breath"); val PL_BREATH_P = intPreferencesKey("pl_breath_p"); val PL_BREATH_M = floatPreferencesKey("pl_breath_m")
        val PL_NIGHT = booleanPreferencesKey("pl_night"); val PL_NIGHT_A = floatPreferencesKey("pl_night_a"); val PL_SHADOW = booleanPreferencesKey("pl_shadow")
        val HIDE_SB = booleanPreferencesKey("hide_sb")
        val INV_ISLAND = booleanPreferencesKey("inv_island")
        val INV_C = booleanPreferencesKey("inv_custom"); val INV_W = floatPreferencesKey("inv_w"); val INV_H = floatPreferencesKey("inv_h")
        val INV_X = floatPreferencesKey("inv_x"); val INV_Y = floatPreferencesKey("inv_y")
        val LAND_ON = booleanPreferencesKey("land_on"); val LAND_BELOW = booleanPreferencesKey("land_below"); val LAND_IN_ISLAND = booleanPreferencesKey("land_in_island")
        val NEON_ST = intPreferencesKey("neon_st"); val NEON_C1 = intPreferencesKey("neon_c1"); val NEON_C2 = intPreferencesKey("neon_c2")
        val NEON_W = floatPreferencesKey("neon_w"); val NEON_G = floatPreferencesKey("neon_g"); val NEON_SP = floatPreferencesKey("neon_sp")
        val DEC_TOP = intPreferencesKey("dec_top"); val DEC_HANG = intPreferencesKey("dec_hang"); val PET = intPreferencesKey("isl_pet")
        val DRIP = booleanPreferencesKey("drip"); val DRIP_S = floatPreferencesKey("drip_s"); val EDGE_ALW = booleanPreferencesKey("edge_alw")
        val FAV_ON = booleanPreferencesKey("fav_on"); val FAV_TRIG = intPreferencesKey("fav_trig"); val FAV_PKGS = stringPreferencesKey("fav_pkgs")
        val REC_ON = booleanPreferencesKey("rec_on"); val REC_WIDE = booleanPreferencesKey("rec_wide_v2")
        val AS_ON = booleanPreferencesKey("as_on"); val AS_W = floatPreferencesKey("as_w"); val AS_H = floatPreferencesKey("as_h"); val AS_FULL = booleanPreferencesKey("as_full"); val AS_STYLE = intPreferencesKey("as_style")
        val RB_ON = booleanPreferencesKey("rb_on"); val RB_A = floatPreferencesKey("rb_a")

        val LY_ON = booleanPreferencesKey("ly_on"); val LY_PANEL = booleanPreferencesKey("ly_panel")
        val LY_COMPACT = booleanPreferencesKey("ly_compact"); val LY_OUT = booleanPreferencesKey("ly_out")
        val LY_P_SIZE = floatPreferencesKey("ly_pSize"); val LY_P_LINES = intPreferencesKey("ly_pLines")
        val LY_C_SIZE = floatPreferencesKey("ly_cSize"); val LY_C_W = floatPreferencesKey("ly_cW")
        val LY_O_SIZE = floatPreferencesKey("ly_oSize"); val LY_O_LINES = intPreferencesKey("ly_oLines")
        val LY_O_W = floatPreferencesKey("ly_oW"); val LY_O_X = floatPreferencesKey("ly_oX"); val LY_O_Y = floatPreferencesKey("ly_oY")
        val LY_COLOR = intPreferencesKey("ly_color"); val LY_DIM = floatPreferencesKey("ly_dim")
        val LY_DYN = booleanPreferencesKey("ly_dyn")
        val LY_FILL = booleanPreferencesKey("ly_fill"); val LY_OFF = intPreferencesKey("ly_off")
        val LY_GLOW = booleanPreferencesKey("ly_glow"); val LY_GLOW_L = floatPreferencesKey("ly_glowL")
        val LY_ANIM = booleanPreferencesKey("ly_anim"); val LY_ANIM_S = intPreferencesKey("ly_animS"); val LY_ANIM_MS = intPreferencesKey("ly_animMs")
        val DOT_ON = booleanPreferencesKey("dot_on"); val DOT_MID = floatPreferencesKey("dot_mid")
        val DOT_L = floatPreferencesKey("dot_l"); val DOT_R = floatPreferencesKey("dot_r"); val DOT_GAP = floatPreferencesKey("dot_gap")
        val DOT_OX = floatPreferencesKey("dot_ox"); val DOT_OY = floatPreferencesKey("dot_oy")
        val LD_ON = booleanPreferencesKey("ld_on"); val LD_C = intPreferencesKey("ld_c"); val LD_S = floatPreferencesKey("ld_s"); val LD_G = floatPreferencesKey("ld_g"); val LD_Y = floatPreferencesKey("ld_y")
        val LD_GL = booleanPreferencesKey("ld_gl"); val LD_GD = floatPreferencesKey("ld_gd"); val LD_GA = floatPreferencesKey("ld_ga")
        val LD_B = booleanPreferencesKey("ld_b"); val LD_BM = intPreferencesKey("ld_bm"); val LD_BA = floatPreferencesKey("ld_ba"); val LD_BS = intPreferencesKey("ld_bs")
        val TORCH_ON = booleanPreferencesKey("torch_on")
        val FID_ON = booleanPreferencesKey("fid_on"); val FID_C = intPreferencesKey("fid_c"); val FID_S = floatPreferencesKey("fid_s")
        val FID_Y = floatPreferencesKey("fid_y"); val FID_SP = floatPreferencesKey("fid_sp")
        val SFX_ON = booleanPreferencesKey("sfx_on"); val SFX_TAP = stringPreferencesKey("sfx_tap"); val SFX_CHG = stringPreferencesKey("sfx_chg"); val SFX_UNP = stringPreferencesKey("sfx_unp")
        val SFX_NTF = stringPreferencesKey("sfx_ntf"); val SFX_SIL = stringPreferencesKey("sfx_sil"); val SFX_CALL = stringPreferencesKey("sfx_call")
        val MU_EXP_S = floatPreferencesKey("mu_exp_s"); val NT_EXP_S = floatPreferencesKey("nt_exp_s"); val NT_CMP_S = floatPreferencesKey("nt_cmp_s")
        val DOT_MERGE = booleanPreferencesKey("dot_merge"); val DOT_MERGE_KEEP = booleanPreferencesKey("dot_merge_keep"); val PRIV_IND = booleanPreferencesKey("privacy_ind"); val RINGER_IND = booleanPreferencesKey("ringer_ind"); val RINGER_EXP = booleanPreferencesKey("ringer_exp"); val RINGER_W = floatPreferencesKey("ringer_w"); val RINGER_H = floatPreferencesKey("ringer_h"); val HIDE_APPS = booleanPreferencesKey("hide_apps"); val HIDE_PKGS = stringPreferencesKey("hide_pkgs"); val UI_HAPTIC = booleanPreferencesKey("ui_haptic"); val DOT_N = intPreferencesKey("dot_n"); val DOT_L2 = floatPreferencesKey("dot_l2"); val DOT_R2 = floatPreferencesKey("dot_r2")
        val STACK_EXP = booleanPreferencesKey("stack_exp")
        val IP_ON = booleanPreferencesKey("ip_on"); val IP_CIRCLE = booleanPreferencesKey("ip_circle"); val IP_EV = booleanPreferencesKey("ip_ev")
        val IP_SIDE = intPreferencesKey("ip_side"); val IP_PW = floatPreferencesKey("ip_pw"); val IP_PH = floatPreferencesKey("ip_ph")
        val IP_CD = floatPreferencesKey("ip_cd"); val IP_GAP = floatPreferencesKey("ip_gap"); val IP_CTR = booleanPreferencesKey("ip_ctr")
        val IP_OX = floatPreferencesKey("ip_ox"); val IP_OY = floatPreferencesKey("ip_oy")
        val TRN_ON = booleanPreferencesKey("trn_on"); val TRN_TRIG = intPreferencesKey("trn_trig")
        val TRN_SRC = stringPreferencesKey("trn_src"); val TRN_TGT = stringPreferencesKey("trn_tgt")
        val CE_ON = booleanPreferencesKey("ce_on"); val CE_MAP = stringPreferencesKey("ce_map")
        // MoreSettings
        val DS_ON = booleanPreferencesKey("ds_on"); val DS_STYLE = intPreferencesKey("ds_style"); val DS_PHOTO = stringPreferencesKey("ds_photo")
        val DS_ALW = booleanPreferencesKey("ds_alw"); val DS_EXP = booleanPreferencesKey("ds_exp"); val DS_TRIG = intPreferencesKey("ds_trig")
        val DS_W = floatPreferencesKey("ds_w"); val DS_H = floatPreferencesKey("ds_h"); val DS_SEC = floatPreferencesKey("ds_sec")
        val MID_ON = booleanPreferencesKey("mid_on"); val MID_W = floatPreferencesKey("mid_w"); val MID_H = floatPreferencesKey("mid_h")
        val LB_ON = booleanPreferencesKey("lb_on"); val LB_EXP = booleanPreferencesKey("lb_exp")
        val NET_ON = booleanPreferencesKey("net_on"); val AIR_ON = booleanPreferencesKey("air_on"); val RVOL_ON = booleanPreferencesKey("rvol_on")
        val FAV_STAR = booleanPreferencesKey("fav_star"); val LY_SIDE = booleanPreferencesKey("ly_side")
        val PT_ON = booleanPreferencesKey("pt_on"); val PT_SCENE = intPreferencesKey("pt_scene"); val PT_DEPTH = floatPreferencesKey("pt_depth")
        val TH_ON = booleanPreferencesKey("th_on"); val CAMO_ON = booleanPreferencesKey("camo_on"); val CAMO_SKIP = stringPreferencesKey("camo_skip")
        val NT_TR = booleanPreferencesKey("nt_tr"); val NT_TR_TGT = stringPreferencesKey("nt_tr_tgt"); val CONTRAST = booleanPreferencesKey("contrast")
        val MOOD = booleanPreferencesKey("mood"); val DRW_ON = booleanPreferencesKey("drw_on"); val DRW_TRIG = intPreferencesKey("drw_trig")
        val TRASH = booleanPreferencesKey("trash"); val AL_ON = booleanPreferencesKey("al_on"); val AL_PKGS = stringPreferencesKey("al_pkgs")
        val NFLOW = booleanPreferencesKey("nflow"); val TFAN = booleanPreferencesKey("tfan"); val ISHAPE = intPreferencesKey("ishape")
        val CPORTAL = booleanPreferencesKey("cportal"); val CRING = booleanPreferencesKey("cring"); val NBIRD = booleanPreferencesKey("nbird")
        val HPCARD = booleanPreferencesKey("hp_card"); val HPBATT = booleanPreferencesKey("hp_batt"); val NAVI = booleanPreferencesKey("nav_island")
        val FOCUSI = booleanPreferencesKey("focus_ind"); val HOTSPOT = booleanPreferencesKey("hotspot_ind"); val DELIV = booleanPreferencesKey("deliv_island")
        val C_TILT = booleanPreferencesKey("c_tilt"); val C_MOODX = booleanPreferencesKey("c_moodx"); val C_MOODL = booleanPreferencesKey("c_moodl")
        val C_DISS = booleanPreferencesKey("c_diss"); val C_CAL = booleanPreferencesKey("c_cal"); val C_ARCS = booleanPreferencesKey("c_arcs")
        val C_LOWB = booleanPreferencesKey("c_lowb"); val C_SHAT = booleanPreferencesKey("c_shat"); val C_PROG = booleanPreferencesKey("c_prog")
        val C_SCORE = booleanPreferencesKey("c_score")
        val C_ETA = booleanPreferencesKey("c_eta"); val C_FLIP = booleanPreferencesKey("c_flip")
    }

    val flow: Flow<IslandSettings> = store.data.map { p ->
        val d = IslandSettings()
        IslandSettings(
            compactWidthDp = p[K.CW] ?: d.compactWidthDp,
            compactHeightDp = p[K.CH] ?: d.compactHeightDp,
            expandedWidthDp = p[K.W] ?: d.expandedWidthDp,
            expandedHeightDp = p[K.H] ?: d.expandedHeightDp,
            offsetXDp = p[K.OX] ?: d.offsetXDp,
            offsetYDp = p[K.OY] ?: d.offsetYDp,
            accentArgb = p[K.COLOR] ?: d.accentArgb,
            dynamicColor = p[K.DYN] ?: d.dynamicColor,
            eqDynamicColor = p[K.EQ_DYN] ?: d.eqDynamicColor,
            eqColorArgb = p[K.EQ_COLOR] ?: d.eqColorArgb,
            artBgEnabled = p[K.ART_BG] ?: d.artBgEnabled,
            artBgCompact = p[K.ART_BG_C] ?: d.artBgCompact,
            customBgEnabled = p[K.CB_ON] ?: d.customBgEnabled,
            customBgSeparate = p[K.CB_SEP] ?: d.customBgSeparate,
            customBgDim = p[K.CB_DIM] ?: d.customBgDim,
            customBgBothUri = p[K.CB_U_BOTH] ?: d.customBgBothUri,
            customBgCompactUri = p[K.CB_U_C] ?: d.customBgCompactUri,
            customBgExpandedUri = p[K.CB_U_E] ?: d.customBgExpandedUri,
            customBgVideoUri = p[K.CB_V] ?: d.customBgVideoUri,
            fxWeather = p[K.FX_W] ?: d.fxWeather,
            fxIntensity = p[K.FX_I] ?: d.fxIntensity,
            fxLightning = p[K.FX_L] ?: d.fxLightning,
            fxLightningSec = p[K.FX_LS] ?: d.fxLightningSec,
            fxFreezeSpeed = p[K.FX_FS] ?: d.fxFreezeSpeed,
            fxThawTaps = p[K.FX_TT] ?: d.fxThawTaps,
            fxMeltSpeed = p[K.FX_MS] ?: d.fxMeltSpeed,
            fxBreak = p[K.FX_B] ?: d.fxBreak,
            fxRestoreSec = p[K.FX_RS] ?: d.fxRestoreSec,
            musicBottomFx = p[K.MB_FX] ?: d.musicBottomFx,
            notifBottomFx = p[K.NB_FX] ?: d.notifBottomFx,
            customArtEnabled = p[K.CA_ON] ?: d.customArtEnabled,
            customArtUri = p[K.CA_U] ?: d.customArtUri,
            customArtVideoUri = p[K.CA_V] ?: d.customArtVideoUri,
            glassEnabled = p[K.GLASS] ?: d.glassEnabled,
            glassAlpha = p[K.ALPHA] ?: d.glassAlpha,
            blurRadiusPx = p[K.BLUR] ?: d.blurRadiusPx,
            liquidEnabled = p[K.LQ_ON] ?: d.liquidEnabled,
            liquidRefraction = p[K.LQ_R] ?: d.liquidRefraction,
            liquidDispersion = p[K.LQ_D] ?: d.liquidDispersion,
            liquidSpecular = p[K.LQ_S] ?: d.liquidSpecular,
            glowEnabled = p[K.GLOW] ?: d.glowEnabled,
            glowIntensity = p[K.GLOW_I] ?: d.glowIntensity,
            progressGlowEnabled = p[K.PG_ON] ?: d.progressGlowEnabled,
            progressGlowArtColor = p[K.PG_ART] ?: d.progressGlowArtColor,
            progressGlowColorArgb = p[K.PG_COLOR] ?: d.progressGlowColorArgb,
            progressGlowIntensity = p[K.PG_I] ?: d.progressGlowIntensity,
            progressGlowTipOnly = p[K.PG_TIP] ?: d.progressGlowTipOnly,
            progressGlowInside = p[K.PG_IN] ?: d.progressGlowInside,
            likeArtColor = p[K.LIKE_ART] ?: d.likeArtColor,
            likeColorArgb = p[K.LIKE_C] ?: d.likeColorArgb,
            borderEnabled = p[K.BORDER] ?: d.borderEnabled,
            borderColorArgb = p[K.BORDER_C] ?: d.borderColorArgb,
            borderArtColor = p[K.BORDER_ART] ?: d.borderArtColor,
            islandColorArgb = p[K.ISL_C] ?: d.islandColorArgb,
            borderWidthDp = p[K.BORDER_W] ?: d.borderWidthDp,
            animEnabled = p[K.ANIM] ?: d.animEnabled,
            animStyle = p[K.ANIM_S] ?: d.animStyle,
            musicBlurAnim = p[K.MX_BLUR] ?: d.musicBlurAnim,
            musicArtFlip = p[K.MX_FLIP] ?: d.musicArtFlip,
            swipeTrackChange = p[K.SW_TRK] ?: d.swipeTrackChange,
            pillCallStyle = p[K.PILL_CALL] ?: d.pillCallStyle,
            pillMusicStyle = p[K.PILL_MUSIC] ?: d.pillMusicStyle,
            pillFitIsland = p[K.PILL_FIT] ?: d.pillFitIsland,
            pillMusicWDp = (p[K.PILL_MW] ?: d.pillMusicWDp).coerceIn(220f, 400f),
            pillMusicHDp = (p[K.PILL_MH] ?: d.pillMusicHDp).coerceIn(70f, 200f),
            pillWaveArtColor = p[K.PILL_ART] ?: d.pillWaveArtColor,
            pillWaveLeftArgb = p[K.PILL_WL] ?: d.pillWaveLeftArgb,
            pillWaveMidArgb = p[K.PILL_WM] ?: d.pillWaveMidArgb,
            pillWaveRightArgb = p[K.PILL_WR] ?: d.pillWaveRightArgb,
            showIdle = p[K.IDLE] ?: d.showIdle,
            favoritesEnabled = p[K.FAV_ON] ?: d.favoritesEnabled,
            favoritesTrigger = p[K.FAV_TRIG] ?: d.favoritesTrigger,
            favoritePkgs = p[K.FAV_PKGS] ?: d.favoritePkgs,
            favoriteLaunchAnim = p[K.FAV_ANIM] ?: d.favoriteLaunchAnim,
            favoriteLaunchSpeed = p[K.FAV_SPD] ?: d.favoriteLaunchSpeed,
            notifBanner = p[K.NT_BANNER] ?: d.notifBanner,
            notifInline = p[K.NT_INLINE] ?: d.notifInline,
            bannerMenuEnabled = p[K.NT_BMENU] ?: d.bannerMenuEnabled,
            bannerMenuTap = p[K.NT_BMENU_TAP] ?: d.bannerMenuTap,
            bannerIconOpens = p[K.NT_BICON] ?: d.bannerIconOpens,
            notifTapOpens = p[K.NT_OPEN] ?: d.notifTapOpens,
            notifOpenLong = p[K.NT_OPEN_LONG] ?: d.notifOpenLong,
            notifOpenSwipeDown = p[K.NT_OPEN_DN] ?: d.notifOpenSwipeDown,
            notifCollapseTap = p[K.NT_COL_TAP] ?: d.notifCollapseTap,
            notifCollapseLong = p[K.NT_COL_LONG] ?: d.notifCollapseLong,
            notifCollapseSwipeUp = p[K.NT_COL_UP] ?: d.notifCollapseSwipeUp,
            notifDeleteSwipeSide = p[K.NT_DEL_SIDE] ?: d.notifDeleteSwipeSide,
            notifDeleteSwipeUp = p[K.NT_DEL_UP] ?: d.notifDeleteSwipeUp,
            sideTapPromote = p[K.SIDE_PROMOTE] ?: d.sideTapPromote,
            notifExpandedSec = p[K.NT_EXP_S] ?: d.notifExpandedSec,
            musicExpandedSec = p[K.MU_EXP_S] ?: d.musicExpandedSec,
            lockDotEnabled = p[K.LD_ON] ?: d.lockDotEnabled, lockDotColorArgb = p[K.LD_C] ?: d.lockDotColorArgb,
            lockDotSizeDp = p[K.LD_S] ?: d.lockDotSizeDp, lockDotGapDp = p[K.LD_G] ?: d.lockDotGapDp, lockDotOffsetYDp = p[K.LD_Y] ?: d.lockDotOffsetYDp,
            lockDotGlow = p[K.LD_GL] ?: d.lockDotGlow, lockDotGlowDp = p[K.LD_GD] ?: d.lockDotGlowDp, lockDotGlowAlpha = p[K.LD_GA] ?: d.lockDotGlowAlpha,
            lockDotBlink = p[K.LD_B] ?: d.lockDotBlink, lockDotBlinkMs = p[K.LD_BM] ?: d.lockDotBlinkMs,
            lockDotMinAlpha = p[K.LD_BA] ?: d.lockDotMinAlpha, lockDotBlinkStyle = p[K.LD_BS] ?: d.lockDotBlinkStyle,
            torchIndicator = p[K.TORCH_ON] ?: d.torchIndicator,
            faceIdEnabled = p[K.FID_ON] ?: d.faceIdEnabled, faceIdColorArgb = p[K.FID_C] ?: d.faceIdColorArgb,
            faceIdSizeDp = p[K.FID_S] ?: d.faceIdSizeDp, faceIdOffsetYDp = p[K.FID_Y] ?: d.faceIdOffsetYDp, faceIdSpeed = p[K.FID_SP] ?: d.faceIdSpeed,
            sfxEnabled = p[K.SFX_ON] ?: d.sfxEnabled,
            sfxTap = SoundCue.decode(p[K.SFX_TAP]), sfxCharge = SoundCue.decode(p[K.SFX_CHG]), sfxUnplug = SoundCue.decode(p[K.SFX_UNP]),
            sfxNotif = SoundCue.decode(p[K.SFX_NTF]), sfxSilent = SoundCue.decode(p[K.SFX_SIL]), sfxCall = SoundCue.decode(p[K.SFX_CALL]),
            notifCompactSec = p[K.NT_CMP_S] ?: d.notifCompactSec,
            hideStatusBar = p[K.HIDE_SB] ?: d.hideStatusBar,
            invertedIsland = p[K.INV_ISLAND] ?: d.invertedIsland,
            invCustom = p[K.INV_C] ?: d.invCustom,
            invWidthDp = p[K.INV_W] ?: d.invWidthDp,
            invHeightDp = p[K.INV_H] ?: d.invHeightDp,
            invOffsetXDp = p[K.INV_X] ?: d.invOffsetXDp,
            invOffsetYDp = p[K.INV_Y] ?: d.invOffsetYDp,
            landscapeMode = p[K.LAND_ON] ?: d.landscapeMode,
            landscapeBubbleBelow = p[K.LAND_BELOW] ?: d.landscapeBubbleBelow,
            landscapeEventsInIsland = p[K.LAND_IN_ISLAND] ?: d.landscapeEventsInIsland,
            neonStyle = p[K.NEON_ST] ?: d.neonStyle, neonColor1Argb = p[K.NEON_C1] ?: d.neonColor1Argb,
            neonColor2Argb = p[K.NEON_C2] ?: d.neonColor2Argb, neonWidthDp = p[K.NEON_W] ?: d.neonWidthDp,
            neonGlow = p[K.NEON_G] ?: d.neonGlow, neonSpeed = p[K.NEON_SP] ?: d.neonSpeed,
            decorTop = p[K.DEC_TOP] ?: d.decorTop, decorHang = p[K.DEC_HANG] ?: d.decorHang, islandPet = p[K.PET] ?: d.islandPet,
            dripEnabled = p[K.DRIP] ?: d.dripEnabled, dripEverySec = p[K.DRIP_S] ?: d.dripEverySec,
            edgeGlowAlways = p[K.EDGE_ALW] ?: d.edgeGlowAlways,
            notifLaunchAnim = p[K.NT_ANIM] ?: d.notifLaunchAnim,
            musicExitAnim = p[K.MX_ANIM] ?: d.musicExitAnim,
            musicExitSpeed = p[K.MX_SPD] ?: d.musicExitSpeed,
            musicExitSwallow = p[K.MX_SW] ?: d.musicExitSwallow,
            musicExitStretch = p[K.MX_STR] ?: d.musicExitStretch,
            musicFluidAnim = p[K.MX_FLUID] ?: d.musicFluidAnim,
            play = PlaySettings(
                playPhysics = p[K.PL_PHY] ?: d.playPhysics, playPhysicsRangeDp = p[K.PL_PHY_R] ?: d.playPhysicsRangeDp, playJelly = p[K.PL_JELLY] ?: d.playJelly,
                playPet = p[K.PL_PET] ?: d.playPet, playMagnet = p[K.PL_MAG] ?: d.playMagnet, playMagnetBuds = p[K.PL_MAG_B] ?: d.playMagnetBuds,
                playPuff = p[K.PL_PUFF] ?: d.playPuff, playPuffStrength = p[K.PL_PUFF_S] ?: d.playPuffStrength, playFace = p[K.PL_FACE] ?: d.playFace,
                playRipple = p[K.PL_RIP] ?: d.playRipple, playRippleTap = p[K.PL_RIP_T] ?: d.playRippleTap,
                playPocket = p[K.PL_POC] ?: d.playPocket, playPocketIdleSec = p[K.PL_POC_S] ?: d.playPocketIdleSec,
                playLiquid = p[K.PL_LIQ] ?: d.playLiquid, playEater = p[K.PL_EAT] ?: d.playEater, playEaterFull = p[K.PL_EAT_F] ?: d.playEaterFull,
                playPull = p[K.PL_PULL] ?: d.playPull, playDaylight = p[K.PL_DAY] ?: d.playDaylight, playDaylightAmt = p[K.PL_DAY_A] ?: d.playDaylightAmt,
                playGame = p[K.PL_GAME] ?: d.playGame,
                playFling = p[K.PL_FLING] ?: d.playFling, playPetTalk = p[K.PL_TALK] ?: d.playPetTalk, playPlant = p[K.PL_PLANT] ?: d.playPlant,
                playSeason = p[K.PL_SEASON] ?: d.playSeason, playSeasonMode = p[K.PL_SEASON_M] ?: d.playSeasonMode,
                playRhythm = p[K.PL_RHY] ?: d.playRhythm, playRhythms = p[K.PL_RHYS] ?: d.playRhythms,
                playParty = p[K.PL_PARTY] ?: d.playParty, playBirthDay = p[K.PL_BD] ?: d.playBirthDay, playBirthMonth = p[K.PL_BM] ?: d.playBirthMonth,
                playDoodle = p[K.PL_DOODLE] ?: d.playDoodle,
                playVisitors = p[K.PL_VIS] ?: d.playVisitors, playDice = p[K.PL_DICE] ?: d.playDice, playDiceMode = p[K.PL_DICE_M] ?: d.playDiceMode,
                playBreath = p[K.PL_BREATH] ?: d.playBreath, playBreathPattern = p[K.PL_BREATH_P] ?: d.playBreathPattern, playBreathMin = p[K.PL_BREATH_M] ?: d.playBreathMin,
                playNightLight = p[K.PL_NIGHT] ?: d.playNightLight, playNightAmt = p[K.PL_NIGHT_A] ?: d.playNightAmt, playShadow = p[K.PL_SHADOW] ?: d.playShadow,
            ),
            launchSpeed = p[K.LA_SPD] ?: d.launchSpeed,
            musicOpenAnim = p[K.MO_ANIM] ?: d.musicOpenAnim,
            launchClassic = p[K.LA_CL] ?: d.launchClassic, launchAtomic = p[K.LA_AT] ?: d.launchAtomic,
            favLaunchClassic = p[K.FLA_CL] ?: d.favLaunchClassic, favLaunchAtomic = p[K.FLA_AT] ?: d.favLaunchAtomic,
            openExpArt = p[K.OP_EA] ?: d.openExpArt, openExpTitle = p[K.OP_ET] ?: d.openExpTitle, openExpAny = p[K.OP_EX] ?: d.openExpAny,
            openCompArt = p[K.OP_CA] ?: d.openCompArt, openCompLong = p[K.OP_CL] ?: d.openCompLong, openCompTap = p[K.OP_CT] ?: d.openCompTap,
            expandOnLong = p[K.EXP_LONG] ?: d.expandOnLong, swipeDownExpand = p[K.SW_DN] ?: d.swipeDownExpand,
            swipeUpCollapse = p[K.SW_UP] ?: d.swipeUpCollapse, longPressMs = p[K.LP_MS] ?: d.longPressMs,
            musicExitClassic = p[K.MX_CLASSIC] ?: d.musicExitClassic,
            musicExitAtomic = p[K.MX_ATOMIC] ?: d.musicExitAtomic,
            musicExitAtomicFull = p[K.MX_ATOMIC_FULL] ?: d.musicExitAtomicFull,
            hideInMusicApp = p[K.HIDE_MUSIC] ?: d.hideInMusicApp,
            screenRecEnabled = p[K.REC_ON] ?: d.screenRecEnabled,
            screenRecWide = p[K.REC_WIDE] ?: d.screenRecWide,
            assistantEnabled = p[K.AS_ON] ?: d.assistantEnabled,
            assistantWidthDp = p[K.AS_W] ?: d.assistantWidthDp,
            assistantHeightDp = p[K.AS_H] ?: d.assistantHeightDp,
            assistantFullWidth = p[K.AS_FULL] ?: d.assistantFullWidth,
            assistantStyle = (p[K.AS_STYLE] ?: d.assistantStyle).coerceIn(0, 1),
            rainbowEnabled = p[K.RB_ON] ?: d.rainbowEnabled,
            rainbowAlpha = p[K.RB_A] ?: d.rainbowAlpha,
            lyricsEnabled = p[K.LY_ON] ?: d.lyricsEnabled,
            lyricsInPanel = p[K.LY_PANEL] ?: d.lyricsInPanel,
            lyricsCompact = p[K.LY_COMPACT] ?: d.lyricsCompact,
            lyricsOutside = p[K.LY_OUT] ?: d.lyricsOutside,
            lyricsPanelSizeSp = p[K.LY_P_SIZE] ?: d.lyricsPanelSizeSp,
            lyricsPanelLines = p[K.LY_P_LINES] ?: d.lyricsPanelLines,
            lyricsCompactSizeSp = p[K.LY_C_SIZE] ?: d.lyricsCompactSizeSp,
            lyricsCompactWidthDp = p[K.LY_C_W] ?: d.lyricsCompactWidthDp,
            lyricsOutsideSizeSp = p[K.LY_O_SIZE] ?: d.lyricsOutsideSizeSp,
            lyricsOutsideLines = p[K.LY_O_LINES] ?: d.lyricsOutsideLines,
            lyricsOutsideWidthDp = p[K.LY_O_W] ?: d.lyricsOutsideWidthDp,
            lyricsOutsideXDp = p[K.LY_O_X] ?: d.lyricsOutsideXDp,
            lyricsOutsideYDp = p[K.LY_O_Y] ?: d.lyricsOutsideYDp,
            lyricsColorArgb = p[K.LY_COLOR] ?: d.lyricsColorArgb,
            lyricsDynamicColor = p[K.LY_DYN] ?: d.lyricsDynamicColor,
            lyricsDimAlpha = p[K.LY_DIM] ?: d.lyricsDimAlpha,
            lyricsFillEnabled = p[K.LY_FILL] ?: d.lyricsFillEnabled,
            lyricsOffsetMs = p[K.LY_OFF] ?: d.lyricsOffsetMs,
            lyricsGlowEnabled = p[K.LY_GLOW] ?: d.lyricsGlowEnabled,
            lyricsGlowLevel = p[K.LY_GLOW_L] ?: d.lyricsGlowLevel,
            lyricsAnimEnabled = p[K.LY_ANIM] ?: d.lyricsAnimEnabled,
            lyricsAnimStyle = p[K.LY_ANIM_S] ?: d.lyricsAnimStyle,
            lyricsAnimMs = p[K.LY_ANIM_MS] ?: d.lyricsAnimMs,
            dotMode = p[K.DOT_ON] ?: d.dotMode,
            dotMidDp = p[K.DOT_MID] ?: d.dotMidDp,
            dotLeftDp = p[K.DOT_L] ?: d.dotLeftDp,
            dotRightDp = p[K.DOT_R] ?: d.dotRightDp,
            dotGapDp = p[K.DOT_GAP] ?: d.dotGapDp,
            dotCount = (p[K.DOT_N] ?: d.dotCount).coerceIn(3, 5),
            dotLeft2Dp = p[K.DOT_L2] ?: d.dotLeft2Dp,
            dotRight2Dp = p[K.DOT_R2] ?: d.dotRight2Dp,
            dotOffsetXDp = p[K.DOT_OX] ?: d.dotOffsetXDp,
            dotOffsetYDp = p[K.DOT_OY] ?: d.dotOffsetYDp,
            dotMerge = p[K.DOT_MERGE] ?: d.dotMerge,
            dotMergeKeep = p[K.DOT_MERGE_KEEP] ?: d.dotMergeKeep,
            privacyIndicator = p[K.PRIV_IND] ?: d.privacyIndicator,
            ringerIndicator = p[K.RINGER_IND] ?: d.ringerIndicator,
            ringerExpand = p[K.RINGER_EXP] ?: d.ringerExpand,
            ringerWidthPct = p[K.RINGER_W] ?: d.ringerWidthPct, ringerHeightPct = p[K.RINGER_H] ?: d.ringerHeightPct,
            volumeIndicator = p[K.VOL_IND] ?: d.volumeIndicator,
            hideInApps = p[K.HIDE_APPS] ?: d.hideInApps,
            hiddenAppPkgs = p[K.HIDE_PKGS] ?: d.hiddenAppPkgs,
            uiHaptics = p[K.UI_HAPTIC] ?: d.uiHaptics,
            stackExpand = p[K.STACK_EXP] ?: d.stackExpand,
            iphoneMode = p[K.IP_ON] ?: d.iphoneMode,
            iphoneCircle = p[K.IP_CIRCLE] ?: d.iphoneCircle,
            iphoneEvents = p[K.IP_EV] ?: d.iphoneEvents,
            iphoneCircleSide = (p[K.IP_SIDE] ?: d.iphoneCircleSide).coerceIn(0, 1),
            iphonePillWDp = p[K.IP_PW] ?: d.iphonePillWDp,
            iphonePillHDp = p[K.IP_PH] ?: d.iphonePillHDp,
            iphoneCircleDp = p[K.IP_CD] ?: d.iphoneCircleDp,
            iphoneGapDp = p[K.IP_GAP] ?: d.iphoneGapDp,
            iphoneCenterGroup = p[K.IP_CTR] ?: d.iphoneCenterGroup,
            iphoneOffsetXDp = p[K.IP_OX] ?: d.iphoneOffsetXDp,
            iphoneOffsetYDp = p[K.IP_OY] ?: d.iphoneOffsetYDp,
            translateEnabled = p[K.TRN_ON] ?: d.translateEnabled,
            translateTrigger = p[K.TRN_TRIG] ?: d.translateTrigger,
            translateSrc = p[K.TRN_SRC] ?: d.translateSrc,
            translateTgt = p[K.TRN_TGT] ?: d.translateTgt,
            colorEditorEnabled = p[K.CE_ON] ?: d.colorEditorEnabled,
            colorOverrides = p[K.CE_MAP] ?: d.colorOverrides,
            more = MoreSettings(
                designEnabled = p[K.DS_ON] ?: d.more.designEnabled,
                designStyle = (p[K.DS_STYLE] ?: d.more.designStyle).coerceIn(0, DESIGN_COUNT - 1),
                designPhotoUri = p[K.DS_PHOTO] ?: d.more.designPhotoUri,
                designAlways = p[K.DS_ALW] ?: d.more.designAlways,
                designPhotoExpand = p[K.DS_EXP] ?: d.more.designPhotoExpand,
                designPhotoTrigger = (p[K.DS_TRIG] ?: d.more.designPhotoTrigger).coerceIn(0, 1),
                designPhotoWDp = (p[K.DS_W] ?: d.more.designPhotoWDp).coerceIn(160f, 420f),
                designPhotoHDp = (p[K.DS_H] ?: d.more.designPhotoHDp).coerceIn(60f, 420f),
                designPhotoSec = (p[K.DS_SEC] ?: d.more.designPhotoSec).coerceIn(1f, 30f),
                musicMid = p[K.MID_ON] ?: d.more.musicMid,
                musicMidWDp = (p[K.MID_W] ?: d.more.musicMidWDp).coerceIn(260f, 400f),
                musicMidHDp = (p[K.MID_H] ?: d.more.musicMidHDp).coerceIn(140f, 220f),
                lowBattIndicator = p[K.LB_ON] ?: d.more.lowBattIndicator,
                lowBattExpand = p[K.LB_EXP] ?: d.more.lowBattExpand,
                netIndicator = p[K.NET_ON] ?: d.more.netIndicator,
                airplaneIndicator = p[K.AIR_ON] ?: d.more.airplaneIndicator,
                ringVolIndicator = p[K.RVOL_ON] ?: d.more.ringVolIndicator,
                favStar = p[K.FAV_STAR] ?: d.more.favStar,
                lyricsSide = p[K.LY_SIDE] ?: d.more.lyricsSide,
                portalEnabled = p[K.PT_ON] ?: d.more.portalEnabled,
                portalScene = (p[K.PT_SCENE] ?: d.more.portalScene).coerceIn(0, PORTAL_NAMES.size - 1),
                portalDepth = (p[K.PT_DEPTH] ?: d.more.portalDepth).coerceIn(0.2f, 2f),
                thumbEnabled = p[K.TH_ON] ?: d.more.thumbEnabled,
                camoEnabled = p[K.CAMO_ON] ?: d.more.camoEnabled,
                camoSkipPkgs = p[K.CAMO_SKIP] ?: d.more.camoSkipPkgs,
                notifTranslate = p[K.NT_TR] ?: d.more.notifTranslate,
                notifTranslateTgt = p[K.NT_TR_TGT] ?: d.more.notifTranslateTgt,
                contrastEnabled = p[K.CONTRAST] ?: d.more.contrastEnabled,
                musicMood = p[K.MOOD] ?: d.more.musicMood,
                drawerEnabled = p[K.DRW_ON] ?: d.more.drawerEnabled,
                drawerTrigger = (p[K.DRW_TRIG] ?: d.more.drawerTrigger).coerceIn(0, 1),
                trashIsland = p[K.TRASH] ?: d.more.trashIsland,
                appLockEnabled = p[K.AL_ON] ?: d.more.appLockEnabled,
                appLockPkgs = p[K.AL_PKGS] ?: d.more.appLockPkgs,
                notifFlow = p[K.NFLOW] ?: d.more.notifFlow,
                thumbFan = p[K.TFAN] ?: d.more.thumbFan,
                islandShape = (p[K.ISHAPE] ?: d.more.islandShape).coerceIn(0, 4),
                coverPortal = p[K.CPORTAL] ?: d.more.coverPortal,
                chargeRing = p[K.CRING] ?: d.more.chargeRing,
                notifBirds = p[K.NBIRD] ?: d.more.notifBirds,
                headphoneCard = p[K.HPCARD] ?: d.more.headphoneCard,
                headphoneBattery = p[K.HPBATT] ?: d.more.headphoneBattery,
                navIsland = p[K.NAVI] ?: d.more.navIsland,
                focusIndicator = p[K.FOCUSI] ?: d.more.focusIndicator,
                hotspotIndicator = p[K.HOTSPOT] ?: d.more.hotspotIndicator,
                deliveryIsland = p[K.DELIV] ?: d.more.deliveryIsland,
                fxTilt = p[K.C_TILT] ?: d.more.fxTilt,
                moodExtended = p[K.C_MOODX] ?: d.more.moodExtended,
                moodLyrics = p[K.C_MOODL] ?: d.more.moodLyrics,
                coverDissolve = p[K.C_DISS] ?: d.more.coverDissolve,
                calendarFx = p[K.C_CAL] ?: d.more.calendarFx,
                chargeArcs = p[K.C_ARCS] ?: d.more.chargeArcs,
                lowBattSparks = p[K.C_LOWB] ?: d.more.lowBattSparks,
                notifShatter = p[K.C_SHAT] ?: d.more.notifShatter,
                progressIsland = p[K.C_PROG] ?: d.more.progressIsland,
                scoreIsland = p[K.C_SCORE] ?: d.more.scoreIsland,
                chargeEta = p[K.C_ETA] ?: d.more.chargeEta,
                flipSilence = p[K.C_FLIP] ?: d.more.flipSilence,
            ),
        ).also { Pal.sync(it.colorEditorEnabled, it.colorOverrides) }   // özel renkleri tüm arayüze (ada, bildirim, ayarlar) yay
    }.distinctUntilChanged()

    suspend fun save(s: IslandSettings) {
        store.edit {
            it[K.CW] = s.compactWidthDp; it[K.CH] = s.compactHeightDp
            it[K.W] = s.expandedWidthDp; it[K.H] = s.expandedHeightDp
            it[K.OX] = s.offsetXDp; it[K.OY] = s.offsetYDp
            it[K.COLOR] = s.accentArgb; it[K.DYN] = s.dynamicColor; it[K.EQ_DYN] = s.eqDynamicColor; it[K.EQ_COLOR] = s.eqColorArgb
            it[K.ART_BG] = s.artBgEnabled; it[K.ART_BG_C] = s.artBgCompact
            it[K.CB_ON] = s.customBgEnabled; it[K.CB_SEP] = s.customBgSeparate; it[K.CB_DIM] = s.customBgDim
            it[K.CB_U_BOTH] = s.customBgBothUri; it[K.CB_U_C] = s.customBgCompactUri; it[K.CB_U_E] = s.customBgExpandedUri
            it[K.FX_W] = s.fxWeather; it[K.FX_I] = s.fxIntensity; it[K.FX_L] = s.fxLightning; it[K.FX_LS] = s.fxLightningSec
            it[K.FX_FS] = s.fxFreezeSpeed; it[K.FX_TT] = s.fxThawTaps; it[K.FX_MS] = s.fxMeltSpeed; it[K.FX_B] = s.fxBreak; it[K.FX_RS] = s.fxRestoreSec
            it[K.CB_V] = s.customBgVideoUri; it[K.MB_FX] = s.musicBottomFx; it[K.NB_FX] = s.notifBottomFx
            it[K.CA_ON] = s.customArtEnabled; it[K.CA_U] = s.customArtUri; it[K.CA_V] = s.customArtVideoUri
            it[K.GLASS] = s.glassEnabled; it[K.ALPHA] = s.glassAlpha; it[K.BLUR] = s.blurRadiusPx
            it[K.LQ_ON] = s.liquidEnabled; it[K.LQ_R] = s.liquidRefraction
            it[K.LQ_D] = s.liquidDispersion; it[K.LQ_S] = s.liquidSpecular
            it[K.GLOW] = s.glowEnabled; it[K.GLOW_I] = s.glowIntensity
            it[K.PG_ON] = s.progressGlowEnabled; it[K.PG_ART] = s.progressGlowArtColor
            it[K.PG_COLOR] = s.progressGlowColorArgb; it[K.PG_I] = s.progressGlowIntensity; it[K.PG_TIP] = s.progressGlowTipOnly
            it[K.PG_IN] = s.progressGlowInside
            it[K.LIKE_ART] = s.likeArtColor; it[K.LIKE_C] = s.likeColorArgb
            it[K.BORDER] = s.borderEnabled; it[K.BORDER_C] = s.borderColorArgb; it[K.BORDER_ART] = s.borderArtColor; it[K.ISL_C] = s.islandColorArgb; it[K.BORDER_W] = s.borderWidthDp
            it[K.ANIM] = s.animEnabled; it[K.ANIM_S] = s.animStyle; it[K.MX_BLUR] = s.musicBlurAnim; it[K.MX_FLIP] = s.musicArtFlip; it[K.SW_TRK] = s.swipeTrackChange

            it[K.IDLE] = s.showIdle
            it[K.PILL_CALL] = s.pillCallStyle; it[K.PILL_MUSIC] = s.pillMusicStyle
            it[K.PILL_FIT] = s.pillFitIsland; it[K.PILL_MW] = s.pillMusicWDp; it[K.PILL_MH] = s.pillMusicHDp
            it[K.PILL_ART] = s.pillWaveArtColor; it[K.PILL_WL] = s.pillWaveLeftArgb; it[K.PILL_WM] = s.pillWaveMidArgb; it[K.PILL_WR] = s.pillWaveRightArgb
            it[K.FAV_ANIM] = s.favoriteLaunchAnim; it[K.FAV_SPD] = s.favoriteLaunchSpeed
            it[K.NT_BANNER] = s.notifBanner
            it[K.NT_INLINE] = s.notifInline
            it[K.NT_BMENU] = s.bannerMenuEnabled; it[K.NT_BMENU_TAP] = s.bannerMenuTap; it[K.NT_BICON] = s.bannerIconOpens
            it[K.NT_OPEN] = s.notifTapOpens; it[K.NT_OPEN_LONG] = s.notifOpenLong; it[K.NT_OPEN_DN] = s.notifOpenSwipeDown
            it[K.NT_COL_TAP] = s.notifCollapseTap; it[K.NT_COL_LONG] = s.notifCollapseLong; it[K.NT_COL_UP] = s.notifCollapseSwipeUp
            it[K.NT_DEL_SIDE] = s.notifDeleteSwipeSide; it[K.NT_DEL_UP] = s.notifDeleteSwipeUp; it[K.SIDE_PROMOTE] = s.sideTapPromote
             it[K.NT_ANIM] = s.notifLaunchAnim; it[K.MX_ANIM] = s.musicExitAnim; it[K.MX_SPD] = s.musicExitSpeed; it[K.MX_SW] = s.musicExitSwallow; it[K.MX_STR] = s.musicExitStretch; it[K.LA_SPD] = s.launchSpeed; it[K.MO_ANIM] = s.musicOpenAnim; it[K.LA_CL] = s.launchClassic; it[K.LA_AT] = s.launchAtomic; it[K.FLA_CL] = s.favLaunchClassic; it[K.FLA_AT] = s.favLaunchAtomic;
            it[K.OP_EA] = s.openExpArt; it[K.OP_ET] = s.openExpTitle; it[K.OP_EX] = s.openExpAny
            it[K.OP_CA] = s.openCompArt; it[K.OP_CL] = s.openCompLong; it[K.OP_CT] = s.openCompTap
            it[K.EXP_LONG] = s.expandOnLong; it[K.SW_DN] = s.swipeDownExpand; it[K.SW_UP] = s.swipeUpCollapse; it[K.LP_MS] = s.longPressMs
            it[K.MX_CLASSIC] = s.musicExitClassic; it[K.MX_ATOMIC] = s.musicExitAtomic; it[K.MX_ATOMIC_FULL] = s.musicExitAtomicFull; it[K.MX_FLUID] = s.musicFluidAnim;
            it[K.PL_PHY] = s.playPhysics; it[K.PL_PHY_R] = s.playPhysicsRangeDp; it[K.PL_JELLY] = s.playJelly; it[K.PL_PET] = s.playPet
            it[K.PL_MAG] = s.playMagnet; it[K.PL_MAG_B] = s.playMagnetBuds; it[K.PL_PUFF] = s.playPuff; it[K.PL_PUFF_S] = s.playPuffStrength
            it[K.PL_FACE] = s.playFace; it[K.PL_RIP] = s.playRipple; it[K.PL_RIP_T] = s.playRippleTap; it[K.PL_POC] = s.playPocket; it[K.PL_POC_S] = s.playPocketIdleSec;
            it[K.PL_LIQ] = s.playLiquid; it[K.PL_EAT] = s.playEater; it[K.PL_EAT_F] = s.playEaterFull; it[K.PL_PULL] = s.playPull
            it[K.PL_DAY] = s.playDaylight; it[K.PL_DAY_A] = s.playDaylightAmt; it[K.PL_GAME] = s.playGame;
            it[K.PL_FLING] = s.playFling; it[K.PL_TALK] = s.playPetTalk; it[K.PL_PLANT] = s.playPlant; it[K.PL_SEASON] = s.playSeason; it[K.PL_SEASON_M] = s.playSeasonMode
            it[K.PL_RHY] = s.playRhythm; it[K.PL_RHYS] = s.playRhythms; it[K.PL_PARTY] = s.playParty; it[K.PL_BD] = s.playBirthDay; it[K.PL_BM] = s.playBirthMonth; it[K.PL_DOODLE] = s.playDoodle;
            it[K.PL_VIS] = s.playVisitors; it[K.PL_DICE] = s.playDice; it[K.PL_DICE_M] = s.playDiceMode; it[K.PL_BREATH] = s.playBreath; it[K.PL_BREATH_P] = s.playBreathPattern
            it[K.PL_BREATH_M] = s.playBreathMin; it[K.PL_NIGHT] = s.playNightLight; it[K.PL_NIGHT_A] = s.playNightAmt; it[K.PL_SHADOW] = s.playShadow; it[K.HIDE_MUSIC] = s.hideInMusicApp
            it[K.HIDE_SB] = s.hideStatusBar
            it[K.INV_ISLAND] = s.invertedIsland
            it[K.INV_C] = s.invCustom; it[K.INV_W] = s.invWidthDp; it[K.INV_H] = s.invHeightDp
            it[K.INV_X] = s.invOffsetXDp; it[K.INV_Y] = s.invOffsetYDp
            it[K.LAND_ON] = s.landscapeMode; it[K.LAND_BELOW] = s.landscapeBubbleBelow; it[K.LAND_IN_ISLAND] = s.landscapeEventsInIsland
            it[K.NEON_ST] = s.neonStyle; it[K.NEON_C1] = s.neonColor1Argb; it[K.NEON_C2] = s.neonColor2Argb
            it[K.NEON_W] = s.neonWidthDp; it[K.NEON_G] = s.neonGlow; it[K.NEON_SP] = s.neonSpeed
            it[K.DEC_TOP] = s.decorTop; it[K.DEC_HANG] = s.decorHang; it[K.PET] = s.islandPet
            it[K.DRIP] = s.dripEnabled; it[K.DRIP_S] = s.dripEverySec; it[K.EDGE_ALW] = s.edgeGlowAlways
            it[K.FAV_ON] = s.favoritesEnabled; it[K.FAV_TRIG] = s.favoritesTrigger; it[K.FAV_PKGS] = s.favoritePkgs
            it[K.REC_ON] = s.screenRecEnabled; it[K.REC_WIDE] = s.screenRecWide
            it[K.AS_ON] = s.assistantEnabled; it[K.AS_W] = s.assistantWidthDp; it[K.AS_H] = s.assistantHeightDp
            it[K.AS_FULL] = s.assistantFullWidth; it[K.AS_STYLE] = s.assistantStyle
            it[K.RB_ON] = s.rainbowEnabled; it[K.RB_A] = s.rainbowAlpha
            it[K.LY_ON] = s.lyricsEnabled; it[K.LY_PANEL] = s.lyricsInPanel
            it[K.LY_COMPACT] = s.lyricsCompact; it[K.LY_OUT] = s.lyricsOutside
            it[K.LY_P_SIZE] = s.lyricsPanelSizeSp; it[K.LY_P_LINES] = s.lyricsPanelLines
            it[K.LY_C_SIZE] = s.lyricsCompactSizeSp; it[K.LY_C_W] = s.lyricsCompactWidthDp
            it[K.LY_O_SIZE] = s.lyricsOutsideSizeSp; it[K.LY_O_LINES] = s.lyricsOutsideLines
            it[K.LY_O_W] = s.lyricsOutsideWidthDp; it[K.LY_O_X] = s.lyricsOutsideXDp; it[K.LY_O_Y] = s.lyricsOutsideYDp
            it[K.LY_COLOR] = s.lyricsColorArgb; it[K.LY_DIM] = s.lyricsDimAlpha; it[K.LY_DYN] = s.lyricsDynamicColor
            it[K.LY_FILL] = s.lyricsFillEnabled; it[K.LY_OFF] = s.lyricsOffsetMs
            it[K.LY_GLOW] = s.lyricsGlowEnabled; it[K.LY_GLOW_L] = s.lyricsGlowLevel
            it[K.LY_ANIM] = s.lyricsAnimEnabled; it[K.LY_ANIM_S] = s.lyricsAnimStyle; it[K.LY_ANIM_MS] = s.lyricsAnimMs
            it[K.DOT_ON] = s.dotMode; it[K.DOT_MID] = s.dotMidDp
            it[K.DOT_L] = s.dotLeftDp; it[K.DOT_R] = s.dotRightDp; it[K.DOT_GAP] = s.dotGapDp
            it[K.DOT_OX] = s.dotOffsetXDp; it[K.DOT_OY] = s.dotOffsetYDp
            it[K.NT_EXP_S] = s.notifExpandedSec; it[K.NT_CMP_S] = s.notifCompactSec
            it[K.MU_EXP_S] = s.musicExpandedSec
            it[K.LD_ON] = s.lockDotEnabled; it[K.LD_C] = s.lockDotColorArgb; it[K.LD_S] = s.lockDotSizeDp; it[K.LD_G] = s.lockDotGapDp; it[K.LD_Y] = s.lockDotOffsetYDp
            it[K.LD_GL] = s.lockDotGlow; it[K.LD_GD] = s.lockDotGlowDp; it[K.LD_GA] = s.lockDotGlowAlpha
            it[K.LD_B] = s.lockDotBlink; it[K.LD_BM] = s.lockDotBlinkMs; it[K.LD_BA] = s.lockDotMinAlpha; it[K.LD_BS] = s.lockDotBlinkStyle
            it[K.TORCH_ON] = s.torchIndicator
            it[K.FID_ON] = s.faceIdEnabled; it[K.FID_C] = s.faceIdColorArgb; it[K.FID_S] = s.faceIdSizeDp
            it[K.FID_Y] = s.faceIdOffsetYDp; it[K.FID_SP] = s.faceIdSpeed
            it[K.SFX_ON] = s.sfxEnabled
            it[K.SFX_TAP] = s.sfxTap.encode(); it[K.SFX_CHG] = s.sfxCharge.encode(); it[K.SFX_UNP] = s.sfxUnplug.encode()
            it[K.SFX_NTF] = s.sfxNotif.encode(); it[K.SFX_SIL] = s.sfxSilent.encode(); it[K.SFX_CALL] = s.sfxCall.encode()
            it[K.DOT_MERGE] = s.dotMerge; it[K.DOT_MERGE_KEEP] = s.dotMergeKeep
            it[K.RINGER_IND] = s.ringerIndicator; it[K.RINGER_EXP] = s.ringerExpand; it[K.RINGER_W] = s.ringerWidthPct; it[K.RINGER_H] = s.ringerHeightPct; it[K.VOL_IND] = s.volumeIndicator
            it[K.PRIV_IND] = s.privacyIndicator; it[K.HIDE_APPS] = s.hideInApps; it[K.HIDE_PKGS] = s.hiddenAppPkgs; it[K.UI_HAPTIC] = s.uiHaptics
            it[K.DOT_N] = s.dotCount; it[K.DOT_L2] = s.dotLeft2Dp; it[K.DOT_R2] = s.dotRight2Dp
            it[K.STACK_EXP] = s.stackExpand
            it[K.IP_ON] = s.iphoneMode; it[K.IP_CIRCLE] = s.iphoneCircle; it[K.IP_EV] = s.iphoneEvents; it[K.IP_SIDE] = s.iphoneCircleSide
            it[K.IP_PW] = s.iphonePillWDp; it[K.IP_PH] = s.iphonePillHDp; it[K.IP_CD] = s.iphoneCircleDp; it[K.IP_GAP] = s.iphoneGapDp
            it[K.IP_CTR] = s.iphoneCenterGroup; it[K.IP_OX] = s.iphoneOffsetXDp; it[K.IP_OY] = s.iphoneOffsetYDp
            it[K.TRN_ON] = s.translateEnabled; it[K.TRN_TRIG] = s.translateTrigger
            it[K.TRN_SRC] = s.translateSrc; it[K.TRN_TGT] = s.translateTgt
            it[K.CE_ON] = s.colorEditorEnabled; it[K.CE_MAP] = s.colorOverrides
            val m = s.more
            it[K.DS_ON] = m.designEnabled; it[K.DS_STYLE] = m.designStyle; it[K.DS_PHOTO] = m.designPhotoUri
            it[K.DS_ALW] = m.designAlways; it[K.DS_EXP] = m.designPhotoExpand; it[K.DS_TRIG] = m.designPhotoTrigger
            it[K.DS_W] = m.designPhotoWDp; it[K.DS_H] = m.designPhotoHDp; it[K.DS_SEC] = m.designPhotoSec
            it[K.MID_ON] = m.musicMid; it[K.MID_W] = m.musicMidWDp; it[K.MID_H] = m.musicMidHDp
            it[K.LB_ON] = m.lowBattIndicator; it[K.LB_EXP] = m.lowBattExpand
            it[K.NET_ON] = m.netIndicator; it[K.AIR_ON] = m.airplaneIndicator; it[K.RVOL_ON] = m.ringVolIndicator
            it[K.FAV_STAR] = m.favStar; it[K.LY_SIDE] = m.lyricsSide
            it[K.PT_ON] = m.portalEnabled; it[K.PT_SCENE] = m.portalScene; it[K.PT_DEPTH] = m.portalDepth
            it[K.TH_ON] = m.thumbEnabled; it[K.CAMO_ON] = m.camoEnabled; it[K.CAMO_SKIP] = m.camoSkipPkgs
            it[K.NT_TR] = m.notifTranslate; it[K.NT_TR_TGT] = m.notifTranslateTgt; it[K.CONTRAST] = m.contrastEnabled
            it[K.MOOD] = m.musicMood; it[K.DRW_ON] = m.drawerEnabled; it[K.DRW_TRIG] = m.drawerTrigger
            it[K.TRASH] = m.trashIsland; it[K.AL_ON] = m.appLockEnabled; it[K.AL_PKGS] = m.appLockPkgs
            it[K.NFLOW] = m.notifFlow; it[K.TFAN] = m.thumbFan; it[K.ISHAPE] = m.islandShape
            it[K.CPORTAL] = m.coverPortal; it[K.CRING] = m.chargeRing; it[K.NBIRD] = m.notifBirds
            it[K.HPCARD] = m.headphoneCard; it[K.HPBATT] = m.headphoneBattery; it[K.NAVI] = m.navIsland
            it[K.FOCUSI] = m.focusIndicator; it[K.HOTSPOT] = m.hotspotIndicator; it[K.DELIV] = m.deliveryIsland
            it[K.C_TILT] = m.fxTilt; it[K.C_MOODX] = m.moodExtended; it[K.C_MOODL] = m.moodLyrics
            it[K.C_DISS] = m.coverDissolve; it[K.C_CAL] = m.calendarFx; it[K.C_ARCS] = m.chargeArcs
            it[K.C_LOWB] = m.lowBattSparks; it[K.C_SHAT] = m.notifShatter; it[K.C_PROG] = m.progressIsland
            it[K.C_SCORE] = m.scoreIsland
            it[K.C_ETA] = m.chargeEta; it[K.C_FLIP] = m.flipSilence
        }
    }

    /** Kayıtlı ayarların yalnızca [f] ile değiştirilen kısmını yazar (Ana Sayfa'daki kartlar için). */
    suspend fun update(f: (IslandSettings) -> IslandSettings) {
        save(f(flow.first()))
    }

    // ---------------------------------------------------------------- Yedekleme
    /** Galeri adresleri başka cihazda / yeniden kurulumda geçerli olmadığı için yedeğe girmez. */
    private val exportSkip = setOf("cb_u_both", "cb_u_c", "cb_u_e", "ca_u", "sfx_tap", "sfx_chg", "sfx_unp", "sfx_ntf", "sfx_sil", "sfx_call", "ds_photo")

    /** Tüm kayıtlı ayarları JSON metni olarak verir. */
    suspend fun exportJson(): String {
        val o = org.json.JSONObject()
        store.data.first().asMap().forEach { (k, v) ->
            if (k.name in exportSkip) return@forEach
            val e = org.json.JSONObject()
            when (v) {
                is Boolean -> e.put("t", "b").put("v", v)
                is Int -> e.put("t", "i").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is String -> e.put("t", "s").put("v", v)
                else -> return@forEach
            }
            o.put(k.name, e)
        }
        return org.json.JSONObject().put("app", "dynamicisland").put("version", 1).put("settings", o).toString(2)
    }

    /** Yedek metnini ayarlara yazar. Geçersiz dosyada false döner ve hiçbir şey değişmez. */
    suspend fun importJson(text: String): Boolean {
        val root = runCatching { org.json.JSONObject(text) }.getOrNull() ?: return false
        if (root.optString("app") != "dynamicisland") return false
        val st = root.optJSONObject("settings") ?: return false
        store.edit { p ->
            val names = st.keys()
            while (names.hasNext()) {
                val name = names.next()
                if (name in exportSkip) continue
                val e = st.optJSONObject(name) ?: continue
                runCatching {
                    when (e.getString("t")) {
                        "b" -> p[booleanPreferencesKey(name)] = e.getBoolean("v")
                        "i" -> p[intPreferencesKey(name)] = e.getInt("v")
                        "l" -> p[longPreferencesKey(name)] = e.getLong("v")
                        "f" -> p[floatPreferencesKey(name)] = e.getDouble("v").toFloat()
                        "s" -> p[stringPreferencesKey(name)] = e.getString("v")
                    }
                    Unit
                }
            }
        }
        return true
    }
}

/** Ekran kaydı önizlemesi için (isteğe bağlı) galeri video okuma izni. */
fun recPreviewPermission(): String =
    if (android.os.Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_VIDEO
    else android.Manifest.permission.READ_EXTERNAL_STORAGE
