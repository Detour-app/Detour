namespace Detour.Api.Authentication;

/// <summary>
/// The claims this backend reads off a token, and the schemes it accepts.
///
/// Named here rather than inline so a rename is a compile error instead of a silently
/// unauthenticated request.
/// </summary>
public static class DetourClaims
{
    /// <summary>The identity provider's stable subject. The only link to a local account.</summary>
    public const string Subject = "sub";

    /// <summary>Keycloak's handle claim. Becomes the local username on first sign-in.</summary>
    public const string PreferredUsername = "preferred_username";

    public const string Email = "email";

    /// <summary>Keycloak nests realm roles one level down, under <c>realm_access.roles</c>.</summary>
    public const string RealmAccess = "realm_access";
}

public static class DetourAuthenticationSchemes
{
    /// <summary>Rider sessions. The default for everything the app calls.</summary>
    public const string Bearer = "Bearer";

    /// <summary>
    /// Read-only dashboard keys. A separate scheme, not a second kind of bearer token, so that
    /// "this credential can only read" is a property of how the request authenticated rather
    /// than something every handler has to re-check.
    /// </summary>
    public const string ApiKey = "ApiKey";
}
