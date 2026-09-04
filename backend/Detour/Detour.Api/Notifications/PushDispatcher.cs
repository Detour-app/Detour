using Detour.Domain.Notifications;

namespace Detour.Api.Notifications;

/// <summary>
/// Turns one <see cref="PushJob"/> into wake-pings: look up every token for the
/// recipients, route each to the gateway for its platform (FCM for Android, APNs
/// for iOS), and prune whatever those gateways report dead. Pure orchestration —
/// no queue, no hosted-service lifecycle — so it is unit-tested directly against
/// fake gateways.
/// </summary>
public sealed class PushDispatcher(
    IDeviceTokenRepository tokens,
    IEnumerable<IPushGateway> gateways,
    ILogger<PushDispatcher> logger)
{
    private readonly IReadOnlyDictionary<DevicePlatform, IPushGateway> _byPlatform =
        gateways.ToDictionary(g => g.Platform);

    public async Task DispatchAsync(PushJob job, CancellationToken cancellationToken)
    {
        if (job.RecipientUserIds.Count == 0)
            return;

        var targets = await tokens.GetForUsersAsync(job.RecipientUserIds, cancellationToken);
        if (targets.Count == 0)
            return;

        // One gateway per platform, each sent only its own tokens. A platform with
        // no registered gateway (e.g. APNs unconfigured) is logged and skipped, not
        // fatal — the other platform's devices still wake.
        var toPrune = new List<string>();
        foreach (var group in targets.GroupBy(t => t.Platform))
        {
            if (!_byPlatform.TryGetValue(group.Key, out var gateway))
            {
                logger.LogWarning(
                    "No push gateway for platform {Platform}; {Count} token(s) unreachable",
                    group.Key.Name, group.Count());
                continue;
            }

            var platformTokens = group.Select(t => t.Token).Distinct().ToArray();
            var result = await gateway.SendWakeAsync(platformTokens, job.CollapseKey, cancellationToken);
            toPrune.AddRange(result.TokensToPrune);
        }

        if (toPrune.Count > 0)
        {
            logger.LogInformation("Pruning {Count} dead push token(s)", toPrune.Count);
            await tokens.DeleteByTokensAsync(toPrune, cancellationToken);
        }
    }
}
