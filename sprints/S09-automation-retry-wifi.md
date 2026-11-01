# S09 — Otomasyon 2: retry, başarısız ekranı, "Eve gelince"

**Spec:** 9.4 (retry kuralı, WIFI_CONNECT), 10.3 (başarısız satırı), 10.4, 12.2 (push + predictive back), 16 (ScheduleRunner), 17 faz 8 (ikinci yarı) · **Kararlar:** K10, K12, K13

## Amaç
Zamanlayıcı başarısız olursa ayarlı aralıklarla yeniden dener, vazgeçince bildirim gönderir; detay ekranı tüm denemeleri gösterir. "Eve gelince" tetikleyicisi ev Wi-Fi'ına bağlanınca çalışır.

## Doğrulanacak
- Doze'da 2 dk retry aralığının `setExactAndAllowWhileIdle` ile tutup tutmadığı; tutmuyorsa `setAlarmClock` (K13).
- Android 17'de `registerNetworkCallback(request, PendingIntent)` callback'inde SSID okuma koşulları; One UI 9 pil yönetiminde güvenilirlik.
- `POST_NOTIFICATIONS` + bildirim kanalı.

## Görevler
1. **Retry zinciri:** başarısız deneme → `retryIntervalMin` sonra `RetryAlarmReceiver` (K13'e göre alarm türü); toplam `giveUpMin` aşılınca `FAILED`; her deneme `attemptsJson`'a `{at, outcome, reason}` (`OK`, `NO_REPLY`, `AGENT_NO_REPLY`, `NO_WIFI`, `AGENT_ERROR(code)`); Wi-Fi yokken deneme `NO_WIFI` sayılır ve devam eder; BootReceiver devam eden zincirleri yeniden kurar.
2. **Notifier:** `FAILED` → bildirim "Çalışma PC uyanmadı" (aksiyona göre: "kapanmadı", "uyutulamadı"…), ikon `sym_*` vector; dokununca `ScheduleFailureScreen` (deep link). Başarıda bildirim yok.
3. **ScheduleFailureScreen (10.4):** push geçişi (sağdan, alttaki %30 paralaks + kararma) + `PredictiveBackHandler`; sürekli canlı arka plan; 96 kırmızımsı daire + ikon, başlık, zaman aralığı; deneme listesi (dikey bağlantı çizgili, mono zamanlar); "Kontrol et" kartı (yalnız WAKE); "Tamam" + "Şimdi tekrar dene" (elle akış, WakeCoordinator/PowerCoordinator).
4. **Otomasyon kartında** "⚠ Son çalışma başarısız · Bugün 18:42 · Detay" satırı → ekran; sonraki başarılı çalışmada animasyonla kaybolur.
5. **WIFI_CONNECT:** `WifiTriggerReceiver` (PendingIntent network callback); SSID eşleşirse çalışır; aynı zamanlayıcı 10 dk içinde tekrar tetiklenmez; `skipNextEvent`; konum izni yoksa WIFI seçeneği pasif + ipucu. Editor'de WIFI tetikleyicisi açılır (varsayılan SSID = şu anki ağ); kart etiketi "⌂ Eve gelince · Ev-WiFi".
6. **Debug › Zamanlayıcı** genişler: devam eden retry zinciri (sonraki deneme zamanı), "Sahte FAILED üret" (bildirim + ekranı denemek için), WIFI tetik günlüğü.

## Otomatik testler (Claude koşturur)
- ScheduleRunner (bölüm 16): 2/10 dk ile 6 deneme (t=0,2,4,6,8,10); `NO_WIFI` sayılır ve devam eder; zaten açık → `SKIPPED_ALREADY`; ilk denemede başarı → tek kayıt; giveUp sınırı; boot sonrası zincirin devamı.
- WIFI: SSID eşleşme, 10 dk tekrar engeli, skipNextEvent.
- Robolectric Compose: başarısız ekranı deneme listesi ve başlık metinleri; "Kontrol et" yalnız WAKE.

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | PC'nin Ethernet kablosunu çıkar (PC kapalı/uykuda). "Aç" zamanlayıcısı şu an + 2 dk. Telefon ekranını kapat. | ~2, 4, 6, 8, 10, 12. dakikalarda denemeler (Debug'da görünür); 6 denemeden sonra bildirim "Çalışma PC uyanmadı". |
| 2 | Bildirime dokun | Başarısız ekranı sağdan kayarak açılır: "6 deneme · 2 dk arayla", "10 dk sonra vazgeçildi", her satırda saat + "Paket gitti · cevap yok"; "Kontrol et" kartı. |
| 3 | Kenardan geri kaydırmaya başla, yarıda bırak / sonuna kadar götür | Ekran parmağı takip eder; yarıda geri oturur, sonuna kadar götürünce kapanır. |
| 4 | Otomasyon kartı | "⚠ Son çalışma başarısız · … · Detay" satırı. |
| 5 | Kabloyu tak, "Şimdi tekrar dene" | Elle uyandırma akışı başlar, PC uyanır. |
| 6 | Bir sonraki zamanlayıcı başarılı çalışsın | Kırmızı satır animasyonla kaybolur. |
| 7 | 3. denemede kabloyu tak | Sonraki denemede başarı, bildirim yok, kayıt `SUCCESS` (3 deneme). |
| 8 | Denemeler sürerken telefonun Wi-Fi'ını 3 dk kapat | O denemeler "Wi-Fi yok" olarak kaydedilir, zincir devam eder. |
| 9 | "Eve gelince · Ev-WiFi · Aç" kur. Wi-Fi'dan çık (Wi-Fi kapat ya da evden uzaklaş), PC uyusun, Wi-Fi'a geri bağlan | Bağlanınca birkaç sn içinde PC uyanır. |
| 10 | 10 dk içinde Wi-Fi'ı kapat-aç | İkinci kez tetiklenmez. |
| 11 | "Sonrakini atla" sonra Wi-Fi'a bağlan | Çalışmaz, kayıt `SKIPPED_BY_USER`, atlama temizlenir. |
| 12 | Retry sırasında telefonu yeniden başlat | Zincir kaldığı yerden devam eder. |

## Bitti tanımı
Testler yeşil; K13 kararı uygulandı ve raporlandı; cihaz testi #1–12 geçti.

## Sprint sonu raporu
_2026-11-01 · Claude_

### Doğrulananlar
- **K13 (Doze, retry aralığı):** S08'de Kaan'ın telefonunda okunan değerler geçerli: `allow_while_idle_quota=72`/saat ve alarm başına 10 sn ağ izni.
  - Varsayılan zincir (2 dk arayla 10 dk) 6 alarm kuruyor, kotaya rahat sığıyor.
  - Retry alarmları da `setExactAndAllowWhileIdle`; **`setAlarmClock`'a gerek yok** (durum çubuğunda alarm ikonu çıkmaz).
- **`registerNetworkCallback(request, PendingIntent)` + SSID (Android 12+):** belgeye göre callback'ler varsayılan olarak konum bilgisini (SSID dahil) içermiyor ([NetworkCallback](https://developer.android.com/reference/android/net/ConnectivityManager.NetworkCallback)). Bilgiyi açan `FLAG_INCLUDE_LOCATION_INFO` yalnız callback nesnesiyle kullanılabiliyor, PendingIntent yolunda yok. Arka planda SSID'nin gizli gelmesi bekleniyor.
  - Çözüm: alıcı ağı bayraklı `NetworkMonitor` callback'inden okuyor.
  - SSID yine gizliyse **ağ imzasıyla** (ağ geçidi + DHCP sunucusu + önek; konum izni gerektirmez) tanıyor. İmza uygulama ön plandayken SSID okununca öğreniliyor.
  - Hangisinin işe yaradığı Debug › Zamanlayıcı › "Eve gelince günlüğü"nde yazıyor ("SSID okundu" / "SSID gizli · ağ imzasından"). One UI 9'daki gerçek davranışı cihaz testi #9 gösterecek.
- **`POST_NOTIFICATIONS` + kanal:** S08'deki "Zamanlayıcılar" kanalı kullanılıyor. İzin yoksa bildirim sessizce atlanıyor; Debug'daki test butonları izni istiyor.

### Yapılanlar
- **Retry zinciri (ScheduleRunner):**
  - Başarısız deneme → `retryIntervalMin` sonra `RetryAlarmReceiver` (exact, çalışma başına tek alarm) → `VerifyWorker`.
  - Denemeler ilk denemeye göre t = 0, 2, 4, 6, 8, 10 dk'da yapılıyor. Sıradaki deneme `giveUpMin`'i aşacaksa vazgeçiliyor → `FAILED`.
  - Her deneme `attemptsJson`'a `{at, outcome, reason}` olarak ekleniyor. Wi-Fi yoksa `NO_WIFI` sayılıp zincir sürüyor.
  - Zincir sürerken PC açılmışsa (örneğin elle) ya da kapanmışsa (Kapat/Uyku/Hazırda) başarı sayılıyor, komut tekrar gitmiyor.
  - Agent eşli değilse ya da cihaz silinmişse tekrar denenmiyor. Zamanlayıcı kapatılırsa zincir sessizce bitiyor.
  - Aynı deneme iki kez çalışmıyor: zamanlayıcı başına kilit + deneme sırası kontrolü (alarm ve WorkManager tekrarı birlikte gelse bile). Aynı planlı çalışma için ikinci kayıt açılmıyor.
  - `BootReceiver` (açılış, güncelleme, saat değişimi) süren zincirlerin alarmını yeniden kuruyor. Vakti geçmiş deneme hemen yapılıyor, süresi dolmuş zincir `FAILED` oluyor.
  - Kapat/Uyku/Hazırda doğrulaması sıradaki denemeye taşmıyor: `min(max(60 sn, 90 sn), aralık − 5 sn)`.
- **Bildirimler:**
  - `FAILED` → "Ofis PC uyanmadı" (aksiyona göre: kapanmadı, uyutulamadı…) + "6 deneme · 10 dk sonra vazgeçildi". Dokununca başarısız ekranı açılıyor (uygulama açıksa da). Başarıda bildirim yok.
  - "Bildirim gönder" açıksa ayrıca: emir verildi → sessizce "Açılmadı · … · 2. deneme birazdan" → sonuç.
  - Bildirim ikonu artık uygulama logosu.
- **ScheduleFailureScreen (10.4):**
  - Ayarlar'daki push kabuğuyla açılıyor: sağdan giriş, alttaki ekran %30 paralaks + kararma. Predictive back parmağı takip ediyor, arka plan tam ekran sahnesinde sürekli canlı.
  - 96'lık kırmızımsı cam daire + cihaz ikonu (arkada nefes alan kırmızı hale), başlık, "Eve gelince · Bugün 18:42 – 18:52".
  - Deneme kartı: "6 deneme · 2 dk arayla", "10 dk sonra vazgeçildi", noktalı dikey zaman çizgisi, mono saatler.
  - "Kontrol et" kartı yalnız WAKE için.
  - "Tamam" + "Şimdi tekrar dene": Cihazlar'a geçer, o cihazı seçer, elle akışı (WakeCoordinator / PowerCoordinator) başlatır.
  - Bölümler sırayla süzülerek geliyor.
- **Otomasyon kartı:** son sonuçlanmış çalışma `FAILED` ise kırmızı satır "⚠ Son çalışma başarısız · Bugün 18:42 · Detay ›" (dokununca ekran). Sonraki başarılı çalışmada yaylanarak kapanıyor; atlanan çalışmalar satırı silmiyor.
- **WIFI_CONNECT ("Eve gelince"):**
  - Açık bir eve gelince zamanlayıcısı varken PendingIntent callback'i kayıtlı (süreç ölse de sistem `WifiTriggerReceiver`'ı çağırıyor). Boot sonrası yeniden kuruluyor.
  - Kayıt anında bağlı olunan ağ "eve gelmek" sayılmıyor: evde kurunca hemen çalışmıyor. Aynı bağlantı için ikinci karar yok.
  - SSID eşleşirse çalışıyor; aynı zamanlayıcı 10 dk içinde tekrar tetiklenmiyor. `skipNextEvent` → `SKIPPED_BY_USER`, atlama temizleniyor.
  - Editörde segment açıldı (seçim zemini `bouncy` kayıyor):
    - konum izni yoksa "Eve gelince" soluk ve dokununca izin isteniyor; reddedildiyse ayarlara yönlendiriyor;
    - seçilince gün/saat bölümleri kapanıyor, "Wi-Fi ağı" alanı açılıyor (varsayılan şu anki ağ, "Şu anki ağ: X" kısayolu);
    - önizleme "Ev-WiFi ağına bağlanınca çalışır".
  - Kart etiketi "⌂ Eve gelince · Ev-WiFi".
- **Debug:**
  - **Sahte PC** sekmesi (Kaan'ın isteği; yalnız debug build). Uygulamaya normal cihaz gibi eklenen, agent'ı eşli sayılan sahte PC.
    - Magic packet: paketle açılır / hiç açılmaz / N. paket turunda açılır (sayaçlı).
    - Agent: uygular / cevap vermez / BUSY / kabul eder ama kapanmaz.
    - Şimdi aç / kapat.
    - Durum telefonda saklanıyor: alarmın arka planda açtığı süreç de aynı PC'yi görüyor.
    - Telefonun Wi-Fi'ı gerçek. Gerçek PC'ye hiç dokunmuyor.
  - **Zamanlayıcı** sekmesi:
    - süren retry zincirleri (yapılan deneme sayısı, sıradaki deneme zamanı);
    - zamanlayıcı başına "Sahte FAILED üret" (kayıt + gerçek bildirim → ekran);
    - "Eve gelince günlüğü" (SSID nasıl okundu, hangi zamanlayıcı neden tetiklendi/tetiklenmedi) ve öğrenilen ağ imzaları.

### Testler
- Android: **297/297 yeşil**.
  - `ScheduleRunnerTest` (23):
    - 2/10 dk → 6 deneme (t = 0, 2, 4, 6, 8, 10) + `FAILED` + tek bildirim;
    - giveUp sınırları (2/5 → 3, 1/2 → 3);
    - 3. denemede başarı → tek kayıt `SUCCESS`, bildirim yok;
    - `NO_WIFI` sayılır, zincir sürer;
    - zincirde elle açılma → `SUCCESS`;
    - aynı deneme iki kez çalışmaz;
    - boot sonrası devam ve süresi dolmuş zincir;
    - kapatılan zamanlayıcı;
    - WIFI `skipNextEvent`;
    - SHUTDOWN zincirde sonradan kapanma;
    - `AGENT_NO_REPLY` × 6;
    - bildirim açık/kapalı/atlanmış;
    - eşsiz agent'ta tekrar yok;
    - S08 testleri (zaten açık, K12, `SKIPPED_BY_USER`…).
  - `WifiTriggerRulesTest`: SSID eşleşme (tırnak, büyük/küçük harf), 10 dk kilidi, kapalı / saat zamanlayıcısı, ağ imzasından tanıma.
  - `FailureLabelsTest`: özetler, başlıklar, alt satır, kart satırı (Bugün / Dün / tarih).
  - Robolectric Compose:
    - başarısız ekranı: başlık, özet, 6 satır, "Kontrol et" ve "Şimdi tekrar dene";
    - agent aksiyonunda kart yok;
    - karttaki kırmızı satır "Detay" ile açılıyor, başarıda kalkıyor;
    - editörde "Eve gelince" → SSID varsayılanı, gün/saat gizli, `WIFI_CONNECT` kaydı.
- `assembleDebug` ve `assembleRelease` temiz. Release'te sahte PC yok (`Optional` bağlama boş). Agent değişmedi.

### Animasyon denetimi
- Push + predictive back: mevcut `PushHost` (smooth, parmağı takip, yarıda geri oturma). Arka plan tam ekran sahnesine akıyor ve sürekli canlı.
- Başarısız ekranı bölümleri 60 ms arayla `smooth` süzülüyor. Hale `breathing` ile (katman ölçeği; çizim önbellekte, kare başına yeniden çizilmiyor).
- Kart kırmızı satırı açılıp kapanırken genişleyip daralıyor; kapanırken son metin çiziliyor.
- Editör:
  - tetikleyici zemini `bouncy` kayıyor, renkler `snappy`;
  - gün/saat ↔ Wi-Fi bölümleri ve konum ipucu açılıp kapanırken genişleyip daralıyor;
  - "Şu anki ağ" kısayolu solarak geliyor.
- Animasyonlu `dropShadow` yok (S06 dersi).

### Sapmalar
- **`FAILED` bildirimi "Bildirim gönder" kapalıyken de geliyor** (spec 9.4: başarısızlık her zaman bildirilir). Anahtar emir/ara deneme/başarı bildirimlerini açıp kapatıyor.
- **Sahte PC** (Kaan'ın isteği, sprint dışı). Cihaz testleri sahte PC ile yapılıyor; gerçek PC ve kablo çıkarma gerekmiyor.
- **Ağ imzası:** arka planda SSID gizli gelirse "Eve gelince" ağı bununla tanıyor (spec'te yoktu).
- Retry'da Kapat/Uyku/Hazırda doğrulaması aralığa sığdırıldı (S08 sapmasının kapanışı).
- Kartta "deneme sürüyor" satırı yok; süren zincir Debug'da görünüyor.

### Kontrol raporu (K20)
Şunlara baktım, tamam; sen de kontrol et:
- Manifest: `RetryAlarmReceiver` ve `WifiTriggerReceiver` dışa kapalı. Wi-Fi PendingIntent'i açık bileşenli ve `FLAG_MUTABLE` (sistem ağ bilgisini ekleyebilsin diye).
- Telefona kuruldu, açılış logunda çökme yok. Eve gelince zamanlayıcısı yokken callback kaydı kaldırılıyor.
- Bildirime dokununca uygulama açıksa da kapalıysa da başarısız ekranı açılıyor (`SINGLE_TOP` + `onNewIntent`).
- Release APK'da Debug sekmeleri ve sahte PC yok.

### Açık kalanlar
- Ayarlar'daki retry aralığı / vazgeçme alanları ve kırmızı izin satırları (S10).
- "Eve gelince"nin One UI 9'da arka planda SSID okuyup okumadığı (cihaz testi #9 günlüğü).

### Cihaz testi: sahte PC ile (Kaan, 2026-11-01)
Kaan'ın isteği: magic packet ve agent gerçek PC'de zaten doğrulandı, testler PC'yi açıp kapatmadan yapılsın. Yukarıdaki tablonun yerine geçer.
Hazırlık: WoL Debug › **Sahte PC** › "Sahte PC ekle".

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Sahte PC: "Hiç açılmaz" + "Şimdi kapat". Uygulamada Sahte PC için "Aç" zamanlayıcısı kur (şu an + 2 dk). Ekranı kapat, 12 dk bekle | Debug › Zamanlayıcı'da denemeler 0, 2, 4, 6, 8, 10. dakikalarda. Sonunda bildirim: "Sahte PC uyanmadı · 6 deneme · 10 dk sonra vazgeçildi" |
| 2 | Bildirime dokun | Başarısız ekranı sağdan kayarak açılır: başlık, "Hafta içi · … · Bugün …", "6 deneme · 2 dk arayla", "10 dk sonra vazgeçildi", 6 satır (saat + "Paket gitti · cevap yok"), "Kontrol et" kartı |
| 3 | Kenardan geri kaydır: önce yarıda bırak, sonra sonuna kadar götür | Parmağı takip eder. Yarıda bırakınca geri oturur, sonuna kadar götürünce kapanır |
| 4 | Otomasyon kartına bak, "Detay"a dokun | Kırmızı satır: "Son çalışma başarısız · Bugün … · Detay ›". Dokununca aynı ekran açılır |
| 5 | Sahte PC'yi "Paketle açılır" yap, ekranda "Şimdi tekrar dene" | Cihazlar'a geçer, hero "Uyanıyor", ~5 sn'de "Açık" |
| 6 | Sahte PC "Şimdi kapat", Debug › Zamanlayıcı › "Şimdi tetikle" | İlk denemede başarı. Karttaki kırmızı satır yaylanarak kaybolur |
| 7 | Sahte PC: "N. denemede açılır", N=3, sayaç "Sıfırla", "Şimdi kapat". Sonra "Şimdi tetikle" | 3. denemede (4. dk) açılır. Başarısızlık bildirimi yok. Son çalışma `SUCCESS`, 3 deneme |
| 8 | "Hiç açılmaz" + "Şimdi tetikle". İlk denemeden sonra telefonun Wi-Fi'ını ~3 dk kapat, sonra aç | O sıradaki denemeler "Wi-Fi yok" diye yazılır, zincir sürer |
| 9 | "Eve gelince" zamanlayıcısı kur (konum izni ver; SSID kendiliğinden şu anki ağ olur), aksiyon Aç. Sahte PC "Paketle açılır" + "Şimdi kapat". Wi-Fi'ı kapat, ~10 sn sonra aç | Bağlanınca birkaç sn içinde Sahte PC açılır. Debug › "Eve gelince günlüğü": "tetiklendi" + SSID'nin nasıl okunduğu |
| 10 | 10 dk dolmadan: Sahte PC "Şimdi kapat", Wi-Fi kapat-aç | Tetiklenmez; günlükte "10 dk kilidi" |
| 11 | 10 dk sonra kartta "Sonrakini atla", Wi-Fi kapat-aç | Çalışmaz; son çalışma `SKIPPED_BY_USER`; kart normale döner |
| 12 | "Hiç açılmaz" + "Şimdi tetikle", 2. denemeden önce telefonu yeniden başlat | Açılınca zincir kaldığı yerden sürer (Debug'da denemeler artar) |

### Cihaz testi düzeltmeleri
**Tur 1 (Kaan, 2026-11-01):** 1, 2, 4–8 geçti; 3 yarım; 9 tutarsız ("5-6 denemede bir kez"); 10–12 daha ayrıntılı tarif istendi. Ek istek: sekmeler arası kaydırma. Ek hata: kenar esnemesinde seçim halkası esnemiyor.
- **#3:** geri hareketinde yarıdan az çekip bırakınca ekran artık kapanmıyor, geri oturuyor (ilerleme < 0,5).
- **Sekmeler arası kaydırma:**
  - Cihazlar ↔ Otomasyon ekrandan yatay kaydırılarak geçiliyor, parmağı takip ediyor: bırakılan sekme %30 paralaksla kayıp soluyor, komşusu yandan geliyor.
  - %30'u geçince ya da hızlı fırlatınca geçiyor, yoksa yaylanarak geri dönüyor. Kenarda lastik etkisi var, nav göstergesi kayıyor.
- **#9 kök nedeni** (`dumpsys connectivity` + logcat): Android PendingIntent ağ kaydını **her teslimattan sonra kendisi siliyor**. Wi-Fi'dayken yeniden kaydolmak hemen bir "yankı" teslimatı doğuruyor. Tek "başarılı" tetik de aslında uygulama açılırken yenilenen kaydın yankısıydı. Yeni düzen (K28 güncellendi):
  - Kayıt **Wi-Fi'dan çıkınca** kuruluyor: uygulama açıksa bağlantı kopar kopmaz, değilse 15 dk'da bir (WorkManager) ve açılışta.
  - Uygulama açıkken yeni Wi-Fi bağlantısı doğrudan yakalanıyor.
  - Aynı bağlantı tek kez işleniyor.
  - Debug'da "Sistem kaydı: KURULU / bekliyor" satırı var; günlük hangi yoldan geldiğini yazıyor ("uygulama açıkken" / "sistem").
  - Ara sürümdeki kayıt döngüsü (yankıya yeniden kayıt) yakalandı, düzeltildi.
- **Wi-Fi adı bazen gelmiyor:** Android arka planda yeniden bağlanınca SSID'yi gizli veriyor ve öne gelince yenilemiyor.
  - Ağ imzası → SSID eşlemesi (`KnownNetworks`) her okunuşta öğreniliyor; ad gizli gelirse bilinen ağın adı gösteriliyor.
  - Uygulama öne gelince ad yoksa callback tazeleniyor.
- **Seçim halkası:** ayrı katmanda çizildiği için listenin kenar esnemesi ona uygulanmıyordu. Artık hedef kartın kendi çiziminde (üstte), kartla birlikte esniyor; kartlar arası `bouncy` kayma aynen.
- Testler 298/298 (sekme kaydırma testi eklendi).

