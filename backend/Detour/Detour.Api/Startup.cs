using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Configuration;
using Detour.Api.Services;
using Detour.Api.Translations;
using Detour.Database;
using Detour.Domain;
using Microsoft.AspNetCore.Server.Kestrel.Core;
using Microsoft.Extensions.Diagnostics.HealthChecks;
using Microsoft.Extensions.Options;
using Shared.Api;
using Shared.Api.Middlewares;
using Shared.Api.OpenApi;
using Shared.Api.RateLimiting;
using Shared.Caching;
using Shared.Logging;
using Shared.OpenTelemetry;
using Shared.Sse;

namespace Detour.Api;

public class Startup(IConfiguration configuration)
{
    private ApiConfiguration MappedConfiguration { get; } =
        configuration.Get<ApiConfiguration>()
        ?? throw new InvalidOperationException("Configuration is missing or invalid.");

    public void ConfigureLogging(ConfigureHostBuilder hostBuilder) =>
        hostBuilder.ConfigureSerilog(MappedConfiguration.Serilog);

    public void ConfigureServices(IServiceCollection services)
    {
        services.AddSingleton(MappedConfiguration.OpenTelemetry);
        services.AddSingleton(TimeProvider.System);
        services.AddHttpContextAccessor();

        services.Configure<IdpSettings>(configuration.GetSection(IdpSettings.SectionName));
        services.AddSingleton<IOptions<IdpSettings>>(
            new OptionsWrapper<IdpSettings>(MappedConfiguration.Idp));

        services.AddDetourDatabase(configuration);
        services.AddPostCommitActionScheduler();

        services.AddScoped<ICurrentUser, CurrentUser>();
        services.AddDetourServices();

        services.AddCaching(new CacheConfiguration
        {
            RedisConnectionString = MappedConfiguration.Cache.RedisConnectionString,
            Duration = TimeSpan.FromSeconds(MappedConfiguration.Cache.DurationSeconds),
            FailSafeMaxDuration = TimeSpan.FromSeconds(MappedConfiguration.Cache.FailSafeMaxDurationSeconds),
        });

        // Registered ahead of the live surface, which is deliberately not built yet. Circles work
        // without it: positions and presence events are ordinary REST reads and writes.
        services.AddSse();

        // The app gzips the bodies it sends — a sync upload is the whole trip and trace history
        // each time, and it compresses about ten to one. Nothing decompresses a *request* body
        // by default, so without this every sync arrives as unreadable bytes and fails as a 400.
        //
        // The middleware bounds the decompressed stream by the same max request body size that
        // bounds the compressed one, which is what stops a compression bomb being cheap.
        services.AddRequestDecompression();
        services.Configure<KestrelServerOptions>(options =>
            options.Limits.MaxRequestBodySize = DetourLimits.MaxRequestBodyBytes);

        services.AddRouting(options => options.LowercaseUrls = true);
        services.AddEndpointsApiExplorer();
        services.AddProblemDetails();
        services.AddTranslations();
        services.SetupOpenApi();

        services.SetupOpenTelemetry(MappedConfiguration.OpenTelemetry);
        services.Configure<RateLimitSettings>(configuration.GetSection("RateLimit"));
        services.AddDefaultRateLimit(DetourAuthenticationSchemes.ApiKey);

        services.AddDetourAuthentication(MappedConfiguration.Idp);
        services.AddDetourAuthorization(MappedConfiguration.Idp.AdministratorRole);

        var healthChecks = services.AddHealthChecks()
            .AddNpgSql(
                configuration.GetConnectionString("DefaultConnection")!,
                name: "postgres",
                failureStatus: HealthStatus.Unhealthy,
                tags: ["critical"]);

        // Redis is L2 only — a cache miss is a slower request, not a broken one — so it is
        // degraded rather than critical, and unregistered entirely when not configured.
        if (!string.IsNullOrWhiteSpace(MappedConfiguration.Cache.RedisConnectionString))
        {
            healthChecks.AddRedis(
                MappedConfiguration.Cache.RedisConnectionString,
                name: "redis",
                failureStatus: HealthStatus.Degraded);
        }

        var origins = MappedConfiguration.Cors.AllowedOrigins;
        services.AddCors(options => options.AddDefaultPolicy(policy =>
            policy.WithOrigins(origins).AllowAnyHeader().AllowAnyMethod()));
    }

    /// <summary>
    /// Order here is the security boundary, not a style choice. Each comment records what
    /// breaks when a piece moves.
    /// </summary>
    public static void Configure(WebApplication app)
    {
        app.UseCors();
        app.UseRequestLocalization();

        // Before anything reads a body, and before authentication: an unauthenticated request
        // is exactly the one whose decompressed size has to be bounded, and the bound is what
        // this installs.
        app.UseRequestDecompression();

        app.UseMiddleware<TraceIdMiddleware>();

        // Early, so it wraps the controllers and the transaction middleware: a re-thrown
        // ResultException is caught here and rendered as a localized 400.
        app.UseExceptionHandler();
        app.UseStatusCodePages();

        app.UseRouting();

        app.UseAuthentication();
        app.UseAuthorization();

        // After authentication, so the per-rider and per-key partitions can read the claims
        // they key on. Before the transaction middleware, so a throttled request never opens a
        // database transaction.
        app.UseRateLimiter();

        // After authentication for the same reason: an unauthenticated request must not be
        // able to open a transaction, which is otherwise a cheap way to exhaust the pool.
        app.UseCustomTransactionMiddleware<Database.DetourDbContext>();

        if (app.Environment.IsLocalDevelopment())
            app.MapOpenApi();
        else
            app.UseHttpsRedirection();

        app.MapControllers();
    }
}
