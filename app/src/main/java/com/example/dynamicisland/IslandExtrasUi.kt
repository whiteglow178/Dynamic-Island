package com.example.dynamicisland

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/*
 * YENİ ADA TASARIMLARI
 *  - Zamanlayıcı / alarm: kapalı adada turuncu simge + kalan süre; açık adada duraklat / kapat düğmeleri,
 *    süre dolunca yeniden başlat / kapat; alarm çalınca ertele (zZ) / kapat; adadan zamanlayıcı kurma paneli (cetvel).
 *  - Rahatsız Etmeyin kartı (aya dokunarak aç / kapat), kulaklık küçük adası, ses kaydı,
 *    küçültülmüş gelen arama, maç skorunda takım logoları, yeni navigasyon paneli.
 * Ölçüler [xCompactWidthDp] / [xExpandedSizeDp] ile hem arayüze hem servis penceresine verilir.
 */

internal val X_ORANGE = Color(0xFFFF9F0A)
private val X_GRAY_BTN = Color(0xFF3A3A3C)
private val X_RED = Color(0xFFFF375F)
private val X_GREEN = Color(0xFF30D158)
private val X_PURPLE = Color(0xFF7D7AFF)

// ================================================================ Ölçüler

/** Zamanlayıcı / alarm hapının yüksekliği (açık). */
fun timerPillHeightDp(cfg: IslandSettings): Float =
    if (cfg.more.timerFullWidth) max(cfg.compactHeightDp * 2f, 66f) else max(cfg.compactHeightDp * 1.75f, 60f)

/** Zamanlayıcı kurma panelinin yüksekliği. */
const val TIMER_SET_H_DP = 168f

/** Genişlemiş Rahatsız Etmeyin kartının yüksekliği. */
fun focusCardHeightDp(cfg: IslandSettings): Float = max(cfg.compactHeightDp * 1.75f, 60f)

/** Ses kaydı açık hapının yüksekliği. */
fun voiceRecHeightDp(cfg: IslandSettings): Float = max(cfg.compactHeightDp * 1.5f, 52f)

/** Yeni tasarımların kapalı ada genişliği (dp). null = varsayılan genişlik. Nokta modunda uygulanmaz. */
fun xCompactWidthDp(c: IslandContent, cfg: IslandSettings): Float? {
    if (cfg.dotMode) return null
    return when {
        c is IslandContent.Live && c.kind == LiveKind.Score && cfg.more.scoreLogos -> maxOf(cfg.compactWidthDp, 196f)
        c is IslandContent.Status && c.kind == StatusKind.VoiceRec -> maxOf(cfg.compactWidthDp, 170f)
        c is IslandContent.Status && c.kind == StatusKind.Timer && c.phase <= 1 -> maxOf(cfg.compactWidthDp, 150f)
        else -> null
    }
}

/** Yeni tasarımların açık ada boyutu (genişlik, yükseklik) dp. null = varsayılan. [screenWDp] 0 ise 400 dp sayılır. */
fun xExpandedSizeDp(c: IslandContent, cfg: IslandSettings, screenWDp: Float): Pair<Float, Float>? {
    if (c !is IslandContent.Status) return null
    val sw = if (screenWDp > 0f) screenWDp else 400f
    val wide = sysPillWidthDp(cfg, sw)
    return when {
        c.kind == StatusKind.Timer && c.phase == 3 -> (sw - 8f).coerceAtLeast(wide) to TIMER_SET_H_DP   // ekran kaydındaki gibi tüm genişlik
        c.kind == StatusKind.Timer || c.kind == StatusKind.Alarm ->
            (if (cfg.more.timerFullWidth) (sw - 12f).coerceAtLeast(wide) else wide) to timerPillHeightDp(cfg)
        c.kind == StatusKind.Focus && c.phase == 1 -> wide to focusCardHeightDp(cfg)
        c.kind == StatusKind.VoiceRec -> wide to voiceRecHeightDp(cfg)
        else -> null
    }
}

// ================================================================ Ortak küçük parçalar

/** Yuvarlak düğme (dokunuşu kendisi işler; adanın dokunuşuna geçmez). */
@Composable
private fun RoundBtn(bg: Color, size: Dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(bg, CircleShape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Kronometre simgesi (24 birimlik tuval). */
private fun DrawScope.drawStopwatch(color: Color, sc: Float) {
    val st = Stroke(width = 2.4f * sc, cap = StrokeCap.Round)
    drawCircle(color, radius = 8.2f * sc, center = Offset(12f * sc, 13.5f * sc), style = st)
    drawLine(color, Offset(12f * sc, 13.5f * sc), Offset(12f * sc, 9.2f * sc), strokeWidth = 2.4f * sc, cap = StrokeCap.Round)
    drawLine(color, Offset(9.5f * sc, 2.6f * sc), Offset(14.5f * sc, 2.6f * sc), strokeWidth = 2.4f * sc, cap = StrokeCap.Round)
    drawLine(color, Offset(18.6f * sc, 6.4f * sc), Offset(20f * sc, 5f * sc), strokeWidth = 2.2f * sc, cap = StrokeCap.Round)
}

/** Dolu çalar saat simgesi (alarm). */
private fun DrawScope.drawAlarmClock(color: Color, ink: Color, sc: Float) {
    drawCircle(color, radius = 2.9f * sc, center = Offset(5.2f * sc, 5.2f * sc))
    drawCircle(color, radius = 2.9f * sc, center = Offset(18.8f * sc, 5.2f * sc))
    drawLine(color, Offset(7f * sc, 19.5f * sc), Offset(5.2f * sc, 21.6f * sc), strokeWidth = 2.2f * sc, cap = StrokeCap.Round)
    drawLine(color, Offset(17f * sc, 19.5f * sc), Offset(18.8f * sc, 21.6f * sc), strokeWidth = 2.2f * sc, cap = StrokeCap.Round)
    drawCircle(color, radius = 8.4f * sc, center = Offset(12f * sc, 13f * sc))
    drawLine(ink, Offset(12f * sc, 13f * sc), Offset(12f * sc, 8.6f * sc), strokeWidth = 2f * sc, cap = StrokeCap.Round)
    drawLine(ink, Offset(12f * sc, 13f * sc), Offset(15.2f * sc, 13f * sc), strokeWidth = 2f * sc, cap = StrokeCap.Round)
}

private fun DrawScope.drawX(color: Color, m: Float, w: Float) {
    drawLine(color, Offset(m, m), Offset(size.width - m, size.height - m), strokeWidth = w, cap = StrokeCap.Round)
    drawLine(color, Offset(size.width - m, m), Offset(m, size.height - m), strokeWidth = w, cap = StrokeCap.Round)
}

/** Geri sayım metni: 4:52, 1:04:09; 10 sn altında da dakika gösterilir. */
private fun clockText(ms: Long): String {
    val t = ((ms + 999) / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Zamanlayıcıda kalan süre; kronometrede (extra 2) geçen süre. Çalışıyorsa saniyede birkaç kez yenilenir.
 */
@Composable
private fun rememberRemaining(c: IslandContent.Status): Long {
    val watch = c.extra == 2
    var now by remember(c.endsAt, c.startedAt, c.phase) { mutableLongStateOf(System.currentTimeMillis()) }
    val ticking = c.phase == 0 && (if (watch) c.startedAt > 0L else c.endsAt > 0L)
    if (ticking) LaunchedEffect(c.endsAt, c.startedAt) {
        while (true) { now = System.currentTimeMillis(); delay(if (watch) 100 else 200) }
    }
    return when {
        c.phase == 1 -> c.remainMs
        watch -> (now - c.startedAt).coerceAtLeast(0L)
        c.endsAt > 0L -> (c.endsAt - now).coerceAtLeast(0L)
        else -> 0L
    }
}

/** Kronometre metni: 0:07,42 / 12:05,3 / 1:02:03 */
private fun watchText(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    val h = t / 3_600_000; val m = (t % 3_600_000) / 60_000; val s = (t % 60_000) / 1000; val cs = (t % 1000) / 10
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, s)
        else -> "%d:%02d,%02d".format(m, s, cs)
    }
}

/**
 * Azalan zamanlayıcı halkası (kapalı ada): soluk tam halka + kalan süre oranında turuncu yay.
 * Süre azaldıkça yay saat yönünün tersine kısalır (ekran görüntüsündeki gibi).
 */
private fun DrawScope.drawTimerRing(frac: Float, color: Color) {
    val sw = size.width * 0.16f
    val r = size.minDimension / 2f - sw / 2f
    val tl = Offset(size.width / 2f - r, size.height / 2f - r)
    drawCircle(color.copy(alpha = 0.28f), r, style = Stroke(sw))
    val f = frac.coerceIn(0f, 1f)
    if (f > 0f) drawArc(color, -90f, 360f * f, false, tl, Size(r * 2f, r * 2f), style = Stroke(sw, cap = StrokeCap.Round))
}

// ================================================================ Zamanlayıcı / alarm

/** Kapalı ada: solda turuncu kronometre (alarmda çalar saat), sağda turuncu kalan süre. */
@Composable
internal fun TimerCompact(c: IslandContent.Status, modifier: Modifier = Modifier) {
    val left = rememberRemaining(c)
    val watch = c.extra == 2
    Row(modifier.padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(17.dp)) {
            val sc = size.width / 24f
            when {
                c.kind == StatusKind.Alarm -> drawAlarmClock(X_ORANGE, Color.Black, sc)
                watch -> drawStopwatch(X_ORANGE, sc)
                // Zamanlayıcı: kalan süre oranında azalan halka (toplam bilinmiyorsa tam halka)
                else -> drawTimerRing(if (c.phase == 2) 0f else if (c.totalMs > 0L) left.toFloat() / c.totalMs else 1f, X_ORANGE)
            }
        }
        Spacer(Modifier.weight(1f))
        val txt = when {
            c.kind == StatusKind.Alarm -> c.value
            watch -> watchText(left).substringBefore(',')
            c.phase == 2 -> "0:00"
            else -> clockText(left)
        }
        Text(txt, color = X_ORANGE, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1, softWrap = false)
    }
}

/**
 * Açık zamanlayıcı / alarm hapı (ekran kaydındaki iOS tasarımı):
 *  - çalışıyor: [turuncu duraklat] [gri X] ........ "Zamanlayıcı" 4:52
 *  - duraklatıldı: duraklat yerine oynat simgesi
 *  - süre doldu: [turuncu yeniden başlat] [gri X] ........ "Zamanlayıcı"
 *  - alarm çalıyor: [çalar saat] "Alarm 20:41" ........ [turuncu zZ] [gri X]
 */
@Composable
internal fun TimerPanel(c: IslandContent.Status, heightDp: Float, rb: RainbowStyle?) {
    val btn = (heightDp * 0.66f).coerceIn(36f, 52f).dp
    val orangeBg = X_ORANGE.copy(alpha = 0.30f)
    if (c.kind == StatusKind.Alarm && c.phase == 1) {
        // Alarm kuruldu bilgisi: [çalar saat] "Alarm 07:30" ............ "8 sa 12 dk sonra"
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size((heightDp * 0.5f).coerceIn(22f, 32f).dp)) {
                drawAlarmClock(X_ORANGE, Color.Black, size.width / 24f)
            }
            Spacer(Modifier.width(8.dp))
            Text("${c.title.ifBlank { "Alarm" }} ${c.value}", color = X_ORANGE, fontWeight = FontWeight.SemiBold,
                fontSize = (heightDp * 0.30f).coerceIn(15f, 21f).sp, maxLines = 1, modifier = Modifier.weight(1f))
            Text(c.subtitle.ifBlank { "Kuruldu" }, color = X_ORANGE.copy(alpha = 0.7f), fontWeight = FontWeight.Medium,
                fontSize = (heightDp * 0.24f).coerceIn(12f, 16f).sp, maxLines = 1)
        }
        return
    }
    if (c.kind == StatusKind.Alarm) {
        Row(Modifier.fillMaxSize().padding(start = 16.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size((heightDp * 0.5f).coerceIn(22f, 32f).dp)) {
                drawAlarmClock(X_ORANGE, Color.Black, size.width / 24f)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                listOf(c.title.ifBlank { "Alarm" }, c.value).filter { it.isNotBlank() }.joinToString(" "),
                color = X_ORANGE, fontWeight = FontWeight.SemiBold, fontSize = (heightDp * 0.30f).coerceIn(15f, 21f).sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            RoundBtn(orangeBg, btn, { IslandActions.onAlarmSnooze() }) {
                Text("zZ", color = X_ORANGE, fontWeight = FontWeight.Bold, fontSize = (btn.value * 0.34f).sp)
            }
            Spacer(Modifier.width(8.dp))
            RoundBtn(X_GRAY_BTN, btn, { IslandActions.onAlarmDismiss() }) {
                Canvas(Modifier.size(btn * 0.36f)) { drawX(Color.White, 0f, 2.6.dp.toPx()) }
            }
        }
        return
    }
    val left = rememberRemaining(c)
    Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        val done = c.phase == 2
        RoundBtn(orangeBg, btn, { if (done) IslandActions.onTimerRestart() else IslandActions.onTimerPause() }) {
            Canvas(Modifier.size(btn * 0.42f)) {
                val w = size.width; val h = size.height
                when {
                    done -> {
                        // yeniden başlat: ok uçlu yay
                        val st = Stroke(width = w * 0.15f, cap = StrokeCap.Round)
                        drawArc(X_ORANGE, -60f, 300f, false, Offset(w * 0.1f, h * 0.1f), Size(w * 0.8f, h * 0.8f), style = st)
                        val tip = Path().apply {
                            moveTo(w * 0.70f, h * 0.02f); lineTo(w * 0.98f, h * 0.30f); lineTo(w * 0.62f, h * 0.36f); close()
                        }
                        drawPath(tip, X_ORANGE)
                    }
                    c.phase == 1 -> {
                        val p = Path().apply { moveTo(w * 0.18f, 0f); lineTo(w, h / 2f); lineTo(w * 0.18f, h); close() }
                        drawPath(p, X_ORANGE)
                    }
                    else -> {
                        val bw = w * 0.30f
                        drawRoundRect(X_ORANGE, Offset(w * 0.10f, 0f), Size(bw, h), CornerRadius(bw * 0.3f))
                        drawRoundRect(X_ORANGE, Offset(w * 0.60f, 0f), Size(bw, h), CornerRadius(bw * 0.3f))
                    }
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        RoundBtn(X_GRAY_BTN, btn, { if (done) IslandActions.onAlarmDismiss() else IslandActions.onTimerCancel() }) {
            Canvas(Modifier.size(btn * 0.36f)) { drawX(Color.White, 0f, 2.6.dp.toPx()) }
        }
        Spacer(Modifier.weight(1f))
        if (done) {
            Text(c.title.ifBlank { "Zamanlayıcı" }, color = X_ORANGE, fontWeight = FontWeight.SemiBold,
                fontSize = (heightDp * 0.34f).coerceIn(17f, 24f).sp, maxLines = 1, modifier = Modifier.rainbowFill(rb))
        } else {
            val watch = c.extra == 2
            Text(c.title.ifBlank { if (watch) "Kronometre" else "Zamanlayıcı" }, color = X_ORANGE, fontWeight = FontWeight.Medium,
                fontSize = (heightDp * 0.24f).coerceIn(12f, 16f).sp, maxLines = 1,
                modifier = Modifier.padding(top = (heightDp * 0.12f).dp))
            Spacer(Modifier.width(8.dp))
            // Zamanlayıcıda süreye dokununca kurma paneli (cetvel) açılır
            Text(if (watch) watchText(left) else clockText(left), color = X_ORANGE, fontWeight = FontWeight.Normal,
                fontSize = (heightDp * 0.62f).coerceIn(26f, 44f).sp, maxLines = 1, softWrap = false,
                modifier = Modifier.rainbowFill(rb).then(
                    if (watch) Modifier
                    else Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        IslandActions.onOpenTimerSetter()
                    }
                ))
        }
    }
}

/** Zamanlayıcı kurma paneli durumu (seçili dakika). Panel her açıldığında son seçim korunur. */
object TimerSetSession {
    val minutes = MutableStateFlow(5)
}

/**
 * Zamanlayıcı kurma paneli (ekran kaydındaki "yeni zamanlayıcı" tasarımı): üstte sola / sağa kaydırılan turuncu cetvel
 * (her çizgi 1 dakika, 5'in katlarında sayı), altta "Başlat" düğmesi ve büyük turuncu süre.
 */
@Composable
internal fun TimerSetPanel() {
    val mins by TimerSetSession.minutes.collectAsState()
    // Kaydırırken geçilen çizgiler parlar ve iz bırakarak söner (dakika -> parlama başlangıcı)
    val glow = remember { HashMap<Int, Long>() }
    var glowUntil by remember { mutableLongStateOf(0L) }
    var frameT by remember { mutableLongStateOf(0L) }
    LaunchedEffect(glowUntil) {
        while (android.os.SystemClock.uptimeMillis() < glowUntil) {
            withFrameMillis { frameT = android.os.SystemClock.uptimeMillis() }
        }
        frameT = android.os.SystemClock.uptimeMillis()
        glow.clear()
    }
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 20.dp, top = 14.dp, bottom = 14.dp)) {
        var acc by remember { mutableStateOf(0f) }
        Canvas(
            Modifier.fillMaxWidth().weight(1f)
                .pointerInput(Unit) {
                    val step = TIMER_TICK_DP.dp.toPx()
                    detectHorizontalDragGestures(onDragEnd = { acc = 0f }, onDragCancel = { acc = 0f }) { change, drag ->
                        change.consume()
                        acc -= drag                                  // sola kaydırınca süre artar (cetvel sola kayar)
                        val n = (acc / step).toInt()
                        if (n != 0) {
                            acc -= n * step
                            val old = TimerSetSession.minutes.value
                            val nv = (old + n).coerceIn(1, 180)
                            if (nv != old) {
                                val t = android.os.SystemClock.uptimeMillis()
                                val lo = minOf(old, nv); val hi = maxOf(old, nv)
                                for (m in lo..hi) glow[m] = t
                                glowUntil = t + TIMER_GLOW_MS
                                TimerSetSession.minutes.value = nv
                            }
                        }
                    }
                },
        ) {
            val now = frameT.let { if (it == 0L) android.os.SystemClock.uptimeMillis() else it }
            val step = TIMER_TICK_DP.dp.toPx()
            val cx = size.width / 2f
            val base = size.height * 0.94f
            val tickH = size.height * 0.40f
            val fade = size.width / 2f
            val first = mins - (cx / step).toInt() - 1
            val last = mins + (cx / step).toInt() + 1
            val paint = android.graphics.Paint().apply {
                isAntiAlias = true
                textSize = 12.sp.toPx()
                textAlign = android.graphics.Paint.Align.CENTER
                isFakeBoldText = true
            }
            val sw = 2.dp.toPx()
            for (m in first..last) {
                if (m < 0) continue
                val x = cx + (m - mins) * step
                // kenarlara doğru sönerek kaybolur
                val edge = (1f - abs(x - cx) / fade).coerceIn(0f, 1f)
                val a = edge * edge * (3f - 2f * edge)
                if (a <= 0.02f) continue
                // seçilen süre (işaretin solu) parlak turuncu, sağı soluk
                val base0 = if (m <= mins) 1f else 0.42f
                val g = glow[m]?.let { (1f - (now - it).toFloat() / TIMER_GLOW_MS).coerceIn(0f, 1f) } ?: 0f
                val h = tickH * (1f + 0.10f * g)
                if (g > 0f) {
                    // yumuşak hale + sarıya dönen parlak çizgi
                    drawLine(X_GLOW.copy(alpha = a * g * 0.35f), Offset(x, base - h), Offset(x, base),
                        strokeWidth = sw * 3.4f, cap = StrokeCap.Round)
                }
                val col = lerp(X_ORANGE, X_GLOW, g)
                drawLine(col.copy(alpha = a * (base0 + (1f - base0) * g)), Offset(x, base - h), Offset(x, base),
                    strokeWidth = sw, cap = StrokeCap.Round)
                if (m % 5 == 0 && m > 0) {
                    val la = a * (if (m <= mins) 0.95f else 0.55f)
                    paint.color = android.graphics.Color.argb((la * 255).toInt(), 255, 255, 255)
                    drawContext.canvas.nativeCanvas.drawText(m.toString(), x, base - tickH - 8.dp.toPx(), paint)
                }
            }
            // seçili çizginin altında küçük turuncu üçgen
            val ty = base + 4.dp.toPx()
            val tri = Path().apply {
                moveTo(cx, ty); lineTo(cx - 5.dp.toPx(), ty + 7.dp.toPx()); lineTo(cx + 5.dp.toPx(), ty + 7.dp.toPx()); close()
            }
            drawPath(tri, X_ORANGE)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(X_ORANGE.copy(alpha = 0.22f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        IslandActions.onTimerStart(TimerSetSession.minutes.value * 60_000L)
                    }
                    .padding(horizontal = 16.dp, vertical = 9.dp),
            ) {
                Text("Zamanlayıcıyı Başlat", color = X_ORANGE, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1)
            }
            Spacer(Modifier.weight(1f))
            val h = mins / 60; val m = mins % 60
            Text(if (h > 0) "%d:%02d:00".format(h, m) else "%02d:00".format(m), color = X_ORANGE,
                fontWeight = FontWeight.Normal, fontSize = 44.sp, maxLines = 1, softWrap = false)
        }
    }
}

private const val TIMER_TICK_DP = 7f
private const val TIMER_GLOW_MS = 650L
private val X_GLOW = Color(0xFFFFE27A)

// ================================================================ Rahatsız Etmeyin kartı

/**
 * Genişlemiş Rahatsız Etmeyin (ekran kaydındaki gibi): solda dairede mor ay (aç / kapat düğmesi), ortada
 * "Rahatsız Etmeyin", sağda mor "Açık" ya da gri "Kapalı".
 */
@Composable
internal fun FocusCardContent(c: IslandContent.Status, heightDp: Float, toggle: Boolean, rb: RainbowStyle?) {
    val on = c.extra == 1
    val circle = (heightDp * 0.72f).coerceIn(38f, 54f).dp
    Row(Modifier.fillMaxSize().padding(start = 10.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(circle).clip(CircleShape).background(if (on) Color.White else X_GRAY_BTN)
                .then(
                    if (toggle) Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        IslandActions.onFocusToggle()
                    } else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(circle * 0.56f)) {
                val sc = size.width / 24f
                withTransform({ scale(sc, sc, Offset.Zero) }) { drawMoonGlyph(if (on) X_PURPLE else Color.White) }
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(c.title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = (heightDp * 0.27f).coerceIn(15f, 19f).sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).rainbowFill(rb))
        Text(c.value, color = if (on) X_PURPLE else Color.White.copy(alpha = 0.55f), fontWeight = FontWeight.SemiBold,
            fontSize = (heightDp * 0.27f).coerceIn(15f, 19f).sp, maxLines = 1)
    }
}

// ================================================================ Kulaklık (küçük ada)

/** Kulaklık bağlandı, küçük ada: solda kulaklık simgesi, sağda yeşil pil halkası (pil bilinmiyorsa dolu halka). */
@Composable
internal fun HeadphoneCompact(c: IslandContent.Status, chDp: Float, modifier: Modifier = Modifier) {
    val ic = (chDp * 0.58f).coerceIn(14f, 24f).dp
    Row(modifier.padding(start = 10.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(ic)) {
            val sc = size.width / 24f
            withTransform({ scale(sc, sc, Offset.Zero) }) { drawHeadphoneGlyph(Color.White, c.extra == 1) }
        }
        Spacer(Modifier.weight(1f))
        Canvas(Modifier.size(ic)) {
            val sw = 2.4.dp.toPx()
            val r = size.minDimension / 2f - sw / 2f
            val pct = if (c.progress >= 0f) c.progress.coerceIn(0f, 1f) else 1f
            val col = if (c.progress in 0f..0.2f) Color(0xFFFF453A) else X_GREEN
            drawCircle(col.copy(alpha = 0.25f), r, style = Stroke(sw))
            drawArc(col, -90f, 360f * pct, false, Offset(size.width / 2f - r, size.height / 2f - r), Size(r * 2f, r * 2f),
                style = Stroke(sw, cap = StrokeCap.Round))
        }
    }
}

// ================================================================ Ses kaydı

/** Hareketli kayıt dalgası: sağda (en yeni) kırmızı çubuklar, solda sönerek noktaya dönen eski kayıt. */
@Composable
internal fun VoiceWave(modifier: Modifier, active: Boolean, color: Color = X_RED) {
    val tr = rememberInfiniteTransition(label = "vrec")
    val ph by tr.animateFloat(0f, 1000f, infiniteRepeatable(tween(400_000, easing = LinearEasing)), label = "vrecPh")
    Canvas(modifier) {
        val gap = 3.2.dp.toPx()
        val bw = 1.8.dp.toPx()
        val n = (size.width / gap).toInt().coerceAtLeast(4)
        val shift = if (active) ph * 2.5f else 0f               // dalga sola akar
        val off = shift - shift.toInt()
        for (i in 0 until n) {
            val k = i + shift.toInt()
            val t = i / (n - 1f)
            // sözde rastgele genlik (çubuk kimliğine bağlı: kayarken titremez)
            val r = abs(sin(k * 12.9898f) * 43758.547f) % 1f
            val amp = if (active) 0.25f + 0.75f * r else 0.15f
            val env = (t * 1.6f).coerceIn(0.12f, 1f)          // soldakiler küçülüp nokta olur
            val h = max(bw, size.height * amp * env)
            val x = (i - off) * gap + gap / 2f
            val a = (0.35f + 0.65f * t).coerceIn(0f, 1f)
            drawLine(color.copy(alpha = a), Offset(x, (size.height - h) / 2f), Offset(x, (size.height + h) / 2f),
                strokeWidth = bw, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun rememberElapsed(c: IslandContent.Status): Long {
    var now by remember(c.startedAt, c.phase) { mutableLongStateOf(System.currentTimeMillis()) }
    if (c.phase == 0 && c.startedAt > 0L) LaunchedEffect(c.startedAt) {
        while (true) { now = System.currentTimeMillis(); delay(250) }
    }
    return if (c.phase == 0 && c.startedAt > 0L) (now - c.startedAt).coerceAtLeast(0L) else c.remainMs
}

private fun elapsedText(ms: Long): String {
    val t = (ms / 1000).coerceAtLeast(0)
    val h = t / 3600; val m = (t % 3600) / 60; val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** Kapalı ada (ses kaydı): solda kırmızı dalga, sağda kırmızı süre. */
@Composable
internal fun VoiceRecCompact(c: IslandContent.Status, chDp: Float, modifier: Modifier = Modifier) {
    val el = rememberElapsed(c)
    Row(modifier.padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        VoiceWave(Modifier.width(54.dp).height((chDp * 0.5f).coerceIn(8f, 20f).dp), active = c.phase == 0)
        Spacer(Modifier.weight(1f))
        Text(elapsedText(el), color = X_RED, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

/** Açık ada (ses kaydı): solda uzun dalga, sağda süre ve durdur düğmesi (kırmızı halka içinde kare). */
@Composable
internal fun VoiceRecPanel(c: IslandContent.Status, heightDp: Float) {
    val el = rememberElapsed(c)
    val btn = (heightDp * 0.7f).coerceIn(32f, 46f).dp
    Row(Modifier.fillMaxSize().padding(start = 18.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        VoiceWave(Modifier.weight(1f).height((heightDp * 0.45f).dp), active = c.phase == 0)
        Spacer(Modifier.width(12.dp))
        Text(elapsedText(el), color = X_RED, fontWeight = FontWeight.SemiBold, fontSize = (heightDp * 0.34f).coerceIn(15f, 20f).sp, maxLines = 1)
        Spacer(Modifier.width(10.dp))
        RoundBtn(Color.Transparent, btn, { IslandActions.onVoiceStop() }) {
            Canvas(Modifier.size(btn)) {
                val sw = 2.5.dp.toPx()
                drawCircle(X_RED, size.minDimension / 2f - sw, style = Stroke(sw))
                val q = size.width * 0.34f
                drawRoundRect(X_RED, Offset((size.width - q) / 2f, (size.height - q) / 2f), Size(q, q), CornerRadius(q * 0.22f))
            }
        }
    }
}

// ================================================================ Arama (küçük ada)

/** Küçültülmüş gelen arama: solda arayan kişinin resmi (yoksa baş harf), sağda gri ters telefon. */
@Composable
internal fun IncomingCallCompact(c: IslandContent.Call, chDp: Float, modifier: Modifier = Modifier) {
    val av = (chDp - 10f).coerceAtLeast(14f).dp
    Row(modifier.padding(start = 5.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val a = c.avatar
        if (a != null) {
            Image(bitmap = a, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(av).clip(CircleShape))
        } else {
            Box(Modifier.size(av).clip(CircleShape).background(Color(0xFF8E8E93)), contentAlignment = Alignment.Center) {
                Text(c.name.trim().take(1).uppercase().ifEmpty { "?" }, color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = (av.value * 0.45f).sp)
            }
        }
        Spacer(Modifier.weight(1f))
        PhoneIcon(135f, Modifier.size((chDp * 0.5f).coerceIn(12f, 20f).dp), Color(0xFF8E8E93))
    }
}

// ================================================================ Maç skoru: takım logoları

/** Takım logoları: TheSportsDB (anahtarsız deneme anahtarı) ile ada göre bulunur, bellekte saklanır. */
object TeamLogos {
    private val cache = HashMap<String, ImageBitmap?>()
    private val failedAt = HashMap<String, Long>()

    fun cached(name: String): ImageBitmap? = synchronized(cache) { cache[name.lowercase()] }

    suspend fun get(name: String): ImageBitmap? {
        val key = name.trim().lowercase()
        if (key.isBlank()) return null
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
            val f = failedAt[key]
            if (f != null && System.currentTimeMillis() - f < 10 * 60_000L) return null
        }
        val bmp = withContext(Dispatchers.IO) {
            fetch(name.trim()) ?: fetch(ascii(name.trim())) ?: name.trim().split(' ').firstOrNull()?.takeIf { it.length >= 4 }?.let { fetch(ascii(it)) }
        }
        synchronized(cache) {
            if (bmp != null) cache[key] = bmp else failedAt[key] = System.currentTimeMillis()
        }
        return bmp
    }

    private fun ascii(s: String): String = s
        .replace('ı', 'i').replace('İ', 'I').replace('ş', 's').replace('Ş', 'S').replace('ğ', 'g').replace('Ğ', 'G')
        .replace('ü', 'u').replace('Ü', 'U').replace('ö', 'o').replace('Ö', 'O').replace('ç', 'c').replace('Ç', 'C')

    private fun fetch(name: String): ImageBitmap? = runCatching {
        val q = java.net.URLEncoder.encode(name, "UTF-8")
        val body = http("https://www.thesportsdb.com/api/v1/json/3/searchteams.php?t=$q") ?: return@runCatching null
        val arr = org.json.JSONObject(body).optJSONArray("teams") ?: return@runCatching null
        if (arr.length() == 0) return@runCatching null
        val o = arr.getJSONObject(0)
        val url = o.optString("strBadge").ifBlank { o.optString("strTeamBadge") }.ifBlank { return@runCatching null }
        val conn = java.net.URL("$url/small").openConnection() as java.net.HttpURLConnection
        val bytes = try {
            conn.connectTimeout = 4_000; conn.readTimeout = 4_000
            if (conn.responseCode != 200) null else conn.inputStream.use { it.readBytes() }
        } finally { conn.disconnect() }
        val data = bytes ?: run {
            val c2 = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            try { c2.connectTimeout = 4_000; c2.readTimeout = 4_000; c2.inputStream.use { it.readBytes() } } finally { c2.disconnect() }
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 96) sample *= 2
        val b = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
        b?.asImageBitmap()
    }.getOrNull()

    private fun http(url: String): String? {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        return try {
            conn.connectTimeout = 4_000; conn.readTimeout = 4_000
            if (conn.responseCode != 200) null else conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
    }
}

/** Takım logosu; bulunamazsa takımın baş harfi renkli dairede. */
@Composable
internal fun TeamLogo(name: String, size: Dp, modifier: Modifier = Modifier) {
    val logo by produceState(TeamLogos.cached(name), name) { value = TeamLogos.get(name) }
    val img = logo
    if (img != null) {
        Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = modifier.size(size))
    } else {
        val hue = ((name.hashCode() and 0x7fffffff) % 360).toFloat()
        val col = Color.hsv(hue, 0.55f, 0.85f)
        Box(modifier.size(size).clip(CircleShape).background(col), contentAlignment = Alignment.Center) {
            Text(name.trim().take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.48f).sp, maxLines = 1)
        }
    }
}

/**
 * Kapalı ada (maç skoru, logolu): solda logo + skor; kamera deliğinin hemen sağında yeşil dakika, sonra skor + logo.
 */
@Composable
internal fun ScoreCompact(c: IslandContent.Live, chDp: Float, modifier: Modifier = Modifier) {
    val lg = (chDp - 10f).coerceIn(14f, 26f).dp
    val fs = (chDp * 0.46f).coerceIn(12f, 17f).sp
    Box(modifier) {
        Row(Modifier.fillMaxSize().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TeamLogo(c.teamA, lg)
            Spacer(Modifier.width(6.dp))
            Text(c.scoreA.coerceAtLeast(0).toString(), color = if (c.goal) X_GREEN else Color.White, fontWeight = FontWeight.Bold,
                fontSize = fs, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(c.scoreB.coerceAtLeast(0).toString(), color = if (c.goal) X_GREEN else Color.White, fontWeight = FontWeight.Bold,
                fontSize = fs, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            TeamLogo(c.teamB, lg)
        }
        val status = when {
            c.phase == 2 -> "MS"
            c.phase == 1 -> "İY"
            else -> c.minute
        }
        if (status.isNotBlank()) {
            // Ortadaki kamera deliğinin sağında
            Text(status, color = X_GREEN, fontWeight = FontWeight.SemiBold, fontSize = (chDp * 0.32f).coerceIn(9f, 12f).sp, maxLines = 1,
                modifier = Modifier.align(Alignment.Center).offset(x = (chDp * 0.75f).dp))
        }
    }
}

// ================================================================ Navigasyon (yeni panel)

private val navDistRx = Regex("""(\d+(?:[.,]\d+)?)\s?(km|mi)\b""", RegexOption.IGNORE_CASE)
private val navEtaRx = Regex("""\b(\d{1,2}[:.]\d{2})\s*(AM|PM|ÖÖ|ÖS)?\b""", RegexOption.IGNORE_CASE)
private val navDurRx = Regex("""(?:(\d{1,2})\s*(?:sa|saat|h|hr|hrs|ч)\.?\s*)?(\d{1,3})\s*(?:dk|dakika|min|mins|мин)\b""", RegexOption.IGNORE_CASE)
private val navHourRx = Regex("""(\d{1,3})\s*(?:sa|saat|h|hr|hrs|ч)\b""", RegexOption.IGNORE_CASE)

private fun DrawScope.drawPin(color: Color) {
    val w = size.width; val h = size.height
    val p = Path().apply {
        moveTo(w / 2f, h)
        cubicTo(w * 0.15f, h * 0.62f, 0f, h * 0.48f, 0f, h * 0.36f)
        cubicTo(0f, h * 0.12f, w * 0.22f, 0f, w / 2f, 0f)
        cubicTo(w * 0.78f, 0f, w, h * 0.12f, w, h * 0.36f)
        cubicTo(w, h * 0.48f, w * 0.85f, h * 0.62f, w / 2f, h)
        close()
    }
    drawPath(p, color)
    drawCircle(Color.White, w * 0.16f, Offset(w / 2f, h * 0.35f))
}

/** Sağa dönüş oku (bildirimde ok yoksa). */
private fun DrawScope.drawTurnRight(color: Color) {
    val w = size.width; val h = size.height
    val st = Stroke(width = w * 0.16f, cap = StrokeCap.Round)
    val p = Path().apply { moveTo(w * 0.28f, h * 0.95f); lineTo(w * 0.28f, h * 0.50f); quadraticBezierTo(w * 0.28f, h * 0.32f, w * 0.48f, h * 0.32f); lineTo(w * 0.70f, h * 0.32f) }
    drawPath(p, color, style = st)
    val tip = Path().apply { moveTo(w * 0.66f, h * 0.10f); lineTo(w * 0.96f, h * 0.32f); lineTo(w * 0.66f, h * 0.54f); close() }
    drawPath(tip, color)
}

/**
 * Açık navigasyon adası (ekran kaydındaki tasarım): solda büyük dönüş oku, yanında mesafe (kalın) ve yön (gri),
 * sağ üstte kırmızı konum iğnesi; altında yeşil rota çizgisi ve sarı konum oku; en altta kalan km, varış saati, kalan süre.
 */
@Composable
internal fun NavPanel(c: IslandContent.Live, rb: RainbowStyle?) {
    val all = listOf(c.text, c.sub, c.title).joinToString(" · ")
    val km = navDistRx.findAll(all).map { it.value }.firstOrNull { it.replace(" ", "") != c.big.replace(" ", "") }.orEmpty()
    val eta = navEtaRx.find(all)?.value.orEmpty()
    val dur = navDurRx.find(all)?.let { m ->
        val h = m.groupValues[1]; val mm = m.groupValues[2]
        if (h.isNotBlank()) "$h sa $mm dk" else "$mm dk"
    } ?: navHourRx.find(all)?.let { it.groupValues[1] + " sa" }.orEmpty()
    Column(Modifier.fillMaxSize().padding(start = 18.dp, end = 16.dp, top = 12.dp, bottom = 10.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
            val ic = c.icon
            if (ic != null) {
                Image(bitmap = ic, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(46.dp))
            } else {
                Canvas(Modifier.size(42.dp)) { drawTurnRight(Color.White) }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                if (c.big.isNotBlank()) Text(c.big, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 28.sp, maxLines = 1,
                    modifier = Modifier.rainbowFill(rb))
                if (c.title.isNotBlank()) Text(c.title, color = Color.White.copy(alpha = 0.62f), fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Canvas(Modifier.size(width = 20.dp, height = 26.dp).align(Alignment.Top)) { drawPin(Color(0xFFFF3B30)) }
        }
        val prog = if (c.progress in 0f..1f) c.progress else 0f
        Canvas(Modifier.fillMaxWidth().height(16.dp)) {
            val y = size.height / 2f
            val lw = 4.dp.toPx()
            val ax = 6.dp.toPx() + (size.width - 12.dp.toPx()) * prog
            drawLine(Color.White.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), strokeWidth = lw, cap = StrokeCap.Round)
            drawLine(Color(0xFF7ED321), Offset(ax, y), Offset(size.width - lw, y), strokeWidth = lw, cap = StrokeCap.Round)
            // sarı konum oku (sağa bakan)
            val s = 8.dp.toPx()
            val arrow = Path().apply {
                moveTo(ax + s, y); lineTo(ax - s * 0.9f, y - s * 0.75f); lineTo(ax - s * 0.45f, y); lineTo(ax - s * 0.9f, y + s * 0.75f); close()
            }
            drawPath(arrow, Color(0xFFFFD60A))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            if (km.isBlank() && eta.isBlank() && dur.isBlank()) {
                Text(listOf(c.sub, c.text).firstOrNull { it.isNotBlank() }.orEmpty(), color = Color.White.copy(alpha = 0.7f),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                Text(km, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    modifier = Modifier.weight(1f))
                Text(eta, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(dur, color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** Kapalı navigasyon adası: solda beyaz dönüş oku (bildirimin oku), sağda beyaz mesafe. */
@Composable
internal fun NavCompact(c: IslandContent.Live, chDp: Float, modifier: Modifier = Modifier) {
    val ic = (chDp - 12f).coerceIn(14f, 24f).dp
    Row(modifier.padding(start = 10.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        val img = c.icon
        if (img != null) Image(bitmap = img, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(ic))
        else Canvas(Modifier.size(ic)) { drawTurnRight(Color.White) }
        Spacer(Modifier.weight(1f))
        Text(c.big, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, softWrap = false)
    }
}

// ================================================================ Kapalı ada katmanı (IslandPill'den çağrılır)

/**
 * Yeni tasarımların kapalı ada içeriği. [alpha] ada açılırken solma değeridir. Çizdiyse true döner
 * (çağıran taraf aynı içerik için eski kapalı görünümü çizmez).
 */
@Composable
internal fun XCompactLayer(content: IslandContent, cfg: IslandSettings, cw: Dp, ch: Dp, alpha: () -> Float) {
    val mod = Modifier.fillMaxSize().graphicsAlpha(alpha)
    Box(Modifier.size(cw, ch)) {
        when {
            content is IslandContent.Status && (content.kind == StatusKind.Timer || content.kind == StatusKind.Alarm) && content.phase != 3 ->
                TimerCompact(content, mod)
            content is IslandContent.Status && content.kind == StatusKind.VoiceRec -> VoiceRecCompact(content, ch.value, mod)
            content is IslandContent.Status && content.kind == StatusKind.Headphones -> HeadphoneCompact(content, ch.value, mod)
            content is IslandContent.Call && content.isIncoming -> IncomingCallCompact(content, ch.value, mod)
            content is IslandContent.Live && content.kind == LiveKind.Score && cfg.more.scoreLogos -> ScoreCompact(content, ch.value, mod)
            content is IslandContent.Live && content.kind == LiveKind.Nav -> NavCompact(content, ch.value, mod)
        }
    }
}

/** Yeni kapalı ada tasarımı bu içerik için var mı? */
internal fun hasXCompact(content: IslandContent, cfg: IslandSettings): Boolean = when {
    cfg.dotMode -> false
    content is IslandContent.Status && (content.kind == StatusKind.Timer || content.kind == StatusKind.Alarm) && content.phase != 3 -> true
    content is IslandContent.Status && content.kind == StatusKind.VoiceRec -> true
    content is IslandContent.Status && content.kind == StatusKind.Headphones -> true
    content is IslandContent.Call && content.isIncoming && !cfg.pillCallStyle -> true
    content is IslandContent.Live && content.kind == LiveKind.Score && cfg.more.scoreLogos -> true
    content is IslandContent.Live && content.kind == LiveKind.Nav -> true
    else -> false
}

private fun Modifier.graphicsAlpha(a: () -> Float): Modifier = this.graphicsLayer { alpha = a() }
