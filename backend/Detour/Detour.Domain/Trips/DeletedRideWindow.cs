namespace Detour.Domain.Trips;

/// <summary>
/// Where a deleted ride ended, inferred from its points, for a ride buried before the server
/// kept anything that says so (#895). Rides deleted before #780/#877 deployed lost their row and
/// never sent a window, so the start is all that is left.
/// </summary>
public static class DeletedRideWindow
{
    /// <summary>
    /// The inferred <c>[start, end]</c>, both ends included, or null when the points don't
    /// start the ride.
    /// </summary>
    /// <param name="startMs">The tombstoned start.</param>
    /// <param name="pointInstants">The rider's sample instants from the start on, ascending.</param>
    /// <param name="upperBoundMs">
    /// The last instant the ride could reach, included: one before the next ride's start, or the
    /// start plus <see cref="DetourLimits.MaxDeletedRideWindow"/>, whichever is earlier.
    /// </param>
    /// <returns>
    /// Null when no point is within <see cref="DetourLimits.DeletedRideGap"/> of the start: the
    /// span was already cleaned, or never uploaded. Otherwise the window ends at the last point
    /// before the first gap longer than <see cref="DetourLimits.DeletedRideGap"/>, or before
    /// <paramref name="upperBoundMs"/>.
    /// </returns>
    public static (long FromMs, long ToMs)? Infer(long startMs, IReadOnlyList<long> pointInstants, long upperBoundMs)
    {
        var gapMs = (long)DetourLimits.DeletedRideGap.TotalMilliseconds;
        long? end = null;

        foreach (var instant in pointInstants)
        {
            if (instant < startMs)
                continue;
            if (instant > upperBoundMs || instant - (end ?? startMs) > gapMs)
                break;

            end = instant;
        }

        return end is { } last ? (startMs, last) : null;
    }
}
