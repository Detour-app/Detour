using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.Api.Notifications;

public static class NotificationsInstaller
{
    public static IServiceCollection AddNotifications(
        this IServiceCollection services, IConfiguration configuration)
    {
        var settings = configuration.GetSection(NotificationSettings.SectionName)
            .Get<NotificationSettings>() ?? new NotificationSettings();
        services.AddSingleton(settings);

        // Singleton: FirebaseApp is process-global and the SDK's HTTP client
        // is built to be shared.
        services.AddSingleton<IFcmGateway, FcmGateway>();

        // Build the gateway at host start, not on the first job: surfaces the
        // unconfigured warning at startup and turns a bad credentials path into a
        // failed deploy rather than a silent no-op.
        services.AddHostedService<FcmGatewayWarmup>();

        // PushDispatcher is scoped because IDeviceTokenRepository is scoped; the
        // worker resolves it per job inside its own scope.
        services.AddSingleton<IPushQueue, PushQueue>();
        services.AddScoped<PushDispatcher>();
        services.AddHostedService<PushDispatchWorker>();

        return services;
    }
}
