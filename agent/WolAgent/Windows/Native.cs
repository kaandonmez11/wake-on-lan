using System.Runtime.InteropServices;

namespace WolAgent.Windows;

/// <summary>Win32 P/Invoke tanımları (powrprof, wtsapi32, advapi32, user32, kernel32).</summary>
internal static partial class Native
{
    // --- powrprof ---------------------------------------------------------------------------

    [LibraryImport("powrprof.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.U1)]
    public static partial bool SetSuspendState(
        [MarshalAs(UnmanagedType.U1)] bool hibernate,
        [MarshalAs(UnmanagedType.U1)] bool force,
        [MarshalAs(UnmanagedType.U1)] bool wakeupEventsDisabled);

    /// <summary>SYSTEM_POWER_CAPABILITIES ham tampon olarak okunur; tüm alanlar 1 baytlık BOOLEAN/BYTE.</summary>
    [LibraryImport("powrprof.dll")]
    [return: MarshalAs(UnmanagedType.U1)]
    public static partial bool GetPwrCapabilities(byte[] capabilities);

    // SYSTEM_POWER_CAPABILITIES alan ofsetleri (winnt.h, NTDDI ≥ WINXP dalı):
    // 0 PowerButton … 3 S1, 4 S2, 5 S3, 6 S4, 7 S5, 8 HiberFilePresent, … 14 ProcessorThrottle,
    // 15 ProcessorMinThrottle, 16 ProcessorMaxThrottle, 17 FastSystemS4, 18 Hiberboot, 19 WakeAlarmPresent, 20 AoAc.
    public const int CapSystemS3 = 5;
    public const int CapSystemS4 = 6;
    public const int CapHiberFilePresent = 8;
    public const int CapAoAc = 20;
    public const int CapBufferSize = 128;

    public const uint DEVICE_NOTIFY_CALLBACK = 2;
    public const uint PBT_POWERSETTINGCHANGE = 0x8013;

    [UnmanagedFunctionPointer(CallingConvention.Winapi)]
    public delegate uint PowerNotifyCallback(IntPtr context, uint type, IntPtr setting);

    [StructLayout(LayoutKind.Sequential)]
    public struct DeviceNotifySubscribeParameters
    {
        public IntPtr Callback;
        public IntPtr Context;
    }

    [LibraryImport("powrprof.dll")]
    public static partial uint PowerSettingRegisterNotification(
        in Guid settingGuid, uint flags, IntPtr recipient, out IntPtr registrationHandle);

    [LibraryImport("powrprof.dll")]
    public static partial uint PowerSettingUnregisterNotification(IntPtr registrationHandle);

    public static readonly Guid GUID_CONSOLE_DISPLAY_STATE = new("6FE69556-704A-47A0-8F24-C28D936FDA47");

    // --- kernel32 ---------------------------------------------------------------------------

    [LibraryImport("kernel32.dll")]
    public static partial uint WTSGetActiveConsoleSessionId();

    [LibraryImport("kernel32.dll")]
    public static partial uint SetThreadExecutionState(uint flags);

    public const uint ES_SYSTEM_REQUIRED = 0x00000001;
    public const uint ES_DISPLAY_REQUIRED = 0x00000002;

    [LibraryImport("kernel32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool CloseHandle(IntPtr handle);

    [LibraryImport("kernel32.dll")]
    public static partial IntPtr GetCurrentProcess();

    // --- wtsapi32 ---------------------------------------------------------------------------

    public static readonly IntPtr WTS_CURRENT_SERVER_HANDLE = IntPtr.Zero;

    [LibraryImport("wtsapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool WTSDisconnectSession(IntPtr server, uint sessionId, [MarshalAs(UnmanagedType.Bool)] bool wait);

    // --- advapi32 ---------------------------------------------------------------------------

    public const uint TOKEN_ALL_ACCESS = 0xF01FF;
    public const uint TOKEN_ADJUST_PRIVILEGES = 0x0020;
    public const uint TOKEN_QUERY = 0x0008;
    public const int SecurityImpersonation = 2;
    public const int TokenPrimary = 1;
    public const int TokenSessionId = 12;
    public const uint SE_PRIVILEGE_ENABLED = 0x2;

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool DuplicateTokenEx(IntPtr existing, uint access, IntPtr attributes,
        int impersonationLevel, int tokenType, out IntPtr newToken);

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool SetTokenInformation(IntPtr token, int infoClass, ref uint info, uint length);

    [LibraryImport("advapi32.dll", EntryPoint = "LookupPrivilegeValueW", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool LookupPrivilegeValue(string? system, string name, out long luid);

    [StructLayout(LayoutKind.Sequential, Pack = 4)]
    public struct TokenPrivileges
    {
        public uint Count;
        public long Luid;
        public uint Attributes;
    }

    [LibraryImport("advapi32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool AdjustTokenPrivileges(IntPtr token, [MarshalAs(UnmanagedType.Bool)] bool disableAll,
        ref TokenPrivileges newState, uint length, IntPtr previous, IntPtr returnLength);

    [LibraryImport("advapi32.dll", EntryPoint = "InitiateSystemShutdownExW", SetLastError = true, StringMarshalling = StringMarshalling.Utf16)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool InitiateSystemShutdownEx(string? machine, string? message, uint timeout,
        [MarshalAs(UnmanagedType.Bool)] bool forceAppsClosed, [MarshalAs(UnmanagedType.Bool)] bool reboot, uint reason);

    // SHTDN_REASON_MAJOR_OTHER | SHTDN_REASON_MINOR_OTHER | SHTDN_REASON_FLAG_PLANNED
    public const uint ShutdownReason = 0x00000000 | 0x00000000 | 0x80000000;

    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct StartupInfo
    {
        public int cb;
        public string? lpReserved;
        public string? lpDesktop;
        public string? lpTitle;
        public int dwX, dwY, dwXSize, dwYSize, dwXCountChars, dwYCountChars, dwFillAttribute, dwFlags;
        public short wShowWindow, cbReserved2;
        public IntPtr lpReserved2, hStdInput, hStdOutput, hStdError;
    }

    [StructLayout(LayoutKind.Sequential)]
    public struct ProcessInformation
    {
        public IntPtr hProcess, hThread;
        public int dwProcessId, dwThreadId;
    }

    public const uint CREATE_NO_WINDOW = 0x08000000;

    [DllImport("advapi32.dll", EntryPoint = "CreateProcessAsUserW", SetLastError = true, CharSet = CharSet.Unicode)]
    public static extern bool CreateProcessAsUser(IntPtr token, string? application, string commandLine,
        IntPtr processAttributes, IntPtr threadAttributes, bool inheritHandles, uint creationFlags,
        IntPtr environment, string? currentDirectory, ref StartupInfo startupInfo, out ProcessInformation processInformation);

    // --- user32 (yardımcı süreçte, konsol oturumunda çalışır) ----------------------------------

    [LibraryImport("user32.dll", SetLastError = true)]
    public static partial IntPtr OpenInputDesktop(uint flags, [MarshalAs(UnmanagedType.Bool)] bool inherit, uint access);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool SetThreadDesktop(IntPtr desktop);

    [LibraryImport("user32.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    public static partial bool CloseDesktop(IntPtr desktop);

    public const uint GENERIC_ALL = 0x10000000;

    public static readonly IntPtr HWND_BROADCAST = 0xFFFF;
    public const uint WM_SYSCOMMAND = 0x0112;
    public const int SC_MONITORPOWER = 0xF170;
    public const uint SMTO_ABORTIFHUNG = 0x0002;

    [LibraryImport("user32.dll", EntryPoint = "SendMessageTimeoutW", SetLastError = true)]
    public static partial IntPtr SendMessageTimeout(IntPtr hwnd, uint msg, IntPtr wParam, IntPtr lParam,
        uint flags, uint timeout, out IntPtr result);
}
