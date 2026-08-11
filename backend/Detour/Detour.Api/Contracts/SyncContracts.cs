using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Detour.Api.Contracts;

/// <summary>
/// Everything a device holds, offered for merge. Every field is optional, and absent never
/// means "clear" — a client that syncs only trips must not blank the numbers its friends read,
/// and an older build that knows nothing about a field must not be able to reset it.
/// </summary>
public record SyncRequest
{
    /// <summary>
    /// Recorded journeys. The document is stored as sent; only a handful of well-known fields
    /// are read out of it, for the read-only dashboard's ride list.
    /// </summary>
    public IReadOnlyList<TripPayload>? Trips { get; init; }

    /// <summary>
    /// Start instants the device has deleted. Applied after the upserts, so a trip present in
    /// both lists ends up deleted and the removal propagates to every other device instead of
    /// the server's copy resurrecting it on the next sync.
    /// </summary>
    public IReadOnlyList<long>? DeletedTripStartTimes { get; init; }

    /// <summary>
    /// Fog-of-war lines, each a serialised array of <c>[lat, lon, tMs, speedKmh, leanDeg]</c>
    /// points. Deduplicated by content, because every sync re-sends the whole history.
    /// </summary>
    public IReadOnlyList<string>? Traces { get; init; }

    /// <summary>The rider's own shortcuts. Keyed by the client's identifier.</summary>
    public IReadOnlyList<SavedPlacePayload>? SavedPlaces { get; init; }

    /// <summary>Badge identifier to the instant it was earned. The earliest instant wins.</summary>
    public IReadOnlyDictionary<string, long>? Badges { get; init; }

    public RiderStatsPayload? Stats { get; init; }

    /// <summary>Absent leaves the setting alone; it is never read as "off".</summary>
    public bool? ShareFog { get; init; }
}

public record TripPayload
{
    [Required] public long StartTimeMs { get; init; }
    public long? EndTimeMs { get; init; }
    public double DistanceMeters { get; init; }
    public double TopSpeedKmh { get; init; }
    public double? MaxGForce { get; init; }
    public string? Mode { get; init; }

    /// <summary>
    /// Everything else the device recorded, kept verbatim. The backend never looks inside: it
    /// cannot disclose what it does not parse, and that is a property worth keeping.
    /// </summary>
    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extra { get; init; }
}

public record SavedPlacePayload
{
    [Required] public long Id { get; init; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extra { get; init; }
}

public record RiderStatsPayload(
    double TotalDistanceMeters,
    double TopSpeedKmh,
    double LongestTripMeters,
    double? MaxLeanDegrees,
    int MunicipalitiesVisited,
    double BestCoveragePercent,
    int TripCount);

/// <summary>
/// The merged union, which is what restores a device after a reinstall. Idempotent: syncing the
/// same input twice yields the same result.
/// </summary>
public record SyncResponse(
    [Required] IReadOnlyList<JsonElement> Trips,
    [Required] IReadOnlyList<string> Traces,
    [Required] IReadOnlyList<JsonElement> SavedPlaces,
    [Required] IReadOnlyDictionary<string, long> Badges,
    [Required] bool ShareFog);
