package com.example.dynamicisland

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// =====================================================================================================
//  RENK DÜZENLEYİCİ
//  Uygulamadaki (ayar ekranı, ada, bildirim, arama, efektler...) sabit renklerin hepsi buradaki kayıttan gelir.
//  Düzenleyici kapalıyken her renk kendi varsayılan değerindedir; açıkken kullanıcının seçtikleri geçerli olur.
// =====================================================================================================

/** Düzenlenebilir tek bir renk. [def] ARGB (alfa dahil); [alpha] true ise saydamlığı da ayarlanabilir. */
class ColorSlot(val key: String, val label: String, val def: Long, val alpha: Boolean = false)

class ColorGroup(val title: String, val subtitle: String, val slots: List<ColorSlot>)

/** Renk kaydı + canlı geçersiz kılmalar. Okumalar Compose durumu üzerinden yapılır; değişince arayüz kendiliğinden yenilenir. */
object Pal {
    private var enabled by mutableStateOf(false)
    private var map by mutableStateOf<Map<String, Int>>(emptyMap())

    /** Palet her değiştiğinde artar; renk listesi / resim önbelleği tutan yerler buna bakıp yenilenir. */
    var version by mutableIntStateOf(0)
        private set
    private val defaults: Map<String, Long> by lazy { ColorGroups.all.flatMap { it.slots }.associate { it.key to it.def } }

    /** Ayarlar her değiştiğinde çağrılır (kayıtlı ayardan ya da taslaktan). */
    fun sync(on: Boolean, encoded: String) {
        val m = decode(encoded)
        var changed = false
        if (enabled != on) { enabled = on; changed = true }
        if (map != m) { map = m; changed = true }
        if (changed) version++
    }

    /** [key] renginin o anki değeri (düzenleyici kapalıysa ya da değiştirilmediyse varsayılan). */
    fun c(key: String): Color {
        val d = defaults[key] ?: return Color.Magenta   // yazım hatası hemen göze çarpsın
        val o = if (enabled) map[key] else null
        return Color(o ?: d.toInt())
    }

    fun decode(s: String): Map<String, Int> {
        if (s.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for (part in s.split(',')) {
            val i = part.indexOf(':')
            if (i <= 0) continue
            val v = part.substring(i + 1).trim().toLongOrNull(16) ?: continue
            out[part.substring(0, i).trim()] = v.toInt()
        }
        return out
    }

    fun encode(m: Map<String, Int>): String =
        m.entries.joinToString(",") { it.key + ":" + java.lang.Long.toHexString(it.value.toLong() and 0xFFFFFFFFL) }
}

private fun s(key: String, label: String, def: Long, alpha: Boolean = false) = ColorSlot(key, label, def, alpha)

object ColorGroups {
    val all: List<ColorGroup> = listOf(
        ColorGroup(
            "Uygulama arayüzü", "Ayar ekranı, kartlar, anahtarlar, kaydırıcılar",
            listOf(
                s("ui_background", "Zemin", 0xFF000000),
                s("ui_on_background", "Zemin üstü yazı / başlık", 0xFFFFFFFF),
                s("ui_surface", "Kart yüzeyi", 0xFF1C1C1E),
                s("ui_on_surface", "Kart yazısı", 0xFFFFFFFF),
                s("ui_surface_variant", "İkincil yüzey (seçili satır, buton)", 0xFF2C2C2E),
                s("ui_on_surface_variant", "Soluk yazı / açıklama", 0xFF8E8E93),
                s("ui_primary", "Vurgu (birincil)", 0xFFFFFFFF),
                s("ui_on_primary", "Vurgu üstü yazı", 0xFF000000),
                s("ui_primary_container", "Vurgu kabı", 0xFF3A3A3C),
                s("ui_on_primary_container", "Vurgu kabı yazısı", 0xFFFFFFFF),
                s("ui_secondary", "İkincil vurgu", 0xFFAEAEB2),
                s("ui_on_secondary", "İkincil vurgu üstü yazı", 0xFF000000),
                s("ui_secondary_container", "İkincil kap", 0xFF2C2C2E),
                s("ui_on_secondary_container", "İkincil kap yazısı", 0xFFFFFFFF),
                s("ui_outline", "Çerçeve çizgisi", 0xFF48484A),
                s("ui_outline_variant", "Ayırıcı çizgi", 0xFF38383A),
                s("ui_switch_on", "Anahtar: açık", 0xFF4CD964),
                s("ui_switch_off", "Anahtar: kapalı", 0xFFE4E1DE),
                s("ui_switch_thumb", "Anahtar düğmesi", 0xFFFFFFFF),
                s("ui_slider_track", "Kaydırıcı: boş kısım", 0x40FFFFFF, true),
                s("ui_slider_fill", "Kaydırıcı: dolu kısım", 0xFFFFFFFF),
                s("ui_tab_selected", "Sekme: seçili arka plan", 0xFF636366),
                s("ui_tab_selected_text", "Sekme: seçili yazı", 0xFFFFFFFF),
                s("ui_ok", "Durum: hazır / izin verildi", 0xFF4CD964),
                s("ui_success_text", "Başarı yazısı", 0xFF34C759),
                s("ui_warn", "Durum: eksik / uyarı", 0xFFFFB340),
                s("ui_warn_text", "Uyarı yazısı", 0xFFFFC107),
                s("ui_error", "Durum: hata", 0xFFFF6B5E),
            ),
        ),
        ColorGroup(
            "Ada gövdesi ve cam", "Adanın iç katmanları, cam parlamaları, yer tutucular",
            listOf(
                s("island_text", "Bildirim başlığı", 0xFFFFFFFF),
                s("island_text_dim", "Bildirim metni", 0xB3FFFFFF, true),
                s("island_letter", "Simge harfi (uygulama simgesi yokken)", 0xFFFFFFFF),
                s("island_dot_idle", "Duran müziğin küçük noktası", 0x66FFFFFF, true),
                s("art_placeholder_bg", "Kapak yer tutucu: zemin", 0x24FFFFFF, true),
                s("art_placeholder_note", "Kapak yer tutucu: nota", 0xB3FFFFFF, true),
                s("art_border", "Kapak / simge ince çerçevesi", 0x1AFFFFFF, true),
                s("island_scrim", "Özel arka plan karartması", 0xFF000000),
                s("island_flip_shade", "Kapak dönerken gölge", 0xFF000000),
                s("island_assistant_body", "Asistan açıkken cam gövde", 0xFF000000),
                s("glass_sheen", "Cam: parlama / yansıma", 0xFFFFFFFF),
                s("glass_shade", "Cam: alt gölge", 0xFF000000),
            ),
        ),
        ColorGroup(
            "Müzik paneli", "Şarkı adı, sanatçı, ilerleme çubuğu ve düğmeler",
            listOf(
                s("music_title", "Şarkı adı", 0xFFFFFFFF),
                s("music_artist", "Sanatçı", 0x9EFFFFFF, true),
                s("music_time", "Süre yazıları", 0x8CFFFFFF, true),
                s("music_prog_track", "İlerleme çubuğu: boş kısım", 0x38FFFFFF, true),
                s("music_prog_fill", "İlerleme çubuğu: dolu kısım", 0xFFFFFFFF),
                s("music_neon_core", "Neon çubuk çekirdeği", 0xE0FFFFFF, true),
                s("music_ctl", "Oynat / ileri / geri simgeleri", 0xFFFFFFFF),
                s("music_ctl_dim", "Kalp (beğenilmemiş) / yayın simgesi", 0xBFFFFFFF, true),
                s("music_play_bg", "Büyük oynat düğmesi: daire", 0xFFFFFFFF),
                s("music_play_ink", "Büyük oynat düğmesi: simge", 0xFF0B0B0F),
            ),
        ),
        ColorGroup(
            "Yan baloncuklar", "Adanın yanında / noktada görünen küçük olay simgeleri",
            listOf(
                s("side_note", "Müzik notası", 0xCCFFFFFF, true),
                s("side_text", "Geri sayım / harf", 0xFFFFFFFF),
            ),
        ),
        ColorGroup(
            "Bildirim banner'ı (cam)", "iOS tarzı cam bildirim bannerı",
            listOf(
                s("banner_bg", "Banner zemini", 0xA8F2F2F7, true),
                s("banner_sheen", "Cam parlaması", 0xFFFFFFFF),
                s("banner_title", "Başlık", 0xFF111114),
                s("banner_time", "Zaman yazısı", 0xFF6B6B70),
                s("banner_body", "Mesaj metni", 0xFF1C1C1E),
                s("banner_icon_bg", "Simge zemini", 0xD9FFFFFF, true),
                s("banner_icon_letter", "Simge harfi", 0xFF8E8E93),
                s("banner_icon_border", "Küçük simge çerçevesi", 0xFFFFFFFF),
            ),
        ),
        ColorGroup(
            "Arama", "Gelen / devam eden arama adası ve kenar tuşları",
            listOf(
                s("call_answer", "Cevapla (yeşil)", 0xFF34C759),
                s("call_decline", "Reddet / kapat (kırmızı)", 0xFFFF3B30),
                s("call_text", "Kişi adı / ana yazı", 0xFFFFFFFF),
                s("call_text_dim", "\"Gelen arama\" yazısı", 0x99FFFFFF, true),
                s("call_icon", "Ahize simgesi", 0xFFFFFFFF),
                s("call_speaker_off", "Hoparlör düğmesi: kapalı", 0x29FFFFFF, true),
                s("call_btn_active_bg", "Hoparlör düğmesi: açık daire", 0xFFFFFFFF),
                s("call_btn_active_icon", "Hoparlör düğmesi: açık simge", 0xFF000000),
                s("call_avatar_bg", "Avatar zemini", 0xFF3A3A3C),
                s("call_avatar_a", "Gelen arama avatarı: gradyan başı", 0xFFFF6B9D),
                s("call_avatar_b", "Gelen arama avatarı: gradyan sonu", 0xFFFF9A5A),
                s("call_wave_a", "Ses dalgası: sol uç", 0xFF4CE06A),
                s("call_wave_mid", "Ses dalgası: orta", 0xFFFFFFFF),
                s("call_wave_b", "Ses dalgası: sağ uç", 0xFFFF9248),
                s("side_pill_bg", "Kenar tuşları zemini", 0xFF000000),
            ),
        ),
        ColorGroup(
            "Durum olayları", "Şarj, zil, zamanlayıcı, alarm, kamera / mikrofon",
            listOf(
                s("status_charging", "Şarj", 0xFF34C759),
                s("status_ringer_normal", "Zil: sesli", 0xFFFFFFFF),
                s("status_ringer_vibrate", "Zil: titreşim", 0xFFFF9500),
                s("status_ringer_silent", "Zil: sessiz", 0xFFFF3B30),
                s("status_timer", "Zamanlayıcı", 0xFF0A84FF),
                s("status_alarm", "Alarm", 0xFFFF9500),
                s("status_camera", "Kamera noktası", 0xFF34C759),
                s("status_mic", "Mikrofon noktası", 0xFFFF9F0A),
                s("status_icon", "Simge çizgileri", 0xFFFFFFFF),
                s("status_text", "Ana yazı", 0xFFFFFFFF),
                s("status_text_dim", "Alt yazı", 0xB3FFFFFF, true),
                s("status_track", "İlerleme çubuğu zemini", 0x29FFFFFF, true),
                s("status_batt_body", "Pil gövdesi", 0x47FFFFFF, true),
                s("status_batt_tip", "Pil ucu", 0x66FFFFFF, true),
                s("status_wifi", "Wi-Fi / bağlandı", 0xFF0A84FF),
                s("status_cell", "Mobil veri", 0xFF34C759),
                s("status_offline", "İnternet kesildi", 0xFFFF453A),
                s("status_airplane", "Uçak modu", 0xFFFF9F0A),
                s("status_low_batt", "Düşük pil", 0xFFFF3B30),
                s("status_power_save", "Düşük güç modu", 0xFFFFCC00),
            ),
        ),
        ColorGroup(
            "Ekran kaydı", "Kayıt adası ve geri sayım",
            listOf(
                s("rec_red", "Kayıt noktası (kırmızı)", 0xFFFF3B30),
                s("rec_ring", "Halka / çerçeve", 0xFFFFFFFF),
                s("rec_text", "Yazılar", 0xFFFFFFFF),
                s("rec_play_bg", "Önizleme kutusu", 0x24FFFFFF, true),
                s("rec_play_icon", "Önizleme oynat simgesi", 0xBFFFFFFF, true),
            ),
        ),
        ColorGroup(
            "Shazam", "Müzik tanıma adası",
            listOf(
                s("shazam_blue_top", "Düğme gradyanı: üst", 0xFF33AEFF),
                s("shazam_blue_bottom", "Düğme gradyanı: alt", 0xFF0A68FF),
                s("shazam_blue", "Ana mavi", 0xFF0088FF),
                s("shazam_cyan_a", "Cyan vurgu 1", 0xFF3CE6FF),
                s("shazam_cyan_b", "Cyan vurgu 2", 0xFF22E0FF),
                s("shazam_spark", "Kıvılcımlar", 0xFF9FE9FF),
                s("shazam_bar_flat", "Ses çubukları (sakin)", 0xFF1E7BFF),
                s("shazam_bar_end", "Ses çubukları (uç renk)", 0xFF6BBBFF),
                s("shazam_glow", "Parıltı / ışınlar", 0xFFFFFFFF),
                s("shazam_glyph", "Shazam simgesi", 0xFFFFFFFF),
                s("shazam_title", "Şarkı adı", 0xFFFFFFFF),
                s("shazam_artist", "Sanatçı", 0xA8FFFFFF, true),
                s("shazam_listen", "\"Dinliyor\" yazısı", 0xFFFFFFFF),
                s("shazam_close_bg", "Kapat düğmesi zemini", 0xFF14224A),
                s("shazam_close_x", "Kapat düğmesi çarpısı", 0xFF3D7BFF),
            ),
        ),
        ColorGroup(
            "Çeviri paneli", "Boştayken açılan çeviri paneli",
            listOf(
                s("tr_text", "Yazı", 0xFFFFFFFF),
                s("tr_dim", "Soluk yazı", 0x8CFFFFFF, true),
                s("tr_hint", "İpucu yazısı", 0x66FFFFFF, true),
                s("tr_field", "Dil satırı alanı", 0x1AFFFFFF, true),
                s("tr_box", "Metin / sonuç kutusu", 0x0FFFFFFF, true),
                s("tr_chip", "Düğme zemini (seçili / seçili değil)", 0xFFFFFFFF),
                s("tr_chip_soft", "Küçük düğme zemini", 0x24FFFFFF, true),
                s("tr_error", "Hata yazısı", 0xFFFF8A80),
                s("tr_selected", "Seçili dil noktası", 0xFF0A84FF),
            ),
        ),
        ColorGroup(
            "Favori uygulamalar", "Boştayken açılan uygulama paneli",
            listOf(
                s("fav_icon_bg", "Simge yer tutucusu", 0x2EFFFFFF, true),
                s("fav_label", "Uygulama adı", 0xD9FFFFFF, true),
            ),
        ),
        ColorGroup(
            "Hava ve olay efektleri", "Yağmur, kar, asit, donma, şimşek, kırılma",
            listOf(
                s("fx_white", "Kar / çerçeve / parlama (beyazlar)", 0xFFFFFFFF),
                s("fx_rain", "Yağmur damlası", 0xFFA9D8FF),
                s("fx_acid", "Asit yağmuru", 0xFFB5F23D),
                s("fx_acid_blob", "Asit damlası", 0xFF9BE81B),
                s("fx_acid_dark", "Asit erimesi: koyu", 0xFF8BE000),
                s("fx_acid_light", "Asit erimesi: açık", 0xFFD7FF6A),
                s("fx_ice_a", "Buz: açık mavi", 0xFFCDEEFF),
                s("fx_ice_b", "Buz: yüzey tonu", 0xFFCFEFFF),
                s("fx_ice_c", "Buz: sivri uçlar", 0xFFD6F1FF),
                s("fx_ice_d", "Buz: kenar gradyanı", 0xFFD3E3F3),
                s("fx_crack", "Buz çatlağı", 0xFF5F97B8),
                s("fx_bolt", "Şimşek halesi", 0xFFBFD8FF),
                s("fx_shade", "Kırılma çatlağı (koyu)", 0xFF000000),
            ),
        ),
        ColorGroup(
            "Asistan ve kenar parıltısı", "Google Asistan / Gemini animasyonu ve ekran kenarı ışığı",
            listOf(
                s("asst_1", "Renk 1", 0xFF0A84FF),
                s("asst_2", "Renk 2", 0xFF7B4DFF),
                s("asst_3", "Renk 3", 0xFFFF2D87),
                s("asst_4", "Renk 4", 0xFFFF3B30),
                s("asst_5", "Renk 5", 0xFFFF9500),
                s("asst_6", "Renk 6", 0xFFFFD60A),
                s("asst_7", "Renk 7 (yalnızca asistan; beyaz)", 0xFFFFFFFF),
                s("asst_8", "Renk 8", 0xFF64D2FF),
                s("orb_rim", "Alt kenar cam yansıması", 0xFFAEB4BE),
                s("orb_sweep", "Işık süpürmesi: beyaz", 0xFFFFFFFF),
                s("orb_sweep2_mid", "Işık süpürmesi 2: orta", 0xFFC9CDD6),
                s("orb_sweep2_edge", "Işık süpürmesi 2: uç", 0xFFDADDE4),
            ),
        ),
        ColorGroup(
            "Gökkuşağı", "Gökkuşağı modundaki 7 renk",
            listOf(
                s("rb_1", "Renk 1", 0xFFFF3B30),
                s("rb_2", "Renk 2", 0xFFFF9500),
                s("rb_3", "Renk 3", 0xFFFFCC00),
                s("rb_4", "Renk 4", 0xFF34C759),
                s("rb_5", "Renk 5", 0xFF00C7BE),
                s("rb_6", "Renk 6", 0xFF0A84FF),
                s("rb_7", "Renk 7", 0xFFBF5AF2),
            ),
        ),
        ColorGroup(
            "Şarkı sözleri ve kilit noktası", "Sözlerin gölgesi, durum yazısı, nokta parlaması",
            listOf(
                s("lyrics_shadow", "Dışarıdaki sözlerin gölgesi", 0xFF000000),
                s("lyrics_status", "Durum yazısı (\"Söz bulunamadı\" vb.)", 0x80FFFFFF, true),
                s("lock_dot_shine", "Kilit noktası: parlak leke", 0xFFFFFFFF),
            ),
        ),
        ColorGroup(
            "Ayar önizlemesi", "Ayar ekranının üstündeki telefon önizlemesi",
            listOf(
                s("preview_card_a", "Önizleme kartı: gradyan 1", 0xFF1F4E6B),
                s("preview_card_b", "Önizleme kartı: gradyan 2", 0xFF3A3A78),
                s("preview_card_c", "Önizleme kartı: gradyan 3", 0xFF5A3470),
                s("preview_phone", "Telefon gövdesi", 0xFF08090C),
                s("preview_phone_border", "Telefon çerçevesi", 0xFF454956),
                s("preview_btn", "Telefon yan tuşları", 0xFF2A2D35),
                s("preview_wall_a", "Duvar kâğıdı: üst", 0xFF2E6A93),
                s("preview_wall_b", "Duvar kâğıdı: orta", 0xFF5B4496),
                s("preview_wall_c", "Duvar kâğıdı: alt", 0xFFB2557F),
                s("preview_lockbg", "Kilit noktası önizleme zemini", 0xFF15151A),
            ),
        ),
    )
}

// ---------------------------------------------------------------- Ayar alanına bağlı renkler
private class FieldColor(
    val label: String, val hint: String?, val default: Int,
    val read: (IslandSettings) -> Int, val write: (IslandSettings, Int) -> IslandSettings,
)

private val FIELD_COLORS: List<FieldColor> = listOf(
    FieldColor("Ada gövdesi", "Albüm kapağı rengi açıkken yalnızca kapak yokken kullanılır", 0xFF000000.toInt(),
        { it.islandColorArgb }, { st, v -> st.copy(islandColorArgb = v) }),
    FieldColor("Vurgu rengi", "Uygulama simgesi yokken harf zemini vb.", 0xFF0A84FF.toInt(),
        { it.accentArgb }, { st, v -> st.copy(accentArgb = v) }),
    FieldColor("Ekolayzır", "Albüm kapağı rengi açıkken yalnızca kapak yokken kullanılır", 0xFF0A84FF.toInt(),
        { it.eqColorArgb }, { st, v -> st.copy(eqColorArgb = v) }),
    FieldColor("İlerleme çubuğu parıltısı", "Parıltı açıkken geçerli", 0xFF0A84FF.toInt(),
        { it.progressGlowColorArgb }, { st, v -> st.copy(progressGlowColorArgb = v) }),
    FieldColor("Beğeni (kalp)", "Kalp rengi", 0xFFFF2D6F.toInt(),
        { it.likeColorArgb }, { st, v -> st.copy(likeColorArgb = v) }),
    FieldColor("Çerçeve", "Çerçeve açıkken geçerli", 0xFFFFFFFF.toInt(),
        { it.borderColorArgb }, { st, v -> st.copy(borderColorArgb = v) }),
    FieldColor("Kilit ekranı noktası", null, 0xFF9B4DFF.toInt(),
        { it.lockDotColorArgb }, { st, v -> st.copy(lockDotColorArgb = v) }),
    FieldColor("Şarkı sözleri", "Albüm kapağı rengi açıkken yalnızca kapak yokken kullanılır", 0xFFFFFFFF.toInt(),
        { it.lyricsColorArgb }, { st, v -> st.copy(lyricsColorArgb = v) }),
)

// ---------------------------------------------------------------- Arayüz
/** Ana sayfa: düzenleyiciyi aç / kapat, değişiklik sayısı, toplu sıfırlama. */
@Composable
fun ColorEditorMain(draft: IslandSettings, onDraft: (IslandSettings) -> Unit) {
    val cs = MaterialTheme.colorScheme
    SwitchRow(
        "Renk düzenleyici",
        "Açınca uygulamadaki her şeyin rengini tek tek ayarlayabilirsin. Kapatınca seçtiklerin silinmez, yalnızca varsayılan renkler kullanılır",
        draft.colorEditorEnabled,
    ) { onDraft(draft.copy(colorEditorEnabled = it)) }
    if (draft.colorEditorEnabled) {
        val changed = Pal.decode(draft.colorOverrides).size
        SectionDivider()
        Text(
            "Aşağıdaki sayfalarda renkler gruplara ayrılmıştır (arayüz, ada, müzik, bildirim, arama, durum, efektler...). Bir renge dokununca renk, doygunluk, parlaklık, saydamlık ve hex ayarı açılır. Değişiklikler canlı uygulanır.",
            color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
        )
        Text(
            if (changed == 0) "Henüz değiştirilmiş renk yok" else "$changed renk değiştirildi",
            color = cs.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
        IslandActionButton("Tüm renk değişikliklerini sıfırla", {
            var st = draft.copy(colorOverrides = "")
            FIELD_COLORS.forEach { f -> st = f.write(st, f.default) }
            onDraft(st)
        })
    }
}

/** "Ada ve vurgu renkleri": zaten ayar olarak var olan renkler, düzenleyiciden de ayarlanır. */
@Composable
fun ColorFieldEditor(draft: IslandSettings, onDraft: (IslandSettings) -> Unit) {
    FIELD_COLORS.forEachIndexed { i, f ->
        if (i > 0) SectionDivider()
        ColorEditRow(
            label = f.label, hint = f.hint, argb = f.read(draft), defaultArgb = f.default, hasAlpha = false,
            onChange = { onDraft(f.write(draft, it)) },
            onReset = { onDraft(f.write(draft, f.default)) },
        )
    }
}

/** Bir gruptaki tüm renk satırları. */
@Composable
fun ColorGroupEditor(group: ColorGroup, draft: IslandSettings, onDraft: (IslandSettings) -> Unit) {
    val map = Pal.decode(draft.colorOverrides)
    group.slots.forEachIndexed { i, slot ->
        if (i > 0) SectionDivider()
        val def = slot.def.toInt()
        ColorEditRow(
            label = slot.label, hint = null, argb = map[slot.key] ?: def, defaultArgb = def, hasAlpha = slot.alpha,
            onChange = { v -> onDraft(draft.copy(colorOverrides = Pal.encode(map + (slot.key to v)))) },
            onReset = { onDraft(draft.copy(colorOverrides = Pal.encode(map - slot.key))) },
        )
    }
}

private fun withAlpha(argb: Int, a: Float): Int {
    val ai = (a.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (argb and 0x00FFFFFF) or (ai shl 24)
}

private fun hexOf(argb: Int, alpha: Boolean): String {
    val rgb = String.format("%06X", argb and 0x00FFFFFF)
    return if (alpha) String.format("%02X", (argb ushr 24) and 0xFF) + rgb else rgb
}

/** "RRGGBB" ya da "AARRGGBB" (başında # olabilir) → ARGB; geçersizse null. */
private fun parseHex(text: String, keepAlpha: Int): Int? {
    val t = text.trim().removePrefix("#")
    if (t.length != 6 && t.length != 8) return null
    val v = t.toLongOrNull(16) ?: return null
    return if (t.length == 8) v.toInt() else (v.toInt() and 0x00FFFFFF) or (keepAlpha and 0xFF000000.toInt())
}

@Composable
private fun ColorEditRow(
    label: String, hint: String?, argb: Int, defaultArgb: Int, hasAlpha: Boolean,
    onChange: (Int) -> Unit, onReset: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }       // sıfırlama / hex girişinde seçiciyi yeniden kurmak için
    val changed = argb != defaultArgb
    val swatchShape = RoundedCornerShape(8.dp)

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Saydam renkler okunsun diye iki tonlu zemin üstüne çizilir
            Box(Modifier.size(30.dp).clip(swatchShape).background(Color(0xFF8E8E93))) {
                Box(Modifier.size(30.dp).background(Color(argb)).border(1.dp, cs.outline, swatchShape))
            }
            Column(Modifier.weight(1f)) {
                Text(label, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(
                    "#" + hexOf(argb, hasAlpha || (argb ushr 24) != 0xFF) + if (changed) "  •  değiştirildi" else "",
                    color = cs.onSurfaceVariant, fontSize = 12.sp,
                )
            }
            Text(if (open) "Kapat" else "Düzenle", color = cs.onSurfaceVariant, fontSize = 13.sp)
        }
        if (open) {
            if (hint != null) Text(hint, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
            androidx.compose.runtime.key(tick) {
                ColorPicker(argb) { rgb -> onChange(withAlpha(rgb, ((argb ushr 24) and 0xFF) / 255f)) }
            }
            if (hasAlpha) {
                val a = ((argb ushr 24) and 0xFF) / 255f
                SliderRow("Saydamlık", a, 0f..1f, "%${(a * 100).toInt()}") { onChange(withAlpha(argb, it)) }
            }
            HexField(hasAlpha, tick) { text ->
                val v = parseHex(text, argb)
                if (v != null) { onChange(v); tick++ }
                v != null
            }
            ChipButton("Varsayılana dön", Modifier.fillMaxWidth(), enabled = changed) { onReset(); tick++ }
        }
    }
}

@Composable
private fun ChipButton(text: String, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier.height(38.dp).clip(shape).background(cs.surfaceVariant)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = if (enabled) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.5f), fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/** Hex girişi: geçerli bir değer yazılınca uygulanır ve alan temizlenir. */
@Composable
private fun HexField(alpha: Boolean, tick: Int, onApply: (String) -> Boolean) {
    val cs = MaterialTheme.colorScheme
    var text by remember(tick) { mutableStateOf("") }
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier.fillMaxWidth().height(40.dp).clip(shape).background(cs.surfaceVariant).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("#", color = cs.onSurfaceVariant, fontSize = 15.sp)
        Spacer(Modifier.width(4.dp))
        Box(Modifier.weight(1f)) {
            if (text.isEmpty()) {
                Text(if (alpha) "Hex yaz (AARRGGBB ya da RRGGBB)" else "Hex yaz (RRGGBB)", color = cs.onSurfaceVariant.copy(alpha = 0.6f), fontSize = 14.sp, maxLines = 1)
            }
            BasicTextField(
                value = text,
                onValueChange = { v ->
                    val clean = v.filter { it.isLetterOrDigit() || it == '#' }.take(9)
                    text = clean
                    val digits = clean.removePrefix("#")
                    if (digits.length == 6 || digits.length == 8) onApply(clean)
                },
                singleLine = true,
                textStyle = TextStyle(color = cs.onSurface, fontSize = 15.sp),
                cursorBrush = SolidColor(cs.onSurface),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
