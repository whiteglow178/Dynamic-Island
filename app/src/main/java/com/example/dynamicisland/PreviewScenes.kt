package com.example.dynamicisland

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

private val ASSIST_COLORS = listOf(
    Color(0xFF4F8CFF), Color(0xFFB45CFF), Color(0xFFFF5FA2),
    Color(0xFFFFA24D), Color(0xFF3FD2E8), Color(0xFF4F8CFF),
)

private fun previewTop(cfg: IslandSettings) = (PHONE_BASE_TOP_DP + cfg.offsetYDp).coerceAtLeast(0f)

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
    val topV = previewTop(cfg)
    val open = if (cfg.assistantEnabled) rememberLoopOpen() else false
    val phase by rememberInfiniteTransition(label = "asst").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "phase",
    )
    val glow by animateFloatAsState(if (open) 1f else 0f, tween(380), label = "glow")

    Box(Modifier.fillMaxSize()) {
        if (cfg.assistantEnabled && cfg.assistantFullWidth) {
            // Ada kutusu yok: renkli animasyon ekranın solundan sağına uzanır
            val h by animateDpAsState(if (open) cfg.assistantHeightDp.dp else 0.dp, spring(0.8f, Spring.StiffnessLow), label = "barH")
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(h)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; alpha = glow }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.verticalGradient(listOf(Color.Black, Color.Transparent)), blendMode = BlendMode.DstIn)
                    }
                    .drawBehind {
                        val w = size.width
                        drawRect(Brush.horizontalGradient(ASSIST_COLORS, startX = phase * w, endX = phase * w + w, tileMode = TileMode.Repeated))
                    },
            )
        } else {
            val w by animateDpAsState(if (open) cfg.assistantWidthDp.dp else cfg.compactWidthDp.dp, spring(0.72f, Spring.StiffnessMediumLow), label = "w")
            val h by animateDpAsState(if (open) cfg.assistantHeightDp.dp else cfg.compactHeightDp.dp, spring(0.72f, Spring.StiffnessMediumLow), label = "h")
            val shape = RoundedCornerShape(minOf(h / 2, 44.dp))
            Box(
                Modifier.align(Alignment.TopCenter).offset(x = cfg.offsetXDp.dp, y = topV.dp)
                    .size(w, h).clip(shape).background(Color.Black),
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = glow }
                        .drawBehind {
                            val ww = size.width
                            drawRect(Brush.horizontalGradient(ASSIST_COLORS, startX = phase * ww, endX = phase * ww + ww, tileMode = TileMode.Repeated))
                        },
                )
                Box(
                    Modifier.fillMaxSize().graphicsLayer { alpha = glow }
                        .background(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.88f), Color.Transparent))),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- Favori uygulamalar
@Composable
private fun FavoritesScene(cfg: IslandSettings) {
    val ctx = LocalContext.current
    val pkgs = cfg.favoritePkgList()
    val apps by produceState<List<Pair<String, ImageBitmap?>>>(emptyList(), pkgs) {
        value = withContext(Dispatchers.Default) {
            val pm = ctx.packageManager
            pkgs.map { pkg ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("")
                val icon = runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
                label to icon
            }
        }
    }
    val items: List<Pair<String, ImageBitmap?>> = if (apps.isEmpty()) List(FAV_COLUMNS) { "Uygulama" to null } else apps
    val rows = ((items.size + FAV_COLUMNS - 1) / FAV_COLUMNS).coerceAtLeast(1)
    val topInset = cfg.compactHeightDp
    val fullH = topInset + 6f + rows * FAV_ROW_DP + 8f
    val open = if (cfg.favoritesEnabled) rememberLoopOpen() else false
    val w by animateDpAsState(if (open) cfg.expandedWidthDp.dp else cfg.compactWidthDp.dp, spring(0.78f, Spring.StiffnessMediumLow), label = "favW")
    val h by animateDpAsState(if (open) fullH.dp else cfg.compactHeightDp.dp, spring(0.78f, Spring.StiffnessMediumLow), label = "favH")
    val content by animateFloatAsState(if (open) 1f else 0f, tween(300), label = "favContent")
    val shape = RoundedCornerShape(minOf(h / 2, 32.dp))

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.align(Alignment.TopCenter).offset(x = cfg.offsetXDp.dp, y = previewTop(cfg).dp)
                .size(w, h).clip(shape).background(Color.Black),
        ) {
            Column(
                Modifier.fillMaxSize().padding(top = (topInset + 6f).dp, start = 12.dp, end = 12.dp)
                    .graphicsLayer { alpha = content },
            ) {
                items.chunked(FAV_COLUMNS).forEach { row ->
                    Row(Modifier.fillMaxWidth().height(FAV_ROW_DP.dp)) {
                        row.forEach { (label, icon) ->
                            Column(
                                Modifier.weight(1f).padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center,
                            ) {
                                val iconShape = RoundedCornerShape(12.dp)
                                if (icon != null) Image(icon, null, Modifier.size(48.dp).clip(iconShape), contentScale = ContentScale.Fit)
                                else Box(Modifier.size(48.dp).clip(iconShape).background(Color.White.copy(alpha = 0.18f)))
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    label, color = Color.White.copy(alpha = 0.85f), fontSize = 10.5.sp, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                    modifier = Modifier.padding(horizontal = 2.dp),
                                )
                            }
                        }
                        repeat(FAV_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Kenar tuşları
@Composable
private fun SideCallScene(cfg: IslandSettings) {
    val ctx = LocalContext.current
    val p by SideCallStore.flow(ctx).collectAsState()
    val open = if (p.enabled) rememberLoopOpen() else false
    val slide by animateDpAsState(if (open) 0.dp else 72.dp, spring(0.8f, Spring.StiffnessLow), label = "sideSlide")

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val pillH = 116.dp
        val y = maxHeight * 0.58f - pillH / 2

        // Gelen arama adası
        val callShape = RoundedCornerShape(30.dp)
        val callW = if (p.enabled) minOf(cfg.expandedWidthDp.dp, maxWidth - 24.dp) else cfg.compactWidthDp.dp
        val callH = if (p.enabled) 60.dp else cfg.compactHeightDp.dp
        Box(
            Modifier.align(Alignment.TopCenter).offset(x = cfg.offsetXDp.dp, y = previewTop(cfg).dp)
                .size(callW, callH).clip(callShape).background(Color.Black),
        ) {
            if (p.enabled) {
                Row(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Ayşe", color = Color.White, fontSize = 14.sp, maxLines = 1)
                        Text("Gelen arama", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, maxLines = 1)
                    }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(SIDE_PREVIEW_RED), contentAlignment = Alignment.Center) {
                        Text("✆", color = Color.White, fontSize = 15.sp, modifier = Modifier.rotate(135f))
                    }
                    Box(Modifier.size(30.dp).clip(CircleShape).background(SIDE_PREVIEW_GREEN), contentAlignment = Alignment.Center) {
                        Text("✆", color = Color.White, fontSize = 15.sp)
                    }
                }
            }
        }

        if (p.enabled && (p.side == 0 || p.side == 2)) {
            SidePillPreview(p.transparency, Modifier.align(Alignment.TopStart).offset(x = 6.dp - slide, y = y))
        }
        if (p.enabled && (p.side == 1 || p.side == 2)) {
            SidePillPreview(p.transparency, Modifier.align(Alignment.TopEnd).offset(x = (-6).dp + slide, y = y))
        }
    }
}

private val SIDE_PREVIEW_GREEN = Color(0xFF34C759)
private val SIDE_PREVIEW_RED = Color(0xFFFF3B30)

@Composable
private fun SidePillPreview(transparency: Float, modifier: Modifier) {
    Column(
        modifier.size(58.dp, 116.dp).clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = (1f - transparency).coerceIn(0f, 1f))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(SIDE_PREVIEW_GREEN), contentAlignment = Alignment.Center) {
            Text("✆", color = Color.White, fontSize = 22.sp)
        }
        Box(Modifier.size(46.dp).clip(CircleShape).background(SIDE_PREVIEW_RED), contentAlignment = Alignment.Center) {
            Text("✆", color = Color.White, fontSize = 22.sp, modifier = Modifier.rotate(135f))
        }
    }
}
