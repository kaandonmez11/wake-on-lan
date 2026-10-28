using WolAgent;

// S00: boş servis iskeleti. CLI (install/uninstall/reset/status) ve UDP sunucusu S04'te gelir.
var builder = Host.CreateApplicationBuilder(args);
builder.Services.AddWindowsService(options => options.ServiceName = "WolAgent");
builder.Services.AddHostedService<Worker>();

var host = builder.Build();
host.Run();
