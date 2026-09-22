using Shared.Database;

namespace Detour.Domain.Cameras;

public interface ICameraRepository : IBaseRepository<Camera>
{
    Task<List<Camera>> BboxAsync(double minLat, double minLon, double maxLat, double maxLon, CancellationToken cancellationToken);

    /// <summary>Clusters <paramref name="incoming"/> against every camera — retired included —
    /// whose bbox overlaps its own within a 40 m margin, merges into the match if found (via
    /// <see cref="Camera.MergeSource"/>, which reactivates a retired match), else persists
    /// <paramref name="incoming"/> as new. Retired cameras stay candidates so a source that
    /// stops reporting and later comes back finds its old row instead of forking a duplicate
    /// (issue #389).</summary>
    Task<Camera> UpsertAsync(Camera incoming, CancellationToken cancellationToken);

    /// <summary>Marks a completed import run of <paramref name="source"/> as missing every
    /// active camera that source has ever reported whose <see cref="CameraSource.SourceId"/>
    /// isn't in <paramref name="seenSourceIds"/> this run (via <see cref="Camera.MarkSourceMissing"/>),
    /// retiring any camera whose every source has independently missed
    /// <paramref name="retireAfterMisses"/> consecutive runs. Issue #369. Returns the number of
    /// cameras retired this call.</summary>
    Task<int> RetireMissingAsync(string source, IReadOnlySet<string> seenSourceIds, int retireAfterMisses, CancellationToken cancellationToken);
}
