# S03 — Ağ çekirdeği

**Spec:** 7.1, 7.2, 7.3 (S3, S4), 14 (`ACCESS_LOCAL_NETWORK`, konum), 18, 17 faz 3 · **Kararlar:** K19

## Amaç
Telefon Wi-Fi'ı doğru takip ediyor, tüm soketleri Wi-Fi'a bağlıyor ve **gerçek PC'yi uyandırıyor** — mobil veri açıkken bile. TCP ve ICMP probe'ları canlı/kapalı ayırt ediyor.

## Doğrulanacak
- Android 17'de `ACCESS_LOCAL_NETWORK`: tam izin adı, runtime akışı, broadcast/UDP/TCP/NSD/`.local` kapsamı.
- SSID okumak için gereken izin kombinasyonu ve konum servisinin açık olma şartı (Android 17 + One UI 9); `FLAG_INCLUDE_LOCATION_INFO`.
- `/system/bin/ping`'in uygulama sürecinden çalıştırılabilirliği (değilse S4 devre dışı bayrağı).

## Görevler
1. **Manifest izinleri** (bölüm 14 tablosu; bu sprintte ağ + konum + exact alarm hariç olanlar).
2. **NetworkMonitor:** `registerNetworkCallback(TRANSPORT_WIFI, FLAG_INCLUDE_LOCATION_INFO)`; `WifiState` (connected, network, ssid?, rssi, signalLevel 0–4, linkSpeedMbps, phoneIp, prefixLength, broadcast). `StateFlow`.
3. **Soket bağlama:** `SocketFactory` soyutlaması; her UDP/TCP soket `wifiNetwork.bindSocket` ile bağlanır; Wi-Fi yoksa hata döner (istisnasız kural, 7.1).
4. **MulticastLock** yardımcısı (tarama/discovery sırasında).
5. **WolSender:** subnet broadcast port 9 + 7, `255.255.255.255` port 9; t=0/2/5 sn; Wi-Fi'a bağlı.
6. **TcpProbe (S3):** `probePorts` paralel connect, bağlandı **veya** connection refused = canlı, 400 ms.
7. **IcmpProbe (S4):** `/system/bin/ping -c 1 -W 1`, 1000 ms; ping yoksa/çalışmıyorsa `available=false`.
8. **İzin akışı:** `ACCESS_LOCAL_NETWORK` ve konum için basit istek yardımcıları (tam ekran kapılar S11'de; şimdilik Debug'da buton).
9. **Debug › Ağ sekmesi:**
   - canlı Wi-Fi kartı: bağlı mı, SSID (yoksa "Wi-Fi"), sinyal seviyesi 0–4, RSSI, link hızı, telefon IP'si, prefix, broadcast;
   - izin durumları + "İzin iste" butonları;
   - IP + MAC girişi (son girilen hatırlanır), **"Uyandır"** butonu (gönderim zamanlarını t=0/2/5 log'lar);
   - "TCP probe" ve "Ping" butonları: sonuç (canlı/yanıt yok) + ms; ping yoksa "S4 kullanılamıyor".

## Otomatik testler (Claude koşturur)
- WolSender: sahte soket üreticisiyle hedef adres/port listesi ve 0/2/5 sn zamanlaması (sanal zaman).
- TcpProbe: refused = canlı, timeout = yanıt yok (sahte soket).
- IcmpProbe: çıktı ayrıştırma (başarılı/başarısız), ping yok senaryosu.
- NetworkMonitor broadcast hesabı (BroadcastResolver entegrasyonu).

## Cihaz testi (Kaan yapar)
Hazırlık: PC'de BIOS WoL açık, ağ kartında "Magic Packet ile uyandır" açık, Hızlı Başlat kapalı, kablolu. PC'nin IP ve MAC'ini `ipconfig /all`'dan not al.

| # | Dene | Beklenen sonuç |
|---|---|---|
| 1 | WoL Debug › Ağ, izinleri ver | Yerel ağ ve konum "✓". Wi-Fi kartında ev ağının SSID'si, sinyal seviyesi, Mbps, telefon IP'si doğru görünür. |
| 2 | Konum iznini Android ayarlarından kaldır, geri dön | SSID yerine "Wi-Fi" yazar; diğer bilgiler durur. |
| 3 | PC açıkken IP gir → "TCP probe" | "Canlı · X ms". |
| 4 | PC açıkken "Ping" | Windows firewall ICMP'yi bloklıyorsa "yanıt yok" — **normal**; ya da "S4 kullanılamıyor". Hangisi olduğunu bana yaz. |
| 5 | PC'yi uykuya al, 30 sn bekle, IP+MAC gir → "Uyandır" | Log'da 3 gönderim (0, 2, 5 sn). PC ~5–20 sn içinde uyanır. |
| 6 | PC'yi **Kapat** (shutdown), 1 dk bekle → "Uyandır" | PC açılır. |
| 7 | Mobil veriyi aç (Wi-Fi da açık) → #5'i tekrarla | PC yine uyanır (paket mobil veriden gitmez). |
| 8 | PC kapalıyken "TCP probe" | "Yanıt yok" (~400 ms sonra). |
| 9 | Wi-Fi'ı kapat | Wi-Fi kartı "Bağlı değil"; "Uyandır" hata mesajı verir ("Wi-Fi yok"), çökmez. |

## Bitti tanımı
Testler yeşil; doğrulanacaklar raporlandı; cihaz testi #1–9 geçti (özellikle #5–#7).

## Sprint sonu raporu
_2026-10-29 · Claude_

### Doğrulananlar
- **`ACCESS_LOCAL_NETWORK`** (developer.android.com › local-network-permission, Android 17 davranış değişiklikleri; SDK 37 `android.jar`'da sabit mevcut):
  - Tam ad `android.permission.ACCESS_LOCAL_NETWORK`. Runtime izin, **NEARBY_DEVICES** grubunda.
  - Yalnız targetSdk ≥ 37'de zorunlu (bizde 37).
  - Kapsamı: giden/gelen TCP, UDP unicast/multicast/broadcast, `NsdManager` dahil tüm ağ API'leri, `.local` çözümleme.
  - İzin yokken TCP genelde zaman aşımına düşer, UDP `EPERM` verir.
  - İstek: tek runtime diyaloğu; aynı gruptaki başka izin önceden verildiyse yeniden sorulmaz.
- **ICMP de bu izne bağlı** (telefonda doğrulandı; doküman ICMP'den hiç bahsetmiyor):
  - Uygulama UID'siyle (`run-as`) izin yokken `ping: sendmsg: Operation not permitted`.
  - İzin geçici verilince `time=7.67 ms`; ardından izin eski hâline döndürüldü.
- **`/system/bin/ping`** uygulama sürecinden çalıştırılabiliyor (Android 17 / One UI, SM-S921B) → S4 açık.
  - `available=false` yalnız ping ikilisi yoksa ya da ICMP soketi açılamıyorsa.
- **SSID:**
  - `NetworkCallback(FLAG_INCLUDE_LOCATION_INFO)` + `ACCESS_FINE_LOCATION` (Android 12+ yaklaşık konumla birlikte istenir) + konum servisi açık olmalı.
  - Biri eksikse Android `<unknown ssid>` döndürür → `ssid=null` → UI "Wi-Fi" yazar; RSSI, hız ve IP gelmeye devam eder.
  - Bu kısım resmi sayfadan birebir okunamadı (referans sayfası index olarak geldi); bilinen platform davranışına göre uygulandı, cihaz testi #1/#2 doğrular.

### Yapılanlar
- **Manifest:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `ACCESS_LOCAL_NETWORK`, `ACCESS_FINE_LOCATION` + `ACCESS_COARSE_LOCATION`. `ManifestPermissionsTest` hepsini kontrol ediyor.
- **`WifiState`** (spec 7.1 alanları) + saf `WifiState.from(...)`:
  - SSID temizleme (tırnak, `<unknown ssid>`);
  - sinyal seviyesini cihaz ölçeğinden 0–4'e çevirme;
  - broadcast `BroadcastResolver` ile (/31, /32 → null).
- **`NetworkMonitor`** (Hilt singleton, `StateFlow<WifiState>`, uygulama ömrü boyunca):
  - `TRANSPORT_WIFI`, INTERNET yeteneği aranmıyor (internetsiz ev ağı da sayılır).
  - `refresh()` callback'i yeniden kaydeder (izin/konum değişince SSID için).
- **`SocketFactory` / `WifiSocketFactory`:** her UDP/TCP soketi `network.bindSocket` ile Wi-Fi'a bağlı. Wi-Fi yoksa `NoWifiException("Wi-Fi yok")`; mobil veriye düşülmez.
- **`MulticastLockHolder`:** referans sayımlı `withLock { }` (S07 taraması kullanacak).
- **`WolSender`:**
  - hedefler subnet broadcast :9, :7 ve 255.255.255.255:9;
  - turlar t=0/2/5 sn (başlangıca göre mutlak), tur başına sonuç ve hedef hatası;
  - broadcast yoksa ya da Wi-Fi giderse `NoWifiException`.
- **`TcpProbe` (S3):**
  - paralel connect, bağlandı **veya** refused = canlı, 400 ms;
  - ilk canlı portta bekleyen soketler kapatılıp hemen dönülüyor;
  - "No route to host" canlı sayılmıyor.
- **`IcmpProbe` (S4):**
  - `/system/bin/ping -c 1 -W 1 <ip>`, 1000 ms; yalnız IPv4 argüman kabul;
  - sonuç `Reply(ms)` / `NoReply(blocked?)` / `Unavailable`; `available` bayrağı.
- **`NetPermissions`:** izin/konum servisi durumları ve istek dizileri. Tam ekran kapılar S11'de.
- **Hilt `NetModule`:** `@IoDispatcher`, `@AppScope`, `Clock`, SocketFactory ve PingRunner bağlamaları.
- **WoL Debug › Ağ sekmesi:**
  - canlı Wi-Fi kartı: SSID / "Wi-Fi", sinyal 0–4 + dBm, Mbps, telefon IP/prefix, broadcast;
  - izin satırları + "İzin iste"; konum servisi durumu;
  - IP + MAC (son girilen hatırlanır), **Uyandır** (tur günlüğü t=0.00/2.00/5.00 sn);
  - TCP probe, Ping.

### Sapmalar / notlar
- **Ping çıktısında `sendmsg: Operation not permitted`** (izin yok) S4'ü kalıcı kapatmıyor.
  - Sonuç `NoReply(blocked=true)`; Debug'da "Engellendi (yerel ağ izni?)".
  - İlk yazdığım hâli bunu "kullanılamıyor" sayardı; telefonda görülünce düzeltildi ve gerçek çıktıyla test eklendi.
- **Ping süreci `bindSocket` ile Wi-Fi'a sabitlenemiyor** (ayrı süreç); varsayılan ağın yönlendirmesini kullanır.
  - Wi-Fi bağlıyken LAN alt ağ rotası wlan0'dadır; mobil veri açıkken de normalde sorun olmaz.
  - S4 zaten yalnız pozitif kanıt, tek başına "kapalı" kararı vermez. 7.1'deki "istisnasız" kuralın tek bilinen istisnası budur.
- **İzin yokken WoL gönderimi** hedef başına `EPERM` hatası olarak günlüğe düşer, çökmez.

### Otomatik test sonuçları
- `./gradlew :app:testDebugUnitTest` → **142/142** yeşil (S03 ile +25). Yeniler (`NetCoreTest`):
  - **WolSender** (sanal zaman): 0/2/5 sn × 3 hedef sırası, paket baytları, tur bildirimi, broadcast yok → hiç gönderim yok, Wi-Fi turlar arasında gidince hata, tek hedef hatası diğerlerini durdurmaz, geçersiz MAC.
  - **TcpProbe** (sahte soket): refused = canlı, kabul = canlı + accepted, timeout = yanıt yok, EHOSTUNREACH = yanıt yok, ilk canlıda bekleyen connect'ler kapatılıp hızlı dönüş, Wi-Fi yok.
  - **IcmpProbe:** başarılı çıktıdan ms, kayıp = yanıt yok, EPERM = engellendi (kullanılabilir kalır), soket açılamıyor = kullanılamaz, ping yok → `available=false` ve süreç bir daha açılmaz, argüman listesi, IPv4 dışı argüman reddi.
  - **WifiState:** broadcast (/24, /22, /31, /32, IP yok), SSID temizleme, 0–4 ölçek, link hızı.
  - **ManifestPermissionsTest:** 8 izin.
- `assembleDebug` + `assembleRelease` yeşil.
- Son derleme telefona kuruldu: WoL Debug açılıyor, `NetworkMonitor` callback'i kaydoluyor, crash log temiz.

### Kontrol raporu — "baktım, tamam; sen de kontrol et"
- [x] Tüm soketler `SocketFactory`'den geliyor; kodda `DatagramSocket()`/`Socket()` doğrudan açılan başka yer yok.
- [x] 0/2/5 sn zamanlaması ve 3 hedef sanal zamanla testte doğrulandı → #5.
- [x] Refused = canlı, timeout = yanıt yok testte → #3, #8.
- [x] Wi-Fi yokken `NoWifiException` → Debug "Wi-Fi yok" → #9.
- [x] Ping uygulama UID'siyle çalışıyor, ICMP yerel ağ iznine bağlı (telefonda) → #4.
- [x] **Kaan: #1–#4, #6–#9 geçti** (2026-10-29). #7 kapalı PC ile yapıldı (mobil veri açıkken uyandı).
- [~] **#5 (uykudan uyandırma) PC kaynaklı olarak olmadı.** Kaan'ın PC'si yalnız **Modern Bekleme (S0 Low Power Idle, Network Connected)** destekliyor; S3 yok (`powercfg /a`).
  - Uykuda ağ bağlı kaldığı için TCP probe ve Ping "canlı" dedi; magic packet S0ix'ten uyandırmadı. Kaan'ın eski WoL uygulaması da uyandıramadı.
  - Uygulamanın paketi doğru: kapalı PC'yi açıyor (#6, #7).

### Açık kalanlar
- **Modern Bekleme (S05/S06'ya not):** Böyle bir PC uykudayken S3/S4'e cevap verebilir. Bu yüzden agent'sız "Açık" görünebilir. Agent'lı cihazda S1 ve `lastAction=SLEEP` (K9) ile ayrım S05'te ele alınacak. Uyandırılabilir "uyku" için Hazırda Beklet önerilir.
- Tam ekran `NoWifiScreen` / `NoPermissionScreen` ve onboarding: S11.
- `MulticastLockHolder` tarama ile birlikte S07'de kullanılacak.
- PendingIntent tabanlı Wi-Fi callback'inin One UI 9 güvenilirliği: S09 ("eve gelince").
