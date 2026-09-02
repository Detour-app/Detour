using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Options;
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
                    app.Run(Echo);
                }))
            .StartAsync();
    }

    /// <summary>
    /// <c>/scheme</c> echoes the request scheme, anything else the resolved client address.
    /// Two answers from one terminal so both <c>X-Forwarded-For</c> and
    /// <c>X-Forwarded-Proto</c> are observable through the same host.
    /// </summary>
    private static Task Echo(HttpContext context) =>
        context.Response.WriteAsync(
            context.Request.Path == "/scheme"
                ? context.Request.Scheme
                : context.Connection.RemoteIpAddress?.ToString() ?? "none");

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

    private static async Task<string> ResolvedScheme(
        ForwardedHeadersSettings settings,
        string peer,
        string forwardedProto)
    {
        using var host = await HostWith(settings, IPAddress.Parse(peer));
        var client = host.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Forwarded-Proto", forwardedProto);

        return await client.GetStringAsync("/scheme");
    }

    private static ForwardedHeadersSettings BoundFrom(Dictionary<string, string?> values) =>
        ForwardedHeadersSettings.From(
            new ConfigurationBuilder().AddInMemoryCollection(values).Build());

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
    public async Task A_loopback_peer_is_not_trusted_unless_it_is_the_configured_proxy()
    {
        // The framework seeds KnownProxies with ::1 and KnownIPNetworks with 127.0.0.0/8, so
        // an operator who names 10.0.0.9 would also, silently, be trusting anything on the
        // loopback interface. AddTrustedProxies clears both lists before adding the configured
        // entries; this is the test that makes those two Clear() calls load-bearing.
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownProxies = ["10.0.0.9"] },
            peer: "127.0.0.1",
            forwardedFor: "203.0.113.7");

        resolved.Should().Be("127.0.0.1");
    }

    [Fact]
    public async Task A_trusted_proxy_can_forward_the_scheme()
    {
        // UseHttpsRedirection reads this. Without XForwardedProto a request that reached the
        // proxy over https is answered with a redirect to http, which the proxy then resolves
        // over http again.
        var scheme = await ResolvedScheme(
            new ForwardedHeadersSettings { KnownProxies = ["10.0.0.9"] },
            peer: "10.0.0.9",
            forwardedProto: "https");

        scheme.Should().Be("https");
    }

    [Fact]
    public async Task Only_the_hop_the_trusted_proxy_appended_is_believed()
    {
        // ForwardLimit is 1, so only the rightmost entry — the one the nearest trusted hop
        // added — counts. Anything to its left was supplied by the caller. Were the limit
        // null, a caller could prepend an address of its choosing and pick its own rate-limit
        // bucket on every request.
        var resolved = await ResolvedAddress(
            new ForwardedHeadersSettings { KnownProxies = ["10.0.0.9"] },
            peer: "10.0.0.9",
            forwardedFor: "1.2.3.4, 198.51.100.5");

        resolved.Should().Be("198.51.100.5");
    }

    [Fact]
    public async Task No_middleware_is_added_when_no_proxy_is_configured()
    {
        // Distinct from the spoofed-header test: there the options were never configured
        // either, so removing only the UseTrustedProxies guard changes nothing observable.
        // Here the options are already set up to trust the peer, so the sole thing keeping the
        // header from being honoured is that the middleware is absent.
        using var host = await new HostBuilder()
            .ConfigureWebHost(web => web
                .UseTestServer()
                .ConfigureServices(services => services.Configure<ForwardedHeadersOptions>(options =>
                {
                    options.ForwardedHeaders =
                        Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor;
                    options.KnownProxies.Add(IPAddress.Parse("10.0.0.9"));
                }))
                .Configure(app =>
                {
                    app.Use(async (context, next) =>
                    {
                        context.Connection.RemoteIpAddress = IPAddress.Parse("10.0.0.9");
                        await next();
                    });
                    app.UseTrustedProxies(new ForwardedHeadersSettings());
                    app.Run(Echo);
                }))
            .StartAsync();

        var client = host.GetTestClient();
        client.DefaultRequestHeaders.Add("X-Forwarded-For", "203.0.113.7");

        (await client.GetStringAsync("/")).Should().Be("10.0.0.9");
    }

    [Fact]
    public void The_options_are_left_untouched_when_no_proxy_is_configured()
    {
        // The other half of the pair above: AddTrustedProxies must not call Configure at all.
        // ForwardedHeaders staying None is the observable proof that it did not, and it is
        // what makes an accidentally-registered middleware inert rather than dangerous.
        var services = new ServiceCollection().AddOptions();

        services.AddTrustedProxies(new ForwardedHeadersSettings());

        var options = services.BuildServiceProvider()
            .GetRequiredService<IOptions<ForwardedHeadersOptions>>().Value;

        options.ForwardedHeaders.Should()
            .Be(Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.None);
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

    [Fact]
    public void A_malformed_proxy_network_fails_at_startup()
    {
        var settings = new ForwardedHeadersSettings { KnownNetworks = ["172.16.0.0/99"] };

        var act = () => new ServiceCollection().AddTrustedProxies(settings);

        act.Should().Throw<FormatException>();
    }

    [Fact]
    public void A_scalar_value_binds_as_one_entry()
    {
        // The environment-variable shape, which is the one docker/prod/docker-compose.yml
        // uses: `ForwardedHeaders__KnownNetworks: 172.16.0.0/12`. The configuration binder
        // fills a string[] only from indexed children, so a settings class that relied on
        // binding would leave this empty and never trust the proxy — on every containerised
        // deployment, without an error.
        var settings = BoundFrom(new() { ["ForwardedHeaders:KnownNetworks"] = "172.16.0.0/12" });

        settings.IsConfigured.Should().BeTrue();
        settings.KnownNetworks.Should().Equal("172.16.0.0/12");
    }

    [Fact]
    public void Indexed_children_bind_as_a_list()
    {
        // The appsettings.json shape: a JSON array arrives as ForwardedHeaders:KnownProxies:0.
        var settings = BoundFrom(new() { ["ForwardedHeaders:KnownProxies:0"] = "10.0.0.9" });

        settings.IsConfigured.Should().BeTrue();
        settings.KnownProxies.Should().Equal("10.0.0.9");
    }

    [Fact]
    public void A_delimited_scalar_binds_as_several_trimmed_entries()
    {
        // Several proxies through one environment variable. The trim matters:
        // IPAddress.Parse(" 10.0.0.10") throws, so the space after the comma would otherwise
        // fail the boot.
        var settings = BoundFrom(new() { ["ForwardedHeaders:KnownProxies"] = "10.0.0.9, 10.0.0.10" });

        settings.KnownProxies.Should().Equal("10.0.0.9", "10.0.0.10");
    }

    [Fact]
    public void An_empty_scalar_leaves_the_deployment_unconfigured()
    {
        // What `${FORWARDED_KNOWN_NETWORKS:-}` expands to when the operator has no proxy. It
        // must stay unconfigured rather than becoming one empty entry, which IPNetwork.Parse
        // would throw on — failing the boot for everyone who legitimately has no proxy.
        var settings = BoundFrom(new()
        {
            ["ForwardedHeaders:KnownNetworks"] = string.Empty,
            ["ForwardedHeaders:KnownProxies"] = string.Empty,
        });

        settings.IsConfigured.Should().BeFalse();
        settings.KnownNetworks.Should().BeEmpty();
    }

    [Fact]
    public void An_absent_section_leaves_the_deployment_unconfigured()
    {
        var settings = ForwardedHeadersSettings.From(new ConfigurationBuilder().Build());

        settings.IsConfigured.Should().BeFalse();
    }
}
