using Detour.Api.Authentication;
using Detour.Api.Authorization;
using Detour.Api.Contracts;
using Detour.Api.Services;
using Detour.Domain;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Shared.Domain;

namespace Detour.Api.Controllers;

/// <summary>
/// The read-only surface a home-automation dashboard polls.
///
/// Authenticated by API key rather than a rider session, and that is the whole permission
/// model: a key reads its own owner's data and can do nothing else. The key may travel as a
/// header (for a polling sensor) or as a query parameter — an embedded frame cannot set a
/// header, which is precisely why these credentials can only ever read.
/// </summary>
[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Dashboard)]
public class DashboardController(ICurrentUser currentUser, IDashboardService dashboard) : ControllerBase
{
    [HttpGet("stats")]
    [EndpointSummary("Lifetime numbers, badges, and progress toward the next one.")]
    [EndpointDescription(
        "The rider's own synced figures with two corrections this backend is better placed to "
        + "make: the ride count comes from the trips actually held, and the deepest lean is "
        + "whichever of the recorded and reported figures is deeper — reported as null, not "
        + "zero, when nothing has ever measured one.")]
    [ProducesResponseType<DashboardStatsResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<DashboardStatsResponse>> GetStats(CancellationToken cancellationToken)
    {
        var owner = await currentUser.GetAsync(cancellationToken);
        return Ok(await dashboard.GetStatsAsync(owner, cancellationToken));
    }

    [HttpGet("rides")]
    [EndpointSummary("Rides, newest first.")]
    [ProducesResponseType<RidesResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<RidesResponse>> GetRides(
        [FromQuery] int limit,
        CancellationToken cancellationToken)
    {
        var owner = await currentUser.GetAsync(cancellationToken);
        return Ok(await dashboard.GetRidesAsync(
            owner.Id, Clamp(limit, 25, 1, DetourLimits.MaxDashboardRides), cancellationToken));
    }

    [HttpGet("rides/track")]
    [EndpointSummary("One ride, thinned to fit a dashboard entity.")]
    [EndpointDescription(
        "No start instant means the newest ride, so a polling sensor needs no second request to "
        + "discover what 'latest' is. The speed and lean peaks are read off the raw track rather "
        + "than off what survived thinning — dropping a sample must not drop the corner it was "
        + "carrying.")]
    [ProducesResponseType<RideTrackResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<ActionResult<RideTrackResponse>> GetTrack(
        [FromQuery] long? start,
        [FromQuery] int tolerance,
        [FromQuery] int max,
        CancellationToken cancellationToken)
    {
        var owner = await currentUser.GetAsync(cancellationToken);

        var result = await dashboard.GetTrackAsync(
            owner.Id,
            start,
            Clamp(tolerance, 6, 0, 200),
            Clamp(max, 400, 2, 5_000),
            cancellationToken);

        if (result.HasError(ValidationKeys.Trip.NotFound))
            return NotFound();

        result.ThrowIfFailure();
        return Ok(result.Value);
    }

    [HttpGet("traces")]
    [EndpointSummary("The owner's own fog-of-war lines, positions only.")]
    [EndpointDescription(
        "For a heatmap that fetches once and thins in the browser. Never another rider's lines: "
        + "shared fog is a separate capability and this is not it.")]
    [ProducesResponseType<TracesResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<TracesResponse>> GetTraces(
        [FromQuery] int every,
        CancellationToken cancellationToken)
    {
        var owner = await currentUser.GetAsync(cancellationToken);
        return Ok(await dashboard.GetTracesAsync(
            owner.Id, Clamp(every, 1, 1, DetourLimits.MaxTraceThinning), cancellationToken));
    }

    [HttpGet("coverage")]
    [EndpointSummary("Every trace, aggressively thinned into one geometry.")]
    [EndpointDescription(
        "The entity-attribute counterpart to the traces endpoint, which is far too large for "
        + "something a dashboard has to hold in state. Simplification runs per line so one long "
        + "trace cannot eat the whole budget; the point budget is then a total across all lines.")]
    [ProducesResponseType<CoverageResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<CoverageResponse>> GetCoverage(
        [FromQuery] int tolerance,
        [FromQuery] int max,
        CancellationToken cancellationToken)
    {
        var owner = await currentUser.GetAsync(cancellationToken);
        return Ok(await dashboard.GetCoverageAsync(
            owner.Id,
            Clamp(tolerance, 25, 0, 500),
            Clamp(max, 6_000, 2, 40_000),
            cancellationToken));
    }

    /// <summary>
    /// Absent or out of range becomes the default rather than an error. A dashboard URL is
    /// typed by hand into a config file; refusing it outright helps nobody.
    /// </summary>
    private static int Clamp(int value, int fallback, int min, int max) =>
        value <= 0 ? fallback : Math.Clamp(value, min, max);
}
