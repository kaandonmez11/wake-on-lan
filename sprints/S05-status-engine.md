# S05 — StatusEngine + Wake/Power koordinatörleri

**Spec:** 7.5, 9.1, 9.2, 9.3, 10.5 (agent bağlantı denemesi zamanları), 16 (domain testleri), 17 faz 5 · **Kararlar:** K6, K7, K8, K9, K12, K16, K19

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
Hazırlık: Debug › Veri'de gerçek PC'yi (IP + MAC) ekle; agent kurulu ve S04'te eşleşmiş olsun.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | PC açıkken Debug › Durum | ~1 sn içinde "Açık · X ms", verified ✓, S1 ✓. |
| 2 | Uygulamayı kapatıp aç | Önce son durum soluk ("kontrol ediliyor"), ~1 sn sonra kesinleşir. |
| 3 | 2 dk ekranı izle | Durum **hiç titremez** (Açık↔Kapalı gidip gelmez). |
| 4 | PC'de agent servisini durdur (`services.msc`) | Durum "Açık" kalır, "Agent cevap vermiyor" bayrağı çıkar (S3 sayesinde). Sonra servisi geri başlat → bayrak kalkar. |
| 5 | PC açıkken Ethernet kablosunu çek | ~6–9 sn sonra "Kapalı". Kabloyu tak → birkaç sn içinde "Açık". |
| 6 | "Uyku" | "Uyutuluyor…" → birkaç sn sonra "**Uyuyor**". |
| 7 | Uyuyan PC'de 5 güç butonuna bak | Hepsi pasif. |
| 8 | "Uyandır" | Adımlar sırayla: PACKET → REPLY → PORT, saniye sayacı akar; PC açılınca "Açık". Veri sekmesinde yeni başarılı deneme + süresi. |
| 9 | "Kapat" (Zorla) | "Kapanıyor…" → "Kapalı". |
| 10 | Kapalı PC'yi uyandırırken kablosu çıkık olsun | 60 sn sonra "Uyanmadı · BIOS'ta WoL…" uyarısı, durum "Kapalı", başarısız deneme yazılır. |
| 11 | "Yeniden" | "Yeniden başlıyor…" → kısa süre kapalı → tekrar "Açık". |
| 12 | Nazik + Not Defteri'nde kaydedilmemiş yazı + "Kapat" | 90 sn sonra "Kapanmadı · Açık bir program engelliyor olabilir". |
| 13 | PC'de `ipconfig /release` sonra `/renew` ile farklı IP al (ya da router'da IP değiştir) | En geç ~60 sn içinde cihazın IP'si yeni IP'ye güncellenir, durum "Açık". |
| 14 | Debug › Veri'de MAC'siz yeni kayıt ekle (aynı PC'nin IP'si) | PC açıkken birkaç sn içinde MAC kendiliğinden dolar (agent/NetBIOS'tan). |

## Bitti tanımı
Domain testleri yeşil; cihaz testi #1–14 geçti; titreme yok.

## Sprint sonu raporu
_(Claude doldurur)_
