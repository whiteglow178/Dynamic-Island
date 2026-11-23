package com.example.dynamicisland

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Favori paneldeki bir uygulama (simge önceden yüklenir; panelde disk/PackageManager erişimi olmaz). */
@Immutable
data class FavApp(val pkg: String, val label: String, val icon: ImageBitmap?)

enum class StatusKind {
    Ringer, Charging, Timer, Alarm, Camera, Mic, Volume,
    Network,          // internet bağlandı / kesildi (geniş iOS hapı)
    Airplane,         // uçak modu açıldı / kapandı (geniş iOS hapı)
    RingVolume,       // zil sesi seviyesi (geniş iOS hapı: zil simgesi + "Zil" + ince seviye çubuğu)
    LowBattery,       // düşük pil: ince geniş hap (ada genişlemez)
    LowBatteryCard,   // düşük pil: büyük kart (Düşük Güç Modu düğmesi)
    Focus,            // Rahatsız Etmeyin açıldı / kapandı (geniş iOS hapı)
    Hotspot,          // kişisel erişim noktası açıldı / kapandı (geniş iOS hapı)
    Headphones,       // kulaklık bağlandı: kart (ad + simge + varsa pil)
    VoiceRec,         // ses kaydı sürüyor (ses kaydedici uygulamalar): kapalı adada dalga + süre
}

/** Açık ada genişliğinde kart olarak açılan durumlar: düşük pil kartı ve kulaklık kartı. */
fun IslandContent.Status.isCard(): Boolean = kind == StatusKind.LowBatteryCard || kind == StatusKind.Headphones

/** Canlı etkinlik türü: navigasyon ya da sipariş / kargo takibi. */
enum class LiveKind { Nav, Delivery, Progress, Score, Code }

/** Geniş, tek satırlık iOS hapları: ağ, uçak modu, zil sesi seviyesi ve ince düşük pil hapı. */
fun IslandContent.Status.isSysPill(): Boolean =
    kind == StatusKind.Network || kind == StatusKind.Airplane || kind == StatusKind.RingVolume || kind == StatusKind.LowBattery ||
        kind == StatusKind.Focus || kind == StatusKind.Hotspot

/** Dar "hap" şeklinde açılan durumlar: şarj ve zil modu (Zil / Sessiz / Titreşim). */
fun IslandContent.Status.isPill(): Boolean = kind == StatusKind.Charging || kind == StatusKind.Ringer || kind == StatusKind.Volume

/** Sistem ekran kaydı aşamaları. */
enum class RecPhase { Countdown, Recording, Processing, Saved }

/** SoundCapsule: Listening = dinliyor (kapalı ada, logo + ses dalgası), Found = şarkı bulundu (ada açılır, kapak + isim). */
enum class SoundCapsulePhase { Listening, Found }

@Immutable
sealed interface IslandContent {
    data object Idle : IslandContent

    /** Google Asistan / Gemini açıkken: ada Siri benzeri renkli animasyona dönüşür. */
    data object Assistant : IslandContent

    /** Boştayken adaya dokununca/basılı tutunca açılan favori uygulamalar paneli. */
    data class Favorites(val apps: List<FavApp>) : IslandContent

    /** Ada çekmecesi: hızlı düğmeler paneli (fener, ekran görüntüsü, kilit...). */
    data object Drawer : IslandContent

    /** Zaman kapsülü: geçmişte bırakılan not / fotoğraf, zamanı gelince adada açılır. */
    data class Capsule(val id: Long, val text: String, val photo: String, val createdAt: Long) : IslandContent

    /**
     * Canlı etkinlik (iOS Live Activity benzeri): navigasyonda dönüş oku + mesafe, siparişte uygulama + kalan süre.
     * Bildirim kalkana (ya da teslim edilene) kadar adada kalır. [big] kapalı adanın sağındaki kısa yazı.
     */
    data class Live(
        val kind: LiveKind,
        val pkg: String,
        val key: String,
        val big: String,                                  // "250 m" / "12 dk" / "Yolda"
        val title: String,                                // navigasyon: talimat; sipariş: başlık
        val text: String,                                 // ikinci satır
        val sub: String = "",                             // navigasyon: varış / kalan süre; sipariş: ek bilgi
        val icon: ImageBitmap? = null,                    // dönüş oku (bildirimin büyük simgesi)
        val appIcon: ImageBitmap? = null,                 // uygulama simgesi (servis ekler)
        val stage: Int = -1,                              // sipariş aşaması: 0 alındı, 1 hazırlanıyor, 2 yolda, 3 teslim edildi
        val progress: Float = -1f,                        // bildirimdeki ilerleme 0..1 (-1 = yok)
        val intent: android.app.PendingIntent? = null,
        // ---- Maç skoru (LiveKind.Score)
        val teamA: String = "",
        val teamB: String = "",
        val scoreA: Int = -1,
        val scoreB: Int = -1,
        val minute: String = "",                          // "67'", "45+2'"
        val phase: Int = 0,                               // 0 oynanıyor, 1 devre arası, 2 maç sonu
        val goal: Boolean = false,                        // az önce gol oldu (servis işaretler)
    ) : IslandContent

    /** Özel ada tasarımı (fotoğraf): boştaki adaya dokununca ada genişleyip fotoğrafı tamamen gösterir. */
    data object Photo : IslandContent

    /** Boştayken adaya dokununca açılan çeviri paneli (üstte dil seçimi, altta yazı alanı + sonuç). Durum [TranslateSession]'dadır. */
    data object Translate : IslandContent

    data class Notif(
        val pkg: String, val title: String, val text: String,
        val icon: ImageBitmap? = null,
        val intent: android.app.PendingIntent? = null,   // bildirimin kendi hedefi (ilgili sohbet / ekran)
        val key: String = "",                            // sistem bildirim anahtarı (adadan silmek için)
        val avatar: ImageBitmap? = null,                 // gönderenin fotoğrafı (bildirimin büyük simgesi); banner görünümünde kullanılır
        val messaging: Boolean = false,                  // sohbet bildirimi (MessagingStyle / mesaj kategorisi): bannerda kişi fotoğrafı + köşede uygulama simgesi
        val accent: Int = 0,                             // uygulama simgesinin baskın rengi (ARGB); 0 = renkli ışık yok
        val translatedFrom: String = "",                 // bildirim çevirisi: kaynak dil kodu ("en"); boş = çevrilmedi
        val chips: List<NotifChip> = emptyList(),        // akıllı bildirim: ertele / kopyala / ara / harita / bağlantı (NotifSmart.kt)
    ) : IslandContent

    /** Telefon araması: gelen (Cevapla / Reddet) ya da aktif (süre + Kapat). */
    data class Call(
        val name: String,
        val isIncoming: Boolean,
        val duration: String? = null,     // aktif aramada "0:01"
        val avatar: ImageBitmap? = null,
        val speaker: Boolean = false,     // hoparlör açık mı (aktif aramada)
    ) : IslandContent

    /** Kısa durum bildirimi: zil modu, şarj, zamanlayıcı, alarm. */
    data class Status(
        val kind: StatusKind,
        val title: String,
        val subtitle: String = "",
        val value: String = "",          // sağ taraftaki büyük yazı (\"%78\", \"07:30\" ...)
        val progress: Float = 0f,        // şarj seviyesi (0..1)
        val endsAt: Long = 0L,           // zamanlayıcı: bitiş zamanı (System.currentTimeMillis)
        val totalMs: Long = 0L,          // zamanlayıcı: toplam süre
        val silent: Boolean = false,     // zil modu: sessiz mi
        val vibrate: Boolean = false,    // zil modu: titreşim mi (silent=false, vibrate=true -> titreşim)
        val extra: Int = 0,              // ağ: 0 Wi-Fi, 1 mobil, 2 diğer, 3 bağlantı yok; uçak: 1 açık; düşük pil: 1 düşük güç modu açık
        /**
         * Görünüm aşaması (yeni tasarımlar, bkz. IslandExtrasUi.kt):
         *  zamanlayıcı: 0 çalışıyor, 1 duraklatıldı, 2 süre doldu, 3 kurma paneli; alarm: 2 çalıyor;
         *  Rahatsız Etmeyin: 1 genişlemiş kart; ses kaydı: 1 duraklatıldı, 2 kaydedildi.
         */
        val phase: Int = 0,
        val remainMs: Long = 0L,         // duraklatılmış zamanlayıcıda kalan süre
        val startedAt: Long = 0L,        // ses kaydı: başlama zamanı (System.currentTimeMillis)
    ) : IslandContent

    /** Sistem ekran kaydı: geri sayım / kayıt / işleniyor / kaydedildi. Süre UI'da startedAt'ten hesaplanır. */
    data class ScreenRec(
        val phase: RecPhase,
        val startedAt: Long = 0L,
        val frozenMs: Long = 0L,          // kayıt bitince dondurulan süre
        val count: Int = 0,               // geri sayım rakamı
        val thumb: ImageBitmap? = null,   // kaydedildi bildirimindeki küçük resim
    ) : IslandContent

    /** SoundCapsule: dinleme animasyonu ve bulunan şarkı (albüm kapağı, şarkı adı, sanatçı). Bildirimden beslenir. */
    data class SoundCapsule(
        val phase: SoundCapsulePhase,
        val title: String = "",
        val artist: String = "",
        val cover: ImageBitmap? = null,
    ) : IslandContent

    data class Media(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val durationMs: Long,
        val positionMs: Long,     // son bilinen konum
        val speed: Float,
        val updatedAt: Long,      // positionMs'in alındığı elapsedRealtime
        val art: ImageBitmap? = null, // albüm kapağı (küçültülmüş, önbellekli)
        val artColor: Int = 0,        // kapaktan çıkarılan baskın renk (0 = yok)
    ) : IslandContent {
        /** Sistem saatinden anlık konum tahmini: polling yerine hesaplama. */
        fun positionAt(now: Long): Long {
            val p = if (playing) positionMs + ((now - updatedAt) * speed).toLong() else positionMs
            return if (durationMs > 0) p.coerceIn(0L, durationMs) else p.coerceAtLeast(0L)
        }
    }
}

@Immutable
data class IslandUiState(
    val content: IslandContent = IslandContent.Idle,
    val expanded: Boolean = false,
    /** Kapalı adada şarkı sözü gösteriliyor mu (ada bu durumda daha geniş olur). Servis hesaplar. */
    val compactLyrics: Boolean = false,
    /** Müzikten çıkış "yutma" efekti: ada kısa süre büyüyüp (içeriksiz) eski haline döner. */
    val swallow: Boolean = false,
    /**
     * Ortadaki adanın yanında ayrı baloncuk olarak duran arka plan olayları (ada genişlemişken de panelin yanında kalır;
     * dar ekranda sığmıyorsa genişlemişken gösterilmez).
     * [0] = sağ baloncuk (sondan ikinci olay), [1] = sol baloncuk (sondan üçüncü olay). Servis hesaplar.
     * Her öğe Media / ScreenRec / Call içeriğidir; arayüz bunları küçük simge olarak çizer.
     */
    val side: List<IslandContent> = emptyList(),
    /** Yan baloncuğa dokunulunca ada o baloncuktan büyüyerek açılır: +1 = sağdaki, -1 = soldaki, 0 = normal. Kapanınca sıfırlanır. */
    val fromSide: Int = 0,
    /** Yığın görünümü: birden fazla olay alt alta kartlar halinde açık. Boşsa kapalı. Servis hesaplar. */
    val stack: List<IslandContent> = emptyList(),
    /** Yığın kapanış animasyonu oynuyor. */
    val stackClosing: Boolean = false,
)

// ---------------------------------------------------------------- Şarkı sözü modeli
@Immutable
data class LyricLine(val timeMs: Long, val text: String)

@Immutable
sealed interface LyricsState {
    data object Idle : LyricsState
    data object Loading : LyricsState
    data object NotFound : LyricsState
    /** synced=false ise sözler şarkı süresine eşit aralıklarla yayılmıştır. */
    data class Ready(val lines: List<LyricLine>, val synced: Boolean, val source: String = "") : LyricsState
}

sealed interface IslandEvent {
    data class Notification(
        val pkg: String, val title: String, val text: String,
        val intent: android.app.PendingIntent? = null,
        val key: String = "",
        val avatar: ImageBitmap? = null,
        val messaging: Boolean = false,
    ) : IslandEvent
    /** Arama bildirimi (gelen ya da aktif). Cevapla / Reddet-Kapat düğmeleri bildirimin kendi PendingIntent'leridir. */
    data class Call(
        val key: String, val pkg: String, val name: String, val incoming: Boolean,
        val startedAt: Long, val avatar: ImageBitmap?,
        val answer: android.app.PendingIntent?, val decline: android.app.PendingIntent?,
    ) : IslandEvent
    data class CallEnded(val key: String) : IslandEvent
    /** Zil modu / şarj / zamanlayıcı / alarm gibi kısa durum gösterimi. */
    data class Status(val content: IslandContent.Status, val holdMs: Long = 4_000L) : IslandEvent
    /** Sistemin ekran kaydı bildirimi (izin gerektirmez; bildirim erişiminden gelir). */
    data class ScreenRec(
        val phase: RecPhase, val key: String, val startedAt: Long, val count: Int,
        val stop: android.app.PendingIntent?, val open: android.app.PendingIntent?,
        val thumb: ImageBitmap?,
    ) : IslandEvent
    data class ScreenRecGone(val key: String, val phase: RecPhase) : IslandEvent
    data class MediaChanged(val media: IslandContent.Media?) : IslandEvent
    data class AssistantChanged(val active: Boolean) : IslandEvent
    /** Ön plandaki uygulama değişti ([from] -> [to]); müzik uygulamasından çıkış animasyonu için. */
    data class AppSwitched(val from: String, val to: String) : IslandEvent
    /** SoundCapsule dinlemeye başladı (SoundCapsule'ın "dinliyor" bildirimi geldi). */
    data class SoundCapsuleListening(val key: String) : IslandEvent
    /** SoundCapsule şarkıyı buldu (sonuç bildirimi): başlık, sanatçı ve kapak bildirimden gelir. */
    data class SoundCapsuleFound(
        val title: String, val artist: String, val cover: ImageBitmap?,
        val open: android.app.PendingIntent?, val key: String,
    ) : IslandEvent
    /** Kullanıcı bir bildirimi sildi (kaydırarak ya da "Tümünü temizle"): çöp kutusu ada animasyonu için. */
    data class NotifTrashed(val pkg: String, val text: String = "") : IslandEvent
    /** SoundCapsule'ın dinleme bildirimi kalktı ya da "eşleşme yok" bildirimi geldi. */
    data object SoundCapsuleGone : IslandEvent
    /** Navigasyon / sipariş bildirimi geldi ya da güncellendi. */
    data class LiveUpdate(val live: IslandContent.Live) : IslandEvent
    /** Canlı etkinliğin bildirimi kalktı. */
    data class LiveGone(val key: String) : IslandEvent
    /** Ana Sayfa > Dene: favori uygulamalar panelini aç (ayarlarda favoriler açık ve en az bir uygulama seçili olmalı). */
    data object ShowFavorites : IslandEvent
    /** Saat uygulamasının zamanlayıcı bildirimi (geri sayıyor ya da duraklatılmış). */
    data class ClockTimer(
        val key: String, val endsAt: Long, val remainMs: Long, val paused: Boolean, val label: String,
        val pause: android.app.PendingIntent?, val resume: android.app.PendingIntent?, val cancel: android.app.PendingIntent?,
    ) : IslandEvent
    /** Saat uygulamasında alarm çalıyor ya da zamanlayıcının süresi doldu ([timerDone]). */
    data class ClockAlarm(
        val key: String, val label: String, val time: String, val timerDone: Boolean,
        val snooze: android.app.PendingIntent?, val dismiss: android.app.PendingIntent?,
    ) : IslandEvent
    /** Saat uygulamasının zamanlayıcı / alarm bildirimi kalktı. */
    data class ClockGone(val key: String) : IslandEvent
    /** Ses kaydedici uygulamada kayıt sürüyor / duraklatıldı. */
    data class VoiceRec(
        val key: String, val pkg: String, val startedAt: Long, val elapsedMs: Long, val paused: Boolean,
        val stop: android.app.PendingIntent?, val pauseResume: android.app.PendingIntent?, val open: android.app.PendingIntent?,
    ) : IslandEvent
    /** Ses kaydı bildirimi kalktı (kayıt bitti). */
    data class VoiceRecGone(val key: String) : IslandEvent
}

/** Arayüz -> NotificationListener (medya kontrolü) komutları. */
sealed interface MediaCommand {
    data object PlayPause : MediaCommand
    data object Next : MediaCommand
    data object Previous : MediaCommand
    data class SeekTo(val ms: Long) : MediaCommand
}

object IslandBus {
    private val _events = MutableSharedFlow<IslandEvent>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val events: SharedFlow<IslandEvent> = _events.asSharedFlow()
    fun post(e: IslandEvent) { _events.tryEmit(e) }

    private val _commands = MutableSharedFlow<MediaCommand>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val commands: SharedFlow<MediaCommand> = _commands.asSharedFlow()
    fun send(c: MediaCommand) { _commands.tryEmit(c) }
}
