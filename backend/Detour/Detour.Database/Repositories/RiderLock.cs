using Detour.Domain.Users;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

/// <summary>A transaction-scoped Postgres advisory lock keyed on the rider.</summary>
public class RiderLock(ICustomDbContextFactory<DetourDbContext> factory) : IRiderLock
{
    public async Task AcquireAsync(Guid userId, CancellationToken cancellationToken)
    {
        // Interpolated, so the key is bound as a parameter (see DeviceTokenRepository).
        var key = KeyOf(userId);
        await factory.CreateDbContext().Database.ExecuteSqlInterpolatedAsync(
            $"SELECT pg_advisory_xact_lock({key})",
            cancellationToken);
    }

    /// <summary>
    /// The lock's 64-bit key: both halves of the id folded together. Two riders sharing a key
    /// only wait for each other; no other advisory lock is taken in this database.
    /// </summary>
    internal static long KeyOf(Guid userId)
    {
        Span<byte> bytes = stackalloc byte[16];
        userId.TryWriteBytes(bytes);
        return BitConverter.ToInt64(bytes[..8]) ^ BitConverter.ToInt64(bytes[8..]);
    }
}
