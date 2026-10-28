# Sprintler — ortak kurallar

Bu klasördeki her dosya bir sprinttir. Sıra: S00 → S13. Bir sprint bitmeden sonrakine geçilmez.
Kaynak: `SPEC.md` (çelişkide **bölüm 19 Karar günlüğü** geçerli).

## Her sprintte Claude'un yapacakları
1. `SPEC.md` + ilgili sprint dosyasını oku. Önceki sprintin "Sprint sonu raporu"nu oku.
2. Sprintteki **"Doğrulanacak"** maddelerini koda geçmeden resmi dokümandan kontrol et; sonucu sprint raporuna ve commit mesajına yaz. Spec'le çelişen bir engel çıkarsa dur, Kaan'a yaz (SPEC kural 5).
3. Görevleri uygula. Saf mantık için önce test yaz.
4. Otomatik testleri koş: `android/` için `./gradlew :app:testDebugUnitTest :app:assembleDebug`, `agent/` için `dotnet test`. Hepsi yeşil olmalı.
5. UI sprintlerinde bölüm 12'deki ilgili animasyon maddelerini kodda tek tek denetle (animasyonsuz durum değişimi = bug).
6. Sprint dosyasının sonundaki **"Sprint sonu raporu"** bölümünü doldur:
   - ne yapıldı, doğrulanacakların sonucu, sapmalar;
   - **Kontrol raporu:** "Şunlara baktım, tamam — sen de kontrol et" listesi (K20);
   - açık kalanlar.
7. `TASKLIST.md`'de o sprintin kutularını işaretle.
8. Tek commit at: `S0X: <özet>` + doğrulama notları, ardından `git push origin master` (Kaan onayladı, 2026-10-29).

## Cihaz kuralları
- Claude debug APK'yı Kaan'ın telefonuna ADB (kablosuz) ile kurabilir.
- Claude telefondan **asla ekran görüntüsü / kayıt almaz**. Sonucu Kaan bildirir.

## Kaan'ın yapacakları
- Sprint dosyasındaki **"Cihaz testi"** tablosunu sırayla dener; her satırda "Dene → Beklenen sonuç" var.
- Sonuç farklıysa Claude'a satır numarasıyla yazar (ör. "S05 #4 tutmadı, durum 20 sn kapalı göstermedi").

## Debug aracı (K19)
Debug build'de ikinci bir launcher ikonu: **"WoL Debug"**. Her sprint buraya kendi sekmesini ekler (Vektörler, Tasarım, Veri, Ağ, Agent, Durum…). Release build'de yoktur. Cihaz testlerinin çoğu bu araçla başlar; asıl UI geldikçe testler asıl uygulamaya kayar.

## APK kurulumu (Kaan için)
Android Studio'da `android/` klasörünü aç → üstteki cihaz seçiminde telefonunu seç → ▶ Run. Ya da Claude'un ürettiği `android/app/build/outputs/apk/debug/app-debug.apk` dosyasını telefona kur.
