package com.example.dynamicisland

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * Adanın (kapalı halde) ekran üzerindeki konumu (piksel) ve animasyon hızı.
 * speed: 1 = normal, 2 = iki kat hızlı, 0.5 = yarı hızlı (ayarlardan gelir).
 */
class LaunchSpec(val cx: Float, val top: Float, val w: Float, val h: Float, val speed: Float = 1f, val color: Color = Color.Black)

// ---- Ayar noktaları
private const val BASE_MS = 650            // hız 1x iken adanın altından çıkıp ekranı kaplama süresi
private const val HOLD_MS = 350L           // ekran kaplandıktan sonra siyahın bekleme süresi (uygulama açılırken)
private const val FADE_MS = 240            // siyahın kalkması
private const val CORNER_DP = 44f          // büyürken köşe yuvarlaklığı (telefon ekran köşesine yakın)
private const val MARGIN_DP = 20f          // son boyutun ekranı taşma payı (köşe yarıçapının en az ~%30'u olmalı)
private const val START_W_DP = 46f         // adanın altından çıkan küçük şeklin başlangıç genişliği
private const val START_H_DP = 30f         // ... ve yüksekliği

// Yavaş başlar, yaklaştıkça hızlanır, sonda yumuşakça oturur
private val ApproachEasing = CubicBezierEasing(0.55f, 0f, 0.25f, 1f)

/**
 * Adanın altından küçük bir siyah şekil çıkar ve ekrana yaklaşıyormuş gibi gitgide büyüyerek tüm ekranı kaplar.
 * Büyüme üstel (katlanarak) olduğu için başta yavaş, yaklaştıkça hızlı hissedilir.
 * Ekran tamamen kapanınca [onCovered] çağrılır (uygulamayı o an aç); siyah bir süre sonra solar ve [onFinished] gelir.
 */
@Composable
fun LaunchTransition(spec: LaunchSpec, onCovered: () -> Unit, onFinished: () -> Unit) {
    val covered by rememberUpdatedState(onCovered)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ms = (BASE_MS / spec.speed.coerceIn(0.25f, 4f)).toInt()

    LaunchedEffect(Unit) {
        a.animateTo(1f, tween(ms, easing = ApproachEasing))
        covered()                                      // ekran tamamen siyah: uygulamayı aç
        delay(HOLD_MS)
        fade.animateTo(0f, tween(FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .graphicsLayer { alpha = fade.value }
    ) {
        val d = density
        val m = MARGIN_DP * d                          // ekran dışına taşma payı: yuvarlak köşeler ekranın dışında kalır
        val sw = size.width + 2 * m
        val sh = size.height + 2 * m
        val t = a.value

        // Başlangıç: adanın alt kenarında küçük bir şekil; bitiş: tüm ekran
        val w0 = START_W_DP * d
        val h0 = START_H_DP * d
        val cx0 = spec.cx
        val cy0 = spec.top + spec.h + h0 * 0.15f
        val w = w0 * (sw / w0).pow(t)                  // üstel büyüme: yaklaşma hissi
        val h = h0 * (sh / h0).pow(t)
        val cx = cx0 + (size.width / 2f - cx0) * t
        val cy = cy0 + (size.height / 2f - cy0) * t
        val alpha = (t * 12f).coerceIn(0f, 1f)         // ilk anda yumuşakça belirir
        val radius = minOf(CORNER_DP * d, minOf(w, h) / 2f)   // köşe yarıçapı büyürken sabit kalır: sivrilme olmaz

        translate(-origin.x, -origin.y) {
            if (t >= 1f) {
                drawRect(spec.color, Offset(-m, -m), Size(sw, sh))
            } else {
                drawRoundRect(spec.color.copy(alpha = spec.color.alpha * alpha), Offset(cx - w / 2f, cy - h / 2f), Size(w, h), CornerRadius(radius))
            }
        }
    }
}

// ---- Müzik uygulamasından çıkış: uzun (telefon oranlı) panel adaya küçülür
private const val SHRINK_BASE_MS = 320     // hız 1x iken küçülme süresi
private const val SHRINK_FADE_MS = 70      // adaya varınca silinmesi
private const val SHRINK_APPEAR_MS = 20    // yumuşakça belirme (ani kararma olmasın)
private const val PANEL_DELAY_MS = 20L     // ada açıldıktan sonra panelin başlama gecikmesi (hız 1x)
private const val SWALLOW_AT = 0.10f       // küçülmenin bu noktasında ada büyüyüp yutmaya başlar
private const val START_W_FRAC = 0.62f     // başlangıç genişliği (ekran genişliğine oranı); yükseklik ekran oranıyla gelir
private const val START_TOP_FRAC = 0.30f   // başlangıçta panelin üst kenarı (ekran yüksekliğine oranı)
private const val END_W_DP = 34f           // adaya girerken ince uzun şekil
private const val END_H_DP = 56f

private val ShrinkEasing = CubicBezierEasing(0.3f, 0f, 0.5f, 1f)

/**
 * Uygulama ekranı gibi uzun (ekranla aynı oranlı) siyah panel: aşağıdan yukarı adaya doğru daralarak küçülür.
 * [onSwallow]: panel adaya yaklaşınca çağrılır (ada büyüyüp yutar). Bitince [onFinished] gelir.
 */
@Composable
fun ShrinkToIsland(spec: LaunchSpec, onSwallow: () -> Unit, onFinished: () -> Unit) {
    val swallow by rememberUpdatedState(onSwallow)
    val finished by rememberUpdatedState(onFinished)
    val a = remember { Animatable(0f) }            // 0 = başlangıç paneli, 1 = adanın içinde ince şekil
    val appear = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val ms = (SHRINK_BASE_MS / spec.speed.coerceIn(0.25f, 4f)).toInt()

    LaunchedEffect(Unit) {
        delay((PANEL_DELAY_MS / spec.speed.coerceIn(0.25f, 4f)).toLong())   // ada önce açılsın
        launch { appear.animateTo(1f, tween(SHRINK_APPEAR_MS)) }
        var fired = false
        a.animateTo(1f, tween(ms, easing = ShrinkEasing)) {
            if (!fired && value >= SWALLOW_AT) { fired = true; swallow() }
        }
        if (!fired) swallow()
        fade.animateTo(0f, tween(SHRINK_FADE_MS))
        finished()
    }

    Canvas(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .graphicsLayer { alpha = appear.value * fade.value }
    ) {
        val d = density
        val t = a.value

        // Başlangıç: ekran oranında uzun panel (ekranı kaplamaz); bitiş: adanın içinde ince uzun şekil
        val w0 = size.width * START_W_FRAC
        val h0 = w0 * (size.height / size.width)
        val top0 = size.height * START_TOP_FRAC
        val cx0 = size.width / 2f
        val w1 = spec.h * 0.5f                         // ince, uzun şekil (eski görünüm) ama adanın İÇİNDE biter:
        val h1 = spec.h * 0.8f                         // adanın altından taşmaz
        val top1 = spec.top + spec.h * 0.1f
        val cx1 = spec.cx
        val w = w0 * (w1 / w0).pow(t)                  // üstel daralma
        val h = h0 * (h1 / h0).pow(t)
        val top = top0 + (top1 - top0) * t             // üst kenar adaya doğru yükselir
        val cx = cx0 + (cx1 - cx0) * t
        val radius = minOf(CORNER_DP * d, minOf(w, h) / 2f)

        translate(-origin.x, -origin.y) {
            drawRoundRect(spec.color, Offset(cx - w / 2f, top), Size(w, h), CornerRadius(radius))
        }
    }
}
