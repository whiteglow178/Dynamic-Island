package com.example.dynamicisland

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * ÇİZEREK KOMUT: boştaki adaya basılı tutunca ekran koyulaşır; parmağınla tek hamlede bir harf çizersin
 * (C, M, L, N, S, V, W, Z, U, O). Harfe atanan komut çalışır: kamera, müzik, fener, çeviri, favoriler...
 *
 * Ek izin GEREKTİRMEZ: mevcut "diğer uygulamaların üzerinde göster" / erişilebilirlik penceresini kullanır,
 * hiçbir veri toplamaz ve ağa çıkmaz (tanıma tamamen cihazda, küçük bir şablon karşılaştırmasıdır).
 */

// ---------------------------------------------------------------- Harf tanıma (Android'den bağımsız)
object GlyphRecognizer {
    private const val N = 40
    private const val MAX_DIST = 0.20f     // şablona ortalama uzaklık (0..1 birim kareye göre) üst sınırı
    private const val MIN_MARGIN = 0.015f  // ikinci en iyi şablonla fark: belirsizse reddet

    private class Tpl(val ch: Char, val fwd: FloatArray, val rev: FloatArray)

    private fun arc(cx: Float, cy: Float, rx: Float, ry: Float, a0: Float, a1: Float, n: Int = 24): List<Float> {
        val out = ArrayList<Float>()
        for (i in 0..n) {
            val a = Math.toRadians((a0 + (a1 - a0) * i / n).toDouble())
            out.add((cx + rx * Math.cos(a)).toFloat()); out.add((cy - ry * Math.sin(a)).toFloat())
        }
        return out
    }

    private fun poly(vararg v: Float): List<Float> = v.toList()

    private val templates: List<Tpl> by lazy {
        val raw = listOf(
            'C' to arc(0.5f, 0.5f, 0.5f, 0.5f, 45f, 315f),
            'V' to poly(0f, 0f, 0.5f, 1f, 1f, 0f),
            'Z' to poly(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
            'L' to poly(0f, 0f, 0f, 1f, 1f, 1f),
            'N' to poly(0f, 1f, 0f, 0f, 1f, 1f, 1f, 0f),
            'M' to poly(0f, 1f, 0f, 0f, 0.5f, 0.6f, 1f, 0f, 1f, 1f),
            'W' to poly(0f, 0f, 0.25f, 1f, 0.5f, 0.4f, 0.75f, 1f, 1f, 0f),
            'U' to poly(0f, 0f, 0f, 0.7f, 0.5f, 1f, 1f, 0.7f, 1f, 0f),
            // S: üst yay + alt yay
            'S' to (arc(0.5f, 0.25f, 0.5f, 0.25f, 30f, 270f) + arc(0.5f, 0.75f, 0.5f, 0.25f, 90f, -150f).drop(2)),
        )
        raw.mapNotNull { (ch, pts) ->
            val a = normalize(pts.toFloatArray()) ?: return@mapNotNull null
            val r = FloatArray(a.size)
            for (i in 0 until N) { r[2 * i] = a[2 * (N - 1 - i)]; r[2 * i + 1] = a[2 * (N - 1 - i) + 1] }
            Tpl(ch, a, r)
        }
    }

    /** Yol uzunluğuna göre [n] eşit aralıklı noktaya yeniden örnekler (x,y dizilimli). */
    private fun resample(p: FloatArray, n: Int): FloatArray? {
        val cnt = p.size / 2
        if (cnt < 2) return null
        val d = FloatArray(cnt)
        for (i in 1 until cnt) d[i] = d[i - 1] + hypot(p[2 * i] - p[2 * i - 2], p[2 * i + 1] - p[2 * i - 1])
        val len = d[cnt - 1]
        if (len <= 0f) return null
        val out = FloatArray(n * 2)
        var j = 1
        for (k in 0 until n) {
            val t = len * k / (n - 1)
            while (j < cnt - 1 && d[j] < t) j++
            val seg = d[j] - d[j - 1]
            val u = if (seg == 0f) 0f else ((t - d[j - 1]) / seg).coerceIn(0f, 1f)
            out[2 * k] = p[2 * (j - 1)] + (p[2 * j] - p[2 * (j - 1)]) * u
            out[2 * k + 1] = p[2 * (j - 1) + 1] + (p[2 * j + 1] - p[2 * (j - 1) + 1]) * u
        }
        return out
    }

    /** Çizimi birim kareye ölçekler (çok ince şekiller aşırı gerilmesin diye alt sınırlı). */
    private fun normalize(p: FloatArray): FloatArray? {
        val r = resample(p, N) ?: return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until N) {
            minX = min(minX, r[2 * i]); maxX = max(maxX, r[2 * i])
            minY = min(minY, r[2 * i + 1]); maxY = max(maxY, r[2 * i + 1])
        }
        val m = max(maxX - minX, maxY - minY)
        if (m <= 0f) return null
        val bw = max(maxX - minX, 0.3f * m)
        val bh = max(maxY - minY, 0.3f * m)
        for (i in 0 until N) { r[2 * i] = (r[2 * i] - minX) / bw; r[2 * i + 1] = (r[2 * i + 1] - minY) / bh }
        return r
    }

    private fun meanDist(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in 0 until N) s += hypot(a[2 * i] - b[2 * i], a[2 * i + 1] - b[2 * i + 1])
        return s / N
    }

    /**
     * [xy]: x,y,x,y... (piksel). Çizim [minDiagPx]'ten küçükse ya da hiçbir harfe yeterince benzemiyorsa null.
     * Kapalı, yaklaşık yuvarlak bir çizim "O" sayılır.
     */
    fun recognize(xy: FloatArray, minDiagPx: Float): Char? {
        val cnt = xy.size / 2
        if (cnt < 4) return null
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var len = 0f
        for (i in 0 until cnt) {
            minX = min(minX, xy[2 * i]); maxX = max(maxX, xy[2 * i])
            minY = min(minY, xy[2 * i + 1]); maxY = max(maxY, xy[2 * i + 1])
            if (i > 0) len += hypot(xy[2 * i] - xy[2 * i - 2], xy[2 * i + 1] - xy[2 * i - 1])
        }
        val bw = maxX - minX; val bh = maxY - minY
        if (hypot(bw, bh) < minDiagPx) return null
        val m = max(bw, bh)
        val gap = hypot(xy[0] - xy[2 * cnt - 2], xy[1] - xy[2 * cnt - 1])
        val ratio = bw / max(bh, 1e-6f)
        if (gap < 0.3f * m && ratio > 0.5f && ratio < 2f && len > 2.2f * m) return 'O'

        val q = normalize(xy) ?: return null
        var best = Float.MAX_VALUE; var second = Float.MAX_VALUE; var bestCh: Char? = null
        for (t in templates) {
            val d = min(meanDist(q, t.fwd), meanDist(q, t.rev))
            if (d < best) { second = best; best = d; bestCh = t.ch } else if (d < second) second = d
        }
        if (best > MAX_DIST || second - best < MIN_MARGIN) return null
        return bestCh
    }
}

// ---------------------------------------------------------------- Komutlar
object GestureAction {
    const val NONE = 0
    const val CAMERA = 1
    const val PLAY_PAUSE = 2
    const val NEXT = 3
    const val PREVIOUS = 4
    const val TORCH = 5
    const val TRANSLATE = 6
    const val FAVORITES = 7
    const val DOODLE = 8

    val NAMES = listOf(
        "Yok", "Kamera", "Müzik oynat / duraklat", "Sonraki şarkı", "Önceki şarkı",
        "El feneri", "Çeviri paneli", "Favori uygulamalar", "Karalama tahtası",
    )

    /** Servise bağlı olmayan komutları çalıştırır; servis gerektirenler (çeviri, favoriler, karalama) serviste işlenir. */
    fun runSimple(ctx: Context, a: Int) {
        when (a) {
            CAMERA -> runCatching {
                ctx.startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PLAY_PAUSE -> IslandBus.send(MediaCommand.PlayPause)
            NEXT -> IslandBus.send(MediaCommand.Next)
            PREVIOUS -> IslandBus.send(MediaCommand.Previous)
            TORCH -> FlashToggle.toggle(ctx)
        }
    }
}

/** El fenerini açıp kapatır (kamera izni gerekmez: CameraManager.setTorchMode). */
object FlashToggle {
    private val state = HashMap<String, Boolean>()
    private var cb: CameraManager.TorchCallback? = null

    private fun cm(ctx: Context): CameraManager? = ctx.applicationContext.getSystemService(CameraManager::class.java)

    private fun flashId(m: CameraManager): String? {
        var pick: String? = null
        var pickBack = false
        runCatching {
            for (id in m.cameraIdList) {
                val ch = m.getCameraCharacteristics(id)
                if (ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != true) continue
                val back = ch.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
                if (pick == null || (back && !pickBack)) { pick = id; pickBack = back }
            }
        }
        return pick
    }

    /** Fenerin şu anki durumunu öğrenmek için önceden dinlemeye başlar (çizim sürerken). */
    fun warmUp(ctx: Context) {
        if (cb != null) return
        val m = cm(ctx) ?: return
        val c = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { state[cameraId] = enabled }
        }
        if (runCatching { m.registerTorchCallback(c, Handler(Looper.getMainLooper())) }.isSuccess) cb = c
    }

    /** Fener şu an açık mı (bilinmiyorsa false). */
    fun isOn(): Boolean = state.values.any { it }

    fun toggle(ctx: Context) {
        val m = cm(ctx) ?: return
        val id = flashId(m) ?: return
        warmUp(ctx)
        runCatching { m.setTorchMode(id, !(state[id] ?: false)) }
    }
}

// ---------------------------------------------------------------- Ayar deposu
data class GesturePrefs(
    val enabled: Boolean = false,
    val map: Map<Char, Int> = GestureStore.DEFAULTS,
)

object GestureStore {
    val LETTERS = listOf('C', 'M', 'L', 'N', 'S', 'V', 'W', 'Z', 'U', 'O')
    val DEFAULTS: Map<Char, Int> = mapOf(
        'C' to GestureAction.CAMERA, 'M' to GestureAction.PLAY_PAUSE, 'L' to GestureAction.TORCH,
        'N' to GestureAction.NEXT, 'V' to GestureAction.FAVORITES, 'Z' to GestureAction.DOODLE,
        'S' to GestureAction.NONE, 'W' to GestureAction.NONE, 'U' to GestureAction.NONE, 'O' to GestureAction.NONE,
    )

    private const val FILE = "gesture_cmd_prefs"
    @Volatile private var state: MutableStateFlow<GesturePrefs>? = null

    private fun ensure(ctx: Context): MutableStateFlow<GesturePrefs> =
        state ?: synchronized(this) {
            state ?: MutableStateFlow(load(ctx.applicationContext)).also { state = it }
        }

    fun flow(ctx: Context): StateFlow<GesturePrefs> = ensure(ctx)

    fun update(ctx: Context, change: (GesturePrefs) -> GesturePrefs) {
        val st = ensure(ctx)
        val n = change(st.value)
        st.value = n
        runCatching {
            val e = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            e.putBoolean("enabled", n.enabled)
            LETTERS.forEach { ch -> e.putInt("a_$ch", n.map[ch] ?: GestureAction.NONE) }
            e.apply()
        }
    }

    private fun load(ctx: Context): GesturePrefs {
        val sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val m = LETTERS.associateWith { ch ->
            sp.getInt("a_$ch", DEFAULTS[ch] ?: GestureAction.NONE).coerceIn(0, GestureAction.NAMES.size - 1)
        }
        return GesturePrefs(enabled = sp.getBoolean("enabled", false), map = m)
    }
}

// ---------------------------------------------------------------- Çizim yüzeyi (tam ekran pencere)
/**
 * Boştaki adaya basılı tutunca açılır: ekranı hafifçe karartır, parmakla çizilen harfi tanır, komutu çalıştırır.
 * Ekrana kısa dokunmak ya da 7 sn dokunmamak kapatır.
 */
class GesturePad(
    private val ctx: Context,
    private val onAction: (Int) -> Unit,
) {
    private var view: PadView? = null
    private var host: WindowManager? = null

    fun canShow(): Boolean =
        IslandAccessibilityService.instance != null || android.provider.Settings.canDrawOverlays(ctx)

    private fun params(type: Int) = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, type,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    fun show() {
        if (view != null) return
        val prefs = GestureStore.flow(ctx).value
        if (prefs.map.values.contains(GestureAction.TORCH)) FlashToggle.warmUp(ctx)
        val v = PadView(ctx, prefs.map) { ch -> finish(ch) }

        // Önce erişilebilirlik penceresi (durum çubuğunun üstünde), olmazsa normal "üstte göster" penceresi
        val tries = ArrayList<Pair<WindowManager, Int>>()
        IslandAccessibilityService.instance?.let { a ->
            (a.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.let { tries.add(it to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) }
        }
        if (android.provider.Settings.canDrawOverlays(ctx)) {
            (ctx.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.let { tries.add(it to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) }
        }
        var err: Throwable? = null
        for ((wm, type) in tries) {
            try {
                wm.addView(v, params(type))
                view = v; host = wm
                v.start()
                return
            } catch (t: Throwable) { err = t }
        }
        android.widget.Toast.makeText(
            ctx, "Çizim ekranı açılamadı: " + (err?.let { it.javaClass.simpleName + " " + (it.message ?: "") } ?: "pencere izni yok"),
            android.widget.Toast.LENGTH_LONG,
        ).show()
    }

    fun dismiss() {
        val v = view ?: return
        v.stop()
        runCatching { host?.removeView(v) }
        view = null; host = null
    }

    /** [ch] null: iptal. Pencere kapandıktan sonra komut çalışır (alttaki uygulama / yeni ekran engellenmesin). */
    private fun finish(ch: Char?) {
        val action = if (ch == null) GestureAction.NONE else (GestureStore.flow(ctx).value.map[ch] ?: GestureAction.NONE)
        dismiss()
        if (action != GestureAction.NONE) onAction(action)
    }
}

private class PadView(
    context: Context,
    private val map: Map<Char, Int>,
    private val onDone: (Char?) -> Unit,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private var pts = FloatArray(512)
    private var n = 0                       // dizideki float sayısı
    private var locked = false              // sonuç gösteriliyor: dokunuş yok sayılır
    private var hint = "Bir harf çiz  (C, M, L, N, S, V, W, Z, U, O)"
    private var hintColor = AColor.argb(190, 255, 255, 255)

    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 16 * dp; color = AColor.argb(70, 120, 180, 255)
    }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        strokeWidth = 5 * dp; color = AColor.WHITE
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 17 * dp; isFakeBoldText = true
    }
    private val path = Path()

    private val timeout = Runnable { onDone(null) }

    fun start() { handler.postDelayed(timeout, 7000) }
    fun stop() { handler.removeCallbacksAndMessages(null) }

    private fun add(x: Float, y: Float) {
        if (n + 2 > pts.size) pts = pts.copyOf(pts.size * 2)
        pts[n++] = x; pts[n++] = y
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (locked) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                handler.removeCallbacks(timeout)
                n = 0; path.reset(); add(e.x, e.y); path.moveTo(e.x, e.y)
                hint = ""; invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                for (h in 0 until e.historySize) { add(e.getHistoricalX(h), e.getHistoricalY(h)); path.lineTo(e.getHistoricalX(h), e.getHistoricalY(h)) }
                add(e.x, e.y); path.lineTo(e.x, e.y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                add(e.x, e.y)
                finishStroke()
            }
            MotionEvent.ACTION_CANCEL -> { n = 0; path.reset(); invalidate(); handler.postDelayed(timeout, 7000) }
        }
        return true
    }

    private fun finishStroke() {
        val xy = pts.copyOf(n)
        // Çok kısa dokunuş: kapat
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until xy.size / 2) { minX = min(minX, xy[2 * i]); maxX = max(maxX, xy[2 * i]); minY = min(minY, xy[2 * i + 1]); maxY = max(maxY, xy[2 * i + 1]) }
        if (hypot(maxX - minX, maxY - minY) < 24 * dp) { onDone(null); return }

        val ch = GlyphRecognizer.recognize(xy, 56 * dp)
        if (ch == null) {
            hint = "Anlaşılamadı, tekrar dene"; hintColor = AColor.argb(255, 255, 140, 120)
            locked = true; invalidate()
            handler.postDelayed({
                n = 0; path.reset(); locked = false
                hint = "Bir harf çiz  (C, M, L, N, S, V, W, Z, U, O)"; hintColor = AColor.argb(190, 255, 255, 255)
                invalidate(); handler.postDelayed(timeout, 7000)
            }, 700)
            return
        }
        val a = map[ch] ?: GestureAction.NONE
        hint = if (a == GestureAction.NONE) "$ch  →  komut atanmamış" else "$ch  →  ${GestureAction.NAMES[a]}"
        hintColor = if (a == GestureAction.NONE) AColor.argb(255, 255, 200, 120) else AColor.argb(255, 140, 230, 160)
        locked = true
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        invalidate()
        handler.postDelayed({ onDone(ch) }, 450)
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(AColor.argb(150, 0, 0, 0))
        if (n >= 4) { c.drawPath(path, glow); c.drawPath(path, line) }
        text.color = hintColor
        if (hint.isNotEmpty()) c.drawText(hint, width / 2f, height * 0.16f, text)
    }
}

// ---------------------------------------------------------------- Ayarlar bölümü
@Composable
fun GestureSettingsSection() {
    val ctx = LocalContext.current
    val p by GestureStore.flow(ctx).collectAsState()
    SectionCard("Çizerek komut", "Boştaki adaya basılı tut, ekrana bir harf çiz: harfe atadığın komut çalışsın") {
        SwitchRow(
            "Çizerek komut", "Açıkken boştaki adaya basılı tutmak harf çizme ekranını açar. Çeviri ve favoriler de buradan harfe atanarak açılır",
            p.enabled,
        ) { v -> GestureStore.update(ctx) { it.copy(enabled = v) } }
        if (p.enabled) {
            SectionDivider()
            SubHeader("Harf → komut (düğmeye dokun: sıradaki komuta geçer)")
            GestureStore.LETTERS.forEach { ch ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(ch.toString(), color = MaterialTheme.colorScheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    IslandActionButton(GestureAction.NAMES[p.map[ch] ?: 0], {
                        GestureStore.update(ctx) { s ->
                            val next = ((s.map[ch] ?: 0) + 1) % GestureAction.NAMES.size
                            s.copy(map = s.map + (ch to next))
                        }
                    })
                }
            }
            Text(
                "Harfi tek hamlede, parmağını kaldırmadan çiz. Anlaşılmazsa ekran tekrar dener; ekrana kısa dokunarak ya da 7 sn bekleyerek kapatırsın. Hiçbir veri kaydedilmez, ek izin istenmez.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp,
            )
        }
    }
}
