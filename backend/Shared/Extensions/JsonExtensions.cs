using System.Text.Json;

namespace Shared.Extensions;

public static class JsonExtensions
{
    public static T? TryDeserialize<T>(this string? json) where T : class
    {
        if (string.IsNullOrEmpty(json))
            return null;

        try
        {
            return JsonSerializer.Deserialize<T>(json);
        }
        catch (JsonException)
        {
            return null;
        }
    }
}