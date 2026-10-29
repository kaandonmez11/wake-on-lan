# S04 — Windows agent v1 + AgentClient + NetBIOS

**Spec:** 7.3 (S1, S2), 8 (tamamı), 13, 16 (C#), 17 faz 4 · **Kararlar:** K3, K4, K5, K6, K16, K19, K22

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
Hazırlık: `agent/publish/WolAgent.exe`'yi PC'ye kopyala.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | PC'de **yönetici** komut isteminde `WolAgent.exe install` | "Kuruldu" benzeri çıktı; `services.msc`'de "WoL Denetim Merkezi agent" Çalışıyor / Otomatik. |
| 2 | `wolagent status` | Servis çalışıyor, agentId, "Eşleşmemiş", port 40900, hibernate destekli mi, NIC listesi. |
| 3 | Windows Defender Güvenlik Duvarı › Gelen kuralları | UDP 40900, tüm profiller, uzak adres "Yerel alt ağ" kuralı var. |
| 4 | Telefonda WoL Debug › Agent › "Ara" | PC'nin adı, NIC'leri listelenir; kablolu Ethernet MAC'i işaretli; paired=false. |
| 5 | "Eşleş" | "Eşleşti ✓". `wolagent status` artık telefonun adını/eşleşmiş gösterir. |
| 6 | "Durum" | Uptime ve hostname gelir, "imza ✓". |
| 7 | "Kilitle" | PC kilit ekranına düşer; telefonda "ACK ✓". |
| 8 | "Uyku" | PC uykuya (Modern Bekleme) geçer, ekran kapanır. Telefonda "ACK ✓". |
| 8a | PC uykudayken (30 sn bekle) "Durum" | **K22 denemesi:** cevap gelir ve `display: kapalı` görünür. Gelmezse bana yaz (bu yol kapanır). |
| 8b | PC uykudayken "Ekranı aç" | PC uykudan çıkar, kilit ekranı görünür; "Durum" artık `display: açık`. |
| 8c | PC'yi Başlat › Uyku ile uyut, 2 dk bekle → "Durum", sonra "Ekranı aç" | 8a ve 8b'deki gibi (uzun beklemede de agent ulaşılabilir). |
| 9 | "Hazırda" | Hibernate açıksa PC hazırda bekletmeye geçer; kapalıysa telefonda `UNSUPPORTED` mesajı. |
| 10 | PC'de Not Defteri'ne kaydetmeden yazı yaz → **Nazik** + "Kapat" | Windows kapanmayı bekletir/engeller ("Kaydet?" ekranı); PC açık kalır. |
| 11 | Aynı durumda **Zorla** + "Kapat" | PC kaydetmeden kapanır. |
| 12 | PC açıkken "Yeniden" | PC yeniden başlar, servis kendiliğinden tekrar çalışır (`wolagent status`). |
| 13 | "NetBIOS" | PC'nin hostname'i ve MAC'i gelir (NetBIOS kapalıysa yanıt yok — bana yaz). |
| 14 | Telefonda uygulamayı kaldır-kur, "Eşleş" | "Başka telefona eşli · PC'de `wolagent reset` çalıştır". |
| 15 | PC'de `wolagent reset`, telefonda tekrar "Eşleş" | "Eşleşti ✓". |
| 16 | `%ProgramData%\WolAgent\logs\` dosyasını aç | Her istek tek satır; anahtar/imza görünmüyor. |
| 17 | `wolagent uninstall --purge` | Servis, firewall kuralı, `Program Files\WolAgent` ve `ProgramData\WolAgent` silinmiş. Sonra tekrar `install` + eşleş (sonraki sprintler için). |

## Bitti tanımı
İki tarafın testleri yeşil; doğrulanacaklar raporlandı; cihaz testi #1–17 geçti (8a–8c K22 denemesi: sonucu ne olursa olsun raporlanır).

## Sprint sonu raporu
_(Claude doldurur)_
