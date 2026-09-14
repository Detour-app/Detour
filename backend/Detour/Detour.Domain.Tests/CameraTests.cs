using Detour.Domain.Cameras;

namespace Detour.Domain.Tests;

public class CameraTests
{
    private static readonly CameraSource OsmSource = new("osm", "n123", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);

    [Fact]
    public void CreatePoint_sets_bbox_to_the_single_point()
    {
        var result = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9", OsmSource);

        Assert.False(result.IsFailure);
        var cam = result.Value;
        Assert.Equal(50.85, cam.BboxMinLat);
        Assert.Equal(50.85, cam.BboxMaxLat);
        Assert.Equal(4.36, cam.BboxMinLon);
        Assert.Equal(4.36, cam.BboxMaxLon);
    }

    [Fact]
    public void CreateSection_bbox_covers_every_polyline_point()
    {
        var polyline = new[] { (50.80, 4.30), (50.82, 4.35), (50.79, 4.40) };
        var result = Camera.CreateSection(CameraKind.Section, polyline, 70, "N141", OsmSource);

        Assert.False(result.IsFailure);
        var cam = result.Value;
        Assert.Equal(50.79, cam.BboxMinLat);
        Assert.Equal(50.82, cam.BboxMaxLat);
        Assert.Equal(4.30, cam.BboxMinLon);
        Assert.Equal(4.40, cam.BboxMaxLon);
    }

    [Fact]
    public void MergeSource_replaces_a_same_source_entry_rather_than_duplicating_it()
    {
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9", OsmSource).Value;
        var refreshed = OsmSource with { LastSeen = OsmSource.LastSeen.AddDays(7) };

        cam.MergeSource(refreshed);

        Assert.Single(cam.Sources);
        Assert.Equal(refreshed.LastSeen, cam.Sources[0].LastSeen);
        Assert.Equal(refreshed.LastSeen, cam.LastSeen);
    }

    [Fact]
    public void Cluster_matches_same_kind_within_40_metres()
    {
        var existing = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85000, 4.36000, 50, "N9", OsmSource).Value;
        // ~35 m north of `existing`.
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85031, 4.36000, 50, "N9",
            OsmSource with { SourceId = "n124" }).Value;

        var match = Camera.Cluster([existing], incoming, maxDistanceMeters: 40);

        Assert.Same(existing, match);
    }

    [Fact]
    public void Cluster_does_not_match_a_different_kind_even_when_coincident()
    {
        var existing = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9", OsmSource).Value;
        var incoming = Camera.CreatePoint(CameraKind.RedLight, 50.85, 4.36, null, null,
            OsmSource with { SourceId = "n124" }).Value;

        var match = Camera.Cluster([existing], incoming, maxDistanceMeters: 40);

        Assert.Null(match);
    }

    [Fact]
    public void Cluster_returns_null_beyond_the_distance_threshold()
    {
        var existing = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85000, 4.36000, 50, "N9", OsmSource).Value;
        // ~111 m north.
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85100, 4.36000, 50, "N9",
            OsmSource with { SourceId = "n124" }).Value;

        var match = Camera.Cluster([existing], incoming, maxDistanceMeters: 40);

        Assert.Null(match);
    }
}
