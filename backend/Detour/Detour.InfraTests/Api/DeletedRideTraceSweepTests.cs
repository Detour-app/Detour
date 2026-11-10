using System.Net;
using System.Net.Http.Json;
using Detour.Api.Services;
using Detour.Domain.Traces;
using Detour.InfraTests.Database;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging.Abstractions;

namespace Detour.InfraTests.Api;

/// <summary>
/// The one-time sweep of rides deleted before the server removed their location (#895). Each
/// test buries a ride through the real sync, then clears its <c>trace_swept_at</c> the way the
/// migration leaves a row that predates it, and runs a pass directly at a fixed instant.
///
/// A pass looks at every rider, so tests here only assert on their own riders.
/// </summary>
[Collection(PostgresCollection.Name)]
public class DeletedRideTraceSweepTests(PostgresFixture postgres) : IAsyncLifetime
{
    private const long Start = 1_700_000_000_000;
    private const long Minute = 60_000;
    private static readonly DateTimeOffset SweptAt = new(2026, 11, 10, 12, 0, 0, TimeSpan.Zero);

    private DetourApiFactory _factory = null!;
    private readonly ControlledRemover.Controls _remover = new();

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres, services =>
        {
            services.AddSingleton(_remover);
            services.AddScoped<IDeletedRideTraceRemover>(sp => new ControlledRemover(
                ActivatorUtilities.CreateInstance<DeletedRideTraceRemover>(sp),
                sp.GetRequiredService<ControlledRemover.Controls>()));
        });
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_old_deletion_loses_its_ride_up_to_the_first_long_gap()
    {
        var rider = await _factory.SignInAsync();
        var ride = Points(Range(0, 40));
        var later = Points(Range(60, 80)); // after a 20 min stop, a ride with no row
        await Sync(rider, new { traces = new[] { ride, later } });
        await BuryBeforeTheFix(rider, Start);

        var outcome = await RunPass();

        outcome.RidersFailed.Should().Be(0);
        (await Sync(rider, new { })).Traces.Should().Equal(later);
        (await StoredInstants(rider)).Should().Equal(Range(60, 80));
        (await TraceTombstoned(rider, ride)).Should().BeTrue();
        (await SweptAtOf(rider, Start)).Should().Be(SweptAt);

        // Another device still holding the cut line can't bring it back.
        (await Sync(rider, new { traces = new[] { ride, later } })).Traces.Should().Equal(later);
        (await StoredInstants(rider)).Should().Equal(Range(60, 80));
    }

    [Fact]
    public async Task A_later_trip_row_ends_the_cut_at_its_start()
    {
        var rider = await _factory.SignInAsync();
        var ride = Points(Range(0, 40));
        var next = Points(Range(42, 60)); // starts 2 min after the deleted ride's last point
        await Sync(rider, new
        {
            trips = new[] { Ride(Start + 42 * Minute, Start + 60 * Minute) },
            traces = new[] { ride, next },
        });
        await BuryBeforeTheFix(rider, Start);

        await RunPass();

        (await Sync(rider, new { })).Traces.Should().Equal(next);
        (await StoredInstants(rider)).Should().Equal(Range(42, 60));
    }

    [Fact]
    public async Task A_line_straddling_the_inferred_end_keeps_the_points_after_it()
    {
        var rider = await _factory.SignInAsync();
        var straddling = Points([.. Range(0, 40), .. Range(50, 60)]);
        await Sync(rider, new { traces = new[] { straddling } });
        await BuryBeforeTheFix(rider, Start);

        await RunPass();

        (await Sync(rider, new { })).Traces.Should().Equal(Points(Range(50, 60)));
        (await StoredInstants(rider)).Should().Equal(Range(50, 60));
        (await TraceTombstoned(rider, straddling)).Should().BeTrue();
    }

    [Fact]
    public async Task No_point_near_the_start_removes_nothing_and_still_stamps()
    {
        var rider = await _factory.SignInAsync();
        var line = Points(Range(6, 20));
        await Sync(rider, new { traces = new[] { line } });
        await BuryBeforeTheFix(rider, Start);

        await RunPass();

        (await Sync(rider, new { })).Traces.Should().Equal(line);
        (await StoredInstants(rider)).Should().Equal(Range(6, 20));
        (await SweptAtOf(rider, Start)).Should().Be(SweptAt);
    }

    [Fact]
    public async Task A_swept_tombstone_is_not_inferred_again()
    {
        var rider = await _factory.SignInAsync();
        await Sync(rider, new { traces = new[] { Points(Range(0, 10)) } });
        await BuryBeforeTheFix(rider, Start);
        await RunPass();

        // A line flushed mid-ride on another device lands in the old span afterwards.
        var lateLine = Points(Range(1, 5));
        await Sync(rider, new { traces = new[] { lateLine } });
        await RunPass();

        (await Sync(rider, new { })).Traces.Should().Equal(lateLine);
        (await StoredInstants(rider)).Should().Equal(Range(1, 5));
    }

    [Fact]
    public async Task A_deletion_made_after_the_fix_is_stamped_at_creation_and_never_inferred()
    {
        var rider = await _factory.SignInAsync();
        var line = Points(Range(0, 10));
        await Sync(rider, new { traces = new[] { line } });

        // No row and no window: the sync can't tell where the ride went, and the sweep doesn't guess.
        await Sync(rider, new { deletedTripStartTimes = new[] { Start } });
        (await SweptAtOf(rider, Start)).Should().NotBeNull();

        await RunPass();

        (await Sync(rider, new { })).Traces.Should().Equal(line);
    }

    [Fact]
    public async Task One_riders_failure_leaves_them_untouched_and_the_pass_goes_on()
    {
        var failing = await _factory.SignInAsync();
        var other = await _factory.SignInAsync();
        var line = Points(Range(0, 10));
        foreach (var rider in new[] { failing, other })
        {
            await Sync(rider, new { traces = new[] { line } });
            await BuryBeforeTheFix(rider, Start);
        }

        _remover.FailFor = failing.UserId;
        var outcome = await RunPass();

        outcome.RidersFailed.Should().Be(1);
        (await SweptAtOf(failing, Start)).Should().BeNull("a failed rider is retried on the next pass");
        (await Sync(failing, new { })).Traces.Should().Equal(line);
        (await StoredInstants(failing)).Should().Equal(Range(0, 10));

        (await SweptAtOf(other, Start)).Should().Be(SweptAt);
        (await Sync(other, new { })).Traces.Should().BeEmpty();
    }

    [Fact]
    public async Task A_sync_for_the_same_rider_waits_for_the_pass_and_cannot_bring_the_line_back()
    {
        var rider = await _factory.SignInAsync();
        var line = Points(Range(0, 10));
        await Sync(rider, new { traces = new[] { line } });
        await BuryBeforeTheFix(rider, Start);

        _remover.HoldFor = rider.UserId;
        var pass = RunPass();
        await _remover.Entered.Task.WaitAsync(TimeSpan.FromSeconds(30));

        // Device B uploads the line while the pass holds the rider's lock.
        var sync = Sync(rider, new { traces = new[] { line } });
        var finishedEarly = await Task.WhenAny(sync, Task.Delay(TimeSpan.FromSeconds(1))) == sync;

        _remover.Release.SetResult();
        await pass;
        var merged = await sync;

        finishedEarly.Should().BeFalse("the sync takes the rider's lock before reading anything");
        merged.Traces.Should().BeEmpty();
        (await StoredInstants(rider)).Should().BeEmpty();
        (await TraceTombstoned(rider, line)).Should().BeTrue();
    }

    private Task<DeletedRideTraceSweep.Outcome> RunPass() =>
        new DeletedRideTraceSweep(
                _factory.Services.GetRequiredService<IServiceScopeFactory>(),
                new FixedClock(SweptAt),
                NullLogger<DeletedRideTraceSweep>.Instance)
            .RunAsync(CancellationToken.None);

    /// <summary>
    /// Tombstones the ride with no row and no window, then clears the stamp: what the migration
    /// leaves for a ride deleted before #780/#877 deployed.
    /// </summary>
    private async Task BuryBeforeTheFix(SignedInClient rider, long start)
    {
        await Sync(rider, new { deletedTripStartTimes = new[] { start } });

        await using var db = postgres.CreateContext();
        await db.Database.ExecuteSqlInterpolatedAsync(
            $"UPDATE detour.trip_tombstones SET trace_swept_at = NULL WHERE user_id = {rider.UserId} AND trip_start_time_ms = {start}");
    }

    private async Task<DateTimeOffset?> SweptAtOf(SignedInClient rider, long start)
    {
        await using var db = postgres.CreateContext();
        return await db.TripTombstones
            .Where(t => t.UserId == rider.UserId && t.TripStartTimeMs == start)
            .Select(t => t.TraceSweptAt)
            .SingleAsync();
    }

    private async Task<bool> TraceTombstoned(SignedInClient rider, string line)
    {
        var hash = Trace.HashOf(line);
        await using var db = postgres.CreateContext();
        return await db.TraceTombstones.AnyAsync(t => t.UserId == rider.UserId && t.LineHash == hash);
    }

    private async Task<List<long>> StoredInstants(SignedInClient rider)
    {
        await using var db = postgres.CreateContext();
        return await db.TrackPoints
            .Where(p => p.UserId == rider.UserId)
            .OrderBy(p => p.TimestampMs)
            .Select(p => p.TimestampMs)
            .ToListAsync();
    }

    private static async Task<SyncPayload> Sync(SignedInClient rider, object request)
    {
        var response = await rider.PostAsJsonAsync("/api/sync", request);
        response.StatusCode.Should().Be(HttpStatusCode.OK);
        return (await response.Content.ReadFromJsonAsync<SyncPayload>())!;
    }

    /// <summary>One instant a minute after <see cref="Start"/>, from minute to minute inclusive.</summary>
    private static long[] Range(int fromMinute, int toMinute) =>
        [.. Enumerable.Range(fromMinute, toMinute - fromMinute + 1).Select(m => Start + m * Minute)];

    /// <summary>A line with one sample per instant, in the client's number format.</summary>
    private static string Points(long[] instants) =>
        $"[{string.Join(',', instants.Select(t => $"[51.05,3.72,{t},50.0,12.5]"))}]";

    private static object Ride(long startTimeMs, long endTimeMs) =>
        new { startTimeMs, endTimeMs, mode = "motorcycle", distanceMeters = 1_000.0, topSpeedKmh = 80.0 };

    private sealed record SyncPayload(IReadOnlyList<long> DeletedTripStartTimes, IReadOnlyList<string> Traces);

    private sealed class FixedClock(DateTimeOffset now) : TimeProvider
    {
        public override DateTimeOffset GetUtcNow() => now;
    }

    /// <summary>The real remover, with a seam to fail or hold it for one rider.</summary>
    private sealed class ControlledRemover(IDeletedRideTraceRemover inner, ControlledRemover.Controls controls)
        : IDeletedRideTraceRemover
    {
        public sealed class Controls
        {
            public Guid? FailFor { get; set; }
            public Guid? HoldFor { get; set; }
            public TaskCompletionSource Entered { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
            public TaskCompletionSource Release { get; } = new(TaskCreationOptions.RunContinuationsAsynchronously);
        }

        public async Task<int> RemoveAsync(Guid userId, IReadOnlyList<TimeWindow> windows, CancellationToken cancellationToken)
        {
            if (userId == controls.FailFor)
                throw new InvalidOperationException("injected failure");

            if (userId == controls.HoldFor)
            {
                controls.Entered.TrySetResult();
                await controls.Release.Task;
            }

            return await inner.RemoveAsync(userId, windows, cancellationToken);
        }
    }
}
