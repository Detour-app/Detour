using System.Globalization;

namespace Shared.Translations;

public class TranslationOptions
{
    public CultureInfo DefaultCulture { get; set; } = Culture.Default;
}