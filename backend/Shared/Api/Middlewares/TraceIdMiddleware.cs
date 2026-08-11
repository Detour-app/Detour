using System.Diagnostics;
using Microsoft.AspNetCore.Http;

namespace Shared.Api.Middlewares;

public class TraceIdMiddleware(RequestDelegate next)
{
    private static readonly ActivitySource ActivitySource = new("Api");

    public async Task InvokeAsync(HttpContext context)
    {
        var hasActivity = Activity.Current is not null;
        Activity? activity = Activity.Current;

        if (!hasActivity)
        {
            activity = ActivitySource.StartActivity($"HTTP {context.Request.Method}", ActivityKind.Server);
        }

        if (activity is not null)
        {
            var traceIdHex = activity.TraceId.ToString();

            // Expose the trace id for clients and align ASP.NET's TraceIdentifier with it for consistency
            context.Response.Headers["traceId"] = traceIdHex;
            context.TraceIdentifier = traceIdHex;
        }

        try
        {
            await next(context);
        }
        finally
        {
            // Only dispose the activity we created
            if (!hasActivity && activity is not null)
            {
                activity.Dispose();
            }
        }
    }
}