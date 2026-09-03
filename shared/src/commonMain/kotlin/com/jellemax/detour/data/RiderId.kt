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
 */
@JvmInline
value class RiderId(val value: String)

/**
 * A rider as every payload that names one carries them: the identity, and the
 * handle to draw. Mirrors the server's `RiderRef`.
 */
data class RiderRef(val id: RiderId, val username: String)
