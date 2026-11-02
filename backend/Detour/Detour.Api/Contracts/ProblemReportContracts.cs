using System.ComponentModel.DataAnnotations;
using Detour.Domain.ProblemReports;

namespace Detour.Api.Contracts;

/// <summary>
/// A crash or a "Something's wrong" note (#811). <c>kind</c> is <c>crash</c> or <c>manual</c>,
/// <c>platform</c> is <c>android</c> or <c>ios</c>, <c>occurredAt</c> is epoch milliseconds on
/// the device. <c>events</c> is the device event log (#819), one line per entry, never
/// coordinates.
/// </summary>
public record ProblemReportBody(
    [Required] string Kind,
    [Required] string Platform,
    [Required] string AppVersion,
    [Required] string OsVersion,
    [Required] string DeviceModel,
    [Required] long OccurredAt,
    string? Note,
    string? StackTrace,
    IReadOnlyList<string>? Events)
{
    public ProblemReportDetails ToDetails() =>
        new(Kind, Platform, AppVersion, OsVersion, DeviceModel, OccurredAt, Note, StackTrace, Events);
}

/// <summary>The stored report's id, for the rider to quote if they follow up.</summary>
public record ProblemReportCreatedResponse([Required] Guid Id);

/// <summary>
/// One stored report as an administrator reads it (#820). Times are epoch milliseconds.
/// <c>reporter</c> stands in for the account (<see cref="ProblemReport.Reporter"/>) and is null for
/// an anonymous report; nothing here names the account.
/// </summary>
public record AdminProblemReportResponse(
    [Required] Guid Id,
    [Required] long ReceivedAt,
    [Required] string Kind,
    [Required] string Platform,
    [Required] string AppVersion,
    [Required] string OsVersion,
    [Required] string DeviceModel,
    [Required] long OccurredAt,
    string? Note,
    string? StackTrace,
    [Required] IReadOnlyList<string> Events,
    string? Reporter,
    long? FiledAt,
    string? FiledIssue);

/// <summary>A page of reports, oldest first. Pass the last one's id as <c>after</c> for the next.</summary>
public record AdminProblemReportsResponse([Required] IReadOnlyList<AdminProblemReportResponse> Reports);

/// <summary>The public issue a report was filed as.</summary>
public record MarkProblemReportFiledBody([Required] string Issue);
