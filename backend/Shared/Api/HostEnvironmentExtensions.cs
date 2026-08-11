using Microsoft.Extensions.Hosting;

namespace Shared.Api;

public static class HostEnvironmentExtensions
{
    public static bool IsLocalDevelopment(this IHostEnvironment environment) =>
        environment.IsDevelopment() || environment.IsEnvironment("Devcontainer");
}
