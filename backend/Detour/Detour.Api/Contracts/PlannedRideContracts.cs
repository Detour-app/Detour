using System.ComponentModel.DataAnnotations;
using System.Text.Json;

namespace Detour.Api.Contracts;

public record MeetingPointBody(
    [Required] double Latitude,
    [Required] double Longitude,
    string? Label);

/// <summary>
/// Create and edit take the same body: an edit replaces every field, so an edit without
/// <c>route</c> detaches it. <c>routeId</c> is the owner's own identifier for a saved route,
/// opaque here. <c>route</c> is the route itself, in the shape a share carries (§8), stored as
/// sent and returned to every invitee (#757).
/// </summary>
public record PlannedRideBody(
    [Required] string Title,
    [Required] long StartsAtMs,
    [Required] MeetingPointBody MeetingPoint,
    long? RouteId,
    string? Description,
    RoutePayload? Route = null);

/// <summary>
/// Either list may be empty. A circle expands to its accepted members at the moment of the
/// invite; joining the circle later does not add someone to a ride already planned.
/// </summary>
public record PlannedRideInviteBody(
    IReadOnlyList<Guid>? RiderIds,
    IReadOnlyList<Guid>? CircleIds);

/// <summary><c>going</c>, <c>maybe</c> or <c>declined</c>.</summary>
public record RsvpBody([Required] string Answer);

public record RsvpResponse([Required] string Answer);

public record MeetingPointResponse(
    [Required] double Latitude,
    [Required] double Longitude,
    string? Label);

/// <summary>
/// <c>answer</c> is <c>pending</c> until the invitee answers.
/// </summary>
public record PlannedRideInviteeResponse(
    [Required] RiderRef Rider,
    [Required] string Answer);

/// <summary>
/// <c>myAnswer</c> is the caller's own answer, and null when the caller is the owner — the
/// owner is not invited to their own ride, and is not in <c>invitees</c>.
/// </summary>
public record PlannedRideResponse(
    [Required] Guid Id,
    [Required] RiderRef Owner,
    [Required] string Title,
    [Required] long StartsAtMs,
    [Required] MeetingPointResponse MeetingPoint,
    long? RouteId,
    string? Description,
    [Required] long CreatedAtMs,
    JsonElement? Route,
    string? MyAnswer,
    [Required] IReadOnlyList<PlannedRideInviteeResponse> Invitees);

public record PlannedRidesResponse([Required] IReadOnlyList<PlannedRideResponse> Rides);
