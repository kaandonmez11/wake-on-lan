# S10 — Ayarlar

**Spec:** 7.5 (ipucu), 9.4 (pil, exact alarm), 10.7, 12.2 (push + predictive back), 12.3 (sayı alanı), 14, 15 (ayar sayıları), 16 (Ayarlar UI testi), 17 faz 9 · **Kararlar:** K20

## Amaç
Ayarlar ekranı tamam: tüm izinler tek yerden görülür ve düzeltilir, retry ayarları stepper + klavyeyle değişir, cihaz başına kapatma şekli, tile slotları/modu, titreşim.

## Doğrulanacak
- Her iznin ilgili sistem ekranı intent'leri (yerel ağ, konum, exact alarm, bildirim, pil optimizasyonu) Android 17 + One UI 9'da.

## Görevler
1. **SettingsScreen + ViewModel:** başlıktaki ayarlar balonundan push geçişi + `PredictiveBackHandler`, geri butonu.
2. **İzinler grubu:** Yerel ağ erişimi, Konum, Tam saatli alarm, Bildirimler, Pil (Kısıtlanmamış). "✓ Verildi" (yeşil) / amber "İzin ver"/"Düzelt" → sistem ekranı; `ON_RESUME`'da canlı yenileme (animasyonlu durum değişimi). Exact alarm verilmemişse satır kırmızı. S08'deki geçici uyarı satırı buraya yönlendirir.
3. **Zamanlayıcı tekrar denemesi:** `NumberStepperField` ×2 (−/+ stepper, ortada sayısal klavyeli mono alan, odakta Buz halkası, "dk"); haneli yuvarlanma; aralık dışı/boş → kırmızımsı yatay titreme (3 salınım `bouncy`) + son geçerli değere dönüş; `giveUp ≥ interval`.
4. **Cihazlar · Kapatma şekli:** cihaz başına renk noktası, ad, agent durumu, "Zorla / Nazik" segmenti (kayan gösterge).
5. **Hızlı ayarlar:** 3 slot satırı (cihaz seçici + "Boş"), "Tile davranışı" A/B/C segmenti (tile'lar S12'de; değer şimdiden saklanır).
6. **Diğer:** Titreşim anahtarı (Haptics bayrağına bağlanır); ipucu "Router'da bu PC'lere sabit IP (DHCP rezervasyonu) vermen önerilir."
7. **Hakkında:** sürüm + lisanslar (assets/licenses listesi).

## Otomatik testler (Claude koşturur)
- Robolectric Compose: sayı alanına aralık dışı değer → eski değere döner; `giveUp < interval` engellenir; stepper sınırlarda durur; segment seçimi kaydedilir.
- İzin satırı durum eşlemesi (sahte izin kaynağıyla).

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Başlıktaki ayarlar balonu | Ayarlar sağdan kayarak gelir, alttaki ekran hafif kayıp kararır. |
| 2 | Kenardan geri kaydır | Ekran parmağı takip eder (predictive back). |
| 3 | Her izin satırı | Durumu doğru ("✓ Verildi" ya da amber buton). |
| 4 | Konum iznini Android ayarlarından kaldır, uygulamaya dön | Satır kendiliğinden amber "İzin ver"e döner; dokununca izin istenir, verince "✓". |
| 5 | Pil satırı → "Düzelt" | Pil optimizasyonu ekranı/izin dialog'u açılır; "Kısıtlanmamış" yapınca dönüşte "✓". |
| 6 | Bildirimler, tam saatli alarm, yerel ağ için aynısı | Her biri doğru sistem ekranını açar, dönüşte güncellenir. |
| 7 | Tekrar aralığı "+" ile 5'e çık | Sayılar haneli yuvarlanır. |
| 8 | Alana klavyeyle 45 yaz, klavyeyi kapat | Alan kırmızımsı titrer, eski değere döner. |
| 9 | Vazgeçme süresini aralıktan küçük yap | Reddedilir (titreme). |
| 10 | Aralık 1, vazgeçme 3 yap → S09 #1'i kısaca tekrarla | Denemeler 1 dk arayla, 3 dk sonra vazgeçilir. (Sonra 2/10'a geri al.) |
| 11 | PC için "Nazik" seç → Not Defteri'nde kaydedilmemiş yazıyla Kapat | PC kapanmaz; 90 sn sonra "Kapanmadı · Açık bir program engelliyor olabilir". |
| 12 | Titreşimi kapat, bir hold butonu kullan | Hiç titreşim yok. |

## Bitti tanımı
Testler yeşil; cihaz testi #1–12 geçti.

## Sprint sonu raporu
_(Claude doldurur)_
