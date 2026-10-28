# WoL Denetim Merkezi

Yerel ağdaki Windows bilgisayarları Wake-on-LAN ile uyandıran, yanlarındaki küçük bir agent üzerinden kilitleyen / uyutan / kapatan, durumlarını çoklu sinyalle gösteren native Android uygulaması + Windows servisi. Kişisel kullanım, sideload.

- Ürün tanımı: [`SPEC.md`](SPEC.md) (bölüm 19 karar günlüğü önceliklidir)
- Sprint planı: [`sprints/`](sprints/) · [`TASKLIST.md`](TASKLIST.md) · [`prompts.md`](prompts.md)
- Agent protokolü: [`protocol/PROTOCOL.md`](protocol/PROTOCOL.md)

## Repo yapısı

```text
/
├─ SPEC.md, TASKLIST.md, prompts.md, sprints/
├─ protocol/
│  ├─ PROTOCOL.md       agent protokolünün normatif tarifi
│  └─ vectors.json      Kotlin ve C# testlerinin ortak test vektörleri (üretilir)
├─ android/             Gradle projesi (tek :app modülü)
└─ agent/               .NET 10 çözümü
   ├─ WolAgent/          Worker Service + CLI (tek exe)
   ├─ WolAgent.Tests/    xUnit
   └─ tools/VectorGen/   vectors.json üretici
```

## Gereksinimler

- Android Studio (JBR 21 içinde gelir), Android SDK Platform **37**
- .NET **10** SDK
- Telefon: Android 17 (API 37), `minSdk` 35

## Android

```bash
cd android
./gradlew :app:testDebugUnitTest      # birim + Robolectric testleri
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :app:installDebug           # bağlı telefona kur (ADB)
```

Android Studio'da **`android/`** klasörünü açın.

Debug build telefona iki ikon kurar:
- **WoL Denetim Merkezi**: uygulamanın kendisi.
- **WoL Debug**: her sprintin cihazda test araçları (K19). Release build'de bulunmaz.

Material Symbols yalnız kullanılan ikonlara indirgenmiş olarak gömülüdür. Yeni ikon gerekirse:

```bash
pip install fonttools
# android/tools/symbols/symbols.txt dosyasına adı ekle, sonra:
python tools/symbols/subset_symbols.py   # font + ui/theme/Sym.kt yeniden üretilir
```

Notlar:
- Robolectric 4.17 en fazla SDK 36 destekliyor. JVM UI testleri SDK 36 + Java 21 ile koşar (`app/src/test/resources/robolectric.properties`). Gerçek cihaz testleri Android 17'de yapılır.
- `vectors.json` Gradle tarafından `../protocol`'dan okunur; testlere `wol.vectors` sistem özelliğiyle, debug APK'ya asset olarak girer.

## Agent (Windows)

```bash
cd agent
dotnet test                                    # xUnit
dotnet run --project tools/VectorGen           # protocol/vectors.json'u yeniden üret
```

Tek dosya yayın, kurulum ve CLI (`install` / `uninstall` / `reset` / `status`) S04'te eklenecek.

## Test vektörleri

`protocol/vectors.json` **elle düzenlenmez**. Protokol değişirse:
1. `PROTOCOL.md`'yi güncelle.
2. `agent/tools/VectorGen`'i güncelle ve çalıştır.
3. Kotlin ve C# testlerini yeşile getir.

Üretici deterministiktir: aynı kod aynı dosyayı üretir.
