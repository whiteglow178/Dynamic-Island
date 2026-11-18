package com.example.dynamicisland

import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Kamera / mikrofon göstergesi (nokta modundan bağımsız ayrı ayar). Servisten taşındı; davranış aynıdır.
 * Kamera: CameraManager "kullanılamıyor" bildirimi; mikrofon: AudioRecordingCallback. İkisi de izin gerektirmez.
 */
class PrivacyController(private val host: IslandHost) {

    /** Şu an gösterilmesi gereken gösterge (kullanım yoksa null). */
    var content: IslandContent.Status? = null
        private set

    private val camBusy = HashSet<String>()
    private var micBusy = false
    private var camCb: CameraManager.AvailabilityCallback? = null
    private var micCb: AudioManager.AudioRecordingCallback? = null

    fun start() {
        val ctx = host.context
        val h = Handler(Looper.getMainLooper())
        val cm = ctx.getSystemService(CameraManager::class.java)
        val cb = object : CameraManager.AvailabilityCallback() {
            override fun onCameraUnavailable(cameraId: String) { camBusy.add(cameraId); runCatching { refresh() } }
            override fun onCameraAvailable(cameraId: String) { camBusy.remove(cameraId); runCatching { refresh() } }
        }
        camCb = cb
        runCatching { cm.registerAvailabilityCallback(cb, h) }
        val am = ctx.getSystemService(AudioManager::class.java)
        val mcb = object : AudioManager.AudioRecordingCallback() {
            override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>) {
                micBusy = configs.isNotEmpty()
                runCatching { refresh() }
            }
        }
        micCb = mcb
        runCatching { am.registerAudioRecordingCallback(mcb, h) }
        // Servis başlarken zaten süren bir kayıt varsa onu da yakala
        runCatching { micBusy = am.activeRecordingConfigurations.isNotEmpty() }
    }

    fun stop() {
        val ctx = host.context
        camCb?.let { cb -> runCatching { ctx.getSystemService(CameraManager::class.java).unregisterAvailabilityCallback(cb) } }
        micCb?.let { cb -> runCatching { ctx.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(cb) } }
        camCb = null; micCb = null
    }

    /** Kamera / mikrofon durumuna göre göstergeyi günceller: başlayınca kısa süre panel açılır, sonra nokta olarak kalır. */
    fun refresh() {
        val on = host.settings.privacyIndicator
        val cam = on && camBusy.isNotEmpty()
        val mic = on && micBusy
        val next = if (!cam && !mic) null else IslandContent.Status(
            kind = if (cam) StatusKind.Camera else StatusKind.Mic,
            title = when { cam && mic -> "Kamera ve mikrofon kullanılıyor"; cam -> "Kamera kullanılıyor"; else -> "Mikrofon kullanılıyor" },
        )
        val prev = content
        if (next == prev) return
        content = next
        val cur = host.content
        val curStatus = cur as? IslandContent.Status
        when {
            next != null && prev?.title != next.title && !host.busy -> host.showTemporarily(next, 4_000L)
            next == null && curStatus != null && (curStatus.kind == StatusKind.Camera || curStatus.kind == StatusKind.Mic) -> {
                if (host.expanded) host.collapseSoon() else host.rest()
            }
            else -> host.update { it }
        }
    }
}
