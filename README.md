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

**Yayın** (self-contained tek dosya, ~40 MB, .NET kurulu olmayan PC'de de çalışır):

```bash
dotnet publish agent/WolAgent -c Release -r win-x64 -o agent/publish   # → agent/publish/WolAgent.exe
```

**Kurulum** — `WolAgent.exe`'yi PC'ye kopyala, **yönetici** Komut İstemi'nde:

| Komut | Ne yapar |
|---|---|
| `WolAgent.exe install` | `C:\Program Files\WolAgent\`'a kopyalar, servisi kurar (Otomatik, LocalSystem, 3× yeniden başlatma), güvenlik duvarına UDP 40900 / yerel alt ağ kuralı ekler, ilk kurulumda kimlik + anahtar üretir, başlatır. Tekrar çalıştırmak = güncelleme (anahtarlar korunur). |
| `WolAgent.exe status` | Servis durumu, agentId, eşleşme, hazırda bekletme, uyku türü (S3 / Modern Bekleme), ağ kartları |
| `WolAgent.exe reset` | Eşleşmeyi siler (yeni telefon için) |
| `WolAgent.exe uninstall [--purge]` | Servis, kural ve program klasörünü kaldırır; `--purge` yapılandırma ve logları da siler |

Yapılandırma `%ProgramData%\WolAgent\config.json` (anahtarlar DPAPI ile şifreli, klasör yalnız SYSTEM + Administrators). Loglar `%ProgramData%\WolAgent\logs\agent-yyyyMMdd.log`, 7 gün; anahtar/imza/nonce yazılmaz.

Hata ayıklama: `WolAgent.exe run` servisi konsolda çalıştırır; `WOLAGENT_HOME` ortam değişkeni yapılandırma klasörünü değiştirir.

## Windows güç ikonları

Güç butonları (Kilitle, Uyku, Hazırda, Yeniden, Kapat) Windows 11'in Başlat › Güç menüsündeki ikonlarla birebir aynı olsun diye Microsoft'un **Segoe Fluent Icons** fontundaki glifler kullanılır:

| Buton | Glif | Kod |
|---|---|---|
| Kilitle | Lock | U+E72E |
| Uyku | QuietHours | U+E708 |
| Hazırda | Recent | U+E823 |
| Yeniden | UpdateRestore | U+E777 |
| Kapat | PowerButton | U+E7E8 |

**Lisans:** Segoe Fluent Icons yalnız Windows içinde kullanılabilir; glifler başka platformda dağıtılamaz. Bu yüzden ikon dosyaları **repoda yok**. Her derlemede Gradle'ın `generateWinIcons` görevi onları `android/app/build/generated/winicons/` altına kendisi üretir. `build/` git'e girmez, glifler de hiçbir zaman repoya gitmez.

İkon başına kaynak, öncelik sırasıyla:

1. **Kendi SVG'n:** `android/app/winicons/custom/` klasörüne `lock.svg`, `sleep.svg`, `hibernate.svg`, `restart.svg`, `shutdown.svg` adlarıyla koy. Bir sonraki derlemede o buton bu ikonu kullanır. Bu klasördeki SVG'ler git'e girmez (lisansı belirsiz bir ikon yanlışlıkla paylaşılmasın diye).
2. **Windows fontu:** Windows'ta derliyorsan `C:\Windows\Fonts\SegoeIcons.ttf` otomatik okunur; hiçbir şey yapman gerekmez. Başka bir konum için: `./gradlew assembleDebug -Pwol.segoeIcons=<yol>`.
3. **Yedek:** Font yoksa (macOS/Linux ya da eski Windows) repodaki `android/app/winicons/fallback/` kullanılır. Bunlar Microsoft'un **Fluent UI System Icons** setinden (MIT lisanslı, paylaşılabilir) en yakın eşdeğerler.

**SVG ekleme kuralları** (dönüştürücü bilerek basit tutuldu):
- `viewBox` olmalı (ör. `0 0 24 24`). Yoksa `width`/`height` kullanılır.
- Şekil `<path d="...">` olmalı. Birden fazla path olabilir; `fill-rule="evenodd"` korunur.
- `transform`, `stroke`, `<circle>`/`<rect>`/`<g transform>` desteklenmez. Inkscape'te **Yol › Nesneyi Yola Çevir** ve **Çizgiyi Yola Çevir**, ya da Figma'da **Outline stroke + Flatten** ile tek dolgu yoluna indir.
- Renk önemsiz: uygulama ikonu duruma göre boyar.
- Derleme çıktısında `Windows ikonu: lock.svg (custom)` satırı, dosyanın alındığını gösterir. Hatalı SVG'de görev neyin eksik olduğunu yazarak durur.

## Test vektörleri

`protocol/vectors.json` **elle düzenlenmez**. Protokol değişirse:
1. `PROTOCOL.md`'yi güncelle.
2. `agent/tools/VectorGen`'i güncelle ve çalıştır.
3. Kotlin ve C# testlerini yeşile getir.

Üretici deterministiktir: aynı kod aynı dosyayı üretir.
