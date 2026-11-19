package com.example.dynamicisland

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.PowerManager
import android.widget.Toast
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            IslandTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // İlk açılışta açılış (onboarding) ekranı; bittikten sonra normal uygulama (Kurulum sayfası dahil) açılır
                    val prefs = remember { getSharedPreferences("omni_onboarding", Context.MODE_PRIVATE) }
                    var onboarded by remember { mutableStateOf(prefs.getBoolean("onboarding_done", false)) }
                    // Açılış animasyonu sonrası: "Omni Island" yazısı ekranın ortasında belirir, sonra uygulamanın sol üstündeki başlığın yerine uçar
                    var introFlight by remember { mutableStateOf(false) }      // yazı ekranın üstünde uçuyor (gerçek başlık gizli)
                    var flightGo by remember { mutableStateOf(false) }         // uygulama açıldı, uçuş başlayabilir
                    var titlePos by remember { mutableStateOf<Offset?>(null) } // gerçek başlığın ekrandaki sol üst köşesi (px)
                    val titleIn = remember { Animatable(0f) }                  // 0 -> 1: yazı ortada belirir
                    val flight = remember { Animatable(0f) }                   // 0: ortada büyük, 1: başlığın yerinde
                    val flightScope = rememberCoroutineScope()
                    // "Omni Island" yazısının üstünde: ada belirir, beyaz çizgi etrafını sarar, kamera çentiğinin yerine kayar, çizgi söner
                    var islandIntro by remember { mutableStateOf(false) }
                    val ringLine = remember { Animatable(0f) }    // 0 -> 1: beyaz çizgi adanın etrafını sarar
                    val ringMove = remember { Animatable(0f) }    // 0 -> 1: ada gerçek konumuna gider
                    val ringFade = remember { Animatable(0f) }    // 0 -> 1: çizgi ve ada söner
                    val appCtx = this@MainActivity
                    // İlk girişte (açılış/onboarding sürerken) durum çubuğu ve alt çubuk gizlenir; ada yerleşim animasyonu bitince geri gelir
                    val barsController = remember {
                        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        }
                    }
                    LaunchedEffect(Unit) {
                        if (!onboarded) {
                            if (Build.VERSION.SDK_INT >= 28) {
                                window.attributes = window.attributes.apply {
                                    layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                                }
                            }
                            barsController.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        }
                    }
                    DisposableEffect(Unit) {
                        onDispose { barsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) }
                    }
                    LaunchedEffect(islandIntro) {
                        if (!islandIntro) return@LaunchedEffect
                        ringLine.snapTo(0f); ringMove.snapTo(0f); ringFade.snapTo(0f)
                        ringLine.animateTo(1f, tween(1500, easing = androidx.compose.animation.core.LinearEasing))
                        delay(150)
                        ringMove.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
                        delay(100)
                        ringFade.animateTo(1f, tween(500, easing = androidx.compose.animation.core.LinearEasing))
                        islandIntro = false
                        barsController.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())   // ada yerine oturdu: çubuklar geri gelir
                    }
                    LaunchedEffect(flightGo, titlePos != null) {
                        if (flightGo && titlePos != null) {
                            flight.animateTo(1f, tween(850, easing = FastOutSlowInEasing))
                            introFlight = false
                        }
                    }
                    Box(Modifier.fillMaxSize()) {
                        androidx.compose.animation.Crossfade(
                            targetState = onboarded, animationSpec = tween(450), label = "onbFade",
                        ) { done ->
                            if (done) {
                                AppRoot(titleVisible = !introFlight, onTitlePos = { titlePos = it })
                            } else {
                                OnboardingScreen(
                                    onTitleShow = {
                                        introFlight = true
                                        islandIntro = true
                                        // Yazı gelince adayı otomatik başlat (Kurulum'daki Başlat'a basmaya gerek yok)
                                        if (appCtx.hasOverlay() && appCtx.hasListener() && appCtx.hasPostNotif() && appCtx.hasAccessibility()) {
                                            runCatching {
                                                ContextCompat.startForegroundService(appCtx, Intent(appCtx, IslandOverlayService::class.java))
                                                IslandNotificationListener.republish(appCtx, force = true)
                                            }
                                        }
                                        flightScope.launch { titleIn.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
                                    },
                                    onFinished = {
                                        prefs.edit().putBoolean("onboarding_done", true).apply()
                                        onboarded = true
                                        flightGo = true
                                    },
                                )
                            }
                        }
                        if (introFlight) {
                            val cs = MaterialTheme.colorScheme
                            var rootSize by remember { mutableStateOf(IntSize.Zero) }
                            var textSize by remember { mutableStateOf(IntSize.Zero) }
                            Box(Modifier.fillMaxSize().onSizeChanged { rootSize = it }) {
                                Text(
                                    "Omni Island", color = cs.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                                    letterSpacing = (-0.5).sp, maxLines = 1, softWrap = false,
                                    modifier = Modifier
                                        .offset {
                                            // Yazı hep son konumunda (başlığın yerinde) yerleşir; başlangıç konumu graphicsLayer kaydırmasıyla verilir
                                            val s0 = 1.9f
                                            val x0 = rootSize.width / 2f - textSize.width * s0 / 2f
                                            val y0 = rootSize.height / 2f - textSize.height * s0 / 2f
                                            IntOffset((titlePos?.x ?: x0).roundToInt(), (titlePos?.y ?: y0).roundToInt())
                                        }
                                        .onSizeChanged { textSize = it }
                                        .graphicsLayer {
                                            transformOrigin = TransformOrigin(0f, 0f)
                                            val p = flight.value
                                            val s0 = 1.9f
                                            val s = s0 + (1f - s0) * p
                                            val x0 = rootSize.width / 2f - textSize.width * s0 / 2f
                                            val y0 = rootSize.height / 2f - textSize.height * s0 / 2f
                                            val x1 = titlePos?.x ?: x0
                                            val y1 = titlePos?.y ?: y0
                                            scaleX = s; scaleY = s
                                            translationX = (x0 - x1) * (1f - p)
                                            translationY = (y0 - y1) * (1f - p)
                                            alpha = titleIn.value
                                        },
                                )
                            }
                        }
                        if (islandIntro) {
                            val view = androidx.compose.ui.platform.LocalView.current
                            Canvas(Modifier.fillMaxSize()) {
                                val d = density
                                // Gerçek kamera çentiği (yoksa tipik bir ada konumu)
                                val cut = runCatching {
                                    if (Build.VERSION.SDK_INT >= 28) view.rootWindowInsets?.displayCutout?.boundingRectTop else null
                                }.getOrNull()
                                val pad = 5.dp.toPx()
                                val tw: Float; val th: Float; val tcx: Float; val tcy: Float
                                if (cut != null && !cut.isEmpty) {
                                    tw = cut.width() + 2f * pad; th = cut.height() + 2f * pad
                                    tcx = cut.exactCenterX(); tcy = cut.exactCenterY()
                                } else {
                                    tw = 96.dp.toPx(); th = 32.dp.toPx(); tcx = size.width / 2f; tcy = 22.dp.toPx()
                                }
                                // Başlangıç: "Omni Island" yazısının üstü
                                val sw = 150.dp.toPx(); val sh = 46.dp.toPx()
                                val scx = size.width / 2f; val scy = size.height / 2f - 105.dp.toPx()
                                val m = FastOutSlowInEasing.transform(ringMove.value)
                                val cx = scx + (tcx - scx) * m; val cy = scy + (tcy - scy) * m
                                val w = sw + (tw - sw) * m; val h = sh + (th - sh) * m
                                val a = 1f - ringFade.value
                                drawIntroRing(cx, cy, w, h, ringLine.value, a, 2.4.dp.toPx(), 26.dp.toPx())
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Uygulamayı (ayarlar sayfasının üstüne) öne getirir. */
private fun bringAppToFront(ctx: Context) {
    runCatching {
        ctx.startActivity(
            Intent(ctx, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
        )
    }
}

/** OnboardingScreen.kt için: private yardımcılara köprü. */
internal fun onboardingBringToFront(ctx: Context) = bringAppToFront(ctx)
internal fun onboardingOpenAutostart(ctx: Context) = openAutostartSettings(ctx)

private fun Context.hasOverlay() = Settings.canDrawOverlays(this)
private fun Context.hasListener() =
    NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)
private fun Context.hasAccessibility(): Boolean {
    val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
    return enabled.contains("$packageName/${IslandAccessibilityService::class.java.name}")
}
/** Ayarlarda açık VE servis gerçekten bağlı mı? Servis çökerse ayarlarda açık görünür ama bağlı olmaz. */
private fun a11yConnected() = IslandAccessibilityService.instance != null
private fun Context.canHealA11y() =
    checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
private fun Context.ignoringBattery() =
    runCatching { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) }.getOrDefault(true)

private fun copyText(ctx: Context, label: String, text: String) {
    runCatching {
        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(ctx, "Kopyalandı", Toast.LENGTH_SHORT).show()
    }
}

private const val ADB_GRANT = "adb shell pm grant com.example.dynamicisland android.permission.WRITE_SECURE_SETTINGS"

/** Üreticiye özel "otomatik başlatma / arka planda çalışma" ekranları (paket to sınıf). Hangisi varsa o açılır. */
private val AUTOSTART_TARGETS = listOf(
    "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",             // Xiaomi / Redmi / POCO
    "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",                    // Samsung
    "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity",
    "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",       // Oppo / Realme
    "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
    "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
    "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",       // Vivo / iQOO
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
    "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
    "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",  // Huawei / Honor
    "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
    "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
    "com.oneplus.security" to "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",         // OnePlus
    "com.asus.mobilemanager" to "com.asus.mobilemanager.autostart.AutoStartActivity",                     // Asus
    "com.transsion.phonemaster" to "com.cyin.himgr.autostart.AutoStartActivity",                          // Tecno / Infinix / itel
    "com.evenwell.powersaving.g3" to "com.evenwell.powersaving.g3.exception.PowerSaverExceptionActivity", // Nokia / HMD
)

/**
 * Arka planda otomatik başlatma / çalışma izni ekranını açar. Bu izin ayarı herkesin telefonunda farklı yerde olduğundan
 * bilinen üretici ekranlarını sırayla dener; hiçbiri açılmazsa uygulama bilgisine götürür.
 */
private fun openAutostartSettings(ctx: Context) {
    val candidates = buildList {
        add(Intent("miui.intent.action.OP_AUTO_START").addCategory(Intent.CATEGORY_DEFAULT))
        AUTOSTART_TARGETS.forEach { (pkg, cls) -> add(Intent().setComponent(ComponentName(pkg, cls))) }
    }
    for (i in candidates) {
        val opened = runCatching { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        if (opened) {
            Toast.makeText(ctx, "Dynamic Island için otomatik başlatmaya / arka planda çalışmaya izin ver", Toast.LENGTH_LONG).show()
            return
        }
    }
    Toast.makeText(
        ctx, "Uygulama bilgisi → Pil / Arka plan / Otomatik başlatma bölümünden izin ver", Toast.LENGTH_LONG,
    ).show()
    runCatching {
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

/**
 * Çökmüş erişilebilirlik servisini kapatıp açar (sistemin "hatalı çalışıyor" işaretini temizler).
 * Yalnızca WRITE_SECURE_SETTINGS izni adb ile bir kez verildiyse çalışır.
 */
private suspend fun healAccessibility(ctx: Context): Boolean {
    if (!ctx.canHealA11y()) return false
    val me = ComponentName(ctx, IslandAccessibilityService::class.java)
    val cr = ctx.contentResolver
    return runCatching {
        val cur = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val others = cur.split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
        delay(800)
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others + me.flattenToString()).joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        true
    }.getOrDefault(false)
}

private fun Context.hasPostNotif() =
    Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == 0

@Composable
private fun AppRoot(titleVisible: Boolean = true, onTitlePos: (Offset) -> Unit = {}) {
    val cs = MaterialTheme.colorScheme
    val ctx = LocalContext.current
    // Dört izin de verilmişse Ana Sayfa (0), eksik varsa Kurulum (1) sekmesinde açılır
    val startTab = remember {
        if (ctx.hasOverlay() && ctx.hasListener() && ctx.hasPostNotif() && ctx.hasAccessibility()) 0 else 1
    }
    var tab by remember { mutableIntStateOf(startTab) }       // çubuktaki seçim (anında değişir)
    var shown by remember { mutableIntStateOf(startTab) }     // ekranda gösterilen sayfa (çubuk animasyonu bitince değişir)
    // Ayarlar / Sözler sayfaları ağır; çubuk animasyonu ile aynı karede açılırsa takılıyor.
    // Önce çubuk akıcı kayar, sayfa hemen ardından değişir.
    LaunchedEffect(tab) {
        if (shown != tab) {
            delay(280)
            shown = tab
        }
    }
    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBarsIgnoringVisibility)) {
        Column(
            Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    "Omni Island", color = cs.onBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
                    modifier = Modifier
                        .onGloballyPositioned { onTitlePos(it.positionInRoot()) }
                        .graphicsLayer { alpha = if (titleVisible) 1f else 0f },
                )
                Text("Kamera çevresinde canlı ada", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
            // Alttaki yüzen çubuğun altında kalmasın diye içeriğe alttan boşluk bırakılır
            Box(Modifier.weight(1f).padding(bottom = FloatingTabsHeight + 24.dp)) {
                when (shown) {
                    0 -> HomeScreen()
                    1 -> SetupScreen()
                    2 -> key(2) { SettingsScreen(lyricsPage = false) }
                    else -> key(2) { SettingsScreen(lyricsPage = true) }
                }
            }
        }
        BottomFloatingTabs(
            labels = listOf("Ana Sayfa", "Kurulum", "Ayarlar", "Sözler"),
            glyphs = listOf("⌂", "◉", "≡", "Aa"),   // Ana Sayfa, Kurulum, Ayarlar, Sözler
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }
}

private val FloatingTabsHeight = 68.dp

/** Servis (ada) şu an çalışıyor mu? */
@Suppress("DEPRECATION")
private fun Context.islandServiceRunning(): Boolean = runCatching {
    getSystemService(android.app.ActivityManager::class.java)
        .getRunningServices(Int.MAX_VALUE)
        .any { it.service.className == IslandOverlayService::class.java.name }
}.getOrDefault(false)

/** Ana Sayfa: "Dene" kartı. Dene tuşu tam ekran, kaydırmalı önizleme ekranını açar (TryScreen.kt). */
@Composable
private fun HomeScreen() {
    val ctx = LocalContext.current
    var tryOpen by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        SectionCard("Dene", "Adanın görünümlerini ve animasyonlarını önizle") {
            IslandActionButton("Dene", { tryOpen = true }, tonal = false)
        }
        Spacer(Modifier.height(8.dp))
    }

    if (tryOpen) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { tryOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false,
            ),
        ) {
            TryScreen(isIslandRunning = { ctx.islandServiceRunning() }, onClose = { tryOpen = false })
        }
    }
}

/**
 * Altta yüzen sekme çubuğu.
 * - Dokununca o sekmeye geçer.
 * - Parmağı çubuk üzerinde sağa/sola sürükleyince seçici parmağı takip eder, bırakınca o sekme açılır.
 * - Basılı tutulan sekme hafifçe büyür.
 */
@Composable
private fun BottomFloatingTabs(
    labels: List<String>,
    glyphs: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val barShape = RoundedCornerShape(32.dp)
    val pillShape = RoundedCornerShape(26.dp)
    val count = labels.size.coerceAtLeast(1)
    var held by remember { mutableIntStateOf(-1) }       // parmağın altındaki sekme (-1 = yok)
    val target = if (held >= 0) held else selected.coerceIn(0, count - 1)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(FloatingTabsHeight)
            .clip(barShape)
            .background(cs.surface.copy(alpha = 0.94f))
            .border(1.dp, cs.outlineVariant, barShape)
            .pointerInput(count) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    fun indexAt(x: Float) = ((x / size.width) * count).toInt().coerceIn(0, count - 1)
                    held = indexAt(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        held = indexAt(change.position.x)
                        change.consume()
                    } while (event.changes.any { it.pressed })
                    val finalIndex = held
                    held = -1
                    if (finalIndex >= 0) onSelect(finalIndex)
                }
            }
            .padding(6.dp),
    ) {
        val segW = maxWidth / count
        val x by animateDpAsState(
            segW * target,
            spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "floatTabX",
        )
        val pillScale by animateFloatAsState(
            if (held >= 0) 1.07f else 1f,
            spring(dampingRatio = 0.7f, stiffness = 500f), label = "floatTabScale",
        )
        // Kayan seçici
        Box(
            Modifier.offset(x = x).width(segW).fillMaxHeight()
                .graphicsLayer { scaleX = pillScale; scaleY = pillScale }
                .clip(pillShape).background(cs.primaryContainer),
        )
        Row(Modifier.fillMaxSize()) {
            labels.forEachIndexed { i, label ->
                val sel = i == target
                val fg by animateColorAsState(if (sel) cs.onPrimaryContainer else cs.onSurfaceVariant, tween(200), label = "floatTabFg$i")
                val itemScale by animateFloatAsState(
                    if (sel && held >= 0) 1.1f else 1f,
                    spring(dampingRatio = 0.7f, stiffness = 500f), label = "floatItemScale$i",
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight()
                        .graphicsLayer { scaleX = itemScale; scaleY = itemScale },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    TabGlyph(glyphs.getOrElse(i) { "•" }, fg)
                    Text(label, color = fg, fontSize = 11.sp, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}

/**
 * Sekme simgesi. Tüm simgeler aynı yükseklikte (28 dp) bir kutuya konur, böylece alttaki yazılar hizalı kalır.
 * "◉" ve "≡" yazı karakteri yerine çizilir (font'a göre küçük kalıyordu); "Aa" yazı olarak kalır.
 */
@Composable
private fun TabGlyph(glyph: String, color: Color) {
    Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
        when (glyph) {
            "⌂" -> Canvas(Modifier.size(26.dp)) {
                val sw = 2.4.dp.toPx()
                val w = size.width
                val h = size.height
                val roof = Path().apply {
                    moveTo(w * 0.06f, h * 0.50f)
                    lineTo(w * 0.50f, h * 0.10f)
                    lineTo(w * 0.94f, h * 0.50f)
                }
                val body = Path().apply {
                    moveTo(w * 0.18f, h * 0.42f)
                    lineTo(w * 0.18f, h * 0.88f)
                    lineTo(w * 0.82f, h * 0.88f)
                    lineTo(w * 0.82f, h * 0.42f)
                }
                val door = Path().apply {
                    moveTo(w * 0.42f, h * 0.88f)
                    lineTo(w * 0.42f, h * 0.62f)
                    lineTo(w * 0.58f, h * 0.62f)
                    lineTo(w * 0.58f, h * 0.88f)
                }
                val st = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(roof, color, style = st)
                drawPath(body, color, style = st)
                drawPath(door, color, style = st)
            }
            "◉" -> Canvas(Modifier.size(24.dp)) {
                val sw = 2.4.dp.toPx()
                drawCircle(color, radius = size.minDimension / 2f - sw / 2f, style = Stroke(width = sw))
                drawCircle(color, radius = 4.6.dp.toPx())
            }
            "≡" -> Canvas(Modifier.size(width = 26.dp, height = 24.dp)) {
                val sw = 2.8.dp.toPx()
                val pad = sw / 2f
                listOf(0.2f, 0.5f, 0.8f).forEach { f ->
                    drawLine(
                        color, Offset(pad, size.height * f), Offset(size.width - pad, size.height * f),
                        strokeWidth = sw, cap = StrokeCap.Round,
                    )
                }
            }
            else -> Text(glyph, color = color, fontSize = 22.sp, fontWeight = FontWeight.Bold, lineHeight = 24.sp)
        }
    }
}

@Composable
private fun SetupScreen() {
    val ctx = LocalContext.current
    var overlay by remember { mutableStateOf(ctx.hasOverlay()) }
    var listener by remember { mutableStateOf(ctx.hasListener()) }
    var a11y by remember { mutableStateOf(ctx.hasAccessibility()) }
    var postNotif by remember { mutableStateOf(ctx.hasPostNotif()) }
    val scope = rememberCoroutineScope()
    var connected by remember { mutableStateOf(a11yConnected()) }
    var battery by remember { mutableStateOf(ctx.ignoringBattery()) }
    var exits by remember { mutableStateOf(CrashLog.exitReasons(ctx)) }
    var crash by remember { mutableStateOf(CrashLog.lastCrash(ctx)) }
    val broken = a11y && !connected          // ayarlarda açık ama servis çalışmıyor

    var winDump by remember { mutableStateOf(IslandAccessibilityService.lastDump) }
    LaunchedEffect(Unit) { while (true) { connected = a11yConnected(); winDump = IslandAccessibilityService.lastDump; delay(1000) } }

    // Ayarlar sayfasına gönderdiğimiz izin: verilir verilmez uygulamayı öne getiririz
    var waiting by remember { mutableStateOf<String?>(null) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlay = ctx.hasOverlay(); listener = ctx.hasListener()
        a11y = ctx.hasAccessibility(); postNotif = ctx.hasPostNotif()
        connected = a11yConnected(); battery = ctx.ignoringBattery()
        exits = CrashLog.exitReasons(ctx); crash = CrashLog.lastCrash(ctx)
        waiting = null                       // kullanıcı zaten geri döndü
    }

    LaunchedEffect(waiting) {
        val w = waiting ?: return@LaunchedEffect
        // Arka plan iş parçacığında sık (60 ms) kontrol: izin verildiği anda öne getir
        withContext(Dispatchers.Default) {
            val end = SystemClock.elapsedRealtime() + 180_000L
            while (SystemClock.elapsedRealtime() < end) {
                delay(60)
                val granted = when (w) {
                    "overlay" -> ctx.hasOverlay()
                    "listener" -> ctx.hasListener()
                    else -> ctx.hasAccessibility()
                }
                if (granted) { bringAppToFront(ctx); break }
            }
        }
        waiting = null
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { postNotif = it }

    // Dört izin de verilmeden "hazır" denmez ve Başlat açılmaz
    val missing = buildList {
        if (!overlay) add("Üstte gösterme")
        if (!listener) add("Bildirim erişimi")
        if (!postNotif) add("Bildirim gösterme")
        if (!a11y) add("Erişilebilirlik")
    }
    val canStart = missing.isEmpty()

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            StatusBanner(missing)

            if (broken) {
                SectionCard("Erişilebilirlik servisi çalışmıyor", "Ayarlarda açık görünüyor ama sistem servisi durdurmuş") {
                    Text(
                        "Uygulama süreci kapandığında Android servisi \"hatalı\" diye işaretler ve kendiliğinden geri açmaz. " +
                            "Sözlerin adadan çıkması ve adaya dokunma bu servise bağlı. En sık neden: arka planda otomatik başlatma izninin kapalı olması.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp,
                    )
                    IslandActionButton(if (ctx.canHealA11y()) "Otomatik onar" else "Erişilebilirlik ayarını aç", {
                        scope.launch {
                            if (healAccessibility(ctx)) { delay(1200); connected = a11yConnected() }
                            else {
                                Toast.makeText(ctx, "Dynamic Island'ı kapatıp tekrar aç", Toast.LENGTH_LONG).show()
                                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    })
                    IslandActionButton("Otomatik başlatma iznini aç", { openAutostartSettings(ctx) })
                }
            }

            SectionCard("İzinler", "Her izin Ayarlar'da tek dokunuşla verilir") {
                PermRow("Üstte gösterme", "Adayı diğer uygulamaların üzerinde çizer", overlay, true) {
                    waiting = "overlay"
                    ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
                }
                SectionDivider()
                PermRow("Bildirim erişimi", "Bildirimleri ve müzik oturumunu okur", listener, true) {
                    waiting = "listener"
                    ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                SectionDivider()
                PermRow("Bildirim gösterme", "Servis bildirimi için gerekli", postNotif, true) {
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                SectionDivider()
                PermRow(
                    "Erişilebilirlik",
                    if (broken) "Açık görünüyor ama çalışmıyor — Onar'a dokun" else "Adaya dokunabilmek ve sözlerin adadan çıkması için gerekli",
                    a11y && connected, true, action = if (broken) "Onar" else "Aç",
                ) {
                    if (broken) {
                        scope.launch {
                            if (healAccessibility(ctx)) { delay(1200); connected = a11yConnected() }
                            else {
                                Toast.makeText(ctx, "Dynamic Island'ı kapatıp tekrar aç", Toast.LENGTH_LONG).show()
                                waiting = "a11y"
                                ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                            }
                        }
                    } else {
                        waiting = "a11y"
                        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }
                }
                SectionDivider()
                PermRow("Pil kısıtlaması yok", "Önerilir: sistemin uygulamayı arka planda kapatmasını azaltır", battery, false) {
                    runCatching {
                        ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
                    }.onFailure { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                }
                SectionDivider()
                PermRow(
                    "Arka planda otomatik başlatma",
                    "Önemli: kapalıysa uygulamayı son uygulamalardan silince erişilebilirlik servisi durur. Açıp \"İzin ver\" yap",
                    false, false, action = "Ayarı aç",
                ) { openAutostartSettings(ctx) }
            }

            SectionCard("İpucu", "Sideload (APK) kurulumlarında") {
                Text(
                    "Android 13+ üzerinde bildirim erişimi ve erişilebilirlik anahtarları gri görünebilir. " +
                        "Uygulama bilgisi → ⋮ → \"Kısıtlı ayarlara izin ver\" seçeneğini açıp tekrar dene.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 19.sp,
                )
                IslandActionButton("Uygulama bilgisini aç", {
                    ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                })
            }
            SectionCard("Tanılama", "Uygulama neden kapanıyor?") {
                val cs2 = MaterialTheme.colorScheme
                Text(
                    "Erişilebilirlik: " + (if (!a11y) "kapalı" else if (connected) "bağlı" else "ÇALIŞMIYOR") +
                        "   Bildirim erişimi: " + (if (listener) "açık" else "kapalı"),
                    color = cs2.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
                if (exits.isEmpty()) {
                    Text("Kayıtlı bir süreç sonlanması yok.", color = cs2.onSurfaceVariant, fontSize = 12.sp)
                } else {
                    Text("Son kapanmalar:\n" + exits.joinToString("\n"), color = cs2.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
                }
                crash?.let {
                    Text("Son çökme kaydı:\n" + it.take(900), color = Color(0xFFFF6B5E), fontSize = 11.sp, lineHeight = 15.sp)
                }
                if (winDump.isNotEmpty()) {
                    Text("Son asistan penceresi kaydı:\n" + winDump.take(900), color = cs2.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
                }
                IslandActionButton("Tanılamayı kopyala", {
                    copyText(ctx, "diag", buildString {
                        appendLine("a11y enabled=$a11y connected=$connected listener=$listener battery=$battery")
                        exits.forEach { appendLine(it) }
                        crash?.let { appendLine(it) }
                        if (winDump.isNotEmpty()) appendLine(winDump)
                    })
                })
                if (!ctx.canHealA11y()) {
                    Text(
                        "Otomatik onarım için (bir kez, bilgisayardan):\n$ADB_GRANT",
                        color = cs2.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                    IslandActionButton("adb komutunu kopyala", { copyText(ctx, "adb", ADB_GRANT) })
                }
                if (crash != null) IslandActionButton("Çökme kaydını sil", { CrashLog.clear(ctx); crash = null })
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            ActionBtn(
                "Başlat", primary = true, enabled = canStart, modifier = Modifier.weight(1f),
                onClick = {
                    scope.launch {
                        // Servis çökmüşse önce onar (izin verilmişse), sonra adayı başlat
                        if (ctx.hasAccessibility() && !a11yConnected() && healAccessibility(ctx)) { delay(1200); connected = a11yConnected() }
                        ContextCompat.startForegroundService(ctx, Intent(ctx, IslandOverlayService::class.java))
                        IslandNotificationListener.republish(ctx, force = true)   // servis zaten açıksa da mevcut müziği tekrar yayınla
                    }
                },
            )
            ActionBtn(
                "Durdur", primary = false, enabled = true, modifier = Modifier.weight(0.6f),
                onClick = { ctx.stopService(Intent(ctx, IslandOverlayService::class.java)) },
            )
        }
    }
}

@Composable
private fun ActionBtn(text: String, primary: Boolean, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val bg = when { !enabled -> cs.surfaceVariant; primary -> cs.primary; else -> cs.surfaceVariant }
    val fg = when { !enabled -> cs.onSurfaceVariant.copy(alpha = 0.5f); primary -> cs.onPrimary; else -> cs.onSurface }
    Box(
        modifier.height(54.dp).clip(shape).background(bg)
            .border(1.dp, if (primary && enabled) Color.Transparent else cs.outline, shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun StatusBanner(missing: List<String>) {
    val cs = MaterialTheme.colorScheme
    val ready = missing.isEmpty()
    val title = if (ready) "Her şey hazır" else "Eksik izinler var"
    val desc = if (ready) "Başlat'a dokunarak adayı çalıştırabilirsin."
        else "Başlat'ı açmak için şunları ver: " + missing.joinToString(", ") + "."
    val dot = when { ready -> Color(0xFF4CD964); missing.size == 1 -> Color(0xFFFFB340); else -> Color(0xFFFF6B5E) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape)
            .background(if (ready) cs.primaryContainer.copy(alpha = 0.55f) else cs.surface)
            .border(1.dp, if (ready) cs.primary.copy(alpha = 0.35f) else cs.outlineVariant, shape)
            .padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(12.dp).background(dot, CircleShape))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = cs.onSurface, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp)
        }
    }
}

@Composable
private fun PermRow(title: String, desc: String, granted: Boolean, required: Boolean, action: String = "Aç", onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val ok = Color(0xFF4CD964)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier.size(10.dp).background(
                when { granted -> ok; required -> Color(0xFFFFB340); else -> cs.outline }, CircleShape
            )
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = cs.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = cs.onSurfaceVariant, fontSize = 12.sp, lineHeight = 16.sp)
        }
        if (granted) {
            Text(
                "Hazır", color = ok, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(ok.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        } else {
            Text(
                action, color = cs.onPrimaryContainer, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primaryContainer)
                    .clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
