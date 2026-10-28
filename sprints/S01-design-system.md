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
_(Claude doldurur)_
