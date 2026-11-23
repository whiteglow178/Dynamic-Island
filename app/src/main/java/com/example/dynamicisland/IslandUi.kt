package com.example.dynamicisland

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.flow.first
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

const val MUSIC_EXTRA_DP = 100f

// ---------------------------------------------------------------- Müzik adası: bulanık açılış / ters yönde kapanış
/** Bulanıklığı uygular (Android 12+); desteklenmeyen cihazlarda yalnızca solma + ölçek çalışır. */
internal fun GraphicsLayerScope.setBlurPx(px: Float) {
    // Yarıçap 3 px'lik basamaklara yuvarlanır: aynı efekt art arda karelerde yeniden üretilmez (hızlı aç-kapa'da takılmayı azaltır)
    val q = Math.round(px / 3f) * 3f
    renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && q > 0.5f) BlurEffect(q, q, TileMode.Decal) else null
}

/**
 * Genişletilmiş müzik panelinin içeriği: ada büyüdükçe küçük ve bulanık halden netleşip yerine oturur.
 * [t] ada ilerlemesidir (0 = kapalı, 1 = açık). Küçülürken t azaldığı için aynı eğri tam tersi oynar.
 */
private fun GraphicsLayerScope.musicReveal(t: Float) {
    val r = ((t - 0.45f) / 0.55f).coerceIn(0f, 1f)
    alpha = (r * 1.2f).coerceAtMost(1f)
    val sc = 0.60f + 0.40f * r * r * r
    scaleX = sc; scaleY = sc
    transformOrigin = TransformOrigin(0.5f, 0.2f)
    setBlurPx((1f - r) * 32.dp.toPx())
}

/** Kapalı adadaki içerik (kapak, equalizer, sözler): ada açılırken hafifçe bulanıp küçülerek kaybolur, küçülürken geri gelir. */
private fun GraphicsLayerScope.musicCompactBlur(c: Float) {
    val k = 1f - c
    val sc = 1f - 0.25f * k
    scaleX = sc; scaleY = sc
    setBlurPx(k * 10.dp.toPx())
}

/** Aktif aramada küçültülmüş adanın genişliği (telefon simgesi + süre + ses dalgası sığsın). */
const val CALL_COMPACT_W_DP = 160f

// Hap tasarımları: KÜÇÜLTÜLMÜŞ (kapalı) adanın boyutu. Genişletilmiş panel değişmez.
const val PILL_CALL_H_DP = 68f
const val PILL_MUSIC_H_DP = 100f
const val PILL_MAX_W_DP = 344f
const val PILL_CALL_MIN_W_DP = 240f     // tasarımın okunaklı kalması için en dar tasarım genişliği (ölçekleme hesabı)
const val PILL_MUSIC_MIN_W_DP = 300f

/** Hap tasarımı açıksa ve içerik arama / müzikse kapalı adanın (genişlik, yükseklik) değeri; yoksa null. Üç nokta modunda uygulanmaz. */
fun pillCompactSizeDp(cfg: IslandSettings, content: IslandContent): Pair<Float, Float>? = when {
    cfg.dotMode -> null
    // Orta boy müzik adası: müzik çalarken ada küçülmez, ayarlanan orta boyda açık kalır ("uydur" ayarından bağımsız)
    content is IslandContent.Media && cfg.more.musicMid -> cfg.more.musicMidWDp to cfg.more.musicMidHDp
    cfg.pillFitIsland -> null     // "adanın boyutuna uydur": ada büyümez
    content is IslandContent.Call && cfg.pillCallStyle -> minOf(cfg.expandedWidthDp, PILL_MAX_W_DP) to PILL_CALL_H_DP
    content is IslandContent.Media && cfg.pillMusicStyle -> cfg.pillMusicWDp to cfg.pillMusicHDp
    else -> null
}

/** Servis penceresi için aynı hesap ([kind]: 2 = müzik, 5 / 6 = gelen / aktif arama). */
fun pillCompactSizeForKind(cfg: IslandSettings, kind: Int): Pair<Float, Float>? = when {
    cfg.dotMode -> null
    kind == 2 && cfg.more.musicMid -> cfg.more.musicMidWDp to cfg.more.musicMidHDp
    cfg.pillFitIsland -> null
    (kind == 5 || kind == 6) && cfg.pillCallStyle -> minOf(cfg.expandedWidthDp, PILL_MAX_W_DP) to PILL_CALL_H_DP
    kind == 2 && cfg.pillMusicStyle -> cfg.pillMusicWDp to cfg.pillMusicHDp
    else -> null
}

// ---------------------------------------------------------------- Albüm kapağı dönme (Y ekseninde)
/** Kapak dönme durumu: şarkı değişiminde yarım tur (180°), yön sonraki / önceki şarkıya göre değişir. */
@Stable
class ArtFlip {
    var dir by mutableIntStateOf(1)        // bir sonraki değişim için istenen yön: +1 sonraki, -1 önceki
    var flipDir by mutableIntStateOf(1)    // oynayan dönüşün yönü
    var from by mutableStateOf<ImageBitmap?>(null)   // dönüşün ilk yarısında görünen eski kapak
    var held by mutableStateOf<ImageBitmap?>(null)   // yeni şarkının kapağı yüklenene kadar eski kapak burada tutulur (kapak yok olmasın)
    var trackDeg by mutableFloatStateOf(0f)   // şarkı değişimi dönüşü: 0..180 derece (büyüklük)
    val spin = Animatable(0f)              // ada açılırken 0 -> 360, küçülürken 360 -> 0
}

private class TrackHolder { var key: String? = null; var art: ImageBitmap? = null }

/** Açıyı -90..90 arasına katlar: kart arka yüzü görününce ayna görüntüsü yerine düz görünür (kapak 90°'de değişir). */
private fun foldDeg(a: Float): Float = ((a + 90f) % 180f + 180f) % 180f - 90f

/**
 * Dönen albüm kapağı. [spin] ada geçişinin dönüşü, [flip] şarkı değişiminin dönüşüdür; ikisi toplanır.
 * Kenar-üstüne yaklaştıkça kart kararır ve hafif bulanır (perspektifli, iOS tarzı).
 */
@Composable
fun FlipAlbumArt(
    art: ImageBitmap?, size: Dp, flip0: ArtFlip?, spin: () -> Float, corner: Dp,
    allowOld: Boolean = true, modifier: Modifier = Modifier, video: String = "",
) {
    // Dağılan kapak açıkken çevirme animasyonu yerine parçacık geçişi kullanılır
    val dissolve = FxPrefs.coverDissolve && video.isEmpty()
    val flip = if (dissolve) null else flip0
    val showOld by remember(flip) { derivedStateOf { flip != null && flip.from != null && flip.trackDeg < 90f } }
    val old = flip?.from
    // İlk yarıda eski kapak; ikinci yarıda yenisi (henüz yüklenmediyse eskisi kalır, boşluk / ♪ görünmez)
    val shown = when { !allowOld || old == null -> art; showOld -> old; else -> art ?: old }
    val shape = RoundedCornerShape(corner)
    fun angle(): Float = spin() + if (flip != null) flip.flipDir * flip.trackDeg else 0f
    Box(
        modifier.size(size)
            .graphicsLayer {
                val f = foldDeg(angle())
                rotationY = f
                // Kamera, kartın boyutundan uzakta olmalı; yakın olursa dönerken kart kamerayın arkasına geçip kaybolur
                cameraDistance = maxOf(this.size.width, this.size.height) * 3f   // 'size' parametresi (Dp) ile karışmasın
                setBlurPx(abs(f) / 90f * 5.dp.toPx())
            }
            .clip(shape)
            .drawWithContent {
                drawContent()
                val k = abs(foldDeg(angle())) / 90f
                if (k > 0.01f) drawRect(Pal.c("island_flip_shade").copy(alpha = (k * k * 0.75f).coerceIn(0f, 0.85f)))
            },
        contentAlignment = Alignment.Center,
    ) {
        if (video.isNotEmpty()) {
            VideoBackdrop(video, 0f)
        } else if (dissolve) {
            DissolveArt(art ?: old, Modifier.fillMaxSize())
        } else if (shown != null) {
            Image(
                bitmap = shown, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(Modifier.fillMaxSize().background(Pal.c("art_placeholder_bg")), contentAlignment = Alignment.Center) {
                Text("♪", color = Pal.c("art_placeholder_note"), fontSize = 22.sp)
            }
        }
    }
}

@Immutable
data class Geo(val cw: Dp, val ch: Dp, val ew: Dp, val eh: Dp)

/** Adanın pencere içindeki dikey hizası: normalde üst, "Ters ada" açıkken alt. */
fun IslandSettings.vAlign(): Alignment = if (invertedIsland) Alignment.BottomCenter else Alignment.TopCenter

fun compactWidthFor(cfg: IslandSettings, compactLyrics: Boolean): Float =
    if (compactLyrics) maxOf(cfg.compactWidthDp, cfg.lyricsCompactWidthDp) else cfg.compactWidthDp

fun islandGeo(cfg: IslandSettings, music: Boolean, compactLyrics: Boolean = false) = Geo(
    cw = compactWidthFor(cfg, compactLyrics && music).dp,
    ch = cfg.compactHeightDp.dp,
    ew = cfg.expandedWidthDp.dp,
    eh = (cfg.expandedHeightDp + if (music) cfg.musicExtraDp() else 0f).dp,
)

/**
 * Müzikten çıkış "yutma" efektinde adanın büyüdüğü boyut (dp): kapalı adadan biraz geniş, yaklaşık iki kat yüksek;
 * açık ada boyutunu aşmaz. Hem arayüz hem pencere boyutu aynı formülü kullanır.
 */
fun swallowSizeDp(cw: Float, ch: Float, ew: Float, eh: Float): Pair<Float, Float> =
    minOf(ew, cw * 1.15f).coerceAtLeast(cw) to minOf(eh, ch * 2.1f).coerceAtLeast(ch)

// ---------------------------------------------------------------- Animasyon (5 stil + kapalı)
/** Genişlik ve yükseklik ilerlemesi ayrı: "Sıvı" stilinde farklı zamanlanır, diğerlerinde aynıdır. */
@Stable
class IslandProgress(val w: State<Float>, val h: State<Float>) {
    val mean: Float get() = (w.value + h.value) / 2f
}

internal fun specFor(cfg: IslandSettings, expanding: Boolean, isWidth: Boolean): FiniteAnimationSpec<Float> {
    if (!cfg.animEnabled) return snap()
    return when (cfg.animStyle) {
        0 -> spring(dampingRatio = 0.9f, stiffness = Spring.StiffnessMediumLow)           // Yaylı
        1 -> tween(520, easing = FastOutSlowInEasing)                                      // Yumuşak
        2 -> tween(220, easing = LinearOutSlowInEasing)                                    // Hızlı
        3 -> spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow)           // Zıplamalı
        else -> {                                                                          // Sıvı
            val first = if (expanding) isWidth else !isWidth       // açılırken önce genişlik, kapanırken önce yükseklik
            tween(durationMillis = 340, delayMillis = if (first) 0 else 140, easing = FastOutSlowInEasing)
        }
    }
}

@Composable
fun rememberIslandProgress(cfg: IslandSettings, expanded: Boolean, fast: Boolean = false): IslandProgress {
    val target = if (expanded) 1f else 0f
    // Yutma efekti: ada panel adaya varmadan büyümüş olsun diye ayardaki stil yerine kısa, düz bir animasyon
    val fastSpec: FiniteAnimationSpec<Float> = if (cfg.animEnabled) tween(160, easing = LinearOutSlowInEasing) else snap()
    val pw = animateFloatAsState(target, if (fast) fastSpec else specFor(cfg, expanded, true), label = "pw")
    val ph = animateFloatAsState(target, if (fast) fastSpec else specFor(cfg, expanded, false), label = "ph")
    return remember(pw, ph) { IslandProgress(pw, ph) }
}

private const val EXPAND_LEAD_MS = 24L          // pencere büyüdükten sonra ek bekleme (yaklaşık 1,5 kare)
private const val EXPAND_WAIT_MAX_MS = 220L    // pencere büyümezse en fazla bu kadar beklenir (güvenlik)

/** Servisteki syncWindow ile aynı hesap: açık haldeki ada yüksekliği (dp), pay hariç. */
internal fun expandedHeightDpFor(c: IslandContent, cfg: IslandSettings): Float = xExpandedSizeDp(c, cfg, 0f)?.second ?: when (c) {
    IslandContent.Assistant -> cfg.assistantHeightDp
    is IslandContent.Favorites -> cfg.favoritesHeightDp(c.apps.size)
    IslandContent.Translate -> cfg.translateHeightDp(TranslateSession.bodyDp.value)
    is IslandContent.Call -> if (c.isIncoming) 74f else 88f
    is IslandContent.Status -> when {
        c.isSysPill() -> sysPillHeightDp(cfg)
        c.isCard() -> LOWBATT_CARD_H_DP
        c.isPill() -> if (c.kind == StatusKind.Ringer) ringerPillHeightDp(cfg) else chargePillHeightDp(cfg)
        else -> cfg.expandedHeightDp
    }
    IslandContent.Photo -> cfg.more.designPhotoHDp
    is IslandContent.Capsule -> capsuleHeightDp(c)
    is IslandContent.Live -> LIVE_H_DP
    IslandContent.Drawer -> cfg.drawerHeightDp()
    is IslandContent.ScreenRec -> REC_PILL_H_DP
    is IslandContent.SoundCapsule -> SOUND_CAPSULE_PILL_H_DP
    is IslandContent.Media -> cfg.expandedHeightDp + cfg.musicExtraDp()
    is IslandContent.Notif -> cfg.expandedHeightDp + notifChipsExtraDp(c)
    else -> cfg.expandedHeightDp
}

@Composable
fun IslandRoot(
    state: StateFlow<IslandUiState>,
    settingsFlow: StateFlow<IslandSettings>,
    lyricsFlow: StateFlow<LyricsState>,
    onTap: () -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    onLongPress: () -> Unit = {},
    onLaunch: (String) -> Unit = {},
    onAnswer: () -> Unit = {},
    onDecline: () -> Unit = {},
    onSpeaker: () -> Unit = {},
    onOpenMusic: () -> Unit = {},
    onSwipe: (Int) -> Unit = {},     // +1 = aşağı, -1 = yukarı
    onRecStop: () -> Unit = {},
    onSoundCapsuleStop: () -> Unit = {},       // genişlemiş SoundCapsule "Listening..." panelindeki X düğmesi
    onNotifSwipeSide: () -> Unit = {},   // bildirim üstünde sağa / sola kaydırma
    onSideTap: (Int) -> Unit = {},       // yan baloncuğa dokunma: 0 = sağ, 1 = sol
    onStackClose: () -> Unit = {},       // yığın görünümünde karta dokunma / yukarı kaydırma: yığın kapanır
    onSidePress: (Int) -> Unit = {},     // yan baloncuğa parmak değdi (henüz dokunma tamamlanmadı): pencere önceden büyütülür
) {
    val s by state.collectAsState()
    val cfg by settingsFlow.collectAsState()
    val lyrics by lyricsFlow.collectAsState()
    val content = s.content
    val media = content as? IslandContent.Media
    // Çeviri paneli: sonuç uzadıkça / kısaldıkça panel yumuşakça büyür / küçülür (pencereyi servis boyutlandırır)
    val trBody by TranslateSession.bodyDp.collectAsState()
    val trHeight by animateDpAsState(cfg.translateHeightDp(trBody).dp, tween(200, easing = FastOutSlowInEasing), label = "trH")

    // Kapak dönme: şarkı değişince eski kapak yarım tur dönüp yenisiyle değişir (yön: sonraki / önceki)
    val artFlip = remember { ArtFlip() }
    val trackHolder = remember { TrackHolder() }
    val trackKey = media?.let { it.title + "|" + it.artist }
    val flipOn = cfg.animEnabled && cfg.musicArtFlip
    SideEffect {
        if (trackKey == trackHolder.key) trackHolder.art = media?.art
        if (media?.art != null && artFlip.held != null) artFlip.held = null   // yeni kapak geldi: tutulan eskisi bırakılır
    }
    // Şarkı değişimi, yeni şarkının İLK karesinde eski kapak görünsün diye kompozisyon sırasında hazırlanır (kapak yanıp sönmesin)
    remember(trackKey) {
        val old = trackHolder.art
        val prev = trackHolder.key
        artFlip.trackDeg = 0f
        artFlip.from = if (flipOn && trackKey != null && prev != null && trackKey != prev && old != null) old else null
        if (artFlip.from != null) { artFlip.flipDir = artFlip.dir; artFlip.dir = 1 }
        // Yeni şarkının kapağı henüz yüklenmediyse (art == null) eski kapak ekranda kalır; aksi halde kapak bir an yok olurdu
        artFlip.held = if (trackKey != null && prev != null && trackKey != prev && media?.art == null) old else null
        trackHolder.key = trackKey; trackHolder.art = media?.art
        0
    }
    LaunchedEffect(trackKey) {
        if (artFlip.from != null) {
            animate(0f, 180f, animationSpec = tween(600, easing = FastOutSlowInEasing)) { v, _ -> artFlip.trackDeg = v }
            artFlip.from = null
            artFlip.trackDeg = 0f
        }
    }
    // Yeni şarkının kapağı hiç gelmezse (kapaksız uygulama) eski kapak sonsuza dek kalmasın
    LaunchedEffect(trackKey) { if (artFlip.held != null) { delay(2_500); artFlip.held = null } }
    val cmd: (MediaCommand) -> Unit = { c ->
        when (c) { MediaCommand.Next -> artFlip.dir = 1; MediaCommand.Previous -> artFlip.dir = -1; else -> Unit }
        onCommand(c)
    }
    // Açılırken animasyonu çok kısa geciktir: pencere önce büyüsün, animasyonun ilk (en hızlı) kareleri
    // küçük pencereye çizilip kırpılmasın ("yaylı" animasyonda açılışta takılma). Kapanış gecikmesizdir.
    val wantExpanded = s.expanded   // arama: açık/küçük durumunu servis belirler (gelen arama hep açık)
    var animExpanded by remember { mutableStateOf(wantExpanded) }
    // Pencerenin ölçülen yüksekliği: servis pencereyi büyütünce (sistem uygulayınca) değişir
    var winH by remember { mutableIntStateOf(0) }
    val densityNow = androidx.compose.ui.platform.LocalDensity.current
    val contentNow by rememberUpdatedState(content)
    val cfgLatest by rememberUpdatedState(cfg)
    LaunchedEffect(wantExpanded) {
        if (wantExpanded && cfg.animEnabled && !s.swallow) {
            // Sabit 70 ms yerine: pencere açık boyuta ulaşana kadar bekle (en çok EXPAND_WAIT_MAX_MS).
            // Yay animasyonunun ilk (çok hızlı) kareleri küçük pencerede kırpılırsa ada "pat diye" açılmış gibi görünür.
            val c = cfgLatest
            val needPx = with(densityNow) { (expandedHeightDpFor(contentNow, c) + 2f * c.windowPadDp()).dp.roundToPx() }
            if (winH < needPx - 2) {
                kotlinx.coroutines.withTimeoutOrNull(EXPAND_WAIT_MAX_MS) { snapshotFlow { winH }.first { it >= needPx - 2 } }
                delay(EXPAND_LEAD_MS)       // pencere yüzeyi yeni boyutla en az bir kare çizsin
            }
        }
        animExpanded = wantExpanded
    }
    val progress = rememberIslandProgress(cfg, animExpanded, s.swallow)
    val prevCw = remember { mutableFloatStateOf(cfg.compactWidthDp) }
    val popIn = remember { Animatable(1f) }
    val popLatch = remember { mutableStateOf(false) }
    val ghostHold = remember { SideHold() }
    val ghostProgress = remember { IslandProgress(mutableStateOf(0f), mutableStateOf(0f)) }
    val popScope = rememberCoroutineScope()
    val fromKind = remember { arrayOfNulls<Any>(1) }
    // Genişletilmiş kapak dönüşü zamana bağlı (yavaş): açılırken 0 -> 360, küçülürken ters yönde 360 -> 0
    LaunchedEffect(animExpanded, flipOn) {
        if (!flipOn) { artFlip.spin.snapTo(0f); return@LaunchedEffect }
        if (animExpanded) { artFlip.spin.snapTo(0f); artFlip.spin.animateTo(360f, tween(1100, easing = FastOutSlowInEasing)) }
        else artFlip.spin.animateTo(0f, tween(750, easing = FastOutSlowInEasing))   // küçülürken kapalı adadaki kapak da döner (artSpin)
    }
    val lyricsOn = cfg.lyricsEnabled && (cfg.lyricsInPanel || cfg.lyricsCompact)
    val frame = rememberLyricFrame(
        media, lyrics, cfg.lyricsOffsetMs.toLong(),
        active = lyricsOn && lyrics is LyricsState.Ready, fine = cfg.lyricsFillEnabled,
    )

    // Yan baloncukların rengi: ortadaki adanın gövde rengiyle aynı (kapak rengi açıksa ve müzik ortadaysa kapaktan)
    val sideArtColor = media?.artColor?.takeIf { it != 0 }?.let { Color(it) }
    val sideBody = animateColorAsState(bodyColorFor(cfg, sideArtColor, Color(cfg.accentArgb)), tween(450), label = "sideBody")

    // Tam genişlik asistan: ada kutusu yok, pencere ekran genişliğinde; animasyon doğrudan çizilir
    if (content is IslandContent.Assistant && cfg.assistantFullWidth) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(s.expanded) { shown = s.expanded }
        val barAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 350 else 250), label = "orbBar")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = barAlpha }) { AssistantOrb(fullWidth = true) }
        return
    }

    // Yığın görünümü: birden fazla olay alt alta kartlar halinde (servis s.stack'i doldurur)
    if (s.stack.isNotEmpty()) {
        Box(Modifier.fillMaxSize().padding(cfg.windowPadDp().dp), contentAlignment = cfg.vAlign()) {
            IslandStackPanel(
                items = s.stack, closing = s.stackClosing, cfg = cfg, lyrics = lyrics,
                onClose = onStackClose, onCommand = cmd, onOutput = onOutput, onOpenMusic = onOpenMusic,
                onAnswer = onAnswer, onDecline = onDecline, onSpeaker = onSpeaker,
                onRecStop = onRecStop, onSoundCapsuleStop = onSoundCapsuleStop,
            )
        }
        return
    }

    // Pencere, ada + parıltı payı kadar sabit boyutlu; ada üst-ortada büyür/küçülür
    Box(
        Modifier.fillMaxSize().onSizeChanged { winH = it.height }.padding(cfg.windowPadDp().dp),
        contentAlignment = cfg.vAlign(),
    ) {
        // Boştayken de göster açıksa Idle içerikte de (boş) ada çizilir; kapalıysa hiçbir şey çizilmez.
        if (content !is IslandContent.Idle || cfg.showIdle) {
            val music = content is IslandContent.Media
            // Sözler görününce kapalı ada yumuşakça genişler / sözler bitince daralır
            // Yutma efektinde ada, sözler açık olsa bile VARSAYILAN kapalı genişlikten büyür; efekt bitince ayara göre genişler
            val cwSpec: AnimationSpec<Dp> = if (cfg.animEnabled) tween<Dp>(if (s.swallow) 140 else 380, easing = FastOutSlowInEasing) else snap<Dp>()
            val cw by animateDpAsState(compactWidthFor(cfg, s.compactLyrics && music && !s.swallow).dp, cwSpec, label = "cw")
            var geo = islandGeo(cfg, music, s.compactLyrics).copy(cw = cw)
            if (content is IslandContent.Assistant) geo = geo.copy(ew = cfg.assistantWidthDp.dp, eh = cfg.assistantHeightDp.dp)
            if (content is IslandContent.Favorites) geo = geo.copy(eh = cfg.favoritesHeightDp(content.apps.size).dp)
            if (content is IslandContent.Translate) geo = geo.copy(eh = trHeight)
            if (content is IslandContent.Call) geo = geo.copy(cw = if (cfg.dotMode) cw else maxOf(cw, CALL_COMPACT_W_DP.dp), eh = if (content.isIncoming) 74.dp else 88.dp)
            pillCompactSizeDp(cfg, content)?.let { (pw, ph) -> geo = geo.copy(cw = pw.dp, ch = ph.dp) }
            if (content is IslandContent.Status && content.isPill()) {
                if (content.kind == StatusKind.Ringer) {
                    val swDp = LocalConfiguration.current.screenWidthDp.toFloat()
                    geo = geo.copy(ew = ringerPillWidthDp(cfg, content.silent || content.vibrate, swDp).dp, eh = ringerPillHeightDp(cfg).dp)
                } else geo = geo.copy(ew = chargePillWidthDp(cfg).dp, eh = chargePillHeightDp(cfg).dp)
            }
            // Ağ / uçak modu / zil sesi / ince düşük pil: geniş iOS hapı; düşük pil kartı: açık ada genişliğinde kart
            if (content is IslandContent.Status && content.isSysPill()) {
                val swDp = LocalConfiguration.current.screenWidthDp.toFloat()
                geo = geo.copy(ew = sysPillWidthDp(cfg, swDp).dp, eh = sysPillHeightDp(cfg).dp)
            }
            if (content is IslandContent.Status && content.isCard()) geo = geo.copy(eh = LOWBATT_CARD_H_DP.dp)
            if (content is IslandContent.Live) geo = geo.copy(eh = LIVE_H_DP.dp)
            // Akıllı bildirim: çip satırı için ada biraz daha uzun açılır
            if (content is IslandContent.Notif && content.chips.isNotEmpty()) geo = geo.copy(eh = geo.eh + NOTIF_CHIPS_DP.dp)
            // Özel tasarım fotoğrafı: ada çentiksiz, fotoğrafın tamamını gösteren büyük bir karta açılır
            if (content is IslandContent.Capsule) geo = geo.copy(eh = capsuleHeightDp(content).dp)
            if (content is IslandContent.Drawer) geo = geo.copy(eh = cfg.drawerHeightDp().dp)
            if (content is IslandContent.Photo) {
                val swDp = LocalConfiguration.current.screenWidthDp.toFloat()
                geo = geo.copy(ew = designPhotoWidthDp(cfg, swDp).dp, eh = cfg.more.designPhotoHDp.dp)
            }
            // Ekran kaydı: geri sayım / işleniyor aşamasında kapalı ada biraz genişler (sağda rakam / halka için)
            val recWide = !cfg.dotMode && content is IslandContent.ScreenRec &&
                (content.phase == RecPhase.Countdown || content.phase == RecPhase.Processing)
            val recCw by animateDpAsState(
                if (recWide) maxOf(cw, CALL_COMPACT_W_DP.dp) else cw,
                tween(240, easing = FastOutSlowInEasing), label = "recCw",
            )
            if (content is IslandContent.SoundCapsule) geo = geo.copy(eh = SOUND_CAPSULE_PILL_H_DP.dp)
            // Yeni tasarımlar (zamanlayıcı, alarm, Rahatsız Etmeyin kartı, ses kaydı, logolu skor): IslandExtrasUi.kt
            if (pillCompactSizeDp(cfg, content) == null) xCompactWidthDp(content, cfg)?.let { geo = geo.copy(cw = it.dp) }
            xExpandedSizeDp(content, cfg, LocalConfiguration.current.screenWidthDp.toFloat())?.let { (w, h) -> geo = geo.copy(ew = w.dp, eh = h.dp) }
            if (content is IslandContent.ScreenRec) {
                val screenWDp = LocalConfiguration.current.screenWidthDp.toFloat()
                geo = geo.copy(cw = recCw, ew = recExpandedWidthDp(cfg, screenWDp).dp, eh = REC_PILL_H_DP.dp)
            }
            if (s.swallow && content is IslandContent.Media) {
                val sz = swallowSizeDp(geo.cw.value, geo.ch.value, geo.ew.value, geo.eh.value)
                geo = geo.copy(ew = sz.first.dp, eh = sz.second.dp)
            }
            // Yan baloncuklar adanın ARKASINA çizilir (ada üstte kalır); köprü adanın ucundan uzanır
            // Önceki ortadaki adanın kapalı genişliği: yan baloncuğun konumu buna göre hesaplanmıştı (animasyonun çıkış noktası)
            if (s.fromSide == 0 && !s.expanded) SideEffect { prevCw.value = geo.cw.value }
            val fromX = if (s.fromSide == 0) 0.dp else run {
                val fd = if (s.fromSide > 0) 1 else -1                  // yön
                val lvl = abs(s.fromSide)                                // 1 = iç yuva, 2 = dış yuva
                val r = slotSizeDp(cfg, fd, lvl) / 2f
                val skip = if (lvl == 2) sideSizeDp(cfg, fd) + sideGapDp(cfg) else 0f
                (fd * (prevCw.value / 2f + skip + sideGapDp(cfg) + r)).dp
            }
            // Eski ortadaki olayın son görüntüsü: baloncuğa basılınca, yeni ada genişlemeye başlayana kadar ortada kalır (boşluk olmasın)
            val ghostWanted = s.fromSide != 0 && s.expanded && !animExpanded
            if (s.fromSide == 0 && !s.expanded) ghostHold.c = content
            val ghostFade = remember { Animatable(0f) }
            LaunchedEffect(ghostWanted) { if (ghostWanted) ghostFade.snapTo(1f) else ghostFade.animateTo(0f, tween(180)) }
            // Baloncuktan açılırken yeni içeriğin kapalı genişliği (cw) animasyonla değişir; baloncuklar buna değil,
            // baloncuğun çıkış noktasını belirleyen sabit genişliğe bağlanır: yerlerinde kayıp oynamazlar.
            val sideGeo = if (s.fromSide != 0) geo.copy(cw = prevCw.value.dp) else geo
            IslandSides(s.side, sideGeo, progress, cfg, sideBody, s.fromSide, animExpanded, s.expanded, onSidePress, onSideTap)
            val ghostC = ghostHold.c
            if (ghostC != null && (ghostWanted || ghostFade.value > 0.01f)) {
                val ghostGeo = islandGeo(cfg, ghostC is IslandContent.Media).copy(cw = prevCw.value.dp)
                Box(Modifier.graphicsLayer { alpha = if (ghostWanted) 1f else ghostFade.value }) {
                    IslandPill(ghostC, ghostProgress, ghostGeo, cfg, lyrics, frame, false, {}, {}, {})
                }
            }
            // Baloncuğa küçülüp yerine oturduktan sonra ortadaki ada (önceki olay) yumuşakça belirir
            val trigger = fromKind[0] != null && s.fromSide == 0 && !s.expanded && fromKind[0] != content::class
            if (s.fromSide != 0) fromKind[0] = content::class
            else if (!trigger && (s.expanded || fromKind[0] == content::class)) fromKind[0] = null
            // Ada, tetikten animasyon başlayana kadar gizli kalır (bir kare görünüp yanıp sönmesin)
            val hidden = trigger || popLatch.value
            LaunchedEffect(trigger) {
                if (trigger) {
                    popLatch.value = true
                    fromKind[0] = null
                    // Animasyon kendi scope'unda çalışır: anahtar değişince iptal olup adayı şeffaf bırakmaz
                    popScope.launch {
                        try {
                            popIn.snapTo(0f)
                            popLatch.value = false
                            popIn.animateTo(1f, tween(240))
                        } finally { popLatch.value = false }
                    }
                }
            }
            // Ada açılınca (ya da başka olay gelince) her zaman tam görünür
            LaunchedEffect(s.expanded) { if (s.expanded) popIn.snapTo(1f) }
            // Durum içeriklerinde açık boyut değişince (ör. Rahatsız Etmeyin hapı -> kart) ada birden değil, yumuşakça büyür / küçülür
            // Yalnızca AYNI durum türü içinde (ör. hap -> kart) canlandırılır. Başka bir içerikten gelince eski boyuttan
            // başlayıp bir an büyük dikdörtgen görünmesin diye boyut anında hedefe oturtulur.
            val stKind: Any? = (content as? IslandContent.Status)?.kind
            val stAnim = stKind != null && cfg.animEnabled
            val ewAn = remember { Animatable(geo.ew, Dp.VectorConverter) }
            val ehAn = remember { Animatable(geo.eh, Dp.VectorConverter) }
            val animKind = remember { arrayOfNulls<Any>(1) }
            LaunchedEffect(geo.ew, geo.eh, stKind) {
                if (stAnim && animKind[0] == stKind) {
                    launch { ewAn.animateTo(geo.ew, tween(340, easing = FastOutSlowInEasing)) }
                    ehAn.animateTo(geo.eh, tween(340, easing = FastOutSlowInEasing))
                } else {
                    ewAn.snapTo(geo.ew); ehAn.snapTo(geo.eh)
                    animKind[0] = stKind
                }
            }
            val geoA = if (stAnim && animKind[0] == stKind) geo.copy(ew = ewAn.value, eh = ehAn.value) else geo
            Box(Modifier.graphicsLayer { alpha = if (hidden) 0f else popIn.value }) {
            IslandPill(content, progress, geoA, cfg, lyrics, frame, s.compactLyrics, onTap, cmd, onOutput, onLongPress, onLaunch, onAnswer, onDecline, onSpeaker, swallow = s.swallow, onOpenMusic = onOpenMusic, onSwipe = onSwipe, artFlip = artFlip, onRecStop = onRecStop, onSoundCapsuleStop = onSoundCapsuleStop, onNotifSwipeSide = onNotifSwipeSide, fromX = fromX, fxMode = 2)
            }
        }
    }
}

// ---------------------------------------------------------------- Renk / yüzey / parıltı
private val PillShape = RoundedCornerShape(36.dp)
private val GlassMaxRadius = 36.dp

private fun darkTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    // Eskiden çok koyuydu (0.26): artık kapak rengi daha canlı görünür, beyaz yazı yine rahat okunur.
    // Gri/siyah kapaklar kırmızıya boyanmasın diye gri kalır.
    val gray = hsv[1] < 0.12f
    hsv[1] = if (gray) hsv[1] else hsv[1].coerceIn(0.55f, 0.95f)
    hsv[2] = if (gray) 0.30f else 0.50f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Ekolayzır için: albüm rengi aynı tonda ama en parlak ve biraz pastel; koyu ada gövdesinden net ayrılır. */
internal fun brightTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.85f).coerceIn(0f, 0.8f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

internal fun bodyColorFor(cfg: IslandSettings, art: Color?, accent: Color): Color {
    val tint: Color? = if (cfg.dynamicColor) art else null
    val base = Color(cfg.islandColorArgb)   // albüm rengi kapalı / kapak yok: ada seçilen renge boyanır (varsayılan siyah)
    return when {
        cfg.glassEnabled -> (tint ?: base).copy(alpha = cfg.glassAlpha)
        tint != null -> darkTone(tint)
        else -> base
    }
}

/** Panel animasyonlarının rengi = adanın o anki gövde rengi (cam açıksa aynı şeffaflıkla). */
fun islandPanelColor(cfg: IslandSettings, artArgb: Int, opaque: Boolean = false): Color {
    val art = if (artArgb != 0) Color(artArgb) else null
    val c = bodyColorFor(cfg, art, Color(cfg.accentArgb))
    // Cam açıkken panel de adanın şeffaflığını taşır; ekranı örtmesi gereken geçişlerde (opaque) opak karşılığı kullanılır
    return if (opaque && c.alpha < 1f) androidx.compose.ui.graphics.lerp(Color.Black, c.copy(alpha = 1f), c.alpha) else c
}

// ---------------------------------------------------------------- Liquid Glass katmanları
/**
 * Liquid Glass — içeriğin ARKASINA çizilen katmanlar:
 *  1) üstten alta ışık geçişi (camın kalınlığı), alt kenarda hafif ışık toplanması
 *  2) sol üstte yumuşak speküler parlama
 *  3) kenarlara doğru incelen iç ışık halkaları (lens / kırılma hissi)
 */
private fun DrawScope.drawGlassBase(cornerT: Float = -1f) {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val r = islandCornerPx(w, h, density, cornerT)

    drawRect(
        Brush.verticalGradient(
            0f to Pal.c("glass_sheen").copy(0.17f),
            0.40f to Pal.c("glass_sheen").copy(0.04f),
            0.72f to Color.Transparent,
            1f to Pal.c("glass_sheen").copy(0.09f),
        )
    )
    drawRect(
        Brush.radialGradient(
            0f to Pal.c("glass_sheen").copy(0.22f),
            0.6f to Pal.c("glass_sheen").copy(0.05f),
            1f to Color.Transparent,
            center = Offset(w * 0.20f, h * 0.02f),
            radius = max(w * 0.60f, h),
        )
    )
    // Alt-sağ köşede çok hafif ikincil parlama (ışığın cam içinden geçip toplandığı yer)
    drawRect(
        Brush.radialGradient(
            0f to Pal.c("glass_sheen").copy(0.08f),
            1f to Color.Transparent,
            center = Offset(w * 0.88f, h * 1.0f),
            radius = max(w * 0.35f, h * 0.9f),
        )
    )

    val rings = 6
    val step = 1.4.dp.toPx()
    for (i in 0 until rings) {
        val inset = i * step + step / 2f
        val a = 0.12f * (1f - i / rings.toFloat())
        drawRoundRect(
            color = Pal.c("glass_sheen").copy(alpha = a),
            topLeft = Offset(inset, inset),
            size = Size((w - 2 * inset).coerceAtLeast(0f), (h - 2 * inset).coerceAtLeast(0f)),
            cornerRadius = CornerRadius((r - inset).coerceAtLeast(0f)),
            style = Stroke(width = step),
        )
    }
}

/**
 * Liquid Glass — içeriğin ÜSTÜNE çizilen ince kenar ışığı (specular rim):
 * sol-üstte parlak, ortada sönük, sağ-altta tekrar parlak; iOS'taki cam kenarı gibi.
 */
private fun DrawScope.drawGlassRim(cornerT: Float = -1f) {
    val w = size.width; val h = size.height
    if (w <= 1f || h <= 1f) return
    val r = islandCornerPx(w, h, density, cornerT)

    val sw = 1.3.dp.toPx()
    val rim = Brush.linearGradient(
        0f to Pal.c("glass_sheen").copy(0.90f),
        0.28f to Pal.c("glass_sheen").copy(0.20f),
        0.62f to Pal.c("glass_sheen").copy(0.07f),
        1f to Pal.c("glass_sheen").copy(0.55f),
        start = Offset.Zero, end = Offset(w, h),
    )
    drawRoundRect(
        brush = rim,
        topLeft = Offset(sw / 2f, sw / 2f),
        size = Size(w - sw, h - sw),
        cornerRadius = CornerRadius((r - sw / 2f).coerceAtLeast(0f)),
        style = Stroke(width = sw),
    )

    // İç ikinci halka: kenarın "kalınlığını" verir
    val sw2 = 0.7.dp.toPx()
    val off = sw + sw2 / 2f
    drawRoundRect(
        color = Pal.c("glass_sheen").copy(alpha = 0.10f),
        topLeft = Offset(off, off),
        size = Size((w - 2 * off).coerceAtLeast(0f), (h - 2 * off).coerceAtLeast(0f)),
        cornerRadius = CornerRadius((r - off).coerceAtLeast(0f)),
        style = Stroke(width = sw2),
    )

    // Üst kenarda ortası parlak, uçları kaybolan speküler çizgi
    if (w > 2.2f * r) {
        val y = 1.8.dp.toPx()
        val x0 = r * 0.9f; val x1 = w - r * 0.9f
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.35f to Pal.c("glass_sheen").copy(0.55f),
                0.65f to Pal.c("glass_sheen").copy(0.30f),
                1f to Color.Transparent,
                startX = x0, endX = x1,
            ),
            start = Offset(x0, y), end = Offset(x1, y),
            strokeWidth = 1.1.dp.toPx(), cap = StrokeCap.Round,
        )
    }
}

/** Renk State olarak taşınır, yalnızca çizim fazında okunur => animasyonda recomposition yok. */
@Composable
internal fun Modifier.islandSurface(cfg: IslandSettings, body: State<Color>, shape: Shape = PillShape, cornerT: Float = -1f, borderTint: Color? = null): Modifier {
    val liquid = cfg.glassEnabled && cfg.liquidEnabled
    var m = this.clip(shape).drawBehind { drawRect(body.value) }
    if (liquid) m = m.drawBehind { drawLiquidBase(cfg.liquidSpecular, cornerT) }
    if (liquid) m = m.liquidBackdrop(cfg.liquidRefraction, cfg.liquidDispersion, cornerT)
    else if (cfg.glassEnabled) m = m.drawBehind { drawGlassBase(cornerT) }
    if (cfg.borderEnabled) {
        val rbBorder = rememberRainbowStyle(cfg)
        m = if (rbBorder == null) m.border(cfg.borderWidthDp.dp, borderTint ?: Color(cfg.borderColorArgb), shape)
        else m.drawWithContent {
            drawContent()
            // Şekil kırpıldığı için çizginin yalnızca iç yarısı görünür; bu yüzden 2x kalınlık
            val w = cfg.borderWidthDp.dp.toPx() * 2f
            val brush = rainbowBrush(rbBorder.phase.value, size.width, rbBorder.alpha)
            val r = minOf(36.dp.toPx(), size.height / 2f)
            drawRoundRect(brush, cornerRadius = CornerRadius(r, r), style = Stroke(width = w))
        }
    }
    // Kenar ışığı, içeriğin (yazı/kapak) üstüne çizilir; kullanıcı çerçevesi yine de en üstte kalır
    if (liquid) m = m.drawWithContent { drawContent(); drawLiquidRim(cfg.liquidSpecular, cornerT) }
    else if (cfg.glassEnabled) m = m.drawWithContent { drawContent(); drawGlassRim(cornerT) }
    // En içte: yalnızca iç içerik (kapak, yazı, butonlar) kenarlarda bükülür (Android 13+)
    if (liquid && !LiquidCapture.running) m = m.liquidRefraction(cfg.liquidRefraction, cfg.liquidDispersion, cornerT)
    return m
}

/** Blur filtresi yerine kademeli (azalan opaklıkta) halkalar: GPU dostu, her cihazda çalışır. */
internal fun Modifier.islandGlow(enabled: Boolean, color: State<Color>, intensity: Float): Modifier =
    if (!enabled) this else this.drawBehind {
        val rings = 6
        val step = GLOW_PAD_DP.dp.toPx() / rings
        val base = min(36.dp.toPx(), size.height / 2f)
        val c = color.value
        for (i in 1..rings) {
            val inflate = (i - 0.5f) * step
            val fall = 1f - (i - 1f) / rings
            val a = (intensity * 0.6f * fall * fall).coerceIn(0f, 1f)
            drawRoundRect(
                color = c.copy(alpha = a),
                topLeft = Offset(-inflate, -inflate),
                size = Size(size.width + 2 * inflate, size.height + 2 * inflate),
                cornerRadius = CornerRadius(base + inflate),
                style = Stroke(width = step),
            )
        }
    }

@Composable
fun IslandPill(
    content: IslandContent,
    progress: IslandProgress,
    geo: Geo,
    cfg: IslandSettings,
    lyrics: LyricsState,
    frame: LyricFrame,
    compactLyrics: Boolean,
    onTap: () -> Unit,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    onLongPress: () -> Unit = {},
    onLaunch: (String) -> Unit = {},
    onAnswer: () -> Unit = {},
    onDecline: () -> Unit = {},
    onSpeaker: () -> Unit = {},
    swallow: Boolean = false,      // yutma efekti: ada büyür ama içerik çizilmez
    onOpenMusic: () -> Unit = {},
    onSwipe: (Int) -> Unit = {},
    artFlip: ArtFlip? = null,
    onRecStop: () -> Unit = {},
    onSoundCapsuleStop: () -> Unit = {},
    onNotifSwipeSide: () -> Unit = {},
    fxMode: Int = 0,        // ada olayları: 0 kapalı, 1 önizleme (yalnızca ada içi), 2 gerçek ada (parçalar ekrana düşer)
    fromX: Dp = 0.dp,       // yan baloncuktan açılıyorsa baloncuğun ada merkezine göre yatay konumu (0 = normal açılış)
) {
    val fromNow by rememberUpdatedState(fromX)
    // Ada olayları (yağmur / kar / asit / şimşek / kırılma)
    val fx = remember { IslandFxState() }
    // Şarkının havası: hava efekti ayardan değil, çalan şarkının kapak renginden seçilir (-1 = ayardaki efekt)
    val moodArt = (content as? IslandContent.Media)?.artColor ?: 0
    val moodByArt = if (cfg.more.musicMood && moodArt != 0) {
        if (cfg.more.moodExtended) moodWeatherExtended(moodArt) else moodWeatherFor(moodArt)
    } else -1
    // Takvim olayları: özel gün / saatte olay kendiliğinden açılır (şarkının havasından önceliklidir); dakikada bir yeniden bakılır
    var calTick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(cfg.more.calendarFx) { while (cfg.more.calendarFx) { delay(60_000L); calTick = System.currentTimeMillis() } }
    val calWeather = if (cfg.more.calendarFx) calendarWeather(calTick) else -1
    val moodWeather = if (calWeather >= 0) calWeather else moodByArt
    SideEffect { FxPrefs.coverDissolve = cfg.more.coverDissolve }
    // Ada sakinleri > Büyüme: gerçek adada müzik çaldıkça dakika sayılır (önizlemeler saymaz)
    val petCtx = androidx.compose.ui.platform.LocalContext.current
    val petListen = fxMode == 2 && cfg.more.fxPets && cfg.more.petGrowth && (content as? IslandContent.Media)?.playing == true
    LaunchedEffect(petListen) {
        if (!petListen) return@LaunchedEffect
        PetLife.init(petCtx)
        while (true) { delay(60_000L); PetLife.addMinutes(1f) }
    }
    val effWeather = if (moodWeather >= 0) moodWeather else cfg.fxWeather
    val fxActive = fxMode != 0 && content is IslandContent.Media && (effWeather != 0 || cfg.fxBreak || cfg.more.fxPets) && !swallow && !cfg.dotMode
    // Dokunma ve basılı tutma: lambdalar güncellense de hareket algılayıcısı yeniden kurulmaz
    val tapNow by rememberUpdatedState(onTap)
    val longPressNow by rememberUpdatedState(onLongPress)
    val openMusicNow by rememberUpdatedState(onOpenMusic)
    val swipeNow by rememberUpdatedState(onSwipe)
    val commandNow by rememberUpdatedState(onCommand)
    // Asistan modunda: kendi şekli; cam ve sıvı cam ayarları uygulanır. Çerçeve / dış parıltı kapalı (şekle uymaz).
    val assistant = content is IslandContent.Assistant
    val cfg = when {
        assistant -> cfg.copy(borderEnabled = false, glowEnabled = false, neonStyle = 0)
        moodWeather >= 0 -> cfg.copy(fxWeather = moodWeather, fxLightning = moodWeather == 1 && isDarkArt(moodArt))
        else -> cfg
    }
    assistantGlassShape = assistant && cfg.assistantStyle == 1 && !cfg.assistantFullWidth   // oval stil: köşe yarıçapı tam yuvarlak
    val cornerT = if (assistant) progress.mean else -1f
    val cfgNow by rememberUpdatedState(cfg)
    val isMediaNow by rememberUpdatedState(content is IslandContent.Media)
    val isNotifNow by rememberUpdatedState(content is IslandContent.Notif)
    val notifSideNow by rememberUpdatedState(onNotifSwipeSide)
    // Ada şekli: boştayken seçilen şekle (kalp / bulut / kedi / elmas) akarak dönüşür, bir şey gelince hapa döner
    val shapeStyle = if (!assistant && !cfg.dotMode) cfg.more.islandShape else 0
    val shapeMorph by animateFloatAsState(
        if (shapeStyle != 0 && content is IslandContent.Idle) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = 260f), label = "shapeMorph",
    )
    val shape: Shape = when {
        assistant -> AssistantShape(cornerT)
        shapeStyle != 0 && shapeMorph > 0.001f -> IslandMorphShape(shapeStyle, shapeMorph)
        else -> PillShape
    }
    val accent = remember(cfg.accentArgb) { Color(cfg.accentArgb) }
    val rb = rememberRainbowStyle(cfg)
    val media = content as? IslandContent.Media
    val artColor = if (media != null && media.artColor != 0) Color(media.artColor) else null
    val highlight = if (cfg.dynamicColor && artColor != null) artColor else accent
    // Ekolayzır: kendi ayarı. Kapak rengi açıksa (ve kapak varsa) kapaktan, değilse seçilen renkten
    val eqFromArt = cfg.eqDynamicColor && artColor != null
    val eqColor = remember(eqFromArt, artColor, cfg.eqColorArgb) {
        if (eqFromArt) brightTone(artColor!!) else Color(cfg.eqColorArgb)
    }

    // Galeriden seçilen özel görseller (ayar kapalıysa boş adres verilir => null). Diske kopyalanmaz.
    val bgOn = cfg.customBgEnabled
    val bgBoth = rememberCustomImage(CustomImages.Slot.BG_BOTH, if (bgOn && !cfg.customBgSeparate) cfg.customBgBothUri else "")
    val bgCompact = rememberCustomImage(CustomImages.Slot.BG_COMPACT, if (bgOn && cfg.customBgSeparate) cfg.customBgCompactUri else "")
    val bgExpanded = rememberCustomImage(CustomImages.Slot.BG_EXPANDED, if (bgOn && cfg.customBgSeparate) cfg.customBgExpandedUri else "")
    val bgVideo = if (bgOn) cfg.customBgVideoUri else ""
    val artVideo = if (cfg.customArtEnabled) cfg.customArtVideoUri else ""
    val customArt = rememberCustomImage(CustomImages.Slot.ART, if (cfg.customArtEnabled) cfg.customArtUri else "")

    // Asistanda cam gövde en az %60 koyu kalır: renk bantları koyu zeminde okunur
    // Kamuflaj: boştaki ada yanındaki ekran rengini alır (portal açıksa kamuflaj uygulanmaz)
    val camo = CamoState.color
    val camoOn = (cfg.more.camoEnabled || cfg.more.contrastEnabled) && !cfg.more.portalEnabled && content is IslandContent.Idle && camo != null
    val bodyTarget = when {
        camoOn && cfg.more.contrastEnabled -> contrastOf(camo!!)     // kontrast ada: çevrenin zıt rengi
        camoOn -> Color(camo!!)
        assistant && cfg.glassEnabled -> Pal.c("island_assistant_body").copy(alpha = max(cfg.glassAlpha, 0.6f))
        else -> bodyColorFor(cfg, artColor, accent)
    }
    // Yutma efektinde ada rengi anında hedef renge geçer: panel animasyonuyla (aynı renk) uyumlu olsun
    val bodyAnim = animateColorAsState(bodyTarget, if (swallow) snap() else tween(450), label = "body")
    val body = remember(swallow, bodyTarget, bodyAnim) { derivedStateOf { if (swallow) bodyTarget else bodyAnim.value } }
    val glow = animateColorAsState(artColor ?: accent, tween(450), label = "glow")

    val panelVisible by remember(progress) { derivedStateOf { max(progress.w.value, progress.h.value) > 0.01f } }
    // Panel önceden hazırlama: parmak adaya değince müzik paneli görünmeden kompoze edilir; açılış animasyonunun
    // ilk karelerinde ağır kompozisyon (takılma) olmaz. 1,5 sn içinde açılmazsa bırakılır.
    var warm by remember { mutableStateOf(false) }
    LaunchedEffect(warm) { if (warm) { delay(1_500); warm = false } }
    val compactVisible by remember(progress) { derivedStateOf { progress.mean < 0.5f && fromNow == 0.dp } }
    // Basılı tutarak açma (ayar): olaylı ada dokununca değil, basılı tutunca açılır; basılıyken ada açılacağı boyuta doğru büyür
    var holding by remember { mutableStateOf(false) }
    val holdGrow = animateFloatAsState(
        if (holding) 1f else 0f,
        if (holding) tween(cfg.more.holdExpandMs.toInt().coerceAtLeast(100), easing = LinearEasing) else tween(160),
        label = "holdGrow",
    )
    val holdFrac = (cfg.more.holdGrowPct / 100f).coerceIn(0f, 0.6f)
    // Basılı tutarken adanın içindeki simge / yazılar da ada ile birlikte büyür (üstten ortalı, oranı bozulmadan)
    val holdScaleMod = Modifier.graphicsLayer {
        val hg = holdGrow.value * holdFrac
        if (hg > 0f) {
            val cwPx = geo.cw.toPx(); val chPx = geo.ch.toPx()
            val w = cwPx + (geo.ew.toPx() - cwPx) * hg
            val h = chPx + (geo.eh.toPx() - chPx) * hg
            val k = min(w / cwPx, h / chPx).coerceAtLeast(1f)
            scaleX = k; scaleY = k
            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f)
            translationY = (h - k * chPx) / 2f
        }
    }
    val holdModeNow by rememberUpdatedState(
        cfg.more.holdExpand && IslandActions.serviceLive && fxMode == 2 && !swallow &&
            content !is IslandContent.Idle && content !is IslandContent.Assistant && content !is IslandContent.Translate,
    )

    CompositionLocalProvider(LocalIslandFx provides (if (fxActive) fx else null)) {
    Box(
        modifier = Modifier
            // Yan baloncuktan açılış: ada baloncuğun yerinden (daire) başlar, büyürken ortaya kayar
            .graphicsLayer { if (fromNow != 0.dp) translationX = fromNow.toPx() * (1f - progress.w.value).coerceIn(0f, 1f) }
            .graphicsLayer { alpha = if (fxMode == 2 && FxBridge.islandHidden) 0f else 1f }   // ada parçalanınca gövde görünmez
            .layout { measurable, _ ->
                val hg = holdGrow.value * holdFrac
                val w = lerp(if (fromNow != 0.dp) geo.ch else geo.cw, geo.ew, max(progress.w.value, hg)).roundToPx().coerceAtLeast(1)
                val h = lerp(geo.ch, geo.eh, max(progress.h.value, hg)).roundToPx().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .onGloballyPositioned { fx.origin = it }
            .islandGlow(cfg.glowEnabled && shapeMorph < 0.02f, glow, cfg.glowIntensity)      // şekilli adada hap halesi çizilmez
            .islandNeon(cfg)              // renkli çerçeve (neon / gökkuşağı / ateş) -> IslandDecor.kt
            .islandSurface(cfg, body, shape, cornerT, if (cfg.borderArtColor && artColor != null) brightTone(artColor) else null)
            .pointerInput(Unit) {
                // Dokunma + basılı tutma. Ada küçük olduğu için hızlı dokunuşta parmak kenardan kayıp çıkabiliyordu
                // ve standart algılayıcı bunu iptal ediyordu; burada parmak ada dışına çıksa da ~40 dp'ye kadar
                // dokunuş sayılır. Daha fazla sürüklenirse iptal edilir.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (isMediaNow) warm = true
                    val holdMode = holdModeNow && progress.mean < 0.5f
                    if (holdMode) { holding = true; IslandActions.onHoldPress(true) }
                    val slop = 40.dp.toPx()
                    val swipeMin = 18.dp.toPx()
                    var outcome = 0      // 0 = basılı tutma, 1 = dokunma, 2 = iptal, 3 = dikey kaydırma
                    var swipeDir = 0
                    var swipeDirX = 0
                    withTimeoutOrNull(if (holdMode) cfgNow.more.holdExpandMs.toLong() else cfgNow.longPressMs.toLong()) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed) { outcome = 2; break }
                            if (!change.pressed) { outcome = 1; break }
                            val delta = change.position - down.position
                            if (abs(delta.y) > swipeMin && abs(delta.y) > abs(delta.x) * 1.2f) {
                                outcome = 3; swipeDir = if (delta.y > 0f) 1 else -1; break
                            }
                            if (isMediaNow && cfgNow.swipeTrackChange && abs(delta.x) > swipeMin && abs(delta.x) > abs(delta.y) * 1.2f) {
                                outcome = 4; swipeDirX = if (delta.x > 0f) 1 else -1; break
                            }
                            if (isNotifNow && abs(delta.x) > swipeMin && abs(delta.x) > abs(delta.y) * 1.2f) {
                                outcome = 5; break      // bildirim: sağa / sola kaydırma
                            }
                            if (delta.getDistance() > slop) { outcome = 2; break }
                        }
                    }
                    if (holdMode && outcome != 0) { holding = false; IslandActions.onHoldPress(false) }
                    when (outcome) {
                        0 -> {
                            if (holdMode) { IslandActions.onHoldExpand(); holding = false } else longPressNow()
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                        1 -> {
                            val c = cfgNow
                            val compact = progress.mean < 0.5f
                            val x = down.position.x
                            val artZone = min(size.height * 1.15f, size.width * 0.4f)
                            // Küçük adada albüm kapağı bölgesi; açık adada kapak / şarkı adı panelin kendi dokunuşunu işler
                            if (isMediaNow && compact && c.openCompArt && x <= artZone) openMusicNow()
                            else if (!holdMode) tapNow()      // basılı tutarak açma açıkken kısa dokunuş adayı açmaz
                        }
                        4 -> {
                            // Sağdan sola = sonraki şarkı, soldan sağa = önceki şarkı
                            commandNow(if (swipeDirX < 0) MediaCommand.Next else MediaCommand.Previous)
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                        5 -> {
                            notifSideNow()
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                        3 -> {
                            swipeNow(swipeDir)
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Albüm kapağı arka planı: ada içini kaplar, yazıların okunması için hafif karartılır
        val backdropArt = media?.art
        if (cfg.artBgEnabled && backdropArt != null && bgBoth == null && bgVideo.isEmpty() && !swallow) {
            ArtBackdrop(backdropArt, cfg.artBgCompact, progress)
        }
        // Galeriden seçilen arka plan: albüm arka planının üstünde, içeriğin altında
        if (bgVideo.isNotEmpty()) {
            VideoBackdrop(bgVideo, cfg.customBgDim)
        } else if (bgBoth != null || bgCompact != null || bgExpanded != null) {
            CustomBackdrop(bgBoth, bgCompact, bgExpanded, cfg.customBgDim, geo, progress)
        }
        // Özel ada tasarımı (Ana Sayfa): boştaki ada (ve istenirse olaylı kapalı ada) seçilen desen / fotoğrafla dolar
        val dsOn = cfg.more.designEnabled && !assistant && (!cfg.dotMode || content is IslandContent.Photo)
        val dsPhoto = rememberCustomImage(CustomImages.Slot.DESIGN, if (dsOn && cfg.more.designStyle == DESIGN_PHOTO) cfg.more.designPhotoUri else "")
        val dsFull = content is IslandContent.Idle || content is IslandContent.Photo
        val portalIdle = cfg.more.portalEnabled && content is IslandContent.Idle && !cfg.dotMode
        if (portalIdle && !swallow) {
            // Portal ada: boştaki adanın içinde eğimle kayan derinlikli sahne
            PortalScene(cfg.more.portalScene, cfg.more.portalDepth, Modifier.fillMaxSize())
        } else if (cfg.more.coverPortal && media?.art != null && !swallow && !cfg.dotMode) {
            // Kapak portalı: kısa müzik adasının içinde eğimle kayan derin albüm kapağı
            CoverPortal(media.art, Modifier.fillMaxSize().graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) })
        } else if (dsOn && !swallow && !camoOn && (dsFull || cfg.more.designAlways)) {
            DesignFill(
                cfg.more.designStyle, dsPhoto,
                Modifier.fillMaxSize().graphicsLayer { alpha = if (dsFull) 1f else (1f - progress.mean * 2f).coerceIn(0f, 1f) },
            )
        }

        // Pil azken kıvılcım (Ana Sayfa > Birleşik efektler): boştaki adanın kenarında arada bir çatırtı
        if (cfg.more.lowBattSparks && content is IslandContent.Idle && !swallow && !cfg.dotMode) {
            LowBattSparks(Modifier.fillMaxSize())
        }

        // Müzik çalarken alt kenarda albüm renginde hareketli ışık (içeriğin altında)
        if (media != null && cfg.musicBottomFx && !swallow && !cfg.dotMode) {
            MusicBottomFx(artColor ?: accent, media.playing)
        }
        // Bildirim gelince alt kenarda uygulamanın renginde hareketli ışık (Müzik efekti > Bildirimde de)
        if (content is IslandContent.Notif && content.accent != 0 && !swallow && !cfg.dotMode) {
            MusicBottomFx(Color(content.accent), true)
        }

        val musicBlur = cfg.animEnabled && ((cfg.musicBlurAnim && content is IslandContent.Media) || content is IslandContent.SoundCapsule)
        val artSpin: () -> Float = { artFlip?.spin?.value ?: 0f }
        if ((panelVisible || (warm && content is IslandContent.Media)) && !swallow) {
            Box(
                Modifier
                    .requiredSize(geo.ew, geo.eh)
                    .graphicsLayer {
                        if (!panelVisible) alpha = 0f      // yalnızca hazırlık: çizilmez
                        else if (musicBlur) musicReveal(progress.mean)
                        else alpha = ((progress.mean - 0.45f) / 0.55f).coerceIn(0f, 1f)
                    }
            ) {
                when (content) {
                    is IslandContent.Notif -> NotifContent(content, accent, rb)
                    is IslandContent.Call -> CallContent(content, accent, rb, onAnswer, onDecline, onSpeaker)
                    is IslandContent.Status ->
                        when (content.kind) {
                            StatusKind.Charging -> ChargeContent(content, rb)
                            StatusKind.Ringer -> RingerContent(content, rb, !cfg.ringerExpand, ringerPillHeightDp(cfg))
                            StatusKind.Volume -> VolumeContent(content, rb)
                            StatusKind.Network, StatusKind.Airplane, StatusKind.Hotspot -> SysPillContent(content, geo.eh.value, rb)
                            StatusKind.Focus -> if (content.phase == 1) FocusCardContent(content, geo.eh.value, cfg.more.focusToggle, rb)
                                else SysPillContent(content, geo.eh.value, rb)
                            StatusKind.Timer, StatusKind.Alarm -> if (content.phase == 3) TimerSetPanel() else TimerPanel(content, geo.eh.value, rb)
                            StatusKind.VoiceRec -> VoiceRecPanel(content, geo.eh.value)
                            StatusKind.Headphones -> HeadphoneCardContent(content, rb)
                            StatusKind.RingVolume -> RingVolumeContent(content, geo.eh.value, rb)
                            StatusKind.LowBattery -> LowBatteryPillContent(content, geo.eh.value, rb)
                            StatusKind.LowBatteryCard -> LowBatteryCardContent(content, rb)
                            else -> StatusContent(content, rb)
                        }
                    is IslandContent.Media -> MusicPanel(content, eqColor, cfg, lyrics, frame, onCommand, onOutput, customArt, onOpenMusic, artFlip, artSpin, artVideo)
                    IslandContent.Assistant -> if (cfg.assistantStyle == 1) AssistantGlassOrb() else AssistantOrb()
                    is IslandContent.Favorites -> FavoritesPanel(content, cfg.compactHeightDp.dp, onLaunch)
                    IslandContent.Translate -> TranslatePanel(cfg.compactHeightDp.dp)
                    is IslandContent.ScreenRec -> ScreenRecContent(content, onRecStop)
                    is IslandContent.SoundCapsule -> SoundCapsuleContent(content, onSoundCapsuleStop)
                    IslandContent.Photo -> Unit      // fotoğraf arka planda (DesignFill) çizilir; üstünde çentik / içerik yok
                    is IslandContent.Capsule -> CapsuleContent(content)
                    is IslandContent.Live -> if (content.kind == LiveKind.Nav) NavPanel(content, rb) else LivePanel(content, rb)
                    IslandContent.Drawer -> DrawerPanel(cfg.compactHeightDp.dp) { k -> DrawerBridge.onKey?.invoke(k) }
                    IslandContent.Idle -> Unit
                }
            }
        }

        // Baloncuktan açılırken: baloncuğun simgesi (kapak / kırmızı nokta / telefon) ada büyürken solar
        if (fromX != 0.dp && progress.mean < 0.35f) {
            Box(
                Modifier.requiredSize(geo.ch).align(Alignment.TopCenter)
                    .graphicsLayer { alpha = (1f - progress.mean * 3f).coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) { SideGlyph(content, geo.ch) }
        }

        if (cfg.dotMode && compactVisible && !swallow && content !is IslandContent.Idle) {
            // Üç nokta modu: kapalı hal bir daire (nokta); olay simgesi ortada, ada açılırken solar
            Box(
                Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)
                    .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) { SideGlyph(content, geo.ch) }
        }

        if (content is IslandContent.Status && (content.kind == StatusKind.Camera || content.kind == StatusKind.Mic) && compactVisible && !cfg.dotMode) {
            // Normal ada: kapalı halde kamera deliğinin sağında yeşil (kamera) / turuncu (mikrofon) nokta
            Box(
                Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)
                    .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
                contentAlignment = Alignment.Center,
            ) {
                // iOS gibi: adanın sağ ucunda değil, ortadaki kamera deliğinin hemen sağında (adanın içinde, biraz sağda)
                val dotX = (geo.ch * 0.85f).coerceAtMost(geo.cw / 2f - geo.ch * 0.35f)
                Box(Modifier.offset(x = dotX).size(8.dp).background(statusColor(content), RoundedCornerShape(50)))
            }
        }

        if (content is IslandContent.Call && cfg.pillCallStyle && compactVisible && !cfg.dotMode) {
            // Hap tasarımı (küçültülmüş ada): solda yeşil, sağda kırmızı düğme, ortada süre / isim + ses dalgası
            Box(
                Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)
                    .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
            ) {
                PillScaled(geo.cw, geo.ch, PILL_CALL_H_DP.dp, PILL_CALL_MIN_W_DP.dp) {
                    CallPillContent(content, rb, onAnswer, onDecline, onSpeaker)
                }
            }
        }

        if (content is IslandContent.Call && !content.isIncoming && !cfg.pillCallStyle && compactVisible && !cfg.dotMode) {
            // Küçültülmüş aktif arama: solda yeşil telefon + süre, sağda renkli ses dalgası
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                val fade = Modifier.graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) }
                Row(
                    Modifier.fillMaxSize().padding(start = 9.dp, end = 14.dp).then(fade),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhoneIcon(0f, Modifier.size(if (cfg.more.callIconOnly) 14.dp else 12.dp), CallGreen)
                    if (!cfg.more.callIconOnly) {
                        // Ayar kapalıysa: telefonun yanında süre, sağda ses dalgası
                        Spacer(Modifier.width(4.dp))
                        Text(
                            content.duration ?: "0:00", color = CallGreen, fontWeight = FontWeight.Medium,
                            fontSize = 12.sp, maxLines = 1,
                        )
                        Spacer(Modifier.weight(1f))
                        CallWave(Modifier.width(34.dp).height((geo.ch - 16.dp).coerceAtLeast(10.dp)), allGreen = cfg.more.callWaveGreen)
                    }
                }
            }
        }

        if (content is IslandContent.Notif && compactVisible && !cfg.dotMode && cfg.notifInline) {
            // "Adayı genişletme": bildirim kapalı adanın içinde. Solda uygulama simgesi, yanında gönderen (kalın) + bildirim yazısı.
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                val sz = (geo.ch - 10.dp).coerceAtLeast(14.dp)
                val titleSp = (geo.ch.value * 0.31f).coerceIn(7f, 12f)
                val textSp = (titleSp - 1f).coerceAtLeast(6.5f)
                val ic = content.icon
                Row(
                    Modifier.fillMaxSize().padding(start = 7.dp, end = 12.dp)
                        .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    if (ic != null) {
                        Image(bitmap = ic, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(sz).clip(RoundedCornerShape(sz * 0.26f)))
                    } else {
                        Box(Modifier.size(sz).background(accent, RoundedCornerShape(sz * 0.26f)), contentAlignment = Alignment.Center) {
                            Text(content.title.trim().take(1).uppercase().ifEmpty { "•" }, color = Pal.c("island_letter"),
                                fontWeight = FontWeight.Bold, fontSize = (titleSp + 1f).sp)
                        }
                    }
                    val hasTitle = content.title.isNotBlank()
                    val hasText = content.text.isNotBlank()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                        if (hasTitle) {
                            Text(content.title, color = Pal.c("island_text"), fontWeight = FontWeight.Bold, fontSize = titleSp.sp,
                                lineHeight = (titleSp + 1.5f).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.rainbowFill(rb))
                        }
                        if (hasText) {
                            Text(content.text.replace('\n', ' '), color = Pal.c("island_text_dim"), fontSize = textSp.sp,
                                lineHeight = (textSp + 1.5f).sp, maxLines = if (hasTitle) 1 else 2, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.rainbowFill(rb))
                        }
                    }
                }
            }
        } else if (content is IslandContent.Notif && compactVisible && !cfg.dotMode) {
            // Küçültülmüş bildirim: solda uygulamanın simgesi (küçülürken görünür)
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                val sz = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                val ic = content.icon
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(sz)
                        .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
                    contentAlignment = Alignment.Center,
                ) {
                    if (ic != null) {
                        Image(bitmap = ic, contentDescription = null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(sz * 0.28f)))
                    } else {
                        Box(Modifier.fillMaxSize().background(accent, RoundedCornerShape(sz * 0.28f)), contentAlignment = Alignment.Center) {
                            Text(content.title.trim().take(1).uppercase().ifEmpty { "•" }, color = Pal.c("island_letter"), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (compactVisible && !swallow && hasXCompact(content, cfg)) {
            // Yeni kapalı ada tasarımları (zamanlayıcı, ses kaydı, kulaklık, küçültülmüş gelen arama, logolu skor, navigasyon)
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                XCompactLayer(content, cfg, geo.cw, geo.ch) { (1f - progress.mean * 2f).coerceIn(0f, 1f) }
            }
        }

        if (content is IslandContent.Live && compactVisible && !cfg.dotMode && !hasXCompact(content, cfg)) {
            // Canlı etkinlik (navigasyon / sipariş): solda ok ya da uygulama simgesi, sağda mesafe / kalan süre
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                LiveCompact(content, Modifier.fillMaxSize().graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) })
            }
        }

        if (content is IslandContent.ScreenRec && compactVisible && !cfg.dotMode) {
            // Kapalı ada: kayıtta solda yavaşça yanıp sönen kırmızı nokta; geri sayımda halka + rakam; işlenirken dönen halka
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                val fade = Modifier.graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) }
                ScreenRecCompact(content, Modifier.fillMaxSize().then(fade))
            }
        }

        if (content is IslandContent.SoundCapsule && compactVisible && !cfg.dotMode) {
            // Kapalı ada: solda SoundCapsule logosu, sağda mavi ses dalgası. Ada genişlerken ikisi de bulanıp kaybolur.
            // Bulundu görünümü küçülürken (açıldıktan >1,5 sn sonra) logo yerine şarkının kapağı görünür.
            val shz = remember { SoundCapsuleHold() }
            if (content.phase == SoundCapsulePhase.Listening) shz.foundAt = 0L
            else if (shz.foundAt == 0L) shz.foundAt = android.os.SystemClock.elapsedRealtime()
            val expandingNow = content.phase == SoundCapsulePhase.Listening ||
                android.os.SystemClock.elapsedRealtime() - shz.foundAt < 1_500L
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                val fade = Modifier.graphicsLayer {
                    val c = (1f - progress.mean * 2f).coerceIn(0f, 1f)
                    alpha = c
                    if (cfg.animEnabled) musicCompactBlur(c)
                }
                val sz = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                val cover = content.cover
                if (expandingNow || cover == null) {
                    SoundCapsuleGlyphSweep(Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(sz).then(fade))
                } else {
                    Image(
                        bitmap = cover, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 9.dp).size(sz)
                            .clip(RoundedCornerShape(sz * 0.28f)).then(fade),
                    )
                }
                if (expandingNow) {
                    SoundCapsuleWave(
                        Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
                            .width(22.dp).height((geo.ch - 16.dp).coerceAtLeast(10.dp)).then(fade),
                    )
                }
            }
        }

        if (content is IslandContent.Idle && compactVisible && !cfg.dotMode && cfg.islandPet != 0) {
            // Boştaki ada: içinde dolaşan küçük karakter -> IslandDecor.kt
            Box(
                Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)
                    .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) },
            ) {
                IslandPet(cfg.islandPet, Modifier.fillMaxSize())
            }
        }

        // Yutma efekti sırasında kapalı adanın içeriği (kapak, equalizer, sözler) görünmez; efekt bitince yumuşakça gelir
        val swallowAlpha = animateFloatAsState(
            if (swallow) 0f else 1f,
            if (swallow) snap() else tween(240, delayMillis = 60),
            label = "swallowAlpha",
        )
        val midOn = cfg.more.musicMid && !cfg.dotMode
        if (content is IslandContent.Media && midOn && compactVisible && !swallow) {
            // Orta boy müzik adası (iOS tarzı): kapak + şarkı + dalga, ilerleme çubuğu ve kontroller; ada bu boyutta açık kalır
            Box(
                Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)
                    .graphicsLayer {
                        val c = (1f - progress.mean * 2f).coerceIn(0f, 1f)
                        alpha = c * swallowAlpha.value
                        if (musicBlur) musicCompactBlur(c)
                    },
            ) {
                MusicMidPanel(content, eqColor, cfg, onCommand, onOutput, customArt, onOpenMusic, artFlip, artSpin, artVideo)
            }
        }
        if (content is IslandContent.Media && cfg.pillMusicStyle && !midOn && compactVisible && !swallow && !cfg.dotMode) {
            // Hap tasarımı (küçültülmüş ada): solda kapak, ortada başlık / dalga / ilerleme, sağda kontrol düğmeleri
            Box(
                Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)
                    .graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) * swallowAlpha.value },
            ) {
                PillScaled(geo.cw, geo.ch, PILL_MUSIC_H_DP.dp, PILL_MUSIC_MIN_W_DP.dp) {
                    MusicPillPanel(content, cfg, onCommand, customArt, onOpenMusic, artFlip, artSpin, artVideo)
                }
            }
        }
        if (content is IslandContent.Media && !cfg.pillMusicStyle && !midOn && compactVisible && !swallow && !cfg.dotMode) {
            // Sabit (kapalı ada) genişliğinde kutu: ada küçülürken kapak ve equalizer kenarlarla birlikte kaymaz
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).then(holdScaleMod)) {
                val fade = Modifier.graphicsLayer {
                    val c = (1f - progress.mean * 2f).coerceIn(0f, 1f)
                    alpha = if (swallow) 0f else c * swallowAlpha.value
                    if (musicBlur) musicCompactBlur(c)
                }
                val artSize = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                val shownArt = customArt ?: content.art ?: artFlip?.held
                val artShown = shownArt != null || artVideo.isNotEmpty()
                if (artShown) {
                    FlipAlbumArt(
                        shownArt, artSize, artFlip, artSpin, 7.dp, customArt == null && artVideo.isEmpty(),
                        Modifier.align(Alignment.CenterStart).padding(start = 8.dp).then(fade),
                        video = artVideo,
                    )
                }
                if (compactLyrics && cfg.lyricsEnabled && cfg.lyricsCompact && lyrics is LyricsState.Ready) {
                    // Albüm kapağı ile equalizer arasındaki orta boşluk
                    val startPad = if (artShown) 8.dp + artSize + 8.dp else 14.dp
                    val endPad = 10.dp + 23.dp + 8.dp
                    CompactLyrics(
                        cfg, lyrics, frame,
                        Modifier.align(Alignment.TopCenter).padding(start = startPad, end = endPad)
                            .fillMaxWidth().height(geo.ch).then(fade),
                        artColor = content.artColor,
                    )
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).then(fade)) {
                    if (content.playing) MusicMeter(eqColor, cfg.eqRing, rainbow = rb)
                    else Box(Modifier.size(6.dp).background(Pal.c("island_dot_idle"), CircleShape))
                }

            }
        }
        if (fxActive) FxLayer(fx, cfg, progress, live = fxMode == 2, bodyColor = { body.value })
    }
    }
}

/** Kapağı ada içine net (bulanıklaştırılmamış) çizer; yazılar okunsun diye üstüne koyu katman biner. */
@Composable
internal fun ArtBackdrop(art: ImageBitmap, showInCompact: Boolean, progress: IslandProgress) {
    Box(
        Modifier.fillMaxSize().graphicsLayer {
            // Kapalı adada kapalıysa: ada açılırken belirir, küçülürken solar
            alpha = if (showInCompact) 1f else ((progress.mean - 0.25f) / 0.6f).coerceIn(0f, 1f)
        }
    ) {
        Image(
            bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low, modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Pal.c("island_scrim").copy(alpha = 0.50f)))
    }
}

/**
 * Galeriden seçilen fotoğraf(lar) ada içini kaplar.
 * - Tek fotoğraf: ada büyürken/küçülürken kesintisiz ölçeklenir.
 * - Ayrı fotoğraflar: kapalı hal ve açık hal kendi boyutunda çizilir, geçişte çapraz solar.
 *   Bir hal için fotoğraf seçilmemişse o halde özel arka plan çizilmez.
 */
@Composable
private fun CustomBackdrop(
    both: ImageBitmap?, compact: ImageBitmap?, expanded: ImageBitmap?,
    dim: Float, geo: Geo, progress: IslandProgress,
) {
    @Composable
    fun Pic(img: ImageBitmap, modifier: Modifier) {
        Box(modifier) {
            Image(
                bitmap = img, contentDescription = null, contentScale = ContentScale.Crop,
                filterQuality = FilterQuality.Medium, modifier = Modifier.fillMaxSize(),
            )
            Box(Modifier.fillMaxSize().background(Pal.c("island_scrim").copy(alpha = dim.coerceIn(0f, 0.9f))))
        }
    }
    Box(Modifier.fillMaxSize()) {
        if (both != null) {
            Pic(both, Modifier.fillMaxSize())
        } else {
            if (compact != null) {
                Pic(
                    compact,
                    Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter).graphicsLayer {
                        // Açık ada için fotoğraf varsa altta sabit kalır (üstüne açık fotoğraf biner), yoksa açılırken solar
                        alpha = if (expanded != null) 1f else (1f - progress.mean * 2f).coerceIn(0f, 1f)
                    },
                )
            }
            if (expanded != null) {
                Pic(
                    expanded,
                    Modifier.requiredSize(geo.ew, geo.eh).align(Alignment.TopCenter).graphicsLayer {
                        alpha = (progress.mean / 0.6f).coerceIn(0f, 1f)
                    },
                )
            }
        }
    }
}

@Composable
private fun NotifContent(c: IslandContent.Notif, accent: Color, rb: RainbowStyle?) {
    if (c.chips.isEmpty()) { NotifBody(c, accent, rb, Modifier.fillMaxSize()); return }
    // Akıllı bildirim: altta ertele / kopyala çipleri
    Column(Modifier.fillMaxSize()) {
        NotifBody(c, accent, rb, Modifier.fillMaxWidth().weight(1f))
        NotifChipRow(c, Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 10.dp).height(28.dp))
    }
}

@Composable
private fun NotifBody(c: IslandContent.Notif, accent: Color, rb: RainbowStyle?, mod: Modifier) {
    val iconShape = RoundedCornerShape(13.dp)
    Row(
        mod.padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val icon = c.icon
        if (icon != null) {
            Image(
                bitmap = icon, contentDescription = null,
                modifier = Modifier.size(46.dp).clip(iconShape)
                    .border(1.dp, Pal.c("art_border"), iconShape),
            )
        } else {
            Box(Modifier.size(46.dp).background(accent, iconShape), contentAlignment = Alignment.Center) {
                Text(
                    c.title.trim().take(1).uppercase().ifEmpty { "•" },
                    color = Pal.c("island_letter"), fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.rainbowFill(rb, 0.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (c.title.isNotBlank() || c.translatedFrom.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.title, color = Pal.c("island_text"), fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false).rainbowFill(rb))
                if (c.translatedFrom.isNotEmpty()) {
                    // Bildirim çevirisi: küçük "İngilizce → çevrildi" etiketi
                    Text("  ${langNameTr(c.translatedFrom)} · çevrildi", color = Pal.c("island_text_dim"), fontSize = 11.sp, maxLines = 1)
                }
            }
            if (c.text.isNotBlank()) {
                Text(c.text, color = Pal.c("island_text_dim"), fontSize = 13.5.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
        }
    }
}

internal fun formatRemaining(ms: Long): String {
    val t = ((ms + 999) / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Şarj görünümü: kapalı adadan biraz geniş, tek satır. Solda yazı, sağda yeşil yüzde + pil. */
const val CHARGE_PILL_W_DP = 220f
/** Şarj hapı genişliği (ayarlanabilir; en az kapalı ada kadar). */
fun chargePillWidthDp(cfg: IslandSettings): Float = maxOf(cfg.more.chargePillW, cfg.compactWidthDp)
const val REC_PILL_W_DP = 280f      // ekran kaydı: açık hal genişliği
const val REC_PILL_H_DP = 72f       // ekran kaydı: açık hal yüksekliği
const val REC_WIDE_MARGIN_DP = 10f  // ekran kaydı tam genişlik: ekranın sağ/sol kenarından boşluk

/** Ekran kaydı açık ada genişliği (dp). Servis ve arayüz aynı hesabı kullanır. */
fun recExpandedWidthDp(cfg: IslandSettings, screenWDp: Float): Float =
    if (cfg.screenRecWide) (screenWDp - 2f * REC_WIDE_MARGIN_DP).coerceAtLeast(REC_PILL_W_DP)
    else maxOf(REC_PILL_W_DP, cfg.compactWidthDp)
fun chargePillHeightDp(cfg: IslandSettings): Float = cfg.compactHeightDp + 4f

/**
 * Zil / sessiz hapı (iOS ekran kaydından ölçüldü): açık hal ekran genişliğinin ~%64'ü (Zil) / ~%70'i (Sessiz, Titreşim),
 * yüksekliği kapalı adanın ~1,43 katı. Genişleme kapalıysa ada boyutunu değiştirmez.
 */
fun ringerPillWidthDp(cfg: IslandSettings, longText: Boolean, screenWDp: Float): Float =
    if (!cfg.ringerExpand) cfg.compactWidthDp
    else (screenWDp * cfg.ringerWidthPct / 100f).coerceIn(cfg.compactWidthDp, (screenWDp - 12f).coerceAtLeast(cfg.compactWidthDp))
fun ringerPillHeightDp(cfg: IslandSettings): Float =
    if (cfg.ringerExpand) maxOf(cfg.compactHeightDp, cfg.compactHeightDp * cfg.ringerHeightPct / 100f) else cfg.compactHeightDp


// ---------------------------------------------------------------- Kontrast ada / şarkının havası
/** Kontrast ada: çevrenin zıt rengi; orta tonlarda da belirgin olsun diye parlaklık uca itilir. */
internal fun contrastOf(argb: Int): Color {
    val r = 255 - ((argb shr 16) and 0xFF); val g = 255 - ((argb shr 8) and 0xFF); val b = 255 - (argb and 0xFF)
    val hsv = FloatArray(3)
    android.graphics.Color.RGBToHSV(r, g, b, hsv)
    hsv[2] = if (hsv[2] >= 0.5f) maxOf(hsv[2], 0.92f) else minOf(hsv[2], 0.10f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/**
 * Şarkının havası: kapak rengine göre hava efekti (1 yağmur, 2 kar, 3 asit yağmuru, 4 güneş parıltısı).
 * Beyazımsı kapak → kar; çok koyu, gri / soluk, mavi ya da mor kapak → yağmur (çok koyuysa şimşekli);
 * canlı yeşil → asit yağmuru; sıcak renkler (kırmızı, turuncu, sarı, pembe) → güneş parıltısı.
 */
internal fun moodWeatherFor(argb: Int): Int {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    val h = hsv[0]; val s = hsv[1]; val v = hsv[2]
    return when {
        s < 0.18f && v > 0.70f -> 2          // beyazımsı: kar
        v < 0.22f -> 1                        // çok koyu: yağmur
        s < 0.25f -> 1                        // gri / soluk: yağmur
        h in 75f..160f && s > 0.45f -> 3      // canlı yeşil: asit yağmuru
        h in 160f..300f -> 1                  // mavi, mor: yağmur
        else -> 4                             // sıcak renkler: güneş
    }
}

internal fun isDarkArt(argb: Int): Boolean {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, hsv)
    return hsv[2] < 0.35f
}
