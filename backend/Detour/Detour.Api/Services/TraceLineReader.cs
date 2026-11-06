using System.Text.Json;
using Detour.Domain.Traces;

namespace Detour.Api.Services;

/// <summary>
/// Unpacks a fog-of-war line into the individual samples the read-only dashboard reads.
///
/// A line is an array of points, each <c>[lat, lon, tMs, speedKmh, leanDeg]</c>. Older
/// two-element points predate timestamps: they still draw fog, but there is no instant to hang
/// them on, so they are skipped rather than stored with a made-up time.
///
/// Bad values are dropped point by point. One broken reading must not cost the whole ride —
/// that is the difference between a rider losing a sample and losing a day out.
/// </summary>
public static class TraceLineReader
{
    public static bool IsWellFormed(string line)
    {
        try
        {
            using var document = JsonDocument.Parse(line);
            return document.RootElement.ValueKind == JsonValueKind.Array;
        }
        catch (JsonException)
        {
            return false;
        }
    }

    public static List<TrackPoint> ReadPoints(Guid userId, string line)
    {
        var points = new List<TrackPoint>();

        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(line);
        }
        catch (JsonException)
        {
            return points;
        }

        using (document)
        {
            if (document.RootElement.ValueKind != JsonValueKind.Array)
                return points;

            foreach (var raw in document.RootElement.EnumerateArray())
            {
                if (raw.ValueKind != JsonValueKind.Array)
                    continue;

                var values = raw.EnumerateArray().ToArray();
                if (values.Length < 3)
                    continue; // pre-timestamp point: fog only

                if (!TryNumber(values[0], out var latitude)
                    || !TryNumber(values[1], out var longitude)
                    || !TryNumber(values[2], out var timestamp))
                {
                    continue;
                }

                var point = TrackPoint.TryCreate(
                    userId,
                    (long)timestamp,
                    latitude,
                    longitude,
                    values.Length > 3 && TryNumber(values[3], out var speed) ? speed : null,
                    values.Length > 4 && TryNumber(values[4], out var lean) ? lean : null);

                if (point is not null)
                    points.Add(point);
            }
        }

        return points;
    }

    /// <summary>
    /// <paramref name="line"/> with every point inside <paramref name="windows"/> (inclusive) cut
    /// out, as one line per run of at least two surviving points; null when no point is inside,
    /// which means the line is untouched.
    ///
    /// The server-side twin of the client's <c>TraceStore.withoutWindow</c>. Surviving points keep
    /// their exact text, so a run comes out byte-for-byte what the deleting device uploads for it
    /// and deduplicates against that upload. A point with no instant can't be placed in a window
    /// and is kept.
    /// </summary>
    public static IReadOnlyList<string>? WithoutWindows(string line, IReadOnlyList<TimeWindow> windows)
    {
        JsonDocument document;
        try
        {
            document = JsonDocument.Parse(line);
        }
        catch (JsonException)
        {
            return null;
        }

        using (document)
        {
            if (document.RootElement.ValueKind != JsonValueKind.Array)
                return null;

            var cut = false;
            var runs = new List<string>();
            var run = new List<string>();
            foreach (var raw in document.RootElement.EnumerateArray())
            {
                if (TryInstant(raw, out var instant) && windows.Any(w => w.Contains(instant)))
                {
                    cut = true;
                    Close(run, runs);
                }
                else
                {
                    run.Add(raw.GetRawText());
                }
            }

            if (!cut)
                return null;

            Close(run, runs);
            return runs;
        }
    }

    private static void Close(List<string> run, List<string> runs)
    {
        if (run.Count >= 2)
            runs.Add($"[{string.Join(',', run)}]");
        run.Clear();
    }

    private static bool TryInstant(JsonElement point, out long instant)
    {
        instant = 0;
        if (point.ValueKind != JsonValueKind.Array || point.GetArrayLength() < 3
            || !TryNumber(point[2], out var value))
        {
            return false;
        }

        instant = (long)value;
        return true;
    }

    private static bool TryNumber(JsonElement element, out double value)
    {
        value = 0;
        return element.ValueKind == JsonValueKind.Number
               && element.TryGetDouble(out value)
               && double.IsFinite(value);
    }
}

/// <summary>A span of Unix milliseconds, both ends included.</summary>
public readonly record struct TimeWindow(long FromMs, long ToMs)
{
    public bool Contains(long instant) => instant >= FromMs && instant <= ToMs;
}
