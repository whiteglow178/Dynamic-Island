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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * - "Ekran kaydında gizle": sistemin ekran kaydı sürerken ya da ekran yansıtılırken ([CastWatch]) gizleme kendiliğinden açılır.
 * - Kapsam ([Rule]): yalnızca seçili uygulamalar, müziği de gizle, bulanık önizleme.
 *
 * Maskeleme yalnızca EKRANA giden kopyaya uygulanır ([mask]); servisin kendi mantığı gerçek bildirimi görür,
 * böylece dokununca yine doğru sohbet / ekran açılır.
 */
object NotifPrivacy {
    /** Gizlemenin kapsamı (servis ayarlardan doldurur). */
    data class Rule(
        val apps: Set<String> = emptySet(),   // boş = bütün uygulamalar; doluysa yalnızca bunların bildirimleri gizlenir
        val music: Boolean = false,           // müzik de gizlensin (şarkı adı, sanatçı, kapak, sözler)
        val blur: Boolean = false,            // "Yeni bildirim" yerine bulanık önizleme
    ) {
        fun covers(pkg: String) = apps.isEmpty() || pkg in apps
    }

    /** Bilek hareketiyle inen perde. */
    val curtain = MutableStateFlow(false)
    /** Kullanıcı bildirimin üstünde basılı tutuyor: içerik geçici olarak görünür. */
    val peek = MutableStateFlow(false)
    /** Şu an gizleme devrede mi (gizli mod, perde ya da ekran kaydı / yansıtma). Servis hesaplar. */
    val active = MutableStateFlow(false)
    val rule = MutableStateFlow(Rule())
    /** Ekran kaydı sürüyor (sistem kaydedicisi) / ekran başka bir ekrana yansıtılıyor. */
    val recording = MutableStateFlow(false)
    val casting = MutableStateFlow(false)

    /** Bu uygulamanın bildirimi şu an gizleniyor mu (basılı tutma içerik göstersin mi). */
    fun hides(pkg: String) = active.value && rule.value.covers(pkg)
    /** Müzik şu an gizleniyor mu. */
    fun hidesMusic() = active.value && rule.value.music

    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Uygulamanın görünen adı (önbellekli). */
    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault("Bildirim")
    }

    /** Ekranda gösterilecek hali: gizleniyorsa ve basılı tutulmuyorsa maskeli. */
    fun shown(ctx: Context, n: IslandContent.Notif): IslandContent.Notif =
        if (hides(n.pkg) && !peek.value) maskNotif(n, rule.value.blur) { label(ctx, it) } else n

    /**
     * Bildirimin gizlenmiş hali. Uygulama simgesi kalır.
     * - Normal: başlık = uygulamanın adı, metin = "Yeni bildirim".
     * - Bulanık: metin aynı uzunlukta anlamsız harflere çevrilir ve bulanık çizilir (gerçek yazı ekrana hiç gitmez);
     *   sohbet bildiriminde gönderenin adı görünür, diğerlerinde başlık uygulamanın adıdır.
     */
    fun maskNotif(n: IslandContent.Notif, blur: Boolean = false, label: (String) -> String): IslandContent.Notif =
        if (blur) n.copy(
            title = if (n.messaging && n.title.isNotBlank()) n.title else label(n.pkg),
            text = scramble(n.text.ifBlank { "Yeni bildirim" }), translatedFrom = "", chips = emptyList(), blurText = true,
        ) else n.copy(
            title = label(n.pkg), text = "Yeni bildirim", avatar = null, messaging = false,
            translatedFrom = "", chips = emptyList(),
        )

    private const val FILL = "aeonrsitlmkduybcgzvph"
    /** Yazının şeklini (uzunluk, boşluklar, büyük harf) korur, harfleri anlamsız harflerle değiştirir. */
    private fun scramble(t: String): String {
        val src = t.take(140)
        val sb = StringBuilder(src.length)
        src.forEachIndexed { i, ch ->
            sb.append(
                when {
                    ch.isWhitespace() -> ' '
                    ch.isLetterOrDigit() -> FILL[(i * 7 + ch.code) % FILL.length].let { if (ch.isUpperCase()) it.uppercaseChar() else it }
                    else -> ch
                }
            )
        }
        return sb.toString()
    }

    /** Gizli müzik: şarkı adı / sanatçı / kapak gizlenir; ekolayzır ve süre kalır. */
    fun maskMedia(m: IslandContent.Media): IslandContent.Media =
        m.copy(title = "Müzik çalıyor", artist = "", art = null, artColor = 0)

    private fun maskContent(c: IslandContent, r: Rule, label: (String) -> String): IslandContent = when {
        c is IslandContent.Notif && r.covers(c.pkg) -> maskNotif(c, r.blur, label)
        c is IslandContent.Media && r.music -> maskMedia(c)
        else -> c
    }

    /** Ekrana giden durumdaki bildirimleri (ve istenirse müziği) gizler: ortadaki, yan baloncuklar, yığın. */
    fun mask(u: IslandUiState, r: Rule, peeking: Boolean, label: (String) -> String): IslandUiState {
        // Basılı tutarken yalnızca ortadaki bildirim açılır; yan baloncuklar / müzik gizli kalır
        val center = if (peeking && u.content is IslandContent.Notif) u.content else maskContent(u.content, r, label)
        return u.copy(
            content = center,
            side = u.side.map { maskContent(it, r, label) },
            stack = u.stack.map { maskContent(it, r, label) },
        )
    }
}

/**
 * Bulanık önizleme: yazı zaten anlamsız harflerle değiştirildi; üstüne bulanıklık uygulanır (Android 12+).
 * Eski sürümlerde bulanıklık çizilmez ama yazı yine okunmaz.
 */
fun Modifier.privacyBlur(on: Boolean, radius: Dp = 4.dp): Modifier =
    if (on) this.blur(radius, BlurredEdgeTreatment.Unbounded) else this

/**
 * Ekran yansıtma (kablosuz ekran / yayın): sisteme "sunum" ekranı eklenince [NotifPrivacy.casting] açılır.
 * Başka bir uygulamanın ekran paylaşımı her zaman görünmez; sistemin ekran kaydı ayrıca ada olaylarından algılanır.
 */
class CastWatch(ctx: Context) : android.hardware.display.DisplayManager.DisplayListener {
    private val dm = ctx.getSystemService(android.hardware.display.DisplayManager::class.java)
    private var on = false

    fun start() {
        if (on || dm == null) return
        on = true
        runCatching { dm.registerDisplayListener(this, android.os.Handler(android.os.Looper.getMainLooper())) }
        refresh()
    }

    fun stop() {
        if (!on) return
        on = false
        runCatching { dm?.unregisterDisplayListener(this) }
        NotifPrivacy.casting.value = false
    }

    private fun refresh() {
        val n = runCatching { dm?.getDisplays(android.hardware.display.DisplayManager.DISPLAY_CATEGORY_PRESENTATION)?.size ?: 0 }.getOrDefault(0)
        NotifPrivacy.casting.value = n > 0
    }

    override fun onDisplayAdded(displayId: Int) = refresh()
    override fun onDisplayRemoved(displayId: Int) = refresh()
    override fun onDisplayChanged(displayId: Int) = refresh()
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
