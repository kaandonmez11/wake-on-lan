using System.Globalization;

namespace WolAgent.Protocol;

/// <summary>İmzalanan metinler; alanlar sabit sırayla "|" ile birleşir (K5).</summary>
public static class Canonical
{
    public static string PairOk(string phoneId, string agentId) => $"pair-ok|{phoneId}|{agentId}";

    public static string Status(string nonce) => $"status|{nonce}";

    public static string StatusOk(string nonce, string agentId, long uptimeSec, string display) =>
        $"status-ok|{nonce}|{agentId}|{uptimeSec.ToString(CultureInfo.InvariantCulture)}|{display}";

    public static string Cmd(string nonce, string cmd, bool force) =>
        $"cmd|{nonce}|{cmd}|{(force ? "true" : "false")}";

    public static string Ack(string nonce, string cmd) => $"ack|{nonce}|{cmd}";
}
