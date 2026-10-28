using System.Security.Cryptography;
using Rfc7748 = Org.BouncyCastle.Math.EC.Rfc7748.X25519;

namespace WolAgent.Protocol;

/// <summary>Ham 32 baytlık X25519 anahtarları (K5). .NET BCL'de X25519 olmadığı için BouncyCastle (K3).</summary>
public static class X25519Keys
{
    public const int KeySize = 32;

    public static byte[] GeneratePrivateKey()
    {
        var key = new byte[KeySize];
        RandomNumberGenerator.Fill(key);
        return key;
    }

    public static byte[] PublicKey(byte[] privateKey)
    {
        RequireKey(privateKey, nameof(privateKey));
        var pub = new byte[KeySize];
        Rfc7748.ScalarMultBase(privateKey, 0, pub, 0);
        return pub;
    }

    public static byte[] SharedSecret(byte[] privateKey, byte[] peerPublicKey)
    {
        RequireKey(privateKey, nameof(privateKey));
        RequireKey(peerPublicKey, nameof(peerPublicKey));
        var secret = new byte[KeySize];
        if (!Rfc7748.CalculateAgreement(privateKey, 0, peerPublicKey, 0, secret, 0))
            throw new CryptographicException("X25519 ortak sırrı sıfır (geçersiz açık anahtar).");
        return secret;
    }

    private static void RequireKey(byte[] key, string name)
    {
        if (key.Length != KeySize) throw new ArgumentException($"{name} 32 bayt olmalı.", name);
    }
}
