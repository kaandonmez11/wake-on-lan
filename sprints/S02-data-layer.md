# S02 — Veri katmanı

**Spec:** 6, 8.2 (anahtar saklama), 15, 16 (Geçmiş), 17 faz 2 · **Kararlar:** K7, K14, K15, K18, K19

## Amaç
Room + DataStore + KeyVault hazır ve testli. Debug aracıyla telefonda gerçek veritabanına cihaz/deneme ekleyip silerek, uygulama yeniden başlatıldığında verinin kaldığı görülebilir.

## Görevler
1. **Room:** `AppDatabase`; `Device`, `WakeAttempt`, `Schedule`, `ScheduleRun` (bölüm 6) + `DeviceStateCache` (K7). Converters (`List<Int>`, enum'lar). FK cascade. `mac` unique index; ad benzersizliği repository'de (büyük/küçük harf duyarsız, trim).
2. **DAO'lar:** `DeviceDao` (ekleme sırası = `sortOrder` otomatik, K14), `WakeAttemptDao` (son 12, son başarılı, retention 100 — K15), `ScheduleDao`, `ScheduleRunDao` (retention 100), `DeviceStateCacheDao`.
3. **Repository'ler:** `DeviceRepository` (doğrulama 15: ad, IP, MAC normalizasyonu → `S00`'daki `MacAddress`; "Bu MAC zaten kayıtlı"), `ScheduleRepository` (TIME ≥1 gün, WIFI SSID dolu; agent gerektiren aksiyon kontrolü burada değil, UI/S08'de). Türetilen `history` (son 12, eskiden yeniye) ve `reliability` (son 12 üzerinden k/n; boşsa "yeni").
4. **SettingsStore (DataStore):** bölüm 6'daki tüm alanlar; aralık doğrulaması (`retryIntervalMin` 1–30, `giveUpMin` 2–120 ve ≥ interval); `phoneId` ilk açılışta üretilir.
5. **KeyVault:** Keystore AES-GCM anahtarı (`agentKeyAlias`); `K`'yı sarmala/aç; sarmalanmış blob `noBackupFilesDir` altında alias adıyla (veya ayrı tablo) saklanır; silme. Arayüz arkasında (`KeyVault` interface + `AndroidKeyVault` + testte `FakeKeyVault`).
6. **Hilt modülleri** (DB, DAO, repo, settings, vault).
7. **Debug › Veri sekmesi:**
   - "Örnek cihaz ekle" (rastgele ad/IP/MAC/renk), cihaz listesi (ad, IP, MAC, sıra);
   - cihaz satırında "+ başarılı deneme", "+ başarısız deneme", geçmiş özeti (çubuklar metin olarak `✓✗✓…`, "k/n");
   - "Sil" (cascade'i göstermek için denemeler de kaybolur);
   - aynı MAC'li ekleme denemesi → hata mesajı;
   - **Keystore testi:** rastgele 32 bayt sarmala → aç → eşit mi (✓/✗);
   - Ayarlar değerlerini gösteren/düzenleyen satırlar (aralık dışına zorlayınca hata).

## Otomatik testler (Claude koşturur)
- Robolectric + in-memory Room: CRUD, unique MAC, ad benzersizliği, cascade silme, ekleme sırası.
- Geçmiş: 15 denemede yalnız son 12; k/n; deneme yoksa "yeni"; retention 100.
- SettingsStore aralık ve `giveUp ≥ interval` kuralları.
- DeviceRepository doğrulamaları (bölüm 15 tamamı).
- KeyVault: FakeKeyVault ile sözleşme testi.

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | WoL Debug › Veri › "Örnek cihaz ekle"ye 3 kez bas | 3 cihaz eklendiği sırayla listelenir (#0, #1, #2); her birinde IP ve MAC var; "Geçmiş: yeni". |
| 2 | Bir cihazda "+ ✓" ve "+ ✗" butonlarıyla toplam 14 deneme ekle | Geçmiş satırı yalnız **son 12**'yi gösterir (eskiden yeniye ✓/✗); yanında doğru "k/12". |
| 3 | Uygulamayı son uygulamalardan kaydırıp kapat, tekrar aç | Cihazlar, sıraları ve denemeler duruyor. |
| 4 | Bir cihazda "Sil" | Cihaz ve geçmişi kaybolur, diğerleri yerinde ve sıraları değişmez. |
| 5 | "Aynı MAC ile ekle" | Kırmızı "Bu MAC zaten kayıtlı"; cihaz eklenmez. |
| 6 | "Keystore testi" | Yeşil "✓ Sarmala/aç eşit". |
| 7 | Ayarlar'da tekrar aralığına 31 yaz → Kaydet | Kırmızı "Tekrar aralığı 1–30 dk olmalı", alan eski değere döner. Vazgeçmeye tekrar aralığından küçük değer yazınca da reddedilir. Geçerli değer (ör. 3 / 12) kaydedilince yeşil "Kayıtlı: 3 dk / 12 dk". |
| 8 | Android Studio'dan tekrar Run (ya da Claude'un ADB ile yeniden kurması) | Veriler, ayarlar ve phoneId duruyor. |
| 9 | Uygulamayı kaldır, yeniden kur | Liste boş, ayarlar varsayılan (2 / 10), **phoneId değişmiş** (yedekten geri gelmez). |

## Bitti tanımı
Testler yeşil; cihaz testi #1–9 geçti.

## Sprint sonu raporu
_2026-10-29 · Claude_

### Yapılanlar
- **Bağımlılıklar:** Room **2.8.5** (+ room-ktx, KSP compiler, `androidx.room` Gradle eklentisi; şema `android/app/schemas/` altında git'te), DataStore Preferences **1.3.0**. Testler için kotlinx-coroutines-test, Turbine 1.2.1, androidx.test core-ktx, room-testing.
  - Room 3.0 (`androidx.room3`) de kararlı, ama ayrı paket ve farklı API getiriyor. Spec'teki "Room" için olgun 2.x hattı seçildi.
- **domain/model/Enums.kt:** `PowerAction` (`requiresAgent`), `ShutdownMode`, `AttemptSource`, `ScheduleType`, `RunResult`, `TileMode`, `CachedStateKind`.
- **data/db:**
  - `Device`, `WakeAttempt`, `Schedule`, `ScheduleRun` (spec 6 ile birebir) + **`DeviceStateCache`** (K7).
  - Tüm alt tablolar FK cascade + index. `mac` unique (NULL'lar serbest).
  - `Converters` (port listesi; enum'lar adlarıyla saklanıyor).
  - DAO'lar: ekleme sırası `ORDER BY sortOrder, id` (K14), son N sorgusu, **retention 100**: cihaz başına deneme, zamanlayıcı başına çalışma (K15).
- **data/repo:**
  - `Validation`: spec 15 kuralları saf fonksiyon olarak; Türkçe büyük/küçük harf duyarsız ad anahtarı; alt ağ kontrolü.
  - `DeviceRepository`:
    - `validate` hatayı ve uyarıyı ayırıyor; alt ağ dışı IP yalnız uyarı.
    - `add`/`update` transaction içinde. MAC büyük harf + `:` normalize ediliyor; `sortOrder` otomatik.
    - `delete` cascade ile birlikte KeyVault anahtarını da siliyor.
    - `recordWakeAttempt` + trim; `WakeHistory` (son 12 eskiden yeniye, `k/n` ya da "yeni", son başarı zamanı); durum önbelleği.
  - `ScheduleRepository`: yapısal doğrulama (TIME ≥1 gün + 0..1439; WIFI SSID dolu ve trim; cihaz var mı), CRUD, çalışma başlat/güncelle + trim.
  - `RetryPolicy`: 1–30 / 2–120 / giveUp ≥ interval.
- **data/prefs/SettingsStore:**
  - Spec 6'daki tüm alanlar.
  - Retry iki değer birlikte doğrulanıyor; geçersizse hiçbir şey yazılmıyor.
  - `ensurePhoneId()` ilk çağrıda UUID üretiyor, sonra hep aynı.
  - Tile slotları, tile modu, seçili cihaz, titreşim.
- **data/crypto/KeyVault:**
  - Arayüz + `AndroidKeyVault`: tek bir Keystore AES-256-GCM ana anahtarı.
  - Her oturum anahtarı `noBackupFilesDir/vault/<alias>.bin` içinde `IV ‖ şifreli metin+etiket` olarak saklanıyor.
  - Takma ad doğrulaması dosya yolu enjeksiyonunu engelliyor.
- **Hilt:** `DataModule` (Room `wol.db`, DataStore `settings`), `DataBindings` (KeyVault).
- **WoL Debug › Veri:**
  - Örnek cihaz ekleme.
  - Cihaz kartları: sıra, IP, MAC, ✓/✗ geçmiş, k/n, "+ ✓", "+ ✗", Sil.
  - "Aynı MAC ile ekle" ve Keystore testi.
  - Ayarlar: phoneId, retry alanları + Kaydet, titreşim, tile modu.
  - `DebugActivity` artık `@AndroidEntryPoint`.

### Sapmalar / notlar
- **Ad benzersizliği** Türkçe yerel ayarla karşılaştırılıyor ("İstasyon" = "istasyon"). Spec yalnız "aynı ad ikinci kez olamaz" diyor.
- **"Aksiyon agent gerektiriyorsa cihaz eşli olmalı"** kuralı editörde (S08) uygulanacak; repository yalnız yapısal doğrulama yapıyor.
- **SettingsStore testleri bellek içi DataStore ile koşuyor.** Dosya tabanlı DataStore, Windows test makinesinde peş peşe yazarken `.tmp` dosyasını yeniden adlandıramıyor. Bu bir Windows dosya kilidi sorunu, Android'de yok. Kalıcılığı cihaz testi #3/#8/#9 doğruluyor.
- **AndroidKeyVault JVM'de test edilemiyor** (AndroidKeyStore yok). Mantığı `FakeKeyVault` ile sözleşme testinde, gerçek Keystore cihazda (#6) doğrulanıyor.
- **Ortam:** Android Studio'nun JBR'ı hâlâ eksik (`jbr/lib/jvm.cfg` yok; güncelleme tamamlanmamış olabilir). Derlemeler Gradle'ın indirdiği Temurin 17/21 ile yapıldı.

### Otomatik test sonuçları
- `./gradlew :app:testDebugUnitTest` → **117/117** yeşil (S02 ile +28). Yeniler:
  - `DeviceRepositoryTest` (Robolectric + in-memory Room): sıra, MAC normalize, ad trim ve Türkçe benzersizlik, MAC çakışması, MAC'siz çoklu cihaz, spec 15 kuralları, alt ağ uyarısı, düzenleme, cascade + anahtar silme, son 12 eskiden yeniye + k/n, "yeni", retention 100, Turbine ile canlı geçmiş akışı, durum önbelleği upsert.
  - `ScheduleRepositoryTest`: TIME/WIFI doğrulama, olmayan cihaz, düzenleme, çalışma kayıtları + retention 100.
  - `SettingsStoreTest`: varsayılanlar, retry aralıkları ve geçersizde yazmama, phoneId kalıcılığı, slot/mod/seçili cihaz/titreşim.
  - `ValidationAndVaultTest`: alt ağ, MAC, zamanlayıcı, retry, port dönüşümü, KeyVault sözleşmesi.
- `assembleDebug` + `assembleRelease` yeşil. Telefona kuruldu, iki activity de açılıyor, logcat temiz.

### Kontrol raporu — "baktım, tamam; sen de kontrol et"
- [x] Cascade silme (deneme, zamanlayıcı, çalışma, önbellek, Keystore anahtarı) testte doğrulandı → #4.
- [x] Son 12 / k/n hesabı testte doğrulandı → #2.
- [x] Aynı MAC ve geçersiz retry testte reddediliyor → #5, #7.
- [ ] Gerçek Keystore ve kalıcılık sende → #3, #6, #8, #9.

### Açık kalanlar
- Titreşim ayarı DataStore'da duruyor; `Haptics.enabled`'a bağlanması S10'da (Ayarlar).
- `DeviceStateCache`'in doldurulması S05'te (StatusEngine).
