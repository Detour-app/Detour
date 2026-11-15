namespace Detour.Api.Controllers;

/// <summary>
/// The one bounding-box guard the anonymous bbox endpoints (<c>/api/cameras</c>,
/// <c>/api/pois</c>, <c>/api/roads</c>, <c>/api/speedlimits</c>) share, so a fix to it lands
/// in all four (issue #965).
/// </summary>
internal static class BoundingBoxQuery
{
    /// <summary>
    /// True when the box is finite, inside the globe, not inverted, and no wider than
    /// <paramref name="maxSpanDegrees"/> on either axis. Written as positive comparisons on
    /// purpose: every comparison with <c>NaN</c> is false, so a negated guard
    /// (<c>minLat &gt; maxLat || …</c>) lets <c>maxLat=NaN</c> through, and PostgreSQL then
    /// sorts <c>NaN</c> above every float and matches the whole table. The
    /// <see cref="double.IsFinite"/> checks say so explicitly rather than relying on that.
    /// </summary>
    public static bool IsValid(double minLat, double minLon, double maxLat, double maxLon, double maxSpanDegrees) =>
        double.IsFinite(minLat) && double.IsFinite(minLon)
        && double.IsFinite(maxLat) && double.IsFinite(maxLon)
        && minLat <= maxLat && minLon <= maxLon
        && minLat >= -90 && maxLat <= 90 && minLon >= -180 && maxLon <= 180
        && maxLat - minLat <= maxSpanDegrees && maxLon - minLon <= maxSpanDegrees;
}
