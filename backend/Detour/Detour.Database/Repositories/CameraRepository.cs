using Detour.Domain.Cameras;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class CameraRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<Camera, DetourDbContext>(factory), ICameraRepository
{
    private const double ClusterRadiusMeters = 40.0;
    // ~40 m in degrees latitude; a generous bbox pre-filter before the exact Haversine check.
    private const double ClusterMarginDeg = 0.0006;

    public Task<List<Camera>> BboxAsync(double minLat, double minLon, double maxLat, double maxLon, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(BboxAsync)))
            .Where(c => c.Status == CameraStatus.Active)
            .Where(c => c.BboxMinLat <= maxLat && c.BboxMaxLat >= minLat)
            .Where(c => c.BboxMinLon <= maxLon && c.BboxMaxLon >= minLon)
            .ToListAsync(cancellationToken);

    public async Task<Camera> UpsertAsync(Camera incoming, CancellationToken cancellationToken)
    {
        // The row this source already calls by this id wins over proximity: a source whose
        // geometry for one object moves more than ClusterRadiusMeters (a section's bbox centre
        // after a polyline fix) would otherwise get a second row, and the old one would never
        // retire because its SourceId is still seen every run (issue #894).
        var own = await FindBySourceIdAsync(incoming, cancellationToken);
        if (own is not null)
        {
            own.MergeSource(incoming);
            return own;
        }

        // Retired cameras stay in the candidate set (not just Active) so a source that stops
        // reporting and later comes back finds its old row via Cluster/MergeSource instead of
        // creating a duplicate that orphans the retired row's Sources/AttributionJson history.
        // See issue #389.
        var compatibleKinds = Camera.MergeCompatibleKinds(incoming.Kind);
        bool InRange(Camera c) =>
            compatibleKinds.Contains(c.Kind) &&
            c.BboxMinLat <= incoming.BboxMaxLat + ClusterMarginDeg && c.BboxMaxLat >= incoming.BboxMinLat - ClusterMarginDeg &&
            c.BboxMinLon <= incoming.BboxMaxLon + ClusterMarginDeg && c.BboxMaxLon >= incoming.BboxMinLon - ClusterMarginDeg;

        var dbCandidates = await Set
            .TagWith(Tag(nameof(UpsertAsync)))
            .Where(c => compatibleKinds.Contains(c.Kind))
            .Where(c => c.BboxMinLat <= incoming.BboxMaxLat + ClusterMarginDeg && c.BboxMaxLat >= incoming.BboxMinLat - ClusterMarginDeg)
            .Where(c => c.BboxMinLon <= incoming.BboxMaxLon + ClusterMarginDeg && c.BboxMaxLon >= incoming.BboxMinLon - ClusterMarginDeg)
            .ToListAsync(cancellationToken);

        // Set.Local carries entities Save() added earlier in this DbContext's lifetime but not
        // yet flushed — CameraImport's single end-of-loop flush means a whole import run shares
        // one context, so without this an entry upserted earlier in the same run is invisible to
        // a later entry's clustering (the DB query above only sees what's actually in the table).
        // See issue #367.
        var candidates = dbCandidates.UnionBy(Set.Local.Where(InRange), c => c.Id).ToList();

        var match = Camera.Cluster(candidates, incoming, ClusterRadiusMeters);
        if (match is null)
        {
            Save(incoming);
            return incoming;
        }

        match.MergeSource(incoming);
        return match;
    }

    /// <summary>The row already carrying <paramref name="incoming"/>'s (Source, SourceId), Active or
    /// Retired, among kinds it may merge with. Active first, then the most recently seen, so a
    /// stale duplicate left by an import before #894 doesn't win over the live row.</summary>
    private async Task<Camera?> FindBySourceIdAsync(Camera incoming, CancellationToken cancellationToken)
    {
        var key = incoming.Sources[0];
        var compatibleKinds = Camera.MergeCompatibleKinds(incoming.Kind);
        bool Carries(Camera c) =>
            compatibleKinds.Contains(c.Kind) && c.Sources.Any(s => s.Source == key.Source && s.SourceId == key.SourceId);

        // jsonb containment: an array element with both keys. Property names are the record's,
        // as System.Text.Json writes them by default (see Camera.SourcesJson).
        var probe = System.Text.Json.JsonSerializer.Serialize(new[] { new { key.Source, key.SourceId } });
        var dbMatches = await Set
            .TagWith(Tag(nameof(FindBySourceIdAsync)))
            .Where(c => compatibleKinds.Contains(c.Kind))
            .Where(c => EF.Functions.JsonContains(c.SourcesJson, probe))
            .ToListAsync(cancellationToken);

        // Set.Local for a row added earlier in this import run and not flushed yet (see #367).
        return dbMatches.Where(Carries)
            .UnionBy(Set.Local.Where(Carries), c => c.Id)
            .OrderBy(c => c.Status == CameraStatus.Active ? 0 : 1)
            .ThenByDescending(c => c.LastSeen)
            .FirstOrDefault();
    }

    // ponytail: loads every active camera into memory to find which ones carry `source`, since
    // SourcesJson is an opaque jsonb blob rather than a queryable column — fine at the current
    // Western-Europe row count (~15k) for a one-shot admin import run (see CameraImport), not
    // fine at continent scale. Upgrade: a Postgres jsonb containment query/index on SourcesJson,
    // or a proper sources join table, once the row count actually hurts.
    public async Task<int> RetireMissingAsync(string source, string? region, IReadOnlySet<string> seenSourceIds, int retireAfterMisses, CancellationToken cancellationToken)
    {
        var candidates = await Set
            .TagWith(Tag(nameof(RetireMissingAsync)))
            .Where(c => c.Status == CameraStatus.Active)
            .ToListAsync(cancellationToken);

        var retired = 0;
        foreach (var camera in candidates)
        {
            // Every entry of this source, not just the first: a camera clustered from two nodes of
            // one source must charge both, or it never retires once both disappear (issue #406).
            var missing = camera.Sources
                .Where(s => s.Source == source && !seenSourceIds.Contains(s.SourceId))
                .Where(s => region is null || s.Region is null || s.Region == region)
                .ToList();
            foreach (var entry in missing) camera.MarkSourceMissing(source, entry.SourceId, retireAfterMisses);
            if (missing.Count > 0 && camera.Status == CameraStatus.Retired) retired++;
        }
        return retired;
    }
}
