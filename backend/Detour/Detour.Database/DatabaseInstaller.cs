using Detour.Database.Configuration;
using Detour.Database.Repositories;
using Detour.Domain.ApiKeys;
using Detour.Domain.Circles;
using Detour.Domain.Friendships;
using Detour.Domain.Groups;
using Detour.Domain.Notifications;
using Detour.Domain.Places;
using Detour.Domain.Routes;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Detour.Domain.Users;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Shared.Database;

namespace Detour.Database;

public static class DatabaseInstaller
{
    public static IServiceCollection AddDetourDatabase(
        this IServiceCollection services,
        IConfiguration configuration)
    {
        var settings = configuration.GetSection(DatabaseSettings.SectionName).Get<DatabaseSettings>()
                       ?? new DatabaseSettings();
        services.AddSingleton(settings);

        var connectionString = configuration.GetConnectionString("DefaultConnection")
                               ?? throw new InvalidOperationException(
                                   "ConnectionStrings:DefaultConnection is required. It is the only "
                                   + "way this service reaches its database; failing here beats "
                                   + "failing on the first request.");

        // Scoped, not singleton: the factory caches one context per scope so a request's
        // repositories share a change tracker and the transaction middleware's commit covers
        // all of them.
        services.AddScoped<ICustomDbContextFactory<DetourDbContext>>(
            _ => new DetourDbContextFactory(settings, connectionString));

        return services.AddRepositories();
    }

    public static IServiceCollection AddRepositories(this IServiceCollection services)
    {
        services.AddScoped<IUserRepository, UserRepository>();
        services.AddScoped<IBadgeAwardRepository, BadgeAwardRepository>();
        services.AddScoped<ITripRepository, TripRepository>();
        services.AddScoped<ITraceRepository, TraceRepository>();
        services.AddScoped<ITrackPointRepository, TrackPointRepository>();
        services.AddScoped<ISavedPlaceRepository, SavedPlaceRepository>();
        services.AddScoped<IFriendshipRepository, FriendshipRepository>();
        services.AddScoped<ISharedRouteRepository, SharedRouteRepository>();
        services.AddScoped<IGroupRepository, GroupRepository>();
        services.AddScoped<IMemberFixRepository, MemberFixRepository>();
        services.AddScoped<ICirclePlaceRepository, CirclePlaceRepository>();
        services.AddScoped<IPlaceEventRepository, PlaceEventRepository>();
        services.AddScoped<IApiKeyRepository, ApiKeyRepository>();
        services.AddScoped<IDeviceTokenRepository, DeviceTokenRepository>();

        return services;
    }
}
