# S05 — StatusEngine + Wake/Power koordinatörleri

**Spec:** 7.5, 9.1, 9.2, 9.3, 10.5 (agent bağlantı denemesi zamanları), 16 (domain testleri), 17 faz 5 · **Kararlar:** K6, K7, K8, K9, K12, K16, K19, K23

## Amaç
Cihaz durumu tek kaynaktan, çoklu sinyalle, titremeden hesaplanıyor. Uyandırma ve güç komutu akışları uçtan uca çalışıyor. Telefonda debug ekranında her turun sinyalleri ve kararı canlı izlenebiliyor.

## Görevler
1. **domain/model:** `DeviceState` (9.1 + K9: `Off.lastAction`), `Signal`, `PowerAction`, `WakeStep`, tur sonucu tipleri.
2. **StatusEngine:** tur başına S1–S4 paralel; karar kuralları 1–6 + **K8**; negatif tur sayacı (2, geçişlerde 3); stale 15 sn; tur sıklığı (ön plan 3 sn, geçiş 1 sn, arka planda yok); ön plan/arka plan yaşam döngüsü (`ProcessLifecycleOwner`); `StateFlow<Map<Long, DeviceState>>`; tile için `requestRound(deviceId)` API'si.
3. **DeviceStateCache (K7):** her kesin kararda yazılır; açılışta `stale=true` ile yüklenir.
4. **WakeCoordinator** (9.2): cihaz başına tek akış; PACKET → REPLY (60 sn) → PORT (≤30 sn); `WakeAttempt` yazımı (`source` parametreli); başarısızlık uyarısı olay olarak yayınlanır; MAC yoksa başlamaz.
5. **PowerCoordinator** (9.3): CHALLENGE → CMD → ACK; LOCK (On kalır + "Kilitlendi" olayı); SLEEP/HIBERNATE/SHUTDOWN → `PowerTransition` → Off (90 sn timeout, GRACEFUL ek metni); RESTART offline→online (180 sn; ek olarak `uptimeSec` düşüşü de "yeniden başladı" sayılır); **K12**: cihaz `On` değilse komut reddedilir; `UNSUPPORTED`/`BUSY`/`BAD_SIG` olayları.
6. **IP yeniden çözümleme** (7.5): ön planda her 60 sn ve açılışta DISCOVER; agentId eşleşen cihazın IP'si sessizce güncellenir; S2 farklı MAC görünce discovery tetiklenir.
7. **Agent bağlantı denemesi** (10.5): açılışta ve cihaz `On`'a geçtiği an (agentId yoksa) discovery + otomatik eşleşme; K6 akışları; MAC boşsa K16 ile doldurma.
8. **Debug › Durum sekmesi:**
   - her cihaz için canlı durum metni (spec metinleriyle: "Açık · 3 ms", "Kapalı", "Uyuyor", "Uyanıyor · PACKET · 4 sn", "Kapanıyor…"), verified/stale/agentReachable bayrakları;
   - son turun sinyal satırı: `S1 ✓ 12ms · S2 ✓ MAC eş · S3 ✓ · S4 —`, tur sayacı ve negatif tur sayacı;
   - "Uyandır" ve 5 güç butonu (onaylı); durum `On` değilse güç butonları pasif (K12);
   - olay günlüğü (zaman damgalı durum geçişleri, uyarılar).
   - Cihazlar S02'deki Debug › Veri'den eklenir (gerçek PC'yi IP+MAC ile ekle).

**S03/S04 notu (Kaan'ın PC'si, K22 sonuç + K23):** PC yalnız Modern Bekleme destekliyor. Uykudayken ağ açık kalıyor: S3 TCP ve S4 ping cevap verebiliyor. Agent (S1) ise uykuda neredeyse hiç cevap vermiyor; `SCREEN_ON` kaldırıldı.
- Telefondan "Uyku" bu PC'de hazırda bekletme yapar (K23, `sleepHibernates`). Bu yüzden telefondan uyutulan PC gerçekten kapalıdır; durum `Off(lastAction=HIBERNATE)` → "Hazırda" gösterilir.
- Kalan belirsizlik: PC'nin başından elle uyutulursa agent'lı cihazda S1 sessiz + S3/S4 pozitif olabilir. Bunun için kural: `On(verified=false, agentReachable=false)`, UI "Agent cevap vermiyor". Daha iyisi bulunursa S05'te önerilir.

## Otomatik testler (Claude koşturur)
Bölüm 16'daki tamamı: StatusEngine (S1 tek başına açık; S2 MAC uyuşmazlığı açık saymaz **ve S3'ü de geçersiz kılar** — K8; tek negatif tur kapalı yapmaz, iki tur yapar; stale 15 sn; geçişlerde 3 tur), WakeCoordinator (3. sn yanıt → `durationSec=3`; 60 sn → başarısız; ikinci tetikleme yok sayılır; MAC yoksa başlamaz), PowerCoordinator (ACK → transition → Off; 90 sn; RESTART; `UNSUPPORTED`, `BUSY`; Off cihaza komut reddi). K9 lastAction metinleri.

## Cihaz testi (Kaan yapar)
Hazırlık:
1. PC'de (192.168.1.26) yönetici Komut İstemi: `WolAgent.exe reset`. S04'teki Debug eşleşmesi asıl cihaz kaydına taşınmaz; agent boşta olmalı ki uygulama otomatik eşleşsin.
2. WoL Debug › Veri › "Gerçek cihaz ekle": Ad (ör. Ofis PC), IP `192.168.1.26`, MAC (`ipconfig /all` › Fiziksel Adres) → Ekle. Örnek cihazları istersen sil.
3. WoL Debug › Durum sekmesine geç.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | PC açıkken Durum sekmesi | ~1 sn içinde "Açık · X ms". Birkaç sn içinde olay günlüğünde "Agent eşleşti". Sonra `verified=true · agent: ✓`, sinyal satırı `S1 ✓ … · S2 ✓ MAC eş · S3 ✓ …`. |
| 2 | Uygulamayı son uygulamalardan kapat, tekrar aç → Durum | Önce "Açık · kontrol ediliyor" (soluk), ~1 sn sonra "Açık · X ms". |
| 3 | 2 dk ekranı izle | Durum hiç titremez (Açık↔Kapalı gidip gelmez); tur numarası 3 sn'de bir artar. |
| 4 | PC'de `services.msc` › "WoL Denetim Merkezi agent" › Durdur | Durum "Açık" kalır, `agent: CEVAP VERMİYOR`. Servisi yeniden başlat → birkaç sn içinde `agent: ✓`. |
| 5 | PC açıkken Ethernet kablosunu çek | ~6–9 sn sonra "Kapalı". Kabloyu tak → birkaç sn içinde "Açık". |
| 6 | "Uyku" → Gönder | K23: "Hazırda bekletiliyor…" → birkaç sn sonra **"Hazırda"**. |
| 7 | Hazırdaki PC'de 5 güç butonuna bak | Hepsi pasif (soluk); "Uyandır" etkin. |
| 8 | "Uyandır" | "Uyanıyor · Paket" → "Ping yanıtı" (saniye sayacı akar) → "Port açık" → "Açık". Günlükte "Uyandı · X sn". Veri sekmesinde bu cihazda yeni ✓ deneme. |
| 9 | Anahtar "Zorla" iken "Kapat" | "Kapanıyor…" → "Kapalı". |
| 10 | Ethernet kablosu çıkıkken "Uyandır" | 60 sn sonra günlükte "Uyanmadı · BIOS'ta WoL…", durum "Kapalı", Veri'de ✗ deneme. (Sonra kabloyu tak, "Uyandır".) |
| 11 | PC açıkken "Yeniden" | "Yeniden başlıyor…" (kapanıp açılırken bu yazı kalır) → "Açık". Günlükte "Yeniden başladı". |
| 12 | Anahtarı "Nazik" yap, PC'de Not Defteri'ne kaydetmeden yazı yaz → "Kapat" | 90 sn "Kapanıyor…", sonra günlükte "Kapanmadı · Açık bir program engelliyor olabilir"; durum "Açık". (Bitince anahtarı "Zorla"ya geri al.) |
| 13 | PC'de `ipconfig /release` sonra `ipconfig /renew` (ya da router'da IP değiştir) | En geç ~60 sn içinde kartın IP'si yeni IP'ye döner (günlükte "IP güncellendi"), durum "Açık". |
| 14 | Veri › "Gerçek cihaz ekle": başka bir adla, aynı PC'nin IP'si, **MAC boş** | Durum'da önce "MAC bekleniyor"; PC açıkken birkaç sn içinde MAC kendiliğinden dolar (günlükte "MAC bulundu"). Asıl kayıt zaten bu MAC'e sahipse çakışmasın diye doldurulmaz; o zaman ilk kaydı silip yeniden dene. |

## Bitti tanımı
Domain testleri yeşil; cihaz testi #1–14 geçti; titreme yok.

## Sprint sonu raporu
_2026-10-29 · Claude_

### Yapılanlar
- **domain/model:**
  - `DeviceState`: On / Off(`lastAction`, K9) / Waking / PowerTransition, spec 9.1 ile birebir; On'da ayrıca `uptimeSec`.
  - `WakeStep`; turun ham sinyalleri `RoundSignals` (S1–S4); tur kararı `Verdict`.
- **StatusRules** (saf):
  - kurallar 1–5: S1 → doğrulanmış; S2 + MAC eş → doğrulanmış; S2 + farklı MAC → negatif ve S3/S4 yok sayılır (**K8**); S3/S4 → doğrulanmamış; hiçbiri → negatif.
  - Agent'lı cihazda S1 yoksa `agentReachable=false`. MAC'i bilinmeyen cihazda S2 doğrulanmamış pozitif sayılır.
  - `Tracker`: 2 negatif tur (geçişte 3) → Off; tek pozitif → On; 15 sn → `stale`; On görülünce `lastAction` temizlenir (K9).
- **StatusEngine:**
  - Ön planda (`ProcessLifecycleOwner`) 3 sn'de bir tüm cihazlara paralel tur; arka planda tur yok.
  - Wi-Fi yokken tur sayılmaz, böylece Wi-Fi kopunca sahte "Kapalı" olmaz. Cihaz başına mutex.
  - Koordinatörlerin geçiş katmanı (overlay) varken döngü o cihaza dokunmaz.
  - `requestRound` (tile için), `round`, `settle` / `settleOff`, `markTried`.
  - `StateFlow<Map<Long, DeviceState>>` + debug için `rounds`.
- **K7:** açılışta `DeviceStateCache`'ten `stale=true` ile yükleniyor; kesin karar türü ya da `lastAction` değişince yazılıyor.
- **WakeCoordinator** (9.2):
  - cihaz başına tek akış (ikinci tetikleme false döner); MAC yoksa olay ve başlamaz;
  - PACKET (t=0/2/5 paralel) → REPLY (1 sn turlar, 60 sn) → PORT (port kabul ya da S1, ≤ 30 sn);
  - `WakeAttempt(source)` yazılıyor; başarısızlıkta uyarı olayı ve Off; Wi-Fi yoksa deneme yazılmıyor.
- **PowerCoordinator** (9.3 + **K12** + **K23**):
  - yalnız `On` cihaza; agent yok / hazırda bekletme kapalı / zaten işlem var → gerekçeli red;
  - LOCK → On kalır + "Kilitlendi";
  - SLEEP / HIBERNATE / SHUTDOWN → PowerTransition → 3 negatif turda Off(`lastAction`); 90 sn'de "Kapanmadı" (Nazik'te "Açık bir program engelliyor olabilir");
  - RESTART → offline → online **ya da uptime düşüşü**, 180 sn;
  - `force` yalnız SHUTDOWN/RESTART'ta ve cihaz Zorla modundaysa;
  - UNSUPPORTED / BUSY / BAD_SIG / cevapsız → olay; UNKNOWN_PHONE → K6.
  - **K23:** `sleepHibernates` cihazda SLEEP "Hazırda bekletiliyor…" ve sonra "Hazırda" gösteriliyor.
- **AgentLinker** (7.5, 10.5, K6, K8, K16):
  - açılışta ve ön planda 60 sn'de bir DISCOVER; agentId eşleşen cihazın IP'si sessizce güncelleniyor;
  - hostname, hazırda bekletme ve `sleepHibernates` yazılıyor;
  - agentId'siz cihaz IP ya da MAC ile eşleşirse otomatik TOFU eşleşme; anahtar KeyVault'a, agentId ve takma ad cihaza;
  - agent başkasına eşliyse "Başka telefona eşli";
  - UNKNOWN_PHONE → anahtar silinip yeniden eşleşiliyor; S2 farklı MAC → discovery; cihaz On'a geçince (agentId yoksa) discovery;
  - MAC boşsa K16 ile agent NIC'lerinden ya da NetBIOS'tan dolduruluyor (başka cihazda kayıtlı MAC'e dokunulmuyor; kullanıcının MAC'i değişmiyor).
- **`StateLabels`:** spec 10.2 metinleri ("Açık · 3 ms", "Uyuyor", "Hazırda", "Uyanıyor · Paket · 4 sn", "Kapanıyor…" …).
- **`DomainEvents`:** tek seferlik uyarı ve onay olayları (S06'da toast ve çip olacak).
- **Room v2:** `Device.sleepHibernates` (K23), AutoMigration 1→2; şema `schemas/…/2.json`.
- **WoL Debug:**
  - **Durum** sekmesi: canlı durum, bayraklar, sinyal satırı, tur numarası ve ardışık negatif sayısı; Uyandır ve 5 güç butonu (onaylı; `On` değilse pasif, K12); Zorla/Nazik anahtarı; olay günlüğü (durum geçişleri + uyarılar).
  - **Veri** sekmesi: "Gerçek cihaz ekle" formu (ad, IP, isteğe bağlı MAC).

### Sapmalar / notlar
- **Wi-Fi yokken tur atılmıyor:** spec bunu açıkça söylemiyor; Wi-Fi kopunca cihazlar yanlışlıkla "Kapalı"ya düşmesin diye böyle yapıldı. Wi-Fi yokken asıl UI zaten tam ekran NoWifi (S11).
- **WAKE'in PACKET adımı:** ilk paket gidince REPLY başlıyor; 2. ve 5. sn paketleri arka planda sürüyor. Spec testi ("3. sn'de yanıt → `durationSec=3`") bunu gerektiriyor.
- **RESTART:** spec "offline → online" diyor; ayrıca **uptime düşüşü** de "yeniden başladı" sayılıyor (sprint görevi). Hızlı açılan PC'de offline hiç görülmeyebilir.
- **K23 notu:** Kaan'ın PC'si gibi Modern Beklemeli PC'yi elle (başından) uyutursa S3/S4 cevap verebiliyor. Bu durumda "Açık" + "Agent cevap vermiyor" gösteriliyor; S03/S04 notunda kararlaştırıldığı gibi.
- **Debug'daki S04 eşleşmesi** asıl cihaz kaydına taşınmadı. Bu yüzden hazırlıkta `WolAgent.exe reset` gerekiyor; sonra Debug › Agent sekmesindeki eski anahtar `BAD_SIG` verir, normal.
- **AgentLinker'ın birim testi yok** (Room + KeyVault + discovery bir arada). Davranışı cihaz testi #1, #13, #14 doğruluyor. Parçaları (AgentClient, NicSelector, K6 sonuç tipleri) S04'te testli.

### Otomatik test sonuçları
- `./gradlew :app:testDebugUnitTest` → **202/202** yeşil (S05 ile +36).
  - `StatusEngineTest.Rules`: S1 tek başına; S2 MAC eş / farklı (**K8**, S3'ü de geçersiz kılıyor); S1'in K8'den önce gelmesi; S3/S4 ve en küçük ms; MAC'siz S2; negatif; tek negatif ≠ kapalı, 2 → kapalı, geçişte 3; K9 metinleri ve temizlenmesi; 15 sn `stale`.
  - `StatusEngineTest.Engine` (sanal zaman):
    - 3 sn döngü ve 2 turda kapalı;
    - **titreme yok** (aralıklı kayıplarla 40 tur hep On);
    - arka planda tur yok, ön plana dönünce hemen tur;
    - K7 önbellekten `stale` → ilk turda kesinleşme;
    - Wi-Fi yokken tur yok, 15 sn sonra `stale`;
    - önbelleğe yazım;
    - katman (overlay) davranışı; cihaz ekleme/silme; `tried`.
  - `CoordinatorsTest.Wake`: 3. sn yanıt → `durationSec=3`, adımlar PACKET → REPLY → PORT, paketler 0/2/5; 60 sn → başarısız + uyarı + Kapalı; ikinci tetikleme yok sayılıyor; MAC yok; Wi-Fi yok; PORT 30 sn'de atlanıyor.
  - `CoordinatorsTest.Power`: K12 red; agent yok / hazırda bekletme kapalı; SHUTDOWN → transition → Off; SLEEP → Uyuyor; **K23** → Hazırda; 90 sn + Nazik metni; RESTART offline → online; RESTART uptime düşüşü; LOCK; UNSUPPORTED / BUSY / UNKNOWN_PHONE.
  - `MigrationTest` (Robolectric + MigrationTestHelper): v1 → v2 cihaz korunuyor, `sleepHibernates=0`.
- `assembleDebug` + `assembleRelease` yeşil. Telefona kuruldu:
  - mevcut v1 veritabanı göçle açıldı;
  - açılışta discovery çalıştı (MulticastLock alındı ve bırakıldı);
  - crash log temiz.

### Kontrol raporu — "baktım, tamam; sen de kontrol et"
- [x] Karar kuralları, K8, 2/3 tur, `stale`, titreme yok → testte → #1, #3, #5.
- [x] Uyandırma adımları, 3 sn süre, 60 sn başarısızlık → testte → #8, #10.
- [x] K12 (On değilse komut yok), SHUTDOWN / SLEEP / K23 / RESTART / 90 sn → testte → #6, #7, #9, #11, #12.
- [x] Room göçü testte; telefonda mevcut veriyle açıldı.
- [x] **Kaan: #1–#14 geçti** (2026-10-29); titreme yok.

### Açık kalanlar
- Asıl Cihazlar ekranı, olayların toast/çip olarak gösterimi, "Başka telefona eşli" çipi: S06.
- Cihaz ekleme panelinde "agent bulundu → eklenince eşleşir": S07 (AgentLinker hazır).
- Tile `requestRound` kullanımı: S12.
