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
_(Claude doldurur)_
