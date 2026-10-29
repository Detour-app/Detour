namespace Detour.Domain.ServerSecrets;

/// <summary>
/// A key this server made for itself and must keep across restarts, because something derived
/// from it is compared over time. The only one today is the key that hides who sent a problem
/// report from an administrator (#820).
///
/// Keyed by name rather than an id: there is one of each, and a second writer racing the first
/// must find the first one's value rather than add its own.
/// </summary>
public sealed class ServerSecret
{
    public const string ProblemReportReporterKey = "problem-report-reporter";

    public string Name { get; private set; }

    public byte[] Value { get; private set; }

    // EF materialisation. Rows are only ever written by IServerSecretRepository.GetOrCreateAsync.
    private ServerSecret()
    {
        Name = string.Empty;
        Value = [];
    }
}

public interface IServerSecretRepository
{
    /// <summary>
    /// The value stored under <paramref name="name"/>, storing <paramref name="candidate"/> first
    /// when there is none. Two callers racing on a fresh database both get the value that won.
    /// Commits on its own when no request transaction is open, which an administrator's list is
    /// not.
    /// </summary>
    Task<byte[]> GetOrCreateAsync(string name, byte[] candidate, CancellationToken cancellationToken);
}
