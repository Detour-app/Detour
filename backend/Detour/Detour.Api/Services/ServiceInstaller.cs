namespace Detour.Api.Services;

public static class ServiceInstaller
{
    public static IServiceCollection AddDetourServices(this IServiceCollection services)
    {
        services.AddScoped<ISyncService, SyncService>();
        services.AddScoped<IFriendshipService, FriendshipService>();
        services.AddScoped<IRouteSharingService, RouteSharingService>();
        services.AddScoped<IPlannedRideService, PlannedRideService>();
        services.AddScoped<IGroupService, GroupService>();
        services.AddScoped<IConvoyInviteService, ConvoyInviteService>();
        services.AddSingleton<ConvoyJoinThrottle>();
        services.AddScoped<ICircleService, CircleService>();
        services.AddScoped<IDashboardService, DashboardService>();
        services.AddScoped<IDeviceService, DeviceService>();
        services.AddHttpClient(AccountDeletionNotifier.HttpClientName, client =>
            client.Timeout = AccountDeletionNotifier.Timeout);
        services.AddSingleton<IAccountDeletionNotifier, AccountDeletionNotifier>();

        return services;
    }
}
