import SwiftUI
import DetourShared

/// Badges, grouped by kind, earned ones first within each group.
///
/// Every value is recomputed from the trip store and coverage — the only thing
/// on disk is *when* each was first earned, which is also the only thing sync
/// carries. So this screen just asks the shared core to rescore and renders
/// what comes back.
struct BadgesScreen: View {

    @State private var states: [BadgeState] = []
    @State private var loading = true

    var body: some View {
        NavigationStack {
            SwiftUI.Group {
                if loading {
                    ProgressView()
                } else {
                    List {
                        ForEach(kinds, id: \.name) { kind in
                            Section(kind.label) {
                                ForEach(states.filter { $0.def.kind == kind }, id: \.def.id) {
                                    BadgeRow(state: $0)
                                }
                            }
                        }
                    }
                }
            }
            .navigationTitle("Badges")
            .task { await reload() }
            .refreshable { await reload() }
        }
    }

    private var kinds: [BadgeKind] {
        // Only the kinds that actually have badges, in declaration order.
        Enums.shared.badgeKinds.filter { kind in states.contains { $0.def.kind == kind } }
    }

    private func reload() async {
        loading = states.isEmpty
        // Coverage walks every trace point per municipality, so it is worth a
        // detached task even on a screen open.
        let result = await Task.detached {
            let coverage = Coverage.shared.compute()
            return BadgeStore.shared.refresh(stats: BadgeStore.shared.stats(coverage: coverage))
        }.value
        states = result.states
        loading = false
    }
}

private struct BadgeRow: View {
    let state: BadgeState

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: state.earned ? "seal.fill" : "seal")
                .font(.title2)
                .foregroundStyle(state.earned ? .yellow : .secondary)

            VStack(alignment: .leading, spacing: 4) {
                Text(state.def.title)
                    .font(.body.weight(state.earned ? .semibold : .regular))
                if state.earned, let at = state.earnedAtMs?.int64Value {
                    Text("Earned \(formatDate(at))")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    ProgressView(value: Double(state.progress))
                        .tint(.accentColor)
                    Text(progressLabel)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(.vertical, 4)
    }

    /// Value and threshold in the badge's own unit, which is the only thing
    /// that makes "1,240 / 5,000" mean anything.
    private var progressLabel: String {
        switch state.def.kind {
        case .distance, .tripDistance:
            return "\(formatDistanceKm(state.value)) of \(formatDistanceKm(state.def.threshold))"
        case .topSpeed:
            return String(format: "%.0f of %.0f km/h", state.value, state.def.threshold)
        case .coverage:
            return String(format: "%.0f%% of %.0f%%", state.value, state.def.threshold)
        default:
            return String(format: "%.0f of %.0f", state.value, state.def.threshold)
        }
    }
}
