using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary><see cref="Name"/> is blank when the OSM element carried no `name` tag — the client
/// falls back to a kind-specific label itself, the same rule it already applies to an Overpass
/// answer with no name.</summary>
public record PoiDto(
    [Required] Guid Id,
    [Required] string Kind,
    [Required] string Name,
    [Required] double Lat,
    [Required] double Lon);

public record PoisBboxResponse([Required] IReadOnlyList<PoiDto> Pois);
