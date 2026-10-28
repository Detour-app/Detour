using System.Text.RegularExpressions;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.FeatureFlags;

/// <summary>
/// One remotely switchable rollout (#810) and its default for every rider.
///
/// The default lives here, in the database, rather than in configuration, so the owner can turn a
/// feature on for everyone without a deploy. A key with no row is not a flag this server knows:
/// the app keeps its compiled default for it, which is what makes a server without flags cost a
/// self-hoster nothing.
/// </summary>
public sealed class FeatureFlag : Entity
{
    private static readonly Regex KeyPattern =
        new(DetourLimits.FeatureFlagKeyPattern, RegexOptions.Compiled, TimeSpan.FromMilliseconds(100));

    /// <summary>The wire key, e.g. <c>flags-selftest</c>. Never changes once created.</summary>
    public string Key { get; private set; }

    public bool Enabled { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    private FeatureFlag(string key, bool enabled)
    {
        Key = key;
        Enabled = enabled;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<FeatureFlag> Create(string? key, bool enabled)
    {
        if (string.IsNullOrEmpty(key) || !KeyPattern.IsMatch(key))
            return Result.Error(ValidationKeys.FeatureFlag.KeyInvalid, key ?? string.Empty);

        return new FeatureFlag(key, enabled);
    }

    /// <summary>This flag's value for one account, replacing the default for that account only.</summary>
    public FeatureFlagOverride OverrideFor(Guid userId, bool enabled) => new(Key, userId, enabled);

    // EF materialisation.
    private FeatureFlag()
    {
        Key = string.Empty;
    }
}

/// <summary>
/// One account's value for a flag. Goes with the account when it is deleted, and with the flag
/// when that is.
/// </summary>
public sealed class FeatureFlagOverride : Entity
{
    public string FlagKey { get; private set; }

    public Guid UserId { get; private set; }

    public bool Enabled { get; private set; }

    internal FeatureFlagOverride(string flagKey, Guid userId, bool enabled)
    {
        FlagKey = flagKey;
        UserId = userId;
        Enabled = enabled;
    }

    // EF materialisation.
    private FeatureFlagOverride()
    {
        FlagKey = string.Empty;
    }
}

public interface IFeatureFlagRepository : IBaseRepository<FeatureFlag>
{
    /// <summary>Every override one account holds, whichever flags they are for.</summary>
    Task<List<FeatureFlagOverride>> GetOverridesForUserAsync(Guid userId, CancellationToken cancellationToken);
}
