using Detour.Domain.ApiKeys;
using Detour.Domain.Circles;
using Detour.Domain.Groups;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class GroupRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<Group, DetourDbContext>(factory), IGroupRepository
{
    public Task<Group?> GetWithMembersAsync(Guid groupId, CancellationToken cancellationToken) =>
        Set.Include(g => g.Members)
            .TagWith(Tag(nameof(GetWithMembersAsync)))
            .FirstOrDefaultAsync(g => g.Id == groupId, cancellationToken);

    public Task<List<Group>> GetForUserAsync(Guid userId, GroupKind kind, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .Include(g => g.Members)
            .TagWith(Tag(nameof(GetForUserAsync)))
            .Where(g => g.Kind == kind && g.Members.Any(m => m.UserId == userId))
            .OrderByDescending(g => g.CreatedAt)
            .ToListAsync(cancellationToken);

    public Task<GroupMember?> GetAcceptedMembershipAsync(
        Guid groupId,
        Guid userId,
        CancellationToken cancellationToken) =>
        Context.GroupMembers.AsNoTracking()
            .TagWith(Tag(nameof(GetAcceptedMembershipAsync)))
            .FirstOrDefaultAsync(
                m => m.GroupId == groupId
                     && m.UserId == userId
                     && m.Status == GroupMemberStatus.Accepted,
                cancellationToken);

    public Task<List<Guid>> GetAcceptedMemberIdsAsync(Guid groupId, CancellationToken cancellationToken) =>
        Context.GroupMembers.AsNoTracking()
            .TagWith(Tag(nameof(GetAcceptedMemberIdsAsync)))
            .Where(m => m.GroupId == groupId && m.Status == GroupMemberStatus.Accepted)
            .Select(m => m.UserId)
            .ToListAsync(cancellationToken);
}

public class MemberFixRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<MemberFix, DetourDbContext>(factory), IMemberFixRepository
{
    public Task<MemberFix?> GetForMemberAsync(Guid groupId, Guid userId, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetForMemberAsync)))
            .FirstOrDefaultAsync(f => f.GroupId == groupId && f.UserId == userId, cancellationToken);

    public Task<List<MemberFixView>> GetSharingFixesAsync(Guid groupId, CancellationToken cancellationToken) =>
        (from fix in Set.AsNoTracking()
         join member in Context.GroupMembers
             on new { fix.GroupId, fix.UserId } equals new { member.GroupId, member.UserId }
         where fix.GroupId == groupId
               && member.Status == GroupMemberStatus.Accepted
               && member.IsSharing
         select new MemberFixView(
             fix.UserId,
             fix.Latitude,
             fix.Longitude,
             fix.AccuracyMeters,
             fix.TimestampMs))
        .TagWith(Tag(nameof(GetSharingFixesAsync)))
        .ToListAsync(cancellationToken);

    public Task<int> DeleteForMemberAsync(Guid groupId, Guid userId, CancellationToken cancellationToken) =>
        Set.Where(f => f.GroupId == groupId && f.UserId == userId).ExecuteDeleteAsync(cancellationToken);
}

public class CirclePlaceRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<CirclePlace, DetourDbContext>(factory), ICirclePlaceRepository
{
    public Task<List<CirclePlace>> GetForGroupAsync(Guid groupId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForGroupAsync)))
            .Where(p => p.GroupId == groupId)
            .OrderByDescending(p => p.CreatedAt)
            .ToListAsync(cancellationToken);

    public Task<CirclePlace?> GetForOwnerPlaceAsync(
        Guid groupId,
        Guid ownerId,
        long clientPlaceId,
        CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetForOwnerPlaceAsync)))
            .FirstOrDefaultAsync(
                p => p.GroupId == groupId && p.OwnerId == ownerId && p.ClientPlaceId == clientPlaceId,
                cancellationToken);

    public Task<List<CirclePlace>> GetOverflowForOwnerAsync(
        Guid groupId,
        Guid ownerId,
        int keep,
        CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetOverflowForOwnerAsync)))
            .Where(p => p.GroupId == groupId && p.OwnerId == ownerId)
            .OrderByDescending(p => p.CreatedAt)
            .ThenByDescending(p => p.Id)
            .Skip(keep)
            .ToListAsync(cancellationToken);

    public Task<int> DeleteForOwnerAsync(Guid groupId, Guid ownerId, CancellationToken cancellationToken) =>
        Set.Where(p => p.GroupId == groupId && p.OwnerId == ownerId).ExecuteDeleteAsync(cancellationToken);

    public async Task<string?> ResolveNameAsync(
        Guid groupId,
        long clientPlaceId,
        CancellationToken cancellationToken)
    {
        // Most recent match wins. The id is only unique per (circle, owner), so this is
        // best-effort by construction — it words a notification, it does not identify a row.
        var names = await Set.AsNoTracking()
            .TagWith(Tag(nameof(ResolveNameAsync)))
            .Where(p => p.GroupId == groupId && p.ClientPlaceId == clientPlaceId)
            .OrderByDescending(p => p.CreatedAt)
            .Select(p => p.Name)
            .Take(1)
            .ToListAsync(cancellationToken);

        return names.Count == 0 ? null : names[0];
    }
}

public class PlaceEventRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<PlaceEvent, DetourDbContext>(factory), IPlaceEventRepository
{
    public Task<List<PlaceEventView>> GetSinceAsync(
        Guid groupId,
        long sinceMs,
        CancellationToken cancellationToken) =>
        (from placeEvent in Set.AsNoTracking()
         where placeEvent.GroupId == groupId && placeEvent.TimestampMs > sinceMs
         orderby placeEvent.TimestampMs
         select new PlaceEventView(
             placeEvent.Id,
             placeEvent.ClientPlaceId,
             // A correlated subquery, not a join: two members can independently assign the same
             // client place id, and a plain join on (group, place id) would multiply one event
             // into several rows.
             Context.CirclePlaces
                 .Where(p => p.GroupId == placeEvent.GroupId && p.ClientPlaceId == placeEvent.ClientPlaceId)
                 .OrderByDescending(p => p.CreatedAt)
                 .Select(p => p.Name)
                 .FirstOrDefault() ?? string.Empty,
             placeEvent.UserId,
             placeEvent.Kind.Name,
             placeEvent.TimestampMs))
        .TagWith(Tag(nameof(GetSinceAsync)))
        .ToListAsync(cancellationToken);

    public Task<List<PlaceEvent>> GetOverflowAsync(Guid groupId, int keep, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetOverflowAsync)))
            .Where(e => e.GroupId == groupId)
            .OrderByDescending(e => e.TimestampMs)
            .ThenByDescending(e => e.Id)
            .Skip(keep)
            .ToListAsync(cancellationToken);
}

public class ApiKeyRepository(ICustomDbContextFactory<DetourDbContext> factory)
    : BaseRepository<ApiKey, DetourDbContext>(factory), IApiKeyRepository
{
    public Task<ApiKey?> GetByHashAsync(string keyHash, CancellationToken cancellationToken) =>
        Set.TagWith(Tag(nameof(GetByHashAsync)))
            .FirstOrDefaultAsync(k => k.KeyHash == keyHash, cancellationToken);

    public Task<List<ApiKey>> GetForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.AsNoTracking()
            .TagWith(Tag(nameof(GetForUserAsync)))
            .Where(k => k.UserId == userId)
            .OrderByDescending(k => k.CreatedAt)
            .ToListAsync(cancellationToken);

    public Task<int> DeleteForUserAsync(Guid userId, CancellationToken cancellationToken) =>
        Set.Where(k => k.UserId == userId).ExecuteDeleteAsync(cancellationToken);
}
