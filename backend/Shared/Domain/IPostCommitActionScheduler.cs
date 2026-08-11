namespace Shared.Domain;

/// <summary>
/// Schedules an action to run after the ambient transaction commits successfully.
/// If the transaction is rolled back (request failure, exception, cancellation),
/// scheduled actions are discarded and never executed.
/// Actions run fire-and-forget — they should not observe request cancellation tokens.
/// </summary>
public interface IPostCommitActionScheduler
{
    void Schedule(Func<Task> action);
}
