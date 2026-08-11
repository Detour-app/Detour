namespace Detour.Domain;

/// <summary>
/// Every cap the product enforces, in one place.
///
/// These are behaviour, not tuning knobs: each one exists because without it a single client
/// can grow someone else's data without bound. They are carried over from the sync server they
/// replace, so changing one is a product decision — the entity that enforces it, the EF
/// configuration that sizes the column, and the test that pins it all read from here.
/// </summary>
public static class DetourLimits
{
    // --- accounts -----------------------------------------------------------------------
    public const int UsernameMinLength = 3;
    public const int UsernameMaxLength = 24;

    /// <summary>Letters, digits, dot, underscore, hyphen — the legacy handle alphabet.</summary>
    public const string UsernamePattern = "^[A-Za-z0-9_.-]{3,24}$";

    public const int EmailMaxLength = 254;

    /// <summary>Keycloak subject identifiers are UUIDs today; sized for an opaque issuer id.</summary>
    public const int SubjectMaxLength = 128;

    // --- badges -------------------------------------------------------------------------
    public const int MaxBadgesPerUser = 200;
    public const int BadgeIdMaxLength = 40;

    /// <summary>A lowercase family, an underscore, and the tier threshold — e.g. <c>dist_100000</c>.</summary>
    public const string BadgeIdPattern = "^[a-z]+_[0-9]+$";

    // --- groups -------------------------------------------------------------------------
    public const int GroupNameMinLength = 1;
    public const int GroupNameMaxLength = 40;

    /// <summary>
    /// Circles only. The framing is family and housemates; capped before circle fan-out cost
    /// (or a device's geofence budget) becomes a problem worth measuring instead of deciding.
    /// </summary>
    public const int MaxCircleMembers = 15;

    // --- shared routes ------------------------------------------------------------------
    /// <summary>Generous for a polyline and its stops, and nowhere near the request body cap.</summary>
    public const int MaxRoutePayloadBytes = 512 * 1024;

    public const int MinRouteStops = 2;

    /// <summary>
    /// A write cap, not a display cap: without it the inbox limit would only hide the excess
    /// while the table kept growing. Scoped per (recipient, sender) pair rather than per
    /// recipient, so a friend who shares constantly can only push out their own older shares.
    /// </summary>
    public const int MaxSharedRoutesPerPair = 50;

    public const int RouteInboxLimit = 100;

    // --- circle places ------------------------------------------------------------------
    /// <summary>A name, a point and a radius — far smaller than a route's polyline.</summary>
    public const int MaxPlacePayloadBytes = 64 * 1024;

    public const double MinPlaceRadiusMeters = 0;
    public const double MaxPlaceRadiusMeters = 50_000;
    public const int MaxCirclePlacesPerOwner = 50;

    // --- presence events ----------------------------------------------------------------
    /// <summary>Newest-N retention per circle, so one chatty member cannot grow the feed.</summary>
    public const int MaxPlaceEventsPerGroup = 500;

    // --- shared payload fields -----------------------------------------------------------
    public const int DisplayNameMaxLength = 200;
    public const int LabelMaxLength = 60;

    // --- transport ----------------------------------------------------------------------
    /// <summary>
    /// One sync upload carries a device's whole trip and trace history — megabytes after a year
    /// of riding, and it compresses roughly ten to one, so the app always gzips it.
    ///
    /// The same number bounds the body both ways: what may arrive on the wire, and what a
    /// gzipped body is allowed to expand to. A compression bomb is small enough to pass the
    /// first check, which is why the second one exists.
    /// </summary>
    public const long MaxRequestBodyBytes = 64L * 1024 * 1024;

    // --- live relay ---------------------------------------------------------------------
    /// <summary>
    /// A 40 ms 16 kHz mono PCM16 chunk is roughly 1.7 KB base64'd; this bounds worst-case abuse
    /// from a broken or hostile client, generously.
    /// </summary>
    public const int MaxVoiceChunkBase64Length = 20_000;

    /// <summary>The candidate sheet only ever shows three.</summary>
    public const int MaxDestinationCandidates = 3;

    public const int DestinationNameMaxLength = 80;

    // --- dashboard ----------------------------------------------------------------------
    public const int MaxDashboardRides = 500;
    public const int MaxTraceThinning = 50;
}
