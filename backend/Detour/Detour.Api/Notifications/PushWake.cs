namespace Detour.Api.Notifications;

/// <summary>
/// What one wake-ping says, the same on both clouds. Still content-free
/// (<c>docs/PUSH.md</c> §2): <see cref="Data"/> holds a type and at most the id the device
/// fetches by — an id the collapse key already shows the cloud — never a name, a place or a
/// time.
/// </summary>
/// <param name="CollapseKey">FCM <c>collapse_key</c> and APNs <c>apns-collapse-id</c>: a burst
///  for one subject coalesces into a single wake.</param>
/// <param name="Data">The FCM data block; the device switches on its <c>type</c>.</param>
/// <param name="AlertBody">The fixed English placeholder iOS shows if its Notification Service
///  Extension does not rewrite it in time. The client, never this backend, owns the copy.</param>
public sealed record PushWake(string CollapseKey, IReadOnlyDictionary<string, string> Data, string AlertBody)
{
    /// <summary>Something happened in a circle; the device sweeps its circles' feeds.</summary>
    public static PushWake CircleActivity(string circleId) =>
        new(circleId, new Dictionary<string, string> { ["type"] = "circle_wake" }, "New circle activity");

    /// <summary>A planned ride the rider is going or maybe-going to starts soon; the device
    ///  fetches the ride by id and posts the reminder itself.</summary>
    public static PushWake RideReminder(Guid rideId) =>
        new(
            $"ride-{rideId}",
            new Dictionary<string, string> { ["type"] = "ride_reminder", ["rideId"] = rideId.ToString() },
            "A planned ride starts soon");
}
