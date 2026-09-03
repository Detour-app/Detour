using System.ComponentModel.DataAnnotations;

namespace Detour.Api.Contracts;

/// <summary>
/// How a rider appears on the wire, everywhere one is named.
///
/// <see cref="Id"/> is the identity — the local account id, stable for the life of the
/// account and unaffected by a rename. <see cref="Username"/> is a display label and a
/// search key, and is never what a client compares to decide "is this me" or "do I own
/// this". See docs/superpowers/specs/2026-09-03-rider-id-identity-design.md for why both
/// travel together rather than the client resolving one from the other.
/// </summary>
public record RiderRef(
    [Required] Guid Id,
    [Required] string Username);
