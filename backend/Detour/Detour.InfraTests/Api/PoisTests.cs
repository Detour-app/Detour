using System.Net;
using System.Net.Http.Json;
using Detour.Api.Contracts;
using Detour.Database;
using Detour.Database.Repositories;
using Detour.Domain.Pois;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;
using Shared.Database;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class PoisTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    /// <summary>Seeds one POI straight through the repository — there is no write endpoint for
    /// POIs, the importer is the only writer.</summary>
    private async Task<Poi> SeedPoiAsync(string sourceId, double lat, double lon, string kind = "viewpoint", string name = "Test POI")
    {
        using var scope = _factory.Services.CreateScope();
        var repo = new PoiRepository(scope.ServiceProvider
            .GetRequiredService<ICustomDbContextFactory<DetourDbContext>>());
        var poi = Poi.Create(sourceId, kind, name, lat, lon).Value;
        await repo.UpsertAsync(poi, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);
        return poi;
    }

    [Fact]
    public async Task Get_returns_pois_whose_point_overlaps_the_query()
    {
        await SeedPoiAsync("n-api-1", 52.35, 4.87, "viewpoint", "Belvedere");

        var response = await _factory.CreateClient()
            .GetAsync("/api/pois?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<PoisBboxResponse>();
        Assert.Contains(body!.Pois, p => p.Name == "Belvedere");
    }

    [Fact]
    public async Task Get_with_a_kind_filter_excludes_a_poi_of_a_different_kind()
    {
        await SeedPoiAsync("n-api-kind-1", 52.35, 4.87, "viewpoint");
        await SeedPoiAsync("n-api-kind-2", 52.35, 4.87, "food");

        var response = await _factory.CreateClient()
            .GetAsync("/api/pois?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95&kind=viewpoint");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<PoisBboxResponse>();
        Assert.Contains(body!.Pois, p => p.Kind == "viewpoint");
        Assert.DoesNotContain(body.Pois, p => p.Kind == "food");
    }

    [Fact]
    public async Task Get_with_a_malformed_bbox_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/pois?minLat=abc&minLon=4.30&maxLat=50.90&maxLon=4.40");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_with_minLat_greater_than_maxLat_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/pois?minLat=50.90&minLon=4.30&maxLat=50.80&maxLon=4.40");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_with_a_bbox_spanning_the_whole_globe_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/pois?minLat=-90&minLon=-180&maxLat=90&maxLon=180");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_excludes_a_poi_outside_the_query_box()
    {
        var poi = await SeedPoiAsync("n-api-2", 10.00, 10.00);

        var response = await _factory.CreateClient()
            .GetAsync("/api/pois?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<PoisBboxResponse>();
        Assert.DoesNotContain(body!.Pois, p => p.Id == poi.Id);
    }
}
