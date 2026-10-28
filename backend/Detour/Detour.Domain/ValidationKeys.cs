using JV.ResultUtilities.Extensions;
using JV.ResultUtilities.ValidationMessage;

namespace Detour.Domain;

public static class ValidationKeys
{
    public static class User
    {
        private const string Base = "User";

        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound")
                .WithGuidParameter("id");

        public static readonly ValidationKeyDefinition NotFoundByUsername =
            ValidationKeyDefinition.Create($"{Base}.NotFoundByUsername")
                .WithStringParameter("username");

        public static readonly ValidationKeyDefinition UsernameRequired =
            ValidationKeyDefinition.Create($"{Base}.UsernameRequired");

        public static readonly ValidationKeyDefinition UsernameInvalid =
            ValidationKeyDefinition.Create($"{Base}.UsernameInvalid");

        public static readonly ValidationKeyDefinition AlreadyExistsWithName =
            ValidationKeyDefinition.Create($"{Base}.AlreadyExistsWithName")
                .WithStringParameter("name");

        public static readonly ValidationKeyDefinition SubjectRequired =
            ValidationKeyDefinition.Create($"{Base}.SubjectRequired");

        public static readonly ValidationKeyDefinition EmailInvalid =
            ValidationKeyDefinition.Create($"{Base}.EmailInvalid");
    }

    public static class Badge
    {
        private const string Base = "Badge";

        public static readonly ValidationKeyDefinition IdInvalid =
            ValidationKeyDefinition.Create($"{Base}.IdInvalid")
                .WithStringParameter("id");

        public static readonly ValidationKeyDefinition EarnedAtInvalid =
            ValidationKeyDefinition.Create($"{Base}.EarnedAtInvalid");
    }

    public static class ProblemReport
    {
        private const string Base = "ProblemReport";

        public static readonly ValidationKeyDefinition KindInvalid =
            ValidationKeyDefinition.Create($"{Base}.KindInvalid");

        public static readonly ValidationKeyDefinition PlatformInvalid =
            ValidationKeyDefinition.Create($"{Base}.PlatformInvalid");

        public static readonly ValidationKeyDefinition DeviceRequired =
            ValidationKeyDefinition.Create($"{Base}.DeviceRequired");

        public static readonly ValidationKeyDefinition DeviceTooLong =
            ValidationKeyDefinition.Create($"{Base}.DeviceTooLong")
                .WithIntParameter("maxLength");

        public static readonly ValidationKeyDefinition OccurredAtInvalid =
            ValidationKeyDefinition.Create($"{Base}.OccurredAtInvalid");

        public static readonly ValidationKeyDefinition NoteTooLong =
            ValidationKeyDefinition.Create($"{Base}.NoteTooLong")
                .WithIntParameter("maxLength");

        public static readonly ValidationKeyDefinition StackTraceTooLarge =
            ValidationKeyDefinition.Create($"{Base}.StackTraceTooLarge")
                .WithIntParameter("maxBytes");

        public static readonly ValidationKeyDefinition EventsTooLarge =
            ValidationKeyDefinition.Create($"{Base}.EventsTooLarge")
                .WithIntParameter("maxBytes");

        public static readonly ValidationKeyDefinition FiledIssueInvalid =
            ValidationKeyDefinition.Create($"{Base}.FiledIssueInvalid");

        public static readonly ValidationKeyDefinition CursorUnknown =
            ValidationKeyDefinition.Create($"{Base}.CursorUnknown");
    }

    public static class Trip
    {
        private const string Base = "Trip";

        public static readonly ValidationKeyDefinition StartTimeRequired =
            ValidationKeyDefinition.Create($"{Base}.StartTimeRequired");

        public static readonly ValidationKeyDefinition PayloadRequired =
            ValidationKeyDefinition.Create($"{Base}.PayloadRequired");

        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound");
    }

    public static class TripTitle
    {
        private const string Base = "TripTitle";

        public static readonly ValidationKeyDefinition EditedAtInvalid =
            ValidationKeyDefinition.Create($"{Base}.EditedAtInvalid");

        public static readonly ValidationKeyDefinition TooLong =
            ValidationKeyDefinition.Create($"{Base}.TooLong")
                .WithIntParameter("maximum");
    }

    public static class Trace
    {
        private const string Base = "Trace";

        public static readonly ValidationKeyDefinition LineRequired =
            ValidationKeyDefinition.Create($"{Base}.LineRequired");

        public static readonly ValidationKeyDefinition LineInvalid =
            ValidationKeyDefinition.Create($"{Base}.LineInvalid");
    }

    public static class SavedPlace
    {
        private const string Base = "SavedPlace";

        public static readonly ValidationKeyDefinition IdRequired =
            ValidationKeyDefinition.Create($"{Base}.IdRequired");

        public static readonly ValidationKeyDefinition PayloadRequired =
            ValidationKeyDefinition.Create($"{Base}.PayloadRequired");
    }

    public static class SavedRoute
    {
        private const string Base = "SavedRoute";

        public static readonly ValidationKeyDefinition IdRequired =
            ValidationKeyDefinition.Create($"{Base}.IdRequired");

        public static readonly ValidationKeyDefinition PayloadRequired =
            ValidationKeyDefinition.Create($"{Base}.PayloadRequired");
    }

    public static class Friendship
    {
        private const string Base = "Friendship";

        public static readonly ValidationKeyDefinition CannotFriendYourself =
            ValidationKeyDefinition.Create($"{Base}.CannotFriendYourself");

        public static readonly ValidationKeyDefinition NoPendingRequest =
            ValidationKeyDefinition.Create($"{Base}.NoPendingRequest");

        public static readonly ValidationKeyDefinition CannotAcceptOwnRequest =
            ValidationKeyDefinition.Create($"{Base}.CannotAcceptOwnRequest");

        public static readonly ValidationKeyDefinition CannotDeclineOwnRequest =
            ValidationKeyDefinition.Create($"{Base}.CannotDeclineOwnRequest");

        public static readonly ValidationKeyDefinition NotFriends =
            ValidationKeyDefinition.Create($"{Base}.NotFriends");
    }

    public static class SharedRoute
    {
        private const string Base = "SharedRoute";

        public static readonly ValidationKeyDefinition CannotShareWithYourself =
            ValidationKeyDefinition.Create($"{Base}.CannotShareWithYourself");

        public static readonly ValidationKeyDefinition RecipientNotAFriend =
            ValidationKeyDefinition.Create($"{Base}.RecipientNotAFriend");

        public static readonly ValidationKeyDefinition RouteIdRequired =
            ValidationKeyDefinition.Create($"{Base}.RouteIdRequired");

        public static readonly ValidationKeyDefinition NotEnoughStops =
            ValidationKeyDefinition.Create($"{Base}.NotEnoughStops")
                .WithIntParameter("minimum");

        public static readonly ValidationKeyDefinition PayloadTooLarge =
            ValidationKeyDefinition.Create($"{Base}.PayloadTooLarge")
                .WithIntParameter("maxBytes");

        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound")
                .WithGuidParameter("id");
    }

    public static class PlannedRide
    {
        private const string Base = "PlannedRide";

        public static readonly ValidationKeyDefinition TitleRequired =
            ValidationKeyDefinition.Create($"{Base}.TitleRequired");

        public static readonly ValidationKeyDefinition TitleTooLong =
            ValidationKeyDefinition.Create($"{Base}.TitleTooLong")
                .WithIntParameter("maxLength");

        public static readonly ValidationKeyDefinition MeetingLabelTooLong =
            ValidationKeyDefinition.Create($"{Base}.MeetingLabelTooLong")
                .WithIntParameter("maxLength");

        public static readonly ValidationKeyDefinition DescriptionTooLong =
            ValidationKeyDefinition.Create($"{Base}.DescriptionTooLong")
                .WithIntParameter("maxLength");

        public static readonly ValidationKeyDefinition RouteIdInvalid =
            ValidationKeyDefinition.Create($"{Base}.RouteIdInvalid");

        public static readonly ValidationKeyDefinition RouteNotEnoughStops =
            ValidationKeyDefinition.Create($"{Base}.RouteNotEnoughStops")
                .WithIntParameter("minimum");

        public static readonly ValidationKeyDefinition RoutePayloadTooLarge =
            ValidationKeyDefinition.Create($"{Base}.RoutePayloadTooLarge")
                .WithIntParameter("maxBytes");

        public static readonly ValidationKeyDefinition AnswerInvalid =
            ValidationKeyDefinition.Create($"{Base}.AnswerInvalid");

        /// <summary>
        /// Also returned when the ride does not exist at all. "No such ride" and "not invited"
        /// must be indistinguishable, or ride ids can be enumerated.
        /// </summary>
        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound");

        /// <summary>An invitee trying to edit, delete or invite. They can see the ride, so this
        /// leaks nothing a 404 would hide.</summary>
        public static readonly ValidationKeyDefinition NotTheOwner =
            ValidationKeyDefinition.Create($"{Base}.NotTheOwner");

        public static readonly ValidationKeyDefinition InviteeNotAFriendOrCircleMember =
            ValidationKeyDefinition.Create($"{Base}.InviteeNotAFriendOrCircleMember");

        public static readonly ValidationKeyDefinition NotInvited =
            ValidationKeyDefinition.Create($"{Base}.NotInvited");
    }

    public static class Group
    {
        private const string Base = "Group";

        public static readonly ValidationKeyDefinition NameRequired =
            ValidationKeyDefinition.Create($"{Base}.NameRequired");

        public static readonly ValidationKeyDefinition NameTooLong =
            ValidationKeyDefinition.Create($"{Base}.NameTooLong")
                .WithIntParameter("maxLength");

        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound")
                .WithGuidParameter("id");

        /// <summary>
        /// Deliberately also returned when the group does not exist at all. "No such group" and
        /// "not a member" must be indistinguishable, or group ids can be enumerated.
        /// </summary>
        public static readonly ValidationKeyDefinition NotAMember =
            ValidationKeyDefinition.Create($"{Base}.NotAMember");

        public static readonly ValidationKeyDefinition AlreadyAMember =
            ValidationKeyDefinition.Create($"{Base}.AlreadyAMember");

        public static readonly ValidationKeyDefinition InviteeNotAFriend =
            ValidationKeyDefinition.Create($"{Base}.InviteeNotAFriend");

        public static readonly ValidationKeyDefinition NoPendingInvite =
            ValidationKeyDefinition.Create($"{Base}.NoPendingInvite");

        public static readonly ValidationKeyDefinition CircleFull =
            ValidationKeyDefinition.Create($"{Base}.CircleFull")
                .WithIntParameter("maxMembers");

        public static readonly ValidationKeyDefinition NotACircle =
            ValidationKeyDefinition.Create($"{Base}.NotACircle");

        public static readonly ValidationKeyDefinition NotAConvoy =
            ValidationKeyDefinition.Create($"{Base}.NotAConvoy");

        public static readonly ValidationKeyDefinition NotTheHost =
            ValidationKeyDefinition.Create($"{Base}.NotTheHost");
    }

    public static class ConvoyInvite
    {
        private const string Base = "ConvoyInvite";

        /// <summary>No link hashes to the token presented.</summary>
        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound");

        /// <summary>The link existed but was revoked or ran past its expiry.</summary>
        public static readonly ValidationKeyDefinition Expired =
            ValidationKeyDefinition.Create($"{Base}.Expired");

        /// <summary>The host removed this rider; the same link will not let them back in.</summary>
        public static readonly ValidationKeyDefinition GuestRemoved =
            ValidationKeyDefinition.Create($"{Base}.GuestRemoved");

        /// <summary>The convoy already holds <see cref="DetourLimits.MaxConvoyGuests"/> link guests.</summary>
        public static readonly ValidationKeyDefinition ConvoyFull =
            ValidationKeyDefinition.Create($"{Base}.ConvoyFull")
                .WithIntParameter("maxGuests");

        public static readonly ValidationKeyDefinition GuestNotFound =
            ValidationKeyDefinition.Create($"{Base}.GuestNotFound");

        public static readonly ValidationKeyDefinition TooManyAttempts =
            ValidationKeyDefinition.Create($"{Base}.TooManyAttempts");
    }

    public static class CirclePlace
    {
        private const string Base = "CirclePlace";

        public static readonly ValidationKeyDefinition PlaceIdRequired =
            ValidationKeyDefinition.Create($"{Base}.PlaceIdRequired");

        public static readonly ValidationKeyDefinition RadiusOutOfRange =
            ValidationKeyDefinition.Create($"{Base}.RadiusOutOfRange")
                .WithIntParameter("maxMeters");

        public static readonly ValidationKeyDefinition PayloadTooLarge =
            ValidationKeyDefinition.Create($"{Base}.PayloadTooLarge")
                .WithIntParameter("maxBytes");

        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound")
                .WithGuidParameter("id");
    }

    public static class PlaceEvent
    {
        private const string Base = "PlaceEvent";

        public static readonly ValidationKeyDefinition KindInvalid =
            ValidationKeyDefinition.Create($"{Base}.KindInvalid");
    }

    public static class Location
    {
        private const string Base = "Location";

        public static readonly ValidationKeyDefinition CoordinatesOutOfRange =
            ValidationKeyDefinition.Create($"{Base}.CoordinatesOutOfRange");

        public static readonly ValidationKeyDefinition AccuracyOutOfRange =
            ValidationKeyDefinition.Create($"{Base}.AccuracyOutOfRange");
    }

    public static class DeviceToken
    {
        private const string Base = "DeviceToken";

        public static readonly ValidationKeyDefinition TokenRequired =
            ValidationKeyDefinition.Create($"{Base}.TokenRequired");

        public static readonly ValidationKeyDefinition TokenInvalid =
            ValidationKeyDefinition.Create($"{Base}.TokenInvalid");

        public static readonly ValidationKeyDefinition PlatformInvalid =
            ValidationKeyDefinition.Create($"{Base}.PlatformInvalid");
    }

    public static class ApiKey
    {
        private const string Base = "ApiKey";

        public static readonly ValidationKeyDefinition NotFound =
            ValidationKeyDefinition.Create($"{Base}.NotFound")
                .WithGuidParameter("id");

        public static readonly ValidationKeyDefinition LabelTooLong =
            ValidationKeyDefinition.Create($"{Base}.LabelTooLong")
                .WithIntParameter("maxLength");
    }

    public static class Camera
    {
        private const string Base = "Camera";

        public static readonly ValidationKeyDefinition CoordinatesInvalid =
            ValidationKeyDefinition.Create($"{Base}.CoordinatesInvalid");

        public static readonly ValidationKeyDefinition PolylineTooShort =
            ValidationKeyDefinition.Create($"{Base}.PolylineTooShort");
    }

    public static class SpeedLimitWay
    {
        private const string Base = "SpeedLimitWay";

        public static readonly ValidationKeyDefinition CoordinatesInvalid =
            ValidationKeyDefinition.Create($"{Base}.CoordinatesInvalid");

        public static readonly ValidationKeyDefinition PolylineTooShort =
            ValidationKeyDefinition.Create($"{Base}.PolylineTooShort");
    }

    public static class RoadWay
    {
        private const string Base = "RoadWay";

        public static readonly ValidationKeyDefinition CoordinatesInvalid =
            ValidationKeyDefinition.Create($"{Base}.CoordinatesInvalid");

        public static readonly ValidationKeyDefinition PolylineTooShort =
            ValidationKeyDefinition.Create($"{Base}.PolylineTooShort");
    }

    public static class Poi
    {
        private const string Base = "Poi";

        public static readonly ValidationKeyDefinition CoordinatesInvalid =
            ValidationKeyDefinition.Create($"{Base}.CoordinatesInvalid");

        public static readonly ValidationKeyDefinition KindBlank =
            ValidationKeyDefinition.Create($"{Base}.KindBlank");
    }

    public static class MunicipalityBoundary
    {
        private const string Base = "MunicipalityBoundary";

        public static readonly ValidationKeyDefinition NameBlank =
            ValidationKeyDefinition.Create($"{Base}.NameBlank");

        public static readonly ValidationKeyDefinition RingsInvalid =
            ValidationKeyDefinition.Create($"{Base}.RingsInvalid");

        public static readonly ValidationKeyDefinition CoordinatesInvalid =
            ValidationKeyDefinition.Create($"{Base}.CoordinatesInvalid");
    }
}
