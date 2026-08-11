using System.Security.Cryptography;
using System.Text;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.ApiKeys;

/// <summary>
/// A read-only credential for a dashboard.
///
/// Separate from the identity provider on purpose: a key pasted into a home-automation config
/// can only read, it reads only its owner's own data, and revoking it does not sign the owner's
/// phone out. Only the hash is stored — the plaintext is shown once, at creation, and is
/// unrecoverable afterwards.
/// </summary>
public sealed class ApiKey : Entity
{
    /// <summary>Bytes of entropy in a minted key.</summary>
    private const int KeyBytes = 32;

    public Guid UserId { get; private set; }

    /// <summary>SHA-256 of the plaintext, hex. Never the plaintext itself.</summary>
    public string KeyHash { get; private set; }

    public string Label { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    public DateTimeOffset? LastUsedAt { get; private set; }

    private ApiKey(Guid userId, string keyHash, string label)
    {
        UserId = userId;
        KeyHash = keyHash;
        Label = label;
        CreatedAt = DateTimeOffset.UtcNow;
    }

    /// <summary>
    /// Mints a key and returns it alongside the plaintext. The plaintext is the only copy that
    /// will ever exist — the caller must hand it straight to the owner.
    /// </summary>
    public static Result<(ApiKey Key, string Plaintext)> Issue(Guid userId, string? label)
    {
        var validation = ValidateLabel(label);
        if (validation.IsFailure)
            return validation;

        var plaintext = Convert.ToBase64String(RandomNumberGenerator.GetBytes(KeyBytes))
            .Replace('+', '-')
            .Replace('/', '_')
            .TrimEnd('=');

        return (new ApiKey(userId, HashOf(plaintext), NormalizeLabel(label)), plaintext);
    }

    /// <summary>
    /// Throttled to once an hour so an actively polling dashboard does not cost a write on
    /// every request. Returns true when the caller should persist the change.
    /// </summary>
    public bool Touch()
    {
        var now = DateTimeOffset.UtcNow;
        if (LastUsedAt is { } last && now - last < TimeSpan.FromHours(1))
            return false;

        LastUsedAt = now;
        return true;
    }

    public static string HashOf(string plaintext) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(plaintext)));

    private static Result ValidateLabel(string? label) =>
        (label ?? string.Empty).Trim().Length > DetourLimits.LabelMaxLength
            ? Result.Error(ValidationKeys.ApiKey.LabelTooLong, DetourLimits.LabelMaxLength)
            : Result.Ok();

    private static string NormalizeLabel(string? label)
    {
        var trimmed = (label ?? string.Empty).Trim();
        return trimmed.Length == 0 ? "dashboard" : trimmed;
    }

    // EF materialisation.
    private ApiKey()
    {
        KeyHash = string.Empty;
        Label = string.Empty;
    }
}

public interface IApiKeyRepository : IBaseRepository<ApiKey>
{
    Task<ApiKey?> GetByHashAsync(string keyHash, CancellationToken cancellationToken);

    Task<List<ApiKey>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    Task<int> DeleteForUserAsync(Guid userId, CancellationToken cancellationToken);
}
