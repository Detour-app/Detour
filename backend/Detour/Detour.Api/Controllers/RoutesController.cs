using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using Detour.Domain;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The rider's saved routes one at a time, for the web planner (#912). The phone keeps using
/// <c>/sync</c>; both read and write the same rows under the same rules.
/// </summary>
[ApiController]
[Route("api/routes")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class RoutesController(ICurrentUser currentUser, ISavedRouteService routes) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("The caller's saved routes.")]
    [EndpointDescription(
        "Each route document exactly as stored, the same ones /sync returns, ascending by id. "
        + "Deleted routes are not listed.")]
    [ProducesResponseType<RouteListResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<RouteListResponse>> List(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        return Ok(await routes.ListAsync(user.Id, cancellationToken));
    }

    [HttpPut("{clientId:long}")]
    [RequestSizeLimit(DetourLimits.MaxRoutePayloadBytes)]
    [EndpointSummary("Create or edit one saved route.")]
    [EndpointDescription(
        "The body is the route document, stored as sent. It needs the id from the path, an "
        + "editedAtMs above 0, at least two stops and a mode. The newest edit wins: a body whose "
        + "editedAtMs is older than the stored copy's is refused with 409 and that copy, so a "
        + "phone's newer edit is never overwritten. A route the rider deleted answers 410 and is "
        + "not stored again.")]
    [ProducesResponseType<RouteDocument>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "Id mismatch, no edit stamp, too few stops, no mode, or too large.")]
    [ProducesResponseType<RouteConflictResponse>(StatusCodes.Status409Conflict)]
    [ProducesResponseType(StatusCodes.Status410Gone)]
    [ProducesResponseType(StatusCodes.Status413PayloadTooLarge)]
    public async Task<IActionResult> Put(
        long clientId,
        [FromBody] RouteDocument body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = await routes.PutAsync(user.Id, clientId, body, cancellationToken);
        result.ThrowIfFailure();

        var outcome = result.Value;
        return outcome.Status switch
        {
            RouteWriteStatus.Deleted => StatusCode(StatusCodes.Status410Gone),
            RouteWriteStatus.Stale => Conflict(new RouteConflictResponse(outcome.Document!.Value)),
            _ => Ok(outcome.Document),
        };
    }

    [HttpDelete("{clientId:long}")]
    [EndpointSummary("Delete one saved route.")]
    [EndpointDescription(
        "Removes the stored copy and keeps the deletion, so a phone that still holds the route "
        + "drops it on its next sync instead of uploading it again. Idempotent: deleting a route "
        + "that is already gone, or never existed, answers 204 too.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> Delete(long clientId, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        (await routes.DeleteAsync(user.Id, clientId, cancellationToken)).ThrowIfFailure();
        return NoContent();
    }
}
