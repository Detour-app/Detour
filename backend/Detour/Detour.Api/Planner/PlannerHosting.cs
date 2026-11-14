using Detour.Api.Configuration;
using Microsoft.Extensions.FileProviders;
using Microsoft.Net.Http.Headers;

namespace Detour.Api.Planner;

/// <summary>
/// Serves the web planner (#913, built from <c>web/</c>) under <c>/planner</c>: its static files
/// from <see cref="PlannerSettings.Path"/>, and <c>index.html</c> for any other path below it so
/// a reload or a deep link lands in the app instead of on a 404.
///
/// Same origin as <c>/api</c>, which is why the planner needs no CORS entry: behind the
/// one-hostname proxy (docker/prod/nginx) both are the rider's one address.
/// </summary>
public static class PlannerHosting
{
    public const string BasePath = "/planner";

    /// <summary>
    /// OpenFreeMap, where the planner's map style, tiles, glyphs and sprites come from
    /// (<c>web/src/map.ts</c>). The only third-party origin the page may reach.
    /// </summary>
    public const string TileOrigin = "https://tiles.openfreemap.org";

    /// <summary>
    /// Mounts the planner when <paramref name="settings"/> points at a built copy. Called after
    /// the rate limiter, so the planner's files share the global per-IP budget like
    /// <c>/convoy-invite</c>, and before the transaction middleware, so a file never opens a
    /// database transaction.
    /// </summary>
    public static void UsePlanner(this WebApplication app, PlannerSettings settings, string issuer)
    {
        if (string.IsNullOrWhiteSpace(settings.Path))
            return;

        var root = System.IO.Path.GetFullPath(settings.Path);
        var index = System.IO.Path.Combine(root, "index.html");
        if (!File.Exists(index))
        {
            // Loud, because the image sets the path: a missing build there is a broken image.
            app.Logger.LogWarning("Planner:Path {Path} has no index.html; /planner is not served", root);
            return;
        }

        var files = new PhysicalFileProvider(root);
        var csp = ContentSecurityPolicy(issuer);

        app.Map(BasePath, planner =>
        {
            planner.Use(async (context, next) =>
            {
                // /planner without the slash: the page's relative asset URLs need it.
                if (!context.Request.Path.HasValue)
                {
                    context.Response.Redirect($"{context.Request.PathBase}/{context.Request.QueryString}");
                    return;
                }

                var headers = context.Response.Headers;
                headers.ContentSecurityPolicy = csp;
                headers.XContentTypeOptions = "nosniff";
                // The sign-in callback carries the authorization code in the query string.
                headers["Referrer-Policy"] = "no-referrer";
                await next();
            });

            planner.UseStaticFiles(new StaticFileOptions
            {
                FileProvider = files,
                OnPrepareResponse = file =>
                    // Vite puts a content hash in every name under assets/, so those never
                    // change; anything else (index.html, a favicon) is revalidated.
                    file.Context.Response.Headers.CacheControl =
                        file.Context.Request.Path.StartsWithSegments("/assets")
                            ? "public, max-age=31536000, immutable"
                            : "no-cache",
            });

            planner.Run(async context =>
            {
                // A missing hashed asset is a stale page asking for an old build; HTML in its
                // place would only fail as a script with a less useful error.
                var isPageRequest = HttpMethods.IsGet(context.Request.Method)
                    || HttpMethods.IsHead(context.Request.Method);
                if (!isPageRequest || context.Request.Path.StartsWithSegments("/assets"))
                {
                    context.Response.StatusCode = StatusCodes.Status404NotFound;
                    return;
                }

                context.Response.ContentType = "text/html; charset=utf-8";
                context.Response.Headers.CacheControl = "no-cache";
                if (HttpMethods.IsHead(context.Request.Method))
                    return;
                await context.Response.SendFileAsync(index, context.RequestAborted);
            });
        });
    }

    /// <summary>
    /// Scripts, styles and the map worker from this origin only; network calls to this origin
    /// (<c>/api</c>, <c>/route</c>), the realm that signs riders in, and the tile host. Inline
    /// script is refused, so an injected route name cannot run.
    /// </summary>
    public static string ContentSecurityPolicy(string issuer)
    {
        var connect = new List<string> { "'self'", TileOrigin };
        // The realm is on this origin under /auth with +caddy, and 'self' covers it; a realm on
        // its own host needs its origin for discovery and the token exchange.
        if (Uri.TryCreate(issuer, UriKind.Absolute, out var issuerUri))
            connect.Add(issuerUri.GetLeftPart(UriPartial.Authority));

        return string.Join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            $"img-src 'self' data: blob: {TileOrigin}",
            "font-src 'self'",
            $"connect-src {string.Join(' ', connect.Distinct())}",
            "worker-src 'self'",
            "object-src 'none'",
            "base-uri 'none'",
            "form-action 'self'",
            "frame-ancestors 'none'");
    }
}
