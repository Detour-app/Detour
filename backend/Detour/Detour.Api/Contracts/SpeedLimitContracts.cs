using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

public record SpeedLimitWayDto(
    [Required] Guid Id,
    [Required] int MaxSpeedKmh,
    [Required] List<List<double>> Polyline);

public record SpeedLimitsBboxResponse([Required] IReadOnlyList<SpeedLimitWayDto> Ways);
