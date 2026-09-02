package com.jellemax.detour.data

/**
 * One address, normalised for comparison: trimmed, with trailing slashes gone.
 *
 * Two call sites share this today — [Capabilities.parse] and
 * [RoutingServer.pick] — and a third is coming: `Auth.idTokenIssuer` will need
 * the same rule to compare a `iss` claim against this same value. Given a home
 * now rather than after that lands, because a sign-in is refused when the
 * copies disagree — a realm emitting a trailing slash in `iss` would fail a
 * comparison that is actually a match — and documentary agreement is enough at
 * two copies but not at three.
 */
internal fun normalisedAddress(raw: String): String = raw.trim().trimEnd('/')
