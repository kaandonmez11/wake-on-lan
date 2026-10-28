using System.Security.Cryptography;
using System.Text.Json.Nodes;
using WolAgent.Protocol;
using static WolAgent.Tests.Vectors;

namespace WolAgent.Tests;

public class ProtocolVectorTests
{
    [Fact]
    public void X25519_MatchesRfc7748KnownAnswer()
    {
        // vectors.json'dan bağımsız sabit: RFC 7748 §6.1.
        var alicePriv = Convert.FromHexString("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        var bobPub = Convert.FromHexString("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");
        Assert.Equal("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a",
            Convert.ToHexStringLower(X25519Keys.PublicKey(alicePriv)));
        Assert.Equal("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742",
            Convert.ToHexStringLower(X25519Keys.SharedSecret(alicePriv, bobPub)));
    }

    [Theory]
    [MemberData(nameof(X25519Cases))]
    public void X25519_VectorCases(string name)
    {
        var c = Case("x25519", name);
        Assert.Equal(B64(c, "alicePub"), X25519Keys.PublicKey(B64(c, "alicePriv")));
        Assert.Equal(B64(c, "bobPub"), X25519Keys.PublicKey(B64(c, "bobPriv")));
        Assert.Equal(B64(c, "shared"), X25519Keys.SharedSecret(B64(c, "alicePriv"), B64(c, "bobPub")));
        Assert.Equal(B64(c, "shared"), X25519Keys.SharedSecret(B64(c, "bobPriv"), B64(c, "alicePub")));
    }

    public static IEnumerable<object[]> X25519Cases() => Cases("x25519");

    [Theory]
    [MemberData(nameof(HkdfCases))]
    public void Hkdf_VectorCases(string name)
    {
        var c = Case("hkdf", name);
        var okm = HKDF.DeriveKey(HashAlgorithmName.SHA256, B64(c, "ikm"), c["length"]!.GetValue<int>(),
            B64(c, "salt"), B64(c, "info"));
        Assert.Equal(B64(c, "okm"), okm);
    }

    public static IEnumerable<object[]> HkdfCases() => Cases("hkdf");

    [Fact]
    public void Pairing_BothSidesDeriveSameSessionKey()
    {
        var p = Pairing;
        var agentId = Str(p, "agentId");
        var phoneId = Str(p, "phoneId");
        Assert.Equal(agentId + phoneId, Str(p, "salt"));

        var phoneSide = X25519Keys.SharedSecret(B64(p, "phonePriv"), B64(p, "agentPub"));
        var agentSide = X25519Keys.SharedSecret(B64(p, "agentPriv"), B64(p, "phonePub"));
        Assert.Equal(B64(p, "sharedSecret"), phoneSide);
        Assert.Equal(phoneSide, agentSide);
        Assert.Equal(SessionKey, AgentCrypto.DeriveSessionKey(agentSide, agentId, phoneId));
    }

    [Theory]
    [MemberData(nameof(SignatureCases))]
    public void Signatures_MatchAndVerify(string name)
    {
        var c = Case("signatures", name);
        var canonical = Str(c, "canonical");
        Assert.Equal(Str(c, "sig"), AgentCrypto.Sign(SessionKey, canonical));
        Assert.True(AgentCrypto.Verify(SessionKey, canonical, Str(c, "sig")));
        Assert.False(AgentCrypto.Verify(SessionKey, canonical + "x", Str(c, "sig")));
    }

    public static IEnumerable<object[]> SignatureCases() => Cases("signatures");

    [Fact]
    public void Signatures_CoverEveryCommandAndForceFlag()
    {
        foreach (var cmd in AgentProtocol.Commands)
        {
            Assert.Equal(Canonical.Cmd(Nonce("CMD.sig/" + cmd + "/force"), cmd, true),
                Str(Case("signatures", $"CMD.sig/{cmd}/force"), "canonical"));
            Assert.Equal(Canonical.Cmd(Nonce("CMD.sig/" + cmd + "/graceful"), cmd, false),
                Str(Case("signatures", $"CMD.sig/{cmd}/graceful"), "canonical"));
            Assert.StartsWith("ack|", Str(Case("signatures", $"ACK.sig/{cmd}"), "canonical"));
        }

        static string Nonce(string name) => Str(Case("signatures", name), "canonical").Split('|')[1];
    }

    [Fact]
    public void Messages_CoverAllTypes()
    {
        var types = Json["messages"]!.AsArray().Select(m => Str(m!["json"]!.AsObject(), "t")).ToHashSet();
        string[] expected =
        [
            "DISCOVER", "DISCOVER_REPLY", "PAIR_HELLO", "PAIR_OK", "PAIR_DENIED", "CHALLENGE", "NONCE",
            "STATUS", "STATUS_OK", "CMD", "ACK", "ERR",
        ];
        Assert.Equal(expected.Order(), types.Order());

        var errCodes = Json["messages"]!.AsArray()
            .Select(m => m!["json"]!.AsObject())
            .Where(j => Str(j, "t") == "ERR")
            .Select(j => Str(j, "code"));
        Assert.Equal(["BAD_SIG", "BUSY", "NONCE_EXPIRED", "UNKNOWN_PHONE", "UNSUPPORTED"], errCodes.Order());
    }

    [Fact]
    public void Messages_EnvelopeAndSignedFieldsAreConsistent()
    {
        foreach (var node in Json["messages"]!.AsArray())
        {
            var entry = node!.AsObject();
            var msg = entry["json"]!.AsObject();
            Assert.Equal(AgentProtocol.Version, msg["v"]!.GetValue<int>());
            Assert.Matches("^[0-9a-f]{16}$", Str(msg, "rid"));
            Assert.True(System.Text.Encoding.UTF8.GetByteCount(msg.ToJsonString()) <= AgentProtocol.MaxDatagramBytes);

            if (!entry.ContainsKey("canonical")) continue;
            var canonical = CanonicalFor(msg, entry["context"]?.AsObject());
            Assert.Equal(Str(entry, "canonical"), canonical);
            Assert.True(AgentCrypto.Verify(SessionKey, canonical, Str(entry, "sig")));
            var sigField = Str(msg, "t") == "PAIR_OK" ? "proof" : "sig";
            Assert.Equal(Str(entry, "sig"), Str(msg, sigField));
        }
    }

    private static string CanonicalFor(JsonObject msg, JsonObject? context) => Str(msg, "t") switch
    {
        "PAIR_OK" => Canonical.PairOk(Str(context!, "phoneId"), Str(msg, "agentId")),
        "STATUS" => Canonical.Status(Str(msg, "nonce")),
        "STATUS_OK" => Canonical.StatusOk(Str(context!, "nonce"), Str(msg, "agentId"),
            msg["uptimeSec"]!.GetValue<long>()),
        "CMD" => Canonical.Cmd(Str(msg, "nonce"), Str(msg, "cmd"), msg["force"]!.GetValue<bool>()),
        "ACK" => Canonical.Ack(Str(context!, "nonce"), Str(msg, "cmd")),
        var t => throw new InvalidOperationException($"İmzasız tip: {t}"),
    };
}
