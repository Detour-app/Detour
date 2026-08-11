namespace Detour.Domain;

/// <summary>
/// The one place a coordinate is judged usable.
///
/// JSON parsing accepts Infinity and NaN, and a stored non-number breaks every map that later
/// reads it back — a peer's live view, a stored last fix, a GeoJSON layer. Coordinates are
/// therefore range-checked wherever they enter, not wherever they are drawn.
/// </summary>
public static class GeoPoint
{
    public static bool IsValid(double latitude, double longitude) =>
        double.IsFinite(latitude)
        && double.IsFinite(longitude)
        && latitude is >= -90 and <= 90
        && longitude is >= -180 and <= 180;

    /// <summary>Metres per degree of latitude. Longitude covers cos(lat) of this.</summary>
    public const double MetresPerDegree = 111_320.0;
}
