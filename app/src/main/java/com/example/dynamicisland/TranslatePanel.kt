package com.example.dynamicisland

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val PanelText: Color get() = Pal.c("tr_text")
private val PanelDim: Color get() = Pal.c("tr_dim")
private val PanelField: Color get() = Pal.c("tr_field")

// ---------------------------------------------------------------- Adanın içindeki çeviri paneli
/**
 * Üstte dil seçimi (Türkçe ⇄ İngilizce), altında klavyenin açılacağı yazı alanı ve çeviri sonucu.
 * Panel yüksekliği sonuç uzunluğuna göre servis tarafından ayarlanır ([TranslateSession.bodyDp]).
 */
@Composable
fun TranslatePanel(topInset: Dp) {
    val ui by TranslateSession.state.collectAsState()
    var text by remember { mutableStateOf(TextFieldValue("")) }
    val focus = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val view = LocalView.current
    val inPreview = LocalIslandPreview.current

    // Diller yer değiştirince sonuç yazı alanına taşınır
    LaunchedEffect(ui.nonce) {
        if (ui.nonce > 0) text = TextFieldValue(ui.input, TextRange(ui.input.length))
    }
    // Panel açılınca (ve dil listesinden dönünce) klavye açılır; pencere odağı alana kadar kısa süre beklenir
    LaunchedEffect(ui.picking == 0) {
        if (ui.picking != 0 || inPreview) return@LaunchedEffect     // ayarlar önizlemesinde klavye açılmaz
        delay(220)
        var tries = 0
        while (!view.hasWindowFocus() && tries < 15) { delay(100); tries++ }
        runCatching { focus.requestFocus() }
        kb?.show()
        delay(200)
        kb?.show()
    }

    Column(
        Modifier.fillMaxSize().padding(top = topInset + 6.dp, start = 14.dp, end = 14.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ---- Dil satırı: [Kaynak ▾] ⇄ [Hedef ▾]            ✕
        Row(
            Modifier.fillMaxWidth().height(TR_ROW_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            LangChip(TranslateSession.nameOf(ui.src), ui.picking == 1, Modifier.weight(1f)) { TranslateSession.pick(1) }
            Box(
                Modifier.size(36.dp).clip(CircleShape).clickable { TranslateSession.swap() },
                contentAlignment = Alignment.Center,
            ) { SwapIcon(PanelText.copy(alpha = 0.9f), 20.dp) }
            LangChip(TranslateSession.nameOf(ui.tgt), ui.picking == 2, Modifier.weight(1f)) { TranslateSession.pick(2) }
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(PanelField)
                    .clickable { TranslateSession.closeRequest?.invoke() },
                contentAlignment = Alignment.Center,
            ) { CrossIcon(PanelText.copy(alpha = 0.85f), 13.dp) }
        }

        if (ui.picking != 0) {
            LangList(if (ui.picking == 1) ui.src else ui.tgt) { TranslateSession.setLang(ui.picking, it) }
        } else {
            // ---- Yazı alanı (klavye buradan açılır)
            Box(
                Modifier.fillMaxWidth().height(TR_INPUT_DP.dp).clip(RoundedCornerShape(14.dp)).background(PanelField)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (text.text.isEmpty()) {
                            Text("Çevirmek istediğin metni yaz", color = Pal.c("tr_hint"), fontSize = 15.sp, maxLines = 1)
                        }
                        BasicTextField(
                            value = text,
                            onValueChange = { text = it; TranslateSession.onText(it.text) },
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            textStyle = TextStyle(color = PanelText, fontSize = 16.sp, lineHeight = 21.sp),
                            cursorBrush = SolidColor(PanelText),
                            maxLines = 2,
                            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                            keyboardActions = KeyboardActions(onDone = { kb?.hide() }),
                        )
                    }
                    if (text.text.isNotEmpty()) {
                        Box(
                            Modifier.size(28.dp).clip(CircleShape).clickable { text = TextFieldValue(""); TranslateSession.onText("") },
                            contentAlignment = Alignment.Center,
                        ) { CrossIcon(PanelDim, 11.dp) }
                    }
                }
            }

            // ---- Çeviri sonucu (yalnızca yazı varken); alan sonuç uzunluğuna göre büyür
            if (ui.input.isNotBlank()) {
                val hasOut = ui.output.isNotEmpty()
                val shown = if (hasOut) ui.output else ui.message()
                val h = (ui.resultLines() * TR_LINE_DP + 16).dp
                Row(
                    Modifier.fillMaxWidth().height(h).clip(RoundedCornerShape(14.dp)).background(Pal.c("tr_box"))
                        .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(
                        shown,
                        Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        color = when {
                            ui.status == TranslateStatus.Error && !hasOut -> Pal.c("tr_error")
                            hasOut -> PanelText
                            else -> PanelDim
                        },
                        fontSize = 16.sp, lineHeight = 21.sp,
                        fontWeight = if (hasOut) FontWeight.Medium else FontWeight.Normal,
                    )
                    if (hasOut) CopyChip(ui.output)
                }
            }
        }
    }
}

@Composable
private fun LangChip(name: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(36.dp).clip(RoundedCornerShape(12.dp))
            .background(Pal.c("tr_chip").copy(alpha = if (active) 0.24f else 0.12f))
            .clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            name, color = PanelText, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(5.dp))
        ChevronIcon(PanelDim, 10.dp)
    }
}

@Composable
private fun CopyChip(value: String) {
    val ctx = LocalContext.current
    var copied by remember(value) { mutableStateOf(false) }
    Box(
        Modifier.padding(start = 6.dp).height(26.dp).clip(RoundedCornerShape(9.dp)).background(Pal.c("tr_chip_soft"))
            .clickable {
                runCatching {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("çeviri", value))
                    copied = true
                }
            }
            .padding(horizontal = 9.dp),
        contentAlignment = Alignment.Center,
    ) { Text(if (copied) "Kopyalandı" else "Kopyala", color = PanelText, fontSize = 11.5.sp, maxLines = 1) }
}

@Composable
private fun LangList(current: String, onPick: (String) -> Unit) {
    val langs = remember { TranslateSession.languages }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = langs.indexOfFirst { it.code == current }.coerceAtLeast(0))
    LazyColumn(
        Modifier.fillMaxWidth().height(TR_PICK_DP.dp).clip(RoundedCornerShape(14.dp)).background(Pal.c("tr_box")),
        state = state,
    ) {
        items(langs, key = { it.code }) { l ->
            Row(
                Modifier.fillMaxWidth().height(40.dp).clickable { onPick(l.code) }.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    l.name, Modifier.weight(1f), color = PanelText, fontSize = 15.sp,
                    fontWeight = if (l.code == current) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (l.code == current) Box(Modifier.size(8.dp).clip(CircleShape).background(Pal.c("tr_selected")))
            }
        }
    }
}

// ---------------------------------------------------------------- Küçük simgeler (ek simge kütüphanesi gerekmez)
@Composable
private fun SwapIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height; val sw = w * 0.1f
        fun ln(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(w * x1, h * y1), Offset(w * x2, h * y2), sw, StrokeCap.Round)
        ln(0.12f, 0.34f, 0.88f, 0.34f); ln(0.88f, 0.34f, 0.68f, 0.16f); ln(0.88f, 0.34f, 0.68f, 0.52f)   // →
        ln(0.88f, 0.68f, 0.12f, 0.68f); ln(0.12f, 0.68f, 0.32f, 0.50f); ln(0.12f, 0.68f, 0.32f, 0.86f)   // ←
    }
}

@Composable
private fun CrossIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = w * 0.14f
        drawLine(color, Offset(0f, 0f), Offset(w, w), sw, StrokeCap.Round)
        drawLine(color, Offset(w, 0f), Offset(0f, w), sw, StrokeCap.Round)
    }
}

@Composable
private fun ChevronIcon(color: Color, size: Dp) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width; val sw = w * 0.16f
        drawLine(color, Offset(0f, w * 0.3f), Offset(w / 2f, w * 0.72f), sw, StrokeCap.Round)
        drawLine(color, Offset(w, w * 0.3f), Offset(w / 2f, w * 0.72f), sw, StrokeCap.Round)
    }
}

// ---------------------------------------------------------------- Ayarlar bölümü
@Composable
fun TranslateSection(draft: IslandSettings, set: (IslandSettings) -> Unit) {
    val cs = MaterialTheme.colorScheme
    var picking by remember { mutableStateOf(0) }    // 0 = kapalı, 1 = kaynak, 2 = hedef

    SectionCard("Çeviri", "Adaya dokunup yazdığın kelimeyi ya da cümleyi anında çevir") {
        SwitchRow(
            "Çeviri paneli",
            "Ada boştayken açılır: üstte dil seçimi, altta yazı alanı ve çeviri. Panel gerektiği kadar genişler",
            draft.translateEnabled,
        ) { set(draft.copy(translateEnabled = it)) }

        if (draft.translateEnabled) {
            if (!draft.showIdle) {
                Text(
                    "Çalışması için yukarıdaki \"Boştayken de göster\" ayarı açık olmalı.",
                    color = cs.error, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            SubHeader("Ne zaman açılsın?")
            RadioRow("Dokununca", "Boştayken adaya kısa dokunuşla açılır", draft.translateTrigger == 0) {
                set(draft.copy(translateTrigger = 0))
            }
            RadioRow("Basılı tutunca", "Yalnızca adaya basılı tutunca açılır", draft.translateTrigger == 1) {
                set(draft.copy(translateTrigger = 1))
            }
            if (draft.favoritesReady()) {
                Text(
                    when {
                        draft.translateTrigger == 0 -> "Favori uygulamalar bu durumda adaya basılı tutunca açılır."
                        draft.favoritesTrigger == 1 -> "Favori uygulamalar da basılı tutmayı kullanıyor; bu hareket çeviriyi açar, favoriler açılmaz."
                        else -> "Favori uygulamalar dokununca açılmaya devam eder."
                    },
                    color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
                )
            }
            SectionDivider()
            SubHeader("Varsayılan diller")
            LangRow("Kaynak dil", draft.translateSrc) { picking = 1 }
            LangRow("Hedef dil", draft.translateTgt) { picking = 2 }
            Text(
                "Panelde dilleri değiştirince son seçimin hatırlanır. Çeviri cihazda yapılır; her dil modeli ilk kullanımda (yaklaşık 30 MB) iner, sonrasında internetsiz çalışır.",
                color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }

    if (picking != 0) {
        val which = picking
        LangDialog(
            title = if (which == 1) "Kaynak dil" else "Hedef dil",
            current = if (which == 1) draft.translateSrc else draft.translateTgt,
            onDismiss = { picking = 0 },
        ) { code ->
            var s = draft.translateSrc; var t = draft.translateTgt
            if (which == 1) { if (code == t) t = s; s = code } else { if (code == s) s = t; t = code }
            set(draft.copy(translateSrc = s, translateTgt = t))
        }
    }
}

@Composable
private fun LangRow(label: String, code: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), color = cs.onSurface, fontSize = 15.sp)
        Text(TranslateSession.nameOf(code), color = cs.onSurfaceVariant, fontSize = 15.sp)
    }
}

@Composable
private fun LangDialog(title: String, current: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val langs = remember { TranslateSession.languages }
    val state = rememberLazyListState(initialFirstVisibleItemIndex = langs.indexOfFirst { it.code == current }.coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), state = state) {
                items(langs, key = { it.code }) { l ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(l.code); onDismiss() }
                            .padding(vertical = 11.dp, horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            l.name, Modifier.weight(1f), color = cs.onSurface, fontSize = 15.sp,
                            fontWeight = if (l.code == current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                        if (l.code == current) Box(Modifier.size(8.dp).clip(CircleShape).background(cs.primary))
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Kapat") } },
    )
}
