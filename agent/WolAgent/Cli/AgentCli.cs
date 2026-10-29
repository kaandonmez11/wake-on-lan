using System.Diagnostics;
using System.Security.Principal;
using System.ServiceProcess;
using WolAgent.Protocol;
using WolAgent.Storage;
using WolAgent.Windows;

namespace WolAgent.Cli;

/// <summary>Spec 13.2: install / uninstall [--purge] / reset / status. Hepsi yönetici ister.</summary>
public static class AgentCli
{
    public const string ServiceName = "WolAgent";
    public const string DisplayName = "WoL Denetim Merkezi agent";
    public const string Description = "WoL Denetim Merkezi agent: telefondan durum, kilit, uyku, hazırda bekletme, kapatma ve yeniden başlatma.";
    public const string FirewallRule = "WoL Denetim Merkezi agent (UDP 40900)";

    public static string InstallDir =>
        Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "WolAgent");

    public static string InstalledExe => Path.Combine(InstallDir, "WolAgent.exe");

    public static int Run(string command, string[] args)
    {
        if (!IsAdmin())
        {
            Console.Error.WriteLine("Bu komut yönetici olarak çalıştırılmalı (Komut İstemi › Yönetici olarak çalıştır).");
            return 5;
        }
        try
        {
            return command switch
            {
                "install" => Install(),
                "uninstall" => Uninstall(args.Contains("--purge")),
                "reset" => Reset(),
                "status" => Status(),
                _ => Usage(),
            };
        }
        catch (Exception e)
        {
            Console.Error.WriteLine($"Hata: {e.Message}");
            return 1;
        }
    }

    public static int Usage()
    {
        Console.WriteLine("""
            Kullanım (yönetici Komut İstemi):
              WolAgent.exe install            Kur ya da güncelle (anahtarlar korunur)
              WolAgent.exe uninstall [--purge] Kaldır (--purge: yapılandırma ve loglar da)
              WolAgent.exe reset              Eşleşmeyi sil (yeni telefon için)
              WolAgent.exe status             Durumu göster
            """);
        return 2;
    }

    private static int Install()
    {
        var source = Environment.ProcessPath ?? throw new InvalidOperationException("Exe yolu bilinmiyor");
        var existing = FindService();
        var update = existing is not null;
        if (update)
        {
            Console.WriteLine("Mevcut kurulum bulundu, güncelleniyor…");
            StopService(existing!);
        }

        Directory.CreateDirectory(InstallDir);
        if (!string.Equals(Path.GetFullPath(source), Path.GetFullPath(InstalledExe), StringComparison.OrdinalIgnoreCase))
            CopyWithRetry(source, InstalledExe);

        var store = new FileConfigStore(FileConfigStore.DefaultDirectory, new DpapiProtector());
        var hadConfig = store.Exists;
        var cfg = store.EnsureCreated();
        FileConfigStore.RestrictAcl(store.Directory);

        var bin = $"\"{InstalledExe}\"";
        if (update)
            Sc("config", ServiceName, "binPath=", bin, "start=", "auto", "DisplayName=", DisplayName);
        else
            Sc("create", ServiceName, "binPath=", bin, "start=", "auto", "DisplayName=", DisplayName, "obj=", "LocalSystem");
        Sc("description", ServiceName, Description);
        Sc("failure", ServiceName, "reset=", "86400", "actions=", "restart/5000/restart/5000/restart/5000");

        Netsh("advfirewall", "firewall", "delete", "rule", $"name={FirewallRule}");
        Netsh("advfirewall", "firewall", "add", "rule", $"name={FirewallRule}", "dir=in", "action=allow",
            "protocol=UDP", $"localport={AgentProtocol.Port}", "remoteip=localsubnet", "profile=any",
            $"program={InstalledExe}", "enable=yes");

        using (var sc = new ServiceController(ServiceName))
        {
            sc.Start();
            sc.WaitForStatus(ServiceControllerStatus.Running, TimeSpan.FromSeconds(20));
        }

        Console.WriteLine(update ? "Güncellendi ✓" : "Kuruldu ✓");
        Console.WriteLine($"  Servis     : {DisplayName} (Çalışıyor, Otomatik)");
        Console.WriteLine($"  Konum      : {InstallDir}");
        Console.WriteLine($"  agentId    : {cfg.AgentId}{(hadConfig ? " (korundu)" : " (yeni)")}");
        Console.WriteLine($"  Güvenlik duvarı: UDP {AgentProtocol.Port}, yerel alt ağ");
        return 0;
    }

    private static int Uninstall(bool purge)
    {
        var service = FindService();
        if (service is not null)
        {
            StopService(service);
            Sc("delete", ServiceName);
            Console.WriteLine("Servis kaldırıldı.");
        }
        Netsh("advfirewall", "firewall", "delete", "rule", $"name={FirewallRule}");
        Console.WriteLine("Güvenlik duvarı kuralı kaldırıldı.");

        if (purge && Directory.Exists(FileConfigStore.DefaultDirectory))
        {
            Directory.Delete(FileConfigStore.DefaultDirectory, recursive: true);
            Console.WriteLine($"Silindi: {FileConfigStore.DefaultDirectory}");
        }

        if (Directory.Exists(InstallDir))
        {
            var running = Path.GetFullPath(Environment.ProcessPath ?? "");
            if (running.StartsWith(Path.GetFullPath(InstallDir), StringComparison.OrdinalIgnoreCase))
            {
                // Çalışan exe kendini silemez: süreç kapandıktan sonra klasörü silen ayrık bir komut.
                Process.Start(new ProcessStartInfo("cmd.exe", $"/c ping -n 3 127.0.0.1 >nul & rmdir /s /q \"{InstallDir}\"")
                {
                    CreateNoWindow = true,
                    UseShellExecute = false,
                });
                Console.WriteLine($"Silinecek (birkaç saniye içinde): {InstallDir}");
            }
            else
            {
                Directory.Delete(InstallDir, recursive: true);
                Console.WriteLine($"Silindi: {InstallDir}");
            }
        }
        Console.WriteLine(purge ? "Kaldırıldı ✓ (yapılandırma dahil)" : "Kaldırıldı ✓ (yapılandırma korundu)");
        return 0;
    }

    private static int Reset()
    {
        var store = new FileConfigStore(FileConfigStore.DefaultDirectory, new DpapiProtector());
        if (!store.Exists)
        {
            Console.WriteLine("Yapılandırma yok; önce 'install'.");
            return 1;
        }
        var pairing = store.Load().Pairing;
        store.SavePairing(null);
        Console.WriteLine(pairing is null
            ? "Zaten eşleşmemiş. Yeni telefon eşleşebilir ✓"
            : $"Eşleşme silindi ({pairing.PhoneName}). Yeni telefon eşleşebilir ✓");
        return 0;
    }

    private static int Status()
    {
        var service = FindService();
        string state;
        if (service is null) state = "Kurulu değil";
        else
        {
            using (service)
            {
                state = service.Status switch
                {
                    ServiceControllerStatus.Running => "Çalışıyor",
                    ServiceControllerStatus.Stopped => "Durdu",
                    var s => s.ToString(),
                };
                state += service.StartType == ServiceStartMode.Automatic ? " · Otomatik" : $" · {service.StartType}";
            }
        }
        Console.WriteLine($"Servis      : {state}");

        var store = new FileConfigStore(FileConfigStore.DefaultDirectory, new DpapiProtector());
        if (store.Exists)
        {
            var cfg = store.Load();
            Console.WriteLine($"agentId     : {cfg.AgentId}");
            Console.WriteLine(cfg.Pairing is { } p
                ? $"Eşleşme     : {p.PhoneName} ({p.PhoneId[..8]}…), {p.PairedAt.ToLocalTime():yyyy-MM-dd HH:mm}"
                : "Eşleşme     : Eşleşmemiş");
        }
        else Console.WriteLine("agentId     : (yapılandırma yok)");

        var caps = PowerCapabilities.Read();
        Console.WriteLine($"Port        : UDP {AgentProtocol.Port}");
        Console.WriteLine($"Hazırda bekl: {(caps.HibernateSupported ? "destekli" : "yok (powercfg /h on)")}");
        Console.WriteLine($"Uyku        : {(caps.S3 ? "S3" : caps.ModernStandby ? "Modern Bekleme (S0)" : "yok")}");
        Console.WriteLine("Ağ kartları :");
        foreach (var nic in WindowsSystemInfo.ListNics())
            Console.WriteLine($"  {(nic.Wired ? "kablolu " : "kablosuz")} {nic.Mac}  {(nic.Ip.Length > 0 ? nic.Ip : "IP yok"),-15}  {nic.Name}");
        return 0;
    }

    private static ServiceController? FindService() =>
        ServiceController.GetServices().FirstOrDefault(s => s.ServiceName.Equals(ServiceName, StringComparison.OrdinalIgnoreCase));

    private static void StopService(ServiceController service)
    {
        using (service)
        {
            service.Refresh();
            if (service.Status is ServiceControllerStatus.Stopped) return;
            service.Stop();
            service.WaitForStatus(ServiceControllerStatus.Stopped, TimeSpan.FromSeconds(20));
        }
    }

    private static void CopyWithRetry(string source, string target)
    {
        for (var attempt = 1; ; attempt++)
        {
            try
            {
                File.Copy(source, target, overwrite: true);
                return;
            }
            catch (IOException) when (attempt < 10)
            {
                Thread.Sleep(500); // servis süreci dosyayı yeni bırakıyor olabilir
            }
        }
    }

    private static void Sc(params string[] args) => Exec("sc.exe", args);

    private static void Netsh(params string[] args) => Exec("netsh.exe", args, allowFailure: args.Contains("delete"));

    private static void Exec(string file, string[] args, bool allowFailure = false)
    {
        var psi = new ProcessStartInfo(file) { UseShellExecute = false, RedirectStandardOutput = true, RedirectStandardError = true, CreateNoWindow = true };
        foreach (var a in args) psi.ArgumentList.Add(a);
        using var p = Process.Start(psi)!;
        var output = p.StandardOutput.ReadToEnd() + p.StandardError.ReadToEnd();
        p.WaitForExit();
        if (p.ExitCode != 0 && !allowFailure)
            throw new InvalidOperationException($"{file} {string.Join(' ', args)} → {p.ExitCode}: {output.Trim()}");
    }

    private static bool IsAdmin()
    {
        using var identity = WindowsIdentity.GetCurrent();
        return new WindowsPrincipal(identity).IsInRole(WindowsBuiltInRole.Administrator);
    }
}
