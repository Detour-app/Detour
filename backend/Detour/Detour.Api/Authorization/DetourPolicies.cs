using Detour.Api.Authentication;
using Microsoft.AspNetCore.Authorization;

namespace Detour.Api.Authorization;

public static class DetourPolicies
{
    /// <summary>
    /// A signed-in rider. Everything the app calls sits behind this — deliberately a policy
    /// rather than a bare <c>[Authorize]</c>, so it can only be satisfied by the bearer scheme
    /// and never by a read-only dashboard key.
    /// </summary>
    public const string Rider = "rider";

    /// <summary>
    /// An administrator. Grants account management and nothing else: there is no endpoint that
    /// returns anyone's trips, traces, routes or places, and that is what keeps the privacy
    /// promise rather than the absence of a permission here.
    /// </summary>
    public const string Administrator = "administrator";

    /// <summary>
    /// A read-only dashboard key. Only ever authorises reads of its own owner's data.
    /// </summary>
    public const string Dashboard = "dashboard";
}

public static class AuthorizationInstaller
{
    public static IServiceCollection AddDetourAuthorization(
        this IServiceCollection services,
        string administratorRole)
    {
        services.AddAuthorizationBuilder()
            .AddPolicy(DetourPolicies.Rider, policy => policy
                .AddAuthenticationSchemes(DetourAuthenticationSchemes.Bearer)
                .RequireAuthenticatedUser())
            .AddPolicy(DetourPolicies.Administrator, policy => policy
                .AddAuthenticationSchemes(DetourAuthenticationSchemes.Bearer)
                .RequireAuthenticatedUser()
                .RequireRole(administratorRole))
            .AddPolicy(DetourPolicies.Dashboard, policy => policy
                .AddAuthenticationSchemes(DetourAuthenticationSchemes.ApiKey)
                .RequireAuthenticatedUser());

        return services;
    }
}
