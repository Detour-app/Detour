using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary>
/// What this deployment can do, for a client that may be newer or older than it.
///
/// Two rules govern this document and both are load-bearing rather than
/// decorative, because a self-hoster updates on their own schedule and there is
/// no coordination point with the app: a client ignores feature strings and
/// fields it does not know, and <see cref="Schema"/> is bumped only when an
/// existing field changes meaning — never for an addition.
///
/// It is also a version-fingerprinting surface, which is accepted because a
/// self-hosted open-source server's version is discoverable anyway. What follows
/// from that is a content rule: feature names and values a client needs to
/// configure itself, and nothing else. No dependency versions, no build strings,
/// no counts. Operational detail belongs on <c>/api/health</c>.
/// </summary>
public record CapabilitiesResponse(
    [Required] int Schema,
    [Required] IReadOnlyList<string> Features,
    [Required] IdpCapabilityResponse Idp);

/// <summary>
/// Where riders sign in. <see cref="Issuer"/> is <c>Idp:Authority</c> verbatim —
/// the same string the token pipeline requires as <c>iss</c>, exactly and not as
/// a prefix. Stating it unchanged is what makes it impossible for this server to
/// advertise a realm whose tokens it would then refuse.
/// </summary>
public record IdpCapabilityResponse([Required] string Issuer);
