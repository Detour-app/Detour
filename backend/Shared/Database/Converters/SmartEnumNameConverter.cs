using Ardalis.SmartEnum;
using Microsoft.EntityFrameworkCore.Storage.ValueConversion;

namespace Shared.Database.Converters;

/// <summary>
/// Stores a SmartEnum as its Name (string). Drop-in replacement for HasConversion&lt;string&gt;()
/// on a native enum: the column stays text and the stored value stays the member name.
/// </summary>
public sealed class SmartEnumNameConverter<TEnum> : ValueConverter<TEnum, string>
    where TEnum : SmartEnum<TEnum>
{
    public SmartEnumNameConverter()
        : base(
            smartEnum => smartEnum.Name,
            name => SmartEnum<TEnum>.FromName(name, ignoreCase: false))
    { }
}
