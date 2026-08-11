using System.Collections;
using System.Reflection;
using Ardalis.SmartEnum;

namespace Shared.Api.Json;

/// <summary>Reads SmartEnum member names by reflection, for OpenAPI schema generation.</summary>
public static class SmartEnumReflection
{
    public static IReadOnlyList<string> GetNames(Type smartEnumType)
    {
        if (!typeof(ISmartEnum).IsAssignableFrom(smartEnumType))
            return [];

        var baseType = FindSmartEnumBase(smartEnumType);
        if (baseType is null) return [];

        var listProperty = baseType.GetProperty("List",
            BindingFlags.Public | BindingFlags.Static | BindingFlags.FlattenHierarchy);
        if (listProperty?.GetValue(null) is not IEnumerable list) return [];

        var nameProperty = baseType.GetProperty("Name", BindingFlags.Public | BindingFlags.Instance);
        if (nameProperty is null) return [];

        var names = new List<string>();
        foreach (var item in list)
            if (nameProperty.GetValue(item)?.ToString() is { } name)
                names.Add(name);
        return names;
    }

    private static Type? FindSmartEnumBase(Type type)
    {
        for (var t = type; t is not null; t = t.BaseType)
            if (t.IsGenericType && t.GetGenericTypeDefinition() == typeof(SmartEnum<,>))
                return t;
        return null;
    }
}
