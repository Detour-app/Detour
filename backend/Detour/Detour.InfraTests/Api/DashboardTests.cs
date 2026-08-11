using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;

namespace Detour.InfraTests.Api;

/// <summary>
/// The read-only dashboard surface, and the credential that reaches it. The promise under test
/// is narrow and total: a key reads its own owner's data and nothing else.
/// </summary>
[Collection(PostgresCollection.Name)]
public class DashboardTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task A_dashboard_key_is_shown_once_and_never_again()
    {
        var (rider, _) = await NewRider();

        var issued = await IssueKey(rider);
        issued.GetProperty("key").GetString().Should().NotBeNullOrWhiteSpace();

        var listed = await (await rider.GetAsync("/api/me/api-keys"))
            .Content.ReadFromJsonAsync<JsonElement>();

        var entry = listed.GetProperty("keys")[0];
        entry.TryGetProperty("key", out _).Should().BeFalse("the plaintext is unrecoverable by design");
        entry.GetProperty("id").GetGuid().Should().Be(issued.GetProperty("id").GetGuid());
    }

    [Fact]
    public async Task A_rider_session_cannot_reach_the_dashboard()
    {
        // The dashboard policy accepts the key scheme only, so "this credential can only read"
        // is a property of how the request authenticated rather than a per-handler check.
        var (rider, _) = await NewRider();

        (await rider.GetAsync("/api/dashboard/stats")).StatusCode
            .Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task An_unknown_key_is_refused()
    {
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Api-Key", "not-a-real-key");

        (await client.GetAsync("/api/dashboard/stats")).StatusCode
            .Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task A_revoked_key_stops_working_immediately()
    {
        var (rider, _) = await NewRider();
        var issued = await IssueKey(rider);
        var dashboard = KeyClient(issued.GetProperty("key").GetString()!);

        (await dashboard.GetAsync("/api/dashboard/stats")).StatusCode.Should().Be(HttpStatusCode.OK);

        (await rider.DeleteAsync($"/api/me/api-keys/{issued.GetProperty("id").GetGuid()}"))
            .StatusCode.Should().Be(HttpStatusCode.NoContent);

        (await dashboard.GetAsync("/api/dashboard/stats")).StatusCode
            .Should().Be(HttpStatusCode.Unauthorized);
    }

    [Fact]
    public async Task Another_riders_key_cannot_be_revoked()
    {
        var (alex, _) = await NewRider();
        var (blake, _) = await NewRider();
        var issued = await IssueKey(alex);

        (await blake.DeleteAsync($"/api/me/api-keys/{issued.GetProperty("id").GetGuid()}"))
            .StatusCode.Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task The_key_works_as_a_query_parameter_too()
    {
        // An embedded dashboard frame cannot set a header, which is exactly why these
        // credentials can only ever read.
        var (rider, _) = await NewRider();
        var key = (await IssueKey(rider)).GetProperty("key").GetString()!;

        var response = await _factory.CreateClient()
            .GetAsync($"/api/dashboard/stats?key={Uri.EscapeDataString(key)}");

        response.StatusCode.Should().Be(HttpStatusCode.OK);
    }

    [Fact]
    public async Task Stats_correct_the_ride_count_and_leave_an_unmeasured_lean_null()
    {
        var (rider, _) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { Trip(1_000), Trip(2_000) },
            // The device claims one ride; the backend holds two and says so.
            stats = new { totalDistanceMeters = 5_000.0, tripCount = 1 },
        });

        var dashboard = await DashboardClient(rider);
        var stats = await (await dashboard.GetAsync("/api/dashboard/stats"))
            .Content.ReadFromJsonAsync<JsonElement>();

        stats.GetProperty("rideCount").GetInt32().Should().Be(2);
        stats.GetProperty("stats").GetProperty("tripCount").GetInt32().Should().Be(2);
        stats.GetProperty("stats").GetProperty("maxLeanDegrees").ValueKind
            .Should().Be(JsonValueKind.Null, "never measured is not rode upright");
    }

    [Fact]
    public async Task The_badge_catalogue_scores_every_badge_earned_or_not()
    {
        var (rider, _) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            stats = new { totalDistanceMeters = 250_000.0 },
            badges = new Dictionary<string, long> { ["dist_100000"] = 1_700_000_000_000 },
        });

        var dashboard = await DashboardClient(rider);
        var stats = await (await dashboard.GetAsync("/api/dashboard/stats"))
            .Content.ReadFromJsonAsync<JsonElement>();

        var catalogue = stats.GetProperty("badgeCatalogue").EnumerateArray().ToList();
        catalogue.Should().HaveCountGreaterThan(15);

        var earned = catalogue.Single(b => b.GetProperty("id").GetString() == "dist_100000");
        earned.GetProperty("earnedAtMs").GetInt64().Should().Be(1_700_000_000_000);
        earned.GetProperty("progressPercent").GetDouble().Should().Be(100);

        var next = catalogue.Single(b => b.GetProperty("id").GetString() == "dist_500000");
        next.GetProperty("earnedAtMs").ValueKind.Should().Be(JsonValueKind.Null);
        next.GetProperty("progressPercent").GetDouble().Should().Be(50);
    }

    [Fact]
    public async Task A_ride_reports_the_peaks_its_points_actually_hold()
    {
        var (rider, _) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { Trip(1_000, endMs: 5_000) },
            traces = new[] { "[[51.05,3.72,2000,50.0,12.5],[51.06,3.73,3000,88.0,-42.0]]" },
        });

        var dashboard = await DashboardClient(rider);
        var rides = await (await dashboard.GetAsync("/api/dashboard/rides"))
            .Content.ReadFromJsonAsync<JsonElement>();

        var ride = rides.GetProperty("rides")[0];
        ride.GetProperty("pointCount").GetInt32().Should().Be(2);
        // Absolute value: a lean is signed, and the deepest one is the deepest either way.
        ride.GetProperty("maxLeanDegrees").GetDouble().Should().Be(42);
    }

    [Fact]
    public async Task An_unended_ride_does_not_swallow_the_one_after_it()
    {
        // The window fallback is capped at the next ride's start, or an open ride takes the
        // next ride's peaks with it.
        var (rider, _) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { Trip(1_000, endMs: null), Trip(10_000, endMs: 20_000) },
            traces = new[] { "[[51.05,3.72,2000,50.0,10.0],[51.06,3.73,15000,88.0,55.0]]" },
        });

        var dashboard = await DashboardClient(rider);
        var rides = await (await dashboard.GetAsync("/api/dashboard/rides"))
            .Content.ReadFromJsonAsync<JsonElement>();

        var byStart = rides.GetProperty("rides").EnumerateArray()
            .ToDictionary(r => r.GetProperty("startMs").GetInt64());

        byStart[1_000].GetProperty("pointCount").GetInt32().Should().Be(1);
        byStart[10_000].GetProperty("pointCount").GetInt32().Should().Be(1);
    }

    [Fact]
    public async Task The_track_endpoint_defaults_to_the_newest_ride()
    {
        var (rider, _) = await NewRider();
        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { Trip(1_000, endMs: 5_000), Trip(10_000, endMs: 20_000) },
            traces = new[] { "[[51.05,3.72,12000,50.0,10.0],[51.06,3.73,15000,88.0,55.0]]" },
        });

        var dashboard = await DashboardClient(rider);
        var track = await (await dashboard.GetAsync("/api/dashboard/rides/track"))
            .Content.ReadFromJsonAsync<JsonElement>();

        track.GetProperty("startMs").GetInt64().Should().Be(10_000);
        track.GetProperty("coordinates").GetArrayLength().Should().Be(2);
        track.GetProperty("bounds").GetProperty("centreLatitude").GetDouble().Should().BeApproximately(51.055, 0.001);
    }

    [Fact]
    public async Task A_track_is_thinned_to_the_point_budget_but_keeps_the_raw_peaks()
    {
        var (rider, _) = await NewRider();
        var line = "[" + string.Join(",", Enumerable.Range(0, 400)
            .Select(i => $"[{51.0 + (i * 0.001)},{3.7 + (i * 0.001)},{2000 + i},{40 + (i % 50)},{i % 60}]")) + "]";

        await rider.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { Trip(1_000, endMs: 500_000) },
            traces = new[] { line },
        });

        var dashboard = await DashboardClient(rider);
        var track = await (await dashboard.GetAsync("/api/dashboard/rides/track?max=20&tolerance=1"))
            .Content.ReadFromJsonAsync<JsonElement>();

        track.GetProperty("pointCount").GetInt32().Should().Be(400);
        track.GetProperty("usedPoints").GetInt32().Should().BeLessThanOrEqualTo(20);
        // Read off the raw track, not off what survived thinning.
        track.GetProperty("maxLeanDegrees").GetDouble().Should().Be(59);
    }

    [Fact]
    public async Task A_named_ride_that_does_not_exist_is_a_not_found()
    {
        var (rider, _) = await NewRider();
        var dashboard = await DashboardClient(rider);

        (await dashboard.GetAsync("/api/dashboard/rides/track?start=999")).StatusCode
            .Should().Be(HttpStatusCode.NotFound);
    }

    [Fact]
    public async Task Traces_and_coverage_return_only_the_owners_lines()
    {
        var (alex, _) = await NewRider();
        var (blake, _) = await NewRider();

        await alex.PostAsJsonAsync("/api/sync", new
        {
            traces = new[] { "[[51.05,3.72,1000,50.0,10.0],[51.06,3.73,2000,55.0,12.0]]" },
            shareFog = true,
        });
        await blake.PostAsJsonAsync("/api/sync", new
        {
            traces = new[] { "[[40.05,2.72,1000,50.0,10.0],[40.06,2.73,2000,55.0,12.0]]" },
            shareFog = true,
        });

        var dashboard = await DashboardClient(alex);

        var traces = await (await dashboard.GetAsync("/api/dashboard/traces"))
            .Content.ReadFromJsonAsync<JsonElement>();
        traces.GetProperty("traces").GetArrayLength().Should().Be(1);

        var coverage = await (await dashboard.GetAsync("/api/dashboard/coverage"))
            .Content.ReadFromJsonAsync<JsonElement>();
        coverage.GetProperty("lineCount").GetInt32().Should().Be(1);
        coverage.GetProperty("bounds").GetProperty("minLatitude").GetDouble().Should().BeApproximately(51.05, 0.001);
    }

    [Fact]
    public async Task An_out_of_range_parameter_falls_back_rather_than_failing()
    {
        // A dashboard URL is typed by hand into a config file; refusing it outright helps nobody.
        var (rider, _) = await NewRider();
        var dashboard = await DashboardClient(rider);

        (await dashboard.GetAsync("/api/dashboard/rides?limit=999999")).StatusCode
            .Should().Be(HttpStatusCode.OK);
        (await dashboard.GetAsync("/api/dashboard/traces?every=-4")).StatusCode
            .Should().Be(HttpStatusCode.OK);
    }

    private async Task<(HttpClient Client, string Username)> NewRider()
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var client = _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}", username, null, "detour-user"));
        (await client.GetAsync("/api/me")).EnsureSuccessStatusCode();
        return (client, username);
    }

    private static async Task<JsonElement> IssueKey(HttpClient rider)
    {
        var response = await rider.PostAsJsonAsync("/api/me/api-keys", new { label = "home assistant" });
        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<JsonElement>();
    }

    private async Task<HttpClient> DashboardClient(HttpClient rider) =>
        KeyClient((await IssueKey(rider)).GetProperty("key").GetString()!);

    private HttpClient KeyClient(string key)
    {
        var client = _factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Api-Key", key);
        return client;
    }

    private static object Trip(long startMs, long? endMs = null) => new
    {
        startTimeMs = startMs,
        endTimeMs = endMs,
        mode = "motorcycle",
        distanceMeters = 12_000.0,
        topSpeedKmh = 88.0,
    };
}
