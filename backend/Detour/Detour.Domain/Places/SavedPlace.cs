using JV.ResultUtilities;
using Shared.Domain;
using Shared.Database;

namespace Detour.Domain.Places;

/// <summary>
/// A rider's own shortcut — home, work, a favourite viewpoint. Private to its owner; the
/// circle-shared kind is <c>CirclePlace</c>, which is a different concept with a different
/// lifetime.
///
/// Opaque payload keyed by the client's own identifier, so a rename replaces the stored copy
/// and the merged union restores every shortcut after a reinstall.
/// </summary>
public sealed class SavedPlace : Entity
{
    public Guid UserId { get; private set; }

    /// <summary>The client-assigned identifier. Unique per owner, meaningless across owners.</summary>
    public long ClientPlaceId { get; private set; }

    public string Payload { get; private set; }

    public DateTimeOffset UpdatedAt { get; private set; }

    private SavedPlace(Guid userId, long clientPlaceId, string payload)
    {
        UserId = userId;
        ClientPlaceId = clientPlaceId;
        Payload = payload;
        UpdatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<SavedPlace> Create(Guid userId, long clientPlaceId, string payload)
    {
        var validation = Validate(clientPlaceId, payload);
        if (validation.IsFailure)
            return validation;

        return new SavedPlace(userId, clientPlaceId, payload);
    }

    public Result Replace(string payload)
    {
        var validation = Validate(ClientPlaceId, payload);
        if (validation.IsFailure)
            return validation;

        Payload = payload;
        UpdatedAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    private static Result Validate(long clientPlaceId, string? payload)
    {
        if (clientPlaceId == 0)
            return Result.Error(ValidationKeys.SavedPlace.IdRequired);

        return string.IsNullOrWhiteSpace(payload)
            ? Result.Error(ValidationKeys.SavedPlace.PayloadRequired)
            : Result.Ok();
    }
}

public interface ISavedPlaceRepository : IBaseRepository<SavedPlace>
{
    Task<List<SavedPlace>> GetForUserAsync(Guid userId, CancellationToken cancellationToken);

    Task<List<SavedPlace>> GetByClientIdsAsync(
        Guid userId,
        IReadOnlyCollection<long> clientPlaceIds,
        CancellationToken cancellationToken);
}
