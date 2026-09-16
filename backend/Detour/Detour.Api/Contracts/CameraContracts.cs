using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

public record CameraDto(
    [Required] Guid Id,
    [Required] string Kind,
    double? Lat,
    double? Lon,
    List<List<double>>? Polyline,
    int? MaxSpeedKmh,
    string? RoadRef);

public record CamerasBboxResponse([Required] IReadOnlyList<CameraDto> Cameras);
