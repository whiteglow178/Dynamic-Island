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

enum class StatusKind { Ringer, Charging, Timer, Alarm }

@Immutable
sealed interface IslandContent {
    data object Idle : IslandContent

    /** Google Asistan / Gemini açıkken: ada Siri benzeri renkli animasyona dönüşür. */
    data object Assistant : IslandContent

    /** Boştayken adaya dokununca/basılı tutunca açılan favori uygulamalar paneli. */
    data class Favorites(val apps: List<FavApp>) : IslandContent

    data class Notif(
        val pkg: String, val title: String, val text: String,
        val icon: ImageBitmap? = null,
        val intent: android.app.PendingIntent? = null,   // bildirimin kendi hedefi (ilgili sohbet / ekran)
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
    data class MediaChanged(val media: IslandContent.Media?) : IslandEvent
    data class AssistantChanged(val active: Boolean) : IslandEvent
    /** Ön plandaki uygulama değişti ([from] -> [to]); müzik uygulamasından çıkış animasyonu için. */
    data class AppSwitched(val from: String, val to: String) : IslandEvent
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
