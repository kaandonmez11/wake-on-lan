# S01 — Android iskeleti + tasarım sistemi

**Spec:** 4, 5 (ui/theme, ui/background, ui/nav), 10 (genel ölçüler), 10.9, 11, 12.1–12.4, 12.7, 17 faz 1 · **Kararlar:** K19

## Amaç
Uygulamanın görsel temeli hazır: fontlar, renkler, cam yüzey, spring token'ları, haptikler, canlı AGSL arka plan + sahneler, yüzen nav ve iki boş sekme. Telefonda sekmeler arası geçiş ve arka plan morph'u gerçek 120 Hz ekranda görülebilir.

## Doğrulanacak
- Haze (`dev.chrisbanes.haze`) güncel stabil sürümü ve kullanılan Compose BOM ile uyumu.
- Compose'ta değişken font eksenlerinin (`FontVariation`, FILL/wght/GRAD/opsz) Material Symbols ile çalışması.
- Onest ve JetBrains Mono'nun resmi kaynağı (Google Fonts / GitHub) ve OFL lisans metinleri.

## Görevler
1. **Fontlar:** Onest (500/600/700/800), JetBrains Mono (500/600/700) `res/font`; Material Symbols Outlined değişken TTF + codepoints. Lisanslar `assets/licenses/`.
2. **ui/theme:** `Color` (11.3, cihaz paleti 8 gradient, açık kart karışımları), `Type` (11.4 tablo, tabular rakamlar), `Motion` (12.1 spring token'ları, `stiffness=(2π/response)²`), "animasyonları kaldır" algısı (`ANIMATOR_DURATION_SCALE`), `Haptics` (12.7 primitifleri; ayardan kapatılabilir — şimdilik bayrak), `Glass` (11.2 değerleri), `Symbols` (`SymbolIcon(name, fill, weight)` + FILL animasyonu), köşe yarıçapları (11.5).
3. **Ortak modifier'lar:** basma geri bildirimi (0.96 `quick`, bırakınca `snappy`), haneli yuvarlanan sayı (`RollingNumber`), durum metni `AnimatedContent` kalıbı.
4. **GlassSurface:** normal / güçlü / dock varyantları (Haze blur + doygunluk, iç çerçeve, üst vurgu, gölge).
5. **LivingBackground (AGSL):** tek geçiş shader, 4 odak + `time` + film grain; `Scenes` (Cihazlar, Otomasyon, tam ekran); sahne değişiminde her odak `gentle` spring ile akar; sekmeli ekranlarda sabit, tam ekran sahnede sürekli salınım (12.4).
6. **Nav:** `AppNav` + `FloatingNavBar` (10.9): sola cam hap içinde 2 sekme, aktif gösterge spring ile kayar, aktif ikon FILL 0→1; sağda 68 gradient "+" (şimdilik sadece basma animasyonu).
7. **İki boş sekme:** "Cihazlar" ve "Otomasyon" sayfa başlıkları (40/42, 700); Otomasyon'da alt açıklama satırı. Sekme geçişi: crossfade + 0.98→1 ölçek + yöne göre 12 kayma (`smooth`) + arka plan sahne morph'u.
8. **Edge-to-edge:** şeffaf sistem çubukları, açık ikonlar; alt nav için 124 boşluk.
9. **Debug › Tasarım sekmesi:** cam varyantları vitrini; 5 spring token'ı için ileri-geri kayan kutular; her haptik primitif için buton; sahne seçici (Cihazlar/Otomasyon/Tam ekran sürekli); RollingNumber demo (+1/−1); FPS/frame süresi göstergesi.

## Otomatik testler (Claude koşturur)
- Spring token stiffness değerleri (tablo ile ±1).
- Robolectric Compose: nav'da sekmeye dokununca seçili sekme değişir; FILL hedefi 1 olur.
- `assembleDebug` yeşil.

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Uygulamayı aç | Koyu zemin üzerinde yumuşak mavi-mor ışık lekeleri; üst/alt sistem çubukları şeffaf, ikonlar beyaz; altta yüzen cam nav. |
| 2 | Otomasyon sekmesine dokun | Gösterge yaylanarak kayar; Otomasyon ikonu içi dolarak belirginleşir, Cihazlar ikonu boşalır; içerik hafif kayıp kararak değişir; arka plan ışıkları mor-turkuaza **akarak** geçer (sıçrama yok). |
| 3 | İki sekme arasında hızlı hızlı gidip gel | Animasyonlar yarıda kesilip yeni yöne akıcı döner, takılma/atlama yok. |
| 4 | Sekmeli ekranda 10 sn bekle | Arka plan **duruyor** (sürekli hareket yok). |
| 5 | WoL Debug › Tasarım › sahne "Tam ekran" | Arka plan ışıkları yavaşça sürekli salınır/büyüyüp küçülür. |
| 6 | Tasarım › haptik butonları | Her biri farklı his: hafif tık, tok "thud", tık-tık vb. |
| 7 | Tasarım › cam vitrini | Cam yüzeylerin arkası bulanık, kenarda ince parlak çizgi, üst kenar daha parlak. |
| 8 | Tasarım › FPS göstergesi açıkken sekme değiştir | ~120 fps, kare süresi ~8 ms civarı; kırmızı/uzun kare yok. |
| 9 | Telefon Ayarlar › Geliştirici seçenekleri › "Animatör süre ölçeği: Kapalı", uygulamaya dön, sekme değiştir | Geçişler kısa yumuşak solmaya iner; Tam ekran sahnede salınım durur. (Testten sonra ayarı geri aç.) |
| 10 | Sayfa başlıkları ve Debug'daki sayılar | Başlıklar Onest (yuvarlak modern font); sayılar JetBrains Mono (eşit genişlikli). |

## Bitti tanımı
Testler yeşil; 12.1–12.4 maddeleri kodda denetlendi; cihaz testi #1–10 geçti.

## Sprint sonu raporu
_2026-10-29 · Claude_

### Yapılanlar
- **Fontlar:** Onest ve JetBrains Mono **değişken** TTF olarak eklendi (google/fonts, OFL). Ağırlıklar (Onest 500–800, Mono 500–700) aynı dosyanın `wght` ekseninden üretiliyor; rakamlar tabular (`tnum`).
- **Material Symbols:** Tam değişken font ~10 MB. `tools/symbols/subset_symbols.py` ile yalnız kullanılan **38 sembole** indirildi (68 KB). FILL/GRAD/opsz/wght eksenleri korunuyor. Kod noktaları üretilen `ui/theme/Sym.kt`'den geliyor. Yeni ikon: `tools/symbols/symbols.txt`'ye ekle ve script'i çalıştır. Lisanslar `assets/licenses/`.
- **ui/theme:**
  - `Color` (11.3 + 8'li `DevicePalette` + açık kart karışımları), `Type` (11.4 rolleri), `Shapes`/`WolDimens` (11.5 + spec 10 ölçüleri).
  - `Motion`: 5 `SpringToken`, stiffness `(2π/response)²`; `LocalReduceMotion` (ANIMATOR_DURATION_SCALE'i ContentObserver ile canlı izler); `motionSpec`.
  - `Haptics`: 12.7 primitifleri, `enabled` bayrağı, SPIN yoksa çift CLICK.
  - `Glass` (varyantlar, CSS açılı gradient), `Symbols` (`SymbolIcon`), `WolTheme`.
- **SymbolIcon:** FILL ekseni `snappy` spring ile akıyor. Her karede yeni Typeface üretmemek için FILL 0.1'lik adımlara yuvarlanıyor ve aileler önbellekte tutuluyor. İkon boyutu font ölçeğinden etkilenmiyor.
- **ui/components:**
  - `GlassSurface`: Haze 2 `hazeBlur`, blur 24/30 + doygunluk 1.6 (`ColorMatrix`), `#0C0C1E` %40 zemin, 145° beyaz gradient, %14 çerçeve + %35 üst vurgu, `dropShadow` 0/12/32. Varyantlar: Normal / Strong (sağ üstten cihaz rengi radial) / Dock.
  - `pressable`/`pressScale` (0.96 `quick`, bırakınca `snappy`), `AnimatedText`, `RollingNumber` (haneler bağımsız), `TabScaffold`.
- **Haze kaynak düzeni:** arka plan `hazeSource(z=0)`, sekme içeriği `hazeSource(z=1)`. İçerikteki cam yalnız arka planı, nav dock ikisini birden bulanıklaştırıyor. Bu, Haze'in `Behind` seçim kuralından geliyor (kaynak koddan doğrulandı).
- **LivingBackground (AGSL):**
  - Tek geçiş: 4 Gaussian ışık odağı + film grain.
  - Sahne geçişinde her odak (konum, yarıçap, renk, opaklık) `gentle` spring ile akıyor.
  - Sekmeli ekranlarda frame döngüsü yok (sabit, pil dostu). `continuous=true` iken odaklar 14–19 sn periyotlarla ±45 dp salınıyor ve 0.92–1.12 ölçekleniyor.
  - Hareket azaltılmışsa döngü durur.
  - RuntimeShader yoksa radial gradient yedeği çiziliyor ve logcat'e uyarı yazılıyor.
  - Sahneler: Cihazlar (11.1 tablosu), Otomasyon (mor-turkuaz), Tam ekran.
- **Nav (10.9):**
  - `FloatingNavBar`: dock cam hap; aktif gösterge `bouncy` spring ile kayıyor, aktif ikon FILL 0→1, renkler spring ile akıyor, sekme değişiminde TICK haptiği.
  - Sağda 68'lik Buz gradient "+" (basma ölçeği, `contentDescription` "Cihaz ekle"; S07'de panele bağlanacak).
  - `AppNav`: sekme geçişi fade + 0.98→1 ölçek + yöne göre 12 dp kayma (`smooth`). Hareket azaltılmışsa 120 ms crossfade.
- **İki boş sekme:** "Cihazlar"; "Otomasyon" + "Zamanlayıcılar telefondan çalışır, Wi-Fi şart".
- **Edge-to-edge:** `SystemBarStyle.dark(TRANSPARENT)` ile şeffaf çubuklar ve beyaz ikonlar; içerikte üst güvenli alan + 12, altta 124 boşluk.
- **WoL Debug › Tasarım:** sahne seçici; cam vitrini (3 varyant); 5 spring token için kayan noktalar ("Oynat"a yarıda basınca yön hızı koruyarak değişir); 8 haptik butonu; RollingNumber (+1/−1/+17); FILL demo; yazı tipi örnekleri; FPS / en uzun kare göstergesi.

### Doğrulanacakların sonucu
| Konu | Sonuç |
|---|---|
| Haze güncel sürümü ve Compose uyumu | **2.0.1** (Maven Central, Eylül 2026). Compose UI 1.12.1'e karşı derlenmiş; BOM 2026.09.00 ile derleme ve Robolectric testleri yeşil. 2.x API'si 1.x'ten farklı (`hazeBlur(HazeInput.Sources, HazeBlurStyle {…})`, `HazeColorEffect`); imzalar kaynak jar'dan okundu. Doygunluk için ayrı parametre yok, `HazeColorEffect.colorFilter(ColorMatrix.setToSaturation(1.6))` kullanıldı. Deneysel native backdrop yolu (Android 37.2) **kapalı**, varsayılan kaynak yolu kullanılıyor. |
| Compose değişken font eksenleri + Material Symbols | `FontVariation.Settings` ile FILL/wght/GRAD/opsz çalışıyor. Font dosyasında 4 eksen doğrulandı (fontTools). FILL animasyonu adım önbelleğiyle uygulandı. |
| Onest / JetBrains Mono kaynak ve lisans | google/fonts (`ofl/onest`, `ofl/jetbrainsmono`), SIL OFL 1.1. Material Symbols Apache 2.0. Lisans metinleri `assets/licenses/`. |
| AGSL cihazda | S24'te shader derleniyor (yedek yola düşmedi, logcat temiz). |

### Animasyon denetimi (12.1–12.4)
| Madde | Durum |
|---|---|
| Spring token'ları, stiffness formülü | ✓ (testli) |
| Durum değişimlerinde sabit süreli tween yok | ✓ (tween yalnız hareket azaltılmışken crossfade için) |
| Kesilebilirlik | ✓ (`animate*AsState` / `AnimatedContent`; Tasarım › Spring demosunda denenebilir) |
| Animasyonları kaldır | ✓ (sonsuz döngü durur, sekme geçişi 120 ms crossfade, FILL ve renkler kısa tween) |
| Sekme değişimi: crossfade + 0.98→1 + 12 kayma + sahne morph + gösterge spring | ✓ |
| İkon FILL 0→1 `snappy` | ✓ |
| Nav sekme renkleri | **Düzeltildi:** ilk sürümde anlıktı, `animateColorAsState(snappy)` yapıldı |
| Basma 0.96 `quick` / `snappy` geri | ✓ (sekmeler, "+", debug butonları) |
| Arka plan sabit (sekmeli) / sürekli (tam ekran) | ✓ |
| Debug aracı | Debug aracındaki cam çiplerin varyant değişimi anlık. Bu bilinçli: yalnız geliştirici aracı, kullanıcı arayüzü değil. |

### Sapmalar / notlar
- **Ölçü yorumu:** Spec 11.1'deki odak konumları CSS yerleşimi olarak yorumlandı (`left/right: x`, `top: y`, merkez = kenar + yarıçap).
- **Otomasyon ve Tam ekran sahnesi** renkleri spec'te sayısal verilmemişti. "Mor-turkuaz" ve "nötr" tarifine göre seçildi; istersen değiştiririm.
- **Haptik `enabled`** şimdilik bellekte duruyor. DataStore (S02) ve Ayarlar (S10) ile bağlanacak.
- **APK boyutu:** debug 32 MB, release 24 MB (R8 kapalı). Küçültme S13'te.
- **Ortam:** Sprint sırasında Android Studio güncellendiği için JBR geçici olarak yoktu. Derlemeler Gradle'ın indirdiği Temurin 17/21 ile yapıldı. Bir kerelik, `-Porg.gradle.java.installations.auto-detect=false` ile; projeye bir şey eklenmedi.

### Otomatik test sonuçları
- `./gradlew :app:testDebugUnitTest` → **86/86** yeşil. Yeni olanlar:
  - `DesignTokensTest`: stiffness tablosu, palet, açık kart karışımı, CSS açıları.
  - `AppNavTest` (Robolectric Compose): açılışta Cihazlar seçili; Otomasyon'a dokununca seçim ve içerik değişiyor, geri dönülüyor; "+" tetikleniyor.
- `assembleDebug` + `assembleRelease` yeşil. Debug APK telefona ADB ile kuruldu; iki activity de açılıyor, logcat'te çökme yok.

### Kontrol raporu — "baktım, tamam; sen de kontrol et"
- [x] Nav sekme seçimi ve içerik değişimi testte doğru → sen de #2–3'te hisset.
- [x] Spring stiffness değerleri spec tablosuyla aynı → #3 ve Tasarım › Spring demosunda bak.
- [x] Shader cihazda derleniyor → #1, #4, #5'te arka planı gör.
- [x] Hareket azaltma kodda tüm döngüleri durduruyor → #9.
- [ ] #1–#10 cihaz testleri sende.

### Açık kalanlar
- Wi-Fi pill + ayarlar balonu S06'da (başlığın üstüne).
- "+" butonu S07'de panele bağlanacak.
- Seçili cihaza göre arka plan tonu (`tint`) S06'da.
