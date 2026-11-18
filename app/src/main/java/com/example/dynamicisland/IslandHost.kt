package com.example.dynamicisland

import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.CoroutineScope

/**
 * Ada servisinin, ayrı dosyalara taşınan olay denetleyicilerine (Shazam, ekran kaydı...) açtığı küçük arayüz.
 * Denetleyiciler servisin özel alanlarına doğrudan dokunmaz; yalnızca buradaki işlevleri kullanır.
 */
interface IslandHost {
    val scope: CoroutineScope
    val context: Context
    val settings: IslandSettings
    /** Şu an adada gösterilen içerik. */
    val content: IslandContent
    /** Google Asistan açık ya da gelen arama var: yeni olaylar adayı ele geçirmez. */
    val blocked: Boolean

    /** Adada panel açık mı? */
    val expanded: Boolean
    /** Asistan açık ya da herhangi bir arama (gelen / aktif) var. */
    val busy: Boolean

    /** Panel bir yan baloncuktan "peek" ile açılmış mı? */
    val isPeeking: Boolean
    /** Arka planda, aramadan daha yeni bir olay (müzik / kayıt...) var mı? */
    val newerThanCall: Boolean

    fun update(f: (IslandUiState) -> IslandUiState)
    /** Arka plan olay sırasını güncel etkin olaylara göre yeniler. */
    fun syncBgOrder()
    /** Adayı küçültüp dinlenme durumuna döndürür (bekleyerek; coroutine içinden çağrılır). */
    suspend fun collapseAndRest()
    /** İçeriği genişletilmiş gösterir, [holdMs] sonra küçültür. */
    fun showTemporarily(c: IslandContent, holdMs: Long)
    /** Ortaya en yeni etkin olayı yerleştirir (hiçbiri yoksa ada boşa döner). */
    fun rest()
    /** Adayı tamamen gizler (dokunuşu da geçirir) ya da geri getirir. */
    fun setIslandHidden(hidden: Boolean)
    /** Bekleyen küçülme işini iptal eder. */
    /** [holdMs] sonra adayı küçültür (öncekini iptal eder). */
    fun scheduleCollapse(holdMs: Long)
    fun cancelCollapse()
    /** Adayı küçültür ve dinlenme durumuna döndürür (önceki küçülme işi iptal edilir). */
    fun collapseSoon()
    fun send(pi: PendingIntent?)
    /** Ekran kaydı içeriğini adaya yayınlar (sıra / yan baloncuk kararlarını servis verir). */
    fun publishScreenRec(c: IslandContent.ScreenRec, expanded: Boolean)
}
