using Microsoft.AspNetCore.Builder;
using Microsoft.AspNetCore.Http;
using Microsoft.EntityFrameworkCore;
using Microsoft.Extensions.Logging;
using Shared.Database;

namespace Shared.Api.Middlewares;

public static class CustomTransactionMiddlewareExtensions
{
    public static IApplicationBuilder UseCustomTransactionMiddleware<T>(this IApplicationBuilder app)
        where T : DbContext
    {
        return app.UseMiddleware<CustomTransactionMiddleware<T>>();
    }
}

public class CustomTransactionMiddleware<T>(RequestDelegate next, ILogger<CustomTransactionMiddleware<T>> logger)
    : TransactionMiddlewareBase<T>(next, logger)
    where T : DbContext
{
    public async Task InvokeAsync(
        HttpContext context,
        ICustomDbContextFactory<T> dbContextFactory,
        IPostCommitActionQueue postCommitQueue)
    {
        await ExecuteWithTransaction(context, dbContextFactory, postCommitQueue);
    }
}