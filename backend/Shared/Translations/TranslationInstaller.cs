using System.Globalization;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;

namespace Shared.Translations;

public static class TranslationInstaller
{
    public static IServiceCollection AddTranslations<TResource>(
        this IServiceCollection services,
        CultureInfo? defaultCulture = null,
        IList<CultureInfo>? supportedCultures = null)
    {
        defaultCulture ??= Culture.Default;
        supportedCultures ??= Culture.SupportedCultures;

        services.AddLocalization();

        services.Configure<TranslationOptions>(o => o.DefaultCulture = defaultCulture);

        services.Configure<RequestLocalizationOptions>(options =>
        {
            var cultureNames = supportedCultures
                .Select(c => c.TwoLetterISOLanguageName)
                .ToArray();

            options.SetDefaultCulture(defaultCulture.TwoLetterISOLanguageName)
                .AddSupportedCultures(cultureNames)
                .AddSupportedUICultures(cultureNames);
        });

        services.AddSingleton<ITranslator, DefaultTranslator<TResource>>();

        return services;
    }
}