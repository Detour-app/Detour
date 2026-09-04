using System.Net.Http.Json;
using System.Text.Json;
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

    private CapturingGateway _gateway = null!;
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _gateway = new CapturingGateway();
        _factory = new DetourApiFactory(postgres, services =>
            services.AddSingleton<IFcmGateway>(_gateway));
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
