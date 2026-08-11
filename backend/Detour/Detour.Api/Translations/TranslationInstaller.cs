using System.Globalization;
using Shared.Api.ResultTypeUtils.ExceptionHandlers;
using Shared.Translations;

namespace Detour.Api.Translations;

public static class TranslationInstaller
{
    private static readonly CultureInfo DefaultCulture = new("en");

    private static readonly IList<CultureInfo> SupportedCultures = [new("en")];

    public static IServiceCollection AddTranslations(this IServiceCollection services)
    {
        services.AddTranslations<Translations>(DefaultCulture, SupportedCultures);

        // The other half of the Result convention: a thrown ResultException becomes a localized
        // 400 ProblemDetails instead of a 500 with an exception message in it.
        services.AddExceptionHandler<ResultExceptionHandler>();

        return services;
    }
}

// Names the .resx set. Renaming this class means renaming Translations.en.resx alongside it.
public class Translations;
