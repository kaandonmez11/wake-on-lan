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
| 1 | WoL Debug › Veri › "Örnek cihaz ekle" ×3 | 3 cihaz eklendiği sırayla listelenir. |
| 2 | Bir cihaza 14 deneme ekle (karışık ✓/✗) | Geçmiş yalnız **son 12**'yi gösterir; "k/12" doğru sayıda. |
| 3 | Uygulamayı kapat (son uygulamalardan kaydır), tekrar aç | Cihazlar ve denemeler duruyor. |
| 4 | Bir cihazı sil | Cihaz ve denemeleri kaybolur, diğerleri yerinde. |
| 5 | Var olan bir cihazın MAC'iyle yeni cihaz eklemeyi dene | "Bu MAC zaten kayıtlı" hatası, cihaz eklenmez. |
| 6 | Keystore testi | ✓ "Sarmala/aç eşit". |
| 7 | Ayarlarda tekrar aralığını 31 yap | Hata, değer eski haline döner. |
| 8 | Android Studio'dan tekrar Run (güncelleme kurulumu) | Veriler duruyor. |
| 9 | Uygulamayı kaldır, yeniden kur | Liste boş (yedekten geri gelmez). |

## Bitti tanımı
Testler yeşil; cihaz testi #1–9 geçti.

## Sprint sonu raporu
_(Claude doldurur)_
