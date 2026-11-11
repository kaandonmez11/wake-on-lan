# S11 — Tam ekran kapılar + onboarding

**Spec:** 10.6, 12.2 (kapı giriş/çıkış), 12.4 (sürekli arka plan), 12.5 (Wi-Fi halkaları), 14 (onboarding), 16 (NoWifi UI testi), 17 faz 10

## Amaç
Wi-Fi yoksa ya da yerel ağ izni yoksa uygulama tam ekran ve animasyonlu bir kapı gösterir; koşul düzelince kendiliğinden çözülerek kapanır. İlk açılışta 3 sayfalık izin akışı.

## Görevler
1. **NoWifiScreen:** 124 cam daire + `wifi_off` + amber parlama, sürekli dışa yayılan 3 halka (0.6→1.6, 0.5→0, 2.4 sn, 0.8 sn faz) + nefes; başlık/açıklama; bilgi kartı ("Şu an mobil veri · {operatör}" / "Bağlantı yok"); "Wi-Fi ayarlarını aç" (`Settings.Panel.ACTION_WIFI`) + alt not; sürekli canlı arka plan sahnesi.
2. **NoPermissionScreen:** aynı düzen; izin iste, kalıcı reddedilmişse uygulama ayarları.
3. **Kapı yönetimi** (`AppNav` üstünde): öncelik izin > Wi-Fi; giriş 1.04→1 + fade; çıkış yukarı hafif kayıp çözülme, alttaki ekran ortaya çıkar. Ayarlar ve başarısız ekranının da üstünde.
4. **Onboarding:** ilk açılışta 3 sayfa (Yerel ağ · Konum (atlanabilir) · Bildirim), cam kartlar, canlı arka plan, yatay spring sayfa geçişi; tamamlanınca bir daha gösterilmez (DataStore bayrağı). Debug araçlarındaki geçici izin butonları kalır.
5. Animasyonları kaldır modunda halkalar durur, geçişler crossfade.

## Otomatik testler (Claude koşturur)
- Robolectric Compose: Wi-Fi bağlantısı kesilince `NoWifiScreen` görünür, gelince kaybolur (sahte NetworkMonitor); izin yokken NoPermission öncelikli; onboarding yalnız ilk açılışta.

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Uygulamayı kaldır, kur, aç | Onboarding: 1. sayfa yerel ağ izni; izin ver → sayfa yaylanarak kayar → Konum → Bildirim → ana ekran. |
| 2 | Uygulamayı kapatıp aç | Onboarding tekrar çıkmaz. |
| 3 | Uygulama açıkken Wi-Fi'ı kapat (mobil veri açık) | Tam ekran "Wi-Fi'a bağlı değilsin" büyüyerek/solarak gelir; halkalar sürekli yayılır; kart "Şu an mobil veri · {operatör}". |
| 4 | Mobil veriyi de kapat | Kart "Bağlantı yok"a animasyonla değişir. |
| 5 | "Wi-Fi ayarlarını aç" | Wi-Fi paneli açılır. |
| 6 | Wi-Fi'ı aç | Kapı yukarı doğru çözülerek kaybolur, alttaki ekran görünür; ekran kendiliğinden kapanır. |
| 7 | Ayarlar ekranındayken Wi-Fi'ı kapat | Kapı Ayarlar'ın da üstünde çıkar. |
| 8 | Android ayarlarından yerel ağ iznini kaldır, uygulamaya dön | İzin kapısı çıkar; "İzin ver" → izin; verince kapanır. |
| 9 | İzni "bir daha sorma" ile reddet | Buton uygulama ayarlarını açar. |
| 10 | Animasyonları kaldır açıkken Wi-Fi kapat | Halkalar durur; giriş/çıkış kısa solma. |

## Bitti tanımı
Testler yeşil; cihaz testi #1–10 geçti.

## Sprint sonu raporu
_2026-11-11 · Claude_

### Doğrulananlar
Sprint dosyasında "Doğrulanacak" maddesi yok. Kullanılan platform parçaları:
- **Wi-Fi paneli:** `Settings.Panel.ACTION_WIFI`; açılamazsa `ACTION_WIFI_SETTINGS`.
- **Mobil veri / operatör:** varsayılan ağın `TRANSPORT_CELLULAR` taşıyıp taşımadığı (`registerDefaultNetworkCallback`) ve `TelephonyManager.networkOperatorName` (izin gerektirmez).
- **Kalıcı ret:** izin isteği reddedilince `shouldShowRequestPermissionRationale` false ise sistem artık sormuyor → buton uygulama ayarlarını açıyor.
- One UI 9'daki gerçek davranışı cihaz testi #3–9 gösterecek.

### Yapılanlar
- **Kapı yönetimi (`GateHost`, MainActivity'de her şeyin üstünde):**
  - Öncelik: onboarding > yerel ağ izni > Wi-Fi (`Gates.decide`, saf).
  - Giriş 1.04 → 1 ölçek + fade (`smooth`); çıkış yukarı 28 dp kayıp çözülme, alttaki ekran ortaya çıkar. Kapılar arası geçiş crossfade.
  - Ayarlar, Başarısız ekranı ve panellerin de üstünde. Alttaki ekrana dokunuş geçmez; geri tuşu uygulamayı arka plana alır.
  - Kendi sürekli canlı arka planı var (tam ekran sahnesi).
  - Wi-Fi kapısı kopukluk 700 ms sürerse açılır (açılışta ve mesh node geçişinde yanıp sönmez); bağlantı gelince hemen kapanır.
  - Ekrana her dönüşte izinler yeniden okunur.
- **NoWifiScreen:** 124 cam daire + `wifi_off` + amber parlama; 3 halka sürekli dışa yayılır (0.6 → 1.6, 0.5 → 0, 2.4 sn, 0.8 sn faz); daire nefes alır. Başlık, açıklama, bilgi kartı ("Şu an mobil veri · {operatör}" / "Bağlantı yok", metin akarak değişir), "Wi-Fi ayarlarını aç", alt not.
- **NoPermissionScreen:** aynı düzen, Buz renginde. "İzin ver" izni ister; kalıcı reddedildiyse buton "Uygulama ayarlarını aç" olur ve not yol tarifi verir.
- **Onboarding:** ilk açılışta 3 sayfa (Yerel ağ · Konum · Bildirim).
  - Her sayfa halkalı ikon + cam kart; sayfalar yatay `smooth` yayla kayar, noktalı gösterge `bouncy`.
  - "İzin ver" izni ister, sonuç ne olursa olsun ilerler. İzin zaten varsa buton "Devam" (son sayfada "Başla").
  - Konum ve Bildirim'de "Şimdi değil". Yerel ağ reddedilirse onboarding'den sonra izin kapısı çıkar.
  - Tamamlanınca DataStore bayrağı (`onboardingDone`) yazılır, bir daha gösterilmez. Konum verilince Wi-Fi adı yeniden okunur.
- **Animasyonları kaldır:** halkalar sabit ve soluk durur, giriş/çıkış kısa solma, sayfa geçişi kısa.

### Testler
- Android: **317/317 yeşil**. `GateTest` (Robolectric Compose, sahte Wi-Fi / izin / bayrak / mobil kaynakları):
  - karar önceliği ve bilgi kartı metinleri;
  - Wi-Fi kesilince NoWifi görünür, mobil veri kapanınca kart "Bağlantı yok" olur, Wi-Fi gelince kapı kaybolur;
  - kısa kopukluk kapı açmaz;
  - izin yokken NoPermission öncelikli, izin verilince Wi-Fi kapısına geçer;
  - onboarding yalnız ilk açılışta: 3 sayfa, bayrak yazılır, bir daha çıkmaz.
- `assembleDebug` ve `assembleRelease` temiz. Agent değişmedi.

### Animasyon denetimi
- Kapı giriş/çıkış tek `Animatable` (katman aşamasında ölçek, kayma, alfa). Kapılar arası `AnimatedContent`.
- Halkalar çizim aşamasında tek döngüden; nefes katman ölçeği. Görünmeyen onboarding sayfasının döngüleri durur.
- Bilgi kartı ve buton metni `AnimatedText`; "Şimdi değil" solarak gelir/gider.
- Animasyonlu `dropShadow` yok.

### Sapmalar
- Wi-Fi kapısı 700 ms gecikmeli açılıyor (yanıp sönmeyi önlemek için).
- İzin kapısında da halkalar var ("aynı düzen").
- Onboarding'de izin reddedilse de ilerleniyor; yerel ağ için sonradan izin kapısı devreye giriyor.
- **Mevcut kurulumlarda** (Kaan'ın telefonu) bayrak yok: onboarding bir kez görünür, izinler verili olduğu için "Devam, Devam, Başla" ile geçilir.

### Kontrol raporu (K20)
Şunlara baktım, tamam; sen de kontrol et:
- Telefona kuruldu, açılışta çökme yok.
- Kapı görünmezken kompozisyonda yer almıyor (arka plan döngüsü çalışmıyor).
- Debug araçlarındaki izin butonları duruyor.

### Açık kalanlar
- Kapı açıkken alttaki ekranın sürekli animasyonları durdurulmuyor (kapı opak olduğu için görünmüyor); S13 performans cilasında bakılabilir.

### Cihaz testi
✅ 2026-11-11, Kaan: #1–10 geçti. #1 uygulamayı kaldırmadan, Debug › Veri › "Onboarding ile aç" ile denendi (Kaan'ın isteğiyle eklendi).

