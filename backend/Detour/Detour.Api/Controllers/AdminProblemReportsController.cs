using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Detour.Api.Authorization;
using Detour.Api.Configuration;
using Detour.Api.Contracts;
using Detour.Domain;
using Detour.Domain.ProblemReports;
using Detour.Domain.ServerSecrets;
using JV.ResultUtilities;
using JV.ResultUtilities.Extensions;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace Detour.Api.Controllers;

/// <summary>
/// The loop host's side of problem reports (#811): list the ones it has not filed as a public
/// issue yet, and mark one filed.
///
/// The first administrator endpoint that answers with something a rider wrote — a note, and an
/// event log that never holds coordinates but does hold trip begin/end times and speeds. The owner
/// decided that in #811. Trips, traces, routes and places stay unreadable, as on
/// <see cref="AdminController"/>, and nothing here names the account: <c>reporter</c> is an HMAC
/// of it, enough to count distinct riders and nothing more.
///
/// Never calls <c>ICurrentUser</c>. The loop host signs in as a Keycloak service account, which
/// has no rider row and would fail the handle rule if one were provisioned for it (BACKEND_SPEC
/// §4.2); the role on the token is all this needs.
/// </summary>
[ApiController]
[Route("api/admin/problem-reports")]
[Produces("application/json")]
[Authorize(Policy = DetourPolicies.Administrator)]
public class AdminProblemReportsController(
    IProblemReportRepository reports,
    IServerSecretRepository secrets,
    IOptions<ProblemReportSettings> settings) : ControllerBase
{
    [HttpGet]
    [EndpointSummary("Problem reports, oldest first.")]
    [EndpointDescription(
        "filed=false for the ones not filed yet; leave it out for all. limit defaults to 50, at "
        + "most 200. after is the last id of the previous page; an id that is gone (filed and "
        + "pruned, or past retention) answers 400, and the caller lists again from the start. "
        + "Prunes past retention before it reads, like an insert. No user id or username: reporter "
        + "is an HMAC of the account, null for an anonymous report.")]
    [ProducesResponseType<AdminProblemReportsResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    public async Task<ActionResult<AdminProblemReportsResponse>> List(
        [FromQuery] bool? filed,
        [FromQuery] int limit,
        [FromQuery] Guid? after,
        CancellationToken cancellationToken)
    {
        var limits = settings.Value;
        await reports.TrimAsync(
            DateTimeOffset.UtcNow.AddDays(-limits.RetentionDays), limits.MaxRows, cancellationToken);

        ProblemReport? cursor = null;
        if (after is { } afterId)
        {
            cursor = await reports.GetNonTrackingAsync(afterId, cancellationToken);
            if (cursor is null)
                Result.Error(ValidationKeys.ProblemReport.CursorUnknown).ThrowIfFailure();
        }

        var pageSize = limit <= 0
            ? DetourLimits.ProblemReportPageDefault
            : Math.Min(limit, DetourLimits.ProblemReportPageMax);
        var page = await reports.GetPageNonTrackingAsync(filed, cursor, pageSize, cancellationToken);

        var key = await ReporterKeyAsync(cancellationToken);
        return Ok(new AdminProblemReportsResponse([.. page.Select(r => ToResponse(r, key))]));
    }

    [HttpPost("{id:guid}/filed")]
    [EndpointSummary("Mark a problem report filed as a public issue.")]
    [EndpointDescription(
        "issue is the https URL of the issue. Marking it again is a no-op that keeps the first "
        + "issue, so a retried call is safe.")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status403Forbidden)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> MarkFiled(
        Guid id,
        [FromBody] MarkProblemReportFiledBody body,
        CancellationToken cancellationToken)
    {
        var report = await reports.GetAsync(id, cancellationToken);
        if (report is null)
            return NotFound();

        report.MarkFiled(body.Issue).ThrowIfFailure();
        return NoContent();
    }

    /// <summary>
    /// The configured secret when there is one, else the one this server made and stored the
    /// first time it was asked.
    /// </summary>
    private async Task<byte[]> ReporterKeyAsync(CancellationToken cancellationToken)
    {
        var configured = settings.Value.ReporterKeySecret;
        if (!string.IsNullOrWhiteSpace(configured))
            return Encoding.UTF8.GetBytes(configured);

        return await secrets.GetOrCreateAsync(
            ServerSecret.ProblemReportReporterKey, RandomNumberGenerator.GetBytes(32), cancellationToken);
    }

    private static AdminProblemReportResponse ToResponse(ProblemReport report, byte[] key) => new(
        report.Id,
        report.ReceivedAt.ToUnixTimeMilliseconds(),
        report.Kind,
        report.Platform,
        report.AppVersion,
        report.OsVersion,
        report.DeviceModel,
        report.OccurredAt.ToUnixTimeMilliseconds(),
        report.Note,
        report.StackTrace,
        JsonSerializer.Deserialize<string[]>(report.Events) ?? [],
        ProblemReport.Reporter(report.UserId, key),
        report.FiledAt?.ToUnixTimeMilliseconds(),
        report.FiledIssue);
}
