using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Cameras;

/// <summary>
/// What a camera enforces. Sections and average-speed zones carry a polyline instead of a
/// point; the rest are point cameras. Kept as one enum, not a type hierarchy, because every
/// caller (the bbox endpoint, the importer, the client) switches on it exhaustively rather
/// than dispatching polymorphically.
/// </summary>
public enum CameraKind
{
    FixedSpeed,
    RedLight,
    SpeedAndRedLight,
    Section,
    AverageSpeedZone,
    MobileHotspot,
}

public enum CameraStatus { Active, Retired }

/// <summary>One source's claim about this camera. A camera accumulates one per source that has
/// ever listed it; see <see cref="Camera.MergeSource"/> for how a source refreshes its own
/// entry instead of duplicating it.</summary>
public sealed record CameraSource(string Source, string SourceId, DateTimeOffset FirstSeen, DateTimeOffset LastSeen);

public sealed class Camera : Entity
{
    public CameraKind Kind { get; private set; }
    public double? Lat { get; private set; }
    public double? Lon { get; private set; }

    /// <summary>Section/zone geometry as `[[lat,lon],...]` JSON, null for point cameras.</summary>
    public string? PolylineJson { get; private set; }

    public double BboxMinLat { get; private set; }
    public double BboxMaxLat { get; private set; }
    public double BboxMinLon { get; private set; }
    public double BboxMaxLon { get; private set; }

    public int? MaxSpeedKmh { get; private set; }
    public string? RoadRef { get; private set; }

    /// <summary>The source list as opaque JSON — same "stored string, lazily-parsed view"
    /// convention as <c>SavedPlace.Payload</c>. Stored (not a computed/shadow property) so EF
    /// can map it directly; see <see cref="Sources"/> for the parsed view every caller uses.</summary>
    public string SourcesJson { get; private set; } = "[]";

    [System.Text.Json.Serialization.JsonIgnore]
    public IReadOnlyList<CameraSource> Sources =>
        System.Text.Json.JsonSerializer.Deserialize<List<CameraSource>>(SourcesJson) ?? [];

    public CameraStatus Status { get; private set; } = CameraStatus.Active;
    public DateTimeOffset FirstSeen { get; private set; }
    public DateTimeOffset LastSeen { get; private set; }
    public DateTimeOffset UpdatedAt { get; private set; }

    private Camera() { } // EF Core

    private Camera(
        CameraKind kind, double? lat, double? lon, string? polylineJson,
        double minLat, double maxLat, double minLon, double maxLon,
        int? maxSpeedKmh, string? roadRef, CameraSource source)
    {
        Kind = kind;
        Lat = lat;
        Lon = lon;
        PolylineJson = polylineJson;
        BboxMinLat = minLat;
        BboxMaxLat = maxLat;
        BboxMinLon = minLon;
        BboxMaxLon = maxLon;
        MaxSpeedKmh = maxSpeedKmh;
        RoadRef = roadRef;
        SourcesJson = System.Text.Json.JsonSerializer.Serialize(new List<CameraSource> { source });
        FirstSeen = source.FirstSeen;
        LastSeen = source.LastSeen;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<Camera> CreatePoint(
        CameraKind kind, double lat, double lon, int? maxSpeedKmh, string? roadRef, CameraSource source)
    {
        if (lat is < -90 or > 90 || lon is < -180 or > 180)
            return Result.Error(ValidationKeys.Camera.CoordinatesInvalid);

        return new Camera(kind, lat, lon, null, lat, lat, lon, lon, maxSpeedKmh, roadRef, source);
    }

    public static Result<Camera> CreateSection(
        CameraKind kind, IReadOnlyList<(double Lat, double Lon)> polyline,
        int? maxSpeedKmh, string? roadRef, CameraSource source)
    {
        if (polyline.Count < 2)
            return Result.Error(ValidationKeys.Camera.PolylineTooShort);

        foreach (var point in polyline)
        {
            if (point.Lat is < -90 or > 90 || point.Lon is < -180 or > 180)
                return Result.Error(ValidationKeys.Camera.CoordinatesInvalid);
        }

        var minLat = polyline.Min(p => p.Lat);
        var maxLat = polyline.Max(p => p.Lat);
        var minLon = polyline.Min(p => p.Lon);
        var maxLon = polyline.Max(p => p.Lon);
        var json = System.Text.Json.JsonSerializer.Serialize(polyline.Select(p => new[] { p.Lat, p.Lon }));

        return new Camera(kind, null, null, json, minLat, maxLat, minLon, maxLon, maxSpeedKmh, roadRef, source);
    }

    /// <summary>Refreshes this source's own entry (matched on <see cref="CameraSource.Source"/> +
    /// <see cref="CameraSource.SourceId"/>) rather than appending a duplicate, and advances
    /// <see cref="LastSeen"/> — the max over every source, so one still-reporting source keeps
    /// the camera <see cref="CameraStatus.Active"/> even while another has stopped seeing it.</summary>
    public void MergeSource(CameraSource incoming)
    {
        var sources = Sources.ToList();
        var i = sources.FindIndex(s => s.Source == incoming.Source && s.SourceId == incoming.SourceId);
        if (i >= 0) sources[i] = incoming; else sources.Add(incoming);
        SourcesJson = System.Text.Json.JsonSerializer.Serialize(sources);

        if (incoming.LastSeen > LastSeen) LastSeen = incoming.LastSeen;
        if (incoming.FirstSeen < FirstSeen) FirstSeen = incoming.FirstSeen;
        Status = CameraStatus.Active;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public void Retire()
    {
        Status = CameraStatus.Retired;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    /// <summary>The existing camera <paramref name="incoming"/> is the same physical camera as,
    /// or null if it matches none. Same kind and within <paramref name="maxDistanceMeters"/> of
    /// the anchor point — <see cref="Lat"/>/<see cref="Lon"/> for a point camera, the bbox centre
    /// for a section, which is adequate at the ~40 m clustering radius sections and zones use.
    /// Pure — no DB access — so the repository (Task 2) can run it over a bbox-scoped candidate
    /// list without this function knowing how that list was fetched.</summary>
    public static Camera? Cluster(IReadOnlyList<Camera> existing, Camera incoming, double maxDistanceMeters)
    {
        var (iLat, iLon) = Anchor(incoming);
        Camera? best = null;
        var bestDistance = maxDistanceMeters;
        foreach (var candidate in existing)
        {
            if (candidate.Kind != incoming.Kind) continue;
            var (cLat, cLon) = Anchor(candidate);
            var d = HaversineMeters(iLat, iLon, cLat, cLon);
            if (d <= bestDistance) { best = candidate; bestDistance = d; }
        }
        return best;
    }

    private static (double Lat, double Lon) Anchor(Camera c) =>
        c.Lat is not null ? (c.Lat.Value, c.Lon!.Value) : ((c.BboxMinLat + c.BboxMaxLat) / 2, (c.BboxMinLon + c.BboxMaxLon) / 2);

    private static double HaversineMeters(double lat1, double lon1, double lat2, double lon2)
    {
        const double r = 6_371_000.0;
        var dLat = DegToRad(lat2 - lat1);
        var dLon = DegToRad(lon2 - lon1);
        var a = Math.Sin(dLat / 2) * Math.Sin(dLat / 2) +
                Math.Cos(DegToRad(lat1)) * Math.Cos(DegToRad(lat2)) * Math.Sin(dLon / 2) * Math.Sin(dLon / 2);
        return 2 * r * Math.Asin(Math.Sqrt(a));
    }

    private static double DegToRad(double deg) => deg * Math.PI / 180;
}
