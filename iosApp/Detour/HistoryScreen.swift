import SwiftUI
import DetourShared

/// Past trips, newest first, with the vehicle correction and delete that the
/// sync server needs to hear about.
struct HistoryScreen: View {

    @State private var trips: [Trip] = []
    @State private var editing: Trip?

    var body: some View {
        NavigationStack {
            Group {
                if trips.isEmpty {
                    ContentUnavailableView(
                        "No rides yet",
                        systemImage: "road.lanes",
                        description: Text("Spin for a destination, or just start riding — Detour logs a drive on its own.")
                    )
                } else {
                    List {
                        ForEach(trips, id: \.startTimeMs) { trip in
                            NavigationLink {
                                TripDetailScreen(trip: trip)
                            } label: {
                                TripRow(trip: trip)
                            }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    delete(trip)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                Button {
                                    editing = trip
                                } label: {
                                    Label("Vehicle", systemImage: "car")
                                }
                                .tint(.indigo)
                            }
                        }
                    }
                }
            }
            .navigationTitle("History")
            .task { reload() }
            .confirmationDialog(
                "Which vehicle was this?",
                isPresented: .constant(editing != nil),
                presenting: editing
            ) { trip in
                ForEach(Enums.shared.travelModes, id: \.name) { mode in
                    Button(mode.label) { updateMode(trip, to: mode) }
                }
                Button("Cancel", role: .cancel) { editing = nil }
            }
        }
    }

    private func reload() {
        trips = TripStore.shared.load()
    }

    private func updateMode(_ trip: Trip, to mode: TravelMode) {
        TripStore.shared.updateMode(startTimeMs: trip.startTimeMs, mode: mode)
        editing = nil
        reload()
        // Push the correction so it survives a reinstall / other devices.
        Task { _ = try? await SyncClient.shared.sync() }
    }

    private func delete(_ trip: Trip) {
        TripStore.shared.delete(startTimeMs: trip.startTimeMs)
        reload()
        Task { _ = try? await SyncClient.shared.sync() }
    }
}

private struct TripRow: View {
    let trip: Trip

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Image(systemName: icon(for: trip.mode))
                    .foregroundStyle(.secondary)
                Text(formatDate(trip.startTimeMs))
                    .font(.subheadline.weight(.medium))
                Spacer()
                Text(formatDistanceKm(trip.distanceMeters))
                    .font(.subheadline.monospacedDigit())
            }
            HStack(spacing: 12) {
                Label(formatDurationHistory(trip.durationMs), systemImage: "clock")
                Label(formatSpeedKmh(trip.topSpeedMps), systemImage: "speedometer")
                if trip.maxLeanAngleDeg > 0 {
                    Label(formatLeanAngle(trip.maxLeanAngleDeg), systemImage: "motorcycle")
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            .labelStyle(.titleAndIcon)
        }
        .padding(.vertical, 2)
    }
}

func icon(for mode: TravelMode) -> String {
    switch mode {
    case .walk: return "figure.walk"
    case .bike: return "bicycle"
    case .moto: return "motorcycle"
    default: return "car.fill"
    }
}
