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
_(Claude doldurur)_
