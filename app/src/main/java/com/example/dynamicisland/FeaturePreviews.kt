package com.example.dynamicisland

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/*
 * AYARLAR ÖNİZLEMESİ: ÖZELLİK SAHNELERİ
 *
 * Eskiden yalnızca birkaç ayarın (asistan, favoriler, kenar tuşları...) kendi sahnesi vardı; geri kalan sayfalarda
 * önizleme sadece bildirim / müzik adasını gösteriyordu. Burada her özellik için telefonun içinde GERÇEK bileşenler
 * oynatılır: IslandRoot (asıl ada), DecorLayer (süsler, mürekkep), PlayLayer (oyuncak ada efektleri), cam banner,
 * kenar şeritleri, ekran kenarı ışığı.
 *
 * Önizleme gerçek adayı ETKİLEMEZ: oyuncak ada olayları önizlemenin kendi kanalından (PlayBus, preview = true) gider;
 * adanın kendi hareketleri (fizik, savrulma, çekiştirme, cep, fırlatma) burada ayrıca canlandırılır.
 */

/** Ayarlar önizlemesinin içinde mi çiziliyor? (ör. çeviri paneli orada klavyeyi açmasın) */
internal val LocalIslandPreview = androidx.compose.runtime.staticCompositionLocalOf { false }

internal enum class Feat(val big: Boolean = false) {
    Inverted(true), Landscape(true),
    Ringer, Volume, Translate, Banner, Stack,
    SideVolume(true), SideMusic(true), Gesture(true),
    DecorTop, DecorHang, DecorInside, Drip(true), EdgeGlow(true),
    Physics, Pet, Magnet, Puff, Face, Ripple, Liquid, Eater, Pull, Daylight, Fling(true),
    Plant, Season(true), Party(true), Doodle, Dice, Breath, NightLight, Shadow, Pocket,
}

/** Sayfa başlığına göre o sayfadaki özellikler ve "açık mı" koşulları (ilk eşleşen gösterilir). */
/** Özellik + "açık mı" koşulu (lambda parametresinin türü açık olsun diye). */
private infix fun Feat.on(p: (IslandSettings) -> Boolean): Pair<Feat, (IslandSettings) -> Boolean> = this to p

private fun featuresOf(title: String?): List<Pair<Feat, (IslandSettings) -> Boolean>> = when (title) {
    "Konum" -> listOf(Feat.Landscape on { s -> s.landscapeMode }, Feat.Inverted on { s -> s.invertedIsland })
    "Görünürlük" -> listOf(Feat.Ringer on { s -> s.ringerIndicator }, Feat.Volume on { s -> s.volumeIndicator })
    "Çeviri" -> listOf(Feat.Translate on { _ -> true })
    "Bildirimler" -> listOf(Feat.Banner on { s -> s.notifBanner })
    "Yan baloncuklar" -> listOf(Feat.Stack on { s -> s.stackExpand })
    "Kenar ses tuşları" -> listOf(Feat.SideVolume on { _ -> true })
    "Kenar müzik çubukları" -> listOf(Feat.SideMusic on { _ -> true })
    "Çizerek komut" -> listOf(Feat.Gesture on { _ -> true })
    "Adanın üstünde" -> listOf(Feat.DecorTop on { _ -> true })
    "Adadan sarkan süs" -> listOf(Feat.DecorHang on { _ -> true })
    "Adanın içinde" -> listOf(Feat.DecorInside on { _ -> true })
    "Mürekkep damlası" -> listOf(Feat.Drip on { _ -> true })
    "Ekran kenarı ışığı" -> listOf(Feat.EdgeGlow on { _ -> true })
    "Fiziksel ada" -> listOf(Feat.Physics on { _ -> true })
    "Ada evcili" -> listOf(Feat.Pet on { _ -> true })
    "Mıknatıs" -> listOf(Feat.Magnet on { _ -> true })
    "Dokununca savrulma" -> listOf(Feat.Puff on { _ -> true })
    "Ada yüzü" -> listOf(Feat.Face on { _ -> true })
    "Dalga" -> listOf(Feat.Ripple on { _ -> true })
    "Pil sıvısı" -> listOf(Feat.Liquid on { _ -> true })
    "Bildirim yiyen ada" -> listOf(Feat.Eater on { _ -> true })
    "Çekiştirme" -> listOf(Feat.Pull on { _ -> true })
    "Gün ışığı rengi" -> listOf(Feat.Daylight on { _ -> true })
    "Adayı fırlat" -> listOf(Feat.Fling on { _ -> true })
    "Ada saksısı" -> listOf(Feat.Plant on { _ -> true })
    "Mevsim modu" -> listOf(Feat.Season on { _ -> true })
    "Kutlama anları" -> listOf(Feat.Party on { _ -> true })
    "Karalama tahtası" -> listOf(Feat.Doodle on { _ -> true })
    "Salla, zar at" -> listOf(Feat.Dice on { _ -> true })
    "Nefes egzersizi" -> listOf(Feat.Breath on { _ -> true })
    "Gece lambası" -> listOf(Feat.NightLight on { _ -> true })
    "Ada gölgesi" -> listOf(Feat.Shadow on { _ -> true })
    "Cep hissi" -> listOf(Feat.Pocket on { _ -> true })
    else -> emptyList()
}

/** Gösterilecek sahne: en son açılan özellik ([focus]) öncelikli, yoksa sayfadaki ilk açık özellik. */
internal fun resolveFeature(title: String?, s: IslandSettings, focus: Feat?): Feat? {
    val on = featuresOf(title).filter { it.second(s) }.map { it.first }
    return if (focus != null && focus in on) focus else on.firstOrNull()
}

/** Bu sayfada [old] -> [new] değişikliğinde yeni AÇILAN özellik (önizleme hemen ona geçer). */
internal fun justEnabledFeature(title: String?, old: IslandSettings, new: IslandSettings): Feat? =
    featuresOf(title).firstOrNull { (_, pred) -> !pred(old) && pred(new) }?.first

/** Normal (üst kısım) önizlemede ada üst kenarından itibaren gereken yükseklik (sanal dp). */
internal fun featureNeedDp(f: Feat, cfg: IslandSettings): Float {
    val ch = cfg.compactHeightDp
    return when (f) {
        Feat.Ringer -> maxOf(ch * cfg.ringerHeightPct / 100f, cfg.expandedHeightDp * 0.6f) + 10f
        Feat.Volume -> maxOf(ch * 1.6f, 60f)
        Feat.Translate -> cfg.translateHeightDp(TranslateSession.bodyDp.value)
        Feat.Banner -> 104f
        Feat.Stack -> stackHeightDp(sampleStack(), cfg) + 8f
        Feat.DecorTop, Feat.DecorInside, Feat.Physics, Feat.Face, Feat.Liquid, Feat.Doodle, Feat.Shadow, Feat.Daylight -> ch + 34f
        Feat.Puff -> ch + 50f
        Feat.Pocket -> ch + 40f
        Feat.Pull -> ch * 2.4f + 10f
        Feat.DecorHang -> ch * 3.6f
        Feat.Pet -> ch * 3.2f
        Feat.Magnet -> ch * 4.2f
        Feat.Plant -> ch * 4.2f
        Feat.Ripple -> 230f
        Feat.Eater -> 210f
        Feat.Dice -> 220f
        Feat.Breath -> 190f
        Feat.NightLight -> 170f
        else -> 200f
    }
}

internal fun featureHint(f: Feat): String = when (f) {
    Feat.Inverted -> "Ters ada: ekranın altında, panel yukarı açılır"
    Feat.Landscape -> "Yatay mod: ada kamera kenarında, olaylar baloncukta"
    Feat.Ringer -> "Zil / Titreşim / Sessiz göstergesi"
    Feat.Volume -> "Medya sesi göstergesi"
    Feat.Translate -> "Çeviri paneli"
    Feat.Banner -> "iOS tarzı cam banner"
    Feat.Stack -> "Olaylar alt alta kartlar halinde"
    Feat.SideVolume -> "Kenar ses tuşları"
    Feat.SideMusic -> "Kenar müzik çubukları (örnek şarkı)"
    Feat.Gesture -> "Basılı tut, harf çiz: komut çalışır"
    Feat.Drip -> "Mürekkep damlası (ayardaki aralıkla tekrarlar)"
    Feat.EdgeGlow -> "Ekran kenarında akan ışık"
    Feat.Fling -> "Ada savrulup ekranda seker"
    Feat.NightLight -> "Gece lambası (gerçekte gece şarjdayken)"
    Feat.Doodle -> if (DoodleStore.strokes.isEmpty()) "Henüz çizim yok: Çiz düğmesiyle çiz" else "Boştaki adada çizimin"
    else -> "Gerçek adadaki gibi; telefona dokununca büyür / küçülür"
}

/** Yatay mod sahnesinde telefon 90° döndürülür; önizleme kartı bunu bilmek için. */
internal fun Feat.rotated(): Boolean = this == Feat.Landscape

private fun sampleStack(): List<IslandContent> = listOf(
    IslandContent.Notif("demo", "Ayşe", "Akşam görüşelim mi? 😊"),
    IslandContent.Media("Örnek Şarkı", "Sanatçı", true, 210_000, 75_000, 1f, SystemClock.elapsedRealtime(), art = null, artColor = SAMPLE_COLOR),
    IslandContent.Status(kind = StatusKind.Charging, title = "Şarj oluyor", subtitle = "Hızlı şarj", value = "%78", progress = 0.78f),
)

// ================================================================ Sahne

@Composable
internal fun FeatureScene(f: Feat, cfgIn: IslandSettings) {
    val ctx = LocalContext.current
    // Boşta görünmezse süsler / efektler boşlukta kalır: önizlemede ada boştayken de görünür
    val cfg = remember(cfgIn) { cfgIn.copy(showIdle = true) }
    val cfgFlow = remember { MutableStateFlow(cfg) }
    LaunchedEffect(cfg) { cfgFlow.value = cfg }
    val ui = remember(f) { MutableStateFlow(IslandUiState()) }
    val lyrics = remember { MutableStateFlow<LyricsState>(LyricsState.Idle) }
    val events = remember { MutableSharedFlow<PlayEvent>(extraBufferCapacity = 16) }
    val bus = remember { PlayBus(events, { e -> events.tryEmit(e) }, preview = true) }
    val previewStart = remember(f) { SystemClock.uptimeMillis() }
    val appIcon = remember {
        runCatching { ctx.packageManager.getApplicationIcon(ctx.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }

    // Adanın önizlemedeki hareketi (fizik, savrulma, çekiştirme, cep, fırlatma, yutkunma)
    var mx by remember(f) { mutableFloatStateOf(0f) }
    var my by remember(f) { mutableFloatStateOf(0f) }
    var sx by remember(f) { mutableFloatStateOf(1f) }
    var sy by remember(f) { mutableFloatStateOf(1f) }
    var pivotTop by remember(f) { mutableStateOf(false) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val d = LocalDensity.current.density
        val W = constraints.maxWidth.toFloat()
        val H = constraints.maxHeight.toFloat()
        val topV = (PHONE_BASE_TOP_DP + cfg.offsetYDp).coerceAtLeast(0f)
        val cwPx = compactWidthFor(cfg, false) * d
        val chPx = cfg.compactHeightDp * d
        val pad = cfg.windowPadDp()
        val inv = cfg.invertedIsland
        val islandTop = if (inv) H - (topV * d + chPx) else topV * d
        val cxHome = W / 2f + cfg.offsetXDp * d
        val uiNow by ui.collectAsState()
        val compact = !uiNow.expanded && uiNow.stack.isEmpty()
        val idle = compact && uiNow.content is IslandContent.Idle

        val playAnchor = remember { MutableStateFlow<PlayAnchor?>(null) }
        val decorAnchor = remember { MutableStateFlow<DecorAnchor?>(null) }
        val islandColor = Color(cfg.islandColorArgb).copy(alpha = 1f)
        androidx.compose.runtime.SideEffect {
            playAnchor.value = PlayAnchor(
                cx = cxHome + mx, top = islandTop + my, w = cwPx * sx, h = chPx * sy, inverted = inv,
                compactVisible = compact, shown = true, screenW = W, screenH = H, color = islandColor,
            )
            decorAnchor.value = DecorAnchor(cxHome + mx, islandTop + my, cwPx, chPx, inv, compact, H)
        }

        // ---------------------------------------------------------------- Senaryolar
        LaunchedEffect(f) {
            when (f) {
                Feat.Inverted -> while (true) {
                    delay(900)
                    ui.value = IslandUiState(IslandContent.Notif("demo", "Ayşe", "Akşam görüşelim mi? 😊", appIcon), expanded = false)
                    delay(260)
                    ui.value = ui.value.copy(expanded = true); delay(3200)
                    ui.value = ui.value.copy(expanded = false); delay(900)
                    ui.value = IslandUiState()
                }
                Feat.Ringer -> {
                    val modes = listOf(
                        IslandContent.Status(StatusKind.Ringer, title = "Sessiz", silent = true),
                        IslandContent.Status(StatusKind.Ringer, title = "Titreşim", vibrate = true),
                        IslandContent.Status(StatusKind.Ringer, title = "Zil"),
                    )
                    var i = 0
                    while (true) {
                        delay(800)
                        ui.value = IslandUiState(modes[i % modes.size], expanded = true); delay(2000)
                        ui.value = ui.value.copy(expanded = false); delay(700)
                        ui.value = IslandUiState(); i++
                    }
                }
                Feat.Volume -> while (true) {
                    delay(800)
                    var v = 0.3f
                    while (v <= 0.85f) {
                        ui.value = IslandUiState(
                            IslandContent.Status(StatusKind.Volume, title = "Ses", value = "%${(v * 100).toInt()}", progress = v),
                            expanded = true,
                        )
                        delay(260); v += 0.1f
                    }
                    delay(1200)
                    ui.value = ui.value.copy(expanded = false); delay(700)
                    ui.value = IslandUiState()
                }
                Feat.Translate -> { ui.value = IslandUiState(IslandContent.Translate, expanded = true) }
                Feat.Stack -> while (true) {
                    ui.value = IslandUiState(sampleStack().first(), expanded = false); delay(1400)
                    ui.value = IslandUiState(sampleStack().first(), stack = sampleStack()); delay(4200)
                    ui.value = ui.value.copy(stackClosing = true); delay(450)
                    ui.value = IslandUiState(); delay(900)
                }
                Feat.Landscape, Feat.Banner, Feat.Gesture -> Unit
                Feat.Physics -> {
                    val range = cfg.playPhysicsRangeDp * d
                    var t = 0f
                    while (true) {
                        androidx.compose.runtime.withFrameNanos { }
                        t += 1f / 60f
                        mx = sin(t * 1.4f) * range
                        sx = 1f + 0.05f * sin(t * 2.8f)
                        sy = 1f - 0.04f * sin(t * 2.8f)
                    }
                }
                Feat.Puff -> {
                    val a = Animatable(0f)
                    while (true) {
                        delay(1600)
                        bus.emit(PlayEvent.Puff)
                        val peak = (0.35f + 0.65f * cfg.playPuffStrength).coerceIn(0f, 1f)
                        a.animateTo(peak, tween(90)) { sx = 1f + 0.10f * value; sy = 1f - 0.16f * value; mx = (Math.random().toFloat() - 0.5f) * 8f * d * value }
                        a.animateTo(0f, tween(560, easing = FastOutSlowInEasing)) { sx = 1f + 0.10f * value; sy = 1f - 0.16f * value; mx = (Math.random().toFloat() - 0.5f) * 8f * d * value }
                        mx = 0f
                    }
                }
                Feat.Pull -> {
                    pivotTop = true
                    val a = Animatable(0f)
                    while (true) {
                        delay(1300)
                        a.animateTo(0.9f, tween(520, easing = FastOutSlowInEasing)) { sy = 1f + value; sx = 1f - 0.12f * value }
                        delay(150)
                        a.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = 620f)) { sy = 1f + value; sx = 1f - 0.12f * value.coerceAtLeast(0f) }
                    }
                }
                Feat.Pocket -> {
                    val drop = Animatable(0f)
                    val sink = Animatable(0f)
                    while (true) {
                        drop.snapTo(-(islandTop + chPx * 1.4f))
                        drop.animateTo(0f, spring(dampingRatio = 0.42f, stiffness = 260f)) { my = value }
                        delay(2200)
                        // Uzun süre boşta: kamera deliğine girer, sonra fırlayıp çıkar
                        val kx = (chPx * 0.6f / cwPx).coerceIn(0.05f, 1f)
                        sink.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) { sx = 1f + (kx - 1f) * value; sy = 1f - 0.4f * value }
                        delay(1300)
                        sink.animateTo(0f, spring(dampingRatio = 0.5f, stiffness = 380f)) { sx = 1f + (kx - 1f) * value; sy = 1f - 0.4f * value }
                        delay(1500)
                    }
                }
                Feat.Fling -> {
                    while (true) {
                        delay(1500)
                        // Yana savrulur, kenarlardan seker, sonra yerine döner (gerçekteki FlingFx gibi)
                        var x = 0f; var y = 0f
                        var vx = 2400f * d * (if (Math.random() < 0.5) 1f else -1f); var vy = -700f * d
                        val hw = cwPx / 2f; val hh = chPx / 2f
                        val cy0 = islandTop + hh
                        val t0 = SystemClock.uptimeMillis()
                        while (SystemClock.uptimeMillis() - t0 < 2300L) {
                            androidx.compose.runtime.withFrameNanos { }
                            val dt = 1f / 60f
                            vy += 2600f * d * dt
                            x += vx * dt; y += vy * dt
                            if (cxHome + x - hw < 0f) { x = hw - cxHome; vx = abs(vx) * 0.72f }
                            if (cxHome + x + hw > W) { x = W - hw - cxHome; vx = -abs(vx) * 0.72f }
                            if (cy0 + y - hh < 0f) { y = hh - cy0; vy = abs(vy) * 0.72f }
                            if (cy0 + y + hh > H) { y = H - hh - cy0; vy = -abs(vy) * 0.6f; vx *= 0.9f }
                            mx = x; my = y
                        }
                        val ax = Animatable(mx); val ay = Animatable(my)
                        launch { ax.animateTo(0f, tween(700, easing = FastOutSlowInEasing)) { mx = value } }
                        ay.animateTo(0f, tween(700, easing = FastOutSlowInEasing)) { my = value }
                    }
                }
                Feat.Eater -> {
                    val j = Animatable(0f)
                    launch {
                        events.collect { e ->
                            if (e == PlayEvent.Eaten) launch {
                                j.snapTo(-0.25f)
                                j.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 500f)) { sx = 1f + 0.16f * value; sy = 1f - 0.13f * value }
                            }
                        }
                    }
                    while (true) { delay(900); bus.emit(PlayEvent.Notif); delay(2600) }
                }
                Feat.Pet -> while (true) { delay(1500); bus.emit(PlayEvent.Notif); delay(2800) }
                Feat.Magnet -> while (true) { delay(1200); bus.emit(PlayEvent.Charger); delay(2200); bus.emit(PlayEvent.Buds); delay(1800) }
                Feat.Ripple -> while (true) { delay(1300); bus.emit(PlayEvent.Ripple) }
                Feat.Party -> while (true) { delay(900); bus.emit(PlayEvent.Party); delay(4600) }
                Feat.Dice -> while (true) { delay(900); bus.emit(PlayEvent.Dice); delay(3800) }
                Feat.Plant -> while (true) { delay(2000); bus.emit(PlayEvent.Water); delay(2500) }
                else -> Unit
            }
            Unit
        }

        CompositionLocalProvider(LocalPlayBus provides bus, LocalIslandPreview provides true) {
            // ---- Adanın altındaki katmanlar (gerçekte adanın altındaki ayrı pencereler)
            if (f == Feat.EdgeGlow) ScreenEdgeGlow(remember { MutableStateFlow(true) }, 40f * d)
            PlayLayer(cfgFlow, playAnchor, previewStart)
            DecorLayer(cfgFlow, decorAnchor)

            if (f == Feat.SideVolume) SideVolumePreview(H, d)
            if (f == Feat.SideMusic) SideMusicPreview(H, d)

            // ---- Asıl ada (gerçek IslandRoot)
            run {
                Box(
                    Modifier
                        .fillMaxSize()
                        .offset {
                            val y = if (inv) -((topV - pad) * d) else (topV - pad) * d
                            IntOffset((cfg.offsetXDp * d + mx).toInt(), (y + my).toInt())
                        }
                        .graphicsLayer {
                            scaleX = sx; scaleY = sy
                            val pivotY = if (inv) {
                                if (pivotTop) size.height - pad * d else size.height - pad * d - chPx / 2f
                            } else {
                                if (pivotTop) pad * d else pad * d + chPx / 2f
                            }
                            transformOrigin = TransformOrigin(0.5f, if (size.height > 0f) (pivotY / size.height).coerceIn(0f, 1f) else 0f)
                        },
                ) {
                    IslandRoot(state = ui, settingsFlow = cfgFlow, lyricsFlow = lyrics, onTap = {}, onCommand = {}, onOutput = {})
                    // Ada içi çizimler (gerçekte PlayIslandHost çizer): yüz, pil sıvısı, karalama
                    if (idle && (f == Feat.Face || f == Feat.Liquid || f == Feat.Doodle)) {
                        val face = rememberFaceState(on = f == Feat.Face, compactIdle = true)
                        LaunchedEffect(f) { if (f == Feat.Doodle) DoodleStore.load(ctx) }
                        Canvas(Modifier.fillMaxSize()) {
                            val cy = if (inv) size.height - pad * d - chPx / 2f else pad * d + chPx / 2f
                            val c = Offset(size.width / 2f, cy)
                            when (f) {
                                Feat.Face -> if (face.visible) drawFace(face, c, cwPx, chPx)
                                Feat.Liquid -> drawLiquid(c, cwPx, chPx, 0f)
                                else -> if (DoodleStore.strokes.isNotEmpty()) drawDoodle(DoodleStore.strokes, c, cwPx, chPx)
                            }
                        }
                    }
                }
            }

            if (f == Feat.Banner) BannerPreview(W, d, appIcon, cfg)
            if (f == Feat.Gesture) GesturePreview()
            if (f == Feat.Landscape) LandscapeBubblePreview(cxHome, islandTop, cwPx, chPx, appIcon, cfg.landscapeBubbleBelow)
        }
    }
}

// ================================================================ Cam banner

@Composable
private fun BannerPreview(W: Float, d: Float, appIcon: ImageBitmap?, cfg: IslandSettings) {
    var shown by remember { mutableStateOf(false) }
    var which by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500); shown = true; delay(3600); shown = false; delay(900)
            which = (which + 1) % 2
        }
    }
    val accent = remember(appIcon, cfg.notifBottomFx) {
        if (cfg.notifBottomFx && appIcon != null) runCatching { appAccentColor(appIcon.asAndroidBitmap()) }.getOrDefault(0) else 0
    }
    // Biri sohbet bildirimi (kişi + köşede simge), biri uygulama bildirimi (büyük simge)
    val n = if (which == 0) IslandContent.Notif("demo", "Ayşe", "Akşam görüşelim mi? 😊", appIcon, messaging = true, accent = accent)
    else IslandContent.Notif("demo", "Omni Island", "Yeni bir güncelleme hazır", appIcon, messaging = false, accent = accent)
    val wDp = W / d - 20f
    Box(Modifier.offset(10.dp, 10.dp)) {
        NotifBannerRoot(n, shown, wDp, 0, {}, {}, {}, {}, {}, {}, {})
    }
}

// ================================================================ Kenar şeritleri

@Composable
private fun SideVolumePreview(H: Float, d: Float) {
    val ctx = LocalContext.current
    val p by SideVolumeStore.flow(ctx).collectAsState()
    val hPx = (p.lengthDp * d).coerceIn(p.thicknessDp * 2f * d, H)
    val y = (H * p.centerFrac - hPx / 2f).coerceIn(0f, (H - hPx).coerceAtLeast(0f))
    BoxWithConstraints(Modifier.fillMaxSize()) {
        for (left in when (p.side) { 0 -> listOf(true); 1 -> listOf(false); else -> listOf(true, false) }) {
            Box(
                Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                    .offset { IntOffset(0, y.toInt()) }
                    .size(p.thicknessDp.dp, (hPx / d).dp),
            ) { SideVolumeStrip(p, left) }
        }
    }
}

@Composable
private fun SideMusicPreview(H: Float, d: Float) {
    val ctx = LocalContext.current
    val p by SideMusicStore.flow(ctx).collectAsState()
    val media = remember {
        MutableStateFlow<IslandContent.Media?>(
            IslandContent.Media("Örnek Şarkı", "Sanatçı", true, 210_000, 75_000, 1f, SystemClock.elapsedRealtime(), art = null, artColor = SAMPLE_COLOR),
        )
    }
    val hPx = (H * p.lengthFrac).coerceIn(p.thicknessDp * 3f * d, H)
    val y = (H * p.centerFrac - hPx / 2f).coerceIn(0f, (H - hPx).coerceAtLeast(0f))
    Box(Modifier.fillMaxSize()) {
        for (left in when (p.side) { 0 -> listOf(true); 1 -> listOf(false); else -> listOf(true, false) }) {
            val split = p.side == 2 && p.split
            val controls = p.showControls && (!split || left)
            val seek = p.showSeek && (!split || !left)
            if (!controls && !seek) continue
            Box(
                Modifier.align(if (left) Alignment.TopStart else Alignment.TopEnd)
                    .offset { IntOffset(0, y.toInt()) }
                    .size(p.thicknessDp.dp, (hPx / d).dp),
            ) { SideMusicStrip(p, left, controls, seek, media) {} }
        }
    }
}

// ================================================================ Çizerek komut

@Composable
private fun GesturePreview() {
    val ctx = LocalContext.current
    val prefs by GestureStore.flow(ctx).collectAsState()
    val measurer = rememberTextMeasurer()
    val letters = listOf('C', 'M', 'L')
    var idx by remember { mutableStateOf(0) }
    val draw = remember { Animatable(0f) }
    val show = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            show.animateTo(1f, tween(250))
            draw.snapTo(0f)
            draw.animateTo(1f, tween(900, easing = LinearEasing))
            delay(1300)
            show.animateTo(0f, tween(250))
            delay(500)
            idx = (idx + 1) % letters.size
        }
    }
    val ch = letters[idx]
    val action = prefs.map[ch] ?: GestureAction.NONE
    Canvas(Modifier.fillMaxSize()) {
        val a = show.value
        if (a <= 0.01f) return@Canvas
        drawRect(Color.Black.copy(alpha = 0.6f * a))
        val s = size.width * 0.42f
        val ox = (size.width - s) / 2f; val oy = size.height * 0.30f
        fun P(x: Float, y: Float) = Offset(ox + x * s, oy + y * s)
        val path = Path().apply {
            when (ch) {
                'C' -> {
                    for (i in 0..30) {
                        val ang = Math.toRadians((45.0 + 270.0 * i / 30))
                        val p = P(0.5f + 0.5f * kotlin.math.cos(ang).toFloat(), 0.5f - 0.5f * kotlin.math.sin(ang).toFloat())
                        if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y)
                    }
                }
                'M' -> { val q = listOf(P(0f, 1f), P(0f, 0f), P(0.5f, 0.6f), P(1f, 0f), P(1f, 1f)); moveTo(q[0].x, q[0].y); q.drop(1).forEach { lineTo(it.x, it.y) } }
                else -> { moveTo(P(0.15f, 0f).x, P(0.15f, 0f).y); lineTo(P(0.15f, 1f).x, P(0.15f, 1f).y); lineTo(P(0.9f, 1f).x, P(0.9f, 1f).y) }
            }
        }
        val pm = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        pm.getSegment(0f, pm.length * draw.value, part, true)
        drawPath(part, Color(0x4678B4FF), style = Stroke(16.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(part, Color.White.copy(alpha = a), style = Stroke(5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val label = if (draw.value < 1f) "Bir harf çiz"
        else if (action == GestureAction.NONE) "$ch  →  komut atanmamış" else "$ch  →  ${GestureAction.NAMES[action]}"
        val col = if (draw.value < 1f) Color.White.copy(alpha = 0.75f * a) else Color(0xFF8CE6A0).copy(alpha = a)
        val lay = measurer.measure(label, TextStyle(color = col, fontSize = 17.sp, fontWeight = FontWeight.Bold))
        drawText(lay, topLeft = Offset((size.width - lay.size.width) / 2f, size.height * 0.16f))
    }
}

// ================================================================ Yatay mod
// Önizleme kartı telefonu 90° çevirir. Telefonun kendi koordinatında ada yine üstte; yatay bakışta kamera kenarında
// dikey bir hap olur. Bildirim, simgesi düz duran (telefonla birlikte dönmeyen) küçük bir baloncuk olarak ayrılır.

@Composable
private fun LandscapeBubblePreview(cx: Float, top: Float, cw: Float, ch: Float, icon: ImageBitmap?, below: Boolean) {
    val split = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(900)
            split.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 300f))
            delay(2600)
            split.animateTo(0f, tween(380, easing = FastOutSlowInEasing))
            delay(700)
        }
    }
    val d = LocalDensity.current.density
    // Telefon saat yönünün tersine döndüğü için yatay bakışta "aşağı" = telefonun sol tarafı
    val dir = if (below) -1f else 1f
    val bs = ch * 1.05f
    val gap = 8f * d
    Box(Modifier.fillMaxSize()) {
        val p = split.value
        if (p > 0.01f) {
            val bx = cx + dir * (cw / 2f + gap + bs / 2f) * p - bs / 2f
            val by = top + (ch - bs) / 2f
            Box(
                Modifier.offset { IntOffset(bx.toInt(), by.toInt()) }
                    .size((bs / d).dp)
                    .graphicsLayer { scaleX = p; scaleY = p; rotationZ = 90f }   // simge yatay bakışta düz dursun
                    .clip(CircleShape).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) Image(icon, null, Modifier.size((bs * 0.62f / d).dp).clip(CircleShape))
            }
        }
    }
}
