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
    /// <item><c>KnownProxies</c> defaults to the IPv6 loopback address rather than an empty
    /// list, so the defaults are cleared before the configured entries are added.</item>
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
    /// Nothing at all, rather than middleware configured to trust an empty list, because the
    /// framework's <c>KnownProxies</c> default is the IPv6 loopback address — so "configured
    /// with nothing" still believes a loopback peer's header. Absence is the only formulation
    /// that ignores forwarded headers by construction.
    /// </remarks>
    public static IApplicationBuilder UseTrustedProxies(
        this IApplicationBuilder app,
        ForwardedHeadersSettings settings) =>
        settings.IsConfigured ? app.UseForwardedHeaders() : app;
}
