package com.example.dynamicisland

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.max
import kotlin.math.min

const val MUSIC_EXTRA_DP = 100f

/** Aktif aramada küçültülmüş adanın genişliği (telefon simgesi + süre + ses dalgası sığsın). */
const val CALL_COMPACT_W_DP = 160f

@Immutable
data class Geo(val cw: Dp, val ch: Dp, val ew: Dp, val eh: Dp)

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

private fun specFor(cfg: IslandSettings, expanding: Boolean, isWidth: Boolean): FiniteAnimationSpec<Float> {
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

private const val EXPAND_LEAD_MS = 70L

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
) {
    val s by state.collectAsState()
    val cfg by settingsFlow.collectAsState()
    val lyrics by lyricsFlow.collectAsState()
    val content = s.content
    val media = content as? IslandContent.Media
    // Açılırken animasyonu çok kısa geciktir: pencere önce büyüsün, animasyonun ilk (en hızlı) kareleri
    // küçük pencereye çizilip kırpılmasın ("yaylı" animasyonda açılışta takılma). Kapanış gecikmesizdir.
    val wantExpanded = s.expanded   // arama: açık/küçük durumunu servis belirler (gelen arama hep açık)
    var animExpanded by remember { mutableStateOf(wantExpanded) }
    LaunchedEffect(wantExpanded) {
        if (wantExpanded && cfg.animEnabled && !s.swallow) delay(EXPAND_LEAD_MS)
        animExpanded = wantExpanded
    }
    val progress = rememberIslandProgress(cfg, animExpanded, s.swallow)
    val lyricsOn = cfg.lyricsEnabled && (cfg.lyricsInPanel || cfg.lyricsCompact)
    val frame = rememberLyricFrame(
        media, lyrics, cfg.lyricsOffsetMs.toLong(),
        active = lyricsOn && lyrics is LyricsState.Ready, fine = cfg.lyricsFillEnabled,
    )

    // Tam genişlik asistan: ada kutusu yok, pencere ekran genişliğinde; animasyon doğrudan çizilir
    if (content is IslandContent.Assistant && cfg.assistantFullWidth) {
        var shown by remember { mutableStateOf(false) }
        LaunchedEffect(s.expanded) { shown = s.expanded }
        val barAlpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (shown) 350 else 250), label = "orbBar")
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = barAlpha }) { AssistantOrb(fullWidth = true) }
        return
    }

    // Pencere, ada + parıltı payı kadar sabit boyutlu; ada üst-ortada büyür/küçülür
    Box(Modifier.fillMaxSize().padding(cfg.windowPadDp().dp), contentAlignment = Alignment.TopCenter) {
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
            if (content is IslandContent.Call) geo = geo.copy(cw = maxOf(cw, CALL_COMPACT_W_DP.dp), eh = if (content.isIncoming) 74.dp else 88.dp)
            if (content is IslandContent.Status && content.kind == StatusKind.Charging) {
                geo = geo.copy(ew = CHARGE_PILL_W_DP.dp, eh = chargePillHeightDp(cfg).dp)
            }
            if (s.swallow && content is IslandContent.Media) {
                val sz = swallowSizeDp(geo.cw.value, geo.ch.value, geo.ew.value, geo.eh.value)
                geo = geo.copy(ew = sz.first.dp, eh = sz.second.dp)
            }
            IslandPill(content, progress, geo, cfg, lyrics, frame, s.compactLyrics, onTap, onCommand, onOutput, onLongPress, onLaunch, onAnswer, onDecline, onSpeaker, swallow = s.swallow)
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
private fun brightTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 0.85f).coerceIn(0f, 0.8f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Seçilen vurgu rengiyle boyanan ada gövdesi: çok koyu seçildiyse siyah kalır. */
private fun accentBody(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    return if (hsv[2] < 0.15f) Color.Black else darkTone(c)
}

private fun bodyColorFor(cfg: IslandSettings, art: Color?, accent: Color): Color {
    val tint: Color? = if (cfg.dynamicColor) art else null
    return when {
        cfg.glassEnabled -> (tint ?: accent).copy(alpha = cfg.glassAlpha)
        tint != null -> darkTone(tint)
        else -> accentBody(accent)       // albüm rengi kapalı / kapak yok: ada seçilen renge boyanır
    }
}

/** Panel animasyonlarının rengi = adanın o anki gövde rengi (cam ise siyah zemin üstünde birleşmiş hali, opak). */
fun islandPanelColor(cfg: IslandSettings, artArgb: Int): Color {
    val art = if (artArgb != 0) Color(artArgb) else null
    val c = bodyColorFor(cfg, art, Color(cfg.accentArgb))
    return if (c.alpha < 1f) androidx.compose.ui.graphics.lerp(Color.Black, c.copy(alpha = 1f), c.alpha) else c
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
            0f to Color.White.copy(0.17f),
            0.40f to Color.White.copy(0.04f),
            0.72f to Color.Transparent,
            1f to Color.White.copy(0.09f),
        )
    )
    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.22f),
            0.6f to Color.White.copy(0.05f),
            1f to Color.Transparent,
            center = Offset(w * 0.20f, h * 0.02f),
            radius = max(w * 0.60f, h),
        )
    )
    // Alt-sağ köşede çok hafif ikincil parlama (ışığın cam içinden geçip toplandığı yer)
    drawRect(
        Brush.radialGradient(
            0f to Color.White.copy(0.08f),
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
            color = Color.White.copy(alpha = a),
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
        0f to Color.White.copy(0.90f),
        0.28f to Color.White.copy(0.20f),
        0.62f to Color.White.copy(0.07f),
        1f to Color.White.copy(0.55f),
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
        color = Color.White.copy(alpha = 0.10f),
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
                0.35f to Color.White.copy(0.55f),
                0.65f to Color.White.copy(0.30f),
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
private fun Modifier.islandSurface(cfg: IslandSettings, body: State<Color>, shape: Shape = PillShape, cornerT: Float = -1f): Modifier {
    val liquid = cfg.glassEnabled && cfg.liquidEnabled
    var m = this.clip(shape).drawBehind { drawRect(body.value) }
    if (liquid) m = m.drawBehind { drawLiquidBase(cfg.liquidSpecular, cornerT) }
    if (liquid) m = m.liquidBackdrop(cfg.liquidRefraction, cfg.liquidDispersion, cornerT)
    else if (cfg.glassEnabled) m = m.drawBehind { drawGlassBase(cornerT) }
    if (cfg.borderEnabled) {
        val rbBorder = rememberRainbowStyle(cfg)
        m = if (rbBorder == null) m.border(cfg.borderWidthDp.dp, Color(cfg.borderColorArgb), shape)
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
private fun Modifier.islandGlow(enabled: Boolean, color: State<Color>, intensity: Float): Modifier =
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
) {
    // Dokunma ve basılı tutma: lambdalar güncellense de hareket algılayıcısı yeniden kurulmaz
    val tapNow by rememberUpdatedState(onTap)
    val longPressNow by rememberUpdatedState(onLongPress)
    // Asistan modunda: kendi şekli; cam ve sıvı cam ayarları uygulanır. Çerçeve / dış parıltı kapalı (şekle uymaz).
    val assistant = content is IslandContent.Assistant
    val cfg = if (assistant) cfg.copy(borderEnabled = false, glowEnabled = false) else cfg
    val cornerT = if (assistant) progress.mean else -1f
    val shape: Shape = if (assistant) AssistantShape(cornerT) else PillShape
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
    val customArt = rememberCustomImage(CustomImages.Slot.ART, if (cfg.customArtEnabled) cfg.customArtUri else "")

    // Asistanda cam gövde en az %60 koyu kalır: renk bantları koyu zeminde okunur
    val bodyTarget = if (assistant && cfg.glassEnabled) Color.Black.copy(alpha = max(cfg.glassAlpha, 0.6f)) else bodyColorFor(cfg, artColor, accent)
    // Yutma efektinde ada rengi anında hedef renge geçer: panel animasyonuyla (aynı renk) uyumlu olsun
    val bodyAnim = animateColorAsState(bodyTarget, if (swallow) snap() else tween(450), label = "body")
    val body = remember(swallow, bodyTarget, bodyAnim) { derivedStateOf { if (swallow) bodyTarget else bodyAnim.value } }
    val glow = animateColorAsState(artColor ?: accent, tween(450), label = "glow")

    val panelVisible by remember(progress) { derivedStateOf { max(progress.w.value, progress.h.value) > 0.01f } }
    val compactVisible by remember(progress) { derivedStateOf { progress.mean < 0.5f } }

    Box(
        modifier = Modifier
            .layout { measurable, _ ->
                val w = lerp(geo.cw, geo.ew, progress.w.value).roundToPx().coerceAtLeast(1)
                val h = lerp(geo.ch, geo.eh, progress.h.value).roundToPx().coerceAtLeast(1)
                val placeable = measurable.measure(Constraints.fixed(w, h))
                layout(w, h) { placeable.place(0, 0) }
            }
            .islandGlow(cfg.glowEnabled, glow, cfg.glowIntensity)
            .islandSurface(cfg, body, shape, cornerT)
            .pointerInput(Unit) {
                // Dokunma + basılı tutma. Ada küçük olduğu için hızlı dokunuşta parmak kenardan kayıp çıkabiliyordu
                // ve standart algılayıcı bunu iptal ediyordu; burada parmak ada dışına çıksa da ~40 dp'ye kadar
                // dokunuş sayılır. Daha fazla sürüklenirse iptal edilir.
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val slop = 40.dp.toPx()
                    var outcome = 0      // 0 = basılı tutma, 1 = dokunma, 2 = iptal
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (change == null || change.isConsumed) { outcome = 2; break }
                            if (!change.pressed) { outcome = 1; break }
                            if ((change.position - down.position).getDistance() > slop) { outcome = 2; break }
                        }
                    }
                    when (outcome) {
                        0 -> {
                            longPressNow()
                            do {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            } while (c != null && c.pressed)
                        }
                        1 -> tapNow()
                    }
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        // Albüm kapağı arka planı: ada içini kaplar, yazıların okunması için hafif karartılır
        val backdropArt = media?.art
        if (cfg.artBgEnabled && backdropArt != null && bgBoth == null && !swallow) {
            ArtBackdrop(backdropArt, cfg.artBgCompact, progress)
        }
        // Galeriden seçilen arka plan: albüm arka planının üstünde, içeriğin altında
        if (bgBoth != null || bgCompact != null || bgExpanded != null) {
            CustomBackdrop(bgBoth, bgCompact, bgExpanded, cfg.customBgDim, geo, progress)
        }

        if (panelVisible && !swallow) {
            Box(
                Modifier
                    .requiredSize(geo.ew, geo.eh)
                    .graphicsLayer { alpha = ((progress.mean - 0.45f) / 0.55f).coerceIn(0f, 1f) }
            ) {
                when (content) {
                    is IslandContent.Notif -> NotifContent(content, accent, rb)
                    is IslandContent.Call -> CallContent(content, accent, rb, onAnswer, onDecline, onSpeaker)
                    is IslandContent.Status ->
                        if (content.kind == StatusKind.Charging) ChargeContent(content, rb) else StatusContent(content, rb)
                    is IslandContent.Media -> MusicPanel(content, eqColor, cfg, lyrics, frame, onCommand, onOutput, customArt)
                    IslandContent.Assistant -> AssistantOrb()
                    is IslandContent.Favorites -> FavoritesPanel(content, cfg.compactHeightDp.dp, onLaunch)
                    IslandContent.Idle -> Unit
                }
            }
        }

        if (content is IslandContent.Call && !content.isIncoming && compactVisible) {
            // Küçültülmüş aktif arama: solda yeşil telefon + süre, sağda renkli ses dalgası
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val fade = Modifier.graphicsLayer { alpha = (1f - progress.mean * 2f).coerceIn(0f, 1f) }
                Row(
                    Modifier.fillMaxSize().padding(start = 9.dp, end = 14.dp).then(fade),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PhoneIcon(0f, Modifier.size(12.dp), CallGreen)
                    Spacer(Modifier.width(4.dp))
                    Text(
                        content.duration ?: "0:00", color = CallGreen, fontWeight = FontWeight.Medium,
                        fontSize = 12.sp, maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    CallWave(Modifier.width(34.dp).height((geo.ch - 16.dp).coerceAtLeast(10.dp)))
                }
            }
        }

        // Yutma efekti sırasında kapalı adanın içeriği (kapak, equalizer, sözler) görünmez; efekt bitince yumuşakça gelir
        val swallowAlpha = animateFloatAsState(
            if (swallow) 0f else 1f,
            if (swallow) snap() else tween(240, delayMillis = 60),
            label = "swallowAlpha",
        )
        if (content is IslandContent.Media && compactVisible && !swallow) {
            // Sabit (kapalı ada) genişliğinde kutu: ada küçülürken kapak ve equalizer kenarlarla birlikte kaymaz
            Box(Modifier.requiredSize(geo.cw, geo.ch).align(Alignment.TopCenter)) {
                val fade = Modifier.graphicsLayer { alpha = if (swallow) 0f else (1f - progress.mean * 2f).coerceIn(0f, 1f) * swallowAlpha.value }
                val artSize = (geo.ch - 12.dp).coerceAtLeast(14.dp)
                val shownArt = customArt ?: content.art
                shownArt?.let { art ->
                    Image(
                        bitmap = art, contentDescription = null, contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Medium,
                        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp)
                            .size(artSize).clip(RoundedCornerShape(7.dp)).then(fade),
                    )
                }
                if (compactLyrics && cfg.lyricsEnabled && cfg.lyricsCompact && lyrics is LyricsState.Ready) {
                    // Albüm kapağı ile equalizer arasındaki orta boşluk
                    val startPad = if (shownArt != null) 8.dp + artSize + 8.dp else 14.dp
                    val endPad = 10.dp + 23.dp + 8.dp
                    CompactLyrics(
                        cfg, lyrics, frame,
                        Modifier.align(Alignment.TopCenter).padding(start = startPad, end = endPad)
                            .fillMaxWidth().height(geo.ch).then(fade),
                        artColor = content.artColor,
                    )
                }
                Box(Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).then(fade)) {
                    if (content.playing) Equalizer(eqColor, rainbow = rb)
                    else Box(Modifier.size(6.dp).background(Color.White.copy(alpha = 0.40f), CircleShape))
                }

            }
        }
    }
}

/** Kapağı ada içine net (bulanıklaştırılmamış) çizer; yazılar okunsun diye üstüne koyu katman biner. */
@Composable
private fun ArtBackdrop(art: ImageBitmap, showInCompact: Boolean, progress: IslandProgress) {
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
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.50f)))
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
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 0.9f))))
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
    val iconShape = RoundedCornerShape(13.dp)
    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val icon = c.icon
        if (icon != null) {
            Image(
                bitmap = icon, contentDescription = null,
                modifier = Modifier.size(46.dp).clip(iconShape)
                    .border(1.dp, Color.White.copy(alpha = 0.10f), iconShape),
            )
        } else {
            Box(Modifier.size(46.dp).background(accent, iconShape), contentAlignment = Alignment.Center) {
                Text(
                    c.title.trim().take(1).uppercase().ifEmpty { "•" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    modifier = Modifier.rainbowFill(rb, 0.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (c.title.isNotBlank()) {
                Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
            if (c.text.isNotBlank()) {
                Text(c.text, color = Color.White.copy(alpha = 0.70f), fontSize = 13.5.sp, lineHeight = 18.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
        }
    }
}

// ---------------------------------------------------------------- Durum (zil modu / şarj / zamanlayıcı / alarm)
private const val BELL_PATH =
    "M12,22c1.1,0 2,-0.9 2,-2h-4c0,1.1 0.89,2 2,2zM18,16v-5c0,-3.07 -1.64,-5.64 -4.5,-6.32V4c0,-0.83 -0.67,-1.5 -1.5,-1.5" +
    "s-1.5,0.67 -1.5,1.5v0.68C7.63,5.36 6,7.92 6,11v5l-2,2v1h16v-1l-2,-2z"
private val BellPath: Path by lazy { PathParser().parsePathString(BELL_PATH).toPath() }

private fun statusColor(c: IslandContent.Status): Color = when (c.kind) {
    StatusKind.Ringer -> if (c.silent) Color(0xFFFF3B30) else Color(0xFFFF9500)
    StatusKind.Charging -> Color(0xFF34C759)
    StatusKind.Timer -> Color(0xFF0A84FF)
    StatusKind.Alarm -> Color(0xFFFF9500)
}

@Composable
private fun StatusIcon(c: IslandContent.Status, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({ scale(sc, sc, Offset.Zero) }) {
            val w = Color.White
            val st = Stroke(width = 2f, cap = StrokeCap.Round)
            when (c.kind) {
                StatusKind.Ringer -> {
                    drawPath(BellPath, w)
                    if (c.silent) drawLine(w, Offset(3.5f, 3.5f), Offset(20.5f, 20.5f), strokeWidth = 2.4f, cap = StrokeCap.Round)
                }
                StatusKind.Charging -> {
                    val bolt = Path().apply {
                        moveTo(13.5f, 2f); lineTo(5f, 13.5f); lineTo(11f, 13.5f)
                        lineTo(10f, 22f); lineTo(19f, 9.5f); lineTo(13f, 9.5f); close()
                    }
                    drawPath(bolt, w)
                }
                StatusKind.Timer -> {
                    drawCircle(w, radius = 8f, center = Offset(12f, 13.5f), style = st)
                    drawLine(w, Offset(12f, 13.5f), Offset(12f, 9f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(w, Offset(9.5f, 2.5f), Offset(14.5f, 2.5f), strokeWidth = 2f, cap = StrokeCap.Round)
                }
                StatusKind.Alarm -> {
                    drawCircle(w, radius = 7.5f, center = Offset(12f, 13f), style = st)
                    drawLine(w, Offset(12f, 13f), Offset(12f, 9.2f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawLine(w, Offset(12f, 13f), Offset(14.6f, 14.6f), strokeWidth = 2f, cap = StrokeCap.Round)
                    drawCircle(w, radius = 2.4f, center = Offset(4.6f, 4.8f))
                    drawCircle(w, radius = 2.4f, center = Offset(19.4f, 4.8f))
                }
            }
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val t = ((ms + 999) / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Şarj görünümü: kapalı adadan biraz geniş, tek satır. Solda yazı, sağda yeşil yüzde + pil. */
const val CHARGE_PILL_W_DP = 220f
fun chargePillHeightDp(cfg: IslandSettings): Float = cfg.compactHeightDp + 4f

@Composable
private fun ChargeContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val green = Color(0xFF34C759)
    Row(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.5.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
        Spacer(Modifier.weight(1f))      // ortada kamera deliği için boşluk kalır
        Text(c.value, color = green, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1)
        Spacer(Modifier.width(6.dp))
        Canvas(Modifier.size(width = 26.dp, height = 12.dp)) {
            val tipW = 1.8.dp.toPx()
            val bodyW = size.width - tipW - 1.dp.toPx()
            val r = CornerRadius(4.dp.toPx())
            drawRoundRect(Color.White.copy(alpha = 0.28f), size = Size(bodyW, size.height), cornerRadius = r)
            val fillW = (bodyW * c.progress.coerceIn(0f, 1f)).coerceAtLeast(size.height * 0.5f)
            drawRoundRect(green, size = Size(fillW, size.height), cornerRadius = r)
            drawRoundRect(
                Color.White.copy(alpha = 0.40f),
                topLeft = Offset(bodyW + 1.dp.toPx(), size.height * 0.3f),
                size = Size(tipW, size.height * 0.4f),
                cornerRadius = CornerRadius(1.dp.toPx()),
            )
        }
    }
}

@Composable
private fun StatusContent(c: IslandContent.Status, rb: RainbowStyle?) {
    val color = statusColor(c)
    val timer = c.kind == StatusKind.Timer && c.endsAt > 0L
    var now by remember(c.endsAt) { mutableLongStateOf(System.currentTimeMillis()) }
    if (timer) LaunchedEffect(c.endsAt) {
        while (true) { now = System.currentTimeMillis(); delay(200) }
    }
    val remaining = if (timer) (c.endsAt - now).coerceAtLeast(0L) else 0L
    val value = if (timer) formatRemaining(remaining) else c.value
    val frac = when {
        timer && c.totalMs > 0L -> remaining.toFloat() / c.totalMs
        c.kind == StatusKind.Charging -> c.progress
        else -> -1f
    }.let { if (it >= 0f) it.coerceIn(0f, 1f) else it }

    Row(
        Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(46.dp).background(color, RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
            StatusIcon(c, Modifier.size(26.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(c.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.5.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            if (c.subtitle.isNotBlank()) {
                Text(c.subtitle, color = Color.White.copy(alpha = 0.70f), fontSize = 13.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb))
            }
            if (frac >= 0f) {
                Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.16f))) {
                    Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(color, RoundedCornerShape(2.dp)))
                }
            }
        }
        if (value.isNotBlank()) {
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                maxLines = 1, modifier = Modifier.rainbowFill(rb))
        }
    }
}

// ---------------------------------------------------------------- Arama (gelen / aktif)
private const val PHONE_PATH =
    "M6.62,10.79c1.44,2.83 3.76,5.14 6.59,6.59l2.2,-2.2c0.27,-0.27 0.67,-0.36 1.02,-0.24 " +
    "1.12,0.37 2.33,0.57 3.57,0.57 0.55,0 1,0.45 1,1V20c0,0.55 -0.45,1 -1,1 -9.39,0 -17,-7.61 -17,-17 " +
    "0,-0.55 0.45,-1 1,-1h3.5c0.55,0 1,0.45 1,1 0,1.25 0.2,2.45 0.57,3.57 0.11,0.35 0.03,0.74 -0.25,1.02l-2.2,2.2z"

private val PhonePath: Path by lazy { PathParser().parsePathString(PHONE_PATH).toPath() }

private val CallGreen = Color(0xFF34C759)
private val CallRed = Color(0xFFFF3B30)

/** Telefon ahizesi simgesi. [rotation] 135 verilirse "kapat" simgesi olur. */
@Composable
internal fun PhoneIcon(rotation: Float, modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({
            rotate(rotation, Offset(size.width / 2f, size.height / 2f))
            scale(sc, sc, Offset.Zero)
        }) {
            // Dolgu + aynı renkte yuvarlak kontur: ahize kalınlaşır
            drawPath(PhonePath, tint)
            drawPath(PhonePath, tint, style = Stroke(width = PHONE_THICKEN, join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
    }
}

/** Ahize simgesinin ek kalınlığı (24x24'lük simge birimi). Artırınca daha kalın olur. */
private const val PHONE_THICKEN = 1.9f

// Cevapla / Reddet düğmeleri: iOS tarzı düz (gradyansız) kırmızı ve yeşil daireler
private val AnswerColor = Color(0xFF34C759)
private val DeclineColor = Color(0xFFFF3B30)
private val SpeakerOff = Color.White.copy(alpha = 0.16f)

@Composable
private fun CallButton(color: Color, size: Dp, onClick: () -> Unit, content: @Composable (Dp) -> Unit) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(color, CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) { content(size * 0.50f) }
}

// Hoparlör simgesi (ses dalgalı); kapalıyken dış dalga yok
@Composable
private fun SpeakerIcon(on: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val sc = size.width / 24f
        withTransform({ scale(sc, sc, Offset.Zero) }) {
            val body = Path().apply {
                moveTo(3f, 9.5f); lineTo(7f, 9.5f); lineTo(12f, 5f)
                lineTo(12f, 19f); lineTo(7f, 14.5f); lineTo(3f, 14.5f); close()
            }
            drawPath(body, tint)
            drawPath(body, tint, style = Stroke(width = 1.2f, join = StrokeJoin.Round))
            val st = Stroke(width = 2f, cap = StrokeCap.Round)
            drawArc(tint, -45f, 90f, false, Offset(8.5f, 7.5f), Size(9f, 9f), style = st)
            if (on) drawArc(tint, -50f, 100f, false, Offset(5f, 3.5f), Size(16f, 17f), style = st)
        }
    }
}

/** Küçük adadaki ses dalgası: yeşil -> beyaz -> turuncu renk geçişli, hareketli çubuklar. */
@Composable
private fun CallWave(modifier: Modifier = Modifier) {
    val tr = rememberInfiniteTransition(label = "wave")
    val phase: Float by tr.animateFloat(
        initialValue = 0f,
        targetValue = 6.2831855f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing)),
        label = "wavePhase",
    )
    val green = Color(0xFF4CE06A)
    val orange = Color(0xFFFF9248)
    Canvas(modifier) {
        val n = 9
        val gap: Float = size.width / n
        val bw: Float = gap * 0.52f
        for (i in 0 until n) {
            val t: Float = i / (n - 1).toFloat()
            val env: Float = 1f - kotlin.math.abs(t - 0.5f) * 1.1f                   // ortada yüksek, kenarda alçak
            val v: Float = 0.30f + 0.70f * kotlin.math.abs(kotlin.math.sin(phase + i * 0.85f))
            val h: Float = (size.height * env * v).coerceAtLeast(bw)
            val color: Color =
                if (t < 0.5f) androidx.compose.ui.graphics.lerp(green, Color.White, t * 2f)
                else androidx.compose.ui.graphics.lerp(Color.White, orange, (t - 0.5f) * 2f)
            val x: Float = gap * i + gap / 2f
            val y1: Float = (size.height - h) / 2f
            val y2: Float = (size.height + h) / 2f
            drawLine(color, Offset(x, y1), Offset(x, y2), strokeWidth = bw, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun CallContent(
    c: IslandContent.Call,
    accent: Color,
    rb: RainbowStyle?,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onSpeaker: () -> Unit,
) {
    val incoming = c.isIncoming
    val avatarSize = if (incoming) 52.dp else 48.dp
    Row(
        Modifier.fillMaxSize().padding(start = 14.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Avatar: fotoğraf varsa o, yoksa baş harf (gelen aramada renk geçişli, aktif aramada koyu gri)
        val avatar = c.avatar
        if (avatar != null) {
            Image(
                bitmap = avatar, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(avatarSize).clip(CircleShape),
            )
        } else {
            val bg = if (incoming) {
                Modifier.background(
                    Brush.linearGradient(listOf(Color(0xFFFF6B9D), Color(0xFFFF9A5A))), CircleShape,
                )
            } else {
                Modifier.background(Color(0xFF3A3A3C), CircleShape)
            }
            Box(Modifier.size(avatarSize).then(bg), contentAlignment = Alignment.Center) {
                Text(
                    text = c.name.trim().take(1).uppercase().ifEmpty { "?" },
                    color = Color.White, fontWeight = FontWeight.Bold, fontSize = 22.sp,
                    modifier = Modifier.rainbowFill(rb, 0.dp),
                )
            }
        }

        // İsim + durum
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            if (incoming) {
                Text(
                    "Gelen arama", color = Color.White.copy(alpha = 0.60f), fontSize = 13.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    c.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb),
                )
            } else {
                Text(
                    c.duration ?: "Aktif arama", color = CallGreen, fontWeight = FontWeight.Medium,
                    fontSize = 15.sp, maxLines = 1,
                )
                Text(
                    c.name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 19.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.rainbowFill(rb),
                )
            }
        }

        // Butonlar: gelen aramada kırmızı + yeşil; aktif aramada hoparlör + kırmızı
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!incoming) {
                CallButton(if (c.speaker) Color.White else SpeakerOff, 50.dp, onSpeaker) { sz ->
                    SpeakerIcon(c.speaker, if (c.speaker) Color.Black else Color.White, Modifier.size(sz + 4.dp))
                }
            }
            CallButton(DeclineColor, if (incoming) 52.dp else 50.dp, onDecline) { sz -> PhoneIcon(135f, Modifier.size(sz)) }
            if (incoming) CallButton(AnswerColor, 52.dp, onAnswer) { sz -> PhoneIcon(0f, Modifier.size(sz)) }
        }
    }
}
