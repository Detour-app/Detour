package com.jellemax.detour.data

import kotlin.jvm.JvmInline

/**
 * A rider's identity, as the server issues it.
 *
 * A `value class` and not a `String` so that handing a handle to something
 * expecting an identity is a compile error rather than a comparison that is
 * simply false. That is not hypothetical: every `isMe`, ownership and
 * self-filter check in this app compared handles until #133, and the server
 * stores the same handle in a case-insensitive column while Kotlin's `==` is
 * case-sensitive — so the two could disagree with nothing renamed and nobody
 * at fault.
 *
 * Opaque on purpose. It is a UUID string today and nothing here reads it as
 * anything but a key to compare and a value to put back in a path, the same
 * contract [Group.id] already has.
 *
 * That comparison is only as good as both ends spelling the same UUID the
 * same way: this compares the raw string, not the 128 bits underneath, so it
 * depends on the backend and this client agreeing on one string form. Both
 * sides currently get that for free from `System.Text.Json`'s default `Guid`
 * handling and Kotlin's own string equality — lowercase, hyphenated, "D"
 * format on the wire and unchanged on the way in — but nothing pins it. A
 * future `JsonConverter<Guid>` on the API that reformats or cases the string
 * differently would make every identity comparison in this app fail silently
 * rather than throw.
 */
@JvmInline
value class RiderId(val value: String)

/**
 * A rider as every payload that names one carries them: the identity, and the
 * handle to draw. Mirrors the server's `RiderRef`.
 */
data class RiderRef(val id: RiderId, val username: String)
