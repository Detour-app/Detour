using Shared.Database;

namespace Detour.Domain.Cameras;

public interface ICameraRepository : IBaseRepository<Camera>
{
    Task<List<Camera>> BboxAsync(double minLat, double minLon, double maxLat, double maxLon, CancellationToken cancellationToken);

    /// <summary>Clusters <paramref name="incoming"/> against every active camera whose bbox
    /// overlaps its own within a 40 m margin, merges into the match if found (via
    /// <see cref="Camera.MergeSource"/>), else persists <paramref name="incoming"/> as new.</summary>
    Task<Camera> UpsertAsync(Camera incoming, CancellationToken cancellationToken);
}
