using Ardalis.SmartEnum;

namespace Detour.Domain.Groups;

public sealed class GroupMemberStatus : SmartEnum<GroupMemberStatus>
{
    public static readonly GroupMemberStatus Invited = new("Invited", 1);
    public static readonly GroupMemberStatus Accepted = new("Accepted", 2);

    private GroupMemberStatus(string name, int value) : base(name, value) { }
}
