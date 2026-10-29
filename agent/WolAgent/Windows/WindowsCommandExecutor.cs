using System.ComponentModel;
using System.Runtime.InteropServices;
using WolAgent.Core;

namespace WolAgent.Windows;

/// <summary>
/// Spec 13.4: servis LocalSystem'da, Session 0'da çalışır. Masaüstüne dokunan işler (ekranı açma,
/// Modern Bekleme'de uyku) aynı exe'nin konsol oturumunda başlatılan yardımcı moduyla yapılır.
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

            case "SCREEN_ON":
                // Servis tarafında da ekran isteği; asıl iş konsol oturumundaki yardımcıda (sahte fare hareketi).
                Native.SetThreadExecutionState(Native.ES_SYSTEM_REQUIRED | Native.ES_DISPLAY_REQUIRED);
                SessionHelper.Run(Helper.ScreenOnArg, logger);
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
        if (Native.SetSuspendState(false, false, false))
        {
            logger.LogInformation("SLEEP: SetSuspendState tamam (S3={S3}, ModernStandby={Aoac})", caps.S3, caps.ModernStandby);
            return;
        }
        var error = Marshal.GetLastWin32Error();
        if (!caps.ModernStandby) throw new Win32Exception(error, "SetSuspendState(sleep)");
        // Modern Bekleme'de S3 yok: ekranı kapatmak bekleme moduna geçişi başlatır.
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

/// <summary>Konsol oturumunda çalışan yardımcı modlar (`WolAgent.exe screen-on` / `monitor-off`).</summary>
public static class Helper
{
    public const string ScreenOnArg = "screen-on";
    public const string MonitorOffArg = "monitor-off";

    public static int Run(string arg)
    {
        // Kilit/giriş ekranı açıksa giriş masaüstü Winlogon'dur; SendInput yalnız giriş masaüstünde etkili.
        var desk = Native.OpenInputDesktop(0, false, Native.GENERIC_ALL);
        if (desk != IntPtr.Zero) Native.SetThreadDesktop(desk);
        try
        {
            switch (arg)
            {
                case ScreenOnArg:
                    Native.SetThreadExecutionState(Native.ES_DISPLAY_REQUIRED);
                    var size = Marshal.SizeOf<Native.Input>();
                    Native.Input Move(int dx) => new() { type = Native.INPUT_MOUSE, mi = new Native.MouseInput { dx = dx, dwFlags = Native.MOUSEEVENTF_MOVE } };
                    var sent = Native.SendInput(2, [Move(1), Move(-1)], size);
                    Native.SendMessageTimeout(Native.HWND_BROADCAST, Native.WM_SYSCOMMAND, Native.SC_MONITORPOWER, -1,
                        Native.SMTO_ABORTIFHUNG, 1000, out _);
                    return sent == 2 ? 0 : 2;
                case MonitorOffArg:
                    Native.SendMessageTimeout(Native.HWND_BROADCAST, Native.WM_SYSCOMMAND, Native.SC_MONITORPOWER, 2,
                        Native.SMTO_ABORTIFHUNG, 1000, out _);
                    return 0;
                default:
                    return 1;
            }
        }
        finally
        {
            if (desk != IntPtr.Zero) Native.CloseDesktop(desk);
        }
    }
}
