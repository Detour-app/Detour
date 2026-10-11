using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using Detour.Domain;
using Detour.Domain.Groups;
using JV.ResultUtilities;
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
public class ConvoysController(
    ICurrentUser currentUser,
    IGroupService groups,
    IConvoyInviteService invites) : ControllerBase
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

    [HttpPost("{id:guid}/invite-link")]
    [EndpointSummary("Mint an invite link for a convoy you host.")]
    [EndpointDescription(
        "Lets riders who are not the host's friends join this one convoy. The token is returned "
        + "once and only its hash is stored. Minting revokes any link still usable, so a convoy "
        + "has at most one. The link dies with the convoy and after 12 hours regardless.")]
    [ProducesResponseType<ConvoyInviteResponse>(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<ConvoyInviteResponse>> CreateInviteLink(
        Guid id,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var (result, invite) = await invites.CreateAsync(user.Id, id, cancellationToken);
        if (HostGateStatus(result) is { } refused)
            return refused;

        result.ThrowIfFailure();
        return StatusCode(StatusCodes.Status201Created, invite);
    }

    [HttpDelete("{id:guid}/invite-link")]
    [EndpointSummary("Revoke a convoy's invite link.")]
    [EndpointDescription("Stops new joins. Guests already in stay until removed or the convoy ends.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> RevokeInviteLink(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await invites.RevokeAsync(user.Id, id, cancellationToken);
        if (HostGateStatus(result) is { } refused)
            return refused;

        result.ThrowIfFailure();
        return NoContent();
    }

    [HttpGet("{id:guid}/guests")]
    [EndpointSummary("List the riders who joined a convoy you host through its invite link.")]
    [ProducesResponseType<ConvoyGuestsResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<ConvoyGuestsResponse>> Guests(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var (result, guests) = await invites.ListGuestsAsync(user.Id, id, cancellationToken);
        if (HostGateStatus(result) is { } refused)
            return refused;

        result.ThrowIfFailure();
        return Ok(guests);
    }

    [HttpDelete("{id:guid}/guests/{guestId:guid}")]
    [EndpointSummary("Remove a guest from a convoy you host.")]
    [EndpointDescription(
        "Takes them out of the convoy and its live traffic at once. The link they used will not "
        + "let them back in; a newly minted one would.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> RemoveGuest(
        Guid id,
        Guid guestId,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var result = await invites.RemoveGuestAsync(user.Id, id, guestId, cancellationToken);
        if (HostGateStatus(result) is { } refused)
            return refused;

        if (result.HasError(ValidationKeys.ConvoyInvite.GuestNotFound))
            return NotFound();

        result.ThrowIfFailure();
        return NoContent();
    }

    [HttpPost("join")]
    [EndpointSummary("Join a convoy through an invite link.")]
    [EndpointDescription(
        "Adds the caller as a guest for the convoy's lifetime, without a friendship. The token "
        + "travels in the body so it stays out of access logs. Attempts are capped per token and "
        + "per address, and a convoy takes at most 8 riders by link.")]
    [ProducesResponseType<GroupResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status410Gone)]
    [ProducesResponseType(StatusCodes.Status429TooManyRequests)]
    public async Task<ActionResult<GroupResponse>> Join(
        [FromBody] JoinConvoyBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        // The connection address only — the forwarded-headers middleware has already rewritten
        // it when the operator named a trusted proxy, the same rule the global limiter follows.
        var clientIp = HttpContext.Connection.RemoteIpAddress?.ToString() ?? "unknown";
        var result = await invites.JoinAsync(user, body.Token, clientIp, cancellationToken);

        if (result.HasError(ValidationKeys.ConvoyInvite.TooManyAttempts))
            return StatusCode(StatusCodes.Status429TooManyRequests);
        if (result.HasError(ValidationKeys.ConvoyInvite.NotFound))
            return NotFound();
        if (result.HasError(ValidationKeys.ConvoyInvite.Expired))
            return StatusCode(StatusCodes.Status410Gone);
        if (result.HasError(ValidationKeys.ConvoyInvite.GuestRemoved))
            return Forbid();

        result.ThrowIfFailure();
        return Ok(result.Value);
    }

    private ActionResult? HostGateStatus(Result result)
    {
        if (result.HasError(ValidationKeys.Group.NotAMember))
            return NotFound();

        return result.HasError(ValidationKeys.Group.NotTheHost) ? Forbid() : null;
    }
}
