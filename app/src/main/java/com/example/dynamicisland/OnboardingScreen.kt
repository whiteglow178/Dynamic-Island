package com.example.dynamicisland

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.graphics.drawable.AdaptiveIconDrawable
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ---------------------------------------------------------------------------------------------
// Açılış (ilk kurulum) ekranı.
// Akış: Hoşgeldin bildirimi -> Cevapla -> yakınlaşma + klavye ("Hoşbuldum") -> izin aramaları
// (Erişilebilirlik, Bildirim gösterme, Üstte gösterme, Bildirim erişimi, Pil, Otomatik başlatma)
// -> telefon uzaklaşır, "Artık Hazırsınız".
// Telefon: TryPhoneIntro.kt içindeki Phone3DPreview (yalnızca üst kısmı / ada bölgesi görünür).
// ---------------------------------------------------------------------------------------------

private const val ONB_PREFS = "omni_onboarding"
private const val K_WELCOME = "welcome_done"
private const val K_AUTO_OPENED = "autostart_opened"
private const val K_AUTO_DONE = "autostart_done"

private enum class OnbPhase { Welcome, Reply, Perms, Unlock, Finish }

private enum class OnbPerm(
    val callText: String, val okText: String, val title: String, val desc: String,
    val required: Boolean, val tint: Color,
) {
    Access(
        "Erişilebilirlik aç", "Erişilebilirlik hazır",
        "Erişilebilirlik izni gerekli",
        "Adaya dokunabilmen ve sözlerin adadan çıkması için erişilebilirlik servisini açmalısın. Adadaki aramayı Cevapla ile aç.",
        true, Color(0xFF5AC8FA),
    ),
    PostNotif(
        "Bildirim izni ver", "Bildirim izni hazır",
        "Bildirim izni gerekli",
        "Servis bildiriminin görünebilmesi için bildirim gösterme iznini ver.",
        true, Color(0xFFFF9F0A),
    ),
    Overlay(
        "Üstte gösterme aç", "Üstte gösterme hazır",
        "Üstte gösterme izni gerekli",
        "Adanın diğer uygulamaların üzerinde çizilebilmesi için bu izin şart.",
        true, Color(0xFFBF5AF2),
    ),
    Listener(
        "Bildirim erişimi", "Bildirim erişimi hazır",
        "Bildirim erişimi gerekli",
        "Bildirimleri ve müzik oturumunu okuyabilmek için bildirim erişimini aç.",
        true, Color(0xFF30D158),
    ),
    Battery(
        "Pil izni ver", "Pil kısıtlaması kaldırıldı",
        "Pil kısıtlaması yok (önerilir)",
        "Sistemin uygulamayı arka planda kapatmasını azaltır.",
        false, Color(0xFFFFD60A),
    ),
    Autostart(
        "Otomatik başlatma", "Otomatik başlatma tamam",
        "Arka planda otomatik başlatma",
        "Kapalıysa uygulamayı son uygulamalardan silince erişilebilirlik servisi durur. Ayarlarda \"İzin ver\" yap. " +
            "Bu ayar algılanamadığı için açsan da açmasan da devam edebilirsin.",
        false, Color(0xFFFF453A),
    ),
}

private val ONB_ORDER = listOf(
    OnbPerm.Access, OnbPerm.PostNotif, OnbPerm.Overlay, OnbPerm.Listener, OnbPerm.Battery,
    OnbPerm.Autostart,   // en sona: algılanamıyor, verilse de verilmese de devam edilir
)

// ------------------------------------------------------------------ izin kontrolleri
private fun Context.onbOverlay() = Settings.canDrawOverlays(this)
private fun Context.onbListener() = NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
private fun Context.onbAccess(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.contains("$packageName/${IslandAccessibilityService::class.java.name}")
}
private fun Context.onbPostNotif() =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
private fun Context.onbBattery() =
    runCatching { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }.getOrDefault(true)

private fun onbGranted(ctx: Context, p: OnbPerm): Boolean = when (p) {
    OnbPerm.Access -> ctx.onbAccess()
    OnbPerm.PostNotif -> ctx.onbPostNotif()
    OnbPerm.Overlay -> ctx.onbOverlay()
    OnbPerm.Listener -> ctx.onbListener()
    OnbPerm.Battery -> ctx.onbBattery()
    OnbPerm.Autostart -> false
}

/** "Hoşbuldum" kontrolü: büyük/küçük harf, nokta ve Türkçe karakter farkı önemsenmez. */
private fun onbNorm(s: String): String =
    s.trim().trim('.', '!', ' ').lowercase(java.util.Locale.forLanguageTag("tr-TR")).replace('ş', 's').replace('ı', 'i')

// ------------------------------------------------------------------ ada içeriği modelleri
private sealed interface OnbIsland {
    data object Idle : OnbIsland
    data object Notif : OnbIsland
    data object Reply : OnbIsland
    data class Call(val perm: OnbPerm) : OnbIsland
    data class Done(val text: String) : OnbIsland
}

/** Sanal ekran dp'si (412 dp genişlik) cinsinden ada ölçüleri. */
private fun OnbIsland.dims(): Pair<Dp, Dp> = when (this) {
    is OnbIsland.Idle -> 126.dp to 37.dp
    is OnbIsland.Notif -> 340.dp to 138.dp
    is OnbIsland.Reply -> 340.dp to 126.dp
    is OnbIsland.Call -> 340.dp to 138.dp
    is OnbIsland.Done -> 300.dp to 64.dp
}

private data class OnbInfo(val title: String, val desc: String, val warn: String?, val skip: Boolean, val grayHelp: Boolean)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun OnboardingScreen(onFinished: () -> Unit) {
    val ctx = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences(ONB_PREFS, Context.MODE_PRIVATE) }
    val kb = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val focus = remember { FocusRequester() }

    var phase by rememberSaveable {
        mutableStateOf(if (prefs.getBoolean(K_WELCOME, false)) OnbPhase.Perms else OnbPhase.Welcome)
    }
    var tick by remember { mutableIntStateOf(0) }
    val skipped = remember { mutableStateListOf<OnbPerm>() }
    var typed by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf<String?>(null) }
    var warnFor by remember { mutableStateOf<OnbPerm?>(null) }
    var shownPerm by remember { mutableStateOf<OnbPerm?>(null) }
    var waiting by remember { mutableStateOf<OnbPerm?>(null) }
    var notifAsked by remember { mutableStateOf(false) }
    var shakeCount by remember { mutableIntStateOf(0) }
    var started by remember { mutableStateOf(false) }
    var introShown by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }
    var pinWrong by remember { mutableStateOf(false) }
    var unlocked by remember { mutableStateOf(false) }

    fun isDone(p: OnbPerm): Boolean =
        p in skipped || when (p) {
            OnbPerm.Autostart -> prefs.getBoolean(K_AUTO_DONE, false)
            else -> onbGranted(ctx, p)
        }

    val current: OnbPerm? = remember(tick, phase, skipped.size) { ONB_ORDER.firstOrNull { !isDone(it) } }

    // ---- yaşam döngüsü: ayarlardan dönünce izinleri yeniden kontrol et
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        // Otomatik başlatma ayarına gidip dönüldüyse (ya da dönerken süreç öldüyse) tamam say: algılanamıyor
        if (prefs.getBoolean(K_AUTO_OPENED, false)) prefs.edit().putBoolean(K_AUTO_DONE, true).apply()
        waiting = null
        tick++
    }

    // Ayarlara gönderilen izin verilir verilmez uygulamayı öne getir (Kurulum sayfasındaki yöntemin aynısı)
    LaunchedEffect(waiting) {
        val w = waiting ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val end = SystemClock.elapsedRealtime() + 180_000L
            while (SystemClock.elapsedRealtime() < end) {
                delay(60)
                if (onbGranted(ctx, w)) { onboardingBringToFront(ctx); break }
            }
        }
        waiting = null
        tick++
    }

    // İzin dışarıdan verilirse de yakala
    LaunchedEffect(phase) { while (phase == OnbPhase.Perms) { delay(700); tick++ } }

    // Giriş: telefon iner, kısa süre sonra ada bildirimle genişler
    LaunchedEffect(Unit) { started = true; delay(1300); introShown = true }

    // İzin adımları arası geçiş: "hazır ✓" göster, sonra sıradaki arama; hepsi bitince bitiş
    LaunchedEffect(current, phase) {
        if (phase != OnbPhase.Perms) return@LaunchedEffect
        try {
            val prev = shownPerm
            if (prev != null && prev != current) { flash = prev.okText; delay(1000) }
        } finally { flash = null }
        shownPerm = current
        if (current == null) { delay(400); phase = OnbPhase.Unlock }
    }

    LaunchedEffect(phase) {
        if (phase == OnbPhase.Finish) { delay(1400); onFinished() }
    }

    // Cevapla -> klavye
    LaunchedEffect(phase) {
        if (phase == OnbPhase.Reply) {
            delay(550)
            runCatching { focus.requestFocus() }
            kb?.show()
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (!granted) { warnFor = OnbPerm.PostNotif; shakeCount++ }
    }

    // ---- eylemler
    val submit: () -> Unit = submit@{
        if (flash != null) return@submit
        if (onbNorm(typed) == "hosbuldum") {
            prefs.edit().putBoolean(K_WELCOME, true).apply()
            kb?.hide(); focusManager.clearFocus()
            scope.launch {
                flash = "Hoşbuldum gönderildi ✓"
                delay(1200)
                flash = null
                typed = ""
                phase = OnbPhase.Perms
            }
        } else { wrong = true; shakeCount++ }
    }
    val appIcon = remember {
        runCatching {
            val d = ctx.packageManager.getApplicationIcon(ctx.packageName)
            OnbAppIcon(d.toBitmap(192, 192).asImageBitmap(), d is AdaptiveIconDrawable)
        }.getOrNull()
    }
    val focusField: () -> Unit = { runCatching { focus.requestFocus() }; kb?.show() }

    // Şifre ekranı (3 boyutlu telefon): şifre 1234
    val onDigit: (String) -> Unit = { d ->
        if (!unlocked && pin.length < 4) {
            pinWrong = false
            pin += d
            if (pin.length == 4) {
                if (pin == "1234") {
                    unlocked = true   // ana ekran açılır; Omni Island simgesine dokunulunca devam edilir
                } else {
                    pinWrong = true; shakeCount++
                    scope.launch { delay(500); pin = "" }
                }
            }
        }
    }
    val onDelete: () -> Unit = { if (!unlocked && pin.isNotEmpty() && pin.length < 4) { pin = pin.dropLast(1); pinWrong = false } }

    val answerCall: () -> Unit = answer@{
        val p = shownPerm ?: return@answer
        warnFor = null
        when (p) {
            OnbPerm.Access -> {
                waiting = p
                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            OnbPerm.PostNotif -> {
                if (!notifAsked && Build.VERSION.SDK_INT >= 33) {
                    notifAsked = true
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    waiting = p
                    ctx.startActivity(
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    )
                }
            }
            OnbPerm.Overlay -> {
                waiting = p
                ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
            }
            OnbPerm.Listener -> {
                waiting = p
                ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            OnbPerm.Battery -> {
                waiting = p
                runCatching {
                    ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
            }
            OnbPerm.Autostart -> {
                // Algılanamaz: ayara gidildiğini kaydet; dönünce (ON_RESUME) tamam sayılır
                prefs.edit().putBoolean(K_AUTO_OPENED, true).apply()
                onboardingOpenAutostart(ctx)
            }
        }
    }
    val declineCall: () -> Unit = { warnFor = shownPerm ?: current; shakeCount++ }

    // ---- ada modu
    val mode: OnbIsland = when {
        phase == OnbPhase.Reply && flash != null -> OnbIsland.Done(flash!!)
        phase == OnbPhase.Welcome -> if (introShown) OnbIsland.Notif else OnbIsland.Idle
        phase == OnbPhase.Reply -> OnbIsland.Reply
        phase == OnbPhase.Perms -> if (flash != null) OnbIsland.Idle else shownPerm?.let { OnbIsland.Call(it) } ?: OnbIsland.Idle
        else -> OnbIsland.Idle
    }

    // ---- animasyon değerleri
    val zoom by animateFloatAsState(
        if (phase == OnbPhase.Reply && flash == null) 1.28f else 1f,
        spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessLow), label = "onbZoom",
    )
    val enter by animateFloatAsState(if (started) 1f else 0f, tween(1000, easing = FastOutSlowInEasing), label = "onbEnter")
    val exit by animateFloatAsState(if (phase == OnbPhase.Finish) 1f else 0f, tween(450, easing = FastOutSlowInEasing), label = "onbExit")
    val shake = remember { Animatable(0f) }
    LaunchedEffect(shakeCount) {
        if (shakeCount > 0) for (t in listOf(14f, -12f, 9f, -6f, 3f, 0f)) shake.animateTo(t, tween(55))
    }

    // ---- bilgi metni (telefonun dışı)
    val p = shownPerm ?: current
    val info: OnbInfo = when {
        phase == OnbPhase.Perms && flash != null -> OnbInfo("✓ " + flash!!, "", null, false, false)
        phase == OnbPhase.Welcome -> OnbInfo(
            "Omni Island'a hoş geldin",
            "Telefonun adasına gelen bildirimde Cevapla'ya dokunarak başla.", null, false, false,
        )
        phase == OnbPhase.Reply -> OnbInfo(
            "Cevabını yaz",
            "Klavyede \"Hoşbuldum\" yazıp gönder tuşuna basman gerek.",
            if (wrong) "Tam olarak \"Hoşbuldum\" yazmalısın." else null, false, false,
        )
        phase == OnbPhase.Unlock || phase == OnbPhase.Finish -> OnbInfo(
            if (unlocked) "Artık Hazırsınız" else "Şifre: 1234",
            if (unlocked) "Omni Island uygulamasına Hoş Geldiniz. Başlamak için telefondaki Omni Island simgesine dokun."
            else "Telefonun kilidini açmak için tuş takımına 1234 gir.",
            if (pinWrong) "Yanlış şifre, tekrar dene. Şifre: 1234" else null, false, false,
        )
        p != null -> OnbInfo(
            p.title, p.desc,
            if (warnFor == p) {
                if (p.required) "Uygulamanın çalışması için bu iznin verilmesi gerekiyor. Adadaki aramayı Cevapla ile açıp izni ver."
                else "Önerilir ama zorunlu değil. İstersen şimdilik geçebilirsin."
            } else null,
            skip = !p.required && warnFor == p,
            grayHelp = p == OnbPerm.Access || p == OnbPerm.Listener,
        )
        else -> OnbInfo("", "", null, false, false)
    }
    val stepIndex = when (phase) {
        OnbPhase.Welcome, OnbPhase.Reply -> 0
        OnbPhase.Perms -> 1 + ONB_ORDER.indexOf(p).coerceAtLeast(0)
        OnbPhase.Unlock, OnbPhase.Finish -> ONB_ORDER.size
    }

    val screenW = LocalConfiguration.current.screenWidthDp
    val phoneW = (screenW * 0.88f).coerceAtMost(380f)
    // Şifre aşamasında telefonun tamamı görünür: kırpma alanı büyür, telefon ekrana sığacak kadar küçülür
    val phoneH = phoneW * 146.6f / 70.6f
    val fitScale = ((LocalConfiguration.current.screenHeightDp - 250f) / phoneH).coerceIn(0.5f, 1f)
    val full by animateFloatAsState(
        if (phase == OnbPhase.Unlock || phase == OnbPhase.Finish) 1f else 0f,
        tween(700, easing = FastOutSlowInEasing), label = "onbFull",
    )
    val cropH = (280f + (44f + phoneH * fitScale + 12f - 280f) * full).dp

    // Uygulamaya girince 3B telefon, EKRANI cihazın ekranını tam kaplayana kadar yaklaşır
    var rootPx by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    val dens = LocalDensity.current
    val rootWdp = rootPx.width / dens.density
    val rootHdp = rootPx.height / dens.density
    val scrInset = 2.6f * phoneW / 70.6f
    val scrW = phoneW - 2f * scrInset
    val scrH = phoneH - 2f * scrInset
    val sbTop = WindowInsets.systemBars.asPaddingValues().calculateTopPadding().value
    val launchScale = if (rootWdp > 0f) maxOf(rootWdp / scrW, rootHdp / scrH) else 1f
    val launchTy = rootHdp / 2f - (sbTop + 44f) - (scrInset + scrH / 2f) * launchScale
    val launch by animateFloatAsState(
        if (phase == OnbPhase.Finish) 1f else 0f, tween(1100, easing = FastOutSlowInEasing), label = "onbLaunch",
    )

    Box(Modifier.fillMaxSize().background(cs.background).onSizeChanged { rootPx = it }) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
            // ---- 3 boyutlu telefon: yalnızca üst kısım (ada bölgesi) görünür
            Box(
                Modifier.fillMaxWidth().height(cropH).zIndex(1f)
                    .then(if (phase == OnbPhase.Finish) Modifier else Modifier.clipToBounds()),
                contentAlignment = Alignment.TopCenter,
            ) {
                Box(
                    Modifier.padding(top = 44.dp).graphicsLayer {
                        val s0 = zoom * (1f + (fitScale - 1f) * full)
                        val s = s0 + (launchScale - s0) * launch
                        scaleX = s; scaleY = s
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationY = (-90f * (1f - enter) + launchTy * launch) * density
                        alpha = enter
                    },
                ) {
                    Phone3DPreview(phoneWDp = phoneW) {
                        if (phase == OnbPhase.Welcome || phase == OnbPhase.Reply) {
                            // Karşılama bildirimi ve cevap alanı (gerçek adada düğmeli bildirim / yazı alanı yok)
                            OnbIslandView(
                                mode = mode, typed = typed, wrong = wrong,
                                onAnswer = { phase = OnbPhase.Reply },
                                onDecline = {}, onFieldTap = focusField, onSend = submit,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp).offset(x = shake.value.dp),
                            )
                        } else if (phase == OnbPhase.Perms) {
                            // İzin aramaları: uygulamanın GERÇEK adası (ayarlardaki renk / şekil / animasyonla)
                            RealIslandHost(mode = mode, shakeX = shake.value, onAnswer = answerCall, onDecline = declineCall)
                        } else {
                            PhoneLockContent(
                                pin, pinWrong, unlocked, shake.value, onDigit, onDelete, appIcon,
                                onOpened = { if (phase == OnbPhase.Unlock) phase = OnbPhase.Finish },
                            )
                        }
                    }
                }
                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(80.dp)
                        .graphicsLayer { alpha = 1f - full }
                        .background(Brush.verticalGradient(listOf(Color.Transparent, cs.background))),
                )
            }

            // ---- adım noktaları
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp).graphicsLayer { alpha = 1f - exit },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val total = ONB_ORDER.size + 1
                for (i in 0 until total) {
                    val w by animateDpAsState(if (i == stepIndex) 22.dp else 7.dp, label = "dotW$i")
                    Box(
                        Modifier.padding(horizontal = 3.dp).width(w).height(7.dp).clip(CircleShape)
                            .background(if (i <= stepIndex) cs.primary else cs.outline.copy(alpha = 0.6f)),
                    )
                }
            }

            // ---- telefonun dışındaki bilgilendirme
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 28.dp).graphicsLayer { alpha = 1f - exit }) {
                AnimatedContent(
                    targetState = info,
                    transitionSpec = { fadeIn(tween(260, 120)) togetherWith fadeOut(tween(120)) },
                    label = "onbInfo",
                ) { i ->
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(i.title, color = cs.onBackground, fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp)
                        Text(i.desc, color = cs.onSurfaceVariant, fontSize = 15.sp, lineHeight = 21.sp)
                        if (i.warn != null) {
                            Text(i.warn, color = Color(0xFFFF6B5E), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, lineHeight = 20.sp)
                        }
                        if (i.skip) {
                            OnbTextButton("Şimdilik geç") {
                                val cur = p
                                if (cur == OnbPerm.Autostart) prefs.edit().putBoolean(K_AUTO_DONE, true).apply()
                                else if (cur != null) skipped.add(cur)
                                tick++
                            }
                        }
                        if (i.grayHelp) {
                            OnbTextButton("Anahtar gri mi? Uygulama bilgisini aç") {
                                runCatching {
                                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                                }
                            }
                        }
                    }
                }
            }

            // ---- alt kısım: izin listesi (Welcome / izin adımlarında)
            if (phase == OnbPhase.Welcome || phase == OnbPhase.Perms) {
                OnbChecklist(
                    items = ONB_ORDER.map { perm ->
                        perm to when {
                            isDone(perm) -> 2
                            phase == OnbPhase.Perms && perm == p -> 1
                            else -> 0
                        }
                    },
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp).graphicsLayer { alpha = 1f - exit },
                )
            }
        }

        // ---- telefon klavyeyi tetikleyen gizli alan (yazılan metin adadaki alana yansıtılır)
        if (phase == OnbPhase.Reply) {
            BasicTextField(
                value = typed,
                onValueChange = { typed = it.take(20); wrong = false },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                modifier = Modifier.size(1.dp).graphicsLayer { alpha = 0f }.focusRequester(focus),
            )
        }
    }
}

// ------------------------------------------------------------------ küçük bileşenler
@Composable
private fun OnbTextButton(text: String, onClick: () -> Unit) {
    Text(
        text, color = MaterialTheme.colorScheme.primary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 2.dp),
    )
}

@Composable
private fun OnbLogo(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        drawRoundRect(
            Brush.linearGradient(listOf(tint, tint.copy(alpha = 0.55f))),
            cornerRadius = CornerRadius(size.minDimension * 0.28f),
        )
        val pw = size.width * 0.56f
        val ph = size.height * 0.2f
        drawRoundRect(Color.White, Offset((size.width - pw) / 2f, (size.height - ph) / 2f), Size(pw, ph), CornerRadius(ph / 2f))
    }
}

@Composable
private fun OnbButton(text: String, bg: Color, fg: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(40.dp).clip(RoundedCornerShape(20.dp)).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

/** Telefon ekranındaki (412 dp'lik sanal ekran) ada: boyut / köşe yayları animasyonlu, içerik geçişli. */
@Composable
private fun OnbIslandView(
    mode: OnbIsland, typed: String, wrong: Boolean,
    onAnswer: () -> Unit, onDecline: () -> Unit, onFieldTap: () -> Unit, onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val (w, h) = mode.dims()
    val aw by animateDpAsState(w, spring(dampingRatio = 0.78f, stiffness = 260f), label = "islandW")
    val ah by animateDpAsState(h, spring(dampingRatio = 0.78f, stiffness = 260f), label = "islandH")
    val corner by animateDpAsState(if (mode is OnbIsland.Idle) 19.dp else 34.dp, spring(stiffness = 300f), label = "islandR")
    val shape = RoundedCornerShape(corner)
    Box(
        modifier.size(aw, ah).clip(shape).background(Color.Black).border(0.5.dp, Color(0xFF2A2A2E), shape),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = mode, animationSpec = tween(220), label = "islandContent") { m ->
            val (cw, ch) = m.dims()
            Box(Modifier.requiredSize(cw, ch)) {
                when (m) {
                    is OnbIsland.Idle -> Box(
                        Modifier.align(Alignment.CenterEnd).padding(end = 14.dp).size(10.dp).clip(CircleShape).background(Color(0xFF15151B)),
                    )
                    is OnbIsland.Notif -> BannerBody(
                        tint = Color(0xFF0A84FF), label = "Omni Island", text = "Omni Island Uygulamasına Hoşgeldiniz",
                    ) { OnbButton("Cevapla", Color.White, Color.Black, Modifier.weight(1f), onAnswer) }
                    is OnbIsland.Call -> BannerBody(
                        tint = m.perm.tint, label = "Omni Island · Arama", text = m.perm.callText,
                    ) {
                        OnbButton("Reddet", Color(0xFFFF453A), Color.White, Modifier.weight(1f), onDecline)
                        OnbButton("Cevapla", Color(0xFF30D158), Color.White, Modifier.weight(1f), onAnswer)
                    }
                    is OnbIsland.Reply -> ReplyBody(typed, wrong, onFieldTap, onSend)
                    is OnbIsland.Done -> Row(
                        Modifier.fillMaxSize().padding(horizontal = 20.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(30.dp).clip(CircleShape).background(Color(0xFF30D158)), contentAlignment = Alignment.Center) {
                            Text("✓", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Text(m.text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun BannerBody(tint: Color, label: String, text: String, buttons: @Composable RowScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OnbLogo(tint, Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = Color(0xFF9A9AA0), fontSize = 12.sp)
                Text(text, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, lineHeight = 19.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), content = buttons)
    }
}

@Composable
private fun ReplyBody(typed: String, wrong: Boolean, onFieldTap: () -> Unit, onSend: () -> Unit) {
    val caret by rememberInfiniteTransition(label = "caret").animateFloat(
        0f, 1f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "caretA",
    )
    Column(
        Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OnbLogo(Color(0xFF0A84FF), Modifier.size(28.dp))
            Text("Omni Island'a yanıt veriyorsun", color = Color(0xFF9A9AA0), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val fieldShape = RoundedCornerShape(22.dp)
            Box(
                Modifier.weight(1f).height(44.dp).clip(fieldShape).background(Color(0xFF1C1C1E))
                    .border(1.dp, if (wrong) Color(0xFFFF453A) else Color(0xFF3A3A3E), fieldShape)
                    .clickable(onClick = onFieldTap).padding(horizontal = 16.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (typed.isEmpty()) {
                        Text("Cevap yaz…", color = Color(0xFF6E6E73), fontSize = 15.sp)
                    } else {
                        Text(typed, color = Color.White, fontSize = 15.sp, maxLines = 1)
                    }
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.width(1.5.dp).height(20.dp).graphicsLayer { alpha = if (caret > 0.5f) 1f else 0f }.background(Color(0xFF0A84FF)))
                }
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(if (typed.isEmpty()) Color(0xFF3A3A3E) else Color(0xFF0A84FF)).clickable(onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Text("↑", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }
}

/** Gerçek [IslandRoot]: izin aramaları (Cevapla / Reddet) asıl adayla çizilir. TryPhoneShowcase ile aynı yerleşim. */
@Composable
private fun RealIslandHost(mode: OnbIsland, shakeX: Float, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val ctx = LocalContext.current
    val repo = remember { SettingsRepository(ctx.applicationContext) }
    val cfg by repo.flow.collectAsState(initial = IslandSettings())
    val cfgFlow = remember { MutableStateFlow(cfg.effective()) }
    LaunchedEffect(cfg) { cfgFlow.value = cfg.effective() }
    val uiState = remember { MutableStateFlow(IslandUiState()) }
    val lyricsFlow = remember { MutableStateFlow<LyricsState>(LyricsState.Idle) }

    LaunchedEffect(mode) {
        uiState.value = if (mode is OnbIsland.Call) {
            IslandUiState(content = IslandContent.Call(name = mode.perm.callText, isIncoming = true), expanded = true)
        } else IslandUiState()   // kapalı: izinler arası kısa "toparlanma"
    }

    val c = cfg.effective()
    val topV = (PHONE_BASE_TOP_DP + c.offsetYDp).coerceAtLeast(0f)
    Box(Modifier.fillMaxSize().offset(x = (c.offsetXDp + shakeX).dp, y = (topV - c.windowPadDp()).dp)) {
        IslandRoot(
            state = uiState, settingsFlow = cfgFlow, lyricsFlow = lyricsFlow,
            onTap = {}, onCommand = {}, onOutput = {},
            onAnswer = onAnswer, onDecline = onDecline,
        )
    }
}

private fun onbLabel(p: OnbPerm) = when (p) {
    OnbPerm.Access -> "Erişilebilirlik"
    OnbPerm.PostNotif -> "Bildirim gösterme"
    OnbPerm.Overlay -> "Üstte gösterme"
    OnbPerm.Listener -> "Bildirim erişimi"
    OnbPerm.Battery -> "Pil kısıtlaması yok"
    OnbPerm.Autostart -> "Arka planda otomatik başlatma"
}

/** durum: 2 = hazır, 1 = sırada, 0 = bekliyor */
@Composable
private fun OnbChecklist(items: List<Pair<OnbPerm, Int>>, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    val ok = Color(0xFF4CD964)
    Column(
        modifier.fillMaxWidth().clip(shape).background(cs.surface).border(1.dp, cs.outlineVariant, shape)
            .padding(horizontal = 18.dp, vertical = 6.dp),
    ) {
        items.forEach { (perm, st) ->
            Row(
                Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(Modifier.size(10.dp).background(when (st) { 2 -> ok; 1 -> Color(0xFFFFB340); else -> cs.outline }, CircleShape))
                Text(
                    onbLabel(perm), modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (st == 0) cs.onSurfaceVariant else cs.onSurface, fontSize = 14.sp,
                    fontWeight = if (st == 1) FontWeight.SemiBold else FontWeight.Medium,
                )
                Text(
                    when (st) { 2 -> "Hazır"; 1 -> "Sırada"; else -> "Bekliyor" },
                    color = when (st) { 2 -> ok; 1 -> Color(0xFFFFB340); else -> cs.onSurfaceVariant },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

// ------------------------------------------------------------------ 3 boyutlu telefonda şifre ekranı
@Composable
private fun PhoneLockContent(
    pin: String, wrong: Boolean, unlocked: Boolean, shakeX: Float,
    onDigit: (String) -> Unit, onDelete: () -> Unit, appIcon: OnbAppIcon?, onOpened: () -> Unit,
) {
    val ua by animateFloatAsState(if (unlocked) 1f else 0f, tween(650, easing = FastOutSlowInEasing), label = "unlockA")
    val red = Color(0xFFFF6B5E)
    Box(Modifier.fillMaxSize()) {
        // Kilit açılınca ortaya çıkan ana ekran
        if (ua > 0.01f) {
            Box(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = ua; val sc = 1.12f - 0.12f * ua; scaleX = sc; scaleY = sc }
                    .background(Brush.verticalGradient(listOf(Color(0xFF2E6A93), Color(0xFF5B4496), Color(0xFFB2557F)))),
            ) { OnbHomeGrid(appIcon, ua > 0.9f, onOpened) }
        }
        // Şifre ekranı: kilit açılınca yaklaşıp solar
        if (ua < 0.99f) {
            Column(
                Modifier.fillMaxSize()
                    .graphicsLayer { alpha = 1f - ua; val sc = 1f + 0.25f * ua; scaleX = sc; scaleY = sc }
                    .background(Brush.verticalGradient(listOf(Color(0xF01B2A22), Color(0xF00A120E)))),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(118.dp))
                Text("Şifre Gir", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(28.dp))
                Row(Modifier.offset(x = shakeX.dp), horizontalArrangement = Arrangement.spacedBy(30.dp)) {
                    repeat(4) { i ->
                        val edge = if (wrong) red else Color.White
                        Box(
                            Modifier.size(17.dp).clip(CircleShape).border(1.6.dp, edge, CircleShape)
                                .background(if (i < pin.length) edge else Color.Transparent),
                        )
                    }
                }
                Spacer(Modifier.height(70.dp))
                Column(verticalArrangement = Arrangement.spacedBy(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9")).forEach { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) { r.forEach { d -> PinKey(d) { onDigit(d) } } }
                    }
                    PinKey("0") { onDigit("0") }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (pin.isEmpty()) "İptal" else "Sil", color = Color.White, fontSize = 20.sp,
                    modifier = Modifier.padding(bottom = 80.dp).clip(RoundedCornerShape(12.dp))
                        .clickable(enabled = pin.isNotEmpty(), onClick = onDelete).padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        // Ada (kapalı)
        Box(
            Modifier.align(Alignment.TopCenter).padding(top = 14.dp).size(126.dp, 37.dp)
                .clip(RoundedCornerShape(19.dp)).background(Color.Black),
        )
    }
}

@Composable
private fun PinKey(digit: String, onClick: () -> Unit) {
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(digit, color = Color.White, fontSize = 38.sp, fontWeight = FontWeight.Light) }
}

private class OnbAppIcon(val bitmap: ImageBitmap, val adaptive: Boolean)

@Composable
private fun OnbAppIconView(icon: OnbAppIcon?, modifier: Modifier, corner: Dp = 18.dp) {
    Box(modifier.clip(RoundedCornerShape(corner)).background(Color(0xFF1C1C1E))) {
        if (icon != null) {
            Image(
                bitmap = icon.bitmap, contentDescription = null, contentScale = ContentScale.Crop,
                // adaptif simgelerde dış %33'lük taşma payı kırpılır
                modifier = Modifier.fillMaxSize().graphicsLayer { val k = if (icon.adaptive) 1.5f else 1f; scaleX = k; scaleY = k },
            )
        } else OnbLogo(Color(0xFF0A84FF), Modifier.fillMaxSize())
    }
}

/** Telefonun ana ekranı: isimsiz / resimsiz birkaç uygulama + Omni Island. Simgeye dokununca uygulama açılma animasyonu. */
@Composable
private fun OnbHomeGrid(appIcon: OnbAppIcon?, enabled: Boolean, onOpened: () -> Unit) {
    val scope = rememberCoroutineScope()
    val open = remember { Animatable(0f) }
    var tapped by remember { mutableStateOf(false) }
    val pulse by rememberInfiniteTransition(label = "hint").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing)), label = "hintA",
    )
    val colors = listOf(0xFF0A84FF, 0xFF30D158, 0xFFFF9F0A, 0xFFFF453A, 0xFFBF5AF2, 0xFF5AC8FA, 0xFFFFD60A, 0xFFFF375F)
    val omniR = 1
    val omniC = 2
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth.value
        val h = maxHeight.value
        val size = 76f
        val gap = (w - 56f - 4f * size) / 3f
        fun cx(c: Int) = 28f + c * (size + gap)
        fun ry(r: Int) = 120f + r * 120f
        val t = open.value

        Box(Modifier.fillMaxSize().graphicsLayer { val sc = 1f + 0.15f * t; scaleX = sc; scaleY = sc; alpha = 1f - 0.5f * t }) {
            for (r in 0..2) for (c in 0..3) {
                if (r == omniR && c == omniC) continue
                val col = Color(colors[(r * 4 + c) % colors.size])
                Box(
                    Modifier.offset(cx(c).dp, ry(r).dp).size(size.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.6f)))),
                )
            }
            // dock
            for (c in 0..3) {
                val col = Color(colors[(c * 2 + 1) % colors.size])
                Box(
                    Modifier.offset(cx(c).dp, (h - 170f).dp).size(size.dp).clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(col, col.copy(alpha = 0.6f)))),
                )
            }
            // Omni Island: dokunma ipucu halkası + simge + yazı
            if (enabled && !tapped) {
                Box(
                    Modifier.offset(cx(omniC).dp, ry(omniR).dp).size(size.dp)
                        .graphicsLayer { val sc = 1f + 0.3f * pulse; scaleX = sc; scaleY = sc; alpha = 1f - pulse }
                        .border(2.dp, Color.White, RoundedCornerShape(18.dp)),
                )
            }
            OnbAppIconView(
                appIcon,
                Modifier.offset(cx(omniC).dp, ry(omniR).dp).size(size.dp).clickable(enabled = enabled && !tapped) {
                    tapped = true
                    scope.launch {
                        open.animateTo(1f, tween(480, easing = FastOutSlowInEasing))
                        delay(120)
                        onOpened()
                    }
                },
            )
            Box(
                Modifier.offset((cx(omniC) - 14f).dp, (ry(omniR) + 82f).dp).width((size + 28f).dp),
                contentAlignment = Alignment.Center,
            ) { Text("Omni Island", color = Color.White, fontSize = 14.sp, maxLines = 1, softWrap = false) }
        }

        // Uygulama açılma animasyonu: simge büyüyerek ekranı kaplar
        if (t > 0.001f) {
            val x = cx(omniC) * (1f - t)
            val y = ry(omniR) * (1f - t)
            Box(
                Modifier.offset(x.dp, y.dp).size((size + (w - size) * t).dp, (size + (h - size) * t).dp)
                    .clip(RoundedCornerShape((18f + 28f * t).dp)).background(Color.Black),
            ) {
                OnbAppIconView(appIcon, Modifier.align(Alignment.Center).size((size + 34f * t).dp), (18f + 6f * t).dp)
            }
        }
    }
}
