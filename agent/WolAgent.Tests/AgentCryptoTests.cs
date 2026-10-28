using WolAgent.Protocol;

namespace WolAgent.Tests;

public class AgentCryptoTests
{
    [Fact]
    public void Verify_RejectsMalformedBase64()
    {
        Assert.False(AgentCrypto.Verify(new byte[32], "status|x", "%%%"));
    }

    [Fact]
    public void Verify_RejectsWrongKey()
    {
        var sig = AgentCrypto.Sign(new byte[32], "status|x");
        var otherKey = new byte[32];
        otherKey[0] = 1;
        Assert.False(AgentCrypto.Verify(otherKey, "status|x", sig));
    }

    [Fact]
    public void X25519_RandomKeysAgree()
    {
        var a = X25519Keys.GeneratePrivateKey();
        var b = X25519Keys.GeneratePrivateKey();
        Assert.Equal(
            X25519Keys.SharedSecret(a, X25519Keys.PublicKey(b)),
            X25519Keys.SharedSecret(b, X25519Keys.PublicKey(a)));
    }

    [Fact]
    public void X25519_RejectsWrongLength()
    {
        Assert.Throws<ArgumentException>(() => X25519Keys.PublicKey(new byte[31]));
    }

    [Fact]
    public void X25519_RejectsAllZeroPublicKey()
    {
        Assert.Throws<System.Security.Cryptography.CryptographicException>(
            () => X25519Keys.SharedSecret(X25519Keys.GeneratePrivateKey(), new byte[32]));
    }

    [Fact]
    public void Canonical_FormatsBoolsAndIntegers()
    {
        Assert.Equal("cmd|n|LOCK|false", Canonical.Cmd("n", "LOCK", false));
        Assert.Equal("cmd|n|RESTART|true", Canonical.Cmd("n", "RESTART", true));
        Assert.Equal("status-ok|n|a|0", Canonical.StatusOk("n", "a", 0));
    }
}
