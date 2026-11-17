package com.example.dynamicisland

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

private val TR = Locale("tr", "TR")

/** "BOYUT" gibi tamamı büyük harfli başlıkları "Boyut" şekline çevirir; zaten düzgün yazılmışsa dokunmaz. */
private fun softCase(s: String): String =
    if (s.length > 1 && s.any { it.isLetter() } && s == s.uppercase(TR))
        s.lowercase(TR).replaceFirstChar { it.titlecase(TR) }
    else s

// ---------------------------------------------------------------- Dokunsal geri bildirim + satır ayırıcı
/** Ayar ekranındaki anahtar / kaydırıcı titreşimi (Ayarlar > Dokunsal geri bildirim). */
object UiHaptics { @Volatile var enabled: Boolean = true }

/** SectionCard içindeki satırlar alt kenarlarına iOS tarzı ince ayırıcı çizer (kartın son satırınınki maskelenir). */
val LocalRowDivider = compositionLocalOf { false }

@Composable
fun Modifier.rowDivider(): Modifier {
    if (!LocalRowDivider.current) return this
    val c = MaterialTheme.colorScheme.outlineVariant
    return drawBehind {
        val y = size.height + 7.dp.toPx()          // satırlar arası 14 dp boşluğun ortası
        drawLine(c, Offset(0f, y), Offset(size.width, y), strokeWidth = 0.5.dp.toPx())
    }
}

// ---------------------------------------------------------------- Yapı
/**
 * Ayar grubu: başlık kartın ÜSTÜNDE sakin bir etiket olarak durur, satırlar tek bir yüzey içinde toplanır.
 * İçerik açılıp kapanınca yüzey akıcı biçimde büyür / küçülür.
 */
@Composable
fun SectionCard(title: String, subtitle: String? = null, content: @Composable ColumnScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // iOS grup başlığı: kartın üstünde küçük, gri
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(softCase(title), color = cs.onSurfaceVariant, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp)
            if (subtitle != null) Text(subtitle, color = cs.onSurfaceVariant.copy(alpha = 0.75f), fontSize = 12.sp, lineHeight = 16.sp)
        }
        CompositionLocalProvider(LocalRowDivider provides true) {
            Column(
                Modifier.fillMaxWidth()
                    .animateContentSize(tween(260, easing = FastOutSlowInEasing))
                    .clip(shape).background(cs.surface)
                    // Son satırın altındaki ayırıcı, kartın alt boşluğuna taşar: o şeridi kart rengiyle örter
                    .drawWithContent {
                        drawContent()
                        val strip = 14.dp.toPx()
                        drawRect(cs.surface, Offset(0f, size.height - strip), Size(size.width, strip))
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) { content() }
        }
    }
}

/** Kartlar arasında bölüm başlığı (ör. "Yerleşim", "Görünüm", "Hareket"). */
@Composable
fun GroupLabel(text: String) =
    Text(
        softCase(text), color = MaterialTheme.colorScheme.onBackground, fontSize = 26.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp),
    )

@Composable
fun SubHeader(text: String) =
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)

@Composable
fun SectionDivider() = HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)

// ---------------------------------------------------------------- Satırlar
@Composable
fun SwitchRow(title: String, desc: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val haptic = LocalHapticFeedback.current
    val trackColor by animateColorAsState(
        if (checked) Color(0xFF4CD964) else Color(0xFFE4E1DE), tween(200), label = "switchTrack",
    )
    // Düğme: 64 dp çubuk - 2x4 dp kenar boşluğu - 26 dp düğme = 30 dp yol
    val knobX by animateDpAsState(
        if (checked) 30.dp else 0.dp,
        spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMedium), label = "switchKnob",
    )
    Row(
        Modifier.fillMaxWidth().rowDivider().clip(RoundedCornerShape(12.dp))
            .toggleable(value = checked, role = Role.Switch, onValueChange = {
                if (UiHaptics.enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onChange(it)
            })
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (desc != null) Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
        }
        Box(
            Modifier.width(64.dp).height(34.dp)
                .clip(RoundedCornerShape(50))
                .background(trackColor)
                .padding(4.dp),
        ) {
            Box(
                Modifier.offset(x = knobX).size(26.dp)
                    .shadow(2.dp, CircleShape)          // gölge, arka plandan ÖNCE verilmeli
                    .background(Color.White, CircleShape),
            )
        }
    }
}

/** Dokunulan x konumunu (px) aralıktaki değere çevirir. Düğme yarıçapı kadar kenar payı bırakır. */
private fun sliderValueAt(x: Float, widthPx: Float, thumbPx: Float, start: Float, end: Float): Float {
    val usable = (widthPx - thumbPx).coerceAtLeast(1f)
    val f = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
    return start + (end - start) * f
}

/**
 * Özel kaydırıcı: kesintisiz yuvarlak uçlu çubuk (beyaz dolu kısım + yarı saydam kalan kısım),
 * üzerinde çubuğu kesmeyen cam efektli büyük daire düğme. Material Slider kullanılmaz.
 */
@Composable
fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, valueText: String, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val thumbW = 30.dp   // düğme genişliği
    val thumbH = 22.dp   // düğme yüksekliği (daha küçük ve yassı kapsül)
    val trackH = 8.dp
    val start = range.start
    val end = range.endInclusive
    val fraction = if (end > start) ((value - start) / (end - start)).coerceIn(0f, 1f) else 0f

    var dragging by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(
        if (dragging) 1.12f else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "sliderThumb",
    )
    val rawOnChange by rememberUpdatedState(onChange)
    val latestStart by rememberUpdatedState(start)
    val latestEnd by rememberUpdatedState(end)
    // Değer aralığı 20 kademeye bölünür; kademe geçince hafif titreşim verilir
    val haptic = LocalHapticFeedback.current
    val lastTick = remember { intArrayOf(-1) }
    val latestOnChange: (Float) -> Unit = remember {
        { v: Float ->
            val span = latestEnd - latestStart
            val tick = if (span > 0f) (((v - latestStart) / span) * 20f).toInt() else 0
            if (tick != lastTick[0]) {
                if (lastTick[0] != -1 && UiHaptics.enabled) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                lastTick[0] = tick
            }
            rawOnChange(v)
        }
    }

    Column(Modifier.rowDivider()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f, fill = false))
            Spacer(Modifier.width(12.dp))
            Text(valueText, color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(44.dp)
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value.coerceIn(start, end), range)
                    setProgress { target -> latestOnChange(target.coerceIn(start, end)); true }
                }
                .pointerInput(Unit) {
                    val thumbPx = thumbW.toPx()
                    detectTapGestures { pos ->
                        latestOnChange(sliderValueAt(pos.x, size.width.toFloat(), thumbPx, latestStart, latestEnd))
                    }
                }
                .pointerInput(Unit) {
                    val thumbPx = thumbW.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { pos ->
                            dragging = true
                            latestOnChange(sliderValueAt(pos.x, size.width.toFloat(), thumbPx, latestStart, latestEnd))
                        },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { change, _ ->
                        change.consume()
                        latestOnChange(sliderValueAt(change.position.x, size.width.toFloat(), thumbPx, latestStart, latestEnd))
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val travel = maxWidth - thumbW
            // Boş kısım (yarı saydam)
            Box(Modifier.fillMaxWidth().height(trackH).clip(CircleShape).background(Color.White.copy(alpha = 0.25f)))
            // Dolu kısım: düğmenin merkezine kadar uzanır
            Box(Modifier.width(thumbW / 2 + travel * fraction).height(trackH).clip(CircleShape).background(Color.White))
            // Cam düğme: renk tonu yok, LiquidGlass.kt'deki cam yüzey + kenar parlaması çizilir
            Box(
                Modifier.offset(x = travel * fraction).size(thumbW, thumbH)
                    .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                    .clip(CircleShape) // dikdörtgende tam kapsül (uçları yarım daire)
                    .drawBehind {
                        drawLiquidBase(specular = 0.8f)
                        drawLiquidRim(specular = 0.9f)
                    },
            )
        }
    }
}

@Composable
fun RadioRow(title: String, desc: String, selected: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val bg by animateColorAsState(if (selected) cs.surfaceVariant else Color.Transparent, tween(200), label = "radioBg")
    val edge by animateColorAsState(if (selected) cs.primary.copy(alpha = 0.55f) else cs.outlineVariant, tween(200), label = "radioEdge")
    val ring by animateColorAsState(if (selected) cs.primary else cs.outline, tween(200), label = "radioRing")
    val dot by animateDpAsState(if (selected) 10.dp else 0.dp, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium), label = "radioDot")
    Row(
        Modifier.fillMaxWidth().clip(shape).background(bg).border(1.dp, edge, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(20.dp).border(2.dp, ring, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(dot.coerceAtLeast(0.dp)).background(cs.primary, CircleShape))
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, color = cs.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp)
        }
    }
}

// ---------------------------------------------------------------- Sekmeler (kayan seçici)
@Composable
fun SegmentedTabs(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    BoxWithConstraints(
        Modifier.fillMaxWidth().clip(shape).background(cs.surfaceVariant).padding(2.dp),
    ) {
        val segW = maxWidth / labels.size.coerceAtLeast(1)
        val x by animateDpAsState(
            segW * selected.coerceIn(0, (labels.size - 1).coerceAtLeast(0)),
            spring(dampingRatio = 0.82f, stiffness = Spring.StiffnessMediumLow), label = "tabX",
        )
        Box(Modifier.offset(x = x).width(segW).height(34.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF636366)))
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, label ->
                val sel = i == selected
                val fg by animateColorAsState(if (sel) Color.White else cs.onSurfaceVariant, tween(220), label = "tabFg$i")
                Box(
                    Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                        .selectable(selected = sel, role = Role.Tab) { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, color = fg, fontSize = 13.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Eylem düğmesi
@Composable
fun IslandActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, tonal: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier.fillMaxWidth().height(50.dp).clip(shape)
            .background(if (tonal) cs.surfaceVariant else cs.primary)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (tonal) cs.onSurface else cs.onPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ---------------------------------------------------------------- Arama kutusu
@Composable
fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "Ayarlarda ara") {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier.fillMaxWidth().height(40.dp).clip(shape).background(cs.surfaceVariant).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val tint = cs.onSurfaceVariant
        Canvas(Modifier.size(16.dp)) {
            val sw = 1.8.dp.toPx()
            drawCircle(tint, radius = size.minDimension * 0.34f, center = Offset(size.width * 0.42f, size.height * 0.42f), style = Stroke(sw))
            drawLine(tint, Offset(size.width * 0.68f, size.height * 0.68f), Offset(size.width * 0.92f, size.height * 0.92f), strokeWidth = sw, cap = StrokeCap.Round)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(hint, color = tint, fontSize = 15.sp)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(cs.primary), modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(tint.copy(alpha = 0.35f)).clickable(role = Role.Button) { onChange("") },
                contentAlignment = Alignment.Center,
            ) { Text("✕", color = Color.White, fontSize = 10.sp) }
        }
    }
}
