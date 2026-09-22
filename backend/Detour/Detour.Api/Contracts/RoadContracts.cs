using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary><see cref="Highway"/> is the raw OSM `highway` tag (`"primary"`,
/// `"motorway_link"`, …), not a coarser bucket — the client decides how to group or filter
/// it.</summary>
public record RoadWayDto(
    [Required] Guid Id,
    [Required] string Highway,
    [Required] List<List<double>> Polyline);

public record RoadsBboxResponse([Required] IReadOnlyList<RoadWayDto> Ways);
