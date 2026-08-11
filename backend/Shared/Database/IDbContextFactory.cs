using Microsoft.EntityFrameworkCore;

namespace Shared.Database;

public interface ICustomDbContextFactory<TDbContext> : IAsyncDisposable, IDisposable, IDbContextFactory<TDbContext>
    where TDbContext : DbContext
{
    /// <summary>
    /// Always creates a new DbContext
    /// </summary>
    TDbContext CreateNew();

    new TDbContext CreateDbContext();
}
