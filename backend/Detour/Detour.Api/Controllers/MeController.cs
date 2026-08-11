using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Domain.Users;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class MeController(
    ICurrentUser currentUser,
    IBadgeAwardRepository badges) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("Get the signed-in rider's own account.")]
    [EndpointDescription(
        "Returns the caller's handle, address, fog-sharing preference and the aggregate numbers "
        + "their device last synced. The account is created on the first request made with a "
        + "token for a subject this backend has not seen before.")]
    [ProducesResponseType<MeResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<MeResponse>> Get(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var awards = await badges.GetForUserAsync(user.Id, cancellationToken);
        return Ok(MeResponse.Map(user, awards));
    }

    [HttpPut("fog-sharing")]
    [EndpointSummary("Turn shared fog of war on or off.")]
    [EndpointDescription(
        "Sharing is off by default and reciprocal: a rider who is not sharing both stops "
        + "contributing their own traces and stops receiving anyone else's. Turning it off takes "
        + "effect on the next request either side makes.")]
    [ProducesResponseType<MeResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<MeResponse>> SetFogSharing(
        [FromBody] SetFogSharingRequest request,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        user.SetFogSharing(request.ShareFog);

        var awards = await badges.GetForUserAsync(user.Id, cancellationToken);
        return Ok(MeResponse.Map(user, awards));
    }
}

public record SetFogSharingRequest(bool ShareFog);
