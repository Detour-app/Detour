using System.Text.Json;
using Detour.Api.Contracts;
using Detour.Domain.Cameras;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace Detour.Api.Controllers;

/// <summary>
/// The read side of Detour's own camera dataset (issue #303). Unauthenticated, like
/// <c>/api/capabilities</c> — camera locations are public safety information, not rider data,
/// and gating them behind a token would only cost a self-hoster a round-trip for no privacy gain.
/// Covered by the global per-IP limiter only, not the tighter anonymous policy, which is why
/// <see cref="MaxSpanDegrees"/> exists — otherwise a single request spanning the whole globe
/// could materialize and serialize the entire table.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
public class CamerasController(ICameraRepository repository) : ControllerBase
{
    /// <summary>
    /// The largest bounding box, on either axis, this endpoint will answer. Comfortably larger
    /// than the roughly 0.15° span the client's own prefetch radius ever requests
    /// (<c>SpeedCameras.PREFETCH_RADIUS_M</c> in <c>shared/</c>), and small enough that an
    /// unauthenticated, lightly-rate-limited caller cannot force the whole table to
    /// materialize and serialize in one request.
    /// </summary>
    private const double MaxSpanDegrees = 2.0;

    [HttpGet]
    [EndpointSummary("Cameras and enforcement sections whose bounding box overlaps the query box.")]
    [ProducesResponseType<CamerasBboxResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<ActionResult<CamerasBboxResponse>> Get(
        [FromQuery] double minLat, [FromQuery] double minLon,
        [FromQuery] double maxLat, [FromQuery] double maxLon,
        CancellationToken cancellationToken)
    {
        if (minLat > maxLat || minLon > maxLon
            || minLat is < -90 or > 90 || maxLat is < -90 or > 90
            || minLon is < -180 or > 180 || maxLon is < -180 or > 180
            || maxLat - minLat > MaxSpanDegrees || maxLon - minLon > MaxSpanDegrees)
            return BadRequest();

        var cameras = await repository.BboxAsync(minLat, minLon, maxLat, maxLon, cancellationToken);
        return Ok(new CamerasBboxResponse(cameras.Select(ToDto).ToList()));
    }

    private static CameraDto ToDto(Camera c) => new(
        c.Id,
        c.Kind.ToString(),
        c.Lat,
        c.Lon,
        c.PolylineJson is null ? null : JsonSerializer.Deserialize<List<List<double>>>(c.PolylineJson),
        c.MaxSpeedKmh,
        c.RoadRef);
}
