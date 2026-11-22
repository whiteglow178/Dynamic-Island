package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.MediaStore
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Ada çekmecesi (Favori uygulamalar panelinin uyarlaması): uygulamalar yerine hızlı düğmeler.
 * Hiçbiri ek izin istemez: fener kameranın fener modunu, ekran görüntüsü / kilit / paneller erişilebilirlik
 * servisinin sistem eylemlerini, Wi-Fi ve internet sistemin kendi panellerini açar.
 */
enum class DrawerKey(val label: String) {
    Torch("Fener"), Shot("Ekran görüntüsü"), Lock("Kilitle"), Camera("Kamera"),
    Ringer("Zil / titreşim"), Volume("Ses"), Wifi("İnternet"), Quick("Hızlı ayarlar"),
}

/** Çekmece paneli yüksekliği: kamera bölgesi + 2 düğme satırı. */
fun IslandSettings.drawerHeightDp(): Float = favoritesHeightDp(DrawerKey.values().size)

/** Panelden servise köprü: servis açılınca bağlanır. */
object DrawerBridge {
    var onKey: ((DrawerKey) -> Unit)? = null
}

object DrawerActions {
    /** true dönerse panel hemen kapanmalı (eylem ekranı değiştiriyor ya da ekran görüntüsü alınacak). */
    fun closesPanel(k: DrawerKey): Boolean = k != DrawerKey.Torch && k != DrawerKey.Ringer

    fun run(ctx: Context, k: DrawerKey) {
        val a11y = IslandAccessibilityService.instance
        when (k) {
            DrawerKey.Torch -> FlashToggle.toggle(ctx)
            DrawerKey.Shot -> a11y?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            DrawerKey.Lock -> a11y?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            DrawerKey.Quick -> a11y?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            DrawerKey.Camera -> runCatching {
                ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            DrawerKey.Ringer -> runCatching {
                // Sesli <-> titreşim (sessize almak "Rahatsız Etme" erişimi ister; titreşim istemez)
                val am = ctx.getSystemService(AudioManager::class.java)
                am.ringerMode = if (am.ringerMode == AudioManager.RINGER_MODE_NORMAL) AudioManager.RINGER_MODE_VIBRATE else AudioManager.RINGER_MODE_NORMAL
            }
            DrawerKey.Volume -> runCatching {
                ctx.getSystemService(AudioManager::class.java)
                    .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            }
            DrawerKey.Wifi -> runCatching {
                ctx.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    fun active(ctx: Context, k: DrawerKey): Boolean = when (k) {
        DrawerKey.Torch -> FlashToggle.isOn()
        DrawerKey.Ringer -> runCatching { ctx.getSystemService(AudioManager::class.java).ringerMode != AudioManager.RINGER_MODE_NORMAL }.getOrDefault(false)
        else -> false
    }
}

@Composable
fun DrawerPanel(topInset: Dp, onKey: (DrawerKey) -> Unit) {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }       // açık / kapalı durumlarını tazelemek için
    LaunchedEffect(Unit) { FlashToggle.warmUp(ctx); while (true) { delay(400); tick++ } }
    Column(Modifier.fillMaxSize().padding(top = topInset + 6.dp, start = 12.dp, end = 12.dp, bottom = 8.dp)) {
        DrawerKey.values().toList().chunked(FAV_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth().height(FAV_ROW_DP.dp)) {
                row.forEach { k ->
                    val on = tick >= 0 && DrawerActions.active(ctx, k)
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onKey(k); tick++ }.padding(vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Box(
                            Modifier.size(48.dp).clip(CircleShape)
                                .background(if (on) Color.White else Color.White.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            val ink = if (on) Color.Black else Color.White
                            Canvas(Modifier.size(24.dp)) {
                                val sc = size.width / 24f
                                withTransform({ scale(sc, sc, Offset.Zero) }) { drawDrawerGlyph(k, ink, on) }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(k.label, color = Pal.c("fav_label"), fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** 24x24 ızgarada düğme simgeleri. */
private fun DrawScope.drawDrawerGlyph(k: DrawerKey, c: Color, on: Boolean) {
    val st = Stroke(width = 2f, cap = StrokeCap.Round)
    when (k) {
        DrawerKey.Torch -> {
            val head = Path().apply { moveTo(6f, 3f); lineTo(18f, 3f); lineTo(16f, 9f); lineTo(8f, 9f); close() }
            drawPath(head, c)
            drawRoundRect(c, Offset(9f, 9f), Size(6f, 12f), CornerRadius(1.5f, 1.5f))
            drawCircle(if (on) Color.White else Color.Black.copy(alpha = 0.5f), radius = 1.2f, center = Offset(12f, 13f))
        }
        DrawerKey.Shot -> {
            for ((x, y, dx, dy) in listOf(
                floatArrayOf(4f, 4f, 1f, 1f), floatArrayOf(20f, 4f, -1f, 1f), floatArrayOf(4f, 20f, 1f, -1f), floatArrayOf(20f, 20f, -1f, -1f),
            )) {
                drawLine(c, Offset(x, y), Offset(x + 5f * dx, y), strokeWidth = 2f, cap = StrokeCap.Round)
                drawLine(c, Offset(x, y), Offset(x, y + 5f * dy), strokeWidth = 2f, cap = StrokeCap.Round)
            }
            drawCircle(c, radius = 2.5f, center = Offset(12f, 12f))
        }
        DrawerKey.Lock -> {
            drawRoundRect(c, Offset(5f, 10.5f), Size(14f, 10.5f), CornerRadius(2f, 2f))
            drawArc(c, 180f, 180f, false, Offset(8f, 4f), Size(8f, 12f), style = st)
        }
        DrawerKey.Camera -> {
            drawRoundRect(c, Offset(2.5f, 7f), Size(19f, 13f), CornerRadius(3f, 3f), style = st)
            drawRoundRect(c, Offset(8f, 4f), Size(8f, 4f), CornerRadius(1.5f, 1.5f))
            drawCircle(c, radius = 3.6f, center = Offset(12f, 13.5f), style = st)
        }
        DrawerKey.Ringer -> {
            drawPath(BellPath, c)
            if (on) drawLine(c, Offset(3.5f, 3.5f), Offset(20.5f, 20.5f), strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
        DrawerKey.Volume -> {
            val body = Path().apply { moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 4.5f); lineTo(12f, 19.5f); lineTo(7f, 15f); lineTo(3f, 15f); close() }
            drawPath(body, c)
            drawArc(c, -45f, 90f, false, Offset(7f, 7f), Size(10f, 10f), style = st)
            drawArc(c, -45f, 90f, false, Offset(4f, 4f), Size(16f, 16f), style = st)
        }
        DrawerKey.Wifi -> drawWifiGlyph(c)
        DrawerKey.Quick -> {
            drawRoundRect(c, Offset(3f, 5f), Size(18f, 6f), CornerRadius(3f, 3f), style = st)
            drawCircle(c, radius = 2f, center = Offset(17f, 8f))
            drawRoundRect(c, Offset(3f, 13f), Size(18f, 6f), CornerRadius(3f, 3f), style = st)
            drawCircle(c, radius = 2f, center = Offset(7f, 16f))
        }
    }
}
