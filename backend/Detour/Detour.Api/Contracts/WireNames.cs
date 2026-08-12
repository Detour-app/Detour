using Ardalis.SmartEnum;

namespace Detour.Api.Contracts;

/// <summary>
/// How a domain enum is spelled on the wire.
///
/// One vocabulary, lowercase: <c>accepted</c>, <c>invited</c>, <c>pending</c>, <c>circle</c>,
/// <c>convoy</c>, <c>arrive</c>, <c>depart</c>. That is what the apps send, what the functional
/// spec names, and — since reads here are already case-insensitive — the only spelling this
/// service both accepts and produces.
///
/// Not done by renaming the enum members: their <c>Name</c> is what the database stores, so
/// changing it would rewrite meaning already on disk. This is the seam between the two.
/// </summary>
internal static class WireNames
{
    public static string Wire<T>(this SmartEnum<T> value) where T : SmartEnum<T> =>
        value.Name.ToLowerInvariant();

    /// <summary>
    /// For a name that has already been read back out of the database as a plain string, which
    /// is how the projections that feed the read paths carry it.
    /// </summary>
    public static string Wire(this string name) => name.ToLowerInvariant();
}
