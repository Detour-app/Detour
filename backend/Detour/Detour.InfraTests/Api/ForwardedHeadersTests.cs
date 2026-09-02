using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Shared.Api.ForwardedHeaders;

namespace Detour.InfraTests.Api;

/// <summary>
/// What the rate limiter ends up keying on. Asserted through
/// <c>HttpContext.Connection.RemoteIpAddress</c> because that is exactly the value
/// <c>RateLimitingExtensions.ResolveClientIp</c> returns, so these are tests of the limiter's
/// partition key without reaching into a private method.
/// </summary>
public class ForwardedHeadersTests
{
    /// <summary>
    /// A host carrying only the middleware under test. The peer address is set by a middleware
    /// registered ahead of it, since a TestServer connection has none of its own.
    /// </summary>
    private static async Task<IHost> HostWith(ForwardedHeadersSettings settings, IPAddress peer)
    {
        return await new HostBuilder()
            .ConfigureWebHost(web => web
                .UseTestServer()
                .ConfigureServices(services => services.AddTrustedProxies(settings))
                .Configure(app =>
                {
                    app.Use(async (context, next) =>
                    {
                        context.Connection.RemoteIpAddress = peer;
                        await next();
                    });
                    app.UseTrustedProxies(settings);
                    app.Run(context =>
                        context.Response.WriteAsync(
                            context.Connection.RemoteIpAddress?.ToString() ?? "none"));
                }))
            .StartAsync();
    }

    private static async Task<string> ResolvedAddress(
        ForwardedHeadersSettings settings,
        string peer,
        string? forwardedFor)
    {
        using var host = await HostWith(settings, IPAddress.Parse(peer));
        var client = host.GetTestClient();
        if (forwardedFor is not null)
            client.DefaultRequestHeaders.Add("X-Forwarded-For", forwardedFor);

        return await client.GetStringAsync("/");
    }

    [Fact]
    public async Task A_spoofed_forwarded_header_is_ignored_when_no_proxy_is_trusted()
    {
        // The safe default, and the regression guard for it. If someone later "simplifies" the
        // conditional registration away, this is the test that fails — and it has to, because
        // trusting the header unconditionally lets any caller reset its own rate-limit bucket
        // per request, which is worse than the shared bucket this change fixes.
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings(), peer: "10.0.0.9", forwardedFor: "203.0.113.7");

        resolved.Should().Be("10.0.0.9");
    }

    [Fact]
    public async Task A_forwarded_header_from_a_trusted_proxy_is_honoured()
    {
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownProxies = ["10.0.0.9"] },
            peer: "10.0.0.9",
            forwardedFor: "203.0.113.7");

        resolved.Should().Be("203.0.113.7");
    }

    [Fact]
    public async Task A_forwarded_header_from_an_untrusted_hop_is_ignored()
    {
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownProxies = ["10.0.0.9"] },
            peer: "10.0.0.250",
            forwardedFor: "203.0.113.7");

        resolved.Should().Be("10.0.0.250");
    }

    [Fact]
    public async Task A_trusted_proxy_can_be_named_as_a_network()
    {
        // The form docker-compose.yml documents, and the one an operator on a Docker bridge
        // actually needs, since the container's address is assigned rather than fixed.
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownNetworks = ["10.0.0.0/24"] },
            peer: "10.0.0.9",
            forwardedFor: "203.0.113.7");

        resolved.Should().Be("203.0.113.7");
    }

    [Fact]
    public void A_malformed_proxy_address_fails_at_startup()
    {
        // Loudly, rather than degrading to an untrusted proxy — which would silently reinstate
        // the shared-bucket bug on a deployment whose operator believes they configured this.
        var settings = new ForwardedHeadersSettings { KnownProxies = ["not-an-address"] };

        var act = () => new ServiceCollection().AddTrustedProxies(settings);

        act.Should().Throw<FormatException>();
    }
}
