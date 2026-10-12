using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.Database;
using Detour.Domain;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Shared.Database;

namespace Detour.InfraTests.Api;

/// <summary>
/// Joining a convoy by invite link (#641): the one way into a group that does not start from a
/// friendship, so every test here is about how narrow that door is.
/// </summary>
[Collection(PostgresCollection.Name)]
public class ConvoyInviteTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task A_stranger_with_the_link_joins_as_an_accepted_member_without_becoming_a_friend()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);

        invite.GetProperty("token").GetString().Should().NotBeNullOrWhiteSpace();
        invite.GetProperty("expiresAtMs").GetInt64()
            .Should().BeCloseTo(
                DateTimeOffset.UtcNow.Add(DetourLimits.ConvoyInviteLifetime).ToUnixTimeMilliseconds(),
                60_000);

        var response = await Join(guest, invite);

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var joined = await response.Content.ReadFromJsonAsync<JsonElement>();
        joined.GetProperty("id").GetGuid().Should().Be(convoy);
        joined.GetProperty("status").GetString().Should().Be("accepted");
        (await ConvoyIds(guest)).Should().Contain(convoy);

        var friends = await (await guest.GetAsync("/api/friends")).Content.ReadAsStringAsync();
        friends.Should().NotContain(host.Username);
    }

    [Fact]
    public async Task Joining_twice_with_the_same_link_is_harmless()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);

        (await Join(guest, invite)).StatusCode.Should().Be(HttpStatusCode.OK);
        (await Join(guest, invite)).StatusCode.Should().Be(HttpStatusCode.OK);

        var guests = await Guests(host, convoy);
        guests.GetProperty("guests").GetArrayLength().Should().Be(1);
    }

    [Fact]
    public async Task An_unknown_token_is_not_found()
    {
        var rider = await _factory.SignInAsync();

        var response = await rider.PostAsJsonAsync("/api/convoys/join", new { token = "not-a-real-token" });

        response.StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task An_expired_link_is_refused()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);

        using (var scope = _factory.Services.CreateScope())
        await using (var db = scope.ServiceProvider
                         .GetRequiredService<ICustomDbContextFactory<DetourDbContext>>().CreateNew())
        {
            await db.Set<Detour.Domain.Groups.ConvoyInvite>()
                .Where(i => i.GroupId == convoy)
                .ExecuteUpdateAsync(s => s.SetProperty(i => i.ExpiresAt, DateTimeOffset.UtcNow.AddMinutes(-1)));
        }

        (await Join(guest, invite)).StatusCode.Should().Be(HttpStatusCode.Gone);
        (await ConvoyIds(guest)).Should().NotContain(convoy);
    }

    [Fact]
    public async Task A_revoked_link_is_refused_but_guests_already_in_stay()
    {
        var host = await _factory.SignInAsync();
        var early = await _factory.SignInAsync();
        var late = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);
        (await Join(early, invite)).EnsureSuccessStatusCode();

        (await host.DeleteAsync($"/api/convoys/{convoy}/invite-link")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        (await Join(late, invite)).StatusCode.Should().Be(HttpStatusCode.Gone);
        (await ConvoyIds(early)).Should().Contain(convoy);
        (await Guests(host, convoy)).GetProperty("inviteExpiresAtMs").ValueKind
            .Should().Be(JsonValueKind.Null);
    }

    [Fact]
    public async Task Minting_a_new_link_revokes_the_old_one()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var first = await CreateLink(host, convoy);
        var second = await CreateLink(host, convoy);

        (await Join(guest, first)).StatusCode.Should().Be(HttpStatusCode.Gone);
        (await Join(guest, second)).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task A_removed_guest_is_out_and_cannot_rejoin_with_the_same_link()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);
        (await Join(guest, invite)).EnsureSuccessStatusCode();

        var listed = (await Guests(host, convoy)).GetProperty("guests");
        listed.GetArrayLength().Should().Be(1);
        listed[0].GetProperty("id").GetGuid().Should().Be(guest.UserId);
        listed[0].GetProperty("username").GetString().Should().Be(guest.Username);

        (await host.DeleteAsync($"/api/convoys/{convoy}/guests/{guest.UserId}")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        (await ConvoyIds(guest)).Should().NotContain(convoy);
        (await Guests(host, convoy)).GetProperty("guests").GetArrayLength().Should().Be(0);
        (await Join(guest, invite)).StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await ConvoyIds(guest)).Should().NotContain(convoy);
    }

    [Fact]
    public async Task A_removed_guest_cannot_rejoin_with_a_fresh_link_either()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        (await Join(guest, await CreateLink(host, convoy))).EnsureSuccessStatusCode();
        (await host.DeleteAsync($"/api/convoys/{convoy}/guests/{guest.UserId}")).EnsureSuccessStatusCode();

        var fresh = await CreateLink(host, convoy);

        (await Join(guest, fresh)).StatusCode.Should().Be(HttpStatusCode.Forbidden);
        (await ConvoyIds(guest)).Should().NotContain(convoy);
        (await Join(await _factory.SignInAsync(), fresh)).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task A_guest_who_left_on_their_own_can_come_back()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);
        (await Join(guest, invite)).EnsureSuccessStatusCode();

        (await guest.DeleteAsync($"/api/groups/{convoy}/membership")).EnsureSuccessStatusCode();

        (await Join(guest, invite)).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task Only_link_guests_can_be_removed_through_the_guest_route()
    {
        var host = await _factory.SignInAsync();
        var friend = await _factory.SignInAsync();
        await Befriend(host, friend);
        var convoy = await CreateConvoy(host);
        (await host.PostAsJsonAsync($"/api/groups/{convoy}/invitations", new { username = friend.Username }))
            .EnsureSuccessStatusCode();

        (await host.DeleteAsync($"/api/convoys/{convoy}/guests/{friend.UserId}")).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
        (await host.DeleteAsync($"/api/convoys/{convoy}/guests/{host.UserId}")).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task A_member_who_is_not_the_host_cannot_create_revoke_list_or_remove()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);
        (await Join(guest, invite)).EnsureSuccessStatusCode();

        (await guest.PostAsJsonAsync($"/api/convoys/{convoy}/invite-link", new { })).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);
        (await guest.DeleteAsync($"/api/convoys/{convoy}/invite-link")).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);
        (await guest.GetAsync($"/api/convoys/{convoy}/guests")).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);
        (await guest.DeleteAsync($"/api/convoys/{convoy}/guests/{guest.UserId}")).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);

        // The link the host minted still works: the refused revoke changed nothing.
        var other = await _factory.SignInAsync();
        (await Join(other, invite)).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task A_non_member_cannot_tell_a_convoy_apart_from_one_that_does_not_exist()
    {
        var host = await _factory.SignInAsync();
        var stranger = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);

        (await stranger.PostAsJsonAsync($"/api/convoys/{convoy}/invite-link", new { })).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
        (await stranger.PostAsJsonAsync($"/api/convoys/{Guid.NewGuid()}/invite-link", new { })).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task A_circle_has_no_invite_link()
    {
        var owner = await _factory.SignInAsync();
        var response = await owner.PostAsJsonAsync("/api/circles", new { name = "Household" });
        var circle = (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();

        (await owner.PostAsJsonAsync($"/api/convoys/{circle}/invite-link", new { })).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task The_link_dies_with_its_convoy()
    {
        var host = await _factory.SignInAsync();
        var guest = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);

        // The host was the only member, so leaving deletes the convoy.
        (await host.DeleteAsync($"/api/groups/{convoy}/membership")).EnsureSuccessStatusCode();

        (await Join(guest, invite)).StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task The_host_leaving_hands_the_convoy_to_the_longest_standing_rider()
    {
        var host = await _factory.SignInAsync();
        var first = await _factory.SignInAsync();
        var second = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);
        (await Join(first, invite)).EnsureSuccessStatusCode();
        (await Join(second, invite)).EnsureSuccessStatusCode();

        (await host.DeleteAsync($"/api/groups/{convoy}/membership")).EnsureSuccessStatusCode();

        (await OwnerOf(second, convoy)).Should().Be(first.UserId);
        (await second.PostAsJsonAsync($"/api/convoys/{convoy}/invite-link", new { })).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);
        var fresh = await CreateLink(first, convoy);
        (await Guests(first, convoy)).GetProperty("guests").GetArrayLength().Should().Be(1);
        (await Join(await _factory.SignInAsync(), fresh)).StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task A_host_who_left_only_an_invitee_behind_is_replaced_when_they_accept()
    {
        var host = await _factory.SignInAsync();
        var friend = await _factory.SignInAsync();
        await Befriend(host, friend);
        var convoy = await CreateConvoy(host);
        (await host.PostAsJsonAsync($"/api/groups/{convoy}/invitations", new { username = friend.Username }))
            .EnsureSuccessStatusCode();

        (await host.DeleteAsync($"/api/groups/{convoy}/membership")).EnsureSuccessStatusCode();
        (await friend.PostAsJsonAsync($"/api/groups/{convoy}/invitations/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        (await OwnerOf(friend, convoy)).Should().Be(friend.UserId);
        (await friend.PostAsJsonAsync($"/api/convoys/{convoy}/invite-link", new { })).StatusCode
            .Should().Be(HttpStatusCode.Created);
    }

    [Fact]
    public async Task A_host_who_left_only_an_invitee_behind_is_replaced_by_the_next_link_guest()
    {
        var host = await _factory.SignInAsync();
        var friend = await _factory.SignInAsync();
        await Befriend(host, friend);
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);
        (await host.PostAsJsonAsync($"/api/groups/{convoy}/invitations", new { username = friend.Username }))
            .EnsureSuccessStatusCode();

        (await host.DeleteAsync($"/api/groups/{convoy}/membership")).EnsureSuccessStatusCode();
        var stranger = await _factory.SignInAsync();
        (await Join(stranger, invite)).EnsureSuccessStatusCode();

        (await OwnerOf(stranger, convoy)).Should().Be(stranger.UserId);
        (await stranger.PostAsJsonAsync($"/api/convoys/{convoy}/invite-link", new { })).StatusCode
            .Should().Be(HttpStatusCode.Created);
    }

    [Fact]
    public async Task A_convoy_takes_at_most_eight_guests_and_a_removal_frees_a_seat()
    {
        var host = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);

        var guests = new List<SignedInClient>();
        for (var i = 0; i < DetourLimits.MaxConvoyGuests; i++)
        {
            var guest = await _factory.SignInAsync();
            (await Join(guest, invite)).EnsureSuccessStatusCode();
            guests.Add(guest);
        }

        var ninth = await _factory.SignInAsync();
        (await Join(ninth, invite)).StatusCode.Should().Be(HttpStatusCode.BadRequest);
        (await ConvoyIds(ninth)).Should().NotContain(convoy);

        // A fresh link does not reset the count: the cap is per convoy, not per link.
        var second = await CreateLink(host, convoy);
        (await Join(ninth, second)).StatusCode.Should().Be(HttpStatusCode.BadRequest);

        (await host.DeleteAsync($"/api/convoys/{convoy}/guests/{guests[0].UserId}")).EnsureSuccessStatusCode();
        (await Join(ninth, second)).EnsureSuccessStatusCode();
        (await ConvoyIds(ninth)).Should().Contain(convoy);
    }

    [Fact]
    public async Task Repeated_join_attempts_on_one_link_trip_the_limit()
    {
        var host = await _factory.SignInAsync();
        var rider = await _factory.SignInAsync();
        var convoy = await CreateConvoy(host);
        var invite = await CreateLink(host, convoy);
        (await host.DeleteAsync($"/api/convoys/{convoy}/invite-link")).EnsureSuccessStatusCode();

        // Every attempt counts, including the refused ones — that is what makes it a cap on
        // trying rather than on succeeding.
        var allowed = Math.Min(DetourLimits.ConvoyJoinsPerToken, DetourLimits.ConvoyJoinsPerIp);
        for (var i = 0; i < allowed; i++)
            (await Join(rider, invite)).StatusCode.Should().Be(HttpStatusCode.Gone);

        (await Join(rider, invite)).StatusCode.Should().Be(HttpStatusCode.TooManyRequests);
    }

    [Fact]
    public async Task Guessing_many_tokens_from_one_address_trips_the_limit()
    {
        var rider = await _factory.SignInAsync();

        for (var i = 0; i < DetourLimits.ConvoyJoinsPerIp; i++)
        {
            (await rider.PostAsJsonAsync("/api/convoys/join", new { token = $"guess-{i}" })).StatusCode
                .Should().Be(HttpStatusCode.NotFound);
        }

        (await rider.PostAsJsonAsync("/api/convoys/join", new { token = "guess-last" })).StatusCode
            .Should().Be(HttpStatusCode.TooManyRequests);
    }

    private static async Task Befriend(SignedInClient a, SignedInClient b)
    {
        (await a.PostAsJsonAsync("/api/friends/requests", new { username = b.Username }))
            .EnsureSuccessStatusCode();
        (await b.PostAsJsonAsync($"/api/friends/requests/{a.UserId}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }

    private static async Task<Guid> CreateConvoy(SignedInClient client)
    {
        var response = await client.PostAsJsonAsync("/api/convoys", new { name = "Sunday ride" });
        response.EnsureSuccessStatusCode();
        return (await response.Content.ReadFromJsonAsync<JsonElement>()).GetProperty("id").GetGuid();
    }

    private static async Task<JsonElement> CreateLink(SignedInClient host, Guid convoy)
    {
        var response = await host.PostAsJsonAsync($"/api/convoys/{convoy}/invite-link", new { });
        response.StatusCode.Should().Be(HttpStatusCode.Created);
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static Task<HttpResponseMessage> Join(SignedInClient client, JsonElement invite) =>
        client.PostAsJsonAsync("/api/convoys/join", new { token = invite.GetProperty("token").GetString() });

    private static async Task<JsonElement> Guests(SignedInClient host, Guid convoy)
    {
        var response = await host.GetAsync($"/api/convoys/{convoy}/guests");
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static async Task<Guid> OwnerOf(SignedInClient client, Guid convoy)
    {
        var convoys = await client.GetFromJsonAsync<JsonElement>("/api/convoys");
        return convoys.EnumerateArray()
            .Single(c => c.GetProperty("id").GetGuid() == convoy)
            .GetProperty("ownerId").GetGuid();
    }

    private static async Task<List<Guid>> ConvoyIds(SignedInClient client)
    {
        var convoys = await client.GetFromJsonAsync<JsonElement>("/api/convoys");
        return [.. convoys.EnumerateArray().Select(c => c.GetProperty("id").GetGuid())];
    }
}
