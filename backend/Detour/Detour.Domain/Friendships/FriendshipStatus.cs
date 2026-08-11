using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

public sealed class FriendshipStatus : SmartEnum<FriendshipStatus>
{
    public static readonly FriendshipStatus Pending = new("Pending", 1);
    public static readonly FriendshipStatus Accepted = new("Accepted", 2);

    private FriendshipStatus(string name, int value) : base(name, value) { }
}
