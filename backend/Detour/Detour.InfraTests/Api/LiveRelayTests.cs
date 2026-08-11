using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Detour.Api.Live;
using Detour.Domain.Groups;
using Detour.Domain.Users;
using Microsoft.Extensions.Logging.Abstractions;
using Moq;

namespace Detour.InfraTests.Api;

/// <summary>
/// The relay's two gates, exercised together because they are the whole point of it: who may see
/// a rider's position, and whether that position is ever written down.
///
/// These run against real <see cref="LiveRelay"/> and <see cref="LiveConnection"/> objects over a
/// mocked socket rather than through the HTTP stack — the interesting logic is the fan-out and the
/// coalescing, and a Testcontainers Postgres would test neither.
/// </summary>
public class LiveRelayTests
{
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    [Fact]
    public async Task Position_reaches_a_sharing_circle_and_not_a_paused_one()
    {
        var rider = NewUser("rider");
        var watching = Guid.CreateVersion7();
        var ignored = Guid.CreateVersion7();

        var sharing = CircleWith(rider.Id, watching, riderIsSharing: true);
        var paused = CircleWith(rider.Id, ignored, riderIsSharing: false);

        var harness = new Harness();
        harness.WithCircles(sharing, paused);

        var watcher = harness.Connect(watching, "watcher");
        var other = harness.Connect(ignored, "other");

        await harness.Ingest(rider, LivePositionSource.Socket);

        // The pause switch is the privacy gate, and it is per membership: the same fix reaches one
        // circle and is withheld from the other in the same call.
        (await watcher.ReadFrameAsync()).Should().Contain("\"u\":\"rider\"");
        other.HasPendingFrames.Should().BeFalse();
    }

    [Fact]
    public async Task A_convoy_relays_a_position_and_never_stores_one()
    {
        var rider = NewUser("rider");
        var peerId = Guid.CreateVersion7();
        var convoy = ConvoyWith(rider.Id, peerId);

        var harness = new Harness();
        harness.WithConvoys(convoy);

        var peer = harness.Connect(peerId, "peer");

        await harness.Ingest(rider, LivePositionSource.Socket);

        (await peer.ReadFrameAsync()).Should().Contain("\"u\":\"rider\"");

        // A convoy rider who is in no circle leaves nothing behind, however long they ride. This
        // is the property that keeps "relay-only" true now that one ingest serves both kinds.
        harness.MemberFixes.Verify(
            r => r.SaveAsync(It.IsAny<MemberFix>(), It.IsAny<CancellationToken>()), Times.Never);
        harness.MemberFixes.Verify(
            r => r.FlushChangesAsync(It.IsAny<CancellationToken>()), Times.Never);
    }

    [Fact]
    public async Task A_peer_shared_through_two_groups_is_sent_one_copy()
    {
        var rider = NewUser("rider");
        var peerId = Guid.CreateVersion7();

        var harness = new Harness();
        harness.WithConvoys(ConvoyWith(rider.Id, peerId));
        harness.WithCircles(CircleWith(rider.Id, peerId, riderIsSharing: true));

        var peer = harness.Connect(peerId, "peer");

        await harness.Ingest(rider, LivePositionSource.Socket);

        var frame = await peer.ReadFrameAsync();
        var positions = CountOccurrences(frame, "\"u\":\"rider\"");

        // Riding with someone who is also in your circle must not double their traffic. Fan-out
        // targets riders, not memberships, which is what makes this fall out for free.
        positions.Should().Be(1);
        peer.HasPendingFrames.Should().BeFalse();
    }

    [Fact]
    public async Task Time_to_live_follows_the_transport_the_fix_arrived_on()
    {
        var rider = NewUser("rider");
        var peerId = Guid.CreateVersion7();

        var harness = new Harness();
        harness.WithCircles(CircleWith(rider.Id, peerId, riderIsSharing: true));
        var peer = harness.Connect(peerId, "peer");

        await harness.Ingest(rider, LivePositionSource.Http);

        // The background tier reports minutes apart, so its fixes have to outlive several missed
        // rounds. A client pruning on one hardcoded window would flicker this peer off the map.
        (await peer.ReadFrameAsync()).Should().Contain("\"ttl\":300");
    }

    [Fact]
    public async Task Consecutive_positions_are_written_as_one_frame()
    {
        var connection = new Harness().Connect(Guid.CreateVersion7(), "peer");

        connection.Connection.Enqueue(Position("alice", 1));
        connection.Connection.Enqueue(Position("bob", 2));
        connection.Connection.Enqueue(Position("alice", 3));

        var frame = await connection.ReadFrameAsync();

        // Three updates, one frame, one packet — and the stale fix for alice is dropped rather
        // than sent alongside the newer one.
        frame.Should().Contain("\"type\":\"positions\"");
        CountOccurrences(frame, "\"u\":\"").Should().Be(2);
        frame.Should().Contain("\"ts\":3").And.NotContain("\"ts\":1");
    }

    [Fact]
    public async Task A_departure_separates_the_positions_around_it()
    {
        var harness = new Harness();
        var connection = harness.Connect(Guid.CreateVersion7(), "peer");

        connection.Connection.Enqueue(Position("alice", 1));
        connection.Connection.Enqueue(new LiveMessage(new { type = "left", user = "alice" }));
        connection.Connection.Enqueue(Position("alice", 2));

        // Order is causality here: merging across the departure would let a fix already in flight
        // resurrect a peer the client has just been told is gone.
        (await connection.ReadFrameAsync()).Should().Contain("\"ts\":1");
        (await connection.ReadFrameAsync()).Should().Contain("\"type\":\"left\"");
        (await connection.ReadFrameAsync()).Should().Contain("\"ts\":2");
    }

    [Fact]
    public async Task Losing_one_membership_leaves_a_socket_open_for_the_others()
    {
        var harness = new Harness();
        var riderId = Guid.CreateVersion7();
        var connection = harness.Connect(riderId, "rider");

        var convoyId = Guid.CreateVersion7();
        var circleId = Guid.CreateVersion7();
        connection.Connection.Join(convoyId);
        connection.Connection.Join(circleId);

        await harness.Relay.EvictAsync(riderId, circleId, CancellationToken.None);

        // A membership revoked in one group must not kill a ride the rider is still legitimately
        // part of.
        harness.Relay.ConnectedUserIds.Should().Contain(riderId);
        harness.Relay.GroupsFor(riderId).Should().BeEquivalentTo([convoyId]);

        await harness.Relay.EvictAsync(riderId, convoyId, CancellationToken.None);

        harness.Relay.ConnectedUserIds.Should().NotContain(riderId);
    }

    [Fact]
    public void A_second_connection_replaces_the_first()
    {
        var harness = new Harness();
        var riderId = Guid.CreateVersion7();

        harness.Connect(riderId, "rider");
        var reconnected = harness.Connect(riderId, "rider");

        // Spec §11: a reconnect must not leave a ghost receiving forever. One rider, one socket.
        harness.Relay.ConnectedUserIds.Should().ContainSingle();
        harness.Relay.GroupsFor(riderId).Should().BeEmpty();
        reconnected.Connection.UserId.Should().Be(riderId);
    }

    private static PeerPosition Position(string user, long timestampMs) =>
        new(user, 51.05, 3.71, null, null, timestampMs, 20);

    private static int CountOccurrences(string haystack, string needle)
    {
        var count = 0;
        for (var i = haystack.IndexOf(needle, StringComparison.Ordinal);
             i >= 0;
             i = haystack.IndexOf(needle, i + needle.Length, StringComparison.Ordinal))
            count++;

        return count;
    }

    private static User NewUser(string username)
    {
        var (_, user) = User.Create($"subject-{username}", username, null);
        return user;
    }

    private static Group CircleWith(Guid riderId, Guid otherId, bool riderIsSharing)
    {
        // Owned by the other rider so that the subject's own membership is an ordinary accepted
        // row whose sharing switch can be flipped, which is what the pause path actually looks
        // like in production.
        var (_, group) = Group.Create(GroupKind.Circle, "circle", otherId);
        var (_, member) = group.Invite(riderId);
        member.Accept();
        member.SetSharing(riderIsSharing);
        return group;
    }

    private static Group ConvoyWith(Guid riderId, Guid otherId)
    {
        var (_, group) = Group.Create(GroupKind.Convoy, "convoy", otherId);
        var (_, member) = group.Invite(riderId);
        member.Accept();
        return group;
    }

    /// <summary>Wires a real relay to mocked repositories and hands out capturing connections.</summary>
    private sealed class Harness
    {
        public LiveRelay Relay { get; } = new(NullLogger<LiveRelay>.Instance);

        public Mock<IGroupRepository> Groups { get; } = new();

        public Mock<IMemberFixRepository> MemberFixes { get; } = new();

        public Harness()
        {
            WithConvoys();
            WithCircles();
        }

        public void WithConvoys(params Group[] groups) => Setup(GroupKind.Convoy, groups);

        public void WithCircles(params Group[] groups) => Setup(GroupKind.Circle, groups);

        private void Setup(GroupKind kind, Group[] groups) =>
            Groups.Setup(r => r.GetForUserAsync(It.IsAny<Guid>(), kind, It.IsAny<CancellationToken>()))
                .ReturnsAsync([.. groups]);

        public Task Ingest(User rider, LivePositionSource source) =>
            new LiveLocationService(Groups.Object, MemberFixes.Object, Relay)
                .IngestAsync(
                    new LiveRider(rider.Id, rider.Username),
                    new LivePosition(51.05431, 3.71742, 12.0, 142.5, 48.3, 1_754_923_456_789),
                    source,
                    CancellationToken.None);

        public CapturingConnection Connect(Guid userId, string username)
        {
            var capture = new CapturingConnection(userId, username);
            Relay.Register(capture.Connection);
            return capture;
        }
    }

    /// <summary>
    /// A connection over a mocked socket, with the writer running so batching behaves exactly as
    /// it does in production.
    /// </summary>
    private sealed class CapturingConnection
    {
        private readonly List<string> _sent = [];
        private readonly SemaphoreSlim _written = new(0);

        public LiveConnection Connection { get; }

        public bool HasPendingFrames
        {
            get
            {
                lock (_sent)
                    return _sent.Count > 0;
            }
        }

        public CapturingConnection(Guid userId, string username)
        {
            Connection = new LiveConnection(userId, username, new CapturingSocket(Record));
            _ = Connection.RunWriterAsync(JsonOptions, CancellationToken.None);
        }

        private void Record(string frame)
        {
            lock (_sent)
                _sent.Add(frame);

            _written.Release();
        }

        public async Task<string> ReadFrameAsync()
        {
            (await _written.WaitAsync(TimeSpan.FromSeconds(5))).Should().BeTrue("a frame should have been written");

            lock (_sent)
            {
                var frame = _sent[0];
                _sent.RemoveAt(0);
                return frame;
            }
        }
    }

    /// <summary>
    /// A real <see cref="WebSocket"/> rather than a mock: the send overload the writer binds to is
    /// an implicit-conversion choice between <c>ArraySegment</c> and <c>ReadOnlyMemory</c>, and
    /// overriding both here makes the capture independent of which one the compiler picks.
    /// </summary>
    private sealed class CapturingSocket(Action<string> onFrame) : WebSocket
    {
        public override WebSocketState State => WebSocketState.Open;

        public override WebSocketCloseStatus? CloseStatus => null;

        public override string? CloseStatusDescription => null;

        public override string? SubProtocol => null;

        public override Task SendAsync(
            ArraySegment<byte> buffer,
            WebSocketMessageType messageType,
            bool endOfMessage,
            CancellationToken cancellationToken)
        {
            onFrame(Encoding.UTF8.GetString(buffer.Array!, buffer.Offset, buffer.Count));
            return Task.CompletedTask;
        }

        public override ValueTask SendAsync(
            ReadOnlyMemory<byte> buffer,
            WebSocketMessageType messageType,
            bool endOfMessage,
            CancellationToken cancellationToken)
        {
            onFrame(Encoding.UTF8.GetString(buffer.Span));
            return ValueTask.CompletedTask;
        }

        public override Task<WebSocketReceiveResult> ReceiveAsync(
            ArraySegment<byte> buffer, CancellationToken cancellationToken) =>
            Task.FromException<WebSocketReceiveResult>(new NotSupportedException());

        public override Task CloseAsync(
            WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public override Task CloseOutputAsync(
            WebSocketCloseStatus closeStatus, string? statusDescription, CancellationToken cancellationToken) =>
            Task.CompletedTask;

        public override void Abort()
        {
        }

        public override void Dispose()
        {
        }
    }
}
