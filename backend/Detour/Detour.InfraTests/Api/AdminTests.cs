using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// Administration, and the line it must not cross: an administrator manages accounts and can
/// never read what is in one.
/// </summary>
[Collection(PostgresCollection.Name)]
public class AdminTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_ordinary_rider_is_refused()
    {
        var (rider, _) = await NewRider();

        (await rider.GetAsync("/api/admin/accounts")).StatusCode
            .Should().Be(HttpStatusCode.Forbidden);
    }

    [Fact]
    public async Task An_administrator_sees_counts_and_never_content()
    {
        var (rider, riderName) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { new { startTimeMs = 1_000L, mode = "motorcycle", distanceMeters = 12_000.0 } },
            traces = new[] { "[[51.05,3.72,1000,50.0,12.5]]" },
            badges = new Dictionary<string, long> { ["dist_100000"] = 1_000 },
            stats = new { totalDistanceMeters = 12_000.0 },
        });

        var (admin, _) = await NewAdministrator();
        var overview = await (await admin.GetAsync("/api/admin/accounts"))
            .Content.ReadFromJsonAsync<JsonElement>();

        var account = overview.GetProperty("accounts").EnumerateArray()
            .Single(a => a.GetProperty("username").GetString() == riderName);

        account.GetProperty("tripCount").GetInt32().Should().Be(1);
        account.GetProperty("traceCount").GetInt32().Should().Be(1);
        account.GetProperty("badgeCount").GetInt32().Should().Be(1);
        account.GetProperty("totalDistanceKm").GetDouble().Should().Be(12);

        foreach (var forbidden in new[] { "trips", "traces", "routes", "places", "payload" })
            account.TryGetProperty(forbidden, out _).Should().BeFalse($"an administrator cannot read {forbidden}");
    }

    [Fact]
    public async Task Deleting_an_account_takes_everything_it_owns()
    {
        var (rider, riderName) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { new { startTimeMs = 1_000L } },
            traces = new[] { "[[51.05,3.72,1000,50.0,12.5]]" },
            savedPlaces = new[] { new { id = 7L, name = "Home" } },
            badges = new Dictionary<string, long> { ["dist_100000"] = 1_000 },
        });
        await rider.PostAsJsonAsync("/api/me/api-keys", new { label = "dashboard" });

        var riderId = await IdOf(riderName);

        var (admin, _) = await NewAdministrator();
        (await admin.DeleteAsync($"/api/admin/accounts/{riderId}")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        await using var db = postgres.CreateContext();
        (await db.Users.AnyAsync(u => u.Id == riderId)).Should().BeFalse();
        (await db.Trips.AnyAsync(t => t.UserId == riderId)).Should().BeFalse();
        (await db.Traces.AnyAsync(t => t.UserId == riderId)).Should().BeFalse();
        (await db.TrackPoints.AnyAsync(p => p.UserId == riderId)).Should().BeFalse();
        (await db.SavedPlaces.AnyAsync(p => p.UserId == riderId)).Should().BeFalse();
        (await db.BadgeAwards.AnyAsync(b => b.UserId == riderId)).Should().BeFalse();
        (await db.ApiKeys.AnyAsync(k => k.UserId == riderId)).Should().BeFalse();
    }

    [Fact]
    public async Task An_administrator_cannot_delete_their_own_account()
    {
        // Refused rather than allowed to lock themselves out mid-operation.
        var (admin, adminName) = await NewAdministrator();
        var adminId = await IdOf(adminName);

        (await admin.DeleteAsync($"/api/admin/accounts/{adminId}")).StatusCode
            .Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Deleting_an_account_that_does_not_exist_is_a_not_found()
    {
        var (admin, _) = await NewAdministrator();

        (await admin.DeleteAsync($"/api/admin/accounts/{Guid.CreateVersion7()}")).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task Revoking_dashboard_keys_leaves_the_account_intact()
    {
        var (rider, riderName) = await NewRider();
        var issued = await (await rider.PostAsJsonAsync("/api/me/api-keys", new { label = "dashboard" }))
            .Content.ReadFromJsonAsync<JsonElement>();

        var dashboard = _factory.CreateClient();
        dashboard.DefaultRequestHeaders.Add("X-Api-Key", issued.GetProperty("key").GetString());
        (await dashboard.GetAsync("/api/dashboard/stats")).StatusCode.Should().Be(HttpStatusCode.OK);

        var (admin, _) = await NewAdministrator();
        (await admin.DeleteAsync($"/api/admin/accounts/{await IdOf(riderName)}/api-keys")).StatusCode
            .Should().Be(HttpStatusCode.NoContent);

        (await dashboard.GetAsync("/api/dashboard/stats")).StatusCode
            .Should().Be(HttpStatusCode.Unauthorized);
        (await rider.GetAsync("/api/me")).StatusCode
            .Should().Be(HttpStatusCode.OK, "revoking a dashboard key is not signing the rider out");
    }

    private Task<(HttpClient Client, string Username)> NewRider() => NewAccount("detour-user");

    private Task<(HttpClient Client, string Username)> NewAdministrator() =>
        NewAccount("detour-user", "detour-admin");

    private async Task<(HttpClient Client, string Username)> NewAccount(params string[] roles)
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", username, null, roles));
        (await client.GetAsync("/api/me")).EnsureSuccessStatusCode();
        return (client, username);
    }

    private async Task<Guid> IdOf(string username)
    {
        await using var db = postgres.CreateContext();
        return (await db.Users.SingleAsync(u => u.Username == username)).Id;
    }
}
