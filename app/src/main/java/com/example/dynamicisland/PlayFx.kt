package com.example.dynamicisland

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/*
 * OYUNCAK ADA: adayı canlı bir nesne gibi davrandıran efektler. Hepsi ayrı ayardır.
 *  1 Fiziksel ada  : telefonu eğince ada yana kayar, sallayınca jöle gibi titrer      -> PlayIsland.kt
 *  2 Ada evcili     : adanın altından sarkan, ruh hali olan küçük yaratık            -> PlayOverlay.kt
 *  3 Mıknatıs       : şarja takınca ada kabloya doğru damla uzatır, kulaklık bağlanınca iki damla çeker -> PlayOverlay.kt
 *  4 Savrulma      : adaya dokununca ada rüzgâr yemiş gibi savrulur, kenarından toz uçuşur -> PlayIsland.kt + PlayOverlay.kt
 *  6 Ada yüzü       : boştaki adada göz kırpan, bakınan, uyuyan gözler               -> PlayIsland.kt
 *  8 Dalga          : bildirim gelince / adaya dokununca adadan halka halka dalga yayılır -> PlayOverlay.kt
 * 10 Cep hissi      : ekran açılınca ada yukarıdan düşüp yerine oturur, uzun süre boştaysa kamera deliğine girer -> PlayIsland.kt
 *  Pil sıvısı       : boştaki ada pil seviyesi kadar dolu cam kap; eğince sıvı çalkalanır  -> PlayIsland.kt
 *  Bildirim yiyen   : bildirim baloncuğu uçup adaya girer, ada yutar ve "doyar"         -> PlayOverlay.kt + PlayIsland.kt
 *  Çekiştirme       : adayı aşağı çekip bırakınca lastik gibi geri fırlar               -> PlayIsland.kt
 *  Gün ışığı        : adanın çevresindeki hale günün saatine göre renk değiştirir      -> PlayOverlay.kt
 *  Gizli oyun       : boştaki adaya hızlıca 5 kez dokununca "Ada Pong" açılır          -> IslandGame.kt
 *
 * Ada penceresi ile tam ekran efekt penceresi aynı (ana) iş parçacığında çalışır; ortak durum burada tutulur.
 */

/** Efekt olayları: servis / denetleyici üretir, ada ve efekt penceresi dinler. */
sealed interface PlayEvent {
    data object Ripple : PlayEvent        // dalga
    data object Charger : PlayEvent       // şarja takıldı (mıknatıs)
    data object Buds : PlayEvent          // kulaklık bağlandı (mıknatıs)
    data object Puff : PlayEvent          // adaya dokunuldu: savrulma
    data object ScreenOn : PlayEvent      // ekran açıldı (cep hissi, yüz uyanır)
    data object Notif : PlayEvent         // bildirim geldi (evcil kafasını çıkarır, yüz sevinir)
    data object Eaten : PlayEvent         // bildirim baloncuğu adaya girdi (ada yutar)
    data object Party : PlayEvent         // kutlama: konfeti
    data object Water : PlayEvent         // bitki sulandı
    data object Fling : PlayEvent         // ada savruldu (hız: PlayFx.flingVx / flingVy)
    data class Say(val text: String) : PlayEvent   // evcil bir şey söylesin
}

object PlayFx {
    // ---- Sensör (ana iş parçacığında yazılır)
    var gx = 0f                               // yerçekimi, cihaz X ekseni (m/s², alçak geçirgen)
    var gy = 9.81f
    var shake = 0f                            // son sallamanın şiddeti; fizik döngüsü okuyup sıfırlar
    var sensorAt = 0L                         // son sensör olayı (ms)

    // ---- Adanın anlık dönüşümü (ada penceresi ve efekt penceresi okur)
    var slideX by mutableFloatStateOf(0f)     // yana kayma (px)
    var jelly by mutableFloatStateOf(0f)      // jöle titreşimi: + yayvan, - uzun
    var jellyV = 0f                           // jöle hızı (durum değil)
    var dropY by mutableFloatStateOf(0f)      // cep hissi: yukarıdan düşüş (px, negatif = yukarıda)
    var pocket by mutableFloatStateOf(0f)     // 0 normal, 1 kamera deliğine girmiş
    var puff by mutableFloatStateOf(0f)       // dokunma savrulması şiddeti 0..1

    // ---- Telefon durumu (evcil ve yüz kullanır)
    var charging by mutableStateOf(false)
    var batteryLow by mutableStateOf(false)
    var musicPlaying by mutableStateOf(false)
    var batteryPct by mutableFloatStateOf(1f)          // 0..1
    var fullness by mutableFloatStateOf(0f)            // yenen bildirim sayısı (0..4): ada "doyar"
    var liquidAngle by mutableFloatStateOf(0f)         // pil sıvısının yüzey açısı (radyan)
    var liquidV = 0f
    var flying by mutableStateOf(false)                // ada ekranda uçuyor (gerçek ada gizli, efekt penceresi çizer)
    var flingVx = 0f; var flingVy = 0f
    var partyUntil = 0L                                // kutlama süresince evcil dans eder, yüz sevinir
    var lastActivityMs = System.currentTimeMillis()   // son dokunma / bildirim: evcil sıkılır mı

    val events = MutableSharedFlow<PlayEvent>(extraBufferCapacity = 16)

    fun emit(e: PlayEvent) {
        if (e == PlayEvent.Ripple || e == PlayEvent.Notif) lastActivityMs = System.currentTimeMillis()
        events.tryEmit(e)
    }

    /** Adaya jöle darbesi verir (+ yayvanlaşır). */
    fun kick(v: Float) { jellyV += v }
}

/**
 * Sensör, ekran, şarj, pil ve kulaklık olaylarını dinler; yalnızca ilgili ayar açıkken.
 * Ekran kapalıyken sensör dinlenmez (pil tüketmesin).
 */
class PlayController(
    private val ctx: Context,
    private val settings: StateFlow<IslandSettings>,
    private val scope: CoroutineScope,
) {
    private val main = Handler(Looper.getMainLooper())
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var sensorOn = false
    private var receiver: BroadcastReceiver? = null
    private var devCb: AudioDeviceCallback? = null
    private var devCbAt = 0L
    private var screenOn = true
    private var fullCelebrated = true          // açılışta zaten doluysa kutlama yapılmaz
    private var wasCharging = true
    private var wasLow = true

    /** Doğum günü ya da yılbaşı: günün ilk ekran açılışında konfeti. */
    private fun dateParty() {
        val s = settings.value
        if (!s.playParty) return
        val d = java.time.LocalDate.now()
        val birthday = s.playBirthDay > 0 && d.dayOfMonth == s.playBirthDay && d.monthValue == s.playBirthMonth
        val newYear = d.dayOfMonth == 1 && d.monthValue == 1
        if ((birthday || newYear) && PartyStore.claimToday(ctx)) {
            main.postDelayed({
                PlayFx.emit(PlayEvent.Party)
                PlayFx.emit(PlayEvent.Say(if (birthday) "İyi ki doğdun!" else "Mutlu yıllar!"))
            }, 900L)
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
            val a = 0.86f
            PlayFx.gx = PlayFx.gx * a + x * (1f - a)
            PlayFx.gy = PlayFx.gy * a + y * (1f - a)
            val dev = abs(sqrt(x * x + y * y + z * z) - SensorManager.GRAVITY_EARTH)
            if (dev > 6f) PlayFx.shake = max(PlayFx.shake, dev)
            PlayFx.sensorAt = SystemClock.uptimeMillis()
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun start() {
        screenOn = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_ON -> { screenOn = true; syncSensor(); PlayFx.emit(PlayEvent.ScreenOn); dateParty() }
                    Intent.ACTION_SCREEN_OFF -> { screenOn = false; syncSensor() }
                    Intent.ACTION_POWER_CONNECTED -> if (settings.value.playMagnet) PlayFx.emit(PlayEvent.Charger)
                    Intent.ACTION_BATTERY_CHANGED -> readBattery(i)
                }
            }
        }
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_BATTERY_CHANGED)
        }
        val ok = runCatching {
            val sticky = if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(r, f)
            sticky?.let { readBattery(it) }
        }.isSuccess
        if (ok) receiver = r
        // Kulaklık: kayıt anında mevcut cihazlar da "eklendi" olarak gelir; ilk 1,5 sn yok sayılır
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) {
                if (SystemClock.uptimeMillis() - devCbAt < 1_500L) return
                if (!settings.value.playMagnet || !settings.value.playMagnetBuds) return
                if (added?.any { it.isSink && it.type in HEADSET_TYPES } == true) PlayFx.emit(PlayEvent.Buds)
            }
        }
        devCbAt = SystemClock.uptimeMillis()
        runCatching { am?.registerAudioDeviceCallback(cb, main) }.onSuccess { devCb = cb }
        runCatching { PlantStore.load(ctx); DoodleStore.load(ctx) }
        main.postDelayed({ dateParty() }, 3_000L)
        // Sensör yalnızca fizik ya da yüz açıkken
        scope.launch { settings.map { it.playPhysics || it.playFace || it.playLiquid }.distinctUntilChanged().collect { syncSensor() } }
    }

    private fun readBattery(i: Intent) {
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        PlayFx.charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (lvl >= 0) PlayFx.batteryPct = (lvl.toFloat() / sc).coerceIn(0f, 1f)
        // Kutlama: şarjdayken pil %100 olunca (her şarjda bir kez)
        val full = PlayFx.charging && lvl >= sc
        if (full && !fullCelebrated && settings.value.playParty) PlayFx.emit(PlayEvent.Party)
        if (full) fullCelebrated = true
        if (!PlayFx.charging) fullCelebrated = false
        if (st == BatteryManager.BATTERY_STATUS_CHARGING && !wasCharging) PlayFx.emit(PlayEvent.Say("Mmm, enerji!"))
        if (PlayFx.batteryLow && !wasLow) PlayFx.emit(PlayEvent.Say("Acıktım…"))
        wasCharging = st == BatteryManager.BATTERY_STATUS_CHARGING; wasLow = PlayFx.batteryLow
        PlayFx.batteryLow = lvl in 0..(sc * 15 / 100) && !PlayFx.charging
    }

    private fun syncSensor() {
        val s = settings.value
        val want = screenOn && (s.playPhysics || s.playFace || s.playLiquid)
        if (want == sensorOn) return
        if (want) {
            val m = sm ?: return
            val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sensorOn = runCatching { m.registerListener(listener, acc, SensorManager.SENSOR_DELAY_GAME) }.getOrDefault(false)
        } else {
            runCatching { sm?.unregisterListener(listener) }
            sensorOn = false
            PlayFx.gx = 0f; PlayFx.gy = SensorManager.GRAVITY_EARTH; PlayFx.shake = 0f
        }
    }

    fun stop() {
        runCatching { sm?.unregisterListener(listener) }
        sensorOn = false
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        devCb?.let { runCatching { am?.unregisterAudioDeviceCallback(it) } }
        devCb = null
    }

    private companion object {
        val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET, 26 /* TYPE_BLE_HEADSET (API 31) */,
        )
    }
}
