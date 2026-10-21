using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Live;
using Detour.Api.Services;
using Detour.Domain.Users;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Shared.Domain;

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

    [HttpDelete]
    [EndpointSummary("Delete the signed-in rider's own account and everything it owns.")]
    [EndpointDescription(
        "The same cascade as the administrator's delete: trips, traces, points, shortcuts, "
        + "badges, friendships, group memberships, shared routes, circle places and dashboard "
        + "keys all go with it. The Keycloak user is not removed here — this backend holds no "
        + "realm admin credential. When AccountDeletion:WebhookUrl is set, the subject and "
        + "handle are posted there after the delete commits so the realm administrator can "
        + "remove the sign-in; until then, signing in again starts a new, empty account.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Delete(
        IUserRepository users,
        IAccountDeletionNotifier notifier,
        IPostCommitActionScheduler postCommit,
        TimeProvider clock,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var deleted = new DeletedAccount(user.Subject, user.Username, clock.GetUtcNow());

        users.Delete(user);

        // After the commit, not before: a webhook that fires for a delete that then rolls back
        // would have the administrator remove the sign-in of an account that still exists.
        postCommit.Schedule(() => notifier.NotifyAsync(deleted));
        return NoContent();
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
            new LiveRider(user.Id),
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
