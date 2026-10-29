using System.Globalization;
using WolAgent.Core;

namespace WolAgent.Storage;

/// <summary>
/// <c>%ProgramData%\WolAgent\logs\agent-yyyyMMdd.log</c>: günlük dönen, 7 gün saklanır.
/// Satır: zaman · tip · kaynak IP · sonuç. Anahtar, imza, nonce yazılmaz (spec 13.3).
/// </summary>
public sealed class RequestLog(string directory, TimeProvider time) : IRequestLog
{
    public static readonly TimeSpan Retention = TimeSpan.FromDays(7);

    private readonly Lock _gate = new();
    private DateOnly _lastCleanup;

    public void Write(string type, string source, string result)
    {
        var now = time.GetLocalNow();
        var line = $"{now:yyyy-MM-dd HH:mm:ss.fff}\t{type}\t{source}\t{result}{Environment.NewLine}";
        lock (_gate)
        {
            try
            {
                Directory.CreateDirectory(directory);
                File.AppendAllText(Path.Combine(directory, $"agent-{now:yyyyMMdd}.log"), line);
                var today = DateOnly.FromDateTime(now.DateTime);
                if (today != _lastCleanup)
                {
                    _lastCleanup = today;
                    Cleanup(now);
                }
            }
            catch (IOException)
            {
                // Log yazılamaması isteği düşürmemeli.
            }
        }
    }

    private void Cleanup(DateTimeOffset now)
    {
        foreach (var file in Directory.EnumerateFiles(directory, "agent-*.log"))
        {
            var stamp = Path.GetFileNameWithoutExtension(file)["agent-".Length..];
            if (DateTime.TryParseExact(stamp, "yyyyMMdd", CultureInfo.InvariantCulture, DateTimeStyles.None, out var day)
                && day < now.Date - Retention)
            {
                File.Delete(file);
            }
        }
    }
}
