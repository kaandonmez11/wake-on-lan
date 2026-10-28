# S00 — Monorepo, protokol ve saf kripto çekirdeği

**Spec:** 3, 4, 7.2, 8, 8.4, 15 (MAC), 16 (Kotlin + C# vektör testleri), 17 faz 0 · **Kararlar:** K1, K2, K3, K4, K5, K18, K19

## Amaç
Repo spec'teki yapıya kavuşur; protokol yazılı hale gelir; Kotlin ve C# aynı test vektörlerini geçer. Telefonda "WoL Debug" ikonu açılır ve vektörlerin **gerçek cihazda** da geçtiğini gösterir (Android'in X25519 sağlayıcısı cihazda doğrulanmış olur).

## Önkoşul
- Android SDK Platform 37 kurulu (Kaan kuruyor).
- .NET 10 SDK kurulu (Kaan kurdu; `dotnet --list-sdks` ile doğrula).

## Doğrulanacak
- AGP 9.1.x'in `compileSdk 37` desteği; gerekiyorsa AGP güncel stabile yükseltilir.
- Android'de `KeyAgreement.getInstance("XDH")` / `KeyPairGenerator("X25519")` desteği ve ham 32 bayt ↔ X.509/PKCS#8 dönüşümü (API 35+).
- BouncyCastle.Cryptography güncel stabil sürümü (.NET 10 uyumu).

## Görevler
1. **Taşıma (K1):** `git mv` ile `app/`, `gradle/`, `build.gradle.kts`, `settings.gradle.kts`, `gradle.properties`, `gradlew*` → `android/`. `.idea/` git takibinden çıkarılır (Android Studio `android/`'ı açınca yeniden üretir). `local.properties` taşınır, git'e girmez. Kök `.gitignore` güncellenir (Gradle, `.idea`, `bin/`, `obj/`, `*.user`).
2. **Gradle:** compile/target 37, minSdk 35 (K2); Java 17 toolchain; Kotlin + Compose + KSP + Hilt eklentileri; `libs.versions.toml`'a gerekli bağımlılıklar; JUnit5 (`de.mannodermaus.android-junit5`), Robolectric. Eski şablon testleri (`ExampleUnitTest`, `ExampleInstrumentedTest`) ve AppCompat/Material XML teması kaldırılır; `allowBackup=false` (K18).
3. **Uygulama kabuğu:** `WolApp` (`@HiltAndroidApp`), boş `MainActivity` (Compose, koyu `#0C0D10` zemin, ortada "WoL Denetim Merkezi").
4. **Debug aracı (K19):** `src/debug` altında `DebugActivity` + ayrı launcher girişi "WoL Debug"; sekmeli basit iskelet (sonraki sprintler sekme ekler). İlk sekme **Vektörler**.
5. **protocol/PROTOCOL.md:** bölüm 8 + K4 (rid önbelleği, retry), K5 (kodlama), K6 (eşleşme kopması) normatif metin; her mesajın JSON örneği ve `canonical` formülü.
6. **protocol/vectors.json + üretici:** `agent/tools/VectorGen` (C# konsol). Sabit X25519 anahtar çiftleri (telefon + agent), sabit `agentId`/`phoneId`/nonce'lar. İçerik:
   - X25519 ortak sır, HKDF `K`;
   - PAIR_OK `proof`, STATUS `sig`, STATUS_OK `sig`, CMD `sig` (5 komut × force true/false), ACK `sig` — her biri için `canonical` metni + beklenen Base64;
   - magic packet baytları (en az 2 MAC);
   - broadcast vakaları: `192.168.1.24/24`, `10.0.5.17/22`, `/31` ve `/32` → hata;
   - MAC normalizasyonu: geçerli/geçersiz örnekler (`aa-bb-…` → `AA:BB:…`).
7. **agent/ çözümü:** `WolAgent.sln`, `WolAgent/` (şimdilik boş Worker + `Protocol/` klasöründe kripto: X25519 (BouncyCastle), HKDF, HMAC, canonical oluşturucu), `WolAgent.Tests/` (xUnit) → `vectors.json`'daki her şeyi doğrular.
8. **Kotlin saf çekirdek** (`data/crypto`, `net`, `net/agent`): `Hkdf`, `Hmac`, `X25519` (java.security, ham anahtar dönüşümü), `AgentCodec` (canonical + imza + JSON kodlama/çözme), `MagicPacket.build`, `BroadcastResolver.compute`, `MacAddress` (doğrulama + normalizasyon). Saat/rastgelelik enjekte edilir.
9. **Kotlin testleri:** `vectors.json` bir Gradle görevinin `../protocol` yolundan test kaynağına (ve debug asset'ine) kopyalanmasıyla okunur; tüm vektörler + bölüm 16'daki MagicPacket/BroadcastResolver/MAC testleri.
10. **Debug › Vektörler sekmesi:** asset'teki `vectors.json`'u cihazda aynı Kotlin koduyla çalıştırır; grup grup ✓/✗ ve toplam "N/N geçti" gösterir. "Tekrar çalıştır" butonu.
11. **README.md** (kök): repo yapısı, Android derleme, agent derleme/kurulum, test komutları.

## Otomatik testler (Claude koşturur)
- `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`
- `cd agent && dotnet test`
- VectorGen tekrar çalıştırılınca `vectors.json` bayt bayt aynı çıkar (deterministik).

## Cihaz testi (Kaan yapar)
| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | Android Studio'da `android/` klasörünü aç, Gradle sync | Sync hatasız biter. |
| 2 | Debug APK'yı telefona kur (Claude ADB ile kurdu) | Uygulama çekmecesinde **iki ikon**: "WoL Denetim Merkezi" ve "WoL Debug". İkisinde de senin logon (mor-mavi çizgili zemin üzerinde beyaz W + el). |
| 2b | Ana ekranda tema ikonlarını aç (Ayarlar › Duvar kağıdı ve stil › Tema ikonları / Renk paleti) | Logo tek renkli temalı ikon olarak da düzgün görünür. |
| 3 | "WoL Denetim Merkezi"ni aç | Koyu ekran, ortada "WoL Denetim Merkezi" yazısı; çökme yok. |
| 4 | "WoL Debug" → Vektörler | 8 grubun hepsi yeşil ✓, üstte **"63/63 geçti"**, altında "XDH sağlayıcısı: AndroidOpenSSL". |
| 5 | "Tekrar çalıştır"a bas | Aynı sonuç, yine tamamı yeşil. |
| 6 | Uygulamayı kaldırıp yeniden kur | Kurulum sorunsuz (yedekleme kapalı, eski veri gelmez). |

## Bitti tanımı
Testler yeşil; repo bölüm 3'teki yapıda; cihaz testi #1–6 Kaan'dan geçti.

## Sprint sonu raporu
_2026-10-29 · Claude_

### Yapılanlar
- **Taşıma (K1):** Android projesi `android/` altına taşındı (`git mv`). `.idea` takipten çıkarıldı. Kök ve `android/` `.gitignore` güncellendi. Şablon testleri, AppCompat/Material teması, yedekleme XML'leri ve eski webp launcher ikonları kaldırıldı.
- **Gradle:** AGP **9.4.1**, Gradle **9.6.0**, Kotlin **2.4.20** (AGP built-in Kotlin + Compose compiler eklentisi), KSP 2.3.12, Hilt 2.60.1, Compose BOM 2026.09.00, kotlinx-serialization-json 1.12.0 (yalnız runtime; derleyici eklentisi yok), JUnit 6.1.3 (Jupiter + vintage), Robolectric 4.17. compile/target 37, minSdk 35, Java 17 bytecode. `allowBackup=false` (K18).
- **Kabuk:** `WolApp` (`@HiltAndroidApp`), `MainActivity` (Compose, edge-to-edge, `#0C0D10`).
- **Debug aracı (K19):** `src/debug` → `DebugActivity` ("WoL Debug", ayrı task affinity), sekme iskeleti (`DebugTab` enum'u; sonraki sprintler buraya ekler), **Vektörler** sekmesi (`VectorRunner`).
- **Uygulama ikonu (K21, araya giren istek):** `Android_App_Icon/` kaynaklarından adaptive icon yapıldı.
  - Ön plan: `APP_Icon.svg` → `drawable/ic_launcher_foreground.xml` (vector). Ölçek `Logo_All.png` ile aynı oranda ayarlandı, 66dp güvenli alanın içinde.
  - Arka plan: `Logo_Background.png` → 432 px `drawable-nodpi/ic_launcher_background_art.png`.
  - Monochrome (temalı ikon): ön plan vector'ü.
  - Daire maskeli önizleme `Logo_All.png` ile birebir göründü. Kaynak klasör, Kaan'ın izniyle silindi.
- **protocol/PROTOCOL.md:** bölüm 8 + K4 (rid önbelleği, retry), K5 (kodlama), K6 (eşleşme kopması), nonce kuralları, hata kodları ve vektör dosyası rehberi.
- **protocol/vectors.json** (`agent/tools/VectorGen`, deterministik): RFC 7748 X25519, RFC 5869 HKDF (A.1, A.3), eşleşme `K`'sı, 18 imza (5 komut × force/graceful + 5 ACK + PAIR_OK/STATUS/STATUS_OK), 12 mesaj tipinin 16 örneği (5 ERR kodu dahil), 3 magic packet, 11 broadcast vakası, 10 MAC vakası.
- **agent/:** `WolAgent.slnx`; `WolAgent` (Worker iskeleti + `AddWindowsService`, `Protocol/`: `X25519Keys` (BouncyCastle 2.7.0), `AgentCrypto` (BCL HKDF/HMAC, sabit zamanlı doğrulama), `Canonical`, `AgentProtocol`); `WolAgent.Tests` (xUnit); `tools/VectorGen`.
- **Kotlin saf çekirdek:** `data/crypto` (`Hmac`, `Hkdf`, `X25519`: java.security XDH + RFC 8410 ham anahtar önekleri), `net` (`MacAddress`, `MagicPacket`, `BroadcastResolver`), `net/agent` (`AgentMessage`, `AgentCodec`, `AgentCrypto`, `Canonical`).
- **README.md** (kök).

### Doğrulanacakların sonucu
| Konu | Sonuç |
|---|---|
| AGP 9.1.x ile compileSdk 37 | Desteklemiyor. **AGP 9.4.1**'e geçildi: max API 37, min Gradle 9.6.0 (developer.android.com AGP sürüm notları). |
| Android'de XDH / X25519 | Robolectric değil **gerçek cihazda** doğrulandı: S24 / Android 17'de `KeyAgreement("XDH")` sağlayıcısı **AndroidOpenSSL** (Conscrypt), PKCS#8/X.509 ham anahtar dönüşümüyle RFC 7748 bilinen sonucu tutuyor. |
| BouncyCastle (.NET 10) | `BouncyCastle.Cryptography` **2.7.0** (NuGet güncel stabil). `Rfc7748.X25519` RFC bilinen sonucu tutuyor. |

### Sapmalar / notlar
- **Robolectric + SDK 37:** Robolectric 4.17 en fazla SDK 36 destekliyor. JVM testleri `robolectric.properties` ile `sdk=36` üzerinde koşuyor. SDK 36 sandbox'ı Java 21 istediği için test JVM'i Java 21 toolchain'iyle başlatılıyor (`--add-exports/--add-opens` bayraklarıyla); uygulama bytecode'u 17'de kalıyor. Robolectric SDK 37 desteği çıkınca kaldırılacak.
- **MAC girişi baştaki/sondaki boşlukları kırpıyor.** Regex spec'teki gibi; kırpma UI rahatlığı için, iki tarafta aynı ve vektörle sabit.
- **Broadcast vakaları:** `/33` ve geçersiz IP de açık hata veriyor.
- Debug APK asset'lerine `protocol/` klasörü ekleniyor (`vectors.json` + `PROTOCOL.md`). Release'te yok (doğrulandı).

### Otomatik test sonuçları
- `./gradlew :app:testDebugUnitTest` → **78/78** yeşil (vektörler + spec 16 MagicPacket/Broadcast/MAC + codec hata durumları + Robolectric smoke).
- `./gradlew :app:assembleDebug :app:assembleRelease` → yeşil. Release APK'da `DebugActivity` ve `vectors.json` yok, launcher yalnız `MainActivity`.
- `dotnet test` → **32/32** yeşil.
- VectorGen iki kez çalıştırıldı, aynı SHA-256 (deterministik).

### Kontrol raporu — "baktım, tamam; sen de kontrol et"
- [x] Telefona debug APK'yı ADB ile kurdum, WoL Debug › Vektörler açıldı: **63/63 geçti**, XDH sağlayıcısı AndroidOpenSSL. (Bu kontrol ekran görüntüsüyle yapıldı; Kaan'ın kuralıyla bundan sonra cihazdan ekran görüntüsü alınmıyor.)
- [x] Release APK'da debug aracı yok, iki launcher yalnız debug build'de var. → Sen #2'de iki ikonu gör.
- [x] İkon önizlemesi Logo_All ile aynı oranda. → #2 ve #2b'de gerçek launcher'da gör.
- [x] Kotlin ve C# aynı `vectors.json`'u geçiyor; RFC 7748/5869 sabitleri iki tarafta da ayrıca test ediliyor.
- [x] **Kaan: "Tüm testler geçti"** (2026-10-29). Cihaz testi tamam.

### Açık kalanlar
- Android Studio artık **`android/`** klasöründen açılmalı (eski kök proje açıksa kapatıp `android/`'ı aç).
- Agent CLI/servis/UDP: S04.
- Bölüm 18'deki Haze sürümü: S01'de doğrulanacak.
