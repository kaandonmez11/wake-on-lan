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
_(Claude doldurur)_
