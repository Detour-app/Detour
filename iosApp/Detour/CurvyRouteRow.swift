import SwiftUI
import DetourShared

/// The curviness row under a searched destination (#660): the phone's
/// navigation-dock slider (`CurvyRouteControl.kt`), on the shared
/// `CurvyRouteState`. Which control shows, the cost line's clamping and when a
/// comparison is wanted are all `:shared`; this only draws them.
///
/// Stateless: `MapScreen` builds the state from `SpinModel.searched` and the
/// saved preset, and a moved slider goes straight to `Settings`.
struct CurvyRouteRow: View {
    let state: CurvyRouteState
    let onPresetChange: (CurvyPreset) -> Void

    private static let labels = ["Fast", "Balanced", "Twisty"]

    var body: some View {
        if state.control == .unsupported {
            Text("Curvy routes: your routing server can't do this yet")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
        } else if state.control == .slider {
            slider
        }
    }

    private var slider: some View {
        let presets = Enums.shared.curvyPresets
        let index = presets.firstIndex(of: state.preset) ?? 0
        return VStack(alignment: .leading, spacing: 2) {
            Text("Curvy").font(.subheadline.weight(.medium))
            Slider(
                value: Binding(
                    get: { Double(index) },
                    set: { value in
                        let picked = presets[min(max(Int(value.rounded()), 0), presets.count - 1)]
                        if picked != state.preset { onPresetChange(picked) }
                    }),
                in: 0...Double(presets.count - 1),
                step: 1)
            .accessibilityValue(Self.labels[min(index, Self.labels.count - 1)])
            HStack {
                ForEach(Self.labels, id: \.self) { label in
                    Text(label).font(.caption2)
                    if label != Self.labels.last { Spacer() }
                }
            }
            if let line = costLine {
                Text(line)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
    }

    /// "+12 min · +9.4 km · Twistiness 3 of 5", or "Comparing…" while it's
    /// asked for — the phone's `CurvyCostLine`.
    private var costLine: String? {
        if state.loading { return "Comparing with the fastest route…" }
        let cost = state.costLine.map {
            "+\($0.extraMinutes) min · +\(formatDistanceKm($0.extraMeters))"
        }
        // Bends only for a curvy preset, as on the phone, whose fast preset
        // asks for no preview at all.
        let bends = state.wantsPreview ? state.preview?.curviness.map {
            "Twistiness \(Curviness.shared.bends(score: $0.doubleValue)) of \(Curviness.shared.MAX_BENDS)"
        } : nil
        let line = [cost, bends].compactMap { $0 }.joined(separator: " · ")
        return line.isEmpty ? nil : line
    }
}

/// The saved curvy preset, published for `MapScreen` — the same
/// `Settings.curvyPreset` the phone's slider and every A→B request read.
final class CurvyPresetModel: ObservableObject {
    @Published var preset: CurvyPreset = Enums.shared.curvyPresets[0]

    private let watcher = SettingsFlows.shared.curvyPreset()

    init() {
        watcher.watch { [weak self] in
            guard let self else { return }
            preset = watcher.value
        }
    }

    deinit { watcher.cancel() }
}
