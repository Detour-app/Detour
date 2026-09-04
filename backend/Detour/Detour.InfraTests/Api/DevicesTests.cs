using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.Extensions.DependencyInjection;

namespace Detour.InfraTests.Api;

[Collection(PostgresCollection.Name)]
public class DevicesTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Registering_a_token_twice_is_idempotent()
    {
        var (rider, _) = await NewRider();

        (await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-1", platform = "android" }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);
        (await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-1", platform = "android" }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    [Fact]
    public async Task A_second_rider_registering_the_same_token_takes_it_over()
    {
        // The install was handed to a friend, or the same phone signed into a
        // different account. The token must point at exactly one rider.
        var (alex, _) = await NewRider();
        var (blake, _) = await NewRider();

        var blakeId = await MeId(blake);

        await alex.PutAsJsonAsync("/api/devices", new { token = "fcm-shared", platform = "ios" });
        (await blake.PutAsJsonAsync("/api/devices", new { token = "fcm-shared", platform = "ios" }))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);

        // Asserted via the DB: exactly one row, owned by blake.
        using var scope = _factory.Services.CreateScope();
        var repo = scope.ServiceProvider
            .GetRequiredService<Detour.Domain.Notifications.IDeviceTokenRepository>();
        var row = await repo.GetByTokenAsync("fcm-shared", default);
        row.Should().NotBeNull();
        row!.UserId.Should().Be(blakeId);
        (await repo.GetAllAsync(default)).Count(r => r.Token == "fcm-shared").Should().Be(1);
    }

    [Fact]
    public async Task An_unknown_platform_is_rejected()
    {
        var (rider, _) = await NewRider();

        (await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-2", platform = "blackberry" }))
            .StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Deleting_a_token_removes_it_and_is_quiet_about_one_that_was_never_there()
    {
        var (rider, _) = await NewRider();
        await rider.PutAsJsonAsync("/api/devices", new { token = "fcm-3", platform = "android" });

        var delete = new HttpRequestMessage(HttpMethod.Delete, "/api/devices")
        {
            Content = JsonContent.Create(new { token = "fcm-3" }),
        };
        (await rider.SendAsync(delete)).StatusCode.Should().Be(HttpStatusCode.NoContent);

        var deleteAgain = new HttpRequestMessage(HttpMethod.Delete, "/api/devices")
        {
            Content = JsonContent.Create(new { token = "fcm-never" }),
        };
        (await rider.SendAsync(deleteAgain)).StatusCode.Should().Be(HttpStatusCode.NoContent);
    }

    [Fact]
    public async Task Registration_requires_authentication()
    {
        var anon = _factory.CreateClient();
        (await anon.PutAsJsonAsync("/api/devices", new { token = "x", platform = "android" }))
            .StatusCode.Should().Be(HttpStatusCode.Unauthorized);
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
}
