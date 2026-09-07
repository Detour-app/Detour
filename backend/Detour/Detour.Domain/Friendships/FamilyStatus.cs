using Ardalis.SmartEnum;

namespace Detour.Domain.Friendships;

/// <summary>
/// The family tier layered on top of an accepted <see cref="Friendship"/>. Symmetric and
/// consented, like the friendship itself: one rider asks, the other agrees. <see cref="None"/>
/// is the default a friendship carries until someone asks; <see cref="Accepted"/> is what the
/// home-coordinate sharing rule reads.
/// </summary>
public sealed class FamilyStatus : SmartEnum<FamilyStatus>
{
    public static readonly FamilyStatus None = new("None", 1);
    public static readonly FamilyStatus Pending = new("Pending", 2);
    public static readonly FamilyStatus Accepted = new("Accepted", 3);

    private FamilyStatus(string name, int value) : base(name, value) { }
}
