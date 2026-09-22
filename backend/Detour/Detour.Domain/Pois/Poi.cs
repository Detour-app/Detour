using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Pois;

/// <summary>
/// A point of interest — viewpoint, food & drink, or sight — for spin's random-POI feature
/// (issue #383, phase 5 of #302). Sibling of <see cref="Detour.Domain.Roads.RoadWay"/>/
/// <see cref="Detour.Domain.Boundaries.MunicipalityBoundary"/>: one row per OSM element, no
/// cross-source merge machinery — a re-import replaces a row in place by <see cref="SourceId"/>
/// rather than reconciling competing claims from several sources.
/// </summary>
public sealed class Poi : Entity
{
    /// <summary>The OSM element id this row was built from — `"n123456"` for a node or
    /// `"w123456"` for a way, matching `tools/poi-importer/osm_import.py`'s own spelling.
    /// Stable across re-imports, which is what lets <see cref="IPoiRepository.UpsertAsync"/>
    /// replace a row in place instead of growing a duplicate every run.</summary>
    public string SourceId { get; private set; } = "";

    /// <summary>One of `"viewpoint"`, `"food"`, `"sight"` — matches
    /// `PoiKind.name.lowercase()` in `shared/`'s `PoiRoulette.kt`, so the client's own enum
    /// name is the wire value rather than a second mapping either side has to keep in sync.</summary>
    public string Kind { get; private set; } = "";

    /// <summary>The OSM `name` tag, or blank when the element has none — most sights and
    /// viewpoints do, plenty of small cafes don't. `PoiRoulette.randomPoi` (`shared/`) falls
    /// back to the kind's own label when this is blank, the same rule it already applies to an
    /// Overpass element with no `name` tag.</summary>
    public string Name { get; private set; } = "";

    public double Lat { get; private set; }
    public double Lon { get; private set; }

    public double BboxMinLat { get; private set; }
    public double BboxMaxLat { get; private set; }
    public double BboxMinLon { get; private set; }
    public double BboxMaxLon { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    private Poi() { } // EF Core

    private Poi(string sourceId, string kind, string name, double lat, double lon)
    {
        SourceId = sourceId;
        Kind = kind;
        Name = name;
        Lat = lat;
        Lon = lon;
        BboxMinLat = lat;
        BboxMaxLat = lat;
        BboxMinLon = lon;
        BboxMaxLon = lon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<Poi> Create(string sourceId, string kind, string name, double lat, double lon)
    {
        if (lat is < -90 or > 90 || lon is < -180 or > 180)
            return Result.Error(ValidationKeys.Poi.CoordinatesInvalid);

        if (string.IsNullOrWhiteSpace(kind))
            return Result.Error(ValidationKeys.Poi.KindBlank);

        return new Poi(sourceId, kind, name, lat, lon);
    }

    /// <summary>Replaces this row's name/position with a fresh import of the same
    /// <see cref="SourceId"/> — a single source needs no precedence, unlike
    /// <see cref="Detour.Domain.Cameras.Camera.MergeSource"/>.</summary>
    public void ReplaceWith(Poi incoming)
    {
        Kind = incoming.Kind;
        Name = incoming.Name;
        Lat = incoming.Lat;
        Lon = incoming.Lon;
        BboxMinLat = incoming.BboxMinLat;
        BboxMaxLat = incoming.BboxMaxLat;
        BboxMinLon = incoming.BboxMinLon;
        BboxMaxLon = incoming.BboxMaxLon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }
}
