using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Domain.ApiKeys;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// A rider manages their own dashboard keys. Deliberately not an administrator capability: a
/// key reads its owner's rides, so nobody else should be able to mint one.
/// </summary>
[ApiController]
[Route("api/me/api-keys")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Rider)]
public class ApiKeysController(ICurrentUser currentUser, IApiKeyRepository apiKeys) : ControllerBase
{
    [HttpPost]
    [EndpointSummary("Issue a read-only dashboard key.")]
    [EndpointDescription(
        "The plaintext is returned once and never stored — only its hash is, so a database leak "
        + "hands over nothing replayable. A lost key is replaced, not recovered.")]
    [ProducesResponseType<IssuedApiKeyResponse>(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<IssuedApiKeyResponse>> Issue(
        [FromBody] IssueApiKeyBody body,
        CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var result = ApiKey.Issue(user.Id, body.Label);
        result.ThrowIfFailure();

        var (key, plaintext) = result.Value;
        await apiKeys.SaveAsync(key, cancellationToken);

        return CreatedAtAction(
            nameof(Get),
            new { },
            new IssuedApiKeyResponse(key.Id, key.Label, plaintext));
    }

    [HttpGet]
    [EndpointSummary("List the caller's dashboard keys.")]
    [EndpointDescription("Metadata only. The keys themselves are unrecoverable by design.")]
    [ProducesResponseType<ApiKeysResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<ApiKeysResponse>> Get(CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);
        var keys = await apiKeys.GetForUserAsync(user.Id, cancellationToken);

        return Ok(new ApiKeysResponse(
        [
            .. keys.Select(k => new ApiKeyResponse(
                k.Id,
                k.Label,
                k.CreatedAt.ToUnixTimeMilliseconds(),
                k.LastUsedAt?.ToUnixTimeMilliseconds()))
        ]));
    }

    [HttpDelete("{id:guid}")]
    [EndpointSummary("Revoke a dashboard key.")]
    [EndpointDescription("Takes effect immediately, and does not sign the rider's phone out.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Revoke(Guid id, CancellationToken cancellationToken)
    {
        var user = await currentUser.GetAsync(cancellationToken);

        var key = await apiKeys.GetAsync(id, cancellationToken);

        // A key belonging to someone else answers the same as one that never existed.
        if (key is null || key.UserId != user.Id)
            return NotFound();

        apiKeys.Delete(key);
        return NoContent();
    }
}
