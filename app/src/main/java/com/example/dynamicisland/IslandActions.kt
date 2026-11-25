package com.example.dynamicisland

/**
 * Ada içindeki yeni düğmelerden (zamanlayıcı, alarm, düşük güç, Rahatsız Etmeyin, ses kaydı, basılı tutarak açma)
 * servise giden köprü. Servis başlarken bu işlevleri kendi işlevleriyle değiştirir; arayüz yalnızca bunları çağırır.
 * Ana Sayfa > Dene gibi önizlemelerde servis yoksa hiçbir şey yapmaz.
 */
object IslandActions {
    /** Ada servisi çalışıyor ve bu işlevleri bağladı (önizlemelerde false). */
    @Volatile var serviceLive = false
    /** Düşük pil kartındaki pil kapsülü: düşük güç modunu açar / kapatır. */
    @Volatile var onLowPower: () -> Unit = {}
    /** Zamanlayıcı: duraklat / devam. */
    @Volatile var onTimerPause: () -> Unit = {}
    /** Zamanlayıcı: iptal (X). */
    @Volatile var onTimerCancel: () -> Unit = {}
    /** Süresi dolan zamanlayıcı: aynı süreyle yeniden başlat. */
    @Volatile var onTimerRestart: () -> Unit = {}
    /** Kurma panelindeki "Başlat": verilen süre (ms) ile zamanlayıcıyı başlatır. */
    @Volatile var onTimerStart: (Long) -> Unit = {}
    /** Açık zamanlayıcıda süreye dokunma: zamanlayıcı kurma paneli açılır. */
    @Volatile var onOpenTimerSetter: () -> Unit = {}
    /** Kurma panelindeki "Odak": odak zamanlayıcısını (çalışma / mola döngüsü) başlatır. */
    @Volatile var onFocusStart: () -> Unit = {}
    /** Çalan alarm: ertele. */
    @Volatile var onAlarmSnooze: () -> Unit = {}
    /** Çalan alarm / dolan zamanlayıcı: kapat. */
    @Volatile var onAlarmDismiss: () -> Unit = {}
    /** Genişlemiş Rahatsız Etmeyin kartında aya dokunma: modu aç / kapat. */
    @Volatile var onFocusToggle: () -> Unit = {}
    /** Ses kaydı: durdur. */
    @Volatile var onVoiceStop: () -> Unit = {}
    /** Basılı tutarak açma: süre doldu, ada açılsın. */
    @Volatile var onHoldExpand: () -> Unit = {}
    /** Basılı tutarak açma: parmak değdi (true) / kalktı (false). Pencere önceden büyütülür. */
    @Volatile var onHoldPress: (Boolean) -> Unit = {}
    /** Kapalı adayı sağa / sola kaydırma: sağ alttaki hızlı paneli aç / kapat (Ayarlar > Hızlı panel). */
    @Volatile var onQuickPanel: () -> Unit = {}
}
