package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * SES EFEKTLERİ
 * - Ses dosyası uygulamaya KOPYALANMAZ: sistemin belge seçicisinden yalnızca adres + kalıcı okuma izni alınır (depolama izni gerekmez).
 * - Bu dosya hiçbir yere log yazmaz; hatalar sessizce yutulur.
 * - Sesler medya ses düzeyiyle, ses odağı (audio focus) istenmeden çalar: çalan müzik durmaz / kısılmaz.
 */

enum class SoundEvent(val title: String, val hint: String) {
    Tap("Ses · Adaya dokununca", "Adaya her dokunduğunda çalar"),
    Charge("Ses · Şarja takınca", "Şarj kablosu takılınca çalar"),
    Unplug("Ses · Şarjdan çıkarınca", "Şarj kablosu çıkarılınca çalar"),
    Notif("Ses · Bildirim gelince", "Adada gösterilen yeni bildirimde çalar"),
    Silent("Ses · Sessize alınca", "Telefon sessiz ya da titreşim moduna geçince çalar"),
    Call("Ses · Biri arayınca", "Gelen aramada, ada aramayı gösterdiğinde çalar"),
}

/** Bir olaya atanan ses. [endMs] 0 ise sesin sonuna kadar çalar; [partial] kapalıysa tamamı çalar. */
data class SoundCue(
    val enabled: Boolean = false,
    val uri: String = "",
    val name: String = "",
    val volume: Float = 1f,
    val partial: Boolean = false,
    val startMs: Int = 0,
    val endMs: Int = 0,
    val durMs: Int = 0,       // dosyanın toplam süresi (kaydırıcı sınırı)
) {
    fun encode(): String = JSONObject()
        .put("e", enabled).put("u", uri).put("n", name).put("v", volume.toDouble())
        .put("p", partial).put("s", startMs).put("t", endMs).put("d", durMs).toString()

    companion object {
        fun decode(s: String?): SoundCue {
            if (s.isNullOrEmpty()) return SoundCue()
            return runCatching {
                val o = JSONObject(s)
                SoundCue(
                    enabled = o.optBoolean("e"), uri = o.optString("u"), name = o.optString("n"),
                    volume = o.optDouble("v", 1.0).toFloat().coerceIn(0f, 1f),
                    partial = o.optBoolean("p"), startMs = o.optInt("s"), endMs = o.optInt("t"), durMs = o.optInt("d"),
                )
            }.getOrDefault(SoundCue())
        }
    }
}

fun IslandSettings.cue(e: SoundEvent): SoundCue = when (e) {
    SoundEvent.Tap -> sfxTap
    SoundEvent.Charge -> sfxCharge
    SoundEvent.Unplug -> sfxUnplug
    SoundEvent.Notif -> sfxNotif
    SoundEvent.Silent -> sfxSilent
    SoundEvent.Call -> sfxCall
}

fun IslandSettings.withCue(e: SoundEvent, c: SoundCue): IslandSettings = when (e) {
    SoundEvent.Tap -> copy(sfxTap = c)
    SoundEvent.Charge -> copy(sfxCharge = c)
    SoundEvent.Unplug -> copy(sfxUnplug = c)
    SoundEvent.Notif -> copy(sfxNotif = c)
    SoundEvent.Silent -> copy(sfxSilent = c)
    SoundEvent.Call -> copy(sfxCall = c)
}

fun IslandSettings.sfxUris(): List<String> = SoundEvent.values().map { cue(it).uri }.filter { it.isNotEmpty() }

object IslandSounds {
    private val main = Handler(Looper.getMainLooper())

    private class Active(val mp: MediaPlayer, val key: String) {
        var stop: Runnable? = null
    }

    private val active = ArrayList<Active>()

    /** Sesi çalar. [key] aynı olayın önceki sesini keser (art arda dokunuşta üst üste binmesin). */
    fun play(ctx: Context, cue: SoundCue, key: String = cue.uri) {
        if (cue.uri.isEmpty()) return
        val app = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) start(app, cue, key) else main.post { start(app, cue, key) }
    }

    fun stopAll() {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { stopAll() }; return }
        active.toList().forEach { release(it) }
    }

    private fun start(ctx: Context, cue: SoundCue, key: String) {
        active.filter { it.key == key }.forEach { release(it) }
        while (active.size >= 4) release(active[0])
        val mp = MediaPlayer()
        val a = Active(mp, key)
        active.add(a)
        try {
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            mp.setDataSource(ctx, Uri.parse(cue.uri))
            val v = cue.volume.coerceIn(0f, 1f)
            mp.setVolume(v, v)
            val from = if (cue.partial) cue.startMs.coerceAtLeast(0) else 0
            val to = if (cue.partial && cue.endMs > from) cue.endMs else 0
            mp.setOnCompletionListener { release(a) }
            mp.setOnErrorListener { _, _, _ -> release(a); true }
            mp.setOnPreparedListener { p ->
                val go = {
                    try {
                        p.start()
                        if (to > 0) {
                            val r = Runnable { release(a) }
                            a.stop = r
                            main.postDelayed(r, (to - from).toLong())
                        }
                    } catch (e: Exception) { release(a) }
                }
                if (from > 0) {
                    p.setOnSeekCompleteListener { it.setOnSeekCompleteListener(null); go() }
                    p.seekTo(from.toLong(), MediaPlayer.SEEK_CLOSEST)
                } else go()
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            release(a)
        }
    }

    private fun release(a: Active) {
        a.stop?.let { main.removeCallbacks(it) }
        a.stop = null
        active.remove(a)
        runCatching { a.mp.setOnPreparedListener(null); a.mp.setOnCompletionListener(null); a.mp.setOnErrorListener(null) }
        runCatching { a.mp.release() }
    }

    // ---- Dosya seçimi: kopyalama yok, yalnızca adres + kalıcı okuma izni

    fun grant(ctx: Context, uri: Uri) {
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Başka hiçbir olay kullanmıyorsa eski adresin kalıcı iznini bırakır. */
    fun releaseGrant(ctx: Context, oldUri: String, stillUsed: List<String>) {
        if (oldUri.isEmpty() || oldUri in stillUsed) return
        runCatching { ctx.contentResolver.releasePersistableUriPermission(Uri.parse(oldUri), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /** Seçilen dosyanın süresini ve adını okur; ses dosyası değilse null. Yeni seçimde kırpma sıfırlanır. */
    suspend fun load(ctx: Context, uri: Uri, base: SoundCue): SoundCue? = withContext(Dispatchers.IO) {
        runCatching {
            var dur = 0
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(ctx, uri)
                dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            } finally {
                runCatching { r.release() }
            }
            if (dur <= 0) {
                null
            } else {
                var name = ""
                ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                    if (c.moveToFirst()) name = c.getString(0).orEmpty()
                }
                base.copy(enabled = true, uri = uri.toString(), name = name.ifEmpty { "Ses" }, durMs = dur, startMs = 0, endMs = 0)
            }
        }.getOrNull()
    }
}

/** Şarja takma / şarjdan çıkarma / sessize alma olaylarını dinler (ek izin gerekmez). */
class SoundTriggers(private val ctx: Context, private val onEvent: (SoundEvent) -> Unit) {
    private var receiver: BroadcastReceiver? = null
    private var lastRinger = -1

    private fun isSilent(mode: Int) = mode == AudioManager.RINGER_MODE_SILENT || mode == AudioManager.RINGER_MODE_VIBRATE

    fun start() {
        if (receiver != null) return
        lastRinger = (ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.ringerMode ?: -1
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                runCatching { handle(i) }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
        }
        val ok = runCatching {
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
        }.isSuccess
        if (ok) receiver = r
    }

    fun stop() {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
    }

    private fun handle(i: Intent?) {
        val intent = i ?: return
        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> onEvent(SoundEvent.Charge)
            Intent.ACTION_POWER_DISCONNECTED -> onEvent(SoundEvent.Unplug)
            AudioManager.RINGER_MODE_CHANGED_ACTION -> {
                val m = intent.getIntExtra(AudioManager.EXTRA_RINGER_MODE, -1)
                val was = isSilent(lastRinger)
                lastRinger = m
                if (isSilent(m) && !was) onEvent(SoundEvent.Silent)
            }
        }
    }
}
