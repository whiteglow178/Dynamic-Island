package com.example.dynamicisland

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Üstteki önizleme telefonunda hangi ayar grubunun gösterileceği. */
internal enum class PreviewKind { Island, Assistant, Favorites, SideCall }

/** "Tam genişlik" gibi geniş ayarlar için ayarlar sayfasının üstüne binen, tamamı görünen telefon ölçüsü. */
internal const val BIG_PHONE_W_DP = 200f
internal const val BIG_PHONE_H_DP = 430f

// ---------------------------------------------------------------- Telefon maketi
/**
 * Verilen genişlik/yükseklikte telefon maketi. İçerik 412 dp genişliğinde "sanal" ekranda gerçek dp değerleriyle çizilir,
 * yoğunluk küçültülerek maket ekrana sığdırılır (ada ayarları birebir orantılı görünür).
 */
@Composable
internal fun PhoneMock(wDp: Float, hDp: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val k = wDp / PHONE_W_DP
    val bezel = PHONE_BEZEL_DP * k
    val scale = (wDp - 2f * bezel) / PHONE_VIRTUAL_W_DP
    val phoneShape = RoundedCornerShape((38f * k).dp)
    val screenShape = RoundedCornerShape(((38f - PHONE_BEZEL_DP) * k).dp)
    val dens = LocalDensity.current
    val btn = Color(0xFF2A2D35)
    Box(modifier.width(wDp.dp).height(hDp.dp)) {
        Box(Modifier.align(Alignment.TopEnd).offset(x = (2f * k).dp, y = (90f * k).dp).width((3f * k).dp).height((44f * k).dp)
            .clip(RoundedCornerShape(2.dp)).background(btn))
        Box(Modifier.align(Alignment.TopStart).offset(x = (-2f * k).dp, y = (76f * k).dp).width((3f * k).dp).height((28f * k).dp)
            .clip(RoundedCornerShape(2.dp)).background(btn))
        Box(Modifier.align(Alignment.TopStart).offset(x = (-2f * k).dp, y = (112f * k).dp).width((3f * k).dp).height((28f * k).dp)
            .clip(RoundedCornerShape(2.dp)).background(btn))
        Box(
            Modifier.fillMaxSize().clip(phoneShape)
                .background(Color(0xFF08090C))
                .border((1.5f * k).dp, Color(0xFF454956), phoneShape)
                .padding(bezel.dp),
        ) {
            Box(
                Modifier.fillMaxSize().clip(screenShape)
                    .background(Brush.verticalGradient(listOf(Color(0xFF2E6A93), Color(0xFF5B4496), Color(0xFFB2557F)))),
            ) {
                CompositionLocalProvider(LocalDensity provides Density(dens.density * scale, dens.fontScale)) {
                    Box(Modifier.fillMaxSize()) { content() }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Ortak yardımcılar
/** Aç → bekle → kapan → bekle döngüsü; true iken panel / animasyon açık. */
@Composable
private fun rememberLoopOpen(): Boolean {
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            open = false; delay(600)
            open = true; delay(3800)
            open = false; delay(900)
        }
    }
    return open
}

private fun previewTop(cfg: IslandSettings) = (PHONE_BASE_TOP_DP + cfg.offsetYDp).coerceAtLeast(0f)

/**
 * Gerçek [IslandPill]'i (asıl adayla aynı bileşen) ayarlarla çizer; önizleme taklit değil, uygulamanın kendisidir.
 * Açılıp kapanma, şekil, renk, cam, çerçeve, parıltı hep gerçek koddan gelir.
 */
@Composable
private fun PreviewPill(content: IslandContent, open: Boolean, geo: Geo, cfg: IslandSettings, modifier: Modifier) {
    val progress = rememberIslandProgress(cfg, open)
    val frame = remember { LyricFrame(mutableIntStateOf(-1), mutableFloatStateOf(0f)) }
    Box(modifier.offset(x = cfg.offsetXDp.dp, y = previewTop(cfg).dp)) {
        IslandPill(content, progress, geo, cfg, LyricsState.Idle, frame, false, {}, {}, {})
    }
}

/** Telefonun içine (sanal ekrana) çizilen ayar önizlemesi. Island türü SettingsScreen içinde çizilir. */
@Composable
internal fun PreviewScene(kind: PreviewKind, cfg: IslandSettings) {
    when (kind) {
        PreviewKind.Assistant -> AssistantScene(cfg)
        PreviewKind.Favorites -> FavoritesScene(cfg)
        PreviewKind.SideCall -> SideCallScene(cfg)
        PreviewKind.Island -> Unit
    }
}

/** Önizlemenin altındaki kısa açıklama (Bildirim / Müzik sekmeleri yerine). */
internal fun previewHint(kind: PreviewKind, cfg: IslandSettings, bigDismissed: Boolean): String = when (kind) {
    PreviewKind.Assistant -> when {
        !cfg.assistantEnabled -> "Asistan animasyonu kapalı"
        cfg.assistantFullWidth -> if (bigDismissed) "Tam genişlik · büyük telefonu açmak için dokun" else "Tam genişlik · büyük önizlemeyi kapatmak için telefona dokun"
        else -> "Asistan açılınca ada renkli animasyona dönüşür"
    }
    PreviewKind.Favorites -> if (cfg.favoritesEnabled) "Boştaki adaya dokununca açılan favori paneli" else "Favori uygulamalar kapalı"
    PreviewKind.SideCall -> "Gelen aramada kenar tuşları"
    PreviewKind.Island -> ""
}

// ---------------------------------------------------------------- Asistan
@Composable
private fun AssistantScene(cfg: IslandSettings) {
    val open = if (cfg.assistantEnabled) rememberLoopOpen() else false
    Box(Modifier.fillMaxSize()) {
        if (cfg.assistantEnabled && cfg.assistantFullWidth) {
            // IslandRoot ile aynı: ada kutusu yok, gerçek AssistantOrb ekranın solundan sağına çizilir
            val barAlpha by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 350 else 250), label = "orbBar")
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(cfg.assistantHeightDp.dp)
                    .graphicsLayer { alpha = barAlpha },
            ) { AssistantOrb(fullWidth = true) }
        } else {
            // IslandRoot ile aynı geometri: açık boyut = asistan genişliği / yüksekliği; içerik gerçek AssistantOrb
            val geo = islandGeo(cfg, false).copy(ew = cfg.assistantWidthDp.dp, eh = cfg.assistantHeightDp.dp)
            PreviewPill(IslandContent.Assistant, open, geo, cfg, Modifier.align(Alignment.TopCenter))
        }
    }
}

// ---------------------------------------------------------------- Favori uygulamalar
@Composable
private fun FavoritesScene(cfg: IslandSettings) {
    val ctx = LocalContext.current
    val pkgs = cfg.favoritePkgList()
    val apps by produceState<List<FavApp>>(emptyList(), pkgs) {
        value = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            pkgs.map { pkg ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
                val icon: ImageBitmap? = runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                FavApp(pkg, label, icon)
            }
        }
    }
    // Hiç uygulama seçilmediyse yer tutucu simgeler
    val items = if (apps.isEmpty()) List(FAV_COLUMNS) { FavApp("", "Uygulama", null) } else apps
    val content = remember(items) { IslandContent.Favorites(items) }
    val geo = islandGeo(cfg, false).copy(eh = cfg.favoritesHeightDp(items.size).dp)
    val open = if (cfg.favoritesEnabled) rememberLoopOpen() else false

    Box(Modifier.fillMaxSize()) {
        PreviewPill(content, open, geo, cfg, Modifier.align(Alignment.TopCenter))
    }
}

// ---------------------------------------------------------------- Kenar tuşları
@Composable
private fun SideCallScene(cfg: IslandSettings) {
    val ctx = LocalContext.current
    val p by SideCallStore.flow(ctx).collectAsState()
    val open = if (p.enabled) rememberLoopOpen() else false
    val slide by animateDpAsState(if (open) 0.dp else 72.dp, spring(0.8f, Spring.StiffnessLow), label = "sideSlide")

    // Gelen arama adası: gerçek IslandPill + gerçek CallContent (IslandUi.kt ile aynı geometri: açık yükseklik 74 dp)
    val call = remember {
        IslandContent.Call(name = "Ayşe", isIncoming = true, duration = null, avatar = null)
    }
    val geo = islandGeo(cfg, false).copy(eh = 74.dp)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Gerçek pencere: kenardan SIDE_MARGIN_DP boşluk, dikey konum ekran yüksekliğinin SIDE_Y_FRACTION'ı
        val y = maxHeight * SIDE_Y_FRACTION - SIDE_H_DP.dp / 2
        val m = SIDE_MARGIN_DP.dp

        PreviewPill(call, open, geo, cfg, Modifier.align(Alignment.TopCenter))

        // Gerçek SideCallPill (ayarlardaki şeffaflıkla); servis her kenar için ayrı pencere açar
        if (p.enabled && (p.side == 0 || p.side == 2)) {
            Box(Modifier.align(Alignment.TopStart).offset(x = m - slide, y = y).size(SIDE_W_DP.dp, SIDE_H_DP.dp)) {
                SideCallPill(p, {}, {})
            }
        }
        if (p.enabled && (p.side == 1 || p.side == 2)) {
            Box(Modifier.align(Alignment.TopEnd).offset(x = -m + slide, y = y).size(SIDE_W_DP.dp, SIDE_H_DP.dp)) {
                SideCallPill(p, {}, {})
            }
        }
    }
}
