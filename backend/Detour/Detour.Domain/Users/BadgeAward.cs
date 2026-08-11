using System.Text.RegularExpressions;
using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Users;

/// <summary>
/// One achievement a rider has earned, and when they first earned it.
///
/// A row per award rather than a JSON map on the user: "keep the earliest instant seen" is the
/// merge rule, and a row with a unique key per (user, badge) lets the database hold that
/// invariant instead of the merge code.
/// </summary>
public sealed class BadgeAward : Entity
{
    private static readonly Regex IdPattern =
        new(DetourLimits.BadgeIdPattern, RegexOptions.Compiled, TimeSpan.FromMilliseconds(100));

    public Guid UserId { get; private set; }

    /// <summary>The client-defined badge identifier, e.g. <c>dist_100000</c>.</summary>
    public string BadgeId { get; private set; }

    /// <summary>
    /// Unix milliseconds. Kept as the app reported it — a badge is earned on the device, and
    /// re-deriving the instant server-side would move dates on every reinstall.
    /// </summary>
    public long EarnedAtMs { get; private set; }

    private BadgeAward(Guid userId, string badgeId, long earnedAtMs)
    {
        UserId = userId;
        BadgeId = badgeId;
        EarnedAtMs = earnedAtMs;
    }

    public static Result<BadgeAward> Create(Guid userId, string badgeId, long earnedAtMs)
    {
        var validation = Validate(badgeId, earnedAtMs);
        if (validation.IsFailure)
            return validation;

        return new BadgeAward(userId, badgeId.Trim(), earnedAtMs);
    }

    /// <summary>
    /// First time earned wins, so a reinstall cannot move the date forward. Returns true when
    /// the stored instant actually moved.
    /// </summary>
    public bool KeepEarliest(long earnedAtMs)
    {
        if (earnedAtMs <= 0 || earnedAtMs >= EarnedAtMs)
            return false;

        EarnedAtMs = earnedAtMs;
        return true;
    }

    private static Result Validate(string? badgeId, long earnedAtMs)
    {
        if (string.IsNullOrWhiteSpace(badgeId) || !IdPattern.IsMatch(badgeId.Trim()))
            return Result.Error(ValidationKeys.Badge.IdInvalid, badgeId ?? string.Empty);

        if (earnedAtMs <= 0)
            return Result.Error(ValidationKeys.Badge.EarnedAtInvalid);

        return Result.Ok();
    }
}
