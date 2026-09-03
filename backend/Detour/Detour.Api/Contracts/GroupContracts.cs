using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using System.Text.Json.Serialization;
using Detour.Domain.Groups;

namespace Detour.Api.Contracts;

public record CreateGroupBody([Required] string Name);

public record GroupResponse(
    [Required] Guid Id,
    [Required] string Kind,
    [Required] string Name,
    [Required] string Status,
    [Required] IReadOnlyList<GroupMemberResponse> Members);

/// <summary>
/// <c>sharing</c> is present only for circles. A convoy connection <em>is</em> sharing, so
/// there is nothing meaningful to show on that screen.
///
/// <c>id</c> is the identity; <c>username</c> is the label the screens draw. Membership is
/// the only payload that carries both, which is what lets positions, places and events
/// carry the id alone.
/// </summary>
public record GroupMemberResponse(
    [Required] Guid Id,
    [Required] string Username,
    [Required] string Status,
    bool? Sharing);

public record InviteBody([Required] string Username);

public record RespondBody([Required] bool Accept);

public record MembershipStatusResponse([Required] string Status);

public record SetSharingBody([Required] bool Sharing);

public record SharingResponse([Required] bool Sharing);

public record PositionBody(
    [Required] double Latitude,
    [Required] double Longitude,
    double? AccuracyMeters,
    long? TimestampMs);

public record MemberPositionResponse(
    [Required] Guid RiderId,
    [Required] double Latitude,
    [Required] double Longitude,
    double? AccuracyMeters,
    [Required] long TimestampMs);

public record CircleFixesResponse([Required] IReadOnlyList<MemberPositionResponse> Fixes);

/// <summary>A named point with a radius, shared into a circle. Opaque apart from these fields.</summary>
public record CirclePlacePayload
{
    [Required] public long Id { get; init; }
    public string? Name { get; init; }
    [Required] public double RadiusMeters { get; init; }

    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extra { get; init; }
}

public record SharePlaceBody([Required] CirclePlacePayload Place);

public record CirclePlaceResponse(
    [Required] Guid Id,
    [Required] Guid OwnerId,
    [Required] string Name,
    [Required] double RadiusMeters,
    [Required] long CreatedAtMs,
    [Required] JsonElement Place);

public record CirclePlacesResponse([Required] IReadOnlyList<CirclePlaceResponse> Places);

/// <summary>
/// A transition the device detected. The backend records and fans it out; it never evaluates a
/// geofence itself.
/// </summary>
public record RecordEventBody(
    [Required] long PlaceId,
    [Required] string Kind,
    long? TimestampMs);

public record PlaceEventResponse(
    [Required] Guid Id,
    [Required] long PlaceId,
    [Required] string PlaceName,
    [Required] Guid RiderId,
    [Required] string Kind,
    [Required] long TimestampMs);

public record PlaceEventsResponse([Required] IReadOnlyList<PlaceEventResponse> Events);

public static class GroupResponseMapper
{
    public static GroupResponse Map(
        Group group,
        Guid callerId,
        IReadOnlyDictionary<Guid, string> usernames)
    {
        var members = group.Members
            .Where(m => usernames.ContainsKey(m.UserId))
            .Select(m => new GroupMemberResponse(
                m.UserId,
                usernames[m.UserId],
                m.Status.Wire(),
                group.Kind.SupportsPause ? m.IsSharing : null))
            .ToList();

        var callerStatus = group.FindMember(callerId)?.Status.Wire() ?? GroupMemberStatus.Invited.Wire();
        return new GroupResponse(group.Id, group.Kind.Wire(), group.Name, callerStatus, members);
    }
}
