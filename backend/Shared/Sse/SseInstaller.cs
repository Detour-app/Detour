using Microsoft.Extensions.DependencyInjection;

namespace Shared.Sse;

public static class SseInstaller
{
    public static IServiceCollection AddSse(this IServiceCollection services)
    {
        services.AddSingleton<SseEventBus>();
        services.AddSingleton<ISseEventBus>(sp => sp.GetRequiredService<SseEventBus>());
        services.AddHostedService<SseKeepAliveService>();
        return services;
    }
}