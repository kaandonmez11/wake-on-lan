namespace WolAgent;

/// <summary>S00 yer tutucu. S04'te UDP 40900 dinleyicisiyle değişir.</summary>
public class Worker(ILogger<Worker> logger) : BackgroundService
{
    protected override Task ExecuteAsync(CancellationToken stoppingToken)
    {
        logger.LogInformation("WolAgent başladı (protokol v{Version})", Protocol.AgentProtocol.Version);
        return Task.Delay(Timeout.Infinite, stoppingToken);
    }
}
