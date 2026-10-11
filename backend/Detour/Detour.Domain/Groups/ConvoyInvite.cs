using System.Security.Cryptography;
using System.Text;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Groups;

/// <summary>
/// A link the host of a live convoy hands out so riders who are not their friends can join it.
///
/// It is the one deliberate exception to "membership only ever comes from a friendship", so it
/// is narrow on every axis: convoys only, host only, dies with the convoy and after
/// <see cref="DetourLimits.ConvoyInviteLifetime"/> regardless, revocable, and a guest the host
/// removes cannot come back through the same link. Only the hash is stored, as for API keys.
/// </summary>
public sealed class ConvoyInvite : Entity
{
    /// <summary>Bytes of entropy in a minted token — 256 bits, well past the 128 the spec asks.</summary>
    private const int TokenBytes = 32;

    private readonly List<ConvoyGuest> _guests = [];

    public Guid GroupId { get; private set; }

    public Guid CreatedBy { get; private set; }

    /// <summary>SHA-256 of the plaintext token, hex. Never the plaintext itself.</summary>
    public string TokenHash { get; private set; }

    public DateTimeOffset CreatedAt { get; private set; }

    public DateTimeOffset ExpiresAt { get; private set; }

    public DateTimeOffset? RevokedAt { get; private set; }

    public IReadOnlyCollection<ConvoyGuest> Guests => _guests;

    private ConvoyInvite(Guid groupId, Guid createdBy, string tokenHash, DateTimeOffset now)
    {
        GroupId = groupId;
        CreatedBy = createdBy;
        TokenHash = tokenHash;
        CreatedAt = now;
        ExpiresAt = now + DetourLimits.ConvoyInviteLifetime;
    }

    /// <summary>
    /// Mints a link and returns it alongside the plaintext token, which is the only copy that
    /// will ever exist. Who may mint one is the caller's check: this aggregate cannot see the
    /// convoy's host.
    /// </summary>
    public static (ConvoyInvite Invite, string Token) Issue(Guid groupId, Guid hostId, DateTimeOffset now)
    {
        var token = Convert.ToBase64String(RandomNumberGenerator.GetBytes(TokenBytes))
            .Replace('+', '-')
            .Replace('/', '_')
            .TrimEnd('=');

        return (new ConvoyInvite(groupId, hostId, HashOf(token), now), token);
    }

    public bool IsUsable(DateTimeOffset now) => RevokedAt is null && now < ExpiresAt;

    public void Revoke(DateTimeOffset now) => RevokedAt ??= now;

    public ConvoyGuest? FindGuest(Guid userId) => _guests.FirstOrDefault(g => g.UserId == userId);

    /// <summary>
    /// Records a rider coming in through this link. A rider the host removed stays out: the
    /// row is kept with <see cref="ConvoyGuest.RemovedAt"/> set precisely so this can refuse.
    /// Coming back after leaving on their own is allowed — that is a rider with a dropped
    /// connection, not one the host asked to go.
    /// </summary>
    public Result<ConvoyGuest> Admit(Guid userId, DateTimeOffset now)
    {
        if (!IsUsable(now))
            return Result.Error(ValidationKeys.ConvoyInvite.Expired);

        var existing = FindGuest(userId);
        if (existing is not null)
        {
            return existing.IsRemoved
                ? Result.Error(ValidationKeys.ConvoyInvite.GuestRemoved)
                : existing;
        }

        var guest = new ConvoyGuest(Id, userId, now);
        _guests.Add(guest);
        return guest;
    }

    public static string HashOf(string token) =>
        Convert.ToHexStringLower(SHA256.HashData(Encoding.UTF8.GetBytes(token)));

    // EF materialisation.
    private ConvoyInvite()
    {
        TokenHash = string.Empty;
    }
}

/// <summary>
/// A rider who joined through a <see cref="ConvoyInvite"/>. The convoy membership itself is an
/// ordinary <see cref="GroupMember"/>, so the relay needs no second code path; this row is what
/// tells the host who came in by link and what keeps a removed guest from walking back in.
/// </summary>
public sealed class ConvoyGuest : Entity
{
    public Guid InviteId { get; private set; }

    public Guid UserId { get; private set; }

    public DateTimeOffset JoinedAt { get; private set; }

    public DateTimeOffset? RemovedAt { get; private set; }

    internal ConvoyGuest(Guid inviteId, Guid userId, DateTimeOffset now)
    {
        InviteId = inviteId;
        UserId = userId;
        JoinedAt = now;
    }

    public bool IsRemoved => RemovedAt is not null;

    public void Remove(DateTimeOffset now) => RemovedAt ??= now;

    // EF materialisation.
    private ConvoyGuest() { }
}

public interface IConvoyInviteRepository : IBaseRepository<ConvoyInvite>
{
    /// <summary>The link with its guest rows, or null. The join lookup.</summary>
    Task<ConvoyInvite?> GetByHashAsync(string tokenHash, CancellationToken cancellationToken);

    /// <summary>Every link ever minted for this convoy, guests included, newest first.</summary>
    Task<List<ConvoyInvite>> GetForGroupAsync(Guid groupId, CancellationToken cancellationToken);
}
