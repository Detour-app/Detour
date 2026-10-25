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
    /// Start instants the device has deleted. Each is kept as a tombstone (#778): a trip in both
    /// lists, or uploaded later by a device that still holds it, is not stored again, and the
    /// tombstone goes back to every device in <see cref="SyncResponse.DeletedTripStartTimes"/>.
    /// </summary>
    public IReadOnlyList<long>? DeletedTripStartTimes { get; init; }

    /// <summary>
    /// Fog-of-war lines, each a serialised array of <c>[lat, lon, tMs, speedKmh, leanDeg]</c>
    /// points. Deduplicated by content, because every sync re-sends the whole history.
    /// </summary>
    public IReadOnlyList<string>? Traces { get; init; }

    /// <summary>The rider's own shortcuts. Keyed by the client's identifier.</summary>
    public IReadOnlyList<SavedPlacePayload>? SavedPlaces { get; init; }

    /// <summary>
    /// Shortcut identifiers the device has deleted (#695). Applied after the upserts, like
    /// <see cref="DeletedTripStartTimes"/>, so the merged union stops handing the place back.
    /// </summary>
    public IReadOnlyList<long>? DeletedSavedPlaceIds { get; init; }

    /// <summary>
    /// The rider's own saved routes (#592). Keyed by the client's identifier; the document —
    /// stops, routed polyline — is stored as sent.
    /// </summary>
    public IReadOnlyList<SavedRoutePayload>? SavedRoutes { get; init; }

    /// <summary>
    /// Route identifiers the device has deleted. Applied after the upserts, like
    /// <see cref="DeletedSavedPlaceIds"/>, so the merged union stops handing the route back.
    /// </summary>
    public IReadOnlyList<long>? DeletedSavedRouteIds { get; init; }

    /// <summary>Badge identifier to the instant it was earned. The earliest instant wins.</summary>
    public IReadOnlyDictionary<string, long>? Badges { get; init; }

    /// <summary>
    /// Ride titles the rider typed, keyed by the ride's start instant. The newest edit wins; an
    /// empty title is a cleared rename and is kept, so an older title cannot come back (#471).
    /// </summary>
    public IReadOnlyList<TripTitlePayload>? TripTitles { get; init; }

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
    /// When the rider last edited the trip, Unix milliseconds; 0 for never. Absent from a client
    /// that predates it (#486), whose upload then overwrites as before.
    /// </summary>
    public long? EditedAtMs { get; init; }

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

public record SavedRoutePayload
{
    [Required] public long Id { get; init; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extra { get; init; }
}

public record TripTitlePayload(
    [Required] long StartTimeMs,
    [Required(AllowEmptyStrings = true)] string Title,
    [Required] long EditedAtMs);

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
/// <param name="DeletedTripStartTimes">
/// Every ride this rider has deleted on any device (#778). A device drops its copy of each and
/// stops uploading it; an older client ignores the field, and the server ignores its upload.
/// </param>
public record SyncResponse(
    [Required] IReadOnlyList<JsonElement> Trips,
    [Required] IReadOnlyList<long> DeletedTripStartTimes,
    [Required] IReadOnlyList<string> Traces,
    [Required] IReadOnlyList<JsonElement> SavedPlaces,
    [Required] IReadOnlyList<JsonElement> SavedRoutes,
    [Required] IReadOnlyDictionary<string, long> Badges,
    [Required] IReadOnlyList<TripTitlePayload> TripTitles,
    [Required] bool ShareFog);
