import SwiftUI
import CoreLocation
import DetourShared

/// What the break-and-regroup controls show on this device (#658), from the
/// relay's requests and pin, the active convoy, and every position this device
/// has, its own under `selfId`. The rules — who is host, how long a request
/// stays, who has arrived — are `convoyBreakRegroupStateFrom`'s in shared,
/// reached through `ConvoyBreakRegroupStates` because Swift cannot build its
/// `RiderId`-keyed maps. Mirrors the state call in Android's
/// `MapBreakRegroup.kt`.
@MainActor
func convoyBreakRegroupState(
    live: ConvoyLiveClient,
    convoy: DetourShared.Group?,
    selfId: String,
    here: CLLocationCoordinate2D?,
    nowMs: Int64
) -> ConvoyBreakRegroupState {
    var positions = live.peers.mapValues { LatLon(lat: $0.lat, lon: $0.lon) }
    if let here { positions[selfId] = LatLon(lat: here.latitude, lon: here.longitude) }
    return ConvoyBreakRegroupStates.shared.from(
        convoy: convoy,
        selfId: selfId,
        breakRequests: live.breakRequests,
        pin: live.regroupPin,
        positions: positions,
        nowMs: nowMs)
}

extension ConvoyBreakRegroupState {
    /// The break markers and the meeting point, as titled pins for `MapView`,
    /// labelled like the phone map's `setConvoyMarks`.
    var mapPins: [MapPin] {
        let breakPins = breaks.map { pill in
            MapPin(
                coordinate: CLLocationCoordinate2D(latitude: pill.location.lat, longitude: pill.location.lon),
                title: "Break · \(pill.mine ? "You" : pill.name)")
        }
        guard let regroup else { return breakPins }
        let pin = MapPin(
            coordinate: CLLocationCoordinate2D(latitude: regroup.location.lat, longitude: regroup.location.lon),
            title: regroup.label ?? "Regroup point")
        return breakPins + [pin]
    }
}

/// Break please, the host's Regroup here, the break pills and the regroup card
/// (#658) — the iOS side of Android's `MapBreakRegroup.kt`. The caller shows it
/// only while a convoy is joined; this draws `state` and sends through
/// `ConvoyLiveClient`.
struct ConvoyBreakRegroupPanel: View {

    let state: ConvoyBreakRegroupState
    let connected: Bool
    /// This device's own position: where a break is asked from.
    let here: LatLon?
    /// Where Regroup drops the pin — the picked destination, or with nothing
    /// picked, where the host is. Nil with neither.
    let regroupAt: LatLon?
    let onRoute: (LatLon) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let banner = state.regroup {
                RegroupCard(
                    banner: banner,
                    isHost: state.isHost,
                    onRoute: { onRoute(banner.location) },
                    onClear: { ConvoyLiveClient.shared.sendRegroupPinClear() })
            }
            ForEach(Array(state.breaks.enumerated()), id: \.offset) { _, pill in
                Text(pill.mine ? "You asked for a break" : "\(pill.name) wants a break")
                    .font(.callout.weight(.medium))
                    .lineLimit(1)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(.regularMaterial, in: Capsule())
            }
            HStack(spacing: 12) {
                // Large on purpose: pressed with a glove, at a glance, on the move.
                RoundAction(systemImage: "cup.and.saucer.fill", label: "Break", size: 72) {
                    if let here { ConvoyLiveClient.shared.sendBreakRequest(here) }
                }
                .disabled(!connected || here == nil)
                if state.isHost {
                    RoundAction(systemImage: "flag.fill", label: "Regroup", size: 64) {
                        if let regroupAt { ConvoyLiveClient.shared.sendRegroupPin(regroupAt, label: nil) }
                    }
                    .disabled(!connected || regroupAt == nil)
                }
            }
        }
        .frame(maxWidth: 260, alignment: .leading)
    }
}

private struct RoundAction: View {
    let systemImage: String
    let label: String
    let size: CGFloat
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(spacing: 2) {
                Image(systemName: systemImage).font(.title3)
                Text(label).font(.caption2).lineLimit(1)
            }
            .frame(width: size, height: size)
            .background(.regularMaterial, in: Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(label)
    }
}

private struct RegroupCard: View {
    let banner: RegroupBanner
    let isHost: Bool
    let onRoute: () -> Void
    let onClear: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Regroup at \(banner.label ?? "Regroup point")")
                .font(.subheadline.weight(.semibold))
                .lineLimit(2)
            // KotlinDouble? off the shared class — `.doubleValue` unwraps it.
            if let distance = banner.distanceM?.doubleValue {
                Text(banner.hereAlready ? "You're there" : formatDistanceKm(distance))
                    .font(.callout)
            }
            if !banner.arrived.isEmpty {
                Text("There: \(banner.arrived.joined(separator: ", "))")
                    .font(.caption)
            }
            if !banner.notArrived.isEmpty {
                Text("On the way: \(banner.notArrived.joined(separator: ", "))")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            HStack {
                if !banner.hereAlready {
                    Button("Route", action: onRoute)
                }
                if isHost {
                    Button("Clear", action: onClear)
                }
            }
            .buttonStyle(.borderless)
        }
        .padding(12)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}
