namespace Detour.Domain.Users;

/// <summary>
/// Every badge the product defines, and what earns it.
///
/// This is product content, not configuration: the identifiers are stored on rider accounts and
/// shown to friends, so a threshold cannot be edited without deciding what happens to everyone
/// who already holds the badge above it. Kept here so the dashboard can show progress toward
/// the next tier without knowing the tiers itself.
/// </summary>
public static class BadgeCatalogue
{
    public static IReadOnlyList<BadgeFamily> Families { get; } =
    [
        new("dist", "Distance", BadgeMeasure.TotalDistanceMeters,
        [
            new(100_000, "First hundred"),
            new(500_000, "Getting somewhere"),
            new(1_000_000, "Four figures"),
            new(5_000_000, "Long hauler"),
            new(10_000_000, "Ten thousand"),
            new(25_000_000, "Round the world"),
        ]),
        new("speed", "Top speed", BadgeMeasure.TopSpeedKmh,
        [
            new(100, "Ton up"),
            new(130, "Motorway legal"),
            new(160, "Quick"),
            new(200, "Double ton"),
            new(250, "Terminal velocity"),
        ]),
        new("ride", "Single ride", BadgeMeasure.LongestTripMeters,
        [
            new(100_000, "Day out"),
            new(250_000, "Proper ride"),
            new(500_000, "Iron butt"),
        ]),
        new("muni", "Places", BadgeMeasure.MunicipalitiesVisited,
        [
            new(3, "Wanderer"),
            new(10, "Explorer"),
            new(25, "Cartographer"),
            new(50, "Conqueror"),
        ]),
        new("cover", "Coverage", BadgeMeasure.BestCoveragePercent,
        [
            new(10, "Local knowledge"),
            new(25, "Know the back roads"),
            new(50, "Half the town"),
            new(100, "Every last street"),
        ]),
    ];

    /// <summary>
    /// Scores every defined badge against a rider's numbers, earned or not, so a card can show
    /// how far off the next one is.
    /// </summary>
    public static IReadOnlyList<BadgeProgress> Score(
        RiderStats stats,
        IReadOnlyDictionary<string, long> earned)
    {
        var progress = new List<BadgeProgress>();

        foreach (var family in Families)
        {
            var value = Measure(stats, family.Measure);

            foreach (var tier in family.Tiers)
            {
                var id = $"{family.Prefix}_{tier.Threshold}";
                progress.Add(new BadgeProgress(
                    id,
                    family.Kind,
                    tier.Title,
                    tier.Threshold,
                    Math.Round(value, 1),
                    earned.TryGetValue(id, out var at) ? at : null,
                    Math.Round(Math.Min(value / tier.Threshold, 1.0) * 100, 1)));
            }
        }

        return progress;
    }

    private static double Measure(RiderStats stats, BadgeMeasure measure) => measure switch
    {
        BadgeMeasure.TotalDistanceMeters => stats.TotalDistanceMeters,
        BadgeMeasure.TopSpeedKmh => stats.TopSpeedKmh,
        BadgeMeasure.LongestTripMeters => stats.LongestTripMeters,
        BadgeMeasure.MunicipalitiesVisited => stats.MunicipalitiesVisited,
        BadgeMeasure.BestCoveragePercent => stats.BestCoveragePercent,
        _ => 0,
    };
}

public enum BadgeMeasure
{
    TotalDistanceMeters,
    TopSpeedKmh,
    LongestTripMeters,
    MunicipalitiesVisited,
    BestCoveragePercent,
}

public record BadgeFamily(
    string Prefix,
    string Kind,
    BadgeMeasure Measure,
    IReadOnlyList<BadgeTier> Tiers);

public record BadgeTier(int Threshold, string Title);

public record BadgeProgress(
    string Id,
    string Kind,
    string Title,
    int Threshold,
    double Value,
    long? EarnedAtMs,
    double ProgressPercent);
