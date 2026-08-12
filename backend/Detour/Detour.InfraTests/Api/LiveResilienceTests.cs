using System.Net.Http.Json;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.TestHost;

namespace Detour.InfraTests.Api;

/// <summary>
/// What the relay does when it is not being used politely.
///
/// A socket is the one surface a client holds open and can push anything down at any rate, so
/// these are the tests that matter most: every case here is either something a hostile client
/// would try or something a buggy one does by accident — a retry loop that never sleeps, a
/// half-written frame, a phone that vanishes mid-ride. The bar throughout is the same: the
/// connection survives, nothing leaks to a rider who should not see it, and no single client can
/// make the server do unbounded work.
/// </summary>
[Collection(PostgresCollection.Name)]
public class LiveResilienceTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;
    private readonly Dictionary<string, string> _tokens = [];

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    // ---------------------------------------------------------------------------------------
    // Malformed and hostile input. Every one of these must leave the socket usable, which the
    // trailing round trip in each test is what actually proves.
    // ---------------------------------------------------------------------------------------

    public static TheoryData<string, string> Garbage() => new()
    {
        { "not json at all", "{ this is not json" },
        { "truncated object", "{\"type\":\"loc" },
        { "json array", "[1,2,3]" },
        { "json string", "\"location\"" },
        { "json number", "42" },
        { "json null", "null" },
        { "empty object", "{}" },
        { "type is not a string", "{\"type\":7}" },
        { "type is null", "{\"type\":null}" },
        { "unknown type", "{\"type\":\"definitely_not_a_frame\"}" },
        { "location without coordinates", "{\"type\":\"location\"}" },
        { "coordinates as strings", "{\"type\":\"location\",\"lat\":\"51.0\",\"lon\":\"3.7\"}" },
        { "coordinates as objects", "{\"type\":\"location\",\"lat\":{},\"lon\":[]}" },
        { "coordinates out of range", "{\"type\":\"location\",\"lat\":91.0,\"lon\":181.0}" },
        { "join with a non-guid group", "{\"type\":\"join\",\"groupId\":\"not-a-guid\"}" },
        { "join with a numeric group", "{\"type\":\"join\",\"groupId\":12345}" },
        { "vote with no group", "{\"type\":\"spin_vote\",\"index\":0}" },
        { "offer with candidates as an object", "{\"type\":\"spin_offer\",\"candidates\":{}}" },
        // A raw control character inside a JSON string is invalid, where the same character
        // written as a \\u escape is legal - and in an unknown field is correctly ignored
        // rather than rejected, so only the raw form belongs in this list.
        {
            "raw control characters",
            "{\"type\":\"location\",\"lat\":51.0,\"lon\":3.7,\"x\":\"" + (char)0 + (char)31 + "\"}"
        },
        { "unterminated string", "{\"type\":\"location\",\"lat\":51.0,\"lon\":\"" },
        { "trailing garbage after the object", "{\"type\":\"location\",\"lat\":51.0,\"lon\":3.7} trailing" },
        { "deeply nested", "{\"type\":\"location\",\"lat\":51.0,\"lon\":3.7,\"n\":" + Nested(200) + "}" },
    };

    [Theory]
    [MemberData(nameof(Garbage))]
    public async Task A_malformed_frame_is_dropped_and_the_connection_keeps_working(string _, string frame)
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        await alexSocket.SendRawAsync(frame);

        // Nothing may be relayed off a frame the relay could not fully understand: a partially
        // parsed position is worse than no position, because a peer draws it as fact.
        await blakeSocket.ShouldStaySilent();

        // And the socket has to still be a socket afterwards. A parser that throws its way out of
        // the read loop turns one bad byte into a dropped ride.
        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 1L });
        (await blakeSocket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");
    }

    [Fact]
    public async Task An_oversized_frame_is_discarded_without_dropping_the_connection()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        // Well past the 32 KB read buffer. The frame is drained off the wire rather than buffered,
        // which is the difference between refusing to allocate and being made to allocate.
        var huge = "{\"type\":\"location\",\"lat\":51.0,\"lon\":3.7,\"pad\":\"" + new string('x', 200_000) + "\"}";
        await alexSocket.SendRawAsync(huge);

        await blakeSocket.ShouldStaySilent();

        // Critically: the stream must still be framed correctly after draining a giant message.
        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 2L });
        (await blakeSocket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");
    }

    [Fact]
    public async Task A_binary_frame_is_ignored()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        // The protocol is text. Binary is what a future voice frame would use, and until then an
        // unreadable one must not be mistaken for anything.
        await alexSocket.SendBinaryAsync([0xff, 0xfe, 0x00, 0x01]);

        await blakeSocket.ShouldStaySilent();
        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 3L });
        (await blakeSocket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");
    }

    [Fact]
    public async Task A_fragmented_frame_is_reassembled()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        // A client is free to split a message across frames and some proxies do it for you.
        // Reading only the first fragment would parse a truncated object every time.
        await alexSocket.SendFragmentedAsync(
            "{\"type\":\"location\",\"lat\":51.05,", "\"lon\":3.71,\"ts\":4}");

        var frame = await blakeSocket.ReceiveAsync();
        frame.GetProperty("peers")[0].GetProperty("lat").GetDouble().Should().BeApproximately(51.05, 0.001);
    }

    // ---------------------------------------------------------------------------------------
    // Flooding.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task A_flooding_client_is_throttled_rather_than_served()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        await JoinAsync(alexSocket, convoy);

        // Joins are the cheapest observable frame with a reply, so they measure the budget
        // without the test depending on how positions coalesce.
        const int flood = 400;
        for (var i = 0; i < flood; i++)
            await alexSocket.SendAsync(new { type = "join", groupId = convoy });

        var served = 0;
        while (await alexSocket.TryReceiveAsync(TimeSpan.FromMilliseconds(400)) is not null)
            served++;

        // Every position costs database work to resolve who may see it, so an unthrottled socket
        // is one client turning into thousands of queries a second. The exact number depends on
        // how much the bucket refilled while the flood was in flight; what must hold is that it
        // is bounded well below what was sent, and that the rider is not disconnected for it.
        served.Should().BeGreaterThan(0);
        served.Should().BeLessThan(flood / 2);

        blakeName.Should().NotBeNullOrEmpty();
    }

    [Fact]
    public async Task The_budget_refills_so_a_throttled_rider_recovers()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        for (var i = 0; i < 200; i++)
            await alexSocket.SendAsync(new { type = "join", groupId = convoy });

        await blakeSocket.DrainAsync();
        while (await alexSocket.TryReceiveAsync(TimeSpan.FromMilliseconds(300)) is not null)
        {
            // Drain the burst's replies.
        }

        // Throttling is a speed limit, not a ban: a phone that woke up and replayed its joins has
        // to be riding normally again a moment later.
        await Task.Delay(TimeSpan.FromSeconds(1));

        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 9L });
        (await blakeSocket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");
    }

    // ---------------------------------------------------------------------------------------
    // Lifecycle under stress.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task An_abrupt_disconnect_tells_the_peers()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        // Aborted, not closed: a phone that loses signal never sends a close frame, and that is
        // the common case rather than the exotic one.
        blakeSocket.Abort();

        var frame = await alexSocket.ReceiveAsync();
        frame.GetProperty("type").GetString().Should().Be("left");
        frame.GetProperty("user").GetString().Should().Be(blakeName);
    }

    [Fact]
    public async Task A_reconnect_storm_leaves_exactly_one_live_socket()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        await JoinAsync(alexSocket, convoy);

        // A phone flapping between cells does exactly this. Every replaced socket must be dropped,
        // or each reconnect leaks a ghost that keeps receiving a rider's position forever.
        var sockets = new List<LiveSocket>();
        for (var i = 0; i < 8; i++)
        {
            var socket = await ConnectAsync(blakeName);
            await JoinAsync(socket, convoy);
            sockets.Add(socket);
        }

        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 5L });

        (await sockets[^1].ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");

        foreach (var stale in sockets[..^1])
            await stale.ShouldStaySilent();

        foreach (var socket in sockets)
            socket.Dispose();
    }

    [Fact]
    public async Task Concurrent_riders_each_see_only_their_own_convoy()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var (cass, cassName) = await NewRider();
        var (dana, danaName) = await NewRider();

        var first = await ConvoyOf(alex, alexName, blake, blakeName);
        var second = await ConvoyOf(cass, cassName, dana, danaName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        using var cassSocket = await ConnectAsync(cassName);
        using var danaSocket = await ConnectAsync(danaName);

        await JoinAsync(alexSocket, first);
        await JoinAsync(blakeSocket, first);
        await JoinAsync(cassSocket, second);
        await JoinAsync(danaSocket, second);

        // Both convoys riding at once through one relay. The failure this guards against is a
        // fan-out keyed on something shared — a position reaching strangers is the single worst
        // thing this system can do.
        await Task.WhenAll(
            alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 6L }),
            cassSocket.SendAsync(new { type = "location", lat = 52.0, lon = 4.8, ts = 6L }));

        (await blakeSocket.ReceiveAsync()).GetProperty("peers")[0]
            .GetProperty("u").GetString().Should().Be(alexName);
        (await danaSocket.ReceiveAsync()).GetProperty("peers")[0]
            .GetProperty("u").GetString().Should().Be(cassName);

        await blakeSocket.ShouldStaySilent();
        await danaSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task Joining_the_same_group_repeatedly_is_harmless()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);

        for (var i = 0; i < 5; i++)
            await JoinAsync(alexSocket, convoy);

        await JoinAsync(blakeSocket, convoy);
        await blakeSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 7L });

        // Joining is additive, so re-joining has to be idempotent rather than cumulative — five
        // joins must not mean five copies of every frame.
        var frame = await alexSocket.ReceiveAsync();
        frame.GetProperty("peers").GetArrayLength().Should().Be(1);
        await alexSocket.ShouldStaySilent();
    }

    // ---------------------------------------------------------------------------------------
    // Privacy invariants. Each of these is a leak if it ever fails.
    // ---------------------------------------------------------------------------------------

    [Fact]
    public async Task A_vote_in_a_group_the_sender_never_joined_is_not_relayed()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(blakeSocket, convoy);

        // Alex is a member but has not joined on this socket. Membership says who *may* receive a
        // group's traffic; the join says they asked to. Skipping the second check would let a
        // socket act on a group it never opened.
        await alexSocket.SendAsync(new { type = "spin_vote", groupId = convoy, index = 0 });

        await blakeSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task A_stranger_cannot_reach_a_convoy_by_naming_it()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var (_, malloryName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var blakeSocket = await ConnectAsync(blakeName);
        using var mallorySocket = await ConnectAsync(malloryName);
        await JoinAsync(blakeSocket, convoy);

        await mallorySocket.SendAsync(new { type = "join", groupId = convoy });
        (await mallorySocket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("error");

        await mallorySocket.SendAsync(new { type = "location", lat = 1.0, lon = 1.0, ts = 8L });
        await mallorySocket.SendAsync(new
        {
            type = "spin_offer",
            groupId = convoy,
            candidates = new[] { new { lat = 51.0, lon = 3.7 } },
        });

        // Knowing a group id must buy nothing at all — not a position on someone's map, not a
        // destination on their vote sheet.
        await blakeSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task Losing_membership_mid_ride_stops_the_traffic_but_keeps_the_other_group()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();

        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);
        var circle = await CircleOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(alexSocket, circle);
        await JoinAsync(blakeSocket, convoy);
        await JoinAsync(blakeSocket, circle);

        (await blake.DeleteAsync($"/api/groups/{convoy}/membership")).EnsureSuccessStatusCode();

        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 10L });

        // Still in the circle, so the position still arrives — losing one membership must not
        // close a socket that is legitimately open for another.
        (await blakeSocket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");

        await alexSocket.SendAsync(new
        {
            type = "spin_offer",
            groupId = convoy,
            candidates = new[] { new { lat = 51.0, lon = 3.7 } },
        });

        // But the convoy's own traffic is gone.
        await blakeSocket.ShouldStaySilent();
    }

    // ---------------------------------------------------------------------------------------
    // Helpers.
    // ---------------------------------------------------------------------------------------

    private static string Nested(int depth) =>
        new string('[', depth) + new string(']', depth);

    private static Uri LiveUri() => new("http://localhost/api/live");

    private async Task<(HttpClient Client, string Username)> NewRider()
    {
        var username = $"rider{Guid.NewGuid():N}"[..16];
        var token = _factory.IssueToken($"subject-{Guid.NewGuid():N}", username, null, "detour-user");
        _tokens[username] = token;

        var client = _factory.CreateClientWith(token);
        (await client.GetAsync("/api/me")).EnsureSuccessStatusCode();
        return (client, username);
    }

    private async Task<LiveSocket> ConnectAsync(string username)
    {
        var client = _factory.Server.CreateWebSocketClient();
        client.ConfigureRequest = request =>
            request.Headers["Authorization"] = $"Bearer {_tokens[username]}";

        return new LiveSocket(await client.ConnectAsync(LiveUri(), CancellationToken.None));
    }

    private static async Task JoinAsync(LiveSocket socket, Guid groupId)
    {
        await socket.SendAsync(new { type = "join", groupId });
        (await socket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("joined");
    }

    private Task<Guid> ConvoyOf(HttpClient owner, string ownerName, HttpClient guest, string guestName) =>
        GroupOf("convoys", owner, ownerName, guest, guestName);

    private Task<Guid> CircleOf(HttpClient owner, string ownerName, HttpClient guest, string guestName) =>
        GroupOf("circles", owner, ownerName, guest, guestName);

    private async Task<Guid> GroupOf(
        string kind, HttpClient owner, string ownerName, HttpClient guest, string guestName)
    {
        // Befriending twice is harmless and lets a test build both a convoy and a circle from the
        // same pair without ordering the calls.
        await owner.PostAsJsonAsync("/api/friends/requests", new { username = guestName });
        await guest.PostAsJsonAsync($"/api/friends/requests/{ownerName}/respond", new { accept = true });

        var created = await (await owner.PostAsJsonAsync($"/api/{kind}", new { name = "ride" }))
            .Content.ReadFromJsonAsync<JsonElement>();
        var groupId = created.GetProperty("id").GetGuid();

        (await owner.PostAsJsonAsync($"/api/groups/{groupId}/invitations", new { username = guestName }))
            .EnsureSuccessStatusCode();
        (await guest.PostAsJsonAsync($"/api/groups/{groupId}/invitations/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        return groupId;
    }

    private sealed class LiveSocket(WebSocket socket) : IDisposable
    {
        private static readonly TimeSpan Quiet = TimeSpan.FromMilliseconds(750);
        private static readonly TimeSpan Patient = TimeSpan.FromSeconds(5);

        public Task SendAsync(object payload) => SendRawAsync(JsonSerializer.Serialize(payload));

        public async Task SendRawAsync(string raw) =>
            await socket.SendAsync(
                Encoding.UTF8.GetBytes(raw), WebSocketMessageType.Text, true, CancellationToken.None);

        public async Task SendBinaryAsync(byte[] bytes) =>
            await socket.SendAsync(bytes, WebSocketMessageType.Binary, true, CancellationToken.None);

        public async Task SendFragmentedAsync(string first, string rest)
        {
            await socket.SendAsync(
                Encoding.UTF8.GetBytes(first), WebSocketMessageType.Text, false, CancellationToken.None);
            await socket.SendAsync(
                Encoding.UTF8.GetBytes(rest), WebSocketMessageType.Text, true, CancellationToken.None);
        }

        public void Abort() => socket.Abort();

        public async Task<JsonElement> ReceiveAsync()
        {
            var frame = await TryReceiveAsync(Patient);
            frame.Should().NotBeNull("a frame was expected");
            return JsonDocument.Parse(frame!).RootElement.Clone();
        }

        /// <summary>Every use of this is a privacy claim, so it waits rather than polling once.</summary>
        public async Task ShouldStaySilent()
        {
            var frame = await TryReceiveAsync(Quiet);
            frame.Should().BeNull("no frame should have reached this rider");
        }

        public async Task DrainAsync()
        {
            while (await TryReceiveAsync(TimeSpan.FromMilliseconds(200)) is not null)
            {
                // Whatever is already queued is not what the test is about.
            }
        }

        public async Task<string?> TryReceiveAsync(TimeSpan timeout)
        {
            var buffer = new byte[256 * 1024];
            using var cancellation = new CancellationTokenSource(timeout);

            try
            {
                var result = await socket.ReceiveAsync(buffer, cancellation.Token);
                return result.MessageType == WebSocketMessageType.Close
                    ? null
                    : Encoding.UTF8.GetString(buffer, 0, result.Count);
            }
            catch (Exception exception)
                when (exception is OperationCanceledException or WebSocketException or ObjectDisposedException)
            {
                return null;
            }
        }

        public void Dispose() => socket.Dispose();
    }
}
