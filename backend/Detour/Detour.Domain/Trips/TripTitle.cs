using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Trips;

/// <summary>
/// A title the rider typed over a ride's generated one, keyed by the ride's start instant.
///
/// Its own row rather than a field in the trip document: the trip document is replaced by
/// whichever device uploads it, so a rename stored there would be reverted by every device that
/// never saw it (#471). An empty title is a tombstone — the rider cleared the rename — and is
/// kept, so the older title another device still holds cannot come back.
/// </summary>
public sealed class TripTitle : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>The ride's start instant, Unix milliseconds — the same key trips use.</summary>
    public long TripStartTimeMs { get; private set; }

    /// <summary>Empty when the rider cleared the rename.</summary>
    public string Title { get; private set; }

    /// <summary>When the rider made this edit, Unix milliseconds, as the device reported it.</summary>
    public long EditedAtMs { get; private set; }

    private TripTitle(Guid userId, long tripStartTimeMs, string title, long editedAtMs)
    {
        UserId = userId;
        TripStartTimeMs = tripStartTimeMs;
        Title = title;
        EditedAtMs = editedAtMs;
    }

    public static Result<TripTitle> Create(Guid userId, long tripStartTimeMs, string? title, long editedAtMs)
    {
        var validation = Validate(tripStartTimeMs, title, editedAtMs);
        if (validation.IsFailure)
            return validation;

        return new TripTitle(userId, tripStartTimeMs, Normalise(title), editedAtMs);
    }

    /// <summary>
    /// Newest edit wins, so a device that never saw a rename cannot revert it by re-uploading its
    /// older one. A tie keeps the stored copy, which makes a re-upload of the same edit a no-op.
    /// Returns true when the stored title changed.
    /// </summary>
    public bool KeepNewest(string? title, long editedAtMs)
    {
        if (editedAtMs <= EditedAtMs || Validate(TripStartTimeMs, title, editedAtMs).IsFailure)
            return false;

        Title = Normalise(title);
        EditedAtMs = editedAtMs;
        return true;
    }

    private static string Normalise(string? title) => title?.Trim() ?? string.Empty;

    private static Result Validate(long tripStartTimeMs, string? title, long editedAtMs)
    {
        if (tripStartTimeMs <= 0)
            return Result.Error(ValidationKeys.Trip.StartTimeRequired);

        if (editedAtMs < 0)
            return Result.Error(ValidationKeys.TripTitle.EditedAtInvalid);

        return Normalise(title).Length > DetourLimits.DisplayNameMaxLength
            ? Result.Error(ValidationKeys.TripTitle.TooLong, DetourLimits.DisplayNameMaxLength)
            : Result.Ok();
    }
}

public interface ITripTitleRepository : IBaseRepository<TripTitle>
{
    Task<List<TripTitle>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);
}
