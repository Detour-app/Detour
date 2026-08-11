using System.IdentityModel.Tokens.Jwt;
using System.Security.Claims;
using System.Security.Cryptography;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.IdentityModel.Tokens;

namespace Detour.InfraTests.Api;

/// <summary>
/// The real API, against a real Postgres, with a stand-in issuer.
///
/// Tokens are signed with a key generated here and the bearer handler is pointed at it, so the
/// pipeline under test is the production one — same validation parameters, same policies, same
/// role flattening. Only the party holding the signing key changes. A test that stubbed out
/// authentication instead would prove nothing about the thing most worth proving.
/// </summary>
public sealed class DetourApiFactory(PostgresFixture postgres) : WebApplicationFactory<Program>
{
    private const string Issuer = "https://test-issuer.detour.invalid/realms/detour";
    private const string Audience = "detour-api";

    private readonly RsaSecurityKey _signingKey =
        new(RSA.Create(2048)) { KeyId = "detour-test-key" };

    protected override void ConfigureWebHost(IWebHostBuilder builder)
    {
        builder.UseEnvironment("Testing");

        // UseSetting, not ConfigureAppConfiguration: Program.cs reads configuration while
        // building the host, before the callbacks a WebApplicationFactory can register run.
        // These land in host configuration, which is already in place by then.
        builder.UseSetting("ConnectionStrings:DefaultConnection", postgres.ConnectionString);
        builder.UseSetting("Idp:Authority", Issuer);
        builder.UseSetting("Idp:Audience", Audience);
        builder.UseSetting("Idp:RequireHttpsMetadata", "false");
        builder.UseSetting("Idp:AdministratorRole", "detour-admin");
        // The fixture already migrated; a second migrate here races it.
        builder.UseSetting("Database:SkipMigrations", "true");
        // Memory-only cache: Redis adds nothing to what these tests check.
        builder.UseSetting("Cache:RedisConnectionString", "");
        builder.UseSetting("OpenTelemetry:ServiceName", "detour-api-tests");
        builder.UseSetting("OpenTelemetry:OtlpEndpoint", "http://localhost:4317");

        builder.ConfigureTestServices(services =>
        {
            // Replace discovery with the local key. Everything else about the handler — issuer,
            // audience, lifetime, clock skew, claim mapping — stays exactly as configured.
            services.Configure<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme, options =>
            {
                options.Authority = null;
                options.MetadataAddress = null!;
                options.ConfigurationManager = null;
                options.TokenValidationParameters.IssuerSigningKey = _signingKey;
                options.TokenValidationParameters.ValidateIssuerSigningKey = true;
            });

        });
    }

    /// <summary>A token shaped exactly like the realm's: nested realm roles included.</summary>
    public string IssueToken(
        string subject,
        string username,
        string? email = null,
        params string[] realmRoles)
    {
        var claims = new List<Claim>
        {
            new("sub", subject),
            new("preferred_username", username),
            new("realm_access", JsonSerializer.Serialize(new { roles = realmRoles }), JsonClaimValueTypes.Json),
        };

        if (!string.IsNullOrWhiteSpace(email))
            claims.Add(new Claim("email", email));

        var token = new JwtSecurityToken(
            issuer: Issuer,
            audience: Audience,
            claims: claims,
            notBefore: DateTime.UtcNow.AddMinutes(-1),
            expires: DateTime.UtcNow.AddMinutes(15),
            signingCredentials: new SigningCredentials(_signingKey, SecurityAlgorithms.RsaSha256));

        return new JwtSecurityTokenHandler().WriteToken(token);
    }

    /// <summary>A token signed by a key this API has never heard of.</summary>
    public string IssueForeignToken(string subject, string username)
    {
        var foreignKey = new RsaSecurityKey(RSA.Create(2048)) { KeyId = "not-ours" };
        var token = new JwtSecurityToken(
            issuer: Issuer,
            audience: Audience,
            claims: [new Claim("sub", subject), new Claim("preferred_username", username)],
            expires: DateTime.UtcNow.AddMinutes(15),
            signingCredentials: new SigningCredentials(foreignKey, SecurityAlgorithms.RsaSha256));

        return new JwtSecurityTokenHandler().WriteToken(token);
    }

    public HttpClient CreateClientWith(string token)
    {
        var client = CreateClient();
        client.DefaultRequestHeaders.Authorization = new("Bearer", token);
        return client;
    }
}
