using Detour.Api.Contracts;
using Detour.Domain.Pois;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The read side of Detour's own point-of-interest dataset (issue #383, phase 5 of #302) —
/// spin's random-POI feature. Unauthenticated, like `/api/cameras`/`/api/speedlimits`/
/// `/api/roads` — POI locations are public OSM data, not rider data. Covered by the global
/// per-IP limiter only, same reasoning as `SpeedLimitsController.MaxSpanDegrees`.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
public class PoisController(IPoiRepository repository) : ControllerBase
{
    /// <summary>Same reasoning and value as `CamerasController.MaxSpanDegrees`.</summary>
    private const double MaxSpanDegrees = 2.0;

    [HttpGet]
    [EndpointSummary("POIs whose point falls within the query box, optionally restricted to a set of kinds.")]
    [ProducesResponseType<PoisBboxResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<PoisBboxResponse>> Get(
        [FromQuery] double minLat, [FromQuery] double minLon,
        [FromQuery] double maxLat, [FromQuery] double maxLon,
        [FromQuery] string? kind,
        CancellationToken cancellationToken)
    {
        if (minLat > maxLat || minLon > maxLon
            || minLat is < -90 or > 90 || maxLat is < -90 or > 90
            || minLon is < -180 or > 180 || maxLon is < -180 or > 180
            || maxLat - minLat > MaxSpanDegrees || maxLon - minLon > MaxSpanDegrees)
            return BadRequest();

        var kinds = string.IsNullOrWhiteSpace(kind)
            ? null
            : kind.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

        var pois = await repository.BboxAsync(minLat, minLon, maxLat, maxLon, kinds, cancellationToken);
        return Ok(new PoisBboxResponse([.. pois.Select(ToDto)]));
    }

    private static PoiDto ToDto(Poi p) => new(p.Id, p.Kind, p.Name, p.Lat, p.Lon);
}
