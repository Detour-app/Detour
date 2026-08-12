using System.Buffers;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Domain;
using Detour.Domain.Groups;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Shared.Api.Middlewares;

namespace Detour.Api.Live;

/// <summary>
/// The live relay: one duplex connection per rider, carrying their position up and everyone
/// else's down.
///
/// One socket, many groups. A rider in a circle all day who also starts a convoy for a ride needs
/// both at once, so joining <em>adds</em> a membership rather than replacing one. Position frames
/// deliberately carry no group: a fix belongs to the rider, and the relay resolves who may see it
/// from their memberships. Everything that genuinely is convoy-scoped — destination offers and
/// votes — still names its group.
/// </summary>
[ApiController]
[Route("api/live")]
[Authorize(Policy = DetourPolicies.Rider)]
// The socket outlives the request that opened it. Without this the transaction middleware would
// hold a database transaction — and a pooled connection — open for the whole ride.
[SkipTransaction]
public class LiveController(
    ICurrentUser currentUser,
    LiveRelay relay,
    IServiceScopeFactory scopeFactory,
    ILogger<LiveController> logger) : ControllerBase
{
    /// <summary>
    /// Large enough for the biggest frame the protocol defines (a bounded voice chunk), small
    /// enough that a hostile client cannot make the server allocate on demand. Anything longer is
    /// drained and dropped rather than buffered.
    /// </summary>
    private const int MaxFrameBytes = 32 * 1024;

    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    [HttpGet]
    [EndpointSummary("Open the live relay socket.")]
    [EndpointDescription(
        "Upgrades to a WebSocket carrying live positions, destination offers and votes, and "
        + "circle presence events. Authenticated with the same bearer token as the rest of the "
        + "API. Everything else keeps working when this cannot start: only live position and "
        + "voice go away.")]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task Get(CancellationToken cancellationToken)
    {
        if (!HttpContext.WebSockets.IsWebSocketRequest)
        {
            Response.StatusCode = StatusCodes.Status400BadRequest;
            return;
        }

        var user = await currentUser.GetAsync(cancellationToken);
        using var socket = await HttpContext.WebSockets.AcceptWebSocketAsync();

        var connection = relay.Register(new LiveConnection(user.Id, user.Username, socket));
        using var connectionClosed = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);

        var writer = connection.RunWriterAsync(JsonOptions, connectionClosed.Token);

        try
        {
            await ReadLoopAsync(socket, connection, connectionClosed.Token);
        }
        catch (OperationCanceledException)
        {
            // Shutdown, or the rider went away. Neither is an error worth logging.
        }
        catch (WebSocketException exception)
        {
            logger.LogDebug(exception, "Live socket for {UserId} ended abruptly", user.Id);
        }
        finally
        {
            relay.Unregister(connection);
            await connectionClosed.CancelAsync();
            await SwallowAsync(writer);
        }
    }

    private async Task ReadLoopAsync(
        WebSocket socket,
        LiveConnection connection,
        CancellationToken cancellationToken)
    {
        var buffer = ArrayPool<byte>.Shared.Rent(MaxFrameBytes);

        try
        {
            while (socket.State == WebSocketState.Open && !cancellationToken.IsCancellationRequested)
            {
                var frame = await ReadFrameAsync(socket, buffer, cancellationToken);
                if (frame is null)
                    return;

                if (frame.Length == 0)
                    continue;

                await DispatchAsync(connection, frame, cancellationToken);
            }
        }
        finally
        {
            ArrayPool<byte>.Shared.Return(buffer);
        }
    }

    /// <summary>
    /// Reads one whole message. Returns null once the peer closes, and an empty string for a
    /// message that was closed, oversized, or otherwise not worth parsing — an over-long frame is
    /// drained off the wire so the stream stays framed, then discarded.
    /// </summary>
    private static async Task<string?> ReadFrameAsync(
        WebSocket socket,
        byte[] buffer,
        CancellationToken cancellationToken)
    {
        var length = 0;
        var oversized = false;

        while (true)
        {
            // Once the message is known to be too long, keep reading it into the front of the
            // buffer and throw the bytes away. Reading has to continue either way — a message left
            // half-consumed desynchronises the stream — but appending must not, and the offset is
            // what makes the difference: continuing to append would eventually ask for a
            // zero-length segment, which can never make progress and wedges this rider's read loop
            // for the rest of the ride while the relay still counts them as connected.
            var offset = oversized ? 0 : length;

            var result = await socket.ReceiveAsync(
                new ArraySegment<byte>(buffer, offset, buffer.Length - offset), cancellationToken);

            if (result.MessageType == WebSocketMessageType.Close)
                return null;

            if (!oversized)
                length += result.Count;

            if (result.EndOfMessage)
                break;

            // A continuation with no room left: stop growing, keep draining.
            if (!oversized && length >= buffer.Length)
                oversized = true;
        }

        if (oversized)
            return string.Empty;

        return Encoding.UTF8.GetString(buffer, 0, length);
    }

    /// <summary>
    /// Handles one frame in its own dependency-injection scope.
    ///
    /// Per message rather than per connection, because repositories and the database context are
    /// scoped: holding one for the life of a socket would pin a connection for an entire ride and
    /// serve every frame from a change tracker that only grows.
    /// </summary>
    private async Task DispatchAsync(
        LiveConnection connection,
        string frame,
        CancellationToken cancellationToken)
    {
        JsonElement root;
        try
        {
            using var document = JsonDocument.Parse(frame);
            root = document.RootElement.Clone();
        }
        catch (JsonException)
        {
            // A malformed frame is the client's problem. Dropping it keeps one bad message from
            // taking down a connection that is otherwise fine.
            return;
        }

        if (root.ValueKind != JsonValueKind.Object
            || !root.TryGetProperty("type", out var typeElement)
            || typeElement.ValueKind != JsonValueKind.String)
            return;

        // Checked after parsing but before any handler runs, so the budget guards the expensive
        // half — the database work a frame causes — rather than the cheap parse. A dropped frame
        // is silent: telling a flooding client which frames it lost is a second channel to flood.
        if (!connection.TryTakeInboundBudget())
            return;

        await using var scope = scopeFactory.CreateAsyncScope();

        switch (typeElement.GetString())
        {
            case LiveFrameTypes.Join:
                await HandleJoinAsync(scope.ServiceProvider, connection, root, cancellationToken);
                break;

            case LiveFrameTypes.Location:
                await HandleLocationAsync(scope.ServiceProvider, connection, root, cancellationToken);
                break;

            case LiveFrameTypes.DestinationOffer:
                await HandleOfferAsync(scope.ServiceProvider, connection, root, cancellationToken);
                break;

            case LiveFrameTypes.DestinationVote:
                await HandleVoteAsync(scope.ServiceProvider, connection, root, cancellationToken);
                break;

                // Voice (ptt_start / ptt_audio / ptt_end) is deliberately unhandled: the frames are
                // accepted off the wire and dropped, exactly as any unknown type is, so a client that
                // still sends them stays connected and everything else keeps working.
        }
    }

    private async Task HandleJoinAsync(
        IServiceProvider services,
        LiveConnection connection,
        JsonElement frame,
        CancellationToken cancellationToken)
    {
        if (!TryReadGuid(frame, "groupId", out var groupId))
            return;

        var groups = services.GetRequiredService<IGroupRepository>();
        var membership = await groups.GetAcceptedMembershipAsync(groupId, connection.UserId, cancellationToken);

        if (membership is null)
        {
            // Answered the same way whether the group does not exist or the rider is simply not
            // in it — otherwise a socket becomes an oracle for enumerating group ids.
            connection.Enqueue(new LiveMessage(new ErrorFrame("Not a member of that group")));
            return;
        }

        connection.Join(groupId);
        connection.Enqueue(new LiveMessage(new JoinedFrame(groupId)));
    }

    private static async Task HandleLocationAsync(
        IServiceProvider services,
        LiveConnection connection,
        JsonElement frame,
        CancellationToken cancellationToken)
    {
        if (!TryReadDouble(frame, "lat", out var latitude) || !TryReadDouble(frame, "lon", out var longitude))
            return;

        var position = new LivePosition(
            latitude,
            longitude,
            TryReadDouble(frame, "accuracyM", out var accuracy) ? accuracy : null,
            TryReadDouble(frame, "headingDeg", out var heading) ? heading : null,
            TryReadDouble(frame, "speedKmh", out var speed) ? speed : null,
            TryReadInt64(frame, "ts", out var timestamp) ? timestamp : 0);

        // Identity comes off the connection rather than out of the database: it was established
        // by the token at upgrade time and cannot change while the socket is open, so re-reading
        // the row per position would be a third query on the hottest path in the relay for an
        // answer that is already known.
        var locations = services.GetRequiredService<ILiveLocationService>();
        await locations.IngestAsync(
            new LiveRider(connection.UserId, connection.Username),
            position,
            LivePositionSource.Socket,
            cancellationToken);
    }

    private async Task HandleOfferAsync(
        IServiceProvider services,
        LiveConnection connection,
        JsonElement frame,
        CancellationToken cancellationToken)
    {
        var group = await RequireVotingGroupAsync(services, connection, frame, cancellationToken);
        if (group is null)
            return;

        if (!frame.TryGetProperty("candidates", out var candidatesElement)
            || candidatesElement.ValueKind != JsonValueKind.Array)
            return;

        var candidates = new List<DestinationCandidateFrame>();
        foreach (var element in candidatesElement.EnumerateArray())
        {
            var candidate = ReadCandidate(element);

            // One invalid candidate voids the whole frame rather than silently relaying a shorter
            // list: a rider must never vote on a sheet that is missing an option their peers can
            // see, because the tally would then differ between devices.
            if (candidate is null)
                return;

            candidates.Add(candidate);
        }

        if (candidates.Count is 0 || candidates.Count > DetourLimits.MaxDestinationCandidates)
            return;

        relay.PublishToGroup(
            await AcceptedMemberIdsAsync(services, group.Id, connection.UserId, cancellationToken),
            group.Id,
            new DestinationOfferFrame(group.Id, connection.Username, candidates));
    }

    private async Task HandleVoteAsync(
        IServiceProvider services,
        LiveConnection connection,
        JsonElement frame,
        CancellationToken cancellationToken)
    {
        var group = await RequireVotingGroupAsync(services, connection, frame, cancellationToken);
        if (group is null)
            return;

        // Anything outside the sheet is dropped rather than relayed as a vote for a candidate that
        // was never offered.
        if (!TryReadInt32(frame, "index", out var index)
            || index < 0
            || index >= DetourLimits.MaxDestinationCandidates)
            return;

        relay.PublishToGroup(
            await AcceptedMemberIdsAsync(services, group.Id, connection.UserId, cancellationToken),
            group.Id,
            new DestinationVoteFrame(group.Id, connection.Username, index));
    }

    /// <summary>
    /// Resolves the group a voting frame names, or null if it is not one this rider may vote in.
    /// Re-checked per frame rather than trusted from join time: a membership can be revoked while
    /// a socket is open, and the join set alone would keep relaying until the sweep caught up.
    /// </summary>
    private static async Task<Group?> RequireVotingGroupAsync(
        IServiceProvider services,
        LiveConnection connection,
        JsonElement frame,
        CancellationToken cancellationToken)
    {
        if (!TryReadGuid(frame, "groupId", out var groupId) || !connection.IsJoinedTo(groupId))
            return null;

        var groups = services.GetRequiredService<IGroupRepository>();
        var group = await groups.GetWithMembersAsync(groupId, cancellationToken);

        if (group is null
            || !group.Kind.AllowsDestinationVote
            || !group.IsAcceptedMember(connection.UserId))
            return null;

        return group;
    }

    private static async Task<IReadOnlyCollection<Guid>> AcceptedMemberIdsAsync(
        IServiceProvider services,
        Guid groupId,
        Guid excludeUserId,
        CancellationToken cancellationToken)
    {
        var groups = services.GetRequiredService<IGroupRepository>();
        var ids = await groups.GetAcceptedMemberIdsAsync(groupId, cancellationToken);
        return [.. ids.Where(id => id != excludeUserId)];
    }

    private static DestinationCandidateFrame? ReadCandidate(JsonElement element)
    {
        if (element.ValueKind != JsonValueKind.Object
            || !TryReadDouble(element, "lat", out var latitude)
            || !TryReadDouble(element, "lon", out var longitude)
            || !GeoPoint.IsValid(latitude, longitude))
            return null;

        var name = element.TryGetProperty("name", out var nameElement)
                   && nameElement.ValueKind == JsonValueKind.String
            ? nameElement.GetString()
            : null;

        if (name is not null && name.Length > DetourLimits.DestinationNameMaxLength)
            return null;

        return new DestinationCandidateFrame(
            latitude,
            longitude,
            TryReadDouble(element, "distanceM", out var distance) ? distance : null,
            TryReadDouble(element, "durationS", out var duration) ? duration : null,
            name);
    }

    private static bool TryReadGuid(JsonElement element, string name, out Guid value)
    {
        value = Guid.Empty;
        return element.TryGetProperty(name, out var property)
               && property.ValueKind == JsonValueKind.String
               && Guid.TryParse(property.GetString(), out value);
    }

    private static bool TryReadDouble(JsonElement element, string name, out double value)
    {
        value = 0;
        return element.TryGetProperty(name, out var property)
               && property.ValueKind == JsonValueKind.Number
               && property.TryGetDouble(out value)
               && double.IsFinite(value);
    }

    private static bool TryReadInt64(JsonElement element, string name, out long value)
    {
        value = 0;
        return element.TryGetProperty(name, out var property)
               && property.ValueKind == JsonValueKind.Number
               && property.TryGetInt64(out value);
    }

    private static bool TryReadInt32(JsonElement element, string name, out int value)
    {
        value = 0;
        return element.TryGetProperty(name, out var property)
               && property.ValueKind == JsonValueKind.Number
               && property.TryGetInt32(out value);
    }

    private static async Task SwallowAsync(Task task)
    {
        try
        {
            await task;
        }
        catch (Exception exception) when (exception is OperationCanceledException or WebSocketException
                                              or ObjectDisposedException)
        {
            // The writer losing its socket is the normal way this ends.
        }
    }
}
