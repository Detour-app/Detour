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

    // ponytail: loads every active camera into memory to find which ones carry `source`, since
    // SourcesJson is an opaque jsonb blob rather than a queryable column — fine at the current
    // Western-Europe row count (~15k) for a one-shot admin import run (see CameraImport), not
    // fine at continent scale. Upgrade: a Postgres jsonb containment query/index on SourcesJson,
    // or a proper sources join table, once the row count actually hurts.
    public async Task<int> RetireMissingAsync(string source, IReadOnlySet<string> seenSourceIds, int retireAfterMisses, CancellationToken cancellationToken)
    {
        var candidates = await Set
            .TagWith(Tag(nameof(RetireMissingAsync)))
            .Where(c => c.Status == CameraStatus.Active)
            .ToListAsync(cancellationToken);

        var retired = 0;
        foreach (var camera in candidates)
        {
            var entry = camera.Sources.FirstOrDefault(s => s.Source == source);
            if (entry is null || seenSourceIds.Contains(entry.SourceId)) continue;

            camera.MarkSourceMissing(source, retireAfterMisses);
            if (camera.Status == CameraStatus.Retired) retired++;
        }
        return retired;
    }
}
