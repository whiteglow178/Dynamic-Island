package com.example.dynamicisland

// ================================================================================================
// Ayarlar ekranındaki ⓘ düğmesinin bilgi sayfası verisi. Ayar ekranındaki başlık ve açıklamalardan
// derlenmiştir (sayfa sırasıyla). Yeni ayar eklenince bu dosya da güncellenmeli.
// ================================================================================================

internal class InfoItem(val title: String, val desc: String, val header: Boolean = false)
internal class InfoSection(val group: String, val title: String, val subtitle: String, val items: List<InfoItem>)

private fun h(t: String) = InfoItem(t, "", true)
private fun i(t: String, d: String = "") = InfoItem(t, d)

private fun part0(): List<InfoSection> = listOf(
    InfoSection("Yerleşim", "Kapalı ada", "Kamera çevresindeki küçük hal", listOf(
        i("Genişlik"),
        i("Yükseklik"),
    )),
    InfoSection("Yerleşim", "Açık ada", "Dokununca açılan genişletilmiş panel", listOf(
        i("Genişlik"),
        i("Yükseklik"),
    )),
    InfoSection("Yerleşim", "Konum", "Kamera deliğine göre ince ayar", listOf(
        i("Yatay"),
        i("Dikey"),
        i("Bildirim simgelerine yer aç", "Durum çubuğunun solunda bildirim simgeleri adaya yaklaşınca adanın sol ucu simgelerin ötesine çekilir; sağ ucu yerinde kalır, ada kameranın üstünde kalır. Simgeler gidince eski haline döner. Erişilebilirlik izni gerekir (simgeler ekran görüntüsünden okunur, kaydedilmez). Nokta, hap + daire ve ters ada modlarında çalışmaz"),
        i("Ters ada", "Ada ekranın üstünde değil en altında durur. Olaylar ve bildirimler düz görünür; adaya basınca panel aşağı değil yukarı doğru açılır. Dikey konum ayarı alttan ölçülür"),
        i("Ters adaya özel konum ve boyut", "Açıkken ters ada, üstteki Kapalı ada ve Konum ayarlarını değil aşağıdaki kendi ayarlarını kullanır (ekranın altında farklı bir boyut / yer isteyebilirsin)"),
        h("Ters ada boyutu"),
        i("Genişlik"),
        i("Yükseklik"),
        h("Ters ada konumu"),
        i("Alttan uzaklık"),
        i("Yatay mod", "Telefonu yan çevirince ada kamera kenarında dikey bir hap olur. Gelen bildirim, şarj, arama gibi olaylar adanın yanında, simgesi düz duran küçük baloncuk olarak animasyonla ayrılır. Bildirimde uygulamanın simgesi görünür"),
        i("Baloncuklar adanın altında", "Açıkken baloncuklar adanın altına, kapalıyken üstüne dizilir"),
        i("Olaylar adada da görünsün", "Açıkken olay (bildirim, şarj, müzik...) adanın içinde de gösterilir. Adada zaten bir olay varsa gelen yeni olaylar baloncuk olur; aynı anda en çok 2 baloncuk çıkar. Kapalıyken olaylar yalnızca baloncukta görünür. Adanın içinde çizilen özellikler (adada bildirim yazısı, kapalı adada şarkı sözü, arama / müzik hapı, hap + daire modu dairesi) açıksa bu ayar kendiliğinden açık sayılır"),
    )),
    InfoSection("Yerleşim", "Görünürlük", "Ada ne zaman görünsün", listOf(
        i("Boştayken de göster", "Müzik ve bildirim yokken ada kamera çevresinde durur"),
        i("Zil / sessiz göstergesi", "Telefonu sessize, titreşime ya da sesliye alınca ada kısa süre Zil, Sessiz ya da Titreşim yazısıyla açılır"),
        i("Zil göstergesi genişlesin", "Açıkken ada, ekran kaydındaki gibi yana doğru genişleyip solda zil simgesi, sağda Zil / Sessiz / Titreşim yazısı gösterir. Kapalıyken ada boyutunu değiştirmez, yalnızca solda simge görünür"),
        i("Zil hapı genişliği"),
        i("Zil hapı yüksekliği"),
        i("Medya sesi göstergesi", "Ses tuşlarıyla (ya da kenar ses tuşlarıyla) medya sesini artırınca / azaltınca ada kısa süre hoparlör simgesi, seviye çubuğu ve yüzdeyle açılır"),
        i("Zil sesi seviyesi göstergesi", "Zil sesini (ses tuşları ya da ayarlar) değiştirince ada yana genişler: solda zil simgesi ve \"Zil\", sağda ince seviye çubuğu. Telefon sesli moddayken çalışır"),
        i("İnternet göstergesi", "İnternete bağlanınca ada geniş hap olur: solda Wi-Fi / mobil veri simgesi, sağda \"Bağlandı\". Bağlantı kesilince \"Bağlantı kesildi\" yazar. Wi-Fi'den mobil veriye geçişte yanıp sönmez"),
        i("Uçak modu göstergesi", "Uçak modu açılınca / kapanınca ada turuncu uçak simgesi ve \"Açık\" / \"Kapalı\" yazısıyla haber verir"),
        i("Düşük pil göstergesi", "Şarjda değilken pil %20'ye ve %10'a düşünce ada uyarır. Dokununca düşük güç modu açılır (izin yoksa pil tasarrufu ayarı açılır). Düşük güç modu açılınca sarıya döner"),
        i("Düşük pil uyarısı genişlesin", "Açıkken ada büyük bir karta açılır: solda \"%10 Pil\" ve \"Düşük Güç Modu için dokun\", sağda kırmızı kapsül içinde pil. Kapalıyken ada genişlemez; ince geniş hapta \"Düşük Pil\" ve kırmızı yüzde + pil görünür"),
        i("Durum çubuğunu gizle", "Şarj görünümü, zil sesi görünümü vb. açıldığında durum çubuğunu gizler. Durum çubuğu bulanıklaşır (cihaz desteklemiyorsa siyah olur)"),
    )),
    InfoSection("Yerleşim", "Kamera / mikrofon göstergesi", "Bir uygulama kamerayı ya da mikrofonu kullanırken ada haber verir", listOf(
        i("Gösterge", "Kamera ya da mikrofon açılınca ada kısa süre açılır, sonra yeşil (kamera) / turuncu (mikrofon) nokta olarak kalır. Nokta modunda ve normal adada çalışır. Hangi uygulamanın kullandığı Android tarafından verilmez"),
        i("Açılınca genişlesin", "Açıkken kamera / mikrofon başlayınca ada 4 saniye genişleyip sonra küçülür. Kapalıyken hiç genişlemez, doğrudan küçük gösterge olarak görünür"),
        h("Geçmiş"),
        i("Kamera / mikrofon geçmişi", "Kamera ya da mikrofon ne zaman, ne kadar süre kullanıldı ve (tahminen) hangi uygulamayla: aşağıda listelenir. Göstergeden bağımsız çalışır. Yalnızca bellekte tutulur (en çok 40 kayıt): diske yazılmaz, hiçbir yere gönderilmez, uygulama kapanınca ya da telefon yeniden başlayınca silinir. Uygulama adını Android vermez; kullanım başladığında ön planda olan uygulama gösterilir"),
    )),
    InfoSection("Yerleşim", "Nokta modu", "Ada yerine 3, 4 ya da 5 nokta; olaylar nokta (baloncuk) olarak görünür", listOf(
        i("Nokta modu", "Ortadaki nokta kameranın üstünde durur. Yeni olay ortadaki noktada, öncekiler yan noktalarda görünür; dokununca ada gibi genişler"),
        i("Orta nokta (kamera)"),
        i("Sol nokta"),
        i("Sağ nokta"),
        i("Sol dış nokta"),
        i("Sağ dış nokta"),
        i("Noktaları birleştir", "Boştayken noktalar birleşip tek bir dinamik ada gibi görünür. Olay gelince ilgili nokta adadan ayrılır; olayların görünümü aynı kalır"),
        i("Olaylar adanın içinde kalsın", "Olay gelince nokta adadan ayrılmaz; olay simgesi birleşik adanın içinde, kendi yerinde görünür"),
        i("Noktalar arası boşluk"),
        i("Konum: yatay (sağ +)"),
        i("Konum: dikey (aşağı +)"),
    )),
    InfoSection("Yerleşim", "Çentik (notch) modu", "Klasik çentik gibi: ada yerine ekranın üstüne yapışık çentik", listOf(
        i("Çentik modu", "Ada, ekranın tepesine yapışık, kamerayı örten, alt köşeleri yuvarlak ve iki yanında içe kavisli kulakları olan bir çentik olur. Müzik, bildirim, zamanlayıcı, yan baloncuklar ve diğer tüm özellikler aynen çalışır; dokununca çentik aşağı doğru genişler. Nokta modu, hap + daire modu ve ters ada ile birlikte olmaz (biri açılınca öteki kapanır)"),
        i("Genişlik"),
        i("Ek derinlik"),
    )),
    InfoSection("Yerleşim", "Hap + daire modu", "Hap şeklinde ada ve yanında ayrı siyah daire", listOf(
        i("Hap + daire modu", "Ada hap şeklinde olur, yanında siyah bir daire durur. Nokta modu ile birlikte çalışmaz (biri açılınca öteki kapanır)"),
        i("Siyah daire", "Adanın yanındaki ayrı siyah daire (kamera deliği gibi)"),
        i("Olaylar dairede görünsün", "İkinci bir olay başlayınca (müzik, ekran kaydı, arama, kamera / mikrofon...) öncekisi dairede görünür; dokununca ada gibi açılır. Kapalıysa daire süs olarak kalır"),
        i("Daire boyutu (en çok ada yüksekliği)"),
        i("Ada ile daire arası"),
        i("İkiliyi ortala", "Ada + daire birlikte kameranın ortasında durur. Kapalıysa ada kameranın üstünde kalır, daire yanına gelir"),
        i("Ada genişliği"),
        i("Ada yüksekliği"),
        i("Konum: yatay (sağ +)"),
        i("Konum: dikey (aşağı +)"),
    )),
    InfoSection("Yerleşim", "Favori uygulamalar", "Boştayken adaya dokununca açılan uygulama paneli", listOf(
        h("Favori uygulamalar"),
        i("Favori uygulamalar", "Ada boştayken açılır; bir uygulamaya dokununca o uygulama açılır"),
        h("Ne zaman açılsın?"),
        i("Dokununca", "Adaya kısa dokunuşla açılır"),
        i("Basılı tutunca", "Yalnızca adaya basılı tutunca açılır; kısa dokunuş bir şey yapmaz"),
        i("Açılış animasyonu", "Uygulamaya dokununca ada animasyonu ekranı kaplar, ardından uygulama açılır"),
        i("Animasyon hızı"),
        h("Animasyon stili"),
        i("Klasik", "Adadan siyah ekran çıkıp ekranı kaplar"),
        i("Atom ada", "Adadan açılan siyah kart huni gibi genişleyip ekranı kaplar (OriginOS tarzı)"),
    )),
    InfoSection("Yerleşim", "Çeviri", "Boştayken adaya dokununca açılan çeviri paneli", listOf(
        h("Çeviri"),
        i("Çeviri paneli", "Ada boştayken açılır: üstte dil seçimi, altta yazı alanı ve çeviri. Panel gerektiği kadar genişler"),
        h("Ne zaman açılsın?"),
        i("Dokununca", "Boştayken adaya kısa dokunuşla açılır"),
        i("Basılı tutunca", "Yalnızca adaya basılı tutunca açılır"),
        h("Varsayılan diller"),
    )),
    InfoSection("Yerleşim", "Bildirimler", "Adada görünen bildirim için hareketler", listOf(
        h("Gizlilik"),
        i("Gizli bildirim modu", "Adada bildirimin içeriği yerine yalnızca uygulamanın adı ve \"Yeni bildirim\" yazar; kişi fotoğrafı ve hızlı düğmeler de gizlenir. Bildirimin üstünde basılı tutunca içerik görünür, parmağını kaldırınca yeniden gizlenir. Dokununca uygulama her zamanki gibi açılır."),
        i("Bilekle gizle", "Telefonu bilekten hızlıca çevirip geri getirince (kapı kolu çevirir gibi) adadaki bildirimler anında gizlenir, telefon titreşir. Aynı hareketle yeniden görünür. Ekran kapanınca gizleme kendiliğinden kalkar. Jiroskop gerekir."),
        i("Panik sallaması", "Telefonu kısa sürede iki kez sertçe sallayınca: ada küçülür, açık bildirim ve banner kapanır, müzik durur, bildirimler gizlenir. Gizleme ekranı kapatıp açınca kalkar (bilekle gizle açıksa bilek hareketiyle de)."),
        i("Ekran kaydında gizle", "Ekran kaydı sürerken ya da ekran başka bir ekrana yansıtılırken bildirimler kendiliğinden gizlenir; kayıt bitince yeniden görünür. Başka uygulamaların ekran paylaşımı (görüntülü görüşmede ekran paylaşma gibi) her telefonda algılanamayabilir."),
        i("Ekran görüntüsünde gösterme", "Adada bildirim ya da doğrulama kodu varken ekran görüntüsü alınırsa veya ekran kaydedilirse ada kayda girmez (o bölge boş ya da siyah çıkar). Ekranda normal görünür. Banner da kayda girmez. Müzik ve diğer durumlarda ada kayıtlarda normal görünür."),
        i("Telefon masadayken gizle", "Telefon masada yatarken 15 saniye hiç kıpırdamazsa bildirimler gizlenir; eline aldığın an yeniden görünür. Masadaki telefona başkası göz atamaz. Ekran açıkken ivmeölçeri kullanır."),
        i("Kilit ekranında gizle", "Telefon kilitliyken adadaki bildirimler gizlenir, kilidi açınca normal görünür."),
        i("Belirli saatlerde gizle", "Seçtiğin saat aralığında gizleme kendiliğinden açılır (ör. iş saatleri). Gece yarısını aşan aralık da seçilebilir (22:00 – 07:00 gibi)."),
        i("Başlangıç"),
        i("Bitiş"),
        i("Yem metin", "Gizliyken \"Yeni bildirim\" yerine sıradan, dikkat çekmeyen sahte bir bildirim görünür (\"Giyim ürünlerinde %10 indirim\", \"5 güncelleme mevcut\", \"Sıcaklık 20 derece\" gibi). Uygulama simgesi de gizlenir. Açıksa bulanık önizlemenin yerine geçer."),
        i("Bulanık önizleme", "Gizliyken \"Yeni bildirim\" yerine yazı bulanık görünür: mesajın uzunluğu belli olur ama okunmaz. Sohbet bildirimlerinde gönderenin adı görünür. Gerçek yazı ekrana hiç çizilmez."),
        i("Müziği de gizle", "Gizleme devredeyken (gizli mod, bilek hareketi, ekran kaydı, masa, kilit ya da saat) adada şarkı adı, sanatçı, kapak ve şarkı sözleri gizlenir; yalnızca ekolayzır ve süre görünür."),
        h("Her zaman gizlenenler"),
        i("Doğrulama kodlarını gizle", "Bildirimdeki ve adadaki doğrulama kodu ●●●●●● olarak görünür. Basılı tutunca kod görünür; koda dokununca yine doğru kod kopyalanır."),
        i("Hassas kelime filtresi", "Aşağıdaki kelimelerden biri geçen bildirimler hep gizlenir. Büyük / küçük harf fark etmez, kelime tam eşleşmelidir (\"kod\" yazarsan \"kodlama\" gizlenmez)."),
        h("İçeriği açma hareketi"),
        i("Basılı tut", "Gizli bildirimin üstünde basılı tut; bırakınca yeniden gizlenir"),
        i("Sağa, sonra sola çiz", "Parmağını kaldırmadan önce sağa sonra sola kaydır; parmak ekrandayken içerik görünür. Yanındaki biri basılı tutmayı görse de taklit edemez"),
        i("İki parmakla basılı tut", "İki parmağını birden adaya koy; parmaklar ekrandayken içerik görünür"),
        h("Titreşim imzası"),
        h("Uyarılar ve rozet"),
        i("Yeni uygulama uyarısı", "Telefona yeni bir uygulama kurulunca ada \"Yeni uygulama · uygulama adı\" der. Bilmediğin bir kurulumu hemen fark edersin. Güncellemeler sayılmaz. Not: bir uygulamanın sonradan yetki istemesini Android bildirmez, yalnızca kurulum algılanır. İzin gerektirmez"),
        i("Okunmamış bildirim sayacı", "Boştaki adanın sağ köşesinde, bekleyen (silinmemiş) bildirim sayısı küçük kırmızı rozetle görünür. \"Boştayken de göster\" açık olmalı. Rozet göründüğünde ağ hızının yükleme kısmı gizlenir. Devam eden (müzik, arama gibi) ve kaydırılamayan bildirimler sayılmaz"),
        h("Bildirim türü"),
        i("Cam banner", "Bildirimler adada değil, ekranın üstünde tam genişlikte cam banner olarak görünür (geliş / gidiş animasyonu aşağıdan seçilir). Dokununca uygulama açılır, yukarı kaydırınca kapanır. Süre: Genişletilmiş kalma süresi"),
        i("Adayı genişletme (yazı adanın içinde)", "Bildirim gelince ada büyümez. Gönderen, bildirim yazısı ve uygulamanın simgesi küçük adanın içine sığdırılarak gösterilir. Süre: Genişletilmiş + Küçültülmüş kalma süresi toplamı. Nokta modunda etkisizdir. İstersen Ada genişliğini artırarak yazıya daha çok yer açabilirsin."),
        h("Cam banner: animasyon"),
        i("Klasik", "Saatin altındaki küçük haptan büyüyerek açılır, kapanırken aynı yoldan o hapa küçülür"),
        i("Üstten kayma", "Ekranın üst kenarının altından kayarak iner ve yaylanarak oturur; kapanırken yukarı kayar"),
        h("Cam banner: menü ve simge"),
        i("Bildirim menüsü", "Banner'a basınca Cevapla, Kopyala ve İlet seçenekleri çıkar. Kopyalama için herhangi bir izin gerekmez."),
        i("Basılı tutunca", "Banner'a basılı tutunca menü çıkar, kısa dokunuş uygulamayı açar"),
        i("Dokununca", "Banner'a kısa dokununca menü çıkar; uygulamayı açmak için simgeye dokun"),
        i("Simgeye dokununca uygulamayı aç", "Kapalıysa banner'daki simgeye (gönderen fotoğrafı / uygulama simgesi) dokunmak uygulamayı açmaz"),
        h("Süreler"),
        i("Genişletilmiş kalma süresi"),
        i("Küçültülmüş kalma süresi"),
        i("Küçükken dokununca genişlesin", "Küçülmüş (ya da \"ada genişlemesin\" modunda kapalı adada duran) bildirime dokununca bildirim yeniden genişler. Genişken dokununca aşağıdaki ayar geçerli olur"),
        h("Uygulamayı aç"),
        i("Dokununca", "Bildirimin geldiği uygulama açılır (mümkünse ilgili sohbet / ekran)"),
        i("Basılı tutunca"),
        i("Aşağı kaydırınca"),
        i("Açılış animasyonu", "Adadan siyah bir ekran çıkıp ekranı kaplar, ardından uygulama açılır. Hız ve stil: Hareket bölümü"),
        h("Bildirimi küçült"),
        i("Dokununca", "Bildirim açılmadan adaya küçülür"),
        i("Yukarı kaydırınca"),
        h("Bildirimi sil"),
        i("Sağa veya sola kaydırınca", "Bildirim hem adadan hem bildirim panelinden silinir (bildirim erişimi açık olmalı)"),
    )),
    InfoSection("Yerleşim", "Yan baloncuklar", "Adanın yanında ayrılan baloncuklar (müzik, ekran kaydı, arama)", listOf(
        i("Olayları alt alta aç", "Birden fazla olay varken adaya dokununca (ya da aşağı kaydırınca) hepsi genişleyip alt alta kartlar halinde sıralanır. Karta dokununca ya da yukarı kaydırınca kapanır"),
        i("Dokununca olay ortaya geçsin", "Kapalıyken baloncuğa dokununca o uygulamanın paneli genişler ama olay ortaya geçmez; panel kapanınca eski düzen döner. Açıkken olay kalıcı olarak ortaya geçer"),
        i("Uzun yan baloncuk", "Baloncuk tam daire değil, biraz uzun bir kapsül olur. Zamanlayıcıda halkanın yanında kalan süre (22dk), müzikte kapağın yanında ekolayzır görünür. Nokta ve hap + daire modlarında kullanılmaz"),
    )),
)

private fun part1(): List<InfoSection> = listOf(
    InfoSection("Yerleşim", "Ekran kaydı", "Telefonun kendi ekran kaydı adada görünür (ek izin istemez)", listOf(
        i("Ekran kaydı adası", "Bildirim erişimi açık olmalı. Kayıt sırasında kırmızı nokta, dokununca süre ve durdur düğmesi"),
        h("Açık ada genişliği"),
    )),
    InfoSection("Yerleşim", "Google Asistan / Gemini", "Asistan açılınca ada Siri benzeri renkli animasyona dönüşür", listOf(
        i("Asistan animasyonu", "Erişilebilirlik servisi açık olmalı"),
        i("Tam genişlik animasyon", "Ada kutusu olmadan, renkli animasyon ekranın solundan sağına uzanır"),
        h("Animasyon stili"),
        i("Genişlik"),
        i("Yükseklik"),
    )),
    InfoSection("Yerleşim", "Kenar tuşları", "Cevapla / Reddet kenar tuşları", listOf(
        h("Kenar tuşları"),
        i("Kenardan cevapla / reddet", "Tek elle kullanım için ekranın kenarında dikey tuşlar gösterir"),
        h("Konum"),
        h("Arka plan siyahtır; şeffaflığı ayarla"),
        i("Şeffaflık"),
        h("Konum ve boyut"),
        i("Dikey konum"),
        i("Kenardan uzaklık"),
        i("Boyut"),
        i("Yükseklik"),
    )),
    InfoSection("Yerleşim", "Kenar ses tuşları", "Telefonun kenarında ses artır / azalt tuşları", listOf(
        h("Kenar ses tuşları"),
        i("Kenar ses tuşları", "Ekranın yan kenarında dikey, medya sesini artıran ve azaltan iki tuş gösterir. Basılı tutunca ses sürekli değişir"),
        h("Kenar"),
        h("Artır tuşunun yeri"),
        i("Yalnızca müzik varken", "Müzik çalmıyorken çubuk gizlenir"),
        i("Kalınlık"),
        i("Uzunluk"),
        i("Dikey konum"),
        h("Çubuk siyahtır; şeffaflığı ayarla"),
        i("Şeffaflık"),
    )),
    InfoSection("Yerleşim", "Kenar müzik çubukları", "Dikey müzik tuşları ve ilerleme çubuğu", listOf(
        h("Kenar müzik çubukları"),
        i("Kenar müzik çubuğu", "Müzik varken (çalarken ya da duraklatılmışken) telefonun yan kenarında dikey önceki / duraklat / sonraki tuşları ve ilerleme çubuğu gösterir"),
        h("Kenar"),
        i("Kenarlara ayır", "Sol kenarda yalnızca tuşlar, sağ kenarda yalnızca ilerleme çubuğu olur. Kapalıyken iki kenarda da ikisi birden bulunur"),
        i("Önceki / duraklat / sonraki tuşları"),
        i("İlerleme çubuğu", "Dikey; dokunarak ya da sürükleyerek şarkıyı ilerlet / geri sar"),
        h("Tuşların yeri"),
        i("Kalınlık"),
        i("Uzunluk"),
        i("Dikey konum"),
        h("Çubuk siyahtır; şeffaflığı ayarla"),
        i("Şeffaflık"),
    )),
    InfoSection("Yerleşim", "Çizerek komut", "Adaya basılı tut, harf çiz: kamera, müzik, fener...", listOf(
        h("Çizerek komut"),
        i("Çizerek komut", "Açıkken boştaki adaya basılı tutmak harf çizme ekranını açar. Çeviri ve favoriler de buradan harfe atanarak açılır"),
        h("Harf → komut (düğmeye dokun: sıradaki komuta geçer)"),
    )),
    InfoSection("Görünüm", "Gökkuşağı modu", "Ada, müzik paneli, mesaj ve şarkı sözleri gökkuşağı renklerinde akar", listOf(
        i("Gökkuşağı modu", "Açıkken ilgili öğelerde diğer renk ayarları yerine gökkuşağı kullanılır"),
        i("Saydamlık"),
    )),
    InfoSection("Görünüm", "Renk", "Adanın genel renk davranışı", listOf(
        i("Albüm kapağı rengi", "Ada rengi, çalan şarkının kapağına göre değişir"),
        h("Ada rengi / cam rengi"),
        h("Vurgu rengi"),
    )),
    InfoSection("Görünüm", "Ekolayzır rengi", "Müzik çalarken adadaki ses çubukları", listOf(
        h("Görünüm"),
        i("Çubuklar", "Klasik ekolayzır"),
        i("Halka", "Renk geçişli, dalgalanarak şekil değiştiren hareketli bir halka"),
        i("Yörünge", "Ritimle atan parlak bir çekirdek ve etrafında iz bırakarak dönen iki küçük uydu"),
        i("Dalga", "Birbirinin içinden akan üç renkli ses dalgası; uçlarda incelir, ritimle kabarır"),
        i("Piksel", "Eski müzik setlerindeki gibi yanan nokta sütunları; tepedeki nokta parlar"),
        i("Nabız", "Ritimle atan, sağdan sola akan kalp atışı çizgisi; ucunda parlak bir nokta"),
        i("Güneş", "Yavaşça dönen, her biri ritimle uzayıp kısalan on iki renkli ışın"),
        i("Kabarcık", "Altta doğup yalpalayarak yükselen ve tepede sönen renkli kabarcıklar"),
        i("Ayna", "Ortadaki çizgiden yukarı ve aşağı simetrik açılan ince çubuklar"),
        i("Yankı", "Merkezden dışarı yayılıp sönen halkalar ve ritimle atan bir çekirdek"),
        i("Sarmal", "DNA gibi iç içe dönen iki şerit; öne gelen noktalar parlar, şerit ritimle açılıp kapanır"),
        i("Plak", "Dönen küçük bir vinil plak; oluklarda ışık yansıması, etiket kapak renginde"),
        i("Lav", "Lav lambası gibi birleşip ayrılan, ritimle şişen renkli baloncuklar"),
        i("Tepeler", "Üç katmanlı, dolgulu ve akan bir ses dağı; tepeler ritimle yükselir"),
        i("Kalp", "Tık-tık ritmiyle atan parlak bir kalp; her vuruşta küçük kıvılcımlar"),
        i("Albüm kapağı rengi", "Ekolayzır, çalan şarkının kapağına göre renklenir"),
    )),
    InfoSection("Görünüm", "Albüm kapağı şekli", "Müzik çalarken adadaki kapak kare ya da daire", listOf(
        i("Daire albüm kapağı", "Kapak köşeli kare yerine tam daire olur. Dokunma alanı da daireye göre ayarlanır; ada sakinleri dairenin üstünde yürür"),
        h("Nerede daire olsun"),
    )),
    InfoSection("Görünüm", "Albüm arka planı", "Müzik çalarken adanın içi kapak görseliyle dolar", listOf(
        i("Albüm kapağı arka planı", "Kapak bulanık ve hafif karartılmış olarak adanın içine yayılır"),
        i("Kapalı adada da göster", "Kapatırsan arka plan yalnızca açılmış adada görünür"),
    )),
    InfoSection("Görünüm", "Müzik efekti", "Müzik çalarken adanın alt kısmında hareketli renk", listOf(
        i("Alt kenarda renkli ışık", "Albüm kapağının rengine göre adanın altında yavaşça akan ışık; duraklatınca söner"),
        i("Bildirimde de renkli ışık", "Bildirim gelince adanın (ve cam bannerın) alt kenarında, bildirimi gönderen uygulamanın simge renginde akan ışık"),
    )),
)

private fun part2(): List<InfoSection> = listOf(
    InfoSection("Görünüm", "Özel arka plan", "Adanın içini galeriden seçtiğin fotoğraf veya videoyla doldur", listOf(
        i("Galeriden arka plan", "Fotoğraf adanın içini kaplar; yazıların okunması için hafif karartılır"),
        h("Fotoğraf nereye uygulansın"),
        i("Karartma"),
    )),
    InfoSection("Görünüm", "Ada olayları", "Genişletilmiş müzik adasında hava durumu, portal sahneleri ve kırılma efektleri", listOf(
    )),
    InfoSection("Görünüm", "Özel albüm kapağı", "Adanın içindeki kapak yerine galeriden fotoğraf göster", listOf(
        i("Galeriden kapak fotoğrafı", "Kapalı ve açık adadaki küçük albüm kapağı yerine seçtiğin fotoğraf görünür"),
    )),
    InfoSection("Görünüm", "Liquid Glass", "Kapalıyken ada düz, opak siyahtır", listOf(
        i("Cam efekti"),
        i("Şeffaflık"),
        i("Arka plan bulanıklığı"),
        i("Sıvı cam (kırılma)", "Kenarlarda içeriği büken, renk ayıran ve derinlik / parlama ekleyen gerçekçi cam"),
        i("Kırılma"),
        i("Renk ayrışması"),
        i("Parlama"),
    )),
    InfoSection("Görünüm", "Parıltı (Glow)", "Renk, çalan şarkının kapağından alınır", listOf(
        i("Parıltı efekti", "Kapalı ve açık halde adanın etrafında"),
        i("Yoğunluk"),
    )),
    InfoSection("Görünüm", "İlerleme çubuğu parıltısı", "Açık adadaki müzik panelinde, şarkı çubuğunda", listOf(
        i("Parıltı efekti", "Şarkı ilerleme çubuğu ışık yayar"),
        h("Parıltı nerede olsun"),
        i("Parıltı çubuğun içinde kalsın", "Kapalıyken ışık çubuğun dışına yayılır; açıkken dışarı taşmaz"),
        i("Yoğunluk"),
        i("Albüm kapağı rengi", "Parıltı, çalan şarkının kapağına göre değişir"),
    )),
    InfoSection("Görünüm", "Beğeni düğmesi", "Açık adadaki kalp, beğenildiğinde bu renkte olur", listOf(
        i("Yıldız simgesi", "Müzik çalarken ada genişleyince favorilere ekleme düğmesi kalp yerine yıldız şeklinde olur (beğenince içi dolar)"),
        i("Albüm kapağı rengi", "Kalp, çalan şarkının kapağıyla aynı renkte olur"),
    )),
    InfoSection("Görünüm", "Çerçeve", "İstediğin renk ya da albüm kapağı rengi", listOf(
        i("Çerçeve çizgisi"),
        i("Kalınlık"),
        i("Albüm kapağı rengi", "Çerçeve çizgisi, çalan şarkının kapağına göre renklenir"),
    )),
    InfoSection("Ada süsleri", "Renkli çerçeve", "Adanın kenarında parlayan, akan renkler", listOf(
        i("Çizgi kalınlığı"),
        i("Işık şiddeti"),
        i("Hız"),
        h("Müzik panelinde de"),
        i("Tuşlar temaya boyansın", "Müzik paneli açıkken tuşlar, şarkı adı, sanatçı, ilerleme çubuğu ve ekolayzır çerçevenin renkleriyle boyanır: ateşte sarı-turuncu-kırmızı akar, neonda seçtiğin renk parlar. Gökkuşağı modu açıksa o öncelikli"),
    )),
    InfoSection("Ada süsleri", "Adanın üstünde", "Adaya oturan küçük karakter", listOf(
    )),
    InfoSection("Ada süsleri", "Adadan sarkan süs", "İplerle adanın altına asılır, hafifçe sallanır", listOf(
    )),
    InfoSection("Ada süsleri", "Adanın içinde", "Boştaki adada dolaşan karakter", listOf(
    )),
)

private fun part3(): List<InfoSection> = listOf(
    InfoSection("Ada süsleri", "Mürekkep damlası", "Adadan ekrana doğru akan koyu sıvı", listOf(
        i("Adadan damlasın", "Ada renginde damlalar sarkar; biri ekranın altına kadar akıp iz bırakır, sonra kaybolur"),
        i("Aralık"),
    )),
    InfoSection("Ada süsleri", "Ekran kenarı ışığı", "Ekranın çevresinde sürekli akan renkler", listOf(
        i("Her zaman açık", "Asistan animasyonundaki renkli kenar ışığı sürekli görünür"),
    )),
    InfoSection("Oyuncak ada", "Fiziksel ada", "Telefonu eğince ada sıvı gibi yana kayar", listOf(
        i("Fiziksel ada", "Telefonu sağa sola eğince ada o yöne kayar, kenara çarpınca hafifçe ezilip seker. Ekran kapalıyken sensör dinlenmez. Açıkken cam bulanıklığı kapanır (ada kayabilsin diye pencere büyür)"),
        i("En fazla kayma"),
        i("Sallayınca jöle gibi titresin", "Telefonu sallayınca ada jöle gibi sarsılır"),
    )),
    InfoSection("Oyuncak ada", "Ada evcili", "Adanın altından sarkan küçük yaratık", listOf(
        i("Ada evcili", "Adanın kenarına tutunup sarkar. Şarj olurken ve gece uyur, müzik çalarken dans eder, uzun süre dokunmazsan sıkılıp esner, bildirim gelince heyecanla aşağı sarkar, adaya dokununca savrulur. Ada açılınca adanın içine saklanır"),
        i("Konuşsun", "Yanında küçük konuşma baloncukları çıkar: sabah \"Günaydın!\", pil azken \"Acıktım…\", müzikte notalar, gece \"Uyku vakti…\", bildirimde \"Yeni bildirim!\", sulayınca \"Teşekkürler!\". Ara sıra kendi kendine de konuşur"),
    )),
    InfoSection("Oyuncak ada", "Mıknatıs", "Şarj ve kulaklık bağlanınca", listOf(
        i("Şarj kablosunu yakalasın", "Şarja takınca ada, kabloya doğru aşağı sıvı bir damla uzatıp geri çeker"),
        i("Kulaklığı çeksin", "Kulaklık bağlanınca iki yandan iki damla mıknatıs gibi hızlanarak gelip adaya yapışır"),
    )),
    InfoSection("Oyuncak ada", "Dokununca savrulma", "Adaya dokununca rüzgâr yemiş gibi", listOf(
        i("Dokununca savrulma", "Adaya her dokunuşta ada rüzgâr yemiş gibi yassılaşıp titrer, kenarından toz uçuşur, evcil savrulur, gözler kısılır. Dokunmanın normal işi (müziği açmak vb.) aynen çalışır"),
        i("Şiddet"),
    )),
    InfoSection("Oyuncak ada", "Ada yüzü", "Boştaki adada gözler", listOf(
        i("Ada yüzü", "Boştaki adada iki göz: göz kırpar, etrafa bakınır, telefonu eğince o yöne bakar. Şarjda ve gece uyur, pil azken yorgun görünür, bildirimden sonra sevinir, sallayınca başı döner, dokununca gözlerini kısar. \"Boştayken de göster\" açık olmalı"),
    )),
    InfoSection("Oyuncak ada", "Dalga", "Adadan ekrana yayılan su halkaları", listOf(
        i("Bildirimde dalga", "Bildirim gelince adadan ekrana halka halka su dalgası yayılır"),
        i("Dokununca da dalga", "Adaya dokununca da dalga yayılır"),
    )),
    InfoSection("Oyuncak ada", "Pil sıvısı", "Boştaki ada pil kadar dolu bir cam kap", listOf(
        i("Pil sıvısı", "Boştaki adanın içi pil seviyesi kadar sıvıyla dolar (yeşil / sarı / kırmızı). Telefonu eğince sıvı çalkalanıp yana toplanır, şarjdayken kabarcıklar yükselir. \"Boştayken de göster\" açık olmalı"),
    )),
    InfoSection("Oyuncak ada", "Bildirim yiyen ada", "Bildirim baloncuğu uçup adaya girer", listOf(
        i("Bildirim yiyen ada", "Bildirim gelince ekranın kenarından renkli bir baloncuk süzülüp adaya çekilir, ada onu yutkunarak yer"),
        i("Yedikçe doysun", "Her yediği bildirimle ada biraz şişer (en fazla 4). Adaya dokununca ya da 20 dakika sonra eski haline döner"),
    )),
    InfoSection("Oyuncak ada", "Çekiştirme", "Adayı çekip bırak, lastik gibi fırlasın", listOf(
        i("Çekiştirme", "Adayı parmağınla aşağı çekince lastik gibi uzar, bırakınca geri fırlayıp sallanır. Boştaki ada da dokunuş alır: adanın hemen çevresine dokunmak durum çubuğuna geçmez"),
    )),
    InfoSection("Oyuncak ada", "Gün ışığı rengi", "Adanın çevresindeki hale saate göre renk değiştirir", listOf(
        i("Gün ışığı rengi", "Şafakta turuncu, gündüz beyaz, gün batımında pembe ve mor, gece lacivert bir hale adayı sarar. Renk dakika dakika yumuşakça geçer"),
        i("Parlaklık"),
    )),
)

private fun part4(): List<InfoSection> = listOf(
    InfoSection("Oyuncak ada", "Gizli oyun", "Ada Pong", listOf(
        i("Gizli oyun", "Boştaki adaya hızlıca 5 kez dokununca \"Ada Pong\" açılır: ada raket olur, parmağınla sağa sola kaydırıp topu kaçırmamaya çalışırsın. Adaya tek dokunuşun başka bir işi varsa (favoriler, çeviri) bu kısayol çalışmaz"),
    )),
    InfoSection("Oyuncak ada", "Adayı fırlat", "Adayı yana savur, ekranda seksin", listOf(
        i("Adayı fırlat", "Boştaki adayı parmağınla hızlıca sağa ya da sola savur: ada ekrana fırlar, kenarlara çarpıp seker, yerçekimiyle düşer, sonra yaylanarak yerine döner. \"Boştayken de göster\" açık olmalı"),
    )),
    InfoSection("Oyuncak ada", "Ada saksısı", "Adadan sarkan, her gün sulanan bitki", listOf(
        i("Ada saksısı", "Adanın kenarından küçük bir sarmaşık sarkar. Her gün adaya ilk dokunuşun onu sular ve bir boy büyür. Bir gün sulamazsan yaprakları düşer, iki gün sulamazsan solar ve küçülmeye başlar. Yedi gün üst üste bakarsan çiçek açar"),
    )),
    InfoSection("Oyuncak ada", "Mevsim modu", "Kar, çiçek, güneş, yaprak", listOf(
        i("Mevsim modu", "Kışın adanın kenarında kar birikir ve kar taneleri süzülür, ilkbaharda kenarında çiçekler açar ve yapraklar uçuşur, yazın çevresinde ışıltılar parlar, sonbaharda adadan sarı-turuncu yapraklar dökülür"),
    )),
    InfoSection("Oyuncak ada", "Ritim kısayolları", "Adaya ritimle dokun, uygulama açılsın", listOf(
        i("Ritim kısayolları", "Boştaki adaya kendi belirlediğin ritimle dokununca seçtiğin uygulama açılır. Adaya tek dokunuşun başka bir işi varsa (favoriler, çeviri) o da çalışmaya devam eder"),
    )),
    InfoSection("Oyuncak ada", "Kutlama anları", "Konfeti zamanı", listOf(
        i("Kutlama anları", "Şarjda pil %100 olunca, doğum gününde ve yılbaşında adadan konfeti fışkırır, evcil dans eder, yüz sevinir"),
        i("Doğum günü: gün"),
        i("Doğum günü: ay"),
    )),
    InfoSection("Oyuncak ada", "Karalama tahtası", "Adaya kendi çizimin", listOf(
        i("Karalama tahtası", "Boştaki adayı basılı tutunca çizim tahtası açılır. Çizdiğin şey boştaki adada küçük bir çıkartma olarak durur. Basılı tutmanın başka bir işi varsa (favoriler, çeviri) aşağıdaki düğmeyi kullan"),
    )),
    InfoSection("Oyuncak ada", "Ada ziyaretçileri", "Gelen misafirleri yakala, albümü doldur", listOf(
        i("Ada ziyaretçileri", "Günde birkaç kez adanın yanına bir misafir gelir: kelebek, serçe, uğur böceği, arı; gece ateş böceği ve kayan yıldız; çok nadiren UFO ya da altın kelebek. Yaklaşık 25 saniye kalır. O sırada adaya dokunursan yakalarsın"),
    )),
    InfoSection("Oyuncak ada", "Salla, zar at", "Telefonu iki kez salla", listOf(
        i("Salla, zar at", "Telefonu kısa aralıkla iki kez sallayınca adanın altına zar düşer, yuvarlanıp sonucu gösterir. Evcil sonucu söyler"),
    )),
    InfoSection("Oyuncak ada", "Nefes egzersizi", "Ada seninle nefes alır", listOf(
        i("Nefes egzersizi", "Başlatınca ada yavaşça şişip söner, altında \"Nefes al / Tut / Nefes ver\" yazar. Evcil de seninle nefes alır. Boştaki adayı basılı tutarak da başlatabilirsin (basılı tutmanın başka işi yoksa). Bitirmek için adaya dokun"),
        i("Süre"),
    )),
    InfoSection("Oyuncak ada", "Gece lambası", "Gece şarjda sıcak ışık ve büyük saat", listOf(
        i("Gece lambası", "Saat 22:00 ile 07:00 arasında telefon şarjdayken ve ekran açıkken adanın çevresi sıcak bir ışıkla parlar, altında büyük bir saat, gün ve şarj yüzdesi görünür"),
        i("Parlaklık"),
    )),
    InfoSection("Oyuncak ada", "Ada gölgesi", "Ada ekranın üstünde yüzüyormuş gibi", listOf(
        i("Ada gölgesi", "Adanın altında yumuşak bir gölge olur. Telefonu eğdikçe gölge kayar, ada ekranın üstünde yüzüyormuş gibi derinlik kazanır. Koyu duvar kağıtlarında daha az fark edilir"),
    )),
)

private fun part5(): List<InfoSection> = listOf(
    InfoSection("Oyuncak ada", "Cep hissi", "Ekran açılınca ada yerine düşer", listOf(
        i("Cep hissi", "Ekran açılınca ada kamera deliğinden düşüp yaylanarak yerine oturur. Uzun süre boş kalınca küçülüp kamera deliğine girer, bir olay gelince fırlayıp çıkar"),
        i("Deliğe girme süresi"),
    )),
    InfoSection("Hareket", "Açılış animasyonu stili", "Bildirime ya da müzik uygulamasına dokununca", listOf(
        i("Klasik", "Adadan siyah ekran çıkıp ekranı kaplar"),
        i("Atom ada", "Adadan açılan siyah kart huni gibi genişleyip ekranı kaplar, ardından uygulama açılır (OriginOS tarzı)"),
        i("Hız"),
    )),
    InfoSection("Hareket", "Hap tasarımları", "Arama ve müzik adası için alternatif görünüm", listOf(
        i("Arama hapı", "KÜÇÜLTÜLMÜŞ adada: solda yeşil, sağda kırmızı yuvarlak düğme; ortada süre / isim ve hareketli ses dalgası. Genişletilmiş görünüm değişmez. Aktif aramada yeşil düğme hoparlörü açıp kapatır"),
        i("Müzik hapı", "KÜÇÜLTÜLMÜŞ adada: solda albüm kapağı, ortada şarkı adı, renkli dalga ve ilerleme çubuğu, sağda önceki / duraklat / sonraki düğmeleri. Genişletilmiş panel değişmez"),
        i("Orta boy müzik adası", "Müzik çalarken ada küçülmez; ne çok küçük ne çok büyük, orta boyda açık kalır: üstte kapak, şarkı adı ve dalga, ortada ilerleme çubuğu, altta beğeni, geri, oynat / duraklat, ileri ve çıkış düğmeleri. Boş yerine dokununca tam boy panel açılır. Açıkken müzik hapı yerine bu görünüm kullanılır"),
        i("Orta boy genişlik"),
        i("Orta boy yükseklik"),
        i("Adanın boyutuna uydur", "Açıkken ada büyümez: hap tasarımları (arama ve müzik) ayarladığın küçültülmüş ada boyutuna sığacak şekilde ölçeklenir. Kapalıyken ada hap boyutuna (müzikte ~344×100 dp) büyür. Kapalı ada küçükse (varsayılan 120×32) yazılar küçük kalır; okunaklı olması için adanın genişlik / yüksekliğini artır"),
        h("Müzik hapı boyutu"),
        i("Genişlik"),
        i("Yükseklik"),
        i("Albüm kapağına göre renk", "Dalga renkleri çalan şarkının kapağından türetilir; şarkı değişince renk de değişir"),
        h("Kapak yoksa kullanılacak renkler"),
        h("Sol dalga"),
        h("Orta dalga"),
        h("Sağ dalga"),
    )),
    InfoSection("Hareket", "Animasyon", "Açılma ve küçülme geçişi", listOf(
        i("Animasyonlar", "Kapatırsan ada anında açılır/kapanır"),
        i("Müzik adası animasyonu", "Müzik çalarken ada genişlerken içerik bulanıktan netleşerek belirir; küçülürken bunun tersi olur"),
        i("Albüm kapağı dönme animasyonu", "Ada açılırken kapak bir kez 360° döner, küçülürken ters yöne döner. Şarkı değişince kapak dönerek değişir (sonraki: bir yöne, önceki: ters yöne)"),
    )),
    InfoSection("Hareket", "Kilit ekranı noktası", "Telefon kilitliyken adanın yanında yanıp sönen renkli nokta", listOf(
        i("Kilit ekranı noktası", "Yalnızca ekran açık ve kilitliyken görünür; kilit açılınca ya da ekran kapanınca kaybolur"),
        h("Nokta"),
        i("Boyut"),
        i("Adaya uzaklık (sol, eksi = adanın içi)"),
        i("Dikey konum"),
        h("Parıltı (glow)"),
        i("Parıltı", "Noktanın çevresinde yumuşak ışık halesi"),
        i("Parıltı büyüklüğü"),
        i("Parıltı şiddeti"),
        h("Yanıp sönme"),
        i("Yanıp sönsün", "Kapalıysa nokta sabit yanar"),
        i("Tur süresi (küçük = hızlı)"),
        i("Sönükken parlaklık"),
    )),
    InfoSection("Hareket", "Fener (flaş) göstergesi", "Fener açılınca adanın solunda simge çıkar; dokununca ışık kartı açılır", listOf(
        i("Fener göstergesi", "Simgeye dokun: kart açılır. Yukarı/aşağı kaydır: parlaklık (telefon kademeli flaşı destekliyorsa gerçek flaş seviyesi de değişir). Sağa/sola kaydır: ışık huzmesi genişliği. Alttaki fener simgesine dokun: feneri kapatır"),
    )),
    InfoSection("Hareket", "Kilit ekranı Face ID animasyonu", "Telefon kilitliyken ada kareye dönüşüp Face ID taraması ve ✓ onayı oynatır", listOf(
        i("Face ID animasyonu", "Ekran açılıp telefon kilitliyken bir kez oynar: ada kare olur, yüz atar, halka 3B döner, ✓ çizilir, ada hap şekline döner. Kilit açılırsa ✓ aşamasına hızlanır. Animasyon sırasında kilit noktası gizlenir"),
        i("Küçültülmüş Face ID", "Açıkken ada kareye dönüşmez, kapalı boyutunda kalır: solda kilit, sağda Face ID yüzü. Kilit açılınca yüz dönen bir halkaya dönüşür, içinde ✓ kalemle çizilir ve kilit açılır"),
        h("Renk"),
        h("Boyut ve konum"),
        i("Kare boyutu"),
        i("Dikey konum"),
        h("Hız"),
        i("Animasyon hızı (1x = ekran kaydı)"),
    )),
    InfoSection("Yeni ada özellikleri", "Ağ: VPN ve hız", "VPN kalkanı ve anlık internet hızı", listOf(
        i("VPN göstergesi", "VPN bağlanınca adada yeşil kalkanlı \"VPN · Açık\" hapı çıkar. VPN beklenmedik şekilde koparsa (3 sn içinde geri gelmezse) telefon iki kez titrer ve kırmızı \"VPN · Koptu\" uyarısı görünür. VPN açıkken boştaki adanın solunda küçük yeşil kalkan durur (\"Boştayken de göster\" açıksa). İzin gerektirmez."),
        h("Ağ hızı göstergesi"),
        i("Kapalı", "Hız gösterilmez"),
        i("Yalnızca aktarım varken", "İndirme / yükleme 30 KB/sn'yi geçince görünür, bitince 3 sn sonra kaybolur"),
        i("Her zaman", "Ada boştayken hız hep görünür"),
    )),
    InfoSection("Yeni ada özellikleri", "Odak zamanlayıcısı", "Çalışma / mola döngüsü, adada dolan halka", listOf(
        i("Odak süresi"),
        i("Kısa mola"),
        i("Uzun mola (4 odakta bir)"),
        i("Odakta bildirimleri gizle", "Odak süresince adadaki bildirimler gizlenir (Gizlilik ayarlarındaki gizleme biçimiyle: uygulama adı, bulanık ya da yem metin). Molada normal görünür."),
    )),
    InfoSection("Yeni ada özellikleri", "Basılı tutarak aç", "Olay varken ada dokununca değil, basılı tutunca genişlesin", listOf(
        i("Basılı tutunca genişlesin", "Adada bir olay (müzik, arama, zamanlayıcı, bildirim...) varken kısa dokunuş adayı açmaz; parmağını basılı tutunca ada yavaşça büyür ve süre dolunca tamamen açılır"),
        i("Basılı tutma süresi"),
        i("Basılıyken büyüme"),
    )),
    InfoSection("Yeni ada özellikleri", "Alarm ve zamanlayıcı", "Saat uygulamasının alarmı / zamanlayıcısı ve adadan zamanlayıcı kurma", listOf(
        i("Alarm, zamanlayıcı ve kronometre adası", "Saat uygulamasında zamanlayıcı ya da kronometre çalışırken kapalı adada turuncu simge ve süre görünür (zamanlayıcı halkası süre azaldıkça küçülür); dokununca duraklat ve kapat düğmeleri açılır. Alarm kurunca ada kısa süre alarm saatini gösterir; alarm çalınca ada genişler: ertele (zZ) ve kapat. Süre dolunca ada kendiliğinden genişler"),
        h("Adadan zamanlayıcı kur"),
        i("Açık hali ekranı kaplasın", "Açıkken zamanlayıcı, kronometre ve alarm adası ekranın iki kenarına kadar uzanır (kenarlarda az boşluk). Kapalıyken ekranın ~%84'ü kadar olur"),
        i("Süre dolunca ses", "Adadan kurulan zamanlayıcı bitince alarm sesi çalar ve telefon titrer (ada kapatılana kadar, en çok 1 dakika)"),
    )),
    InfoSection("Yeni ada özellikleri", "Durum haplarının boyutu", "Şarj, Rahatsız Etmeyin, ağ, uçak modu, düşük pil hapı ne kadar uzun olsun", listOf(
        i("Şarj hapı genişliği"),
        i("Diğer hapların genişliği"),
    )),
)

private fun part6(): List<InfoSection> = listOf(
    InfoSection("Yeni ada özellikleri", "Kilit simgesi", "Kilitliyken adanın solunda kilit, açılınca açık kilit", listOf(
        i("Kilit simgesi", "Ekran kilitliyken boştaki adanın solunda beyaz kilit görünür. Kilit açılınca (ana ekrana geçince) kilit açılır ve kısa süre sonra kaybolur. Face ID animasyonu sürerken gizlenir"),
    )),
    InfoSection("Yeni ada özellikleri", "Arama görünümü", "Cevaplanan ve gelen aramanın küçük ada görünümü", listOf(
        i("Yalnızca yeşil telefon", "Cevaplanan arama küçükken adada yalnızca yeşil telefon simgesi görünür. Kapalıyken telefonun yanında süre, sağda ses dalgası görünür"),
        i("Ses dalgası tamamen yeşil", "Açıkken sağdaki ses dalgası tamamen yeşil olur. Kapalıyken yeşilden turuncuya renk geçişlidir"),
        i("Gelen aramayı küçült", "Gelen aramada cevaplamadan / reddetmeden adayı yukarı kaydırınca ada küçülür: solda arayan kişinin resmi, sağda gri ters telefon. Dokununca ya da aşağı kaydırınca yeniden açılır"),
    )),
    InfoSection("Yeni ada özellikleri", "Mikrofon / kamera noktası", "Ortada olay varken nokta sağdaki baloncukta", listOf(
        i("Nokta sağda", "Bir uygulama mikrofonu ya da kamerayı kullanırken ortadaki adada zaten bir olay (müzik, arama...) varsa, turuncu / yeşil nokta ortayı almaz; adanın sağında ayrı bir dairede görünür"),
    )),
    InfoSection("Yeni ada özellikleri", "Rahatsız Etmeyin kartı", "Hapa dokununca genişlesin, aya dokununca aç / kapat", listOf(
        i("Dokununca genişlesin", "Rahatsız Etmeyin hapına dokununca ada genişler: solda dairede ay, ortada \"Rahatsız Etmeyin\", sağda Açık / Kapalı"),
        i("Aya dokununca aç / kapat", "Genişlemiş kartta aya dokununca Rahatsız Etmeyin açılır ya da kapanır (bildirim erişimiyle çalışır)"),
    )),
    InfoSection("Yeni ada özellikleri", "Kulaklık, ses kaydı, skor", "Kulaklık küçük adası, ses kaydı adası, takım logoları", listOf(
        i("Kulaklık önce küçük", "Kulaklık bağlanınca ada önce küçük kalır: solda kulaklık, sağda yeşil pil halkası. Dokununca kart açılır (ad, \"Bağlandı\" ve pil yüzdesi)"),
        i("Ses kaydı adası", "Ses kaydedici uygulamayla kayıt yaparken kapalı adada kırmızı ses dalgası ve süre görünür; dokununca durdur düğmesi açılır"),
        i("Takım logoları", "Maç skoru adasında takımların logoları görünür (internetten bulunur, bellekte saklanır). Logo bulunamazsa takımın baş harfi görünür"),
        i("Düşük pil hapına dokununca genişlesin", "\"Düşük pil uyarısı genişlesin\" kapalıyken ince hapa dokununca ada büyük karta açılır; kartta sağdaki pil kapsülüne dokununca düşük güç modu açılır"),
    )),
    InfoSection("Renk düzenleyici", "Renk düzenleyici", "Uygulamadaki her şeyin rengini tek tek ayarla", listOf(
    )),
    InfoSection("Renk düzenleyici", "Renk: Ada ve vurgu renkleri", "Ayarlarda zaten bulunan renkler: ada gövdesi, vurgu, ekolayzır, kalp, çerçeve, kilit noktası, sözler", listOf(
    )),
    InfoSection("Ses", "Ses efektleri", "Adada olaylara göre kendi seçtiğin sesler çalsın", listOf(
        i("Ses efektleri", "Kapalıyken hiçbir ses çalmaz; seçtiğin sesler ve ayarlar silinmez"),
    )),
    InfoSection("Uygulama", "Uygulamaya özel gizleme", "Seçtiğin uygulamalar ön plandayken ada gizlenir", listOf(
        i("Seçili uygulamalarda gizle", "Oyun, kamera ya da video uygulaması gibi adanın görünmesini istemediğin uygulamaları seç. Ada gizliyken dokunuşları da engellemez"),
    )),
    InfoSection("Uygulama", "Yedekleme", "Ayarlarını dosyaya kaydet ya da dosyadan geri yükle", listOf(
    )),
    InfoSection("Uygulama", "Dokunsal geri bildirim", "Ayar ekranındaki anahtar ve kaydırıcılar", listOf(
        i("Titreşim", "Anahtarı çevirince ve kaydırıcıyı sürüklerken hafif titreşim verir"),
    )),
    InfoSection("Uygulama", "Sıfırla", "Tüm ayarları başlangıç değerine döndür", listOf(
    )),
)

private fun part7(): List<InfoSection> = listOf(
    InfoSection("Uygulama", "Şarkı sözleri", "LRCLIB'de aranır (şarkı adı ve sanatçı gönderilir)", listOf(
        i("Şarkı sözleri", "Başlık, sanatçı ve süre sunucuya gönderilir. Bildirimi olan her oynatıcıyla çalışır (Spotify, YouTube Music, Yandex Müzik, VK…)."),
    )),
    InfoSection("Uygulama", "Müzik uygulamasını aç", "Ada üzerinden çalan müziğin uygulamasına geç", listOf(
        h("Ada genişletilmişken"),
        i("Albüm kapağına basınca"),
        i("Şarkı ismine basınca"),
        i("Adanın herhangi bir yerine basınca", "Düğmeler hariç; açıkken ada küçülmez, müzik uygulaması açılır"),
        h("Ada küçültülmüşken"),
        i("Adaya basınca", "Açıksa adayı genişletmek için aşağıdaki seçenekleri kullan"),
        i("Adaya basılı tutunca"),
        i("Basılı tutunca ada genişlesin", "Adaya basınca müzik uygulaması açıldığı için"),
        i("Açılış animasyonu", "Müzik uygulaması açılırken ada animasyonu oynar (stil: Hareket > Açılış animasyonu stili)"),
        i("Basılı tutma süresi"),
    )),
    InfoSection("Uygulama", "Açık kalma süresi", "Müzik adasını genişletince (dokunarak, kaydırarak ya da yan baloncuktan)", listOf(
        i("Genişletilmiş kalma süresi"),
    )),
    InfoSection("Uygulama", "Kaydırma hareketleri", "Müzik adada görünürken", listOf(
        i("Aşağı kaydırınca genişlesin", "Küçük adayı aşağı çekince açılır"),
        i("Yukarı kaydırınca küçülsün", "Açık adayı yukarı kaydırınca küçülür"),
        i("Yana kaydırınca şarkı değişsin", "Müzik çalarken ada küçükken de açıkken de: sağdan sola kaydır = sonraki şarkı, soldan sağa = önceki şarkı"),
    )),
    InfoSection("Uygulama", "Müzik uygulamasındayken", "Çalan müziğin uygulamasını açıkken adada şarkı gösterilmesin", listOf(
        i("Müzik uygulamasında adayı gizle", "Çalan müziğin uygulamasındayken ada müziği göstermez. \"Müzikten çıkış animasyonu\" da açıksa çıkınca önce panel adaya girip yutulur, sonra şarkı görünür"),
    )),
    InfoSection("Uygulama", "Müzikten çıkış", "Müzik uygulamasından çıkınca panel adaya küçülür, ada şarkıyı yutup gösterir", listOf(
        i("Müzikten çıkış animasyonu", "Müzik çalarken o uygulamadan çıkınca çalışır"),
        i("Hız"),
        i("Akıcı yutma", "Uygulama penceresi bulanıklaşarak küçülür ve adanın altına girer, ada aşağı doğru şişip yutar ve yaylanarak eski haline döner. Müzik uygulamasını adadan açarken de tersi oynar: ada şişer, pencere altından bulanık çıkıp ekranı kaplar. Açıksa aşağıdaki yutma ve panel seçenekleri kullanılmaz"),
        i("Ada yutma efekti", "Panel adaya varınca ada kısa süre büyüyüp onu yutar. Kapalıysa ada büyümez (Uzayan ada açıksa onun yerine o oynar)"),
        i("Uzayan ada", "Ada yukarı doğru (ekran kenarına kadar) yumuşakça büyür, sonra normal boyutuna döner. Açıksa kısa büyüme efekti yerine bu oynar; hız yukarıdaki ayardan alınır. Bu sırada cam / çerçeve efektleri düz renkle çizilir"),
        h("Panel animasyonu"),
        i("Klasik panel", "Uzun panel küçülerek adaya girer"),
        i("Atom ada", "Uygulama kartı eğilip huni gibi adaya çekilir (OriginOS tarzı)"),
        i("Atom ada: tam ekran başlangıç", "Müzik uygulamasından çıkarken önce siyah panel tüm ekranı kaplar, sonra atom ada animasyonuyla küçülüp adaya girer"),
    )),
    InfoSection("Uygulama", "Sıfırla", "Söz ayarlarını başlangıç değerine döndür", listOf(
    )),
    InfoSection("Uygulama", "Nerede görünsün", "İstediğin kadarını aynı anda açabilirsin", listOf(
        i("Genişletilmiş adada", "Şarkı adı ile ilerleme çubuğu arasında"),
        i("Kapalı adada (ortada)", "Albüm kapağı ile equalizer arasındaki boşlukta; ada sözler için genişler"),
        i("Adanın dışında", "Adayı açmadan, ekranın üstünde ayrı görünür"),
    )),
    InfoSection("Uygulama", "Genişletilmiş ada", "Panelin içindeki sözler", listOf(
        i("Sözler sağda, kontroller solda", "Ada genişleyince müzik kontrolleri (kapak, şarkı adı, ilerleme çubuğu, düğmeler) sol tarafta, şarkı sözleri sağ tarafta görünür. O an söylenen satırın altında sıradaki satır soluk olarak durur. Panel sözler için uzamaz"),
        i("Yazı boyutu"),
        i("En fazla satır"),
    )),
    InfoSection("Uygulama", "Kapalı ada", "Tek satır; sığmazsa söylendikçe yana kayar", listOf(
        i("Yazı boyutu"),
        i("Ada genişliği (sözler varken)"),
    )),
    InfoSection("Uygulama", "Adanın dışı", "Ayrı, dokunulmaz söz katmanı (ada genişleyince gizlenir)", listOf(
        i("Yazı boyutu"),
        i("En fazla satır"),
        i("Genişlik"),
        i("Yükseklik (ekranın üstünden)"),
        i("Yatay konum"),
    )),
    InfoSection("Uygulama", "Renk", "Üç yerde de ortak", listOf(
        i("Albüm kapağı rengi", "Söz rengi, çalan şarkının kapağına göre değişir"),
        i("Söylenmemiş kısım"),
    )),
)

private fun part8(): List<InfoSection> = listOf(
    InfoSection("Uygulama", "Dolum ve senkron", "Satır şarkıyla birlikte soldan sağa dolar", listOf(
        i("Söylendikçe dolsun", "Kapatırsan satırın tamamı parlak görünür"),
        i("Senkron kaydırma"),
    )),
    InfoSection("Uygulama", "Parıltı (Glow)", "Söylenen kısmın etrafında", listOf(
        i("Parıltı efekti"),
        i("Seviye"),
    )),
    InfoSection("Uygulama", "Satır geçiş animasyonu", "Bir satırdan diğerine geçerken", listOf(
        i("Geçiş animasyonları", "Kapatırsan satırlar anında değişir"),
        i("Süre"),
    )),
    InfoSection("Uygulama", "Sıfırla", "Söz ayarlarını başlangıç değerine döndür", listOf(
        i("Doygunluk"),
        i("Parlaklık"),
        h("Kayıt önizlemesi (isteğe bağlı)"),
        i("Bu olayda çal", "Kapalıyken ses seçili kalır ama çalmaz"),
        i("Ses yüksekliği"),
        i("Yalnızca bir kısmını çal", "Kapalıyken sesin tamamı çalar. Açıkken başlangıç ve bitişi sen seçersin"),
        i("Başlangıç"),
        i("Bitiş"),
    )),
)

/** Bütün ayar sayfaları (bilgi sayfası için). */
internal val SETTINGS_INFO: List<InfoSection> by lazy { part0() + part1() + part2() + part3() + part4() + part5() + part6() + part7() + part8() }
