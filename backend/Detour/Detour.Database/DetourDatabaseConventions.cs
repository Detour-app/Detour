using Npgsql.EntityFrameworkCore.PostgreSQL.Infrastructure;
using Shared.Database;

namespace Detour.Database;

public static class DetourDatabaseConventions
{
    /// <summary>
    /// The backend owns one schema in its database. Keycloak keeps its own database entirely,
    /// so nothing else lives here — but naming the schema rather than using <c>public</c> keeps
    /// a future co-tenant (a read-only reporting role, an extension) from colliding.
    /// </summary>
    public const string Schema = "detour";

    public static void ConfigureNpgsqlOptions(NpgsqlDbContextOptionsBuilder builder)
    {
        builder.SetPostgresVersion(
            NpgsqlDatabaseConventions.PostgresMajorVersion,
            NpgsqlDatabaseConventions.PostgresMinorVersion);
        builder.MigrationsAssembly(typeof(DetourDbContext).Assembly.GetName().Name!);
        builder.MigrationsHistoryTable("__ef_migrations_history", Schema);
    }
}
