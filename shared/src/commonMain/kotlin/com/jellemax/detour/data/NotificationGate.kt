package com.jellemax.detour.data

/**
 * Every notification the app posts by itself, sorted by whether the master
 * switch ([Settings.notificationsEnabled], #977) may hold it back.
 *
 * [ongoing] is the one rule: a notification that stands for something running
 * right now — a trip being recorded, an update downloading — is the one
 * Android requires of a foreground service, so it stays whatever the switch
 * says. Everything else is news the rider can do without. The foreground
 * services' own first post goes through `startForeground` rather than a
 * poster, and is ongoing by definition; it is not listed here.
 */
enum class NotificationKind(val ongoing: Boolean) {
    /** A circle member arrived or left, live or from a catch-up sweep. */
    CIRCLE_PLACE(ongoing = false),

    /** A planned ride is about to start (push or local). */
    RIDE_REMINDER(ongoing = false),

    /** An auto-detected trip was saved. */
    TRIP_ENDED(ongoing = false),

    /** A trip earned one or more badges. */
    BADGES_EARNED(ongoing = false),

    /** A convoy break request or regroup point while the app is in the background. */
    CONVOY_ALERT(ongoing = false),

    /** A new version is available. */
    UPDATE_AVAILABLE(ongoing = false),

    /** A download finished, failed or was stopped. */
    UPDATE_DOWNLOAD_RESULT(ongoing = false),

    /** The update installed; tap to reopen. */
    UPDATE_INSTALLED(ongoing = false),

    /** The live trip-recording notification, re-posted as the trip changes. */
    TRIP_RECORDING(ongoing = true),

    /** The update download's progress notification. */
    UPDATE_DOWNLOAD_PROGRESS(ongoing = true),
}

/**
 * The one check every poster routes through. Shared so iOS can follow with
 * the same table; Android's poster is `notif/Notifier`.
 */
object NotificationGate {

    /** Whether [kind] may be posted. [masterOn] defaults to the stored switch;
     *  it is a parameter so a test can check the table without a [Prefs]. */
    fun allows(kind: NotificationKind, masterOn: Boolean = Settings.notificationsEnabled.value): Boolean =
        masterOn || kind.ongoing
}
