using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

/// <summary>
/// What answering a pending request did, as reported back to the caller who just answered
/// it. Separate from <see cref="FriendshipStatus"/> — that type is the row's durable state,
/// including <c>Declined</c>; this one is a one-off response shape and persists nothing.
/// </summary>
public sealed class RespondOutcome : SmartEnum<RespondOutcome>
{
    public static readonly RespondOutcome Accepted = new("Accepted", 1);
    public static readonly RespondOutcome Declined = new("Declined", 2);

    private RespondOutcome(string name, int value) : base(name, value) { }
}
