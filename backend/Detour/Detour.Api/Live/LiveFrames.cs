using System.Text.Json.Serialization;

namespace Detour.Api.Live;

/// <summary>
/// The live relay's wire vocabulary.
///
/// Frame names are the protocol, not an implementation detail: they are shared with the mobile
/// clients and with <c>docs/CIRCLES_AND_CONVOYS.md</c>. Keys are deliberately short on the
/// high-cadence frames — a position goes out several times a minute per peer, so every byte is
/// multiplied by peers × rounds × riders — and spelled out everywhere else, where clarity is
/// free.
/// </summary>
internal static class LiveFrameTypes
{
    // Client -> server.
    public const string Join = "join";
    public const string Location = "location";
    public const string DestinationOffer = "spin_offer";
    public const string DestinationVote = "spin_vote";

    // Server -> client.
    public const string Joined = "joined";
    public const string Error = "error";
    public const string Positions = "positions";
    public const string Left = "left";
    public const string PlaceEvent = "place_event";
}

/// <summary>
/// Anything the relay writes to a socket. Position updates are the one mergeable kind — see
/// <see cref="LiveConnection"/> for why that distinction lives in the type system rather than in
/// a flag.
/// </summary>
public abstract record LiveOutbound;

/// <summary>
/// One peer's position, as it appears inside a <c>positions</c> frame.
///
/// <c>u</c> is the rider's account id. It is the identity and nothing else — a peer's
/// display handle comes from the group's membership, which the client already holds, so it
/// is not repeated on a frame that goes out several times a minute per peer.
///
/// <paramref name="TtlSeconds"/> is how long this fix should still be drawn before the peer is
/// treated as gone. It is per peer rather than a client-side constant because a convoy rider and
/// a circle member arrive on the same stream at wildly different cadences: a single hardcoded
/// staleness window either flickers circle members out between their updates or leaves a dropped
/// convoy rider frozen on the map.
/// </summary>
public sealed record PeerPosition(
    [property: JsonPropertyName("u")] Guid User,
    [property: JsonPropertyName("lat")] double Latitude,
    [property: JsonPropertyName("lon")] double Longitude,
    [property: JsonPropertyName("h")] double? HeadingDegrees,
    [property: JsonPropertyName("s")] double? SpeedKmh,
    [property: JsonPropertyName("ts")] long TimestampMs,
    [property: JsonPropertyName("ttl")] int TtlSeconds) : LiveOutbound;

/// <summary>A frame that is written as-is, in order, and never merged with its neighbours.</summary>
public sealed record LiveMessage(object Payload) : LiveOutbound;

internal sealed record PositionsFrame(
    [property: JsonPropertyName("peers")] IReadOnlyList<PeerPosition> Peers)
{
    [JsonPropertyName("type")]
    [JsonPropertyOrder(-1)]
    public string Type => LiveFrameTypes.Positions;
}

internal sealed record JoinedFrame(
    [property: JsonPropertyName("groupId")] Guid GroupId)
{
    [JsonPropertyName("type")]
    [JsonPropertyOrder(-1)]
    public string Type => LiveFrameTypes.Joined;
}

/// <summary>
/// Sent for a join the relay refuses. The client closes the socket on this rather than sitting
/// connected-but-never-joined, so it must carry enough to show a rider why.
/// </summary>
internal sealed record ErrorFrame(
    [property: JsonPropertyName("message")] string Message)
{
    [JsonPropertyName("type")]
    [JsonPropertyOrder(-1)]
    public string Type => LiveFrameTypes.Error;
}

internal sealed record LeftFrame(
    [property: JsonPropertyName("user")] Guid User)
{
    [JsonPropertyName("type")]
    [JsonPropertyOrder(-1)]
    public string Type => LiveFrameTypes.Left;
}

internal sealed record DestinationCandidateFrame(
    [property: JsonPropertyName("lat")] double Latitude,
    [property: JsonPropertyName("lon")] double Longitude,
    [property: JsonPropertyName("distanceM")] double? DistanceMetres,
    [property: JsonPropertyName("durationS")] double? DurationSeconds,
    [property: JsonPropertyName("name")] string? Name);

/// <summary>
/// A relayed destination offer. Three candidates means "vote on these"; one means "this won" —
/// the relay treats both identically and holds no round state of its own, so the convention is
/// the clients', and the single-candidate closing frame is what stops a convoy splitting across
/// two destinations.
/// </summary>
internal sealed record DestinationOfferFrame(
    [property: JsonPropertyName("groupId")] Guid GroupId,
    [property: JsonPropertyName("user")] Guid User,
    [property: JsonPropertyName("candidates")] IReadOnlyList<DestinationCandidateFrame> Candidates)
{
    [JsonPropertyName("type")]
    [JsonPropertyOrder(-1)]
    public string Type => LiveFrameTypes.DestinationOffer;
}

internal sealed record DestinationVoteFrame(
    [property: JsonPropertyName("groupId")] Guid GroupId,
    [property: JsonPropertyName("user")] Guid User,
    [property: JsonPropertyName("index")] int Index)
{
    [JsonPropertyName("type")]
    [JsonPropertyOrder(-1)]
    public string Type => LiveFrameTypes.DestinationVote;
}

/// <summary>
/// A circle arrival or departure. Server-originated only: a client cannot cause one by sending
/// it, which is why there is no inbound counterpart.
/// </summary>
internal sealed record PlaceEventFrame(
    [property: JsonPropertyName("groupId")] Guid GroupId,
    [property: JsonPropertyName("user")] Guid User,
    [property: JsonPropertyName("placeId")] long PlaceId,
    [property: JsonPropertyName("placeName")] string PlaceName,
    [property: JsonPropertyName("kind")] string Kind,
    [property: JsonPropertyName("ts")] long TimestampMs)
{
    [JsonPropertyName("type")]
    [JsonPropertyOrder(-1)]
    public string Type => LiveFrameTypes.PlaceEvent;
}
