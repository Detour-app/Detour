using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Domain.FeatureFlags;
using Detour.Domain.Users;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The owner's switch for feature flags (#810): defaults for everyone and overrides for one
/// account. What the app reads is <see cref="FlagsController"/>.
///
/// A flag is not rider content, so this stays inside what an administrator may see (BACKEND_SPEC
/// §2 invariant 9): the list names which accounts hold an override, and nothing about what is in
/// them. Never calls <c>ICurrentUser</c>, so a service account without a rider row can use it too.
///
/// Tagged <c>Admin</c> so the generated Bruno requests land beside the account ones.
/// </summary>
[ApiController]
[Route("api/admin/flags")]
[Tags("Admin")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Administrator)]
public class AdminFlagsController(
    IFeatureFlagRepository flags,
    IUserRepository users) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("Every feature flag default and override.")]
    [EndpointDescription(
        "Defaults ordered by key, and every per-account override with the account's id and "
        + "username. The id is the one GET /api/admin/accounts returns.")]
    [ProducesResponseType<AdminFlagsResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<AdminFlagsResponse>> List(CancellationToken cancellationToken)
    {
        var defaults = await flags.GetAllNonTrackingAsync(cancellationToken);
        var overrides = await flags.GetAllOverridesNonTrackingAsync(cancellationToken);

        return Ok(new AdminFlagsResponse(
            [
                .. defaults
                    .OrderBy(f => f.Key, StringComparer.Ordinal)
                    .Select(f => new AdminFlagResponse(f.Key, f.Enabled, f.UpdatedAt.ToUnixTimeMilliseconds()))
            ],
            [.. overrides.Select(o => new AdminFlagOverrideResponse(o.FlagKey, o.UserId, o.Username, o.Enabled))]));
    }

    [HttpPut("{key}")]
    [EndpointSummary("Set a feature flag default.")]
    [EndpointDescription(
        "Creates the flag when this server has no row for it yet. Every account without an "
        + "override gets the new value on its next GET /api/flags.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<IActionResult> SetDefault(
        string key,
        [FromBody] SetFlagBody body,
        CancellationToken cancellationToken)
    {
        var created = FeatureFlag.Create(key, body.Enabled);
        created.ThrowIfFailure();

        var flag = await flags.GetByKeyAsync(key, cancellationToken);
        if (flag is null)
            await flags.SaveAsync(created.Value, cancellationToken);
        else
            flag.SetEnabled(body.Enabled);

        return NoContent();
    }

    [HttpDelete("{key}")]
    [EndpointSummary("Delete a feature flag and its overrides.")]
    [EndpointDescription(
        "The app then keeps its compiled default for the key, as it does for a key this server "
        + "never had.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DeleteFlag(string key, CancellationToken cancellationToken)
    {
        FeatureFlag.ValidateKey(key).ThrowIfFailure();

        var flag = await flags.GetByKeyAsync(key, cancellationToken);
        if (flag is null)
            return NotFound();

        // The overrides go by the foreign key's cascade.
        flags.Delete(flag);
        return NoContent();
    }

    [HttpPut("{key}/accounts/{accountId:guid}")]
    [EndpointSummary("Set a feature flag for one account.")]
    [EndpointDescription(
        "Replaces the default for that account only. The flag must exist first: set its default "
        + "before overriding it.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound, Description = "No such flag, or no such account.")]
    public async Task<IActionResult> SetOverride(
        string key,
        Guid accountId,
        [FromBody] SetFlagBody body,
        CancellationToken cancellationToken)
    {
        FeatureFlag.ValidateKey(key).ThrowIfFailure();

        var flag = await flags.GetByKeyAsync(key, cancellationToken);
        if (flag is null || !await users.ExistsAsync(accountId, cancellationToken))
            return NotFound();

        var existing = await flags.GetOverrideAsync(key, accountId, cancellationToken);
        if (existing is null)
            flags.SaveOverride(flag.OverrideFor(accountId, body.Enabled));
        else
            existing.SetEnabled(body.Enabled);

        return NoContent();
    }

    [HttpDelete("{key}/accounts/{accountId:guid}")]
    [EndpointSummary("Remove one account's override of a feature flag.")]
    [EndpointDescription("The account gets the default again on its next GET /api/flags.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DeleteOverride(
        string key,
        Guid accountId,
        CancellationToken cancellationToken)
    {
        FeatureFlag.ValidateKey(key).ThrowIfFailure();

        var existing = await flags.GetOverrideAsync(key, accountId, cancellationToken);
        if (existing is null)
            return NotFound();

        flags.DeleteOverride(existing);
        return NoContent();
    }
}
