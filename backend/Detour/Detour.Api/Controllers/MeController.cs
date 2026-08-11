using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Live;
using Detour.Domain.Users;
using JV.ResultUtilities.Extensions;
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

    [HttpPost("fix")]
    [EndpointSummary("Report the rider's own position once.")]
    [EndpointDescription(
        "The background tier of the same ingest the live socket uses: one fix, relayed to every "
        + "circle and convoy the caller shares with someone, and stored as the caller's latest "
        + "position in each circle they are currently sharing with. A convoy never stores one. "
        + "Riders holding a live socket send positions over that instead; this exists so a phone "
        + "with no socket open still shows up on a circle map.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType<ProblemDetails>(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult> ReportPosition(
        [FromBody] PositionBody body,
        ILiveLocationService locations,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = await locations.IngestAsync(
            user,
            new LivePosition(
                body.Latitude,
                body.Longitude,
                body.AccuracyMeters,
                // Heading and speed are meaningless at this cadence — a bearing from minutes ago
                // would only draw a confidently wrong arrow on a peer's map.
                HeadingDegrees: null,
                SpeedKmh: null,
                body.TimestampMs ?? 0),
            LivePositionSource.Http,
            cancellationToken);

        result.ThrowIfFailure();
        return NoContent();
    }
}

public record SetFogSharingRequest(bool ShareFog);
