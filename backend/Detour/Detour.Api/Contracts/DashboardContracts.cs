using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

public record DashboardStatsResponse(
    [Required] RiderStatsResponse Stats,
    [Required] int RideCount,
    [Required] int BadgeCount,
    [Required] IReadOnlyDictionary<string, long> Badges,
    [Required] IReadOnlyList<BadgeProgressResponse> BadgeCatalogue);

public record BadgeProgressResponse(
    [Required] string Id,
    [Required] string Kind,
    [Required] string Title,
    [Required] int Threshold,
    [Required] double Value,
    long? EarnedAtMs,
    [Required] double ProgressPercent);

public record RideSummaryResponse(
    [Required] long StartMs,
    long? EndMs,
    string? Mode,
    [Required] double DistanceKm,
    [Required] double TopSpeedKmh,
    double? MaxLeanDegrees,
    double? MaxGForce,
    [Required] int PointCount);

public record RidesResponse([Required] IReadOnlyList<RideSummaryResponse> Rides);

/// <summary>
/// One ride, thinned to fit a dashboard entity. <c>usedPoints</c> against <c>pointCount</c>
/// says how much was dropped to get there.
/// </summary>
public record RideTrackResponse(
    long? StartMs,
    long? EndMs,
    string? Mode,
    [Required] double DistanceKm,
    double? TopSpeedKmh,
    double? MaxLeanDegrees,
    [Required] int PointCount,
    [Required] int UsedPoints,
    MapBounds? Bounds,
    [Required] IReadOnlyList<IReadOnlyList<double>> Coordinates);

/// <summary>Corners of the box containing the track, for a map that has to choose a zoom.</summary>
public record MapBounds(
    [Required] double MinLatitude,
    [Required] double MinLongitude,
    [Required] double MaxLatitude,
    [Required] double MaxLongitude,
    [Required] double CentreLatitude,
    [Required] double CentreLongitude);

public record TracesResponse([Required] IReadOnlyList<IReadOnlyList<IReadOnlyList<double>>> Traces);

public record CoverageResponse(
    [Required] int LineCount,
    [Required] int PointCount,
    MapBounds? Bounds,
    [Required] IReadOnlyList<IReadOnlyList<IReadOnlyList<double>>> Lines);

public record IssueApiKeyBody(string? Label);

/// <summary>
/// <c>key</c> is the only time the plaintext exists. It is not stored and cannot be shown
/// again; a lost key is replaced, not recovered.
/// </summary>
public record IssuedApiKeyResponse(
    [Required] Guid Id,
    [Required] string Label,
    [Required] string Key);

public record ApiKeyResponse(
    [Required] Guid Id,
    [Required] string Label,
    [Required] long CreatedAtMs,
    long? LastUsedAtMs);

public record ApiKeysResponse([Required] IReadOnlyList<ApiKeyResponse> Keys);
