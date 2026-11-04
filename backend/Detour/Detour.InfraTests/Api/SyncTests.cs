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
    public async Task A_stale_copy_from_another_device_does_not_revert_an_edit()
    {
        // #486: phone A corrects the mode; phone B, which still holds the ride unedited, syncs
        // after it. Last upload winning would put B's stale mode back on the server and on A.
        var client = NewRider();
        await Sync(client, new { trips = new[] { Trip(2_000, mode: "car", editedAtMs: 0) } });
        await Sync(client, new { trips = new[] { Trip(2_000, mode: "motorcycle", editedAtMs: 9_000) } });

        var merged = await Sync(client, new { trips = new[] { Trip(2_000, mode: "car", editedAtMs: 0) } });

        merged.Trips.Should().ContainSingle();
        merged.Trips[0].GetProperty("mode").GetString().Should().Be("motorcycle");
        merged.Trips[0].GetProperty("editedAtMs").GetInt64().Should().Be(9_000);
    }

    [Fact]
    public async Task A_copy_without_an_edit_stamp_still_overwrites()
    {
        // An older client sends no stamp; it keeps today's last-write-wins rather than being
        // locked out of every trip a newer client has edited.
        var client = NewRider();
        await Sync(client, new { trips = new[] { Trip(2_000, mode: "motorcycle", editedAtMs: 9_000) } });

        var merged = await Sync(client, new { trips = new[] { Trip(2_000, mode: "car") } });

        merged.Trips[0].GetProperty("mode").GetString().Should().Be("car");
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
    public async Task A_second_device_still_holding_a_deleted_trip_does_not_bring_it_back()
    {
        // #778: device A deletes the ride; device B, which still holds it and has not heard,
        // uploads it on its next sync. With a hard delete alone the server took it back and the
        // copy flipped between deleted and present on every sync.
        var client = NewRider();
        await Sync(client, new { trips = new[] { Trip(3_000), Trip(4_000) } });
        await Sync(client, new { trips = new[] { Trip(4_000) }, deletedTripStartTimes = new[] { 3_000L } });

        var deviceB = await Sync(client, new { trips = new[] { Trip(3_000), Trip(4_000) } });

        deviceB.Trips.Should().ContainSingle()
            .Which.GetProperty("startTimeMs").GetInt64().Should().Be(4_000);
        deviceB.DeletedTripStartTimes.Should().Equal(3_000L);

        // And it stays gone once B has dropped its copy.
        var later = await Sync(client, new { trips = new[] { Trip(4_000) } });
        later.Trips.Should().ContainSingle();
        later.DeletedTripStartTimes.Should().Equal(3_000L);
    }

    [Fact]
    public async Task A_deletion_re_sent_on_every_sync_keeps_one_tombstone()
    {
        // The device uploads its whole tombstone list each time; the unique index would turn a
        // second row into a failed sync.
        var client = NewRider();
        await Sync(client, new { trips = new[] { Trip(3_000) } });

        await Sync(client, new { deletedTripStartTimes = new[] { 3_000L, 3_000L } });
        var again = await Sync(client, new { deletedTripStartTimes = new[] { 3_000L } });

        again.Trips.Should().BeEmpty();
        again.DeletedTripStartTimes.Should().Equal(3_000L);
    }

    [Fact]
    public async Task A_deleted_saved_place_stays_deleted()
    {
        // #695: the device that removed the shortcut stops uploading it, and before the field
        // existed the server kept its copy and handed it back on every sync.
        var client = NewRider();
        await Sync(client, new
        {
            savedPlaces = new[] { new { id = 7L, name = "Home" }, new { id = 8L, name = "Work" } },
        });

        var afterDelete = await Sync(client, new
        {
            savedPlaces = new[] { new { id = 8L, name = "Work" } },
            deletedSavedPlaceIds = new[] { 7L },
        });
        afterDelete.SavedPlaces.Should().ContainSingle();

        var later = await Sync(client, new { });
        later.SavedPlaces.Should().ContainSingle();
        later.SavedPlaces[0].GetProperty("id").GetInt64().Should().Be(8);
    }

    [Fact]
    public async Task A_saved_place_present_in_both_lists_ends_up_deleted()
    {
        var client = NewRider();

        var merged = await Sync(client, new
        {
            savedPlaces = new[] { new { id = 9L, name = "Cafe" } },
            deletedSavedPlaceIds = new[] { 9L },
        });

        merged.SavedPlaces.Should().BeEmpty();
    }

    [Fact]
    public async Task Saved_routes_round_trip_through_the_merge()
    {
        // #592: a second device (or a reinstall) starts with nothing but the server's union.
        var client = NewRider();
        await Sync(client, new { savedRoutes = new[] { Route(11, "Coast road") } });

        var fresh = await Sync(client, new { });

        fresh.SavedRoutes.Should().ContainSingle();
        var route = fresh.SavedRoutes[0];
        route.GetProperty("id").GetInt64().Should().Be(11);
        route.GetProperty("name").GetString().Should().Be("Coast road");
        route.GetProperty("stops").GetArrayLength().Should().Be(2);
        route.GetProperty("polyline").GetArrayLength().Should().Be(4);
    }

    [Fact]
    public async Task Re_uploading_a_renamed_route_replaces_the_stored_copy()
    {
        var client = NewRider();
        await Sync(client, new { savedRoutes = new[] { Route(12, "Old name") } });

        var merged = await Sync(client, new { savedRoutes = new[] { Route(12, "New name") } });

        merged.SavedRoutes.Should().ContainSingle();
        merged.SavedRoutes[0].GetProperty("name").GetString().Should().Be("New name");
    }

    [Fact]
    public async Task A_deleted_saved_route_stays_deleted()
    {
        var client = NewRider();
        await Sync(client, new { savedRoutes = new[] { Route(13, "Keep"), Route(14, "Drop") } });

        await Sync(client, new
        {
            savedRoutes = new[] { Route(13, "Keep") },
            deletedSavedRouteIds = new[] { 14L },
        });

        var later = await Sync(client, new { });
        later.SavedRoutes.Should().ContainSingle();
        later.SavedRoutes[0].GetProperty("id").GetInt64().Should().Be(13);
    }

    [Fact]
    public async Task A_saved_route_present_in_both_lists_ends_up_deleted()
    {
        var client = NewRider();

        var merged = await Sync(client, new
        {
            savedRoutes = new[] { Route(15, "Brief") },
            deletedSavedRouteIds = new[] { 15L },
        });

        merged.SavedRoutes.Should().BeEmpty();
    }

    [Fact]
    public async Task A_route_without_an_id_fails_the_sync()
    {
        var client = NewRider();

        var response = await client.PostAsJsonAsync("/api/sync", new { savedRoutes = new[] { Route(0, "No id") } });

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Routes_are_never_returned_to_another_rider()
    {
        var owner = NewRider();
        await Sync(owner, new { savedRoutes = new[] { Route(16, "Mine") } });

        var other = await Sync(NewRider(), new { });

        other.SavedRoutes.Should().BeEmpty();
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
    public async Task The_newest_ride_title_wins()
    {
        // The #471 revert: a second device still holding the old title re-uploads it.
        var client = NewRider();
        await Sync(client, new { tripTitles = new[] { Title(1_000, "Coast road", 5_000) } });

        var stale = await Sync(client, new { tripTitles = new[] { Title(1_000, "Sunday ride", 4_000) } });
        stale.TripTitles.Should().ContainSingle().Which.Title.Should().Be("Coast road");

        var newer = await Sync(client, new { tripTitles = new[] { Title(1_000, "Coast road, again", 6_000) } });
        newer.TripTitles.Should().ContainSingle().Which.Title.Should().Be("Coast road, again");
    }

    [Fact]
    public async Task A_cleared_ride_title_stays_cleared()
    {
        var client = NewRider();
        await Sync(client, new { tripTitles = new[] { Title(1_000, "Coast road", 5_000) } });
        await Sync(client, new { tripTitles = new[] { Title(1_000, "", 6_000) } });

        var merged = await Sync(client, new { tripTitles = new[] { Title(1_000, "Coast road", 5_000) } });

        var title = merged.TripTitles.Should().ContainSingle().Subject;
        title.Title.Should().BeEmpty("the tombstone is what stops the older title resurrecting");
        title.EditedAtMs.Should().Be(6_000);
    }

    [Fact]
    public async Task Absent_ride_titles_leave_the_stored_ones_alone()
    {
        var client = NewRider();
        await Sync(client, new { tripTitles = new[] { Title(1_000, "Coast road", 5_000) } });

        var merged = await Sync(client, new { trips = new[] { Trip(1_000) } });

        merged.TripTitles.Should().ContainSingle().Which.Title.Should().Be("Coast road");
    }

    [Fact]
    public async Task An_over_long_ride_title_is_dropped_rather_than_failing_the_sync()
    {
        var client = NewRider();
        var response = await client.PostAsJsonAsync("/api/sync", new
        {
            tripTitles = new[] { Title(1_000, new string('a', 201), 5_000), Title(2_000, "Coast road", 5_000) },
        });

        response.StatusCode.Should().Be(HttpStatusCode.OK);
        var merged = (await response.Content.ReadFromJsonAsync<SyncPayload>())!;
        merged.TripTitles.Should().ContainSingle().Which.StartTimeMs.Should().Be(2_000);
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
    public async Task Two_new_lines_meeting_at_a_flush_boundary_sync_once()
    {
        // The device's tracker flushes its buffer every 200 points with keepLast = true
        // (TripTrackingService), so the last sample of one line is deliberately re-emitted as
        // the first sample of the next — without it the drawn trace would break every 200
        // samples. TrackPoint's key is (UserId, TimestampMs), so those two lines carry one
        // instant twice, and adding them per line made EF's change tracker throw and took the
        // whole sync down with a 500. A first full sync of a real history hits this on every
        // flush boundary it contains.
        var client = NewRider();

        var merged = await Sync(client, new
        {
            traces = new[] { Segment(1_000, 2_000), Segment(2_000, 3_000) },
        });

        merged.Traces.Should().HaveCount(2);

        await using var db = postgres.CreateContext();
        var user = await db.Users.OrderByDescending(u => u.CreatedAt).FirstAsync();
        var stored = await db.TrackPoints
            .Where(p => p.UserId == user.Id)
            .Select(p => p.TimestampMs)
            .ToListAsync();
        stored.Should().BeEquivalentTo([1_000L, 2_000L, 3_000L],
            "the shared instant is one sample, not two");
    }

    [Fact]
    public async Task A_line_whose_boundary_sample_is_already_stored_still_syncs()
    {
        // The same overlap across two syncs rather than within one: the earlier line is skipped
        // by the hash check, so its stored sample is invisible to the merge — and re-inserting
        // that instant violates the primary key at commit instead of tripping the tracker. This
        // is the steady-state shape of the failure, when a sync lands mid-drive and the next
        // segment arrives later.
        var client = NewRider();

        await Sync(client, new { traces = new[] { Segment(4_000, 5_000) } });
        var merged = await Sync(client, new { traces = new[] { Segment(5_000, 6_000) } });

        merged.Traces.Should().HaveCount(2);

        await using var db = postgres.CreateContext();
        var user = await db.Users.OrderByDescending(u => u.CreatedAt).FirstAsync();
        var stored = await db.TrackPoints
            .Where(p => p.UserId == user.Id)
            .Select(p => p.TimestampMs)
            .ToListAsync();
        stored.Should().BeEquivalentTo([4_000L, 5_000L, 6_000L]);
    }

    [Fact]
    public async Task A_deleted_rides_location_leaves_the_server()
    {
        var client = NewRider();
        var ride = Line(5_000);
        var other = Line(90_000);
        await Sync(client, new { trips = new[] { Ride(5_000, 7_000), Ride(90_000, 91_000) }, traces = new[] { ride, other } });

        var merged = await Sync(client, new { deletedTripStartTimes = new[] { 5_000L } });

        merged.Traces.Should().Equal(other);
        (await StoredInstants(client)).Should().Equal(90_000L, 91_000L);
    }

    [Fact]
    public async Task A_device_still_holding_a_deleted_rides_line_does_not_bring_it_back()
    {
        var deviceA = NewRider();
        var line = Line(5_000);
        await Sync(deviceA, new { trips = new[] { Ride(5_000, 7_000) }, traces = new[] { line } });
        await Sync(deviceA, new { deletedTripStartTimes = new[] { 5_000L } });

        // Same account, so same rider: device B uploads its copy of the whole history.
        var deviceB = await Sync(deviceA, new { trips = new[] { Ride(5_000, 7_000) }, traces = new[] { line } });

        deviceB.Traces.Should().BeEmpty("device B replaces its lines with these, which is how it loses the ride");
        (await StoredInstants(deviceA)).Should().BeEmpty();
    }

    [Fact]
    public async Task A_line_straddling_a_deleted_ride_keeps_the_points_either_side()
    {
        var client = NewRider();
        var straddling = Points(1_000, 2_000, 5_000, 6_000, 9_000, 10_000);
        await Sync(client, new { trips = new[] { Ride(5_000, 6_000) }, traces = new[] { straddling } });

        var merged = await Sync(client, new { deletedTripStartTimes = new[] { 5_000L } });

        merged.Traces.Should().BeEquivalentTo(Points(1_000, 2_000), Points(9_000, 10_000));
        (await StoredInstants(client)).Should().Equal(1_000L, 2_000L, 9_000L, 10_000L);

        // Device B re-uploads the old whole line: it stays out, the pieces stay in.
        var later = await Sync(client, new { traces = new[] { straddling } });
        later.Traces.Should().BeEquivalentTo(Points(1_000, 2_000), Points(9_000, 10_000));
    }

    [Fact]
    public async Task The_deleting_devices_own_cut_lines_are_not_stored_twice()
    {
        var client = NewRider();
        var straddling = Points(1_000, 2_000, 5_000, 6_000, 9_000, 10_000);
        await Sync(client, new { trips = new[] { Ride(5_000, 6_000) }, traces = new[] { straddling } });

        // What TraceStore.removeWindow leaves on the device that deleted the ride.
        var merged = await Sync(client, new
        {
            deletedTripStartTimes = new[] { 5_000L },
            traces = new[] { Points(1_000, 2_000), Points(9_000, 10_000) },
        });

        merged.Traces.Should().HaveCount(2);
        merged.Traces.Should().BeEquivalentTo(Points(1_000, 2_000), Points(9_000, 10_000));
    }

    [Fact]
    public async Task A_line_uploaded_alongside_the_deletion_loses_the_ride_too()
    {
        // An older client deletes a ride without cutting its trace, and may flush a line the
        // server has not seen before the sync that carries the deletion.
        var client = NewRider();
        await Sync(client, new { trips = new[] { Ride(5_000, 7_000) } });

        var merged = await Sync(client, new
        {
            deletedTripStartTimes = new[] { 5_000L },
            traces = new[] { Line(5_000) },
        });

        merged.Traces.Should().BeEmpty();
        (await StoredInstants(client)).Should().BeEmpty();
    }

    [Fact]
    public async Task A_deletion_re_sent_on_every_sync_changes_nothing_more()
    {
        // The device uploads its whole tombstone list each time, with its whole trace history.
        var client = NewRider();
        var ride = Line(5_000);
        var other = Line(90_000);
        await Sync(client, new { trips = new[] { Ride(5_000, 7_000) }, traces = new[] { ride, other } });
        await Sync(client, new { deletedTripStartTimes = new[] { 5_000L }, traces = new[] { ride, other } });

        var again = await Sync(client, new { deletedTripStartTimes = new[] { 5_000L }, traces = new[] { ride, other } });

        again.Traces.Should().Equal(other);
        again.DeletedTripStartTimes.Should().Equal(5_000L);
    }

    [Fact]
    public async Task A_deleted_ride_with_no_recorded_end_keeps_its_points()
    {
        // No end, no window: nothing says which points were this ride's.
        var client = NewRider();
        var line = Line(5_000);
        var noEnd = new { startTimeMs = 5_000L, mode = "motorcycle", distanceMeters = 1_000.0, topSpeedKmh = 80.0 };
        await Sync(client, new { trips = new[] { noEnd }, traces = new[] { line } });

        var merged = await Sync(client, new { deletedTripStartTimes = new[] { 5_000L } });

        merged.Trips.Should().BeEmpty();
        merged.Traces.Should().Equal(line);
    }

    [Fact]
    public async Task A_deleted_ride_leaves_another_riders_points_alone()
    {
        var rider = NewRider();
        var other = NewRider();
        await Sync(other, new { traces = new[] { Line(5_000) } });
        await Sync(rider, new { trips = new[] { Ride(5_000, 7_000) }, traces = new[] { Line(5_000) } });

        await Sync(rider, new { deletedTripStartTimes = new[] { 5_000L } });

        (await Sync(other, new { })).Traces.Should().Equal(Line(5_000));
        (await StoredInstants(other)).Should().Equal(5_000L, 6_000L);
    }

    [Fact]
    public async Task A_deleted_ride_with_no_row_loses_its_location_by_the_window_the_device_sent()
    {
        // A mid-ride sync uploaded a line, the sync at the end failed, and the rider deleted the
        // ride offline: the server never had a row, so the device's window is all it has.
        var client = NewRider();
        var line = Line(5_000);
        var other = Line(90_000);
        await Sync(client, new { traces = new[] { line, other } });

        var merged = await Sync(client, new
        {
            deletedTripStartTimes = new[] { 5_000L },
            deletedTripWindows = new[] { new { startTimeMs = 5_000L, endTimeMs = 7_000L } },
            traces = new[] { other },
        });

        merged.Traces.Should().Equal(other);
        merged.DeletedTripStartTimes.Should().Equal(5_000L);
        (await StoredInstants(client)).Should().Equal(90_000L, 91_000L);

        // Another device still holding the line can't bring it back.
        (await Sync(client, new { traces = new[] { line, other } })).Traces.Should().Equal(other);
    }

    [Fact]
    public async Task A_window_with_no_row_keeps_the_points_either_side_of_it()
    {
        var client = NewRider();
        var straddling = Points(1_000, 2_000, 5_000, 6_000, 9_000, 10_000);
        await Sync(client, new { traces = new[] { straddling } });

        var merged = await Sync(client, new
        {
            deletedTripWindows = new[] { new { startTimeMs = 5_000L, endTimeMs = 6_000L } },
            traces = new[] { Points(1_000, 2_000), Points(9_000, 10_000) },
        });

        merged.DeletedTripStartTimes.Should().Equal(5_000L);
        merged.Traces.Should().HaveCount(2);
        merged.Traces.Should().BeEquivalentTo(Points(1_000, 2_000), Points(9_000, 10_000));
        (await StoredInstants(client)).Should().Equal(1_000L, 2_000L, 9_000L, 10_000L);
    }

    [Fact]
    public async Task A_re_sent_window_takes_a_line_that_arrived_after_the_deletion()
    {
        var client = NewRider();
        var windows = new[] { new { startTimeMs = 5_000L, endTimeMs = 7_000L } };
        await Sync(client, new { deletedTripWindows = windows });

        // Device B flushed this line mid-ride and only uploads it now.
        await Sync(client, new { traces = new[] { Line(5_000) } });
        var merged = await Sync(client, new { deletedTripWindows = windows });

        merged.Traces.Should().BeEmpty();
        (await StoredInstants(client)).Should().BeEmpty();
    }

    [Fact]
    public async Task A_window_covers_a_deletion_an_older_server_already_tombstoned()
    {
        // The app can update first: the server buried the start and ignored the window it didn't
        // know, and the line stayed. Once upgraded, the device's next sync still sends it.
        var client = NewRider();
        await Sync(client, new { traces = new[] { Line(5_000) } });
        await Sync(client, new { deletedTripStartTimes = new[] { 5_000L } });
        (await Sync(client, new { })).Traces.Should().ContainSingle("a start alone says nothing about where the ride went");

        var merged = await Sync(client, new
        {
            deletedTripStartTimes = new[] { 5_000L },
            deletedTripWindows = new[] { new { startTimeMs = 5_000L, endTimeMs = 7_000L } },
        });

        merged.Traces.Should().BeEmpty();
    }

    [Fact]
    public async Task A_re_sent_window_leaves_everything_outside_it_alone()
    {
        var client = NewRider();
        var windows = new[] { new { startTimeMs = 5_000L, endTimeMs = 7_000L } };
        var other = Line(90_000);
        await Sync(client, new { deletedTripWindows = windows });

        var again = await Sync(client, new { deletedTripWindows = windows, traces = new[] { other } });
        var third = await Sync(client, new { deletedTripWindows = windows, traces = new[] { other } });

        again.Traces.Should().Equal(other);
        third.Traces.Should().Equal(other);
        third.DeletedTripStartTimes.Should().Equal(5_000L);
        (await StoredInstants(client)).Should().Equal(90_000L, 91_000L);
    }

    [Fact]
    public async Task A_window_ending_before_it_starts_removes_no_location()
    {
        var client = NewRider();
        var line = Line(5_000);
        await Sync(client, new { traces = new[] { line } });

        var merged = await Sync(client, new { deletedTripWindows = new[] { new { startTimeMs = 5_000L, endTimeMs = 0L } } });

        merged.Traces.Should().Equal(line);
        merged.DeletedTripStartTimes.Should().Equal(5_000L);
    }

    [Fact]
    public async Task A_window_leaves_another_riders_points_alone()
    {
        var rider = NewRider();
        var other = NewRider();
        await Sync(other, new { traces = new[] { Line(5_000) } });
        await Sync(rider, new { traces = new[] { Line(5_000) } });

        await Sync(rider, new { deletedTripWindows = new[] { new { startTimeMs = 5_000L, endTimeMs = 7_000L } } });

        (await Sync(other, new { })).Traces.Should().Equal(Line(5_000));
        (await StoredInstants(other)).Should().Equal(5_000L, 6_000L);
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

    private static object Trip(
        long startTimeMs,
        string mode = "motorcycle",
        double distanceMeters = 1_000,
        long? editedAtMs = null) =>
        editedAtMs is { } edited
            ? (object)new { startTimeMs, endTimeMs = startTimeMs + 60_000, mode, distanceMeters, topSpeedKmh = 80.0, editedAtMs = edited }
            : new { startTimeMs, endTimeMs = startTimeMs + 60_000, mode, distanceMeters, topSpeedKmh = 80.0 };

    private static object Title(long startTimeMs, string title, long editedAtMs) =>
        new { startTimeMs, title, editedAtMs };

    /// <summary>The shape the app's <c>SavedRoute.toJson</c> writes: flat polyline, two stops.</summary>
    private static object Route(long id, string name) => new
    {
        id,
        name,
        createdMs = 1_700_000_000_000L,
        mode = "MOTO",
        stops = new[] { new { lat = 50.8, lon = 3.2, name = "Home" }, new { lat = 50.9, lon = 3.3, name = "Work" } },
        polyline = new[] { 50.8, 3.2, 50.9, 3.3 },
        distanceMeters = 15_234.5,
        timeMs = 3_600_000L,
    };

    private static string Line(long startMs) =>
        $"[[51.05,3.72,{startMs},50.0,12.5],[51.06,3.73,{startMs + 1000},55.0,18.0]]";

    /// <summary>
    /// A two-sample line with both instants named. [Line] derives its second one, which cannot
    /// express two consecutive segments sharing a boundary the way a real flush does.
    /// </summary>
    private static string Segment(long firstMs, long lastMs) =>
        $"[[51.05,3.72,{firstMs},50.0,12.5],[51.06,3.73,{lastMs},55.0,18.0]]";

    /// <summary>The instants of this rider's stored track points, ascending.</summary>
    private async Task<List<long>> StoredInstants(HttpClient client)
    {
        var me = await client.GetFromJsonAsync<JsonElement>("/api/me");
        var id = me.GetProperty("id").GetGuid();

        await using var db = postgres.CreateContext();
        return await db.TrackPoints
            .Where(p => p.UserId == id)
            .OrderBy(p => p.TimestampMs)
            .Select(p => p.TimestampMs)
            .ToListAsync();
    }

    /// <summary>A line with one sample per instant, in the client's number format.</summary>
    private static string Points(params long[] instants) =>
        $"[{string.Join(',', instants.Select(t => $"[51.05,3.72,{t},50.0,12.5]"))}]";

    /// <summary>A trip that recorded an end, so it has a window to delete.</summary>
    private static object Ride(long startTimeMs, long endTimeMs) =>
        new { startTimeMs, endTimeMs, mode = "motorcycle", distanceMeters = 1_000.0, topSpeedKmh = 80.0 };

    private sealed record SyncPayload(
        IReadOnlyList<JsonElement> Trips,
        IReadOnlyList<long> DeletedTripStartTimes,
        IReadOnlyList<string> Traces,
        IReadOnlyList<JsonElement> SavedPlaces,
        IReadOnlyList<JsonElement> SavedRoutes,
        IReadOnlyDictionary<string, long> Badges,
        IReadOnlyList<SyncedTitle> TripTitles,
        bool ShareFog);

    private sealed record SyncedTitle(long StartTimeMs, string Title, long EditedAtMs);
}
