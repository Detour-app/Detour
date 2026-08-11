using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using Detour.Domain;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Shared.Domain;

namespace Detour.Api.Controllers;

[ApiController]
[Route("api/shared-routes")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class SharedRoutesController(ICurrentUser currentUser, IRouteSharingService routes) : ControllerBase
{
    [HttpPost]
    [EndpointSummary("Send a planned route to a friend.")]
    [EndpointDescription(
        "The friendship is re-checked on every share, so a route cannot be pushed to someone "
        + "unfriended a moment ago. Re-sharing the same route replaces the recipient's earlier "
        + "copy rather than appearing twice.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "Not a friend, too few stops, or too large.")]
    public async Task<IActionResult> Share(
        [FromBody] ShareRouteBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        (await routes.ShareAsync(user, body, cancellationToken)).ThrowIfFailure();
        return NoContent();
    }

    [HttpGet]
    [EndpointSummary("Routes friends have shared with the caller.")]
    [EndpointDescription("Newest first. The sender's name comes from their account, not from the stored document.")]
    [ProducesResponseType<SharedRouteInboxResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<SharedRouteInboxResponse>> GetInbox(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        return Ok(await routes.GetInboxAsync(user.Id, cancellationToken));
    }

    [HttpDelete("{id:guid}")]
    [EndpointSummary("Drop a shared route.")]
    [EndpointDescription(
        "Either side may: the recipient clearing their inbox, or the sender un-sharing. A caller "
        + "who is neither gets the same answer as one asking about a route that does not exist.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = await routes.DeleteAsync(user.Id, id, cancellationToken);
        if (result.HasError(ValidationKeys.SharedRoute.NotFound))
            return NotFound();

        result.ThrowIfFailure();
        return NoContent();
    }
}
