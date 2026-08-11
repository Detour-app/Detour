using System.Globalization;
using Microsoft.Extensions.Localization;
using Microsoft.Extensions.Options;

namespace Shared.Translations;

public class DefaultTranslator<TResource>(
    IStringLocalizer<TResource> localizer,
    IOptions<TranslationOptions> options) : ITranslator
{
    public string Translate(string key)
    {
        return Translate(key, string.Empty);
    }

    public string Translate(string key, params object[] parameters)
    {
        var result = localizer.GetString(key, parameters);

        if (result.ResourceNotFound)
        {
            var originalCulture = CultureInfo.CurrentUICulture;
            try
            {
                CultureInfo.CurrentUICulture = options.Value.DefaultCulture;
                var fallbackResult = localizer.GetString(key, parameters);

                return CapitalizeFirstLetter(fallbackResult.ResourceNotFound ? key : fallbackResult.Value);
            }
            finally
            {
                CultureInfo.CurrentUICulture = originalCulture;
            }
        }

        return CapitalizeFirstLetter(result.Value);
    }
    
    private static string CapitalizeFirstLetter(string value)
    {
        if (string.IsNullOrWhiteSpace(value))
        {
            return value;
        }

        return char.ToUpper(value[0], CultureInfo.CurrentCulture) + value[1..];
    }
}