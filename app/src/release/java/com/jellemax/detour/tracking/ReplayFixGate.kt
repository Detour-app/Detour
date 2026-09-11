package com.jellemax.detour.tracking

/**
 * The release build's half of [ReplayFixGate]: believe every fix.
 *
 * The debug variant compiles `app/src/debug/.../ReplayFixGate.kt` instead, which
 * throws away the real position while a mock replay is running so the replay is
 * not corrupted by the desk it runs at (#47). None of that applies to a rider.
 * Their fixes are all real, `isMock` on their device would mean they attached a
 * mock provider on purpose, and a test rig has no business deciding which of
 * their positions to discard.
 *
 * So this is not the filter behind a flag — it is the filter absent from the
 * artefact, which is the only version of "conditionally compiled" worth having.
 * Two source sets, one signature; R8 folds the constant and the call site goes.
 */
object ReplayFixGate {

    /** Always true: see the file's doc for why a release build filters nothing. */
    @Suppress("UNUSED_PARAMETER")
    fun accept(fix: LocationFix): Boolean = true

    /** Nothing to reset; kept so both variants offer the same surface. */
    fun reset() = Unit
}
