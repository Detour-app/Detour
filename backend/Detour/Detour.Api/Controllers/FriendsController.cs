using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class FriendsController(ICurrentUser currentUser, IFriendshipService friendships) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("List friends and pending requests.")]
    [EndpointDescription(
        "One list of riders, each tagged with its relation to the caller — friend, incoming, "
        + "or outgoing — rather than three separate sets. Incoming and outgoing describe the "
        + "same pending request seen from either side, not two different states.")]
    [ProducesResponseType<FriendsResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<FriendsResponse>> Get(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        return Ok(await friendships.ListAsync(user.Id, cancellationToken));
    }

    [HttpPost("requests")]
    [EndpointSummary("Ask a rider to be friends.")]
    [EndpointDescription(
        "Asking someone who already asked you accepts their request instead — otherwise two "
        + "riders who each reached out would stay strangers. Asking an existing friend does "
        + "nothing.")]
    [ProducesResponseType<FriendshipStatusResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "No such rider, or the caller asked themselves.")]
    public async Task<ActionResult<FriendshipStatusResponse>> SendRequest(
        [FromBody] FriendRequestBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await friendships.RequestAsync(user, body.Username, cancellationToken);
        result.ThrowIfFailure();
        return Ok(new FriendshipStatusResponse(result.Value.Wire()));
    }

    [HttpPost("requests/{id:guid}/respond")]
    [EndpointSummary("Accept or decline a pending request.")]
    [EndpointDescription("Only the side that did not send the request may answer it.")]
    [ProducesResponseType<FriendshipStatusResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "No pending request from that rider.")]
    public async Task<ActionResult<FriendshipStatusResponse>> Respond(
        Guid id,
        [FromBody] FriendRespondBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await friendships.RespondAsync(user, id, body.Accept, cancellationToken);
        result.ThrowIfFailure();
        return Ok(new FriendshipStatusResponse(result.Value.Wire()));
    }

    [HttpDelete("{id:guid}")]
    [EndpointSummary("End a friendship.")]
    [EndpointDescription(
        "Also deletes every route shared between the two riders, in both directions. A route is "
        + "places you have been, so losing the friendship takes it back.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Remove(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        (await friendships.RemoveAsync(user, id, cancellationToken)).ThrowIfFailure();
        return NoContent();
    }

    [HttpGet("stats")]
    [EndpointSummary("Friends' lifetime numbers and badges.")]
    [EndpointDescription(
        "Aggregates only — the numbers each friend's own device computed. Sorted by total "
        + "distance. No endpoint anywhere returns another rider's trips.")]
    [ProducesResponseType<IReadOnlyList<FriendStatsResponse>>(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<FriendStatsResponse>>> GetStats(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await friendships.GetFriendStatsAsync(user.Id, cancellationToken);
        result.ThrowIfFailure();
        return Ok(result.Value);
    }

    [HttpGet("fog")]
    [EndpointSummary("The union of friends' explored roads.")]
    [EndpointDescription(
        "Requires the caller to be sharing too: a rider who turns sharing off both stops "
        + "contributing and stops receiving. Lines come back unattributed — this is a map, not "
        + "a per-friend history — and a friend who has just revoked sharing drops out on this "
        + "request, not the next cache cycle.")]
    [ProducesResponseType<SharedFogResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<SharedFogResponse>> GetFog(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        return Ok(await friendships.GetSharedFogAsync(user, cancellationToken));
    }
}
