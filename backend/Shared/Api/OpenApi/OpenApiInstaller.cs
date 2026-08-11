using System.Text.Json;
using System.Text.Json.Nodes;
using System.Text.Json.Serialization;
using Ardalis.SmartEnum;
using Microsoft.AspNetCore.OpenApi;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.OpenApi;
using Shared.Api.Json;
using Shared.Api.Mvc;

namespace Shared.Api.OpenApi;

public static class OpenApiInstaller
{
    public static IServiceCollection SetupOpenApi(
        this IServiceCollection services,
        Action<OpenApiOptions>? configureOptions = null)
    {
        services.ConfigureHttpJsonOptions(options =>
        {
            options.SerializerOptions.NumberHandling = JsonNumberHandling.Strict;
            options.SerializerOptions.Converters.Add(new JsonStringEnumConverter());
            options.SerializerOptions.Converters.Add(new SmartEnumJsonConverterFactory());
        });

        services.AddControllers(mvcOptions =>
        {
            mvcOptions.ModelBinderProviders.Insert(0, new SmartEnumModelBinderProvider());
        }).AddJsonOptions(options =>
        {
            options.JsonSerializerOptions.NumberHandling = JsonNumberHandling.Strict;
            options.JsonSerializerOptions.Converters.Add(new JsonStringEnumConverter());
            options.JsonSerializerOptions.Converters.Add(new SmartEnumJsonConverterFactory());
        });

        services.AddOpenApi(options =>
        {
            options.AddSchemaTransformer((schema, context, ct) =>
            {
                var type = context.JsonTypeInfo.Type;
                if (type.IsEnum)
                {
                    schema.Type = JsonSchemaType.String;
                    schema.Enum =
                    [
                        ..Enum.GetNames(type)
                            .Select(name => (JsonNode)JsonValue.Create(name))
                    ];
                }
                else if (typeof(ISmartEnum).IsAssignableFrom(type))
                {
                    schema.Type = JsonSchemaType.String;
                    schema.Enum =
                    [
                        ..SmartEnumReflection.GetNames(type)
                            .Select(name => (JsonNode)JsonValue.Create(name))
                    ];
                }

                return Task.CompletedTask;
            });

            // Re-assert string enum shape after default transformations.
            // ASP.NET Core 10's OpenAPI generator merges nullable usages of an enum into
            // the canonical component schema, appending `null` to enum values and dropping
            // `type: string`. That breaks clients like openapi-typescript that expect a
            // clean string enum. We normalise the enum schemas as a final step.
            options.AddDocumentTransformer((document, _, _) =>
            {
                if (document.Components?.Schemas is null) return Task.CompletedTask;

                foreach (var (_, schemaBase) in document.Components.Schemas)
                {
                    if (schemaBase is not OpenApiSchema schema) continue;
                    if (schema.Enum is not { Count: > 0 }) continue;

                    var cleaned = schema.Enum
                        .Where(v => v is not null && v.GetValueKind() != JsonValueKind.Null)
                        .ToList<JsonNode>();

                    if (cleaned.Count == schema.Enum.Count && schema.Type == JsonSchemaType.String) continue;

                    schema.Enum = cleaned;
                    schema.Type = JsonSchemaType.String;
                }

                return Task.CompletedTask;
            });

            options.CreateSchemaReferenceId = typeInfo =>
                typeof(ISmartEnum).IsAssignableFrom(typeInfo.Type)
                    ? typeInfo.Type.Name
                    : OpenApiOptions.CreateDefaultSchemaReferenceId(typeInfo);

            options.AddOperationTransformer<SmartEnumParameterOperationTransformer>();

            // Declares the bearer scheme once so every [Authorize] endpoint documents it.
            options.AddDocumentTransformer<BearerSecuritySchemeTransformer>();

            // Ensure component schemas exist for SmartEnum types that only appear as
            // query/route parameters (never in a response body). The operation transformer
            // above records these in PendingSchemas; this document transformer creates them.
            options.AddDocumentTransformer((document, _, _) =>
            {
                if (SmartEnumParameterOperationTransformer.PendingSchemas.IsEmpty)
                    return Task.CompletedTask;

                document.Components ??= new OpenApiComponents();
                document.Components.Schemas ??= new Dictionary<string, IOpenApiSchema>();

                foreach (var (schemaName, smartEnumType) in SmartEnumParameterOperationTransformer.PendingSchemas)
                {
                    if (document.Components.Schemas.ContainsKey(schemaName))
                        continue;

                    document.Components.Schemas[schemaName] = new OpenApiSchema
                    {
                        Type = JsonSchemaType.String,
                        Enum =
                        [
                            ..SmartEnumReflection.GetNames(smartEnumType)
                                .Select(name => (JsonNode)JsonValue.Create(name))
                        ]
                    };
                }

                return Task.CompletedTask;
            });

            configureOptions?.Invoke(options);
        });

        return services;
    }
}