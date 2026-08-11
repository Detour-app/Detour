namespace Shared.Api.Mvc;

/// <summary>
/// <c>type</c> URIs for the ProblemDetails responses this platform writes by hand.
/// <para>
/// A problem type is an identifier a client matches on, not a document to fetch (RFC 9457 §3.1), so
/// it has to be a URI that stays valid for every deployment. It must therefore never be built from
/// a hostname a particular deployment happens to answer on — the placeholder
/// <c>api.example.com</c> that these replaced was worse still, being a domain nobody serves.
/// </para>
/// <para>
/// These point at RFC 9110's own section for the status code, which is what ASP.NET Core's built-in
/// ProblemDetails writer uses when a problem carries no more specific type. The consequence is that
/// two different conditions sharing a status code also share a type URI, so <c>Title</c> is what
/// names the specific condition. Nothing in the platform branches on <c>type</c> today; if a client
/// ever needs to tell two same-status problems apart, give that condition its own stable URI here
/// (a non-resolving <c>urn:</c> is fine) rather than reintroducing a per-deployment hostname.
/// </para>
/// <para>
/// These cover the responses written by hand. The automatic <c>Result</c>-to-ProblemDetails path has
/// its own status-code table in <c>ResultExceptionHandler.StatusCodeTypeUris</c>
/// (<c>Shared.Api.ResultTypeUtils</c>), which is deliberately not merged with this: that project and
/// this one have no reference between them today, and coupling them to share a handful of string
/// constants costs more than the duplication does. Change both if an entry here ever changes.
/// </para>
/// </summary>
public static class ProblemTypes
{
    /// <summary>RFC 9110 §15.5.4 — 403 Forbidden.</summary>
    public const string Forbidden = "https://tools.ietf.org/html/rfc9110#section-15.5.4";

    /// <summary>RFC 9110 §15.5.10 — 409 Conflict.</summary>
    public const string Conflict = "https://tools.ietf.org/html/rfc9110#section-15.5.10";
}
