package com.example.dynamicisland

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.math.ceil

const val GLOW_PAD_DP = 14f     // parıltı için pencere kenar boşluğu
const val BOUNCE_PAD_DP = 16f   // "Zıplamalı" animasyonun taşma payı
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
    val offsetYDp: Float = 0f,
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
    val customArtEnabled: Boolean = false,
    val customArtUri: String = "",
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

    // Görünürlük
    val showIdle: Boolean = true,            // müzik/bildirim yokken de ada görünsün

    // Favori uygulamalar (boştayken ada genişleyip seçilen uygulamaları gösterir)
    val favoritesEnabled: Boolean = false,
    val favoritesTrigger: Int = 0,           // 0 = dokununca, 1 = basılı tutunca
    val favoritePkgs: String = "",           // paket adları, virgülle ayrılmış (sıra korunur)
    val favoriteLaunchSpeed: Float = 1f,     // açılış animasyonu hızı (1 = normal, 2 = iki kat hızlı)
    val notifTapOpens: Boolean = true,       // adadaki bildirime dokununca uygulama açılsın
    val hideStatusBar: Boolean = false,      // şarj / zil modu gibi durum görünümleri açıkken durum çubuğunu gizle
    val notifLaunchAnim: Boolean = true,     // ... ve açılırken adadan siyah ekran animasyonu oynasın
    val favoriteLaunchAnim: Boolean = true,  // uygulamaya dokununca adadan siyah ekran çıkıp ekranı kaplasın

    // Google Asistan / Gemini animasyonu
    val assistantEnabled: Boolean = true,
    val assistantWidthDp: Float = 150f,
    val assistantHeightDp: Float = 112f,
    val musicExitSwallow: Boolean = true,     // panel adaya varınca ada büyüyüp onu yutar
    val musicExitSpeed: Float = 1f,           // müzikten çıkış animasyonu hızı (1 = normal)
    val hideInMusicApp: Boolean = false,      // müzik uygulamasındayken adada çalan müzik görünmez
    val musicExitAnim: Boolean = true,        // müzik uygulamasından çıkınca panel adaya küçülür, ada şarkıyı gösterir
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
) {
    /** Pencerenin ada etrafında bırakacağı pay (parıltı / zıplama taşması için). */
    fun windowPadDp(): Float = maxOf(
        if (glowEnabled) GLOW_PAD_DP else 0f,
        if (animEnabled && animStyle == 3) BOUNCE_PAD_DP else 0f,
    )

    /** Seçili favori uygulamaların paket adları (en fazla [MAX_FAV], tekrarsız). */
    fun favoritePkgList(): List<String> = favoritePkgs.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_FAV)

    /** Özellik gerçekten çalışabilir mi: açık, ada boştayken görünür ve en az bir uygulama seçili. */
    fun favoritesReady(): Boolean = favoritesEnabled && showIdle && favoritePkgList().isNotEmpty()

    /** Favori panelinin yüksekliği: kamera bölgesi + uygulama satırları. */
    fun favoritesHeightDp(count: Int): Float {
        val rows = maxOf(1, ceil(count / FAV_COLUMNS.toFloat()).toInt())
        return compactHeightDp + 6f + rows * FAV_ROW_DP + 8f
    }

    /** Geçiş animasyonunun yaklaşık bitiş süresi (pencere boyutunu ona göre değiştiririz). */
    fun settleMs(): Long = if (!animEnabled) 60L else when (animStyle) {
        0 -> 520L; 1 -> 560L; 2 -> 300L; 3 -> 750L; else -> 650L
    }

    /** Genişletilmiş panelde şarkı sözü satırına ayrılan sabit yükseklik. */
    fun lyricsBlockDp(): Float = ceil(lyricsPanelSizeSp * 1.3f * lyricsPanelLines) + 10f

    /** Müzik panelinin temel yüksekliğine eklenen miktar (sözler açıksa söz alanı dahil). */
    fun musicExtraDp(): Float = MUSIC_EXTRA_DP + if (lyricsEnabled && lyricsInPanel) lyricsBlockDp() else 0f

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
        )
    }
}

private val Context.islandStore by preferencesDataStore(name = "island_settings")

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
        val CA_ON = booleanPreferencesKey("ca_on"); val CA_U = stringPreferencesKey("ca_u")
        val GLASS = booleanPreferencesKey("glass"); val ALPHA = floatPreferencesKey("alpha"); val BLUR = intPreferencesKey("blur")
        val LQ_ON = booleanPreferencesKey("lq_on"); val LQ_R = floatPreferencesKey("lq_r")
        val LQ_D = floatPreferencesKey("lq_d"); val LQ_S = floatPreferencesKey("lq_s")
        val GLOW = booleanPreferencesKey("glow"); val GLOW_I = floatPreferencesKey("glowI")
        val PG_ON = booleanPreferencesKey("pg_on"); val PG_ART = booleanPreferencesKey("pg_art")
        val PG_COLOR = intPreferencesKey("pg_color"); val PG_I = floatPreferencesKey("pg_i"); val PG_TIP = booleanPreferencesKey("pg_tip"); val PG_IN = booleanPreferencesKey("pg_in")
        val LIKE_ART = booleanPreferencesKey("like_art"); val LIKE_C = intPreferencesKey("like_c")
        val BORDER = booleanPreferencesKey("border"); val BORDER_C = intPreferencesKey("borderC"); val BORDER_ART = booleanPreferencesKey("border_art"); val ISL_C = intPreferencesKey("island_c"); val BORDER_W = floatPreferencesKey("borderW")
        val ANIM = booleanPreferencesKey("animOn"); val ANIM_S = intPreferencesKey("animStyle")

        val IDLE = booleanPreferencesKey("idle")
        val FAV_ANIM = booleanPreferencesKey("fav_anim"); val FAV_SPD = floatPreferencesKey("fav_spd")
        val NT_OPEN = booleanPreferencesKey("nt_open"); val NT_ANIM = booleanPreferencesKey("nt_anim"); val MX_ANIM = booleanPreferencesKey("mx_anim"); val MX_SPD = floatPreferencesKey("mx_spd"); val MX_SW = booleanPreferencesKey("mx_sw"); val HIDE_MUSIC = booleanPreferencesKey("hide_music_in_app")
        val HIDE_SB = booleanPreferencesKey("hide_sb")
        val FAV_ON = booleanPreferencesKey("fav_on"); val FAV_TRIG = intPreferencesKey("fav_trig"); val FAV_PKGS = stringPreferencesKey("fav_pkgs")
        val AS_ON = booleanPreferencesKey("as_on"); val AS_W = floatPreferencesKey("as_w"); val AS_H = floatPreferencesKey("as_h"); val AS_FULL = booleanPreferencesKey("as_full")
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
            customArtEnabled = p[K.CA_ON] ?: d.customArtEnabled,
            customArtUri = p[K.CA_U] ?: d.customArtUri,
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
            showIdle = p[K.IDLE] ?: d.showIdle,
            favoritesEnabled = p[K.FAV_ON] ?: d.favoritesEnabled,
            favoritesTrigger = p[K.FAV_TRIG] ?: d.favoritesTrigger,
            favoritePkgs = p[K.FAV_PKGS] ?: d.favoritePkgs,
            favoriteLaunchAnim = p[K.FAV_ANIM] ?: d.favoriteLaunchAnim,
            favoriteLaunchSpeed = p[K.FAV_SPD] ?: d.favoriteLaunchSpeed,
            notifTapOpens = p[K.NT_OPEN] ?: d.notifTapOpens,
            hideStatusBar = p[K.HIDE_SB] ?: d.hideStatusBar,
            notifLaunchAnim = p[K.NT_ANIM] ?: d.notifLaunchAnim,
            musicExitAnim = p[K.MX_ANIM] ?: d.musicExitAnim,
            musicExitSpeed = p[K.MX_SPD] ?: d.musicExitSpeed,
            musicExitSwallow = p[K.MX_SW] ?: d.musicExitSwallow,
            hideInMusicApp = p[K.HIDE_MUSIC] ?: d.hideInMusicApp,
            assistantEnabled = p[K.AS_ON] ?: d.assistantEnabled,
            assistantWidthDp = p[K.AS_W] ?: d.assistantWidthDp,
            assistantHeightDp = p[K.AS_H] ?: d.assistantHeightDp,
            assistantFullWidth = p[K.AS_FULL] ?: d.assistantFullWidth,
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
        )
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
            it[K.CA_ON] = s.customArtEnabled; it[K.CA_U] = s.customArtUri
            it[K.GLASS] = s.glassEnabled; it[K.ALPHA] = s.glassAlpha; it[K.BLUR] = s.blurRadiusPx
            it[K.LQ_ON] = s.liquidEnabled; it[K.LQ_R] = s.liquidRefraction
            it[K.LQ_D] = s.liquidDispersion; it[K.LQ_S] = s.liquidSpecular
            it[K.GLOW] = s.glowEnabled; it[K.GLOW_I] = s.glowIntensity
            it[K.PG_ON] = s.progressGlowEnabled; it[K.PG_ART] = s.progressGlowArtColor
            it[K.PG_COLOR] = s.progressGlowColorArgb; it[K.PG_I] = s.progressGlowIntensity; it[K.PG_TIP] = s.progressGlowTipOnly
            it[K.PG_IN] = s.progressGlowInside
            it[K.LIKE_ART] = s.likeArtColor; it[K.LIKE_C] = s.likeColorArgb
            it[K.BORDER] = s.borderEnabled; it[K.BORDER_C] = s.borderColorArgb; it[K.BORDER_ART] = s.borderArtColor; it[K.ISL_C] = s.islandColorArgb; it[K.BORDER_W] = s.borderWidthDp
            it[K.ANIM] = s.animEnabled; it[K.ANIM_S] = s.animStyle

            it[K.IDLE] = s.showIdle
            it[K.FAV_ANIM] = s.favoriteLaunchAnim; it[K.FAV_SPD] = s.favoriteLaunchSpeed
            it[K.NT_OPEN] = s.notifTapOpens; it[K.NT_ANIM] = s.notifLaunchAnim; it[K.MX_ANIM] = s.musicExitAnim; it[K.MX_SPD] = s.musicExitSpeed; it[K.MX_SW] = s.musicExitSwallow; it[K.HIDE_MUSIC] = s.hideInMusicApp
            it[K.HIDE_SB] = s.hideStatusBar
            it[K.FAV_ON] = s.favoritesEnabled; it[K.FAV_TRIG] = s.favoritesTrigger; it[K.FAV_PKGS] = s.favoritePkgs
            it[K.AS_ON] = s.assistantEnabled; it[K.AS_W] = s.assistantWidthDp; it[K.AS_H] = s.assistantHeightDp
            it[K.AS_FULL] = s.assistantFullWidth
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
        }
    }
}
