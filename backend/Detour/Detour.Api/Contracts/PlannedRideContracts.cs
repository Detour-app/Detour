using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

public record MeetingPointBody(
    [Required] double Latitude,
    [Required] double Longitude,
    string? Label);

/// <summary>
/// Create and edit take the same body: an edit replaces every field. <c>routeId</c> is the
/// owner's own identifier for a saved route, opaque here; invitees get the route itself through
/// a share (§8), not through this field.
/// </summary>
public record PlannedRideBody(
    [Required] string Title,
    [Required] long StartsAtMs,
    [Required] MeetingPointBody MeetingPoint,
    long? RouteId,
    string? Description);

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
    string? MyAnswer,
    [Required] IReadOnlyList<PlannedRideInviteeResponse> Invitees);

public record PlannedRidesResponse([Required] IReadOnlyList<PlannedRideResponse> Rides);
