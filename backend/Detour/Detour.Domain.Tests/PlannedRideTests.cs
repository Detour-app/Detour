using Detour.Domain;
using Detour.Domain.PlannedRides;

namespace Detour.Domain.Tests;

public class PlannedRideTests
{
    private static PlannedRideDetails Details(
        string title = "Ardennes loop",
        double latitude = 50.4,
        double longitude = 5.9,
        string? label = "Fuel stop",
        long? routeId = 7,
        string? description = null) =>
        new(title, DateTimeOffset.UtcNow.AddDays(3), latitude, longitude, label, routeId, description);

    [Fact]
    public void A_ride_needs_a_title()
    {
        var (result, _) = PlannedRide.Create(Guid.CreateVersion7(), Details(title: "   "));

        result.HasError(ValidationKeys.PlannedRide.TitleRequired).Should().BeTrue();
    }

    [Fact]
    public void A_title_over_the_limit_is_refused()
    {
        var title = new string('x', DetourLimits.PlannedRideTitleMaxLength + 1);

        var (result, _) = PlannedRide.Create(Guid.CreateVersion7(), Details(title: title));

        result.HasError(ValidationKeys.PlannedRide.TitleTooLong).Should().BeTrue();
    }

    [Theory]
    [InlineData(91, 5)]
    [InlineData(50, 181)]
    [InlineData(double.NaN, 5)]
    public void A_meeting_point_off_the_map_is_refused(double latitude, double longitude)
    {
        var (result, _) = PlannedRide.Create(
            Guid.CreateVersion7(), Details(latitude: latitude, longitude: longitude));

        result.HasError(ValidationKeys.Location.CoordinatesOutOfRange).Should().BeTrue();
    }

    [Fact]
    public void Long_labels_and_descriptions_are_refused()
    {
        var label = new string('x', DetourLimits.LabelMaxLength + 1);
        var description = new string('x', DetourLimits.PlannedRideDescriptionMaxLength + 1);

        var (labelResult, _) = PlannedRide.Create(Guid.CreateVersion7(), Details(label: label));
        var (descriptionResult, _) = PlannedRide.Create(Guid.CreateVersion7(), Details(description: description));

        labelResult.HasError(ValidationKeys.PlannedRide.MeetingLabelTooLong).Should().BeTrue();
        descriptionResult.HasError(ValidationKeys.PlannedRide.DescriptionTooLong).Should().BeTrue();
    }

    [Fact]
    public void Blank_optional_fields_are_stored_as_absent()
    {
        var (_, ride) = PlannedRide.Create(Guid.CreateVersion7(), Details(label: "  ", description: ""));

        ride.MeetingLabel.Should().BeNull();
        ride.Description.Should().BeNull();
    }

    [Fact]
    public void An_attached_route_is_stored_and_an_edit_without_one_detaches_it()
    {
        var (_, ride) = PlannedRide.Create(
            Guid.CreateVersion7(), Details() with { Route = new PlannedRideRoute("{\"id\":7}", 2) });

        ride.RoutePayload.Should().Be("{\"id\":7}");
        ride.Update(Details()).IsFailure.Should().BeFalse();
        ride.RoutePayload.Should().BeNull();
    }

    [Fact]
    public void A_route_needs_two_stops_and_fits_the_shared_route_cap()
    {
        var oneStop = new PlannedRideRoute("{}", 1);
        var tooLarge = new PlannedRideRoute(new string('x', DetourLimits.MaxRoutePayloadBytes + 1), 2);

        var (stopsResult, _) = PlannedRide.Create(Guid.CreateVersion7(), Details() with { Route = oneStop });
        var (sizeResult, _) = PlannedRide.Create(Guid.CreateVersion7(), Details() with { Route = tooLarge });

        stopsResult.HasError(ValidationKeys.PlannedRide.RouteNotEnoughStops).Should().BeTrue();
        sizeResult.HasError(ValidationKeys.PlannedRide.RoutePayloadTooLarge).Should().BeTrue();
    }

    [Fact]
    public void The_owner_is_never_their_own_invitee()
    {
        var owner = Guid.CreateVersion7();
        var (_, ride) = PlannedRide.Create(owner, Details());

        ride.Invite(owner).Should().BeNull();
        ride.Rsvps.Should().BeEmpty();
        ride.IsVisibleTo(owner).Should().BeTrue();
    }

    [Fact]
    public void Inviting_again_keeps_the_answer()
    {
        var invitee = Guid.CreateVersion7();
        var (_, ride) = PlannedRide.Create(Guid.CreateVersion7(), Details());
        ride.Invite(invitee)!.AnswerWith(RsvpAnswer.Going);

        ride.Invite(invitee);

        ride.Rsvps.Should().ContainSingle().Which.Answer.Should().Be(RsvpAnswer.Going);
    }

    [Fact]
    public void Only_the_owner_and_invitees_can_see_a_ride()
    {
        var invitee = Guid.CreateVersion7();
        var (_, ride) = PlannedRide.Create(Guid.CreateVersion7(), Details());
        ride.Invite(invitee);

        ride.IsVisibleTo(invitee).Should().BeTrue();
        ride.IsVisibleTo(Guid.CreateVersion7()).Should().BeFalse();
    }

    [Fact]
    public void Pending_is_not_an_answer()
    {
        var (_, ride) = PlannedRide.Create(Guid.CreateVersion7(), Details());
        var rsvp = ride.Invite(Guid.CreateVersion7())!;

        rsvp.AnswerWith(RsvpAnswer.Pending).HasError(ValidationKeys.PlannedRide.AnswerInvalid)
            .Should().BeTrue();
        RsvpAnswer.TryParseAnswer("pending").Should().BeNull();
        RsvpAnswer.TryParseAnswer("Going").Should().Be(RsvpAnswer.Going);
    }

    [Fact]
    public void A_declined_rider_can_change_their_mind()
    {
        var (_, ride) = PlannedRide.Create(Guid.CreateVersion7(), Details());
        var rsvp = ride.Invite(Guid.CreateVersion7())!;

        rsvp.AnswerWith(RsvpAnswer.Declined);
        rsvp.AnswerWith(RsvpAnswer.Maybe);

        rsvp.Answer.Should().Be(RsvpAnswer.Maybe);
        rsvp.AnsweredAt.Should().NotBeNull();
    }
}
