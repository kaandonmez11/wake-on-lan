# S10 — Ayarlar

**Spec:** 7.5 (ipucu), 9.4 (pil, exact alarm), 10.7, 12.2 (push + predictive back), 12.3 (sayı alanı), 14, 15 (ayar sayıları), 16 (Ayarlar UI testi), 17 faz 9 · **Kararlar:** K20

## Amaç
Ayarlar ekranı tamam: tüm izinler tek yerden görülür ve düzeltilir, retry ayarları stepper + klavyeyle değişir, cihaz başına kapatma şekli, tile slotları/modu, titreşim.

## Doğrulanacak
- Her iznin ilgili sistem ekranı intent'leri (yerel ağ, konum, exact alarm, bildirim, pil optimizasyonu) Android 17 + One UI 9'da.

## Görevler
1. **SettingsScreen + ViewModel:** başlıktaki ayarlar balonundan push geçişi + `PredictiveBackHandler`, geri butonu.
2. **İzinler grubu:** Yerel ağ erişimi, Konum, Tam saatli alarm, Bildirimler, Pil (Kısıtlanmamış). "✓ Verildi" (yeşil) / amber "İzin ver"/"Düzelt" → sistem ekranı; `ON_RESUME`'da canlı yenileme (animasyonlu durum değişimi). Exact alarm verilmemişse satır kırmızı. S08'deki geçici uyarı satırı buraya yönlendirir.
3. **Zamanlayıcı tekrar denemesi:** `NumberStepperField` ×2 (−/+ stepper, ortada sayısal klavyeli mono alan, odakta Buz halkası, "dk"); haneli yuvarlanma; aralık dışı/boş → kırmızımsı yatay titreme (3 salınım `bouncy`) + son geçerli değere dönüş; `giveUp ≥ interval`.
4. **Cihazlar · Kapatma şekli:** cihaz başına renk noktası, ad, agent durumu, "Zorla / Nazik" segmenti (kayan gösterge).
5. **Hızlı ayarlar:** 3 slot satırı (cihaz seçici + "Boş"), "Tile davranışı" A/B/C segmenti (tile'lar S12'de; değer şimdiden saklanır).
6. **Diğer:** Titreşim anahtarı (Haptics bayrağına bağlanır); ipucu "Router'da bu PC'lere sabit IP (DHCP rezervasyonu) vermen önerilir."
7. **Hakkında:** sürüm + lisanslar (assets/licenses listesi).

## Otomatik testler (Claude koşturur)
- Robolectric Compose: sayı alanına aralık dışı değer → eski değere döner; `giveUp < interval` engellenir; stepper sınırlarda durur; segment seçimi kaydedilir.
- İzin satırı durum eşlemesi (sahte izin kaynağıyla).

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Başlıktaki ayarlar balonu | Ayarlar sağdan kayarak gelir, alttaki ekran hafif kayıp kararır. |
| 2 | Kenardan geri kaydır | Ekran parmağı takip eder (predictive back). |
| 3 | Her izin satırı | Durumu doğru ("✓ Verildi" ya da amber buton). |
| 4 | Konum iznini Android ayarlarından kaldır, uygulamaya dön | Satır kendiliğinden amber "İzin ver"e döner; dokununca izin istenir, verince "✓". |
| 5 | Pil satırı → "Düzelt" | Pil optimizasyonu ekranı/izin dialog'u açılır; "Kısıtlanmamış" yapınca dönüşte "✓". |
| 6 | Bildirimler, tam saatli alarm, yerel ağ için aynısı | Her biri doğru sistem ekranını açar, dönüşte güncellenir. |
| 7 | Tekrar aralığı "+" ile 5'e çık | Sayılar haneli yuvarlanır. |
| 8 | Alana klavyeyle 45 yaz, klavyeyi kapat | Alan kırmızımsı titrer, eski değere döner. |
| 9 | Vazgeçme süresini aralıktan küçük yap | Reddedilir (titreme). |
| 10 | Aralık 1, vazgeçme 3 yap → S09 #1'i kısaca tekrarla | Denemeler 1 dk arayla, 3 dk sonra vazgeçilir. (Sonra 2/10'a geri al.) |
| 11 | PC için "Nazik" seç → Not Defteri'nde kaydedilmemiş yazıyla Kapat | PC kapanmaz; 90 sn sonra "Kapanmadı · Açık bir program engelliyor olabilir". |
| 12 | Titreşimi kapat, bir hold butonu kullan | Hiç titreşim yok. |

## Bitti tanımı
Testler yeşil; cihaz testi #1–12 geçti.

## Sprint sonu raporu
_2026-11-01 · Claude_

### Doğrulananlar
İzinlerin sistem ekranları ([Schedule alarms](https://developer.android.com/training/scheduling/alarms), [Android 14 exact alarm değişiklikleri](https://developer.android.com/about/versions/14/changes/schedule-exact-alarms)):
- **Tam saatli alarm:** `USE_EXACT_ALARM` kurulumda veriliyor; yine de `canScheduleExactAlarms()` kontrol ediliyor. Kapalıysa `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (paket uri'li) açılıyor, açılamazsa uygulama bilgisi. Dönüşte `ON_RESUME`'da yeniden okunuyor (belgedeki önerilen akış).
- **Pil:** `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` sistem dialog'u; manifeste `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` eklendi (telefonda `granted=true`). Açılamazsa `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` listesi. Durum `PowerManager.isIgnoringBatteryOptimizations`.
- **Bildirim:** önce runtime istek; reddedildiyse `ACTION_APP_NOTIFICATION_SETTINGS`.
- **Konum:** önce runtime istek (kesin + yaklaşık). Reddedildiyse uygulama bilgisi. İzin var ama konum servisi kapalıysa `ACTION_LOCATION_SOURCE_SETTINGS`; servis açılınca Wi-Fi adı yeniden okunuyor.
- **Yerel ağ (`ACCESS_LOCAL_NETWORK`, NEARBY_DEVICES grubu):** önce runtime istek; reddedildiyse uygulama bilgisi › İzinler.
- One UI 9'da her ekranın açıldığı cihaz testi #3–6 ile görülecek.

### Yapılanlar
- **SettingsScreen + ViewModel:** Cihazlar başlığındaki ayarlar balonundan push (sağdan giriş, alttaki %30 paralaks + kararma), predictive back (yarıda bırakınca geri oturur, S09), geri butonu, arka plan tam ekran canlı sahne. Bölümler cam gruplar halinde.
- **İzinler:**
  - Yerel ağ, Konum, Tam saatli alarm, Bildirimler, Pil satırları: "✓ Verildi" (yeşil) ya da amber "İzin ver"/"Düzelt".
  - Ekrana her dönüşte (`ON_RESUME`) ve izin sonucundan sonra canlı yenileniyor. Durum rozeti ölçeklenip solarak, alt yazı ve renk akarak değişiyor.
  - Tam saatli alarm yoksa satır kırmızımsı.
  - Otomasyon'daki geçici uyarı satırı ("… · Ayarlar") dokununca Ayarlar'ı açıyor.
- **Zamanlayıcı tekrar denemesi:** `NumberStepperField` ×2 (Tekrar aralığı 1–30, Vazgeçme süresi 2–120).
  - −/+ basılı tutunca hızlanıyor, sınırda soluk ve duruyor. Ortada sayısal klavyeli mono alan, odakta Buz halkası, birimi "dk". Stepper'la değişimde haneler yuvarlanıyor.
  - Aralık dışı/boş giriş ya da vazgeçme < aralık → kırmızımsı yatay titreme (3 salınım + `bouncy` oturma) + hata titreşimi + son geçerli değere dönüş.
  - Altında canlı özet "2 dk arayla 6 deneme · 10 dk sonra vazgeçilir".
- **Cihazlar · Kapatma şekli:** cihaz başına renk noktası, ad, agent durumu ("Agent eşli" / amber "Agent yok"), "Zorla / Nazik" segmenti (kayan gösterge `bouncy`). Altında iki modun açıklaması.
- **Hızlı ayarlar:** 3 tile satırı ("Boş" + cihaz çipleri) ve "Tile davranışı" A/B/C segmenti, seçilen modun açıklamasıyla (tile'lar S12'de; değerler şimdiden DataStore'da).
- **Diğer:**
  - Titreşim anahtarı `Haptics.enabled`'a bağlı: kapalıyken hiçbir titreşim çalmıyor; açınca hemen bir tık hissettiriyor.
  - DHCP rezervasyonu ipucu.
- **Hakkında:** sürüm (0.1.0 (1)) + `assets/licenses` listesi. Satıra dokununca lisans metni açılıp kapanıyor (ok döner, alan genişleyip daralır).

### Testler
- Android: **309/309 yeşil**.
  - `PermissionRowsTest`: hepsi verildi; runtime yok → "İzin ver", reddedildi → "Düzelt"; konum servisi kapalı; tam saatli alarm kırmızı; pil "Düzelt"; sayı ayrıştırma; lisans adları.
  - `SettingsScreenTest` (Robolectric Compose):
    - aralık dışı (45) ve boş giriş eski değere dönüyor, geçerli değer yazılıyor;
    - vazgeçme < aralık reddediliyor; aralık "+" vazgeçmeyi geçemiyor;
    - stepper sınırlarda pasif ve duruyor;
    - Zorla/Nazik, tile modu, tile slotu ve titreşim seçimleri kaydediliyor;
    - izin satırları sahte kaynakla doğru durumda ve değişince canlı güncelleniyor.
- `assembleDebug` ve `assembleRelease` temiz. Agent değişmedi.

### Animasyon denetimi
- Push + predictive back: mevcut `PushHost`.
- İzin rozeti `AnimatedContent` (fade + `bouncy` ölçek), satır zemini/alt yazı rengi akıyor, alt yazı `AnimatedText`.
- Stepper: haneler yuvarlanıyor; −/+ basma ölçeği; sınırda soluklaşma animasyonlu; titreme `Quick` → `bouncy`; kırmızı tonu yumuşak sönüyor; odak halkası rengi akıyor.
- Segmentler: kayan zemin `bouncy`, yazı rengi `snappy`. Tile çipleri: çerçeve/zemin rengi akıyor. Mod açıklaması `AnimatedText`.
- Titreşim anahtarı: `GradientSwitch` (`bouncy`). Lisanslar: ok dönüşü + genişleme.
- Animasyonlu `dropShadow` yok.

### Sapmalar
- **Tile slot seçimi** açılır liste yerine yatay çip satırı (birkaç cihazda tek dokunuş).
- **Kapatma şekli** agent'sız cihazda da seçilebiliyor (agent eşlenince geçerli olur); satırda "Agent yok" uyarısı var.
- Ayarlar başlığında Wi-Fi pill'i yok (Otomasyon'daki gibi sade başlık).

### Kontrol raporu (K20)
Şunlara baktım, tamam; sen de kontrol et:
- Manifestte `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` var; telefonda `granted=true`. Diğer izinler (`ACCESS_LOCAL_NETWORK`, `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`) verilmiş durumda.
- Kurulum + açılışta çökme yok.
- Retry ayarı `SettingsStore.setRetryPolicy` ile iki değer birlikte doğrulanıp yazılıyor; ScheduleRunner her denemede güncel değeri okuyor (S09).
- Titreşim kapalıyken `Haptics` hiçbir primitif çalmıyor (tek giriş noktası `compose()` bayrağa bakıyor).

### Açık kalanlar
- Tile'lar (S12) slot/mod değerlerini kullanacak.

### Cihaz testi düzeltmeleri
**Tur 1 (Kaan, 2026-11-01):** 1–6, 8–11 geçti; 7 ve 12 notlu. Ek istekler: sekme kaydırmasında gösterge/arka plan parmağa bağlı olsun, titreşim ayarı kalksın, Hakkında'ya imza, ipuçları ve uyarılar bölümü.
- **Sekme kaydırması:**
  - Alttaki sekme göstergesi ve canlı arka plan artık sürüklemeye bire bir bağlı (ileri-geri sarılabiliyor).
  - Gösterge `Animatable` + `drag()`. Arka plan `SceneScrub`: odaklar hedef sahneye sürükleme oranında yaklaşıyor; Cihazlar'ın renk tonu da dahil.
  - Bırakınca kalan yolu yayla tamamlıyor, sekme değişince animasyon tekrar oynamadan oturuyor.
  - Sekme yazı/ikon rengi göstergenin konumuna göre akıyor.
- **#7:** "dk" etiketi "+" butonundan sonra. Sayı alanındayken boş bir yere dokununca odak kalkıyor ve değer doğrulanıyor.
- **#12 / titreşim:** Kaan'ın isteğiyle titreşim ayarı tamamen kaldırıldı; titreşimler hep açık.
- **Hakkında:** sürümün altında "Designed and developed by Kaan Donmez." (ad altı çizili, dokununca LinkedIn).
- **İpuçları ve uyarılar** ("Diğer"in yerine):
  - 5 sarı uyarı: BIOS WoL, ağ kartı ayarları, Hızlı Başlat, aynı Wi-Fi ağı, pil kısıtlaması.
  - 5 ipucu: kablolu bağlantı, router'da sabit IP, Modern Bekleme'li PC, `wolagent reset`, basılı tutma / yatay kaydırma.
- Testler 311/311 (boşluğa dokununca odak, ipucu/uyarı satırları, imza).

**Tur 2 (Kaan, 2026-11-01):** 1–3, 5–8 geçti. Notlar ve yapılanlar:
- **Kaydırma sonu flaşı:** sekme değiştiği karede arka plan ve gösterge yeni duruma bir kare geç oturuyordu, arada eski görüntü çiziliyordu. Sürükleme hedefi artık sürükleme sıfırlanana dek sabit; gösterge mutlak konumla çiziliyor. Böylece flaş olmuyor.
- **İmza:** lisans satırlarından ayrı, kartın dışında ve ortalı: ince Buz çizgisi, aralıklı "DESIGNED AND DEVELOPED BY", altında büyük, Buz gradient'li ve altı çizili "Kaan Donmez" (LinkedIn).
- **Ayarlar'daki boş şerit:** geri butonu artık içeriğin üstünde yüzüyor, içerik arkasından kayıyor. Başarısız ekranında da aynısı.
- **Üst/alt kenar bulanıklığı (iOS gibi):**
  - `EdgeBlur`: Haze kademeli bulanıklık + hafif kararma, durum çubuğu ve alt bölge yüksekliğinde.
  - Sekmelerde arka plan + sekme içeriği bulanıklaşıyor, yüzen nav üstte ve etkilenmiyor.
  - Push ekranlarında (Ayarlar, Başarısız) arka plan + o ekranın içeriği bulanıklaşıyor; alttaki sekmeler karışmıyor (zIndex seçimi).
  - Toast'lar bulanıklığın üstünde (AppNav overlay).
- **Debug:** zamanlayıcı başına "Başarısız yap · ekranı aç": FAILED kaydı + bildirim üretip başarısız ekranını doğrudan açıyor (Kaan: 12 dk beklemeden).
- Testler 311/311. Bellek 106 MB PSS (S06 tabanı 126 MB).

**Tur 3 (Kaan, 2026-11-01):** 1–4 ve 6 geçti; 7 tam ekran olduğu için kaydırılamıyor (Kaan geçti saydı). Notlar ve yapılanlar:
- **Çökme (Debug › Zamanlayıcı):** "Başarısız yap" yeni çalışma kayıtları üretince çalışma no 11, zamanlayıcı no 11 ile aynı liste anahtarını kullandı ("Key 11 was already used"). Anahtarlar türe göre ayrıldı (`schedule-`, `run-`, günlük satırı sırası).
- **Ayarlar'ı sağa çekerek kapatma:** push ekranı her yerden yatay sürüklenebiliyor ve parmağı takip ediyor. %35'i geçince ya da hızlı fırlatınca kapanıyor, yoksa yaylanarak oturuyor. Yatay kayan çip satırları önce alıyor.
- **İmza:** "KAAN DONMEZ" başlıkla aynı stil ve renkte (büyük harf, aralıklı), altı çizili, biraz daha büyük. Renkli çubuk kaldırıldı.
- **Bulanıklık kenarları:**
  - Sağ/sol siyah kısımlar: bulanıklık ekran dışını siyah sayıyordu → kenar pikselleri tekrarlanıyor (`BlurredEdgeTreatment.Rectangle`).
  - Ayarlar/Başarısız'daki açık şerit: push ekranının %30 karartması bulanıklık kaynağının dışındaydı → kaynağın içine alındı.
  - Geçiş daha uzun (üst +28, alt +36 dp) ve ease-in-out azalıyor; kararma üç duraklı.
- Testler 312/312 (push sürükleyerek kapatma testi).

**Tur 4 (Kaan, 2026-11-11):** 1, 2, 5, 6 geçti; 3 (tile çipleri yatay kaymıyor, ekran kapanıyor) Kaan dokunarak seçtiği için geçti saydı. Not: Başarısız ekranındaki alt butonlar bulanıklaşıyordu.
- **Başarısız ekranı alt butonları:** push ekranı artık alt içeriğini `LocalPushOverlay` ile `PushHost`'a veriyor; `PushHost` onu kenar bulanıklığının üstüne, yüzen nav gibi net çiziyor. Alt bulanıklık bölgesi butonların arkasını kaplayacak kadar uzatıldı (96 dp); içerik butonların altında kalmasın diye kaydırma alanına ek boşluk eklendi. Testlerde (push dışında) butonlar yerinde çiziliyor.
- Testler 312/312.

### Cihaz testi
✅ 2026-11-11, Kaan: #1–12 ve dört düzeltme turu geçti. Notlar: #12 titreşim ayarı Kaan'ın isteğiyle kaldırıldı; tile çip satırı yatay kaymıyor (sağa çekme ekranı kapatıyor), Kaan dokunarak seçtiği için kabul etti.

**Kapanış sonrası not (Kaan, 2026-11-11):** ana ekranda üst bulanıklığın solunda koyu, basamaklı bir leke vardı. Kenar bulanıklığı artık yalnız içerik katmanını bulanıklaştırıyor, zemini saydam; canlı arka plan bulanıklaştırılmadan alttan görünüyor. Push ekranlarının karartması yeniden dış kutuda. 312/312. Cihazda doğrulanmadı (Kaan bakacak).

