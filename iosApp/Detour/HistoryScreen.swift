import SwiftUI
import DetourShared

/// The Logbook: past trips as month chapters, newest first, with the badges
/// earned among them, built by the shared `Logbook` that Android renders from
/// too. Keeps the vehicle correction and delete that the sync server needs to
/// hear about.
///
/// Places come from the shared `TripTraces`, which matches trace segments to
/// trips the same way Android does (#669).
struct HistoryScreen: View {

    @State private var trips: [Trip] = []
    @State private var titles: [KotlinLong: String] = [:]
    @State private var places: [KotlinLong: [PlaceVisit]] = [:]
    @State private var milestones: [LogbookItemMilestone] = []
    @State private var filter: LogbookFilter = .all
    @State private var editing: Trip?

    /// Listed here rather than added to iosMain's `Enums`, which keeps this
    /// change out of `shared/` (#623).
    private static let filters: [LogbookFilter] = [.all, .moto, .car]

    var body: some View {
        NavigationStack {
            SwiftUI.Group {
                if trips.isEmpty {
                    ContentUnavailableView(
                        "No rides yet",
                        systemImage: "road.lanes",
                        description: Text("Spin for a destination, or just start riding — Detour logs a drive on its own.")
                    )
                } else {
                    timeline
                }
            }
            .navigationTitle("Logbook")
            .task { await reload() }
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

    private var timeline: some View {
        let months = Logbook.shared.build(
            trips: trips,
            filter: filter,
            places: places,
            editedTitles: titles,
            milestones: milestones
        )
        return List {
            Picker("Vehicle", selection: $filter) {
                ForEach(Self.filters, id: \.name) { f in
                    Text(f.label).tag(f)
                }
            }
            .pickerStyle(.segmented)
            .listRowBackground(Color.clear)

            if months.isEmpty {
                Text(filter == .moto ? "No moto rides yet." : filter == .car ? "No car rides yet." : "No rides yet.")
                    .foregroundStyle(.secondary)
            }
            ForEach(months, id: \.key) { month in
                Section {
                    ForEach(month.entries) { entry in
                        switch entry {
                        case .ride(let ride):
                            rideRow(ride)
                        case .milestone(let milestone):
                            MilestoneRow(milestone: milestone)
                        }
                    }
                } header: {
                    MonthHeader(month: month)
                }
            }
        }
    }

    private func rideRow(_ ride: LogbookItemRide) -> some View {
        NavigationLink {
            TripDetailScreen(trip: ride.trip)
        } label: {
            RideRow(ride: ride)
        }
        .swipeActions(edge: .trailing) {
            Button(role: .destructive) {
                delete(ride.trip)
            } label: {
                Label("Delete", systemImage: "trash")
            }
            Button {
                editing = ride.trip
            } label: {
                Label("Vehicle", systemImage: "car")
            }
            .tint(.indigo)
        }
    }

    private func reload() async {
        // Matching walks every trace point per trip and tests it against the
        // municipalities, so it runs detached, like BadgesScreen's coverage.
        let data = await Task.detached {
            TripTraces.shared.loadLogbook(trips: TripStore.shared.load())
        }.value
        trips = data.trips
        titles = data.titles
        places = data.places
        milestones = data.milestones
    }

    private func updateMode(_ trip: Trip, to mode: TravelMode) {
        TripStore.shared.updateMode(startTimeMs: trip.startTimeMs, mode: mode)
        editing = nil
        Task { await reload() }
        // Push the correction so it survives a reinstall / other devices.
        Task { _ = try? await SyncClient.shared.sync() }
    }

    private func delete(_ trip: Trip) {
        TripStore.shared.delete(startTimeMs: trip.startTimeMs)
        Task { await reload() }
        Task { _ = try? await SyncClient.shared.sync() }
    }
}

/// One row of a month: `LogbookItem` is a Kotlin sealed interface, which
/// arrives in Swift as a protocol, so it's unwrapped once here rather than
/// cast in every view.
private enum LogbookEntry: Identifiable {
    case ride(LogbookItemRide)
    case milestone(LogbookItemMilestone)

    /// A ride by its start time; a badge by when and what, since two badges
    /// earned on the same trip share an instant.
    var id: String {
        switch self {
        case .ride(let r): return "r\(r.trip.startTimeMs)"
        case .milestone(let m): return "b\(m.atMs)\(m.title)"
        }
    }
}

private extension LogbookMonth {
    /// yyyymm, the way `Logbook.monthOf` keys a month.
    var key: Int32 { year * 100 + month }

    var entries: [LogbookEntry] {
        items.compactMap { item in
            if let r = item as? LogbookItemRide { return .ride(r) }
            if let m = item as? LogbookItemMilestone { return .milestone(m) }
            return nil
        }
    }
}

/// "September 2026" over the month's ride count, distance and new places.
private struct MonthHeader: View {
    let month: LogbookMonth

    private static let monthFormat: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("MMMM yyyy")
        return f
    }()

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title)
                .font(.headline)
                .foregroundStyle(.primary)
            Text(summary)
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .textCase(nil)
    }

    private var title: String {
        let date = DateComponents(
            calendar: Calendar.current, year: Int(month.year), month: Int(month.month), day: 1
        ).date
        return date.map { Self.monthFormat.string(from: $0) } ?? "\(month.year)-\(month.month)"
    }

    private var summary: String {
        let rides = month.rides.count
        var parts = [rides == 1 ? "1 ride" : "\(rides) rides", formatDistanceKm(month.meters)]
        let newPlaces = Int(month.newPlaces)
        if newPlaces > 0 { parts.append(newPlaces == 1 ? "1 new place" : "\(newPlaces) new places") }
        return parts.joined(separator: " · ")
    }
}

private struct RideRow: View {
    let ride: LogbookItemRide

    var body: some View {
        let trip = ride.trip
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Image(systemName: icon(for: trip.mode))
                    .foregroundStyle(.secondary)
                    .accessibilityLabel(trip.mode == .moto ? "Motorcycle" : "Car")
                Text(ride.title)
                    .font(.subheadline.weight(.medium))
                Spacer()
                Text(formatDistanceKm(trip.distanceMeters))
                    .font(.subheadline.monospacedDigit())
            }
            if let chip = ride.chip {
                Text(chip)
                    .font(.caption.weight(.medium))
                    .padding(.horizontal, 8)
                    .padding(.vertical, 2)
                    .background(Color.accentColor.opacity(0.15), in: Capsule())
            }
            HStack(spacing: 12) {
                Text(formatDate(trip.startTimeMs))
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

/// A badge earned, between the rides it was earned among.
private struct MilestoneRow: View {
    let milestone: LogbookItemMilestone

    var body: some View {
        Label(milestone.title, systemImage: "rosette")
            .font(.subheadline)
            .foregroundStyle(.secondary)
    }
}

func icon(for mode: TravelMode) -> String {
    switch mode {
    case .moto: return "motorcycle"
    default: return "car.fill"
    }
}
