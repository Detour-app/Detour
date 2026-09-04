namespace Detour.Api.Notifications;

/// <summary>
/// Turns one <see cref="PushJob"/> into FCM sends: look up every token for the
/// recipients, wake them, prune whatever the gateway reports dead. Pure
/// orchestration — no queue, no hosted-service lifecycle — so it is unit-tested
/// directly.
/// </summary>
public sealed class PushDispatcher(
    Detour.Domain.Notifications.IDeviceTokenRepository tokens,
    IFcmGateway gateway,
    ILogger<PushDispatcher> logger)
{
    public async Task DispatchAsync(PushJob job, CancellationToken cancellationToken)
    {
        if (job.RecipientUserIds.Count == 0)
            return;

        var targets = await tokens.GetForUsersAsync(job.RecipientUserIds, cancellationToken);
        if (targets.Count == 0)
            return;

        var tokenList = targets.Select(t => t.Token).Distinct().ToArray();
        var result = await gateway.SendWakeAsync(tokenList, job.CollapseKey, cancellationToken);

        var prune = result.TokensToPrune.ToArray();
        if (prune.Length > 0)
        {
            logger.LogInformation("Pruning {Count} dead push token(s)", prune.Length);
            await tokens.DeleteByTokensAsync(prune, cancellationToken);
        }
    }
}
