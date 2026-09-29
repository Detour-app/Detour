using System.Net;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Shared.Api.RateLimiting;

namespace Detour.InfraTests.Api;

public class RateLimitSettingsTests
{
    [Fact]
    public void The_global_limiter_uses_the_configured_ip_budget()
    {
        // The budget comes from the configured RateLimitSettings, whichever order the two
        // registrations run in.
        var services = new ServiceCollection();
        services.AddDefaultRateLimit();
        services.Configure<RateLimitSettings>(s => s.Ip = new RateLimitTier
        {
            TokenLimit = 1,
            TokensPerPeriod = 1,
            ReplenishmentPeriodSeconds = 60,
        });
        using var provider = services.BuildServiceProvider();
        var limiter = provider.GetRequiredService<IOptions<RateLimiterOptions>>().Value.GlobalLimiter!;
        var context = new DefaultHttpContext();
        context.Connection.RemoteIpAddress = IPAddress.Parse("203.0.113.7");

        limiter.AttemptAcquire(context).IsAcquired.Should().BeTrue();
        limiter.AttemptAcquire(context).IsAcquired.Should().BeFalse();
    }
}
