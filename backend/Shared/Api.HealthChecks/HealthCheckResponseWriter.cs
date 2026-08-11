using Microsoft.AspNetCore.Http;
using Microsoft.Extensions.Diagnostics.HealthChecks;

namespace Shared.Api.HealthChecks;

public static class HealthCheckResponseWriter
{
    public static Task Write(HttpContext context, HealthReport report)
    {
        context.Response.ContentType = "application/json";
        var result = HealthCheckReport.From(report);
        return context.Response.WriteAsJsonAsync(result);
    }
}
