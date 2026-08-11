using System.Reflection;
using Ardalis.SmartEnum;
using Microsoft.AspNetCore.Mvc.ModelBinding;

namespace Shared.Api.Mvc;

/// <summary>
/// Model binder that resolves SmartEnum values from their Name string.
/// Handles [FromQuery], [FromRoute], and [FromForm] parameters whose type
/// implements <see cref="ISmartEnum"/>.
/// </summary>
public sealed class SmartEnumModelBinder(Type smartEnumType) : IModelBinder
{
    private readonly MethodInfo? _tryFromName = ResolveTryFromName(smartEnumType);

    public Task BindModelAsync(ModelBindingContext bindingContext)
    {
        var modelName = bindingContext.ModelName;
        var valueProviderResult = bindingContext.ValueProvider.GetValue(modelName);

        if (valueProviderResult == ValueProviderResult.None)
        {
            return HandleMissingValue(bindingContext);
        }

        bindingContext.ModelState.SetModelValue(modelName, valueProviderResult);
        var value = valueProviderResult.FirstValue;

        if (string.IsNullOrWhiteSpace(value))
        {
            return HandleMissingValue(bindingContext);
        }

        if (_tryFromName is null)
        {
            bindingContext.ModelState.TryAddModelError(
                modelName,
                $"The type '{smartEnumType.Name}' could not be resolved as a SmartEnum.");
            bindingContext.Result = ModelBindingResult.Failed();
            return Task.CompletedTask;
        }

        var parameters = new object?[] { value, /*ignoreCase:*/ true, null };
        var success = (bool)_tryFromName.Invoke(null, parameters)!;

        if (success)
        {
            bindingContext.Result = ModelBindingResult.Success(parameters[2]);
        }
        else
        {
            bindingContext.ModelState.TryAddModelError(
                modelName,
                $"The value '{value}' is not a valid {smartEnumType.Name}.");
            bindingContext.Result = ModelBindingResult.Failed();
        }

        return Task.CompletedTask;
    }

    private static Task HandleMissingValue(ModelBindingContext bindingContext)
    {
        if (IsNullableModelType(bindingContext))
        {
            bindingContext.Result = ModelBindingResult.Success(null);
        }

        // Non-nullable: leave Result unset so MVC's required-binding logic returns 400.
        return Task.CompletedTask;
    }

    private static bool IsNullableModelType(ModelBindingContext bindingContext)
        => !bindingContext.ModelMetadata.IsRequired
           || bindingContext.ModelMetadata.ModelType != bindingContext.ModelMetadata.UnderlyingOrModelType;

    private static MethodInfo? ResolveTryFromName(Type enumType)
    {
        if (!typeof(ISmartEnum).IsAssignableFrom(enumType))
            return null;

        // TryFromName is defined on SmartEnum<TEnum, TValue> with an `out TEnum` parameter,
        // so the by-ref type must use the concrete enum type, not the closed base type.
        return enumType.GetMethod(
            "TryFromName",
            BindingFlags.Public | BindingFlags.Static | BindingFlags.FlattenHierarchy,
            null,
            [typeof(string), typeof(bool), enumType.MakeByRefType()],
            null);
    }
}
