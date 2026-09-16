using Detour.Domain.Roads;

namespace Detour.Domain.Tests;

public class SpeedLimitWayTests
{
    private static readonly (double, double)[] Polyline = [(50.80, 4.30), (50.82, 4.35), (50.79, 4.40)];

    [Fact]
    public void Create_bbox_covers_every_polyline_point()
    {
        var result = SpeedLimitWay.Create("w1", Polyline, 70);

        Assert.False(result.IsFailure);
        var way = result.Value;
        Assert.Equal(50.79, way.BboxMinLat);
        Assert.Equal(50.82, way.BboxMaxLat);
        Assert.Equal(4.30, way.BboxMinLon);
        Assert.Equal(4.40, way.BboxMaxLon);
        Assert.Equal(70, way.MaxSpeedKmh);
        Assert.Equal("w1", way.SourceId);
    }

    [Fact]
    public void Create_rejects_a_single_point_polyline()
    {
        var result = SpeedLimitWay.Create("w1", [(50.80, 4.30)], 70);

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.SpeedLimitWay.PolylineTooShort));
    }

    [Fact]
    public void Create_rejects_out_of_range_coordinates()
    {
        var withBadLat = new (double, double)[] { (50.80, 4.30), (95.0, 4.35) }; // 95.0 is > 90
        var result = SpeedLimitWay.Create("w1", withBadLat, 70);

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.SpeedLimitWay.CoordinatesInvalid));
    }

    [Fact]
    public void ReplaceWith_overwrites_geometry_and_limit_unconditionally()
    {
        var way = SpeedLimitWay.Create("w1", Polyline, 70).Value;

        var wider = new (double, double)[] { (51.00, 5.00), (51.10, 5.10) };
        var incoming = SpeedLimitWay.Create("w1", wider, 90).Value;

        way.ReplaceWith(incoming);

        Assert.Equal(90, way.MaxSpeedKmh);
        Assert.Equal(51.00, way.BboxMinLat);
        Assert.Equal(51.10, way.BboxMaxLat);
    }
}
