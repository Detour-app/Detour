using Microsoft.AspNetCore.Http;

namespace Shared.Extensions;

public static class PathStringExtensions
{
    public static bool IsOpenApiRequest(this PathString path) =>
        path.StartsWithSegments("/openapi", StringComparison.OrdinalIgnoreCase);
}