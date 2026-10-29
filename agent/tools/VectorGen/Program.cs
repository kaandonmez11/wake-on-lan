using System.Net;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using WolAgent.Protocol;

// protocol/vectors.json üretici. Tamamen deterministik: aynı kod → bayt bayt aynı dosya.
// Kullanım: dotnet run --project agent/tools/VectorGen [-- <çıktı yolu>]

var outputPath = args.Length > 0 ? args[0] : Path.Combine(FindRepoRoot(), "protocol", "vectors.json");

// RFC 7748 §6.1 anahtarları: telefon = Alice, agent = Bob.
var phonePriv = Hex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
var agentPriv = Hex("5dab087e624a8a4b79e17f8b83800ee66f3bb1292618b6fd1c2f8b27ff88e0eb");
var phonePub = X25519Keys.PublicKey(phonePriv);
var agentPub = X25519Keys.PublicKey(agentPriv);
var shared = X25519Keys.SharedSecret(phonePriv, agentPub);

const string phoneId = "6f1c2a4e-8b3d-4f7a-9c21-0d5e7b9a1f34";
const string agentId = "3b9e7d10-4c2a-4e8f-a6b5-91d0c3e2f478";
const string phoneName = "Galaxy S24";
const string hostname = "OFIS-PC";
const string agentVersion = "1.0.0";
var key = AgentCrypto.DeriveSessionKey(shared, agentId, phoneId);

var nonceA = B64(Enumerable.Range(0, 16).Select(i => (byte)i).ToArray());
var nonceB = B64(Enumerable.Range(0, 16).Select(i => (byte)(0xF0 - i * 7)).ToArray());
const long uptimeSec = 86461;

JsonArray Nics() =>
[
    new JsonObject { ["mac"] = "D8:BB:C1:0A:2F:91", ["name"] = "Ethernet", ["wired"] = true, ["ip"] = "192.168.1.24" },
    new JsonObject { ["mac"] = "4C:03:4F:77:1E:05", ["name"] = "Wi-Fi", ["wired"] = false, ["ip"] = "192.168.1.57" },
];

var root = new JsonObject
{
    ["_comment"] = "Bu dosya agent/tools/VectorGen ile üretilir; elle düzenlemeyin. Base64 = standart, padding'li.",
    ["version"] = AgentProtocol.Version,
    ["x25519"] = new JsonArray
    {
        new JsonObject
        {
            ["name"] = "rfc7748-6.1",
            ["alicePriv"] = B64(phonePriv),
            ["alicePub"] = B64(phonePub),
            ["bobPriv"] = B64(agentPriv),
            ["bobPub"] = B64(agentPub),
            ["shared"] = B64(shared),
        },
    },
    ["hkdf"] = new JsonArray
    {
        HkdfCase("rfc5869-a1", Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), Hex("000102030405060708090a0b0c"),
            Hex("f0f1f2f3f4f5f6f7f8f9"), 42),
        HkdfCase("rfc5869-a3-empty-salt", Hex("0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b0b"), [], [], 42),
    },
    ["pairing"] = new JsonObject
    {
        ["phoneId"] = phoneId,
        ["agentId"] = agentId,
        ["phonePriv"] = B64(phonePriv),
        ["phonePub"] = B64(phonePub),
        ["agentPriv"] = B64(agentPriv),
        ["agentPub"] = B64(agentPub),
        ["sharedSecret"] = B64(shared),
        ["salt"] = agentId + phoneId,
        ["info"] = AgentProtocol.PairInfo,
        ["sessionKey"] = B64(key),
    },
    ["signatures"] = Signatures(),
    ["messages"] = Messages(),
    ["magicPackets"] = new JsonArray
    {
        MagicCase("D8:BB:C1:0A:2F:91"),
        MagicCase("00:11:22:33:44:55"),
        MagicCase("FF:FF:FF:FF:FF:FF"),
    },
    ["broadcast"] = new JsonArray
    {
        BroadcastCase("192.168.1.24", 24),
        BroadcastCase("10.0.5.17", 22),
        BroadcastCase("172.16.5.4", 16),
        BroadcastCase("10.1.2.3", 8),
        BroadcastCase("192.168.1.130", 25),
        BroadcastCase("192.168.1.24", 30),
        BroadcastCase("192.168.1.24", 31),
        BroadcastCase("192.168.1.24", 32),
        BroadcastCase("192.168.1.24", 33),
        BroadcastCase("192.168.1.300", 24),
        BroadcastCase("192.168.1", 24),
    },
    ["macAddresses"] = new JsonArray
    {
        MacCase("aa:bb:cc:dd:ee:ff"),
        MacCase("AA-BB-CC-DD-EE-FF"),
        MacCase("00:1a:2B:3c:4D:5e"),
        MacCase(" d8-bb-c1-0a-2f-91 "),
        MacCase(""),
        MacCase("AA:BB:CC:DD:EE"),
        MacCase("AA:BB:CC:DD:EE:FG"),
        MacCase("AABBCCDDEEFF"),
        MacCase("AA:BB:CC:DD:EE:FF:00"),
        MacCase("AA:BB:CC:DD:EE:F"),
    },
};

var json = root.ToJsonString(new JsonSerializerOptions
{
    WriteIndented = true,
    NewLine = "\n",
    Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
}) + "\n";
File.WriteAllText(outputPath, json, new UTF8Encoding(encoderShouldEmitUTF8Identifier: false));
Console.WriteLine($"Yazıldı: {outputPath}");
return;

JsonArray Signatures()
{
    var list = new JsonArray
    {
        SigCase("PAIR_OK.proof", Canonical.PairOk(phoneId, agentId)),
        SigCase("STATUS.sig", Canonical.Status(nonceA)),
        SigCase("STATUS_OK.sig", Canonical.StatusOk(nonceA, agentId, uptimeSec, "on")),
        SigCase("STATUS_OK.sig/display-off", Canonical.StatusOk(nonceA, agentId, uptimeSec, "off")),
    };
    foreach (var cmd in AgentProtocol.Commands)
    foreach (var force in new[] { false, true })
        list.Add(SigCase($"CMD.sig/{cmd}/{(force ? "force" : "graceful")}", Canonical.Cmd(nonceB, cmd, force)));
    foreach (var cmd in AgentProtocol.Commands)
        list.Add(SigCase($"ACK.sig/{cmd}", Canonical.Ack(nonceB, cmd)));
    return list;
}

JsonArray Messages()
{
    var statusSig = AgentCrypto.Sign(key, Canonical.Status(nonceA));
    var statusOkSig = AgentCrypto.Sign(key, Canonical.StatusOk(nonceA, agentId, uptimeSec, "off"));
    var cmdSig = AgentCrypto.Sign(key, Canonical.Cmd(nonceB, "SHUTDOWN", true));
    var ackSig = AgentCrypto.Sign(key, Canonical.Ack(nonceB, "SHUTDOWN"));
    var proof = AgentCrypto.Sign(key, Canonical.PairOk(phoneId, agentId));

    return
    [
        Msg("DISCOVER", "a1b2c3d4e5f60718", new JsonObject()),
        Msg("DISCOVER_REPLY", "a1b2c3d4e5f60718", new JsonObject
        {
            ["agentId"] = agentId, ["hostname"] = hostname, ["agentVersion"] = agentVersion,
            ["paired"] = false, ["hibernate"] = true, ["sleepHibernates"] = true, ["nics"] = Nics(),
        }),
        Msg("PAIR_HELLO", "0f1e2d3c4b5a6978", new JsonObject
        {
            ["phoneId"] = phoneId, ["phoneName"] = phoneName, ["phonePub"] = B64(phonePub),
        }),
        Msg("PAIR_OK", "0f1e2d3c4b5a6978", new JsonObject
        {
            ["agentId"] = agentId, ["agentPub"] = B64(agentPub), ["proof"] = proof,
        }, Canonical.PairOk(phoneId, agentId), proof, new JsonObject { ["phoneId"] = phoneId }),
        Msg("PAIR_DENIED", "0f1e2d3c4b5a6978", new JsonObject()),
        Msg("CHALLENGE", "1122334455667788", new JsonObject { ["phoneId"] = phoneId }),
        Msg("NONCE", "1122334455667788", new JsonObject { ["nonce"] = nonceA }),
        Msg("STATUS", "99aabbccddeeff00", new JsonObject
        {
            ["phoneId"] = phoneId, ["nonce"] = nonceA, ["sig"] = statusSig,
        }, Canonical.Status(nonceA), statusSig),
        Msg("STATUS_OK", "99aabbccddeeff00", new JsonObject
        {
            ["agentId"] = agentId, ["hostname"] = hostname, ["uptimeSec"] = uptimeSec,
            ["hibernate"] = true, ["display"] = "off", ["nics"] = Nics(), ["sig"] = statusOkSig,
        }, Canonical.StatusOk(nonceA, agentId, uptimeSec, "off"), statusOkSig, new JsonObject { ["nonce"] = nonceA }),
        Msg("CMD", "0102030405060708", new JsonObject
        {
            ["phoneId"] = phoneId, ["nonce"] = nonceB, ["cmd"] = "SHUTDOWN", ["force"] = true, ["sig"] = cmdSig,
        }, Canonical.Cmd(nonceB, "SHUTDOWN", true), cmdSig),
        Msg("ACK", "0102030405060708", new JsonObject
        {
            ["cmd"] = "SHUTDOWN", ["sig"] = ackSig,
        }, Canonical.Ack(nonceB, "SHUTDOWN"), ackSig, new JsonObject { ["nonce"] = nonceB }),
        Msg("ERR", "1122334455667788", new JsonObject { ["code"] = "UNKNOWN_PHONE" }),
        Msg("ERR", "0102030405060708", new JsonObject { ["code"] = "BAD_SIG" }),
        Msg("ERR", "0102030405060708", new JsonObject { ["code"] = "NONCE_EXPIRED" }),
        Msg("ERR", "0102030405060708", new JsonObject { ["code"] = "UNSUPPORTED" }),
        Msg("ERR", "0102030405060708", new JsonObject { ["code"] = "BUSY" }),
    ];
}

JsonObject Msg(string type, string rid, JsonObject fields, string? canonical = null, string? sig = null,
    JsonObject? context = null)
{
    var message = new JsonObject { ["v"] = AgentProtocol.Version, ["t"] = type, ["rid"] = rid };
    foreach (var (name, value) in fields.ToList())
    {
        fields.Remove(name);
        message[name] = value;
    }
    var entry = new JsonObject { ["name"] = type, ["json"] = message };
    if (canonical is not null)
    {
        entry["canonical"] = canonical;
        entry["sig"] = sig;
    }
    if (context is not null) entry["context"] = context;
    return entry;
}

JsonObject SigCase(string name, string canonical) =>
    new() { ["name"] = name, ["canonical"] = canonical, ["sig"] = AgentCrypto.Sign(key, canonical) };

static JsonObject HkdfCase(string name, byte[] ikm, byte[] salt, byte[] info, int length) => new()
{
    ["name"] = name,
    ["ikm"] = B64(ikm),
    ["salt"] = B64(salt),
    ["info"] = B64(info),
    ["length"] = length,
    ["okm"] = B64(System.Security.Cryptography.HKDF.DeriveKey(
        System.Security.Cryptography.HashAlgorithmName.SHA256, ikm, length, salt, info)),
};

static JsonObject MagicCase(string mac)
{
    var macBytes = Convert.FromHexString(mac.Replace(":", ""));
    var packet = new byte[102];
    for (var i = 0; i < 6; i++) packet[i] = 0xFF;
    for (var r = 0; r < 16; r++) macBytes.CopyTo(packet, 6 + r * 6);
    return new JsonObject { ["mac"] = mac, ["packetHex"] = Convert.ToHexStringLower(packet) };
}

static JsonObject BroadcastCase(string ip, int prefix)
{
    var result = new JsonObject { ["ip"] = ip, ["prefix"] = prefix };
    var valid = Regex.IsMatch(ip, @"^\d{1,3}(\.\d{1,3}){3}$")
                && ip.Split('.').All(o => int.Parse(o) <= 255)
                && prefix is >= 0 and <= 30;
    if (!valid)
    {
        result["error"] = true;
        return result;
    }
    var addr = IPAddress.Parse(ip).GetAddressBytes();
    var value = (uint)(addr[0] << 24 | addr[1] << 16 | addr[2] << 8 | addr[3]);
    var hostMask = prefix == 0 ? uint.MaxValue : (1u << (32 - prefix)) - 1;
    var bc = value | hostMask;
    result["broadcast"] = $"{bc >> 24}.{(bc >> 16) & 0xFF}.{(bc >> 8) & 0xFF}.{bc & 0xFF}";
    return result;
}

static JsonObject MacCase(string input)
{
    var trimmed = input.Trim();
    var valid = Regex.IsMatch(trimmed, "^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$");
    var result = new JsonObject { ["input"] = input, ["valid"] = valid };
    if (valid) result["normalized"] = trimmed.ToUpperInvariant().Replace('-', ':');
    return result;
}

static string B64(byte[] bytes) => Convert.ToBase64String(bytes);

static byte[] Hex(string hex) => Convert.FromHexString(hex);

static string FindRepoRoot()
{
    for (var dir = new DirectoryInfo(Directory.GetCurrentDirectory()); dir is not null; dir = dir.Parent)
        if (File.Exists(Path.Combine(dir.FullName, "SPEC.md"))) return dir.FullName;
    throw new InvalidOperationException("Repo kökü (SPEC.md) bulunamadı; çıktı yolunu argüman olarak verin.");
}
