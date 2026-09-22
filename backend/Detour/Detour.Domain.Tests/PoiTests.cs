using Detour.Domain.Pois;

namespace Detour.Domain.Tests;

public class PoiTests
{
    [Fact]
    public void Create_sets_the_bbox_to_the_single_point()
    {
        var result = Poi.Create("n1", "viewpoint", "Belvedere", 50.80, 4.30);

        Assert.False(result.IsFailure);
        var poi = result.Value;
        Assert.Equal(50.80, poi.BboxMinLat);
        Assert.Equal(50.80, poi.BboxMaxLat);
        Assert.Equal(4.30, poi.BboxMinLon);
        Assert.Equal(4.30, poi.BboxMaxLon);
        Assert.Equal("viewpoint", poi.Kind);
        Assert.Equal("Belvedere", poi.Name);
        Assert.Equal("n1", poi.SourceId);
    }

    [Fact]
    public void Create_allows_a_blank_name()
    {
        // Plenty of OSM POIs — especially small cafes — carry no `name` tag; the client falls
        // back to a kind-specific label for those, not this layer.
        var result = Poi.Create("n1", "food", "", 50.80, 4.30);

        Assert.False(result.IsFailure);
        Assert.Equal("", result.Value.Name);
    }

    [Fact]
    public void Create_rejects_out_of_range_coordinates()
    {
        var result = Poi.Create("n1", "sight", "Fort", 95.0, 4.30); // 95.0 is > 90

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.Poi.CoordinatesInvalid));
    }

    [Fact]
    public void Create_rejects_a_blank_kind()
    {
        var result = Poi.Create("n1", "", "Fort", 50.80, 4.30);

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.Poi.KindBlank));
    }

    [Fact]
    public void ReplaceWith_overwrites_name_kind_and_position_unconditionally()
    {
        var poi = Poi.Create("n1", "viewpoint", "Old name", 50.80, 4.30).Value;

        var incoming = Poi.Create("n1", "sight", "New name", 51.00, 5.00).Value;

        poi.ReplaceWith(incoming);

        Assert.Equal("sight", poi.Kind);
        Assert.Equal("New name", poi.Name);
        Assert.Equal(51.00, poi.Lat);
        Assert.Equal(5.00, poi.Lon);
        Assert.Equal(51.00, poi.BboxMinLat);
        Assert.Equal(51.00, poi.BboxMaxLat);
    }
}
