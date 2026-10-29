using WolAgent;
using WolAgent.Cli;
using WolAgent.Core;
using WolAgent.Storage;
using WolAgent.Windows;

// Tek exe (spec 13.1): argümansız = servis; install/uninstall/reset/status = CLI;
// screen-on/monitor-off = servisin konsol oturumunda başlattığı yardımcı (K22).
var command = args.FirstOrDefault()?.ToLowerInvariant();
try
{
    Console.OutputEncoding = System.Text.Encoding.UTF8; // Türkçe CLI çıktısı için
}
catch (IOException)
{
    // Servis modunda konsol yok.
}
switch (command)
{
    case Helper.ScreenOnArg or Helper.MonitorOffArg:
        return Helper.Run(command);
    case "install" or "uninstall" or "reset" or "status":
        return AgentCli.Run(command, args);
    case "-h" or "--help" or "help" or "/?":
        return AgentCli.Usage();
    case null or "run":
        break; // "run": hata ayıklama için konsolda servis modu
    default:
        return AgentCli.Usage();
}

var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(options => options.ServiceName = AgentCli.ServiceName);
builder.Services.AddSingleton(TimeProvider.System);
builder.Services.AddSingleton<IConfigStore>(_ =>
{
    var store = new FileConfigStore(FileConfigStore.DefaultDirectory, new DpapiProtector());
    store.EnsureCreated(); // config silinmişse servis yeni kimlikle açılır (eşleşme yok)
    return store;
});
builder.Services.AddSingleton<IRequestLog>(sp =>
    new RequestLog(Path.Combine(FileConfigStore.DefaultDirectory, "logs"), sp.GetRequiredService<TimeProvider>()));
builder.Services.AddSingleton<DisplayStateMonitor>();
builder.Services.AddSingleton<ISystemInfo, WindowsSystemInfo>();
builder.Services.AddSingleton<ICommandExecutor, WindowsCommandExecutor>();
builder.Services.AddSingleton(sp => new AgentCore(
    sp.GetRequiredService<IConfigStore>(),
    sp.GetRequiredService<ISystemInfo>(),
    sp.GetRequiredService<ICommandExecutor>(),
    sp.GetRequiredService<TimeProvider>(),
    sp.GetRequiredService<IRequestLog>()));
builder.Services.AddHostedService<Worker>();

var host = builder.Build();
host.Run();
return 0;
