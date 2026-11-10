using Detour.Domain.Trips;

namespace Detour.Domain.Tests;

public class DeletedRideWindowTests
{
    private const long Start = 1_000_000;
    private const long Minute = 60_000;
    private static readonly long Gap = (long)DetourLimits.DeletedRideGap.TotalMilliseconds;
    private static readonly long Cap = Start + (long)DetourLimits.MaxDeletedRideWindow.TotalMilliseconds;

    [Fact]
    public void No_points_infers_nothing()
    {
        Assert.Null(DeletedRideWindow.Infer(Start, [], Cap));
    }

    [Fact]
    public void A_first_point_beyond_the_gap_infers_nothing()
    {
        // Already cleaned, or never uploaded: the points after it are another ride's.
        Assert.Null(DeletedRideWindow.Infer(Start, [Start + Gap + 1, Start + Gap + Minute], Cap));
    }

    [Fact]
    public void A_first_point_exactly_at_the_gap_still_starts_the_ride()
    {
        Assert.Equal((Start, Start + Gap), DeletedRideWindow.Infer(Start, [Start + Gap], Cap));
    }

    [Fact]
    public void A_single_point_is_a_window_from_the_start_to_it()
    {
        Assert.Equal((Start, Start + Minute), DeletedRideWindow.Infer(Start, [Start + Minute], Cap));
    }

    [Fact]
    public void A_gap_longer_than_the_limit_ends_the_window()
    {
        // A 40 min ride sampled every minute, a 20 min stop, then the next ride.
        long[] points = [.. Enumerable.Range(0, 41).Select(i => Start + i * Minute), Start + 60 * Minute];

        Assert.Equal((Start, Start + 40 * Minute), DeletedRideWindow.Infer(Start, points, Cap));
    }

    [Fact]
    public void A_gap_of_exactly_the_limit_does_not_end_it()
    {
        Assert.Equal(
            (Start, Start + 2 * Gap),
            DeletedRideWindow.Infer(Start, [Start + Gap, Start + 2 * Gap], Cap));
    }

    [Fact]
    public void The_upper_bound_ends_the_window_and_is_included()
    {
        // The next row (or tombstone) starts at Start + 3 min; the caller passes one before it.
        var nextStart = Start + 3 * Minute;
        long[] points = [Start, Start + Minute, Start + 2 * Minute, nextStart - 1, nextStart, nextStart + Minute];

        Assert.Equal((Start, nextStart - 1), DeletedRideWindow.Infer(Start, points, nextStart - 1));
    }

    [Fact]
    public void The_span_cap_ends_a_ride_with_no_gap()
    {
        var everyFourMinutes = Enumerable.Range(0, 800).Select(i => Start + i * 4 * Minute).ToList();

        var window = DeletedRideWindow.Infer(Start, everyFourMinutes, Cap);

        Assert.NotNull(window);
        Assert.True(window.Value.ToMs <= Cap);
        Assert.True(Cap - window.Value.ToMs < 4 * Minute);
    }

    [Fact]
    public void Points_before_the_start_are_ignored()
    {
        Assert.Equal((Start, Start + Minute), DeletedRideWindow.Infer(Start, [Start - Minute, Start + Minute], Cap));
    }
}
