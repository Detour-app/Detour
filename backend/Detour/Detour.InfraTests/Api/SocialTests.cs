using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

/// <summary>
/// Friendship, fog sharing and route sharing — the three places one rider's data can reach
/// another. Each test here is a privacy promise, not a feature.
/// </summary>
[Collection(PostgresCollection.Name)]
public class SocialTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task A_request_stays_pending_until_the_other_side_accepts()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();

        var status = await Request(alex, blakeName);
        status.Should().Be("pending");

        (await Friends(alex)).Outgoing.Should().ContainSingle().Which.Should().Be(blakeName);
        (await Friends(blake)).Incoming.Should().ContainSingle().Which.Should().Be(alexName);
        (await Friends(alex)).Friends.Should().BeEmpty();
    }

    [Fact]
    public async Task The_requester_cannot_accept_their_own_request()
    {
        var (alex, _) = await NewRider();
        var (_, blakeName) = await NewRider();
        await Request(alex, blakeName);

        var response = await alex.PostAsJsonAsync(
            $"/api/friends/requests/{blakeName}/respond", new { accept = true });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Asking_back_is_the_same_as_accepting()
    {
        // Otherwise two riders who each reached out first would stay strangers forever.
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();

        await Request(alex, blakeName);
        var status = await Request(blake, alexName);

        status.Should().Be("accepted");
        (await Friends(alex)).Friends.Should().ContainSingle().Which.Should().Be(blakeName);
    }

    [Fact]
    public async Task Declining_removes_the_request()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Request(alex, blakeName);

        var response = await blake.PostAsJsonAsync(
            $"/api/friends/requests/{alexName}/respond", new { accept = false });
        response.StatusCode.Should().Be(HttpStatusCode.OK);

        (await Friends(alex)).Outgoing.Should().BeEmpty();
        (await Friends(blake)).Incoming.Should().BeEmpty();
    }

    [Fact]
    public async Task Friend_stats_return_aggregates_and_never_rides()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        await blake.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { new { startTimeMs = 1_000L, mode = "motorcycle" } },
            stats = new { totalDistanceMeters = 42_000.0, topSpeedKmh = 150.0 },
        });

        var stats = await (await alex.GetAsync("/api/friends/stats"))
            .Content.ReadFromJsonAsync<JsonElement>();

        stats.GetArrayLength().Should().Be(1);
        var friend = stats[0];
        friend.GetProperty("username").GetString().Should().Be(blakeName);
        friend.GetProperty("stats").GetProperty("totalDistanceMeters").GetDouble().Should().Be(42_000);
        friend.TryGetProperty("trips", out _).Should().BeFalse("a friend's rides are never returned");
    }

    [Fact]
    public async Task Fog_is_only_shared_when_both_sides_share()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        await blake.PostAsJsonAsync("/api/sync", new
        {
            traces = new[] { "[[51.05,3.72,1000,50.0,12.5]]" },
            shareFog = true,
        });

        // Alex is not sharing: they contribute nothing and therefore receive nothing.
        var withheld = await Fog(alex);
        withheld.GetProperty("sharing").GetBoolean().Should().BeFalse();
        withheld.GetProperty("traces").GetArrayLength().Should().Be(0);

        await alex.PutAsJsonAsync("/api/me/fog-sharing", new { shareFog = true });

        var shared = await Fog(alex);
        shared.GetProperty("sharing").GetBoolean().Should().BeTrue();
        shared.GetProperty("traces").GetArrayLength().Should().Be(1);
    }

    [Fact]
    public async Task Revoking_fog_sharing_stops_the_lines_on_the_next_request()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        await alex.PutAsJsonAsync("/api/me/fog-sharing", new { shareFog = true });
        await blake.PostAsJsonAsync("/api/sync", new
        {
            traces = new[] { "[[51.05,3.72,1000,50.0,12.5]]" },
            shareFog = true,
        });
        (await Fog(alex)).GetProperty("traces").GetArrayLength().Should().Be(1);

        await blake.PutAsJsonAsync("/api/me/fog-sharing", new { shareFog = false });

        (await Fog(alex)).GetProperty("traces").GetArrayLength().Should()
            .Be(0, "the flag is re-read on the row, not cached");
    }

    [Fact]
    public async Task A_stranger_gets_no_fog_even_when_both_share()
    {
        var (alex, _) = await NewRider();
        var (blake, _) = await NewRider();

        await alex.PutAsJsonAsync("/api/me/fog-sharing", new { shareFog = true });
        await blake.PostAsJsonAsync("/api/sync", new
        {
            traces = new[] { "[[51.05,3.72,1000,50.0,12.5]]" },
            shareFog = true,
        });

        (await Fog(alex)).GetProperty("traces").GetArrayLength().Should().Be(0);
    }

    [Fact]
    public async Task A_route_can_only_be_shared_with_a_friend()
    {
        var (alex, _) = await NewRider();
        var (_, blakeName) = await NewRider();

        var response = await alex.PostAsJsonAsync("/api/shared-routes", new
        {
            to = blakeName,
            route = new { id = 1L, name = "Coast road", stops = new[] { new { lat = 1.0 }, new { lat = 2.0 } } },
        });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task A_shared_route_lands_in_the_friends_inbox()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        (await ShareRoute(alex, blakeName, 1, "Coast road")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        var inbox = await Inbox(blake);
        inbox.GetProperty("routes").GetArrayLength().Should().Be(1);
        inbox.GetProperty("routes")[0].GetProperty("from").GetString().Should().Be(alexName);
        inbox.GetProperty("routes")[0].GetProperty("name").GetString().Should().Be("Coast road");
    }

    [Fact]
    public async Task Re_sharing_replaces_rather_than_duplicating()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        await ShareRoute(alex, blakeName, 1, "Coast road");
        await ShareRoute(alex, blakeName, 1, "Coast road, revised");

        var routes = (await Inbox(blake)).GetProperty("routes");
        routes.GetArrayLength().Should().Be(1);
        routes[0].GetProperty("name").GetString().Should().Be("Coast road, revised");
    }

    [Fact]
    public async Task A_route_needs_at_least_two_stops()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        var response = await alex.PostAsJsonAsync("/api/shared-routes", new
        {
            to = blakeName,
            route = new { id = 9L, name = "One stop", stops = new[] { new { lat = 1.0 } } },
        });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Unfriending_takes_back_every_route_between_the_pair()
    {
        // A route is places you have been, so losing the friendship takes it back — both
        // directions, not only what you received.
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        await ShareRoute(alex, blakeName, 1, "Mine to yours");
        await ShareRoute(blake, alexName, 2, "Yours to mine");

        (await Inbox(blake)).GetProperty("routes").GetArrayLength().Should().Be(1);
        (await Inbox(alex)).GetProperty("routes").GetArrayLength().Should().Be(1);

        (await alex.DeleteAsync($"/api/friends/{blakeName}")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        (await Inbox(blake)).GetProperty("routes").GetArrayLength().Should().Be(0);
        (await Inbox(alex)).GetProperty("routes").GetArrayLength().Should().Be(0);
    }

    [Fact]
    public async Task An_uninvolved_rider_cannot_delete_someone_elses_shared_route()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var (casey, _) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        await ShareRoute(alex, blakeName, 1, "Coast road");

        var routeId = (await Inbox(blake)).GetProperty("routes")[0].GetProperty("id").GetGuid();

        (await casey.DeleteAsync($"/api/shared-routes/{routeId}")).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
        (await Inbox(blake)).GetProperty("routes").GetArrayLength().Should().Be(1);

        (await blake.DeleteAsync($"/api/shared-routes/{routeId}")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);
        (await Inbox(blake)).GetProperty("routes").GetArrayLength().Should().Be(0);
    }

    private async Task<(HttpClient Client, string Username)> NewRider()
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", username, null, "detour-user"));

        // Force provisioning so the account exists before anyone looks it up by handle.
        (await client.GetAsync("/api/me")).EnsureSuccessStatusCode();
        return (client, username);
    }

    private static async Task<string> Request(HttpClient client, string username)
    {
        var response = await client.PostAsJsonAsync("/api/friends/requests", new { username });
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return body.GetProperty("status").GetString()!;
    }

    private static async Task Befriend(HttpClient a, string aName, HttpClient b, string bName)
    {
        await Request(a, bName);
        var response = await b.PostAsJsonAsync($"/api/friends/requests/{aName}/respond", new { accept = true });
        response.EnsureSuccessStatusCode();
    }

    private static async Task<FriendsPayload> Friends(HttpClient client) =>
        (await (await client.GetAsync("/api/friends")).Content.ReadFromJsonAsync<FriendsPayload>())!;

    private static async Task<JsonElement> Fog(HttpClient client) =>
        await (await client.GetAsync("/api/friends/fog")).Content.ReadFromJsonAsync<JsonElement>();

    private static async Task<JsonElement> Inbox(HttpClient client) =>
        await (await client.GetAsync("/api/shared-routes")).Content.ReadFromJsonAsync<JsonElement>();

    private static Task<HttpResponseMessage> ShareRoute(
        HttpClient client, string to, long routeId, string name) =>
        client.PostAsJsonAsync("/api/shared-routes", new
        {
            to,
            route = new
            {
                id = routeId,
                name,
                stops = new[] { new { lat = 51.0, lon = 3.7 }, new { lat = 51.1, lon = 3.8 } },
            },
        });

    private sealed record FriendsPayload(
        IReadOnlyList<string> Friends,
        IReadOnlyList<string> Incoming,
        IReadOnlyList<string> Outgoing);
}
