namespace Detour.Api.Services;

public static class ServiceInstaller
{
    public static IServiceCollection AddDetourServices(this IServiceCollection services)
    {
        services.AddScoped<ISyncService, SyncService>();
        services.AddScoped<IFriendshipService, FriendshipService>();
        services.AddScoped<IRouteSharingService, RouteSharingService>();
        services.AddScoped<IGroupService, GroupService>();
        services.AddScoped<ICircleService, CircleService>();
        services.AddScoped<IDashboardService, DashboardService>();
        services.AddScoped<IDeviceService, DeviceService>();

        return services;
    }
}
