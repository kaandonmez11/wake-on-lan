using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Reflection;
using System.Runtime.InteropServices;
using WolAgent.Core;

namespace WolAgent.Windows;

/// <summary>Güç yetenekleri (GetPwrCapabilities).</summary>
public sealed record PowerCapabilities(bool S3, bool S4, bool HiberFilePresent, bool ModernStandby)
{
    /// <summary>HIBERNATE için: SystemS4 ve hiberfil.sys var (spec 13.4).</summary>
    public bool HibernateSupported => S4 && HiberFilePresent;

    public static PowerCapabilities Read()
    {
        var buffer = new byte[Native.CapBufferSize];
        if (!Native.GetPwrCapabilities(buffer)) return new PowerCapabilities(false, false, false, false);
        return new PowerCapabilities(
            buffer[Native.CapSystemS3] != 0,
            buffer[Native.CapSystemS4] != 0,
            buffer[Native.CapHiberFilePresent] != 0,
            buffer[Native.CapAoAc] != 0);
    }
}

public sealed class WindowsSystemInfo(DisplayStateMonitor display) : ISystemInfo
{
    private static readonly string[] VirtualMarkers =
        ["hyper-v", "virtual", "vmware", "virtualbox", "vpn", "tap-", "wan miniport", "bluetooth", "loopback", "wireguard",
         "tailscale", "zerotier", "filter", "scheduler", "-0000"];

    public string Hostname => Environment.MachineName;

    public string AgentVersion { get; } =
        typeof(WindowsSystemInfo).Assembly.GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion.Split('+')[0]
        ?? "1.0.0";

    public long UptimeSec => Environment.TickCount64 / 1000;

    public bool HibernateSupported => PowerCapabilities.Read().HibernateSupported;

    public string Display => display.Current;

    public IReadOnlyList<AgentNic> Nics() => ListNics();

    /// <summary>
    /// Fiziksel Ethernet ve Wi-Fi kartları (sanal/VPN/Hyper-V hariç), MAC'i olanlar.
    /// Çalışmayan kablolu kart da listelenir: WoL için MAC'i önemli.
    /// </summary>
    public static IReadOnlyList<AgentNic> ListNics()
    {
        var result = new List<AgentNic>();
        foreach (var nic in NetworkInterface.GetAllNetworkInterfaces())
        {
            var wired = nic.NetworkInterfaceType is NetworkInterfaceType.Ethernet or NetworkInterfaceType.GigabitEthernet
                or NetworkInterfaceType.FastEthernetT or NetworkInterfaceType.FastEthernetFx or NetworkInterfaceType.Ethernet3Megabit;
            var wifi = nic.NetworkInterfaceType == NetworkInterfaceType.Wireless80211;
            if (!wired && !wifi) continue;
            var text = (nic.Name + " " + nic.Description).ToLowerInvariant();
            if (VirtualMarkers.Any(text.Contains)) continue;
            var mac = nic.GetPhysicalAddress().GetAddressBytes();
            if (mac.Length != 6 || mac.All(b => b == 0)) continue;
            if (!wired && nic.OperationalStatus != OperationalStatus.Up) continue;

            var ip = nic.GetIPProperties().UnicastAddresses
                .FirstOrDefault(a => a.Address.AddressFamily == AddressFamily.InterNetwork)?.Address.ToString() ?? "";
            result.Add(new AgentNic(string.Join(":", mac.Select(b => b.ToString("X2"))), nic.Name, wired, ip));
        }
        // Filtre sürücüleri aynı MAC'le sahte arayüz gösterir: MAC başına IP'li olan kalır.
        // Kablolu ve IP'li olanlar önce; DISCOVER 1200 baytı aşmasın diye en fazla 6.
        return result.OrderByDescending(n => n.Wired).ThenByDescending(n => n.Ip.Length > 0)
            .DistinctBy(n => n.Mac).Take(6).ToList();
    }
}

/// <summary>
/// K22: konsol ekranının durumu (GUID_CONSOLE_DISPLAY_STATE). Servis penceresiz olduğundan
/// DEVICE_NOTIFY_CALLBACK ile kaydolur; kayıt anında güncel değer gelir.
/// </summary>
public sealed class DisplayStateMonitor : IDisposable
{
    private readonly Native.PowerNotifyCallback _callback;
    private readonly IntPtr _params;
    private IntPtr _handle;
    private volatile string _current = "unknown";

    public DisplayStateMonitor()
    {
        _callback = OnNotify; // GC'den korumak için alan olarak tutulur
        _params = Marshal.AllocHGlobal(Marshal.SizeOf<Native.DeviceNotifySubscribeParameters>());
        Marshal.StructureToPtr(new Native.DeviceNotifySubscribeParameters
        {
            Callback = Marshal.GetFunctionPointerForDelegate(_callback),
            Context = IntPtr.Zero,
        }, _params, false);
        var status = Native.PowerSettingRegisterNotification(
            Native.GUID_CONSOLE_DISPLAY_STATE, Native.DEVICE_NOTIFY_CALLBACK, _params, out _handle);
        if (status != 0) _handle = IntPtr.Zero;
    }

    public string Current => _current;

    private uint OnNotify(IntPtr context, uint type, IntPtr setting)
    {
        if (type != Native.PBT_POWERSETTINGCHANGE || setting == IntPtr.Zero) return 0;
        // POWERBROADCAST_SETTING: Guid (16) · DataLength (4) · Data
        var guid = Marshal.PtrToStructure<Guid>(setting);
        if (guid != Native.GUID_CONSOLE_DISPLAY_STATE) return 0;
        var length = Marshal.ReadInt32(setting, 16);
        if (length < 4) return 0;
        _current = Marshal.ReadInt32(setting, 20) switch
        {
            0 => "off",
            1 => "on",
            2 => "dimmed",
            _ => "unknown",
        };
        return 0;
    }

    public void Dispose()
    {
        if (_handle != IntPtr.Zero) Native.PowerSettingUnregisterNotification(_handle);
        _handle = IntPtr.Zero;
        Marshal.FreeHGlobal(_params);
    }
}
