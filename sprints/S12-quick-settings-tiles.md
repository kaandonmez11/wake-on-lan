# S12 — Hızlı Ayarlar tile'ları

**Spec:** 9.1 (tile tur sıklığı), 10.8, 12.2 (tile overlay), 12.6, 17 faz 11 · **Kararlar:** K9, K10, K12

## Amaç
3 tile slotu bildirim panelinden cihazları gösterir ve kontrol eder: kapalıyı tek dokunuşla (kilit ekranında bile) uyandırır, açığı seçili moda göre (A overlay, B dialog, C çift dokunuş) kapatır.

## Doğrulanacak
- Android 17'de `TileService.startActivityAndCollapse(PendingIntent)`, `unlockAndRun`, `showDialog` davranışı ve kilit ekranında `onClick` kısıtları.
- Tile ikonu için vector drawable gereksinimleri.

## Görevler
1. **WolTileService1..3** (manifest'te statik, `ACTIVE_TILE` meta-data gerekirse) + `TileBinder` (slot → cihaz, DataStore `tileSlots`).
2. **Tile içeriği:** başlık = ad, alt yazı = durum ("Açık · 2 sa" (K9 uptime), "Uyuyor", "Hazırda", "Kapalı", "Uyanıyor 8 sn", "Kapanıyor…", "Wi-Fi yok", "İzin yok"), ikon = `sym_*` vector; state `ACTIVE`/`INACTIVE`/`UNAVAILABLE`; atanmamış slot "WoL slot N" · "Cihaz seç" → Ayarlar'ın slot bölümü.
3. **onStartListening:** DB + StatusEngine'den oku, hemen `requestRound`, panel açıkken 3 sn turlar, `updateTile()`; `onStopListening`'de dur.
4. **Kapalı cihaza dokunma:** doğrudan uyandırma (kilit ekranında da) → `VerifyWorker` ile doğrulama (K10), `WakeAttempt(TILE)`; başarısızlıkta bildirim.
5. **Açık cihaza dokunma** (önce `unlockAndRun`, K12: yalnız `On`):
   - **A · TilePowerActivity:** şeffaf, blur'lu tam ekran; cihaz adı + durum, 172 orb + 220 halka (hold = Kapat), "Kapatmak için basılı tut", 4 mini hold buton (Windows ikonlu), "Vazgeç"; sürekli canlı arka plan; giriş blur 0→hedef + orb 0.8→1 `bouncy`, kapanışta tersi; komut sonrası kapanır.
   - **B · Dialog:** `showDialog` ile küçük cam dialog, aynı hold butonları.
   - **C · Çift dokunuş:** ilk dokunuş alt yazı 3 sn "Kapatmak için tekrar dokun", ikinci dokunuş Kapat.
6. **Komut sonrası:** tile alt yazısı durum akışını izler; panel kapansa da doğrulama worker'da; bildirim yalnız başarısızlıkta.
7. Ayarlar'daki slot satırına "Eklemek için bildirim panelini düzenle" ipucu.

## Otomatik testler (Claude koşturur)
- TileBinder eşlemesi, durum → tile metni/state eşlemesi (tüm durumlar), C modunda 3 sn pencere mantığı (sanal zaman).
- Robolectric Compose: TilePowerActivity'de 1.5 sn hold → Kapat, erken bırakma → iptal.

## Cihaz testi (Kaan yapar)
Hazırlık: Ayarlar › Hızlı ayarlar › Slot 1 = PC. Bildirim panelini düzenle → "WoL" tile'ını panele ekle. Slot 2'yi boş bırakıp onu da ekle.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Paneli aç, PC açık | Tile: PC adı, "Açık · X sa", aktif (renkli). |
| 2 | Boş slot tile'ı | "WoL slot 2 · Cihaz seç"; dokununca Ayarlar'da slot seçimi açılır. |
| 3 | PC'yi kapat (uygulamadan), paneli aç | Tile birkaç sn içinde "Kapalı"/"Uyuyor", pasif görünüm. |
| 4 | **Telefon kilitliyken** paneli indir, PC tile'ına dokun | Kilit açtırmadan "Uyanıyor…"; PC uyanır; tile sonra "Açık". |
| 5 | Uyandırma sonrası uygulamayı aç | Geçmişte yeni başarılı deneme. |
| 6 | Mod A: telefon kilitliyken açık PC tile'ına dokun | Önce kilit açma istenir; açınca bulanık arkalı tam ekran overlay yaylanarak gelir: büyük orb, "Kapatmak için basılı tut", 4 mini Windows ikonlu buton, "Vazgeç". |
| 7 | Orb'u 1.5 sn tut | Halka dolar, thud; overlay kapanır; tile "Kapanıyor…" → "Kapalı". |
| 8 | Overlay'de "Vazgeç" | Overlay tersine animasyonla kapanır, hiçbir şey olmaz. |
| 9 | Ayarlar'da mod B, açık PC tile'ına dokun | Panel üstünde küçük cam dialog; hold butonları çalışır. |
| 10 | Mod C, tek dokun | Alt yazı 3 sn "Kapatmak için tekrar dokun"; 3 sn içinde tekrar dokun → Kapat; 3 sn geçerse normale döner. |
| 11 | Wi-Fi kapalıyken panel | Tile "Wi-Fi yok", kullanılamaz (gri). |
| 12 | PC'nin kablosunu çıkar, tile'dan uyandır, paneli kapat | ~60 sn sonra "uyanmadı" bildirimi. |

## Bitti tanımı
Testler yeşil; doğrulanacaklar raporlandı; cihaz testi #1–12 geçti.

## Sprint sonu raporu
_(Claude doldurur)_
