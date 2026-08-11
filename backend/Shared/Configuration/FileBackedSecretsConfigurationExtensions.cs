using Microsoft.Extensions.Configuration;

namespace Shared.Configuration;

public static class FileBackedSecretsConfigurationExtensions
{
    public static IConfigurationBuilder AddFileBackedSecrets(this IConfigurationBuilder builder)
    {
        builder.Add(new FileBackedSecretsConfigurationSource());
        return builder;
    }
}
