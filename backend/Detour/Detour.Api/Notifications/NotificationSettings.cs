namespace Detour.Api.Notifications;

public sealed class NotificationSettings
{
    public const string SectionName = "Notifications";

    /// <summary>Absolute path to the Firebase service-account JSON. Null / empty
    ///  in every environment that has not been given one — the gateway then
    ///  no-ops, which is the correct state for a backend that ships before its
    ///  Firebase project exists.</summary>
    public string? FirebaseCredentialsPath { get; init; }

    /// <summary>Bounded — a wake-ping is self-healing, so a full queue drops
    ///  rather than grows.</summary>
    public int QueueCapacity { get; init; } = 1024;
}
