using System.Text.Json;
using System.Text.Json.Serialization;
using Ardalis.SmartEnum;

namespace Shared.Api.Json;

/// <summary>
/// Globally serializes every SmartEnum type by its Name (string), matching the existing
/// JsonStringEnumConverter contract so the OpenAPI/JSON wire format is unchanged.
/// Deserialization is tolerant: accepts PascalCase names, case-insensitive names,
/// and integer values for backwards compatibility with pre-SmartEnum clients.
/// </summary>
public sealed class SmartEnumJsonConverterFactory : JsonConverterFactory
{
    public override bool CanConvert(Type typeToConvert)
        => typeof(ISmartEnum).IsAssignableFrom(typeToConvert);

    public override JsonConverter CreateConverter(Type typeToConvert, JsonSerializerOptions options)
    {
        var smartEnumBase = FindSmartEnumBase(typeToConvert)
            ?? throw new InvalidOperationException(
                $"Type {typeToConvert} implements ISmartEnum but does not inherit SmartEnum<TEnum, TValue>.");

        var converterType = typeof(TolerantSmartEnumConverter<,>)
            .MakeGenericType(smartEnumBase.GetGenericArguments());
        return (JsonConverter)Activator.CreateInstance(converterType)!;
    }

    private static Type? FindSmartEnumBase(Type type)
    {
        for (var t = type; t is not null; t = t.BaseType)
            if (t.IsGenericType && t.GetGenericTypeDefinition() == typeof(SmartEnum<,>))
                return t;
        return null;
    }
}

// TODO: retire old number tolerant converter (if necessary, otherwise we can keep)
internal sealed class TolerantSmartEnumConverter<TEnum, TValue> : JsonConverter<TEnum>
    where TEnum : SmartEnum<TEnum, TValue>
    where TValue : IEquatable<TValue>, IComparable<TValue>
{
    public override TEnum Read(ref Utf8JsonReader reader, Type typeToConvert, JsonSerializerOptions options) =>
        reader.TokenType switch
        {
            JsonTokenType.String => SmartEnum<TEnum, TValue>.FromName(reader.GetString()!, ignoreCase: true),
            JsonTokenType.Number when typeof(TValue) == typeof(int)
                => SmartEnum<TEnum, TValue>.FromValue((TValue)(object)reader.GetInt32()),
            _ => throw new JsonException(
                $"Cannot deserialize {typeof(TEnum).Name}: expected a string name or integer value, got {reader.TokenType}.")
        };

    public override void Write(Utf8JsonWriter writer, TEnum value, JsonSerializerOptions options)
        => writer.WriteStringValue(value.Name);
}
