using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

/// <summary>
/// The web planner's per-route API (#912). It writes the rows <c>/sync</c> merges, so every rule
/// here is also a promise to the phone: a planner edit the phone's stale copy cannot revert, a
/// planner delete the phone cannot undo.
/// </summary>
[Collection(PostgresCollection.Name)]
public class RoutesTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Lists_the_riders_routes_as_stored()
    {
        var client = NewRider();
        await Sync(client, new { savedRoutes = new[] { Route(31, "Coast road", 1_000), Route(30, "Hills", 2_000) } });

        var list = await List(client);

        list.Routes.Select(r => r.GetProperty("id").GetInt64()).Should().Equal(30, 31);
        list.Routes[1].GetProperty("name").GetString().Should().Be("Coast road");
        list.Routes[1].GetProperty("polyline").GetArrayLength().Should().Be(4);
    }

    [Fact]
    public async Task A_put_creates_a_route_the_phone_then_syncs()
    {
        var client = NewRider();

        var response = await Put(client, 40, Route(40, "Planned in the browser", 5_000));

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var stored = await response.Content.ReadFromJsonAsync<JsonElement>();
        stored.GetProperty("name").GetString().Should().Be("Planned in the browser");

        var synced = await Sync(client, new { });
        synced.SavedRoutes.Should().ContainSingle();
        synced.SavedRoutes[0].GetProperty("editedAtMs").GetInt64().Should().Be(5_000);
        synced.SavedRoutes[0].GetProperty("mode").GetString().Should().Be("MOTO");
        synced.SavedRoutes[0].GetProperty("stops").GetArrayLength().Should().Be(2);
    }

    [Fact]
    public async Task A_newer_edit_replaces_the_stored_copy()
    {
        var client = NewRider();
        await Sync(client, new { savedRoutes = new[] { Route(41, "From the phone", 5_000) } });

        var response = await Put(client, 41, Route(41, "Edited in the planner", 6_000));

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var synced = await Sync(client, new { });
        synced.SavedRoutes.Should().ContainSingle();
        synced.SavedRoutes[0].GetProperty("name").GetString().Should().Be("Edited in the planner");
    }

    [Fact]
    public async Task A_stale_edit_is_refused_with_the_stored_copy()
    {
        // The phone edited the route after the planner loaded it; the planner's save must not
        // overwrite that edit, and gets the newer copy to show instead.
        var client = NewRider();
        await Sync(client, new { savedRoutes = new[] { Route(42, "Newer, from the phone", 9_000) } });

        var response = await Put(client, 42, Route(42, "Older, from the planner", 8_000));

        response.StatusCode.Should().Be(HttpStatusCode.Conflict);
        var conflict = await response.Content.ReadFromJsonAsync<JsonElement>();
        conflict.GetProperty("stored").GetProperty("name").GetString().Should().Be("Newer, from the phone");

        var synced = await Sync(client, new { });
        synced.SavedRoutes[0].GetProperty("name").GetString().Should().Be("Newer, from the phone");
    }

    [Fact]
    public async Task A_route_without_an_edit_stamp_is_refused()
    {
        var client = NewRider();
        var route = new { id = 43L, name = "No stamp", mode = "MOTO", stops = TwoStops };

        (await Put(client, 43, route)).StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await List(client)).Routes.Should().BeEmpty();
    }

    [Fact]
    public async Task A_route_with_one_stop_is_refused()
    {
        var client = NewRider();
        var route = new { id = 44L, mode = "MOTO", editedAtMs = 5_000L, stops = new[] { new { lat = 50.8, lon = 3.2 } } };

        (await Put(client, 44, route)).StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Theory]
    [InlineData(null)]
    [InlineData(" ")]
    public async Task A_route_without_a_mode_is_refused(string? mode)
    {
        var client = NewRider();
        var route = new { id = 45L, mode, editedAtMs = 5_000L, stops = TwoStops };

        (await Put(client, 45, route)).StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task A_route_whose_id_differs_from_the_path_is_refused()
    {
        var client = NewRider();

        (await Put(client, 46, Route(47, "Wrong id", 5_000))).StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await List(client)).Routes.Should().BeEmpty();
    }

    [Fact]
    public async Task A_delete_reaches_the_phone_and_is_idempotent()
    {
        var client = NewRider();
        await Sync(client, new { savedRoutes = new[] { Route(48, "Keep", 5_000), Route(49, "Drop", 5_000) } });

        (await client.DeleteAsync("/api/routes/49")).StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await client.DeleteAsync("/api/routes/49")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        // A phone that still holds the route uploads it again; the deletion holds, and comes back
        // down so the phone drops its copy.
        var synced = await Sync(client, new { savedRoutes = new[] { Route(49, "Drop", 5_000) } });
        synced.SavedRoutes.Select(r => r.GetProperty("id").GetInt64()).Should().Equal(48);
        synced.DeletedSavedRouteIds.Should().Equal(49);
        (await List(client)).Routes.Select(r => r.GetProperty("id").GetInt64()).Should().Equal(48);
    }

    [Fact]
    public async Task A_put_after_a_delete_is_gone()
    {
        var client = NewRider();
        await Put(client, 50, Route(50, "Short-lived", 5_000));
        await client.DeleteAsync("/api/routes/50");

        var response = await Put(client, 50, Route(50, "Back again", 6_000));

        response.StatusCode.Should().Be(HttpStatusCode.Gone);
        (await List(client)).Routes.Should().BeEmpty();
    }

    [Fact]
    public async Task Another_riders_route_is_invisible()
    {
        var owner = NewRider();
        var other = NewRider();
        await Put(owner, 51, Route(51, "Mine", 5_000));

        (await List(other)).Routes.Should().BeEmpty();

        // The same client id is a different route for someone else: their writes and deletes
        // land on their own rows and leave the owner's alone.
        (await Put(other, 51, Route(51, "Theirs", 1_000))).StatusCode.Should().Be(HttpStatusCode.OK);
        (await other.DeleteAsync("/api/routes/51")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        var mine = await List(owner);
        mine.Routes.Should().ContainSingle();
        mine.Routes[0].GetProperty("name").GetString().Should().Be("Mine");
        (await Sync(owner, new { })).DeletedSavedRouteIds.Should().BeEmpty();
    }

    private static readonly object[] TwoStops =
    [
        new { lat = 50.8, lon = 3.2, name = "Home" },
        new { lat = 50.9, lon = 3.3, name = "Work" },
    ];

    private HttpClient NewRider() =>
        _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}",
            $"rider{Guid.NewGuid():N}"[..16],
            null,
            "detour-user"));

    private static Task<HttpResponseMessage> Put(HttpClient client, long clientId, object route) =>
        client.PutAsJsonAsync($"/api/routes/{clientId}", route);

    private static async Task<RouteList> List(HttpClient client)
    {
        var response = await client.GetAsync("/api/routes");
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        return (await response.Content.ReadFromJsonAsync<RouteList>())!;
    }

    private static async Task<SyncedRoutes> Sync(HttpClient client, object request)
    {
        var response = await client.PostAsJsonAsync("/api/sync", request);
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        return (await response.Content.ReadFromJsonAsync<SyncedRoutes>())!;
    }

    /// <summary>The shape the app's <c>SavedRoute.toJson</c> writes, with an edit stamp.</summary>
    private static object Route(long id, string name, long editedAtMs) => new
    {
        id,
        name,
        createdMs = 1_700_000_000_000L,
        mode = "MOTO",
        stops = TwoStops,
        polyline = new[] { 50.8, 3.2, 50.9, 3.3 },
        distanceMeters = 15_234.5,
        timeMs = 3_600_000L,
        editedAtMs,
    };

    private sealed record RouteList(IReadOnlyList<JsonElement> Routes);

    private sealed record SyncedRoutes(IReadOnlyList<JsonElement> SavedRoutes, IReadOnlyList<long> DeletedSavedRouteIds);
}
