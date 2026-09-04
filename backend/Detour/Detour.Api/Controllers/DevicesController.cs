using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

[ApiController]
[Route("api/devices")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class DevicesController(ICurrentUser currentUser, IDeviceService devices) : ControllerBase
{
    [HttpPut]
    [EndpointSummary("Register or refresh this install's push token.")]
    [EndpointDescription(
        "Idempotent. Called on sign-in, on app start, and whenever the push "
        + "service rotates the token. A token already registered to another "
        + "rider is reassigned to the caller — one install, one owner.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest, Description = "Unknown platform or blank token.")]
    public async Task<IActionResult> Register(
        [FromBody] RegisterDeviceBody body, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        (await devices.RegisterAsync(user.Id, body.Token, body.Platform, cancellationToken))
            .ThrowIfFailure();
        return NoContent();
    }

    [HttpDelete]
    [EndpointSummary("Drop this install's push token.")]
    [EndpointDescription("Called on sign-out. Silent about a token that was never registered.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    public async Task<IActionResult> Unregister(
        [FromBody] UnregisterDeviceBody body, CancellationToken cancellationToken)
    {
        await currentUser.GetAsync(cancellationToken); // auth*n* only; a token is not owner-scoped to delete
        await devices.RemoveAsync(body.Token, cancellationToken);
        return NoContent();
    }
}
