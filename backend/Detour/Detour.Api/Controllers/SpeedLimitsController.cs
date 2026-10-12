using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain.Roads;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The read side of Detour's own speed-limit-way dataset (issue #379). Unauthenticated, like
/// <c>/api/cameras</c> — posted speed limits are public safety information, not rider data.
/// Covered by the global per-IP limiter only, not the tighter anonymous policy, which is why
/// <see cref="MaxSpanDegrees"/> exists — otherwise a single request spanning the whole globe
/// could materialize and serialize the entire table.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
public class SpeedLimitsController(ISpeedLimitWayRepository repository) : ControllerBase
{
    /// <summary>Same reasoning as <c>CamerasController.MaxSpanDegrees</c>, tighter value (#691):
    /// this table holds every limited way in the region, so a 2° box was most of a country per
    /// request. 0.25° is still about three times the widest span
    /// <c>RoadRoulette.SPEED_PREFETCH_RADIUS_M</c> ever requests (1.5 km radius: 0.027° of
    /// latitude, 0.08° of longitude at 70°N).</summary>
    private const double MaxSpanDegrees = 0.25;

    [HttpGet]
    [EndpointSummary("Drivable ways with a posted speed limit whose bounding box overlaps the query box.")]
    [ProducesResponseType<SpeedLimitsBboxResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<SpeedLimitsBboxResponse>> Get(
        [FromQuery] double minLat, [FromQuery] double minLon,
        [FromQuery] double maxLat, [FromQuery] double maxLon,
        CancellationToken cancellationToken)
    {
        if (minLat > maxLat || minLon > maxLon
            || minLat is < -90 or > 90 || maxLat is < -90 or > 90
            || minLon is < -180 or > 180 || maxLon is < -180 or > 180
            || maxLat - minLat > MaxSpanDegrees || maxLon - minLon > MaxSpanDegrees)
            return BadRequest();

        var ways = await repository.BboxAsync(minLat, minLon, maxLat, maxLon, cancellationToken);
        return Ok(new SpeedLimitsBboxResponse([.. ways.Select(ToDto)]));
    }

    private static SpeedLimitWayDto ToDto(SpeedLimitWay w) => new(
        w.Id,
        w.MaxSpeedKmh,
        JsonSerializer.Deserialize<List<List<double>>>(w.PolylineJson)!);
}
