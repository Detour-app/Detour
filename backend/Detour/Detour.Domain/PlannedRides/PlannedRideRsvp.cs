using Ardalis.SmartEnum;
using JV.ResultUtilities;
using Shared.Domain;

namespace Detour.Domain.PlannedRides;

/// <summary>
/// <c>Pending</c> is "asked, not answered yet" — the state an invitation starts in. A rider can
/// only ever answer with one of the other three.
/// </summary>
public sealed class RsvpAnswer : SmartEnum<RsvpAnswer>
{
    public static readonly RsvpAnswer Pending = new("Pending", 1);
    public static readonly RsvpAnswer Going = new("Going", 2);
    public static readonly RsvpAnswer Maybe = new("Maybe", 3);
    public static readonly RsvpAnswer Declined = new("Declined", 4);

    private RsvpAnswer(string name, int value) : base(name, value) { }

    /// <summary>What a rider may send. <c>pending</c> is not an answer, so it does not parse.</summary>
    public static RsvpAnswer? TryParseAnswer(string? name) =>
        name is not null && TryFromName(name, ignoreCase: true, out var answer) && answer != Pending
            ? answer
            : null;
}

/// <summary>
/// One invitee's invitation to one planned ride, and their answer to it.
///
/// Declining keeps the row: the owner sees who said no, and the rider can change their mind
/// until the ride is deleted. The row goes only when the ride does, or when the friendship
/// between the two riders ends.
/// </summary>
public sealed class PlannedRideRsvp : Entity
{
    public Guid RideId { get; private set; }

    public Guid UserId { get; private set; }

    public RsvpAnswer Answer { get; private set; }

    public DateTimeOffset InvitedAt { get; private set; }

    public DateTimeOffset? AnsweredAt { get; private set; }

    private PlannedRideRsvp(Guid rideId, Guid userId)
    {
        RideId = rideId;
        UserId = userId;
        Answer = RsvpAnswer.Pending;
        InvitedAt = DateTimeOffset.UtcNow;
    }

    internal static PlannedRideRsvp CreateInvited(Guid rideId, Guid userId) => new(rideId, userId);

    public Result AnswerWith(RsvpAnswer answer)
    {
        if (answer == RsvpAnswer.Pending)
            return Result.Error(ValidationKeys.PlannedRide.AnswerInvalid);

        Answer = answer;
        AnsweredAt = DateTimeOffset.UtcNow;
        return Result.Ok();
    }

    // EF materialisation.
    private PlannedRideRsvp()
    {
        Answer = RsvpAnswer.Pending;
    }
}
