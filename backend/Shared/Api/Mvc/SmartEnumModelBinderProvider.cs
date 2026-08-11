using Ardalis.SmartEnum;
using Microsoft.AspNetCore.Mvc.ModelBinding;

namespace Shared.Api.Mvc;

/// <summary>
/// Registers <see cref="SmartEnumModelBinder"/> for every model type implementing
/// <see cref="ISmartEnum"/>. Insert at position 0 in
/// <see cref="Microsoft.AspNetCore.Mvc.MvcOptions.ModelBinderProviders"/> so it runs
/// before the default complex-type binder.
/// </summary>
public sealed class SmartEnumModelBinderProvider : IModelBinderProvider
{
    public IModelBinder? GetBinder(ModelBinderProviderContext context)
    {
        ArgumentNullException.ThrowIfNull(context);

        var modelType = context.Metadata.UnderlyingOrModelType;
        if (typeof(ISmartEnum).IsAssignableFrom(modelType))
        {
            return new SmartEnumModelBinder(modelType);
        }

        return null;
    }
}
