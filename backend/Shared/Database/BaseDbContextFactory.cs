using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Microsoft.Extensions.Logging;
using Npgsql.EntityFrameworkCore.PostgreSQL.Infrastructure;
using Shared.Database.Configuration;

namespace Shared.Database;

/// <summary>
/// Abstract base for connection-string-based DbContext factories.
/// Provides <see cref="CreateDbContext"/> (cached) and <see cref="CreateNew"/> (fresh)
/// with Npgsql + snake_case + optional sensitive-data logging. Subclasses supply
/// <see cref="CreateInstance"/> and optionally override <see cref="ConfigureOptions"/>
/// to add interceptors (e.g. audit).
/// </summary>
public abstract class BaseDbContextFactory<TDbContext>(
    BaseDatabaseSettings settings,
    string connectionString)
    : ICustomDbContextFactory<TDbContext>
    where TDbContext : DbContext
{
    private readonly bool _enableSensitiveDataLogging = settings.EnableQueryLogging;
    private TDbContext? _dbContext;

    public TDbContext CreateDbContext()
    {
        _dbContext ??= BuildContext();

        return _dbContext;
    }

    /// <summary>
    /// Always creates a new DbContext. The caller owns it — the factory keeps no
    /// reference, so disposing it cannot affect <see cref="CreateDbContext"/>.
    /// </summary>
    public TDbContext CreateNew() => BuildContext();

    private TDbContext BuildContext()
    {
        var dbOptions = new DbContextOptionsBuilder<TDbContext>()
            .UseNpgsql(connectionString, ConfigureNpgsqlOptions);

        ConfigureOptions(dbOptions);

        dbOptions.UseSnakeCaseNamingConvention();

        if (_enableSensitiveDataLogging)
        {
            dbOptions
                .EnableSensitiveDataLogging()
                .LogTo(Console.WriteLine, (eventId, logLevel) => logLevel >= LogLevel.Information
                                                                 || eventId == RelationalEventId.DataReaderDisposing);
        }

        return CreateInstance(dbOptions.Options);
    }

    /// <summary>
    /// Override to add interceptors or other options after UseNpgsql and before
    /// UseSnakeCaseNamingConvention. The default implementation is a no-op.
    /// </summary>
    protected virtual void ConfigureOptions(DbContextOptionsBuilder<TDbContext> builder)
    {
    }

    /// <summary>
    /// Override to configure Npgsql-specific provider options.
    /// </summary>
    protected virtual void ConfigureNpgsqlOptions(NpgsqlDbContextOptionsBuilder builder) =>
        NpgsqlDatabaseConventions.ConfigureProvider<TDbContext>(builder);

    /// <summary>
    /// Construct a new <typeparamref name="TDbContext"/> from the configured options.
    /// </summary>
    protected abstract TDbContext CreateInstance(DbContextOptions<TDbContext> options);

    public void Dispose()
    {
        Dispose(true);
        GC.SuppressFinalize(this);
    }

    public async ValueTask DisposeAsync()
    {
        if (_dbContext != null)
        {
            await _dbContext.DisposeAsync();
            _dbContext = null;
        }

        Dispose(false);
        GC.SuppressFinalize(this);
    }

    protected virtual void Dispose(bool disposing)
    {
        if (disposing)
        {
            _dbContext?.Dispose();
            _dbContext = null;
        }
    }
}
