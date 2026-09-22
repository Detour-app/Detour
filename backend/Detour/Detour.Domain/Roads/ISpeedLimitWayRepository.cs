using Shared.Database;

namespace Detour.Domain.Roads;

public interface ISpeedLimitWayRepository : IBaseRepository<SpeedLimitWay>
{
    Task<List<SpeedLimitWay>> BboxAsync(double minLat, double minLon, double maxLat, double maxLon, CancellationToken cancellationToken);

    /// <summary>Upserts by <see cref="SpeedLimitWay.SourceId"/>: a matching existing row is
    /// replaced in place (<see cref="SpeedLimitWay.ReplaceWith"/>), else <paramref name="incoming"/>
    /// is persisted as new.
    ///
    /// No missing-row deletion here, unlike
    /// <see cref="Detour.Domain.Cameras.ICameraRepository.RetireMissingAsync"/>: the self-host
    /// import workflow runs one `.pbf` region per invocation (`docker/prod/config/regions.env`),
    /// and a run over one region legitimately never sees another region's ways — deleting
    /// whatever a single run didn't see would wipe every previously imported region on the next
    /// one. Left for a follow-up once imports are region-scoped (a `region` column, or a
    /// combined multi-region run) the way <see cref="Detour.Domain.Cameras.Camera.MarkSourceMissing"/>'s
    /// per-source hysteresis already solves it for cameras.</summary>
    Task<SpeedLimitWay> UpsertAsync(SpeedLimitWay incoming, CancellationToken cancellationToken);
}
