# S04 — Windows agent v1 + AgentClient + NetBIOS

**Spec:** 7.3 (S1, S2), 8 (tamamı), 13, 16 (C#), 17 faz 4 · **Kararlar:** K3, K4, K5, K6, K16, K19, K22, K23

## Amaç
PC'de servis olarak çalışan agent kurulur. Telefon agent'ı bulur, eşleşir, imzalı STATUS alır ve 5 güç komutunu gönderir. NetBIOS (S2) hostname + MAC getirir.

## Doğrulanacak
- `WTSDisconnectSession` ile konsol oturumunu kilitlemenin Windows 10/11'de servis (Session 0) içinden çalıştığı.
- `SetSuspendState` ve `InitiateSystemShutdownEx`'in LocalSystem'da davranışı, `SE_SHUTDOWN_NAME`.
- `GetPwrCapabilities` ile hibernate tespiti.
- Firewall kuralının (`remoteip=LocalSubnet`) programatik eklenmesi (netsh veya COM `INetFwPolicy2`).
- Self-contained tek dosya `win-x64` yayın komutu (.NET 10).
- **K22:** Modern Bekleme'de (S0ix) servis UDP paketi alabiliyor mu; Session 0'dan konsol oturumunda yardımcı süreç başlatma (`WTSQueryUserToken` + `CreateProcessAsUser`) ve `SendInput`'un bekleme modundan çıkarması; `GUID_CONSOLE_DISPLAY_STATE` bildirimi.

## Görevler — agent (C#)
1. **WolAgent** Worker Service (`UseWindowsService`), tek exe; `Program` CLI ayrıştırma: `install`, `uninstall [--purge]`, `reset`, `status`, (argümansız = servis modu).
2. **Config** (`%ProgramData%\WolAgent\config.json`): agentId, X25519 özel anahtar (DPAPI LocalMachine), eşleşme (phoneId, phonePub, K — DPAPI). Klasör ACL: SYSTEM + Administrators.
3. **Log:** günlük dönen dosya, 7 gün; her istek tek satır (tip, kaynak IP, sonuç); anahtar/imza asla.
4. **UDP 40900 sunucu:** tüm IPv4 arayüzleri; yalnız RFC1918/link-local kaynak; ≤1200 bayt JSON.
5. **Mesajlar:** DISCOVER, PAIR_HELLO (TOFU), CHALLENGE/NONCE (16 bayt, tek kullanımlık, 10 sn), STATUS, CMD/ACK, ERR kodları. **K4 rid yanıt önbelleği** (10 sn). **K5** kodlama.
6. **Komut yürütücü** (`ICommandExecutor` arayüzü): LOCK, SLEEP, HIBERNATE (`UNSUPPORTED` kontrolü), SHUTDOWN, RESTART (force/graceful); ACK'ten 300 ms sonra uygula; aynı anda tek komut → `BUSY`.
7. **NIC listesi:** MAC, ad, kablolu mu, IPv4.
8. **install/uninstall/reset/status** bölüm 13.2'deki gibi (recovery 3× 5 sn, açıklama, Automatic, firewall kuralı, güncelleme modunda anahtarları koruma).
6b. **K22 — `SCREEN_ON` + `display`:** yeni komut `SCREEN_ON` (imzalı CMD, ACK) → konsol oturumunda tek fare hareketi; `STATUS_OK`'a `display: on|off`. PROTOCOL.md + VectorGen + `vectors.json` güncellenir, iki tarafın vektör testleri genişler.
9. **xUnit:** vektörler, nonce tek kullanım + 10 sn `NONCE_EXPIRED`, `BAD_SIG`, TOFU (ilk kabul, ikinci `PAIR_DENIED`, reset sonrası kabul), kaynak IP filtresi, sahte yürütücüyle 300 ms gecikme + `BUSY`, rid önbelleği (aynı rid → aynı yanıt, komut bir kez).
10. **Yayın:** `dotnet publish` profili → `agent/publish/WolAgent.exe`; README'de kurulum adımları.

## Görevler — Android
11. **AgentClient:** discovery (broadcast, MulticastLock), pairing (X25519 anahtar üret, K türet, `proof` doğrula, KeyVault'a sakla), challenge + STATUS (S1, 600 ms), challenge + CMD → ACK (toplam ≤ 2 sn); retry 2× 200 ms **aynı rid** (K4); K6 hata akışları (`UNKNOWN_PHONE`, `PAIR_DENIED`).
12. **NetBiosProbe (S2):** NBSTAT UDP 137, 500 ms; hostname + MAC ayrıştırma (birim testli örnek yanıt baytlarıyla).
13. **NIC seçimi (K16)** yardımcı fonksiyonu (saf, testli).
14. **Debug › Agent sekmesi:**
    - "Ara" → bulunan agent'lar (hostname, agentId kısaltılmış, sürüm, paired, hibernate, NIC'ler; K16'ya göre seçilen MAC işaretli);
    - "Eşleş" → sonuç (Eşleşti ✓ / Başka telefona eşli / proof hatası);
    - "Durum" → STATUS_OK (uptime, hostname, imza ✓);
    - 5 komut butonu (bu ekranda **basılı tut değil**, onay iletişim kutulu) + Zorla/Nazik anahtarı; sonuç (ACK ✓ / hata kodu);
    - **"Ekranı aç" (`SCREEN_ON`, K22)** butonu; "Durum" sonucunda `display` (açık/kapalı);
    - "NetBIOS" → hostname + MAC + ms.

## Otomatik testler (Claude koşturur)
- `dotnet test` (madde 9).
- Kotlin: AgentClient sahte UDP ile tüm akışlar ve hata kodları; retry'de rid aynı; NetBIOS ayrıştırma; K16.

## Cihaz testi (Kaan yapar)
Hazırlık: `agent/publish/WolAgent.exe`'yi hedef PC'ye (192.168.1.26) kopyala, ör. Masaüstü. **Yönetici** Komut İstemi aç, o klasöre geç (`cd %USERPROFILE%\Desktop`). Kurulumdan sonra komutlar her yerden `"C:\Program Files\WolAgent\WolAgent.exe" <komut>` ile de çalışır.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | `WolAgent.exe install` | "Kuruldu ✓", agentId (yeni). `services.msc`'de "WoL Denetim Merkezi agent" Çalışıyor / Otomatik. |
| 2 | `WolAgent.exe status` | Servis: Çalışıyor · Otomatik; agentId; Eşleşme: Eşleşmemiş; Port UDP 40900; Hazırda bekl.; **Uyku: Modern Bekleme (S0)**; ağ kartları (kablolu Ethernet MAC'i). |
| 3 | Windows Defender Güvenlik Duvarı › Gelişmiş › Gelen kuralları | "WoL Denetim Merkezi agent (UDP 40900)": UDP 40900, tüm profiller, uzak adres "Yerel alt ağ". |
| 4 | Telefonda WoL Debug › Agent › "Ara" | PC'nin adı ve IP'si; kablolu Ethernet MAC'inin yanında ★; paired=false. |
| 5 | PC'nin kartına dokun → "Eşleş" | "Eşleşti ✓". `WolAgent.exe status` artık "Eşleşme: <telefonun adı>" gösterir. |
| 6 | "Durum" | PC adı · çalışma süresi · ekran açık · imza ✓ · ms. |
| 7 | "Kilitle" → Gönder | PC kilit ekranına düşer; telefonda "ACK ✓". |
| 8 | PC'de oturum aç, "Uyku" → Gönder | Telefonda "ACK ✓"; PC uykuya geçer (ekran kapanır). |
| 8a | **K22:** PC uykudayken 30 sn bekle, "Uzun bekle"yi aç → "Durum" | Cevap gelir ve **"ekran kapalı"** yazar (birkaç saniye sürebilir). "Yanıt yok" derse bana yaz. |
| 8b | **K22:** PC uykudayken "Ekranı aç" → Gönder | PC uykudan çıkar, kilit ekranı görünür. Sonra "Durum" → "ekran açık". |
| 8c | **K22:** PC'yi Başlat › Uyku ile uyut, **2 dk** bekle → "Uzun bekle" ile "Durum", sonra "Ekranı aç" | 8a ve 8b'deki gibi. Olmazsa hangisinin olmadığını yaz. |
| 9 | "Hazırda" → Gönder | Hazırda bekletme açıksa PC hazırda bekletmeye geçer (Ağ sekmesinden "Uyandır" ile aç). Kapalıysa telefonda `UNSUPPORTED`. |
| 10 | PC'de Not Defteri'ne kaydetmeden yazı yaz → anahtar **Nazik** → "Kapat" | Windows "Kaydet?" / "Bu uygulama kapanmayı engelliyor" ekranında bekler; PC açık kalır. |
| 11 | Aynı durumda anahtar **Zorla** → "Kapat" | PC kaydetmeden kapanır. (Sonra Ağ sekmesinden "Uyandır".) |
| 12 | PC açıkken "Yeniden" | PC yeniden başlar; açılınca `WolAgent.exe status` "Çalışıyor" ve telefonda "Durum" yine imza ✓. |
| 13 | "NetBIOS" | PC'nin adı ve MAC'i · ms. (NetBIOS kapalıysa "Yanıt yok"; bana yaz.) |
| 14 | Telefonda uygulamayı kaldır, Claude'a yeniden kurdur (ya da Android Studio ▶), Agent › "Ara" → karta dokun → "Eşleş" | "Başka telefona eşli · PC'de `WolAgent.exe reset` çalıştır". |
| 15 | PC'de `WolAgent.exe reset`, telefonda tekrar "Eşleş" | PC'de "Eşleşme silindi"; telefonda "Eşleşti ✓". |
| 16 | `%ProgramData%\WolAgent\logs\` içindeki dosyayı Not Defteri'yle aç | Her istek tek satır (zaman, tip, IP, sonuç); anahtar, imza, nonce yok. |
| 17 | `WolAgent.exe uninstall --purge` | Servis, güvenlik duvarı kuralı, `C:\Program Files\WolAgent` ve `C:\ProgramData\WolAgent` silinmiş. Sonra tekrar `install` + telefonda "Eşleş" (sonraki sprintler için). |

## Bitti tanımı
İki tarafın testleri yeşil; doğrulanacaklar raporlandı; cihaz testi #1–17 geçti (8a–8c K22 denemesi: sonucu ne olursa olsun raporlanır).

## Sprint sonu raporu
_2026-10-29 · Claude_

### Doğrulananlar (Microsoft Learn + bu makinede deneme)
- **`SetSuspendState`:** `SE_SHUTDOWN_NAME` gerekiyor (AdjustTokenPrivileges ile açılıyor). Sayfa Modern Bekleme'den (S0) hiç bahsetmiyor.
  - Bu yüzden SLEEP önce `SetSuspendState(false,…)` deniyor. Başarısızsa ve PC Modern Beklemeli ise ekranı kapatan yardımcıya düşüyor (`SC_MONITORPOWER 2`). Hangi yolun çalıştığını cihaz testi #8 gösterecek.
- **`SYSTEM_POWER_CAPABILITIES`:** alan sırası dokümandan alındı.
  - Ofsetler: S3=5, S4=6, HiberFilePresent=8, AoAc=20.
  - KAAN-DESKTOP'ta (192.168.1.50; `powercfg /a`: S3 + Hazırda var, S0 yok) birebir doğrulandı: S3 ✓, S4 ✓, hiberfil ✓, Modern Bekleme ✗.
- **`WTSDisconnectSession`:** sayfa servisten konsol oturumunu ayırmanın etkisini anlatmıyor. Spec'teki yöntem korundu, cihaz testi #7 doğrulayacak.
- **`PowerSettingRegisterNotification` + `GUID_CONSOLE_DISPLAY_STATE`** (6FE69556-…; 0 kapalı, 1 açık, 2 kısık):
  - Kayıt anında güncel değer geliyor (dokümanda yazıyor).
  - Penceresiz `DEVICE_NOTIFY_CALLBACK` ile bu makinede çalıştı (`display=on`).
- **`InitiateSystemShutdownExW`:** dokümantasyon sayfası 404 verdi. Spec 13.4'teki kullanım uygulandı (`timeout=0`, `force` = Zorla/Nazik); cihaz testi #10/#11.
- **Güvenlik duvarı:** `netsh advfirewall firewall add rule … protocol=UDP localport=40900 remoteip=localsubnet profile=any program=<exe>`. Kural yalnız agent exe'si için.
- **Tek dosya yayın (.NET 10):**
  - `PublishSingleFile` + `SelfContained` + `RuntimeIdentifier=win-x64` + `IncludeNativeLibrariesForSelfExtract`, ayrıca sıkıştırma: **~40 MB** tek exe.
  - Tek dosyada `Assembly.Location` boş döndüğü için exe yolu `Environment.ProcessPath`'ten alınıyor.
- **K22 — Modern Bekleme** (Microsoft "modern standby" sayfaları):
  - Bekleme sırasında **Session 0 servisleri 30 sn'de en fazla ~1 sn** çalıştırılıyor (DAM). 24H2'den itibaren bazı servisler tamamen askıya alınabiliyor.
  - Masaüstü uygulamaları DAM'den sonra hiç çalıştırılmıyor.
  - Yazılımla bekleme modundan çıkış **belgelenmemiş**. Ekranı açabilen uyanma kaynakları: güç tuşu, klavye/fare, Uzak Masaüstü (Ethernet'te).
  - Sonuç: agent'ın uykudayken cevabı **gecikebilir ya da hiç gelmeyebilir**. Bu yüzden Debug'da "Uzun bekle" var (her deneme 4 sn, toplam 12 sn; aynı rid, agent önbelleği sayesinde komut bir kez çalışır).
  - "Ekranı aç" yardımcısı bekleme sırasında hiç başlamayabilir. 8a–8c bunu gösterecek; olmazsa K22 kapatılır, Hazırda Beklet önerilir.

### Yapılanlar
- **Protokol (K22):**
  - `cmd` listesine `SCREEN_ON` eklendi.
  - `STATUS_OK`'a `display` (`on|off|dimmed|unknown`) eklendi ve **imzaya dahil edildi**: `status-ok|nonce|agentId|uptimeSec|display`.
  - PROTOCOL.md, SPEC §8, VectorGen ve `vectors.json` güncellendi (+SCREEN_ON CMD/ACK, display=off örneği). İki taraf aynı vektörlerden geçiyor.
- **Agent (C#, `net10.0-windows`):**
  - `AgentCore` (soketten bağımsız, testli):
    - RFC1918/link-local filtresi; ≤1200 bayt; zarf/sürüm/rid doğrulaması.
    - DISCOVER; TOFU eşleşme (sıfır ortak sır reddi); CHALLENGE/NONCE (16 bayt, tek kullanım, 10 sn).
    - STATUS ve CMD doğrulama sırası: phoneId → imza → nonce.
    - `UNSUPPORTED` (hibernate yok / bilinmeyen komut), `BUSY`.
    - **K4 önbelleği:** (IP, rid) → 10 sn içinde aynı yanıt; komut tekrar çalışmaz.
    - ACK'ten 300 ms sonra yürütme; yürütücü hata verse de BUSY bırakılır.
  - `FileConfigStore`:
    - `%ProgramData%\WolAgent\config.json`; özel anahtar ve K **DPAPI LocalMachine** ile şifreli.
    - Klasör ACL'i yalnız SYSTEM + Administrators, miras kesik.
    - CLI `reset` dosyayı değiştirince servis zaman damgasından görüyor; yeniden başlatma gerekmiyor.
  - `RequestLog`: günlük dosya, 7 gün; satır = zaman · tip · IP · sonuç.
  - `Worker`: UDP 40900, tüm IPv4 arayüzleri; Windows'un `SIO_UDP_CONNRESET` tuzağı kapalı.
  - `WindowsCommandExecutor`:
    - LOCK = `WTSDisconnectSession`;
    - SLEEP = `SetSuspendState` → Modern Bekleme'de ekranı kapatma yedeği;
    - HIBERNATE;
    - SHUTDOWN / RESTART = `InitiateSystemShutdownEx`;
    - **SCREEN_ON** = `SetThreadExecutionState` + konsol oturumunda yardımcı süreç. Yardımcı, aynı exe'nin `screen-on` modu: SYSTEM belirteci + oturum kimliği, giriş masaüstüne geçer, 1 px fare hareketi ve `SC_MONITORPOWER -1` gönderir.
  - `WindowsSystemInfo`:
    - hostname, uptime, hazırda bekletme (S4 + hiberfil), `display`;
    - fiziksel NIC'ler: sanal/VPN/Hyper-V/filtre sürücüsü hariç, MAC başına tek, en fazla 6.
  - **CLI:**
    - `install`: kopyala; servisi oluştur ya da güncelle (Otomatik, LocalSystem, açıklama, 3× 5 sn yeniden başlatma); güvenlik duvarı; kimlik/anahtar (varsa korunur); başlat.
    - `uninstall [--purge]`, `reset`, `status` (uyku türünü de gösterir: S3 / Modern Bekleme).
    - Hepsi yönetici kontrolü yapıyor ve UTF-8 Türkçe çıktı veriyor.
  - Gizli `run` modu (konsolda servis) ve `WOLAGENT_HOME` (test klasörü).
- **Android:**
  - `AgentTransport`: Wi-Fi'a bağlı tek UDP soketi; yeniden denemelerde aynı soket, geç yanıt kaybolmaz.
  - `AgentClient`:
    - `discover` (subnet + 255.255.255.255, MulticastLock, agentId başına tek sonuç);
    - `pair`: yeni X25519 çifti, `proof` doğrulama, özel anahtar sıfırlanır;
    - `status`: 600 ms bütçe, deneme 200 ms;
    - `command`: ≤2 sn, deneme 300 ms; `force` yalnız SHUTDOWN/RESTART'ta.
    - Her istek en fazla 2 kez **aynı baytlarla** tekrar (K4). Yalnız hedef IP'den ve aynı rid'li yanıt kabul edilir.
    - Sonuç tipleri: `UnknownPhone` (K6), `BadSignature`, `NoResponse`, `Error(code)`.
  - `NetBiosProbe` (S2): RFC 1002 NBSTAT sorgusu, 500 ms; hostname (grup adları atlanır) + MAC.
  - `NicSelector` (K16): kablolu ve aynı alt ağda → ilk kablolu → MAC'i olan ilk.
  - **WoL Debug › Agent sekmesi:**
    - Ara: kartlar; ★ = K16 MAC'i; dokununca hedef seçilir.
    - Eşleş / Telefonda unut.
    - Durum: uptime, ekran, hazırda bekletme, imza, ms; "Uzun bekle" anahtarı.
    - 6 komut, onay penceresi ve Zorla/Nazik anahtarıyla.
    - NetBIOS.
    - K6: `UNKNOWN_PHONE` gelince telefondaki anahtar siliniyor; `PAIR_DENIED` → "Başka telefona eşli".

### Sapmalar / notlar
- **`display` imzaya eklendi.** K22 yalnız "alan eklenir" diyordu; imzasız alan kurcalanabilir olurdu. Bu yüzden canonical genişletildi.
- **CLI adı:** spec'te `wolagent install` yazıyor. Exe PATH'e eklenmediği için komut `WolAgent.exe install` (ya da tam yol); davranış aynı.
- **Hedef framework `net10.0-windows`:** DPAPI, servis ve Win32 API'leri Windows'a özgü.
- **Agent `run` modu ve `WOLAGENT_HOME`** spec'te yok; yalnız hata ayıklama ve duman testi için.
- **SLEEP'in Modern Bekleme yedeği** (`SC_MONITORPOWER 2`) spec'te yok; K22 kapsamında eklendi.

### Otomatik test sonuçları
- `dotnet test` → **68/68** yeşil (S04 ile +36). `AgentCoreTests` ve `StorageTests` şunları kapsıyor:
  - kaynak filtresi (9 adres + IPv4-mapped);
  - bozuk / büyük / yanlış sürümlü / yanlış rid'li paket;
  - DISCOVER;
  - TOFU: ilk kabul, ikinci `PAIR_DENIED`, reset sonrası kabul; geçersiz açık anahtar;
  - `UNKNOWN_PHONE`; imzalı STATUS + `display`, `display` kurcalanırsa imza bozuluyor;
  - nonce: tek kullanım, 10 sn'de `NONCE_EXPIRED`; `BAD_SIG` nonce'u yakmıyor; başka telefonun nonce'u kullanılamıyor;
  - CMD: 299 ms'de çalışmıyor, 300 ms'de çalışıyor; `BUSY` ve bitince kabul; yürütücü hatasında `BUSY` bırakılıyor; `UNSUPPORTED` (hazırda bekletme yok, bilinmeyen komut); `SCREEN_ON`; `force` kurcalanırsa `BAD_SIG`;
  - **K4:** aynı rid 3 kez → aynı bayt, komut 1 kez; başka IP önbelleği paylaşmıyor, 10 sn'de düşüyor;
  - log'da anahtar/imza/nonce yok;
  - config: üretilip korunuyor, diskte açık anahtar yok, `reset` başka süreçten görülüyor; log 7 günden eskiyi siliyor.
- `./gradlew :app:testDebugUnitTest` → **171/171** yeşil (S04 ile +29):
  - `AgentClientTest` (sahte agent, sanal zaman):
    - keşif (iki hedef, tekilleştirme);
    - eşleşme: Paired, K eşit, Denied, ProofInvalid, NoResponse (3 deneme);
    - STATUS: imza + display, sahte imza, `UnknownPhone`, 600 ms bütçe, yeniden denemede aynı bayt;
    - CMD: ACK, **ACK kaybolunca aynı CMD tekrarlanıyor ve komut bir kez çalışıyor**, `force` kuralı, `UNSUPPORTED`, sahte ACK.
  - `NetBiosProbeTest`: sorgu baytları, grup adı atlama, MAC, yanlış işlem kimliği / kısa paket, sıfır MAC.
  - `NicSelectorTest`: K16 sırası, IP'siz kablolu kart, alt ağ hesabı.
  - Vektör testleri yeni `vectors.json` ile.
- **Duman testi (gerçek exe, UDP):**
  - `publish/WolAgent.exe run` bu makinede çalıştırıldı, ayrı bir istemciyle şu akış denendi: DISCOVER → PAIR_HELLO → PAIR_OK (proof ✓) → CHALLENGE → STATUS_OK (imza ✓, `display=on`) → aynı rid tekrar = bayt bayt aynı yanıt ("önbellekten tekrar").
  - Log satırları doğru çıktı.
  - Kurulum (`install`) yönetici gerektirdiği için burada çalıştırılmadı; Kaan'da (#1–#3).
- `assembleDebug` + `assembleRelease` yeşil; APK telefona kuruldu, WoL Debug açılıyor, çökme yok.

### Kontrol raporu — "baktım, tamam; sen de kontrol et"
- [x] İki taraf aynı vektörlerden geçiyor; ayrıca gerçek exe'yle UDP üzerinden eşleşme ve imzalı durum çalıştı → #4–#6.
- [x] Nonce tek kullanım / 10 sn, `BAD_SIG`, TOFU, `BUSY`, K4 tek yürütme testte → #5, #14, #15.
- [x] Güç yeteneği ofsetleri gerçek makinede `powercfg /a` ile tutuyor → #2, #9.
- [x] Log'a gizli bilgi yazılmıyor (testte) → #16.
- [x] **Kaan: #1–#7 ve #9–#17 geçti** (2026-10-29). #13 NetBIOS: PC açıkken 7 ms; kapalı PC'de "Yanıt yok" beklenen davranış.
- [x] **#8 Uyku:** Kaan'ın PC'sinde (Modern Bekleme) `SetSuspendState(false)` PC'yi **hazırda bekletti**. Log: ACK 14:55:16, "SLEEP uygulandı" 14:56:58; çağrı PC uyanınca dönüyor. Ağ sekmesindeki "Uyandır" ile açıldı.
- [x] **#8a–#8c (K22) tutmadı:**
  - Elle uyutulan PC bir kez STATUS'a cevap verdi; derin beklemede hiç cevap vermedi.
  - `SCREEN_ON` 3 kez ACK aldı, "uygulandı" log'a düştü, ama ekran açılmadı.
  - Sonuç: K22'nin uyandırma kısmı kapandı, `SCREEN_ON` kaldırıldı.
- [x] **K23 (Kaan'ın kararı):** Modern Beklemeli PC'de telefondan Uyku = Hazırda Beklet. Aşağıdaki K23 eklerine bak.

### Cihaz testi sonrası (K22 sonuç, K23)
- **Kaldırıldı:**
  - `SCREEN_ON` komutu: protokol, agent yürütücüsü ve yardımcı modu, Android enum'u, Debug butonu, vektörler.
  - Debug'daki "Uzun bekle" anahtarı.
- **Eklendi:**
  - `DISCOVER_REPLY.sleepHibernates` (Modern Bekleme + S3 yok + hazırda bekletme açık).
  - Agent bu PC'de SLEEP'i doğrudan `SetSuspendState(true,…)` ile uyguluyor; artık Windows'un yorumuna kalmıyor.
  - `status` çıktısında "→ telefondan Uyku = Hazırda Beklet (K23)" satırı; Debug kartında "Uyku = Hazırda (K23)".
- **Kaldı:** `STATUS_OK.display` (imzalı, bilgi amaçlı).
- **Testler:** `dotnet test` **65/65** (SCREEN_ON vektörleri çıktı; `SCREEN_ON` artık `UNSUPPORTED` testi var). Android **166/166**.

### Açık kalanlar
- **K23 doğrulaması:** agent'ı yeni exe ile güncelle; Uyku → hazırda bekletme → Ağ › Uyandır (sohbetteki kısa liste).
- **"Hazırda" gösterimi** (lastAction, K9 + K23): S05/S06.
- **Asıl eşleşmenin Device'a yazılması** (agentId, KeyVault alias, hazırda bekletme, MAC'in K16 ile doldurulması): S07 (cihaz paneli). Debug şimdilik tek eşleşmeyi kendi tercihlerinde tutuyor.
- S1/S2'nin tur içinde birleştirilmesi: S05 (StatusEngine).
