using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

/// <summary>
/// Planned rides (#639). A ride names a time and a meeting point, so who can see it is a
/// privacy promise: the owner and the riders they invited, nobody else, and only friends or
/// circle members can be invited.
/// </summary>
[Collection(PostgresCollection.Name)]
public class PlannedRideTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task The_owner_creates_a_ride_and_reads_it_back()
    {
        var alex = await _factory.SignInAsync();

        var response = await Create(alex, "Ardennes loop");
        response.StatusCode.Should().Be(HttpStatusCode.Created);
        var ride = await response.Content.ReadFromJsonAsync<JsonElement>();

        ride.GetProperty("owner").GetProperty("id").GetGuid().Should().Be(alex.UserId);
        ride.GetProperty("title").GetString().Should().Be("Ardennes loop");
        ride.GetProperty("startsAtMs").GetInt64().Should().Be(StartsAtMs);
        ride.GetProperty("meetingPoint").GetProperty("label").GetString().Should().Be("Total, Barvaux");
        ride.GetProperty("routeId").GetInt64().Should().Be(42);
        ride.GetProperty("myAnswer").ValueKind.Should().Be(JsonValueKind.Null);
        ride.GetProperty("invitees").GetArrayLength().Should().Be(0);

        var fetched = await Get(alex, Id(ride));
        fetched.StatusCode.Should().Be(HttpStatusCode.OK);
        (await Rides(alex)).Should().ContainSingle();
    }

    [Fact]
    public async Task An_invalid_ride_is_refused()
    {
        var alex = await _factory.SignInAsync();

        var response = await alex.PostAsJsonAsync("/api/planned-rides", Body("  ", latitude: 95));

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await Rides(alex)).Should().BeEmpty();
    }

    [Fact]
    public async Task An_invited_friend_sees_the_ride_and_answers()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        await Befriend(alex, blake);
        var ride = await CreateRide(alex);

        (await Invite(alex, ride, riderIds: [blake.UserId])).StatusCode.Should().Be(HttpStatusCode.OK);

        var seen = (await Rides(blake)).Should().ContainSingle().Subject;
        seen.GetProperty("myAnswer").GetString().Should().Be("pending");

        var answer = await Answer(blake, ride, "going");
        answer.StatusCode.Should().Be(HttpStatusCode.OK);
        (await answer.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("answer").GetString()
            .Should().Be("going");

        var ownersView = await (await Get(alex, ride)).Content.ReadFromJsonAsync<JsonElement>();
        var invitee = ownersView.GetProperty("invitees").EnumerateArray().Should().ContainSingle().Subject;
        invitee.GetProperty("rider").GetProperty("id").GetGuid().Should().Be(blake.UserId);
        invitee.GetProperty("rider").GetProperty("username").GetString().Should().Be(blake.Username);
        invitee.GetProperty("answer").GetString().Should().Be("going");
    }

    [Fact]
    public async Task Answers_can_change_and_pending_is_not_one()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        await Befriend(alex, blake);
        var ride = await CreateRide(alex);
        await Invite(alex, ride, riderIds: [blake.UserId]);

        (await Answer(blake, ride, "declined")).StatusCode.Should().Be(HttpStatusCode.OK);
        (await Answer(blake, ride, "maybe")).StatusCode.Should().Be(HttpStatusCode.OK);
        (await Answer(blake, ride, "pending")).StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await Answer(blake, ride, "sure")).StatusCode.Should().Be(HttpStatusCode.BadRequest);

        (await Rides(blake)).Single().GetProperty("myAnswer").GetString().Should().Be("maybe");
    }

    [Fact]
    public async Task Inviting_again_keeps_the_answer()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        await Befriend(alex, blake);
        var ride = await CreateRide(alex);
        await Invite(alex, ride, riderIds: [blake.UserId]);
        await Answer(blake, ride, "going");

        await Invite(alex, ride, riderIds: [blake.UserId]);

        (await Rides(blake)).Single().GetProperty("myAnswer").GetString().Should().Be("going");
    }

    [Fact]
    public async Task A_stranger_cannot_be_invited_and_the_whole_request_is_refused()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        var stranger = await _factory.SignInAsync();
        await Befriend(alex, blake);
        var ride = await CreateRide(alex);

        var response = await Invite(alex, ride, riderIds: [blake.UserId, stranger.UserId]);

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await Rides(blake)).Should().BeEmpty("one refused invitee refuses the whole request");
        (await Rides(stranger)).Should().BeEmpty();
    }

    [Fact]
    public async Task A_pending_friend_request_is_not_enough_to_invite()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        (await alex.PostAsJsonAsync("/api/friends/requests", new { username = blake.Username }))
            .EnsureSuccessStatusCode();
        var ride = await CreateRide(alex);

        (await Invite(alex, ride, riderIds: [blake.UserId])).StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Inviting_a_circle_invites_its_accepted_members_including_non_friends()
    {
        // Casey is in Alex's circle through Blake, not a friend of Alex. The owner decision on
        // #639 is that circle members can be invited all the same.
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        var casey = await _factory.SignInAsync();
        var dana = await _factory.SignInAsync();
        await Befriend(alex, blake);
        await Befriend(blake, casey);
        await Befriend(alex, dana);

        var circle = await CreateCircle(alex, "Sunday crew");
        await JoinCircle(alex, blake, circle);
        await JoinCircle(blake, casey, circle);
        (await alex.PostAsJsonAsync($"/api/groups/{circle}/invitations", new { username = dana.Username }))
            .EnsureSuccessStatusCode(); // invited, never accepted

        var ride = await CreateRide(alex);
        (await Invite(alex, ride, circleIds: [circle])).StatusCode.Should().Be(HttpStatusCode.OK);

        (await Rides(blake)).Should().ContainSingle();
        (await Rides(casey)).Should().ContainSingle();
        (await Rides(dana)).Should().BeEmpty("an unanswered circle invitation is not membership");

        // A circle member can also be invited one by one.
        var second = await CreateRide(alex);
        (await Invite(alex, second, riderIds: [casey.UserId])).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task A_circle_invitee_who_is_not_a_friend_reads_the_attached_route()
    {
        // #757: the route travels on the ride, so it reaches a circle member the owner could
        // not send a share to.
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        var casey = await _factory.SignInAsync();
        await Befriend(alex, blake);
        await Befriend(blake, casey);
        var circle = await CreateCircle(alex, "Sunday crew");
        await JoinCircle(alex, blake, circle);
        await JoinCircle(blake, casey, circle);

        var created = await alex.PostAsJsonAsync("/api/planned-rides", Body("Ardennes loop", route: Route()));
        created.StatusCode.Should().Be(HttpStatusCode.Created);
        var ride = Id(await created.Content.ReadFromJsonAsync<JsonElement>());
        (await Invite(alex, ride, circleIds: [circle])).EnsureSuccessStatusCode();

        var seen = await (await Get(casey, ride)).Content.ReadFromJsonAsync<JsonElement>();
        var route = seen.GetProperty("route");
        route.GetProperty("name").GetString().Should().Be("Ourthe valley");
        route.GetProperty("stops").GetArrayLength().Should().Be(2);
        route.GetProperty("polyline").GetArrayLength().Should().Be(6, "fields the server does not read come back as sent");
        (await Rides(casey)).Single().GetProperty("route").GetProperty("id").GetInt64().Should().Be(42);

        // An edit replaces every field, the route included.
        (await alex.PutAsJsonAsync($"/api/planned-rides/{ride}", Body("Ardennes loop"))).EnsureSuccessStatusCode();
        (await Rides(casey)).Single().GetProperty("route").ValueKind.Should().Be(JsonValueKind.Null);
    }

    [Fact]
    public async Task A_route_with_one_stop_is_refused()
    {
        var alex = await _factory.SignInAsync();
        var oneStop = new { id = 42L, name = "Nowhere", stops = new[] { new { lat = 50.3, lon = 5.4 } } };

        var response = await alex.PostAsJsonAsync("/api/planned-rides", Body("Ardennes loop", route: oneStop));

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await Rides(alex)).Should().BeEmpty();
    }

    [Fact]
    public async Task A_circle_the_owner_is_not_in_cannot_be_invited()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        var theirs = await CreateCircle(blake, "Not yours");
        var ride = await CreateRide(alex);

        (await Invite(alex, ride, circleIds: [theirs])).StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await Invite(alex, ride, circleIds: [Guid.NewGuid()])).StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Unfriending_removes_the_rsvp_both_ways_but_keeps_the_rides()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        var casey = await _factory.SignInAsync();
        await Befriend(alex, blake);
        await Befriend(alex, casey);

        var alexsRide = await CreateRide(alex);
        await Invite(alex, alexsRide, riderIds: [blake.UserId, casey.UserId]);
        await Answer(blake, alexsRide, "going");
        var blakesRide = await CreateRide(blake);
        await Invite(blake, blakesRide, riderIds: [alex.UserId]);

        (await blake.DeleteAsync($"/api/friends/{alex.UserId}")).StatusCode.Should().Be(HttpStatusCode.NoContent);

        (await Get(blake, alexsRide)).StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await Get(alex, blakesRide)).StatusCode.Should().Be(HttpStatusCode.NotFound);
        (await Rides(blake)).Should().ContainSingle("Blake's own ride stays")
            .Which.GetProperty("id").GetGuid().Should().Be(blakesRide);

        var alexsView = await (await Get(alex, alexsRide)).Content.ReadFromJsonAsync<JsonElement>();
        alexsView.GetProperty("invitees").EnumerateArray().Should().ContainSingle(
                "the other friend's invitation is untouched")
            .Which.GetProperty("rider").GetProperty("id").GetGuid().Should().Be(casey.UserId);
    }

    [Fact]
    public async Task A_non_invitee_gets_404_for_everything_as_if_the_ride_did_not_exist()
    {
        var alex = await _factory.SignInAsync();
        var stranger = await _factory.SignInAsync();
        var ride = await CreateRide(alex);
        var missing = Guid.NewGuid();

        foreach (var id in new[] { ride, missing })
        {
            (await Get(stranger, id)).StatusCode.Should().Be(HttpStatusCode.NotFound);
            (await stranger.PutAsJsonAsync($"/api/planned-rides/{id}", Body("Mine now")))
                .StatusCode.Should().Be(HttpStatusCode.NotFound);
            (await stranger.DeleteAsync($"/api/planned-rides/{id}")).StatusCode.Should().Be(HttpStatusCode.NotFound);
            (await Invite(stranger, id, riderIds: [stranger.UserId])).StatusCode.Should().Be(HttpStatusCode.NotFound);
            (await Answer(stranger, id, "going")).StatusCode.Should().Be(HttpStatusCode.NotFound);
        }

        (await Rides(stranger)).Should().BeEmpty();
        (await Get(alex, ride)).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task Only_the_owner_edits_deletes_or_invites()
    {
        var alex = await _factory.SignInAsync();
        var blake = await _factory.SignInAsync();
        var casey = await _factory.SignInAsync();
        await Befriend(alex, blake);
        await Befriend(blake, casey);
        var ride = await CreateRide(alex);
        await Invite(alex, ride, riderIds: [blake.UserId]);

        (await blake.PutAsJsonAsync($"/api/planned-rides/{ride}", Body("Hijacked")))
            .StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await blake.DeleteAsync($"/api/planned-rides/{ride}")).StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await Invite(blake, ride, riderIds: [casey.UserId])).StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await Rides(casey)).Should().BeEmpty();

        var edited = await alex.PutAsJsonAsync($"/api/planned-rides/{ride}", Body("Moved to Sunday"));
        edited.StatusCode.Should().Be(HttpStatusCode.OK);
        (await Rides(blake)).Single().GetProperty("title").GetString().Should().Be("Moved to Sunday");
        (await Rides(blake)).Single().GetProperty("invitees").GetArrayLength()
            .Should().Be(1, "an edit keeps the invitations");

        (await alex.DeleteAsync($"/api/planned-rides/{ride}")).StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await Rides(blake)).Should().BeEmpty();
        (await Rides(alex)).Should().BeEmpty();
    }

    [Fact]
    public async Task The_owner_has_nothing_to_answer()
    {
        var alex = await _factory.SignInAsync();
        var ride = await CreateRide(alex);

        (await Answer(alex, ride, "going")).StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await Invite(alex, ride, riderIds: [alex.UserId])).StatusCode.Should().Be(HttpStatusCode.OK);
        (await Rides(alex)).Single().GetProperty("invitees").GetArrayLength().Should().Be(0);
    }

    [Fact]
    public async Task Rides_are_listed_soonest_first()
    {
        var alex = await _factory.SignInAsync();
        (await alex.PostAsJsonAsync("/api/planned-rides", Body("Later", startsAtMs: StartsAtMs + 86_400_000)))
            .EnsureSuccessStatusCode();
        (await alex.PostAsJsonAsync("/api/planned-rides", Body("Sooner"))).EnsureSuccessStatusCode();

        (await Rides(alex)).Select(r => r.GetProperty("title").GetString())
            .Should().Equal("Sooner", "Later");
    }

    private const long StartsAtMs = 1_800_000_000_000L;

    private static object Body(
        string title, double latitude = 50.36, long startsAtMs = StartsAtMs, object? route = null) => new
    {
        title,
        startsAtMs,
        meetingPoint = new { latitude, longitude = 5.45, label = "Total, Barvaux" },
        routeId = 42L,
        description = "Bring rain gear.",
        route,
    };

    /// <summary>The client's <c>routeToJson</c> shape: the server reads the id and stops only.</summary>
    private static object Route() => new
    {
        id = 42L,
        name = "Ourthe valley",
        mode = "CAR",
        stops = new[] { new { lat = 50.36, lon = 5.45 }, new { lat = 50.25, lon = 5.6 } },
        polyline = new[] { 50.36, 5.45, 50.3, 5.5, 50.25, 5.6 },
    };

    private static Task<HttpResponseMessage> Create(SignedInClient client, string title) =>
        client.PostAsJsonAsync("/api/planned-rides", Body(title));

    private static async Task<Guid> CreateRide(SignedInClient client)
    {
        var response = await Create(client, "Ardennes loop");
        response.EnsureSuccessStatusCode();
        return Id(await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    private static Guid Id(JsonElement element) => element.GetProperty("id").GetGuid();

    private static Task<HttpResponseMessage> Get(SignedInClient client, Guid ride) =>
        client.GetAsync($"/api/planned-rides/{ride}");

    private static async Task<List<JsonElement>> Rides(SignedInClient client)
    {
        var response = await client.GetAsync("/api/planned-rides");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return [.. body.GetProperty("rides").EnumerateArray()];
    }

    private static Task<HttpResponseMessage> Invite(
        SignedInClient client, Guid ride, Guid[]? riderIds = null, Guid[]? circleIds = null) =>
        client.PostAsJsonAsync($"/api/planned-rides/{ride}/invitations", new { riderIds, circleIds });

    private static Task<HttpResponseMessage> Answer(SignedInClient client, Guid ride, string answer) =>
        client.PutAsJsonAsync($"/api/planned-rides/{ride}/rsvp", new { answer });

    private static async Task Befriend(SignedInClient a, SignedInClient b)
    {
        (await a.PostAsJsonAsync("/api/friends/requests", new { username = b.Username }))
            .EnsureSuccessStatusCode();
        (await b.PostAsJsonAsync($"/api/friends/requests/{a.UserId}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }

    private static async Task<Guid> CreateCircle(SignedInClient client, string name)
    {
        var response = await client.PostAsJsonAsync("/api/circles", new { name });
        response.EnsureSuccessStatusCode();
        return Id(await response.Content.ReadFromJsonAsync<JsonElement>());
    }

    /// <summary>The inviter must be a member and a friend of the joiner, as groups require.</summary>
    private static async Task JoinCircle(SignedInClient inviter, SignedInClient joiner, Guid circle)
    {
        (await inviter.PostAsJsonAsync($"/api/groups/{circle}/invitations", new { username = joiner.Username }))
            .EnsureSuccessStatusCode();
        (await joiner.PostAsJsonAsync($"/api/groups/{circle}/invitations/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }
}
