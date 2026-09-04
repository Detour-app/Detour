using Detour.Domain.Notifications;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class DeviceTokenRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<DeviceToken, DetourDbContext>(factory), IDeviceTokenRepository
{
    public Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetByTokenAsync)))
            .FirstOrDefaultAsync(t => t.Token == token, cancellationToken);

    public Task<List<DeviceTokenTarget>> GetForUsersAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUsersAsync)))
            .Where(t => userIds.Contains(t.UserId))
            .Select(t => new DeviceTokenTarget(t.UserId, t.Token))
            .ToListAsync(cancellationToken);

    public Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken cancellationToken) =>
        Set.Where(t => tokens.Contains(t.Token)).ExecuteDeleteAsync(cancellationToken);
}
