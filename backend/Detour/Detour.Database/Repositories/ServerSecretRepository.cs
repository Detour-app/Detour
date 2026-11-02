using Detour.Domain.ServerSecrets;
using Microsoft.EntityFrameworkCore;
using Shared.Database;

namespace Detour.Database.Repositories;

public class ServerSecretRepository(ICustomDbContextFactory<DetourDbContext> factory) : IServerSecretRepository
{
    public async Task<byte[]> GetOrCreateAsync(string name, byte[] candidate, CancellationToken cancellationToken)
    {
        var context = factory.CreateDbContext();

        // Raw SQL for the upsert EF Core does not have: the first writer's value stands and a racing
        // second one is dropped, so every reader agrees. Interpolated, so both holes are bound as
        // parameters rather than spliced into the statement (see DeviceTokenRepository).
        await context.Database.ExecuteSqlInterpolatedAsync(
            $"""
            INSERT INTO detour.server_secrets (name, value) VALUES ({name}, {candidate})
            ON CONFLICT (name) DO NOTHING
            """,
            cancellationToken);

        return await context.Set<ServerSecret>()
            .AsNoTracking()
            .TagWith($"{nameof(ServerSecretRepository)}.{nameof(GetOrCreateAsync)}")
            .Where(s => s.Name == name)
            .Select(s => s.Value)
            .SingleAsync(cancellationToken);
    }
}
