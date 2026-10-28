namespace WolAgent.Protocol;

/// <summary>protocol/PROTOCOL.md sabitleri.</summary>
public static class AgentProtocol
{
    public const int Version = 1;
    public const int Port = 40900;
    public const int MaxDatagramBytes = 1200;
    public const string PairInfo = "wol-pair-v1";
    public const int SessionKeyBytes = 32;
    public const int NonceBytes = 16;
    public static readonly TimeSpan NonceLifetime = TimeSpan.FromSeconds(10);
    public static readonly TimeSpan ResponseCacheLifetime = TimeSpan.FromSeconds(10);

    public static readonly IReadOnlyList<string> Commands = ["LOCK", "SLEEP", "HIBERNATE", "SHUTDOWN", "RESTART"];
}
