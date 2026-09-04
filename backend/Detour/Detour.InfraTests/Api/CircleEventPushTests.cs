using System.Net.Http.Json;
using System.Text.Json;
using Detour.Api.Live;
using Detour.Api.Notifications;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class CircleEventPushTests(PostgresFixture postgres) : IAsyncLifetime
{
    private sealed class CapturingGateway : IFcmGateway
    {
        public readonly TaskCompletionSource<IReadOnlyCollection<string>> FirstSend = new();
        public List<string> AllTokens { get; } = [];

        public Task<FcmSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)
        {
            AllTokens.AddRange(tokens);
            FirstSend.TrySetResult(tokens);
            return Task.FromResult(FcmSendResult.Empty);
        }
    }

    /// <summary>
    /// Stands in for the real relay so a member can be marked "holding a live
    /// socket" without opening one. Only <see cref="ConnectedUserIds"/> matters
    /// here — <c>CircleService</c> reads it to decide who still needs a push.
    /// </summary>
    private sealed class FakeRelay : ILiveRelay
    {
        public readonly HashSet<Guid> Connected = [];

        public IReadOnlyCollection<Guid> ConnectedUserIds => [.. Connected];

        public void PublishPlaceEvent(
            IEnumerable<Guid> recipientUserIds, Guid groupId, string username,
            long placeId, string placeName, string kind, long timestampMs)
        { }

        public Task EvictAsync(Guid userId, Guid groupId, CancellationToken ct) => Task.CompletedTask;
    }

    private CapturingGateway _gateway = null!;
    private FakeRelay _relay = null!;
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _gateway = new CapturingGateway();
        _relay = new FakeRelay();
        _factory = new DetourApiFactory(postgres, services =>
        {
            services.AddSingleton<IFcmGateway>(_gateway);
            services.AddSingleton<ILiveRelay>(_relay);
        });
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Recording_an_event_wakes_an_accepted_member_who_is_offline()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);

        var circle = await CreateCircle(alex, "Household");
        await Invite(alex, circle, blakeName);
        await Respond(blake, circle, accept: true);

        // Blake has an app install, but no live socket.
        await blake.PutAsJsonAsync("/api/devices", new { token = "fcm-blake", platform = "android" });

        // Alex crosses a geofence.
        await alex.PostAsJsonAsync($"/api/circles/{circle}/events",
            new { placeId = 1L, kind = "arrive", timestampMs = 1_000L });

        var woken = await _gateway.FirstSend.Task.WaitAsync(TimeSpan.FromSeconds(5));
        woken.Should().ContainSingle().Which.Should().Be("fcm-blake");
    }

    [Fact]
    public async Task The_mover_is_never_woken_by_their_own_event()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        var circle = await CreateCircle(alex, "Household");
        await Invite(alex, circle, blakeName);
        await Respond(blake, circle, accept: true);

        await alex.PutAsJsonAsync("/api/devices", new { token = "fcm-alex", platform = "android" });
        await blake.PutAsJsonAsync("/api/devices", new { token = "fcm-blake", platform = "android" });

        await alex.PostAsJsonAsync($"/api/circles/{circle}/events",
            new { placeId = 1L, kind = "arrive", timestampMs = 1_000L });

        await _gateway.FirstSend.Task.WaitAsync(TimeSpan.FromSeconds(5));
        _gateway.AllTokens.Should().NotContain("fcm-alex");
    }

    [Fact]
    public async Task A_connected_member_is_not_pushed_even_though_they_hold_a_device_token()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var (cass, cassName) = await NewRider();
        await Befriend(alex, alexName, blake, blakeName);
        await Befriend(alex, alexName, cass, cassName);

        var circle = await CreateCircle(alex, "Household");
        await Invite(alex, circle, blakeName);
        await Respond(blake, circle, accept: true);
        await Invite(alex, circle, cassName);
        await Respond(cass, circle, accept: true);

        // Both have an install. Blake is also holding a live relay socket; cass is not.
        await blake.PutAsJsonAsync("/api/devices", new { token = "fcm-blake", platform = "android" });
        await cass.PutAsJsonAsync("/api/devices", new { token = "fcm-cass", platform = "android" });
        _relay.Connected.Add(await MeId(blake));

        await alex.PostAsJsonAsync($"/api/circles/{circle}/events",
            new { placeId = 1L, kind = "arrive", timestampMs = 1_000L });

        // The push fires — for the offline member — and the connected one is left out
        // of it entirely: he already got the live place_event frame.
        var woken = await _gateway.FirstSend.Task.WaitAsync(TimeSpan.FromSeconds(5));
        woken.Should().Contain("fcm-cass");
        _gateway.AllTokens.Should().NotContain("fcm-blake");
    }

    private static async Task<Guid> MeId(HttpClient client)
    {
        var body = await (await client.GetAsync("/api/me")).Content.ReadFromJsonAsync<JsonElement>();
        return body.GetProperty("id").GetGuid();
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

    private static async Task<Guid> CreateCircle(HttpClient client, string name)
    {
        var response = await client.PostAsJsonAsync("/api/circles", new { name });
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadFromJsonAsync<JsonElement>();
        return body.GetProperty("id").GetGuid();
    }

    private static Task<HttpResponseMessage> Invite(HttpClient client, Guid groupId, string username) =>
        client.PostAsJsonAsync($"/api/groups/{groupId}/invitations", new { username });

    private static Task<HttpResponseMessage> Respond(HttpClient client, Guid groupId, bool accept) =>
        client.PostAsJsonAsync($"/api/groups/{groupId}/invitations/respond", new { accept });
}
