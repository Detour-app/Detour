namespace Detour.Api.Notifications;

/// <summary>One circle event's worth of wake-pings: the recipients who were not
///  holding a live socket when it was recorded, and the circle id to collapse on.</summary>
public sealed record PushJob(IReadOnlyCollection<Guid> RecipientUserIds, string CollapseKey);
