using System.Net;
using System.Net.Http.Json;
using Detour.Api.Contracts;
using Detour.Database;
using Detour.Database.Repositories;
using Detour.Domain.Roads;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;
using Shared.Database;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class RoadsTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    /// <summary>Seeds one way straight through the repository — there is no write endpoint for
    /// roads, the importer is the only writer.</summary>
    private async Task<RoadWay> SeedWayAsync(string sourceId, double lat, double lon, string highway = "primary")
    {
        using var scope = _factory.Services.CreateScope();
        var repo = new RoadWayRepository(scope.ServiceProvider
            .GetRequiredService<ICustomDbContextFactory<DetourDbContext>>());
        var way = RoadWay.Create(sourceId, [(lat, lon), (lat + 0.001, lon + 0.001)], highway).Value;
        await repo.UpsertAsync(way, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);
        return way;
    }

    [Fact]
    public async Task Get_returns_ways_whose_bbox_overlaps_the_query()
    {
        await SeedWayAsync("w-api-1", 52.35, 4.87, "primary");

        var response = await _factory.CreateClient()
            .GetAsync("/api/roads?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<RoadsBboxResponse>();
        Assert.Contains(body!.Ways, w => w.Highway == "primary");
    }

    [Fact]
    public async Task Get_with_a_classes_filter_excludes_a_way_of_a_different_class()
    {
        await SeedWayAsync("w-api-classes-1", 52.35, 4.87, "primary");
        await SeedWayAsync("w-api-classes-2", 52.35, 4.87, "residential");

        var response = await _factory.CreateClient()
            .GetAsync("/api/roads?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95&classes=primary");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<RoadsBboxResponse>();
        Assert.Contains(body!.Ways, w => w.Highway == "primary");
        Assert.DoesNotContain(body.Ways, w => w.Highway == "residential");
    }

    [Fact]
    public async Task Get_with_a_malformed_bbox_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/roads?minLat=abc&minLon=4.30&maxLat=50.90&maxLon=4.40");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_with_minLat_greater_than_maxLat_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/roads?minLat=50.90&minLon=4.30&maxLat=50.80&maxLon=4.40");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_with_a_bbox_spanning_the_whole_globe_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/roads?minLat=-90&minLon=-180&maxLat=90&maxLon=180");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_excludes_a_way_outside_the_query_box()
    {
        var way = await SeedWayAsync("w-api-2", 10.00, 10.00);

        var response = await _factory.CreateClient()
            .GetAsync("/api/roads?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<RoadsBboxResponse>();
        Assert.DoesNotContain(body!.Ways, w => w.Id == way.Id);
    }
}
