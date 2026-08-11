using System.Security.Cryptography;
using System.Text;
using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.Traces;

/// <summary>
/// One fog-of-war line: an ordered list of recorded points, stored as the client serialised it.
///
/// Deduplicated by content hash, because every sync re-sends the whole history — re-uploading a
/// line the server already holds has to be a no-op, and it has to be cheap.
/// </summary>
public sealed class Trace : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>SHA-256 of <see cref="Line"/>, hex. The natural key with <see cref="UserId"/>.</summary>
    public string LineHash { get; private set; }

    public string Line { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    private Trace(Guid userId, string lineHash, string line)
    {
        UserId = userId;
        LineHash = lineHash;
        Line = line;
        CreatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<Trace> Create(Guid userId, string line)
    {
        if (string.IsNullOrWhiteSpace(line))
            return Result.Error(ValidationKeys.Trace.LineRequired);

        var trimmed = line.Trim();
        return new Trace(userId, HashOf(trimmed), trimmed);
    }

    public static string HashOf(string line) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(line)));
}
