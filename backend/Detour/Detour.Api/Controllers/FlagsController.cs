using Detour.Api.Authentication;
using Detour.Api.Contracts;
using Detour.Domain.FeatureFlags;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The feature flags (#810) for the caller: the defaults, with a signed-in account's overrides
/// in their place.
///
/// Public on purpose. A rider who is not signed in still gets the defaults, so every key is
/// readable by anyone; a key names a feature and says nothing the public repository doesn't.
/// Which accounts hold an override is never part of the answer.
///
/// Covered by the global per-IP limiter only, not <c>RateLimitPolicies.Anonymous</c>. That policy
/// is sized for guessing at sign-in (10 a minute per address); here there is nothing to guess,
/// the answer is a few small reads, and the app asks on every start and sign-in, so riders
/// behind one address (a household, a carrier NAT) would get a 429 and stale flags for nothing.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
public class FlagsController(
    IFeatureFlagRepository flags,
    ICurrentUser currentUser) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("Feature flags for the caller.")]
    [EndpointDescription(
        "Without an Authorization header: every flag's default. With a valid bearer: the "
        + "account's overrides replace the defaults. With an Authorization header that does not "
        + "validate: 401, so the client refreshes its token instead of caching the defaults "
        + "over the account's overrides.")]
    [ProducesResponseType<FlagsResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    public async Task<ActionResult<FlagsResponse>> Get(CancellationToken cancellationToken)
    {
        // [AllowAnonymous] lets a token that fails validation through as anonymous; answering
        // that with the defaults would let an expired token overwrite the account's cached values.
        var signedIn = User.Identity?.IsAuthenticated == true;
        if (Request.Headers.Authorization.Count > 0 && !signedIn)
            return Challenge(DetourAuthenticationSchemes.Bearer);

        var defaults = await flags.GetAllNonTrackingAsync(cancellationToken);
        if (!signedIn)
            return Ok(FlagsResponse.From(defaults, []));

        var user = await currentUser.GetAsync(cancellationToken);
        var overrides = await flags.GetOverridesForUserAsync(user.Id, cancellationToken);
        return Ok(FlagsResponse.From(defaults, overrides));
    }
}
