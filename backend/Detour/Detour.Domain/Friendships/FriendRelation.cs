using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

/// <summary>
/// Which of the three sets a rider falls into on the caller's friend list.
///
/// An enum rather than the list a rider appears in: the response used to carry three
/// arrays and encode this by position, so nothing could type-check that a rider appeared
/// in exactly one. A member here is also where a future declined or blocked relation
/// (#139) lands, instead of a fourth array.
/// </summary>
public sealed class FriendRelation : SmartEnum<FriendRelation>
{
    public static readonly FriendRelation Friend = new("Friend", 1);
    public static readonly FriendRelation Incoming = new("Incoming", 2);
    public static readonly FriendRelation Outgoing = new("Outgoing", 3);

    private FriendRelation(string name, int value) : base(name, value) { }
}
