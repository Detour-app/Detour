using System.Net;
using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.HttpOverrides;
using Microsoft.Extensions.DependencyInjection;

namespace Shared.Api.ForwardedHeaders;

/// <summary>
/// Wiring for <c>X-Forwarded-*</c>, opt-in by construction.
/// </summary>
public static class ForwardedHeadersExtensions
{
    /// <summary>
    /// Configures the forwarded-headers middleware, when — and only when — the operator named
    /// a proxy.
    /// </summary>
    /// <remarks>
    /// Every framework default here is overridden deliberately rather than inherited:
    /// <list type="bullet">
    /// <item><c>ForwardedHeaders</c> defaults to <c>None</c>, so the middleware would run and
    /// do nothing.</item>
    /// <item><c>KnownProxies</c> defaults to <c>::1</c> and <c>KnownIPNetworks</c> to
    /// <c>127.0.0.0/8</c> rather than to empty lists, so both are cleared before the
    /// configured entries are added — otherwise naming one proxy silently trusts anything on
    /// the loopback interface too.</item>
    /// <item><c>ForwardLimit</c> is left at its default of 1: one proxy, one hop. A chained
    /// deployment needs a deliberate change here, not a silently larger number.</item>
    /// </list>
    /// Parsing is strict and a malformed value throws at startup. A mistyped CIDR must be a
    /// boot failure rather than a quietly untrusted proxy, which would look like a working
    /// deployment while every caller shared one rate-limit bucket.
    /// </remarks>
    public static IServiceCollection AddTrustedProxies(
        this IServiceCollection services,
        ForwardedHeadersSettings settings)
    {
        if (!settings.IsConfigured) return services;

        var proxies = settings.KnownProxies.Select(IPAddress.Parse).ToList();
        var networks = settings.KnownNetworks.Select(System.Net.IPNetwork.Parse).ToList();

        services.Configure<ForwardedHeadersOptions>(options =>
        {
            options.ForwardedHeaders =
                Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedFor
                | Microsoft.AspNetCore.HttpOverrides.ForwardedHeaders.XForwardedProto;

            options.KnownProxies.Clear();
            options.KnownIPNetworks.Clear();

            foreach (var proxy in proxies) options.KnownProxies.Add(proxy);
            foreach (var network in networks) options.KnownIPNetworks.Add(network);
        });

        return services;
    }

    /// <summary>
    /// Adds the forwarded-headers middleware when a proxy is configured, and nothing at all
    /// when one is not.
    /// </summary>
    /// <remarks>
    /// Nothing at all, rather than middleware configured to trust an empty list. Measured on
    /// net10.0: an enabled middleware whose trust lists are both empty honours
    /// <c>X-Forwarded-For</c> from any peer at all — peer <c>203.0.113.99</c> claiming
    /// <c>1.2.3.4</c> resolves to <c>1.2.3.4</c>. "Configured to trust nobody" is therefore
    /// the opposite of what empty lists produce, and absence is the only formulation that
    /// ignores forwarded headers by construction.
    /// </remarks>
    public static IApplicationBuilder UseTrustedProxies(
        this IApplicationBuilder app,
        ForwardedHeadersSettings settings) =>
        settings.IsConfigured ? app.UseForwardedHeaders() : app;
}
