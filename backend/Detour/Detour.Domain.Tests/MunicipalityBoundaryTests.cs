using Detour.Domain.Boundaries;

namespace Detour.Domain.Tests;

public class MunicipalityBoundaryTests
{
    private static readonly List<(double, double)> Square =
        [(50.00, 4.00), (50.00, 4.02), (50.02, 4.02), (50.02, 4.00)];

    private static IReadOnlyList<IReadOnlyList<(double, double)>> Rings(params IReadOnlyList<(double, double)>[] rings) => rings;

    [Fact]
    public void Create_bbox_covers_every_ring_point()
    {
        var result = MunicipalityBoundary.Create("r1", "Testville", Rings(Square));

        Assert.False(result.IsFailure);
        var boundary = result.Value;
        Assert.Equal(50.00, boundary.BboxMinLat);
        Assert.Equal(50.02, boundary.BboxMaxLat);
        Assert.Equal(4.00, boundary.BboxMinLon);
        Assert.Equal(4.02, boundary.BboxMaxLon);
        Assert.Equal("Testville", boundary.Name);
        Assert.Equal("r1", boundary.SourceId);
    }

    [Fact]
    public void OsmId_strips_the_source_id_prefix()
    {
        var boundary = MunicipalityBoundary.Create("r123456", "Testville", Rings(Square)).Value;

        Assert.Equal(123456, boundary.OsmId);
    }

    [Fact]
    public void Create_rejects_no_rings()
    {
        var result = MunicipalityBoundary.Create("r1", "Testville", Rings());

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.MunicipalityBoundary.RingsInvalid));
    }

    [Fact]
    public void Create_rejects_a_ring_with_fewer_than_three_points()
    {
        var result = MunicipalityBoundary.Create("r1", "Testville", Rings([(50.00, 4.00), (50.01, 4.01)]));

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.MunicipalityBoundary.RingsInvalid));
    }

    [Fact]
    public void Create_rejects_a_blank_name()
    {
        var result = MunicipalityBoundary.Create("r1", "  ", Rings(Square));

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.MunicipalityBoundary.NameBlank));
    }

    [Fact]
    public void Create_rejects_out_of_range_coordinates()
    {
        var withBadLat = new List<(double, double)> { (50.00, 4.00), (95.0, 4.01), (50.01, 4.02) };
        var result = MunicipalityBoundary.Create("r1", "Testville", Rings(withBadLat));

        Assert.True(result.IsFailure);
        Assert.True(result.HasError(ValidationKeys.MunicipalityBoundary.CoordinatesInvalid));
    }

    [Fact]
    public void Contains_is_true_inside_the_ring_and_false_outside_it()
    {
        var boundary = MunicipalityBoundary.Create("r1", "Testville", Rings(Square)).Value;

        Assert.True(boundary.Contains(50.01, 4.01));
        Assert.False(boundary.Contains(51.00, 4.01));
    }

    [Fact]
    public void Contains_subtracts_an_inner_ring()
    {
        List<(double, double)> outer = [(50.00, 4.00), (50.00, 4.02), (50.02, 4.02), (50.02, 4.00)];
        List<(double, double)> inner = [(50.008, 4.008), (50.008, 4.012), (50.012, 4.012), (50.012, 4.008)];
        var boundary = MunicipalityBoundary.Create("r1", "Testville", Rings(outer, inner)).Value;

        Assert.True(boundary.Contains(50.005, 4.005), "outside the inner ring, still inside the outer one");
        Assert.False(boundary.Contains(50.010, 4.010), "inside the enclave, which the even-odd ray cast subtracts");
    }

    [Fact]
    public void ReplaceWith_overwrites_name_and_geometry_unconditionally()
    {
        var boundary = MunicipalityBoundary.Create("r1", "Testville", Rings(Square)).Value;

        List<(double, double)> wider = [(51.00, 5.00), (51.00, 5.10), (51.10, 5.10), (51.10, 5.00)];
        var incoming = MunicipalityBoundary.Create("r1", "Newname", Rings(wider)).Value;

        boundary.ReplaceWith(incoming);

        Assert.Equal("Newname", boundary.Name);
        Assert.Equal(51.00, boundary.BboxMinLat);
        Assert.Equal(51.10, boundary.BboxMaxLat);
    }
}
