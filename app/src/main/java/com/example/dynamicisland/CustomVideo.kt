package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Video arka plan: fotoğraf gibi KOPYALANMAZ. Yalnızca videonun adresi (URI) ayarlara kaydedilir,
 * video seçicinin verdiği kalıcı okuma izniyle orijinal yerinden oynatılır (depolama izni gerekmez).
 * Orijinal video silinirse/taşınırsa arka plan sessizce kaybolur (fotoğraftaki davranışla aynı).
 */
object CustomVideo {
    /** Seçiciden dönen adrese kalıcı OKUMA izni alır. */
    fun grant(ctx: Context, uri: Uri) {
        try {
            ctx.applicationContext.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) { }
    }

    /** Video kaldırılınca/değişince verilen izni geri bırakır (başka yerde kullanılmıyorsa). */
    fun releaseUnused(ctx: Context, old: String, now: IslandSettings) {
        if (old.isEmpty() || now.customBgVideoUri == old || now.customArtVideoUri == old) return
        if (CustomImages.Slot.values().any { now.imageUri(it) == old }) return
        try {
            ctx.applicationContext.contentResolver.releasePersistableUriPermission(Uri.parse(old), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: Throwable) { }
    }
}

/** Sessiz, döngülü, ada içini kaplayan (center-crop) video. Ekran kapanınca durur, açılınca devam eder. */
@Composable
fun VideoBackdrop(uri: String, dim: Float, modifier: Modifier = Modifier) {
    var failed by remember(uri) { mutableStateOf(false) }
    if (uri.isEmpty() || failed) return
    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c -> LoopingVideoView(c) },
            update = { v ->
                v.onError = { failed = true }
                v.setSource(Uri.parse(uri))
            },
            onRelease = { it.shutdown() },
        )
        Box(Modifier.fillMaxSize().background(Pal.c("island_scrim").copy(alpha = dim.coerceIn(0f, 0.9f))))
    }
}

private class LoopingVideoView(ctx: Context) : TextureView(ctx), TextureView.SurfaceTextureListener {
    var onError: () -> Unit = {}
    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var uri: Uri? = null
    private var vw = 0
    private var vh = 0
    private var screenOn = true
    private var prepared = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            screenOn = i?.action != Intent.ACTION_SCREEN_OFF
            syncPlayback()
        }
    }

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    fun setSource(u: Uri) {
        if (u == uri) return
        uri = u
        restart()
    }

    private fun restart() {
        releasePlayer()
        val u = uri ?: return
        val s = surface ?: return
        try {
            val mp = MediaPlayer()
            player = mp
            mp.setSurface(s)
            mp.setDataSource(context.applicationContext, u)
            mp.isLooping = true
            mp.setVolume(0f, 0f)
            mp.setOnVideoSizeChangedListener { _, w, h -> vw = w; vh = h; applyCrop() }
            mp.setOnPreparedListener { prepared = true; syncPlayback() }
            mp.setOnErrorListener { _, _, _ -> post { onError() }; true }
            mp.prepareAsync()
        } catch (_: Throwable) {
            releasePlayer()
            post { onError() }
        }
    }

    private fun syncPlayback() {
        val mp = player ?: return
        if (!prepared) return
        try {
            if (screenOn && isAttachedToWindow) { if (!mp.isPlaying) mp.start() } else if (mp.isPlaying) mp.pause()
        } catch (_: Throwable) { }
    }

    /** TextureView görüntüyü gerer; en-boy oranını geri kazanıp kenarları keser (center-crop). */
    private fun applyCrop() {
        if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) return
        val viewAspect = width.toFloat() / height
        val videoAspect = vw.toFloat() / vh
        var sx = 1f
        var sy = 1f
        if (videoAspect > viewAspect) sx = videoAspect / viewAspect else sy = viewAspect / videoAspect
        val m = Matrix()
        m.setScale(sx, sy, width / 2f, height / 2f)
        setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        applyCrop()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        try { context.applicationContext.registerReceiver(screenReceiver, f) } catch (_: Throwable) { }
        syncPlayback()
    }

    override fun onDetachedFromWindow() {
        try { context.applicationContext.unregisterReceiver(screenReceiver) } catch (_: Throwable) { }
        syncPlayback()
        super.onDetachedFromWindow()
    }

    private fun releasePlayer() {
        prepared = false
        player?.let { try { it.stop() } catch (_: Throwable) { }; try { it.release() } catch (_: Throwable) { } }
        player = null
    }

    fun shutdown() {
        releasePlayer()
        surface?.release(); surface = null
    }

    override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
        surface = Surface(st)
        restart()
    }

    override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) { applyCrop() }

    override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
        releasePlayer()
        surface?.release(); surface = null
        return true
    }

    override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
}
