using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.Domain;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// Convoys, circles, and everything gated on membership. The access chain under test is:
/// friends first, then invited, then accepted, then — for a circle — not paused.
/// </summary>
[Collection(PostgresCollection.Name)]
public class GroupTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Creating_a_circle_joins_the_owner()
    {
        var (alex, alexName) = await NewRider();

        var circle = await CreateCircle(alex, "Household");

        circle.GetProperty("kind").GetString().Should().Be("circle");
        circle.GetProperty("status").GetString().Should().Be("accepted");
        var members = circle.GetProperty("members");
        members.GetArrayLength().Should().Be(1);
        members[0].GetProperty("username").GetString().Should().Be(alexName);
        members[0].GetProperty("sharing").GetBoolean().Should().BeTrue();
    }

    [Fact]
    public async Task A_convoy_reports_no_sharing_flag()
    {
        // A convoy connection is sharing, so there is nothing meaningful to show.
        var (alex, _) = await NewRider();

        var convoy = await CreateConvoy(alex, "Sunday ride");

        convoy.GetProperty("members")[0].GetProperty("sharing").ValueKind
            .Should().Be(JsonValueKind.Null);
    }

    [Fact]
    public async Task Only_a_friend_can_be_invited()
    {
        var (alex, _) = await NewRider();
        var (_, blakeName) = await NewRider();
        var circle = await CreateCircle(alex, "Household");

        var response = await Invite(alex, circle, blakeName);

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task A_non_member_cannot_invite_and_cannot_tell_the_group_apart_from_one_that_does_not_exist()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var (casey, caseyName) = await NewRider();
        await Befriend(blake, blakeName, casey, caseyName);
        _ = alexName;

        var circle = await CreateCircle(alex, "Household");

        var real = await Invite(blake, circle, caseyName);
        var imaginary = await Invite(blake, Guid.CreateVersion7(), caseyName);

        real.StatusCode.Should().Be(imaginary.StatusCode).And.Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task An_invited_member_only_appears_after_accepting()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        var circle = await CreateCircle(alex, "Household");

        (await Invite(alex, circle, blakeName)).EnsureSuccessStatusCode();

        var pending = await Circles(blake);
        pending[0].GetProperty("status").GetString().Should().Be("invited");

        (await Respond(blake, circle, accept: true)).EnsureSuccessStatusCode();

        var joined = await Circles(blake);
        joined[0].GetProperty("status").GetString().Should().Be("accepted");
    }

    [Fact]
    public async Task A_circle_fills_up_and_a_convoy_does_not()
    {
        var (alex, alexName) = await NewRider();
        var circle = await CreateCircle(alex, "Household");

        // The owner already holds one of the slots.
        for (var i = 1; i < DetourLimits.MaxCircleMembers; i++)
        {
            var (friend, friendName) = await NewRider();
            await Befriend(alex, alexName, friend, friendName);
            (await Invite(alex, circle, friendName)).EnsureSuccessStatusCode();
        }

        var (extra, extraName) = await NewRider();
        await Befriend(alex, alexName, extra, extraName);

        (await Invite(alex, circle, extraName)).StatusCode.Should().Be(HttpStatusCode.BadRequest);

        var convoy = await CreateConvoy(alex, "Big ride");
        (await Invite(alex, convoy, extraName)).EnsureSuccessStatusCode();
    }

    [Fact]
    public async Task An_emptied_convoy_disappears_and_an_emptied_circle_does_not()
    {
        var (alex, _) = await NewRider();
        var convoy = await CreateConvoy(alex, "Sunday ride");
        var circle = await CreateCircle(alex, "Household");

        (await alex.DeleteAsync($"/api/groups/{Id(convoy)}/membership")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);
        (await alex.DeleteAsync($"/api/groups/{Id(circle)}/membership")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.Groups.FindAsync(Id(convoy))).Should().BeNull();
        (await db.Groups.FindAsync(Id(circle))).Should().NotBeNull("a circle persists when everyone leaves");
    }

    [Fact]
    public async Task A_position_is_visible_to_the_circle_and_pausing_withdraws_it()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        var circle = await CreateCircle(alex, "Household");
        (await Invite(alex, circle, blakeName)).EnsureSuccessStatusCode();
        (await Respond(blake, circle, true)).EnsureSuccessStatusCode();

        await ReportPosition(blake, circle, 51.05, 3.72);

        var seen = await Positions(alex, circle);
        seen.GetArrayLength().Should().Be(1);
        seen[0].GetProperty("username").GetString().Should().Be(blakeName);

        (await blake.PutAsJsonAsync($"/api/circles/{Id(circle)}/sharing", new { sharing = false }))
            .EnsureSuccessStatusCode();

        (await Positions(alex, circle)).GetArrayLength().Should()
            .Be(0, "a paused member is excluded from the read even though the row remains");
    }

    [Fact]
    public async Task A_paused_member_reporting_a_position_is_accepted_and_discarded()
    {
        // The device does not need special handling for pause; the server drops it either way.
        var (alex, _) = await NewRider();
        var circle = await CreateCircle(alex, "Household");
        (await alex.PutAsJsonAsync($"/api/circles/{Id(circle)}/sharing", new { sharing = false }))
            .EnsureSuccessStatusCode();

        (await ReportPosition(alex, circle, 51.05, 3.72)).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        var circleId = Id(circle);
        await using var db = postgres.CreateContext();
        (await db.MemberFixes.CountAsync(f => f.GroupId == circleId)).Should().Be(0);
    }

    [Fact]
    public async Task A_position_is_overwritten_in_place()
    {
        var (alex, _) = await NewRider();
        var circle = await CreateCircle(alex, "Household");

        await ReportPosition(alex, circle, 51.05, 3.72);
        await ReportPosition(alex, circle, 51.99, 3.99);

        var positions = await Positions(alex, circle);
        positions.GetArrayLength().Should().Be(1, "no history, no trail");
        positions[0].GetProperty("latitude").GetDouble().Should().Be(51.99);
    }

    [Fact]
    public async Task An_unusable_position_is_refused()
    {
        var (alex, _) = await NewRider();
        var circle = await CreateCircle(alex, "Household");

        (await ReportPosition(alex, circle, 91, 3.72)).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Pausing_a_convoy_answers_not_found()
    {
        // Not "not applicable" — otherwise this becomes a second way to ask what kind a group is.
        var (alex, _) = await NewRider();
        var convoy = await CreateConvoy(alex, "Sunday ride");

        var response = await alex.PutAsJsonAsync($"/api/circles/{Id(convoy)}/sharing", new { sharing = false });

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task A_shared_place_is_visible_to_the_circle_and_leaving_takes_it_back()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        var circle = await CreateCircle(alex, "Household");
        (await Invite(alex, circle, blakeName)).EnsureSuccessStatusCode();
        (await Respond(blake, circle, true)).EnsureSuccessStatusCode();

        (await SharePlace(blake, circle, 7, "School", 200)).EnsureSuccessStatusCode();
        (await Places(alex, circle)).GetArrayLength().Should().Be(1);

        (await blake.DeleteAsync($"/api/groups/{Id(circle)}/membership")).EnsureSuccessStatusCode();

        (await Places(alex, circle)).GetArrayLength().Should()
            .Be(0, "a place is revoked when its owner leaves the circle");
    }

    [Fact]
    public async Task A_place_radius_outside_the_range_is_refused()
    {
        var (alex, _) = await NewRider();
        var circle = await CreateCircle(alex, "Household");

        (await SharePlace(alex, circle, 7, "School", 0)).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
        (await SharePlace(alex, circle, 7, "School", DetourLimits.MaxPlaceRadiusMeters + 1)).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Only_the_owner_can_delete_a_shared_place()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        var circle = await CreateCircle(alex, "Household");
        (await Invite(alex, circle, blakeName)).EnsureSuccessStatusCode();
        (await Respond(blake, circle, true)).EnsureSuccessStatusCode();
        (await SharePlace(blake, circle, 7, "School", 200)).EnsureSuccessStatusCode();

        var placeId = (await Places(alex, circle))[0].GetProperty("id").GetGuid();

        (await alex.DeleteAsync($"/api/circle-places/{placeId}")).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
        (await blake.DeleteAsync($"/api/circle-places/{placeId}")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);
    }

    [Fact]
    public async Task A_presence_event_carries_the_place_name_and_the_callers_handle()
    {
        var (alex, alexName) = await NewRider();
        var circle = await CreateCircle(alex, "Household");
        (await SharePlace(alex, circle, 7, "School", 200)).EnsureSuccessStatusCode();

        var recorded = await (await alex.PostAsJsonAsync(
                $"/api/circles/{Id(circle)}/events",
                new { placeId = 7L, kind = "Arrive", timestampMs = 1_700_000_000_000L }))
            .Content.ReadFromJsonAsync<JsonElement>();

        recorded.GetProperty("placeName").GetString().Should().Be("School");
        recorded.GetProperty("username").GetString().Should().Be(alexName);
        recorded.GetProperty("kind").GetString().Should().Be("arrive");
    }

    [Fact]
    public async Task The_event_feed_includes_the_callers_own()
    {
        var (alex, _) = await NewRider();
        var circle = await CreateCircle(alex, "Household");
        await alex.PostAsJsonAsync($"/api/circles/{Id(circle)}/events",
            new { placeId = 7L, kind = "Arrive", timestampMs = 1_000L });
        await alex.PostAsJsonAsync($"/api/circles/{Id(circle)}/events",
            new { placeId = 7L, kind = "Depart", timestampMs = 2_000L });

        var all = await Events(alex, circle, since: 0);
        all.GetArrayLength().Should().Be(2);

        var recent = await Events(alex, circle, since: 1_000);
        recent.GetArrayLength().Should().Be(1);
        recent[0].GetProperty("kind").GetString().Should().Be("depart");
    }

    [Fact]
    public async Task An_unrecognised_event_kind_is_refused()
    {
        var (alex, _) = await NewRider();
        var circle = await CreateCircle(alex, "Household");

        var response = await alex.PostAsJsonAsync($"/api/circles/{Id(circle)}/events",
            new { placeId = 7L, kind = "Loitering", timestampMs = 1_000L });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task A_non_member_can_reach_nothing_about_a_circle()
    {
        var (alex, _) = await NewRider();
        var (stranger, _) = await NewRider();
        var circle = await CreateCircle(alex, "Household");

        (await stranger.GetAsync($"/api/circles/{Id(circle)}/positions")).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
        (await stranger.GetAsync($"/api/circles/{Id(circle)}/places")).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
        (await stranger.GetAsync($"/api/circles/{Id(circle)}/events?since=0")).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
        (await ReportPosition(stranger, circle, 51.05, 3.72)).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
    }

    private async Task<(HttpClient Client, string Username)> NewRider()
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", username, null, "detour-user"));
        (await client.GetAsync("/api/me")).EnsureSuccessStatusCode();
        return (client, username);
    }

    private static async Task Befriend(HttpClient a, string aName, HttpClient b, string bName)
    {
        (await a.PostAsJsonAsync("/api/friends/requests", new { username = bName }))
            .EnsureSuccessStatusCode();
        (await b.PostAsJsonAsync($"/api/friends/requests/{aName}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }

    private static async Task<JsonElement> CreateCircle(HttpClient client, string name)
    {
        var response = await client.PostAsJsonAsync("/api/circles", new { name });
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static async Task<JsonElement> CreateConvoy(HttpClient client, string name)
    {
        var response = await client.PostAsJsonAsync("/api/convoys", new { name });
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static Guid Id(JsonElement group) => group.GetProperty("id").GetGuid();

    private static Task<HttpResponseMessage> Invite(HttpClient client, JsonElement group, string username) =>
        Invite(client, Id(group), username);

    private static Task<HttpResponseMessage> Invite(HttpClient client, Guid groupId, string username) =>
        client.PostAsJsonAsync($"/api/groups/{groupId}/invitations", new { username });

    private static Task<HttpResponseMessage> Respond(HttpClient client, JsonElement group, bool accept) =>
        client.PostAsJsonAsync(
            $"/api/groups/{Id(group)}/invitations/respond", new { accept });

    private static async Task<JsonElement> Circles(HttpClient client) =>
        await (await client.GetAsync("/api/circles")).Content.ReadFromJsonAsync<JsonElement>();

    private static Task<HttpResponseMessage> ReportPosition(
        HttpClient client, JsonElement circle, double latitude, double longitude) =>
        ReportPosition(client, Id(circle), latitude, longitude);

    private static Task<HttpResponseMessage> ReportPosition(
        HttpClient client, Guid circleId, double latitude, double longitude) =>
        client.PostAsJsonAsync($"/api/circles/{circleId}/positions",
            new { latitude, longitude, accuracyMeters = 12.0, timestampMs = 1_700_000_000_000L });

    private static async Task<JsonElement> Positions(HttpClient client, JsonElement circle)
    {
        var response = await client.GetAsync($"/api/circles/{Id(circle)}/positions");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return body.GetProperty("fixes");
    }

    private static Task<HttpResponseMessage> SharePlace(
        HttpClient client, JsonElement circle, long placeId, string name, double radiusMeters) =>
        client.PostAsJsonAsync($"/api/circles/{Id(circle)}/places",
            new { place = new { id = placeId, name, radiusMeters, latitude = 51.0, longitude = 3.7 } });

    private static async Task<JsonElement> Places(HttpClient client, JsonElement circle)
    {
        var response = await client.GetAsync($"/api/circles/{Id(circle)}/places");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return body.GetProperty("places");
    }

    private static async Task<JsonElement> Events(HttpClient client, JsonElement circle, long since)
    {
        var response = await client.GetAsync(
            $"/api/circles/{Id(circle)}/events?since={since}");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return body.GetProperty("events");
    }
}
