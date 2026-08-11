using Detour.Database.Configuration;
using Microsoft.EntityFrameworkCore;
using Npgsql.EntityFrameworkCore.PostgreSQL.Infrastructure;
using Shared.Database;

namespace Detour.Database;

public sealed class DetourDbContextFactory(DatabaseSettings settings, string connectionString)
    : BaseDbContextFactory<DetourDbContext>(settings, connectionString)
{
    protected override DetourDbContext CreateInstance(DbContextOptions<DetourDbContext> options) =>
        new(options);

    protected override void ConfigureNpgsqlOptions(NpgsqlDbContextOptionsBuilder builder) =>
        DetourDatabaseConventions.ConfigureNpgsqlOptions(builder);
}
