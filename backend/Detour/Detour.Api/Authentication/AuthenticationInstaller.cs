using System.Security.Claims;
using System.Text.Json;
using Detour.Api.Configuration;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.IdentityModel.Tokens;

namespace Detour.Api.Authentication;

public static class AuthenticationInstaller
{
    /// <summary>
    /// Single realm, single audience. Stock JWT bearer validation — there is no tenant to
    /// resolve, so there is no custom handler to get wrong.
    /// </summary>
    public static IServiceCollection AddDetourAuthentication(
        this IServiceCollection services,
        IdpSettings idp)
    {
        services
            .AddAuthentication(DetourAuthenticationSchemes.Bearer)
            .AddJwtBearer(DetourAuthenticationSchemes.Bearer, options =>
            {
                options.Authority = idp.Authority;
                options.Audience = idp.Audience;
                options.RequireHttpsMetadata = idp.RequireHttpsMetadata;

                // Keep the claim names the token actually carries. The default renames 'sub'
                // to the long ClaimTypes.NameIdentifier URI, which silently breaks every read
                // of the one claim that links a token to a local account.
                options.MapInboundClaims = false;

                options.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidateIssuer = true,
                    ValidIssuer = idp.Authority,
                    ValidateAudience = true,
                    ValidAudience = idp.Audience,
                    ValidateLifetime = true,
                    ValidateIssuerSigningKey = true,
                    // Kept small. The framework default of five minutes silently extends the
                    // life of every token by that much.
                    ClockSkew = TimeSpan.FromSeconds(idp.ClockSkewSeconds),
                    NameClaimType = DetourClaims.PreferredUsername,
                    RoleClaimType = ClaimTypes.Role,
                };

                options.Events = new JwtBearerEvents
                {
                    OnTokenValidated = context =>
                    {
                        FlattenRealmRoles(context.Principal);
                        return Task.CompletedTask;
                    }
                };
            })
            .AddScheme<ApiKeyAuthenticationOptions, ApiKeyAuthenticationHandler>(
                DetourAuthenticationSchemes.ApiKey,
                _ => { });

        return services;
    }

    /// <summary>
    /// Keycloak nests realm roles inside a <c>realm_access</c> JSON object, which the role
    /// claim type cannot see. Lift them onto the principal as ordinary role claims so
    /// <c>RequireRole</c> and <c>[Authorize(Roles = …)]</c> work at all.
    ///
    /// Deliberately done here, on the identity the handler built, rather than by trusting a
    /// flat <c>roles</c> claim: a flat claim can be present in a token that never went through
    /// the realm's own role mapping.
    /// </summary>
    private static void FlattenRealmRoles(ClaimsPrincipal? principal)
    {
        if (principal?.Identity is not ClaimsIdentity identity)
            return;

        var realmAccess = identity.FindFirst(DetourClaims.RealmAccess)?.Value;
        if (string.IsNullOrWhiteSpace(realmAccess))
            return;

        try
        {
            using var document = JsonDocument.Parse(realmAccess);
            if (!document.RootElement.TryGetProperty("roles", out var roles)
                || roles.ValueKind != JsonValueKind.Array)
            {
                return;
            }

            foreach (var role in roles.EnumerateArray())
            {
                if (role.GetString() is { Length: > 0 } name)
                    identity.AddClaim(new Claim(ClaimTypes.Role, name));
            }
        }
        catch (JsonException)
        {
            // A token whose realm_access is not JSON is a token this backend cannot read roles
            // from. It stays authenticated with no roles, which fails closed on every policy
            // that needs one.
        }
    }
}
