using System.Security.Claims;
using Detour.Api.Configuration;
using Detour.Domain;
using Detour.Domain.Users;
using JV.ResultUtilities;
using JV.ResultUtilities.Exceptions;
using Microsoft.Extensions.Options;
using Shared.Domain;

namespace Detour.Api.Authentication;

/// <summary>
/// The local account behind the current request.
///
/// Every handler works in terms of a <see cref="User"/> row, never a raw claim: friendships,
/// group membership and ownership all key on it, and the token only carries an issuer subject.
/// </summary>
public interface ICurrentUser
{
    /// <summary>
    /// The account, provisioned on first sight if the identity provider has just issued its
    /// first token for this subject.
    /// </summary>
    Task<User> GetAsync(CancellationToken cancellationToken);

}

public class CurrentUser(
    IHttpContextAccessor httpContextAccessor,
    IUserRepository users,
    IOptions<IdpSettings> idpSettings,
    ILogger<CurrentUser> logger) : ICurrentUser
{
    private User? _cached;

    public async Task<User> GetAsync(CancellationToken cancellationToken)
    {
        if (_cached is not null)
            return _cached;

        var principal = httpContextAccessor.HttpContext?.User
                        ?? throw new InvalidOperationException("No request in scope.");

        // A dashboard key carries the owner's local id directly; there is nothing to provision.
        if (principal.Identity?.AuthenticationType == DetourAuthenticationSchemes.ApiKey)
        {
            var ownerId = Guid.Parse(principal.FindFirstValue(ClaimTypes.NameIdentifier)!);
            _cached = await users.GetAsync(ownerId, cancellationToken)
                      ?? throw new InvalidOperationException(
                          $"API key {ownerId} authenticated for an account that no longer exists.");
            return _cached;
        }

        var subject = principal.FindFirstValue(DetourClaims.Subject)
                      ?? throw new InvalidOperationException(
                          "An authenticated token carried no subject claim.");

        _cached = await users.GetBySubjectAsync(subject, cancellationToken)
                  ?? await ProvisionAsync(principal, subject, cancellationToken);

        await SyncFromTokenAsync(_cached, principal, cancellationToken);
        return _cached;
    }

    /// <summary>
    /// First sight of a subject creates its local mirror. This is what replaces registration:
    /// the identity provider decides who may exist, and this backend records the row everything
    /// else keys on.
    /// </summary>
    private async Task<User> ProvisionAsync(
        ClaimsPrincipal principal,
        string subject,
        CancellationToken cancellationToken)
    {
        var username = principal.FindFirstValue(DetourClaims.PreferredUsername)
                       ?? throw new InvalidOperationException(
                           "An authenticated token carried no preferred_username claim; the realm "
                           + "must map one, because it becomes the handle other riders search for.");

        var email = principal.FindFirstValue(DetourClaims.Email);

        var (result, user) = User.Create(subject, username, email);
        if (result.IsFailure)
        {
            // The handle came from the identity provider and this backend's own rules rejected
            // it. That is a realm misconfiguration, not something the rider can fix, so it must
            // be loud rather than a silently unprovisioned account.
            logger.LogError(
                "Cannot provision subject {Subject}: {Errors}",
                subject,
                string.Join("; ", result.ValidationMessages.Select(m => m.TranslationKey)));
            throw new ResultException(result.ValidationMessages);
        }

        // Not SaveAsync + flush: a concurrent first request for the same subject would hit the
        // unique index and 500, and inside a write's transaction the error also poisons it.
        // Whichever request inserts, every one of them reads back the same row (#697).
        if (await users.InsertIfAbsentAsync(user, cancellationToken))
            logger.LogInformation("Provisioned local account for subject {Subject}", subject);

        var stored = await users.GetBySubjectAsync(subject, cancellationToken);
        if (stored is not null)
            return stored;

        // Nothing holds this subject, so the clash was the handle: a row whose identity-provider
        // account is gone (deleted there but not here) still owns it. A clear 400 names the
        // handle; an administrator frees it by deleting that row.
        logger.LogWarning(
            "Cannot provision subject {Subject}: handle {Username} is held by another account",
            subject,
            user.Username);
        throw new ResultException(
            Result.Error(ValidationKeys.User.AlreadyExistsWithName, user.Username).ValidationMessages);
    }

    /// <summary>
    /// Re-reads what the identity provider says on every request rather than trusting the local
    /// copy. Revoking the administrator role has to take effect on the next token, not whenever
    /// this row happens to be rewritten for some other reason.
    /// </summary>
    private async Task SyncFromTokenAsync(
        User user,
        ClaimsPrincipal principal,
        CancellationToken cancellationToken)
    {
        var isAdministrator = principal.IsInRole(idpSettings.Value.AdministratorRole);
        if (user.IsAdministrator != isAdministrator)
            user.SetAdministrator(isAdministrator);

        var email = principal.FindFirstValue(DetourClaims.Email);
        if (!string.IsNullOrWhiteSpace(email) && !string.Equals(user.Email, email, StringComparison.OrdinalIgnoreCase))
            user.UpdateEmail(email);

        var username = principal.FindFirstValue(DetourClaims.PreferredUsername);
        if (!string.IsNullOrWhiteSpace(username) && !string.Equals(user.Username, username, StringComparison.OrdinalIgnoreCase))
        {
            // A stale row can still hold the new handle (its identity-provider account deleted
            // there but not here). Renaming onto it would fail the unique index on every request
            // from then on, so the rider keeps the old handle until an administrator frees it.
            if (await users.ExistsByUsernameAsync(username, cancellationToken))
                logger.LogWarning(
                    "Not renaming {UserId} to {Username}: the handle is held by another account",
                    user.Id,
                    username);
            else
                user.Rename(username);
        }

        user.Touch();
    }
}
