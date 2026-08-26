import SwiftUI
import CoreLocation
import DetourShared

/// The main screen: the map, the spin, and the running trip.
struct MapScreen: View {

    @EnvironmentObject private var recorder: TripRecorder
    @StateObject private var spin = SpinModel()
    @StateObject private var modes = TripModeModel()
    /// The trajectcontrole average. Here and not in `NavScreen`, which is a
    /// `fullScreenCover`: the phone shows this chip on its map screen whether
    /// or not navigation is running, and a section is most likely to catch
    /// someone on a free drive down a motorway they are not being routed along.
    @StateObject private var sections = SectionAverageModel()
    @State private var showSearch = false
    @State private var navigating = false
    /// Last-known position per other member, across every circle you're in.
    /// Kept across polls even when a fetch fails, so a blip doesn't blank the
    /// map — see the `.task(id:)` below.
    @State private var circleFixes: [MemberFix] = []
    @StateObject private var circleFixUsername = CircleFixUsernameModel()
    /// Convoy state, for the group spin below. Same shared singleton ConvoyBar
    /// observes — the vote round and the peer strip are two views of it.
    @ObservedObject private var live = ConvoyLiveClient.shared

    /// Circle members post a fix every `CircleSync.syncIntervalSeconds` at
    /// most, so polling faster would just re-fetch the same row — matches
    /// that cadence exactly, same reasoning as Android's `CIRCLE_FIX_POLL_MS`
    /// in MapScreen.kt.
    private static let circleFixPollSeconds = 120

    var body: some View {
        ZStack(alignment: .bottom) {
            MapView(
                center: recorder.lastFix?.coordinate,
                destination: destinationCoordinate,
                route: spin.route,
                circleMembers: circleFixes,
                candidates: candidateRows.map {
                    CLLocationCoordinate2D(latitude: $0.location.lat, longitude: $0.location.lon)
                }
            )
            .ignoresSafeArea()

            VStack(spacing: 10) {
                // Trailing, above everything else in the stack, which is where
                // the phone puts it too — the chip sits at the end of the speed
                // HUD row, above the trip card.
                if let average = sections.averageKmh {
                    HStack {
                        Spacer()
                        SectionAverageChip(averageKmh: average, limitKmh: sections.limitKmh)
                    }
                }
                ConvoyBar()
                if let stats = recorder.stats {
                    TripCard(stats: stats) { recorder.endTrip() }
                } else if !candidateRows.isEmpty {
                    candidatesCard
                } else {
                    spinControls
                }
            }
            .padding()
        }
        // The section tracker's fix stream. Same source the map centre and the
        // trip card read, so the readout cannot lag what is on screen.
        .onChange(of: recorder.lastFix) { _, fix in
            guard let fix else { return }
            sections.update(with: fix)
        }
        // The three places a vote round can resolve from: a new offer landing
        // (which may itself be the closing one-candidate offer), a vote
        // arriving, or the live-peer set changing so that everyone left has
        // now voted. All three funnel into one rule — see resolveGroupSpin.
        .onChange(of: live.spinOffer) { _, _ in resolveGroupSpin() }
        .onChange(of: live.spinVotes) { _, _ in resolveGroupSpin() }
        .onChange(of: live.peers) { _, _ in resolveGroupSpin() }
        // Circle member markers: every circle you're in, always — not just
        // whichever one CirclesScreen last had open. A circle is the always-on
        // relationship (docs/CIRCLES_AND_CONVOYS.md section 2); making the map
        // go blank until you walk into another screen and pick one defeats the
        // point of it, and that selection lived in memory, so every app launch
        // lost it. Polled rather than socketed: a circle fix only changes once
        // a minute or so server-side, so polling faster would just repeat the
        // same row. `othersFixes` is the shared chain Android's MapScreen.kt
        // reads too, so the two platforms can't drift apart on which members
        // count — including dropping your own fix, which the server returns
        // like anyone else's and which would otherwise stack a second marker
        // on your own position.
        //
        // Keyed on `circleFixUsername.username`, an actual `@Published`
        // mirror — not `SettingsValues.shared.authUsername` directly, which
        // is a plain Kotlin getter nothing publishes on, so keying `.task(id:)`
        // on it only re-evaluated when something else happened to recompute
        // this view. That used to mean a sign-out never restarted this task
        // on its own, so the previous rider's circle members' last-known
        // positions — drawn as map markers and punched through the fog scrim
        // — stayed on screen for the rest of the app session. Same shape as
        // `FriendsModel`/`CirclesModel`: watch the StateFlow itself. Matches
        // Android's `LaunchedEffect(accountUsername)` in MapScreen.kt, which
        // is already keyed on a collected StateFlow.
        .task(id: circleFixUsername.username) {
            let me = circleFixUsername.username
            // Cleared unconditionally, not only on the empty branch below.
            // Sign-in and sign-out both happen on the Friends tab, so this
            // `.task` can be torn down without ever running for the `""`
            // transition in between — the Map tab was simply not selected
            // for it. `circleFixes` is `@State`, which survives a TabView
            // switch, so without this the new rider's first visit to the Map
            // tab would start this loop with the previous rider's positions
            // still drawn until the first round trip returns — or
            // indefinitely if it fails, since the `catch` below deliberately
            // keeps the last known positions.
            circleFixes = []
            guard !me.isEmpty else { return }  // signed out: nothing to ask the server for
            while !Task.isCancelled {
                do {
                    let fixes = try await CircleFixes.shared.othersFixes(selfUsername: me)
                    // Cancelling this Task when the id changes does not cancel
                    // the Kotlin coroutine behind `othersFixes` — an exported
                    // suspend fun has no cancellation path through the ObjC
                    // bridge, so a call already in flight always runs to
                    // completion regardless. A sign-out (or a sign-in as
                    // someone else) while this fetch was in flight must not
                    // let its answer land after the id has moved on, the same
                    // reason the shared stores guard their own commits on
                    // Auth.sessionEpoch — this is that guard's Swift-side
                    // equivalent for a value with no shared epoch to check.
                    if circleFixUsername.username == me { circleFixes = fixes }
                } catch {
                    // Offline or server down; keep the last known positions
                    // and retry on the next tick.
                }
                try? await Task.sleep(for: .seconds(Self.circleFixPollSeconds))
            }
        }
        .safeAreaInset(edge: .top) { modePicker }
        .fullScreenCover(isPresented: $navigating) {
            if let route = spin.routeResult {
                NavScreen(route: route, destinationName: nil) { navigating = false }
                    .environmentObject(recorder)
            }
        }
        .sheet(isPresented: $showSearch) {
            SearchSheet { result in
                spin.setDestination(result.location)
                showSearch = false
            }
        }
        .alert("Badge earned", isPresented: .constant(!recorder.newlyEarned.isEmpty)) {
            Button("Nice") { recorder.newlyEarned = [] }
        } message: {
            Text(recorder.newlyEarned.map(\.title).joined(separator: "\n"))
        }
    }

    // MARK: Pieces

    private var modePicker: some View {
        Picker("Vehicle", selection: Binding(
            get: { modes.mode },
            set: { Settings.shared.setTripMode(value: $0) }
        )) {
            ForEach(Enums.shared.travelModes, id: \.name) { mode in
                Text(mode.label).tag(mode)
            }
        }
        .pickerStyle(.segmented)
        .padding(.horizontal)
        .background(.regularMaterial)
    }

    private var spinControls: some View {
        VStack(spacing: 12) {
            Text(status)
                .font(.callout)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)

            HStack {
                Text("\(Int(spin.radiusMeters / 1000)) km")
                    .monospacedDigit()
                    .frame(width: 64, alignment: .leading)
                Slider(
                    value: $spin.radiusMeters,
                    // Each mode has its own sensible range — 3 km on foot,
                    // 400 km for a moto round trip — and the shared enum is
                    // where those live.
                    in: Double(modes.mode.minKm * 1000)...Double(modes.mode.maxKm * 1000),
                    step: 1_000)
            }

            HStack(spacing: 10) {
                Button { showSearch = true } label: {
                    Image(systemName: "magnifyingglass")
                        .frame(height: 44)
                        .frame(maxWidth: 56)
                }
                .buttonStyle(.bordered)

                Button {
                    guard let here = recorder.lastFix?.coordinate else { return }
                    Task { await spin.spin(from: here, mode: modes.mode) }
                } label: {
                    Text(spin.state == .spinning ? "Spinning…" : "Spin")
                        .font(.headline)
                        .frame(maxWidth: .infinity)
                        .frame(height: 44)
                }
                .buttonStyle(.borderedProminent)
                .disabled(recorder.lastFix == nil || spin.state == .spinning)

                Button {
                    recorder.startTrip(destination: spin.destination)
                    // Only navigate when the router actually gave us turns;
                    // otherwise this is just a recorded ride toward a pin.
                    if spin.routeResult?.instructions.isEmpty == false {
                        navigating = true
                    }
                } label: {
                    Image(systemName: spin.routeResult?.instructions.isEmpty == false
                          ? "location.north.fill" : "record.circle")
                        .frame(height: 44)
                        .frame(maxWidth: 56)
                }
                .buttonStyle(.bordered)
                .disabled(recorder.lastFix == nil)
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    // MARK: Candidates and the group spin

    /// What the map and the card actually show: this device's own three rolls,
    /// unless a convoy spin is on the table, in which case everyone — the
    /// sharer included — shows the three from the offer. Keeps every device
    /// pointed at the same coordinates even when the roll happened on another
    /// phone. Mirrors `displayCandidates` in Android's MapScreen.kt.
    private var candidateRows: [CandidateRow] {
        if let offer = live.spinOffer {
            return offer.candidates.enumerated().map { index, c in
                CandidateRow(
                    id: index,
                    location: LatLon(lat: c.lat, lon: c.lon),
                    name: c.name,
                    // c.distanceM/durationS are Kotlin Double? properties on
                    // DetourShared.SpinCandidate, which arrive boxed
                    // (KotlinDouble?) rather than as native Swift Double? —
                    // .doubleValue is what actually unwraps one, same as
                    // RouteResult.timeMs below.
                    distanceM: c.distanceM?.doubleValue,
                    durationS: c.durationS?.doubleValue)
            }
        }
        return spin.candidates.enumerated().map { index, c in
            CandidateRow(
                id: index,
                location: c.destination,
                name: c.name,
                distanceM: c.route?.distanceMeters?.doubleValue ?? c.straightLineMeters,
                durationS: durationSeconds(c.route))
        }
    }

    private var candidatesCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(live.spinOffer == nil ? "Pick a destination" : "Vote on a destination")
                .font(.headline)
            Text(live.spinOffer == nil
                 ? "All three are on the map — tap a row."
                 : "Everyone sees the same three — tap a row to vote.")
                .font(.caption)
                .foregroundStyle(.secondary)

            ForEach(candidateRows) { row in
                Button { pick(row) } label: {
                    HStack(alignment: .firstTextBaseline) {
                        Text(String(UnicodeScalar(UInt8(65 + row.id))))
                            .font(.headline.monospaced())
                            .frame(width: 20)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.name ?? "A road")
                            Text(rowDetail(row))
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            if live.spinOffer != nil {
                                Text(voteLine(for: row))
                                    .font(.caption2)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        Spacer()
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
            }

            // Only pre-share, in a convoy, with rolls of our own to offer.
            if live.activeConvoyId != nil && live.spinOffer == nil && !spin.candidates.isEmpty {
                Button("Share with convoy") { shareWithConvoy() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity)
            }
            // Closing the round is the sharer's call alone — see
            // resolveGroupSpin for why it cannot be everyone's.
            if let offer = live.spinOffer, offer.fromMe {
                Button("Go with the lead") {
                    live.sendSpinOffer([offer.candidates[leadingSpinIndex(of: offer.candidates.count)]])
                }
                .buttonStyle(.borderedProminent)
                .frame(maxWidth: .infinity)
            }

            HStack {
                Button("Cancel") {
                    spin.clearCandidates()
                    live.clearSpinOffer()
                }
                .buttonStyle(.bordered)
                .frame(maxWidth: .infinity)
                // Rerolling would only change this device's own three, not the
                // sheet everyone else is voting on — hidden once shared.
                if live.spinOffer == nil {
                    Button("Reroll") {
                        guard let here = recorder.lastFix?.coordinate else { return }
                        Task { await spin.spin(from: here, mode: modes.mode) }
                    }
                    .buttonStyle(.borderedProminent)
                    .frame(maxWidth: .infinity)
                }
            }
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    /// A tap is a vote while a convoy spin is on the table, and a commit
    /// otherwise. Voting deliberately does not commit: the round ends when the
    /// sharer says it does.
    private func pick(_ row: CandidateRow) {
        if live.spinOffer != nil {
            live.sendSpinVote(row.id)
        } else if row.id < spin.candidates.count {
            spin.choose(spin.candidates[row.id])
        }
    }

    private func shareWithConvoy() {
        live.sendSpinOffer(spin.candidates.map { c in
            let distanceM = c.route?.distanceMeters?.doubleValue ?? c.straightLineMeters
            let durationS = durationSeconds(c.route)
            // DetourShared.SpinCandidate's distanceM/durationS are Kotlin
            // Double? constructor parameters — passing a native Swift
            // Double? does not bridge automatically (only String? does);
            // a nullable Kotlin primitive needs its boxed wrapper explicitly,
            // same as SectionAverage.swift's headingDeg: KotlinDouble(value:)
            // call into SectionAverageHolder.onFix.
            return SpinCandidate(
                lat: c.destination.lat,
                lon: c.destination.lon,
                distanceM: distanceM.map { KotlinDouble(value: $0) },
                durationS: durationS.map { KotlinDouble(value: $0) },
                name: c.name)
        })
    }

    /// How a vote round ends, identical to Android's rule and deliberately so —
    /// the two clients must agree or a convoy splits across two destinations.
    ///
    /// A one-candidate offer is the sharer announcing the winner, and every
    /// device commits it on sight. Only the sharer decides when that moment is,
    /// once everyone still live has voted. Tallying independently on each phone
    /// would be simpler and wrong: a peer quiet for 20 s is pruned from one
    /// device's `peers` and not another's, so two members can call the round
    /// complete on different vote counts.
    private func resolveGroupSpin() {
        guard let offer = live.spinOffer else { return }
        if offer.candidates.count == 1 {
            let winner = offer.candidates[0]
            let target = LatLon(lat: winner.lat, lon: winner.lon)
            live.clearSpinOffer()
            guard let here = recorder.lastFix?.coordinate else {
                spin.setDestination(target)
                return
            }
            Task { await spin.setDestinationRouting(to: target, from: here, mode: modes.mode) }
            return
        }
        guard offer.fromMe else { return }
        let me = SettingsValues.shared.authUsername
        var expected = Set(live.peers.keys)
        if !me.isEmpty { expected.insert(me) }
        guard !expected.isEmpty, expected.isSubset(of: Set(live.spinVotes.keys)) else { return }
        live.sendSpinOffer([offer.candidates[leadingSpinIndex(of: offer.candidates.count)]])
    }

    /// Ties — including "nobody has voted", every count zero — go to the lowest
    /// index. `>` rather than `>=` is what makes that deterministic.
    private func leadingSpinIndex(of count: Int) -> Int {
        var counts = Array(repeating: 0, count: count)
        for index in live.spinVotes.values where counts.indices.contains(index) {
            counts[index] += 1
        }
        var lead = 0
        for i in 1..<max(count, 1) where counts[i] > counts[lead] { lead = i }
        return lead
    }

    private func rowDetail(_ row: CandidateRow) -> String {
        var parts: [String] = []
        if let distance = row.distanceM { parts.append(formatDistanceKm(distance)) }
        if let seconds = row.durationS { parts.append(formatDuration(Int64(seconds * 1000))) }
        return parts.isEmpty ? "Distance unknown" : parts.joined(separator: " · ")
    }

    private func voteLine(for row: CandidateRow) -> String {
        let voters = live.spinVotes.filter { $0.value == row.id }.keys.sorted()
        guard !voters.isEmpty else { return "No votes yet" }
        return "\(voters.count) vote\(voters.count == 1 ? "" : "s") · \(voters.joined(separator: ", "))"
    }

    private var destinationCoordinate: CLLocationCoordinate2D? {
        spin.destination.map {
            CLLocationCoordinate2D(latitude: $0.lat, longitude: $0.lon)
        }
    }

    private var status: String {
        switch spin.state {
        case .idle:
            return recorder.lastFix == nil
                ? "Waiting for a location fix…"
                : "Pick a radius and spin."
        case .spinning:
            return "Finding a road…"
        case .choosing:
            return "Three roads found — pick one."
        case let .found(_, _, distance):
            guard let distance else { return "Found a road." }
            return String(format: "%.1f km by road", distance / 1000)
        case let .failed(message):
            return message
        }
    }
}

/// One row of the candidate card, flattened from either source — this device's
/// own `RouteCandidate` rolls or a convoy `SpinCandidate` off the wire — so the
/// card and the map pins need no second code path for "I received this" versus
/// "I rolled this".
private struct CandidateRow: Identifiable {
    let id: Int
    let location: LatLon
    let name: String?
    let distanceM: Double?
    let durationS: Double?
}

/// `RouteResult.timeMs` arrives as a boxed Kotlin Long; the card wants seconds.
private func durationSeconds(_ route: RouteResult?) -> Double? {
    guard let ms = route?.timeMs?.doubleValue else { return nil }
    return ms / 1000
}

private struct TripCard: View {
    let stats: TripStats
    let onStop: () -> Void

    var body: some View {
        VStack(spacing: 10) {
            HStack {
                stat(formatDistanceKm(stats.distanceMeters), "Distance")
                stat(formatDuration(stats.durationMs), "Time")
                stat(formatSpeedKmh(stats.currentSpeedMps), "Speed")
            }
            // Lean and g are only shown where the vehicle actually measures
            // them; in a car the lean number is the phone moving in its cradle.
            if stats.mode.tracksLean {
                HStack {
                    stat(formatLeanAngle(stats.currentLeanAngleDeg), "Lean")
                    stat(formatLeanAngle(stats.maxLeanAngleDeg), "Max lean")
                    stat(formatGForce(stats.maxGForce), "Max g")
                }
            }
            Button(role: .destructive, action: onStop) {
                Text("Stop").frame(maxWidth: .infinity).frame(height: 40)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding()
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 20))
    }

    private func stat(_ value: String, _ label: String) -> some View {
        VStack(spacing: 2) {
            Text(value).font(.title3.monospacedDigit().weight(.semibold))
            Text(label).font(.caption2).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity)
    }
}

/// Type-ahead place search, backed by the same Photon instance Android uses.
private struct SearchSheet: View {
    let onPick: (GeocodeResult) -> Void

    @State private var query = ""
    @State private var results: [GeocodeResult] = []
    @State private var searching = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                if !query.isEmpty && results.isEmpty && !searching {
                    Text("Nothing found").foregroundStyle(.secondary)
                }
                ForEach(results, id: \.name) { result in
                    Button(result.name) {
                        RecentSearchStore.shared.save(result: result)
                        onPick(result)
                    }
                }
            }
            .overlay { if searching { ProgressView() } }
            .searchable(text: $query, prompt: "Search a place")
            .navigationTitle("Search")
            .toolbar {
                Button("Cancel") { dismiss() }
            }
            .task { results = RecentSearchStore.shared.load() }
            .task(id: query) {
                guard query.count >= 2 else { return }
                // Debounce: Photon is a type-ahead geocoder, but this is
                // someone's own server on the end of a tunnel.
                try? await Task.sleep(for: .milliseconds(300))
                guard !Task.isCancelled else { return }
                searching = true
                results = (try? await Geocoder.shared.search(
                    query: query, near: nil, limit: 8)) ?? []
                searching = false
            }
        }
    }
}

/// An observable source of the signed-in rider's handle, for the circle-fix
/// poll above. `SettingsValues.shared.authUsername` is a plain Kotlin
/// getter — nothing publishes on it, so keying `.task(id:)` on it directly
/// only re-evaluates when something else happens to recompute this view.
/// Same shape as `FriendsModel`/`CirclesModel`/`LaunchSyncGate`: watch the
/// StateFlow itself.
@MainActor
final class CircleFixUsernameModel: ObservableObject {
    @Published var username = ""

    private let watcher = SettingsFlows.shared.authUsername()

    init() {
        watcher.watch { [weak self] in self?.username = self?.watcher.value ?? "" }
    }

    deinit { watcher.cancel() }
}

/// The selected vehicle, which is persisted because the trip recorder reads it
/// too: an auto-detected trip has no other way to know what it is.
@MainActor
final class TripModeModel: ObservableObject {
    @Published var mode: TravelMode = .car

    private let watcher = SettingsFlows.shared.tripMode()

    init() {
        watcher.watch { [weak self] in self?.mode = self?.watcher.value ?? .car }
    }

    deinit { watcher.cancel() }
}
