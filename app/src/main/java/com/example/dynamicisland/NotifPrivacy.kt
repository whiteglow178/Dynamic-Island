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
 * İki tür kural var:
 * - Genel gizleme ([active]): açıkken (seçili uygulamaların ya da hepsinin) bildirimleri gizlenir. Açan koşullar servis
 *   tarafından birleştirilir: gizli mod (hızlı ayarlar kutucuğu da bunu değiştirir), bilek hareketi ([TwistDetector]),
 *   ekran kaydı / yansıtma ([CastWatch]), telefon masada hareketsiz ([StillWatch]), belirlenen saatler, kilit ekranı ([LockWatch]).
 * - Bildirime özel kurallar (genel gizleme kapalıyken de geçerli): hassas kelime, seçili kişiler, doğrulama kodu.
 *
 * Basılı tutunca gizlenen içerik görünür ([peek]), parmak kalkınca yeniden gizlenir.
 * Maskeleme yalnızca EKRANA giden kopyaya uygulanır ([mask]); servisin kendi mantığı gerçek bildirimi görür,
 * böylece dokununca yine doğru sohbet / ekran açılır, kod yine doğru kopyalanır.
 */
object NotifPrivacy {
    /** Gizlemenin kapsamı ve bildirime özel kurallar (servis ayarlardan doldurur). */
    data class Rule(
        val apps: Set<String> = emptySet(),   // boş = bütün uygulamalar; doluysa yalnızca bunların bildirimleri gizlenir
        val music: Boolean = false,           // müzik de gizlensin (şarkı adı, sanatçı, kapak, sözler)
        val blur: Boolean = false,            // "Yeni bildirim" yerine bulanık önizleme
        val words: List<String> = emptyList(),// hassas kelimeler (boş = kapalı)
        val people: List<String> = emptyList(),// gizlenecek kişiler (gönderen adının bir parçası)
        val otp: Boolean = false,             // doğrulama kodları ●●●● olarak görünsün
    ) {
        fun covers(pkg: String) = apps.isEmpty() || pkg in apps
        private val wordRx: Regex? by lazy {
            if (words.isEmpty()) null
            else Regex(words.joinToString("|", "(?iu)(?<![\\p{L}\\p{N}])(?:", ")(?![\\p{L}\\p{N}])") { Regex.escape(it) })
        }
        private val peopleLow by lazy { people.map { it.lowercase(TR) } }
        fun wordHit(n: IslandContent.Notif): Boolean {
            val rx = wordRx ?: return false
            return rx.containsMatchIn(n.title) || rx.containsMatchIn(n.text)
        }
        fun personHit(n: IslandContent.Notif): Boolean {
            if (peopleLow.isEmpty() || n.title.isBlank()) return false
            val t = n.title.lowercase(TR)
            return peopleLow.any { it in t }
        }
    }

    private val TR = java.util.Locale("tr", "TR")
    const val DEFAULT_WORDS = "şifre, parola, kod, doğrulama, OTP, banka, IBAN, TL, ₺, hesap, kart"

    /** Bilek hareketiyle inen perde. */
    val curtain = MutableStateFlow(false)
    /** Kullanıcı gizli içeriğin üstünde basılı tutuyor: içerik geçici olarak görünür. */
    val peek = MutableStateFlow(false)
    /** Genel gizleme devrede mi. Servis hesaplar. */
    val active = MutableStateFlow(false)
    val rule = MutableStateFlow(Rule())
    /** Genel gizlemeyi açan koşullar (servis ve izleyiciler doldurur). */
    val recording = MutableStateFlow(false)
    val casting = MutableStateFlow(false)
    val still = MutableStateFlow(false)
    val locked = MutableStateFlow(false)
    val scheduled = MutableStateFlow(false)

    /** Bu bildirim tamamen gizlenmeli mi (uygulama adı / bulanık). */
    fun hidesNotif(n: IslandContent.Notif, act: Boolean = active.value, r: Rule = rule.value): Boolean =
        (act && r.covers(n.pkg)) || r.wordHit(n) || r.personHit(n)

    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Uygulamanın görünen adı (önbellekli). */
    fun label(ctx: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault("Bildirim")
    }

    /** Ekranda gösterilecek hali (banner ve efektler için). */
    fun display(n: IslandContent.Notif, peeking: Boolean = peek.value, label: (String) -> String): IslandContent.Notif {
        if (peeking) return n
        val r = rule.value
        return when {
            hidesNotif(n, active.value, r) -> maskNotif(n, r.blur, label)
            r.otp && looksOtp(n) -> maskOtp(n)
            else -> n
        }
    }

    /**
     * Bildirimin gizlenmiş hali. Uygulama simgesi kalır.
     * - Normal: başlık = uygulamanın adı, metin = "Yeni bildirim".
     * - Bulanık: metin aynı uzunlukta anlamsız harflere çevrilir ve bulanık çizilir (gerçek yazı ekrana hiç gitmez);
     *   sohbet bildiriminde gönderenin adı görünür, diğerlerinde başlık uygulamanın adıdır.
     */
    fun maskNotif(n: IslandContent.Notif, blur: Boolean = false, label: (String) -> String): IslandContent.Notif =
        if (blur) n.copy(
            title = if (n.messaging && n.title.isNotBlank()) n.title else label(n.pkg),
            text = scramble(n.text.ifBlank { "Yeni bildirim" }), translatedFrom = "", chips = emptyList(),
            blurText = true, privMasked = true,
        ) else n.copy(
            title = label(n.pkg), text = "Yeni bildirim", avatar = null, messaging = false,
            translatedFrom = "", chips = emptyList(), privMasked = true,
        )

    // ---- Doğrulama kodu
    private val otpWord = Regex("(?iu)(kod|code|şifre|sifre|parola|otp|doğrulama|dogrulama|verification|onay|pin)")
    private val otpNum = Regex("(?<![\\p{L}\\p{N}])(?:\\d[ -]?){3,7}\\d(?![\\p{L}\\p{N}])")
    private fun dots(m: String) = "●".repeat(m.count { it.isDigit() }.coerceIn(4, 8))
    fun looksOtp(n: IslandContent.Notif) =
        (otpWord.containsMatchIn(n.title) || otpWord.containsMatchIn(n.text)) && (otpNum.containsMatchIn(n.text) || otpNum.containsMatchIn(n.title))
    fun maskOtp(n: IslandContent.Notif): IslandContent.Notif = n.copy(
        title = otpNum.replace(n.title) { dots(it.value) }, text = otpNum.replace(n.text) { dots(it.value) },
        chips = emptyList(), privMasked = true,
    )
    fun maskLive(c: IslandContent.Live): IslandContent.Live = c.copy(
        big = "●".repeat(c.big.count { it.isLetterOrDigit() }.coerceIn(4, 8)),
        title = otpNum.replace(c.title) { dots(it.value) }, text = otpNum.replace(c.text) { dots(it.value) },
        sub = otpNum.replace(c.sub) { dots(it.value) }, privMasked = true,
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

    private fun maskContent(c: IslandContent, act: Boolean, r: Rule, label: (String) -> String): IslandContent = when (c) {
        is IslandContent.Notif -> when {
            hidesNotif(c, act, r) -> maskNotif(c, r.blur, label)
            r.otp && looksOtp(c) -> maskOtp(c)
            else -> c
        }
        is IslandContent.Live -> if (c.kind == LiveKind.Code && (r.otp || (act && r.covers(c.pkg)))) maskLive(c) else c
        is IslandContent.Media -> if (act && r.music) maskMedia(c) else c
        else -> c
    }

    /** Ekrana giden durumu gizler: ortadaki, yan baloncuklar, yığın. Basılı tutarken yalnızca ortadaki açılır. */
    fun mask(u: IslandUiState, act: Boolean, r: Rule, peeking: Boolean, label: (String) -> String): IslandUiState {
        val c = u.content
        val center = if (peeking && (c is IslandContent.Notif || c is IslandContent.Live)) c else maskContent(c, act, r, label)
        return u.copy(
            content = center,
            side = u.side.map { maskContent(it, act, r, label) },
            stack = u.stack.map { maskContent(it, act, r, label) },
        )
    }

    /** Kuralların hiçbiri bir şey gizleyemiyorsa maskeleme atlanır. */
    fun anyRule(act: Boolean, r: Rule) = act || r.otp || r.words.isNotEmpty() || r.people.isNotEmpty()
}

/** Ekranda gizlenmiş (basılı tutunca açılabilir) içerik mi. */
fun IslandContent.privMasked(): Boolean = when (this) {
    is IslandContent.Notif -> privMasked
    is IslandContent.Live -> privMasked
    else -> false
}

/**
 * Telefon masada: bir süre (15 sn) neredeyse hiç kıpırdamadan duruyorsa [NotifPrivacy.still] açılır; ele alınınca
 * (küçük bir sarsıntı yeter) hemen kapanır. İvmeölçer yalnızca ekran açıkken dinlenir.
 */
class StillWatch(private val ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val acc: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var enabled = false
    private var listening = false
    private var lastMove = 0L
    private var ref = FloatArray(3)
    private var hasRef = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> listen(false)
                Intent.ACTION_SCREEN_ON -> listen(enabled)
            }
        }
    }

    fun start() {
        if (enabled || acc == null) return
        enabled = true
        runCatching {
            ctx.registerReceiver(screen, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON)
            })
        }
        listen(ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive != false)
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        listen(false)
        runCatching { ctx.unregisterReceiver(screen) }
        NotifPrivacy.still.value = false
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        hasRef = false
        lastMove = SystemClock.uptimeMillis()
        NotifPrivacy.still.value = false
        if (on) sm?.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL) else sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val now = SystemClock.uptimeMillis()
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        if (!hasRef) { ref = floatArrayOf(x, y, z); hasRef = true; lastMove = now; return }
        val d = abs(x - ref[0]) + abs(y - ref[1]) + abs(z - ref[2])
        // Referans yavaşça güncellenir: sensörün kendi kayması hareket sayılmaz
        ref[0] += (x - ref[0]) * 0.05f; ref[1] += (y - ref[1]) * 0.05f; ref[2] += (z - ref[2]) * 0.05f
        val flat = abs(z) > 8.6f              // ekran yukarı ya da aşağı bakıyor (masada yatıyor)
        if (d > 0.35f || !flat) {
            lastMove = now
            if (NotifPrivacy.still.value) NotifPrivacy.still.value = false
        } else if (!NotifPrivacy.still.value && now - lastMove > 15_000L) {
            NotifPrivacy.still.value = true
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

/** Kilit ekranı: telefon kilitliyken [NotifPrivacy.locked] açık; kilit açılınca kapanır. */
class LockWatch(private val ctx: Context) {
    private var on = false
    private val km = ctx.getSystemService(android.app.KeyguardManager::class.java)
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            NotifPrivacy.locked.value = when (i?.action) {
                Intent.ACTION_USER_PRESENT -> false
                Intent.ACTION_SCREEN_OFF -> true
                else -> km?.isKeyguardLocked == true
            }
        }
    }

    fun start() {
        if (on) return
        on = true
        runCatching {
            ctx.registerReceiver(rx, IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
            })
        }
        NotifPrivacy.locked.value = km?.isKeyguardLocked == true
    }

    fun stop() {
        if (!on) return
        on = false
        runCatching { ctx.unregisterReceiver(rx) }
        NotifPrivacy.locked.value = false
    }
}

/** Saat aralığı [fromMin]..[toMin] (gün içindeki dakika) şu an içinde mi; gece yarısını aşan aralık da olur (22:00–07:00). */
fun inPrivacyHours(fromMin: Int, toMin: Int): Boolean {
    val c = java.util.Calendar.getInstance()
    val now = c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    return if (fromMin <= toMin) now in fromMin until toMin else now >= fromMin || now < toMin
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
