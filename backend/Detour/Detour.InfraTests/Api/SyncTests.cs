using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;

namespace Detour.InfraTests.Api;

/// <summary>
/// The merge rules. Every one of these is a promise a device depends on: get one wrong and a
/// rider loses an edit, a deletion comes back, or a badge date moves.
/// </summary>
[Collection(PostgresCollection.Name)]
public class SyncTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task Syncing_the_same_input_twice_changes_nothing()
    {
        var client = NewRider();
        var request = new
        {
            trips = new[] { Trip(1_000, distanceMeters: 5_000) },
            traces = new[] { Line(1_000) },
            savedPlaces = new[] { new { id = 7L, name = "Home" } },
            badges = new Dictionary<string, long> { ["dist_100000"] = 5_000 },
        };

        var first = await Sync(client, request);
        var second = await Sync(client, request);

        second.Trips.Should().HaveCount(first.Trips.Count).And.HaveCount(1);
        second.Traces.Should().BeEquivalentTo(first.Traces);
        second.SavedPlaces.Should().HaveCount(1);
        second.Badges.Should().BeEquivalentTo(first.Badges);
    }

    [Fact]
    public async Task Re_uploading_an_edited_trip_replaces_the_stored_copy()
    {
        // The failure this prevents: the stale row comes back in the merge and reverts the edit
        // on the very device that made it.
        var client = NewRider();
        await Sync(client, new { trips = new[] { Trip(2_000, mode: "car") } });

        var merged = await Sync(client, new { trips = new[] { Trip(2_000, mode: "motorcycle") } });

        merged.Trips.Should().ContainSingle();
        merged.Trips[0].GetProperty("mode").GetString().Should().Be("motorcycle");
    }

    [Fact]
    public async Task A_deleted_trip_stays_deleted()
    {
        var client = NewRider();
        await Sync(client, new { trips = new[] { Trip(3_000), Trip(4_000) } });

        var afterDelete = await Sync(client, new { deletedTripStartTimes = new[] { 3_000L } });
        afterDelete.Trips.Should().ContainSingle();

        // The device that deleted it re-syncs with an empty list; the server must not hand it back.
        var later = await Sync(client, new { });
        later.Trips.Should().ContainSingle();
        later.Trips[0].GetProperty("startTimeMs").GetInt64().Should().Be(4_000);
    }

    [Fact]
    public async Task A_trip_present_in_both_lists_ends_up_deleted()
    {
        // Deletes run after the upserts, deliberately. A device that recorded and then deleted
        // a ride between two syncs sends both.
        var client = NewRider();

        var merged = await Sync(client, new
        {
            trips = new[] { Trip(5_000) },
            deletedTripStartTimes = new[] { 5_000L },
        });

        merged.Trips.Should().BeEmpty();
    }

    [Fact]
    public async Task The_earliest_badge_instant_wins()
    {
        var client = NewRider();
        await Sync(client, new { badges = new Dictionary<string, long> { ["dist_100000"] = 5_000 } });

        var later = await Sync(client, new { badges = new Dictionary<string, long> { ["dist_100000"] = 9_000 } });
        later.Badges["dist_100000"].Should().Be(5_000, "a reinstall cannot move the date forward");

        var earlier = await Sync(client, new { badges = new Dictionary<string, long> { ["dist_100000"] = 1_000 } });
        earlier.Badges["dist_100000"].Should().Be(1_000);
    }

    [Fact]
    public async Task An_unrecognisable_badge_id_is_dropped_rather_than_failing_the_sync()
    {
        var client = NewRider();

        var merged = await Sync(client, new
        {
            badges = new Dictionary<string, long> { ["Dist100000"] = 1_000, ["dist_500000"] = 2_000 },
        });

        merged.Badges.Should().ContainKey("dist_500000").And.NotContainKey("Dist100000");
    }

    [Fact]
    public async Task Absent_stats_leave_the_stored_numbers_alone()
    {
        // A client that syncs only trips must not blank the numbers its friends read.
        var client = NewRider();
        await Sync(client, new { stats = new { totalDistanceMeters = 1234.5, topSpeedKmh = 90.0 } });

        await Sync(client, new { trips = new[] { Trip(6_000) } });

        var me = await (await client.GetAsync("/api/me")).Content.ReadFromJsonAsync<JsonElement>();
        me.GetProperty("stats").GetProperty("totalDistanceMeters").GetDouble().Should().Be(1234.5);
    }

    [Fact]
    public async Task Absent_fog_sharing_leaves_the_setting_alone()
    {
        // An older build that knows nothing about the setting must not be able to reset it.
        var client = NewRider();
        await Sync(client, new { shareFog = true });

        var merged = await Sync(client, new { trips = new[] { Trip(7_000) } });

        merged.ShareFog.Should().BeTrue();
    }

    [Fact]
    public async Task A_re_uploaded_trace_line_is_stored_once()
    {
        var client = NewRider();
        var line = Line(8_000);

        await Sync(client, new { traces = new[] { line } });
        var merged = await Sync(client, new { traces = new[] { line, line } });

        merged.Traces.Should().ContainSingle();
    }

    [Fact]
    public async Task Trace_points_are_unpacked_for_the_dashboard()
    {
        var client = NewRider();

        await Sync(client, new { traces = new[] { Line(9_000) } });

        await using var db = postgres.CreateContext();
        var points = await db.TrackPoints.CountAsync();
        points.Should().BeGreaterThan(0);
    }

    [Fact]
    public async Task A_point_with_no_instant_draws_fog_but_stores_no_sample()
    {
        // Two-element points predate timestamps. They are skipped rather than stored with a
        // made-up time, and the line itself is still kept.
        var client = NewRider();

        var merged = await Sync(client, new { traces = new[] { "[[51.05,3.72],[51.06,3.73]]" } });

        merged.Traces.Should().ContainSingle();

        await using var db = postgres.CreateContext();
        var user = await db.Users.OrderByDescending(u => u.CreatedAt).FirstAsync();
        (await db.TrackPoints.CountAsync(p => p.UserId == user.Id)).Should().Be(0);
    }

    [Fact]
    public async Task A_malformed_trace_line_fails_the_whole_sync()
    {
        // All or nothing: a partial import would leave the device merging against a state
        // neither side believes in.
        var client = NewRider();

        var response = await client.PostAsJsonAsync("/api/sync", new
        {
            trips = new[] { Trip(10_000) },
            traces = new[] { "not json at all" },
        });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);

        var after = await Sync(client, new { });
        after.Trips.Should().BeEmpty("nothing from the failed request may have been committed");
    }

    [Fact]
    public async Task A_trip_with_no_start_instant_is_refused()
    {
        var client = NewRider();

        var response = await client.PostAsJsonAsync("/api/sync", new { trips = new[] { Trip(0) } });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
        var problem = await response.Content.ReadFromJsonAsync<JsonElement>();
        problem.GetProperty("detail").GetString().Should().Contain("start time");
    }

    [Fact]
    public async Task Unknown_trip_fields_survive_the_round_trip()
    {
        // The payload stays opaque: the backend cannot disclose what it does not parse, so it
        // must also not quietly drop it.
        var client = NewRider();

        var merged = await Sync(client, new
        {
            trips = new[]
            {
                new { startTimeMs = 11_000L, weather = "rain", companions = new[] { "kim", "sam" } },
            },
        });

        var stored = merged.Trips.Should().ContainSingle().Subject;
        stored.GetProperty("weather").GetString().Should().Be("rain");
        stored.GetProperty("companions").GetArrayLength().Should().Be(2);
    }

    private HttpClient NewRider() =>
        _factory.CreateClientWith(_factory.IssueToken(
            $"subject-{Guid.NewGuid():N}",
            $"rider{Guid.NewGuid():N}"[..16],
            null,
            "detour-user"));

    private static async Task<SyncPayload> Sync(HttpClient client, object request)
    {
        var response = await client.PostAsJsonAsync("/api/sync", request);
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        return (await response.Content.ReadFromJsonAsync<SyncPayload>())!;
    }

    private static object Trip(long startTimeMs, string mode = "motorcycle", double distanceMeters = 1_000) =>
        new { startTimeMs, endTimeMs = startTimeMs + 60_000, mode, distanceMeters, topSpeedKmh = 80.0 };

    private static string Line(long startMs) =>
        $"[[51.05,3.72,{startMs},50.0,12.5],[51.06,3.73,{startMs + 1000},55.0,18.0]]";

    private sealed record SyncPayload(
        IReadOnlyList<JsonElement> Trips,
        IReadOnlyList<string> Traces,
        IReadOnlyList<JsonElement> SavedPlaces,
        IReadOnlyDictionary<string, long> Badges,
        bool ShareFog);
}
