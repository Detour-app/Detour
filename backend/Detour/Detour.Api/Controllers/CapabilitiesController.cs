using Detour.Api.Configuration;
using Detour.Api.Contracts;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Options;
using Shared.Api.RateLimiting;

namespace Detour.Api.Controllers;

/// <summary>
/// What this deployment supports, for a client configuring itself against it.
///
/// Unauthenticated on purpose, and for the same reason <c>/api/health</c> is: a
/// caller needs this *before* it can obtain a token, since one of the things it
/// answers is which realm mints them. Nothing here is a secret — the realm
/// address is typed by riders and displayed by their browsers.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
[EnableRateLimiting(RateLimitPolicies.Anonymous)]
public class CapabilitiesController(IOptions<IdpSettings> idp) : ControllerBase
{
    /// <summary>Feature names this deployment answers for.</summary>
    private static readonly string[] Features = ["idp-discovery"];

    [HttpGet]
    [EndpointSummary("What this deployment supports.")]
    [EndpointDescription(
        "Unauthenticated, because a client needs the realm address before it can "
        + "hold a token. Clients ignore unknown feature names and fields; the schema "
        + "number changes only when an existing field does.")]
    [ProducesResponseType<CapabilitiesResponse>(StatusCodes.Status200OK)]
    public ActionResult<CapabilitiesResponse> Get() =>
        Ok(new CapabilitiesResponse(
            Schema: 1,
            Features: Features,
            Idp: new IdpCapabilityResponse(idp.Value.Authority)));
}
