using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain.Boundaries;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The read side of Detour's own municipality-boundary dataset (issue #381, phase 4 of #302).
/// Unauthenticated, like <c>/api/cameras</c>/<c>/api/speedlimits</c>/<c>/api/roads</c> —
/// administrative boundary geometry is public OSM data, not rider data. Covered by the global
/// per-IP limiter only: unlike those bbox endpoints, a query here is always a single point, so
/// there is no span to cap — the whole table can never be forced to materialise from one
/// request.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
public class MunicipalityController(IMunicipalityBoundaryRepository repository) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("The admin_level=8 boundary containing this point, or none.")]
    [ProducesResponseType<MunicipalityResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<MunicipalityResponse>> Get(
        [FromQuery] double lat, [FromQuery] double lon, CancellationToken cancellationToken)
    {
        if (lat is < -90 or > 90 || lon is < -180 or > 180)
            return BadRequest();

        var candidates = await repository.CandidatesAsync(lat, lon, cancellationToken);
        // Bbox overlap does not mean containment — an enclave's outer town and the enclave
        // itself can both have a bbox covering this point, and MunicipalityBoundary.Contains is
        // the exact test each candidate still has to pass.
        var found = candidates.FirstOrDefault(b => b.Contains(lat, lon));
        return Ok(new MunicipalityResponse(found is null ? null : ToDto(found)));
    }

    private static MunicipalityBoundaryDto ToDto(MunicipalityBoundary b) => new(
        b.OsmId,
        b.Name,
        JsonSerializer.Deserialize<List<List<List<double>>>>(b.RingsJson)!);
}
