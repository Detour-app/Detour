using Detour.Domain.Notifications;

namespace Detour.Api.Notifications;

public readonly record struct PushTokenOutcome(string Token, bool Delivered, bool ShouldPrune);

public sealed record PushSendResult(IReadOnlyList<PushTokenOutcome> Outcomes)
{
    public static readonly PushSendResult Empty = new([]);

    public IEnumerable<string> TokensToPrune =>
        Outcomes.Where(o => o.ShouldPrune).Select(o => o.Token);
}

/// <summary>
/// One cloud's content-free wake-ping to a batch of tokens, all of one platform.
/// The payload carries no user data — the device fetches the event itself once
/// woken (see <c>docs/PUSH.md</c> §2).
///
/// There are two real implementations, one per platform: <see cref="FcmGateway"/>
/// for Android (RS256 → Google OAuth2 → <c>messages:send</c>) and
/// <see cref="ApnsGateway"/> for iOS (ES256 → <c>/3/device</c>). The dispatcher
/// picks by <see cref="DevicePlatform"/> — this is the seam that keeps each
/// gateway 1:1 with the API its vendor documents, so an iOS failure reads Apple's
/// own error rather than Google's relay of it.
/// </summary>
public interface IPushGateway
{
    /// <summary>The platform whose tokens this gateway sends to. The dispatcher
    ///  routes a batch to the gateway whose <see cref="Platform"/> matches.</summary>
    DevicePlatform Platform { get; }

    /// <summary>Wake <paramref name="tokens"/> (all of <see cref="Platform"/>).
    ///  Returns per-token outcomes; <see cref="PushSendResult.TokensToPrune"/> are
    ///  the tokens the cloud reported permanently dead.</summary>
    Task<PushSendResult> SendWakeAsync(
        IReadOnlyCollection<string> tokens,
        string collapseKey,
        CancellationToken cancellationToken);
}
