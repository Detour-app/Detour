namespace Detour.Domain.Traces;

/// <summary>
/// Thins a recorded track to something a dashboard can hold.
///
/// A full ride is tens of thousands of samples; an entity attribute that gets re-sent on every
/// render cannot be. Douglas-Peucker with a tolerance in metres, corrected for longitude
/// covering less ground away from the equator so a tolerance means the same thing north-south
/// and east-west.
/// </summary>
public static class TrackSimplifier
{
    /// <summary>
    /// Indices of the points worth keeping, rather than the points themselves. Callers need to
    /// look back at what the raw track held between two kept points — dropping a sample must
    /// not drop the peak lean it was carrying.
    /// </summary>
    public static List<int> Simplify(
        IReadOnlyList<(double Latitude, double Longitude)> points,
        double toleranceMeters,
        double referenceLatitude)
    {
        if (points.Count <= 2)
            return [.. Enumerable.Range(0, points.Count)];

        if (toleranceMeters <= 0)
            return [.. Enumerable.Range(0, points.Count)];

        var keep = new bool[points.Count];
        keep[0] = true;
        keep[^1] = true;

        // Longitude shrinks by cos(latitude); scaling it here lets the rest of the maths work in
        // plain degrees.
        var longitudeScale = Math.Max(Math.Cos(referenceLatitude * Math.PI / 180.0), 0.01);
        var tolerance = toleranceMeters / GeoPoint.MetresPerDegree;

        // Iterative rather than recursive: a long ride is deep enough to blow the stack, and a
        // rider's day out is exactly the input that would do it.
        var pending = new Stack<(int First, int Last)>();
        pending.Push((0, points.Count - 1));

        while (pending.Count > 0)
        {
            var (first, last) = pending.Pop();
            if (last <= first + 1)
                continue;

            var farthest = -1;
            var farthestDistance = 0.0;

            for (var i = first + 1; i < last; i++)
            {
                var distance = PerpendicularDistance(points[i], points[first], points[last], longitudeScale);
                if (distance > farthestDistance)
                {
                    farthest = i;
                    farthestDistance = distance;
                }
            }

            if (farthest < 0 || farthestDistance <= tolerance)
                continue;

            keep[farthest] = true;
            pending.Push((first, farthest));
            pending.Push((farthest, last));
        }

        var kept = new List<int>();
        for (var i = 0; i < keep.Length; i++)
        {
            if (keep[i])
                kept.Add(i);
        }

        return kept;
    }

    /// <summary>
    /// Evenly drops indices until at most <paramref name="limit"/> remain, always keeping the
    /// first and last. Applied after simplification, because a tolerance alone cannot promise a
    /// bound — a sufficiently wiggly road keeps every point at any tolerance.
    /// </summary>
    public static List<int> ThinTo(List<int> indices, int limit)
    {
        if (limit <= 0 || indices.Count <= limit)
            return indices;

        var step = (double)(indices.Count - 1) / (limit - 1);
        var thinned = new List<int>(limit);

        for (var i = 0; i < limit; i++)
            thinned.Add(indices[(int)Math.Round(i * step)]);

        return [.. thinned.Distinct()];
    }

    private static double PerpendicularDistance(
        (double Latitude, double Longitude) point,
        (double Latitude, double Longitude) start,
        (double Latitude, double Longitude) end,
        double longitudeScale)
    {
        var x = (point.Longitude - start.Longitude) * longitudeScale;
        var y = point.Latitude - start.Latitude;
        var dx = (end.Longitude - start.Longitude) * longitudeScale;
        var dy = end.Latitude - start.Latitude;

        var lengthSquared = (dx * dx) + (dy * dy);
        if (lengthSquared <= double.Epsilon)
            return Math.Sqrt((x * x) + (y * y));

        // Project onto the segment and clamp, so a point beyond either end measures to that end
        // rather than to the infinite line through them.
        var t = Math.Clamp(((x * dx) + (y * dy)) / lengthSquared, 0, 1);
        var offsetX = x - (t * dx);
        var offsetY = y - (t * dy);

        return Math.Sqrt((offsetX * offsetX) + (offsetY * offsetY));
    }
}
