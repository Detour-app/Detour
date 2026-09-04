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

        var existing = await tokens.GetByTokenAsync(token.Trim(), cancellationToken);
        if (existing is not null)
        {
            existing.Refresh(userId, parsedPlatform);
            await tokens.FlushChangesAsync(cancellationToken);
            return Result.Ok();
        }

        var created = DeviceToken.Create(userId, token, parsedPlatform);
        if (created.IsFailure)
            return Result.Error(created.ValidationMessages);

        await tokens.SaveAsync(created.Value, cancellationToken);
        await tokens.FlushChangesAsync(cancellationToken);
        return Result.Ok();
    }

    public Task RemoveAsync(string token, CancellationToken cancellationToken) =>
        tokens.DeleteByTokensAsync([token.Trim()], cancellationToken);
}
