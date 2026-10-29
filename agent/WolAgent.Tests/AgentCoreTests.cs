using System.Net;
using System.Text;
using System.Text.Json.Nodes;
using Microsoft.Extensions.Time.Testing;
using WolAgent.Core;
using WolAgent.Protocol;
using WolAgent.Storage;

namespace WolAgent.Tests;

internal sealed class MemoryConfigStore : IConfigStore
{
    public AgentConfig Config { get; set; } = new(Guid.NewGuid().ToString(), X25519Keys.GeneratePrivateKey(), null);

    public AgentConfig Load() => Config;

    public void SavePairing(Pairing? pairing) => Config = Config with { Pairing = pairing };
}

internal sealed class FakeSystem : ISystemInfo
{
    public string Hostname => "OFIS-PC";
    public string AgentVersion => "1.0.0";
    public long UptimeSec { get; set; } = 3600;
    public bool HibernateSupported { get; set; } = true;
    public bool SleepHibernates { get; set; }
    public string Display { get; set; } = "on";

    public IReadOnlyList<AgentNic> Nics() => [new("D8:BB:C1:0A:2F:91", "Ethernet", true, "192.168.1.26")];
}

internal sealed class FakeExecutor : ICommandExecutor
{
    public List<(string Cmd, bool Force)> Calls { get; } = [];
    public TaskCompletionSource? Gate { get; set; }

    public async Task ExecuteAsync(string cmd, bool force, CancellationToken ct)
    {
        lock (Calls) Calls.Add((cmd, force));
        if (Gate is not null) await Gate.Task;
    }
}

internal sealed class ListLog : IRequestLog
{
    public List<string> Lines { get; } = [];

    public void Write(string type, string source, string result)
    {
        lock (Lines) Lines.Add($"{type}\t{source}\t{result}");
    }
}

/// <summary>Testte telefon rolü: anahtar üretir, mesaj kurar, yanıt imzalarını doğrular.</summary>
internal sealed class TestPhone(string phoneId = "6f1c2a4e-8b3d-4f7a-9c21-0d5e7b9a1f34")
{
    private readonly byte[] _priv = X25519Keys.GeneratePrivateKey();
    private int _rid;

    public string PhoneId { get; } = phoneId;
    public byte[]? Key { get; private set; }
    public string NextRid() => (++_rid).ToString("x16");

    public static byte[] Bytes(JsonObject o) => Encoding.UTF8.GetBytes(o.ToJsonString());

    public static JsonObject Msg(string type, string rid, params (string K, JsonNode? V)[] fields)
    {
        var o = new JsonObject { ["v"] = 1, ["t"] = type, ["rid"] = rid };
        foreach (var (k, v) in fields) o[k] = v;
        return o;
    }

    public JsonObject Hello(string rid) => Msg("PAIR_HELLO", rid,
        ("phoneId", PhoneId), ("phoneName", "Galaxy S24"), ("phonePub", Convert.ToBase64String(X25519Keys.PublicKey(_priv))));

    public void AcceptPairOk(JsonObject reply)
    {
        var agentPub = Convert.FromBase64String(reply["agentPub"]!.GetValue<string>());
        var agentId = reply["agentId"]!.GetValue<string>();
        var key = AgentCrypto.DeriveSessionKey(X25519Keys.SharedSecret(_priv, agentPub), agentId, PhoneId);
        Assert.True(AgentCrypto.Verify(key, Canonical.PairOk(PhoneId, agentId), reply["proof"]!.GetValue<string>()));
        Key = key;
    }

    public JsonObject Challenge(string rid) => Msg("CHALLENGE", rid, ("phoneId", PhoneId));

    public JsonObject Status(string rid, string nonce) => Msg("STATUS", rid,
        ("phoneId", PhoneId), ("nonce", nonce), ("sig", AgentCrypto.Sign(Key!, Canonical.Status(nonce))));

    public JsonObject Cmd(string rid, string nonce, string cmd, bool force = false) => Msg("CMD", rid,
        ("phoneId", PhoneId), ("nonce", nonce), ("cmd", cmd), ("force", force),
        ("sig", AgentCrypto.Sign(Key!, Canonical.Cmd(nonce, cmd, force))));
}

public class AgentCoreTests
{
    private static readonly IPAddress Phone = IPAddress.Parse("192.168.1.88");

    private readonly MemoryConfigStore _config = new();
    private readonly FakeSystem _system = new();
    private readonly FakeExecutor _executor = new();
    private readonly FakeTimeProvider _time = new(new DateTimeOffset(2026, 10, 29, 12, 0, 0, TimeSpan.Zero));
    private readonly ListLog _log = new();
    private readonly AgentCore _core;
    private readonly TestPhone _phone = new();

    public AgentCoreTests()
    {
        _core = new AgentCore(_config, _system, _executor, _time, _log);
    }

    private JsonObject? Send(JsonObject msg, IPAddress? from = null)
    {
        var reply = _core.Handle(TestPhone.Bytes(msg), from ?? Phone);
        return reply is null ? null : JsonNode.Parse(reply)!.AsObject();
    }

    private static string T(JsonObject? o) => o!["t"]!.GetValue<string>();
    private static string Code(JsonObject? o) => o!["code"]!.GetValue<string>();

    private void Pair()
    {
        var reply = Send(_phone.Hello(_phone.NextRid()));
        Assert.Equal("PAIR_OK", T(reply));
        _phone.AcceptPairOk(reply!);
    }

    private string Nonce()
    {
        var reply = Send(_phone.Challenge(_phone.NextRid()));
        Assert.Equal("NONCE", T(reply));
        var nonce = reply!["nonce"]!.GetValue<string>();
        Assert.Equal(16, Convert.FromBase64String(nonce).Length);
        return nonce;
    }

    // --- taşıma / zarf ----------------------------------------------------------------------

    [Theory]
    [InlineData("10.0.0.5", true)]
    [InlineData("172.16.0.1", true)]
    [InlineData("172.31.255.1", true)]
    [InlineData("172.32.0.1", false)]
    [InlineData("192.168.1.88", true)]
    [InlineData("169.254.10.2", true)]
    [InlineData("8.8.8.8", false)]
    [InlineData("127.0.0.1", false)]
    [InlineData("100.64.0.1", false)]
    public void KaynakFiltresi_YalnizRfc1918VeLinkLocal(string ip, bool accepted)
    {
        var reply = Send(TestPhone.Msg("DISCOVER", "a1b2c3d4e5f60718"), IPAddress.Parse(ip));
        Assert.Equal(accepted, reply is not null);
    }

    [Fact]
    public void Ipv4MappedIpv6KaynakKabulEdilir()
    {
        Assert.NotNull(Send(TestPhone.Msg("DISCOVER", "a1b2c3d4e5f60718"), Phone.MapToIPv6()));
    }

    [Fact]
    public void GecersizPaketlerYanitsizAtilir()
    {
        Assert.Null(_core.Handle("{bozuk"u8, Phone));
        Assert.Null(_core.Handle("[]"u8, Phone));
        Assert.Null(Send(new JsonObject { ["v"] = 2, ["t"] = "DISCOVER", ["rid"] = "a1b2c3d4e5f60718" }));
        Assert.Null(Send(TestPhone.Msg("DISCOVER", "A1B2C3D4E5F60718")));
        Assert.Null(Send(TestPhone.Msg("DISCOVER", "a1b2")));
        Assert.Null(Send(TestPhone.Msg("DISCOVER_REPLY", "a1b2c3d4e5f60718")));
        var big = TestPhone.Msg("DISCOVER", "a1b2c3d4e5f60718", ("pad", new string('x', 1300)));
        Assert.Null(Send(big));
    }

    [Fact]
    public void Discover_KimlikVeNicler()
    {
        var reply = Send(TestPhone.Msg("DISCOVER", "a1b2c3d4e5f60718"))!;
        Assert.Equal("DISCOVER_REPLY", T(reply));
        Assert.Equal("a1b2c3d4e5f60718", reply["rid"]!.GetValue<string>());
        Assert.Equal(_config.Config.AgentId, reply["agentId"]!.GetValue<string>());
        Assert.Equal("OFIS-PC", reply["hostname"]!.GetValue<string>());
        Assert.False(reply["paired"]!.GetValue<bool>());
        Assert.True(reply["hibernate"]!.GetValue<bool>());
        Assert.False(reply["sleepHibernates"]!.GetValue<bool>());
        _system.SleepHibernates = true;
        Assert.True(Send(TestPhone.Msg("DISCOVER", "a1b2c3d4e5f6071a"))!["sleepHibernates"]!.GetValue<bool>());
        Assert.Equal("D8:BB:C1:0A:2F:91", reply["nics"]![0]!["mac"]!.GetValue<string>());
        Assert.True(Encoding.UTF8.GetByteCount(reply.ToJsonString()) <= AgentProtocol.MaxDatagramBytes);
    }

    // --- TOFU -------------------------------------------------------------------------------

    [Fact]
    public void Tofu_IlkKabul_IkinciReddedilir_ResetSonrasiKabul()
    {
        Pair();
        Assert.NotNull(_config.Config.Pairing);
        Assert.Equal(_phone.Key, _config.Config.Pairing!.SessionKey);
        Assert.True(Send(TestPhone.Msg("DISCOVER", "a1b2c3d4e5f60719"))!["paired"]!.GetValue<bool>());

        var other = new TestPhone("11111111-2222-3333-4444-555555555555");
        Assert.Equal("PAIR_DENIED", T(Send(other.Hello("00000000000000aa"))));
        Assert.Equal(_phone.PhoneId, _config.Config.Pairing!.PhoneId);

        _config.SavePairing(null); // wolagent reset
        var reply = Send(other.Hello("00000000000000ab"));
        Assert.Equal("PAIR_OK", T(reply));
        other.AcceptPairOk(reply!);
    }

    [Fact]
    public void Tofu_GecersizAcikAnahtarEslesmeYapmaz()
    {
        var zero = TestPhone.Msg("PAIR_HELLO", "00000000000000ac",
            ("phoneId", "p"), ("phoneName", "x"), ("phonePub", Convert.ToBase64String(new byte[32])));
        Assert.Null(Send(zero));
        var shortKey = TestPhone.Msg("PAIR_HELLO", "00000000000000ad",
            ("phoneId", "p"), ("phoneName", "x"), ("phonePub", Convert.ToBase64String(new byte[5])));
        Assert.Null(Send(shortKey));
        Assert.Null(_config.Config.Pairing);
    }

    // --- challenge / STATUS -----------------------------------------------------------------

    [Fact]
    public void Challenge_BilinmeyenTelefon()
    {
        Assert.Equal("UNKNOWN_PHONE", Code(Send(_phone.Challenge("00000000000000b1"))));
        Pair();
        Assert.Equal("UNKNOWN_PHONE", Code(Send(new TestPhone("baska").Challenge("00000000000000b2"))));
    }

    [Fact]
    public void Status_ImzaliYanitVeDisplay()
    {
        Pair();
        _system.Display = "off";
        var nonce = Nonce();
        var reply = Send(_phone.Status(_phone.NextRid(), nonce))!;
        Assert.Equal("STATUS_OK", T(reply));
        Assert.Equal("off", reply["display"]!.GetValue<string>());
        Assert.Equal(3600, reply["uptimeSec"]!.GetValue<long>());
        var canonical = Canonical.StatusOk(nonce, _config.Config.AgentId, 3600, "off");
        Assert.True(AgentCrypto.Verify(_phone.Key!, canonical, reply["sig"]!.GetValue<string>()));
        // display imzaya dahil: değiştirilirse doğrulanmaz.
        Assert.False(AgentCrypto.Verify(_phone.Key!, Canonical.StatusOk(nonce, _config.Config.AgentId, 3600, "on"),
            reply["sig"]!.GetValue<string>()));
    }

    [Fact]
    public void Nonce_TekKullanimlik()
    {
        Pair();
        var nonce = Nonce();
        Assert.Equal("STATUS_OK", T(Send(_phone.Status(_phone.NextRid(), nonce))));
        Assert.Equal("NONCE_EXPIRED", Code(Send(_phone.Status(_phone.NextRid(), nonce))));
    }

    [Fact]
    public void Nonce_10SnSonraGecersiz()
    {
        Pair();
        var nonce = Nonce();
        _time.Advance(TimeSpan.FromSeconds(10));
        Assert.Equal("NONCE_EXPIRED", Code(Send(_phone.Status(_phone.NextRid(), nonce))));

        var fresh = Nonce();
        _time.Advance(TimeSpan.FromSeconds(9.9));
        Assert.Equal("STATUS_OK", T(Send(_phone.Status(_phone.NextRid(), fresh))));
    }

    [Fact]
    public void YanlisImza_BadSig_VeNonceTuketilmez()
    {
        Pair();
        var nonce = Nonce();
        var forged = TestPhone.Msg("STATUS", _phone.NextRid(),
            ("phoneId", _phone.PhoneId), ("nonce", nonce), ("sig", AgentCrypto.Sign(new byte[32], Canonical.Status(nonce))));
        Assert.Equal("BAD_SIG", Code(Send(forged)));
        Assert.Equal("BAD_SIG", Code(Send(TestPhone.Msg("STATUS", _phone.NextRid(),
            ("phoneId", _phone.PhoneId), ("nonce", nonce), ("sig", "base64-degil!")))));
        Assert.Equal("STATUS_OK", T(Send(_phone.Status(_phone.NextRid(), nonce))));
    }

    [Fact]
    public void BaskaTelefonunNonceuKullanilamaz()
    {
        Pair();
        var nonce = Nonce();
        var other = new TestPhone("baska");
        var msg = TestPhone.Msg("STATUS", "00000000000000f1",
            ("phoneId", "baska"), ("nonce", nonce), ("sig", AgentCrypto.Sign(_phone.Key!, Canonical.Status(nonce))));
        Assert.Equal("UNKNOWN_PHONE", Code(Send(msg)));
        Assert.NotNull(other);
    }

    // --- CMD --------------------------------------------------------------------------------

    [Fact]
    public async Task Cmd_Ack_300msSonraUygulanir()
    {
        Pair();
        var nonce = Nonce();
        var reply = Send(_phone.Cmd(_phone.NextRid(), nonce, "SHUTDOWN", force: true))!;
        Assert.Equal("ACK", T(reply));
        Assert.True(AgentCrypto.Verify(_phone.Key!, Canonical.Ack(nonce, "SHUTDOWN"), reply["sig"]!.GetValue<string>()));

        _time.Advance(TimeSpan.FromMilliseconds(299));
        await Task.Delay(50);
        Assert.Empty(_executor.Calls);
        _time.Advance(TimeSpan.FromMilliseconds(1));
        await _core.PendingCommand!.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Equal([("SHUTDOWN", true)], _executor.Calls);
    }

    [Fact]
    public async Task Cmd_IslemSurerkenBusy_BitinceKabul()
    {
        Pair();
        _executor.Gate = new TaskCompletionSource();
        Assert.Equal("ACK", T(Send(_phone.Cmd(_phone.NextRid(), Nonce(), "LOCK"))));
        Assert.Equal("BUSY", Code(Send(_phone.Cmd(_phone.NextRid(), Nonce(), "SLEEP"))));

        _time.Advance(AgentCore.CommandDelay);
        _executor.Gate.SetResult();
        await _core.PendingCommand!.WaitAsync(TimeSpan.FromSeconds(5));

        Assert.Equal("ACK", T(Send(_phone.Cmd(_phone.NextRid(), Nonce(), "SLEEP"))));
    }

    [Fact]
    public async Task Cmd_YurutucuHatasiBusyBirakmaz()
    {
        Pair();
        var failing = new AgentCore(_config, _system, new ThrowingExecutor(), _time, _log);
        var send = (JsonObject m) => JsonNode.Parse(failing.Handle(TestPhone.Bytes(m), Phone)!)!.AsObject();
        Assert.Equal("NONCE", T(send(_phone.Challenge("00000000000000c1"))));
        var nonce = send(_phone.Challenge("00000000000000c2"))["nonce"]!.GetValue<string>();
        Assert.Equal("ACK", T(send(_phone.Cmd("00000000000000c3", nonce, "LOCK"))));
        _time.Advance(AgentCore.CommandDelay);
        await failing.PendingCommand!.WaitAsync(TimeSpan.FromSeconds(5));
        var nonce2 = send(_phone.Challenge("00000000000000c4"))["nonce"]!.GetValue<string>();
        Assert.Equal("ACK", T(send(_phone.Cmd("00000000000000c5", nonce2, "LOCK"))));
        Assert.Contains(_log.Lines, l => l.Contains("LOCK başarısız"));
    }

    private sealed class ThrowingExecutor : ICommandExecutor
    {
        public Task ExecuteAsync(string cmd, bool force, CancellationToken ct) => throw new InvalidOperationException("olmadı");
    }

    [Fact]
    public void Cmd_HibernateKapaliysaUnsupported_BilinmeyenKomutUnsupported()
    {
        Pair();
        _system.HibernateSupported = false;
        Assert.Equal("UNSUPPORTED", Code(Send(_phone.Cmd(_phone.NextRid(), Nonce(), "HIBERNATE"))));
        Assert.Equal("UNSUPPORTED", Code(Send(_phone.Cmd(_phone.NextRid(), Nonce(), "FORMAT_C"))));
        Assert.Empty(_executor.Calls);
    }

    [Fact]
    public void Cmd_ScreenOnArtikYok_K22Kapandi()
    {
        Pair();
        Assert.Equal("UNSUPPORTED", Code(Send(_phone.Cmd(_phone.NextRid(), Nonce(), "SCREEN_ON"))));
        Assert.Empty(_executor.Calls);
    }

    [Fact]
    public void Cmd_ForceImzaDisindaDegistirilemez()
    {
        Pair();
        var nonce = Nonce();
        var msg = _phone.Cmd(_phone.NextRid(), nonce, "SHUTDOWN", force: false);
        msg["force"] = true;
        Assert.Equal("BAD_SIG", Code(Send(msg)));
    }

    // --- K4 rid önbelleği -------------------------------------------------------------------

    [Fact]
    public async Task Rid_AyniIstekAyniYanit_KomutBirKez()
    {
        Pair();
        var nonce = Nonce();
        var request = TestPhone.Bytes(_phone.Cmd("00000000000000d1", nonce, "RESTART"));
        var first = _core.Handle(request, Phone);
        var second = _core.Handle(request, Phone);
        var third = _core.Handle(request, Phone);
        Assert.Equal(first, second);
        Assert.Equal(first, third);
        Assert.Equal("ACK", T(JsonNode.Parse(first)!.AsObject()));

        _time.Advance(AgentCore.CommandDelay);
        await _core.PendingCommand!.WaitAsync(TimeSpan.FromSeconds(5));
        Assert.Single(_executor.Calls);
    }

    [Fact]
    public void Rid_FarkliKaynakIcinOnbellekPaylasilmaz_10SnSonraDuser()
    {
        Pair();
        var nonce = Nonce();
        var request = TestPhone.Bytes(_phone.Status("00000000000000e1", nonce));
        Assert.Equal("STATUS_OK", T(JsonNode.Parse(_core.Handle(request, Phone)!)!.AsObject()));
        var otherIp = IPAddress.Parse("192.168.1.77");
        Assert.Equal("NONCE_EXPIRED", Code(JsonNode.Parse(_core.Handle(request, otherIp)!)!.AsObject()));

        _time.Advance(TimeSpan.FromSeconds(9));
        Assert.Equal("STATUS_OK", T(JsonNode.Parse(_core.Handle(request, Phone)!)!.AsObject()));
        _time.Advance(TimeSpan.FromSeconds(1));
        Assert.Equal("NONCE_EXPIRED", Code(JsonNode.Parse(_core.Handle(request, Phone)!)!.AsObject()));
    }

    // --- log --------------------------------------------------------------------------------

    [Fact]
    public void LogdaAnahtarImzaNonceYok()
    {
        Pair();
        var nonce = Nonce();
        var status = _phone.Status(_phone.NextRid(), nonce);
        Send(status);
        var all = string.Join("\n", _log.Lines);
        Assert.DoesNotContain(nonce, all);
        Assert.DoesNotContain(status["sig"]!.GetValue<string>(), all);
        Assert.DoesNotContain(Convert.ToBase64String(_phone.Key!), all);
        Assert.Contains(_log.Lines, l => l.StartsWith("STATUS\t192.168.1.88\tSTATUS_OK"));
    }
}

public class StorageTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "wolagent-test-" + Guid.NewGuid().ToString("N"));

    private sealed class Passthrough : ISecretProtector
    {
        public byte[] Protect(byte[] data) => data.Select(b => (byte)(b ^ 0x5A)).ToArray();
        public byte[] Unprotect(byte[] data) => Protect(data);
    }

    public void Dispose()
    {
        if (Directory.Exists(_dir)) Directory.Delete(_dir, true);
    }

    [Fact]
    public void Config_IlkKurulumdaUretilir_SonraKorunur()
    {
        var store = new FileConfigStore(_dir, new Passthrough());
        var first = store.EnsureCreated();
        var again = new FileConfigStore(_dir, new Passthrough()).EnsureCreated();
        Assert.Equal(first.AgentId, again.AgentId);
        Assert.Equal(first.PrivateKey, again.PrivateKey);
        Assert.Null(again.Pairing);
        // Özel anahtar diskte açık metin olarak durmaz.
        Assert.DoesNotContain(Convert.ToBase64String(first.PrivateKey), File.ReadAllText(store.FilePath));
    }

    [Fact]
    public void Config_EslesmeYazilir_ResetBaskaSurectenGorunur()
    {
        var service = new FileConfigStore(_dir, new Passthrough());
        service.EnsureCreated();
        var key = Enumerable.Range(0, 32).Select(i => (byte)i).ToArray();
        service.SavePairing(new Pairing("phone-1", "Galaxy", new byte[32], key, DateTimeOffset.UnixEpoch));
        Assert.Equal(key, service.Load().Pairing!.SessionKey);
        Assert.DoesNotContain(Convert.ToBase64String(key), File.ReadAllText(service.FilePath));

        Thread.Sleep(20); // dosya zaman damgası değişsin
        new FileConfigStore(_dir, new Passthrough()).SavePairing(null); // CLI reset
        Assert.Null(service.Load().Pairing);
    }

    [Fact]
    public void Log_GunlukDosya_7GundenEskilerSilinir()
    {
        var time = new FakeTimeProvider(new DateTimeOffset(2026, 10, 29, 12, 0, 0, TimeSpan.Zero));
        Directory.CreateDirectory(_dir);
        File.WriteAllText(Path.Combine(_dir, "agent-20261015.log"), "eski");
        File.WriteAllText(Path.Combine(_dir, "agent-20261025.log"), "yeni");
        var log = new RequestLog(_dir, time);
        log.Write("STATUS", "192.168.1.88", "STATUS_OK");

        var files = Directory.GetFiles(_dir).Select(Path.GetFileName).Order().ToList();
        Assert.Equal(["agent-20261025.log", $"agent-{time.GetLocalNow():yyyyMMdd}.log"], files);
        var line = File.ReadAllText(Path.Combine(_dir, $"agent-{time.GetLocalNow():yyyyMMdd}.log"));
        Assert.Contains("STATUS\t192.168.1.88\tSTATUS_OK", line);
    }
}
