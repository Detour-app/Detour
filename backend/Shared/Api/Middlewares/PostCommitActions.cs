using Microsoft.Extensions.Logging;

namespace Shared.Api.Middlewares;

/// <summary>
/// Scoped queue of actions to run after the ambient transaction commits successfully.
/// Activated by the transaction middleware (HTTP) or hub filter (SignalR) at the start of the
/// scope; entries are executed after a successful commit and discarded on rollback or failure.
/// Outside an activated scope, <see cref="Add"/> throws so callers fail fast instead of
/// silently dropping work.
/// </summary>
public interface IPostCommitActionQueue
{
    bool IsActive { get; }
    void Activate();
    void Add(Func<Task> action);
    Task ExecuteAsync(ILogger logger);
    void Clear();
}

public class PostCommitActionQueue : IPostCommitActionQueue
{
    private List<Func<Task>>? _actions;
    private bool _activated;

    public bool IsActive => _activated;

    public void Activate()
    {
        _activated = true;
    }

    public void Add(Func<Task> action)
    {
        ArgumentNullException.ThrowIfNull(action);
        if (!_activated)
        {
            throw new InvalidOperationException(
                "Post-commit actions require an active transaction scope.");
        }

        _actions ??= [];
        _actions.Add(action);
    }

    public async Task ExecuteAsync(ILogger logger)
    {
        var actions = _actions;
        _actions = null;
        _activated = false;

        if (actions is null)
            return;

        foreach (var action in actions)
        {
            try
            {
                await action();
            }
            catch (Exception ex)
            {
                logger.LogError(ex, "Post-commit action failed.");
            }
        }
    }

    public void Clear()
    {
        _actions = null;
        _activated = false;
    }
}
