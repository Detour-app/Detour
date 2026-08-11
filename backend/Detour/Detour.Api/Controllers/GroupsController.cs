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

/// <summary>
/// Convoys and circles are the same entity with different lifetimes and powers, so membership
/// is managed through one set of routes. What differs — pausing, positions, places, presence —
/// lives on the circle routes, which are the only ones that would mean anything for a convoy.
/// </summary>
[ApiController]
[Route("api/groups")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class GroupsController(ICurrentUser currentUser, IGroupService groups) : ControllerBase
{
    [HttpPost("{id:guid}/invitations")]
    [EndpointSummary("Invite a friend into a convoy or circle.")]
    [EndpointDescription(
        "Requires the caller to be an accepted member and an accepted friend of the invitee — "
        + "that chain is what makes group membership granted access rather than an open room. A "
        + "group the caller does not belong to answers the same way whether or not it exists.")]
    [ProducesResponseType<MembershipStatusResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<MembershipStatusResponse>> Invite(
        Guid id,
        [FromBody] InviteBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await groups.InviteAsync(user, id, body.Username, cancellationToken);
        result.ThrowIfFailure();
        return Ok(new MembershipStatusResponse(result.Value));
    }

    [HttpPost("{id:guid}/invitations/respond")]
    [EndpointSummary("Accept or decline an invitation.")]
    [ProducesResponseType<MembershipStatusResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<MembershipStatusResponse>> Respond(
        Guid id,
        [FromBody] RespondBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await groups.RespondAsync(user.Id, id, body.Accept, cancellationToken);
        result.ThrowIfFailure();
        return Ok(new MembershipStatusResponse(result.Value));
    }

    [HttpDelete("{id:guid}/membership")]
    [EndpointSummary("Leave a convoy or circle.")]
    [EndpointDescription(
        "Takes the leaver's shared places and last known position with them. A convoy with "
        + "nobody left is deleted; a circle persists, including while one member is alone in it.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Leave(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = await groups.LeaveAsync(user.Id, id, cancellationToken);
        if (result.HasError(ValidationKeys.Group.NotAMember))
            return NotFound();

        result.ThrowIfFailure();
        return NoContent();
    }
}

[ApiController]
[Route("api/convoys")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class ConvoysController(ICurrentUser currentUser, IGroupService groups) : ControllerBase
{
    [HttpPost]
    [EndpointSummary("Start a convoy.")]
    [EndpointDescription("A ride together. The creator joins automatically, already accepted.")]
    [ProducesResponseType<GroupResponse>(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<GroupResponse>> Create(
        [FromBody] CreateGroupBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await groups.CreateAsync(user, GroupKind.Convoy, body.Name, cancellationToken);
        result.ThrowIfFailure();
        return CreatedAtAction(nameof(Get), new { }, result.Value);
    }

    [HttpGet]
    [EndpointSummary("List the caller's convoys.")]
    [ProducesResponseType<IReadOnlyList<GroupResponse>>(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<GroupResponse>>> Get(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        return Ok(await groups.ListAsync(user.Id, GroupKind.Convoy, cancellationToken));
    }
}
