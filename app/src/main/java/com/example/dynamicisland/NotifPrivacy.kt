package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs

/**
 * Bildirim gizliliği (Ayarlar > Bildirimler > Gizlilik).
 *
 * - "Gizli bildirim modu": adada bildirimin içeriği yerine yalnızca uygulamanın adı ve "Yeni bildirim" görünür;
 *   kişi fotoğrafı, akıllı düğmeler, çeviri gizlenir. Bildirimin üstünde basılı tutunca içerik görünür ([peek]),
 *   parmak kalkınca yeniden gizlenir.
 * - "Bilekle gizle": telefonu bilekten hızlıca çevirip geri getirince ([TwistDetector]) perde iner ([curtain]):
 *   gizli mod kapalı olsa bile bildirimler aynı şekilde gizlenir. Aynı hareket perdeyi kaldırır; ekran kapanınca
 *   perde kendiliğinden kalkar.
 *
 * Maskeleme yalnızca EKRANA giden kopyaya uygulanır ([mask]); servisin kendi mantığı gerçek bildirimi görür,
 * böylece dokununca yine doğru sohbet / ekran açılır.
 */
object NotifPrivacy {
    /** Bilek hareketiyle inen perde. */
    val curtain = MutableStateFlow(false)
    /** Kullanıcı bildirimin üstünde basılı tutuyor: içerik geçici olarak görünür. */
    val peek = MutableStateFlow(false)
    /** Şu an bildirimler gizleniyor mu (gizli mod ya da perde). Servis hesaplar. */
    val active = MutableStateFlow(false)

    /** Bildirimin gizlenmiş hali: başlık = uygulamanın adı, metin = "Yeni bildirim". Uygulama simgesi kalır. */
    fun maskNotif(n: IslandContent.Notif, label: (String) -> String): IslandContent.Notif =
        n.copy(
            title = label(n.pkg), text = "Yeni bildirim", avatar = null, messaging = false,
            translatedFrom = "", chips = emptyList(),
        )

    private fun maskContent(c: IslandContent, label: (String) -> String): IslandContent =
        if (c is IslandContent.Notif) maskNotif(c, label) else c

    /** Ekrana giden durumdaki bütün bildirimleri (ortadaki, yan baloncuklar, yığın) gizler. */
    fun mask(u: IslandUiState, label: (String) -> String): IslandUiState {
        val hasNotif = u.content is IslandContent.Notif ||
            u.side.any { it is IslandContent.Notif } || u.stack.any { it is IslandContent.Notif }
        if (!hasNotif) return u
        return u.copy(
            content = maskContent(u.content, label),
            side = u.side.map { maskContent(it, label) },
            stack = u.stack.map { maskContent(it, label) },
        )
    }
}

/**
 * Bilekle hızlı çevirme: telefonun uzun ekseni etrafında hızlı bir dönüş ve kısa sürede ters yönde geri dönüş
 * (kapı kolu çevirir gibi). Jiroskobun y ekseni kullanılır. Yürürken / cebe koyarken oluşan yavaş dönüşler sayılmaz:
 * iki yön de eşiği aşmalı ve 450 ms içinde gelmeli. Ekran açıkken dinlenir; ekran kapanınca dinleme durur ve perde kalkar.
 */
class TwistDetector(private val ctx: Context, private val onTwist: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val gyro: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var enabled = false
    private var listening = false
    private var firstSign = 0
    private var firstAt = 0L
    private var lastFire = 0L

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { NotifPrivacy.curtain.value = false; listen(false) }
                Intent.ACTION_SCREEN_ON -> listen(enabled)
            }
        }
    }

    /** Telefonda jiroskop var mı (yoksa ayar işe yaramaz). */
    val available: Boolean get() = gyro != null

    fun start() {
        if (enabled || gyro == null) return
        enabled = true
        runCatching {
            ctx.registerReceiver(screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            })
        }
        val on = ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false
        listen(on)
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        listen(false)
        runCatching { ctx.unregisterReceiver(screen) }
        NotifPrivacy.curtain.value = false
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        firstSign = 0
        if (on) sm?.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME)
        else sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val wy = e.values[1]                     // rad/s, telefonun uzun ekseni etrafında
        val now = SystemClock.uptimeMillis()
        if (now - lastFire < 900L) return        // bir hareket iki kez sayılmasın
        if (firstSign != 0 && now - firstAt > 450L) firstSign = 0
        if (abs(wy) < 5.5f) return
        val sign = if (wy > 0f) 1 else -1
        if (firstSign == 0) { firstSign = sign; firstAt = now; return }
        if (sign == -firstSign) {
            firstSign = 0
            lastFire = now
            onTwist()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
