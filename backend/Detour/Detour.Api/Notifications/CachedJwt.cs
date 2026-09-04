namespace Detour.Api.Notifications;

/// <summary>
/// Mints a bearer token on demand and reuses it until it ages past <c>ttl</c>.
/// APNs is the one gateway that hand-signs its own JWT (ES256 over the <c>.p8</c>),
/// and Apple asks that a provider token be refreshed no more than once every 20
/// minutes while staying valid for up to an hour — so a cache with a TTL between
/// those bounds is not an optimisation but the documented contract.
///
/// FCM does not use this: its bearer comes from Google's own credential library,
/// which caches the OAuth2 access token internally.
///
/// <paramref name="now"/> is injectable so the TTL boundary is unit-testable
/// without waiting out real minutes.
/// </summary>
public sealed class CachedJwt(Func<string> mint, TimeSpan ttl, Func<DateTimeOffset>? now = null)
{
    private readonly Func<DateTimeOffset> _now = now ?? (() => DateTimeOffset.UtcNow);
    private readonly Lock _gate = new();
    private string? _token;
    private DateTimeOffset _mintedAt;

    public string Current
    {
        get
        {
            lock (_gate)
            {
                if (_token is null || _now() - _mintedAt >= ttl)
                {
                    _token = mint();
                    _mintedAt = _now();
                }

                return _token;
            }
        }
    }
}
