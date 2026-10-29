package com.jellemax.detour.data

/**
 * Which BLE central counts as the rider's external display — issue #692.
 *
 * The display's GATT characteristics carry no bonding requirement, so any
 * central in range can connect, and the telemetry one (board speed and lean,
 * which the tracking service prefers over the phone's own sensors) is the
 * only one whose writes reach a recorded trip. The first central that
 * subscribes to the nav characteristic after the display is switched on is
 * remembered as the display, and only its telemetry is believed. Switching
 * the display off forgets it, which is how a rider swaps boards.
 *
 * A device spoofing that address still gets in; the bonded fix is
 * encrypted characteristics, which needs the board's firmware.
 */
object DisplayPin {
    private const val PREFS = "external_display"
    private const val KEY_ADDRESS = "address"

    /** The remembered display, or null before one has subscribed. */
    fun pinned(): String? = prefs(PREFS).string(KEY_ADDRESS).takeIf { it.isNotEmpty() }

    /** [address] subscribed to nav; it becomes the display if none is remembered. */
    fun onNavSubscribe(address: String) {
        val pinned = pinned()
        val pin = pinOnSubscribe(pinned, address)
        if (pin != pinned) prefs(PREFS).put(KEY_ADDRESS, pin)
    }

    fun acceptsTelemetry(address: String): Boolean = acceptsTelemetry(pinned(), address)

    fun forget() {
        prefs(PREFS).remove(KEY_ADDRESS)
    }

    /** The address to remember once [address] subscribes to nav: the one
     *  already remembered, or [address] when there is none yet. */
    internal fun pinOnSubscribe(pinned: String?, address: String): String = pinned ?: address

    /** Whether a telemetry write from [address] may be believed. Nothing is
     *  pinned until a display has subscribed, so nothing is believed before. */
    internal fun acceptsTelemetry(pinned: String?, address: String): Boolean = pinned == address
}
