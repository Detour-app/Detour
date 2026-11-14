using System.Text.RegularExpressions;
using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Users;

/// <summary>
/// A rider's account, as this backend knows it.
///
/// Credentials live in the identity provider — this row is the local mirror that everything
/// else keys on. <see cref="Subject"/> is the issuer's stable subject claim and is the only
/// link between the two; <see cref="Username"/> is the handle riders use to find each other,
/// and is what every friend-facing surface shows.
/// </summary>
public sealed class User : Entity
{
    private static readonly Regex UsernamePattern =
        new(DetourLimits.UsernamePattern, RegexOptions.Compiled, TimeSpan.FromMilliseconds(100));

    /// <summary>The identity provider's <c>sub</c> claim. Immutable for the life of the account.</summary>
    public string Subject { get; private set; }

    public string Username { get; private set; }

    public string? Email { get; private set; }

    /// <summary>
    /// Off by default, and reciprocal: a rider who is not sharing both stops contributing their
    /// own traces and stops receiving anyone else's. See <c>IFriendshipRepository</c> consumers.
    /// </summary>
    public bool ShareFog { get; private set; }

    /// <summary>Realm-level administrator. Mirrored from the identity provider on sign-in.</summary>
    public bool IsAdministrator { get; private set; }

    public RiderStats Stats { get; private set; } = RiderStats.Empty;

    public DateTimeOffset CreatedAt { get; private set; }

    public DateTimeOffset? LastSeenAt { get; private set; }

    /// <summary>
    /// The rider's HERE traffic key (#802), as Data Protection ciphertext — never the key
    /// itself. Null when none is stored. Kept on this row so deleting the account takes it
    /// with it. Protecting and unprotecting happen in the API layer, which holds the key ring;
    /// the domain only ever sees the opaque string.
    /// </summary>
    public string? HereTrafficKeyProtected { get; private set; }

    private User(string subject, string username, string? email)
    {
        Subject = subject;
        Username = username;
        Email = email;
        CreatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<User> Create(string subject, string username, string? email)
    {
        if (string.IsNullOrWhiteSpace(subject))
            return Result.Error(ValidationKeys.User.SubjectRequired);

        var usernameValidation = ValidateUsername(username);
        if (usernameValidation.IsFailure)
            return usernameValidation;

        var emailValidation = ValidateEmail(email);
        if (emailValidation.IsFailure)
            return emailValidation;

        return new User(subject.Trim(), username.Trim(), NormalizeEmail(email));
    }

    public Result Rename(string username)
    {
        var validation = ValidateUsername(username);
        if (validation.IsFailure)
            return validation;

        Username = username.Trim();
        return Result.Ok();
    }

    public Result UpdateEmail(string? email)
    {
        var validation = ValidateEmail(email);
        if (validation.IsFailure)
            return validation;

        Email = NormalizeEmail(email);
        return Result.Ok();
    }

    /// <summary>
    /// Absent means "leave it alone", never "turn it off" — an older client that knows nothing
    /// about fog sharing must not be able to flip the setting by omission.
    /// </summary>
    public void SetFogSharing(bool shareFog) => ShareFog = shareFog;

    public void SetAdministrator(bool isAdministrator) => IsAdministrator = isAdministrator;

    public void ReplaceStats(RiderStats stats) => Stats = RiderStats.Sanitize(stats);

    public void Touch() => LastSeenAt = DateTimeOffset.UtcNow;

    public void SetHereTrafficKeyProtected(string protectedKey) => HereTrafficKeyProtected = protectedKey;

    public void ClearHereTrafficKey() => HereTrafficKeyProtected = null;

    /// <summary>
    /// The plaintext key as it will be stored: trimmed, and 1 to
    /// <see cref="DetourLimits.HereTrafficKeyMaxLength"/> characters. Checked before the key is
    /// protected, since the ciphertext's length says nothing useful about the input.
    /// </summary>
    public static Result<string> NormalizeHereTrafficKey(string? key)
    {
        var trimmed = key?.Trim();
        if (string.IsNullOrEmpty(trimmed) || trimmed.Length > DetourLimits.HereTrafficKeyMaxLength)
            return Result.Error(ValidationKeys.User.HereTrafficKeyInvalid, DetourLimits.HereTrafficKeyMaxLength);

        return trimmed;
    }

    private static Result ValidateUsername(string? username)
    {
        if (string.IsNullOrWhiteSpace(username))
            return Result.Error(ValidationKeys.User.UsernameRequired);

        return UsernamePattern.IsMatch(username.Trim())
            ? Result.Ok()
            : Result.Error(ValidationKeys.User.UsernameInvalid);
    }

    private static Result ValidateEmail(string? email)
    {
        if (string.IsNullOrWhiteSpace(email))
            return Result.Ok();

        var trimmed = email.Trim();
        if (trimmed.Length > DetourLimits.EmailMaxLength)
            return Result.Error(ValidationKeys.User.EmailInvalid);

        // Deliberately loose. The only thing this backend does with an address is hand it to
        // the identity provider, which is the real validator; this keeps obvious junk out.
        var at = trimmed.IndexOf('@');
        var dot = trimmed.LastIndexOf('.');
        return at > 0 && dot > at + 1 && dot < trimmed.Length - 1 && !trimmed.Contains(' ')
            ? Result.Ok()
            : Result.Error(ValidationKeys.User.EmailInvalid);
    }

    private static string? NormalizeEmail(string? email) =>
        string.IsNullOrWhiteSpace(email) ? null : email.Trim();
}
