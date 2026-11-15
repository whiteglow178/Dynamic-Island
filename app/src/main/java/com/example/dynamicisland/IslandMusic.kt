package com.example.dynamicisland

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.util.Locale

// ---------------------------------------------------------------- Kayan başlık
/** Sığmayan şarkı adı: bir süre bekler, sonuna kadar yana kayar, geri döner ve tekrarlar. Sığıyorsa sabit durur. */
@Composable
private fun MarqueeText(text: String, modifier: Modifier, color: Color, fontSize: TextUnit, fontWeight: FontWeight) {
    val scroll = rememberScrollState()
    val max = scroll.maxValue
    LaunchedEffect(text, max) {
        scroll.scrollTo(0)
        if (max <= 0) return@LaunchedEffect
        while (true) {
            delay(1500)
            scroll.animateScrollTo(max, tween((max * 22).coerceIn(800, 12000), easing = LinearEasing))
            delay(1200)
            scroll.animateScrollTo(0, tween(600, easing = FastOutSlowInEasing))
        }
    }
    Text(
        text, color = color, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false,
        modifier = modifier.horizontalScroll(scroll, enabled = false),
    )
}

// ---------------------------------------------------------------- Equalizer (sağ kenar)
@Composable
fun Equalizer(color: Color, height: Dp = 14.dp, barWidth: Dp = 3.dp, animate: Boolean = true, rainbow: RainbowStyle? = null) {
    val t = rememberInfiniteTransition(label = "eq")
    val durations = intArrayOf(380, 520, 300, 460, 410)
    val bars = durations.map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bar$d")
    }
    Row(Modifier.height(height).rainbowFill(rainbow, 0.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        bars.forEach { s ->
            Box(
                Modifier.width(barWidth).fillMaxHeight()
                    .graphicsLayer { scaleY = if (animate) s.value else 0.45f }   // draw fazı: recomposition yok, GPU
                    .background(color, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ---------------------------------------------------------------- Beğeni (yalnızca bu uygulamada saklanır)
object FavoriteStore {
    private const val FILE = "island_favs"
    private const val KEY = "s"
    fun isFav(ctx: Context, key: String): Boolean =
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.contains(key) == true

    fun set(ctx: Context, key: String, on: Boolean) {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val cur = HashSet(sp.getStringSet(KEY, emptySet()) ?: emptySet())
        if (on) cur.add(key) else cur.remove(key)
        sp.edit().putStringSet(KEY, cur).apply()
    }
}

// ---------------------------------------------------------------- Genişletilmiş müzik paneli
@Composable
fun MusicPanel(
    m: IslandContent.Media,
    accent: Color,
    cfg: IslandSettings,
    lyrics: LyricsState,
    frame: LyricFrame,
    onCommand: (MediaCommand) -> Unit,
    onOutput: () -> Unit,
    customArt: ImageBitmap? = null,   // galeriden seçilen kapak; doluysa albüm kapağı yerine gösterilir
    onOpenMusic: () -> Unit = {},     // kapağa / şarkı adına basınca çalan müzik uygulamasını aç (ayarlardan)
) {
    val ctx = LocalContext.current
    val rb = rememberRainbowStyle(cfg)
    val noRipple = remember { MutableInteractionSource() }
    val favKey = remember(m.title, m.artist) { "${m.title}|${m.artist}" }
    var fav by remember(favKey) { mutableStateOf(FavoriteStore.isFav(ctx, favKey)) }
    var lastTap by remember { mutableLongStateOf(0L) }
    // Uygulamanın gerçek beğeni durumu biliniyorsa kalbi ona eşitle (dokunuştan hemen sonra karışma)
    LaunchedEffect(favKey) {
        while (true) {
            if (SystemClock.elapsedRealtime() - lastTap > 2500) {
                PlayerLike.likedState(ctx, m.title)?.let { real ->
                    if (real != fav) { fav = real; FavoriteStore.set(ctx, favKey, real) }
                }
            }
            delay(2000)
        }
    }

    // Beğenilmiş kalbin rengi: kapaktan (açıksa ve kapak varsa), yoksa ayarlardaki renk
    val baseHeart = if (cfg.likeArtColor && m.artColor != 0) glowTone(Color(m.artColor)) else Color(cfg.likeColorArgb)
    val heartColor by animateColorAsState(baseHeart, tween(450), label = "heartColor")

    Column(
        Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(if (cfg.openExpArt) Modifier.clickable(interactionSource = noRipple, indication = null) { onOpenMusic() } else Modifier) {
                AlbumArt(customArt ?: m.art, 60.dp)                               // sol köşe
            }
            // Kapak yüksekliğinde alan: şarkı adı ortada, sanatçı adı kapağın alt kenarıyla hizalı
            Box(
                Modifier.weight(1f).height(60.dp)
                    .then(if (cfg.openExpTitle) Modifier.clickable(interactionSource = noRipple, indication = null) { onOpenMusic() } else Modifier)
            ) {
                MarqueeText(
                    m.title.ifBlank { "Bilinmeyen parça" },
                    Modifier.align(Alignment.CenterStart).fillMaxWidth().rainbowFill(rb),
                    color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                )
                Text(
                    m.artist, color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().offset(y = 3.dp).rainbowFill(rb),
                )
            }
            Equalizer(accent, height = 26.dp, barWidth = 4.dp, animate = m.playing, rainbow = rb)
        }

        if (cfg.lyricsEnabled && cfg.lyricsInPanel) PanelLyrics(cfg, lyrics, frame, artColor = m.artColor)

        MusicProgress(m, cfg, rb) { onCommand(MediaCommand.SeekTo(it)) }

        Row(Modifier.fillMaxWidth().offset(y = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            CtlButton(if (fav) Ctl.HeartOn else Ctl.Heart, 22.dp, Modifier.offset(x = (-8).dp), heartColor, rb) {
                val want = !fav
                fav = want; lastTap = SystemClock.elapsedRealtime()
                FavoriteStore.set(ctx, favKey, want)
                // Çalan uygulamada da beğen / beğenmekten vazgeç
                if (!PlayerLike.setLiked(ctx, m.title, want)) {
                    Toast.makeText(ctx.applicationContext, "Bu oynatıcı dışarıdan beğenmeyi desteklemiyor", Toast.LENGTH_SHORT).show()
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                CtlButton(Ctl.Rew, 24.dp, rb = rb) { onCommand(MediaCommand.Previous) }
                CtlButton(if (m.playing) Ctl.Pause else Ctl.Play, 28.dp, rb = rb) { onCommand(MediaCommand.PlayPause) }
                CtlButton(Ctl.Fwd, 24.dp, rb = rb) { onCommand(MediaCommand.Next) }
            }
            CtlButton(Ctl.Cast, 25.dp, Modifier.offset(x = 5.dp), rb = rb) { onOutput() }
        }
    }
}

@Composable
fun AlbumArt(art: ImageBitmap?, size: Dp) {
    val shape = RoundedCornerShape(12.dp)
    if (art != null) {
        Image(bitmap = art, contentDescription = null, contentScale = ContentScale.Crop, filterQuality = FilterQuality.Medium,
            modifier = Modifier.size(size).clip(shape).border(1.dp, Color.White.copy(alpha = 0.10f), shape))
    } else {
        Box(Modifier.size(size).clip(shape).background(Color.White.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text("♪", color = Color.White.copy(alpha = 0.7f), fontSize = 22.sp)
        }
    }
}

private fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

@Composable
private fun MusicProgress(m: IslandContent.Media, cfg: IslandSettings, rb: RainbowStyle?, onSeek: (Long) -> Unit) {
    // Parıltı rengi: kapaktan (açıksa ve kapak varsa), yoksa ayarlardaki renk
    val baseGlow = if (cfg.progressGlowArtColor && m.artColor != 0) glowTone(Color(m.artColor)) else Color(cfg.progressGlowColorArgb)
    val glowColor by animateColorAsState(baseGlow, tween(450), label = "progressGlowColor")
    val glow = if (cfg.progressGlowEnabled) ProgressGlow(glowColor, cfg.progressGlowIntensity, cfg.progressGlowTipOnly, cfg.progressGlowInside) else null

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    var drag by remember { mutableStateOf<Float?>(null) }

    // Sadece panel görünürken VE müzik çalarken yarım saniyede bir tikler; panel kapanınca coroutine biter.
    LaunchedEffect(m.playing, m.updatedAt, m.positionMs) {
        now = SystemClock.elapsedRealtime()
        while (m.playing) { delay(500); now = SystemClock.elapsedRealtime() }
    }

    val dur = m.durationMs
    val d = drag
    val frac = d ?: if (dur > 0) m.positionAt(now).toFloat() / dur else 0f
    val shownMs = if (d != null) (d * dur).toLong() else m.positionAt(now)
    val gray = Color.White.copy(alpha = 0.55f)

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(fmt(shownMs), color = gray, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, modifier = Modifier.widthIn(min = 32.dp).rainbowFill(rb))
        Box(Modifier.weight(1f)) {
            SeekBar(frac.coerceIn(0f, 1f), dur > 0, d != null, glow, rb) { f, commit ->
                if (commit) { drag = null; onSeek((f * dur).toLong()) } else drag = f
            }
        }
        Text(if (dur > 0) "-" + fmt((dur - shownMs).coerceAtLeast(0L)) else "", color = gray, fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium, textAlign = TextAlign.End, modifier = Modifier.widthIn(min = 36.dp).rainbowFill(rb))
    }
}

@Immutable
private class ProgressGlow(val color: Color, val intensity: Float, val tipOnly: Boolean, val inside: Boolean) {
    /** Uç modu her zaman, tüm çubuk modu ise "içinde kalsın" açıkken çubuğun içinde çizilir. */
    val contained: Boolean get() = tipOnly || inside
}

/** Kapak rengini parıltı için canlı ve parlak bir tona çeker (koyu kapaklarda da görünür kalsın). */
private fun glowTone(c: Color): Color {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(c.toArgb(), hsv)
    hsv[1] = (hsv[1] * 1.1f).coerceIn(0.25f, 1f)
    hsv[2] = hsv[2].coerceAtLeast(0.9f)
    return Color(android.graphics.Color.HSVToColor(hsv))
}

/** Tüm çubuk modu, taşmalı: dolu kısmın arkasına dışa yayılan yumuşak ışık çizer. */
private fun DrawScope.drawOuterGlow(g: ProgressGlow, fillW: Float, cy: Float, th: Float) {
    val k = g.intensity.coerceIn(0f, 1f)
    // Üst üste binen yarı saydam katmanlar: ortası parlak, kenarlara doğru sönen yumuşak ışık
    val layers = 7
    for (i in layers downTo 1) {
        val grow = 16.dp.toPx() * i / layers
        val h = th + grow
        drawRoundRect(
            g.color.copy(alpha = 0.13f * k),
            Offset(-grow / 2f, cy - h / 2f), Size(fillW + grow, h), CornerRadius(h / 2f),
        )
    }
}

/** Parıltıyı dolu çubuğun İÇİNDE çizer (dışarı taşmaz): uç modunda ucu renklenir, tüm çubuk modunda çubuk ışıldar. */
private fun DrawScope.drawInnerGlow(g: ProgressGlow, fillW: Float, cy: Float, th: Float) {
    val k = g.intensity.coerceIn(0f, 1f)
    val top = cy - th / 2f
    val clip = Path().apply { addRoundRect(RoundRect(0f, top, fillW, top + th, CornerRadius(th / 2f))) }
    clipPath(clip) {
        if (g.tipOnly) {
            // Çubuğun son kısmı uca doğru parıltı rengine döner
            val len = minOf(fillW, 40.dp.toPx())
            val x0 = fillW - len
            val brush = Brush.horizontalGradient(
                0f to g.color.copy(alpha = 0f),
                0.55f to g.color.copy(alpha = 0.50f * k),
                1f to g.color.copy(alpha = 0.95f * k),
                startX = x0, endX = fillW,
            )
            drawRect(brush, Offset(x0, top), Size(len, th))
        } else {
            // Neon tüp: kenarlar parıltı rengi, ortada parlak beyaz çekirdek
            drawRect(g.color.copy(alpha = k), Offset(0f, top), Size(fillW, th))
            drawRoundRect(
                Color.White.copy(alpha = 0.88f),
                Offset(0f, cy - th * 0.2f), Size(fillW, th * 0.4f), CornerRadius(th * 0.2f),
            )
        }
    }
}

@Composable
private fun SeekBar(fraction: Float, enabled: Boolean, dragging: Boolean, glow: ProgressGlow?, rb: RainbowStyle?, onChange: (Float, Boolean) -> Unit) {
    var w by remember { mutableIntStateOf(1) }
    val thick by animateFloatAsState(if (dragging) 8f else 5f, spring(stiffness = Spring.StiffnessMedium), label = "seekThick")
    val cb by rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxWidth().height(24.dp).rainbowFill(rb)
            .onSizeChanged { w = it.width.coerceAtLeast(1) }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { cb((it.x / w).coerceIn(0f, 1f), true) }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                var last = 0f
                detectHorizontalDragGestures(
                    onDragStart = { last = (it.x / w).coerceIn(0f, 1f); cb(last, false) },
                    onDragEnd = { cb(last, true) },
                    onDragCancel = { cb(last, true) },
                ) { change, _ ->
                    change.consume()
                    last = (change.position.x / w).coerceIn(0f, 1f); cb(last, false)
                }
            }
    ) {
        val cy = size.height / 2
        val th = thick.dp.toPx()
        drawRoundRect(Color.White.copy(0.22f), Offset(0f, cy - th / 2), Size(size.width, th), CornerRadius(th / 2))
        val fillW = (size.width * fraction).coerceAtLeast(0f)
        val glowOn = glow != null && fillW > 0f
        if (glowOn && !glow!!.contained) drawOuterGlow(glow, fillW, cy, th)
        drawRoundRect(Color.White, Offset(0f, cy - th / 2), Size(fillW, th), CornerRadius(th / 2))
        if (glowOn && glow!!.contained) drawInnerGlow(glow, fillW, cy, th)
    }
}

// ---------------------------------------------------------------- Kontrol ikonları (Canvas; ek ikon kütüphanesi gerekmez)
enum class Ctl { Play, Pause, Next, Prev, Rew, Fwd, Heart, HeartOn, Cast }

private fun DrawScope.heart(s: Float, filled: Boolean, color: Color) {
    val p = Path().apply {
        moveTo(0.5f * s, 0.88f * s)
        cubicTo(0.12f * s, 0.62f * s, 0.02f * s, 0.40f * s, 0.14f * s, 0.25f * s)
        cubicTo(0.26f * s, 0.10f * s, 0.45f * s, 0.14f * s, 0.50f * s, 0.30f * s)
        cubicTo(0.55f * s, 0.14f * s, 0.74f * s, 0.10f * s, 0.86f * s, 0.25f * s)
        cubicTo(0.98f * s, 0.40f * s, 0.88f * s, 0.62f * s, 0.50f * s, 0.88f * s)
        close()
    }
    if (filled) drawPath(p, color) else drawPath(p, color, style = Stroke(width = 0.09f * s, join = StrokeJoin.Round))
}

private fun DrawScope.cast(s: Float, color: Color) {
    val c = Offset(0.5f * s, 0.5f * s)
    drawCircle(color, radius = 0.07f * s, center = c)
    for (r in floatArrayOf(0.22f * s, 0.40f * s)) {
        val tl = Offset(c.x - r, c.y - r)
        val sz = Size(2 * r, 2 * r)
        val st = Stroke(width = 0.07f * s, cap = StrokeCap.Round)
        drawArc(color, -40f, 80f, false, tl, sz, style = st)     // sağ dalga
        drawArc(color, 140f, 80f, false, tl, sz, style = st)     // sol dalga
    }
}

@Composable
private fun CtlButton(kind: Ctl, size: Dp, modifier: Modifier = Modifier, heartColor: Color = Color(0xFFFF2D6F), rb: RainbowStyle? = null, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.84f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "press")
    Box(
        modifier.size(48.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size).rainbowFill(rb, 48.dp)) {
            val s = this.size.width
            fun tri(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
                val p = Path().apply { moveTo(x1 * s, y1 * s); lineTo(x2 * s, y2 * s); lineTo(x3 * s, y3 * s); close() }
                drawPath(p, Color.White)
            }
            fun bar(x: Float, w: Float) = drawRoundRect(Color.White, Offset(x * s, 0.12f * s), Size(w * s, 0.76f * s), CornerRadius(0.04f * s))
            when (kind) {
                Ctl.Play -> tri(0.22f, 0.10f, 0.22f, 0.90f, 0.90f, 0.50f)
                Ctl.Pause -> { bar(0.20f, 0.22f); bar(0.58f, 0.22f) }
                Ctl.Next -> { tri(0.08f, 0.15f, 0.08f, 0.85f, 0.68f, 0.50f); bar(0.76f, 0.14f) }
                Ctl.Prev -> { tri(0.92f, 0.15f, 0.92f, 0.85f, 0.32f, 0.50f); bar(0.10f, 0.14f) }
                Ctl.Rew -> { tri(0.50f, 0.20f, 0.50f, 0.80f, 0.04f, 0.50f); tri(0.96f, 0.20f, 0.96f, 0.80f, 0.50f, 0.50f) }
                Ctl.Fwd -> { tri(0.04f, 0.20f, 0.04f, 0.80f, 0.50f, 0.50f); tri(0.50f, 0.20f, 0.50f, 0.80f, 0.96f, 0.50f) }
                Ctl.Heart -> heart(s, false, Color.White.copy(alpha = 0.75f))
                Ctl.HeartOn -> heart(s, true, heartColor)
                Ctl.Cast -> cast(s, Color.White.copy(alpha = 0.75f))
            }
        }
    }
}

/** Dolu beyaz daire içinde oynat/duraklat; basınca yaylanır. 48 dp dokunma alanı, 44 dp görünür daire. */
@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val sc by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMedium), label = "playPress")
    Box(
        Modifier.size(48.dp).graphicsLayer { scaleX = sc; scaleY = sc }.clip(CircleShape)
            .clickable(interactionSource = src, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(44.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(18.dp)) {
                val w = size.width
                val ink = Color(0xFF0B0B0F)
                if (playing) {
                    drawRoundRect(ink, Offset(0.14f * w, 0.08f * w), Size(0.24f * w, 0.84f * w), CornerRadius(0.05f * w))
                    drawRoundRect(ink, Offset(0.62f * w, 0.08f * w), Size(0.24f * w, 0.84f * w), CornerRadius(0.05f * w))
                } else {
                    val p = Path().apply { moveTo(0.22f * w, 0.06f * w); lineTo(0.22f * w, 0.94f * w); lineTo(0.94f * w, 0.5f * w); close() }
                    drawPath(p, ink)
                }
            }
        }
    }
}
