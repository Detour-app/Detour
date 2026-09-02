using Detour.Database.Configuration;
using Shared.Logging;
using Shared.OpenTelemetry;

namespace Detour.Api.Configuration;

/// <summary>
/// The whole of <c>appsettings.json</c>, bound once at startup so a missing or malformed section
/// is a boot failure rather than a null reference on the first request that needs it.
/// </summary>
public class ApiConfiguration
{
    public required IdpSettings Idp { get; set; }
    public DatabaseSettings Database { get; set; } = new();
    public SerilogConfiguration Serilog { get; set; } = new();
    public required OpenTelemetrySettings OpenTelemetry { get; set; }
    public CacheSettings Cache { get; set; } = new();
    public CorsSettings Cors { get; set; } = new();
}

/// <summary>
/// Where the identity provider lives and what it must say about a token before this backend
/// will act on it. Single realm, single audience — there is no tenant dimension here and adding
/// one is a product decision, not a configuration change.
/// </summary>
public class IdpSettings
{
    public const string SectionName = "Idp";

    /// <summary>
    /// The exact <c>iss</c> claim to require, e.g. <c>http://localhost:7580/realms/detour</c>.
    /// Exact, not a prefix: accepting a family of issuers is how a token from a neighbouring
    /// realm gets honoured.
    /// </summary>
    public required string Authority { get; set; }

    /// <summary>
    /// The <c>aud</c> claim to require. A token minted for a different client is rejected even
    /// when the issuer matches and the signature verifies.
    /// </summary>
    public required string Audience { get; set; }

    /// <summary>
    /// Off only for a local stack that speaks plain HTTP. Anywhere else, discovery over HTTP is
    /// an invitation to swap the signing keys in transit.
    /// </summary>
    public bool RequireHttpsMetadata { get; set; } = true;

    /// <summary>
    /// Realm role that marks an administrator. Mirrored onto the local account on each sign-in
    /// so revoking it in the identity provider takes effect on the next token, not whenever a
    /// local row happens to be rewritten.
    /// </summary>
    public string AdministratorRole { get; set; } = "detour-admin";

    /// <summary>
    /// Tolerance for clock drift between this service and the identity provider. Kept small:
    /// the default five minutes silently extends every token's life.
    /// </summary>
    public int ClockSkewSeconds { get; set; } = 30;
}

public class CacheSettings
{
    /// <summary>Empty means memory-only, which is a correct single-instance deployment.</summary>
    public string? RedisConnectionString { get; set; }

    public int DurationSeconds { get; set; } = 120;
    public int FailSafeMaxDurationSeconds { get; set; } = 600;
}

public class CorsSettings
{
    public string[] AllowedOrigins { get; set; } = [];
}
