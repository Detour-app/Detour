namespace Detour.Api.Notifications;

public sealed class NotificationSettings
{
    public const string SectionName = "Notifications";

    // --- FCM (Android) ---

    /// <summary>Absolute path to the Firebase service-account JSON. The gateway
    ///  mints its OAuth2 bearer from this and reads the project id out of it. Null /
    ///  empty in any environment that has not been given one — FCM then no-ops,
    ///  which is the correct state for a backend that ships before its Firebase
    ///  project exists.</summary>
    public string? FirebaseCredentialsPath { get; init; }

    // --- APNs (iOS) ---

    /// <summary>Absolute path to the APNs auth key (<c>.p8</c>, an EC P-256 private
    ///  key). With this and the three ids below set, iOS tokens go straight to APNs;
    ///  any of them missing disables the iOS path (it no-ops).</summary>
    public string? ApnsKeyPath { get; init; }

    /// <summary>The Key ID of the <c>.p8</c> (Apple Developer → Keys). Becomes the
    ///  JWT header <c>kid</c>.</summary>
    public string? ApnsKeyId { get; init; }

    /// <summary>The Apple Developer Team ID. Becomes the JWT claim <c>iss</c>.</summary>
    public string? ApnsTeamId { get; init; }

    /// <summary>The app's bundle id, sent as <c>apns-topic</c>.</summary>
    public string? ApnsTopic { get; init; }

    /// <summary>Send to Apple's sandbox host instead of production. Development
    ///  builds get their tokens from the sandbox APNs environment; a token and host
    ///  from different environments is rejected as <c>BadDeviceToken</c>.</summary>
    public bool ApnsUseSandbox { get; init; }

    // --- shared ---

    /// <summary>Bounded — a wake-ping is self-healing, so a full queue drops
    ///  rather than grows.</summary>
    public int QueueCapacity { get; init; } = 1024;
}
