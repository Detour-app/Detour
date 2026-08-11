using System.ComponentModel.DataAnnotations;
using Detour.Domain.Users;

namespace Detour.Api.Contracts;

/// <summary>The caller's own account.</summary>
public record MeResponse(
    [Required] Guid Id,
    [Required] string Username,
    string? Email,
    [Required] bool ShareFog,
    [Required] bool IsAdministrator,
    [Required] RiderStatsResponse Stats,
    [Required] IReadOnlyDictionary<string, long> Badges)
{
    public static MeResponse Map(User user, IReadOnlyCollection<BadgeAward> badges) => new(
        user.Id,
        user.Username,
        user.Email,
        user.ShareFog,
        user.IsAdministrator,
        RiderStatsResponse.Map(user.Stats),
        badges.ToDictionary(b => b.BadgeId, b => b.EarnedAtMs));
}

/// <summary>
/// What a rider's device last reported. <c>maxLeanDegrees</c> is deliberately nullable:
/// "never measured" and "rode upright" are different answers, and zero cannot say both.
/// </summary>
public record RiderStatsResponse(
    [Required] double TotalDistanceMeters,
    [Required] double TopSpeedKmh,
    [Required] double LongestTripMeters,
    double? MaxLeanDegrees,
    [Required] int MunicipalitiesVisited,
    [Required] double BestCoveragePercent,
    [Required] int TripCount)
{
    public static RiderStatsResponse Map(RiderStats stats) => new(
        stats.TotalDistanceMeters,
        stats.TopSpeedKmh,
        stats.LongestTripMeters,
        stats.MaxLeanDegrees,
        stats.MunicipalitiesVisited,
        stats.BestCoveragePercent,
        stats.TripCount);
}

/// <summary>Another rider, as a friend sees them. Aggregates only — never their rides.</summary>
public record FriendStatsResponse(
    [Required] string Username,
    [Required] RiderStatsResponse Stats,
    [Required] IReadOnlyDictionary<string, long> Badges);
