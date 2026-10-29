namespace WolAgent.Core;

/// <summary>DISCOVER/STATUS'ta bildirilen ağ kartı (PROTOCOL.md §4.1).</summary>
public sealed record AgentNic(string Mac, string Name, bool Wired, string Ip);

/// <summary>Eşleşmiş telefon (TOFU). Anahtar bellekte açık, diskte DPAPI ile.</summary>
public sealed record Pairing(string PhoneId, string PhoneName, byte[] PhonePub, byte[] SessionKey, DateTimeOffset PairedAt);

/// <summary>Kalıcı agent kimliği + isteğe bağlı eşleşme (spec 13.3).</summary>
public sealed record AgentConfig(string AgentId, byte[] PrivateKey, Pairing? Pairing);

public interface IConfigStore
{
    /// <summary>Güncel yapılandırma. CLI `reset` dosyayı değiştirirse servis bir sonraki çağrıda görür.</summary>
    AgentConfig Load();

    void SavePairing(Pairing? pairing);
}

/// <summary>Makine bilgileri; Windows uygulaması <c>WindowsSystemInfo</c>, testte sahte.</summary>
public interface ISystemInfo
{
    string Hostname { get; }
    string AgentVersion { get; }
    long UptimeSec { get; }
    bool HibernateSupported { get; }

    /// <summary>K23: Modern Beklemeli PC'de telefondan Uyku = Hazırda Beklet (uzaktan uyandırılabilsin).</summary>
    bool SleepHibernates { get; }

    /// <summary>"on" | "off" | "dimmed" | "unknown" (K22).</summary>
    string Display { get; }

    IReadOnlyList<AgentNic> Nics();
}

/// <summary>Güç komutlarını uygular. ACK gönderildikten 300 ms sonra çağrılır.</summary>
public interface ICommandExecutor
{
    Task ExecuteAsync(string cmd, bool force, CancellationToken ct);
}

/// <summary>Her istek tek satır; anahtar/imza yazılmaz (spec 13.3).</summary>
public interface IRequestLog
{
    void Write(string type, string source, string result);
}
