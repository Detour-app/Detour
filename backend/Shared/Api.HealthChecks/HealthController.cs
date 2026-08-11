using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Shared.Api.HealthChecks;

[ApiController]
[Route("api/[controller]")]
[Produces("application/json")]
[AllowAnonymous]
public class HealthController(HealthCheckService healthCheckService) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("Check API health status.")]
    [EndpointDescription("Returns the current health status of the API with per-dependency breakdown.")]
    [ProducesResponseType<HealthCheckReport>(200, Description = "API is healthy or degraded.")]
    [ProducesResponseType<HealthCheckReport>(503, Description = "API is unhealthy — a critical dependency is down.")]
    public async Task<IActionResult> Get(CancellationToken ct)
    {
        var result = await healthCheckService.CheckHealthAsync(ct);
        var report = HealthCheckReport.From(result);
        return result.Status == HealthStatus.Unhealthy
            ? StatusCode(503, report)
            : Ok(report);
    }
}
