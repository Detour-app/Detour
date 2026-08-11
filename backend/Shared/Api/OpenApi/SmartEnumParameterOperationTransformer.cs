using System.Collections.Concurrent;
using System.Reflection;
using Ardalis.SmartEnum;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Controllers;
using Microsoft.AspNetCore.OpenApi;
using Microsoft.OpenApi;

namespace Shared.Api.OpenApi;

/// <summary>
/// Collapses SmartEnum query/route parameters that ASP.NET Core's OpenAPI generator
/// decomposes into <c>Name</c>/<c>Value</c> property pairs back into a single
/// string-enum parameter referencing the existing component schema.
///
/// This is the parameter-side analogue of the schema transformer in
/// <see cref="OpenApiInstaller"/> that already emits SmartEnum component schemas
/// as <c>type: string</c> with the member-name enum list.
///
/// SmartEnum types that appear only as query/route parameters (never in a response body)
/// won't have a component schema created by the schema transformer.
/// <see cref="PendingSchemas"/> collects these; the companion document transformer
/// registered in <see cref="OpenApiInstaller"/> ensures the schemas exist.
/// </summary>
public sealed class SmartEnumParameterOperationTransformer : IOpenApiOperationTransformer
{
    /// <summary>
    /// SmartEnum types whose component schemas must be created because they are referenced
    /// from parameters but never appear in a response body. Populated by the operation
    /// transformer, consumed by the companion document transformer in <see cref="OpenApiInstaller"/>.
    /// </summary>
    internal static ConcurrentDictionary<string, Type> PendingSchemas { get; } = new();

    public Task TransformAsync(
        OpenApiOperation operation,
        OpenApiOperationTransformerContext context,
        CancellationToken cancellationToken)
    {
        if (operation.Parameters is null)
            return Task.CompletedTask;

        if (context.Description.ActionDescriptor is not ControllerActionDescriptor cad)
            return Task.CompletedTask;

        var smartEnumParams = DiscoverSmartEnumParams(cad);

        if (smartEnumParams.Count == 0)
            return Task.CompletedTask;

        foreach (var sep in smartEnumParams)
        {
            // Compute the decomposed parameter names that ASP.NET may have emitted.
            // Pattern 1 (top-level SmartEnum param, e.g. [FromQuery] C2Type? type):
            //   bare "Name" and "Value" (the param's own name is dropped).
            // Pattern 2 (SmartEnum property of a DTO, e.g. GetAllTasksRequest.Status):
            //   "<paramName>.Name" and "<paramName>.Value".
            var namesToRemove = new HashSet<string>(StringComparer.OrdinalIgnoreCase)
            {
                $"{sep.ParamName}.Name",
                $"{sep.ParamName}.Value",
                sep.ParamName
            };

            if (sep.IsTopLevel)
            {
                // Top-level SmartEnum params decompose into bare "Name"/"Value".
                namesToRemove.Add("Name");
                namesToRemove.Add("Value");
            }

            // Capture description from the existing parameter before removing
            // (array params retain their original parameter with the [Description]).
            string? description = null;
            foreach (var p in operation.Parameters)
            {
                if (p.In == sep.Location
                    && string.Equals(p.Name, sep.ParamName, StringComparison.OrdinalIgnoreCase))
                {
                    description = p.Description;
                    break;
                }
            }

            // Remove decomposed parameters.
            for (var i = operation.Parameters.Count - 1; i >= 0; i--)
            {
                var p = operation.Parameters[i];
                if (p.In == sep.Location && p.Name is not null && namesToRemove.Contains(p.Name))
                {
                    operation.Parameters.RemoveAt(i);
                }
            }

            // Record the SmartEnum type so the companion document transformer can ensure
            // the component schema exists (it may not if the type only appears in parameters).
            var schemaName = sep.SmartEnumType.Name;
            PendingSchemas.TryAdd(schemaName, sep.SmartEnumType);

            IOpenApiSchema paramSchema = sep.IsArray
                ? new OpenApiSchema
                {
                    Type = JsonSchemaType.Array,
                    Items = new OpenApiSchemaReference(schemaName, context.Document)
                }
                : new OpenApiSchemaReference(schemaName, context.Document);

            operation.Parameters.Add(new OpenApiParameter
            {
                Name = sep.ParamName,
                In = sep.Location,
                Required = sep.IsRequired,
                Description = description,
                Schema = paramSchema
            });
        }

        return Task.CompletedTask;
    }

    /// <summary>
    /// Inspects the controller action method's parameters to discover SmartEnum types,
    /// both direct (<c>[FromQuery] C2Type? type</c>) and nested inside
    /// <c>[AsParameters]</c> DTO records.
    /// </summary>
    private static List<SmartEnumParamInfo> DiscoverSmartEnumParams(ControllerActionDescriptor cad)
    {
        var results = new List<SmartEnumParamInfo>();
        var methodParams = cad.MethodInfo.GetParameters();

        foreach (var mp in methodParams)
        {
            var paramType = mp.ParameterType;
            var underlyingType = Nullable.GetUnderlyingType(paramType) ?? paramType;

            if (typeof(ISmartEnum).IsAssignableFrom(underlyingType))
            {
                // Direct SmartEnum parameter (e.g. [FromQuery] C2Type? type).
                var location = GetParameterLocation(mp);
                if (location is null) continue;

                var queryName = GetQueryParamName(mp) ?? mp.Name ?? underlyingType.Name;
                var isNullable = IsNullableReferenceType(mp)
                    || Nullable.GetUnderlyingType(paramType) is not null;

                results.Add(new SmartEnumParamInfo(
                    queryName,
                    underlyingType,
                    location.Value,
                    IsRequired: location == ParameterLocation.Path || !isNullable,
                    IsTopLevel: true));
            }
            else if (TryGetSmartEnumElementType(underlyingType) is { } directElementType)
            {
                var location = GetParameterLocation(mp);
                if (location is null) continue;

                var queryName = GetQueryParamName(mp) ?? mp.Name ?? directElementType.Name;
                var isNullable = IsNullableReferenceType(mp)
                    || Nullable.GetUnderlyingType(paramType) is not null;

                results.Add(new SmartEnumParamInfo(
                    queryName,
                    directElementType,
                    location.Value,
                    IsRequired: location == ParameterLocation.Path || !isNullable,
                    IsTopLevel: true,
                    IsArray: true));
            }
            else if (HasAsParametersAttribute(mp))
            {
                // DTO record with [AsParameters] — inspect its properties.
                DiscoverSmartEnumPropsInDto(underlyingType, results);
            }
        }

        return results;
    }

    private static void DiscoverSmartEnumPropsInDto(Type dtoType, List<SmartEnumParamInfo> results)
    {
        foreach (var prop in dtoType.GetProperties(BindingFlags.Public | BindingFlags.Instance))
        {
            var propType = prop.PropertyType;
            var underlyingPropType = Nullable.GetUnderlyingType(propType) ?? propType;

            if (typeof(ISmartEnum).IsAssignableFrom(underlyingPropType))
            {
                var queryName = GetQueryParamNameFromProperty(prop)
                    ?? char.ToLowerInvariant(prop.Name[0]) + prop.Name[1..];

                var isNullable = Nullable.GetUnderlyingType(propType) is not null
                    || IsNullableReferenceTypeProperty(prop);

                results.Add(new SmartEnumParamInfo(
                    queryName,
                    underlyingPropType,
                    ParameterLocation.Query,
                    IsRequired: !isNullable,
                    IsTopLevel: false));
            }
            else if (TryGetSmartEnumElementType(underlyingPropType) is { } elementType)
            {
                var queryName = GetQueryParamNameFromProperty(prop)
                    ?? char.ToLowerInvariant(prop.Name[0]) + prop.Name[1..];

                var isNullable = Nullable.GetUnderlyingType(propType) is not null
                    || IsNullableReferenceTypeProperty(prop);

                results.Add(new SmartEnumParamInfo(
                    queryName,
                    elementType,
                    ParameterLocation.Query,
                    IsRequired: !isNullable,
                    IsTopLevel: false,
                    IsArray: true));
            }
        }
    }

    private static ParameterLocation? GetParameterLocation(ParameterInfo param)
    {
        if (param.GetCustomAttribute<FromRouteAttribute>() is not null)
            return ParameterLocation.Path;
        if (param.GetCustomAttribute<FromQueryAttribute>() is not null)
            return ParameterLocation.Query;
        if (param.GetCustomAttribute<FromFormAttribute>() is not null)
            return ParameterLocation.Query; // Form params appear as query in OpenAPI
        // Default binding for SmartEnum params without explicit attribute —
        // typically query for GET endpoints.
        if (param.GetCustomAttribute<FromBodyAttribute>() is null
            && param.GetCustomAttribute<FromHeaderAttribute>() is null
            && param.GetCustomAttribute<FromServicesAttribute>() is null)
            return ParameterLocation.Query;
        return null;
    }

    private static string? GetQueryParamName(ParameterInfo param)
    {
        var fromQuery = param.GetCustomAttribute<FromQueryAttribute>();
        if (fromQuery?.Name is { Length: > 0 } name) return name;
        var fromRoute = param.GetCustomAttribute<FromRouteAttribute>();
        if (fromRoute?.Name is { Length: > 0 } routeName) return routeName;
        return null;
    }

    private static string? GetQueryParamNameFromProperty(PropertyInfo prop)
    {
        var fromQuery = prop.GetCustomAttribute<FromQueryAttribute>();
        return fromQuery?.Name is { Length: > 0 } name ? name : null;
    }

    private static bool HasAsParametersAttribute(ParameterInfo param)
        => param.GetCustomAttribute<AsParametersAttribute>() is not null;

    private static bool IsNullableReferenceType(ParameterInfo param)
    {
        var context = new NullabilityInfoContext();
        var info = context.Create(param);
        return info.WriteState == NullabilityState.Nullable
            || info.ReadState == NullabilityState.Nullable;
    }

    private static bool IsNullableReferenceTypeProperty(PropertyInfo prop)
    {
        var context = new NullabilityInfoContext();
        var info = context.Create(prop);
        return info.WriteState == NullabilityState.Nullable
            || info.ReadState == NullabilityState.Nullable;
    }

    private static Type? TryGetSmartEnumElementType(Type type)
    {
        if (type.IsArray)
        {
            var elementType = type.GetElementType()!;
            return typeof(ISmartEnum).IsAssignableFrom(elementType) ? elementType : null;
        }

        if (type.IsGenericType)
        {
            var genDef = type.GetGenericTypeDefinition();
            if (genDef == typeof(List<>) || genDef == typeof(IList<>)
                || genDef == typeof(IReadOnlyList<>) || genDef == typeof(ICollection<>)
                || genDef == typeof(IReadOnlyCollection<>) || genDef == typeof(IEnumerable<>))
            {
                var elementType = type.GetGenericArguments()[0];
                return typeof(ISmartEnum).IsAssignableFrom(elementType) ? elementType : null;
            }
        }

        return null;
    }

    private sealed record SmartEnumParamInfo(
        string ParamName,
        Type SmartEnumType,
        ParameterLocation Location,
        bool IsRequired,
        bool IsTopLevel,
        bool IsArray = false);
}
