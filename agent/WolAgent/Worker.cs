using System.Net;
using System.Net.Sockets;
using WolAgent.Core;
using WolAgent.Protocol;

namespace WolAgent;

/// <summary>UDP 40900, tüm IPv4 arayüzleri (spec 13.5). Her datagram <see cref="AgentCore"/>'a gider.</summary>
public sealed class Worker(AgentCore core, ILogger<Worker> logger) : BackgroundService
{
    // Windows: önceki gönderim ICMP "port unreachable" alırsa ReceiveAsync 10054 fırlatır; kapatılır.
    private const int SIO_UDP_CONNRESET = -1744830452;

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var udp = new UdpClient(AddressFamily.InterNetwork);
        udp.Client.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, false);
        udp.Client.IOControl(SIO_UDP_CONNRESET, [0, 0, 0, 0], null);
        udp.EnableBroadcast = true;
        udp.Client.Bind(new IPEndPoint(IPAddress.Any, AgentProtocol.Port));
        logger.LogInformation("WolAgent dinliyor: UDP {Port} (protokol v{Version})", AgentProtocol.Port, AgentProtocol.Version);

        while (!stoppingToken.IsCancellationRequested)
        {
            UdpReceiveResult packet;
            try
            {
                packet = await udp.ReceiveAsync(stoppingToken);
            }
            catch (OperationCanceledException)
            {
                break;
            }
            catch (SocketException e)
            {
                logger.LogWarning("UDP alma hatası: {Error}", e.SocketErrorCode);
                continue;
            }

            try
            {
                var reply = core.Handle(packet.Buffer, packet.RemoteEndPoint.Address);
                if (reply is not null) await udp.SendAsync(reply, packet.RemoteEndPoint, stoppingToken);
            }
            catch (Exception e) when (e is not OperationCanceledException)
            {
                logger.LogError(e, "İstek işlenemedi ({Source})", packet.RemoteEndPoint);
            }
        }
    }
}
