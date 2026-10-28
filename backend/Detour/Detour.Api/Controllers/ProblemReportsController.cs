using Detour.Api.Authentication;
using Detour.Api.Configuration;
using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.ProblemReports;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.RateLimiting;
using Microsoft.Extensions.Options;
using Shared.Api.RateLimiting;

namespace Detour.Api.Controllers;

/// <summary>
/// Problem reports (#811): a crash the app caught, or a rider's "Something's wrong" note, with
/// the app's recent event log. Stored privately on this server; nothing here is readable back
/// through this controller, and nothing leaves the server from it.
///
/// Open to anyone, because the rider who most needs to report a problem may be the one who
/// cannot sign in. A signed-in report is linked to the account and goes when it does.
///
/// Every caller, signed in or not, is held to the anonymous policy: one endpoint gets one
/// policy, and a report is rare enough that a rider never meets the bucket.
/// </summary>
[ApiController]
[Route("api/problem-reports")]
[Produces("application/json")]
[AllowAnonymous]
[EnableRateLimiting(RateLimitPolicies.Anonymous)]
public class ProblemReportsController(
    IProblemReportRepository reports,
    ICurrentUser currentUser,
    IOptions<ProblemReportSettings> settings) : ControllerBase
{
    [HttpPost]
    [RequestSizeLimit(DetourLimits.ProblemReportMaxBodyBytes)]
    [EndpointSummary("Send a problem report.")]
    [EndpointDescription(
        "With or without a bearer. A valid bearer links the report to the account; an "
        + "Authorization header that does not validate is a 401, so the app refreshes and retries "
        + "instead of filing the report as anonymous. Field caps answer 400, a body over 512 KB "
        + "answers 413.")]
    [ProducesResponseType<ProblemReportCreatedResponse>(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status401Unauthorized)]
    [ProducesResponseType(StatusCodes.Status413PayloadTooLarge)]
    public async Task<ActionResult<ProblemReportCreatedResponse>> Create(
        [FromBody] ProblemReportBody body,
        CancellationToken cancellationToken)
    {
        // [AllowAnonymous] lets a token that fails validation through as anonymous, which would
        // file a signed-in rider's report under nobody and keep it past their account deletion.
        var signedIn = User.Identity?.IsAuthenticated == true;
        if (Request.Headers.Authorization.Count > 0 && !signedIn)
            return Unauthorized();

        Guid? userId = signedIn ? (await currentUser.GetAsync(cancellationToken)).Id : null;

        var result = ProblemReport.Create(userId, body.ToDetails());
        result.ThrowIfFailure();
        var report = result.Value;

        await reports.SaveAsync(report, cancellationToken);
        await reports.FlushChangesAsync(cancellationToken);

        var limits = settings.Value;
        await reports.TrimAsync(
            report.ReceivedAt.AddDays(-limits.RetentionDays), limits.MaxRows, cancellationToken);

        return StatusCode(StatusCodes.Status201Created, new ProblemReportCreatedResponse(report.Id));
    }
}
