using System.Text.Json;
using Google.Apis.Auth.OAuth2;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;

namespace Detour.Api.Notifications;

public static class NotificationsInstaller
{
    // The one OAuth2 scope an FCM v1 send needs.
    private const string FcmScope = "https://www.googleapis.com/auth/firebase.messaging";

    public static IServiceCollection AddNotifications(
        this IServiceCollection services, IConfiguration configuration)
    {
        var settings = configuration.GetSection(NotificationSettings.SectionName)
            .Get<NotificationSettings>() ?? new NotificationSettings();
        services.AddSingleton(settings);

        // One HttpClient per cloud, so their handlers (and APNs' HTTP/2 negotiation)
        // stay independent — a stuck Apple connection cannot starve Google.
        services.AddHttpClient(FcmGateway.HttpClientName);
        services.AddHttpClient(ApnsGateway.HttpClientName);

        // Two real implementations behind IPushGateway, resolved as a set the
        // dispatcher indexes by platform. Singletons: each holds a cached credential
        // and a shared HTTP client.
        services.AddSingleton<IPushGateway>(BuildFcmGateway);
        services.AddSingleton<IPushGateway, ApnsGateway>();

        // Build both gateways at host start, not on the first job: surfaces the
        // "not configured" warnings at startup and turns a bad credentials path into
        // a failed deploy rather than a silent no-op.
        services.AddHostedService<PushGatewayWarmup>();

        // PushDispatcher is scoped because IDeviceTokenRepository is scoped; the
        // worker resolves it per job inside its own scope.
        services.AddSingleton<IPushQueue, PushQueue>();
        services.AddScoped<PushDispatcher>();
        services.AddHostedService<PushDispatchWorker>();

        return services;
    }

    private static FcmGateway BuildFcmGateway(IServiceProvider serviceProvider)
    {
        var settings = serviceProvider.GetRequiredService<NotificationSettings>();
        var logger = serviceProvider.GetRequiredService<ILogger<FcmGateway>>();
        var httpClientFactory = serviceProvider.GetRequiredService<IHttpClientFactory>();

        var (projectId, accessToken) = LoadFcmCredential(settings.FirebaseCredentialsPath, logger);
        return new FcmGateway(projectId, accessToken, httpClientFactory, logger);
    }

    /// <summary>
    /// Reads the service-account JSON once: the project id out of it (for the send
    /// URL) and a Google credential that mints and caches the OAuth2 bearer. Unset
    /// path ⇒ the gateway is disabled (returns nulls, no-ops). A path that is set
    /// but unreadable or malformed throws — an operator error that should fail the
    /// deploy, not silently drop every Android wake-ping.
    /// </summary>
    private static (string? ProjectId, Func<CancellationToken, Task<string>>? AccessToken) LoadFcmCredential(
        string? credentialsPath, ILogger logger)
    {
        if (string.IsNullOrWhiteSpace(credentialsPath))
        {
            logger.LogWarning(
                "Notifications:FirebaseCredentialsPath is not set. Android push wake-pings are disabled.");
            return (null, null);
        }

        string projectId;
        GoogleCredential credential;
        try
        {
            using var json = JsonDocument.Parse(File.ReadAllText(credentialsPath));
            projectId = json.RootElement.GetProperty("project_id").GetString()
                ?? throw new InvalidOperationException("service-account JSON has no project_id");

            // ponytail: GoogleCredential.FromFile is [Obsolete] advisory in Google.Apis.Auth;
            // the replacement is async-only and this is a one-shot startup read. Switch if the
            // warning ever hardens into an error.
#pragma warning disable CS0618
            credential = GoogleCredential.FromFile(credentialsPath).CreateScoped(FcmScope);
#pragma warning restore CS0618
        }
        catch (Exception ex)
        {
            throw new InvalidOperationException(
                $"Notifications:FirebaseCredentialsPath '{credentialsPath}' could not be read "
                + "as a Firebase service-account key.", ex);
        }

        ITokenAccess tokenAccess = credential;
        return (projectId, ct => tokenAccess.GetAccessTokenForRequestAsync(cancellationToken: ct));
    }
}
