using System.Net;
using System.Net.Http.Json;
using Detour.Api.Contracts;
using Detour.Database;
using Detour.Database.Repositories;
using Detour.Domain.Cameras;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;
using Shared.Database;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class CamerasTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    private static CameraSource OsmSource(string id) =>
        new("osm", id, DateTimeOffset.UtcNow, DateTimeOffset.UtcNow);

    /// <summary>Seeds one point camera straight through the repository — there is no write
    /// endpoint for cameras (the importer is Task 4), so this is the only way a bbox test gets
    /// a row to find.</summary>
    private async Task<Camera> SeedCameraAsync(double lat, double lon, string sourceId)
    {
        using var scope = _factory.Services.CreateScope();
        var repo = new CameraRepository(scope.ServiceProvider
            .GetRequiredService<ICustomDbContextFactory<DetourDbContext>>());
        var camera = Camera.CreatePoint(CameraKind.FixedSpeed, lat, lon, 50, "N9", OsmSource(sourceId)).Value;
        await repo.UpsertAsync(camera, CancellationToken.None);
        await repo.FlushChangesAsync(CancellationToken.None);
        return camera;
    }

    [Fact]
    public async Task Get_returns_cameras_whose_bbox_overlaps_the_query()
    {
        // Amsterdam-ish, deliberately clear of the Belgium (50.85/4.36) and Paris (48.85/2.35)
        // boxes CameraRepositoryTests seeds into this same un-truncated live table (#303).
        await SeedCameraAsync(52.35, 4.87, "n1");

        var response = await _factory.CreateClient()
            .GetAsync("/api/cameras?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<CamerasBboxResponse>();
        Assert.Single(body!.Cameras);
    }

    [Fact]
    public async Task Get_with_a_malformed_bbox_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/cameras?minLat=abc&minLon=4.30&maxLat=50.90&maxLon=4.40");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_with_minLat_greater_than_maxLat_returns_400()
    {
        var response = await _factory.CreateClient()
            .GetAsync("/api/cameras?minLat=50.90&minLon=4.30&maxLat=50.80&maxLon=4.40");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
    }

    [Fact]
    public async Task Get_excludes_a_camera_outside_the_query_box()
    {
        // Far from the query box below — a different city entirely — so this proves exclusion
        // rather than relying on this class's other test's rows staying out of a wide box.
        var camera = await SeedCameraAsync(10.00, 10.00, "n2");

        var response = await _factory.CreateClient()
            .GetAsync("/api/cameras?minLat=52.30&minLon=4.80&maxLat=52.40&maxLon=4.95");

        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<CamerasBboxResponse>();
        Assert.DoesNotContain(body!.Cameras, c => c.Id == camera.Id);
    }
}
