using Detour.Domain;
using Detour.Domain.Notifications;
using JV.ResultUtilities;

namespace Detour.Api.Services;

public interface IDeviceService
{
    Task<Result> RegisterAsync(Guid userId, string token, string platform, CancellationToken cancellationToken);
    Task RemoveAsync(string token, CancellationToken cancellationToken);
}

public class DeviceService(IDeviceTokenRepository tokens) : IDeviceService
{
    public async Task<Result> RegisterAsync(
        Guid userId, string token, string platform, CancellationToken cancellationToken)
    {
        var parsedPlatform = DevicePlatform.TryParse(platform);
        if (parsedPlatform is null)
            return Result.Error(ValidationKeys.DeviceToken.PlatformInvalid);

        // Create() only validates and trims here - the object itself is
        // discarded in favour of UpsertAsync's atomic insert-or-reassign,
        // which is what closes the race a read-then-write had against the
        // unique index on token (#149).
        var created = DeviceToken.Create(userId, token, parsedPlatform);
        if (created.IsFailure)
            return Result.Error(created.ValidationMessages);

        await tokens.UpsertAsync(userId, created.Value.Token, parsedPlatform, cancellationToken);
        return Result.Ok();
    }

    public Task RemoveAsync(string token, CancellationToken cancellationToken) =>
        tokens.DeleteByTokensAsync([token.Trim()], cancellationToken);
}
