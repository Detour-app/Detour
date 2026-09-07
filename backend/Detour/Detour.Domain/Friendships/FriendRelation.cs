using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

/// <summary>
/// Which set a rider falls into on the caller's friend list.
///
/// An enum rather than the list a rider appears in: the response used to carry three
/// arrays and encode this by position, so nothing could type-check that a rider appeared
/// in exactly one.
///
/// <see cref="Declined"/> (#139) is asymmetric: it is the relation the decliner sees for
/// the other rider, never the other way round — the rider who was declined sees nothing
/// for that pair at all, so a repeat request cannot be told apart from a stranger who
/// never asked.
/// </summary>
public sealed class FriendRelation : SmartEnum<FriendRelation>
{
    public static readonly FriendRelation Friend = new("Friend", 1);
    public static readonly FriendRelation Incoming = new("Incoming", 2);
    public static readonly FriendRelation Outgoing = new("Outgoing", 3);
    public static readonly FriendRelation Declined = new("Declined", 4);

    private FriendRelation(string name, int value) : base(name, value) { }
}
