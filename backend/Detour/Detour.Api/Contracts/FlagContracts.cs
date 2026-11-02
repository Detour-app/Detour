using System.ComponentModel.DataAnnotations;
using Detour.Domain.FeatureFlags;

namespace Detour.Api.Contracts;

/// <summary>
/// One flag's value for the caller. <c>source</c> is <c>default</c> (the server's default for
/// everyone) or <c>account</c> (an override for the signed-in account).
/// </summary>
public record FlagResponse(
    [Required] string Key,
    [Required] bool Enabled,
    [Required] string Source);

/// <summary>
/// Every flag this server holds a row for, ordered by key. A key the app knows that is missing
/// here keeps the app's compiled default.
/// </summary>
public record FlagsResponse([Required] IReadOnlyList<FlagResponse> Flags)
{
    public const string DefaultSource = "default";

    public const string AccountSource = "account";

    /// <summary>
    /// The defaults, each replaced by the account's override where it holds one. No overrides
    /// (an anonymous caller) means the defaults as they are.
    /// </summary>
    public static FlagsResponse From(
        IEnumerable<FeatureFlag> flags,
        IEnumerable<FeatureFlagOverride> overrides)
    {
        var byKey = overrides.ToDictionary(o => o.FlagKey, o => o.Enabled, StringComparer.Ordinal);

        return new FlagsResponse([
            .. flags
                .OrderBy(f => f.Key, StringComparer.Ordinal)
                .Select(f => byKey.TryGetValue(f.Key, out var enabled)
                    ? new FlagResponse(f.Key, enabled, AccountSource)
                    : new FlagResponse(f.Key, f.Enabled, DefaultSource))
        ]);
    }
}

/// <summary>The value an admin call switches a default or an override to.</summary>
public record SetFlagBody([Required] bool Enabled);

/// <summary>One default as the owner sees it, with when it last changed.</summary>
public record AdminFlagResponse(
    [Required] string Key,
    [Required] bool Enabled,
    [Required] long UpdatedAtMs);

/// <summary>One account's override, named so the owner can tell whose it is.</summary>
public record AdminFlagOverrideResponse(
    [Required] string Key,
    [Required] Guid AccountId,
    [Required] string Username,
    [Required] bool Enabled);

/// <summary>Every default and every override on this server, each ordered by key.</summary>
public record AdminFlagsResponse(
    [Required] IReadOnlyList<AdminFlagResponse> Flags,
    [Required] IReadOnlyList<AdminFlagOverrideResponse> Overrides);
