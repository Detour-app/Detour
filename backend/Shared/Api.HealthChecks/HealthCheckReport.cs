using System.ComponentModel.DataAnnotations;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Shared.Api.HealthChecks;

public record HealthCheckReport(
    [Required] string Status,
    [Required] DateTime Timestamp,
    [Required] string Duration,
    [Required] Dictionary<string, HealthCheckEntry> Checks,
    [property: JsonIgnore(Condition = JsonIgnoreCondition.WhenWritingNull)]
    Dictionary<string, HealthCheckReport>? Children = null)
{
    public static HealthCheckReport From(HealthReport report)
    {
        var checks = new Dictionary<string, HealthCheckEntry>();
        Dictionary<string, HealthCheckReport>? children = null;

        foreach (var (key, entry) in report.Entries)
        {
            checks[key] = new HealthCheckEntry(
                entry.Status.ToString(),
                entry.Duration.ToString(),
                entry.Exception?.Message ?? entry.Description);

            if (entry.Data.TryGetValue(ChildServiceHealthCheck.ChildReportKey, out var obj)
                && obj is HealthCheckReport childReport)
            {
                children ??= [];
                children[key] = childReport;
            }
        }

        return new HealthCheckReport(
            report.Status.ToString(),
            DateTime.UtcNow,
            report.TotalDuration.ToString(),
            checks,
            children);
    }
}

public record HealthCheckEntry(
    [Required] string Status,
    [Required] string Duration,
    string? Error);
