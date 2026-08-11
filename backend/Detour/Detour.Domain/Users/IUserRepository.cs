using Shared.Database;

namespace Detour.Domain.Users;

public interface IUserRepository : IBaseRepository<User>
{
    /// <summary>The account behind an identity-provider subject, or null on first sign-in.</summary>
    Task<User?> GetBySubjectAsync(string subject, CancellationToken cancellationToken);

    /// <summary>Case-insensitive, because handles are compared that way everywhere.</summary>
    Task<User?> GetByUsernameAsync(string username, CancellationToken cancellationToken);

    Task<bool> ExistsByUsernameAsync(string username, CancellationToken cancellationToken);

    Task<List<User>> GetManyAsync(IReadOnlyCollection<Guid> ids, CancellationToken cancellationToken);
}

public interface IBadgeAwardRepository : IBaseRepository<BadgeAward>
{
    Task<List<BadgeAward>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    Task<Dictionary<Guid, List<BadgeAward>>> GetForUsersAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken);

    Task<int> CountForUserAsync(Guid userId, CancellationToken cancellationToken);
}
