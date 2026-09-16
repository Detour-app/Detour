using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Roads;

/// <summary>
/// A drivable OSM way, for the road-type mix (issue #380, phase 3 of #302) and spin's
/// random-road feature (issue #382, phase 5 of #302) — <see cref="SpeedLimitWay"/>'s sibling,
/// minus the `maxspeed` requirement: this table carries every drivable way regardless of
/// whether it has a posted limit, since the road-type mix must not undercount an untagged
/// residential street. Same single-source, no-merge shape as <see cref="SpeedLimitWay"/>: a
/// way has exactly one OSM source, so a re-import replaces a row in place.
/// </summary>
public sealed class RoadWay : Entity
{
    /// <summary>The OSM way id this row was built from — `"w123456"`, matching
    /// `tools/roads-importer/osm_import.py`'s own spelling. Stable across re-imports, which is
    /// what lets <see cref="IRoadWayRepository.UpsertAsync"/> replace a row in place instead of
    /// growing a duplicate every run.</summary>
    public string SourceId { get; private set; } = "";

    /// <summary>The raw OSM `highway` tag value (`"primary"`, `"motorway_link"`, …), not the
    /// coarser three-bucket classification the client's own `HighwayClass` derives from it —
    /// that mapping, and any regex-based mode filtering, is a client-side judgement call this
    /// table stays out of.</summary>
    public string Highway { get; private set; } = "";

    /// <summary>The way geometry as `[[lat,lon],...]` JSON, at least two points.</summary>
    public string PolylineJson { get; private set; } = "[]";

    public double BboxMinLat { get; private set; }
    public double BboxMaxLat { get; private set; }
    public double BboxMinLon { get; private set; }
    public double BboxMaxLon { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    private RoadWay() { } // EF Core

    private RoadWay(
        string sourceId, string highway, string polylineJson,
        double minLat, double maxLat, double minLon, double maxLon)
    {
        SourceId = sourceId;
        Highway = highway;
        PolylineJson = polylineJson;
        BboxMinLat = minLat;
        BboxMaxLat = maxLat;
        BboxMinLon = minLon;
        BboxMaxLon = maxLon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<RoadWay> Create(
        string sourceId, IReadOnlyList<(double Lat, double Lon)> polyline, string highway)
    {
        if (polyline.Count < 2)
            return Result.Error(ValidationKeys.RoadWay.PolylineTooShort);

        foreach (var (lat, lon) in polyline)
        {
            if (lat is < -90 or > 90 || lon is < -180 or > 180)
                return Result.Error(ValidationKeys.RoadWay.CoordinatesInvalid);
        }

        var minLat = polyline.Min(p => p.Lat);
        var maxLat = polyline.Max(p => p.Lat);
        var minLon = polyline.Min(p => p.Lon);
        var maxLon = polyline.Max(p => p.Lon);
        var json = System.Text.Json.JsonSerializer.Serialize(polyline.Select(p => new[] { p.Lat, p.Lon }));

        return new RoadWay(sourceId, highway, json, minLat, maxLat, minLon, maxLon);
    }

    /// <summary>Replaces this row's geometry/class with a fresh import of the same
    /// <see cref="SourceId"/> — a single source needs no precedence, unlike
    /// <see cref="Detour.Domain.Cameras.Camera.MergeSource"/>.</summary>
    public void ReplaceWith(RoadWay incoming)
    {
        Highway = incoming.Highway;
        PolylineJson = incoming.PolylineJson;
        BboxMinLat = incoming.BboxMinLat;
        BboxMaxLat = incoming.BboxMaxLat;
        BboxMinLon = incoming.BboxMinLon;
        BboxMaxLon = incoming.BboxMaxLon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }
}
