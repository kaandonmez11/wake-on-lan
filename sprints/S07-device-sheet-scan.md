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
_(Claude doldurur)_
