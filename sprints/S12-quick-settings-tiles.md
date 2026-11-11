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
_2026-11-11 · Claude_

### Doğrulananlar
- **`startActivityAndCollapse`:** Android 14+ hedefleyen uygulamada `Intent` sürümü `UnsupportedOperationException` atıyor; `PendingIntent` sürümü kullanılmalı ([Android 14 davranış değişiklikleri](https://developer.android.com/about/versions/14/behavior-changes-14)). Kod yalnız `PendingIntent` (immutable) kullanıyor.
- **`unlockAndRun`, `showDialog`, kilit ekranında `onClick`:** bunlar için güncel bir belge sayfasına ulaşamadım; `TileService` API'sinin bilinen davranışına göre yazıldı (kilitliyken `onClick` çalışır; `unlockAndRun` kilit açılınca çalıştırır, kilit yoksa hemen; `showDialog` panelin üstünde dialog gösterir). One UI 9'daki gerçek davranışı cihaz testi #4, #6, #9 gösterecek.
- **Tile ikonu:** sistem ikonu tek renge boyar; alfa kanallı tek renk görsel yeterli. Cihaz ikonları font glifi olduğundan vector drawable yerine glif çalışma anında tek renk bitmap'e çiziliyor (`Icon.createWithBitmap`, anahtar başına önbellek). Atanmamış slot uygulama logosunu kullanıyor.

### Yapılanlar
- **`WolTileService1..3`** (manifestte statik, pasif mod) + **`TileBinder`** (slot → cihaz, DataStore `tileSlots`; silinmiş cihaz = atanmamış).
- **Tile içeriği (`TileLabels`, saf):**
  - Başlık cihaz adı; alt yazı "Açık · 2 sa" (K9, agent `uptimeSec`; yoksa "Açık"), "Kapalı", "Uyuyor", "Hazırda", "Uyanıyor 8 sn", "Kapanıyor…", "Wi-Fi yok", "İzin yok", "Kontrol ediliyor…".
  - State: açık `ACTIVE`, kapalı/geçişte `INACTIVE`, Wi-Fi ya da izin yoksa `UNAVAILABLE`.
  - Atanmamış slot: "WoL slot N" · "Cihaz seç"; dokununca Ayarlar açılıp "Hızlı ayarlar" bölümüne kayar.
- **`onStartListening`:** ayar + cihaz + durum + Wi-Fi akışları birleştirilip tile güncellenir. Hemen bir tur atılır, panel açıkken 3 sn'de bir yinelenir. `onStopListening`'de durur.
- **Kapalı cihaza dokunma:** kilit açtırmadan `WakeCoordinator.wake(TILE)`; `TileVerifyWorker` (expedited) akışın bitmesini bekler, `WakeAttempt(TILE)` yazılır. Başarısızsa "… uyanmadı" bildirimi (yeni "Hızlı ayarlar" kanalı).
- **Açık cihaza dokunma** (önce `unlockAndRun`; K12: yalnız `On`):
  - **A · `TilePowerActivity`:** şeffaf, arkası bulanık tam ekran. Cihaz adı + durum, 172 orb + 220 halka (basılı tut = Kapat), "Kapatmak için basılı tut", 4 mini hold buton (Kilitle, Uyku, Hazırda, Yeniden; Windows ikonlu), "Vazgeç". Giriş: bulanıklık 0 → hedef + orb 0.8 → 1 (`bouncy`); kapanışta tersi. Arka plan sürekli canlı (yarı saydam). Komut sonrası, geri tuşunda ve boşluğa dokununca kapanır.
  - **B · dialog:** `showDialog` ile panelin üstünde cam dialog, aynı içerik küçük boyda (Compose, pencereye yaşam döngüsü sahibi bağlanarak).
  - **C · çift dokunuş:** ilk dokunuşta alt yazı 3 sn "Kapatmak için tekrar dokun"; pencere içinde ikinci dokunuş Kapat, süre dolunca normale döner (`DoubleTap`).
- **Komut sonrası:** tile durum akışını izler ("Kapanıyor…" → "Kapalı"). Panel kapansa da worker bekler; Kapat/Uyku/Hazırda sonunda PC hâlâ açıksa "… kapanmadı" bildirimi. Başarıda bildirim yok.
- **Ayarlar › Hızlı ayarlar:** tile'ların panele nasıl ekleneceğini anlatan ipucu.
- **Debug › Sahte PC:** sahte cihaz artık "eşli" sayılıyor (anahtar takma adı), tile ve panelden güç komutları sahte PC'ye gidiyor. Mevcut sahte PC kaydı sekme açılınca kendiliğinden düzeltilir.

### Testler
- Android: **325/325 yeşil**.
  - `TileModelTest`: binder eşlemesi; atanmamış slot; tüm durumların alt yazısı ve state'i (uptime dk/sa/gün dahil); dokunma kararı (kapalı → uyandır, MAC yok, mod A/B/C, geçişte ve Wi-Fi/izin yokken hiçbir şey, K12); mod C 3 sn penceresi (sanal zaman).
  - `TilePowerContentTest` (Robolectric Compose): orb 1,5 sn tutulunca Kapat, 0,8 sn'de bırakınca iptal; mini buton kendi komutunu gönderir, "Vazgeç" kapatır; PC açık değilken komut gitmez.
- `assembleDebug` ve `assembleRelease` temiz. Telefonda 3 tile servisi kayıtlı, açılışta çökme yok. Agent değişmedi.

### Animasyon denetimi
- Mod A: tek `Animatable` (bulanıklık yarıçapı, arka plan alfası, içerik alfası, orb ölçeği); giriş `bouncy`, çıkış `snappy`; azaltılmış harekette kısa solma.
- Orb ve hold butonları mevcut bileşenler (halka dolumu, thud, iptalde geri boşalma). Durum ve ipucu satırı `AnimatedText`.
- Tile'ın kendisi sistem bileşeni; alt yazı durum akışıyla güncellenir.

### Sapmalar
- Tile ikonu vector drawable değil, glif bitmap'i (yukarıda).
- Mod C'de kilit ikinci dokunuşta açtırılıyor (ilk dokunuş yalnız onay yazısını gösterir).
- Tile'dan gelen başarısızlık bildirimi zamanlayıcı başarısız ekranını değil uygulamayı açar.
- Mod A'da arkadaki bulanıklık pencere bulanıklığını destekleyen cihazlarda görünür; desteklenmezse yarı saydam canlı arka plan kalır.
- Uyanırken ya da kapanırken tile'a dokunmak yok sayılır.

### Kontrol raporu (K20)
Şunlara baktım, tamam; sen de kontrol et:
- Manifest: 3 tile servisi `BIND_QUICK_SETTINGS_TILE` izniyle dışa açık; `TilePowerActivity` dışa kapalı, son uygulamalarda görünmez.
- Telefonda `QS_TILE` sorgusunda uygulamanın servisleri listeleniyor.
- Tile'lar Kaan'ın paneline benim tarafımdan eklenmedi; cihaz testinde elle eklenecek.

### Açık kalanlar
- Kilit ekranı davranışları ve mod B dialog'unun One UI'daki görünümü cihazda doğrulanmadı (cihaz testi).
