using System.ComponentModel.DataAnnotations;
using Detour.Api.Configuration;
using Detour.Domain.Notifications;

namespace Detour.Api.Contracts;

/// <summary>
/// What this deployment can do, for a client that may be newer or older than it.
///
/// The compatibility rules this document follows — a client ignores unknown
/// feature strings and fields, and the schema number moves only when an
/// existing field changes meaning, never for an addition — are stated
/// normatively in docs/BACKEND_SPEC.md §15.5. <see cref="SchemaVersion"/>
/// carries the half of that rule that governs the line most likely to be
/// edited without reading it.
///
/// It is also a version-fingerprinting surface, which is accepted because a
/// self-hosted open-source server's version is discoverable anyway. What follows
/// from that is a content rule: feature names and values a client needs to
/// configure itself, and nothing else. No dependency versions, no build strings,
/// no counts. Operational detail belongs on <c>/api/health</c>.
/// </summary>
public record CapabilitiesResponse(
    [Required] int Schema,
    [Required] IReadOnlyList<string> Features,
    [Required] IdpCapabilityResponse Idp)
{
    /// <summary>
    /// Bumped only when an existing field on this response changes meaning —
    /// never for an addition. See docs/BACKEND_SPEC.md §15.5.
    /// </summary>
    public const int SchemaVersion = 1;

    /// <summary>
    /// What every deployment does regardless of configuration. Each string is a
    /// wire contract clients match on, so renaming one is a breaking change even
    /// though <see cref="SchemaVersion"/> does not move for it.
    /// </summary>
    public static readonly IReadOnlyList<string> AlwaysOnFeatures = ["idp-discovery"];

    /// <summary>Android wake-pings are configured and this server can send them.</summary>
    public const string PushAndroidFeature = "push-android";

    /// <summary>iOS wake-pings are configured and this server can send them.</summary>
    public const string PushIosFeature = "push-ios";

    /// <summary>
    /// The advertised feature set for a deployment whose push gateways are
    /// <paramref name="pushPlatforms"/>.
    ///
    /// Split from <see cref="From"/> so the mapping can be asserted without a
    /// host: the controller's job is to read the gateways, and this one's is to
    /// decide what that means on the wire.
    /// </summary>
    public static IReadOnlyList<string> FeaturesFor(IEnumerable<DevicePlatform> pushPlatforms)
    {
        var platforms = pushPlatforms.ToHashSet();
        var features = new List<string>(AlwaysOnFeatures);
        if (platforms.Contains(DevicePlatform.Android))
            features.Add(PushAndroidFeature);
        if (platforms.Contains(DevicePlatform.Ios))
            features.Add(PushIosFeature);
        return features;
    }

    public static CapabilitiesResponse From(
        IdpSettings idpSettings, IEnumerable<DevicePlatform> pushPlatforms) => new(
        SchemaVersion,
        FeaturesFor(pushPlatforms),
        new IdpCapabilityResponse(idpSettings.Authority));
}

/// <summary>
/// Where riders sign in. <see cref="Issuer"/> is <c>Idp:Authority</c> verbatim —
/// the same string the token pipeline requires as <c>iss</c>, exactly and not as
/// a prefix. Stating it unchanged is what makes it impossible for this server to
/// advertise a realm whose tokens it would then refuse.
/// </summary>
public record IdpCapabilityResponse([Required] string Issuer);
