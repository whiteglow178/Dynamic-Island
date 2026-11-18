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

    fun update(f: (IslandUiState) -> IslandUiState)
    /** Bekleyen küçülme işini iptal eder. */
    fun cancelCollapse()
    /** Adayı küçültür ve dinlenme durumuna döndürür (önceki küçülme işi iptal edilir). */
    fun collapseSoon()
    fun send(pi: PendingIntent?)
    /** Ekran kaydı içeriğini adaya yayınlar (sıra / yan baloncuk kararlarını servis verir). */
    fun publishScreenRec(c: IslandContent.ScreenRec, expanded: Boolean)
}
