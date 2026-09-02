using Microsoft.Extensions.Configuration;

namespace Shared.Api.ForwardedHeaders;

/// <summary>
/// Which upstream hops may be believed when they claim, via <c>X-Forwarded-For</c>, to be
/// relaying somebody else's request. Build with <see cref="From"/> rather than binding, for
/// the reason given on <see cref="ReadList"/>.
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
    public string[] KnownProxies { get; init; } = [];

    /// <summary>Proxy networks in CIDR form, e.g. <c>172.18.0.0/16</c>.</summary>
    public string[] KnownNetworks { get; init; } = [];

    /// <summary>
    /// Whether the operator named a proxy. False means the middleware is never added, which is
    /// the only way to ignore forwarded headers entirely: an enabled middleware whose trust
    /// lists are empty honours <c>X-Forwarded-For</c> from any peer at all, so configuring the
    /// options with nothing in them is the opposite of trusting nobody.
    /// </summary>
    public bool IsConfigured => KnownProxies.Length > 0 || KnownNetworks.Length > 0;

    /// <summary>
    /// Reads the <c>ForwardedHeaders</c> section, accepting either shape a deployment can
    /// produce.
    /// </summary>
    public static ForwardedHeadersSettings From(IConfiguration configuration) => new()
    {
        KnownProxies = ReadList(configuration.GetSection($"{SectionName}:{nameof(KnownProxies)}")),
        KnownNetworks = ReadList(configuration.GetSection($"{SectionName}:{nameof(KnownNetworks)}")),
    };

    /// <summary>
    /// Reads one list from a section that may hold either of the two shapes a list arrives in.
    /// </summary>
    /// <remarks>
    /// A JSON array becomes indexed child keys (<c>ForwardedHeaders:KnownNetworks:0</c>); an
    /// environment variable is a single scalar leaf
    /// (<c>ForwardedHeaders__KnownNetworks=172.16.0.0/12</c>), and the scalar form is the one
    /// <c>docker/prod/docker-compose.yml</c> uses. The configuration binder fills a
    /// <c>string[]</c> only from indexed children, so binding the array shape alone is a
    /// silent no-op on every containerised deployment — the operator sets the variable, the
    /// list stays empty, <see cref="IsConfigured"/> stays false, and the proxy is never
    /// trusted. That is why this parsing lives here instead of on the property.
    /// <para>
    /// Delimited scalars are supported for the several-proxies case, and the entries are
    /// trimmed: <c>IPAddress.Parse(" 172.18.0.5 ")</c> throws, so a value with stray
    /// whitespace around a separator would otherwise fail the boot.
    /// </para>
    /// </remarks>
    private static string[] ReadList(IConfigurationSection section)
    {
        var children = section.GetChildren()
            .Select(child => child.Value)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Select(value => value!.Trim())
            .ToArray();

        if (children.Length > 0) return children;

        return (section.Value ?? string.Empty)
            .Split([',', ';', ' '], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
    }
}
