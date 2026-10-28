# WoL agent protokolü v1 (normatif)

Kaynak: `SPEC.md` bölüm 8 + karar günlüğü K4, K5, K6. Bu dosya ile `vectors.json` çelişirse **ikisi de** düzeltilir. Android (`AgentCodec`, `AgentCrypto`) ve agent (`WolAgent.Protocol`) bu dosyaya uymak zorundadır; uyumu `vectors.json` testleri garanti eder.

Anahtar kelimeler: **ZORUNLU**, **YASAK**, **ÖNERİLİR**.

---

## 1. Taşıma

- **UDP 40900**, yalnız IPv4. Her mesaj tek datagramdır: UTF-8 JSON nesnesi, **en fazla 1200 bayt**.
- Agent yalnız **RFC1918** (`10/8`, `172.16/12`, `192.168/16`) ve **link-local** (`169.254/16`) kaynaklı paketleri işler. Diğerlerini **yanıt vermeden** atar.
- Telefondaki tüm soketler Wi-Fi `Network`'üne bağlanır (spec 7.1).

### 1.1 Zarf

Her mesajda bulunur:

| Alan | Tip | Açıklama |
|---|---|---|
| `v` | int | Protokol sürümü, şimdilik `1`. Farklı sürüm → mesaj atılır. |
| `t` | string | Mesaj tipi (§4). |
| `rid` | string | İstek kimliği: 8 rastgele bayt, **16 küçük harf hex** (`^[0-9a-f]{16}$`). Yanıt aynı `rid`'i taşır. |

Bilinmeyen ek alanlar yok sayılır. JSON alan sırası önemsizdir.

### 1.2 Yeniden deneme ve idempotency (K4)

- Telefon yanıt alamazsa isteği **en fazla 2 kez** daha gönderir, aralarında 200 ms bekler.
- Yeniden denemede **aynı `rid` ve bayt bayt aynı yük** ZORUNLUDUR. Yeni nonce veya yeni imza üretmek YASAKTIR.
- Agent, gönderdiği her yanıtı `(kaynak IP, rid)` anahtarıyla **10 sn** önbellekte tutar. Bu sürede aynı anahtarla gelen istek için:
  - önbellekteki yanıtı aynen tekrar gönderir;
  - nonce'u **yeniden tüketmez**, imzayı yeniden kontrol etmez;
  - komutu **tekrar uygulamaz**.
- Böylece ACK kaybolsa bile komut bir kez çalışır ve telefon tekrar denemede ACK'i alır.

---

## 2. Kripto

### 2.1 Kodlama (K5)

| Değer | Kodlama |
|---|---|
| X25519 açık anahtar | Ham 32 bayt (RFC 7748 u-koordinatı), Base64 |
| Base64 | Standart alfabe (`+/`), **padding'li** (`=`) |
| Nonce | 16 rastgele bayt, Base64 (24 karakter) |
| İmza | HMAC-SHA256 çıktısı (32 bayt), Base64 (44 karakter) |
| `canonical` içindeki nonce | Base64 metni **olduğu gibi** |
| `canonical` içindeki bool | `true` / `false` (küçük harf) |
| `canonical` içindeki tamsayı | Ondalık, baştaki sıfırsız, işaretsiz |
| `canonical` içindeki `cmd` | Büyük harf (`LOCK` …) |
| Metin → bayt | UTF-8 |

Özel anahtarlar **asla** ağa çıkmaz.

### 2.2 Anahtarlar

- Agent kurulumda bir X25519 anahtar çifti ve `agentId` (UUID) üretir, kalıcı saklar (spec 13.3).
- Telefon her eşleşmede yeni bir X25519 anahtar çifti üretir. Özel anahtarı eşleşmeden sonra atar; yalnız `K`'yı saklar (Keystore AES-GCM ile sarmalı, yedekleme dışı).

### 2.3 Oturum anahtarı

```
sır = X25519(phonePriv, agentPub) = X25519(agentPriv, phonePub)
K   = HKDF-SHA256(ikm = sır,
                  salt = UTF8(agentId + phoneId),   // ayraçsız birleştirme
                  info = UTF8("wol-pair-v1"),
                  L = 32)
```

Ortak sır sıfır çıkarsa (küçük mertebeli açık anahtar) eşleşme REDDEDİLİR.

### 2.4 İmza

```
sig = Base64( HMAC-SHA256(K, UTF8(canonical)) )
```

`canonical`, alanların sabit sırayla `|` ile birleşimidir:

| Mesaj | canonical |
|---|---|
| `PAIR_OK.proof` | `pair-ok|{phoneId}|{agentId}` |
| `STATUS.sig` | `status|{nonce}` |
| `STATUS_OK.sig` | `status-ok|{nonce}|{agentId}|{uptimeSec}` |
| `CMD.sig` | `cmd|{nonce}|{cmd}|{force}` |
| `ACK.sig` | `ack|{nonce}|{cmd}` |

`STATUS_OK` ve `ACK` içindeki `nonce`, **isteğin** nonce'udur (yanıtta taşınmaz). İmza karşılaştırması sabit zamanlı yapılmalıdır.

---

## 3. Nonce kuralları

- Agent `CHALLENGE`'a 16 baytlık rastgele bir nonce ile yanıt verir. Nonce o `phoneId`'ye bağlıdır.
- Nonce **tek kullanımlıktır** ve **10 sn** geçerlidir. Süresi geçmiş ya da kullanılmış nonce → `ERR NONCE_EXPIRED`.
- §1.2'deki önbellek tekrarı, "kullanım" sayılmaz.

---

## 4. Mesajlar

Örnek JSON'lar ve beklenen imzalar için: `vectors.json` → `messages`.

### 4.1 DISCOVER (broadcast, imzasız)

İstek: `{"v":1,"t":"DISCOVER","rid":"…"}`. Subnet broadcast adresine ve `255.255.255.255`'e gönderilir.

Yanıt (unicast, istek kaynağına):
```json
{"v":1,"t":"DISCOVER_REPLY","rid":"…","agentId":"…","hostname":"OFIS-PC","agentVersion":"1.0.0",
 "paired":false,"hibernate":true,
 "nics":[{"mac":"D8:BB:C1:0A:2F:91","name":"Ethernet","wired":true,"ip":"192.168.1.24"}]}
```
- `mac`: `AA:BB:CC:DD:EE:FF`, büyük harf.
- Telefon NIC seçimini K16'ya göre yapar: kablolu **ve** aynı alt ağda → ilk kablolu → MAC'i olan ilk NIC.

### 4.2 Eşleşme — TOFU

Yalnız agent eşleşmemişken (`paired=false`) mümkündür.

1. Telefon → `{"t":"PAIR_HELLO","rid","phoneId","phoneName","phonePub"}`
2. Agent eşleşmemişse: `K`'yı türetir, `phoneId` + `phonePub` + `K`'yı kalıcı yazar, `paired=true` olur ve yanıt verir:
   `{"t":"PAIR_OK","rid","agentId","agentPub","proof"}`, burada `proof = sig(K, "pair-ok|{phoneId}|{agentId}")`.
3. Telefon `K`'yı kendi tarafında türetir, `proof`'u doğrular. Doğrulanırsa `K`'yı saklar. Doğrulanmazsa eşleşmeyi atar ve "Eşleşme doğrulanamadı" gösterir.
4. Agent zaten eşleşmişse: `{"t":"PAIR_DENIED","rid"}`.

Sıfırlama yalnız PC'den yapılır: `wolagent reset`.

### 4.3 CHALLENGE → NONCE

- İstek: `{"t":"CHALLENGE","rid","phoneId"}`.
- Yanıt: `{"t":"NONCE","rid","nonce"}`.
- `phoneId` bilinmiyorsa: `{"t":"ERR","rid","code":"UNKNOWN_PHONE"}`.

### 4.4 STATUS (S1 sinyali)

- İstek: `{"t":"STATUS","rid","phoneId","nonce","sig"}`, burada `sig = sig(K, "status|{nonce}")`.
- Yanıt: `{"t":"STATUS_OK","rid","agentId","hostname","uptimeSec","hibernate","nics":[…],"sig"}`, burada `sig = sig(K, "status-ok|{nonce}|{agentId}|{uptimeSec}")`.
- Telefon yanıt imzasını doğrulamadan S1'i pozitif SAYMAZ.

### 4.5 CMD → ACK

- İstek: `{"t":"CMD","rid","phoneId","nonce","cmd","force","sig"}`.
  - `cmd ∈ {LOCK, SLEEP, HIBERNATE, SHUTDOWN, RESTART}`.
  - `force`: SHUTDOWN/RESTART'ta cihazın kapatma şekline göre değişir (FORCE → `true`, GRACEFUL → `false`); diğer komutlarda `false`.
  - `sig = sig(K, "cmd|{nonce}|{cmd}|{force}")`.
- Yanıt: `{"t":"ACK","rid","cmd","sig"}`, burada `sig = sig(K, "ack|{nonce}|{cmd}")`.
- Agent ACK'i gönderdikten **300 ms sonra** komutu uygular.
- Aynı anda tek komut çalışır. İşlem sürerken gelen yeni `CMD` → `BUSY`. Aynı `rid`'in tekrarı BUSY sayılmaz, §1.2'ye göre önbellekten yanıtlanır.

### 4.6 ERR

`{"t":"ERR","rid","code"}`. ERR imzasızdır; telefon onu yalnız bilgi olarak kullanır, güvenlik kararı vermez.

| Kod | Ne zaman |
|---|---|
| `UNKNOWN_PHONE` | `phoneId` eşleşmiş telefon değil |
| `BAD_SIG` | İmza doğrulanmadı |
| `NONCE_EXPIRED` | Nonce yok, kullanılmış ya da 10 sn'den eski |
| `UNSUPPORTED` | Komut desteklenmiyor (ör. hibernate kapalı) |
| `BUSY` | Başka komut işleniyor |

---

## 5. Eşleşme kopması (K6)

| Durum | Telefonun davranışı |
|---|---|
| `CHALLENGE` → `UNKNOWN_PHONE` (PC'de `reset` yapılmış) | Cihazın `agentId` + `K`'sını siler, DISCOVER atar; `paired=false` ise otomatik yeniden eşleşir. |
| `PAIR_DENIED`, ya da DISCOVER `paired=true` ama telefonda `K` yok | Agent çipi **"Başka telefona eşli"**, ipucu "PC'de `wolagent reset` çalıştır". |
| `proof` doğrulanmadı | Eşleşme atılır, "Eşleşme doğrulanamadı". |

---

## 6. Test vektörleri

`vectors.json`, `agent/tools/VectorGen` ile deterministik üretilir (`dotnet run --project agent/tools/VectorGen`). **Elle düzenlenmez.**

| Bölüm | İçerik |
|---|---|
| `x25519` | RFC 7748 §6.1 anahtar çiftleri ve ortak sır |
| `hkdf` | RFC 5869 A.1 ve A.3 |
| `pairing` | Sabit kimliklerle `sır` → `K` türetimi (telefon = RFC 7748 Alice, agent = Bob) |
| `signatures` | Her imzalı mesaj için `canonical` + beklenen `sig` (5 komut × force/graceful, 5 ACK dahil) |
| `messages` | 12 mesaj tipinin tam JSON örneği; imzalılar için `canonical`, `sig` ve gerekirse `context` (yanıtta taşınmayan nonce/phoneId) |
| `magicPackets` | MAC → 102 bayt (hex) |
| `broadcast` | IP/prefix → broadcast; `/31`, `/32`, geçersiz girişler → `error` |
| `macAddresses` | Girdi → geçerli mi + normalize biçimi |

Testler:
- Kotlin: `android/app/src/test` (`CryptoVectorTest`, `AgentCodecVectorTest`, `NetPrimitivesTest`). Ayrıca cihazda: WoL Debug → Vektörler.
- C#: `agent/WolAgent.Tests` (`ProtocolVectorTests`).
