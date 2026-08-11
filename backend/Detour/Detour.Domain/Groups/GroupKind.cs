using Ardalis.SmartEnum;

namespace Detour.Domain.Groups;

/// <summary>
/// A convoy and a circle are the same entity with different lifetimes and different powers.
///
/// The behavioural form is deliberate: the three things that differ between them — whether the
/// group dies when the last member leaves, whether voice is allowed, whether pausing means
/// anything — are properties of the kind, not <c>if (kind == ...)</c> branches scattered across
/// handlers. The voice rule in particular is the highest-consequence line in the merge: a
/// circle must never gain always-on voice between people who signed up for a dot on a map.
/// </summary>
public abstract class GroupKind : SmartEnum<GroupKind>
{
    public static readonly GroupKind Convoy = new ConvoyKind();
    public static readonly GroupKind Circle = new CircleKind();

    private GroupKind(string name, int value) : base(name, value) { }

    /// <summary>A convoy with nobody left in it is dead weight; a circle persists.</summary>
    public abstract bool DropWhenEmpty { get; }

    /// <summary>Push-to-talk. Rejected server-side for anything but a convoy.</summary>
    public abstract bool AllowsVoice { get; }

    /// <summary>Shared destination offers and votes — a convoy deciding where to ride together.</summary>
    public abstract bool AllowsDestinationVote { get; }

    /// <summary>Pausing is per person per circle. A convoy connection <em>is</em> sharing.</summary>
    public abstract bool SupportsPause { get; }

    /// <summary>A circle keeps the latest fix per member; a convoy's position is relay-only.</summary>
    public abstract bool PersistsLastFix { get; }

    /// <summary>Null for a convoy, which has no size cap.</summary>
    public abstract int? MaxMembers { get; }

    private sealed class ConvoyKind : GroupKind
    {
        public ConvoyKind() : base("Convoy", 1) { }
        public override bool DropWhenEmpty => true;
        public override bool AllowsVoice => true;
        public override bool AllowsDestinationVote => true;
        public override bool SupportsPause => false;
        public override bool PersistsLastFix => false;
        public override int? MaxMembers => null;
    }

    private sealed class CircleKind : GroupKind
    {
        public CircleKind() : base("Circle", 2) { }
        public override bool DropWhenEmpty => false;
        public override bool AllowsVoice => false;
        public override bool AllowsDestinationVote => false;
        public override bool SupportsPause => true;
        public override bool PersistsLastFix => true;
        public override int? MaxMembers => DetourLimits.MaxCircleMembers;
    }
}
