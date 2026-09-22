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
    [Required] IdpCapabilityResponse Idp,
    RoutingCapabilityResponse? Routing,
    GeocoderCapabilityResponse? Geocoder,
    CameraCapabilityResponse? Cameras,
    SpeedLimitCapabilityResponse? SpeedLimits,
    RoadCapabilityResponse? Roads)
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

    /// <summary>This deployment announces its own GraphHopper instance (issue #177).</summary>
    public const string RoutingDiscoveryFeature = "routing-discovery";

    /// <summary>This deployment announces its own Photon instance (issue #177).</summary>
    public const string GeocoderDiscoveryFeature = "geocoder-discovery";

    /// <summary>This deployment announces its own camera-data endpoint (issue #303).</summary>
    public const string CamerasDiscoveryFeature = "cameras-discovery";

    /// <summary>This deployment announces its own speed-limit-way endpoint (issue #379).</summary>
    public const string SpeedLimitsDiscoveryFeature = "speedlimits-discovery";

    /// <summary>This deployment announces its own drivable-road endpoint (issue #380).</summary>
    public const string RoadsDiscoveryFeature = "roads-discovery";

    /// <summary>
    /// The advertised feature set for a deployment whose push gateways are
    /// <paramref name="pushPlatforms"/>, and whose routing/geocoder/cameras/speed-limits/roads
    /// discovery is <paramref name="routingAnnounced"/>/<paramref name="geocoderAnnounced"/>/
    /// <paramref name="camerasAnnounced"/>/<paramref name="speedLimitsAnnounced"/>/
    /// <paramref name="roadsAnnounced"/>.
    ///
    /// Split from <see cref="From"/> so the mapping can be asserted without a
    /// host: the controller's job is to read the gateways and settings, and this
    /// one's is to decide what that means on the wire.
    /// </summary>
    public static IReadOnlyList<string> FeaturesFor(
        IEnumerable<DevicePlatform> pushPlatforms,
        bool routingAnnounced,
        bool geocoderAnnounced,
        bool camerasAnnounced,
        bool speedLimitsAnnounced,
        bool roadsAnnounced)
    {
        var platforms = pushPlatforms.ToHashSet();
        var features = new List<string>(AlwaysOnFeatures);
        if (platforms.Contains(DevicePlatform.Android))
            features.Add(PushAndroidFeature);
        if (platforms.Contains(DevicePlatform.Ios))
            features.Add(PushIosFeature);
        if (routingAnnounced)
            features.Add(RoutingDiscoveryFeature);
        if (geocoderAnnounced)
            features.Add(GeocoderDiscoveryFeature);
        if (camerasAnnounced)
            features.Add(CamerasDiscoveryFeature);
        if (speedLimitsAnnounced)
            features.Add(SpeedLimitsDiscoveryFeature);
        if (roadsAnnounced)
            features.Add(RoadsDiscoveryFeature);
        return features;
    }

    public static CapabilitiesResponse From(
        IdpSettings idpSettings,
        IEnumerable<DevicePlatform> pushPlatforms,
        RoutingSettings routingSettings,
        GeocoderSettings geocoderSettings,
        CameraSettings cameraSettings,
        SpeedLimitSettings speedLimitSettings,
        RoadSettings roadSettings)
    {
        // Blank means "not configured", and the field itself carries that —
        // absent, not present-but-empty, so a client can test for null rather
        // than for a blank string.
        var routing = string.IsNullOrWhiteSpace(routingSettings.BaseUrl)
            ? null
            : new RoutingCapabilityResponse(routingSettings.BaseUrl);
        var geocoder = string.IsNullOrWhiteSpace(geocoderSettings.BaseUrl)
            ? null
            : new GeocoderCapabilityResponse(geocoderSettings.BaseUrl);
        var cameras = string.IsNullOrWhiteSpace(cameraSettings.BaseUrl)
            ? null
            : new CameraCapabilityResponse(cameraSettings.BaseUrl);
        var speedLimits = string.IsNullOrWhiteSpace(speedLimitSettings.BaseUrl)
            ? null
            : new SpeedLimitCapabilityResponse(speedLimitSettings.BaseUrl);
        var roads = string.IsNullOrWhiteSpace(roadSettings.BaseUrl)
            ? null
            : new RoadCapabilityResponse(roadSettings.BaseUrl);
        return new(
            SchemaVersion,
            FeaturesFor(
                pushPlatforms, routing is not null, geocoder is not null, cameras is not null,
                speedLimits is not null, roads is not null),
            new IdpCapabilityResponse(idpSettings.Authority),
            routing,
            geocoder,
            cameras,
            speedLimits,
            roads);
    }
}

/// <summary>
/// Where riders sign in. <see cref="Issuer"/> is <c>Idp:Authority</c> verbatim —
/// the same string the token pipeline requires as <c>iss</c>, exactly and not as
/// a prefix. Stating it unchanged is what makes it impossible for this server to
/// advertise a realm whose tokens it would then refuse.
/// </summary>
public record IdpCapabilityResponse([Required] string Issuer);

/// <summary>
/// Where this deployment's own GraphHopper instance is, for a client that would
/// otherwise need it typed in by hand (issue #177). Absent — not present with a
/// blank <see cref="BaseUrl"/> — when <c>Routing:BaseUrl</c> is unconfigured.
///
/// The client is the one place that decides whether to trust this: it is never
/// promoted into a rider's own typed configuration, and a plain-<c>http://</c>
/// value is refused rather than upgraded. See <c>RoutingServer.kt</c>'s
/// discovery pair for the mirror of <see cref="IdpCapabilityResponse"/>'s own
/// rule, extended to this field.
/// </summary>
public record RoutingCapabilityResponse([Required] string BaseUrl);

/// <summary>
/// Where this deployment's own Photon instance is. See
/// <see cref="RoutingCapabilityResponse"/> for the reasoning, which applies here
/// unchanged.
/// </summary>
public record GeocoderCapabilityResponse([Required] string BaseUrl);

/// <summary>
/// Where this deployment's own camera-data endpoint is. See
/// <see cref="RoutingCapabilityResponse"/> for the reasoning, which applies here
/// unchanged.
/// </summary>
public record CameraCapabilityResponse([Required] string BaseUrl);

/// <summary>
/// Where this deployment's own speed-limit-way endpoint is. See
/// <see cref="RoutingCapabilityResponse"/> for the reasoning, which applies here
/// unchanged.
/// </summary>
public record SpeedLimitCapabilityResponse([Required] string BaseUrl);

/// <summary>
/// Where this deployment's own drivable-road endpoint is. See
/// <see cref="RoutingCapabilityResponse"/> for the reasoning, which applies here
/// unchanged.
/// </summary>
public record RoadCapabilityResponse([Required] string BaseUrl);
