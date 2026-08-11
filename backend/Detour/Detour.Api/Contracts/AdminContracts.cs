using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary>
/// Account metadata and row counts. Deliberately no trip, trace, route or place content: an
/// administrator cannot read anyone's rides, and that is enforced by there being no endpoint
/// that returns them rather than by a permission this response happens not to carry.
/// </summary>
public record AdminAccountResponse(
    [Required] Guid Id,
    [Required] string Username,
    string? Email,
    [Required] bool IsAdministrator,
    [Required] bool ShareFog,
    [Required] long CreatedAtMs,
    long? LastSeenAtMs,
    [Required] int TripCount,
    [Required] int TraceCount,
    [Required] int BadgeCount,
    [Required] int ApiKeyCount,
    [Required] double TotalDistanceKm);

public record AdminOverviewResponse(
    [Required] int AccountCount,
    [Required] IReadOnlyList<AdminAccountResponse> Accounts);
