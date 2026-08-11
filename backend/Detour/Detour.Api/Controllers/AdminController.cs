using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Domain.ApiKeys;
using Detour.Domain.Traces;
using Detour.Domain.Trips;
using Detour.Domain.Users;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// What is left of administration once identity moved to Keycloak.
///
/// Creating accounts, resetting passwords, issuing invites, granting and revoking the
/// administrator role, and locking an account out are all realm operations now — they happen in
/// the Keycloak console, against the realm's own audit trail, and this backend has no business
/// duplicating them.
///
/// What remains is the part Keycloak cannot know about: how much a rider actually stored here,
/// and removing it. Every response is account metadata and row counts. There is deliberately no
/// endpoint that returns anyone's trips, traces, routes or places — the privacy rules are not
/// relaxed for administrators, and the way that is guaranteed is that the capability does not
/// exist rather than that a permission withholds it.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Administrator)]
public class AdminController(
    ICurrentUser currentUser,
    IUserRepository users,
    ITripRepository trips,
    ITraceRepository traces,
    IBadgeAwardRepository badges,
    IApiKeyRepository apiKeys) : ControllerBase
{
    [HttpGet("accounts")]
    [EndpointSummary("Every account, with what it holds.")]
    [EndpointDescription(
        "Metadata and row counts only. No trip, trace, route or place content is readable here, "
        + "and no endpoint exists that would make it readable.")]
    [ProducesResponseType<AdminOverviewResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<AdminOverviewResponse>> GetAccounts(CancellationToken cancellationToken)
    {
        var accounts = await users.GetAllNonTrackingAsync(cancellationToken);
        var response = new List<AdminAccountResponse>(accounts.Count);

        foreach (var account in accounts.OrderBy(a => a.Username, StringComparer.OrdinalIgnoreCase))
        {
            response.Add(new AdminAccountResponse(
                account.Id,
                account.Username,
                account.Email,
                account.IsAdministrator,
                account.ShareFog,
                account.CreatedAt.ToUnixTimeMilliseconds(),
                account.LastSeenAt?.ToUnixTimeMilliseconds(),
                await trips.CountForUserAsync(account.Id, cancellationToken),
                await traces.CountForUserAsync(account.Id, cancellationToken),
                await badges.CountForUserAsync(account.Id, cancellationToken),
                (await apiKeys.GetForUserAsync(account.Id, cancellationToken)).Count,
                Math.Round(account.Stats.TotalDistanceMeters / 1000.0, 1)));
        }

        return Ok(new AdminOverviewResponse(response.Count, response));
    }

    [HttpDelete("accounts/{id:guid}")]
    [EndpointSummary("Delete an account and everything it owns.")]
    [EndpointDescription(
        "Trips, traces, points, shortcuts, badges, friendships, group memberships, shared "
        + "routes, circle places and dashboard keys all go with it — every table referencing the "
        + "account cascades. Removing the rider from Keycloak is a separate act; doing only that "
        + "leaves their data here, and doing only this leaves them able to sign in and start "
        + "again.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "An administrator cannot delete their own account.")]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DeleteAccount(Guid id, CancellationToken cancellationToken)
    {
        var caller = await currentUser.GetAsync(cancellationToken);

        // Refuse rather than let an administrator lock themselves out mid-operation.
        if (caller.Id == id)
            return BadRequest();

        var account = await users.GetAsync(id, cancellationToken);
        if (account is null)
            return NotFound();

        users.Delete(account);
        return NoContent();
    }

    [HttpDelete("accounts/{id:guid}/api-keys")]
    [EndpointSummary("Revoke every dashboard key an account holds.")]
    [EndpointDescription(
        "The lost-device remedy for the one credential Keycloak does not issue. Does not affect "
        + "the rider's own session, which is Keycloak's to end.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> RevokeApiKeys(Guid id, CancellationToken cancellationToken)
    {
        if (!await users.ExistsAsync(id, cancellationToken))
            return NotFound();

        await apiKeys.DeleteForUserAsync(id, cancellationToken);
        return NoContent();
    }
}
