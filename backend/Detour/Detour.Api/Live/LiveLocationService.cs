using Detour.Domain;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using JV.ResultUtilities;

namespace Detour.Api.Live;

/// <summary>One fix, as reported by a device, before the relay decides who may see it.</summary>
public sealed record LivePosition(
    double Latitude,
    double Longitude,
    double? AccuracyMetres,
    double? HeadingDegrees,
    double? SpeedKmh,
    long TimestampMs);

/// <summary>
/// Where a fix arrived from. This is the only thing the backend knows about a device's reporting
/// cadence, and it is a good enough proxy: a socket is only held open while a rider is actually
/// riding, and the low-cadence HTTP path is what a phone falls back to in the background.
/// </summary>
public enum LivePositionSource
{
    /// <summary>Seconds apart. A convoy, or a foregrounded app.</summary>
    Socket,

    /// <summary>Minutes apart. Background circle presence.</summary>
    Http,
}

public interface ILiveLocationService
{
    /// <summary>
    /// Records one fix and relays it to everyone entitled to see it.
    ///
    /// One ingest for both transports: a rider's position is a property of the rider, not of any
    /// group they happen to be in, so who receives it and whether it is stored are decisions made
    /// here rather than duplicated per caller.
    /// </summary>
    Task<Result> IngestAsync(
        User caller,
        LivePosition position,
        LivePositionSource source,
        CancellationToken cancellationToken);
}

internal sealed class LiveLocationService(
    IGroupRepository groups,
    IMemberFixRepository memberFixes,
    LiveRelay relay) : ILiveLocationService
{
    /// <summary>
    /// How long a socket-borne fix stays drawable. Generous against the ~2s convoy cadence: ten
    /// missed updates, so an ordinary gap in GPS does not flicker a peer off the map, but a rider
    /// who actually dropped off stops being shown as live rather than sitting frozen forever.
    /// </summary>
    private const int SocketTtlSeconds = 20;

    /// <summary>
    /// The background tier reports on the order of minutes, so its fixes have to outlive a couple
    /// of missed rounds. Sending this per peer rather than letting clients assume one staleness
    /// window is what lets circle members and convoy riders share a single stream.
    /// </summary>
    private const int HttpTtlSeconds = 300;

    public async Task<Result> IngestAsync(
        User caller,
        LivePosition position,
        LivePositionSource source,
        CancellationToken cancellationToken)
    {
        if (!GeoPoint.IsValid(position.Latitude, position.Longitude))
            return Result.Error(ValidationKeys.Location.CoordinatesOutOfRange);

        var convoys = await groups.GetForUserAsync(caller.Id, GroupKind.Convoy, cancellationToken);
        var circles = await groups.GetForUserAsync(caller.Id, GroupKind.Circle, cancellationToken);

        var recipients = new HashSet<Guid>();
        var storedAnywhere = false;

        foreach (var group in convoys.Concat(circles))
        {
            var membership = group.FindMember(caller.Id);
            if (membership is null || !membership.IsAccepted)
                continue;

            // Server-side pause, enforced per membership. A rider paused in one circle and sharing
            // in another must disappear from exactly one of them, so this is decided per row
            // rather than once per rider. A convoy does not support pause at all — joining one is
            // itself the consent, and it lasts only as long as the ride.
            if (group.Kind.SupportsPause && !membership.CanBroadcast)
                continue;

            foreach (var member in group.Members)
            {
                if (member.IsAccepted && member.UserId != caller.Id)
                    recipients.Add(member.UserId);
            }

            // Only a kind that persists a last fix keeps a record. A convoy's position is
            // relay-only — the same spirit as fog: a live view between consenting members, not a
            // record. A rider in no circle at all therefore leaves nothing behind however long
            // they ride, whatever cadence they report at.
            if (group.Kind.PersistsLastFix)
            {
                await StoreFixAsync(group.Id, caller.Id, position, cancellationToken);
                storedAnywhere = true;
            }
        }

        if (storedAnywhere)
            await memberFixes.FlushChangesAsync(cancellationToken);

        relay.PublishPosition(
            recipients,
            new PeerPosition(
                caller.Username,
                position.Latitude,
                position.Longitude,
                Normalise(position.HeadingDegrees, 0, 360),
                Normalise(position.SpeedKmh, 0, 1000),
                position.TimestampMs > 0
                    ? position.TimestampMs
                    : DateTimeOffset.UtcNow.ToUnixTimeMilliseconds(),
                source == LivePositionSource.Socket ? SocketTtlSeconds : HttpTtlSeconds));

        return Result.Ok();
    }

    private async Task StoreFixAsync(
        Guid groupId,
        Guid userId,
        LivePosition position,
        CancellationToken cancellationToken)
    {
        var existing = await memberFixes.GetForMemberAsync(groupId, userId, cancellationToken);
        if (existing is not null)
        {
            existing.Update(position.Latitude, position.Longitude, position.AccuracyMetres, position.TimestampMs);
            return;
        }

        var (result, fix) = MemberFix.Create(
            groupId, userId, position.Latitude, position.Longitude, position.AccuracyMetres, position.TimestampMs);

        if (!result.IsFailure)
            await memberFixes.SaveAsync(fix, cancellationToken);
    }

    /// <summary>
    /// Heading and speed are optional extras on a fix, and spec §11 says an out-of-range one is
    /// dropped silently rather than voiding the position it rode in on — a bad compass reading
    /// must not take a rider off the map.
    /// </summary>
    private static double? Normalise(double? value, double min, double max) =>
        value is { } v && double.IsFinite(v) && v >= min && v <= max ? v : null;
}
