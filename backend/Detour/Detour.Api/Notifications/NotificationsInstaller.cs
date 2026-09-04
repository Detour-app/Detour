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

        return services;
    }
}
