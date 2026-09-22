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
/// entry instead of duplicating it. <see cref="MissedRuns"/> counts consecutive import runs of
/// this source that did not see this entry's <see cref="SourceId"/> — reset to 0 whenever
/// <see cref="Camera.MergeSource"/> next refreshes it, driving <see cref="Camera.MarkSourceMissing"/>
/// (issue #369). Absent in JSON written before that field existed, in which case it deserializes
/// to its default (0) — System.Text.Json falls back to a record constructor parameter's declared
/// default when the JSON property is missing.</summary>
public sealed record CameraSource(string Source, string SourceId, DateTimeOffset FirstSeen, DateTimeOffset LastSeen, int MissedRuns = 0);

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

    /// <summary>Which source last set each attribute — `{"maxSpeedKmh":"osm",...}` — so
    /// <see cref="MergeSource"/> can gate a field's overwrite on that field's own last-setter
    /// rank instead of the row's highest-ranked attached source. See issue #373: a source merely
    /// *present* on a row must not block a lower-ranked source from correcting a field that
    /// source never actually had an opinion on.</summary>
    public string AttributionJson { get; private set; } = "{}";

    private IReadOnlyDictionary<string, string> Attribution =>
        System.Text.Json.JsonSerializer.Deserialize<Dictionary<string, string>>(AttributionJson) ?? [];

    private const string FieldMaxSpeed = "maxSpeedKmh";
    private const string FieldRoadRef = "roadRef";
    private const string FieldPosition = "position";

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

        var attribution = new Dictionary<string, string>();
        if (maxSpeedKmh is not null) attribution[FieldMaxSpeed] = source.Source;
        if (roadRef is not null) attribution[FieldRoadRef] = source.Source;
        attribution[FieldPosition] = source.Source; // every camera has a position from creation
        AttributionJson = System.Text.Json.JsonSerializer.Serialize(attribution);

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

    /// <summary>Ranks a source's claim over another's for attribute precedence — higher
    /// wins. Not present means rank 0, the lowest: an unrecognised source can still
    /// contribute a new camera or provenance, it just never overwrites anyone else's
    /// attributes. `lufop` sits below `osm` per issue #303's stated precedence
    /// ("official portal > OSM > LUFOP"); a future official-portal source goes above
    /// both, here.</summary>
    private static readonly IReadOnlyDictionary<string, int> SourceRank = new Dictionary<string, int>(StringComparer.OrdinalIgnoreCase)
    {
        ["lufop"] = 1,
        ["osm"] = 2,
    };

    private static int RankOf(string source) => SourceRank.GetValueOrDefault(source, 0);

    /// <summary>Refreshes this source's own entry (matched on <see cref="CameraSource.Source"/> +
    /// <see cref="CameraSource.SourceId"/>) rather than appending a duplicate, and advances
    /// <see cref="LastSeen"/> — the max over every source, so one still-reporting source keeps
    /// the camera <see cref="CameraStatus.Active"/> even while another has stopped reporting.
    /// This <see cref="Sources"/>-list bookkeeping (and <see cref="FirstSeen"/>/<see cref="Status"/>)
    /// always happens for the incoming source, regardless of rank.
    /// <para>Attribute values (<see cref="MaxSpeedKmh"/>, <see cref="RoadRef"/>, position) adopt
    /// <paramref name="incoming"/>'s value only when its source's <see cref="RankOf"/> is at
    /// least as high as *that field's own* last-setter, per <see cref="Attribution"/> — not the
    /// row's highest-ranked attached source. A source merely present on the row, that never
    /// actually supplied a given field, does not block a lower-ranked source from correcting
    /// that field (issue #373). This still applies to a source refreshing its own entry: it
    /// cannot bypass a *different*, higher-ranked field-setter via self-refresh.</para></summary>
    public void MergeSource(Camera incoming)
    {
        var incomingSource = incoming.Sources[0];
        var sources = Sources.ToList();

        var i = sources.FindIndex(s => s.Source == incomingSource.Source && s.SourceId == incomingSource.SourceId);
        if (i >= 0) sources[i] = incomingSource; else sources.Add(incomingSource);
        SourcesJson = System.Text.Json.JsonSerializer.Serialize(sources);

        if (incomingSource.LastSeen > LastSeen) LastSeen = incomingSource.LastSeen;
        if (incomingSource.FirstSeen < FirstSeen) FirstSeen = incomingSource.FirstSeen;
        Status = CameraStatus.Active;

        // A cross-kind match (see Cluster/MergeCompatibleKinds) means this is the same physical
        // camera enforcing both rules — not a per-source attribute, so it promotes unconditionally,
        // regardless of source rank.
        if (incoming.Kind != Kind) Kind = CameraKind.SpeedAndRedLight;

        var incomingRank = RankOf(incomingSource.Source);
        var attribution = Attribution.ToDictionary();
        int FieldRank(string field) => attribution.TryGetValue(field, out var setter) ? RankOf(setter) : 0;

        if (incoming.MaxSpeedKmh is not null && incomingRank >= FieldRank(FieldMaxSpeed))
        {
            MaxSpeedKmh = incoming.MaxSpeedKmh;
            attribution[FieldMaxSpeed] = incomingSource.Source;
        }
        if (incoming.RoadRef is not null && incomingRank >= FieldRank(FieldRoadRef))
        {
            RoadRef = incoming.RoadRef;
            attribution[FieldRoadRef] = incomingSource.Source;
        }
        if ((incoming.Lat is not null || incoming.PolylineJson is not null) && incomingRank >= FieldRank(FieldPosition))
        {
            if (incoming.Lat is not null && incoming.Lon is not null)
            {
                Lat = incoming.Lat;
                Lon = incoming.Lon;
                BboxMinLat = incoming.BboxMinLat;
                BboxMaxLat = incoming.BboxMaxLat;
                BboxMinLon = incoming.BboxMinLon;
                BboxMaxLon = incoming.BboxMaxLon;
            }
            else
            {
                PolylineJson = incoming.PolylineJson;
                BboxMinLat = incoming.BboxMinLat;
                BboxMaxLat = incoming.BboxMaxLat;
                BboxMinLon = incoming.BboxMinLon;
                BboxMaxLon = incoming.BboxMaxLon;
            }
            attribution[FieldPosition] = incomingSource.Source;
        }
        AttributionJson = System.Text.Json.JsonSerializer.Serialize(attribution);

        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public void Retire()
    {
        Status = CameraStatus.Retired;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    /// <summary>Called once per completed import run of <paramref name="source"/>, for every
    /// active camera that source has ever reported but did not see this run (the repository
    /// decides which cameras qualify — see <see cref="ICameraRepository"/>). Increments that
    /// source's own <see cref="CameraSource.MissedRuns"/> streak — reset to 0 the next time
    /// <see cref="MergeSource"/> refreshes it — and retires the camera only once *every* source
    /// that has ever reported it has independently missed <paramref name="retireAfterMisses"/>
    /// consecutive runs of its own: a source still reporting keeps the camera alive even while
    /// another has gone quiet. Design decision for issue #369 — the threshold is a caller-supplied
    /// count of runs, not wall-clock time, since sources import on different cadences. A no-op if
    /// this camera was never reported by <paramref name="source"/> at all.</summary>
    public void MarkSourceMissing(string source, int retireAfterMisses)
    {
        var sources = Sources.ToList();
        var i = sources.FindIndex(s => s.Source == source);
        if (i < 0) return;

        sources[i] = sources[i] with { MissedRuns = sources[i].MissedRuns + 1 };
        SourcesJson = System.Text.Json.JsonSerializer.Serialize(sources);
        UpdatedAt = DateTimeOffset.UtcNow;

        if (sources.All(s => s.MissedRuns >= retireAfterMisses)) Retire();
    }

    /// <summary>Point kinds that can be the same physical device wearing two hats — a device
    /// tagged as a standalone speed camera by one source and a standalone red-light camera by
    /// another, or already merged, all describe one gantry (see issue #372). Matching across
    /// this set — rather than requiring exact kind equality — is what lets <see cref="Cluster"/>
    /// find that they're the same camera so <see cref="MergeSource"/> can promote it to
    /// <see cref="CameraKind.SpeedAndRedLight"/>. Every other kind only matches its own: a
    /// <see cref="CameraKind.Section"/> and an <see cref="CameraKind.AverageSpeedZone"/> 40 m
    /// apart are not the same thing just because they're close.</summary>
    private static readonly IReadOnlySet<CameraKind> RedLightFamily =
        new HashSet<CameraKind> { CameraKind.FixedSpeed, CameraKind.RedLight, CameraKind.SpeedAndRedLight };

    /// <summary>The kinds <paramref name="kind"/> may cluster with — see <see cref="RedLightFamily"/>.
    /// Exposed so the repository's DB-side candidate query can pre-filter on the same rule
    /// <see cref="Cluster"/> applies in memory, rather than only fetching exact-kind matches.</summary>
    public static IReadOnlyCollection<CameraKind> MergeCompatibleKinds(CameraKind kind) =>
        RedLightFamily.Contains(kind) ? RedLightFamily : [kind];

    /// <summary>The existing camera <paramref name="incoming"/> is the same physical camera as,
    /// or null if it matches none. A compatible kind (<see cref="MergeCompatibleKinds"/>) within
    /// <paramref name="maxDistanceMeters"/> of the anchor point — <see cref="Lat"/>/<see cref="Lon"/>
    /// for a point camera, the bbox centre for a section, which is adequate at the ~40 m
    /// clustering radius sections and zones use. A genuinely separate speed camera and red-light
    /// camera that happen to sit within the radius on different approaches will still merge —
    /// there's no direction data to tell them apart; accepted as a rare false-positive per #372.
    /// Pure — no DB access — so the repository (Task 2) can run it over a bbox-scoped candidate
    /// list without this function knowing how that list was fetched.</summary>
    public static Camera? Cluster(IReadOnlyList<Camera> existing, Camera incoming, double maxDistanceMeters)
    {
        var (iLat, iLon) = Anchor(incoming);
        Camera? best = null;
        var bestDistance = maxDistanceMeters;
        foreach (var candidate in existing)
        {
            if (!MergeCompatibleKinds(incoming.Kind).Contains(candidate.Kind)) continue;
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
