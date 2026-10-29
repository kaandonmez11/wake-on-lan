# S07 — Cihaz ekle/düzenle paneli + ağ taraması

**Spec:** 7.4, 10.5, 12.2 (sheet), 12.3 (listeler), 12.5 (tarama göstergesi), 15, 16 (sheet UI testi), 17 faz 7 · **Kararlar:** K6, K11, K14, K16

## Amaç
Cihazlar artık debug aracından değil, asıl uygulamadan eklenir. Tarama ağdaki cihazları (agent'lı PC'yi MAC'iyle) bulur; eklenince agent otomatik eşleşir.

## Doğrulanacak
- `NsdManager` ile `_workstation._tcp` / `_smb._tcp` çözümlemenin Android 17 yerel ağ izniyle çalışması; ters DNS.

## Görevler
1. **NetworkScanner:** paralel pipeline (agent discovery, /24 sweep ≤64 eşzamanlı 300 ms — portlar K11 dahil, canlılara NetBIOS, mDNS + ters DNS); sonuçlar MAC → yoksa IP ile birleşir; akış olarak artan sonuç; iptal edilebilir; MulticastLock. `MdnsResolver`.
2. **İkon tahmini:** agent/3389 → `pc`; 5000+445 → `nas`; 8001/8002 → `tv`; diğer → `pc`.
3. **DeviceSheet + ViewModel:** modal sheet (~%84, köşe 36, %62 scrim + 6 blur), tutamaç, başlık, kapat; aşağı sürükleyerek kapatma (rubber-band, hıza göre kapan/otur).
4. **Gövde:** canlı önizleme kartı; "Ağda bulunanlar" (yalnız ekleme) + dönen gösterge + her satırda "pop" + "Yeniden tara"; satır (ad · IP · MAC/"MAC bulunamadı" · "Agent"/"Agent yok"; "Ekli" soluk, seçilemez); form (Ad; IP + MAC yan yana mono); ikon grid 5×2 (`IconPicker`, FILL animasyonu); renk grid 4×2 (`ColorPicker`, ✓ + beyaz halka `bouncy`).
5. **Alt bar:** ipucu metinleri (10.5, K6 dahil), düzenlemede "Sil" + onay ("Cihaz silinsin mi?" — cam onay, animasyonlu), "Cihazı ekle"/"Kaydet" (56, seçili renk gradient'i), geçersizken pasif.
6. **Doğrulama:** bölüm 15 (ad 1–32, aynı ad yok, IPv4, alt ağ dışı uyarı, MAC regex + normalizasyon, aynı MAC yok).
7. **Kaydet akışı:** sheet kapanır → cihaz seçilir → Cihazlar sekmesine geçilir; agent bulunduysa arka planda otomatik eşleşme (S05'teki akış); hero'daki agent çipi animasyonla belirir. Panel kapanınca tarama iptal.
8. **Bağlantılar:** nav "+" butonu, boş durumdaki "Cihaz ekle", hero'daki düzenle (kalem) butonu → sheet.
9. Debug › Veri'deki "Örnek cihaz ekle" kalır ama asıl yol artık sheet.

## Otomatik testler (Claude koşturur)
- Robolectric Compose: geçersiz IP ile Kaydet pasif; geçerliyle cihaz listede belirir ve seçilir; aynı MAC hatası; düzenleme modunda "Ağda bulunanlar" yok; Sil onayı.
- NetworkScanner: sahte probe'larla birleştirme (MAC/IP anahtarı), "Ekli" işaretleme, iptal, ikon tahmini.

## Cihaz testi (Kaan yapar)
Hazırlık: Önceki cihazları Debug › Veri'den sil. PC açık, agent kurulu, **`wolagent reset`** yapılmış (eşleşmemiş).

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Alttaki "+" | Panel alttan yaylanarak çıkar, arkası kararıp bulanıklaşır. |
| 2 | "Ağda bulunanlar" | Dönen gösterge; satırlar teker teker "pop" ile gelir. PC'n adı + IP + **MAC** + yeşil "Agent" rozetiyle listede. Router, telefonlar vb. de görünür. |
| 3 | PC satırına dokun | Ad, IP, MAC, ikon (`pc`) forma dolar; önizleme kartı güncellenir; ipucu "Agent bulundu · eklenince otomatik eşleşir". |
| 4 | Renk ve ikon seç | Seçim halkası yaylanarak geçer, önizleme rengi akarak değişir. |
| 5 | "Cihazı ekle" | Panel kapanır, Cihazlar sekmesinde PC seçili; birkaç sn içinde agent çipi animasyonla "Agent ✓" olur. `wolagent status` eşleşmiş gösterir. |
| 6 | Tekrar "+" | PC satırı "Ekli" rozetiyle soluk, dokunulamaz. |
| 7 | IP alanına "192.168.1.300" yaz | Kaydet pasif, ipucu "Ad ve geçerli IP gerekli". |
| 8 | MAC'i boş bırak (geçerli ad + IP) | İpucu "MAC olmadan uyandırılamaz · PC açıkken otomatik bulunur"; eklenebilir. |
| 9 | Paneli aşağı sürükle, yarıda bırak | Geri yerine oturur. Hızlıca aşağı fırlat → kapanır. |
| 10 | Hero'daki kalem → düzenle, adı değiştir, Kaydet | Ad hero ve kartta animasyonla güncellenir. |
| 11 | Düzenle → "Sil" | "Cihaz silinsin mi?" onayı; onaylayınca kart yüksekliği yaylanarak kapanır. |
| 12 | Agent'ı başka bir telefona eşli PC (`reset` yapmadan, uygulamayı kaldırıp kurarak dene) ekle | Agent çipi "Başka telefona eşli" + ipucu `wolagent reset`. |

## Bitti tanımı
Testler yeşil; animasyon denetimi raporda; cihaz testi #1–12 geçti.

## Sprint sonu raporu
_2026-10-29 · Claude_

### Doğrulananlar
- **NsdManager + Android 17 yerel ağ izni:** `discoverServices` + `registerServiceInfoCallback` (API 34+, minSdk 35) kullanılıyor; yerel ağ izni S03'te verildi ve mDNS/NSD'yi kapsıyor. Gerçek cihazda sonuç Kaan'ın 2. testinde görülecek (taramaya isim katkısı); mDNS gelmese de NetBIOS, agent ve ters DNS isim veriyor.
- **Ters DNS:** `InetAddress.canonicalHostName`, 1 sn sınırlı. Yalnız canlı host'lara soruluyor.

### Yapılanlar
- **NetworkScanner (`net/scan/`):**
  - Hepsi paralel: agent DISCOVER, /24 sweep (≤ 64 host aynı anda, 9 port, 300 ms; K11 dahil), canlılara NetBIOS ve ters DNS, mDNS (`_workstation._tcp`, `_smb._tcp`; ad içindeki `[mac]` de okunur).
  - Ölü adreslerin zaman aşımları sıraya girmesin diye sweep IO havuzunun 160'lık görünümünde. Tahmini süre: ölü adresler ~3–4 sn, mDNS penceresi 6 sn.
  - `ScanMerger`: IP'ye göre toplanır, **MAC → yoksa IP** ile tekilleşir. Agent'ın diğer kartları (Wi-Fi IP/MAC) ayrı satır olmaz; agent MAC'i K16 ile seçilir. Telefonun kendi IP'si listelenmez.
  - Ad önceliği: agent hostname → NetBIOS → mDNS → ters DNS → IP.
  - Artan anlık görüntüler akışı; tarama boyunca MulticastLock tutulur; akış iptal edilince her şey durur ve kilit bırakılır.
  - Satırlar geliş sırasında kalır ve sabit anahtarlıdır: yeni satır hep alta eklenir, sonradan MAC gelince satır yeniden "pop" etmez.
  - İkon tahmini: agent/3389 → pc; 5000+445 → nas; 8001/8002 → tv; diğer → pc.
- **DeviceSheet (`ui/sheet/`):**
  - `SheetHost`: ekranın %84'ü, köşe 36, %62 scrim + arkadaki ekrana 6'lık blur (scrim ve blur aynı değerden, birlikte açılır).
  - Alttan `smooth` spring ile çıkar. Tutamaçtan ya da gövde en üstteyken aşağı sürüklenir; yukarı çekişte iOS rubber-band direnci var.
  - Bırakınca: hız > 1800 px/sn ya da %33'ten fazla çekilmişse kapanır; yoksa `snappy` ile geri oturur. Predictive back paneli parmakla aşağı iter. Scrim'e dokununca kapanır.
  - Klavye açılınca alt bar klavyenin üstüne çıkar.
- **Gövde:**
  - Canlı önizleme kartı: açık kart gradient'i, renk `gentle` ile akar; ikon bouncy pop; ad boşsa "Yeni cihaz"; IP · agent bilgisi.
  - "Ağda bulunanlar" (yalnız ekleme): dönen gösterge, satır başına 0.9 → 1 `bouncy` "pop", "Yeniden tara", liste yüksekliği tek `animateContentSize` ile akar.
  - Satırlar: ad · IP · MAC / "MAC bulunamadı" · "Agent" / "Agent yok". "Ekli" satırlar soluk ve dokunulamaz.
  - Form: Ad; IP + MAC yan yana (mono, 50 yükseklik, köşe 18; odak ve hata çerçevesi akar). Ad 32 karakterle sınırlı, IP yalnız rakam/nokta, MAC büyük harf hex.
  - `IconPicker` 5×2 (56): seçim halkası hücreden hücreye `bouncy` kayar, ikon FILL 0 → 1.
  - `ColorPicker` 4×2 (48 hap): beyaz iç halka `bouncy` kayar, ✓ bouncy ölçekle gelir.
- **Alt bar:**
  - İpucu satırı (renk ve metin animasyonlu). Öncelik: "Ad ve geçerli IP gerekli" → aynı ad / aynı MAC / MAC biçimi → K6 "Başka telefona eşli · PC'de `wolagent reset` çalıştır" → "Agent bulundu · eklenince otomatik eşleşir" → "MAC olmadan uyandırılamaz · PC açıkken otomatik bulunur" → alt ağ dışı uyarısı.
  - "Cihazı ekle" / "Kaydet" (56, seçili renk gradient'i, akar). Geçersizken %38 soluk ve pasif.
  - Düzenlemede "Sil" → cam onay "Cihaz silinsin mi?" (scrim solar, kart `bouncy` büyür; Vazgeç / Sil).
- **Doğrulama:** bölüm 15 (S02'deki `DeviceRepository.validate`), her form değişiminde `mapLatest` ile.
- **Kaydet akışı:**
  - Panel kapanır → cihaz seçilir → Cihazlar sekmesine geçilir (Ayarlar açıksa kapanır).
  - `AgentLinker.linkNow()` hemen DISCOVER + TOFU eşleşme yapar. Hero'daki agent çipi mevcut animasyonuyla "Agent"a döner.
  - Panel kapanınca tarama iptal.
  - Düzenlemede ad değişimi kartta da animasyonlu (kart adı `AnimatedText` oldu).
- **Bağlantılar:** nav "+", boş durumdaki "Cihaz ekle", hero kalemi (artık aktif görünümlü) → panel. Debug › Veri'deki ekleme yolu duruyor.
- **Seçim düzeltmesi:** Cihazlar ekranındaki yerel seçim, kalıcı seçim değişince düşüyor. Panelden eklenen cihaz, son dokunulan kartın önüne geçebiliyor.

### Testler
- Android birim + Robolectric: **238/238 yeşil** (S06'da 220).
  - Yeni `NetworkScannerTest` (12): MAC/IP birleştirme, aynı MAC iki IP, agent Wi-Fi kartının emilmesi, "Ekli" işaretleme, mDNS ad/MAC, artan akış, iptal + kilit bırakma, IO hatalı host, ikon tahmini, adres aralığı, sabit satır anahtarı.
  - Yeni `DeviceSheetTest` (6, gerçek in-memory Room): geçersiz IP ile Kaydet pasif; geçerliyle cihaz listede belirir ve seçilir + eşleşme tetiklenir; taranan satır formu doldurur + agent ipucu + "Ekli" dokunulamaz; aynı MAC hatası; düzenlemede "Ağda bulunanlar" yok + kaydet; Sil onayı (Vazgeç / Sil).
- Agent (.NET) değişmedi.
- `assembleDebug` + `assembleRelease` temiz.

### Animasyon denetimi
- Sheet giriş/çıkış, sürükleme, scrim ve blur: değerler yalnız layer/draw aşamasında okunuyor; sürükleme recomposition üretmiyor.
- Seçim halkaları layout aşamasında (`offset {}`) kayıyor; renkler draw'da okunuyor.
- Gösterge `loopProgress` ile, gizli/örtülü ekranda duruyor.
- Liste boyunda tek boyut animatörü; satırlar zıplamasın diye geliş sırası korunuyor.
- Ani değişen nokta yok. Bilinen küçük istisna: agent cevabı Wi-Fi kartının satırı listelendikten sonra gelirse o satır anında kalkar (liste yüksekliği akarak kapanır). Nadir bir durum.
- Panel açıkken arkadaki ekranın sonsuz efektleri (orb halkası, nefes) blur altında çalışmaya devam ediyor; blur GPU'da. Kaan'ın FPS gözlemine göre gerekirse durdurulur.

### Sapmalar
- Satırlar "agent önce, IP sırası" yerine **geliş sırasıyla** listeleniyor. Ortaya satır eklenince alttakiler zıplıyordu (animasyon kuralı).
- Robolectric testlerinde Room sorgu/işlem yürütücüsü doğrudan yürütücü. Room'un işlem iş parçacığından ana looper'a dönüşü compose test saatinde beklemiyordu.

### Cihaz testi
_(Kaan)_
