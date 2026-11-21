package com.example.dynamicisland

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/*
 * Akıcı yutma ada geçişleri.
 *
 * Çıkış ([FluidSwallowToIsland]): uygulama penceresi tam ekrandan başlar, gittikçe bulanıklaşarak küçülür ve
 * adanın hemen altına çekilir. Pencere adaya değerken ada aşağı doğru şişer (pencereyi yutar), pencere adanın
 * içinde kaybolurken altında yumuşak bir gölge kalır; ada sonra yaylanarak normal boyutuna döner.
 *
 * Açılış ([FluidLaunchFromIsland]): ada önce şişer, pencere adanın altından küçük ve çok bulanık bir şekil olarak
 * çıkar, netleşerek büyür ve ekranı kaplar (uygulama o an açılır). Ada pencerenin ÜSTÜNDE kalır ve yaylanarak
 * eski boyutuna döner; pencere katmanı solarak altındaki uygulamayı gösterir.
 *
 * Pencerenin içeriği çizilemediği için (başka uygulamanın ekranı) panel, adanın rengiyle çizilir; bulanıklık
 * Android 12+ cihazlarda gerçek bulanıklaştırmadır, daha eski sürümlerde kenarlar keskin kalır.
 */

// ---- Ayar noktaları (hız 1x)
private const val FLUID_CORNER_DP = 44f          // pencerenin köşe yarıçapı (telefon ekran köşesi)
private const val FLUID_MARGIN_DP = 20f          // tam ekranda köşelerin ekran dışında kalması için taşma payı
private const val FLUID_BLUR_MAX_DP = 14f        // küçük haldeyken en fazla bulanıklık
private const val FLUID_BULGE_W = 1.10f          // şişen ada genişliği = kapalı genişlik x bu
private const val FLUID_BULGE_H = 1.55f          // şişen ada yüksekliği = kapalı yükseklik x bu (aşağı doğru)

private const val EXIT_SHRINK_MS = 380         // pencerenin adaya küçülmesi
private const val EXIT_BULGE_AT = 0.42f        // küçülmenin bu noktasında ada şişmeye başlar
private const val EXIT_BULGE_MS = 210          // adanın şişmesi
private const val EXIT_HOLD_MS = 60L           // şişmiş halde kısa bekleme (yutma hissi)
private const val END_FADE_MS = 70             // katmanın en sonda silinmesi (ada artık gerçek adayla aynı boyutta)

private const val OPEN_BULGE_MS = 190          // açılışta adanın şişmesi
private const val OPEN_PANEL_DELAY_MS = 50L    // pencerenin adanın altından çıkmaya başlaması
private const val OPEN_GROW_MS = 380           // pencerenin ekranı kaplaması
private const val OPEN_HOLD_MS = 260L          // ekran kaplıyken uygulamanın açılmasını bekleme
private const val OPEN_FADE_MS = 260           // pencere katmanının solması

// Pencere eğrisi: hızlı başlar, yumuşakça oturur
private val FluidEase = CubicBezierEasing(0.32f, 0.72f, 0f, 1f)
private val BulgeEase = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
// Çıkış: pencere ilk anda yumuşak kalkar, sonra hızla adaya çekilir (ekran kaydındaki kare zamanlamasına göre)
private val ExitEase = CubicBezierEasing(0.3f, 0.1f, 0.2f, 1f)

private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t
/** Üstel ara değer: boyut değişimi "yaklaşma / uzaklaşma" gibi hissettirir. */
private fun mixExp(a: Float, b: Float, t: Float) = a * (b / a).pow(t)

private fun springSpec(sp: Float) = spring<Float>(
    dampingRatio = 0.82f,
    stiffness = Spring.StiffnessMedium * 0.4f * sp * sp,       // ≈600 (~150-200 ms'de oturur); hız ayarı sertliği ölçekler
)

/** Şişen ada: üst kenar sabit, aşağı ve iki yana büyür. [g] 0 = kapalı ada, 1 = tam şişmiş. */
private fun DrawScope.drawBulgedIsland(spec: LaunchSpec, g: Float) {
    val k = g.coerceAtLeast(0f)
    val w = spec.w * (1f + (FLUID_BULGE_W - 1f) * k)
    val h = spec.h * (1f + (FLUID_BULGE_H - 1f) * k)
    val r = min(w, h) / 2f
    drawRoundRect(spec.color, Offset(spec.cx - w / 2f, spec.top), Size(w, h), CornerRadius(r))
}

/** Pencere panelinin konumu ve boyutu. [p] 0 = tam ekran, 1 = adanın altında küçük resim. */
private class PanelGeom(val l: Float, val t: Float, val w: Float, val h: Float, val r: Float)

private fun panelAt(spec: LaunchSpec, sw: Float, sh: Float, d: Float, p: Float): PanelGeom {
    val m = FLUID_MARGIN_DP * d
    val w0 = sw + 2 * m; val h0 = sh + 2 * m
    val w1 = spec.w * 0.82f
    val h1 = spec.h * 1.35f
    val w = mixExp(w0, w1, p)
    val h = mixExp(h0, h1, p)
    val cx = mix(sw / 2f, spec.cx, p)
    // Merkez: ekran ortasından adanın alt yarısına doğru (küçük resim adanın altına girer)
    val cy = mix(sh / 2f, spec.top + spec.h * 0.95f, p)
    val r = min(mix(FLUID_CORNER_DP * d, min(w1, h1) * 0.42f, p), min(w, h) / 2f)
    return PanelGeom(cx - w / 2f, cy - h / 2f, w, h, r)
}

/** Panel ayrı bir katmanda çizilir: bulanıklık yalnızca panele uygulanır, ada keskin kalır. */
@Composable
private fun PanelLayer(spec: LaunchSpec, origin: () -> Offset, prog: () -> Float, alpha: () -> Float) {
    Canvas(
        Modifier.fillMaxSize().graphicsLayer {
            val a = alpha()
            this.alpha = a
            val p = prog()
            val b = if (a <= 0f) 0f else FLUID_BLUR_MAX_DP * density * p.pow(0.7f)
            renderEffect = if (Build.VERSION.SDK_INT >= 31 && b >= 0.5f) BlurEffect(b, b, TileMode.Decal) else null
        }
    ) {
        if (alpha() <= 0f) return@Canvas
        val o = origin()
        val g = panelAt(spec, size.width, size.height, density, prog())
        translate(-o.x, -o.y) {
            drawRoundRect(spec.color, Offset(g.l, g.t), Size(g.w, g.h), CornerRadius(g.r))
        }
    }
}

@Composable
private fun IslandLayer(spec: LaunchSpec, origin: () -> Offset, bulge: () -> Float) {
    Canvas(Modifier.fillMaxSize()) {
        val o = origin()
        translate(-o.x, -o.y) { drawBulgedIsland(spec, bulge()) }
    }
}

/** Müzik uygulamasından çıkış: pencere bulanıklaşarak adaya küçülür, ada şişip yutar, yaylanarak döner. */
@Composable
fun FluidSwallowToIsland(spec: LaunchSpec, onFinished: () -> Unit) {
    val finished by rememberUpdatedState(onFinished)
    val sp = spec.speed.coerceIn(0.25f, 4f)
    val p = remember { Animatable(0f) }          // pencere: 0 tam ekran → 1 adanın altında
    val panelA = remember { Animatable(1f) }
    val bulge = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        val shrinkMs = (EXIT_SHRINK_MS / sp).toInt()
        val bulgeJob = launch {
            delay((shrinkMs * EXIT_BULGE_AT).toLong())
            bulge.animateTo(1f, tween((EXIT_BULGE_MS / sp).toInt(), easing = BulgeEase))
            delay((EXIT_HOLD_MS / sp).toLong())
            bulge.animateTo(0f, springSpec(sp))
        }
        launch {
            // Pencere adaya değdikten sonra adanın içinde erir (altında yumuşak gölge kalır)
            delay((shrinkMs * 0.62f).toLong())
            panelA.animateTo(0f, tween((shrinkMs * 0.55f).toInt()))
        }
        p.animateTo(1f, tween(shrinkMs, easing = ExitEase))
        bulgeJob.join()
        fade.animateTo(0f, tween(END_FADE_MS))
        finished()
    }

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .graphicsLayer { alpha = fade.value }
    ) {
        PanelLayer(spec, { origin }, { p.value }, { panelA.value })
        IslandLayer(spec, { origin }, { bulge.value })   // ada pencerenin üstünde: pencere onun altına girer
    }
}

/**
 * Müzik uygulamasını açarken: ada şişer, pencere adanın altından bulanık çıkıp netleşerek ekranı kaplar.
 * Ekran kaplanınca [onCovered] (uygulamayı aç); ada yaylanarak döner, pencere solar, sonra [onFinished].
 */
@Composable
fun FluidLaunchFromIsland(spec: LaunchSpec, onCovered: () -> Unit, onFinished: () -> Unit) {
    val covered by rememberUpdatedState(onCovered)
    val finished by rememberUpdatedState(onFinished)
    val sp = spec.speed.coerceIn(0.25f, 4f)
    val p = remember { Animatable(1f) }          // 1 = adanın altında küçük, 0 = tam ekran
    val panelA = remember { Animatable(0f) }
    val bulge = remember { Animatable(0f) }
    val fade = remember { Animatable(1f) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    LaunchedEffect(Unit) {
        launch { bulge.animateTo(1f, tween((OPEN_BULGE_MS / sp).toInt(), easing = BulgeEase)) }
        delay((OPEN_PANEL_DELAY_MS / sp).toLong())
        launch { panelA.animateTo(1f, tween((90 / sp).toInt())) }
        p.animateTo(0f, tween((OPEN_GROW_MS / sp).toInt(), easing = FluidEase))
        covered()                                          // ekran kaplandı: uygulamayı aç
        val settle = launch { bulge.animateTo(0f, springSpec(sp)) }
        delay((OPEN_HOLD_MS / sp).toLong())
        panelA.animateTo(0f, tween(OPEN_FADE_MS))
        settle.join()
        fade.animateTo(0f, tween(END_FADE_MS))
        finished()
    }

    Box(
        Modifier.fillMaxSize()
            .onGloballyPositioned { origin = it.positionOnScreen() }
            .graphicsLayer { alpha = fade.value }
    ) {
        PanelLayer(spec, { origin }, { max(p.value, 0f) }, { panelA.value })
        IslandLayer(spec, { origin }, { bulge.value })
    }
}
