using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary><see cref="Id"/> is the bare OSM relation id (<c>MunicipalityBoundary.OsmId</c>),
/// not the row's own database key — it is what the client persists as `Municipality.id`
/// (`shared/`), and it must match the id an Overpass-sourced lookup already cached for the same
/// relation.</summary>
public record MunicipalityBoundaryDto(
    [Required] long Id,
    [Required] string Name,
    [Required] List<List<List<double>>> Rings);

public record MunicipalityResponse(MunicipalityBoundaryDto? Municipality);
