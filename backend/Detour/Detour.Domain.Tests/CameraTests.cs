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
    public void CreateSection_rejects_out_of_range_coordinates()
    {
        var polylineWithBadLat = new[] { (50.80, 4.30), (95.0, 4.35) }; // 95.0 is > 90
        var result = Camera.CreateSection(CameraKind.Section, polylineWithBadLat, 70, "N141", OsmSource);

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.Camera.CoordinatesInvalid));
    }

    [Fact]
    public void MergeSource_replaces_a_same_source_entry_rather_than_duplicating_it()
    {
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9", OsmSource).Value;
        var refreshedSource = OsmSource with { LastSeen = OsmSource.LastSeen.AddDays(7) };
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9", refreshedSource).Value;

        cam.MergeSource(incoming);

        Assert.Single(cam.Sources);
        Assert.Equal(refreshedSource.LastSeen, cam.Sources[0].LastSeen);
        Assert.Equal(refreshedSource.LastSeen, cam.LastSeen);
    }

    [Fact]
    public void MergeSource_lets_a_higher_ranked_source_overwrite_a_lower_ranked_attribute()
    {
        var lufopSource = new CameraSource("lufop", "l1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, null, null, lufopSource).Value;

        var osmSource = new CameraSource("osm", "n1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 70, "N9", osmSource).Value;

        cam.MergeSource(incoming);

        Assert.Equal(70, cam.MaxSpeedKmh);
        Assert.Equal("N9", cam.RoadRef);
        Assert.Equal(2, cam.Sources.Count);
    }

    [Fact]
    public void MergeSource_does_not_let_a_lower_ranked_source_overwrite_a_higher_ranked_attribute()
    {
        var osmSource = new CameraSource("osm", "n1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 70, "N9", osmSource).Value;

        var lufopSource = new CameraSource("lufop", "l1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 90, "D45", lufopSource).Value;

        cam.MergeSource(incoming);

        Assert.Equal(70, cam.MaxSpeedKmh);
        Assert.Equal("N9", cam.RoadRef);
        Assert.Equal(2, cam.Sources.Count);
    }

    [Fact]
    public void MergeSource_does_not_let_a_lower_ranked_source_bypass_precedence_via_its_own_self_refresh()
    {
        var osmSource = new CameraSource("osm", "n1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 70, "N9", osmSource).Value;

        var lufopSource = new CameraSource("lufop", "l1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var firstLufop = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 90, "D45", lufopSource).Value;
        cam.MergeSource(firstLufop);

        var updatedLufop = lufopSource with { LastSeen = lufopSource.LastSeen.AddDays(1) };
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 130, "D45-bis", updatedLufop).Value;

        cam.MergeSource(incoming);

        Assert.Equal(70, cam.MaxSpeedKmh);
        Assert.Equal("N9", cam.RoadRef);
        Assert.Equal(2, cam.Sources.Count);
    }

    [Fact]
    public void MergeSource_lets_a_source_refresh_its_own_attribute_when_it_is_the_only_source()
    {
        var osmSource = new CameraSource("osm", "n1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 70, "N9", osmSource).Value;

        var updatedOsm = new CameraSource("osm", "n1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddDays(1));
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 90, "N9-bis", updatedOsm).Value;

        cam.MergeSource(incoming);

        Assert.Equal(90, cam.MaxSpeedKmh);
        Assert.Equal("N9-bis", cam.RoadRef);
        Assert.Single(cam.Sources);
    }

    [Fact]
    public void MergeSource_does_not_let_a_null_incoming_attribute_clear_an_existing_value()
    {
        var lufopSource = new CameraSource("lufop", "l1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 90, null, lufopSource).Value;

        var osmSource = new CameraSource("osm", "n1", DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, null, null, osmSource).Value;

        cam.MergeSource(incoming);

        Assert.Equal(90, cam.MaxSpeedKmh);
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
    public void Cluster_does_not_match_an_unrelated_kind_even_when_coincident()
    {
        var existing = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9", OsmSource).Value;
        var incoming = Camera.CreatePoint(CameraKind.MobileHotspot, 50.85, 4.36, null, null,
            OsmSource with { SourceId = "n124" }).Value;

        var match = Camera.Cluster([existing], incoming, maxDistanceMeters: 40);

        Assert.Null(match);
    }

    [Fact]
    public void Cluster_matches_a_fixedspeed_and_redlight_pair_within_radius()
    {
        var existing = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85000, 4.36000, 50, "N9", OsmSource).Value;
        // ~9.8 m away — the real LUFOP Belgium pair that surfaced #372.
        var incoming = Camera.CreatePoint(CameraKind.RedLight, 50.85009, 4.36000, null, null,
            OsmSource with { SourceId = "n124" }).Value;

        var match = Camera.Cluster([existing], incoming, maxDistanceMeters: 40);

        Assert.Same(existing, match);
    }

    [Fact]
    public void MergeSource_promotes_a_fixedspeed_and_redlight_match_to_speedandredlight()
    {
        var cam = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9", OsmSource).Value;
        var redLight = Camera.CreatePoint(CameraKind.RedLight, 50.85, 4.36, null, null,
            OsmSource with { SourceId = "n124" }).Value;

        cam.MergeSource(redLight);

        Assert.Equal(CameraKind.SpeedAndRedLight, cam.Kind);
        Assert.Equal(50, cam.MaxSpeedKmh);
        Assert.Equal(2, cam.Sources.Count);
    }

    [Fact]
    public void MergeSource_leaves_an_already_speedandredlight_camera_at_that_kind()
    {
        var cam = Camera.CreatePoint(CameraKind.SpeedAndRedLight, 50.85, 4.36, 50, "N9", OsmSource).Value;
        var incoming = Camera.CreatePoint(CameraKind.FixedSpeed, 50.85, 4.36, 50, "N9",
            OsmSource with { SourceId = "n124" }).Value;

        cam.MergeSource(incoming);

        Assert.Equal(CameraKind.SpeedAndRedLight, cam.Kind);
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
