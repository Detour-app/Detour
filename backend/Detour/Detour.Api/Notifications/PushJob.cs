namespace Detour.Api.Notifications;

/// <summary>One event's worth of wake-pings: the recipients who were not holding a live
///  socket when it happened, and what the ping says.</summary>
public sealed record PushJob(IReadOnlyCollection<Guid> RecipientUserIds, PushWake Wake);
