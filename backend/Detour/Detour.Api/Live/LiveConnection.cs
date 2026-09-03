using System.Collections.Concurrent;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using System.Threading.Channels;

namespace Detour.Api.Live;

/// <summary>
/// One rider's open socket, and everything the relay needs to route to it.
///
/// A rider gets exactly one of these at a time — see <see cref="LiveRelay"/> for why a second
/// connection evicts the first rather than running alongside it. Group membership is held here
/// rather than looked up per frame so that a rider who is joined to a convoy and three circles
/// costs one set lookup on the hot path instead of four database round trips.
/// </summary>
public sealed class LiveConnection(Guid userId, WebSocket socket)
{
    /// <summary>
    /// Deep enough that it is only ever reached by a socket that has stopped draining entirely.
    /// Positions coalesce in <see cref="RunWriterAsync"/>, so a healthy connection holds a
    /// handful of items even mid-convoy.
    /// ponytail: DropOldest is right for positions (a stale fix is worthless) and merely
    /// survivable for votes. A rider would have to stall ~28s at eight peers to evict one, which
    /// is past the keep-alive timeout — the socket is already dead by then. Split the channel by
    /// priority if that ever stops being true.
    /// </summary>
    private readonly Channel<LiveOutbound> _outbound = Channel.CreateBounded<LiveOutbound>(
        new BoundedChannelOptions(256)
        {
            FullMode = BoundedChannelFullMode.DropOldest,
            SingleReader = true,
            SingleWriter = false,
        });

    private readonly ConcurrentDictionary<Guid, byte> _groups = new();

    /// <summary>
    /// Inbound budget, in frames.
    ///
    /// Every position frame costs database work — the sender's memberships have to be read to
    /// know who may see it — so an unthrottled socket turns one client into thousands of queries
    /// a second, whether it is hostile or merely looping on a bug. A rider sends one position
    /// every two seconds and a handful of votes per ride, so the honest ceiling is far below this;
    /// the headroom is for a reconnect burst replaying joins, not for a steady stream.
    /// </summary>
    private const int InboundBurst = 40;

    private const double InboundPerSecond = 20;

    private readonly Lock _budgetLock = new();
    private double _budget = InboundBurst;
    private long _budgetStampMs = Environment.TickCount64;

    public Guid UserId { get; } = userId;

    /// <summary>Groups this socket has successfully joined. Joining adds; it never replaces.</summary>
    public IReadOnlyCollection<Guid> Groups => [.. _groups.Keys];

    public bool IsJoinedTo(Guid groupId) => _groups.ContainsKey(groupId);

    public void Join(Guid groupId) => _groups.TryAdd(groupId, 0);

    /// <summary>
    /// Drops one membership. Returns whether the socket still holds any other, which is what
    /// decides between evicting a rider from one group and closing their connection outright —
    /// a membership revoked in one circle must not silently kill a convoy they are still
    /// legitimately in.
    /// </summary>
    public bool Part(Guid groupId)
    {
        _groups.TryRemove(groupId, out _);
        return !_groups.IsEmpty;
    }

    /// <summary>
    /// Takes one frame from the inbound budget, refilling it for the time since the last call.
    /// False means this frame is dropped — not that the connection is closed. Dropping is the
    /// right answer here: a position is worthless a moment later anyway, and closing on a burst
    /// would punish a rider whose phone woke up and replayed its joins.
    /// </summary>
    public bool TryTakeInboundBudget()
    {
        lock (_budgetLock)
        {
            var now = Environment.TickCount64;
            // Monotonic and immune to a clock change mid-ride, which a wall clock is not.
            var elapsedMs = Math.Max(0, now - _budgetStampMs);
            _budgetStampMs = now;

            _budget = Math.Min(InboundBurst, _budget + elapsedMs * InboundPerSecond / 1000d);

            if (_budget < 1)
                return false;

            _budget -= 1;
            return true;
        }
    }

    public void Enqueue(LiveOutbound frame) => _outbound.Writer.TryWrite(frame);

    public void CompleteOutbound() => _outbound.Writer.TryComplete();

    /// <summary>
    /// Drains the outbound queue to the socket, merging runs of position updates into a single
    /// <c>positions</c> frame.
    ///
    /// The merge is what makes a convoy cheap: eight riders otherwise means seven separate frames
    /// — seven TCP segments, seven radio wake-ups — per round, and batching them cuts both the
    /// bytes (one envelope, one <c>type</c>) and, more importantly on a phone, the packet count.
    /// It costs nothing when idle: with one position queued the batch is one position, written
    /// immediately. Coalescing only kicks in when frames arrive faster than the socket drains,
    /// which is exactly when it should.
    ///
    /// Runs are merged, not the whole drain: a <c>left</c> between two positions still separates
    /// them, so a peer cannot be removed and then resurrected by a fix that was already in
    /// flight behind it.
    /// </summary>
    public async Task RunWriterAsync(JsonSerializerOptions jsonOptions, CancellationToken cancellationToken)
    {
        var pending = new List<PeerPosition>();

        while (await _outbound.Reader.WaitToReadAsync(cancellationToken).ConfigureAwait(false))
        {
            while (_outbound.Reader.TryRead(out var frame))
            {
                switch (frame)
                {
                    case PeerPosition position:
                        // Last fix wins: an older one for the same peer is not worth a byte.
                        // Ids, so two fixes from one rider always collapse. This compared handles
                        // until #133, and an ordinal compare on a value the database stores as
                        // citext could see one rider's two spellings as two riders.
                        pending.RemoveAll(p => p.User == position.User);
                        pending.Add(position);
                        break;

                    case LiveMessage message:
                        await FlushPositionsAsync(pending, jsonOptions, cancellationToken).ConfigureAwait(false);
                        await SendAsync(message.Payload, jsonOptions, cancellationToken).ConfigureAwait(false);
                        break;
                }
            }

            await FlushPositionsAsync(pending, jsonOptions, cancellationToken).ConfigureAwait(false);
        }
    }

    private async Task FlushPositionsAsync(
        List<PeerPosition> pending,
        JsonSerializerOptions jsonOptions,
        CancellationToken cancellationToken)
    {
        if (pending.Count == 0)
            return;

        var frame = new PositionsFrame([.. pending]);
        pending.Clear();
        await SendAsync(frame, jsonOptions, cancellationToken).ConfigureAwait(false);
    }

    private async Task SendAsync(object payload, JsonSerializerOptions jsonOptions, CancellationToken cancellationToken)
    {
        if (socket.State != WebSocketState.Open)
            return;

        var json = JsonSerializer.Serialize(payload, payload.GetType(), jsonOptions);
        var bytes = Encoding.UTF8.GetBytes(json);
        await socket.SendAsync(bytes, WebSocketMessageType.Text, endOfMessage: true, cancellationToken)
            .ConfigureAwait(false);
    }

    /// <summary>
    /// Sends the close frame and returns, without waiting for the peer to send one back.
    ///
    /// <c>CloseOutputAsync</c> rather than <c>CloseAsync</c> deliberately: the full handshake
    /// blocks until the other end replies, and eviction is driven from inside a request — a rider
    /// leaving a group would otherwise hang their own HTTP call until the socket being evicted got
    /// around to answering, which a backgrounded or dead phone may never do. Half-closing is
    /// enough: the read loop sees it, unregisters, and disposes.
    /// </summary>
    public async Task CloseAsync(string reason, CancellationToken cancellationToken)
    {
        CompleteOutbound();

        if (socket.State is not (WebSocketState.Open or WebSocketState.CloseReceived))
            return;

        try
        {
            await socket.CloseOutputAsync(WebSocketCloseStatus.NormalClosure, reason, cancellationToken)
                .ConfigureAwait(false);
        }
        catch (Exception exception) when (exception is WebSocketException or ObjectDisposedException
                                              or OperationCanceledException)
        {
            // The peer is already gone. Nothing to tell it, and nothing here that a caller can act on.
        }
    }
}
