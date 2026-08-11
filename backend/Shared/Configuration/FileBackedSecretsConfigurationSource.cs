using Microsoft.Extensions.Configuration;

namespace Shared.Configuration;

public sealed class FileBackedSecretsConfigurationSource : IConfigurationSource
{
    public IConfigurationProvider Build(IConfigurationBuilder builder) =>
        new FileBackedSecretsConfigurationProvider([.. builder.Sources
            .Where(s => s is not FileBackedSecretsConfigurationSource)
            .Select(s => s.Build(builder))]);
}

internal sealed class FileBackedSecretsConfigurationProvider(IReadOnlyList<IConfigurationProvider> upstream)
    : ConfigurationProvider
{
    private const string Suffix = "_FILE";

    public override void Load()
    {
        foreach (var provider in upstream)
        {
            provider.Load();
        }

        foreach (var (fileKey, baseKey) in DiscoverFileKeys())
        {
            var path = GetUpstream(fileKey)?.Trim();
            if (string.IsNullOrEmpty(path))
            {
                continue;
            }

            if (!File.Exists(path))
            {
                throw new FileNotFoundException($"Secret file for '{fileKey}' not found: {path}");
            }

            Data[baseKey] = File.ReadAllText(path).Trim();
        }
    }

    private IEnumerable<(string FileKey, string BaseKey)> DiscoverFileKeys()
    {
        var keys = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
        foreach (var provider in upstream)
        {
            foreach (var key in provider.GetChildKeys([], null))
            {
                CollectLeafKeys(provider, key, keys);
            }
        }

        return keys
            .Where(k => k.EndsWith(Suffix, StringComparison.Ordinal))
            .Select(k => (k, k[..^Suffix.Length]));
    }

    private static void CollectLeafKeys(IConfigurationProvider provider, string parent, HashSet<string> keys)
    {
        keys.Add(parent);
        foreach (var child in provider.GetChildKeys([], parent))
        {
            CollectLeafKeys(provider, $"{parent}:{child}", keys);
        }
    }

    private string? GetUpstream(string key)
    {
        foreach (var provider in upstream)
        {
            if (provider.TryGet(key, out var value))
            {
                return value;
            }
        }

        return null;
    }
}
