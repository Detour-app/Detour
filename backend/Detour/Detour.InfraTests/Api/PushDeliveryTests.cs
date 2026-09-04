using System.Collections.Concurrent;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.Api.Notifications;
using Detour.Domain.Notifications;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;

namespace Detour.InfraTests.Api;

/// <summary>
/// End-to-end over the real API and a real Postgres: a circle member registers a
/// device token, another member records an arrival, and the wake-ping comes out
/// the far end of the whole pipeline — endpoint → DB → post-commit trigger →
/// queue → worker → dispatcher → the gateway for that token's platform. The only
/// stand-in is the cloud itself: a capturing <see cref="IPushGateway"/> in place
/// of a real FCM/APNs call, so this runs with no Firebase project and no phone.
/// </summary>
[Collection(PostgresCollection.Name)]
public class PushDeliveryTests(PostgresFixture postgres) : IAsyncLifetime
{
    private readonly CapturingGateway _fcm = new(DevicePlatform.Android);
    private DetourApiFactory _factory = null!;
    private WebApplicationFactory<Program> _web = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        // Same production host, with the push clouds swapped for a capturing gateway.
        _web = _factory.WithWebHostBuilder(builder =>
            builder.ConfigureTestServices(services =>
            {
                services.RemoveAll<IPushGateway>();
                services.AddSingleton<IPushGateway>(_fcm);
            }));
        return Task.CompletedTask;
    }

    public async Task DisposeAsync()
    {
        await _web.DisposeAsync();
        await _factory.DisposeAsync();
    }

    [Fact]
    public async Task An_arrival_wakes_a_registered_circle_member()
    {
        var alex = await NewRider();
        var blake = await NewRider();
        await Befriend(alex, blake);

        var circle = await CreateCircle(alex, "Household");
        (await alex.Client.PostAsJsonAsync($"/api/groups/{Id(circle)}/invitations", new { username = blake.Username }))
            .EnsureSuccessStatusCode();
        (await blake.Client.PostAsJsonAsync($"/api/groups/{Id(circle)}/invitations/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        // Blake registers his phone's push token. Neither member is holding a live
        // relay socket, so an arrival must reach Blake by push.
        (await blake.Client.PutAsJsonAsync("/api/devices", new { token = "blake-fcm-token", platform = "android" }))
            .EnsureSuccessStatusCode();

        // Alex arrives at a shared place.
        (await alex.Client.PostAsJsonAsync($"/api/circles/{Id(circle)}/events",
            new { placeId = 7L, kind = "Arrive", timestampMs = 1_700_000_000_000L }))
            .EnsureSuccessStatusCode();

        var wake = await _fcm.WaitForFirst();
        wake.Tokens.Should().ContainSingle().Which.Should().Be("blake-fcm-token");
        wake.CollapseKey.Should().Be(Id(circle).ToString());
    }

    [Fact]
    public async Task The_mover_is_not_woken_about_their_own_arrival()
    {
        var alex = await NewRider();
        var blake = await NewRider();
        await Befriend(alex, blake);

        var circle = await CreateCircle(alex, "Household");
        (await alex.Client.PostAsJsonAsync($"/api/groups/{Id(circle)}/invitations", new { username = blake.Username }))
            .EnsureSuccessStatusCode();
        (await blake.Client.PostAsJsonAsync($"/api/groups/{Id(circle)}/invitations/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        // Both register a token; Alex is the one who moves.
        (await alex.Client.PutAsJsonAsync("/api/devices", new { token = "alex-fcm-token", platform = "android" }))
            .EnsureSuccessStatusCode();
        (await blake.Client.PutAsJsonAsync("/api/devices", new { token = "blake-fcm-token", platform = "android" }))
            .EnsureSuccessStatusCode();

        (await alex.Client.PostAsJsonAsync($"/api/circles/{Id(circle)}/events",
            new { placeId = 7L, kind = "Arrive", timestampMs = 1_700_000_000_000L }))
            .EnsureSuccessStatusCode();

        var wake = await _fcm.WaitForFirst();
        wake.Tokens.Should().ContainSingle().Which.Should().Be("blake-fcm-token");
        wake.Tokens.Should().NotContain("alex-fcm-token");
    }

    private async Task<Rider> NewRider()
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = _web.CreateClient();
        client.DefaultRequestHeaders.Authorization = new("Bearer", _factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", username, null, "detour-user"));
        // /api/me provisions the account and hands back its id — relationships key
        // on that account id now, not the handle (#133).
        var me = await client.GetFromJsonAsync<JsonElement>("/api/me");
        return new Rider(client, username, me.GetProperty("id").GetGuid());
    }

    private static async Task Befriend(Rider a, Rider b)
    {
        (await a.Client.PostAsJsonAsync("/api/friends/requests", new { username = b.Username }))
            .EnsureSuccessStatusCode();
        // Respond is addressed by the requester's account id, not their username.
        (await b.Client.PostAsJsonAsync($"/api/friends/requests/{a.UserId}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();
    }

    private static async Task<JsonElement> CreateCircle(Rider rider, string name)
    {
        var response = await rider.Client.PostAsJsonAsync("/api/circles", new { name });
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private static Guid Id(JsonElement group) => group.GetProperty("id").GetGuid();

    private sealed record Rider(HttpClient Client, string Username, Guid UserId);

    private sealed record Wake(IReadOnlyList<string> Tokens, string CollapseKey);

    private sealed class CapturingGateway(DevicePlatform platform) : IPushGateway
    {
        private readonly ConcurrentQueue<Wake> _wakes = new();

        public DevicePlatform Platform { get; } = platform;

        public Task<PushSendResult> SendWakeAsync(
            IReadOnlyCollection<string> tokens, string collapseKey, CancellationToken ct)
        {
            _wakes.Enqueue(new Wake([.. tokens], collapseKey));
            return Task.FromResult(new PushSendResult(
                [.. tokens.Select(t => new PushTokenOutcome(t, Delivered: true, ShouldPrune: false))]));
        }

        /// <summary>Dispatch is async (post-commit → queue → worker), so poll briefly.</summary>
        public async Task<Wake> WaitForFirst()
        {
            for (var attempt = 0; attempt < 50; attempt++)
            {
                if (_wakes.TryPeek(out var wake))
                    return wake;
                await Task.Delay(100);
            }

            throw new Xunit.Sdk.XunitException("No wake-ping was dispatched within 5s.");
        }
    }
}
