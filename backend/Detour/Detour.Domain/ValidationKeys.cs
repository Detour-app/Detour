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

    public static class Friendship
    {
        private const string Base = "Friendship";

        public static readonly ValidationKeyDefinition CannotFriendYourself =
            ValidationKeyDefinition.Create($"{Base}.CannotFriendYourself");

        public static readonly ValidationKeyDefinition NoPendingRequest =
            ValidationKeyDefinition.Create($"{Base}.NoPendingRequest");

        public static readonly ValidationKeyDefinition CannotAcceptOwnRequest =
            ValidationKeyDefinition.Create($"{Base}.CannotAcceptOwnRequest");

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

    public static class Location
    {
        private const string Base = "Location";

        public static readonly ValidationKeyDefinition CoordinatesOutOfRange =
            ValidationKeyDefinition.Create($"{Base}.CoordinatesOutOfRange");

        public static readonly ValidationKeyDefinition AccuracyOutOfRange =
            ValidationKeyDefinition.Create($"{Base}.AccuracyOutOfRange");
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
}
