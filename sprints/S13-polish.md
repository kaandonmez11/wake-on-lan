# S13 — Cila: animasyon denetimi, performans, erişilebilirlik, uygulama ikonu

**Spec:** 10.10, 12 (tamamı), 16 (elle test listesi), 17 faz 12 · **Kararlar:** K19, K20, K21

## Amaç
Uygulama baştan sona denetlenir: animasyonsuz geçiş kalmaz, 120 Hz'de jank yok, erişilebilirlik tamam, gerçek uygulama ikonu yerinde. Son uçtan uca test turu.

## Görevler
1. **Uygulama ikonu (K21):** adaptive + monochrome ikon S00'da yapıldı. Burada yalnız gerçek launcher'da son kontrol ve debug ikonuna "DEBUG" bandı.
2. **Animasyon denetimi:** her ekran × her durum için bölüm 12 maddelerinin tablosu (raporda ✓/düzeltildi). Sabit süreli tween kalmamalı (istisnalar hariç); tüm animasyonların kesilebilir olduğu kontrolü.
3. **Animasyonları kaldır:** tüm sonsuz döngüler durur, geçişler crossfade — her ekranda.
4. **Performans:** `:baselineprofile` + `:macrobenchmark` modülleri (açılış, sekme geçişi, hero morph, sheet açma senaryoları); baseline profile üretimi için Kaan'a komut; AGSL shader süresi; gereksiz recomposition taraması (Compose compiler raporları).
5. **Erişilebilirlik (10.10):** tüm ikon butonlarda Türkçe `contentDescription`, ≥44 dokunma hedefi, `Switch` semantics, hold butonlarında `onLongClickLabel` + TalkBack onay alternatifi, durumun metinle anlatımı, kontrast ≥ 4.5:1 (renk çiftleri hesaplanıp raporlanır).
6. **Release build:** R8 açık + kurallar; imzalama için **Kaan'a sor** (yerel keystore oluşturma); "WoL Debug" release'te yok.
7. **Temizlik:** geçici kodlar, TODO'lar, kullanılmayan kaynaklar; README güncel; `TASKLIST.md` tamamen işaretli.

## Otomatik testler (Claude koşturur)
- Tüm birim + Robolectric UI testleri, `dotnet test`, `assembleRelease`, lint (hata yok).

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Release APK'yı kur | Yeni uygulama ikonu (temalı ikon açıkken tek renk de doğru); "WoL Debug" ikonu yok. |
| 2 | Macrobenchmark'ı Claude'un verdiği komutla çalıştır | Rapor üretilir; sonuçları Claude'a ilet (jank yüzdesi, açılış süresi). |
| 3 | Geliştirici seçenekleri › "Yenileme hızını göster" açık, uygulamayı 5 dk kullan | Hep 120; takılma hissi yok. |
| 4 | Animasyonları kaldır açık, tüm ekranları gez | Hiçbir şey sürekli hareket etmiyor; geçişler kısa solma; bozuk görünüm yok. |
| 5 | TalkBack ile tüm ekranları gez | Her buton Türkçe okunur; durumlar ("Açık, 3 milisaniye") okunur; hold butonları onay dialog'u ile çalışır. |
| 6 | **Spec 16 elle test listesi:** uyku ve kapalıdan uyandırma; 5 güç komutu; Zorla/Nazik (Not Defteri); IP değişimi (DHCP yenileme); mobil veri açıkken Wi-Fi'da çalışma; tile'lar (kilit ekranında uyandırma, kapatmada kilit açma); zamanlayıcı retry (kablo çıkarılarak) | Hepsi önceki sprintlerdeki beklenen sonuçları veriyor. |
| 7 | Tüm uygulamayı gez, "pat" diye değişen bir şey ara | Bulunamıyor. Bulursan ekran + adım yaz. |

## Bitti tanımı
Tüm testler yeşil; animasyon denetim tablosu raporda tam; cihaz testi #1–7 geçti; uygulama günlük kullanıma hazır.

## Sprint sonu raporu
_2026-11-12 · Claude_

### Release imzası (Kaan'a soruldu)
Kaan'ın seçimi: **mevcut anahtar** (K30). Release, telefonda kurulu sürümle aynı anahtarla (bu PC'deki debug keystore) imzalanıyor: mevcut uygulamanın üstüne kuruldu, cihazlar, zamanlayıcılar ve PC eşleşmesi kaldı. Anahtar repoda değil; yedeği Kaan'da olmalı (`C:\Users\PC\.android\debug.keystore`).

### Yapılanlar
- **Release build:** R8 + kaynak küçültme açık (`proguard-rules.pro`: satır numaraları, işçiler, enum'lar). APK 39,3 MB (debug) → **3,8 MB** (release). Telefona kuruldu: açılışta çökme yok, `DebugActivity` release'te yok, uygulama hata ayıklanabilir değil.
- **Debug ikonu:** "WoL Debug" launcher ikonunda amber "DEBUG" bandı (asıl ikonla karışmasın).
- **Performans altyapısı:** `:macrobenchmark` modülü (uygulamanın `benchmark` türünü hedefler):
  - `Benchmarks`: soğuk açılış, sekme geçişi, hero morph, panel açma, liste kaydırma (kare süreleri).
  - `BaselineProfileGenerator`: açılış + sık yollar; çıktı `app/src/main/baseline-prof.txt`'ye kopyalanır. `profileinstaller` eklendi, manifestte `<profileable>`.
  - Komutlar README'de. **Ölçüm henüz koşulmadı**: telefonun açık ve kilitsiz olması gerekiyor ve arayüzü kendisi kullanıyor (aşağıda "Açık kalanlar").
- **Compose derleyici raporu** (`-PcomposeReports`): strong skipping açık; 458 yeniden başlatılabilir composable'ın hepsi atlanabilir; 5937 argümandan 15'i bilinen kararsız (cihaz listeleri; örnek karşılaştırmasıyla atlanıyor). Düzeltme gerektiren bir bulgu yok.
- **Temizlik:** TODO/FIXME yok; README güncel (release, imza, ölçüm komutları, lint); SPEC'e K30.

### Animasyon denetimi (bölüm 12)
Yöntem: koddaki tüm `tween`, `snap`, sonsuz döngü ve boyut geçişleri tarandı; ekran ekran maddeler karşılaştırıldı.

| Ekran / durum | Madde | Sonuç |
|---|---|---|
| Sekme değişimi (dokunma) | crossfade + 0.98 → 1 + 12'lik kayma (`smooth`), gösterge `bouncy`, arka plan morph | ✓ |
| Sekme değişimi (kaydırma) | parmağa bağlı, ileri-geri sarılır, bitişte flaş yok | ✓ (S10) |
| Push (Ayarlar, Başarısız) | sağdan giriş, %30 paralaks + kararma, predictive back, sağa çekerek kapatma | ✓ |
| Sheet (cihaz, zamanlayıcı) | alttan `smooth`, scrim + blur, sürükle-kapat, lastik | ✓ |
| Kart → hero | seçim halkası `bouncy` kayar, kenar esnemesinde kartla esner | ✓ (S09) |
| Hero / kartlar | durum çipi, adım kartları, geçmiş çubukları, ilerleme çubuğu, boyut değişimi | ✓ |
| Açık cihaz efektleri | nefes, dönen hüzme, ışık süpürmesi, halka dönüşü | ✓ |
| Basılı tut | 1,5 sn dolum, iptalde geri boşalma, tamamlanma vurgusu, titreşim | ✓ |
| Otomasyon kartı | anahtar, alt satır, amber/kırmızı satır, liste ekleme/silme | ✓ |
| Zamanlayıcı editörü | tetikleyici zemini, gün/saat ↔ Wi-Fi bölümü, haneler | ✓ |
| Ayarlar | izin rozeti, stepper haneleri + titreme, segmentler, lisans açılımı | ✓ |
| Kapılar / onboarding | 1.04 → 1 + fade, yukarı çözülme, halkalar, sayfa yayı | ✓ |
| Tile paneli | bulanıklık 0 → hedef + orb 0.8 → 1 (`bouncy`), tersi | ✓ |
| Toast, bağlam menüsü | yaylı giriş, solarak çıkış | ✓ |

- **Sabit süreli `tween` yalnız istisnalarda:** azaltılmış hareket solmaları, sonsuz döngüler (doğrusal), basılı tut dolumu (spec 1,5 sn) ve uyanma ilerleme çubuğu (gerçek süreye bağlı). Geri kalan her şey spring token'ı.
- **Kesilebilirlik:** geçişler `Animatable`/`animate*AsState` üzerinde; yeni hedef gelince o anki değerden devam ediyor.

### Animasyonları kaldır
- Tüm sonsuz döngüler tek yerden geçiyor (`loopProgress`, `breathing`, canlı arka plan); üçü de azaltılmış harekette duruyor.
- Geçişler `motionSpec` ile kısa solmaya düşüyor; kapı halkaları sabit, stepper titremesi yok.
- Cihazda tüm ekranların gezilmesi cihaz testi #4'te.

### Erişilebilirlik (10.10)
- **Etiketler:** tüm `pressable` öğeler `onClickLabel` ya da görünür metin taşıyor; ikon butonlarda Türkçe `contentDescription` (Geri, Cihaz ekle, Zamanlayıcı ekle, Düzenle…). Süs ikonları `null`.
- **Dokunma hedefi:** 34–42 dp çizilen öğeler var (segmentler, çipler, stepper −/+, hero kalem). Compose küçük öğelerin dokunma alanını çevrede yer varsa kendiliğinden asgari hedefe (48 dp) genişletiyor; görsel boyutlar değiştirilmedi.
- **Anahtarlar:** `Role.Switch` + "Açık/Kapalı" durum metni.
- **Basılı tut:** kart ve hero'da TalkBack çift dokunuşu onay dialog'u açıyor. Tile panelinde çift dokunuş komutu doğrudan gönderiyor (panelin kendisi onay bağlamı).
- **Durum metni:** kartlar "ad, durum" olarak okunuyor; renk tek bilgi taşıyıcısı değil.
- **Kontrast (WCAG oranı, hesaplandı):**

| Metin | Koyu zemin | Cam kart | En kötü nokta* |
|---|---|---|---|
| Birincil (beyaz) | 19,4 | 11,6 | 7,4 |
| İkincil | 13,8 | 8,2 | 5,3 |
| İkincil-alt | 12,2 | 7,3 | 4,7 |
| Üçüncül | 9,3 | 5,6 | **3,5** |
| Amber | 14,3 | 8,5 | 5,4 |
| Kırmızımsı (Danger) | 8,8 | 5,3 | **3,4** |
| Yeşil (Success) | 15,6 | 9,3 | 5,9 |

  \* En kötü nokta: en parlak arka plan lekesinin tam merkezindeki cam kartın üst kenarı.
  - Gradient üstündeki koyu metin: 16 paletin orta noktasında en düşük 5,05. Gradient'in koyu ucunda Gece 2,5, Bakır 3,8, Yakut 4,2, Gök 4,4.
  - Açık kart üstündeki beyaz metin: orta noktada en düşük 4,30 (Limon), 4,49 (Nane); kartın en açık köşesinde 3,3–3,9 (Limon, Gümüş, Nane, Güneş, Gül, Buz).
  - Sonuç: tipik yüzeylerde hepsi ≥ 4,5. **Üçüncül ve kırmızımsı metin en parlak noktada, bazı paletler uçlarda 4,5'in altında**; renkler tasarım kararı olduğu için değiştirilmedi (aşağıda).

### Testler
- Android: **325/325 yeşil**; `lintDebug` hatasız (37 uyarı: `UseKtx` önerileri, sürüm bildirimleri, debug dosyaları); `assembleDebug`, `assembleRelease`, `:macrobenchmark:assembleBenchmark` temiz.
- Agent: `dotnet test` **73/73**.

### Sapmalar
- `:baselineprofile` ve `:macrobenchmark` yerine tek `:macrobenchmark` modülü (üretici + ölçümler); Gradle baseline profile eklentisi kullanılmadı (AGP 9.4 uyumu doğrulanamadı), profil elle kopyalanıyor.
- Release debug anahtarıyla imzalı (Kaan'ın kararı).
- Kontrastı düşük noktalar raporlandı, renkler değiştirilmedi.

### Kontrol raporu (K20)
Şunlara baktım, tamam; sen de kontrol et:
- Release APK telefonda kurulu ve çalışıyor; "WoL Debug" ikonu artık yok (test araçları gerekirse debug sürümü yeniden kurulur, veriler kalır).
- Keystore ve şifre repoda yok.
- `TASKLIST.md`: S00–S13 işaretli; yalnız S13 cihaz testi açık.

### Açık kalanlar
- **Macrobenchmark ve baseline profile** koşulmadı (telefon açık/kilitsiz olmalı; Kaan "çalıştır" deyince ben koşarım ya da README'deki komutla).
- **Kontrast:** üçüncül / kırmızımsı metin ve bazı palet uçları en kötü noktada 4,5'in altında; istenirse tonlar bir kademe açılır.
- AGSL shader süresi ayrıca ölçülmedi (macrobenchmark kare süreleri gösterecek).
