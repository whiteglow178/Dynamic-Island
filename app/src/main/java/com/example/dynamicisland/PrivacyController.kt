package com.example.dynamicisland

import android.content.Intent
import android.hardware.camera2.CameraManager
import android.provider.MediaStore
import android.media.AudioManager
import android.media.AudioRecordingConfiguration
import android.os.Handler
import android.os.Looper

/**
 * Kamera / mikrofon göstergesi (nokta modundan bağımsız ayrı ayar). Servisten taşındı; davranış aynıdır.
 * Kamera: CameraManager "kullanılamıyor" bildirimi; mikrofon: AudioRecordingCallback. İkisi de izin gerektirmez.
 *
 * Fotoğraf modu: birçok üreticinin kamera uygulaması fotoğraf modunda üçüncü taraf uygulamalara bildirilmeyen
 * gizli (üreticiye özel) bir kamera kimliği açar; video modunda ise standart kamerayı açtığı için yalnızca videoda
 * nokta çıkıyordu. Bu yüzden iki ek sinyal daha kullanılır (ikisi de izin gerektirmez):
 *  - Fener "kullanılamaz" oldu (kamera açıkken flaş başka uygulamaya kilitlenir),
 *  - Ön plandaki uygulama telefonun kamera uygulaması (erişilebilirlik servisinin bildirdiği ön plan değişimi).
 */
class PrivacyController(private val host: IslandHost) {

    /** Şu an gösterilmesi gereken gösterge (kullanım yoksa null). */
    var content: IslandContent.Status? = null
        private set

    private val camBusy = HashSet<String>()
    private val torchBusy = HashSet<String>()
    private var camAppFront = false
    private var micBusy = false
    private var torchCb: CameraManager.TorchCallback? = null
    private val cameraPkgs: Set<String> by lazy {
        val pm = host.context.packageManager
        val out = HashSet<String>()
        for (a in listOf(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, MediaStore.INTENT_ACTION_VIDEO_CAMERA, MediaStore.ACTION_IMAGE_CAPTURE)) {
            runCatching { pm.queryIntentActivities(Intent(a), 0).forEach { out.add(it.activityInfo.packageName) } }
        }
        out
    }

    /** Erişilebilirlik servisinden gelen ön plan değişimi: kamera uygulaması açıldıysa kamera kullanılıyor sayılır. */
    fun onForeground(pkg: String) {
        val front = pkg in cameraPkgs
        if (front == camAppFront) return
        camAppFront = front
        runCatching { refresh() }
    }
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
        // Flaş kilitlendi = bir uygulama arka kamerayı açtı (gizli kamera kimliğiyle açılsa da çoğu cihazda gelir)
        val tcb = object : CameraManager.TorchCallback() {
            override fun onTorchModeUnavailable(cameraId: String) { if (torchBusy.add(cameraId)) runCatching { refresh() } }
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (torchBusy.remove(cameraId)) runCatching { refresh() } }
        }
        torchCb = tcb
        runCatching { cm.registerTorchCallback(tcb, h) }
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
        torchCb?.let { cb -> runCatching { ctx.getSystemService(CameraManager::class.java).unregisterTorchCallback(cb) } }
        torchCb = null
        micCb?.let { cb -> runCatching { ctx.getSystemService(AudioManager::class.java).unregisterAudioRecordingCallback(cb) } }
        camCb = null; micCb = null
    }

    /** Kamera / mikrofon durumuna göre göstergeyi günceller: başlayınca kısa süre panel açılır, sonra nokta olarak kalır. */
    fun refresh() {
        val on = host.settings.privacyIndicator
        val cam = on && (camBusy.isNotEmpty() || torchBusy.isNotEmpty() || camAppFront)
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
            next != null && prev?.title != next.title && !host.busy -> {
                // Ayara göre: kısa süre genişleyip küçülür ya da hiç genişlemeden doğrudan küçük (nokta) olarak görünür
                if (host.settings.privacyExpand) host.showTemporarily(next, 4_000L)
                else if (host.expanded) host.update { it } else host.rest()
            }
            next == null && curStatus != null && (curStatus.kind == StatusKind.Camera || curStatus.kind == StatusKind.Mic) -> {
                if (host.expanded) host.collapseSoon() else host.rest()
            }
            else -> host.update { it }
        }
    }
}
