namespace Detour.Domain.Users;

/// <summary>
/// Serialises the writers of one rider's ride data (#895): a sync, and the deleted-ride trace
/// sweep. Without it a sync could check trace tombstones before the sweep commits and store a
/// line again that the sweep just cut, and the sweep runs once per tombstone, so that line would
/// stay.
/// </summary>
public interface IRiderLock
{
    /// <summary>
    /// Waits for, then holds, this rider's lock until the current transaction ends. Outside a
    /// transaction it is released as soon as it is taken, so call it inside one.
    /// </summary>
    Task AcquireAsync(Guid userId, CancellationToken cancellationToken);
}
