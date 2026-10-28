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
_(Claude doldurur)_
