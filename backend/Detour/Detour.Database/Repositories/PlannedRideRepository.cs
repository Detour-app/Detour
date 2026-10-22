using Detour.Domain.PlannedRides;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class PlannedRideRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<PlannedRide, DetourDbContext>(factory), IPlannedRideRepository
{
    public Task<PlannedRide?> GetWithRsvpsAsync(Guid rideId, CancellationToken cancellationToken) =>
        Set.Include(r => r.Rsvps)
            .TagWith(Tag(nameof(GetWithRsvpsAsync)))
            .FirstOrDefaultAsync(r => r.Id == rideId, cancellationToken);

    public Task<List<PlannedRide>> GetForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .Include(r => r.Rsvps)
            .TagWith(Tag(nameof(GetForUserAsync)))
            .Where(r => r.OwnerId == userId || r.Rsvps.Any(v => v.UserId == userId))
            .OrderBy(r => r.StartsAt)
            .ThenBy(r => r.Id)
            .ToListAsync(cancellationToken);

    public Task<int> DeleteRsvpsBetweenAsync(Guid a, Guid b, CancellationToken cancellationToken) =>
        Context.PlannedRideRsvps
            .Where(v => (v.UserId == b && Context.PlannedRides.Any(r => r.Id == v.RideId && r.OwnerId == a))
                        || (v.UserId == a && Context.PlannedRides.Any(r => r.Id == v.RideId && r.OwnerId == b)))
            .ExecuteDeleteAsync(cancellationToken);

    public Task<List<PlannedRide>> GetDueForReminderAsync(
        DateTimeOffset now, TimeSpan lead, CancellationToken cancellationToken)
    {
        var windowEnd = now + lead;
        return Set.Include(r => r.Rsvps)
            .TagWith(Tag(nameof(GetDueForReminderAsync)))
            .Where(r => r.StartsAt > now && r.StartsAt <= windowEnd
                        && (r.RemindedForStartsAt == null || r.RemindedForStartsAt != r.StartsAt))
            .ToListAsync(cancellationToken);
    }
}
