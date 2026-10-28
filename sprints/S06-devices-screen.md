# S06 — Cihazlar ekranı

**Spec:** 10.1, 10.2, 10.10, 11.3, 11.6 (Windows güç ikonları), 12.2 (kart → hero), 12.3, 12.5, 12.6, 12.7, 16 (Compose UI), 17 faz 6 · **Kararlar:** K9, K12, K15, K17

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
Hazırlık: Debug › Veri'den gerçek PC + 1–2 sahte cihaz ekli.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Önizleme PNG'sindeki 5 ikona bak (rapor) | Windows 11 güç/oturum menüsündekilerle **birebir aynı**. Değilse hangisi farklı, yaz. |
| 2 | Uygulamayı aç | Üstte Wi-Fi pill (SSID · Mbps · IP), yanında ayarlar balonu; hero kart seçili PC; altında 2 sütun kartlar. |
| 3 | Telefonla routerdan uzaklaş/yaklaş | Pill'deki Wi-Fi ikonu seviye değiştirirken yumuşakça geçiş yapar. |
| 4 | Açık PC: hero | Gradient orb, halka yavaşça döner; kart hafif nefes alır, kenarında ışık hüzmesi döner, arada ışık süpürmesi geçer. |
| 5 | Güç satırında Windows ikonlu 5 buton | Kilitle, Uyku, Hazırda, Yeniden, Kapat; Kapat kırmızımsı. |
| 6 | "Kilitle"yi kısa bas, hemen bırak | Hiçbir şey olmaz; dolum varsa hızlıca geri boşalır, hafif tık hissi. |
| 7 | "Kilitle"yi 1.5 sn basılı tut | Dolum alttan yukarı ilerler, titreşim gittikçe artar; sonunda "thud" + kısa büyüme; PC kilitlenir, "Kilitlendi" bildirimi. |
| 8 | Orb'u 0.8 sn tut bırak, sonra tekrar bas | Halka geri boşalmaya başlar, tekrar basınca kaldığı yerden devam eder. |
| 9 | Orb'u 1.5 sn tut (Kapat) | Üst satırda "Kapatılıyor · bırakırsan iptal"; tamamlanınca "Kapanıyor…" → kart kırmızı ilerleme → "Kapalı"; kart ve orb renkleri akarak nötre döner, arka plan tonu kırmızımsıya kayıp geri döner. |
| 10 | Kapalı PC: güç satırı | Yok; yalnız orb. Durum "Kapalı", güvenilirlik çubuğu. |
| 11 | Orb'a tek dokun | Tık hissi; amber yay döner, 3 adım kartı belirir (Paket ✓ → Ping yanıtı → Port açık); saniye sayacı haneleri yuvarlanır; arka plan amber tonu. PC açılınca yay halkaya tamamlanır, orb "bloom" yapar + başarı titreşimi; adımlar yerine geçmiş çubukları döner, yeni çubuk kayarak eklenir. |
| 12 | Uyutulmuş PC kartı | "Uyuyor" yazar. |
| 13 | Başka karta dokun | Hero o cihaza morph olur (ikon, ad, renk akar); beyaz seçim halkası yaylanarak yeni karta kayar. |
| 14 | Küçük kartın güç butonu: açık PC'de basılı tut | Halka dolumu kartta görünür; tamamlanınca Kapat çalışır. |
| 15 | Agent servisini durdur | Agent çipi "Agent cevap vermiyor"a animasyonla değişir; güç satırı pasif + ipucu. |
| 16 | Tüm cihazları sil (Debug › Veri) | "Henüz cihaz yok" kartı animasyonla belirir. |
| 17 | TalkBack aç, "Kapat"a çift dokun | Onay dialog'u çıkar (basılı tut yerine). |
| 18 | Ekranda gördüğün herhangi bir değişim | Hiçbir şey "pat" diye değişmiyor; her değişim animasyonlu. Aksi varsa yaz. |

## Bitti tanımı
UI testleri yeşil; 12.3/12.5/12.6/12.7 maddeleri tek tek denetlendi (raporda liste); ikonlar Kaan onaylı; cihaz testi #1–18 geçti.

## Sprint sonu raporu
_(Claude doldurur)_
