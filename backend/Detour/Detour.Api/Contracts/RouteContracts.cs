using System.ComponentModel.DataAnnotations;
using System.Text.Json;
using System.Text.Json.Serialization;

namespace Detour.Api.Contracts;

/// <summary>
/// One saved route as the web planner sends it (#912): the document the app's
/// <c>SavedRoute.toJson</c> writes, with the fields the server checks named and the rest kept
/// verbatim. Stored as sent, in the same row <c>/sync</c> merges, so the phone reads it back
/// unchanged.
/// </summary>
public record RouteDocument
{
    /// <summary>The route's client-assigned identifier; must match the one in the path.</summary>
    public long Id { get; init; }

    /// <summary>
    /// When the route was last edited, Unix milliseconds. Required and positive: the newest edit
    /// wins against the phone's copy, so an unstamped write from the planner could never be
    /// ordered against it.
    /// </summary>
    public long? EditedAtMs { get; init; }

    /// <summary>
    /// The travel mode's name, as the app writes it (<c>MOTO</c>, <c>CAR</c>, ...). Required; a name
    /// the app does not know is accepted, and read there as <c>CAR</c>.
    /// </summary>
    public string? Mode { get; init; }

    /// <summary>At least two — the app drops a route with fewer.</summary>
    public IReadOnlyList<JsonElement>? Stops { get; init; }

    /// <summary>Everything else — name, polyline, distance, ... — kept verbatim.</summary>
    [JsonExtensionData]
    public Dictionary<string, JsonElement>? Extra { get; init; }
}

/// <summary>The caller's saved routes, ascending by id, each exactly as stored.</summary>
public record RouteListResponse([Required] IReadOnlyList<JsonElement> Routes);

/// <summary>
/// A write refused because the stored copy carries a newer edit. <see cref="Stored"/> is that
/// copy, so the planner can show it instead of overwriting it.
/// </summary>
public record RouteConflictResponse([Required] JsonElement Stored);
