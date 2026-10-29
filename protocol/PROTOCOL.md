# WoL agent protokolü v1 (normatif)

Kaynak: `SPEC.md` bölüm 8 + karar günlüğü K4, K5, K6, K22. Bu dosya ile `vectors.json` çelişirse **ikisi de** düzeltilir. Android (`AgentCodec`, `AgentCrypto`) ve agent (`WolAgent.Protocol`) bu dosyaya uymak zorundadır; uyumu `vectors.json` testleri garanti eder.

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
| `STATUS_OK.sig` | `status-ok|{nonce}|{agentId}|{uptimeSec}|{display}` |
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

İstek: `{"v":1,"t":"DISCOVER","rid":"…","phoneId":"…"}`. Subnet broadcast adresine ve `255.255.255.255`'e gönderilir. `phoneId` isteğe bağlıdır (K25); agent onu yalnız `pairedToYou` için kullanır.

Yanıt (unicast, istek kaynağına):
```json
{"v":1,"t":"DISCOVER_REPLY","rid":"…","agentId":"…","hostname":"OFIS-PC","agentVersion":"1.0.0",
 "paired":false,"pairedToYou":false,"hibernate":true,"sleepHibernates":true,
 "nics":[{"mac":"D8:BB:C1:0A:2F:91","name":"Ethernet","wired":true,"ip":"192.168.1.24"}]}
```
- `pairedToYou` (K25): agent, istekteki `phoneId`'ye eşli. Yalnız gösterim ve karar ipucudur (imzasız); asıl kanıt PAIR_HELLO'daki anahtardır. Eski agent göndermez → `false`.
- `mac`: `AA:BB:CC:DD:EE:FF`, büyük harf.
- `sleepHibernates` (K23): bu PC'de `SLEEP` komutu hazırda bekletme yapar (Modern Bekleme'den uzaktan uyandırma olmadığı için). Telefon son aksiyonu "Hazırda" olarak gösterir.
- Telefon NIC seçimini K16'ya göre yapar: kablolu **ve** aynı alt ağda → ilk kablolu → MAC'i olan ilk NIC.

### 4.2 Eşleşme — TOFU

Agent eşleşmemişken (`paired=false`) ya da **zaten bu telefona eşliyken** (K25) mümkündür.

**Telefonun kalıcı anahtarı (K25):** `phonePub` her eşleşmede rastgele değildir. Telefon `ANDROID_ID`'den (uygulama imzası + cihaz başına sabit; uygulama silinip kurulunca değişmez, fabrika ayarında değişir) HKDF-SHA256 ile türetir: `salt = "wol-phone-v1"`, `phoneId = hex(HKDF(info="phone-id", L=16))`, X25519 özel anahtarı `HKDF(info="phone-x25519", L=32)`. `ANDROID_ID` ağa gönderilmez. Agent'ın anahtarı da sabit olduğundan aynı telefon her seferinde aynı `K`'yı türetir.

1. Telefon → `{"t":"PAIR_HELLO","rid","phoneId","phoneName","phonePub"}`
2. Agent eşleşmemişse: `K`'yı türetir, `phoneId` + `phonePub` + `K`'yı kalıcı yazar, `paired=true` olur ve yanıt verir:
   `{"t":"PAIR_OK","rid","agentId","agentPub","proof"}`, burada `proof = sig(K, "pair-ok|{phoneId}|{agentId}")`.
3. Telefon `K`'yı kendi tarafında türetir, `proof`'u doğrular. Doğrulanırsa `K`'yı saklar. Doğrulanmazsa eşleşmeyi atar ve "Eşleşme doğrulanamadı" gösterir.
4. Agent zaten eşleşmişse:
   - aynı `phoneId` **ve** kayıtlı `phonePub` ile birebir aynı → kayıt değişmeden (yalnız `phoneName` güncellenir) aynı `K` ile `PAIR_OK` (uygulama yeniden kurulmuş). Açık anahtarı kopyalayan biri `K`'yı hesaplayamaz, kayıt değişmediği için eşleşmeyi de bozamaz;
   - aksi hâlde `{"t":"PAIR_DENIED","rid"}`.

Eşleşmeyi kaldırmanın iki yolu var: telefondan `CMD UNPAIR` (§4.5, K25) ya da PC'de `wolagent reset`.

### 4.3 CHALLENGE → NONCE

- İstek: `{"t":"CHALLENGE","rid","phoneId"}`.
- Yanıt: `{"t":"NONCE","rid","nonce"}`.
- `phoneId` bilinmiyorsa: `{"t":"ERR","rid","code":"UNKNOWN_PHONE"}`.

### 4.4 STATUS (S1 sinyali)

- İstek: `{"t":"STATUS","rid","phoneId","nonce","sig"}`, burada `sig = sig(K, "status|{nonce}")`.
- Yanıt: `{"t":"STATUS_OK","rid","agentId","hostname","uptimeSec","hibernate","display","nics":[…],"sig"}`, burada `sig = sig(K, "status-ok|{nonce}|{agentId}|{uptimeSec}|{display}")`.
- `display` (K22): konsol ekranının durumu, `GUID_CONSOLE_DISPLAY_STATE`'ten: `on` | `off` | `dimmed` | `unknown`. İmzaya dahildir. Bilgi amaçlıdır; Modern Bekleme'de agent çoğu zaman cevap veremez (K22 sonucu).
- Telefon yanıt imzasını doğrulamadan S1'i pozitif SAYMAZ.

### 4.5 CMD → ACK

- İstek: `{"t":"CMD","rid","phoneId","nonce","cmd","force","sig"}`.
  - `cmd ∈ {LOCK, SLEEP, HIBERNATE, SHUTDOWN, RESTART, UNPAIR}`.
  - `UNPAIR` (K25): telefon cihazı silince gönderir. Agent ACK'i (eski `K` ile imzalı) hazırlar ve eşleşmeyi **hemen** siler; güç komutu değildir, BUSY'ye takılmaz, 300 ms beklemez.
  - `SLEEP` (K23): `DISCOVER_REPLY.sleepHibernates=true` olan PC'de (Modern Bekleme, S3 yok, hazırda bekletme açık) agent hazırda bekletme uygular; aksi hâlde normal uyku.
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
| DISCOVER `paired=true`, `pairedToYou=true`, telefonda `K` yok (uygulama yeniden kuruldu, K25) | PAIR_HELLO atar; agent tanır, aynı `K` ile PAIR_OK. Reset gerekmez. |
| `PAIR_DENIED`, ya da DISCOVER `paired=true`, `pairedToYou=false` ve telefonda `K` yok | Agent çipi **"Başka telefona eşli"**, ipucu "PC'de `wolagent reset` çalıştır". |
| Kullanıcı cihazı sildi (K25) | Hemen `CMD UNPAIR` (≤ 2 sn). Cevap yoksa (PC kapalı) agentId + anahtar "bekleyen UNPAIR" olarak saklanır; agent bir DISCOVER'da görülünce sessizce gönderilir. Bu arada kullanıcı PC'yi yeniden eklerse bekleyen iptal olur. Agent artık bize eşli değilse (`pairedToYou=false`) bekleyen düşer. |
| `proof` doğrulanmadı | Eşleşme atılır, "Eşleşme doğrulanamadı". |

---

## 6. Test vektörleri

`vectors.json`, `agent/tools/VectorGen` ile deterministik üretilir (`dotnet run --project agent/tools/VectorGen`). **Elle düzenlenmez.**

| Bölüm | İçerik |
|---|---|
| `x25519` | RFC 7748 §6.1 anahtar çiftleri ve ortak sır |
| `hkdf` | RFC 5869 A.1 ve A.3 |
| `pairing` | Sabit kimliklerle `sır` → `K` türetimi (telefon = RFC 7748 Alice, agent = Bob) |
| `signatures` | Her imzalı mesaj için `canonical` + beklenen `sig` (5 komut × force/graceful, 5 ACK, STATUS_OK `display` = on/off dahil) |
| `messages` | 12 mesaj tipinin tam JSON örneği ; imzalılar için `canonical`, `sig` ve gerekirse `context` (yanıtta taşınmayan nonce/phoneId) |
| `magicPackets` | MAC → 102 bayt (hex) |
| `broadcast` | IP/prefix → broadcast; `/31`, `/32`, geçersiz girişler → `error` |
| `macAddresses` | Girdi → geçerli mi + normalize biçimi |

Testler:
- Kotlin: `android/app/src/test` (`CryptoVectorTest`, `AgentCodecVectorTest`, `NetPrimitivesTest`). Ayrıca cihazda: WoL Debug → Vektörler.
- C#: `agent/WolAgent.Tests` (`ProtocolVectorTests`).
