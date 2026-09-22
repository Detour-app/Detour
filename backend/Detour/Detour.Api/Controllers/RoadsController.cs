using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain.Roads;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The read side of Detour's own drivable-road dataset (issue #380, phase 3 of #302; also
/// reused by spin's random-road feature, issue #382). Unauthenticated, like
/// <c>/api/cameras</c>/<c>/api/speedlimits</c> — road geometry is public OSM data, not rider
/// data. Covered by the global per-IP limiter only, same reasoning as
/// <c>SpeedLimitsController.MaxSpanDegrees</c>.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
public class RoadsController(IRoadWayRepository repository) : ControllerBase
{
    /// <summary>Same reasoning and value as <c>CamerasController.MaxSpanDegrees</c>.</summary>
    private const double MaxSpanDegrees = 2.0;

    [HttpGet]
    [EndpointSummary("Drivable ways whose bounding box overlaps the query box, optionally restricted to a set of highway classes.")]
    [ProducesResponseType<RoadsBboxResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<RoadsBboxResponse>> Get(
        [FromQuery] double minLat, [FromQuery] double minLon,
        [FromQuery] double maxLat, [FromQuery] double maxLon,
        [FromQuery] string? classes,
        CancellationToken cancellationToken)
    {
        if (minLat > maxLat || minLon > maxLon
            || minLat is < -90 or > 90 || maxLat is < -90 or > 90
            || minLon is < -180 or > 180 || maxLon is < -180 or > 180
            || maxLat - minLat > MaxSpanDegrees || maxLon - minLon > MaxSpanDegrees)
            return BadRequest();

        var highwayClasses = string.IsNullOrWhiteSpace(classes)
            ? null
            : classes.Split(',', StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);

        var ways = await repository.BboxAsync(minLat, minLon, maxLat, maxLon, highwayClasses, cancellationToken);
        return Ok(new RoadsBboxResponse([.. ways.Select(ToDto)]));
    }

    private static RoadWayDto ToDto(RoadWay w) => new(
        w.Id,
        w.Highway,
        JsonSerializer.Deserialize<List<List<double>>>(w.PolylineJson)!);
}
