using System.Net;
using System.Net.Http.Json;
using System.Net.WebSockets;
using System.Text;
using System.Text.Json;
using Detour.InfraTests.Database;
using Microsoft.AspNetCore.TestHost;

namespace Detour.InfraTests.Api;

/// <summary>
/// The live relay end to end: real HTTP pipeline, real bearer validation against realm-shaped
/// tokens, real Postgres membership.
///
/// These exist because everything worth getting wrong about a relay is at a seam — the upgrade is
/// authenticated by the same middleware as the REST surface, the fan-out is decided by rows in
/// the database, and the privacy gate is the join. A unit test over the registry cannot reach any
/// of that.
/// </summary>
[Collection(PostgresCollection.Name)]
public class LiveEndpointTests(PostgresFixture postgres) : IAsyncLifetime
{
    private DetourApiFactory _factory = null!;

    public Task InitializeAsync()
    {
        _factory = new DetourApiFactory(postgres);
        return Task.CompletedTask;
    }

    public Task DisposeAsync() => _factory.DisposeAsync().AsTask();

    [Fact]
    public async Task An_unauthenticated_upgrade_is_refused()
    {
        var client = _factory.Server.CreateWebSocketClient();

        var attempt = async () => await client.ConnectAsync(LiveUri(), CancellationToken.None);

        // The socket is behind the same policy as every other endpoint. If this ever starts
        // succeeding, the relay has become an unauthenticated firehose of riders' positions.
        await attempt.Should().ThrowAsync<Exception>();
    }

    [Fact]
    public async Task A_token_signed_by_a_stranger_is_refused()
    {
        var client = _factory.Server.CreateWebSocketClient();
        client.ConfigureRequest = request =>
            request.Headers["Authorization"] = $"Bearer {_factory.IssueForeignToken("subject-x", "mallory")}";

        var attempt = async () => await client.ConnectAsync(LiveUri(), CancellationToken.None);

        await attempt.Should().ThrowAsync<Exception>();
    }

    [Fact]
    public async Task A_plain_request_to_the_live_route_is_a_bad_request()
    {
        var (rider, _) = await NewRider();

        var response = await rider.GetAsync("/api/live");

        response.StatusCode.Should().Be(HttpStatusCode.BadRequest);
    }

    [Fact]
    public async Task Joining_a_group_you_belong_to_is_acknowledged()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var socket = await ConnectAsync(alexName);
        await socket.SendAsync(new { type = "join", groupId = convoy });

        var frame = await socket.ReceiveAsync();
        frame.GetProperty("type").GetString().Should().Be("joined");
        frame.GetProperty("groupId").GetGuid().Should().Be(convoy);
    }

    [Fact]
    public async Task Joining_a_group_you_do_not_belong_to_is_refused_the_same_way_as_one_that_does_not_exist()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var (_, chrisName) = await NewRider();

        // A convoy alex is not in, and an id that is nothing at all.
        var strangers = await ConvoyOf(blake, blakeName, alex, alexName, acceptInvite: false);

        using var socket = await ConnectAsync(alexName);

        await socket.SendAsync(new { type = "join", groupId = strangers });
        var refusedReal = await socket.ReceiveAsync();

        await socket.SendAsync(new { type = "join", groupId = Guid.CreateVersion7() });
        var refusedFake = await socket.ReceiveAsync();

        // Identical answers, deliberately: any difference turns the socket into an oracle for
        // enumerating group ids.
        refusedReal.GetProperty("type").GetString().Should().Be("error");
        refusedFake.GetProperty("type").GetString().Should().Be("error");
        refusedFake.GetProperty("message").GetString()
            .Should().Be(refusedReal.GetProperty("message").GetString());

        chrisName.Should().NotBeNullOrEmpty();
    }

    [Fact]
    public async Task A_position_reaches_a_convoy_peer_and_never_its_own_sender()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        await alexSocket.SendAsync(new
        {
            type = "location",
            lat = 51.05431,
            lon = 3.71742,
            headingDeg = 142.5,
            speedKmh = 48.3,
            ts = 1_754_923_456_789L,
        });

        var frame = await blakeSocket.ReceiveAsync();
        frame.GetProperty("type").GetString().Should().Be("positions");

        var peer = frame.GetProperty("peers")[0];
        peer.GetProperty("u").GetString().Should().Be(alexName);
        peer.GetProperty("lat").GetDouble().Should().BeApproximately(51.05431, 0.00001);
        peer.GetProperty("ttl").GetInt32().Should().Be(20);

        // The sender must not see itself: the client sets its own marker locally and would draw a
        // duplicate — and, once voice exists, play its own audio back.
        await alexSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task A_position_carries_no_group_and_still_reaches_every_group()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var (cass, cassName) = await NewRider();

        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);
        var circle = await CircleOf(alex, alexName, cass, cassName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        using var cassSocket = await ConnectAsync(cassName);
        await JoinAsync(blakeSocket, convoy);
        await JoinAsync(cassSocket, circle);

        // Note alex joins nothing. A fix belongs to the rider, not to a group, so sending one does
        // not require having joined anything — which is the whole point of dropping groupId.
        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 1L });

        (await blakeSocket.ReceiveAsync()).GetProperty("peers")[0]
            .GetProperty("u").GetString().Should().Be(alexName);
        (await cassSocket.ReceiveAsync()).GetProperty("peers")[0]
            .GetProperty("u").GetString().Should().Be(alexName);
    }

    [Fact]
    public async Task A_paused_circle_member_position_is_not_relayed()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var circle = await CircleOf(alex, alexName, blake, blakeName);

        (await alex.PutAsJsonAsync($"/api/circles/{circle}/sharing", new { sharing = false }))
            .EnsureSuccessStatusCode();

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(blakeSocket, circle);

        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 1L });

        // Pause is enforced server-side on the live path too, so a stale client build cannot keep
        // broadcasting after the rider believes they stopped.
        await blakeSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task A_destination_offer_and_vote_reach_the_convoy()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        await alexSocket.SendAsync(new
        {
            type = "spin_offer",
            groupId = convoy,
            candidates = new[]
            {
                new { lat = 51.0, lon = 3.7, name = "Option 1" },
                new { lat = 51.1, lon = 3.8, name = "Option 2" },
            },
        });

        var offer = await blakeSocket.ReceiveAsync();
        offer.GetProperty("type").GetString().Should().Be("spin_offer");
        offer.GetProperty("user").GetString().Should().Be(alexName);
        offer.GetProperty("candidates").GetArrayLength().Should().Be(2);

        await blakeSocket.SendAsync(new { type = "spin_vote", groupId = convoy, index = 1 });

        var vote = await alexSocket.ReceiveAsync();
        vote.GetProperty("type").GetString().Should().Be("spin_vote");
        vote.GetProperty("user").GetString().Should().Be(blakeName);
        vote.GetProperty("index").GetInt32().Should().Be(1);
    }

    [Fact]
    public async Task One_invalid_candidate_voids_the_whole_offer()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        await alexSocket.SendAsync(new
        {
            type = "spin_offer",
            groupId = convoy,
            candidates = new[]
            {
                new { lat = 51.0, lon = 3.7 },
                new { lat = 999.0, lon = 3.8 },
            },
        });

        // Relaying the shorter list would leave riders voting on different sheets, and index 1
        // would then mean two different places on two phones.
        await blakeSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task A_vote_outside_the_sheet_is_dropped()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        await alexSocket.SendAsync(new { type = "spin_vote", groupId = convoy, index = 7 });

        await blakeSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task A_circle_refuses_a_destination_offer()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var circle = await CircleOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, circle);
        await JoinAsync(blakeSocket, circle);

        await alexSocket.SendAsync(new
        {
            type = "spin_offer",
            groupId = circle,
            candidates = new[] { new { lat = 51.0, lon = 3.7 } },
        });

        // The highest-consequence rule in the convoy/circle merge, enforced on the wire and not
        // only in the domain: a circle must never gain convoy behaviour.
        await blakeSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task Voice_frames_are_ignored_without_breaking_the_connection()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        await alexSocket.SendAsync(new { type = "ptt_start", groupId = convoy });
        await alexSocket.SendAsync(new { type = "ptt_audio", groupId = convoy, chunk = "AAAA" });
        await alexSocket.SendAsync(new { type = "ptt_end", groupId = convoy });
        await alexSocket.SendAsync(new { type = "not_a_real_frame" });
        await alexSocket.SendAsync("{ this is not json");

        await blakeSocket.ShouldStaySilent();

        // The connection has to survive all of that: a client still sending voice must keep
        // working for everything else.
        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 1L });
        (await blakeSocket.ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");
    }

    [Fact]
    public async Task A_place_event_recorded_over_http_reaches_a_connected_circle_member()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var circle = await CircleOf(alex, alexName, blake, blakeName);

        (await alex.PostAsJsonAsync($"/api/circles/{circle}/places",
                new { place = new { id = 42L, name = "Home", radiusMeters = 150.0, latitude = 51.0, longitude = 3.7 } }))
            .EnsureSuccessStatusCode();

        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(blakeSocket, circle);

        (await alex.PostAsJsonAsync($"/api/circles/{circle}/events",
                new { placeId = 42L, kind = "arrive", timestampMs = 1_754_923_456_789L }))
            .EnsureSuccessStatusCode();

        var frame = await blakeSocket.ReceiveAsync();
        frame.GetProperty("type").GetString().Should().Be("place_event");
        frame.GetProperty("user").GetString().Should().Be(alexName);
        frame.GetProperty("placeId").GetInt64().Should().Be(42);
        frame.GetProperty("placeName").GetString().Should().Be("Home");
        // Lowercase wire vocabulary and the short `ts` key — the mobile client's
        // relay parser rejects the frame on either mismatch (issue #74).
        frame.GetProperty("kind").GetString().Should().Be("arrive");
        frame.GetProperty("ts").GetInt64().Should().Be(1_754_923_456_789L);
    }

    [Fact]
    public async Task Leaving_a_group_stops_its_traffic_immediately()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var blakeSocket = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(blakeSocket, convoy);

        (await blake.DeleteAsync($"/api/groups/{convoy}/membership"))
            .EnsureSuccessStatusCode();

        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 1L });

        // Spec §11 wants this instant. Waiting for the periodic sweep would mean a rider who has
        // just walked out keeps watching the ride for another quarter minute.
        await blakeSocket.ShouldStaySilent();
    }

    [Fact]
    public async Task A_reconnect_replaces_the_previous_socket()
    {
        var (alex, alexName) = await NewRider();
        var (blake, blakeName) = await NewRider();
        var convoy = await ConvoyOf(alex, alexName, blake, blakeName);

        using var alexSocket = await ConnectAsync(alexName);
        using var stale = await ConnectAsync(blakeName);
        await JoinAsync(alexSocket, convoy);
        await JoinAsync(stale, convoy);

        using var fresh = await ConnectAsync(blakeName);
        await JoinAsync(fresh, convoy);

        await alexSocket.SendAsync(new { type = "location", lat = 51.0, lon = 3.7, ts = 1L });

        // Only the newest socket is fed. A ghost that keeps receiving after a reconnect is both a
        // leak and a privacy problem — it outlives the rider's intent to be connected.
        (await fresh.ReceiveAsync()).GetProperty("type").GetString().Should().Be("positions");
        await stale.ShouldStaySilent();
    }

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

    private readonly Dictionary<string, string> _tokens = [];

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

    private async Task<Guid> ConvoyOf(
        HttpClient owner, string ownerName, HttpClient guest, string guestName, bool acceptInvite = true) =>
        await GroupOf("convoys", owner, ownerName, guest, guestName, acceptInvite);

    private async Task<Guid> CircleOf(
        HttpClient owner, string ownerName, HttpClient guest, string guestName) =>
        await GroupOf("circles", owner, ownerName, guest, guestName, acceptInvite: true);

    private async Task<Guid> GroupOf(
        string kind, HttpClient owner, string ownerName, HttpClient guest, string guestName, bool acceptInvite)
    {
        (await owner.PostAsJsonAsync("/api/friends/requests", new { username = guestName }))
            .EnsureSuccessStatusCode();
        (await guest.PostAsJsonAsync($"/api/friends/requests/{ownerName}/respond", new { accept = true }))
            .EnsureSuccessStatusCode();

        var created = await (await owner.PostAsJsonAsync($"/api/{kind}", new { name = "ride" }))
            .Content.ReadFromJsonAsync<JsonElement>();
        var groupId = created.GetProperty("id").GetGuid();

        (await owner.PostAsJsonAsync($"/api/groups/{groupId}/invitations", new { username = guestName }))
            .EnsureSuccessStatusCode();

        if (acceptInvite)
            (await guest.PostAsJsonAsync($"/api/groups/{groupId}/invitations/respond", new { accept = true }))
                .EnsureSuccessStatusCode();

        return groupId;
    }

    /// <summary>A connected relay socket, with the send/receive shape these tests keep needing.</summary>
    private sealed class LiveSocket(WebSocket socket) : IDisposable
    {
        /// <summary>
        /// Long enough to be a real answer on a loaded CI box, short enough that a test asserting
        /// silence does not dominate the suite.
        /// </summary>
        private static readonly TimeSpan Quiet = TimeSpan.FromMilliseconds(750);

        private static readonly TimeSpan Patient = TimeSpan.FromSeconds(5);

        public Task SendAsync(object payload) => SendAsync(JsonSerializer.Serialize(payload));

        public async Task SendAsync(string raw) =>
            await socket.SendAsync(
                Encoding.UTF8.GetBytes(raw), WebSocketMessageType.Text, true, CancellationToken.None);

        public async Task<JsonElement> ReceiveAsync()
        {
            var frame = await TryReceiveAsync(Patient);
            frame.Should().NotBeNull("a frame was expected");
            return JsonDocument.Parse(frame!).RootElement.Clone();
        }

        /// <summary>
        /// Asserts nothing arrives. Every use of this is a privacy claim — a frame that must not
        /// reach this rider — so it waits rather than polling once.
        /// </summary>
        public async Task ShouldStaySilent()
        {
            var frame = await TryReceiveAsync(Quiet);
            frame.Should().BeNull("no frame should have reached this rider");
        }

        private async Task<string?> TryReceiveAsync(TimeSpan timeout)
        {
            var buffer = new byte[64 * 1024];
            using var cancellation = new CancellationTokenSource(timeout);

            try
            {
                var result = await socket.ReceiveAsync(buffer, cancellation.Token);
                return result.MessageType == WebSocketMessageType.Close
                    ? null
                    : Encoding.UTF8.GetString(buffer, 0, result.Count);
            }
            catch (OperationCanceledException)
            {
                return null;
            }
            catch (WebSocketException)
            {
                return null;
            }
        }

        public void Dispose() => socket.Dispose();
    }
}
