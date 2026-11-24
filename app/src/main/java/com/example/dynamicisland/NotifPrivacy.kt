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
        val decoy: Boolean = false,           // yem metin: gizliyken sıradan, dikkat çekmeyen sahte bir bildirim görünür
        val reveal: Int = 0,                  // içeriği açma hareketi: 0 basılı tut, 1 sağa-sola çiz, 2 iki parmakla basılı tut
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
            hidesNotif(n, active.value, r) -> maskNotif(n, r, label)
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
    fun maskNotif(n: IslandContent.Notif, r: Rule, label: (String) -> String): IslandContent.Notif =
        if (r.decoy) decoyOf(n)
        else if (r.blur) n.copy(
            title = if (n.messaging && n.title.isNotBlank()) n.title else label(n.pkg),
            text = scramble(n.text.ifBlank { "Yeni bildirim" }), translatedFrom = "", chips = emptyList(),
            blurText = true, privMasked = true,
        ) else n.copy(
            title = label(n.pkg), text = "Yeni bildirim", avatar = null, messaging = false,
            translatedFrom = "", chips = emptyList(), privMasked = true,
        )

    // ---- Yem metin
    /**
     * Gizliyken gösterilen sıradan bildirimler. Uygulama simgesi de gizlenir (yerine başlığın ilk harfi çıkar), böylece
     * ekrana bakan biri gizli bir şey olduğunu anlamaz. Aynı bildirim hep aynı yem metni alır (yanıp sönmesin).
     */
    private val DECOYS = listOf(
        "Mağaza" to "Giyim ürünlerinde %10 indirim",
        "Uygulama mağazası" to "5 güncelleme mevcut",
        "Kampanya" to "Bu fırsatları kaçırma",
        "Hava durumu" to "Sıcaklık 20 derece, parçalı bulutlu",
        "Hava durumu" to "Bugün 18°C, akşam hafif yağmur bekleniyor",
        "Sistem" to "Yedekleme tamamlandı",
        "Sistem güncellemesi" to "Yeni sürüm indirmeye hazır",
        "Galeri" to "Yeni anılarınız hazır",
        "Pil" to "Pil sağlığı iyi durumda",
        "Takvim" to "Yarın için 1 etkinlik",
        "Market" to "Sepetindeki ürünlerde indirim var",
        "Kargo" to "Siparişin hazırlanıyor",
        "Ayakkabı" to "Sezon sonu indirimi başladı",
        "Elektronik" to "Kulaklıklarda %15 indirim",
        "Kampanya" to "Hafta sonuna özel ücretsiz kargo",
        "Haberler" to "Günün öne çıkan haberleri",
        "Spor" to "Haftanın maç programı yayınlandı",
        "Müzik" to "Senin için yeni çalma listesi",
        "Adım sayar" to "Bugün 4.200 adım attın",
        "Depolama" to "Gereksiz dosyalar temizlendi",
        "Güvenlik" to "Tarama tamamlandı, tehdit bulunamadı",
        "Kafe" to "Kahvende ikincisi %50 indirimli",
        "Mağaza" to "Favori ürünün tekrar stokta",
        "Uygulamalar" to "3 uygulama güncellendi",
        "Hava durumu" to "Yarın güneşli, en yüksek 24°C",
        "Kampanya" to "Sana özel kuponun seni bekliyor",
    )
    fun decoyOf(n: IslandContent.Notif): IslandContent.Notif {
        val seed = (n.key.ifEmpty { n.pkg + n.title }).hashCode() and 0x7fffffff
        val (t, x) = DECOYS[seed % DECOYS.size]
        return n.copy(
            title = t, text = x, icon = null, avatar = null, messaging = false, accent = 0,
            translatedFrom = "", chips = emptyList(), blurText = false, privMasked = true,
        )
    }

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
            hidesNotif(c, act, r) -> maskNotif(c, r, label)
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

// ---------------------------------------------------------------- Gizli açma hareketi
/**
 * Gizli içeriği açan özel hareket (basılı tutma dışındaki seçenekler). Hareket tamamlanınca [NotifPrivacy.peek] açılır,
 * parmak(lar) kalkınca kapanır. Dönüş: 0 = hareket olmadı, kısa dokunuştu; 1 = içerik açıldı (ya da hareket bozuldu),
 * dokunma sayılmaz; 2 = uzun basılı tutuldu (hareket yok).
 *
 * - [mode] 1: parmağı kaldırmadan önce sağa, sonra sola çiz.
 * - [mode] 2: iki parmakla basılı tut.
 * Olaylar Initial aşamasında tüketilir: alttaki dokunma algılayıcıları içerik açılınca dokunma saymaz.
 */
suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.privRevealGesture(
    down: androidx.compose.ui.input.pointer.PointerInputChange, mode: Int, stepPx: Float, longMs: Long,
): Int {
    val pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial
    var phase = 0                 // sağ-sol: 0 = sağa bekleniyor, 1 = sola bekleniyor
    var maxX = down.position.x
    var moved = false
    var revealed = false
    try {
        while (true) {
            val ev = awaitPointerEvent(pass)
            val main = ev.changes.firstOrNull { it.id == down.id }
            val pressed = ev.changes.count { it.pressed }
            if (revealed) {
                ev.changes.forEach { it.consume() }
                if (pressed == 0) return 1
                continue
            }
            if (main == null || !main.pressed) {
                return when {
                    moved -> 1
                    main != null && main.uptimeMillis - down.uptimeMillis >= longMs -> 2
                    else -> 0
                }
            }
            val dx = main.position.x - down.position.x
            val dy = main.position.y - down.position.y
            if (kotlin.math.abs(dx) > stepPx * 0.5f || kotlin.math.abs(dy) > stepPx * 0.5f) moved = true
            when (mode) {
                1 -> {
                    if (phase == 0 && dx > stepPx) { phase = 1; maxX = main.position.x }
                    if (phase == 1) {
                        if (main.position.x > maxX) maxX = main.position.x
                        if (maxX - main.position.x > stepPx) revealed = true
                    }
                    if (moved && kotlin.math.abs(dx) > kotlin.math.abs(dy)) main.consume()
                }
                2 -> if (pressed >= 2) revealed = true
            }
            if (revealed) { NotifPrivacy.peek.value = true; ev.changes.forEach { it.consume() } }
        }
    } finally {
        if (revealed) NotifPrivacy.peek.value = false
    }
}

// ---------------------------------------------------------------- Panik sallaması
/**
 * Telefon kısa sürede iki kez sertçe sallanınca [onPanic] çağrılır. İvmeölçer yalnızca ekran açıkken dinlenir.
 * Eşik yüksek tutulur (~2.4 g): yürümek, cebe koymak, masaya bırakmak tetiklemez.
 */
class ShakeWatch(private val ctx: Context, private val onPanic: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val acc: Sensor? = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var enabled = false
    private var listening = false
    private var firstAt = 0L
    private var lastPeak = 0L
    private var lastFire = 0L

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_OFF -> { NotifPrivacy.curtain.value = false; listen(false) }
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
    }

    private fun listen(on: Boolean) {
        if (on == listening) return
        listening = on
        firstAt = 0L
        if (on) sm?.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME) else sm?.unregisterListener(this)
    }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val g = kotlin.math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < 2.4f) return
        val now = SystemClock.uptimeMillis()
        if (now - lastFire < 2_000L) return
        if (now - lastPeak < 180L) return          // aynı sarsıntının devamı
        lastPeak = now
        if (firstAt == 0L || now - firstAt > 1_000L) { firstAt = now; return }
        firstAt = 0L
        lastFire = now
        onPanic()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}

// ---------------------------------------------------------------- Titreşim imzası
/** Kişiye özel titreşim desenleri: ekrana bakmadan kimden mesaj geldiğini anlamak için. */
object BuzzSign {
    val NAMES = listOf("İki kısa", "Uzun", "Kısa · uzun · kısa", "Kalp atışı")
    private val PATTERNS = listOf(
        longArrayOf(0, 60, 120, 60),
        longArrayOf(0, 450),
        longArrayOf(0, 60, 120, 300, 120, 60),
        longArrayOf(0, 40, 90, 110, 450, 40, 90, 110),
    )

    /** Gönderen adı listelerden birine uyuyorsa desenin sırası (0..3), uymuyorsa -1. */
    fun match(title: String, lists: List<String>): Int {
        if (title.isBlank()) return -1
        val t = title.lowercase(java.util.Locale("tr", "TR"))
        lists.forEachIndexed { i, csv ->
            csv.split(',').map { it.trim().lowercase(java.util.Locale("tr", "TR")) }.filter { it.isNotEmpty() }
                .forEach { if (it in t) return i }
        }
        return -1
    }

    fun play(ctx: Context, i: Int) {
        val p = PATTERNS.getOrNull(i) ?: return
        runCatching {
            @Suppress("DEPRECATION")
            val v: android.os.Vibrator? = if (android.os.Build.VERSION.SDK_INT >= 31)
                ctx.getSystemService(android.os.VibratorManager::class.java)?.defaultVibrator
            else ctx.getSystemService(android.os.Vibrator::class.java)
            v?.vibrate(android.os.VibrationEffect.createWaveform(p, -1))
        }
    }
}
