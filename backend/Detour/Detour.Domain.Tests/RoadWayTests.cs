using Detour.Domain.Roads;

namespace Detour.Domain.Tests;

public class RoadWayTests
{
    private static readonly (double, double)[] Polyline = [(50.80, 4.30), (50.82, 4.35), (50.79, 4.40)];

    [Fact]
    public void Create_bbox_covers_every_polyline_point()
    {
        var result = RoadWay.Create("w1", Polyline, "primary");

        Assert.False(result.IsFailure);
        var way = result.Value;
        Assert.Equal(50.79, way.BboxMinLat);
        Assert.Equal(50.82, way.BboxMaxLat);
        Assert.Equal(4.30, way.BboxMinLon);
        Assert.Equal(4.40, way.BboxMaxLon);
        Assert.Equal("primary", way.Highway);
        Assert.Equal("w1", way.SourceId);
    }

    [Fact]
    public void Create_rejects_a_single_point_polyline()
    {
        var result = RoadWay.Create("w1", [(50.80, 4.30)], "primary");

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.RoadWay.PolylineTooShort));
    }

    [Fact]
    public void Create_rejects_out_of_range_coordinates()
    {
        var withBadLat = new (double, double)[] { (50.80, 4.30), (95.0, 4.35) }; // 95.0 is > 90
        var result = RoadWay.Create("w1", withBadLat, "primary");

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.RoadWay.CoordinatesInvalid));
    }

    [Fact]
    public void ReplaceWith_overwrites_geometry_and_highway_class_unconditionally()
    {
        var way = RoadWay.Create("w1", Polyline, "primary").Value;

        var wider = new (double, double)[] { (51.00, 5.00), (51.10, 5.10) };
        var incoming = RoadWay.Create("w1", wider, "residential").Value;

        way.ReplaceWith(incoming);

        Assert.Equal("residential", way.Highway);
        Assert.Equal(51.00, way.BboxMinLat);
        Assert.Equal(51.10, way.BboxMaxLat);
    }
}
