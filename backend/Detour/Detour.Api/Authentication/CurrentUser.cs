using System.Security.Claims;
using Detour.Api.Configuration;
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

        SyncFromToken(_cached, principal);
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

        await users.SaveAsync(user, cancellationToken);
        await users.FlushChangesAsync(cancellationToken);
        logger.LogInformation("Provisioned local account for subject {Subject}", subject);
        return user;
    }

    /// <summary>
    /// Re-reads what the identity provider says on every request rather than trusting the local
    /// copy. Revoking the administrator role has to take effect on the next token, not whenever
    /// this row happens to be rewritten for some other reason.
    /// </summary>
    private void SyncFromToken(User user, ClaimsPrincipal principal)
    {
        var isAdministrator = principal.IsInRole(idpSettings.Value.AdministratorRole);
        if (user.IsAdministrator != isAdministrator)
            user.SetAdministrator(isAdministrator);

        var email = principal.FindFirstValue(DetourClaims.Email);
        if (!string.IsNullOrWhiteSpace(email) && !string.Equals(user.Email, email, StringComparison.OrdinalIgnoreCase))
            user.UpdateEmail(email);

        var username = principal.FindFirstValue(DetourClaims.PreferredUsername);
        if (!string.IsNullOrWhiteSpace(username) && !string.Equals(user.Username, username, StringComparison.OrdinalIgnoreCase))
            user.Rename(username);

        user.Touch();
    }
}
