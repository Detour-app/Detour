using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using Detour.Domain;
using Detour.Domain.Groups;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Shared.Domain;

namespace Detour.Api.Controllers;

[ApiController]
[Route("api/circles")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class CirclesController(
    ICurrentUser currentUser,
    IGroupService groups,
    ICircleService circles) : ControllerBase
{
    [HttpPost]
    [EndpointSummary("Start a circle.")]
    [EndpointDescription(
        "A standing map of who is where. Unlike a convoy it persists when everyone leaves, caps "
        + "its membership, and never carries voice.")]
    [ProducesResponseType<GroupResponse>(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<GroupResponse>> Create(
        [FromBody] CreateGroupBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await groups.CreateAsync(user, GroupKind.Circle, body.Name, cancellationToken);
        result.ThrowIfFailure();
        return CreatedAtAction(nameof(Get), new { }, result.Value);
    }

    [HttpGet]
    [EndpointSummary("List the caller's circles.")]
    [ProducesResponseType<IReadOnlyList<GroupResponse>>(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<GroupResponse>>> Get(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        return Ok(await groups.ListAsync(user.Id, GroupKind.Circle, cancellationToken));
    }

    [HttpPut("{id:guid}/sharing")]
    [EndpointSummary("Pause or resume sharing your position within one circle.")]
    [EndpointDescription(
        "Per person per circle, not per rider. Enforced on both paths: paused positions are "
        + "dropped on the way in and excluded on the way out, so a stale client build cannot "
        + "keep broadcasting after the rider believes they stopped. A convoy id answers "
        + "not-found rather than not-applicable.")]
    [ProducesResponseType<SharingResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<SharingResponse>> SetSharing(
        Guid id,
        [FromBody] SetSharingBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = await groups.SetSharingAsync(user.Id, id, body.Sharing, cancellationToken);
        if (result.HasError(ValidationKeys.Group.NotAMember))
            return NotFound();

        result.ThrowIfFailure();
        return Ok(new SharingResponse(result.Value));
    }

    [HttpPost("{id:guid}/positions")]
    [EndpointSummary("Report your position to a circle.")]
    [EndpointDescription(
        "Low cadence, over plain REST — circles update on the order of minutes, which does not "
        + "justify holding a stream open all day. Overwrites in place: no history, no trail. A "
        + "paused member's report is accepted and discarded rather than refused, so pausing "
        + "needs no special handling on the device.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "Not a member, or an unusable position.")]
    public async Task<IActionResult> ReportPosition(
        Guid id,
        [FromBody] PositionBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        (await circles.RecordPositionAsync(user.Id, id, body, cancellationToken)).ThrowIfFailure();
        return NoContent();
    }

    [HttpGet("{id:guid}/positions")]
    [EndpointSummary("Where everyone in the circle is now.")]
    [EndpointDescription("Accepted and currently-sharing members only.")]
    [ProducesResponseType<CircleFixesResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<CircleFixesResponse>> GetPositions(
        Guid id,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await circles.GetPositionsAsync(user.Id, id, cancellationToken);
        result.ThrowIfFailure();
        return Ok(result.Value);
    }

    [HttpPost("{id:guid}/places")]
    [EndpointSummary("Share a place into a circle.")]
    [EndpointDescription(
        "User-owned and revoked when the owner leaves the circle — the same rule unfriending "
        + "applies to routes. Re-sharing the same place replaces the earlier copy.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> SharePlace(
        Guid id,
        [FromBody] SharePlaceBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        (await circles.SharePlaceAsync(user.Id, id, body.Place, cancellationToken)).ThrowIfFailure();
        return NoContent();
    }

    [HttpGet("{id:guid}/places")]
    [EndpointSummary("Places shared into a circle, by anyone in it.")]
    [ProducesResponseType<CirclePlacesResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<CirclePlacesResponse>> GetPlaces(
        Guid id,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await circles.GetPlacesAsync(user.Id, id, cancellationToken);
        result.ThrowIfFailure();
        return Ok(result.Value);
    }

    [HttpPost("{id:guid}/events")]
    [EndpointSummary("Record an arrival or departure.")]
    [EndpointDescription(
        "Geofences are evaluated on the device; this records the result and fans it out to the "
        + "rest of the circle. The event is always attributed to the caller, so nobody can "
        + "claim a transition happened to someone else.")]
    [ProducesResponseType<PlaceEventResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<PlaceEventResponse>> RecordEvent(
        Guid id,
        [FromBody] RecordEventBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await circles.RecordEventAsync(user, id, body, cancellationToken);
        result.ThrowIfFailure();
        return Ok(result.Value);
    }

    [HttpGet("{id:guid}/events")]
    [EndpointSummary("A circle's recent arrivals and departures.")]
    [EndpointDescription(
        "Oldest first, after the given instant. Includes the caller's own — a rider's timeline "
        + "is part of what a circle shows.")]
    [ProducesResponseType<PlaceEventsResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<PlaceEventsResponse>> GetEvents(
        Guid id,
        [FromQuery] long since,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await circles.GetEventsAsync(user.Id, id, Math.Max(since, 0), cancellationToken);
        result.ThrowIfFailure();
        return Ok(result.Value);
    }
}

[ApiController]
[Route("api/circle-places")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class CirclePlacesController(ICurrentUser currentUser, ICircleService circles) : ControllerBase
{
    [HttpDelete("{id:guid}")]
    [EndpointSummary("Remove a place you shared.")]
    [EndpointDescription(
        "Owner only. A caller who is not the owner gets the same answer as one asking about a "
        + "place that does not exist.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = await circles.DeletePlaceAsync(user.Id, id, cancellationToken);
        if (result.HasError(ValidationKeys.CirclePlace.NotFound))
            return NotFound();

        result.ThrowIfFailure();
        return NoContent();
    }
}
