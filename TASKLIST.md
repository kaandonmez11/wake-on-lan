# TASKLIST — WoL Denetim Merkezi

Ayrıntılar `sprints/` altında; kurallar `sprints/README.md`. Claude bir sprinti bitirince kendi kutularını işaretler; **"Cihaz testi"** kutusunu Kaan testleri geçince işaretler (ya da Claude'a "S0X cihaz testi geçti" der).

| Sprint | Spec fazı | Konu | Durum |
|---|---|---|---|
| S00 | 0 | Monorepo, protokol, vektörler, saf kripto | ✅ |
| S01 | 1 | Android iskeleti + tasarım sistemi | ✅ |
| S02 | 2 | Veri katmanı | ✅ |
| S03 | 3 | Ağ çekirdeği | ✅ |
| S04 | 4 | Windows agent + AgentClient + NetBIOS | ✅ |
| S05 | 5 | StatusEngine + koordinatörler | 🧪 cihaz testi bekliyor |
| S06 | 6 | Cihazlar ekranı (+ Windows güç ikonları) | ☐ |
| S07 | 7 | Cihaz paneli + tarama | ☐ |
| S08 | 8a | Otomasyon 1: zaman tetikli | ☐ |
| S09 | 8b | Otomasyon 2: retry, başarısız ekranı, eve gelince | ☐ |
| S10 | 9 | Ayarlar | ☐ |
| S11 | 10 | Tam ekran kapılar + onboarding | ☐ |
| S12 | 11 | Hızlı Ayarlar tile'ları | ☐ |
| S13 | 12 | Cila, performans, erişilebilirlik, uygulama ikonu | ☐ |

---

## S00 — Monorepo, protokol, saf kripto
- [x] Android projesini `android/` altına taşı, `.idea` takipten çık, `.gitignore`
- [x] compile/target 37, minSdk 35, Kotlin/Compose/KSP/Hilt/JUnit5/Robolectric, `allowBackup=false`
- [x] `WolApp` + boş `MainActivity`
- [x] Debug: `DebugActivity` + "WoL Debug" launcher
- [x] `protocol/PROTOCOL.md` (K4, K5, K6 dahil)
- [x] `agent/tools/VectorGen` + deterministik `protocol/vectors.json`
- [x] `agent/` çözümü + C# kripto (BouncyCastle X25519, HKDF, HMAC, canonical) + xUnit vektör testleri
- [x] Kotlin: Hkdf, Hmac, X25519, AgentCodec, MagicPacket, BroadcastResolver, MacAddress + testler
- [x] Debug › Vektörler sekmesi
- [x] Kök README
- [x] Testler yeşil · Sprint sonu raporu · commit
- [x] **Cihaz testi (Kaan)**

## S01 — Tasarım sistemi
- [x] Fontlar (Onest, JetBrains Mono, Material Symbols) + lisanslar
- [x] theme: Color, Type, Motion, Haptics, Glass, Symbols, köşeler
- [x] Basma modifier'ı, RollingNumber, durum metni kalıbı
- [x] GlassSurface (normal/güçlü/dock)
- [x] LivingBackground (AGSL) + sahneler + morph + sürekli mod
- [x] FloatingNavBar + AppNav + iki boş sekme + sekme geçişi
- [x] Edge-to-edge, animasyonları kaldır davranışı
- [x] Debug › Tasarım sekmesi (vitrin, spring, haptik, sahne, FPS)
- [x] Testler yeşil · animasyon denetimi · rapor · commit
- [x] **Cihaz testi (Kaan)**

## S02 — Veri katmanı
- [x] Room entity'leri + `DeviceStateCache` + converters + FK cascade
- [x] DAO'lar (sıra, son 12, retention 100)
- [x] DeviceRepository / ScheduleRepository + doğrulamalar
- [x] SettingsStore (DataStore)
- [x] KeyVault (Keystore AES-GCM)
- [x] Hilt modülleri
- [x] Debug › Veri sekmesi
- [x] Testler yeşil · rapor · commit
- [x] **Cihaz testi (Kaan)**

## S03 — Ağ çekirdeği
- [x] Manifest izinleri
- [x] NetworkMonitor + WifiState
- [x] Wi-Fi'a bind eden SocketFactory, MulticastLock
- [x] WolSender (0/2/5 sn, broadcast hedefleri)
- [x] TcpProbe (S3), IcmpProbe (S4)
- [x] Yerel ağ + konum izin akışı
- [x] Debug › Ağ sekmesi (Wi-Fi kartı, Uyandır, probe'lar)
- [x] Doğrulanacaklar raporu · testler yeşil · commit
- [x] **Cihaz testi (Kaan)** (#5 PC'nin Modern Bekleme'si yüzünden olmadı, uygulama kaynaklı değil)

## S04 — Agent + AgentClient
- [x] Agent: Worker + CLI (install/uninstall/reset/status)
- [x] Config (DPAPI, ACL) + log
- [x] UDP sunucu, kaynak filtresi, tüm mesajlar, rid önbelleği (K4)
- [x] K22: `STATUS.display` + Modern Bekleme denemesi (uyandırma tutmadı, `SCREEN_ON` kaldırıldı) · K23: Uyku = Hazırda Beklet
- [x] Komut yürütücü (5 komut, 300 ms, BUSY, UNSUPPORTED)
- [x] Firewall kuralı, servis recovery, güncelleme modu
- [x] xUnit testleri + `dotnet publish` tek exe
- [x] Android: AgentClient (discovery, pairing, STATUS, CMD, retry, K6)
- [x] NetBiosProbe (S2), NIC seçimi (K16)
- [x] Debug › Agent sekmesi
- [x] Doğrulanacaklar raporu · testler yeşil · commit
- [x] **Cihaz testi (Kaan)** (PC + telefon) — 8a–8c (K22) tutmadı, K23 ile kapatıldı

## S05 — StatusEngine + koordinatörler
- [x] Domain modelleri (K9 dahil)
- [x] StatusEngine (kurallar 1–6, K8, tur sıklıkları, yaşam döngüsü)
- [x] DeviceStateCache yazma/okuma (K7)
- [x] WakeCoordinator
- [x] PowerCoordinator (K12)
- [x] IP yeniden çözümleme + agent bağlantı denemesi + MAC doldurma
- [x] Debug › Durum sekmesi
- [x] Domain testleri yeşil · rapor · commit
- [ ] **Cihaz testi (Kaan)**

## S06 — Cihazlar ekranı
- [ ] Windows güç ikonları (SegoeIcons.ttf → vector) + önizleme PNG → **Kaan onayı**
- [ ] Başlık: WifiPill + ayarlar balonu
- [ ] Hero kart + çipler
- [ ] PowerOrb + HoldButton + haptikler
- [ ] Güç satırı (K12)
- [ ] Adım kartları, geçmiş çubukları
- [ ] Cihaz kartları + sürekli efektler + shared-element + seçim halkası
- [ ] Boş durum, olay toast'ları, arka plan tonu
- [ ] Compose UI testleri yeşil · animasyon denetimi · rapor · commit
- [ ] **Cihaz testi (Kaan)**

## S07 — Cihaz paneli + tarama
- [ ] NetworkScanner + MdnsResolver + ikon tahmini
- [ ] DeviceSheet (sürükle-kapat, scrim+blur)
- [ ] Önizleme, Ağda bulunanlar, form, IconPicker, ColorPicker
- [ ] Alt bar, ipuçları, Sil onayı, doğrulamalar
- [ ] Kaydet akışı + otomatik eşleşme
- [ ] "+", boş durum, kalem bağlantıları
- [ ] Testler yeşil · animasyon denetimi · rapor · commit
- [ ] **Cihaz testi (Kaan)**

## S08 — Otomasyon 1
- [ ] ScheduleEngine (saf) + metinler
- [ ] AlarmScheduler + ScheduleAlarmReceiver + VerifyWorker
- [ ] ScheduleRunner tek deneme (K12)
- [ ] BootReceiver
- [ ] AutomationScreen + kartlar + GradientSwitch
- [ ] ScheduleEditorSheet (TIME)
- [ ] Debug › Zamanlayıcı sekmesi
- [ ] Testler yeşil · rapor · commit
- [ ] **Cihaz testi (Kaan)**

## S09 — Otomasyon 2
- [ ] Retry zinciri (K13) + attemptsJson + NO_WIFI
- [ ] Notifier + bildirim kanalı
- [ ] ScheduleFailureScreen (push + predictive back)
- [ ] Kartta başarısız satırı
- [ ] WIFI_CONNECT (PendingIntent callback, 10 dk kilit, skipNextEvent)
- [ ] Debug › Zamanlayıcı genişletmesi
- [ ] Doğrulanacaklar raporu · testler yeşil · commit
- [ ] **Cihaz testi (Kaan)**

## S10 — Ayarlar
- [ ] SettingsScreen (push + predictive back)
- [ ] İzinler grubu + canlı yenileme
- [ ] NumberStepperField ×2
- [ ] Kapatma şekli segmentleri
- [ ] Tile slotları + mod
- [ ] Titreşim, ipucu, hakkında/lisanslar
- [ ] Testler yeşil · rapor · commit
- [ ] **Cihaz testi (Kaan)**

## S11 — Kapılar + onboarding
- [ ] NoWifiScreen (halkalar, bilgi kartı)
- [ ] NoPermissionScreen
- [ ] Kapı yönetimi (giriş/çıkış animasyonu, öncelik)
- [ ] Onboarding 3 sayfa
- [ ] Testler yeşil · rapor · commit
- [ ] **Cihaz testi (Kaan)**

## S12 — Tile'lar
- [ ] WolTileService1..3 + TileBinder
- [ ] Tile metinleri/state eşlemesi, onStartListening turları
- [ ] Kapalıya dokun → uyandır (kilit ekranında) + VerifyWorker
- [ ] Mod A TilePowerActivity, Mod B dialog, Mod C çift dokunuş
- [ ] Doğrulanacaklar raporu · testler yeşil · commit
- [ ] **Cihaz testi (Kaan)**

## S13 — Cila
- [x] Uygulama ikonu — S00'da Kaan'ın kaynaklarıyla yapıldı (S13'te yalnız debug ikonu bandı kaldı)
- [ ] Animasyon denetim tablosu (her ekran × durum)
- [ ] Animasyonları kaldır davranışı (tüm ekranlar)
- [ ] Baseline profile + Macrobenchmark
- [ ] Erişilebilirlik + kontrast raporu
- [ ] Release build + R8 + imzalama (**Kaan'a sor**)
- [ ] Temizlik, README
- [ ] Testler + lint yeşil · rapor · commit
- [ ] **Cihaz testi (Kaan)** — spec 16 elle test listesi dahil
