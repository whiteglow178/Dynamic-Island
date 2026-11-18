package com.example.dynamicisland

import androidx.compose.runtime.Immutable
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.text.Collator
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.ceil
import kotlin.math.max

// Panel ölçüleri (dp). Ada yüksekliği = kamera bölgesi + 6 + dil satırı + 8 + gövde + 14 (bkz. IslandSettings.translateHeightDp)
const val TR_ROW_DP = 40f        // üstteki dil seçim satırı
const val TR_INPUT_DP = 52       // yazı alanı
const val TR_PICK_DP = 176       // dil listesi açıkken gövde yüksekliği
const val TR_LINE_DP = 21        // çeviri sonucunda bir satır
const val TR_MAX_LINES = 5       // sonuç alanı en çok bu kadar satıra büyür, fazlası kaydırılır

enum class TranslateStatus { Idle, Downloading, Translating, Done, Error }

@Immutable
data class Lang(val code: String, val name: String)

@Immutable
data class TranslateUi(
    val src: String = "tr",
    val tgt: String = "en",
    val input: String = "",
    val output: String = "",
    val status: TranslateStatus = TranslateStatus.Idle,
    val picking: Int = 0,          // 0 = yok, 1 = kaynak dil listesi açık, 2 = hedef dil listesi açık
    val nonce: Int = 0,            // artınca panel yazı alanını [input] ile değiştirir (dilleri yer değiştirince)
    val charsPerLine: Int = 31,    // sonuç alanında bir satıra sığan yaklaşık harf sayısı
) {
    /** Sonuç henüz yokken gösterilen durum yazısı. */
    fun message(): String = when (status) {
        TranslateStatus.Downloading -> "Dil modeli indiriliyor… (ilk seferde yaklaşık 30 MB, sonrasında internetsiz çalışır)"
        TranslateStatus.Translating -> "Çevriliyor…"
        TranslateStatus.Error -> "Çeviri yapılamadı. Dil modelini indirmek için internet gerekebilir."
        else -> ""
    }

    /** Sonuç alanının satır sayısı (yükseklik hesabı ve arayüz aynı değeri kullanır). */
    fun resultLines(): Int {
        val shown = if (output.isNotEmpty()) output else message()
        val cpl = max(8, charsPerLine)
        val n = shown.split('\n').sumOf { max(1, ceil(it.length / cpl.toFloat()).toInt()) }
        return n.coerceIn(1, TR_MAX_LINES)
    }
}

private fun bodyDpFor(u: TranslateUi): Int {
    if (u.picking != 0) return TR_PICK_DP
    if (u.input.isBlank()) return TR_INPUT_DP
    return TR_INPUT_DP + 8 + u.resultLines() * TR_LINE_DP + 16
}

/**
 * Çeviri paneli oturumu: yazılan metni ML Kit ile (cihazda, ücretsiz, API anahtarsız) çevirir.
 * Panel ve servis aynı nesneyi kullanır; durum [state] ile arayüze, gövde yüksekliği [bodyDp] ile pencereye gider.
 */
object TranslateSession {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(TranslateUi())
    val state: StateFlow<TranslateUi> = _state

    private val _body = MutableStateFlow(TR_INPUT_DP)
    /** Panelin dil satırı altındaki bölümünün yüksekliği (dp). Servis pencereyi buna göre boyutlandırır. */
    val bodyDp: StateFlow<Int> = _body

    /** Servis atar: panelden kapatma isteği. */
    var closeRequest: (() -> Unit)? = null
    /** Servis atar: dil çifti değişince ayarlara kaydetmek için. */
    var onLangsChanged: ((String, String) -> Unit)? = null

    private var job: Job? = null
    private var translator: Translator? = null
    private var translatorKey = ""
    private val downloaded = HashSet<String>()     // modeli cihazda olduğu doğrulanmış diller

    private fun set(f: (TranslateUi) -> TranslateUi) {
        val n = f(_state.value)
        _state.value = n
        _body.value = bodyDpFor(n)
    }

    // ---------------------------------------------------------------- Diller
    val languages: List<Lang> by lazy {
        val col = Collator.getInstance(TR)
        TranslateLanguage.getAllLanguages().map { Lang(it, nameOf(it)) }.sortedWith { a, b -> col.compare(a.name, b.name) }
    }

    fun nameOf(code: String): String {
        val n = Locale.forLanguageTag(code).getDisplayLanguage(TR)
        return if (n.isBlank() || n.equals(code, ignoreCase = true)) code.uppercase() else n.replaceFirstChar { it.titlecase(TR) }
    }

    private fun valid(code: String, fallback: String) = if (languages.any { it.code == code }) code else fallback

    // ---------------------------------------------------------------- Oturum
    fun open(src: String, tgt: String, charsPerLine: Int) {
        job?.cancel()
        var s = valid(src, TranslateLanguage.TURKISH)
        var t = valid(tgt, TranslateLanguage.ENGLISH)
        if (s == t) t = if (s == TranslateLanguage.ENGLISH) TranslateLanguage.TURKISH else TranslateLanguage.ENGLISH
        set { TranslateUi(src = s, tgt = t, charsPerLine = charsPerLine) }
    }

    /** Panel kapandı: bekleyen çeviri iptal edilir, durum sıfırlanır (diller korunur). */
    fun close() {
        job?.cancel()
        set { it.copy(input = "", output = "", status = TranslateStatus.Idle, picking = 0, nonce = 0) }
    }

    /** Servis kapanırken ML Kit istemcisini bırakır. */
    fun release() {
        job?.cancel()
        runCatching { translator?.close() }
        translator = null; translatorKey = ""
    }

    fun onText(text: String) {
        job?.cancel()
        if (text.isBlank()) {
            set { it.copy(input = text, output = "", status = TranslateStatus.Idle) }
            return
        }
        set { it.copy(input = text) }
        job = scope.launch { delay(350); run(text) }      // her tuşta değil, yazma duraklayınca çevirir
    }

    /** Dil listesini aç / kapat (1 = kaynak, 2 = hedef). */
    fun pick(which: Int) = set { it.copy(picking = if (it.picking == which) 0 else which) }

    fun setLang(which: Int, code: String) {
        val u = _state.value
        var s = u.src; var t = u.tgt
        // Karşı tarafla aynı dil seçilirse ikisi yer değiştirir
        if (which == 1) { if (code == t) t = s; s = code } else { if (code == s) s = t; t = code }
        applyLangs(s, t, moveOutput = false)
    }

    fun swap() {
        val u = _state.value
        applyLangs(u.tgt, u.src, moveOutput = true)
    }

    private fun applyLangs(s: String, t: String, moveOutput: Boolean) {
        val u = _state.value
        // Yer değiştirince çeviri sonucu yeni yazı olur; böylece ters yönde hemen kontrol edilebilir
        val newInput = if (moveOutput && u.status == TranslateStatus.Done && u.output.isNotBlank()) u.output else u.input
        set {
            it.copy(
                src = s, tgt = t, picking = 0, input = newInput, output = "",
                status = if (newInput.isBlank()) TranslateStatus.Idle else TranslateStatus.Translating,
                nonce = if (newInput != u.input) it.nonce + 1 else it.nonce,
            )
        }
        onLangsChanged?.invoke(s, t)
        job?.cancel()
        if (newInput.isNotBlank()) job = scope.launch { run(newInput) }
    }

    // ---------------------------------------------------------------- Çeviri
    private fun clientFor(src: String, tgt: String): Translator {
        val key = "$src>$tgt"
        if (key != translatorKey || translator == null) {
            runCatching { translator?.close() }
            translator = Translation.getClient(
                TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build(),
            )
            translatorKey = key
        }
        return translator!!
    }

    private suspend fun run(text: String) {
        val u = _state.value
        if (u.src == u.tgt) { set { it.copy(output = text, status = TranslateStatus.Done) }; return }
        fun current() = _state.value.let { it.input == text && it.src == u.src && it.tgt == u.tgt }
        try {
            val tr = clientFor(u.src, u.tgt)
            ensureModels(u.src, u.tgt, tr)
            val out = tr.translate(text).await()
            if (current()) set { it.copy(output = out, status = TranslateStatus.Done) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (current()) set { it.copy(output = "", status = TranslateStatus.Error) }
        }
    }

    /** Diller cihazda yoksa "indiriliyor" gösterir ve indirir (Wi-Fi şartı yok); varsa doğrudan çevirir. */
    private suspend fun ensureModels(src: String, tgt: String, tr: Translator) {
        val mgr = RemoteModelManager.getInstance()
        var missing = false
        for (code in listOf(src, tgt)) {
            if (code in downloaded) continue
            val has = try {
                mgr.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (has) downloaded += code else missing = true
        }
        if (missing) {
            set { it.copy(status = TranslateStatus.Downloading) }
            tr.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
            downloaded += src; downloaded += tgt
        }
        set { it.copy(status = TranslateStatus.Translating) }
    }
}

private val TR = Locale("tr", "TR")

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
}
