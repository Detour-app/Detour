using Detour.Domain.ProblemReports;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class ProblemReportRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<ProblemReport, DetourDbContext>(factory), IProblemReportRepository
{
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
