using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Boundaries;

/// <summary>
/// An OSM `admin_level=8` boundary — gemeente, commune, Gemeinde, municipality — for the
/// fog-of-war coverage feature (issue #381, phase 4 of #302). Sibling of
/// <see cref="Detour.Domain.Roads.SpeedLimitWay"/>: one row per OSM relation, no cross-source
/// merge machinery — a re-import replaces a row in place by <see cref="SourceId"/> rather than
/// reconciling competing claims from several sources.
/// </summary>
public sealed class MunicipalityBoundary : Entity
{
    /// <summary>The OSM relation id this row was built from — `"r123456"`, matching
    /// `tools/municipality-importer/osm_import.py`'s own spelling. Stable across re-imports,
    /// which is what lets <see cref="IMunicipalityBoundaryRepository.UpsertAsync"/> replace a
    /// row in place instead of growing a duplicate every run.</summary>
    public string SourceId { get; private set; } = "";

    public string Name { get; private set; } = "";

    /// <summary>Outer and inner rings alike, as `[[[lat,lon],...],...]` JSON — one array per
    /// ring, closing segment implied rather than repeated. Mirrors `Municipality.rings`
    /// (`shared/`) exactly, so <see cref="Contains"/> and its client counterpart
    /// (`Municipality.contains`) run the identical even-odd ray cast over the identical
    /// shape.</summary>
    public string RingsJson { get; private set; } = "[]";

    public double BboxMinLat { get; private set; }
    public double BboxMaxLat { get; private set; }
    public double BboxMinLon { get; private set; }
    public double BboxMaxLon { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    /// <summary>The bare OSM relation id, stripped of <see cref="SourceId"/>'s `"r"` prefix —
    /// what `MunicipalityBoundaryDto` exposes as its id. Matches the numeric id
    /// `MunicipalityStore.fetch`'s Overpass client (`shared/`) already persists as
    /// `Municipality.id`, so a boundary already cached from an earlier Overpass lookup and this
    /// endpoint's answer for the same relation compare equal instead of duplicating.</summary>
    public long OsmId => long.Parse(SourceId.AsSpan(1));

    private MunicipalityBoundary() { } // EF Core

    private MunicipalityBoundary(
        string sourceId, string name, string ringsJson,
        double minLat, double maxLat, double minLon, double maxLon)
    {
        SourceId = sourceId;
        Name = name;
        RingsJson = ringsJson;
        BboxMinLat = minLat;
        BboxMaxLat = maxLat;
        BboxMinLon = minLon;
        BboxMaxLon = maxLon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<MunicipalityBoundary> Create(
        string sourceId, string name, IReadOnlyList<IReadOnlyList<(double Lat, double Lon)>> rings)
    {
        if (string.IsNullOrWhiteSpace(name))
            return Result.Error(ValidationKeys.MunicipalityBoundary.NameBlank);

        if (rings.Count == 0 || rings.Any(r => r.Count < 3))
            return Result.Error(ValidationKeys.MunicipalityBoundary.RingsInvalid);

        foreach (var ring in rings)
        {
            foreach (var (lat, lon) in ring)
            {
                if (lat is < -90 or > 90 || lon is < -180 or > 180)
                    return Result.Error(ValidationKeys.MunicipalityBoundary.CoordinatesInvalid);
            }
        }

        var points = rings.SelectMany(r => r).ToList();
        var minLat = points.Min(p => p.Lat);
        var maxLat = points.Max(p => p.Lat);
        var minLon = points.Min(p => p.Lon);
        var maxLon = points.Max(p => p.Lon);
        var json = System.Text.Json.JsonSerializer.Serialize(
            rings.Select(r => r.Select(p => new[] { p.Lat, p.Lon })));

        return new MunicipalityBoundary(sourceId, name, json, minLat, maxLat, minLon, maxLon);
    }

    /// <summary>Replaces this row's name/geometry with a fresh import of the same
    /// <see cref="SourceId"/> — a single source needs no precedence, unlike
    /// <see cref="Detour.Domain.Cameras.Camera.MergeSource"/>.</summary>
    public void ReplaceWith(MunicipalityBoundary incoming)
    {
        Name = incoming.Name;
        RingsJson = incoming.RingsJson;
        BboxMinLat = incoming.BboxMinLat;
        BboxMaxLat = incoming.BboxMaxLat;
        BboxMinLon = incoming.BboxMinLon;
        BboxMaxLon = incoming.BboxMaxLon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    /// <summary>Even-odd ray cast, counting crossings of a ray going east from (<paramref
    /// name="lat"/>, <paramref name="lon"/>) — the identical algorithm to
    /// `Municipality.contains` (`shared/`), so the backend and the client agree on which
    /// boundary a point falls in. The bbox check first is an exact reject, not an
    /// optimisation shortcut: <see cref="IMunicipalityBoundaryRepository.CandidatesAsync"/>
    /// already pre-filtered by bbox, but <see cref="MunicipalityController"/> also calls this
    /// directly per candidate, where it is the only check.</summary>
    public bool Contains(double lat, double lon)
    {
        if (lat < BboxMinLat || lat > BboxMaxLat || lon < BboxMinLon || lon > BboxMaxLon)
            return false;

        var rings = System.Text.Json.JsonSerializer.Deserialize<List<List<double[]>>>(RingsJson)!;
        var inside = false;
        foreach (var ring in rings)
        {
            for (var i = 0; i < ring.Count; i++)
            {
                var a = ring[i];
                var b = ring[(i + 1) % ring.Count]; // implicit closing segment
                if ((a[0] > lat) != (b[0] > lat))
                {
                    var x = a[1] + (lat - a[0]) / (b[0] - a[0]) * (b[1] - a[1]);
                    if (x > lon) inside = !inside;
                }
            }
        }
        return inside;
    }
}
