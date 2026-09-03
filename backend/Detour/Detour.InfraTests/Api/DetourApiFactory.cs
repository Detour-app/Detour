using System.IdentityModel.Tokens.Jwt;
using System.Net.Http.Json;
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
    // Public so a test can assert that the API advertises exactly what it was
    // configured with, rather than repeating the literal and drifting from it.
    public const string Issuer = "https://test-issuer.detour.invalid/realms/detour";
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

    /// <summary>
    /// Signs in a fresh rider under a random handle and provisions their account, resolving the
    /// id up front so a test can address them by whichever the endpoint under test expects —
    /// riders are addressed by id everywhere except the handful of lookup-by-handle endpoints.
    /// </summary>
    public async Task<SignedInClient> SignInAsync()
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = CreateClientWith(IssueToken($"subject-{Guid.NewGuid():N}", username, null, "detour-user"));

        var signedIn = new SignedInClient(client, username);
        await signedIn.ResolveUserIdAsync();
        return signedIn;
    }
}

/// <summary>
/// A bearer-authenticated test client plus the identity it signed in as. Forwards the handful of
/// HTTP verbs the test suite needs so a call site never has to reach past this for the plain
/// <see cref="HttpClient"/> just to get at <see cref="Username"/> or <see cref="UserId"/>.
/// </summary>
public sealed class SignedInClient(HttpClient client, string username)
{
    public HttpClient Client { get; } = client;

    public string Username { get; } = username;

    // Fetched once on sign-in, from the endpoint that already returns it.
    public Guid UserId { get; private set; }

    internal async Task ResolveUserIdAsync()
    {
        var me = await Client.GetFromJsonAsync<JsonElement>("/api/me");
        UserId = me.GetProperty("id").GetGuid();
    }

    public Task<HttpResponseMessage> GetAsync(string requestUri) => Client.GetAsync(requestUri);

    public Task<HttpResponseMessage> DeleteAsync(string requestUri) => Client.DeleteAsync(requestUri);

    public Task<HttpResponseMessage> PostAsJsonAsync<TValue>(string requestUri, TValue value) =>
        Client.PostAsJsonAsync(requestUri, value);

    public Task<HttpResponseMessage> PutAsJsonAsync<TValue>(string requestUri, TValue value) =>
        Client.PutAsJsonAsync(requestUri, value);

    public Task<TValue?> GetFromJsonAsync<TValue>(string requestUri) =>
        Client.GetFromJsonAsync<TValue>(requestUri);
}
