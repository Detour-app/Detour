using Shared.Database.Configuration;

namespace Detour.Database.Configuration;

public class DatabaseSettings : BaseDatabaseSettings
{
    public const string SectionName = "Database";

    /// <summary>
    /// Set on a host that applies migrations out of band (a migration job, a DBA). The API
    /// applies them at startup by default — this is a single-service deployment, and a schema
    /// that lags the code is a worse failure than a brief startup delay.
    /// </summary>
    public bool SkipMigrations { get; set; }
}
