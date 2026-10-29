using System.Security.AccessControl;
using System.Security.Cryptography;
using System.Security.Principal;
using System.Text;
using System.Text.Json;
using System.Text.Json.Nodes;
using WolAgent.Core;
using WolAgent.Protocol;

namespace WolAgent.Storage;

/// <summary>Gizli baytları diskte korur. Windows'ta DPAPI LocalMachine; testte düz geçiş.</summary>
public interface ISecretProtector
{
    byte[] Protect(byte[] data);
    byte[] Unprotect(byte[] data);
}

public sealed class DpapiProtector : ISecretProtector
{
    private static readonly byte[] Entropy = Encoding.UTF8.GetBytes("WolAgent/v1");

    public byte[] Protect(byte[] data) =>
        ProtectedData.Protect(data, Entropy, DataProtectionScope.LocalMachine);

    public byte[] Unprotect(byte[] data) =>
        ProtectedData.Unprotect(data, Entropy, DataProtectionScope.LocalMachine);
}

/// <summary>
/// <c>%ProgramData%\WolAgent\config.json</c> (spec 13.3). Özel anahtar ve oturum anahtarı DPAPI ile şifreli.
/// Dosya değişirse (CLI <c>reset</c>) bir sonraki <see cref="Load"/> yeniden okur.
/// </summary>
public sealed class FileConfigStore(string directory, ISecretProtector protector) : IConfigStore
{
    /// <summary><c>%ProgramData%\WolAgent</c>; hata ayıklama/duman testi için <c>WOLAGENT_HOME</c> ile değiştirilebilir.</summary>
    public static string DefaultDirectory =>
        Environment.GetEnvironmentVariable("WOLAGENT_HOME") is { Length: > 0 } home
            ? home
            : Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData), "WolAgent");

    private readonly Lock _gate = new();
    private AgentConfig? _cached;
    private DateTime _cachedStamp;

    public string Directory { get; } = directory;
    public string FilePath => Path.Combine(Directory, "config.json");
    public bool Exists => File.Exists(FilePath);

    public AgentConfig Load()
    {
        lock (_gate)
        {
            var stamp = File.Exists(FilePath) ? File.GetLastWriteTimeUtc(FilePath) : DateTime.MinValue;
            if (_cached is not null && stamp == _cachedStamp) return _cached;
            if (!File.Exists(FilePath)) throw new InvalidOperationException($"Yapılandırma yok: {FilePath} (önce 'install').");
            _cached = Parse(File.ReadAllText(FilePath));
            _cachedStamp = stamp;
            return _cached;
        }
    }

    /// <summary>İlk kurulumda agentId + X25519 anahtar çifti üretir; varsa dokunmaz.</summary>
    public AgentConfig EnsureCreated()
    {
        System.IO.Directory.CreateDirectory(Directory);
        if (!Exists) Write(new AgentConfig(Guid.NewGuid().ToString(), X25519Keys.GeneratePrivateKey(), null));
        return Load();
    }

    public void SavePairing(Pairing? pairing)
    {
        lock (_gate)
        {
            var current = Load();
            Write(current with { Pairing = pairing });
        }
    }

    private void Write(AgentConfig cfg)
    {
        var o = new JsonObject
        {
            ["agentId"] = cfg.AgentId,
            ["privateKey"] = Convert.ToBase64String(protector.Protect(cfg.PrivateKey)),
        };
        if (cfg.Pairing is { } p)
        {
            o["pairing"] = new JsonObject
            {
                ["phoneId"] = p.PhoneId,
                ["phoneName"] = p.PhoneName,
                ["phonePub"] = Convert.ToBase64String(p.PhonePub),
                ["sessionKey"] = Convert.ToBase64String(protector.Protect(p.SessionKey)),
                ["pairedAt"] = p.PairedAt.ToString("O"),
            };
        }
        var tmp = FilePath + ".tmp";
        File.WriteAllText(tmp, o.ToJsonString(new JsonSerializerOptions { WriteIndented = true }));
        File.Move(tmp, FilePath, overwrite: true);
        _cached = null;
    }

    private AgentConfig Parse(string text)
    {
        var o = JsonNode.Parse(text)!.AsObject();
        Pairing? pairing = null;
        if (o["pairing"] is JsonObject p)
        {
            pairing = new Pairing(
                p["phoneId"]!.GetValue<string>(),
                p["phoneName"]?.GetValue<string>() ?? "",
                Convert.FromBase64String(p["phonePub"]!.GetValue<string>()),
                protector.Unprotect(Convert.FromBase64String(p["sessionKey"]!.GetValue<string>())),
                DateTimeOffset.Parse(p["pairedAt"]!.GetValue<string>()));
        }
        return new AgentConfig(
            o["agentId"]!.GetValue<string>(),
            protector.Unprotect(Convert.FromBase64String(o["privateKey"]!.GetValue<string>())),
            pairing);
    }

    /// <summary>Klasöre yalnız SYSTEM + Administrators erişir; miras kesilir (spec 13.3).</summary>
    public static void RestrictAcl(string directory)
    {
        var info = new DirectoryInfo(directory);
        var security = new DirectorySecurity();
        security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);
        foreach (var sid in new[] { WellKnownSidType.LocalSystemSid, WellKnownSidType.BuiltinAdministratorsSid })
        {
            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(sid, null), FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None, AccessControlType.Allow));
        }
        info.SetAccessControl(security);
    }
}
