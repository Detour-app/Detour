using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.ProblemReports;

/// <summary>
/// A crash or a rider's "Something's wrong" note (#811), with the app's recent event log.
///
/// Private to this server. Only administrators read it (#820), and what reaches a public issue is
/// a redacted copy the loop host makes. A signed-in rider's report names their account, so it
/// goes when the account does; an anonymous one names nobody.
///
/// Kept for a bounded time and a bounded count, both enforced when a report arrives and when an
/// administrator lists them (<see cref="IProblemReportRepository.TrimAsync"/>), never by a sweep.
/// </summary>
public sealed class ProblemReport : Entity
{
    public const string CrashKind = "crash";
    public const string ManualKind = "manual";

    public const string AndroidPlatform = "android";
    public const string IosPlatform = "ios";

    private static readonly string[] Kinds = [CrashKind, ManualKind];
    private static readonly string[] Platforms = [AndroidPlatform, IosPlatform];
    private static readonly long MaxUnixMilliseconds = DateTimeOffset.MaxValue.ToUnixTimeMilliseconds();

    public DateTimeOffset ReceivedAt { get; private set; }

    /// <summary>When the crash or the note happened on the device, which for a crash is the
    /// start before the one that sent it.</summary>
    public DateTimeOffset OccurredAt { get; private set; }

    public string Kind { get; private set; }

    public string Platform { get; private set; }

    public string AppVersion { get; private set; }

    public string OsVersion { get; private set; }

    public string DeviceModel { get; private set; }

    public string? Note { get; private set; }

    public string? StackTrace { get; private set; }

    /// <summary>The event log as a JSON array of strings, stored as sent.</summary>
    public string Events { get; private set; }

    /// <summary>The account that sent it, or null for a rider who was not signed in.</summary>
    public Guid? UserId { get; private set; }

    /// <summary>When the loop host filed its redacted issue (#820), or null while it hasn't.</summary>
    public DateTimeOffset? FiledAt { get; private set; }

    /// <summary>The public issue it was filed as, as the loop host's URL for it.</summary>
    public string? FiledIssue { get; private set; }

    private ProblemReport(Guid? userId, ProblemReportDetails details)
    {
        ReceivedAt = DateTimeOffset.UtcNow;
        OccurredAt = DateTimeOffset.FromUnixTimeMilliseconds(details.OccurredAtMs);
        Kind = details.Kind!;
        Platform = details.Platform!;
        AppVersion = details.AppVersion!.Trim();
        OsVersion = details.OsVersion!.Trim();
        DeviceModel = details.DeviceModel!.Trim();
        Note = NormalizeOptional(details.Note);
        StackTrace = NormalizeOptional(details.StackTrace);
        Events = JsonSerializer.Serialize(details.Events ?? []);
        UserId = userId;
    }

    public static Result<ProblemReport> Create(Guid? userId, ProblemReportDetails details)
    {
        var validation = Validate(details);
        if (validation.IsFailure)
            return validation;

        return new ProblemReport(userId, details);
    }

    /// <summary>
    /// Records the public issue the loop host filed this report as (#820). Filing it again is a
    /// no-op that keeps the first issue: a retried call must not move the report to a duplicate.
    /// </summary>
    public Result MarkFiled(string? issueUrl)
    {
        var issue = issueUrl?.Trim();
        if (string.IsNullOrEmpty(issue)
            || issue.Length > DetourLimits.ProblemReportFiledIssueMaxLength
            || !Uri.TryCreate(issue, UriKind.Absolute, out var uri)
            || uri.Scheme != Uri.UriSchemeHttps)
            return Result.Error(ValidationKeys.ProblemReport.FiledIssueInvalid);

        if (FiledAt is not null)
            return Result.Ok();

        FiledAt = DateTimeOffset.UtcNow;
        FiledIssue = issue;
        return Result.Ok();
    }

    /// <summary>
    /// Who sent it, as an administrator may see it: an HMAC-SHA256 of the account id under a
    /// server-held key, hex-encoded, or null for an anonymous report. The same account always
    /// gives the same value and two accounts never share one, so the loop host can count distinct
    /// reporters; without the key, it cannot be turned back into the account.
    /// </summary>
    public static string? Reporter(Guid? userId, byte[] key)
    {
        if (userId is not { } id)
            return null;

        return Convert.ToHexStringLower(HMACSHA256.HashData(key, Encoding.UTF8.GetBytes(id.ToString("D"))));
    }

    private static Result Validate(ProblemReportDetails details)
    {
        if (details.Kind is null || !Kinds.Contains(details.Kind))
            return Result.Error(ValidationKeys.ProblemReport.KindInvalid);

        if (details.Platform is null || !Platforms.Contains(details.Platform))
            return Result.Error(ValidationKeys.ProblemReport.PlatformInvalid);

        string?[] device = [details.AppVersion, details.OsVersion, details.DeviceModel];
        if (device.Any(string.IsNullOrWhiteSpace))
            return Result.Error(ValidationKeys.ProblemReport.DeviceRequired);

        if (device.Any(d => d!.Trim().Length > DetourLimits.ProblemReportDeviceFieldMaxLength))
            return Result.Error(
                ValidationKeys.ProblemReport.DeviceTooLong, DetourLimits.ProblemReportDeviceFieldMaxLength);

        if (details.OccurredAtMs <= 0 || details.OccurredAtMs > MaxUnixMilliseconds)
            return Result.Error(ValidationKeys.ProblemReport.OccurredAtInvalid);

        if (details.Note?.Trim().Length > DetourLimits.ProblemReportNoteMaxLength)
            return Result.Error(ValidationKeys.ProblemReport.NoteTooLong, DetourLimits.ProblemReportNoteMaxLength);

        if (Utf8Bytes(details.StackTrace) > DetourLimits.ProblemReportStackTraceMaxBytes)
            return Result.Error(
                ValidationKeys.ProblemReport.StackTraceTooLarge, DetourLimits.ProblemReportStackTraceMaxBytes);

        return (details.Events ?? []).Sum(Utf8Bytes) > DetourLimits.ProblemReportEventsMaxBytes
            ? Result.Error(ValidationKeys.ProblemReport.EventsTooLarge, DetourLimits.ProblemReportEventsMaxBytes)
            : Result.Ok();
    }

    private static long Utf8Bytes(string? value) => value is null ? 0 : Encoding.UTF8.GetByteCount(value);

    private static string? NormalizeOptional(string? value)
    {
        var trimmed = value?.Trim();
        return string.IsNullOrEmpty(trimmed) ? null : trimmed;
    }

    // EF materialisation.
    private ProblemReport()
    {
        Kind = string.Empty;
        Platform = string.Empty;
        AppVersion = string.Empty;
        OsVersion = string.Empty;
        DeviceModel = string.Empty;
        Events = "[]";
    }
}

/// <summary>Everything the app sends, as it arrived; <see cref="ProblemReport.Create"/> checks it.</summary>
public sealed record ProblemReportDetails(
    string? Kind,
    string? Platform,
    string? AppVersion,
    string? OsVersion,
    string? DeviceModel,
    long OccurredAtMs,
    string? Note,
    string? StackTrace,
    IReadOnlyList<string>? Events);

public interface IProblemReportRepository : IBaseRepository<ProblemReport>
{
    /// <summary>
    /// Up to <paramref name="limit"/> reports, oldest first by (received, id), starting after
    /// <paramref name="after"/> when given. <paramref name="filed"/> null means filed or not.
    /// </summary>
    Task<List<ProblemReport>> GetPageNonTrackingAsync(
        bool? filed, ProblemReport? after, int limit, CancellationToken cancellationToken);

    /// <summary>
    /// Deletes every report received before <paramref name="receivedBefore"/>, then every report
    /// past the newest <paramref name="keep"/>. Runs on each insert, after it, and before each
    /// administrator list (#820).
    /// </summary>
    Task TrimAsync(DateTimeOffset receivedBefore, int keep, CancellationToken cancellationToken);
}
