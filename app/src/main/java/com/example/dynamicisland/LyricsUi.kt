package com.example.dynamicisland

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max

// ---------------------------------------------------------------- Senkron: hangi satır, ne kadar söylendi
@Stable
class LyricFrame(val index: State<Int>, val fraction: State<Float>)

fun lineIndexAt(lines: List<LyricLine>, t: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var ans = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= t) { ans = mid; lo = mid + 1 } else hi = mid - 1
    }
    return ans
}

/** Satırın ne kadarının söylendiği (0..1): sonraki satıra kadar ya da karakter sayısından tahmin edilen süre. */
fun lineFraction(lines: List<LyricLine>, i: Int, t: Long): Float {
    if (i < 0) return 0f
    val l = lines[i]
    if (l.text.isBlank()) return 0f
    val est = (l.text.length * 95L).coerceAtLeast(1500L)
    val next = lines.getOrNull(i + 1)?.timeMs ?: Long.MAX_VALUE
    val end = minOf(next, l.timeMs + est)
    val dur = (end - l.timeMs).coerceAtLeast(300L)
    return ((t - l.timeMs).toFloat() / dur).coerceIn(0f, 1f)
}

/**
 * Çalan şarkının konumundan (sistem saatiyle) satır + dolum oranını üretir. State olarak döner:
 * dolum yalnızca çizim fazında okunur, kare başına recomposition olmaz.
 */
@Composable
fun rememberLyricFrame(
    media: IslandContent.Media?,
    state: LyricsState,
    offsetMs: Long,
    active: Boolean = true,
    fine: Boolean = true,
    loopMs: Long = 0L,
): LyricFrame {
    val index = remember { mutableIntStateOf(-1) }
    val fraction = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(media, state, offsetMs, active, fine, loopMs) {
        val lines = (state as? LyricsState.Ready)?.lines
        if (media == null || lines.isNullOrEmpty()) {
            index.intValue = -1; fraction.floatValue = 0f
            return@LaunchedEffect
        }
        fun step() {
            var t = media.positionAt(SystemClock.elapsedRealtime()) + offsetMs
            if (loopMs > 0L) t = ((t % loopMs) + loopMs) % loopMs
            val i = lineIndexAt(lines, t)
            val f = lineFraction(lines, i, t)
            if (index.intValue != i) index.intValue = i
            if (abs(fraction.floatValue - f) > 0.002f || f == 0f || f == 1f) fraction.floatValue = f
        }
        step()
        if (active && media.playing) {
            if (fine) {
                while (true) {
                    withFrameMillis { it }
                    step()
                    delay(24)       // pil: ~30 fps yeter; her ekran karesinde uyanmak yerine kare öncesi uyutulur
                }
            } else {
                while (true) { delay(150); step() }
            }
        }
    }
    return remember { LyricFrame(index, fraction) }
}

// ---------------------------------------------------------------- Satır geçiş animasyonu
/**
 * Sözün çıkacağı nokta: söz alanının merkezinden adanın merkezine olan fark (piksel).
 * Yalnızca "Adanın dışında" söz penceresi doldurur; diğer yerlerde (0, 0) kalır ve söz kendi yerinde küçülüp büyür.
 */
@Immutable
data class LyricOrigin(val dx: Float = 0f, val dy: Float = 0f, val ix: Float = Float.NaN, val iy: Float = Float.NaN)

val LocalLyricOrigin = compositionLocalOf { LyricOrigin() }

/** 6 = Adadan çıkış, 7 = Adadan tek tek: sözlerin adanın içinden çıkıp büyümesi (dış söz penceresi taşmayı karşılar). */
fun IslandSettings.lyricsFromIsland(): Boolean = lyricsAnimEnabled && (lyricsAnimStyle == 6 || lyricsAnimStyle == 7)

private val PopEasing = CubicBezierEasing(0.2f, 1.25f, 0.4f, 1f)   // hafif taşan "pop" eğrisi

private class LyricTiming(val enterMs: Int, val enterDelay: Int, val exitMs: Int, val easing: androidx.compose.animation.core.Easing)

private fun lyricTiming(style: Int, d: Int): LyricTiming = when (style) {
    6 -> LyricTiming(enterMs = d, enterDelay = 0, exitMs = d, easing = FastOutSlowInEasing)               // eski adaya girerken yeni çıkar
    7 -> LyricTiming(enterMs = 1, enterDelay = 0, exitMs = (d * 0.5f).toInt(), easing = FastOutSlowInEasing)  // yalnızca eski satır adaya geri girer; yeni satırın kelimeleri tek tek çıkar
    9 -> LyricTiming(enterMs = d / 2, enterDelay = d / 2, exitMs = d / 2, easing = FastOutSlowInEasing)  // çevirme: önce eski döner, sonra yeni
    else -> LyricTiming(enterMs = d, enterDelay = 0, exitMs = (d * 0.6f).toInt(), easing = FastOutSlowInEasing)
}

/** Özel (ölçek / öteleme / döndürme) animasyonları: AnimatedContent'in giriş-çıkış geçişine bağlanır. */
@Composable
private fun AnimatedContentScope.LyricLayer(style: Int, d: Int, content: @Composable () -> Unit) {
    val t = remember(style, d) { lyricTiming(style, d) }
    val origin = LocalLyricOrigin.current
    val camera = LocalDensity.current.density * 14f
    val enter = transition.animateFloat(
        transitionSpec = { tween(t.enterMs, t.enterDelay, t.easing) }, label = "lyEnter",
    ) { if (it == EnterExitState.PreEnter) 0f else 1f }
    val exit = transition.animateFloat(
        transitionSpec = { tween(t.exitMs, 0, FastOutSlowInEasing) }, label = "lyExit",
    ) { if (it == EnterExitState.PostExit) 1f else 0f }

    Box(
        Modifier.graphicsLayer {
            val e = enter.value
            val x = exit.value
            when (style) {
                6, 7 -> {
                    // k: 0 = yerinde (tam boy), 1 = adanın içinde (küçük). 7'de giriş yok: kelimeler kendi çıkar.
                    val k = if (style == 7) x else max(1f - e, x)
                    val sc = 0.12f + 0.88f * (1f - k)
                    scaleX = sc; scaleY = sc
                    translationX = origin.dx * k
                    translationY = origin.dy * k
                }
                9 -> {
                    cameraDistance = camera
                    rotationX = -90f * (1f - e) + 90f * x
                }
                else -> {   // 10: odak çekme
                    val sc = 1f + 0.40f * (1f - e) - 0.15f * x
                    scaleX = sc; scaleY = sc
                }
            }
        }
    ) { content() }
}

@Composable
fun LyricTransition(
    index: Int,
    cfg: IslandSettings,
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.CenterStart,
    content: @Composable (Int) -> Unit,
) {
    if (!cfg.lyricsAnimEnabled || cfg.lyricsAnimStyle == 5) {
        Box(modifier, contentAlignment = contentAlignment) { content(index) }
        return
    }
    val d = cfg.lyricsAnimMs.coerceIn(80, 1200)
    val style = cfg.lyricsAnimStyle
    AnimatedContent(
        targetState = index,
        modifier = modifier,
        contentAlignment = contentAlignment,
        label = "lyric",
        transitionSpec = {
            val t: ContentTransform = when (style) {
                0 -> fadeIn(tween(d, delayMillis = d / 4)) togetherWith fadeOut(tween(d / 2))
                1 -> (slideInVertically(tween(d)) { it / 2 } + fadeIn(tween(d))) togetherWith
                    (slideOutVertically(tween(d)) { -it / 2 } + fadeOut(tween(d / 2)))
                2 -> (scaleIn(tween(d), initialScale = 0.8f) + fadeIn(tween(d))) togetherWith
                    (scaleOut(tween(d), targetScale = 1.15f) + fadeOut(tween(d / 2)))
                3 -> (slideInVertically(spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) { it } + fadeIn(tween(d / 2))) togetherWith
                    (slideOutVertically(tween(d)) { -it } + fadeOut(tween(d / 2)))
                4 -> (slideInHorizontally(tween(d)) { it / 3 } + fadeIn(tween(d))) togetherWith
                    (slideOutHorizontally(tween(d)) { -it / 3 } + fadeOut(tween(d / 2)))
                8 -> // Soldan açılma: satır soldan sağa açılarak belirir
                    (expandHorizontally(tween(d, easing = FastOutSlowInEasing), expandFrom = Alignment.Start, clip = true) +
                        fadeIn(tween(d / 2))) togetherWith fadeOut(tween(d / 2))
                else -> {   // 6, 7, 9, 10: ölçek/öteleme/döndürme LyricLayer'da; burada yalnızca solma süresi
                    val tm = lyricTiming(style, d)
                    fadeIn(tween(tm.enterMs, tm.enterDelay, FastOutSlowInEasing)) togetherWith fadeOut(tween(tm.exitMs))
                }
            }
            t.using(SizeTransform(clip = false))
        },
    ) { i ->
        if (style == 6 || style == 7 || style == 9 || style == 10) LyricLayer(style, d) { content(i) }
        else content(i)
    }
}

/** Albüm kapağının baskın renginden, koyu ada üstünde okunaklı (açık ve canlı) bir söz rengi üretir. */
fun lyricColorFromArt(artColor: Int, fallback: Color): Color {
    if (artColor == 0) return fallback
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(artColor, hsv)
    hsv[1] = hsv[1].coerceIn(0.35f, 0.80f)
    hsv[2] = 1f
    return Color(android.graphics.Color.HSVToColor(hsv))
}

// ---------------------------------------------------------------- Tek satır: karaoke dolumu + parıltı
/**
 * Satırı iki katmanda çizer: soluk taban + söylenen kısım kadar parlak üst katman (satır sarsa da soldan sağa,
 * satır satır ilerler). scroll=true: tek satır; sığmazsa dolumla birlikte yana kayar.
 */
@Composable
fun LyricLineText(
    text: String,
    lineIndex: Int,
    frame: LyricFrame,
    cfg: IslandSettings,
    sizeSp: Float,
    maxLines: Int,
    scroll: Boolean,
    align: TextAlign,
    modifier: Modifier = Modifier,
    outside: Boolean = false,
    artColor: Int = 0,
) {
    if (text.isBlank()) return
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val origin = LocalLyricOrigin.current
    // "Adadan tek tek": satır kelime kelime, şarkıda o kelimeye gelinince adadan çıkar
    val wordMode = cfg.lyricsAnimEnabled && cfg.lyricsAnimStyle == 7
    val words = remember(text, wordMode) { if (wordMode) wordRanges(text) else emptyList() }
    val wordAnims = remember(words) { words.map { Animatable(0f) } }
    if (wordMode) {
        val ms = cfg.lyricsAnimMs.coerceIn(120, 900)
        LaunchedEffect(words, ms) {
            snapshotFlow {
                val cur = frame.index.value
                val f = if (cur == lineIndex) frame.fraction.value else if (cur < lineIndex) 0f else -1f   // geçmiş satırda yeni kelime çıkmaz
                words.count { it.startFrac <= f + 0.0001f }
            }.collect { n ->
                for (i in 0 until n.coerceAtMost(wordAnims.size)) {
                    val a = wordAnims[i]
                    if (a.value == 0f && !a.isRunning) launch { a.animateTo(1f, tween(ms, easing = PopEasing)) }
                }
            }
        }
    }
    BoxWithConstraints(
        modifier,
        contentAlignment = if (align == TextAlign.Start) Alignment.CenterStart else Alignment.Center,
    ) {
        val maxPx = with(density) { maxWidth.roundToPx() }
        val style = remember(sizeSp, align) {
            TextStyle(fontSize = sizeSp.sp, lineHeight = (sizeSp * 1.25f).sp, fontWeight = FontWeight.SemiBold, textAlign = align)
        }
        val layout = remember(text, style, maxPx, maxLines, scroll) {
            measurer.measure(
                text = text,
                style = style,
                overflow = if (scroll) TextOverflow.Clip else TextOverflow.Ellipsis,
                softWrap = !scroll,
                maxLines = if (scroll) 1 else maxLines,
                constraints = Constraints(maxWidth = if (scroll) Constraints.Infinity else maxPx),
            )
        }
        // "Adadan tek tek" modu: her kelime ayrı bir metin düzeni olarak ölçülür (komşu harf kırıntısı çıkmasın)
        val wordLayouts = remember(layout, words, wordMode) {
            if (!wordMode) emptyList() else {
                val visEnd = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
                words.map { w ->
                    val e = minOf(w.end, visEnd)
                    if (w.start >= e) null else measurer.measure(
                        text = text.substring(w.start, e),
                        style = style,
                        overflow = TextOverflow.Clip,
                        softWrap = false,
                        maxLines = 1,
                    )
                }
            }
        }
        val textW = layout.size.width
        val textH = layout.size.height
        val canvasW = if (scroll) maxPx else minOf(textW, maxPx)
        // Renk: albüm kapağından (açıksa ve kapak varsa), yoksa elle seçilen renk. Şarkı değişince yumuşakça geçer.
        val baseColor = Color(cfg.lyricsColorArgb)
        val targetColor = if (cfg.lyricsDynamicColor) lyricColorFromArt(artColor, baseColor) else baseColor
        val color by animateColorAsState(targetColor, tween(450), label = "lyricColor")
        val dim = color.copy(alpha = cfg.lyricsDimAlpha)
        val rb = rememberRainbowStyle(cfg)   // gökkuşağı modu: açıksa söz rengi yerine gökkuşağı

        // Parıltı yarıçapı yazı boyutuyla orantılı ve küçük tutulur: harflerin hemen çevresinde kalır,
        // yan satırlara / öğelere taşmaz. Seviye 1.0'da bile yazı boyutunun ~üçte biri.
        val fontPx = with(density) { sizeSp.sp.toPx() }
        val glowPx = if (cfg.lyricsGlowEnabled) fontPx * (0.05f + 0.28f * cfg.lyricsGlowLevel) else 0f
        val bleed = ceil(glowPx)       // parıltının kesilmeden çizilebilmesi için çizim payı
        val shadow: Shadow? = when {
            glowPx > 0f -> Shadow(color.copy(alpha = 0.8f), Offset.Zero, glowPx)
            outside -> Shadow(Pal.c("lyrics_shadow").copy(alpha = 0.55f), Offset(0f, 2f), 8f)   // glow kapalıyken okunurluk
            else -> null
        }

        Canvas(Modifier.size(with(density) { canvasW.toDp() }, with(density) { textH.toDp() })) {
            val cur = frame.index.value
            val progress = when {
                cur == lineIndex -> frame.fraction.value
                cur > lineIndex -> 1f
                else -> 0f
            }
            val f = if (cfg.lyricsFillEnabled) progress else 1f
            val rbBrush: Brush? = rb?.let { rainbowBrush(it.phase.value, maxOf(size.width, textW.toFloat(), 160.dp.toPx()), it.alpha) }
            val rbColor: Color? = rb?.let { rainbowColorAt(it.phase.value, it.alpha) }
            val litShadow: Shadow? = if (rbColor != null && glowPx > 0f) Shadow(rbColor.copy(alpha = rbColor.alpha * 0.8f), Offset.Zero, glowPx) else shadow
            val overflow = (textW - size.width).coerceAtLeast(0f)
            val dx = if (!scroll) 0f else if (overflow > 0f) -overflow * progress else (size.width - textW) / 2f

            // Tek satırlı (kayan) modda yatayda alan sınırında kesilir; parıltı dikeyde ve diğer modda
            // yatayda `bleed` kadar pay alır, böylece kenarlarda sert kesik oluşmaz.
            val clipL = if (scroll) 0f else -bleed
            val clipR = if (scroll) size.width else size.width + bleed
            if (wordMode) {   // kelimeler adadan gelirken canvas sınırının dışında da çizilir (kırpma yok)
                translate(left = dx) {
                    val visEnd = layout.getLineEnd(layout.lineCount - 1, visibleEnd = true)
                    val len = text.length.toFloat()
                    // Adanın bu canvas'a göre konumu
                    val ix = size.width / 2f + origin.dx - dx
                    val iy = size.height / 2f + origin.dy
                    val gb = bleed * 2.5f + 4f          // parıltı yayılımını kesmeyecek kadar geniş kırpma payı
                    for ((i, w) in words.withIndex()) {
                        if (w.start >= visEnd) break
                        val a = wordAnims.getOrNull(i)?.value ?: 1f
                        if (a <= 0.001f) continue
                        val wl = wordLayouts.getOrNull(i) ?: continue
                        val wcol = if (rb != null) rainbowColorAt(rb.phase.value + i * 0.09f, rb.alpha) else color   // gökkuşağı: kelime başına ton farkı
                        val wdim = if (rb != null) wcol.copy(alpha = wcol.alpha * cfg.lyricsDimAlpha) else dim
                        val endC = minOf(w.end, visEnd)
                        val bl = layout.getBoundingBox(w.start)
                        val br = layout.getBoundingBox((endC - 1).coerceAtLeast(w.start))
                        val ln = layout.getLineForOffset(w.start)
                        val rect = Rect(bl.left, layout.getLineTop(ln), br.right, layout.getLineBottom(ln))
                        val topLeft = Offset(bl.left, layout.getLineTop(ln))
                        val pivot = rect.center
                        val sc = 0.12f + 0.88f * a
                        val tx = (ix - pivot.x) * (1f - a)
                        val ty = (iy - pivot.y) * (1f - a)
                        val alpha = (a * 3f).coerceIn(0f, 1f)
                        val wf = if (cfg.lyricsFillEnabled) ((progress * len - w.start) / (endC - w.start).coerceAtLeast(1)).coerceIn(0f, 1f) else 1f
                        // Parıltı kelime adadan çıkarken çizilmez, yerine yaklaşınca yumuşakça belirir:
                        // küçülen kelimede sabit yarıçaplı parıltı kenarlardan taşıp ince çizgiler oluşturuyordu.
                        val gA = ((a - 0.6f) / 0.4f).coerceIn(0f, 1f)
                        val wShadow: Shadow? = when {
                            glowPx > 0f -> if (gA > 0f) Shadow(wcol.copy(alpha = wcol.alpha * 0.8f * gA), Offset.Zero, glowPx) else null
                            outside -> shadow
                            else -> null
                        }
                        // Her kelime KENDİ metniyle çizilir: tüm satırı kırpıp çizmek, komşu kelimelerin
                        // harf kenarlarını da (kırpma payı içinde kalan) ince çizgiler olarak gösteriyordu.
                        withTransform({ translate(tx, ty); scale(sc, sc, pivot) }) {
                            if (wf < 0.999f) drawText(wl, color = wdim.copy(alpha = wdim.alpha * alpha), topLeft = topLeft, shadow = if (outside && glowPx <= 0f) shadow else null)
                            if (wf >= 0.999f) {
                                drawText(wl, color = wcol.copy(alpha = wcol.alpha * alpha), topLeft = topLeft, shadow = wShadow)
                            } else if (wf > 0f) {
                                clipRect(rect.left - gb, rect.top - gb, rect.left + rect.width * wf, rect.bottom + gb) {
                                    drawText(wl, color = wcol.copy(alpha = wcol.alpha * alpha), topLeft = topLeft, shadow = wShadow)
                                }
                            }
                        }
                    }
                }
                return@Canvas
            }
            clipRect(left = clipL, top = -bleed, right = clipR, bottom = size.height + bleed) {
                translate(left = dx) {
                    if (f < 0.999f) {
                        if (rbBrush != null) drawText(layout, brush = rbBrush, alpha = cfg.lyricsDimAlpha, shadow = if (outside && glowPx <= 0f) shadow else null)
                        else drawText(layout, color = dim, shadow = if (outside && glowPx <= 0f) shadow else null)
                    }
                    if (f >= 0.999f) {
                        if (rbBrush != null) drawText(layout, brush = rbBrush, shadow = litShadow)
                        else drawText(layout, color = color, shadow = shadow)
                    } else if (f > 0f) {
                        val n = layout.lineCount
                        var total = 0f
                        for (i in 0 until n) total += layout.getLineRight(i) - layout.getLineLeft(i)
                        var remaining = f * total
                        for (i in 0 until n) {
                            if (remaining <= 0f) break
                            val l = layout.getLineLeft(i)
                            val r = layout.getLineRight(i)
                            val w = r - l
                            val take = minOf(remaining, w)
                            clipRect(
                                left = l - bleed - 2f,
                                top = layout.getLineTop(i) - bleed,
                                right = if (take >= w) r + bleed + 2f else l + take,
                                bottom = layout.getLineBottom(i) + bleed,
                            ) {
                                if (rbBrush != null) drawText(layout, brush = rbBrush, shadow = litShadow)
                                else drawText(layout, color = color, shadow = shadow)
                            }
                            remaining -= w
                        }
                    }
                }
            }
        }
    }
}

@Immutable
private class WordRange(val start: Int, val end: Int, val startFrac: Float)

/** Satırdaki kelimelerin karakter aralıkları; startFrac = kelimenin satırın hangi oranında söylendiği (0..1). */
private fun wordRanges(text: String): List<WordRange> {
    val out = ArrayList<WordRange>()
    val n = text.length.toFloat().coerceAtLeast(1f)
    var i = 0
    while (i < text.length) {
        while (i < text.length && text[i].isWhitespace()) i++
        val s = i
        while (i < text.length && !text[i].isWhitespace()) i++
        if (i > s) out += WordRange(s, i, s / n)
    }
    return out
}

private fun lineText(r: LyricsState.Ready, i: Int): String =
    if (i < 0) "♪" else r.lines.getOrNull(i)?.text.orEmpty()

// ---------------------------------------------------------------- Genişletilmiş panel satırı
@Composable
fun PanelLyrics(cfg: IslandSettings, state: LyricsState, frame: LyricFrame, modifier: Modifier = Modifier, artColor: Int = 0) {
    Box(modifier.fillMaxWidth().height(cfg.lyricsBlockDp().dp), contentAlignment = Alignment.CenterStart) {
        when (state) {
            is LyricsState.Ready -> LyricTransition(frame.index.value, cfg, Modifier.fillMaxWidth()) { i ->
                LyricLineText(lineText(state, i), i, frame, cfg, cfg.lyricsPanelSizeSp, cfg.lyricsPanelLines, false, TextAlign.Start, Modifier.fillMaxWidth(), artColor = artColor)
            }
            LyricsState.Loading -> StatusText("Şarkı sözü aranıyor…")
            LyricsState.NotFound -> StatusText("Şarkı sözü bulunamadı")
            LyricsState.Idle -> Unit
        }
    }
}

/**
 * "Sözler sağda" düzeni: açık müzik adasının sağ sütunu. Üstte o an söylenen satır (karaoke dolumuyla),
 * altında sıradaki satır soluk olarak görünür.
 */
@Composable
fun SideLyrics(cfg: IslandSettings, state: LyricsState, frame: LyricFrame, modifier: Modifier = Modifier, artColor: Int = 0) {
    Box(modifier, contentAlignment = Alignment.CenterStart) {
        when (state) {
            is LyricsState.Ready -> {
                val sizeSp = cfg.lyricsPanelSizeSp.coerceIn(11f, 17f)
                val base = if (cfg.lyricsDynamicColor) lyricColorFromArt(artColor, Color(cfg.lyricsColorArgb)) else Color(cfg.lyricsColorArgb)
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LyricTransition(frame.index.value, cfg, Modifier.fillMaxWidth()) { i ->
                        LyricLineText(lineText(state, i), i, frame, cfg, sizeSp, 3, false, TextAlign.Start, Modifier.fillMaxWidth(), artColor = artColor)
                    }
                    val next = state.lines.getOrNull(frame.index.value + 1)?.text.orEmpty()
                    if (next.isNotBlank()) {
                        Text(
                            next, color = base.copy(alpha = cfg.lyricsDimAlpha.coerceIn(0.2f, 0.8f)),
                            fontSize = (sizeSp - 2f).sp, lineHeight = (sizeSp + 1f).sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            LyricsState.Loading -> StatusText("Sözler aranıyor…")
            LyricsState.NotFound -> StatusText("Söz bulunamadı")
            LyricsState.Idle -> Unit
        }
    }
}

@Composable
private fun StatusText(s: String) = Text(s, color = Pal.c("lyrics_status"), fontSize = 14.sp, fontWeight = FontWeight.Medium)

// ---------------------------------------------------------------- Kapalı adanın ortası (tek satır, yana kayar)
@Composable
fun CompactLyrics(cfg: IslandSettings, state: LyricsState, frame: LyricFrame, modifier: Modifier = Modifier, artColor: Int = 0) {
    val ready = state as? LyricsState.Ready ?: return
    Box(
        modifier
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                drawRect(
                    Brush.horizontalGradient(0f to Color.Transparent, 0.1f to Color.Black, 0.9f to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        LyricTransition(frame.index.value, cfg, Modifier.fillMaxWidth(), Alignment.Center) { i ->
            LyricLineText(lineText(ready, i), i, frame, cfg, cfg.lyricsCompactSizeSp, 1, true, TextAlign.Center, Modifier.fillMaxWidth(), artColor = artColor)
        }
    }
}

// ---------------------------------------------------------------- Adanın dışındaki sözler
@Composable
fun OutsideLyricText(cfg: IslandSettings, state: LyricsState, frame: LyricFrame, modifier: Modifier = Modifier, artColor: Int = 0) {
    val ready = state as? LyricsState.Ready ?: return
    Box(modifier, contentAlignment = Alignment.Center) {
        LyricTransition(frame.index.value, cfg, Modifier.fillMaxWidth(), Alignment.Center) { i ->
            LyricLineText(
                lineText(ready, i), i, frame, cfg, cfg.lyricsOutsideSizeSp, cfg.lyricsOutsideLines,
                false, TextAlign.Center, Modifier.fillMaxWidth(), outside = true, artColor = artColor,
            )
        }
    }
}

/** Ayrı, dokunmatik olmayan overlay penceresinin içeriği. Ada genişleyip sözleri kendi içinde gösterirken gizlenir. */
@Composable
fun OutsideLyricsRoot(
    ui: StateFlow<IslandUiState>,
    settingsFlow: StateFlow<IslandSettings>,
    lyricsFlow: StateFlow<LyricsState>,
    mediaFlow: StateFlow<IslandContent.Media?>,
    originFlow: StateFlow<LyricOrigin>,
) {
    val cfg by settingsFlow.collectAsState()
    val origin by originFlow.collectAsState()
    val lyrics by lyricsFlow.collectAsState()
    val media by mediaFlow.collectAsState()
    val u by ui.collectAsState()
    val hiddenByPanel = u.expanded && cfg.lyricsInPanel && u.content is IslandContent.Media
    val show = cfg.lyricsEnabled && cfg.lyricsOutside && media?.playing == true &&
        lyrics is LyricsState.Ready && !hiddenByPanel
    val frame = rememberLyricFrame(media, lyrics, cfg.lyricsOffsetMs.toLong(), active = show, fine = cfg.lyricsFillEnabled)
    val shownAlpha by animateFloatAsState(if (show) 1f else 0f, tween(250), label = "outA")
    if (shownAlpha > 0.01f) {
        if (cfg.lyricsFromIsland()) {
            // Pencere adaya kadar uzatıldı (üstte boş pay var): söz alanı altta, eski yerinde kalır; sözler adadan çıkıp oraya gelir
            val areaDp = (cfg.lyricsOutsideSizeSp * LocalDensity.current.fontScale * 1.3f * cfg.lyricsOutsideLines).dp
            // Söz alanının ekrandaki GERÇEK merkezi ölçülür; sistem pencereyi kaydırsa bile adaya olan uzaklık doğru çıkar
            val hostView = LocalView.current
            var areaCenter by remember { mutableStateOf<Offset?>(null) }
            val areaCoords = remember { arrayOfNulls<androidx.compose.ui.layout.LayoutCoordinates>(1) }
            fun measureArea() {
                val c = areaCoords[0]?.takeIf { it.isAttached } ?: return
                val loc = IntArray(2)
                hostView.getLocationOnScreen(loc)
                val p = c.positionInRoot()
                val nc = Offset(loc[0] + p.x + c.size.width / 2f, loc[1] + p.y + c.size.height / 2f)
                val old = areaCenter
                if (old == null || (old - nc).getDistance() > 1.5f) areaCenter = nc
            }
            // Sistem pencereyi sonradan kaydırabilir (düzen değişmeden): konumu düzenli aralıkla yeniden ölç
            LaunchedEffect(Unit) { while (true) { delay(400); measureArea() } }
            val effOrigin = remember(origin, areaCenter) {
                val c = areaCenter
                if (c != null && !origin.ix.isNaN() && !origin.iy.isNaN()) LyricOrigin(dx = origin.ix - c.x, dy = origin.iy - c.y) else origin
            }
            CompositionLocalProvider(LocalLyricOrigin provides effOrigin) {
                Box(
                    Modifier.fillMaxSize().padding(24.dp).graphicsLayer { this.alpha = shownAlpha },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        Modifier.fillMaxWidth().height(areaDp).onGloballyPositioned { c ->
                            areaCoords[0] = c
                            measureArea()
                        },
                        contentAlignment = Alignment.Center,
                    ) {
                        OutsideLyricText(cfg, lyrics, frame, Modifier.fillMaxWidth(), artColor = media?.artColor ?: 0)
                    }
                }
            }
        } else {
            Box(
                Modifier.fillMaxSize().padding(24.dp).graphicsLayer { this.alpha = shownAlpha },
                contentAlignment = Alignment.Center,
            ) { OutsideLyricText(cfg, lyrics, frame, Modifier.fillMaxWidth(), artColor = media?.artColor ?: 0) }
        }
    }
}
