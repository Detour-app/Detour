using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using JV.ResultUtilities.Extensions;

namespace Detour.Api.Controllers;

[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class SyncController(ICurrentUser currentUser, ISyncService sync) : ControllerBase
{
    [HttpPost]
    [EndpointSummary("Merge a device's rides, traces, shortcuts and badges.")]
    [EndpointDescription(
        "Bidirectional and idempotent. Every field is optional and absent never means 'clear'. "
        + "Trips key on their start instant, so a re-upload replaces an edited ride rather than "
        + "being ignored; deletions are applied after the upserts so they propagate to every "
        + "other device. Traces deduplicate on content. Badges keep the earliest instant seen. "
        + "Returns the merged union, which is what restores a device after a reinstall.")]
    [ProducesResponseType<SyncResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "A trip, place or trace line could not be read.")]
    public async Task<ActionResult<SyncResponse>> Post(
        [FromBody] SyncRequest request,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = await sync.MergeAsync(user, request, cancellationToken);
        result.ThrowIfFailure();

        return Ok(result.Value);
    }
}
