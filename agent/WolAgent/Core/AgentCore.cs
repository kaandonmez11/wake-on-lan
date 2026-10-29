using System.Net;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using WolAgent.Protocol;

namespace WolAgent.Core;

/// <summary>
/// Protokolün tamamı (PROTOCOL.md): kaynak filtresi, zarf, TOFU eşleşme, nonce, imzalar,
/// K4 yanıt önbelleği, tek komut + BUSY, ACK'ten 300 ms sonra yürütme. Soket bilmez; testlenir.
/// </summary>
public sealed partial class AgentCore(
    IConfigStore config,
    ISystemInfo system,
    ICommandExecutor executor,
    TimeProvider time,
    IRequestLog log,
    Func<byte[]>? nonceSource = null)
{
    public static readonly TimeSpan CommandDelay = TimeSpan.FromMilliseconds(300);

    private readonly Func<byte[]> _nonceSource = nonceSource ?? (() => RandomNumberGenerator.GetBytes(AgentProtocol.NonceBytes));
    private readonly Dictionary<string, (string PhoneId, DateTimeOffset Expires)> _nonces = new();
    private readonly Dictionary<(string Ip, string Rid), (byte[] Reply, DateTimeOffset Expires)> _cache = new();
    private readonly Lock _gate = new();
    private bool _busy;

    /// <summary>Son zamanlanan komut (testler bekleyebilsin diye).</summary>
    public Task? PendingCommand { get; private set; }

    [GeneratedRegex("^[0-9a-f]{16}$")]
    private static partial Regex RidPattern();

    /// <summary>Bir datagramı işler; yanıt yoksa (atılan paket) null.</summary>
    public byte[]? Handle(ReadOnlySpan<byte> datagram, IPAddress source)
    {
        var src = source.IsIPv4MappedToIPv6 ? source.MapToIPv4() : source;
        if (!IsLocalSource(src) || datagram.Length > AgentProtocol.MaxDatagramBytes) return null;

        JsonObject msg;
        try
        {
            msg = JsonNode.Parse(Encoding.UTF8.GetString(datagram))?.AsObject() ?? throw new JsonException();
        }
        catch (Exception e) when (e is JsonException or InvalidOperationException or ArgumentException)
        {
            return null;
        }
        if (Int(msg, "v") != AgentProtocol.Version) return null;
        var rid = Str(msg, "rid");
        var type = Str(msg, "t");
        if (rid is null || type is null || !RidPattern().IsMatch(rid)) return null;

        lock (_gate)
        {
            var now = time.GetUtcNow();
            Prune(now);
            var key = (src.ToString(), rid);
            if (_cache.TryGetValue(key, out var cached))
            {
                log.Write(type, key.Item1, "önbellekten tekrar");
                return cached.Reply;
            }

            JsonObject? reply;
            try
            {
                reply = Dispatch(type, rid, msg, now);
            }
            catch (Exception e) when (e is FormatException or CryptographicException or InvalidOperationException)
            {
                log.Write(type, key.Item1, "geçersiz: " + e.GetType().Name);
                return null;
            }
            if (reply is null)
            {
                log.Write(type, key.Item1, "yok sayıldı");
                return null;
            }
            var bytes = Encoding.UTF8.GetBytes(reply.ToJsonString());
            _cache[key] = (bytes, now + AgentProtocol.ResponseCacheLifetime);
            log.Write(type, key.Item1, Str(reply, "t") == "ERR" ? "ERR " + Str(reply, "code") : Str(reply, "t")!);
            return bytes;
        }
    }

    private JsonObject? Dispatch(string type, string rid, JsonObject msg, DateTimeOffset now) => type switch
    {
        "DISCOVER" => DiscoverReply(rid, msg),
        "PAIR_HELLO" => PairHello(rid, msg, now),
        "CHALLENGE" => Challenge(rid, msg, now),
        "STATUS" => Status(rid, msg, now),
        "CMD" => Command(rid, msg, now),
        _ => null, // yanıt tipleri ve bilinmeyenler
    };

    private JsonObject DiscoverReply(string rid, JsonObject msg)
    {
        var cfg = config.Load();
        // K25: isteyen telefon zaten eşli telefon mu (yalnız gösterim; güvenlik kararı PAIR_HELLO'da verilir).
        var askingPhone = Str(msg, "phoneId");
        var pairedToYou = cfg.Pairing is not null && askingPhone is not null && askingPhone == cfg.Pairing.PhoneId;
        return Envelope("DISCOVER_REPLY", rid,
            ("agentId", cfg.AgentId), ("hostname", system.Hostname), ("agentVersion", system.AgentVersion),
            ("paired", cfg.Pairing is not null), ("pairedToYou", pairedToYou), ("hibernate", system.HibernateSupported),
            ("sleepHibernates", system.SleepHibernates), ("nics", NicsJson()));
    }

    private JsonObject? PairHello(string rid, JsonObject msg, DateTimeOffset now)
    {
        var phoneId = Str(msg, "phoneId");
        var phoneName = Str(msg, "phoneName") ?? "";
        var pubText = Str(msg, "phonePub");
        if (string.IsNullOrWhiteSpace(phoneId) || pubText is null) return null;

        var cfg = config.Load();
        var phonePub = Convert.FromBase64String(pubText);
        if (phonePub.Length != X25519Keys.KeySize) return null;

        // K25: eşli telefon (aynı phoneId + aynı kalıcı açık anahtar) yeniden kurulmuş olabilir. Agent'ın anahtarı da
        // telefonunki de sabit olduğundan K değişmez: kayıt olduğu gibi kalır, yalnız PAIR_OK tekrar gönderilir.
        // Açık anahtarı kopyalayan biri K'yı hesaplayamaz; kayıt değişmediği için eşleşmeyi de bozamaz.
        if (cfg.Pairing is { } existing)
        {
            if (existing.PhoneId != phoneId || !CryptographicOperations.FixedTimeEquals(existing.PhonePub, phonePub))
                return Envelope("PAIR_DENIED", rid);
            if (existing.PhoneName != phoneName) config.SavePairing(existing with { PhoneName = phoneName });
            return PairOk(rid, cfg, phoneId, existing.SessionKey);
        }

        var shared = X25519Keys.SharedSecret(cfg.PrivateKey, phonePub); // sıfır sır → CryptographicException → atılır
        var key = AgentCrypto.DeriveSessionKey(shared, cfg.AgentId, phoneId);
        config.SavePairing(new Pairing(phoneId, phoneName, phonePub, key, now));
        return PairOk(rid, cfg, phoneId, key);
    }

    private static JsonObject PairOk(string rid, AgentConfig cfg, string phoneId, byte[] key) =>
        Envelope("PAIR_OK", rid,
            ("agentId", cfg.AgentId),
            ("agentPub", Convert.ToBase64String(X25519Keys.PublicKey(cfg.PrivateKey))),
            ("proof", AgentCrypto.Sign(key, Canonical.PairOk(phoneId, cfg.AgentId))));

    private JsonObject Challenge(string rid, JsonObject msg, DateTimeOffset now)
    {
        var pairing = config.Load().Pairing;
        var phoneId = Str(msg, "phoneId");
        if (pairing is null || phoneId != pairing.PhoneId) return Err(rid, "UNKNOWN_PHONE");
        var nonce = Convert.ToBase64String(_nonceSource());
        _nonces[nonce] = (phoneId, now + AgentProtocol.NonceLifetime);
        return Envelope("NONCE", rid, ("nonce", nonce));
    }

    private JsonObject Status(string rid, JsonObject msg, DateTimeOffset now)
    {
        var cfg = config.Load();
        if (Authenticate(msg, cfg, now, n => Canonical.Status(n), out var nonce) is { } error) return Err(rid, error);
        var uptime = system.UptimeSec;
        var display = system.Display;
        return Envelope("STATUS_OK", rid,
            ("agentId", cfg.AgentId), ("hostname", system.Hostname), ("uptimeSec", uptime),
            ("hibernate", system.HibernateSupported), ("display", display), ("nics", NicsJson()),
            ("sig", AgentCrypto.Sign(cfg.Pairing!.SessionKey, Canonical.StatusOk(nonce, cfg.AgentId, uptime, display))));
    }

    private JsonObject Command(string rid, JsonObject msg, DateTimeOffset now)
    {
        var cfg = config.Load();
        var cmd = Str(msg, "cmd") ?? "";
        var force = Bool(msg, "force") ?? false;
        if (Authenticate(msg, cfg, now, n => Canonical.Cmd(n, cmd, force), out var nonce) is { } error) return Err(rid, error);
        if (!AgentProtocol.Commands.Contains(cmd)) return Err(rid, "UNSUPPORTED");
        if (cmd == "UNPAIR")
        {
            // K25: telefon cihazı sildi → eşleşme hemen silinir; ACK eski anahtarla imzalanır.
            var ack = Envelope("ACK", rid, ("cmd", cmd), ("sig", AgentCrypto.Sign(cfg.Pairing!.SessionKey, Canonical.Ack(nonce, cmd))));
            config.SavePairing(null);
            log.Write("CMD", "-", "UNPAIR: eşleşme silindi");
            return ack;
        }
        if (cmd == "HIBERNATE" && !system.HibernateSupported) return Err(rid, "UNSUPPORTED");
        if (_busy) return Err(rid, "BUSY");

        _busy = true;
        PendingCommand = RunLaterAsync(cmd, force);
        return Envelope("ACK", rid, ("cmd", cmd), ("sig", AgentCrypto.Sign(cfg.Pairing!.SessionKey, Canonical.Ack(nonce, cmd))));
    }

    /// <summary>phoneId → imza → nonce sırasıyla doğrular; hata kodu ya da null.</summary>
    private string? Authenticate(JsonObject msg, AgentConfig cfg, DateTimeOffset now, Func<string, string> canonical, out string nonce)
    {
        nonce = Str(msg, "nonce") ?? "";
        var phoneId = Str(msg, "phoneId");
        if (cfg.Pairing is null || phoneId != cfg.Pairing.PhoneId) return "UNKNOWN_PHONE";
        if (!AgentCrypto.Verify(cfg.Pairing.SessionKey, canonical(nonce), Str(msg, "sig") ?? "")) return "BAD_SIG";
        // Tek kullanımlık: doğrulanınca hemen tüketilir.
        if (!_nonces.Remove(nonce, out var entry) || entry.PhoneId != phoneId || entry.Expires <= now) return "NONCE_EXPIRED";
        return null;
    }

    private async Task RunLaterAsync(string cmd, bool force)
    {
        try
        {
            await Task.Delay(CommandDelay, time).ConfigureAwait(false);
            await executor.ExecuteAsync(cmd, force, CancellationToken.None).ConfigureAwait(false);
            log.Write("CMD", "-", $"{cmd} uygulandı");
        }
        catch (Exception e)
        {
            log.Write("CMD", "-", $"{cmd} başarısız: {e.GetType().Name}: {e.Message}");
        }
        finally
        {
            lock (_gate) _busy = false;
        }
    }

    private void Prune(DateTimeOffset now)
    {
        foreach (var k in _cache.Where(e => e.Value.Expires <= now).Select(e => e.Key).ToList()) _cache.Remove(k);
        foreach (var k in _nonces.Where(e => e.Value.Expires <= now).Select(e => e.Key).ToList()) _nonces.Remove(k);
    }

    private JsonArray NicsJson() => new(system.Nics()
        .Select(n => (JsonNode)new JsonObject { ["mac"] = n.Mac, ["name"] = n.Name, ["wired"] = n.Wired, ["ip"] = n.Ip })
        .ToArray());

    /// <summary>Yalnız RFC1918 ve link-local IPv4 (PROTOCOL.md §1).</summary>
    public static bool IsLocalSource(IPAddress ip)
    {
        if (ip.AddressFamily != AddressFamily.InterNetwork) return false;
        var b = ip.GetAddressBytes();
        return b[0] == 10
               || (b[0] == 172 && b[1] >= 16 && b[1] <= 31)
               || (b[0] == 192 && b[1] == 168)
               || (b[0] == 169 && b[1] == 254);
    }

    private static JsonObject Err(string rid, string code) => Envelope("ERR", rid, ("code", code));

    private static JsonObject Envelope(string type, string rid, params (string Key, object Value)[] fields)
    {
        var o = new JsonObject { ["v"] = AgentProtocol.Version, ["t"] = type, ["rid"] = rid };
        foreach (var (k, v) in fields)
            o[k] = v switch
            {
                JsonNode node => node,
                string s => s,
                bool b => b,
                long l => l,
                int i => i,
                _ => throw new ArgumentException($"Desteklenmeyen alan: {k}"),
            };
        return o;
    }

    private static string? Str(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<string>(out var s) ? s : null;

    private static bool? Bool(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<bool>(out var b) ? b : null;

    private static int? Int(JsonObject o, string key) =>
        o[key] is JsonValue v && v.TryGetValue<int>(out var i) ? i : null;
}
