package com.example.dynamicisland

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.app.NotificationManagerCompat

/**
 * Üç iş yapar:
 * 1) Adanın penceresine ev sahipliği yapar: TYPE_ACCESSIBILITY_OVERLAY durum çubuğunun üstünde
 *    durur ve dokunuş alır (normal uygulama overlay'i durum çubuğunun altında kalır).
 * 2) Yedek bildirim olayı: yalnızca TYPE_NOTIFICATION_STATE_CHANGED dinler. Bildirim erişimi
 *    (NotificationListener) açıksa çift bildirim olmaması için sessiz kalır.
 * 3) Google Asistan / Gemini algılama: alttaki asistan barı açılınca / kapanınca [IslandEvent.AssistantChanged] yayınlar.
 */
class IslandAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: IslandAccessibilityService? = null
        /** Servis bağlanınca / kopunca overlay servisine haber verir (ana thread). */
        @Volatile var onChanged: (() -> Unit)? = null
        /** Son asistan penceresi kaydı (Kurulum > Tanılama'da görünür): algılama sorunlarını incelemek için. */
        @Volatile var lastDump: String = ""
        /** Şu an ön plandaki (klavye/sistem hariç) uygulamanın paketi. */
        @Volatile var currentFg: String? = null

        /** Asistan / Gemini arayüzünü barındıran paketler. Cihazında başka bir asistan varsa buraya ekle. */
        private val ASSISTANT_PKGS = setOf(
            "com.google.android.googlequicksearchbox",   // Google uygulaması (Asistan + Gemini kaplaması)
            "com.google.android.apps.bard",              // Gemini uygulaması
            "com.google.android.apps.googleassistant",
        )
        /** Asistan açıkken de olay üretebilen, asistanın kapandığı anlamına GELMEYEN paketler. */
        private val IGNORED_PKGS = setOf("com.android.systemui")

        private val REC_PKG_HINTS = listOf("screenrecord", "screenrec", "screen_record")
        private val REC_TIME = Regex("^(\\d{1,2}:)?\\d{1,2}:\\d{2}$")
        private fun isRecPkg(p: String?): Boolean = p != null && REC_PKG_HINTS.any { p.lowercase().contains(it) }

        /** Adadaki durdur düğmesi: sistem kaydedicinin yüzen çubuğundaki durdur düğmesine dokunur. */
        fun stopScreenRec(): Boolean = instance?.doStopScreenRec() ?: false
    }

    private class Win(val w: AccessibilityWindowInfo, val pkg: String?, val r: Rect)

    private val handler = Handler(Looper.getMainLooper())
    private var assistantOn = false
    private var eventSaysOn = false      // pencere listesi alınamazsa olay tabanlı yedek
    private val evLog = ArrayDeque<String>()

    private val check = object : Runnable {
        override fun run() {
            runCatching {
                setAssistant(assistantVisible())
                // Asistan kapanırken her zaman olay gelmeyebilir: açıkken kısa aralıkla kontrol et
                if (assistantOn) handler.postDelayed(this, 400)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Pencere listesini okuyabilmek için (XML'e ek olarak, çalışma anında da)
        runCatching {
            serviceInfo?.let { info ->
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                info.notificationTimeout = 0      // XML'de bir bekleme tanımlıysa olaylar gecikmeli gelir; çıkış animasyonu hemen başlasın
                serviceInfo = info
            }
        }
        instance = this
        notifyChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        currentFg = null
        stopAssistantWatch()
        instance = null
        notifyChanged()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopAssistantWatch()
        instance = null
        super.onDestroy()
    }

    private fun stopAssistantWatch() {
        handler.removeCallbacks(check)
        eventSaysOn = false
        runCatching { setAssistant(false) }
    }

    /**
     * Overlay servisinin geri çağrısında oluşan HİÇBİR hata buraya sızmamalı: erişilebilirlik servisi ile
     * overlay servisi aynı süreçte çalışır; yakalanmayan tek bir istisna süreci çökertir ve sistem
     * erişilebilirlik servisini "Bu hizmet hatalı çalışıyor / Çalışmıyor" diye kalıcı işaretler.
     */
    private fun notifyChanged() {
        runCatching { onChanged?.invoke() }
    }

    private fun setAssistant(on: Boolean) {
        if (on == assistantOn) return
        assistantOn = on
        IslandBus.post(IslandEvent.AssistantChanged(on))
    }

    private fun isAssist(p: String?) = p != null && p in ASSISTANT_PKGS

    // ---- Ön plandaki uygulama takibi (müzik uygulamasından çıkış animasyonu için)
    private var fgPkg: String? = null

    /** Klavye, sistem diyalogları, asistan gibi "uygulamadan çıkış" sayılmayan geçici pencereler. */
    private fun isTransientPkg(p: String): Boolean =
        p == packageName || p in IGNORED_PKGS || isAssist(p) || p == "android" ||
            p.contains("permissioncontroller") || p == "com.android.intentresolver" ||
            p in imePackages()

    private fun imePackages(): Set<String> = runCatching {
        getSystemService(InputMethodManager::class.java).enabledInputMethodList.map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    private var fgRetries = 0
    private val FG_RETRY_MS = 35L     // eskiden 90 ms: ön plan değişimini daha sık kontrol et
    private val FG_RETRIES = 16       // toplam ~0,55 sn boyunca denenir
    private val fgCheck = object : Runnable {
        override fun run() {
            val changed = runCatching { checkForeground() }.getOrDefault(true)
            // Hızlı geçişte aktif pencere henüz güncellenmemiş olabilir: değişiklik görülmediyse kısa aralıkla tekrar bak
            if (!changed && fgRetries > 0) {
                fgRetries--
                handler.postDelayed(this, FG_RETRY_MS)
            }
        }
    }

    /**
     * Olay sırası güvenilmez (launcher uygulama açılırken de geç olay üretebilir). Bu yüzden olay gelince kısa
     * bir gecikmeyle GERÇEK aktif pencereye bakılır; yalnızca uygulama gerçekten değiştiyse olay yayınlanır.
     */
    private fun trackForeground() {
        handler.removeCallbacks(fgCheck)
        fgRetries = FG_RETRIES
        handler.postDelayed(fgCheck, 8)
    }

    private fun activePkg(): String? {
        val fromWindows = runCatching {
            windows.firstOrNull { it.isActive && it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.root?.packageName?.toString()
        }.getOrNull()
        return fromWindows ?: runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
    }

    /** true: ön plan değişti (ya da bakılacak bir şey kalmadı); false: henüz değişiklik görülmedi. */
    private fun checkForeground(): Boolean {
        val cur = activePkg() ?: return false
        if (isTransientPkg(cur)) return false
        val old = fgPkg
        if (old == cur) return false
        fgPkg = cur
        currentFg = cur
        if (old != null) IslandBus.post(IslandEvent.AppSwitched(old, cur))
        return true
    }

    /**
     * Gemini / Asistan barı = olay sınıfı "Floaty" (FloatyActivity) olan pencere. Normal Google uygulaması
     * GoogleAppActivity ile açıldığı için bar sayılmaz. Pencere boyutuna bakılmaz (navigasyon çubuğu,
     * tam ekran şeffaf pencere gibi cihaz farkları yüzünden güvenilmez).
     * Bar açıkken pencere listesinden Google penceresi kaybolduysa bar kapanmış demektir.
     */
    private fun assistantVisible(): Boolean {
        val ws = runCatching { windows }.getOrNull()
        if (ws.isNullOrEmpty()) return eventSaysOn
        val dm = resources.displayMetrics
        val infos = ws.map { w ->
            val r = Rect()
            runCatching { w.getBoundsInScreen(r) }
            Win(w, runCatching { w.root?.packageName?.toString() }.getOrNull(), r)
        }
        val hasAssistWindow = infos.any { isAssist(it.pkg) }
        // Paket adı okunamayan bir pencere varsa (bar odaksız olabilir) olay bilgisine güven
        val hasUnknownWindow = infos.any { it.pkg == null && it.w.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY }
        val result = eventSaysOn && (hasAssistWindow || hasUnknownWindow)
        if (eventSaysOn && !result) eventSaysOn = false

        if (hasAssistWindow || result) {
            lastDump = buildString {
                appendLine("ekran=${dm.widthPixels}x${dm.heightPixels} sonuç=$result bayrak=$eventSaysOn")
                infos.forEach { appendLine("tür=${it.w.type} pkg=${it.pkg} başlık=${it.w.title} sınır=${it.r.flattenToString()} katman=${it.w.layer}") }
                if (evLog.isNotEmpty()) appendLine("olaylar: " + evLog.joinToString(" | "))
            }
        }
        return result
    }

    /** Gemini / Asistan barını açan ekran sınıfı mı? */
    private fun isBarClass(cls: String?): Boolean {
        val c = cls?.lowercase() ?: return false
        return "floaty" in c || "robin" in c || "invocation" in c || "assistantoverlay" in c
    }

    // ---- Sistem ekran kaydedicisinin yüzen çubuğu (ör. MIUI/Redmi): bildirimde süre olmadığı için çubuktaki
    // süre yazısı (00:05) okunur. Süre görünüyorsa kayıt var; kaybolunca bitti. Tek rakam = geri sayım.
    private var recA11yOn = false
    private var recA11yDigit = 0
    private var recMiss = 0
    private var recCheckPending = false
    private val recCheck = object : Runnable {
        override fun run() {
            recCheckPending = false
            runCatching { checkRecorder() }
            if (recA11yOn || recA11yDigit != 0) scheduleRecCheck(350)
        }
    }

    private fun scheduleRecCheck(delayMs: Long = 60) {
        if (recCheckPending) return
        recCheckPending = true
        handler.postDelayed(recCheck, delayMs)
    }

    private fun collectTexts(n: AccessibilityNodeInfo, out: MutableList<String>, depth: Int = 0) {
        if (depth > 12) return
        n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(it) }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            try { collectTexts(c, out, depth + 1) } finally { @Suppress("DEPRECATION") runCatching { c.recycle() } }
        }
    }

    private fun checkRecorder() {
        val texts = mutableListOf<String>()
        val ws = runCatching { windows }.getOrNull().orEmpty()
        for (w in ws) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            try {
                if (isRecPkg(root.packageName?.toString())) collectTexts(root, texts)
            } finally { @Suppress("DEPRECATION") runCatching { root.recycle() } }
        }
        val timer = texts.firstOrNull { REC_TIME.matches(it) }
        val digit = texts.firstOrNull { it.length == 1 && it[0] in '1'..'9' }?.get(0)?.digitToInt() ?: 0
        if (timer != null) {
            recMiss = 0
            if (recA11yDigit != 0) { recA11yDigit = 0; IslandBus.post(IslandEvent.ScreenRecGone("a11y", RecPhase.Countdown)) }
            if (!recA11yOn) {
                recA11yOn = true
                val parts = timer.split(':').map { it.toIntOrNull() ?: 0 }
                val sec = parts.fold(0L) { acc, p -> acc * 60 + p }
                IslandBus.post(IslandEvent.ScreenRec(RecPhase.Recording, "a11y", System.currentTimeMillis() - sec * 1000, 0, null, null, null))
                scheduleRecCheck(350)
            }
            return
        }
        if (recA11yOn) {
            // Süre yazısı bir an kaybolabilir (yeniden çizim): üst üste 2 kontrolde yoksa bitti say
            if (++recMiss >= 2) {
                recA11yOn = false; recMiss = 0
                IslandBus.post(IslandEvent.ScreenRecGone("a11y", RecPhase.Recording))
            }
            return
        }
        if (digit != 0) {
            if (digit != recA11yDigit) {
                recA11yDigit = digit
                IslandBus.post(IslandEvent.ScreenRec(RecPhase.Countdown, "a11y", 0L, digit, null, null, null))
            }
            scheduleRecCheck(350)
        } else if (recA11yDigit != 0) {
            recA11yDigit = 0
            IslandBus.post(IslandEvent.ScreenRecGone("a11y", RecPhase.Countdown))
        }
    }

    private fun collectClickable(n: AccessibilityNodeInfo, out: MutableList<Pair<Int, AccessibilityNodeInfo>>, depth: Int = 0) {
        if (depth > 12) return
        if (n.isClickable && n.isEnabled) {
            val r = Rect(); n.getBoundsInScreen(r)
            out.add(r.width() * r.height() to AccessibilityNodeInfo.obtain(n))
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            try { collectClickable(c, out, depth + 1) } finally { @Suppress("DEPRECATION") runCatching { c.recycle() } }
        }
    }

    private fun doStopScreenRec(): Boolean {
        val ws = runCatching { windows }.getOrNull().orEmpty()
        for (w in ws) {
            val root = runCatching { w.root }.getOrNull() ?: continue
            try {
                if (!isRecPkg(root.packageName?.toString())) continue
                val list = mutableListOf<Pair<Int, AccessibilityNodeInfo>>()
                collectClickable(root, list)
                // Durdur düğmesi çubuğun en küçük tıklanabilir öğesidir
                val btn = list.filter { it.first > 0 }.minByOrNull { it.first }?.second
                if (btn != null && btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                // Bulunamazsa: çubuğun solundaki yuvarlak düğmenin olduğu yere dokun
                val r = Rect(); w.getBoundsInScreen(r)
                if (r.width() > 0 && r.height() > 0) return tapAt(r.left + r.height() / 2f, r.centerY().toFloat())
            } finally { @Suppress("DEPRECATION") runCatching { root.recycle() } }
        }
        return false
    }

    private fun tapAt(x: Float, y: Float): Boolean = runCatching {
        val p = android.graphics.Path().apply { moveTo(x, y) }
        val g = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(p, 0, 60)).build()
        dispatchGesture(g, null, null)
    }.getOrDefault(false)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        runCatching {
            val e = event ?: return
            if (isRecPkg(e.packageName?.toString())) scheduleRecCheck()
            if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && (recA11yOn || recA11yDigit != 0)) scheduleRecCheck()
            when (e.eventType) {
                AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                    if (NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)) return
                    val text = e.text.joinToString(" ").trim()
                    if (text.isEmpty()) return
                    IslandBus.post(IslandEvent.Notification(e.packageName?.toString().orEmpty(), "", text))
                }
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                    val pkg = e.packageName?.toString()
                    if (e.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != null) {
                        trackForeground()
                        if (isAssist(pkg)) {
                            evLog.addLast("$pkg ${e.className}")
                            while (evLog.size > 6) evLog.removeFirst()
                            eventSaysOn = isBarClass(e.className?.toString())
                        } else if (pkg != packageName && pkg !in IGNORED_PKGS) eventSaysOn = false
                    }
                    // Pencere listesi değişince (uygulamadan çıkarken ilk haber genelde bu olay) ön plan kontrolü zaten bekliyorsa dokunma, yoksa hemen başlat
                    if (e.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED && !handler.hasCallbacks(fgCheck)) trackForeground()
                    // Olay yağmurunu tek kontrole indir
                    handler.removeCallbacks(check)
                    handler.postDelayed(check, 120)
                }
            }
        }
    }

    override fun onInterrupt() = Unit
}
