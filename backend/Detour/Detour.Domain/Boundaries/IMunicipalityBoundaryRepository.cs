using Shared.Database;

namespace Detour.Domain.Boundaries;

public interface IMunicipalityBoundaryRepository : IBaseRepository<MunicipalityBoundary>
{
    /// <summary>Every boundary whose bbox contains (<paramref name="lat"/>, <paramref
    /// name="lon"/>) — a cheap pre-filter over the composite bbox index. The caller
    /// (<see cref="MunicipalityBoundary.Contains"/>, via <c>MunicipalityController</c>) runs
    /// the exact ray cast over the handful of rows this returns; two candidate bboxes can
    /// overlap the point without either polygon actually containing it, and an enclave means
    /// more than one *can* legitimately contain it.</summary>
    Task<List<MunicipalityBoundary>> CandidatesAsync(double lat, double lon, CancellationToken cancellationToken);

    /// <summary>Upserts by <see cref="MunicipalityBoundary.SourceId"/>: a matching existing row
    /// is replaced in place (<see cref="MunicipalityBoundary.ReplaceWith"/>), else <paramref
    /// name="incoming"/> is persisted as new.
    ///
    /// No missing-row deletion here, same reasoning as
    /// <see cref="Detour.Domain.Roads.ISpeedLimitWayRepository.UpsertAsync"/>: a per-region
    /// import run legitimately never sees another region's boundaries, so deleting whatever one
    /// run didn't see would wipe every previously imported region on the next.</summary>
    Task<MunicipalityBoundary> UpsertAsync(MunicipalityBoundary incoming, CancellationToken cancellationToken);
}
