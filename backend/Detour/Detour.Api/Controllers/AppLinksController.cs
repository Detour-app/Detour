using System.Net;
using Detour.Api.Configuration;
using Detour.Api.Contracts;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Options;

namespace Detour.Api.Controllers;

/// <summary>
/// The web half of a convoy invite link (#764, CIRCLES_AND_CONVOYS.md §4.1): the Digital Asset
/// Links file that lets Android hand <c>/convoy-invite</c> straight to the app, and the page a
/// link opens on where the app isn't installed.
///
/// Outside <c>/api</c> because both paths are fixed by someone else: Android fetches the asset
/// file from the host's root, and the invite link is the address the app shares. A proxy in front
/// routes both exact paths here (docker/prod/nginx). Not part of the OpenAPI document: neither is
/// an API a client calls, and the Bruno collection would only carry two requests nobody sends.
///
/// Public, and covered by the global per-IP limiter only. Neither answer depends on the caller,
/// and the invite token never reaches this page: it rides in the link's fragment, which a browser
/// keeps to itself.
/// </summary>
[ApiExplorerSettings(IgnoreApi = true)]
[AllowAnonymous]
public class AppLinksController(IOptions<AppLinkSettings> settings) : ControllerBase
{
    private const string HandleAllUrls = "delegate_permission/common.handle_all_urls";

    /// <summary>
    /// The configured apps, or <c>[]</c>. Always a 200 with JSON: Android treats a 404 or a
    /// redirect here as "no app may claim this host", which is the right answer for an empty list
    /// too, but a deployment that hasn't set fingerprints yet should see a well-formed file.
    /// </summary>
    [HttpGet("/.well-known/assetlinks.json")]
    public ActionResult<IReadOnlyList<AssetLinkStatement>> AssetLinks() =>
        settings.Value.Android
            .Select(app => (Package: app.Package.Trim(), Fingerprints: Fingerprints(app.Sha256)))
            .Where(app => app.Package.Length > 0 && app.Fingerprints.Count > 0)
            .Select(app => new AssetLinkStatement(
                [HandleAllUrls],
                new AssetLinkTarget("android_app", app.Package, app.Fingerprints)))
            .ToList();

    /// <summary>
    /// Where an invite link lands in a browser: on a computer, or a phone without the app (or
    /// with an app this deployment's fingerprints don't cover). Says what the link is and how to
    /// get the app; joining itself only happens in the app.
    /// </summary>
    [HttpGet("/convoy-invite")]
    public ContentResult InvitePage()
    {
        var install = WebUtility.HtmlEncode(settings.Value.InstallUrl);
        return Content(
            $$"""
            <!doctype html>
            <html lang="en">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <meta name="robots" content="noindex">
            <title>Detour convoy invite</title>
            <style>
            body { font-family: system-ui, sans-serif; max-width: 32rem; margin: 3rem auto; padding: 0 1rem; line-height: 1.5; }
            a.button { display: inline-block; padding: .6rem 1.2rem; border-radius: .4rem; background: #e8a33d; color: #1d1f21; text-decoration: none; font-weight: 600; }
            </style>
            </head>
            <body>
            <h1>You're invited to ride in a convoy</h1>
            <p>Open this link on your phone with Detour installed to see the convoy and join it.
            Don't have the app yet? Install it, sign in, then open the link again.</p>
            <p lang="nl">Open deze link op je telefoon met Detour om het konvooi te zien en mee te rijden.
            Nog geen app? Installeer ze, meld je aan en open de link opnieuw.</p>
            <p><a class="button" href="{{install}}">Get Detour</a></p>
            </body>
            </html>
            """,
            "text/html; charset=utf-8");
    }

    private static List<string> Fingerprints(string configured) =>
    [
        .. configured
            .Split([',', ' ', '\n'], StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries)
            .Select(f => f.ToUpperInvariant()),
    ];
}
