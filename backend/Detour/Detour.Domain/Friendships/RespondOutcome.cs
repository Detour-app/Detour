using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

/// <summary>
/// What answering a pending request did.
///
/// Deliberately not a <see cref="FriendshipStatus"/> member: declining deletes the row, so
/// "declined" is the absence of a friendship rather than a state of one, and widening that
/// enum would make it queryable when no row exists. This type reports what just happened to
/// the caller and persists nothing. #139 covers making a decline durable.
/// </summary>
public sealed class RespondOutcome : SmartEnum<RespondOutcome>
{
    public static readonly RespondOutcome Accepted = new("Accepted", 1);
    public static readonly RespondOutcome Declined = new("Declined", 2);

    private RespondOutcome(string name, int value) : base(name, value) { }
}
