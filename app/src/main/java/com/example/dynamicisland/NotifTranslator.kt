package com.example.dynamicisland

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentificationOptions
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Bildirim çevirisi (Çeviri paneli + bildirimler): gelen bildirimin dili cihazda tanınır; hedef dilden farklıysa
 * cihazda çevrilip adada çevrilmiş haliyle gösterilir. Metin hiçbir sunucuya gönderilmez (ML Kit cihazda çalışır).
 * O dilin çeviri modeli henüz cihazda yoksa bildirim orijinal haliyle gösterilir, model arka planda bir kez indirilir;
 * aynı dildeki sonraki bildirimler çevrilir.
 */
object NotifTranslator {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var langId: LanguageIdentifier? = null
    private val clients = HashMap<String, Translator>()
    private val ready: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())        // cihazda modeli olan diller
    private val downloading: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    private fun identifier(): LanguageIdentifier =
        langId ?: LanguageIdentification.getClient(
            LanguageIdentificationOptions.Builder().setConfidenceThreshold(0.6f).build(),
        ).also { langId = it }

    /** Çevrilmiş (başlık, metin, kaynak dil kodu) döner; çevrilmediyse null. En çok ~1,5 sn bekler. */
    suspend fun translate(title: String, text: String, tgt: String): Triple<String, String, String>? {
        val sample = text.ifBlank { title }.trim()
        if (sample.length < 4) return null
        return withTimeoutOrNull(1_500L) {
            val src = runCatching { identifier().identifyLanguage(sample).awaitOrNull() }.getOrNull() ?: return@withTimeoutOrNull null
            if (src == "und" || src == tgt) return@withTimeoutOrNull null
            val srcCode = TranslateLanguage.fromLanguageTag(src) ?: return@withTimeoutOrNull null
            val tgtCode = TranslateLanguage.fromLanguageTag(tgt) ?: return@withTimeoutOrNull null
            if (!modelsReady(srcCode, tgtCode)) return@withTimeoutOrNull null
            val tr = clientFor(srcCode, tgtCode)
            val outText = if (text.isBlank()) "" else tr.translate(text).awaitOrNull() ?: return@withTimeoutOrNull null
            // Başlık çoğunlukla kişi adıdır: yalnızca metin yoksa ya da başlık uzun bir cümleyse çevrilir
            val outTitle = if (text.isBlank() || title.length > 24) tr.translate(title).awaitOrNull() ?: title else title
            Triple(outTitle, outText, srcCode)
        }
    }

    private fun clientFor(src: String, tgt: String): Translator = synchronized(clients) {
        clients.getOrPut("$src>$tgt") {
            Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(src).setTargetLanguage(tgt).build())
        }
    }

    /** İki dilin modeli de cihazdaysa true; değilse eksikleri arka planda indirmeye başlar ve false döner. */
    private suspend fun modelsReady(src: String, tgt: String): Boolean {
        val mgr = RemoteModelManager.getInstance()
        var all = true
        for (code in listOf(src, tgt)) {
            if (code in ready) continue
            val has = mgr.isModelDownloaded(TranslateRemoteModel.Builder(code).build()).awaitOrNull() == true
            if (has) ready += code else all = false
        }
        if (!all) {
            val key = "$src>$tgt"
            if (key !in downloading) {
                downloading += key
                scope.launch {
                    val ok = clientFor(src, tgt).downloadModelIfNeeded(DownloadConditions.Builder().build()).awaitOk()
                    if (ok) { ready += src; ready += tgt }
                    downloading -= key
                }
            }
        }
        return all
    }
}

/** Görev başarılıysa sonucunu, başarısızsa null döner (istisna fırlatmaz). */
private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(it) }
    addOnFailureListener { if (cont.isActive) cont.resume(null) }
    addOnCanceledListener { if (cont.isActive) cont.resume(null) }
}

/** Görev başarıyla bittiyse true (sonucu Void olan görevler için). */
private suspend fun <T> Task<T>.awaitOk(): Boolean = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { if (cont.isActive) cont.resume(true) }
    addOnFailureListener { if (cont.isActive) cont.resume(false) }
    addOnCanceledListener { if (cont.isActive) cont.resume(false) }
}

/** Dil kodunun Türkçe adı (bildirimdeki küçük "… dilinden çevrildi" etiketi için). */
fun langNameTr(code: String): String = when (code) {
    "en" -> "İngilizce"; "de" -> "Almanca"; "fr" -> "Fransızca"; "es" -> "İspanyolca"; "it" -> "İtalyanca"
    "ru" -> "Rusça"; "ar" -> "Arapça"; "ja" -> "Japonca"; "ko" -> "Korece"; "zh" -> "Çince"; "pt" -> "Portekizce"
    "nl" -> "Felemenkçe"; "az" -> "Azerice"; "fa" -> "Farsça"; "tr" -> "Türkçe"; "uk" -> "Ukraynaca"; "pl" -> "Lehçe"
    else -> code.uppercase()
}
