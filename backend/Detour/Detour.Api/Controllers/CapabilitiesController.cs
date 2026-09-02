using Detour.Api.Configuration;
using Detour.Api.Contracts;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace Detour.Api.Controllers;

/// <summary>
/// What this deployment supports, for a client configuring itself against it.
///
/// Unauthenticated on purpose, and for the same reason <c>/api/health</c> is: a
/// caller needs this *before* it can obtain a token, since one of the things it
/// answers is which realm mints them. Nothing here is a secret — the realm
/// address is typed by riders and displayed by their browsers.
///
/// Covered by the global per-IP limiter only, exactly as <c>/api/health</c> is,
/// and *not* by the tighter <c>RateLimitPolicies.Anonymous</c> policy — even
/// though the shape of this endpoint is what that policy was written for.
///
/// The reason is #119: nothing in this service reads <c>X-Forwarded-*</c>, so
/// behind a reverse proxy every caller resolves to the proxy's address and a
/// per-IP bucket is one bucket for the whole deployment. A 20-token /
/// 10-per-60s budget shared that way is roughly ten sign-ins a minute across
/// every rider, and the client cannot tell the resulting 429 from "this server
/// has no capability endpoint" — so a correctly configured server would report
/// a configuration error for a transient one.
///
/// Apply the policy here once #119 lands and a per-IP bucket means one client
/// again. Until then the tighter budget would cost sign-ins and buy nothing
/// that the global limiter does not already provide.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
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
