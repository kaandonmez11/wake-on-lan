# WoL Denetim Merkezi — Proje Spec'i

> Tek kaynak doküman. Claude Code bu dosyayı okuyup projeyi baştan sona kurar.
> Arayüz dili **Türkçe**; kod, paket, sınıf, dosya adları **İngilizce**.
> Sahibi ve tek kullanıcısı: Kaan. Store'a çıkmıyor, sideload.

---

## 0. Claude Code için çalışma kuralları

1. **Fazlara sadık kal** (bölüm 17). Bir fazı bitirmeden sonrakine geçme. Her faz sonunda: derleme yeşil, testler yeşil, kabul kriterleri sağlanmış olmalı.
2. **Animasyon şartı pazarlık konusu değil** (bölüm 12). Animasyonsuz durum değişimi, ekran geçişi ya da görünüp kaybolan öğe = **bug**. Her UI fazının kabul kriterlerinde ayrıca kontrol edilir.
3. **Tek kaynak doğruluk:** cihaz durumu yalnız `StatusEngine`'de hesaplanır. UI, tile ve bildirimler sadece okur.
4. Bu spec'te "doğrulanacak" diye işaretli noktaları koda geçmeden önce resmi dokümandan kontrol et, sonucu commit mesajında belirt.
5. Spec ile çelişen bir teknik engel çıkarsa **sessizce değiştirme**: dur, engeli ve önerini yaz.
6. Görsel referans Kaan'daki tasarım canvas'ı. Bu doküman ondaki her şeyi ölçüleriyle tarif eder; canvas'a erişimin yoksa bu doküman yeterlidir.
7. **Bölüm 19 (Karar günlüğü) bu dokümanın geri kalanıyla çelişirse bölüm 19 geçerlidir.** Fazlar `sprints/` altındaki sprint dosyalarıyla yürütülür (bölüm 17 yerine).

---

## 1. Ürün özeti

Yerel ağdaki Windows bilgisayarları:
- **Wake-on-LAN** ile uyandıran,
- yanlarında çalışan küçük bir **agent** üzerinden **kilitleyen / uyutan / hazırda bekleten / kapatan / yeniden başlatan**,
- durumlarını (açık, kapalı, uyanıyor, kapanıyor) **çoklu sinyalle tutarlı** gösteren,
- **zamanlayıcıyla** otomatik açıp kapatan,
- **Hızlı Ayarlar (Quick Settings) tile'larından** kontrol edilen,

native bir Android uygulaması + Windows servisi.

**Temel ilkeler**
- Her şey yerel ağda. İnternet, hesap, backend yok. Wake-on-WAN yok.
- Telefonun **Wi-Fi'a bağlı olması şart** (internet şart değil). Wi-Fi yoksa tam ekran bilgi ekranı.
- Agent **aptal bir komut alıcı**: kendi zamanlayıcısı, kararı, PC'de penceresi/uyarısı yok. Sadece telefondan gelen imzalı komutu uygular ve durum bildirir.
- Görsel olarak üst düzey: koyu "Grafit" cam tema, Apple tarzı fizik tabanlı animasyonlar.

**Kapsam dışı (bilinçli):** gruplar, CSV import/export, ana ekran widget'ı, internet üzerinden uyandırma, durum geçmişi/istatistik ekranı, çoklu telefon, Play Store.

---

## 2. Hedef ortam

| Konu | Değer |
|---|---|
| Telefon | Samsung Galaxy S24, One UI 9, **Android 17 (API 37)** |
| `compileSdk` / `targetSdk` | 37 / 37 |
| `minSdk` | 35 (tek cihaz; eski API yedekleri yazılmayacak) |
| PC | Windows 10/11 x64, kablolu Ethernet, BIOS'ta WoL açık |
| Ekran | 120 Hz; animasyonlar 120 fps'te akıcı olmalı |

---

## 3. Monorepo yapısı

```text
/
├─ SPEC.md                     ← bu dosya
├─ protocol/
│  ├─ PROTOCOL.md              ← agent protokolünün normatif tarifi (bölüm 8'den üretilir)
│  └─ vectors.json             ← Kotlin ve C# testlerinin ORTAK test vektörleri
├─ android/                    ← Gradle projesi (tek :app modülü)
└─ agent/                      ← .NET 10 çözümü
   ├─ WolAgent/                ← Worker Service + CLI (tek exe)
   └─ WolAgent.Tests/          ← xUnit
```

`protocol/vectors.json` ile hem Android hem agent aynı bayt dizilerini üretip doğrulamak zorunda. Protokol tutarlılığının garantisi bu dosya.

---

## 4. Android teknoloji yığını

| Alan | Seçim | Not |
|---|---|---|
| Dil | Kotlin, coroutines + Flow | UI durumu `StateFlow` |
| UI | Jetpack Compose, Material 3 yalnız temel bileşenler için | Görünüm tamamen özel tasarım sistemi (bölüm 11) |
| Mimari | MVVM, tek yönlü veri akışı, Hilt | |
| Depolama | Room + DataStore (Preferences) | |
| Kripto | Android Keystore (AES-GCM sarmalama), `java.security` X25519, HKDF-SHA256 (küçük dahili impl.), HMAC-SHA256 | |
| Arka plan | `AlarmManager` (exact), `BroadcastReceiver`, `goAsync()`, expedited `WorkManager` | Foreground service yok |
| Ağ | `DatagramSocket`, `Socket`, `ConnectivityManager`, `NsdManager` | Tüm soketler Wi-Fi `Network`'üne bind edilir |
| Cam/blur | Haze (`dev.chrisbanes.haze`) | Sürümü proje başında güncel stabile çek (doğrulanacak) |
| Arka plan efekti | AGSL `RuntimeShader` | Bölüm 12.4 |
| Fontlar | **Onest** (arayüz, 500/600/700/800) + **JetBrains Mono** (teknik metin, 500/600/700) | İkisi de OFL; `res/font` içine gömülü |
| İkonlar | **Material Symbols Outlined** (değişken font + gerektiğinde vector drawable) + Windows güç ikonları için özel SVG | Bölüm 11.6 |
| Test | JUnit5, Turbine, Compose UI test, Robolectric gerekirse | |

---

## 5. Android paket yapısı

```text
app/src/main/java/.../wol/
  data/
    db/          AppDatabase, DeviceDao, WakeAttemptDao, ScheduleDao, ScheduleRunDao, entities, converters
    repo/        DeviceRepository, ScheduleRepository
    prefs/       SettingsStore (DataStore)
    crypto/      KeyVault (Keystore sarmalama), X25519, Hkdf, Hmac
  net/
    NetworkMonitor.kt       Wi-Fi Network takibi, LinkProperties, SSID, RSSI, linkSpeed, telefon IP'si
    MagicPacket.kt          saf fonksiyon
    BroadcastResolver.kt    saf fonksiyon
    WolSender.kt            UDP gönderimi (Wi-Fi'a bind)
    probes/
      TcpProbe.kt           S3
      IcmpProbe.kt          S4 (/system/bin/ping)
      NetBiosProbe.kt       S2 (NBSTAT, UDP 137)
    agent/
      AgentCodec.kt         mesaj kodlama/çözme (saf)
      AgentClient.kt        discovery, pairing, challenge/command, status
    scan/
      NetworkScanner.kt     birleşik tarama pipeline'ı
      MdnsResolver.kt
  domain/
    model/       DeviceState, Signal, PowerAction, ...
    StatusEngine.kt         çoklu sinyal füzyonu + durum makinesi (tek kaynak)
    WakeCoordinator.kt      uyandırma akışı
    PowerCoordinator.kt     agent komut akışı
    ScheduleEngine.kt       sonraki çalışma, atlama (saf)
    ScheduleRunner.kt       zamanlayıcı çalıştırma + retry
  work/
    AlarmScheduler.kt, ScheduleAlarmReceiver.kt, RetryAlarmReceiver.kt,
    BootReceiver.kt, WifiTriggerReceiver.kt, VerifyWorker.kt, Notifier.kt
  tile/
    WolTileService1.kt, WolTileService2.kt, WolTileService3.kt, TileBinder.kt
    TilePowerActivity.kt    (seçenek A overlay'i)
  ui/
    theme/       Color, Type, Motion (spring token'ları), Haptics, Glass, Symbols
    background/  LivingBackground (AGSL), Scenes
    components/  GlassSurface, PowerOrb, HoldButton, DeviceHeroCard, DeviceCard, StepRow,
                 HistoryBars, GradientSwitch, Chip, WifiPill, IconPicker, ColorPicker,
                 NumberStepperField, SymbolIcon, WinPowerIcon
    devices/     DevicesScreen + ViewModel
    automation/  AutomationScreen + ViewModel, ScheduleEditorSheet
    failure/     ScheduleFailureScreen
    sheet/       DeviceSheet (ekle/düzenle) + ViewModel
    settings/    SettingsScreen + ViewModel
    gate/        NoWifiScreen, NoPermissionScreen
    nav/         AppNav, FloatingNavBar
```

Ağ ve kripto kodu Android'den bağımsız (soket üreticisi, saat ve rastgelelik enjekte edilir) ki birim testlenebilsin.

---

## 6. Veri modeli (Room)

Canlı durum (açık/kapalı/uyanıyor) **saklanmaz**, `StatusEngine` hesaplar. Saklananlar:

```kotlin
@Entity(indices = [Index(value = ["mac"], unique = true)])
data class Device(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val name: String,                 // trim, 1..32
  val ip: String,                   // son bilinen IPv4
  val mac: String?,                 // "AA:BB:CC:DD:EE:FF" büyük harf; null = "MAC bekleniyor"
  val hostname: String? = null,     // agent/NetBIOS/mDNS'ten
  val iconKey: String,              // bölüm 11.6 tablosu
  val colorIndex: Int,              // 0..7
  val probePorts: List<Int> = listOf(3389, 445, 135),
  val sortOrder: Int = 0,
  // agent
  val agentId: String? = null,      // UUID; null = agent yok/eşleşmedi
  val agentKeyAlias: String? = null,// Keystore'daki sarmalanmış anahtarın takma adı
  val agentPort: Int = 40900,
  val hibernateAvailable: Boolean = false,
  val shutdownMode: ShutdownMode = ShutdownMode.FORCE  // FORCE | GRACEFUL
)

@Entity
data class WakeAttempt(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val deviceId: Long,               // FK cascade
  val startedAt: Long,
  val success: Boolean,
  val durationSec: Int?,
  val source: AttemptSource         // MANUAL | SCHEDULE | TILE
)

@Entity
data class Schedule(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val deviceId: Long,               // FK cascade
  val action: PowerAction,          // WAKE | LOCK | SLEEP | HIBERNATE | SHUTDOWN | RESTART
  val type: ScheduleType,           // TIME | WIFI_CONNECT
  val daysMask: Int = 0,            // TIME: bit0=Pzt .. bit6=Paz
  val minuteOfDay: Int? = null,     // TIME: 0..1439
  val ssid: String? = null,         // WIFI_CONNECT
  val enabled: Boolean = true,
  val skippedOccurrence: Long? = null,
  val skipNextEvent: Boolean = false
)

@Entity
data class ScheduleRun(
  @PrimaryKey(autoGenerate = true) val id: Long = 0,
  val scheduleId: Long,             // FK cascade
  val occurrence: Long,             // planlanan zaman (WIFI için tetiklenme anı)
  val startedAt: Long,
  val endedAt: Long?,
  val result: RunResult,            // RUNNING | SUCCESS | FAILED | SKIPPED_ALREADY | SKIPPED_BY_USER
  val attemptsJson: String          // [{at, outcome, reason}] — bölüm 9.4
)
```

**DataStore (ayarlar):** `retryIntervalMin` (varsayılan 2, aralık 1–30), `giveUpMin` (varsayılan 10, aralık 2–120, `≥ retryIntervalMin`), `hapticsEnabled` (true), `tileSlots` (3 slot → deviceId?), `tileMode` (A | B | C, varsayılan A), `phoneId` (UUID, ilk açılışta), `selectedDeviceId`.

**Türetilenler:** `history` = son 12 `WakeAttempt` (eskiden yeniye); `reliability` = başarılı/toplam; hiç deneme yoksa "yeni".

---

## 7. Ağ katmanı

### 7.1 NetworkMonitor
- `ConnectivityManager.registerNetworkCallback` ile **TRANSPORT_WIFI** ağını takip eder (`FLAG_INCLUDE_LOCATION_INFO` ile; SSID için).
- Yayınladığı `StateFlow<WifiState>`: `connected`, `network: Network?`, `ssid?`, `rssi`, `signalLevel` (0–4, `WifiManager.calculateSignalLevel`), `linkSpeedMbps`, `phoneIp`, `prefixLength`, `broadcast`.
- **Wi-Fi yoksa** (`connected=false`) uygulama tam ekran `NoWifiScreen` gösterir (bölüm 10.6). İnternet olup olmaması önemsiz.
- Mobil veri açık olsa da tüm soketler `wifiNetwork.bindSocket(socket)` ile Wi-Fi'a zorlanır. Bu kural istisnasızdır (WoL, probe, agent, tarama).
- mDNS ve broadcast alımı için tarama sırasında `WifiManager.MulticastLock` alınır.

### 7.2 Magic packet
- 102 bayt: 6 × `0xFF` + MAC × 16.
- Hedefler: **subnet broadcast** (ör. `192.168.1.255`) port 9 ve 7, ayrıca `255.255.255.255` port 9. Unicast yok (uyuyan cihaz ARP'ye yanıt vermez).
- Gönderim: t=0, t=2 sn, t=5 sn (3 kez).
- `MagicPacket.build(mac)` ve `BroadcastResolver.compute(ip, prefix)` saf fonksiyon; ilk testler bunlara.

### 7.3 Sinyaller (probe'lar)
| Sinyal | Yöntem | Zaman aşımı | Kimlik doğrular mı |
|---|---|---|---|
| **S1 Agent** | Bölüm 8 `STATUS` (imzalı) | 600 ms | Evet (agentId + HMAC) |
| **S2 NetBIOS** | UDP 137 NBSTAT sorgusu; yanıttan hostname + MAC | 500 ms | Evet (MAC eşleşmesi) |
| **S3 TCP** | `probePorts`'a paralel connect; bağlanma **veya** "connection refused" = canlı | 400 ms | Hayır |
| **S4 ICMP** | `/system/bin/ping -c 1 -W 1 <ip>` (varlığını kontrol et; yoksa S4 devre dışı) | 1000 ms | Hayır |

Not: Windows firewall ICMP'yi varsayılan olarak bloklar; S4 tek başına asla "kapalı" kararı veremez.

### 7.4 Tarama (cihaz ekleme paneli)
Hepsi paralel, sonuçlar **MAC → yoksa IP** anahtarıyla birleşir:
1. **Agent discovery** broadcast (bölüm 8.3). Agent'lı PC'ler hostname + MAC + paired bilgisiyle döner.
2. **/24 sweep**: 254 adres, ≤64 eşzamanlı, TCP portları 445, 3389, 22, 80, 443, 5000, 8080, 8001, 8002 (300 ms).
3. Canlılara **NetBIOS** → hostname + MAC.
4. **mDNS** (`_workstation._tcp`, `_smb._tcp`) ve ters DNS → isim.
5. İkon tahmini: agent/3389 → `pc`; 5000+445 → `nas`; 8001/8002 → `tv`.

Liste satırı: ad · IP · MAC (yoksa "MAC bulunamadı") · rozet ("Agent" yeşil / "Agent yok"). Zaten ekli cihaz **"Ekli"** rozetiyle soluk ve seçilemez gösterilir. Panel kapanınca tarama iptal edilir.

### 7.5 IP değişimi
- Uygulama ön plandayken her 60 sn ve her açılışta bir `DISCOVER` broadcast'i atılır; agentId eşleşen cihazın IP'si değiştiyse sessizce güncellenir.
- S2 bir IP'de **farklı MAC** görürse: o IP'de başka cihaz var demektir. Cihaz "açık" sayılmaz, IP güncellemesi için discovery tetiklenir.
- Ayarlar'da kısa ipucu: "Router'da bu PC'lere sabit IP (DHCP rezervasyonu) vermen önerilir."

---

## 8. Agent protokolü (normatif)

> `protocol/PROTOCOL.md` bu bölümden üretilir. `protocol/vectors.json` her mesaj türü için örnek girdi/çıktı ve HMAC değerlerini içerir.

### 8.1 Taşıma
- **UDP 40900**, IPv4. Her mesaj tek datagram, UTF-8 **JSON**, en fazla 1200 bayt.
- Agent yalnız **RFC1918 / link-local** kaynaklı paketleri işler, diğerlerini sessizce atar.
- Ortak zarf: `{"v":1,"t":"<TYPE>","rid":"<istek id, 8 bayt hex>", ...}`. Yanıt aynı `rid`'i taşır.
- Telefon her istekte en fazla 2 kez yeniden dener (200 ms ara).

### 8.2 Kripto
- Her agent kurulumda bir **X25519** anahtar çifti üretir ve saklar.
- Eşleşmede ortak sır: `X25519(phonePriv, agentPub)`.
- Oturum anahtarı: `K = HKDF-SHA256(ikm = sır, salt = agentId‖phoneId (UTF-8), info = "wol-pair-v1", L = 32)`.
- Bütün imzalar: `HMAC-SHA256(K, canonical)`; `canonical` = alanların **sabit sırayla** `|` ile birleşimi (her mesaj için aşağıda). Base64 (padding'li) ile taşınır.
- Telefonda `K` Keystore'daki AES-GCM anahtarıyla sarmalanıp saklanır; Auto Backup'tan hariç tutulur.

### 8.3 Mesajlar

**DISCOVER** (broadcast, imzasız)
`{"t":"DISCOVER","rid"}` → `{"t":"DISCOVER_REPLY","rid","agentId","hostname","agentVersion","paired":bool,"hibernate":bool,"sleepHibernates":bool (K23),"nics":[{"mac","name","wired":bool,"ip"}]}`

**Eşleşme — TOFU** (yalnız `paired=false` iken)
1. `{"t":"PAIR_HELLO","rid","phoneId","phoneName","phonePub"}`
2. Agent eşleşmemişse: `K`'yı türetir, phoneId + phonePub'ı kalıcı yazar, `paired=true` olur ve
   `{"t":"PAIR_OK","rid","agentId","agentPub","proof"}` döner; `proof = HMAC(K, "pair-ok|"+phoneId+"|"+agentId)`.
3. Telefon `proof`'u doğrular, `K`'yı saklar. Doğrulanmazsa eşleşmeyi atar.
4. Agent zaten eşleşmişse: `{"t":"PAIR_DENIED","rid"}`.
- Sıfırlama yalnız PC'de: `wolagent.exe reset`.

**Challenge**
`{"t":"CHALLENGE","rid","phoneId"}` → `{"t":"NONCE","rid","nonce"}` (16 bayt, base64; tek kullanımlık, 10 sn geçerli). Bilinmeyen phoneId → `{"t":"ERR","rid","code":"UNKNOWN_PHONE"}`.

**STATUS** (S1 sinyali)
`{"t":"STATUS","rid","phoneId","nonce","sig"}`; `sig = HMAC(K, "status|"+nonce)`
→ `{"t":"STATUS_OK","rid","agentId","hostname","uptimeSec","hibernate":bool,"display","nics":[...],"sig"}`; `sig = HMAC(K, "status-ok|"+nonce+"|"+agentId+"|"+uptimeSec+"|"+display)` (K22: `display` eklendi).

**CMD**
`{"t":"CMD","rid","phoneId","nonce","cmd","force":bool,"sig"}`
- `cmd ∈ {LOCK, SLEEP, HIBERNATE, SHUTDOWN, RESTART}` (K23: Modern Beklemeli PC'de SLEEP = hazırda bekletme)
- `sig = HMAC(K, "cmd|"+nonce+"|"+cmd+"|"+force)`
→ `{"t":"ACK","rid","cmd","sig"}`; `sig = HMAC(K, "ack|"+nonce+"|"+cmd)`. Agent ACK'i gönderdikten **300 ms sonra** komutu uygular.
Hata kodları: `BAD_SIG`, `NONCE_EXPIRED`, `UNSUPPORTED` (ör. hibernate kapalı), `BUSY`.

### 8.4 Test vektörleri
`vectors.json` en az şunları içerir: sabit X25519 anahtar çiftleriyle `K` türetimi; her imzalı mesaj için `canonical` metni ve beklenen `sig`; magic packet baytları; broadcast hesapları. Kotlin ve C# testleri bu dosyayı okuyup **aynı** sonuçları üretmek zorunda.

---

## 9. Domain mantığı

### 9.1 StatusEngine — çoklu sinyal füzyonu

Her **tur**da cihaz için S1–S4 paralel çalışır (S1 yalnız `agentId` varsa). Karar kuralları, sırayla:

1. **S1 geçerli imzalı yanıt** → `On(verified=true)`. Bitti.
2. **S2 yanıt + MAC eşleşiyor** → `On(verified=true)`.
3. **S2 yanıt + MAC farklı** → bu IP'de başka cihaz var: bu cihaz için **pozitif sayılmaz**; IP yeniden çözümleme tetiklenir.
4. **S3 veya S4 yanıt** (kimlik doğrulanmamış) → `On(verified=false)`. Agent'lı cihazda S1 gelmediyse ek bilgi: `agentReachable=false` (UI: "Agent cevap vermiyor").
5. **Hiçbiri** → negatif tur. **Ardışık 2 negatif tur** (geçiş durumlarında 3) → `Off`.
6. **Bayatlık:** son kesin karar 15 sn'den eskiyse `stale=true` (UI soluk + "kontrol ediliyor"). Uygulama açılışında son bilinen durum `stale` gösterilir, ilk tur (~1 sn) bitince kesinleşir.

Pozitif kanıt güçlü, negatif kanıt zayıftır: tek bir cevap "açık" demek; cevapsızlık ancak tekrarlanınca "kapalı".

**Durum modeli**
```kotlin
sealed interface DeviceState {
  data class On(val pingMs: Int, val verified: Boolean, val agentReachable: Boolean, val stale: Boolean)
  data class Off(val tried: Boolean, val stale: Boolean)           // tried=false → "yeni"
  data class Waking(val step: WakeStep, val elapsedSec: Int)       // PACKET | REPLY | PORT
  data class PowerTransition(val action: PowerAction, val elapsedSec: Int) // Kapanıyor/Uyutuluyor/Yeniden başlıyor
}
// Uygulama seviyesi kapılar: NoWifi, NoLocalNetworkPermission → tam ekran (bölüm 10.6)
```

**Tur sıklığı**
- Uygulama ön planda: 3 sn.
- Geçiş durumlarında (`Waking`, `PowerTransition`): 1 sn.
- Tile paneli açıkken (`onStartListening`): hemen 1 tur, panel açık kaldıkça 3 sn.
- Arka planda sürekli tur **yok**.

Tüm kararlar `StateFlow<Map<Long, DeviceState>>` olarak yayınlanır. UI, tile ve bildirim yalnız bunu okur.

### 9.2 WakeCoordinator (elle uyandırma)
Cihaz başına tek akış; ikinci tetikleme yok sayılır.
1. `Waking(PACKET)`: magic packet t=0/2/5 sn.
2. `Waking(REPLY)`: 1 sn turlar; ilk pozitif sinyalde başarı → `WakeAttempt(success, durationSec)` yazılır. Zaman aşımı **60 sn**.
3. `Waking(PORT)`: `probePorts`'tan biri bağlantı kabul edene ya da agent S1 verene dek (en fazla 30 sn). Dolmazsa adım atlanır, başarı geçerli kalır.
4. 60 sn'de cevap yoksa `WakeAttempt(success=false)`, durum `Off`'a döner ve uyarı: "Uyanmadı · BIOS'ta WoL ve ağ kartında Magic Packet ayarını kontrol et".
- MAC yoksa uyandırma butonu pasif, ipucu: "MAC bekleniyor · PC açıkken otomatik bulunur".
- **Elle uyandırmada otomatik retry yok** (retry yalnız zamanlayıcıda).

### 9.3 PowerCoordinator (agent komutları)
1. `CHALLENGE` → `CMD` (`force` = cihazın `shutdownMode`'u; LOCK/SLEEP/HIBERNATE için false) → `ACK` beklenir (toplam ≤ 2 sn).
2. ACK sonrası:
   - **LOCK**: durum `On` kalır; kısa onay ("Kilitlendi") + haptic.
   - **SLEEP / HIBERNATE / SHUTDOWN**: `PowerTransition(action)`; 1 sn turlar; offline olunca `Off`. 90 sn'de offline olmazsa uyarı: "Kapanmadı" (GRACEFUL modda ek metin: "Açık bir program engelliyor olabilir").
   - **RESTART**: `PowerTransition(RESTART)` → offline → tekrar online (en fazla 180 sn) → `On`.
3. Agent yoksa/eşleşmemişse güç butonları pasif: "Agent bulunamadı". Agent eşli ama cevap vermiyorsa: "Agent cevap vermiyor".
4. Hibernate kapalıysa (`hibernateAvailable=false`) Hazırda Beklet butonu pasif.

### 9.4 Zamanlayıcılar

**Tetikleyiciler:** `TIME` (günler + saat) ve `WIFI_CONNECT` ("eve gelince": belirli SSID'ye bağlanınca; varsayılan SSID = şu anki ağ).

**Aksiyonlar:** `WAKE` (magic packet) veya agent komutları (`LOCK`, `SLEEP`, `HIBERNATE`, `SHUTDOWN`, `RESTART`). **Her şeyi telefon yapar**, agent kendi başına hiçbir şey yapmaz.

**ScheduleEngine (saf)**
- `nextOccurrence(schedule, now)` → TIME için en yakın gelecek gün+saat; WIFI için null.
- "Atla": `skippedOccurrence = nextOccurrence(now)` (WIFI: `skipNextEvent=true`). Tetiklenince eşleşiyorsa çalıştırılmaz → `ScheduleRun(SKIPPED_BY_USER)`, atlama temizlenir, sonraki alarm kurulur.
- Atlama zamanı geçince kendiliğinden temizlenir; anahtar kapatılınca sıfırlanır.
- Buton metni: yarınsa "Yarını atla", bugünse "Bugünü atla" (gece saatleri için "Bu geceyi atla"), diğer günler "Sonrakini atla"; WIFI için "Sonrakini atla". Atlanmışken satır amber ("Yarın atlandı · sonraki Cuma 08:30"), buton "Geri al".

**ScheduleRunner — retry kuralı (yalnız zamanlayıcılarda)**
- Çalışma başlar → `ScheduleRun(RUNNING)`.
- Hedef durum zaten sağlanmışsa (WAKE ama cihaz açık; SHUTDOWN ama kapalı) → `SKIPPED_ALREADY`, sessiz.
- Her **deneme**: Wi-Fi yoksa sonuç `NO_WIFI`; varsa aksiyon uygulanır ve en fazla `min(60 sn, retryInterval)` doğrulanır. Sonuçlar: `OK`, `NO_REPLY` ("Paket gitti · cevap yok"), `AGENT_NO_REPLY`, `NO_WIFI`, `AGENT_ERROR(code)`.
- Başarısızsa bir sonraki deneme `retryIntervalMin` sonra (`AlarmManager` exact), toplam süre `giveUpMin`'i geçince vazgeçilir → `FAILED`.
- Varsayılan: 2 dk arayla, 10 dk → 6 deneme (t=0, 2, 4, 6, 8, 10).
- Her deneme `attemptsJson`'a eklenir: `{at, outcome, reason}`.
- `FAILED` olunca **bildirim**: "Çalışma PC uyanmadı" (aksiyona göre metin), dokununca `ScheduleFailureScreen`. Başarıda bildirim yok.

**Arka plan altyapısı**
- `USE_EXACT_ALARM` (sideload; kurulumda verilir) ile `setExactAndAllowWhileIdle`. Yine de `canScheduleExactAlarms()` kontrol edilir; false ise Ayarlar'da kırmızı satır.
- Alarm/receiver işi `goAsync()` ile paketleri hemen gönderir; doğrulama expedited `VerifyWorker`'da.
- `BootReceiver`: `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED`, `TIME_SET`, `TIMEZONE_CHANGED` → tüm alarmlar ve devam eden retry zincirleri yeniden kurulur.
- `WIFI_CONNECT`: `registerNetworkCallback(request, PendingIntent)`; SSID eşleşirse çalışır; aynı zamanlayıcı 10 dk içinde tekrar tetiklenmez. (Android 17'de PendingIntent callback'inde SSID okuma koşulları **doğrulanacak**.)
- Samsung pil kısıtlaması: Ayarlar'da "Pil · Kısıtlanmamış olmalı" satırı; `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` ile istek.

---

## 10. Ekranlar ve davranışlar

Ölçüler dp; referans genişlik 390. Yatay kenar 16, bileşen arası 14, üst güvenli alan + 12. Edge-to-edge; sistem çubukları şeffaf, ikonlar açık. İçerik kaydırılır, alttaki yüzen nav için 124 boşluk. **Yenile butonu ve pull-to-refresh yok**, her şey otomatik.

### 10.1 Ortak başlık (Cihazlar ve Otomasyon)
- **Wi-Fi pill** (cam, yükseklik 44, tam hap): Wi-Fi ikonu **sinyal seviyesine göre** (Material Symbols: 0 → `signal_wifi_0_bar`, 1 → `network_wifi_1_bar`, 2 → `network_wifi_2_bar`, 3 → `network_wifi_3_bar`, 4 → `signal_wifi_4_bar`; seviye değişimi crossfade + hafif scale), sonra `SSID · {linkSpeed} Mbps · {telefonIP}`. SSID ve "Mbps" kısmının sayısı ile IP **JetBrains Mono**. SSID okunamazsa yalnız "Wi-Fi".
- Hemen sağında ayrı **ayarlar balonu** (cam, 44, `settings` ikonu) → Ayarlar ekranı.
- Altında sayfa başlığı ("Cihazlar" / "Otomasyon"). **Başlığın altında alt ağ/IP satırı yok.** Otomasyon'da başlık altında tek satır açıklama: "Zamanlayıcılar telefondan çalışır, Wi-Fi şart".

### 10.2 Cihazlar sekmesi

**Hero kart (seçili cihaz)** — köşe 38, padding 18.
- Sol üst: 48 ikon balonu + 38 düzenle (kalem) butonu.
- Ad (28/30, 700), altında `IP · MAC` (JetBrains Mono 11).
- Çipler: durum çipi ("● Açık · 3 ms", "Uyanıyor · 7 sn", "Kapalı", "Kapanıyor…"; ms ve süreler mono), agent çipi ("Agent" ✓ / "Agent yok" / "Agent cevap vermiyor"), gerekirse "MAC bekleniyor".
- Sağda **124 güç halkası / 96 orb**:
  - Kapalı: nötr cam orb, **dokun = uyandır**.
  - Açık: cihaz gradient'i + parlama, halka gradient, yavaşça döner. **Basılı tut = Kapat**.
  - Uyanıyor: amber dönen yay.
  - Basılı tutarken: halka kırmızı (`#FF8F85`) doluyor.
- **Açık cihazda:** "Güç · Basılı tut" başlığı + 5'li buton satırı (yükseklik 62, köşe 20): **Kilitle, Uyku, Hazırda, Yeniden, Kapat** (Kapat kırmızımsı vurgulu). **Hepsi basılı tutarak** çalışır (bölüm 12.6). Agent yoksa satır pasif + ipucu.
- **Kapalı cihazda:** güç satırı yok; tek eylem orb'a dokunarak uyandırmak.
- **Uyanırken:** güç satırı ve geçmiş yerine 3 adım kartı: Paket / Ping yanıtı / Port açık (tamam = cihaz gradient'i dolu + ✓, aktif = amber dönen halka, bekleyen = soluk halka).
- Altta son 12 deneme çubukları (başarı beyaz %85 tam boy, başarısız `#FF8F85` yarım boy) + "Son 12 deneme · 11/12" ve sağda "Son uyandırma · 08:31" (sayılar mono).
- Uyanıyor kartı: amber zemin (amber %30 → %8), 1 amber %60 çerçeve.

**Cihaz kartları** — 2 sütun, aralık 12, yükseklik 150, köşe 32.
- Üst sol 44 ikon balonu, üst sağ 44 güç butonu (kapalı: dokun = uyandır; açık: basılı tut = Kapat, halka dolumu kartta da görünür).
- Altta ad (18, 700), durum satırı ("Açık · 3 ms", "Kapalı · 8/9", "Kapalı · yeni", "Uyanıyor…", "Kapatılıyor…"), 4 yükseklikli çubuk (kapalı: güvenilirlik oranı; açık: tam gradient; uyanıyor: amber ilerleme; kapanıyor: kırmızı ilerleme).
- Karta dokunmak cihazı seçer: hero kart o cihaza **shared-element** ile geçer, seçili kartta 2 beyaz halka (halka bir karttan diğerine spring ile kayar).
- Boş durum: "Henüz cihaz yok" cam kartı + "Cihaz ekle" butonu.

### 10.3 Otomasyon sekmesi
Her zamanlayıcı cam kart (köşe 28):
- Cihaz ikon balonu (cihaz renginde), cihaz adı + **aksiyon etiketi** (Aç = yeşil, Kapat = kırmızımsı, Uyku/Kilitle/Hazırda/Yeniden = nötr), tetikleyici ("Hafta içi · 08:30", "⌂ Eve gelince · Ev-WiFi").
- Sağda 52×32 anahtar (açıkken cihaz gradient'i + parlama).
- Açıkken altta "Sonraki: Yarın 08:30" + atla butonu (44).
- Atlanmışsa amber satır + "Geri al".
- Son çalışma `FAILED` ise kırmızımsı satır: "⚠ Son çalışma başarısız · Bugün 18:42 · Detay" → `ScheduleFailureScreen`. Sonraki başarılı çalışmada kaybolur.
- Kapalı zamanlayıcı %70 opak.
- Listenin altında **"Zamanlayıcı ekle"** butonu → `ScheduleEditorSheet` (cihaz seçimi, aksiyon seçimi, tetikleyici TIME/WIFI, gün seçici, saat seçici; aynı sheet düzenlemede de, silme dahil). Karta dokunmak düzenler.

### 10.4 Zamanlayıcı başarısız ekranı
- Geri butonu (cam 44).
- Ortada 96 kırmızımsı cam daire içinde cihaz ikonu, başlık "Çalışma PC uyanmadı" (aksiyona göre: "kapanmadı", "uyutulamadı"…), alt satır "Eve gelince · Bugün 18:42 – 18:52".
- Deneme listesi kartı: üstte "6 deneme · 2 dk arayla" ve "10 dk sonra vazgeçildi"; her satır zaman (mono) + sebep, aralarında dikey bağlantı çizgisi.
- "Kontrol et" kartı (yalnız WAKE için): BIOS WoL · Magic Packet · Hızlı Başlat kapalı · kablolu bağlantı.
- Altta "Tamam" (cam) + "Şimdi tekrar dene" (cihaz gradient'i; elle akışı başlatır).

### 10.5 Cihaz ekle / düzenle paneli (modal bottom sheet)
- Yükseklik ekranın ~%84'ü, üst köşe 36, arkada %62 siyah scrim + 6 blur. Tutamaç, başlık ("Cihaz ekle" / "Cihazı düzenle"), kapat butonu. **Aşağı sürükleyerek kapatılabilir.**
- Gövde (kaydırılır):
  1. Canlı önizleme kartı (açık stil, seçili renk/ikon; ad boşsa "Yeni cihaz"), alt satırda IP ve agent bilgisi.
  2. "Ağda bulunanlar" (yalnız ekleme modunda) + tarama göstergesi + "Yeniden tara". Satıra dokununca ad, IP, MAC, ikon forma dolar.
  3. "Cihaz bilgisi": Ad; IP ve MAC yan yana (input yüksekliği 50, köşe 18; IP ve MAC mono).
  4. İkon: 5×2 grid (56).
  5. Renk: 4×2 grid (48 hap), 8 gradient; seçili olanda beyaz iç halka + ✓.
- Sabit alt bar: ipucu satırı + düzenlemede "Sil" (onay: "Cihaz silinsin mi?") + "Cihazı ekle"/"Kaydet" (56, seçili renk gradient'i).
- İpucu metinleri: geçersizse "Ad ve geçerli IP gerekli"; MAC boşsa "MAC olmadan uyandırılamaz · PC açıkken otomatik bulunur"; agent bulunduysa "Agent bulundu · eklenince otomatik eşleşir".
- Kaydedince: sheet kapanır, cihaz seçilir, Cihazlar sekmesine geçilir. Agent bulunduysa **arka planda otomatik eşleşme** (bölüm 8.3); eşleşme başarısı hero karttaki agent çipinin animasyonla belirmesiyle görünür.
- Agent bağlantı denemesi: cihaz eklenince, **her uygulama açılışında** ve **cihaz `On` durumuna geçtiği an** (agentId yoksa) discovery + eşleşme denenir. Bulunan bilgi kalıcıdır; PC kapanınca "agent yok"a düşmez.

### 10.6 Tam ekran kapılar
**NoWifiScreen** (Wi-Fi'a bağlı değilken her ekranın üstünde):
- Ortada Wi-Fi-off ikonu (cam 124 daire, amber parlama), etrafında **sürekli dışa yayılan 3 halka** (bölüm 12.5).
- Başlık "Wi-Fi'a bağlı değilsin", açıklama "Bilgisayarlarına yerel ağ üzerinden ulaşıyorum. Mobil veriyle çalışmaz."
- Bilgi kartı: "Şu an mobil veri · {operatör}" ya da "Bağlantı yok".
- Buton "Wi-Fi ayarlarını aç" (`Settings.Panel.ACTION_WIFI`), alt not "Wi-Fi'a bağlanınca bu ekran kendiliğinden kapanır".
- Wi-Fi gelince **animasyonla kendiliğinden kapanır**.

**NoPermissionScreen**: aynı düzen; yerel ağ izni yoksa. Buton izni ister; kalıcı reddedilmişse uygulama ayarlarını açar.

### 10.7 Ayarlar
Push geçişiyle açılır, geri butonu + predictive back.
- **İzinler** (cam grup): Yerel ağ erişimi, Konum (Wi-Fi adı için), Tam saatli alarm, Bildirimler, Pil (Kısıtlanmamış olmalı). Her satırda "✓ Verildi" (yeşil) ya da amber "İzin ver"/"Düzelt" butonu → ilgili sistem ekranı. Dönüşte durum canlı güncellenir.
- **Zamanlayıcı tekrar denemesi**: "Tekrar aralığı" ve "Vazgeçme süresi". Her biri **−/+ stepper + ortada klavyeyle yazılabilir sayı alanı** (sayısal klavye, mono, odakta Buz renginde halka), birimi "dk". Aralık dışı/boş girişte alan kırmızımsı titreşir ve son geçerli değere döner; `giveUp ≥ interval` zorunlu.
- **Cihazlar · Kapatma şekli**: her cihaz için renk noktası, ad, agent durumu ve "Zorla / Nazik" segmenti.
- **Hızlı ayarlar**: 3 tile slotu için cihaz seçimi (slot başına satır, "Boş" seçeneği) + "Tile davranışı" segmenti A/B/C (bölüm 10.8).
- **Diğer**: Titreşim anahtarı; ipucu "Router'da sabit IP önerilir".

### 10.8 Hızlı Ayarlar tile'ları
- **3 sabit slot**: `WolTileService1..3` (manifest'te statik). Atanmamış slot: başlık "WoL slot N", alt yazı "Cihaz seç", dokununca Ayarlar'daki slot seçimi açılır.
- Başlık = cihaz adı, alt yazı = durum ("Açık · 2 sa", "Uyuyor", "Uyanıyor 8 sn", "Kapanıyor…", "Wi-Fi yok", "İzin yok"), ikon = cihaz ikonunun Material Symbols vector drawable'ı.
- State eşlemesi: `ACTIVE` = açık, `INACTIVE` = kapalı/geçişte, `UNAVAILABLE` = Wi-Fi yok / izin yok.
- `onStartListening`: DB + StatusEngine'den taze oku, hemen 1 tur at, `updateTile()`.
- **Kapalı cihaza dokunma** → doğrudan uyandır (kilit ekranında da çalışır), alt yazı "Uyanıyor…" olur.
- **Açık cihaza dokunma** → seçili `tileMode`'a göre (önce `unlockAndRun` ile kilit açtırılır):
  - **A (varsayılan) · Overlay:** şeffaf, arkası blur'lu tam ekran `TilePowerActivity`. Ortada cihaz adı + durum, büyük orb (172) ve 220'lik ilerleme halkası, altında "Kapatmak için basılı tut", altında 4 mini buton (Kilitle, Uyku, Hazırda, Yeniden; hepsi hold), en altta "Vazgeç". Arka plan sürekli canlı.
  - **B · Dialog:** `TileService.showDialog` ile panel üstünde küçük cam dialog, içinde aynı hold butonları.
  - **C · Çift dokunuş:** ilk dokunuşta alt yazı 3 sn "Kapatmak için tekrar dokun", ikinci dokunuş Kapat; diğer aksiyonlar yok.
- Komut sonrası tile alt yazısı durum akışını izler; panel kapansa da sonuç bildirimi yalnız başarısızlıkta.

### 10.9 Alt gezinme
Yüzen, kenarlardan 16, alttan 20 (+ sistem inset). Solda 68 yüksekliğinde cam hap içinde 2 sekme (Cihazlar `desktop_windows`, Otomasyon `bolt`; 22 ikon + 11 etiket; aktif: %16 beyaz dolgu + 1 %22 çerçeve; aktif sekme göstergesi sekmeler arasında **spring ile kayar**, aktif ikon FILL 0→1 animasyonlu). Sağda 68 gradient "+" → Cihaz ekle paneli.

### 10.10 Erişilebilirlik
Tüm ikon butonlar Türkçe `contentDescription`; dokunma hedefi ≥ 44; anahtarlar `Switch` semantics; hold butonları `onLongClickLabel` + TalkBack için alternatif (TalkBack açıkken çift dokunup bekleme yerine onay dialog'u); durum yalnız renkle değil metinle de anlatılır; metin kontrastı ≥ 4.5:1.

---

## 11. Tasarım sistemi

### 11.1 Zemin
Koyu `#0C0D10` üzerinde AGSL ile çizilen canlı mesh gradient (bölüm 12.4). Varsayılan sahne odakları:

| Odak | Konum (dp) | Yarıçap | Renk | Opaklık |
|---|---|---|---|---|
| 1 | sol-üst (-120, -100) | 180 | `#5B6B8C` | 0.55 |
| 2 | sağ (-140, 250) | 180 | `#3B4A6B` | 0.50 |
| 3 | sol (-100, 540) | 170 | `#4A3F6B` | 0.45 |
| 4 | sağ-alt (-90, 660) | 150 | `#2F6B6B` | 0.40 |

### 11.2 Cam yüzey (`GlassSurface`)
Zemin beyaz %18 → %5 (145°) + üzerine `#0C0C1E` %40; backdrop blur 24 + doygunluk 1.6 (Haze); 1 beyaz %14 iç çerçeve + üst kenarda 1 beyaz %35 vurgu; gölge 0/12/32 siyah %28. Varyantlar: *güçlü* (hero, %22 → %6 + sağ üstten cihaz rengi %30 radial), *dock* (nav, %20 → %6, blur 30).

### 11.3 Renkler
Metin: birincil `#FFFFFF`, ikincil `#D5D8F0` / `#C9CCE0` / `#E8EAF8` (renkli kart üstü), üçüncül `#AEB2CC`. Durum: amber `#FFD97A`, başarısız/kapat `#FF8F85` (açık tonu `#FFD0CB`), başarılı nokta `#7CFFB2`. Gradient üstü koyu metin `#0B0B1A`.

**Cihaz paleti** (135° gradient, `colorIndex` sırasıyla):

| # | Ad | Başlangıç | Bitiş |
|---|---|---|---|
| 0 | Buz | `#5EE7FF` | `#B07CFF` |
| 1 | Nane | `#7CFFB2` | `#2DD4BF` |
| 2 | Gün batımı | `#FFB36B` | `#FF5FA2` |
| 3 | Güneş | `#FDE047` | `#FB923C` |
| 4 | Pembe | `#FF8AD8` | `#8B5CF6` |
| 5 | Okyanus | `#60A5FA` | `#22D3EE` |
| 6 | Mercan | `#FF8F85` | `#FFB36B` |
| 7 | Gümüş | `#E5E7EB` | `#9CA3AF` |

**Açık cihaz kartı:** 145° `mix(c1, #0B0B1A, %42)` → `mix(c2, #0B0B1A, %50)`; 1 çerçeve c1 %60; gölge 0/10/40 c2 %40. Güç butonu c1→c2 + parlama c1 %55. Kapalı kart ikon balonu: c1 %20 zemin + c1 ikon.

### 11.4 Tipografi
**Onest** (arayüz) ve **JetBrains Mono** (teknik: IP, MAC, alt ağ, Mbps sayısı, ms, oranlar 11/12, saatler, sayaç saniyeleri, ayarlardaki sayı alanları). Rakamlar tabular.

| Rol | Font | Boyut/satır | Ağırlık | Harf aralığı |
|---|---|---|---|---|
| Sayfa başlığı | Onest | 40/42 | 700 | -1.2 |
| Hero cihaz adı | Onest | 28/30 | 700 | -0.8 |
| Ekran başlığı (Ayarlar, sheet) | Onest | 24–34 | 700–800 | -0.6…-1.2 |
| Kart adı | Onest | 18 | 700 | 0 |
| Gövde | Onest | 13–16 | 500–600 | 0 |
| Çip / etiket | Onest | 11–12 | 600 | 0 |
| Teknik | JetBrains Mono | 11–13 | 500 | -0.2 |

### 11.5 Köşeler
Hero 38, kart 32, zamanlayıcı kartı 28, ayar grubu 26, sheet üstü 36, güç butonu 20, input 18, çip ve butonlar tam hap.

### 11.6 İkonlar
**Material Symbols Outlined** her yerde. Uygulama içinde değişken font (`MaterialSymbolsOutlined[FILL,GRAD,opsz,wght].ttf` + codepoints) ile `SymbolIcon(name, fill, weight)` composable'ı; FILL ekseni seçim/aktiflikte **animasyonla** 0→1. Tile ve bildirim ikonları için aynı sembollerin vector drawable'ları (`res/drawable/sym_*.xml`).

| Anahtar | Symbol |
|---|---|
| `pc` | `desktop_windows` |
| `laptop` | `laptop_mac` |
| `nas` | `dns` |
| `tv` | `tv` |
| `console` | `sports_esports` |
| `printer` | `print` |
| `speaker` | `speaker` |
| `camera` | `photo_camera` |
| `chip` | `memory` |
| `bulb` | `lightbulb` |

Arayüz: Cihazlar `desktop_windows`, Otomasyon `bolt`, ayarlar `settings`, güç/uyandır `power_settings_new`, düzenle `edit`, ekle `add`, kapat `close`, geri `arrow_back_ios_new`, Wi-Fi seviye ikonları (bölüm 10.1), Wi-Fi yok `wifi_off`, agent `verified_user`, eve gelince `home`, uyarı `warning`, onay `check`.

**İstisna — Windows güç ikonları (tek özel SVG yeri):** Kilitle, Uyku, Hazırda Beklet, Yeniden Başlat, Kapat ikonları **Windows 11'in güç menüsündeki ikonlarla birebir aynı görünmeli**. Windows'un kendi glifleri (Segoe Fluent Icons) başka platformda dağıtılamaz; bu yüzden aynı tasarım dilindeki **Fluent UI System Icons (MIT lisanslı, Microsoft)** SVG'leri vector drawable olarak eklenir (`res/drawable/win_*.xml`, `WinPowerIcon` composable'ı):

| Aksiyon | Fluent ikon (24, Regular) |
|---|---|
| Kilitle | `lock_closed` |
| Uyku | `weather_moon` |
| Hazırda Beklet | `snowflake` — Windows 11'de belirgin ayrı bir ikonu yok; Kaan onaylayana kadar yer tutucu |
| Yeniden Başlat | `arrow_clockwise` |
| Kapat | `power` |

Lisans dosyası `android/app/src/main/assets/licenses/` altına konur.

---

## 12. Hareket sistemi (ZORUNLU)

> **Kural:** Uygulamada **hiçbir şey animasyonsuz değişmez.** Ekran geçişi, durum değişimi, metin/sayı değişimi, renk, boyut, görünme/kaybolma, liste ekleme/çıkarma — hepsi animasyonlu. Animasyonsuz geçiş = bug. Referans: **Apple (iOS) hareket dili**: fizik tabanlı, kesilebilir, hız korunarak yönü değişebilen spring'ler.

### 12.1 Spring token'ları
Apple'ın `response` / `dampingFraction` modeli Compose `spring()`'e çevrilir: `stiffness = (2π / response)²` (kütle 1), `dampingRatio = dampingFraction`.

| Token | response | damping | stiffness | Kullanım |
|---|---|---|---|---|
| `quick` | 0.20 | 1.00 | ≈ 987 | basma geri bildirimi, küçük ikon değişimleri |
| `snappy` | 0.30 | 0.86 | ≈ 439 | çipler, butonlar, metin değişimleri |
| `smooth` | 0.50 | 1.00 | ≈ 158 | kartlar, sheet, ekran geçişleri |
| `bouncy` | 0.50 | 0.70 | ≈ 158 | anahtar düğmesi, seçim halkası, başarı vurgusu |
| `gentle` | 0.80 | 1.00 | ≈ 62 | arka plan sahne geçişi, renk tonu kaymaları |

- Durum değişimlerinde **sabit süreli tween yasak**. İstisnalar: sonsuz döngüler (dönen halka, drift, nefes alma, halka yayılması) ve hold dolumu (zamana bağlı olmak zorunda).
- Tüm animasyonlar **kesilebilir**: `Animatable`/`animate*AsState` yeni hedefe mevcut hızı koruyarak gider.
- Sistemin "Animasyonları kaldır" ayarı açıksa (`ANIMATOR_DURATION_SCALE == 0`): sonsuz döngüler durur, geçişler kısa crossfade'e iner.

### 12.2 Ekran geçişleri
- **Sekme değişimi:** içerik crossfade + hafif ölçek (0.98 → 1) ve yöne göre 12'lik kayma (`smooth`); aynı anda arka plan sahnesi morph olur (12.4); nav göstergesi spring ile kayar.
- **Push ekranlar** (Ayarlar, Başarısız detayı): iOS tarzı sağdan giriş, alttaki ekran %30 paralaks + hafif kararma; **predictive back** (`PredictiveBackHandler`) ile parmağı takip eden etkileşimli geri.
- **Sheet:** alttan `smooth` spring ile çıkar, scrim + blur birlikte açılır; sürükleyerek kapatılır (rubber-band), bırakınca hıza göre kapanır ya da geri oturur.
- **Kart → hero:** `SharedTransitionLayout` ile seçilen cihazın ikon balonu, adı ve rengi hero karta morph olur; seçim halkası `bouncy` ile yeni karta kayar.
- **Tam ekran kapılar** (Wi-Fi yok, izin yok): 1.04 → 1 ölçek + fade ile girer; koşul ortadan kalkınca yukarı doğru hafif kayıp çözülerek çıkar ve altındaki ekran ortaya çıkar.
- **Tile overlay (A):** blur 0 → hedef + orb 0.8 → 1 (`bouncy`); kapanışta tersi.

### 12.3 Bileşen animasyonları
- **Basma:** her dokunulabilir öğe 0.96 ölçek + vurgu (`quick`), bırakınca `snappy` ile geri.
- **Metin/sayı:** durum metinleri `AnimatedContent` (yukarı kayıp çıkar, aşağıdan girer + fade); ping ms ve sayaçlar **haneli yuvarlanma** (her hane bağımsız).
- **Renkler:** durum renkleri `animateColorAsState(spring)`; açık ↔ kapalı kart dönüşümü gradient + gölge + çerçeve birlikte akar.
- **Orb:** kapalı → uyanıyor (amber yay belirir ve döner) → açık (yay tam halkaya tamamlanır, **bloom**: orb 1.0 → 1.08 → 1.0 `bouncy` + parlama patlaması + başarı haptic'i).
- **Adım kartları:** tamamlanan adım gradient ile soldan sağa dolar + ✓ çizilerek belirir (path trim); aktif adım halkası döner.
- **Geçmiş çubukları:** ilk görünüşte soldan sağa 25 ms aralıkla `bouncy` büyür; yeni deneme eklenince kaydırarak girer.
- **Listeler:** öğeler 30 ms kademeli fade + 8 yukarı kayma ile girer; silinen öğe yüksekliği spring ile kapanır.
- **Anahtar:** düğme `bouncy` ile kayar, zemin rengi akar.
- **Çipler:** içerik değişince genişlik `animateContentSize(snappy)`.
- **Wi-Fi seviye ikonu:** crossfade + 0.9 → 1 ölçek.
- **Material Symbols FILL ekseni:** seçili sekme/aktif ikon FILL 0 → 1 (`snappy`).
- **Ayarlar sayı alanı:** stepper değişiminde haneli yuvarlanma; geçersiz girişte yatay titreme (3 salınım, `bouncy`).

### 12.4 Canlı arka plan (AGSL)
- Tek tam ekran `RuntimeShader`: 4 odak noktası (konum, yarıçap, renk, opaklık) + `time` uniform'u; yumuşak mesh gradient + çok hafif film grain (banding'i önler).
- **Sahneler:** her sekmenin kendi odak düzeni ve ton dağılımı var (Cihazlar: soğuk mavi-mor; Otomasyon: mor-turkuaz ağırlıklı; tam ekranlar: duruma göre). Sahne değişiminde her odak `gentle` spring ile yeni konum/renge akar.
- **Cihaz tonu:** odaklardan biri seçili cihazın rengine doğru kayar (Buz seçiliyken mavi-mor, Nane'de yeşil-turkuaz). Uyanırken amber, kapatırken kırmızımsı tona geçici olarak kayar.
- **Sekmeli ekranlarda** (Cihazlar, Otomasyon) arka plan **sabittir**, sadece sahne/ton değişimlerinde animasyonla akar.
- **Tam ekranlarda** (Wi-Fi yok, izin yok, Başarısız detayı, tile overlay) arka plan **sürekli** canlıdır: odaklar 14–19 sn'lik farklı periyotlarla ±40–50 dp salınır ve 0.92–1.12 ölçeklenir.
- Performans: shader tek geçiş; 120 Hz'de 1 ms altında kalmalı.

### 12.5 Sürekli efektler
- **Açık cihaz kartları ve hero:**
  - nefes alan parlama (gölge opaklığı ve yayılımı 3 sn döngü, ±%15),
  - kenarda cihaz renginde yavaş dönen ışık hüzmesi (conic gradient, 8 sn tur),
  - 7 sn'de bir yüzeyden geçen yumuşak ışık süpürmesi.
- **Güç halkası (açık):** gradient halka 12 sn'de bir tur döner.
- **Uyanıyor:** amber yay 1 sn'de tur; kart amber parlaması nefes alır (1.6 sn).
- **Wi-Fi yok halkaları:** 3 halka merkezden dışa yayılır (ölçek 0.6 → 1.6, opaklık 0.5 → 0), her biri 2.4 sn, aralarında 0.8 sn faz farkı; ikon dairesi hafifçe nefes alır.
- **Tarama göstergesi:** dönen halka + bulunan her satır geldiğinde hafif "pop".

### 12.6 Basılı tut (hold-to-confirm)
- Kilitle, Uyku, Hazırda, Yeniden, Kapat (hero satırı, orb, küçük kart güç butonu, tile overlay) **basılı tutarak** çalışır. Uyandırma tek dokunuş.
- **Süre:** 1.5 sn. Dolum yavaş ve hafif ease-in (başta biraz daha yavaş) ilerler.
- **Bırakınca:** dolum **anında sıfırlanmaz**, `snappy` spring ile hızlıca geri boşalır (~250 ms). Yeniden basılırsa kaldığı yerden devam eder.
- Buton basılıyken 0.96 ölçek, dolum alttan yukarı (butonlarda) / halka boyunca (orb'da) ilerler; dolum rengi aksiyona göre (Kapat/Yeniden kırmızımsı, diğerleri beyaz).
- Tamamlanınca: kısa "commit" vurgusu (1.0 → 1.05 → 1.0) + güçlü haptic, ardından komut akışı.
- Hold sırasında hero kartın üst satırında ipucu: "Kapatılıyor · bırakırsan iptal" (aksiyona göre).

### 12.7 Haptikler
`VibrationEffect.Composition` primitifleri; Ayarlar'dan kapatılabilir.
- Hold dolumu: %15'lik dilimlerde `PRIMITIVE_LOW_TICK`, şiddeti 0.2 → 0.8 artar; tamamlanınca `PRIMITIVE_THUD`.
- Hold iptali: tek hafif `PRIMITIVE_TICK`.
- Uyandırma gönderimi: `PRIMITIVE_CLICK`; cihaz açılınca (bloom ile senkron) `PRIMITIVE_SPIN` ya da çift `CLICK`.
- Anahtar, sekme, seçim: `PRIMITIVE_TICK` (hafif).
- Hata/"Uyanmadı": iki kısa `PRIMITIVE_TICK`.

---

## 13. Windows agent

### 13.1 Yığın
.NET 10 (LTS), Worker Service (`UseWindowsService()`), **self-contained tek dosya** `win-x64` exe (`WolAgent.exe`). Hem servis hem CLI aynı exe. PC tarafında **hiçbir UI, pencere, bildirim yok**.

### 13.2 CLI (yönetici olarak)
| Komut | Ne yapar |
|---|---|
| `wolagent install` | `%ProgramFiles%\WolAgent\`'a kopyalar; servisi kaydeder (Automatic, LocalSystem, açıklama "WoL Denetim Merkezi agent"); recovery: 3 kez yeniden başlat (5 sn); firewall kuralı: **UDP 40900 inbound, tüm profiller, remoteip=LocalSubnet**; ilk kurulumda X25519 anahtar çifti ve agentId üretir; servisi başlatır. Tekrar çalıştırılırsa güncelleme yapar (servisi durdur, değiştir, başlat; anahtarları korur). |
| `wolagent uninstall` | Servisi, firewall kuralını, program dosyalarını kaldırır (`--purge` ile config dahil). |
| `wolagent reset` | Eşleşmeyi siler (yeni telefon için). Anahtar çifti korunur. |
| `wolagent status` | Servis durumu, agentId, eşleşme durumu, port, hibernate desteği, NIC listesi. |

### 13.3 Depolama ve log
- Config: `%ProgramData%\WolAgent\config.json` (agentId, X25519 özel anahtarı DPAPI LocalMachine ile şifreli, eşleşmiş phoneId + phonePub + türetilmiş anahtar DPAPI ile). Klasör ACL: SYSTEM + Administrators.
- Log: `%ProgramData%\WolAgent\logs\` günlük dönen dosya, 7 gün; her istek tek satır (tip, kaynak IP, sonuç; anahtar/imza loglanmaz).

### 13.4 Komut uygulama (servis LocalSystem'da, Session 0)
| Komut | Uygulama |
|---|---|
| LOCK | `WTSGetActiveConsoleSessionId` + `WTSDisconnectSession` (servisten `LockWorkStation` çalışmaz; etkisi kilit ekranı) |
| SLEEP | `SetSuspendState(false, false, false)` |
| HIBERNATE | `GetPwrCapabilities` ile `SystemS4 && HiberFilePresent` kontrolü; yoksa `UNSUPPORTED`; varsa `SetSuspendState(true, false, false)` |
| SHUTDOWN | `InitiateSystemShutdownEx(null, null, 0, force, false, reason)` (`SE_SHUTDOWN_NAME` etkinleştirilir) |
| RESTART | aynısı, `bRebootAfterShutdown = true` |

- `force=false` (Nazik): programlar engelleyebilir; telefon 90 sn sonra "Kapanmadı" der.
- Aynı anda tek komut; işlemdeyken gelen komuta `BUSY`.

### 13.5 Ağ
- UDP 40900'ü tüm IPv4 arayüzlerinde dinler; yalnız RFC1918/link-local kaynak kabul.
- DISCOVER yanıtında tüm NIC'ler (MAC, ad, kablolu mu, IP) listelenir; telefon kablolu ve WoL'a uygun olanın MAC'ini seçer.

---

## 14. İzinler ve manifest

| İzin | Neden | Nasıl |
|---|---|---|
| `INTERNET` | UDP/TCP | normal |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Wi-Fi ağı, link hızı, RSSI | normal |
| `CHANGE_WIFI_MULTICAST_STATE` | MulticastLock (mDNS, broadcast) | normal |
| `ACCESS_LOCAL_NETWORK` | **Android 17'de yerel ağ erişimi zorunlu izin** | runtime; ilk açılışta. Reddedilirse `NoPermissionScreen` |
| `ACCESS_FINE_LOCATION` | SSID okuma (Wi-Fi pill, "eve gelince") | runtime; reddedilirse pill "Wi-Fi", WIFI_CONNECT oluşturulamaz |
| `USE_EXACT_ALARM` | Zamanlayıcılar (sideload, kurulumda verilir) | normal |
| `RECEIVE_BOOT_COMPLETED` | Alarmları yeniden kurmak | normal |
| `POST_NOTIFICATIONS` | Başarısız zamanlayıcı bildirimi | runtime |
| `VIBRATE` | Haptikler | normal |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Samsung pil kısıtlamasından muafiyet isteği | Ayarlar'dan |

Doğrulanacaklar: Android 17'de `ACCESS_LOCAL_NETWORK`'ün tam davranışı (NSD, broadcast, `.local` çözümleme dahil); SSID için gereken izin kombinasyonu; PendingIntent tabanlı Wi-Fi callback'inin One UI 9'da güvenilirliği.

**Onboarding (ilk açılış):** 3 sayfalık cam akış: 1) Yerel ağ izni, 2) Konum (Wi-Fi adı için, atlanabilir), 3) Bildirim. Her sayfa canlı arka planlı tam ekran; sayfalar arası yatay spring geçiş.

---

## 15. Doğrulama kuralları

- **Ad:** trim sonrası 1–32 karakter; aynı ad ikinci kez olamaz.
- **IP:** IPv4, dört okte 0–255; telefonun alt ağında olmalı (değilse uyarı, engel değil).
- **MAC:** boş olabilir; doluysa `^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$`, kaydederken büyük harf + `:`; aynı MAC ikinci kez eklenemez ("Bu MAC zaten kayıtlı").
- **Ayarlar sayıları:** `retryIntervalMin` 1–30, `giveUpMin` 2–120 ve `≥ retryIntervalMin`.
- **Zamanlayıcı:** TIME için en az 1 gün; WIFI için SSID dolu; aksiyon agent gerektiriyorsa cihazda agent eşli olmalı (değilse kaydet pasif + ipucu).

---

## 16. Testler

**Kotlin birim testleri**
- `MagicPacket`: uzunluk 102; ilk 6 bayt `0xFF`; MAC 16 kez; `vectors.json` ile bayt eşitliği.
- `BroadcastResolver`: `192.168.1.24/24 → 192.168.1.255`, `10.0.5.17/22 → 10.0.7.255`, `/31` ve `/32` için açık hata.
- MAC normalizasyon/doğrulama.
- `AgentCodec` + kripto: `vectors.json`'daki tüm `canonical`/`sig` değerleri, HKDF ve X25519 türetimi.
- `StatusEngine` (sahte probe'lar, sanal zaman): S1 tek başına açık; S2 MAC uyuşmazlığı açık saymaz; tek negatif tur kapalı yapmaz, iki tur yapar; stale 15 sn; geçişlerde 3 tur kuralı.
- `WakeCoordinator`: 3. sn'de yanıt → başarı `durationSec=3`; 60 sn yanıtsız → başarısız; ikinci tetikleme yok sayılır; MAC yoksa başlamaz.
- `PowerCoordinator`: ACK → PowerTransition → offline → Off; 90 sn timeout; RESTART offline+online; `UNSUPPORTED`, `BUSY` hataları.
- `ScheduleEngine`: hafta içi 08:30, çarşamba 23:49 → perşembe 08:30; atla → cuma 08:30; atlama geçince temizlenir; anahtar kapanınca sıfırlanır; hafta sonu ve gece yarısı geçişleri; buton metinleri.
- `ScheduleRunner`: 2/10 dk ile 6 deneme; Wi-Fi yokken deneme `NO_WIFI` sayılır ve devam eder; zaten açık → `SKIPPED_ALREADY`; ilk denemede başarı → tek kayıt.
- Geçmiş: 12'den fazla denemede yalnız son 12; k/n.

**C# (xUnit)**
- `vectors.json`'daki tüm mesajlar, imzalar, HKDF.
- Nonce: tek kullanımlık, 10 sn sonra `NONCE_EXPIRED`, yanlış imza `BAD_SIG`.
- TOFU: ilk PAIR_HELLO kabul, ikincisi `PAIR_DENIED`, `reset` sonrası tekrar kabul.
- Kaynak IP filtresi (public IP reddedilir).
- Komut yürütücüsü arayüz arkasında; testte sahte yürütücü ile 300 ms gecikme ve `BUSY` davranışı.

**Compose UI testleri**
- Karta dokununca hero o cihaza geçer.
- Kapalı cihazda orb'a dokunmak uyandırmayı başlatır; açık cihazda dokunmak bir şey yapmaz, 1.5 sn basılı tutmak Kapat'ı tetikler, 0.8 sn'de bırakmak tetiklemez.
- Sheet: geçersiz IP ile kaydet pasif; geçerliyle cihaz listede belirir ve seçilir.
- "Yarını atla" → amber satır + "Geri al".
- Ayarlar: sayı alanına aralık dışı değer girilince eski değere döner.
- Wi-Fi bağlantısı kesilince `NoWifiScreen` görünür, gelince kaybolur.

**Elle test (gerçek cihaz)**
BIOS'ta WoL açık, Windows'ta ağ kartında "Magic Packet ile uyandır" açık, Hızlı Başlat kapalı, kablolu bağlı PC ile: uyku ve kapalıdan uyandırma; 5 güç komutu; Zorla/Nazik kapatma (açık Not Defteri'nde kaydedilmemiş metinle); IP değişimi (DHCP yenileme); mobil veri açıkken Wi-Fi'da çalışma; tile'lar (kilit ekranında uyandırma, kapatmada kilit açma isteği); zamanlayıcı retry (PC'nin kablosu çıkarılarak).

---

## 17. Uygulama fazları

Her fazın sonunda: derleme + testler yeşil, kabul kriterleri sağlandı, **UI fazlarında animasyon denetimi** (bölüm 12'deki ilgili maddeler tek tek).

| # | Faz | Kabul kriteri |
|---|---|---|
| 0 | **Monorepo + protokol:** klasörler, `PROTOCOL.md`, `vectors.json` (üretimi için küçük bir C# veya Kotlin script'i), README | Vektör dosyası tüm mesaj türlerini kapsıyor |
| 1 | **Android iskeleti + tasarım sistemi:** Hilt, fontlar (Onest, JetBrains Mono, Material Symbols), renkler, `GlassSurface`, spring token'ları, haptik yardımcıları, AGSL arka plan + sahneler, floating nav, iki boş sekme, edge-to-edge | Sekme değişiminde nav göstergesi kayıyor, ikon FILL animasyonu ve arka plan sahne morph'u çalışıyor |
| 2 | **Veri katmanı:** Room, DAO'lar, repository'ler, DataStore, KeyVault | CRUD + geçmiş testleri |
| 3 | **Ağ çekirdeği:** NetworkMonitor (bind, SSID, RSSI, linkSpeed), MagicPacket, BroadcastResolver, WolSender, S3/S4 probe'ları, `ACCESS_LOCAL_NETWORK` akışı | Geçici debug butonuyla gerçek PC uyanıyor; mobil veri açıkken de |
| 4 | **Agent v1 + AgentClient:** servis, CLI, firewall, discovery, TOFU eşleşme, challenge, STATUS, 5 komut; Kotlin tarafında AgentClient ve S1, S2 (NetBIOS) | İki taraf `vectors.json`'u geçiyor; telefondan gerçek PC kilitleniyor/kapanıyor |
| 5 | **StatusEngine + koordinatörler:** füzyon, durum makinesi, Wake/Power akışları, IP yeniden çözümleme | Bölüm 16'daki domain testleri; gerçek cihazda durum titremesi yok |
| 6 | **Cihazlar ekranı:** başlık + Wi-Fi pill, hero kart, kartlar, hold butonları, adım kartları, geçmiş, boş durum, tüm animasyonlar ve haptikler | Bölüm 10.2 + 12.3/12.5/12.6/12.7 eksiksiz |
| 7 | **Cihaz paneli + tarama:** form, doğrulama, ikon/renk, önizleme, silme, tarama pipeline'ı, otomatik agent eşleşme | Agent'lı PC taramada MAC'iyle geliyor, ekleyince eşleşiyor |
| 8 | **Otomasyon:** ScheduleEngine, ScheduleRunner + retry, alarmlar, receiver'lar, editor sheet, atla/geri al, WIFI_CONNECT, başarısız bildirimi ve ekranı | Retry senaryosu gerçek cihazda 6 denemeyle bitiyor, detay ekranı doğru |
| 9 | **Ayarlar:** izin satırları, stepper + klavye sayı alanları, kapatma şekli, tile slotları ve modu, titreşim | Tüm izin yönlendirmeleri ve dönüşte canlı güncelleme |
| 10 | **Tam ekran kapılar + onboarding** | Wi-Fi kesilip gelince ekran animasyonla girip çıkıyor; halkalar sürekli |
| 11 | **Hızlı ayarlar:** 3 TileService, durum eşlemesi, overlay A, alternatif B ve C | Kilit ekranında uyandırma; kapatmada kilit açma; üç mod da denenebilir |
| 12 | **Cila:** tam animasyon denetimi (her ekran, her durum), "animasyonları kaldır" davranışı, 120 Hz performans (Macrobenchmark + baseline profile), erişilebilirlik, uygulama ikonu | Jank yok; animasyonsuz geçiş kalmadı |

---

## 18. Açık noktalar / kodlamadan önce doğrulanacaklar

- Haze'in güncel sürümü ve Compose sürümüyle uyumu.
- `ACCESS_LOCAL_NETWORK`'ün Android 17'deki kapsamı ve runtime akışı.
- SSID için gereken izinler (ve konum servisinin açık olması şartı) — Android 17 + One UI 9.
- PendingIntent tabanlı Wi-Fi callback'inin One UI 9 pil yönetimi altında güvenilirliği.
- `/system/bin/ping`'in uygulama sürecinden çalıştırılabilirliği (değilse S4 kapatılır).
- Hazırda Beklet ikonu (Fluent `snowflake` yer tutucu) — Kaan onayı bekliyor. → K17 ile çözüldü.

---

## 19. Karar günlüğü (2026-10-29, Kaan ile netleştirildi)

| # | Konu | Karar |
|---|---|---|
| K1 | Repo | Kökteki mevcut Android projesi `android/` altına taşınır. Paket/namespace `com.kdgames.wake_on_lan` korunur; bölüm 5'teki `.../wol/` kökü = bu paket. |
| K2 | SDK | `compileSdk`/`targetSdk` 37, `minSdk` 35 (bölüm 2 geçerli). |
| K3 | Agent runtime | .NET 8 yerine **.NET 10 LTS** (.NET 8 desteği 10 Kasım 2026'da bitiyor). C#'ta X25519 BCL'de yok → **BouncyCastle.Cryptography** (MIT) kullanılır (sürüm doğrulanacak). |
| K4 | Yeniden deneme / idempotency | Telefon yeniden denemede **aynı `rid` ve aynı yükü** gönderir. Agent son yanıtları (`kaynak IP + rid` anahtarıyla) **10 sn** önbellekte tutar; aynı `rid` gelirse önbellekteki yanıtı tekrar döner, nonce'u yeniden tüketmez, komutu **tekrar uygulamaz**. |
| K5 | Kodlama | X25519 açık anahtarları **ham 32 bayt**, Base64 (standart, padding'li). `canonical` içinde: nonce = Base64 metni olduğu gibi; bool = `true`/`false`; tamsayılar ondalık, baştaki sıfırsız; `cmd` büyük harf; UTF-8. `rid` = 16 küçük harf hex. JSON alan sırası önemsiz. |
| K6 | Eşleşme kopması | `CHALLENGE` → `UNKNOWN_PHONE` gelirse telefon o cihazın `agentId` + anahtarını siler, DISCOVER atar, `paired=false` ise otomatik yeniden eşleşir. `PAIR_DENIED` (ya da DISCOVER `paired=true` ama telefonda anahtar yok) → agent çipi **"Başka telefona eşli"**, ipucu "PC'de `wolagent reset` çalıştır". |
| K7 | Son bilinen durum | Room'da yalnız gösterim amaçlı küçük önbellek tablosu `DeviceStateCache(deviceId, kind, lastPowerAction, uptimeSec?, at)`. Açılışta `stale` gösterim için okunur; doğruluk kaynağı değildir, kararı yine yalnız `StatusEngine` verir. |
| K8 | S2 MAC uyuşmazlığı | O turda S3/S4 sonuçları **yok sayılır**, tur negatif sayılır; IP yeniden çözümleme tetiklenir. |
| K9 | Uyku / kapalı ayrımı | `Off`'a `lastAction: PowerAction?` eklenir. Son geçiş SLEEP → "Uyuyor", HIBERNATE → "Hazırda", diğer → "Kapalı". `On` görülünce temizlenir. Tile "Açık · 2 sa" süresi agent `uptimeSec`'ten; agent yoksa yalnız "Açık". |
| K10 | Arka plan doğrulama | Tile'dan ve zamanlayıcıdan başlayan aksiyonlar expedited `VerifyWorker` içinde doğrulanır; `WakeAttempt(TILE/SCHEDULE)` orada yazılır. Uygulama ön plandayken akış uygulama içindeki koordinatörlerde çalışır. Foreground service yine yok. |
| K11 | Tarama portları | /24 sweep listesine 8001 ve 8002 eklenir (TV tahmini için). |
| K12 | Kapalı PC'ye güç komutu | Mantıksal olarak mümkün değildir: Kilitle/Uyku/Hazırda/Yeniden/Kapat butonları yalnız `On` iken etkin (hero, kart, tile, overlay). Zamanlayıcıda agent aksiyonu tetiklendiğinde PC kapalıysa komut **gönderilmez**, `SKIPPED_ALREADY` yazılır, bildirim yok. |
| K13 | Doze | Zamanlayıcı ana tetikleri `setExactAndAllowWhileIdle`. Retry zinciri için Doze kotası sprint başında dokümandan doğrulanır; 2 dk aralığı bozuyorsa retry alarmları `setAlarmClock` ile kurulur (durum çubuğunda alarm ikonu kabul). |
| K14 | Sıralama | Ekleme sırası. `sortOrder` = ekleme sırası; sıralama UI'ı yok. |
| K15 | Geçmiş / saklama | Güvenilirlik ve "k/n" **son 12 deneme** üzerinden. Cihaz başına son 100 `WakeAttempt`, zamanlayıcı başına son 100 `ScheduleRun` tutulur, eskiler silinir. |
| K16 | NIC / MAC seçimi | Öncelik: kablolu **ve** IP'si telefonla aynı alt ağda → ilk kablolu → MAC'i olan ilk NIC. Cihazın MAC'i boşsa otomatik doldurulur; kullanıcının girdiği MAC sessizce değiştirilmez. |
| K17 | Windows güç ikonları | **Birebir Windows 11** isteniyor: glifler Kaan'ın PC'sindeki **Segoe Fluent Icons** (`C:\Windows\Fonts\SegoeIcons.ttf`) fontundan çıkarılıp `res/drawable/win_*.xml` vector drawable'a çevrilir. Font lisansı başka platformda dağıtıma izin vermez; uygulama yalnız kişisel sideload, **asla yayınlanmaz** (Kaan kabul etti). Kod noktaları Microsoft'un Segoe Fluent Icons listesinden alınır; Claude önizleme PNG'si üretip Kaan'a onaylatır. Font bulunamazsa web'deki resmi glif kaynakları, en son çare Fluent UI System Icons. **Güncelleme (S06, 2026-10-29):** Repo herkese açık olduğu için glifler git'e girmez; derlemede `generateWinIcons` ile `SegoeIcons.ttf`'ten `build/` altına üretilir. Öncelik: `winicons/custom/*.svg` (kullanıcı) → Segoe → Fluent UI (MIT) yedeği. Kaan'ın onayladığı set: Kilitle E72E, Uyku E708, **Hazırda E823 (saat)**, Yeniden E777, Kapat E7E8. |
| K18 | Yedekleme | `android:allowBackup="false"`. |
| K19 | Cihazda test edilebilirlik | Her sprint gerçek cihazda tek başına denenebilir olmalı. Debug build'de ayrı launcher ikonlu **"WoL Debug"** (`DebugActivity`, `src/debug`) her sprintte ilgili test araçlarını sunar; release build'de yoktur. |
| K20 | Test sorumluluğu | Birim + Compose UI testleri (Robolectric, JVM) Claude tarafından koşturulur; her sprint sonunda "Kontrol raporu" ile "baktım, tamam; sen de kontrol et" listesi verilir. Gerçek cihaz/instrumented/Macrobenchmark testleri Kaan'dadır. |
| K21 | Uygulama ikonu | Kaan'ın kaynakları (S00'da geldi): ön plan `APP_Icon.svg` → vector drawable, arka plan `Logo_Background.png`, nihai görünüm referansı `Logo_All.png`. Adaptive + monochrome olarak S00'da uygulandı. |
| K22 | Modern Bekleme'den uyandırma (Kaan'ın PC'si yalnız S0 Low Power Idle, Network Connected destekliyor; S3 yok; magic packet uykudan uyandırmıyor, S03 #5) | PC ayarı değiştirilmez. Uykudan çıkış **agent üzerinden denenir** (S04): 1) yeni komut `SCREEN_ON` — agent konsol oturumunda (WTSQueryUserToken + CreateProcessAsUser) küçük bir yardımcı çalıştırıp tek bir sahte fare hareketi (SendInput) yaptırır → Windows bekleme modundan çıkar, kilit ekranı açılır; 2) `STATUS_OK`'a `display: "on"|"off"` alanı eklenir (GUID_CONSOLE_DISPLAY_STATE) → uykudaki PC "Açık" değil "Uyuyor" görünür (S05); 3) agent'lı cihazda "Uyandır" magic packet ile `SCREEN_ON`'u birlikte dener. Önce S04'te agent'ın uykudayken UDP 40900'e cevap verip vermediği cihazda doğrulanır; vermezse bu yol kapatılır ve uyandırılabilir uyku için Hazırda Beklet önerilir. Protokol değişikliği PROTOCOL.md v1'e eklenir, `vectors.json` yeniden üretilir. |
| K22 sonuç | S04 cihaz testi (2026-10-29) | **Uyandırma kısmı kapandı.** Uykuya yeni giren PC bir kez STATUS'a cevap verdi; derin bekleme modunda hiç cevap vermedi. `SCREEN_ON` agent'a ulaştı ve uygulandı (log'da görünüyor) ama ekran açılmadı: Windows bekleme modunda masaüstü işlemlerine izin vermiyor (Microsoft: Session 0 servisleri 30 sn'de ~1 sn çalışır, yazılımla çıkış belgelenmemiş). `SCREEN_ON` protokolden ve koddan kaldırıldı. `STATUS_OK.display` (imzalı) bilgi amaçlı kaldı. |
| K23 | Modern Beklemeli PC'de "Uyku" | Kaan seçti: telefondan **Uyku = Hazırda Beklet** (PC Modern Beklemeli, S3 yok ve hazırda bekletme açıksa). Böylece PC telefondan yeniden uyandırılabilir. Agent bunu `DISCOVER_REPLY.sleepHibernates=true` ile bildirir. Telefon bu cihazda Uyku sonrası son aksiyonu **"Hazırda"** gösterir (K9). Hazırda bekletme kapalıysa normal uyku denenir. |
| K24 | Kapalı PC'de güç satırı | Kaan (S06 cihaz testi): Kilitle/Uyku/Hazırda/Yeniden/Kapat satırı kapalı (ve geçişteki) PC'de de **görünür ama pasif**; yalnız uyanırken yerini adım kartlarına bırakır. Spec 10.2'deki "Kapalı cihazda güç satırı yok" maddesinin yerine geçer. K12 (komut gönderilmez) aynen geçerli.  (S06 4. tur) Güç satırında **Kapat yok** (büyük orb basılı tut = Kapat): Kilitle, Uyku, Hazırda, Yeniden. |
