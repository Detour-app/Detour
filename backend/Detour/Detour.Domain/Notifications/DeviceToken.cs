using Ardalis.SmartEnum;
using JV.ResultUtilities;
using Shared.Database;
using Shared.Domain;

namespace Detour.Domain.Notifications;

public sealed class DevicePlatform : SmartEnum<DevicePlatform>
{
    public static readonly DevicePlatform Android = new("Android", 1);
    public static readonly DevicePlatform Ios = new("Ios", 2);

    private DevicePlatform(string name, int value) : base(name, value) { }

    public static DevicePlatform? TryParse(string? name) =>
        name is not null && TryFromName(name, ignoreCase: true, out var platform) ? platform : null;
}

/// <summary>
/// One push registration token for one app install. The token — an FCM
/// registration token on both platforms, since iOS registers through the
/// Firebase SDK rather than raw APNs — is the natural key: an install has
/// exactly one, and when the same install signs into a different account the
/// row is reassigned, never duplicated.
/// </summary>
public sealed class DeviceToken : Entity
{
    public Guid UserId { get; private set; }
    public string Token { get; private set; } = string.Empty;
    public DevicePlatform Platform { get; private set; } = DevicePlatform.Android;
    public DateTimeOffset CreatedAt { get; private set; }
    public DateTimeOffset LastRefreshedAt { get; private set; }

    private DeviceToken() { } // EF

    private DeviceToken(Guid userId, string token, DevicePlatform platform)
    {
        UserId = userId;
        Token = token;
        Platform = platform;
        CreatedAt = DateTimeOffset.UtcNow;
        LastRefreshedAt = CreatedAt;
    }

    public static Result<DeviceToken> Create(Guid userId, string token, DevicePlatform? platform)
    {
        if (string.IsNullOrWhiteSpace(token))
            return Result.Error(ValidationKeys.DeviceToken.TokenRequired);

        if (platform is null)
            return Result.Error(ValidationKeys.DeviceToken.PlatformInvalid);

        return new DeviceToken(userId, token.Trim(), platform);
    }

    public void Refresh(Guid userId, DevicePlatform platform)
    {
        UserId = userId;
        Platform = platform;
        LastRefreshedAt = DateTimeOffset.UtcNow;
    }
}

public interface IDeviceTokenRepository : IBaseRepository<DeviceToken>
{
    Task<DeviceToken?> GetByTokenAsync(string token, CancellationToken cancellationToken);

    /// <summary>Every registered (user, token) pair for the given users. The
    ///  fan-out's read side — one query, not one per recipient.</summary>
    Task<List<DeviceTokenTarget>> GetForUsersAsync(
        IReadOnlyCollection<Guid> userIds,
        CancellationToken cancellationToken);

    Task DeleteByTokensAsync(IReadOnlyCollection<string> tokens, CancellationToken cancellationToken);
}

public readonly record struct DeviceTokenTarget(Guid UserId, string Token);
