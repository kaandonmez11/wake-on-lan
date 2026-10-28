using System.Security.Cryptography;
using System.Text;

namespace WolAgent.Protocol;

/// <summary>Oturum anahtarı türetimi ve imzalar (PROTOCOL.md §2).</summary>
public static class AgentCrypto
{
    /// <summary>K = HKDF-SHA256(ikm = sır, salt = agentId‖phoneId (UTF-8), info = "wol-pair-v1", L = 32).</summary>
    public static byte[] DeriveSessionKey(byte[] sharedSecret, string agentId, string phoneId) =>
        HKDF.DeriveKey(
            HashAlgorithmName.SHA256,
            sharedSecret,
            AgentProtocol.SessionKeyBytes,
            Encoding.UTF8.GetBytes(agentId + phoneId),
            Encoding.UTF8.GetBytes(AgentProtocol.PairInfo));

    public static string Sign(byte[] key, string canonical) =>
        Convert.ToBase64String(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(canonical)));

    public static bool Verify(byte[] key, string canonical, string signature)
    {
        byte[] given;
        try { given = Convert.FromBase64String(signature); }
        catch (FormatException) { return false; }
        var expected = HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(canonical));
        return CryptographicOperations.FixedTimeEquals(expected, given);
    }
}
