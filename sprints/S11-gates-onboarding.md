# S11 — Tam ekran kapılar + onboarding

**Spec:** 10.6, 12.2 (kapı giriş/çıkış), 12.4 (sürekli arka plan), 12.5 (Wi-Fi halkaları), 14 (onboarding), 16 (NoWifi UI testi), 17 faz 10

## Amaç
Wi-Fi yoksa ya da yerel ağ izni yoksa uygulama tam ekran ve animasyonlu bir kapı gösterir; koşul düzelince kendiliğinden çözülerek kapanır. İlk açılışta 3 sayfalık izin akışı.

## Görevler
1. **NoWifiScreen:** 124 cam daire + `wifi_off` + amber parlama, sürekli dışa yayılan 3 halka (0.6→1.6, 0.5→0, 2.4 sn, 0.8 sn faz) + nefes; başlık/açıklama; bilgi kartı ("Şu an mobil veri · {operatör}" / "Bağlantı yok"); "Wi-Fi ayarlarını aç" (`Settings.Panel.ACTION_WIFI`) + alt not; sürekli canlı arka plan sahnesi.
2. **NoPermissionScreen:** aynı düzen; izin iste, kalıcı reddedilmişse uygulama ayarları.
3. **Kapı yönetimi** (`AppNav` üstünde): öncelik izin > Wi-Fi; giriş 1.04→1 + fade; çıkış yukarı hafif kayıp çözülme, alttaki ekran ortaya çıkar. Ayarlar ve başarısız ekranının da üstünde.
4. **Onboarding:** ilk açılışta 3 sayfa (Yerel ağ · Konum (atlanabilir) · Bildirim), cam kartlar, canlı arka plan, yatay spring sayfa geçişi; tamamlanınca bir daha gösterilmez (DataStore bayrağı). Debug araçlarındaki geçici izin butonları kalır.
5. Animasyonları kaldır modunda halkalar durur, geçişler crossfade.

## Otomatik testler (Claude koşturur)
- Robolectric Compose: Wi-Fi bağlantısı kesilince `NoWifiScreen` görünür, gelince kaybolur (sahte NetworkMonitor); izin yokken NoPermission öncelikli; onboarding yalnız ilk açılışta.

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Uygulamayı kaldır, kur, aç | Onboarding: 1. sayfa yerel ağ izni; izin ver → sayfa yaylanarak kayar → Konum → Bildirim → ana ekran. |
| 2 | Uygulamayı kapatıp aç | Onboarding tekrar çıkmaz. |
| 3 | Uygulama açıkken Wi-Fi'ı kapat (mobil veri açık) | Tam ekran "Wi-Fi'a bağlı değilsin" büyüyerek/solarak gelir; halkalar sürekli yayılır; kart "Şu an mobil veri · {operatör}". |
| 4 | Mobil veriyi de kapat | Kart "Bağlantı yok"a animasyonla değişir. |
| 5 | "Wi-Fi ayarlarını aç" | Wi-Fi paneli açılır. |
| 6 | Wi-Fi'ı aç | Kapı yukarı doğru çözülerek kaybolur, alttaki ekran görünür; ekran kendiliğinden kapanır. |
| 7 | Ayarlar ekranındayken Wi-Fi'ı kapat | Kapı Ayarlar'ın da üstünde çıkar. |
| 8 | Android ayarlarından yerel ağ iznini kaldır, uygulamaya dön | İzin kapısı çıkar; "İzin ver" → izin; verince kapanır. |
| 9 | İzni "bir daha sorma" ile reddet | Buton uygulama ayarlarını açar. |
| 10 | Animasyonları kaldır açıkken Wi-Fi kapat | Halkalar durur; giriş/çıkış kısa solma. |

## Bitti tanımı
Testler yeşil; cihaz testi #1–10 geçti.

## Sprint sonu raporu
_(Claude doldurur)_
