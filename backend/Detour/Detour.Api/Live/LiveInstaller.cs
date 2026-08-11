namespace Detour.Api.Live;

public static class LiveInstaller
{
    /// <summary>
    /// The relay is a singleton because a connection outlives the request that opened it, and a
    /// frame raised while serving one rider has to reach a socket another rider opened. Everything
    /// that touches the database around it stays scoped.
    /// </summary>
    public static IServiceCollection AddLiveRelay(this IServiceCollection services)
    {
        services.AddSingleton<LiveRelay>();
        services.AddSingleton<ILiveRelay>(provider => provider.GetRequiredService<LiveRelay>());
        services.AddScoped<ILiveLocationService, LiveLocationService>();
        services.AddHostedService<LiveRevocationSweep>();

        return services;
    }
}
