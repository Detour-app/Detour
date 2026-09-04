using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

public record RegisterDeviceBody([Required] string Token, [Required] string Platform);

public record UnregisterDeviceBody([Required] string Token);
