using System.Text;
using Detour.Domain;
using Detour.Domain.ApiKeys;
using Detour.Domain.Circles;
using Detour.Domain.Groups;
using Detour.Domain.Routes;
using Detour.Domain.Traces;
using Detour.Domain.Users;

namespace Detour.Domain.Tests;

public class SharedRouteTests
{
    [Fact]
    public void A_route_needs_at_least_two_stops()
    {
        var (result, _) = SharedRoute.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 42, "Coast road", "{}", stopCount: 1);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.SharedRoute.NotEnoughStops).Should().BeTrue();
    }

    [Fact]
    public void A_route_cannot_be_shared_with_yourself()
    {
        var me = Guid.CreateVersion7();

        var (result, _) = SharedRoute.Create(me, me, 42, "Coast road", "{}", stopCount: 3);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.SharedRoute.CannotShareWithYourself).Should().BeTrue();
    }

    [Fact]
    public void An_oversized_payload_is_refused_at_the_write_not_the_read()
    {
        var payload = new string('x', DetourLimits.MaxRoutePayloadBytes + 1);

        var (result, _) = SharedRoute.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 42, "Big", payload, stopCount: 3);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.SharedRoute.PayloadTooLarge).Should().BeTrue();
    }

    [Fact]
    public void A_blank_name_falls_back_rather_than_failing()
    {
        var (result, route) = SharedRoute.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 42, "   ", "{}", stopCount: 2);

        result.IsFailure.Should().BeFalse();
        route.Name.Should().Be("Route");
    }

    [Fact]
    public void Either_side_of_a_share_can_see_it()
    {
        var from = Guid.CreateVersion7();
        var to = Guid.CreateVersion7();
        var (_, route) = SharedRoute.Create(from, to, 42, "Coast road", "{}", stopCount: 2);

        route.IsVisibleTo(from).Should().BeTrue();
        route.IsVisibleTo(to).Should().BeTrue();
        route.IsVisibleTo(Guid.CreateVersion7()).Should().BeFalse();
    }
}

public class CirclePlaceTests
{
    [Theory]
    [InlineData(0)]
    [InlineData(-1)]
    [InlineData(DetourLimits.MaxPlaceRadiusMeters + 1)]
    [InlineData(double.NaN)]
    [InlineData(double.PositiveInfinity)]
    public void A_radius_outside_the_range_is_refused(double radius)
    {
        var (result, _) = CirclePlace.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 7, "School", radius, "{}");

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.CirclePlace.RadiusOutOfRange).Should().BeTrue();
    }

    [Fact]
    public void An_oversized_payload_is_refused()
    {
        var payload = new string('x', DetourLimits.MaxPlacePayloadBytes + 1);

        var (result, _) = CirclePlace.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 7, "School", 200, payload);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.CirclePlace.PayloadTooLarge).Should().BeTrue();
    }
}

public class PlaceEventTests
{
    [Fact]
    public void A_missing_kind_is_refused_rather_than_defaulted()
    {
        var (result, _) = PlaceEvent.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 7, kind: null, timestampMs: 1);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.PlaceEvent.KindInvalid).Should().BeTrue();
    }

    [Fact]
    public void A_missing_timestamp_falls_back_to_now()
    {
        var (result, placeEvent) = PlaceEvent.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 7, PlaceEventKind.Arrive, timestampMs: null);

        result.IsFailure.Should().BeFalse();
        placeEvent.TimestampMs.Should().BeGreaterThan(0);
    }
}

public class MemberFixTests
{
    [Theory]
    [InlineData(91, 0)]
    [InlineData(-91, 0)]
    [InlineData(0, 181)]
    [InlineData(double.NaN, 0)]
    [InlineData(0, double.PositiveInfinity)]
    public void An_unusable_coordinate_is_refused(double latitude, double longitude)
    {
        // A stored non-number breaks every map that later reads it back, so coordinates are
        // judged where they enter rather than where they are drawn.
        var (result, _) = MemberFix.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), latitude, longitude, null, 1);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Location.CoordinatesOutOfRange).Should().BeTrue();
    }

    [Fact]
    public void An_implausible_accuracy_is_dropped_and_the_position_kept()
    {
        var (result, fix) = MemberFix.Create(
            Guid.CreateVersion7(), Guid.CreateVersion7(), 51.05, 3.72, double.NaN, 1);

        result.IsFailure.Should().BeFalse();
        fix.AccuracyMeters.Should().BeNull();
        fix.Latitude.Should().Be(51.05);
    }
}

public class TrackPointTests
{
    [Fact]
    public void A_point_with_no_instant_is_dropped()
    {
        TrackPoint.TryCreate(Guid.CreateVersion7(), 0, 51.05, 3.72, null, null).Should().BeNull();
    }

    [Fact]
    public void A_point_outside_the_world_is_dropped()
    {
        TrackPoint.TryCreate(Guid.CreateVersion7(), 1, 91, 3.72, null, null).Should().BeNull();
    }

    [Fact]
    public void A_non_finite_reading_is_dropped_without_losing_the_point()
    {
        var point = TrackPoint.TryCreate(
            Guid.CreateVersion7(), 1, 51.05, 3.72, double.NaN, double.PositiveInfinity);

        point.Should().NotBeNull();
        point!.SpeedKmh.Should().BeNull();
        point.LeanDegrees.Should().BeNull();
    }
}

public class BadgeAwardTests
{
    [Fact]
    public void An_id_outside_the_family_underscore_threshold_shape_is_refused()
    {
        var (result, _) = BadgeAward.Create(Guid.CreateVersion7(), "Dist100000", 1);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Badge.IdInvalid).Should().BeTrue();
    }

    [Fact]
    public void The_earliest_instant_wins_so_a_reinstall_cannot_move_the_date_forward()
    {
        var (_, award) = BadgeAward.Create(Guid.CreateVersion7(), "dist_100000", 5_000);

        award.KeepEarliest(9_000).Should().BeFalse();
        award.EarnedAtMs.Should().Be(5_000);

        award.KeepEarliest(1_000).Should().BeTrue();
        award.EarnedAtMs.Should().Be(1_000);
    }
}

public class RiderStatsTests
{
    [Fact]
    public void Non_finite_numbers_never_reach_storage()
    {
        var sanitized = RiderStats.Sanitize(new RiderStats(
            double.PositiveInfinity, double.NaN, -5, double.NaN, -3, double.NegativeInfinity, -1));

        sanitized.TotalDistanceMeters.Should().Be(0);
        sanitized.TopSpeedKmh.Should().Be(0);
        sanitized.LongestTripMeters.Should().Be(0);
        sanitized.MaxLeanDegrees.Should().BeNull();
        sanitized.MunicipalitiesVisited.Should().Be(0);
        sanitized.BestCoveragePercent.Should().Be(0);
        sanitized.TripCount.Should().Be(0);
    }

    [Fact]
    public void A_null_lean_stays_null_because_never_measured_is_not_rode_upright()
    {
        RiderStats.Sanitize(RiderStats.Empty with { MaxLeanDegrees = null })
            .MaxLeanDegrees.Should().BeNull();
    }
}

public class TraceTests
{
    [Fact]
    public void The_same_line_always_hashes_the_same_so_a_re_upload_is_a_no_op()
    {
        var userId = Guid.CreateVersion7();
        const string line = "[[51.05,3.72,1000,50.0,12.5]]";

        var (_, first) = Trace.Create(userId, line);
        var (_, second) = Trace.Create(userId, "  " + line + "  ");

        second.LineHash.Should().Be(first.LineHash);
    }

    [Fact]
    public void A_blank_line_is_refused()
    {
        var (result, _) = Trace.Create(Guid.CreateVersion7(), "   ");

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.Trace.LineRequired).Should().BeTrue();
    }
}

public class ApiKeyTests
{
    [Fact]
    public void Issuing_returns_a_plaintext_that_is_never_stored()
    {
        var (result, issued) = ApiKey.Issue(Guid.CreateVersion7(), "home assistant");

        result.IsFailure.Should().BeFalse();
        issued.Plaintext.Should().NotBeNullOrWhiteSpace();
        issued.Key.KeyHash.Should().Be(ApiKey.HashOf(issued.Plaintext));
        issued.Key.KeyHash.Should().NotContain(issued.Plaintext);
    }

    [Fact]
    public void Two_keys_never_collide()
    {
        var userId = Guid.CreateVersion7();

        var (_, first) = ApiKey.Issue(userId, null);
        var (_, second) = ApiKey.Issue(userId, null);

        second.Plaintext.Should().NotBe(first.Plaintext);
    }

    [Fact]
    public void A_blank_label_falls_back_rather_than_failing()
    {
        var (result, issued) = ApiKey.Issue(Guid.CreateVersion7(), "  ");

        result.IsFailure.Should().BeFalse();
        issued.Key.Label.Should().Be("dashboard");
    }

    [Fact]
    public void An_oversized_label_is_refused()
    {
        var label = new string('x', DetourLimits.LabelMaxLength + 1);

        var (result, _) = ApiKey.Issue(Guid.CreateVersion7(), label);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.ApiKey.LabelTooLong).Should().BeTrue();
    }

    [Fact]
    public void Touch_is_throttled_so_a_polling_dashboard_does_not_cost_a_write_per_request()
    {
        var (_, issued) = ApiKey.Issue(Guid.CreateVersion7(), null);

        issued.Key.Touch().Should().BeTrue();
        issued.Key.Touch().Should().BeFalse();
    }
}

public class UserTests
{
    [Theory]
    [InlineData("ab")]
    [InlineData("this-name-is-far-too-long-to-fit")]
    [InlineData("has space")]
    [InlineData("bad!char")]
    public void An_invalid_handle_is_refused(string username)
    {
        var (result, _) = User.Create("subject-1", username, null);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.User.UsernameInvalid).Should().BeTrue();
    }

    [Fact]
    public void A_handle_with_the_allowed_punctuation_is_accepted()
    {
        var (result, user) = User.Create("subject-1", "max.ke_24-x", null);

        result.IsFailure.Should().BeFalse();
        user.Username.Should().Be("max.ke_24-x");
    }

    [Fact]
    public void Fog_sharing_is_off_by_default()
    {
        var (_, user) = User.Create("subject-1", "rider", null);

        user.ShareFog.Should().BeFalse();
    }

    [Fact]
    public void An_address_that_is_obviously_not_one_is_refused()
    {
        var (result, _) = User.Create("subject-1", "rider", "not-an-address");

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.User.EmailInvalid).Should().BeTrue();
    }

    [Fact]
    public void An_oversized_address_is_refused()
    {
        var email = new string('a', DetourLimits.EmailMaxLength) + "@example.com";

        var (result, _) = User.Create("subject-1", "rider", email);

        result.IsFailure.Should().BeTrue();
    }

    [Fact]
    public void A_missing_subject_is_refused_because_it_is_the_only_link_to_the_identity_provider()
    {
        var (result, _) = User.Create("  ", "rider", null);

        result.IsFailure.Should().BeTrue();
        result.HasError(ValidationKeys.User.SubjectRequired).Should().BeTrue();
    }
}
