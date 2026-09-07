using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Detour.Api.Contracts;

/// <summary>
/// Everyone the caller has a friendship row with, accepted or pending, each tagged with
/// which direction it points. One list rather than three: three arrays encoded the relation
/// by position, so nothing checked that a rider appeared in exactly one.
/// </summary>
public record FriendsResponse([Required] IReadOnlyList<FriendEntry> Riders);

/// <summary>
/// <c>Family</c> is the caller's view of the mutual family tier: <c>none</c>, <c>outgoing</c>
/// (the caller asked), <c>incoming</c> (the other rider asked) or <c>family</c> (agreed). A
/// client that predates the field ignores it and sees an ordinary friend.
/// </summary>
public record FriendEntry(
    [Required] RiderRef Rider,
    [Required] string Relation,
    [Required] string Family);

/// <summary>
/// <c>sharing</c> reports the caller's own setting, so a client can explain an empty result
/// rather than showing a blank map.
/// </summary>
public record SharedFogResponse(
    [Required] bool Sharing,
    [Required] IReadOnlyList<string> Traces);

public record FriendRequestBody([Required] string Username);

public record FriendRespondBody([Required] bool Accept);

public record FriendshipStatusResponse([Required] string Status);

/// <summary>
/// A planned route offered to a friend. Everything but the identifier and the stops is opaque
/// to this backend.
/// </summary>
public record RoutePayload
{
    [Required] public long Id { get; init; }

    public string? Name { get; init; }

    /// <summary>At least two — a route with one stop is a place.</summary>
    public IReadOnlyList<JsonElement>? Stops { get; init; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extra { get; init; }
}

public record ShareRouteBody(
    [Required] string To,
    [Required] RoutePayload Route);

public record SharedRouteResponse(
    [Required] Guid Id,
    [Required] RiderRef From,
    [Required] long CreatedAtMs,
    [Required] string Name,
    [Required] JsonElement Route);

public record SharedRouteInboxResponse([Required] IReadOnlyList<SharedRouteResponse> Routes);
