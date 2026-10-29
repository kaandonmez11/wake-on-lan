# S08 — Otomasyon 1: zaman tetikli zamanlayıcılar

**Spec:** 9.4 (ScheduleEngine, tek deneme çalıştırma, arka plan altyapısı), 10.3, 15 (zamanlayıcı), 16 (ScheduleEngine), 17 faz 8 (ilk yarı) · **Kararlar:** K10, K12, K13

## Amaç
Otomasyon sekmesi çalışır: TIME zamanlayıcıları oluşturulur, düzenlenir, atlanır; telefon ekranı kapalıyken bile zamanında PC'yi uyandırır / kapatır. (Retry, başarısız ekranı ve "eve gelince" S09'da.)

## Doğrulanacak
- `USE_EXACT_ALARM` + `setExactAndAllowWhileIdle` Android 17 davranışı, `canScheduleExactAlarms()`.
- Doze'da allow-while-idle kotası (K13 kararı için not; uygulaması S09'da).
- `goAsync()` süre sınırı ve expedited WorkManager kotası.

## Görevler
1. **ScheduleEngine (saf):** `nextOccurrence`, atla (`skippedOccurrence` / `skipNextEvent`), atlama geçince temizleme, anahtar kapatınca sıfırlama, buton metinleri ("Yarını atla", "Bugünü atla", "Bu geceyi atla", "Sonrakini atla", "Geri al"), amber satır metni ("Yarın atlandı · sonraki Cuma 08:30"), tetikleyici etiketi ("Hafta içi · 08:30", "Her gün", "Pzt, Çar · 07:00").
2. **AlarmScheduler:** zamanlayıcı başına exact alarm kur/iptal; `ScheduleAlarmReceiver` (`goAsync`, WAKE ise paketleri hemen gönderir) → `VerifyWorker` (expedited, K10).
3. **ScheduleRunner (tek deneme):** `ScheduleRun(RUNNING)`; hedef zaten sağlanmışsa `SKIPPED_ALREADY` (K12: agent aksiyonunda PC kapalıysa komut gönderilmez); atlanmışsa `SKIPPED_BY_USER`; aksiyonu uygula, `min(60 sn, retryInterval)` doğrula; sonuç `SUCCESS`/`FAILED` (S09'da retry zinciriyle genişler); `attemptsJson`; sonraki alarm kurulur. Arka planda tek seferlik durum turu (StatusEngine `requestRound`).
4. **BootReceiver:** `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` → tüm alarmlar yeniden.
5. **AutomationScreen + ViewModel (10.3):** zamanlayıcı kartları (cihaz ikon balonu, ad, aksiyon etiketi renkleri, tetikleyici, 52×32 GradientSwitch `bouncy`, "Sonraki: Yarın 08:30" + atla butonu, amber atlandı satırı + "Geri al", kapalı %70 opak); liste animasyonları; "Zamanlayıcı ekle".
6. **ScheduleEditorSheet:** cihaz seçimi, aksiyon seçimi (Windows ikonlu agent aksiyonları + Aç), tetikleyici TIME (WIFI seçeneği görünür ama S09'a kadar "yakında" pasif), gün seçici, saat seçici (mono, sayılar yuvarlanır), sil; doğrulama (≥1 gün; agent aksiyonu için cihazda agent eşli değilse kaydet pasif + ipucu).
7. **İzin:** `canScheduleExactAlarms()` false ise Otomasyon'da geçici uyarı satırı (Ayarlar'daki kırmızı satır S10'da).
8. **Debug › Zamanlayıcı sekmesi:** kurulu alarmların listesi (zamanlayıcı, sonraki tetik), "Şimdi tetikle" (alarmı beklemeden receiver'ı çağırır), son `ScheduleRun` kayıtları + `attemptsJson`.

## Otomatik testler (Claude koşturur)
- ScheduleEngine (bölüm 16): hafta içi 08:30 + çarşamba 23:49 → perşembe 08:30; atla → cuma 08:30; atlama geçince temizlenir; anahtar kapanınca sıfırlanır; hafta sonu ve gece yarısı; tüm buton metinleri.
- ScheduleRunner: zaten açık → `SKIPPED_ALREADY`; PC kapalı + SHUTDOWN/SLEEP/LOCK → komut gönderilmeden `SKIPPED_ALREADY`; ilk denemede başarı → tek kayıt; atlanmış → `SKIPPED_BY_USER`.
- Robolectric Compose: "Yarını atla" → amber satır + "Geri al"; editor doğrulamaları.

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Otomasyon sekmesi (boş) | Başlık + "Zamanlayıcılar telefondan çalışır, Wi-Fi şart" + "Zamanlayıcı ekle". |
| 2 | Zamanlayıcı ekle: PC · Aç · bugünün günü · **şu an + 3 dk** | Kart belirir, "Sonraki: Bugün HH:MM". |
| 3 | PC'yi uyut, telefon ekranını kapat, bekle | Saat gelince PC uyanır (telefonu elleme). Debug › Zamanlayıcı'da `SUCCESS`. |
| 4 | Zamanlayıcı ekle: PC · Kapat · şu an + 3 dk (PC açık) | Saat gelince PC kapanır. |
| 5 | PC kapalıyken "Uyku" zamanlayıcısı şu an + 3 dk | Saat gelince hiçbir şey olmaz; kayıt `SKIPPED_ALREADY`, bildirim yok. |
| 6 | Hafta içi 08:30 zamanlayıcısında "Yarını atla" | Satır amber "Yarın atlandı · sonraki …", buton "Geri al". "Geri al" → normale döner (animasyonlu). |
| 7 | Anahtarı kapat | Kart %70 soluklaşır, anahtar yaylanarak kayar; atlama sıfırlanır. |
| 8 | Agent'ı eşli olmayan bir cihaz için "Kapat" seç | Kaydet pasif + ipucu. |
| 9 | Şu an + 5 dk'ya zamanlayıcı kur, telefonu yeniden başlat | Saat gelince yine çalışır. |
| 10 | Telefonun saat dilimini değiştir, geri al | "Sonraki" metni doğru güncellenir. |
| 11 | Karta dokun → düzenle → sil | Sheet açılır; silince kart yaylanarak kapanır. |

## Bitti tanımı
Testler yeşil; cihaz testi #1–11 geçti.

## Sprint sonu raporu
_2026-10-30 · Claude_

### Doğrulananlar
- **`USE_EXACT_ALARM` + `setExactAndAllowWhileIdle` (Android 17):** izin kurulumda verildi (`dumpsys package`: `USE_EXACT_ALARM: granted=true`). Kod yine `canScheduleExactAlarms()`'a bakıyor; false ise `setAndAllowWhileIdle`'a düşüp Otomasyon'da uyarı satırı gösteriyor.
- **Doze kotası (K13):** belgelerde Android 12+ için güncel rakam yok (eski "9 dk'da bir" kuralı Android 6 dönemine ait). Kaan'ın telefonundaki gerçek ayar `dumpsys alarm` ile okundu: `allow_while_idle_quota=72` / `allow_while_idle_window=1h`, `allow_while_idle_whitelist_duration=10s`.
  - Sonuç: S09'daki 2 dk'lık retry zinciri (10 dk'da 6 alarm) kotaya rahat sığıyor. Her alarm uygulamaya 10 sn ağ izni veriyor.
  - **`setAlarmClock`'a gerek yok**, retry alarmları da `setExactAndAllowWhileIdle`.
- **`goAsync()`:** sınır ~10 sn (ön plan yayını), arka plan yayınında ~30 sn ([BroadcastReceiver](https://developer.android.com/reference/android/content/BroadcastReceiver)). Alıcı 8 sn bütçeyle çalışıyor: sonraki alarmı kuruyor, WAKE ise ilk magic packet turunu gönderiyor (≤ 1,5 sn bekler), doğrulamayı işe devrediyor.
- **Expedited WorkManager:** kotalı (standby kovasına göre), Doze ve Pil Tasarrufu'ndan daha az etkileniyor ([Define work requests](https://developer.android.com/guide/background/persistent/getting-started/define-work)). `OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST` ile kota bitince normal işe düşüyor. Ekran kapalıyken gerçek davranışı cihaz testi #3 gösterecek.

### Yapılanlar
- **ScheduleEngine (saf):**
  - `nextOccurrence` (strictly after, yaz saati güvenli).
  - Atla/geri al; geçmiş atlamanın temizlenmesi; anahtarla atlamanın sıfırlanması; "sonraki" (atlanmışsa bir sonraki).
  - Metinler: tetikleyici ("Hafta içi · 08:30", "Her gün · 07:00", "Pzt, Çar · 07:00", "⌂ Eve gelince · SSID"); zaman ("Bugün/Yarın/Cuma 08:30"); butonlar ("Bugünü/Yarını/Bu geceyi/Sonrakini atla", "Geri al"); amber satır ("Yarın atlandı · sonraki Cuma 08:30").
- **ScheduleService + AndroidAlarmScheduler:**
  - Zamanlayıcı listesini izliyor; açık TIME zamanlayıcısının sıradaki çalışmasına exact alarm kuruyor, kapalı/silinen/WIFI olanlarınkini iptal ediyor.
  - Alarm tetiklenince bir sonrakini hemen kuruyor (süreç ölse de zincir kopmuyor).
  - Uygulama süreci her açıldığında başlıyor (alarmla açılan arka plan süreci dahil).
- **ScheduleAlarmReceiver** (`goAsync`), **BootReceiver** (`BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` → tüm alarmlar yeniden), **VerifyWorker** (expedited, K10).
- **ScheduleRunner (tek deneme):**
  - `RUNNING` kaydı; 3 tura kadar durum kontrolü. WAKE ama açık → `SKIPPED_ALREADY`; agent aksiyonu ama kapalı → komut **gönderilmeden** `SKIPPED_ALREADY` (K12). Atlanmışsa `SKIPPED_BY_USER` + atlama temizlenir.
  - WAKE: paket (alıcı göndermediyse) + `min(60 sn, aralık)` doğrulama. `WakeAttempt(SCHEDULE)` yazılır. Uygulama açıksa hero "Uyanıyor" gösterir.
  - Agent: komut → Kilitle/Yeniden'de ACK yeter; Kapat/Uyku/Hazırda'da PC'nin düştüğü 2 negatif turla görülür.
  - Sonuçlar `OK / NO_REPLY / AGENT_NO_REPLY / NO_WIFI / AGENT_ERROR` olarak `attemptsJson`'a (`{at, outcome, reason}`) yazılır.
- **Otomasyon sekmesi:**
  - Ortak başlık (Wi-Fi pill + ayarlar balonu), "Otomasyon" ve açıklama.
  - Tam saatli alarm izni yoksa amber uyarı satırı.
  - Kartlar (cam, köşe 28):
    - cihaz renginde ikon balonu, ad, renkli aksiyon etiketi (Aç yeşil, Kapat kırmızımsı, diğerleri nötr; Windows ikonlu) ve tetikleyici;
    - 52×32 **GradientSwitch** (düğme `bouncy` kayar, zemin gradient'i ve hafif parlama akar);
    - "Sonraki: …" + 44'lük atla butonu; atlanınca satır amber'e akar, buton "Geri al" olur;
    - kapalı kart %70 opak, alt satır yaylanarak kapanır;
    - kartlar `animateItem` ile girer, kayar ve çıkar.
  - "Zamanlayıcı ekle" (cihaz yoksa pasif + "Önce bir cihaz ekle"). Metinler 30 sn'de bir ve saat dilimi değişince yenileniyor.
- **ScheduleEditorSheet** (cihaz panelinin kabuğu: %84, scrim + blur, sürükle-kapat):
  - cihaz çipleri;
  - aksiyon ızgarası 3×2 (Aç + Windows ikonlu 5 agent aksiyonu; agent yoksa soluk);
  - tetikleyici segmenti (Saat; "Eve gelince · yakında" pasif);
  - gün seçici (Hafta içi / Hafta sonu / Her gün + 7 gün, `bouncy` dolum);
  - mono saat seçici: haneler yuvarlanıyor; ok tuşları basılı tutunca hızlanıyor, sayının üstünde dikey sürükleme de çalışıyor;
  - ipucu satırı: hata ya da "Sonraki: Yarın 08:30" önizlemesi;
  - düzenlemede "Sil" + "Zamanlayıcı silinsin mi?" onayı.
  - Doğrulama: cihaz seçili, ≥ 1 gün, agent aksiyonunda agent eşli, Hazırda'da hazırda bekletme açık.
- **Debug › Zamanlayıcı:** izin durumu; her zamanlayıcının kurulu alarmı ve atlanan çalışması; "Şimdi tetikle" (alarmı beklemeden alıcıyı çağırır); son 20 `ScheduleRun` (sonuç, zamanlar, `attemptsJson` çözülmüş ve ham).

### Testler
- Android: **270/270 yeşil**.
  - `ScheduleEngineTest` (8): çarşamba 23:49 → perşembe 08:30; atla → cuma; atlamanın temizlenmesi; anahtarla sıfırlama; hafta sonu ve gece yarısı; tam tetik anı; tüm buton metinleri; etiketler; saat dilimi.
  - `ScheduleRunnerTest` (11): zaten açık; K12 (kapalıya SHUTDOWN/SLEEP/LOCK gitmez); ilk denemede başarı → tek kayıt + `WakeAttempt(SCHEDULE)`; alıcı paketi gönderdiyse tekrar yok; cevapsız → `NO_REPLY` ve 60 sn pencere; `SKIPPED_BY_USER`; kapalı zamanlayıcı; SHUTDOWN → 2 negatif turda başarı; `AGENT_NO_REPLY`; `NO_WIFI`; `attemptsJson` gidiş-dönüş.
  - Robolectric Compose (5): "Yarını atla" → amber satır + "Geri al" → normale dönüş, anahtar kapanınca satır kalkar; boş sekme; editörde gün yoksa ve agent'sız cihazda Kapat seçiliyse kaydet pasif + ipucu; geçerli zamanlayıcı kaydı.
  - `AppNavTest` güncellendi: Otomasyon başlığının üstünde artık Wi-Fi pill'i olduğundan her başlık kendi son konumuna göre ölçülüyor (ölçek payı %2).
- Agent değişmedi (73/73). `assembleDebug` ve `assembleRelease` temiz.

### Animasyon denetimi
- Anahtar: düğme `bouncy` (layout aşamasında `offset {}`), zemin/parlama `snappy` (draw).
- Kart opaklığı `smooth`. Alt satır açılıp kapanırken genişleyip daralıyor.
- Amber geçişi renk + metin animasyonu; buton metni solarak değişiyor, genişliği yayla akıyor.
- Kartlar `animateItem` ile giriyor, kayıyor ve çıkıyor; silinen kartın yeri yayla kapanıyor.
- Editör: seçimler renk/dolum animasyonlu; günler `bouncy`; haneler bağımsız yuvarlanıyor.
- Hiçbir efekt animasyonlu `dropShadow` kullanmıyor (S06 dersi). Anahtar parlaması sabit radial gradient, opaklığı akıyor.

### Sapmalar
- Kapat/Uyku/Hazırda doğrulaması `min(60 sn, aralık)` yerine en az 90 sn (PowerCoordinator'la aynı). Windows'un kapanması 60 sn'yi geçebiliyor; S09 retry zincirinde aralıkla sınırlanacak.
- Kilitle ve Yeniden'de imzalı ACK başarı sayılıyor (yeniden başlamayı arka planda izlemek S09 kapsamı).
- Yeni zamanlayıcının varsayılan saati şimdiden sonraki ilk 5 dk dilimi, günleri hafta içi.
- Tetikleyici etiketinde "Her gün" de saatle yazılıyor ("Her gün · 07:00").
- Alttaki nav "+" Otomasyon sekmesinde de cihaz ekliyor; zamanlayıcı listenin altındaki butondan ekleniyor.

### Kontrol raporu (K20)
Şunlara baktım, tamam — sen de kontrol et:
- Manifestte `USE_EXACT_ALARM` ve `RECEIVE_BOOT_COMPLETED` var, ikisi de telefonda verilmiş durumda.
- Alıcılar `exported` durumu: alarm alıcısı dışa kapalı, açılış alıcısı sistem yayınları için açık.
- Uygulama güncellenince (`MY_PACKAGE_REPLACED`) süreç arka planda başladı, log'da çökme yok.
- Alarm zinciri tetiklenince bir sonraki çalışma hemen kuruluyor; zamanlayıcı silinince ya da kapanınca alarm iptal ediliyor.

### Açık kalanlar (S09)
- Retry zinciri ve vazgeçme, `FAILED` bildirimi, başarısız ekranı ve karttaki kırmızı satır.
- `WIFI_CONNECT` tetikleyicisi.
- Ayarlar'daki kırmızı izin satırı (S10).

### Cihaz testi
_(Kaan)_
