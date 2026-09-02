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
///
/// Rate-limited where health is not, and deliberately so: health is polled by
/// load balancers and uptime monitors on a fixed cadence, so the 20-token /
/// 10-per-60s per-IP bucket would throttle exactly the caller that must never
/// be throttled. This endpoint is hit once per interactive sign-in, a rate that
/// budget fits.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
[EnableRateLimiting(RateLimitPolicies.Anonymous)]
public class CapabilitiesController(IOptions<IdpSettings> idpSettings) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("What this deployment supports.")]
    [EndpointDescription(
        "Unauthenticated, because a client needs the realm address before it can "
        + "hold a token. Clients ignore unknown feature names and fields; the schema "
        + "number changes only when an existing field does.")]
    [ProducesResponseType<CapabilitiesResponse>(StatusCodes.Status200OK)]
    public ActionResult<CapabilitiesResponse> Get() => Ok(CapabilitiesResponse.From(idpSettings.Value));
}
