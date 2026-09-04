namespace Detour.Api.Notifications;

public readonly record struct FcmTokenOutcome(string Token, bool Delivered, bool ShouldPrune);

public sealed record FcmSendResult(IReadOnlyList<FcmTokenOutcome> Outcomes)
{
    public static readonly FcmSendResult Empty = new([]);

    public IEnumerable<string> TokensToPrune =>
        Outcomes.Where(o => o.ShouldPrune).Select(o => o.Token);
}

/// <summary>
/// The one call the backend makes to Firebase Cloud Messaging: a content-free
/// wake-ping to a batch of tokens. The payload carries no user data — the
/// device fetches the event itself once woken (spec §Q2). iOS tokens are FCM
/// tokens too; Firebase relays them to APNs (spec approach A).
/// </summary>
public interface IFcmGateway
{
    Task<FcmSendResult> SendWakeAsync(
        IReadOnlyCollection<string> tokens,
        string collapseKey,
        CancellationToken cancellationToken);
}
