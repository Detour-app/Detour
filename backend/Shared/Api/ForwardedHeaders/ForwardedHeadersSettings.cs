namespace Shared.Api.ForwardedHeaders;

/// <summary>
/// Which upstream hops may be believed when they claim, via <c>X-Forwarded-For</c>, to be
/// relaying somebody else's request. Bind from <c>appsettings.json</c> section
/// <c>ForwardedHeaders</c>.
/// </summary>
/// <remarks>
/// Both lists empty is the safe default and means forwarded headers are ignored entirely —
/// see <see cref="IsConfigured"/>. The property names are the ones
/// <c>docker/prod/docker-compose.yml</c> and <c>backend/INSTALL.md</c> already tell operators
/// to set, so they are deliberately not renamed to match the framework's own
/// <c>KnownIPNetworks</c>.
/// </remarks>
public sealed class ForwardedHeadersSettings
{
    public const string SectionName = "ForwardedHeaders";

    /// <summary>Exact proxy addresses to trust, e.g. <c>172.18.0.5</c>.</summary>
    public string[] KnownProxies { get; set; } = [];

    /// <summary>Proxy networks in CIDR form, e.g. <c>172.18.0.0/16</c>.</summary>
    public string[] KnownNetworks { get; set; } = [];

    /// <summary>
    /// Whether the operator named a proxy. False means the middleware is never added, which is
    /// the only way to ignore forwarded headers entirely: the framework's own default for
    /// <c>KnownProxies</c> is the IPv6 loopback address rather than an empty list, so
    /// configuring the options with nothing in them still trusts a loopback peer.
    /// </summary>
    public bool IsConfigured => KnownProxies.Length > 0 || KnownNetworks.Length > 0;
}
