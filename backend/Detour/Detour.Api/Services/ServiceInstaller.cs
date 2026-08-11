namespace Detour.Api.Services;

public static class ServiceInstaller
{
    public static IServiceCollection AddDetourServices(this IServiceCollection services)
    {
        services.AddScoped<ISyncService, SyncService>();

        return services;
    }
}
