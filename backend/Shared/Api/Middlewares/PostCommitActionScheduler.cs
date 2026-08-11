using Microsoft.Extensions.DependencyInjection;
using Shared.Domain;

namespace Shared.Api.Middlewares;

public static class PostCommitActionSchedulerExtensions
{
    public static IServiceCollection AddPostCommitActionScheduler(this IServiceCollection services)
    {
        services.AddScoped<IPostCommitActionQueue, PostCommitActionQueue>();
        services.AddScoped<IPostCommitActionScheduler, PostCommitActionScheduler>();
        return services;
    }
}

public class PostCommitActionScheduler(IPostCommitActionQueue queue) : IPostCommitActionScheduler
{
    public void Schedule(Func<Task> action)
    {
        ArgumentNullException.ThrowIfNull(action);
        queue.Add(action);
    }
}
