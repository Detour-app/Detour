using Microsoft.EntityFrameworkCore;
using Shared.Domain;

namespace Shared.Database;

public interface IBaseRepository<TEntity>
    where TEntity : Entity
{
    Task<TEntity?> GetAsync(Guid id, CancellationToken token);
    Task<TEntity?> GetNonTrackingAsync(Guid id, CancellationToken token);
    Task<bool> ExistsAsync(Guid id, CancellationToken token);
    Task<List<TEntity>> GetAllAsync(CancellationToken token);
    Task<List<TEntity>> GetAllNonTrackingAsync(CancellationToken token);
    Task SaveAsync(TEntity entity, CancellationToken token);
    void Save(TEntity entity);
    void Delete(TEntity entity);
    Task ReloadAsync(TEntity entity, CancellationToken token);

    /// <summary>
    /// Persists any pending changes tracked by the repository to the underlying data store.
    /// should not be used in an http context, middlewares should handle transactions.
    /// </summary>
    /// <param name="token">A cancellation token that can be used to cancel the operation.</param>
    /// <returns>A task that represents the asynchronous flush operation.</returns>
    Task FlushChangesAsync(CancellationToken token);
}

public interface INameUniqueRepository<TEntity> : IBaseRepository<TEntity>
    where TEntity : Entity, INamedEntity
{
    Task<bool> ExistsByNameAsync(string name, CancellationToken cancellationToken);
    Task<bool> ExistsByNameExcludingIdAsync(string name, Guid excludeId, CancellationToken cancellationToken);
}

public abstract class BaseRepository<TEntity, TContext> : IBaseRepository<TEntity>
    where TEntity : Entity
    where TContext : DbContext
{
    protected readonly TContext? DbContext;
    protected readonly ICustomDbContextFactory<TContext>? DbContextFactory;

    /// <summary>
    /// Returns the active DbContext.
    /// When backed by a factory, a new instance is created each call.
    /// When a DbContext is injected directly, the same instance is reused.
    /// </summary>
    protected TContext Context =>
        DbContext ?? DbContextFactory!.CreateDbContext();

    protected DbSet<TEntity> Set => Context.Set<TEntity>();

    protected BaseRepository(TContext dbContext)
    {
        DbContext = dbContext ?? throw new ArgumentNullException(nameof(dbContext));
    }

    protected BaseRepository(ICustomDbContextFactory<TContext> dbContextFactory)
    {
        DbContextFactory = dbContextFactory ?? throw new ArgumentNullException(nameof(dbContextFactory));
    }

    protected string Tag(string method) => $"{GetType().Name}.{method}";

    public Task<TEntity?> GetAsync(Guid id, CancellationToken token)
    {
        return Set
            .TagWith(Tag(nameof(GetAsync)))
            .FirstOrDefaultAsync(entity => entity.Id == id, cancellationToken: token);
    }

    public Task<TEntity?> GetNonTrackingAsync(Guid id, CancellationToken token)
    {
        return Set
            .AsNoTracking()
            .TagWith(Tag(nameof(GetNonTrackingAsync)))
            .FirstOrDefaultAsync(entity => entity.Id == id, cancellationToken: token);
    }

    public Task<bool> ExistsAsync(Guid id, CancellationToken token)
    {
        return Set
            .TagWith(Tag(nameof(ExistsAsync)))
            .AnyAsync(entity => entity.Id == id, token);
    }

    public Task<List<TEntity>> GetAllAsync(CancellationToken token)
    {
        return Set
            .TagWith(Tag(nameof(GetAllAsync)))
            .ToListAsync(token);
    }

    public Task<List<TEntity>> GetAllNonTrackingAsync(CancellationToken token)
    {
        return Set
            .AsNoTracking()
            .TagWith(Tag(nameof(GetAllNonTrackingAsync)))
            .ToListAsync(token);
    }

    public async Task SaveAsync(TEntity entity, CancellationToken token)
    {
        if (!Set.Local.Contains(entity))
            await Set.AddAsync(entity, token);
    }

    public void Save(TEntity entity)
    {
        if (!Set.Local.Contains(entity))
            Set.Add(entity);
    }

    public void Delete(TEntity entity)
    {
        Set.Remove(entity);
    }

    public async Task ReloadAsync(TEntity entity, CancellationToken token)
    {
        await Context.Entry(entity).ReloadAsync(token);
    }

    public Task FlushChangesAsync(CancellationToken token)
    {
        return Context.SaveChangesAsync(token);
    }
}

public abstract class NamedBaseRepository<TEntity, TContext>(ICustomDbContextFactory<TContext> dbContextFactory)
    : BaseRepository<TEntity, TContext>(dbContextFactory), INameUniqueRepository<TEntity>
    where TEntity : Entity, INamedEntity
    where TContext : DbContext
{
    public Task<bool> ExistsByNameAsync(string name, CancellationToken cancellationToken)
    {
        return Set.TagWith(Tag(nameof(ExistsByNameAsync))).AnyAsync(e => e.Name == name, cancellationToken);
    }

    public Task<bool> ExistsByNameExcludingIdAsync(string name, Guid excludeId, CancellationToken cancellationToken)
    {
        return Set.TagWith(Tag(nameof(ExistsByNameExcludingIdAsync)))
            .AnyAsync(e => e.Name == name && e.Id != excludeId, cancellationToken);
    }
}