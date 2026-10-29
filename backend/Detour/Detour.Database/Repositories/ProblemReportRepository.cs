using Detour.Domain.ProblemReports;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class ProblemReportRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<ProblemReport, DetourDbContext>(factory), IProblemReportRepository
{
    public Task<List<ProblemReport>> GetPageNonTrackingAsync(
        bool? filed, ProblemReport? after, int limit, CancellationToken cancellationToken)
    {
        var query = Set.AsNoTracking().TagWith(Tag(nameof(GetPageNonTrackingAsync)));

        if (filed is { } wanted)
            query = query.Where(r => (r.FiledAt != null) == wanted);

        if (after is not null)
            query = query.Where(r => r.ReceivedAt > after.ReceivedAt
                || (r.ReceivedAt == after.ReceivedAt && r.Id.CompareTo(after.Id) > 0));

        return query.OrderBy(r => r.ReceivedAt).ThenBy(r => r.Id).Take(limit).ToListAsync(cancellationToken);
    }

    public async Task TrimAsync(DateTimeOffset receivedBefore, int keep, CancellationToken cancellationToken)
    {
        await Set.TagWith(Tag(nameof(TrimAsync)))
            .Where(r => r.ReceivedAt < receivedBefore)
            .ExecuteDeleteAsync(cancellationToken);

        await Set.TagWith(Tag(nameof(TrimAsync)))
            .Where(r => !Set.OrderByDescending(n => n.ReceivedAt)
                .ThenByDescending(n => n.Id)
                .Take(keep)
                .Select(n => n.Id)
                .Contains(r.Id))
            .ExecuteDeleteAsync(cancellationToken);
    }
}
