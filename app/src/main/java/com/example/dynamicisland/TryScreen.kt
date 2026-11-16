package com.example.dynamicisland

import android.os.SystemClock
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

// ---------------------------------------------------------------- Dene ekranı
// iPhone "Eylem Düğmesi" seçicisi gibi: ortada parlayan büyük hap, yanlarda soluk komşu simgeler,
// altta sayfa noktaları + başlık + açıklama. Yatay kaydırınca sonraki olay; hapa dokununca ada o olayı gösterir.
//
// ÖLÇÜLER (ekran görüntüsünden, ekran genişliği = 1.0):
//   hap genişliği  ≈ 0.227   hap yüksekliği ≈ 2.03 x hap genişliği
//   gri bant genişliği ≈ 2.4 x hap genişliği (≈ 0.55)
//   yan simgelerin merkezi ≈ ±0.28   ortadaki simge ≈ 0.52 x hap genişliği

private class TryItem(val title: String, val desc: String, val color: Color, val kind: Int)

private val TryItems = listOf(
    TryItem("Gelen Arama", "Gelen arama tasarımı ve animasyonu. Cevapla ve Reddet düğmeleri çalışır.", Color(0xFF34C759), 0),
    TryItem("Mesaj", "Mesaj bildirimi görünümü.", Color(0xFF0A84FF), 1),
    TryItem("Zil Modu", "Sessiz ve zil görünümü. Her dokunuşta değişir.", Color(0xFFF08A24), 2),
    TryItem("Şarj", "Şarj oluyor görünümü.", Color(0xFF30D158), 3),
    TryItem("Zamanlayıcı", "Geri sayan zamanlayıcı görünümü.", Color(0xFFF5C400), 4),
    TryItem("Alarm", "Alarm görünümü.", Color(0xFFFF453A), 5),
    TryItem("Müzik", "Müzik adası. Tekrar dokununca kapanır.", Color(0xFFFF2D55), 6),
    TryItem("Ekran Kaydı", "Geri sayım, kayıt, işleniyor ve kaydedildi aşamaları.", Color(0xFFE8334A), 7),
    TryItem("Asistan", "Asistan animasyonu ve ekran kenarı parıltısı.", Color(0xFFAF52DE), 8),
    TryItem("Favoriler", "Favori uygulamalar paneli. Ayarlardan favori uygulama seçili olmalı.", Color(0xFF5E5CE6), 9),
)

private val TryBg = Color(0xFF050507)
private val TrySideTint = Color(0xFFB4B8C2)

/** Servisten bağımsız çalışan demo kapsamı: ekrandan çıkılsa da ekran kaydı / asistan demosu düzgün biter. */
private val demoScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
private var recJob: Job? = null

private fun runRecDemo() {
    recJob?.cancel()
    recJob = demoScope.launch {
        val key = "demo_rec"
        for (n in 3 downTo 1) {
            IslandBus.post(IslandEvent.ScreenRec(RecPhase.Countdown, key, 0L, n, null, null, null))
            delay(1000)
        }
        val t0 = System.currentTimeMillis()
        IslandBus.post(IslandEvent.ScreenRec(RecPhase.Recording, key, t0, 0, null, null, null))
        delay(4500)
        IslandBus.post(IslandEvent.ScreenRecGone(key, RecPhase.Recording))
        delay(1800)
        IslandBus.post(IslandEvent.ScreenRec(RecPhase.Saved, key, t0, 0, null, null, null))
    }
}

private fun runAssistantDemo() {
    IslandBus.post(IslandEvent.AssistantChanged(true))
    demoScope.launch {
        delay(4000)
        IslandBus.post(IslandEvent.AssistantChanged(false))
    }
}

private fun fl(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun mix(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

@Composable
internal fun TryScreen(isIslandRunning: () -> Boolean, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val last = TryItems.size - 1
    val pos = remember { Animatable(0f) }            // sürekli sayfa konumu (0 .. last)
    var silentDemo by remember { mutableStateOf(true) }
    var musicOn by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var glowY by remember { mutableFloatStateOf(0f) } // hapın ekrandaki dikey merkezi (bant parıltısı buna göre çizilir)
    // Telefon eğilme animasyonu: 0 = düz, 1 = yana kaymış ve eğik
    val tilt = remember { Animatable(0f) }
    val tiltJob = remember { arrayOfNulls<Job>(1) }
    val playTilt: (Long) -> Unit = { holdMs ->
        tiltJob[0]?.cancel()
        tiltJob[0] = scope.launch {
            tilt.animateTo(1f, spring(0.72f, 90f))
            delay(holdMs)
            tilt.animateTo(0f, spring(0.8f, 110f))
        }
    }
    val holdFor: (Int) -> Long = { idx ->
        when (idx) { 7 -> 8500L; 4 -> 6000L; 8 -> 4500L; 3, 5 -> 4200L; else -> 3000L }
    }
    val pressScale by animateFloatAsState(if (pressed) 0.94f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "tryPress")

    // Ekrandan çıkarken demo müziği kaldır
    DisposableEffect(Unit) { onDispose { if (musicOn) IslandBus.post(IslandEvent.MediaChanged(null)) } }

    val trigger: (Int) -> Unit = { idx ->
        if (!isIslandRunning()) {
            Toast.makeText(ctx, "Önce Kurulum sekmesinden adayı başlat", Toast.LENGTH_LONG).show()
        } else when (idx) {
            0 -> IslandBus.post(
                IslandEvent.Call(
                    key = DEMO_CALL_KEY, pkg = "demo", name = "Ayşe Yılmaz", incoming = true,
                    startedAt = 0L, avatar = null, answer = null, decline = null,
                )
            )
            1 -> IslandBus.post(IslandEvent.Notification(ctx.packageName, "Ayşe", "Akşam görüşelim mi? 😊", null))
            2 -> {
                IslandBus.post(
                    IslandEvent.Status(
                        IslandContent.Status(
                            kind = StatusKind.Ringer,
                            title = if (silentDemo) "Sessiz" else "Zil",
                            subtitle = if (silentDemo) "Zil kapalı" else "Zil açık",
                            silent = silentDemo,
                        ), holdMs = 2_500L,
                    )
                )
                silentDemo = !silentDemo
            }
            3 -> IslandBus.post(
                IslandEvent.Status(
                    IslandContent.Status(
                        kind = StatusKind.Charging, title = "Şarj oluyor", subtitle = "Hızlı şarj",
                        value = "%78", progress = 0.78f,
                    ), holdMs = 4_000L,
                )
            )
            4 -> {
                val total = 300_000L
                IslandBus.post(
                    IslandEvent.Status(
                        IslandContent.Status(
                            kind = StatusKind.Timer, title = "Zamanlayıcı", subtitle = "Kalan süre",
                            endsAt = System.currentTimeMillis() + total, totalMs = total,
                        ), holdMs = 6_000L,
                    )
                )
            }
            5 -> IslandBus.post(
                IslandEvent.Status(
                    IslandContent.Status(
                        kind = StatusKind.Alarm, title = "Alarm", subtitle = "Hafta içi", value = "07:30",
                    ), holdMs = 4_000L,
                )
            )
            6 -> {
                if (!musicOn) {
                    IslandBus.post(
                        IslandEvent.MediaChanged(
                            IslandContent.Media(
                                title = "Gece Yarısı", artist = "Demo Sanatçı", playing = true,
                                durationMs = 215_000L, positionMs = 42_000L, speed = 1f,
                                updatedAt = SystemClock.elapsedRealtime(), art = null, artColor = 0,
                            )
                        )
                    )
                } else IslandBus.post(IslandEvent.MediaChanged(null))
                musicOn = !musicOn
            }
            7 -> runRecDemo()
            8 -> runAssistantDemo()
            9 -> IslandBus.post(IslandEvent.ShowFavorites)
            else -> Unit
        }
    }

    // Sürükleme bırakılınca en yakın sayfaya yerleş (kısa hızlı kaydırma da bir sayfa ilerletir)
    val settle: (Int, Float) -> Unit = { startPage, lastD ->
        val frac = pos.value - startPage
        val t = when {
            frac > 0.18f || (frac > 0.04f && lastD < -6f) -> startPage + 1
            frac < -0.18f || (frac < -0.04f && lastD > 6f) -> startPage - 1
            else -> startPage
        }.coerceIn(0, last)
        scope.launch { pos.animateTo(t.toFloat(), spring(0.8f, 380f)) }
    }

    val near = pos.value.roundToInt().coerceIn(0, last)
    val i0 = pos.value.toInt().coerceIn(0, last)
    val i1 = (i0 + 1).coerceAtMost(last)
    val accent = mix(TryItems[i0].color, TryItems[i1].color, (pos.value - i0).coerceIn(0f, 1f))

    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black)) {
        // Ekran sabit kalır; yalnızca metalik kenar (hap + simgelerle birlikte) sola kayar
        val slideMax = 0f   // yana kayma yok; hareket 3 boyutlu eğilme
        val camPx = with(density) { 24.dp.toPx() }

        Box(Modifier.fillMaxSize().background(TryBg)) {
        // Tüm ölçüler ekran genişliğine göre (ekran görüntüsündeki oranlar)
        val pillW = min(this@BoxWithConstraints.maxWidth.value * 0.227f, this@BoxWithConstraints.maxHeight.value * 0.11f).dp
        val pillH = pillW * 2.03f
        val sideXpx = with(density) { (this@BoxWithConstraints.maxWidth * 0.28f).toPx() }
        val pillWpx = with(density) { pillW.toPx() }
        val pillHpx = with(density) { pillH.toPx() }

        // Ortada gri metalik bant (yanlar siyah); seçili rengin parıltısı bandın içinde yayılır
        // Metalik kenar (bant + hap + simgeler + yazılar) tek katman: 3 boyutlu eğilir. Ekran ve geri tuşu sabit kalır.
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val t = tilt.value
                cameraDistance = camPx
                rotationZ = -14f * t      // ekran görüntüsünde telefon saat yönünün tersine döner
                rotationX = 12f * t
                rotationY = -10f * t
                translationX = size.width * 0.06f * t
                translationY = -size.height * 0.01f * t
                scaleX = 1f + 0.10f * t
                scaleY = 1f + 0.10f * t
            },
        ) {
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawBehind {
                    val cy = if (glowY > 0f) glowY else size.height * 0.4f
                    drawMetalBand(accent, cy, pillWpx, pillHpx, tilt.value, -slideMax * tilt.value)
                },
        )
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Spacer(Modifier.height(58.dp))   // geri tuşunun yeri (tuş en üstte, sabit katmanda)

            // Hap + noktalar + başlık + açıklama tek grup olarak dikeyde ortalanır
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.weight(0.9f))

                // Hap sahnesi
                Box(
                    Modifier.fillMaxWidth().height(pillH + 40.dp)
                        .onGloballyPositioned {
                            val y = it.positionInRoot().y + it.size.height / 2f
                            // Eğilme sırasında konum değişir; parıltı merkezini yalnızca telefon düzken güncelle
                            if (tilt.value == 0f && abs(y - glowY) > 0.5f) glowY = y
                        }
                        .pointerInput(Unit) {
                            var startPage = 0
                            var lastD = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { scope.launch { pos.stop() }; startPage = pos.value.roundToInt(); lastD = 0f },
                                onDragEnd = { settle(startPage, lastD) },
                                onDragCancel = { settle(startPage, 0f) },
                                onHorizontalDrag = { change, d ->
                                    change.consume()
                                    lastD = d
                                    val stepPx = size.width * 0.28f
                                    scope.launch { pos.snapTo((pos.value - d / stepPx).coerceIn(-0.25f, last + 0.25f)) }
                                },
                            )
                        },
                ) {
                    // Renkli hap sabit: yerinde durur, kaydırınca yalnızca rengi karışarak değişir
                    Box(
                        Modifier.align(Alignment.Center).size(pillW, pillH)
                            .graphicsLayer {
                                translationX = -slideMax * tilt.value
                                val pop = 1f + 0.06f * tilt.value   // hap öne doğru çıkar
                                scaleX = pressScale * pop; scaleY = pressScale * pop
                            }
                            .drawBehind { drawCapsule(accent, tilt.value) },
                    )
                    // Simgeler kayar
                    TryItems.forEachIndexed { i, item ->
                        val rel = i - pos.value
                        val d = abs(rel)
                        if (d < 1.7f) {
                            val dn = d.coerceAtMost(1f)
                            val a = if (d < 1f) 1f - 0.45f * d else 0.55f * ((1.7f - d) / 0.7f)
                            Box(
                                Modifier.align(Alignment.Center).size(pillW, pillH)
                                    .offset { IntOffset((rel * sideXpx).roundToInt(), 0) }
                                    .graphicsLayer {
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                        alpha = a
                                        translationX = -slideMax * tilt.value
                                        val s = (if (i == near) pressScale else 1f) * (1f + 0.06f * tilt.value)
                                        scaleX = s; scaleY = s
                                    },
                                contentAlignment = Alignment.Center,
                            ) {
                                TryGlyph(
                                    kind = item.kind,
                                    tint = mix(Color.White, TrySideTint, dn),
                                    gap = mix(accent, TryBg, dn),
                                    modifier = Modifier.size(pillW * fl(0.52f, 0.42f, dn)),
                                )
                            }
                        }
                    }

                    // Ortadaki hapa dokunma: ada o olayı gösterir
                    Box(
                        Modifier.align(Alignment.Center).size(pillW + 20.dp, pillH + 20.dp).pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { pressed = true; tryAwaitRelease(); pressed = false },
                                onTap = {
                                    val idx = pos.value.roundToInt().coerceIn(0, last)
                                    if (isIslandRunning()) playTilt(holdFor(idx))
                                    trigger(idx)
                                },
                            )
                        },
                    )
                }

                Spacer(Modifier.height(20.dp))

                // Noktalar + başlık + açıklama
                val fade = (1f - 2f * abs(pos.value - near)).coerceIn(0f, 1f)
                val cur = TryItems[near]
                Column(
                    Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        TryItems.forEachIndexed { i, _ ->
                            val on = i == near
                            Box(
                                Modifier.size(if (on) 7.dp else 6.dp).clip(CircleShape)
                                    .background(Color.White.copy(alpha = if (on) 1f else 0.35f)),
                            )
                        }
                    }
                    Column(
                        Modifier.padding(top = 22.dp).graphicsLayer { alpha = fade },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(cur.title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Text(cur.desc, color = Color(0xFFC4C8D2), fontSize = 13.sp, lineHeight = 17.sp, textAlign = TextAlign.Center)
                        Text("Denemek için simgeye dokun", color = Color(0xFF7E8494), fontSize = 11.sp, textAlign = TextAlign.Center)
                    }
                }

                Spacer(Modifier.weight(1.1f))
            }
        }
        } // eğilen metalik kenar katmanı

        // Geri tuşu (sabit)
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp)) {
                Box(
                    Modifier.size(46.dp).clip(CircleShape).background(Color(0x33FFFFFF)).clickable(onClick = onClose),
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(20.dp)) {
                        val p = Path().apply {
                            moveTo(size.width * 0.62f, size.height * 0.12f)
                            lineTo(size.width * 0.28f, size.height * 0.5f)
                            lineTo(size.width * 0.62f, size.height * 0.88f)
                        }
                        drawPath(p, Color.White, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }
        }
        } // telefon
    }
}

/**
 * iPhone Eylem Düğmesi ekranındaki gibi: ortada gri metalik dikey bant, üstte ve altta siyaha solar, yanlar siyah.
 * [cy] hapın dikey merkezi, [pw]/[ph] hapın piksel boyutu; bant hapa göre ölçeklenir (genişlik ≈ 2.4 x hap).
 */
private fun DrawScope.drawMetalBand(accent: Color, cy: Float, pw: Float, ph: Float, tilt: Float, shift: Float) {
    val bw = pw * 2.4f
    val left = (size.width - bw) / 2f + shift
    val top = cy - ph * 1.6f
    val h = ph * 3.9f
    val bottom = top + h
    // Gri metalik dolgu
    drawRect(
        Brush.verticalGradient(
            0f to Color(0x00808088),
            0.14f to Color(0x55707078),
            0.40f to Color(0xCC8A8A90),
            0.58f to Color(0xFFA2A2A8),
            0.74f to Color(0xFF68686E),
            0.90f to Color(0x00404046),
            1f to Color.Transparent,
            startY = top, endY = bottom,
        ),
        topLeft = Offset(left, top), size = Size(bw, h),
    )
    // İki yanda parlak dikey kenar çizgileri
    for (x in listOf(left + bw * 0.04f, left + bw * 0.96f)) {
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent, 0.3f to Color(0x99FFFFFF), 0.7f to Color(0x40FFFFFF), 1f to Color.Transparent,
                startY = top, endY = bottom,
            ),
            topLeft = Offset(x - 1.dp.toPx(), top), size = Size(2.dp.toPx(), h),
        )
        drawRect(
            Brush.verticalGradient(
                0f to Color.Transparent, 0.35f to Color(0x24FFFFFF), 0.75f to Color(0x10FFFFFF), 1f to Color.Transparent,
                startY = top, endY = bottom,
            ),
            topLeft = Offset(x - 7.dp.toPx(), top), size = Size(14.dp.toPx(), h),
        )
    }
    // Sol alt tarafta yumuşak beyaz ışık (telefon kenarına vuran yansıma)
    drawRect(
        Brush.radialGradient(
            listOf(Color(0x59FFFFFF), Color.Transparent),
            center = Offset(left + bw * 0.22f, cy + ph * 0.95f), radius = bw * 0.55f,
        ),
    )
    // Altta parmak silueti (düğmeye basan parmak)
    val archTop = cy + ph * 0.5f + 175.dp.toPx()
    val archH = ph * 1.2f
    val archW = pw * 1.04f
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent), startY = archTop, endY = archTop + archH),
        topLeft = Offset((size.width - archW) / 2f + shift, archTop), size = Size(archW, archH),
        cornerRadius = CornerRadius(archW / 2f),
    )
    drawRoundRect(
        Brush.verticalGradient(listOf(Color(0x24FFFFFF), Color.Transparent), startY = archTop, endY = archTop + archH),
        topLeft = Offset((size.width - archW) / 2f + shift, archTop), size = Size(archW, archH),
        cornerRadius = CornerRadius(archW / 2f), style = Stroke(2.dp.toPx()),
    )
    // Seçili rengin parıltısı
    drawRect(
        Brush.radialGradient(
            listOf(accent.copy(alpha = 0.80f), accent.copy(alpha = 0.32f), Color.Transparent),
            center = Offset(size.width / 2f + shift, cy), radius = bw * 0.75f,
        ),
    )
    // Yan kenarları siyaha yumuşakça söndür
    drawRect(
        Brush.horizontalGradient(
            0f to Color.Transparent, 0.07f to Color.Black, 0.93f to Color.Black, 1f to Color.Transparent,
            startX = left, endX = left + bw,
        ),
        blendMode = BlendMode.DstIn,
    )
    // Tuşa basınca telefon döner: bandın sağında önce metal yan duvar (kalınlık), sonra ince siyah çerçeve, sonra beyaz ekran
    if (tilt > 0.01f) {
        val edge = left + bw
        val wall = 10.dp.toPx() * tilt                 // metal çerçevenin kalınlığı
        val line = 3.dp.toPx() + 2.dp.toPx() * tilt    // ekranın en kenarındaki ince siyah çizgi
        if (wall > 0.5f) {
            drawRect(
                Brush.verticalGradient(
                    0f to Color.Transparent, 0.22f to Color(0xFFB9BCC3), 0.62f to Color(0xFF8E9197),
                    0.85f to Color(0xFF4A4B50), 1f to Color.Transparent,
                    startY = top, endY = bottom,
                ),
                topLeft = Offset(edge, top), size = Size(wall, h),
            )
            // Yan duvarın ekrana doğru koyulaşması (yuvarlak kenar hissi)
            drawRect(
                Brush.horizontalGradient(0f to Color(0x00000000), 1f to Color(0x99000000), startX = edge, endX = edge + wall),
                topLeft = Offset(edge, top), size = Size(wall, h),
            )
        }
        val bx = edge + wall
        drawRect(Color(0xFF0A0A0C), topLeft = Offset(bx, 0f), size = Size(line, size.height), alpha = tilt)
        val wx = bx + line
        val ww = size.width - wx
        if (ww > 0f) {
            drawRect(
                Brush.horizontalGradient(
                    0f to Color(0xFF9AA3A5), 0.2f to Color(0xFFE9EEEE), 1f to Color(0xFFD9E0E0),
                    startX = wx, endX = size.width,
                ),
                topLeft = Offset(wx, 0f), size = Size(ww, size.height), alpha = tilt,
            )
            // Çerçevenin ekrana düşen gölgesi
            drawRect(
                Brush.horizontalGradient(0f to Color(0x66000000), 1f to Color.Transparent, startX = wx, endX = wx + 30.dp.toPx()),
                topLeft = Offset(wx, 0f), size = Size(min(ww, 30.dp.toPx()), size.height), alpha = tilt,
            )
            // Beyaz ekran aşağı doğru kararır
            drawRect(
                Brush.verticalGradient(0f to Color.Transparent, 0.55f to Color.Transparent, 0.95f to TryBg),
                topLeft = Offset(wx, 0f), size = Size(ww, size.height), alpha = tilt,
            )
        }
    }
}

private fun DrawScope.drawCapsule(color: Color, depth: Float = 0f) {
    val r = size.width / 2f
    // Yumuşak dış parıltı: büyüyen, sönük halkalar
    for (k in 9 downTo 1) {
        val e = k * 2.5.dp.toPx()
        drawRoundRect(color.copy(alpha = 0.045f), Offset(-e, -e), Size(size.width + 2f * e, size.height + 2f * e), CornerRadius(r + e))
    }
    // Kalınlık: sağ-aşağıya doğru koyulaşan kopyalar (tuş yüksekliği); yalnızca telefon eğikken görünür
    if (depth > 0.01f) {
        val steps = 10
        val dx = 0.8.dp.toPx() * depth
        val dy = 1.2.dp.toPx() * depth
        drawRoundRect(
            Color(0x66000000), Offset(dx * (steps + 2), dy * (steps + 2)), size, CornerRadius(r),
        )
        for (k in steps downTo 1) {
            drawRoundRect(
                mix(color, Color.Black, 0.40f + 0.35f * k / steps), Offset(dx * k, dy * k), size, CornerRadius(r),
            )
        }
    }
    drawRoundRect(Brush.verticalGradient(listOf(mix(color, Color.White, 0.22f), color)), cornerRadius = CornerRadius(r))
    drawRoundRect(mix(color, Color.Black, 0.2f), cornerRadius = CornerRadius(r), style = Stroke(size.width * 0.057f))
    if (depth > 0.01f) {
        // Sol-üst kenarda ince parlak vurgu
        drawRoundRect(
            Color.White.copy(alpha = 0.22f * depth), Offset(size.width * 0.05f, size.width * 0.05f),
            Size(size.width * 0.9f, size.height - size.width * 0.1f), CornerRadius(r * 0.9f), style = Stroke(1.5.dp.toPx()),
        )
    }
}

// ---------------------------------------------------------------- Simgeler (24x24 birimlik çizim)
@Composable
private fun TryGlyph(kind: Int, tint: Color, gap: Color, modifier: Modifier = Modifier) {
    if (kind == 0) {
        PhoneIcon(0f, modifier, tint)
        return
    }
    Canvas(modifier) {
        val s = size.width / 24f
        withTransform({ scale(s, s, Offset.Zero) }) { drawGlyph(kind, tint, gap) }
    }
}

private fun DrawScope.drawGlyph(kind: Int, c: Color, gap: Color) {
    val line = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    when (kind) {
        1 -> { // mesaj balonu
            drawPath(Path().apply { addRoundRect(RoundRect(Rect(3f, 4f, 21f, 17f), CornerRadius(4.5f, 4.5f))) }, c)
            drawPath(Path().apply { moveTo(7f, 15f); lineTo(6f, 21f); lineTo(12.5f, 16f); close() }, c)
        }
        2 -> { // sessiz zil
            val body = Path().apply {
                moveTo(6f, 16f); lineTo(6f, 11f)
                arcTo(Rect(6f, 4.5f, 18f, 16.5f), 180f, 180f, false)
                lineTo(18f, 16f); lineTo(20.5f, 18.5f); lineTo(3.5f, 18.5f); close()
            }
            drawPath(body, c)
            drawCircle(c, 1.9f, Offset(12f, 21f))
            drawLine(gap, Offset(4.5f, 3.5f), Offset(19.5f, 21f), strokeWidth = 4.6f, cap = StrokeCap.Round)
            drawLine(c, Offset(4.5f, 3.5f), Offset(19.5f, 21f), strokeWidth = 1.9f, cap = StrokeCap.Round)
        }
        3 -> { // şarj: yıldırım
            drawPath(
                Path().apply {
                    moveTo(13.5f, 2f); lineTo(5f, 13.5f); lineTo(11f, 13.5f); lineTo(10f, 22f)
                    lineTo(19f, 10f); lineTo(13f, 10f); close()
                }, c,
            )
        }
        4 -> { // zamanlayıcı (kronometre)
            drawCircle(c, 7.5f, Offset(12f, 13.5f), style = line)
            drawLine(c, Offset(10f, 2.8f), Offset(14f, 2.8f), 2f, StrokeCap.Round)
            drawLine(c, Offset(12f, 13.5f), Offset(12f, 9.5f), 2f, StrokeCap.Round)
            drawLine(c, Offset(18f, 7f), Offset(19.4f, 5.6f), 2f, StrokeCap.Round)
        }
        5 -> { // alarm saati
            drawCircle(c, 7f, Offset(12f, 13.5f), style = line)
            drawLine(c, Offset(12f, 13.5f), Offset(12f, 9.5f), 2f, StrokeCap.Round)
            drawLine(c, Offset(12f, 13.5f), Offset(15f, 15f), 2f, StrokeCap.Round)
            drawLine(c, Offset(3.5f, 6.5f), Offset(6.8f, 3.5f), 2f, StrokeCap.Round)
            drawLine(c, Offset(20.5f, 6.5f), Offset(17.2f, 3.5f), 2f, StrokeCap.Round)
        }
        6 -> { // müzik notası
            drawCircle(c, 3.2f, Offset(8f, 18f))
            drawCircle(c, 3.2f, Offset(17f, 16f))
            drawLine(c, Offset(10.6f, 17.4f), Offset(10.6f, 5f), 2.2f, StrokeCap.Round)
            drawLine(c, Offset(19.6f, 15.4f), Offset(19.6f, 3.5f), 2.2f, StrokeCap.Round)
            drawLine(c, Offset(10.6f, 5f), Offset(19.6f, 3.5f), 2.6f, StrokeCap.Round)
        }
        7 -> { // ekran kaydı
            drawCircle(c, 8.5f, Offset(12f, 12f), style = line)
            drawCircle(c, 4.6f, Offset(12f, 12f))
        }
        8 -> { // asistan: parıltı yıldızı
            drawPath(
                Path().apply {
                    moveTo(11f, 2.5f)
                    quadraticBezierTo(11.8f, 10.2f, 19.5f, 11f)
                    quadraticBezierTo(11.8f, 11.8f, 11f, 19.5f)
                    quadraticBezierTo(10.2f, 11.8f, 2.5f, 11f)
                    quadraticBezierTo(10.2f, 10.2f, 11f, 2.5f)
                    close()
                }, c,
            )
            drawPath(
                Path().apply {
                    moveTo(18.5f, 13.5f)
                    quadraticBezierTo(18.8f, 16.7f, 22f, 17f)
                    quadraticBezierTo(18.8f, 17.3f, 18.5f, 20.5f)
                    quadraticBezierTo(18.2f, 17.3f, 15f, 17f)
                    quadraticBezierTo(18.2f, 16.7f, 18.5f, 13.5f)
                    close()
                }, c,
            )
        }
        9 -> { // favoriler: 2x2 uygulama ızgarası
            val cr = CornerRadius(2.4f, 2.4f)
            drawRoundRect(c, Offset(3.5f, 3.5f), Size(7.5f, 7.5f), cr)
            drawRoundRect(c, Offset(13f, 3.5f), Size(7.5f, 7.5f), cr)
            drawRoundRect(c, Offset(3.5f, 13f), Size(7.5f, 7.5f), cr)
            drawRoundRect(c, Offset(13f, 13f), Size(7.5f, 7.5f), cr)
        }
    }
}
