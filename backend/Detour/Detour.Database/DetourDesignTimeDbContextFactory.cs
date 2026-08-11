using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Design;

namespace Detour.Database;

/// <summary>
/// Only <c>dotnet ef migrations add</c> uses this. That command builds the model and reads the
/// snapshot; it never touches the database, so this works whether or not the target exists.
///
/// The connection string is deliberately a constant rather than read from configuration or the
/// environment: an accidental <c>database update</c> must not be pointable at an arbitrary
/// target by ambient config.
/// </summary>
public class DetourDesignTimeDbContextFactory : IDesignTimeDbContextFactory<DetourDbContext>
{
    private const string DesignTimeConnection =
        "Host=localhost;Port=5432;Database=detour;Username=postgres;Password=postgres";

    public DetourDbContext CreateDbContext(string[] args)
    {
        var options = new DbContextOptionsBuilder<DetourDbContext>()
            .UseNpgsql(DesignTimeConnection, DetourDatabaseConventions.ConfigureNpgsqlOptions)
            .UseSnakeCaseNamingConvention()
            .Options;

        return new DetourDbContext(options);
    }
}
