using System.Text;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Circles;

/// <summary>
/// A named point with a radius, owned by one member and shared into one circle.
///
/// Follows the shared-route precedent exactly: user-owned, shared into a group, and revoked
/// when the sharing relationship ends — here, the owner leaving that circle.
///
/// <see cref="ClientPlaceId"/> is assigned by the owner's device and is only unique per
/// (circle, owner): two members can independently pick the same integer. Anything that looks a
/// place up by that id has to cope with that, which is why presence events resolve a name by
/// most-recent match rather than by join.
/// </summary>
public sealed class CirclePlace : Entity
{
    public Guid GroupId { get; private set; }

    public Guid OwnerId { get; private set; }

    public long ClientPlaceId { get; private set; }

    public string Name { get; private set; }

    public double RadiusMeters { get; private set; }

    public string Payload { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    private CirclePlace(Guid groupId, Guid ownerId, long clientPlaceId, string name,
        double radiusMeters, string payload)
    {
        GroupId = groupId;
        OwnerId = ownerId;
        ClientPlaceId = clientPlaceId;
        Name = name;
        RadiusMeters = radiusMeters;
        Payload = payload;
        CreatedAt = DateTimeOffset.UtcNow;
    }

    public static Result<CirclePlace> Create(
        Guid groupId,
        Guid ownerId,
        long clientPlaceId,
        string? name,
        double radiusMeters,
        string payload)
    {
        var validation = Validate(clientPlaceId, radiusMeters, payload);
        if (validation.IsFailure)
            return validation;

        return new CirclePlace(groupId, ownerId, clientPlaceId, NormalizeName(name), radiusMeters, payload);
    }

    public Result Replace(string? name, double radiusMeters, string payload)
    {
        var validation = Validate(ClientPlaceId, radiusMeters, payload);
        if (validation.IsFailure)
            return validation;

        Name = NormalizeName(name);
        RadiusMeters = radiusMeters;
        Payload = payload;
        CreatedAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    private static Result Validate(long clientPlaceId, double radiusMeters, string? payload)
    {
        if (clientPlaceId == 0)
            return Result.Error(ValidationKeys.CirclePlace.PlaceIdRequired);

        if (!double.IsFinite(radiusMeters)
            || radiusMeters <= DetourLimits.MinPlaceRadiusMeters
            || radiusMeters > DetourLimits.MaxPlaceRadiusMeters)
        {
            return Result.Error(ValidationKeys.CirclePlace.RadiusOutOfRange,
                (int)DetourLimits.MaxPlaceRadiusMeters);
        }

        if (string.IsNullOrWhiteSpace(payload))
            return Result.Error(ValidationKeys.CirclePlace.PlaceIdRequired);

        return Encoding.UTF8.GetByteCount(payload) > DetourLimits.MaxPlacePayloadBytes
            ? Result.Error(ValidationKeys.CirclePlace.PayloadTooLarge, DetourLimits.MaxPlacePayloadBytes)
            : Result.Ok();
    }

    private static string NormalizeName(string? name)
    {
        var trimmed = (name ?? string.Empty).Trim();
        if (trimmed.Length == 0)
            return "Place";

        return trimmed.Length > DetourLimits.DisplayNameMaxLength
            ? trimmed[..DetourLimits.DisplayNameMaxLength]
            : trimmed;
    }

    // EF materialisation.
    private CirclePlace()
    {
        Name = string.Empty;
        Payload = string.Empty;
    }
}

public interface ICirclePlaceRepository : IBaseRepository<CirclePlace>
{
    Task<List<CirclePlace>> GetForGroupAsync(Guid groupId, CancellationToken cancellationToken);

    Task<CirclePlace?> GetForOwnerPlaceAsync(
        Guid groupId,
        Guid ownerId,
        long clientPlaceId,
        CancellationToken cancellationToken);

    /// <summary>Everything above the newest <paramref name="keep"/> for this (circle, owner).</summary>
    Task<List<CirclePlace>> GetOverflowForOwnerAsync(
        Guid groupId,
        Guid ownerId,
        int keep,
        CancellationToken cancellationToken);

    /// <summary>Called when the owner leaves: their places go with them.</summary>
    Task<int> DeleteForOwnerAsync(Guid groupId, Guid ownerId, CancellationToken cancellationToken);

    /// <summary>
    /// Best-effort display name for a client-assigned place id. Most recent match wins, because
    /// the id is only unique per (circle, owner) — it is used to word a notification, never to
    /// identify a row.
    /// </summary>
    Task<string?> ResolveNameAsync(Guid groupId, long clientPlaceId, CancellationToken cancellationToken);
}
