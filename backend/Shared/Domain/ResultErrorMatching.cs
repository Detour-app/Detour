using JV.ResultUtilities;
using JV.ResultUtilities.ValidationMessage;

namespace Shared.Domain;

public static class ResultErrorMatching
{
    public static bool HasError(this Result result, ValidationKeyDefinition key) =>
        result.IsFailure && result.ValidationMessages.Any(v => v.TranslationKey == key.Key);

    public static bool HasError<T>(this Result<T> result, ValidationKeyDefinition key) =>
        result.IsFailure && result.ValidationMessages.Any(v => v.TranslationKey == key.Key);
}
