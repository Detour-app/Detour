using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Shared.Extensions;

namespace Shared.Api.Middlewares;

public abstract class TransactionMiddlewareBase<T>(RequestDelegate next, ILogger logger)
    where T : DbContext
{
    private readonly HashSet<string> _methodsToSkip = ["GET", "OPTIONS", "HEAD"];

    protected async Task ExecuteWithTransaction(
        HttpContext context,
        IDbContextFactory<T> dbContextFactory,
        IPostCommitActionQueue postCommitQueue)
    {
        var endpoint = context.GetEndpoint();
        if (_methodsToSkip.Contains(context.Request.Method)
            || context.Request.Path.IsOpenApiRequest()
            || endpoint?.Metadata.GetMetadata<SkipTransactionAttribute>() is not null)
        {
            await next(context);
            return;
        }

        // create DbContext
        await using T dbContext = dbContextFactory.CreateDbContext();

        // Start the transaction
        await using var transaction = await dbContext.Database.BeginTransactionAsync(context.RequestAborted);

        postCommitQueue.Activate();

        try
        {
            // Execute the rest of the pipeline
            await next(context);

            // Commit only when the pipeline completed successfully
            if (context.Response.StatusCode < 400)
            {
                await dbContext.SaveChangesAsync(context.RequestAborted);
                await transaction.CommitAsync(context.RequestAborted);
                await postCommitQueue.ExecuteAsync(logger);
            }
            else
            {
                postCommitQueue.Clear();
                await transaction.RollbackAsync(context.RequestAborted);
            }
        }
        catch (Exception ex)
        {
            postCommitQueue.Clear();

            // Log before rolling back: a failed write breaks the Npgsql connection, so the rollback
            // itself can throw (ObjectDisposedException: NpgsqlTransaction) and would otherwise replace
            // the real cause — leaving it visible only as an InnerException. See issue #778.
            logger.LogError(ex, "Error while processing request – rolling back transaction.");

            try
            {
                await transaction.RollbackAsync(context.RequestAborted);
            }
            catch (Exception rollbackEx)
            {
                logger.LogError(rollbackEx,
                    "Rollback failed after a request error; the transaction was already unusable.");
            }

            throw; // Let the global exception handler convert this to the proper response
        }
    }
}
