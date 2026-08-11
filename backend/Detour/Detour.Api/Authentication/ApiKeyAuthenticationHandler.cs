using System.Security.Claims;
using System.Text.Encodings.Web;
using Detour.Domain.ApiKeys;
using Microsoft.AspNetCore.Authentication;
using Microsoft.Extensions.Options;

namespace Detour.Api.Authentication;

public class ApiKeyAuthenticationOptions : AuthenticationSchemeOptions
{
    /// <summary>Header form, for a polling sensor that can send one.</summary>
    public const string HeaderName = "X-Api-Key";

    /// <summary>
    /// Query form. An embedded dashboard frame cannot set a header, so this has to work — and
    /// it is the reason these keys can only ever read.
    /// </summary>
    public const string QueryParameterName = "key";
}

/// <summary>
/// Authenticates a read-only dashboard key.
///
/// The credential is compared by hash: only the hash is stored, so a database leak hands over
/// nothing replayable. A key never grants anything but reads of its own owner's data, which is
/// enforced by the <c>dashboard</c> policy being the only one this scheme can satisfy.
/// </summary>
public class ApiKeyAuthenticationHandler(
    IOptionsMonitor<ApiKeyAuthenticationOptions> options,
    ILoggerFactory logger,
    UrlEncoder encoder,
    IApiKeyRepository apiKeys)
    : AuthenticationHandler<ApiKeyAuthenticationOptions>(options, logger, encoder)
{
    protected override async Task<AuthenticateResult> HandleAuthenticateAsync()
    {
        var presented = ReadKey();
        if (string.IsNullOrWhiteSpace(presented))
            return AuthenticateResult.NoResult();

        var key = await apiKeys.GetByHashAsync(ApiKey.HashOf(presented), Context.RequestAborted);
        if (key is null)
        {
            // Deliberately the same answer as a key that was never issued. Nothing here should
            // let a caller tell "revoked" from "never existed".
            return AuthenticateResult.Fail("Invalid API key.");
        }

        if (key.Touch())
            await apiKeys.FlushChangesAsync(Context.RequestAborted);

        var identity = new ClaimsIdentity(
            [
                new Claim(ClaimTypes.NameIdentifier, key.UserId.ToString()),
                // jti is what the per-key rate-limit partition keys on, so each dashboard key
                // gets its own budget instead of sharing the owner's.
                new Claim("jti", key.Id.ToString()),
            ],
            DetourAuthenticationSchemes.ApiKey);

        return AuthenticateResult.Success(
            new AuthenticationTicket(new ClaimsPrincipal(identity), Scheme.Name));
    }

    private string? ReadKey()
    {
        if (Request.Headers.TryGetValue(ApiKeyAuthenticationOptions.HeaderName, out var header)
            && header.Count > 0)
        {
            return header[0];
        }

        return Request.Query.TryGetValue(ApiKeyAuthenticationOptions.QueryParameterName, out var query)
               && query.Count > 0
            ? query[0]
            : null;
    }
}
