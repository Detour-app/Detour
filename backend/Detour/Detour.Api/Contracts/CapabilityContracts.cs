using System.ComponentModel.DataAnnotations;
using Detour.Api.Configuration;

namespace Detour.Api.Contracts;

/// <summary>
/// What this deployment can do, for a client that may be newer or older than it.
///
/// The compatibility rules this document follows — a client ignores unknown
/// feature strings and fields, and the schema number moves only when an
/// existing field changes meaning, never for an addition — are stated
/// normatively in docs/BACKEND_SPEC.md §15.5. <see cref="SchemaVersion"/>
/// carries the half of that rule that governs the line most likely to be
/// edited without reading it.
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
    [Required] IdpCapabilityResponse Idp)
{
    /// <summary>
    /// Bumped only when an existing field on this response changes meaning —
    /// never for an addition. See docs/BACKEND_SPEC.md §15.5.
    /// </summary>
    public const int SchemaVersion = 1;

    /// <summary>
    /// Hardcoded because nothing here is conditional yet — #113 tracks the
    /// client-side gating framework that will make it so. Each string is a wire
    /// contract clients match on, so renaming one is a breaking change even
    /// though <see cref="SchemaVersion"/> does not move for it.
    /// </summary>
    public static readonly IReadOnlyList<string> KnownFeatures = ["idp-discovery"];

    public static CapabilitiesResponse From(IdpSettings idpSettings) => new(
        SchemaVersion,
        KnownFeatures,
        new IdpCapabilityResponse(idpSettings.Authority));
}

/// <summary>
/// Where riders sign in. <see cref="Issuer"/> is <c>Idp:Authority</c> verbatim —
/// the same string the token pipeline requires as <c>iss</c>, exactly and not as
/// a prefix. Stating it unchanged is what makes it impossible for this server to
/// advertise a realm whose tokens it would then refuse.
/// </summary>
public record IdpCapabilityResponse([Required] string Issuer);
