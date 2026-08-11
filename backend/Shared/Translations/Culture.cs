using System.Globalization;

namespace Shared.Translations;

public static class Culture
{
    public static CultureInfo English => new("en");

    /// <summary>
    /// The default culture and language string, language string is important for culture behaviour pipeline, needs compile time constant
    /// </summary>
    public static CultureInfo Default => English;

    public const string DefaultLanguage = "en";


    /// <summary>
    /// All the supported cultures
    /// </summary>
    public static IList<CultureInfo> SupportedCultures =>
    [
        English,
    ];
}