using System.ComponentModel;
using System.Runtime.InteropServices;
using WolAgent.Core;

namespace WolAgent.Windows;

/// <summary>
/// Spec 13.4: servis LocalSystem'da, Session 0'da çalışır. Masaüstüne dokunan iş (hazırda bekletmesi kapalı
/// Modern Beklemeli PC'de ekranı kapatma) aynı exe'nin konsol oturumunda başlatılan yardımcı moduyla yapılır.
/// </summary>
public sealed class WindowsCommandExecutor(ILogger<WindowsCommandExecutor> logger) : ICommandExecutor
{
    public Task ExecuteAsync(string cmd, bool force, CancellationToken ct)
    {
        switch (cmd)
        {
            case "LOCK":
                // Servisten LockWorkStation çalışmaz; konsol oturumunu ayırmak kilit ekranını getirir.
                if (!Native.WTSDisconnectSession(Native.WTS_CURRENT_SERVER_HANDLE, Native.WTSGetActiveConsoleSessionId(), false))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "WTSDisconnectSession");
                break;

            case "SLEEP":
                Sleep();
                break;

            case "HIBERNATE":
                Privileges.EnableShutdown();
                if (!Native.SetSuspendState(true, false, false))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "SetSuspendState(hibernate)");
                break;

            case "SHUTDOWN":
            case "RESTART":
                Privileges.EnableShutdown();
                if (!Native.InitiateSystemShutdownEx(null, null, 0, force, cmd == "RESTART", Native.ShutdownReason))
                    throw new Win32Exception(Marshal.GetLastWin32Error(), "InitiateSystemShutdownEx");
                break;

            default:
                throw new NotSupportedException(cmd);
        }
        return Task.CompletedTask;
    }

    private void Sleep()
    {
        var caps = PowerCapabilities.Read();
        Privileges.EnableShutdown();
        if (caps.SleepHibernates)
        {
            // K23: Modern Bekleme'den uzaktan uyandırma yok (K22 denemesi); hazırda bekletme WoL ile uyanır.
            logger.LogInformation("SLEEP → hazırda bekletme (Modern Bekleme, K23)");
            if (!Native.SetSuspendState(true, false, false))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "SetSuspendState(hibernate)");
            return;
        }
        if (Native.SetSuspendState(false, false, false))
        {
            logger.LogInformation("SLEEP: SetSuspendState tamam (S3={S3}, ModernStandby={Aoac})", caps.S3, caps.ModernStandby);
            return;
        }
        var error = Marshal.GetLastWin32Error();
        if (!caps.ModernStandby) throw new Win32Exception(error, "SetSuspendState(sleep)");
        // Modern Bekleme + hazırda bekletme kapalı: ekranı kapatmak bekleme moduna geçişi başlatır.
        logger.LogWarning("SLEEP: SetSuspendState başarısız ({Error}); Modern Bekleme için ekran kapatılıyor", error);
        SessionHelper.Run(Helper.MonitorOffArg, logger);
    }
}

internal static class Privileges
{
    public static void EnableShutdown() => Enable("SeShutdownPrivilege");

    private static void Enable(string name)
    {
        if (!Native.OpenProcessToken(Native.GetCurrentProcess(), Native.TOKEN_ADJUST_PRIVILEGES | Native.TOKEN_QUERY, out var token))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "OpenProcessToken");
        try
        {
            if (!Native.LookupPrivilegeValue(null, name, out var luid))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "LookupPrivilegeValue");
            var tp = new Native.TokenPrivileges { Count = 1, Luid = luid, Attributes = Native.SE_PRIVILEGE_ENABLED };
            if (!Native.AdjustTokenPrivileges(token, false, ref tp, (uint)Marshal.SizeOf<Native.TokenPrivileges>(), IntPtr.Zero, IntPtr.Zero))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "AdjustTokenPrivileges");
        }
        finally
        {
            Native.CloseHandle(token);
        }
    }
}

/// <summary>
/// Aynı exe'yi konsol oturumunda (SYSTEM belirteci, oturum kimliği değiştirilmiş) yardımcı modunda başlatır.
/// Kullanıcı oturum açmamış olsa da çalışır; yardımcı giriş masaüstüne kendisi geçer.
/// </summary>
internal static class SessionHelper
{
    public static void Run(string arg, ILogger logger)
    {
        var exe = Environment.ProcessPath ?? throw new InvalidOperationException("Exe yolu bilinmiyor");
        var session = Native.WTSGetActiveConsoleSessionId();
        if (session == 0xFFFFFFFF) throw new InvalidOperationException("Etkin konsol oturumu yok");

        if (!Native.OpenProcessToken(Native.GetCurrentProcess(), Native.TOKEN_ALL_ACCESS, out var own))
            throw new Win32Exception(Marshal.GetLastWin32Error(), "OpenProcessToken");
        var token = IntPtr.Zero;
        try
        {
            if (!Native.DuplicateTokenEx(own, Native.TOKEN_ALL_ACCESS, IntPtr.Zero, Native.SecurityImpersonation, Native.TokenPrimary, out token))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "DuplicateTokenEx");
            if (!Native.SetTokenInformation(token, Native.TokenSessionId, ref session, sizeof(uint)))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "SetTokenInformation(SessionId)");

            var si = new Native.StartupInfo { cb = Marshal.SizeOf<Native.StartupInfo>(), lpDesktop = @"winsta0\default" };
            if (!Native.CreateProcessAsUser(token, exe, $"\"{exe}\" {arg}", IntPtr.Zero, IntPtr.Zero, false,
                    Native.CREATE_NO_WINDOW, IntPtr.Zero, Path.GetDirectoryName(exe), ref si, out var pi))
                throw new Win32Exception(Marshal.GetLastWin32Error(), "CreateProcessAsUser");
            Native.CloseHandle(pi.hThread);
            Native.CloseHandle(pi.hProcess);
            logger.LogInformation("Yardımcı başlatıldı: {Arg}, oturum {Session}, pid {Pid}", arg, session, pi.dwProcessId);
        }
        finally
        {
            if (token != IntPtr.Zero) Native.CloseHandle(token);
            Native.CloseHandle(own);
        }
    }
}

/// <summary>Konsol oturumunda çalışan yardımcı mod (`WolAgent.exe monitor-off`).</summary>
public static class Helper
{
    public const string MonitorOffArg = "monitor-off";

    public static int Run(string arg)
    {
        if (arg != MonitorOffArg) return 1;
        // Kilit/giriş ekranı açıksa giriş masaüstü Winlogon'dur; mesaj oraya da gitsin.
        var desk = Native.OpenInputDesktop(0, false, Native.GENERIC_ALL);
        if (desk != IntPtr.Zero) Native.SetThreadDesktop(desk);
        try
        {
            Native.SendMessageTimeout(Native.HWND_BROADCAST, Native.WM_SYSCOMMAND, Native.SC_MONITORPOWER, 2,
                Native.SMTO_ABORTIFHUNG, 1000, out _);
            return 0;
        }
        finally
        {
            if (desk != IntPtr.Zero) Native.CloseDesktop(desk);
        }
    }
}
