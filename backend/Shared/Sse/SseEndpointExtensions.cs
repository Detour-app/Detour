using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;

namespace Shared.Sse;

public static class SseEndpointExtensions
{
    // Tells reverse proxies (nginx in particular) not to buffer the response body. Without it a proxy only
    // flushes once its buffer fills, which pins SSE events and defeats the point of the stream.
    private const string AccelBufferingHeader = "X-Accel-Buffering";

    /// <summary>
    /// Applies the conventions every Server-Sent Events endpoint needs: opt out of HTTP request-duration
    /// metrics (long-lived streams would otherwise skew the histogram) and disable reverse-proxy buffering.
    /// </summary>
    public static TBuilder WithServerSentEvents<TBuilder>(this TBuilder builder)
        where TBuilder : IEndpointConventionBuilder
    {
        builder.DisableHttpMetrics();
        builder.AddEndpointFilter(async (context, next) =>
        {
            context.HttpContext.Response.Headers[AccelBufferingHeader] = "no";
            return await next(context);
        });
        return builder;
    }
}
