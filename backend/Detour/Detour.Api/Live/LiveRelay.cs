using System.Collections.Concurrent;
using System.Net.WebSockets;

namespace Detour.Api.Live;

/// <summary>
/// The registry of open live connections, and the only thing that writes to them.
///
/// Singleton by necessity: a connection outlives the request that opened it, and a frame raised
/// while handling one rider's request has to reach a socket opened by another's.
/// </summary>
public interface ILiveRelay
{
    /// <summary>Riders currently holding a socket. The sweep's work list.</summary>
    IReadOnlyCollection<Guid> ConnectedUserIds { get; }

    /// <summary>
    /// Delivers a circle presence event to every accepted member that is currently connected.
    /// Callers outside this namespace get this narrow surface rather than the frame types, so a
    /// service cannot accidentally invent a new wire message.
    /// </summary>
    void PublishPlaceEvent(
        IEnumerable<Guid> recipientUserIds,
        Guid groupId,
        string username,
        long placeId,
        string placeName,
        string kind,
        long timestampMs);

    /// <summary>
    /// Drops one rider's membership of one group, closing their socket only if that leaves them
    /// with nothing else joined. Called when a membership is revoked out from under a live
    /// connection.
    /// </summary>
    Task EvictAsync(Guid userId, Guid groupId, CancellationToken cancellationToken);
}

public sealed class LiveRelay(ILogger<LiveRelay> logger) : ILiveRelay
{
    /// <summary>
    /// One connection per rider. Spec §11 requires a second connection to close the first rather
    /// than leave a ghost receiving forever; keying on the rider rather than on (rider, group)
    /// makes that the only possible outcome instead of a rule to remember.
    /// </summary>
    private readonly ConcurrentDictionary<Guid, LiveConnection> _connections = new();

    public IReadOnlyCollection<Guid> ConnectedUserIds => [.. _connections.Keys];

    /// <summary>Groups one rider's socket currently holds, or nothing if they are not connected.</summary>
    public IReadOnlyCollection<Guid> GroupsFor(Guid userId) =>
        _connections.TryGetValue(userId, out var connection) ? connection.Groups : [];

    /// <summary>
    /// Registers <paramref name="connection"/>, evicting whatever that rider had open. The
    /// eviction is deliberately not awaited on the caller's critical path — a peer that has
    /// stopped reading must not be able to stall the reconnect that replaced it.
    /// </summary>
    public LiveConnection Register(LiveConnection connection)
    {
        if (_connections.TryRemove(connection.UserId, out var previous))
        {
            logger.LogDebug("Replacing live connection for {UserId}", connection.UserId);
            _ = CloseQuietlyAsync(previous, "replaced by a newer connection");
        }

        _connections[connection.UserId] = connection;
        return connection;
    }

    /// <summary>
    /// Removes <paramref name="connection"/> and tells everyone who shared a group with it that
    /// the rider is gone.
    ///
    /// The recipient set is computed from the connections themselves rather than from the
    /// database: teardown runs on a dropped socket, often while the process is shutting down or
    /// the network is already gone, and a query there would be both slow and liable to fail at
    /// the worst moment. Anyone not currently connected has nothing to be told.
    /// </summary>
    public void Unregister(LiveConnection connection)
    {
        // Only if it is still the current one — a fast reconnect may already have replaced it,
        // and this teardown must not evict the connection that superseded it.
        _connections.TryRemove(new KeyValuePair<Guid, LiveConnection>(connection.UserId, connection));

        connection.CompleteOutbound();

        var groups = connection.Groups;
        if (groups.Count == 0)
            return;

        var frame = new LiveMessage(new LeftFrame(connection.Username));
        foreach (var peer in _connections.Values)
        {
            if (peer.UserId != connection.UserId && groups.Any(peer.IsJoinedTo))
                peer.Enqueue(frame);
        }
    }

    /// <summary>
    /// Queues one peer's position for every recipient that currently holds a socket. Recipients
    /// who are offline are simply skipped: a position is a live view, never a message to store
    /// and deliver later.
    /// </summary>
    public void PublishPosition(IEnumerable<Guid> recipientUserIds, PeerPosition position)
    {
        foreach (var userId in recipientUserIds)
        {
            if (_connections.TryGetValue(userId, out var connection))
                connection.Enqueue(position);
        }
    }

    /// <summary>
    /// Queues a frame for every recipient joined to <paramref name="groupId"/>. The join check is
    /// the second half of the privacy gate: accepted membership says a rider <em>may</em> receive
    /// a group's traffic, and this says they actually asked to.
    /// </summary>
    public void PublishToGroup(IEnumerable<Guid> recipientUserIds, Guid groupId, object payload)
    {
        var frame = new LiveMessage(payload);
        foreach (var userId in recipientUserIds)
        {
            if (_connections.TryGetValue(userId, out var connection) && connection.IsJoinedTo(groupId))
                connection.Enqueue(frame);
        }
    }

    public void PublishPlaceEvent(
        IEnumerable<Guid> recipientUserIds,
        Guid groupId,
        string username,
        long placeId,
        string placeName,
        string kind,
        long timestampMs) =>
        PublishToGroup(
            recipientUserIds,
            groupId,
            new PlaceEventFrame(groupId, username, placeId, placeName, kind, timestampMs));

    public Task EvictAsync(Guid userId, Guid groupId, CancellationToken cancellationToken)
    {
        if (!_connections.TryGetValue(userId, out var connection))
            return Task.CompletedTask;

        // Dropping the membership and de-registering are synchronous, so traffic stops before this
        // returns — which is the part callers actually depend on. Only the close frame is left to
        // finish on its own, for the same reason a replaced connection is closed off-path: a
        // socket whose send buffer has filled must not stall the request that evicted it.
        if (connection.Part(groupId))
            return Task.CompletedTask;

        _connections.TryRemove(new KeyValuePair<Guid, LiveConnection>(userId, connection));
        _ = CloseQuietlyAsync(connection, "membership revoked");
        return Task.CompletedTask;
    }

    private async Task CloseQuietlyAsync(LiveConnection connection, string reason)
    {
        try
        {
            await connection.CloseAsync(reason, CancellationToken.None).ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is WebSocketException or OperationCanceledException
                                              or ObjectDisposedException)
        {
            logger.LogDebug(exception, "Closing live connection for {UserId} failed", connection.UserId);
        }
    }
}
