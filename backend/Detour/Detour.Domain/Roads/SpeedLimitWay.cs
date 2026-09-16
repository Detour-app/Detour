using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Roads;

/// <summary>
/// A drivable OSM way with a posted speed limit, for the ambient speed-limit sign (issue #379,
/// phase 3 of #302) — <see cref="Detour.Domain.Cameras.Camera"/>'s sibling table, minus the
/// per-source merge machinery that one carries: a way has exactly one OSM source, identified by
/// its own stable id, so a re-import replaces a row in place rather than reconciling competing
/// claims from several sources.
/// </summary>
public sealed class SpeedLimitWay : Entity
{
    /// <summary>The OSM way id this row was built from — `"w123456"`, matching
    /// `tools/speedlimit-importer/osm_import.py`'s own spelling. Stable across re-imports,
    /// which is what lets <see cref="ISpeedLimitWayRepository.UpsertAsync"/> replace a row in
    /// place instead of growing a duplicate every run.</summary>
    public string SourceId { get; private set; } = "";

    public int MaxSpeedKmh { get; private set; }

    /// <summary>The way geometry as `[[lat,lon],...]` JSON, at least two points.</summary>
    public string PolylineJson { get; private set; } = "[]";

    public double BboxMinLat { get; private set; }
    public double BboxMaxLat { get; private set; }
    public double BboxMinLon { get; private set; }
    public double BboxMaxLon { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    private SpeedLimitWay() { } // EF Core

    private SpeedLimitWay(
        string sourceId, int maxSpeedKmh, string polylineJson,
        double minLat, double maxLat, double minLon, double maxLon)
    {
        SourceId = sourceId;
        MaxSpeedKmh = maxSpeedKmh;
        PolylineJson = polylineJson;
        BboxMinLat = minLat;
        BboxMaxLat = maxLat;
        BboxMinLon = minLon;
        BboxMaxLon = maxLon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<SpeedLimitWay> Create(
        string sourceId, IReadOnlyList<(double Lat, double Lon)> polyline, int maxSpeedKmh)
    {
        if (polyline.Count < 2)
            return Result.Error(ValidationKeys.SpeedLimitWay.PolylineTooShort);

        foreach (var (lat, lon) in polyline)
        {
            if (lat is < -90 or > 90 || lon is < -180 or > 180)
                return Result.Error(ValidationKeys.SpeedLimitWay.CoordinatesInvalid);
        }

        var minLat = polyline.Min(p => p.Lat);
        var maxLat = polyline.Max(p => p.Lat);
        var minLon = polyline.Min(p => p.Lon);
        var maxLon = polyline.Max(p => p.Lon);
        var json = System.Text.Json.JsonSerializer.Serialize(polyline.Select(p => new[] { p.Lat, p.Lon }));

        return new SpeedLimitWay(sourceId, maxSpeedKmh, json, minLat, maxLat, minLon, maxLon);
    }

    /// <summary>Replaces this row's geometry/limit with a fresh import of the same
    /// <see cref="SourceId"/> — a single source needs no precedence, unlike
    /// <see cref="Detour.Domain.Cameras.Camera.MergeSource"/>.</summary>
    public void ReplaceWith(SpeedLimitWay incoming)
    {
        MaxSpeedKmh = incoming.MaxSpeedKmh;
        PolylineJson = incoming.PolylineJson;
        BboxMinLat = incoming.BboxMinLat;
        BboxMaxLat = incoming.BboxMaxLat;
        BboxMinLon = incoming.BboxMinLon;
        BboxMaxLon = incoming.BboxMaxLon;
        UpdatedAt = DateTimeOffset.UtcNow;
    }
}
