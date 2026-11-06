using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Traces;

/// <summary>
/// The record that a stored trace line was removed because a ride it covered was deleted (#780).
///
/// Traces deduplicate on content, so once the line's row is gone a device still holding its copy
/// would upload it as new, and the deleted ride's location would be back. With this row the upload
/// is ignored and the line is absent from the response, so that device drops its copy too. Only
/// the hash stays; the positions are not kept.
/// </summary>
public sealed class TraceTombstone : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>SHA-256 of the removed line, hex, as in <see cref="Trace.LineHash"/>.</summary>
    public string LineHash { get; private set; }

    /// <summary>When the server removed the line.</summary>
    public DateTimeOffset DeletedAt { get; private set; }

    private TraceTombstone(Guid userId, string lineHash)
    {
        UserId = userId;
        LineHash = lineHash;
        DeletedAt = DateTimeOffset.UtcNow;
    }

    public static Result<TraceTombstone> Create(Guid userId, string lineHash)
    {
        if (string.IsNullOrWhiteSpace(lineHash))
            return Result.Error(ValidationKeys.Trace.LineRequired);

        return new TraceTombstone(userId, lineHash);
    }
}

public interface ITraceTombstoneRepository : IBaseRepository<TraceTombstone>
{
    /// <summary>The hashes this user has tombstoned, out of the candidates offered.</summary>
    Task<HashSet<string>> GetExistingHashesAsync(
        Guid userId,
        IReadOnlyCollection<string> hashes,
        CancellationToken cancellationToken);
}
