import Foundation

/// Same formatting the Android UI uses, so a trip reads identically on both.
/// Not in `:shared`: these are presentation, and every one of them wants the
/// platform's own locale machinery.

func formatDuration(_ ms: Int64) -> String {
    let total = ms / 1000
    let h = total / 3600, m = (total % 3600) / 60, s = total % 60
    return h > 0
        ? String(format: "%d:%02d:%02d", h, m, s)
        : String(format: "%d:%02d", m, s)
}

/// History shows past trips side by side ("1:12:36" next to "7:19"), where the
/// live card's seconds-precision "M:SS" is ambiguous — is "7:19" seven minutes
/// or seven hours? `formatDuration` still owns the live trip card, where
/// seconds matter and there is only ever one duration on screen at a time.
func formatDurationHistory(_ ms: Int64) -> String {
    let totalMinutes = ms / 60_000
    let h = totalMinutes / 60, m = totalMinutes % 60
    return h > 0 ? "\(h) h \(m) min" : "\(m) min"
}

func formatSpeedKmh(_ mps: Double) -> String {
    String(format: "%.0f km/h", mps * 3.6)
}

func formatDistanceKm(_ meters: Double) -> String {
    meters < 1000
        ? String(format: "%.0f m", meters)
        : String(format: "%.1f km", meters / 1000)
}

func formatLeanAngle(_ deg: Double) -> String { String(format: "%.0f°", deg) }

func formatGForce(_ g: Double) -> String { String(format: "%.1f g", g) }

// Built once and reused: a DateFormatter costs a few milliseconds to construct,
// and a List reuses rows fast enough during a fling for that to show.
private let tripDateFormatter: DateFormatter = {
    let f = DateFormatter()
    f.setLocalizedDateFormatFromTemplate("EEE d MMM yyyy HH:mm")
    return f
}()

private let timeOfDayFormatter: DateFormatter = {
    let f = DateFormatter()
    f.setLocalizedDateFormatFromTemplate("HH:mm")
    return f
}()

func formatDate(_ timeMs: Int64) -> String {
    tripDateFormatter.string(from: Date(timeIntervalSince1970: Double(timeMs) / 1000))
}

func formatTimeOfDay(_ timeMs: Int64) -> String {
    timeOfDayFormatter.string(from: Date(timeIntervalSince1970: Double(timeMs) / 1000))
}
