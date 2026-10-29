# S06 — Cihazlar ekranı

**Spec:** 10.1, 10.2, 10.10, 11.3, 11.6 (Windows güç ikonları), 12.2 (kart → hero), 12.3, 12.5, 12.6, 12.7, 16 (Compose UI), 17 faz 6 · **Kararlar:** K9, K12, K15, K17, K24

## Amaç
Asıl uygulamanın Cihazlar sekmesi tamamen çalışır ve animasyonludur: Wi-Fi pill, hero kart, orb, hold butonları, adım kartları, geçmiş çubukları, cihaz kartları, boş durum. Güç butonlarında **birebir Windows 11 ikonları**.

## Doğrulanacak
- Segoe Fluent Icons kod noktaları (Microsoft Learn "Segoe Fluent Icons font" listesi): Kilitle, Uyku, Hazırda Beklet, Yeniden Başlat, Kapat için Windows 11 güç/oturum menüsünde kullanılan glifler.
- `SharedTransitionLayout`'un güncel Compose sürümündeki API'si.

## Görevler
1. **Windows güç ikonları (K17):**
   - `C:\Windows\Fonts\SegoeIcons.ttf`'ten 5 glifin outline'ı çıkarılır (fontTools ile scratchpad'de küçük script; Python yoksa .NET ile) → SVG → `res/drawable/win_lock.xml`, `win_sleep.xml`, `win_hibernate.xml`, `win_restart.xml`, `win_shutdown.xml`.
   - `WinPowerIcon(action)` composable.
   - **Önizleme PNG'si** (5 ikon yan yana, beyaz üstü koyu) üretilip rapora eklenir; **Kaan onaylamadan sprint bitmiş sayılmaz.** Hazırda için Windows'ta ayrı glif yoksa en yakın aday ve 2 alternatif önizlemede gösterilir, Kaan seçer.
   - `assets/licenses/` içine not: kişisel kullanım, dağıtılmaz.
2. **Başlık (10.1):** WifiPill (seviye ikonu crossfade + 0.9→1 ölçek, SSID/Mbps sayısı/IP mono, SSID yoksa "Wi-Fi"), ayarlar balonu (şimdilik "Ayarlar yakında" yerine boş push ekranı; S10'da dolar), sayfa başlığı.
3. **Hero kart (10.2):** ikon balonu + düzenle butonu (S07'ye kadar pasif), ad, `IP · MAC`, çipler (durum, agent: "Agent ✓" / "Agent yok" / "Agent cevap vermiyor" / "Başka telefona eşli" (K6), "MAC bekleniyor"), `animateContentSize`.
4. **PowerOrb (124 halka / 96 orb):** kapalı nötr (dokun = uyandır), uyanıyor amber dönen yay, açık gradient + 12 sn dönen halka (basılı tut = Kapat), hold dolumu kırmızı; bloom + haptic (12.3).
5. **HoldButton (12.6):** 1.5 sn hafif ease-in dolum; bırakınca `snappy` geri boşalma, kaldığı yerden devam; 0.96 ölçek; commit vurgusu + THUD; haptik tickler (12.7); hero üst satırda "Kapatılıyor · bırakırsan iptal" ipucu; TalkBack alternatifi (onay dialog'u, 10.10).
6. **Güç satırı:** Kilitle, Uyku, Hazırda, Yeniden, Kapat (62, köşe 20, Kapat kırmızımsı) — yalnız `On` iken (K12); agent yoksa pasif + ipucu; `hibernateAvailable=false` → Hazırda pasif.
7. **Uyanırken:** 3 adım kartı (StepRow) — dolma + ✓ path trim, aktif halka döner; amber kart zemini ve nefes alan parlama.
8. **Geçmiş (HistoryBars):** son 12 (K15), giriş animasyonu 25 ms kademeli `bouncy`, yeni deneme kayarak girer; "Son 12 deneme · k/n" + "Son uyandırma · 08:31" (mono).
9. **Cihaz kartları (DeviceCard):** 2 sütun grid, durum satırı metinleri (K9 dahil: "Uyuyor"), 4'lük çubuk varyantları, kart güç butonu (dokun = uyandır / basılı tut = Kapat), açık kart görünümü (11.3) + sürekli efektler (12.5: nefes, conic hüzme, ışık süpürmesi); seçim → hero'ya shared-element, 2 beyaz seçim halkası `bouncy` kayar; liste ekleme/çıkarma animasyonları.
10. **Boş durum:** "Henüz cihaz yok" cam kart + "Cihaz ekle" (S07'ye kadar Debug › Veri'ye yönlendiren geçici davranış).
11. **Olaylar:** "Kilitlendi", "Uyanmadı · …", "Kapanmadı" vb. kısa cam toast'ları (animasyonlu giriş/çıkış) + haptikler.
12. **Arka plan tonu (12.4):** seçili cihaz rengine kayma; uyanırken amber, kapatırken kırmızımsı.
13. **ViewModel:** yalnız StatusEngine + repo'ları okur; `selectedDeviceId` DataStore'da.

## Otomatik testler (Claude koşturur)
Robolectric Compose UI (bölüm 16): karta dokununca hero o cihaza geçer; kapalı cihazda orb dokunuşu uyandırmayı başlatır; açık cihazda dokunmak bir şey yapmaz; 1.5 sn basılı tutmak Kapat'ı tetikler, 0.8 sn'de bırakmak tetiklemez; Off cihazda güç satırı yok. Ek: durum metni eşlemeleri, contentDescription'ların varlığı.

## Cihaz testi (Kaan yapar)
Hazırlık: WoL Debug › Veri'de gerçek PC (eşli) + 1–2 örnek cihaz ekli. Asıl uygulamayı ("WoL Denetim Merkezi") aç.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Hero'daki güç satırında ikonlara bak | Windows 11 Güç menüsündekilerle aynı: Kilitle asma kilit, Uyku hilal, Hazırda saat, Yeniden saat yönünün tersine ok, Kapat güç simgesi. |
| 2 | Uygulamayı aç | Üstte Wi-Fi pill (SSID · Mbps · IP), sağında ayarlar balonu, "Cihazlar" başlığı, hero kartta seçili PC, altında 2 sütun kartlar. |
| 3 | Telefonla routerdan uzaklaş/yaklaş | Pill'deki Wi-Fi ikonu seviye değiştirirken yumuşakça geçer; Mbps sayısının haneleri yuvarlanır. |
| 4 | Açık PC: hero ve kart | Gradient orb, halka yavaşça döner; kart ve hero hafif nefes alır, kenarda ışık hüzmesi döner, arada yüzeyden ışık süpürmesi geçer. |
| 5 | Güç satırı | Windows ikonlu 5 buton: Kilitle, Uyku, Hazırda, Yeniden, Kapat; Kapat kırmızımsı. |
| 6 | "Kilitle"ye kısa bas, hemen bırak | Hiçbir şey olmaz; dolum varsa hızlıca geri boşalır, hafif bir tık hissi. |
| 7 | "Kilitle"yi 1.5 sn basılı tut | Dolum alttan yukarı ilerler, titreşimler gittikçe güçlenir; sonunda tok bir "thud" + kısa büyüme; PC kilitlenir, üstte "Kilitlendi" bildirimi. |
| 8 | Orb'u 0.8 sn tut bırak, sonra hemen tekrar bas | Halka geri boşalmaya başlar; tekrar basınca kaldığı yerden devam eder. |
| 9 | Orb'u 1.5 sn tut (Kapat) | Hero'nun üstünde "Kapatılıyor · bırakırsan iptal"; tamamlanınca "Kapanıyor…", kartta kırmızı ilerleme, sonra "Kapalı"; kart ve orb renkleri akarak nötre döner; arka plan tonu kırmızımsıya kayıp geri döner. |
| 10 | Kapalı PC | Güç satırı yok, yalnız orb; durum "Kapalı · k/n", kartta güvenilirlik çubuğu. |
| 11 | Orb'a tek dokun | Tık hissi; amber yay döner, 3 adım kartı belirir (Paket → Ping yanıtı → Port açık), saniye sayacının haneleri yuvarlanır, arka plan amber tonuna kayar. PC açılınca yay halkaya tamamlanır, orb "bloom" yapar + başarı titreşimi; adımların yerine geçmiş çubukları gelir, yeni çubuk kayarak eklenir. |
| 12 | PC'yi "Uyku" ile uyut (basılı tut) | K23: kart "Hazırda bekletiliyor…" → **"Hazırda"**. |
| 13 | Başka bir karta dokun | Hero o cihaza akarak geçer (ikon rengi, ad, adres değişir); beyaz seçim halkası yaylanarak yeni karta kayar. |
| 14 | Açık PC'nin küçük kartındaki güç butonunu basılı tut | Halka dolumu kartta görünür; tamamlanınca Kapat çalışır. |
| 15 | PC'de agent servisini durdur | Agent çipi "Agent cevap vermiyor"a animasyonla döner; güç satırı soluk ve basılı tutunca çalışmaz, altında ipucu. Servisi başlat → geri gelir. |
| 16 | WoL Debug › Veri'den tüm cihazları sil, uygulamaya dön | "Henüz cihaz yok" kartı animasyonla belirir. (Sonra PC'yi tekrar ekle.) |
| 17 | TalkBack'i aç, "Kapat"a çift dokun | Basılı tutmak yerine onay penceresi çıkar. |
| 18 | Ayarlar balonuna dokun, sonra geri hareketi yap (kenardan kaydır, yarıda bırak / sonuna kadar çek) | Ayarlar sağdan kayarak gelir, alttaki ekran sola kayıp kararır; geri hareketi parmağı takip eder, yarıda bırakınca geri oturur, sonuna kadar çekince kapanır. |
| 19 | Ekranda gördüğün herhangi bir değişim | Hiçbir şey "pat" diye değişmiyor; her değişim animasyonlu. Aksi varsa yaz. |

## Bitti tanımı
UI testleri yeşil; 12.3/12.5/12.6/12.7 maddeleri tek tek denetlendi (raporda liste); ikonlar Kaan onaylı; cihaz testi #1–19 geçti.

## Sprint sonu raporu
_2026-10-29 · Claude_

### Doğrulananlar
- **Segoe Fluent Icons kod noktaları** (Microsoft Learn "Segoe Fluent Icons font" listesi):
  - Lock E72E, QuietHours E708, PowerButton E7E8, UpdateRestore E777 (alternatif Refresh E72C, RestartUpdate2 ED21, Restart3 F305), Recent E823.
  - Listede "Hibernate" adlı glif yok.
  - **Kaan'ın Windows 11 güç menüsü ekran görüntüsüyle** eşleştirildi: Kilitle E72E, Uyku E708, **Hazırda E823 (saat, Kaan seçti)**, Yeniden E777, Kapat E7E8. Önizleme ve set Kaan onaylı (2026-10-29).
- **SharedTransitionLayout:** Compose BOM 2026.09'da kararlı. Kullanılmadı, gerekçesi Sapmalar'da.

### Yapılanlar
- **Windows güç ikonları (K17), lisans güvenli kurgu:**
  - Repo herkese açık olduğu için (GitHub API: `private: false`) glifler git'e **girmiyor**. Kaan'ın seçimi.
  - `generateWinIcons` Gradle görevi her derlemede `C:\Windows\Fonts\SegoeIcons.ttf`'i Java AWT ile okuyor, glif outline'larını `build/generated/winicons/res/drawable/win_*.xml` olarak üretiyor (AGP `addGeneratedSourceDirectory`). fontTools çıktısıyla birebir aynı path doğrulandı.
  - İkon başına öncelik: `app/winicons/custom/<ad>.svg` (kullanıcının kendi SVG'si, git'e girmez, basit SVG → VectorDrawable dönüştürücü) → Segoe → `app/winicons/fallback/` (Fluent UI System Icons, MIT).
  - Üç yol da denendi: sahte custom SVG, var olmayan font yolu (`-Pwol.segoeIcons`) ve normal derleme.
  - README'ye "Windows güç ikonları" bölümü eklendi (Kaan'ın isteği: repoyu indiren herkes kendi SVG'sini koyabilsin). Lisans notları `assets/licenses/` altında.
  - Git geçmişinde hiç glif yok.
- **`WinPowerIcon(action)`** composable.
- **Başlık (10.1):**
  - **WifiPill:** seviye ikonu crossfade + 0.9→1; SSID ve IP mono, Mbps sayısı haneli yuvarlanıyor; SSID yoksa "Wi-Fi", bağlı değilse "Wi-Fi yok".
  - Ayarlar balonu → iOS tarzı push ekranı: sağdan giriş, alttaki %30 paralaks + kararma, **predictive back**. İçerik S10'da.
  - Sayfa başlığı.
- **HeroCard:**
  - ikon balonu (renk akar), düzenle butonu (S07'ye kadar bilgi toast'ı), ad, IP · MAC;
  - çipler: durum (ping ms ve uyanma saniyesi haneli yuvarlanır), agent ("Agent" / "Agent yok" / "Agent cevap vermiyor" / "Başka telefona eşli"), "MAC bekleniyor"; hepsi `animateContentSize`;
  - Strong cam + cihaz rengi vurgu; açıkken nefes alan gölge, dönen hüzme, ışık süpürmesi; uyanırken amber zemin + nefes alan çerçeve.
- **PowerOrb:**
  - kapalı nötr cam (dokun = uyandır);
  - uyanıyor amber yay (1 sn tur); açık gradient orb + 12 sn dönen gradient halka (basılı tut = Kapat, halka boyunca kırmızı dolum);
  - açılışta yay tamamlanıyor + **bloom** (1 → 1.08 → 1 bouncy + parlama patlaması + başarı haptiği).
- **HoldToConfirm (12.6):** 1.5 sn hafif ease-in; bırakınca `snappy` geri boşalma ve kaldığı yerden devam; 0.96 basma; commit 1 → 1.05 → 1 + THUD; %15 dilimlerde LOW_TICK (0.2 → 0.8); iptalde tık; TalkBack'te çift dokunuş → onay penceresi.
- **Güç satırı:** 5 buton (62, köşe 20, alttan dolum; Kapat ve Yeniden kırmızı dolar, Kapat kırmızımsı). Yalnız On'da (K12). Agent yok / cevap vermiyor / başka telefona eşli / hazırda bekletme kapalı → pasif + ipucu.
- **WakeSteps:** 3 adım kartı. Tamamlanan gradient ile soldan dolar ve ✓ path trim ile çizilir; aktif amber halka döner.
- **HistoryBars:** son 12 (K15); 25 ms kademeli `bouncy` büyüme; yeni deneme `animateItem` ile kayarak giriyor; "Son 12 deneme · k/n" ve "Son uyandırma · 08:31" (sayılar mono).
- **DeviceCard:**
  - 150 / köşe 32;
  - kapalı cam ↔ açık 145° karışım gradient + c1 %60 çerçeve + nefes alan c2 gölge, hüzme, süpürme; geçiş akıyor;
  - durum satırı (K9: "Uyuyor" / "Hazırda");
  - 4'lük çubuk: güvenilirlik / tam gradient / amber ve kırmızı ilerleme;
  - kart güç butonu: dokun = uyandır, basılı tut = Kapat, halka dolumu kartta.
- **Grid:** tüm ekran tek `LazyVerticalGrid`; kart ekleme, çıkarma ve yer değiştirme `animateItem` (spring).
- **Seçim halkası:** 2 beyaz halka, seçim değişince `bouncy` ile yeni karta kayıyor; kaydırmada karta yapışık.
- **Boş durum:** "Henüz cihaz yok" cam kartı + "Cihaz ekle" (S07'ye kadar Debug › Veri'ye yönlendiren toast). Alt bardaki "+" de aynısını yapıyor.
- **Olaylar:** cam toast'lar (yukarıdan kayıp gelir, 2.5 sn sonra çözülür; başarı yeşil, uyarı kırmızımsı) + hata haptiği.
- **Arka plan tonu (12.4):** seçili cihaz rengi; uyanırken amber, geçişte kırmızımsı (`gentle` spring ile akar).
- **DevicesViewModel** (Hilt): repo + StatusEngine + AgentLinker + NetworkMonitor okuyor; seçili cihaz DataStore'da; aksiyonlar koordinatörlere gidiyor.
- **AppNav:** Cihazlar içeriği ve arka plan tonu dışarıdan veriliyor (testler iskeletle koşuyor).

### Animasyon denetimi (12.3 / 12.5 / 12.6 / 12.7, tek tek)
- **12.3 Basma 0.96:** hold butonları, orb, kart, kart güç butonu, balonlar ✓
- **12.3 Metin:** durum metinleri AnimatedContent ✓ · ping ms, uyanma sayacı, Mbps haneli ✓
- **12.3 Renkler:** orb, ikon balonu, kart açık↔kapalı (gradient + gölge + çerçeve birlikte), çip noktaları, çubuk ✓
- **12.3 Orb:** amber yay → tam halka → bloom ✓
- **12.3 Adım kartları:** soldan dolum + ✓ path trim + dönen aktif halka ✓
- **12.3 Geçmiş çubukları:** 25 ms kademeli bouncy + yeni deneme kayarak ✓
- **12.3 Listeler:** `animateItem` ile fade ve spring yerleşim ✓; 30 ms kademe ve 8 dp kayma yok (Sapmalar)
- **12.3 Çipler:** `animateContentSize(snappy)` ✓ · Wi-Fi ikonu crossfade + 0.9 → 1 ✓ · agent ikonu FILL 0 → 1 ✓
- **12.5:** nefes 3 sn ±%15 ✓ · conic hüzme 8 sn ✓ · ışık süpürmesi 7 sn ✓ · güç halkası 12 sn ✓ · amber yay 1 sn ✓ · amber parlama 1.6 sn ✓. Hepsi "Animasyonları kaldır"da duruyor.
- **12.6:** 1.5 sn ease-in, snappy geri boşalma ve devam, dolum (butonda alttan, orb'da halka), renk, commit vurgusu, ipucu satırı, kart butonu ✓
- **12.7:** LOW_TICK dilimleri, THUD, iptal, uyandırma CLICK, açılınca SPIN (bloom ile senkron), seçimde TICK, hata ✓
- **12.2:** push ekran + predictive back ✓; kart → hero yerinde akış (Sapmalar)

### Sapmalar / notlar
- **Kart → hero `SharedTransitionLayout` yerine yerinde akış:** seçilen kart gridde görünür kalıyor. Paylaşılan öğe aynı anda iki yerde olamayacağı için hero'nun ikon rengi, adı ve adresi spring ile akarak değişiyor, seçim halkası bouncy ile kayıyor. Spec'teki görsel sonuç (renk, ikon, ad akar + halka kayar) karşılanıyor.
- **Liste girişinde 30 ms kademe ve 8 dp kayma yok:** `animateItem` gecikme desteklemiyor; fade ve spring yerleşim var. Gerekirse S13'te.
- **Windows ikonları repoda değil** (yukarıda); bu, K17'deki "SVG'ler res/drawable'a konur" maddesinin yerine geçti. SPEC K17 güncellendi.
- **Düzenle ve Cihaz ekle** S07'ye kadar bilgi toast'ı; **Ayarlar** içeriği S10.

### Otomatik test sonuçları
- `./gradlew :app:testDebugUnitTest` → **214/214** yeşil (S06 ile +12).
  - `DevicesScreenTest` (Robolectric Compose):
    - karta dokununca hero o cihaza geçiyor;
    - kapalı cihazda orb'a dokunmak uyandırıyor ve güç satırı yok;
    - açık cihazda orb'a dokunmak bir şey yapmıyor;
    - **orb 1.5 sn → Kapat, 0.8 sn → tetiklemiyor**;
    - güç satırı butonunu basılı tutunca komut gidiyor;
    - TalkBack çift dokunuşu onay penceresi açıyor;
    - agent cevap vermiyorsa güç satırı pasif;
    - uyanırken adım kartları;
    - boş durum ve erişilebilirlik etiketleri (Ayarlar, Wi-Fi sinyali, Uyandır).
  - `DeviceLabelsTest`: kart metinleri (K9 dahil), çip, agent çipi, güç kullanılabilirliği, arka plan tonu.
- `assembleDebug` + `assembleRelease` yeşil; APK'da 5 `win_*` drawable üretilmiş. Telefona kuruldu, ana uygulama açılıyor, crash log temiz.

### Kontrol raporu — "baktım, tamam; sen de kontrol et"
- [x] İkon seti Kaan'ın Windows 11 menüsüyle eşleşti ve onaylandı; glifler repoda değil, derlemede üretiliyor → #1, #5.
- [x] Basılı tut 1.5 / 0.8 sn, dokun = uyandır, Off'ta güç satırı yok, TalkBack onayı testte → #6–#11, #17.
- [x] Animasyon maddeleri yukarıdaki listede tek tek işaretlendi → #3, #4, #9, #11, #13, #19.
- [ ] Gerçek cihazda görünüm, akıcılık ve haptikler sende → #1–#19.

### Cihaz testi 1. tur düzeltmeleri (Kaan, 2026-10-29)
- **Kapalı PC'de güç satırı görünmüyordu:** artık görünür ama pasif (yeni karar **K24**). Büyük kartın modu "uyanıyor / normal" olarak ikiye indi; PC açılıp kapanırken kartın boyutu değişmiyor.
- **Pill'de IP düzgün görünmüyordu** (dar alanda alt satıra sarıyordu): SSID gerekirse "…" ile kısalıyor; Mbps ve IP tek satır ve hep tam. Pill, ayarlar balonuna kadar olan tüm genişliği kullanıyor.
- **Çökme ve Wi-Fi aç/kapa sonrası "açık PC kapalı":**
  - Wi-Fi kapanırken soketi eski ağa bağlama hatası "Wi-Fi yok" türünde değildi. StatusEngine onu yakalamıyordu; hata uygulama genelindeki arka plan iş akışına sızıyordu. Bu hem uygulamayı düşürebiliyor hem de tur döngüsünü öldürüp ekranda son durumu (Kapalı) bırakabiliyordu.
  - Düzeltme: bağlama hatası `NoWifiException` sayılıyor; turda beklenmeyen hata "tur yok" sayılıyor ve döngü ölmüyor; koordinatörler G/Ç hatalarını yakalıyor; uygulama scope'una `CoroutineExceptionHandler` eklendi.
  - Regresyon testi: `beklenmeyen hata tur sayilmaz, dongu olmez`.
  - Not: telefon ADB'den düştüğü için çökme log'u okunamadı; kök neden koddan çıkarıldı.
- **FPS düşüşü / takılmalar:**
  - Nefes gölgesi her karede farklı yarıçapla yeniden bulanıklaştırılıyordu. Artık sabit yarıçaplı tembel `dropShadow` kullanılıyor ve yalnız saydamlık nefes alıyor.
  - Nefes, amber parlama, basılı-tut dolumu, vurgu ve orb basma değerleri kompozisyonda okunuyordu; artık yalnız çizim/katman aşamasında okunuyor, her karede yeniden kompozisyon yok.
  - Büyük kartta iç içe iki boyut animasyonu vardı (`animateContentSize` + `SizeTransform`); teke indi.
  - "Basılı tutuluyor" ipucu artık dolum değerini değil, yalnız basma durumunu okuyor.
- **Seçim halkası yarıda kesilip yeni hedefe gidiyordu:** hero boyut değiştirince kartlar kaydığı için hedef her karede değişiyor ve animasyon yeniden başlıyordu. Artık halka iki kartın canlı konumları arasında 0→1 ilerlemeyle çiziliyor; kartlar kaysa da kesintisiz gidiyor.
- **Kart seçimi** DataStore yazımını beklemeden anında görünüyor.
- Testler **216/216** (+2: kapalı cihazda pasif güç satırı, durum döngüsünün hatadan sağ çıkması).

### Cihaz testi 2. tur düzeltmeleri (Kaan, 2026-10-29)
- **Asıl çökme sebebi bulundu: bellek.** Log'da `lmkd: Reclaim 'com.kdgames.wake_on_lan' … 772688kB rss, 712068kB anon rss` ve 500–640 ms'lik `NativeAlloc` GC duraklamaları vardı. Uygulama Java hatası vermeden sistem tarafından öldürülüyordu; FPS düşüşünün sebebi de bu duraklamalardı.
  - Suçlu: animasyonlu nefes gölgesi (`dropShadow`). Yarıçapı ya da saydamlığı değişince her karede her kart için yeni bir bulanık gölge bitmap'i üretiliyordu.
  - Yerine bitmap kullanmayan, dışa doğru sönen ince halkalar (`GlowLayer`) geldi.
- **Kare başı nesne üretimi kaldırıldı:**
  - Dönen hüzme ve ışık süpürmesinde shader'lar ve şekil yolu boyut başına bir kez yaratılıyor, her karede yalnız matris güncelleniyor.
  - Orb halkasının gradyanları önbellekte.
- **Katman izolasyonu:** hüzme, nefes, amber parlama, orb halkası, kart güç butonu ve adım spinner'ı kendi grafik katmanlarında. Artık her karede yalnız o ince katman yeniden kaydediliyor; kartın tamamı ve cam yüzeyin gradyanları değil.
- **Gerçek zamanlı arka plan bulanıklığı** artık yalnız nav dock ve toast'ta (`GlassSurface.backdropBlur`). Kartlar, hero, pill ve balonlarda kapalı: canlı arka plan zaten yumuşak bir gradyan olduğu için fark gözle görülmüyor, her yüzey için ayrı GPU blur geçişi kalktı. Spec 11.2'den bilinçli sapma.
- **Ölçüm** (telefonda, ekran açık, 1 dk): PSS ~65 MB, RSS ~155 MB, sabit; önceki sürüm ~770 MB'a çıkıyordu.
- **Büyük kart boyutu akmıyordu** ("MAC bekleniyor" çipi bir anda kapanıp kart bir anda kısalıyordu): boyutun tek kaynağı yeniden Column'un `animateContentSize`'ı, içteki AnimatedContent boyutu anında alıyor. Çip artık daralarak ve solarak kapanıyor.
- **Art arda dokunuşta seçim halkası sekiyordu:** yeni hedefe artık o an çizildiği yerden gidiyor; eski karta sıçramıyor.
- Testler **216/216**.

### Açık kalanlar
- Cihaz ekle / düzenle paneli, tarama: S07.
- Ayarlar içeriği ve titreşim anahtarı: S10.
- Liste giriş kademesi: S13 cila listesine.
