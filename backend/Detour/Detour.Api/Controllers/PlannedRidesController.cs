using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using Detour.Domain;
using JV.ResultUtilities;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Shared.Domain;

namespace Detour.Api.Controllers;

/// <summary>
/// Rides planned ahead: a time, a meeting point and the friends or circle members asked along.
/// A ride is visible to its owner and its invitees only; anyone else gets the same 404 an id
/// that does not exist gets. An invitee can read and answer, never edit.
/// </summary>
[ApiController]
[Route("api/planned-rides")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class PlannedRidesController(ICurrentUser currentUser, IPlannedRideService plannedRides) : ControllerBase
{
    [HttpPost]
    [EndpointSummary("Plan a ride.")]
    [EndpointDescription(
        "The caller owns it. Nobody is invited yet; invite separately. There is no time-window "
        + "rule: the owner may start the ride whenever they like.")]
    [ProducesResponseType<PlannedRideResponse>(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<PlannedRideResponse>> Create(
        [FromBody] PlannedRideBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await plannedRides.CreateAsync(user, body, cancellationToken);
        result.ThrowIfFailure();
        return CreatedAtAction(nameof(Get), new { id = result.Value.Id }, result.Value);
    }

    [HttpGet]
    [EndpointSummary("Rides the caller owns or is invited to.")]
    [EndpointDescription("Soonest start first, past rides included, each with every invitee and their answer.")]
    [ProducesResponseType<PlannedRidesResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<PlannedRidesResponse>> List(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        return Ok(await plannedRides.ListAsync(user.Id, cancellationToken));
    }

    [HttpGet("{id:guid}")]
    [EndpointSummary("One planned ride.")]
    [ProducesResponseType<PlannedRideResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<PlannedRideResponse>> Get(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await plannedRides.GetAsync(user.Id, id, cancellationToken);
        return Answer(result) ?? Ok(result.Value);
    }

    [HttpPut("{id:guid}")]
    [EndpointSummary("Edit a planned ride. Owner only.")]
    [EndpointDescription("Replaces every field. Invitations and answers are kept.")]
    [ProducesResponseType<PlannedRideResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden, Description = "The caller is invited, not the owner.")]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<PlannedRideResponse>> Update(
        Guid id,
        [FromBody] PlannedRideBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await plannedRides.UpdateAsync(user.Id, id, body, cancellationToken);
        return Answer(result) ?? Ok(result.Value);
    }

    [HttpDelete("{id:guid}")]
    [EndpointSummary("Cancel a planned ride. Owner only.")]
    [EndpointDescription("Deletes the ride and every invitation and answer with it.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden, Description = "The caller is invited, not the owner.")]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await plannedRides.DeleteAsync(user.Id, id, cancellationToken);
        return Answer(result) ?? NoContent();
    }

    [HttpPost("{id:guid}/invitations")]
    [EndpointSummary("Invite friends or circles. Owner only.")]
    [EndpointDescription(
        "Each rider must be an accepted friend of the owner or an accepted member of a circle the "
        + "owner belongs to; a circle expands to its accepted members now. One rider who may not "
        + "be invited refuses the whole request. Inviting someone already asked keeps their answer.")]
    [ProducesResponseType<PlannedRideResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden, Description = "The caller is invited, not the owner.")]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<PlannedRideResponse>> Invite(
        Guid id,
        [FromBody] PlannedRideInviteBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await plannedRides.InviteAsync(user.Id, id, body, cancellationToken);
        return Answer(result) ?? Ok(result.Value);
    }

    [HttpPut("{id:guid}/rsvp")]
    [EndpointSummary("Answer an invitation: going, maybe or declined.")]
    [EndpointDescription("The answer can be changed until the ride is cancelled. The owner has nothing to answer.")]
    [ProducesResponseType<RsvpResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<RsvpResponse>> Rsvp(
        Guid id,
        [FromBody] RsvpBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await plannedRides.AnswerAsync(user.Id, id, body.Answer, cancellationToken);
        return Answer(result) ?? Ok(new RsvpResponse(result.Value));
    }

    /// <summary>The two refusals that are not a 400, or null when the result succeeded.</summary>
    private ActionResult? Answer(Result result)
    {
        if (result.HasError(ValidationKeys.PlannedRide.NotFound))
            return NotFound();

        if (result.HasError(ValidationKeys.PlannedRide.NotTheOwner))
            return Forbid();

        result.ThrowIfFailure();
        return null;
    }

    private ActionResult? Answer<T>(Result<T> result)
    {
        if (result.HasError(ValidationKeys.PlannedRide.NotFound))
            return NotFound();

        if (result.HasError(ValidationKeys.PlannedRide.NotTheOwner))
            return Forbid();

        result.ThrowIfFailure();
        return null;
    }
}
