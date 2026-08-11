using Microsoft.EntityFrameworkCore;
using Npgsql.EntityFrameworkCore.PostgreSQL.Infrastructure;

namespace Shared.Database;

public static class NpgsqlDatabaseConventions
{
    public const int PostgresMajorVersion = 18;
    public const int PostgresMinorVersion = 0;

    public static void ConfigureProvider<TDbContext>(NpgsqlDbContextOptionsBuilder builder)
        where TDbContext : DbContext
    {
        builder.SetPostgresVersion(PostgresMajorVersion, PostgresMinorVersion);
        builder.MigrationsAssembly(typeof(TDbContext).Assembly.GetName().Name!);
    }
}
