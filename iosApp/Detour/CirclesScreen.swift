import SwiftUI
import DetourShared

/// A plain geofence radius suggestion — big enough that ordinary GPS jitter at
/// a house or a workplace doesn't sit right on the line, small enough that it
/// doesn't spill onto a neighbour's place. Editable per share. Matches
/// Android's `DEFAULT_CIRCLE_PLACE_RADIUS_M`.
private let defaultCirclePlaceRadiusM = 150.0

/// Which circle `CirclesScreen` currently has open, if any — the places and
/// events it shows are scoped to that one. The map no longer reads it: it
/// draws every circle you're in, all the time (docs/CIRCLES_AND_CONVOYS.md
/// section 2), so there is nothing here for leaving the screen to disagree
/// with. Still needed independently of `CirclesStore.state.selectedId`:
/// `RootView` sets this straight from a tapped arrival/departure notification
/// (see its `PendingCircleOpen` handling), before `CirclesScreen` — and the
/// store behind it — even exist for this session's circles tab.
@MainActor
final class CircleMapState: ObservableObject {
    static let shared = CircleMapState()
    private init() {}

    @Published private(set) var viewedCircleId: String?

    func setViewed(_ id: String?) {
        viewedCircleId = id
    }
}

/// Circles: the same `Groups` gate as convoys, opposite policy — a circle
/// survives being alone in it, has a pause switch instead of push-to-talk, and
/// shows a low-cadence last-known position plus shared places and their
/// arrival/departure events instead of a live map
/// (docs/CIRCLES_AND_CONVOYS.md sections 2 and 7). Kept as its own tab, not
/// folded into FriendsScreen's convoy section — the doc calls merging the two
/// UIs "the one merge with no payoff", since a circle and a convoy card have
/// almost nothing visually in common once sharing state, places and events
/// are on screen.
struct CirclesScreen: View {

    @StateObject private var model = CirclesModel()
    @ObservedObject private var mapState = CircleMapState.shared

    @State private var creatingNew = false
    @State private var invitingTo: DetourShared.Group?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                // The list-scoped banner: create/invite/respond/leave/toggle
                // failures all land in `CirclesState.error`, which is shown
                // above whichever branch is on screen — the same place
                // Android's `CirclesScreen.kt` shows it, above both
                // `CircleListSection` and `CircleDetailSection`, since a
                // detail-pane action (leave, toggle sharing) can set it too.
                if let error = model.state.error {
                    Text(error)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .padding([.horizontal, .top])
                }
                SwiftUI.Group {
                    if !model.configured {
                        unavailable("No sync server configured. Set one in Settings first — circles live on your own server.")
                    } else if !model.signedIn {
                        unavailable("Sign in under Friends first — circles share that same friends list.")
                    } else if let selected = selectedCircle {
                        CircleDetailView(
                            circle: selected,
                            username: model.username,
                            riderId: model.riderId,
                            state: model.state,
                            onInvite: { invitingTo = selected },
                            onLeave: {
                                mapState.setViewed(nil)
                                Task { _ = try? await CirclesStore.shared.leave(groupId: selected.id) }
                            },
                            onToggleSharing: { sharing in
                                Task {
                                    _ = try? await CirclesStore.shared.setSharing(groupId: selected.id, sharing: sharing)
                                }
                            }
                        )
                    } else {
                        circleList
                    }
                }
            }
            .navigationTitle(selectedCircle?.name ?? "Circles")
            .toolbar {
                // Matches Android: no top-bar create action — "New circle"
                // lives inline above the list itself (see `circleList`).
                if selectedCircle != nil {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button {
                            mapState.setViewed(nil)
                        } label: {
                            Label("Circles", systemImage: "chevron.left")
                        }
                    }
                }
            }
            // Keyed on `signedIn`, not bare — see `FriendsModel`'s task for
            // why: a bare `.task` only runs at this Group's first appearance,
            // which on a mid-session sign-in (started from the Friends tab)
            // is long past. Without the `id:`, switching to Circles after
            // signing in shows the "sign in first" gate clear but the list
            // still empty until a pull-to-refresh or restart.
            .task(id: model.signedIn) { await model.reload() }
            // Reconciles `CirclesStore`'s own selection with `mapState`
            // whenever the latter changes — a tap in `circleList`, the back
            // button clearing it, or `RootView` setting it from a
            // notification tap before this screen ever appeared. Replaces
            // both the old `CirclesModel`'s deep-link `LaunchedEffect`
            // equivalent and `CircleDetailView.loadPlacesAndEvents()`: this
            // is the one place that now loads a circle's places/events.
            //
            // `.task(id:)` marks its predecessor's Swift `Task` cancelled
            // whenever `viewedCircleId` changes again before the first
            // finishes, but that does not stop the Kotlin coroutine behind
            // `CirclesStore.shared.select`: an exported `suspend fun`
            // compiles to an ObjC completion-handler bridge with no
            // cancellation path, so the predecessor's request keeps running
            // and its result still arrives. What actually keeps that stale
            // result from landing under the newer circle's heading is
            // `CirclesState.commitIfViewing`, inside `CirclesStore.loadDetail`
            // — see its doc there — not cancellation.
            //
            // `CirclesStore.select` never throws for an ordinary failure
            // either — it reports through `CirclesState.detailError` instead
            // — so `try?` here is mostly ceremony: the only thing it could
            // ever discard is a genuine Swift-side cancellation error, thrown
            // if this `Task` is torn down before the bridge call even starts.
            // Silently discarding that is exactly what the old
            // `guard !Task.isCancelled else { return }` in
            // `loadPlacesAndEvents()` did: a cancelled load must not raise
            // "Something went wrong", and with nothing here to report one in
            // the first place, that effect falls out for free.
            .task(id: mapState.viewedCircleId) {
                try? await CirclesStore.shared.select(groupId: mapState.viewedCircleId)
            }
            .refreshable { await model.reload() }
            .sheet(isPresented: $creatingNew) {
                CreateCircleSheet { name in
                    creatingNew = false
                    Task { _ = try? await CirclesStore.shared.create(name: name) }
                }
            }
            .sheet(isPresented: invitingPresented) {
                if let circle = invitingTo {
                    InviteToCircleSheet(circleName: circle.name) { username in
                        invitingTo = nil
                        Task { _ = try? await CirclesStore.shared.invite(groupId: circle.id, username: username) }
                    }
                }
            }
        }
    }

    // `Group` doesn't conform to `Identifiable` (a Kotlin bridge type, kept
    // free of Swift-only protocol conformances elsewhere in the app too), so
    // presentation is driven off a derived Bool binding, same as RoutesScreen
    // does for its own optional-item sheets.
    private var invitingPresented: Binding<Bool> {
        Binding(get: { invitingTo != nil }, set: { if !$0 { invitingTo = nil } })
    }

    private var selectedCircle: DetourShared.Group? {
        model.state.circles.first { $0.id == mapState.viewedCircleId }
    }

    private var circleList: some View {
        List {
            Section {
                if model.state.circles.isEmpty {
                    Text("No circles yet. A circle is always-on, low-cadence location sharing with family or roommates — unlike a convoy it doesn't end when a ride does, and there's no push-to-talk.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.state.circles, id: \.id) { circle in
                        CircleRow(
                            circle: circle,
                            busy: model.state.busy,
                            onOpen: { mapState.setViewed(circle.id) },
                            onAccept: {
                                Task { _ = try? await CirclesStore.shared.respond(groupId: circle.id, accept: true) }
                            },
                            onDecline: {
                                Task { _ = try? await CirclesStore.shared.respond(groupId: circle.id, accept: false) }
                            }
                        )
                    }
                }
            } header: {
                HStack {
                    Text("Your circles")
                    Spacer()
                    Button {
                        creatingNew = true
                    } label: {
                        Label("New circle", systemImage: "plus")
                    }
                }
            }
        }
    }

    private func unavailable(_ text: String) -> some View {
        Text(text)
            .foregroundStyle(.secondary)
            .padding()
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
    }
}

/// The token/username pair every screen that gates on an account needs, plus
/// the one feature store this screen binds to — same house shape
/// `SettingsModel` uses in SettingsScreen.swift: hold the watchers, mirror
/// their values into `@Published`, cancel every one in `deinit`. Owns
/// watchers, not decisions — the reload/act/error logic this used to hold is
/// the same logic the Compose screen held, and it now lives once in
/// `CirclesStore` (commonMain).
@MainActor
final class CirclesModel: ObservableObject {

    @Published var signedIn = false
    @Published var username = ""
    @Published var riderId = ""
    @Published private(set) var state: CirclesState

    var configured: Bool { SyncClient.shared.configured() }

    // Same shape as `FriendsModel`: `signedIn`/`username` need to be
    // `@Published`, fed by a token watcher, before `.task(id:)` above has
    // anything to key on — a computed `var` over `Account.shared.signedIn`
    // publishes nothing and only happened to look reactive because something
    // else (a scenePhase change) was recomputing the view anyway.
    private let token = SettingsFlows.shared.authToken()
    private let name = SettingsFlows.shared.authUsername()
    private let riderIdWatcher = SettingsFlows.shared.authRiderId()
    private let circlesFlow = FeatureFlows.shared.circles()

    init() {
        state = circlesFlow.value

        token.watch { [weak self] in
            self?.signedIn = !(self?.token.value.isEmpty ?? true)
        }
        name.watch { [weak self] in
            // Clears rather than freezes when the session goes away — same
            // fix as FriendsModel's matching watcher, and just as needed
            // here: a frozen `username` left `place.ownerId == riderId` below
            // showing the previous rider's unshare affordance over a place
            // that was never theirs to remove.
            guard let self else { return }
            self.username = self.signedIn ? self.name.value : ""
        }
        riderIdWatcher.watch { [weak self] in
            // Same clear-rather-than-freeze hazard as `username` above.
            guard let self else { return }
            self.riderId = self.signedIn ? self.riderIdWatcher.value : ""
        }
        circlesFlow.watch { [weak self] in
            guard let self else { return }
            self.state = self.circlesFlow.value
        }
    }

    deinit {
        [token, name, riderIdWatcher, circlesFlow].forEach { $0.cancel() }
    }

    /// `CirclesStore.reload` never throws for an ordinary failure — it
    /// reports through `CirclesState.error` instead — but
    /// `CancellationException` still crosses (it is
    /// `@Throws(Exception::class)`), so Swift still needs `try`. `try?` lets
    /// a cancelled reload discard silently, same as `FriendsModel.reload`.
    func reload() async {
        guard configured, signedIn else { return }
        try? await CirclesStore.shared.reload()
    }
}

private struct CircleRow: View {
    let circle: DetourShared.Group
    let busy: Bool
    let onOpen: () -> Void
    let onAccept: () -> Void
    let onDecline: () -> Void

    var body: some View {
        // Not a Button (which would nest under the Accept/Decline buttons
        // below) — a plain tap gesture, gated the same way Android's
        // `Modifier.clickable` only applies once the invite is accepted.
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(circle.name).font(.body.weight(.bold))
                Spacer()
                if circle.status == "invited" {
                    Button(action: onAccept) {
                        Image(systemName: "checkmark.circle.fill")
                    }
                    .disabled(busy)
                    .buttonStyle(.borderless)
                    Button(action: onDecline) {
                        Image(systemName: "xmark.circle")
                    }
                    .disabled(busy)
                    .buttonStyle(.borderless)
                }
            }
            Text(circle.members.map { $0.username + ($0.status == "invited" ? " (invited)" : "") }
                .joined(separator: ", "))
                .font(.caption)
                .foregroundStyle(.secondary)
        }
        .contentShape(Rectangle())
        .onTapGesture { if circle.status == "accepted" { onOpen() } }
    }
}

/// One member row: name, "(you)"/"invited" suffix, and a sharing indicator —
/// mirrors Android's `MemberRow` in CirclesScreen.kt.
private struct CircleMemberRow: View {
    let member: GroupMember
    let isMe: Bool

    var body: some View {
        HStack {
            Text(member.username + suffix)
            Spacer()
            Image(systemName: member.sharing ? "eye" : "eye.slash")
                .foregroundStyle(.secondary)
        }
    }

    private var suffix: String {
        var s = ""
        if isMe { s += " (you)" }
        if member.status == "invited" { s += " · invited" }
        return s
    }
}

/// The detail pane for one circle: members, the sharing/notify switches,
/// shared places and recent arrivals/departures. A pure function of
/// `circle`/`username`/`state` plus the three callbacks that need to
/// coordinate with `CirclesScreen`'s own state (`invitingTo`, `mapState`) —
/// every other action (refresh, share, unshare) calls `CirclesStore`
/// directly, same as Android's `CircleDetailSection` calls `CirclesStore`
/// directly for the equivalents.
///
/// Bound to `state.detailBusy`/`state.detailError` for the shared-places
/// section only; everything else here (the sharing/notify switches, Invite,
/// Leave) reads `state.busy`/`state.error` instead — opening or refreshing
/// this pane must never grey those out, which is exactly the bug the split
/// into two busy/error pairs fixed (see `CirclesState`'s doc in
/// CirclesStore.kt).
private struct CircleDetailView: View {
    let circle: DetourShared.Group
    let username: String
    /// This device's own account id — see `CirclesModel`'s mirror. What every
    /// "is this me"/"did I share this" check below now compares on (#133),
    /// in place of `username`.
    let riderId: String
    let state: CirclesState
    let onInvite: () -> Void
    let onLeave: () -> Void
    let onToggleSharing: (Bool) -> Void

    @State private var shareOpen = false
    /// Local mirror of `CircleNotifications.notifyEnabled` — that store is
    /// plain `UserDefaults`, not a `StateFlow` the view can bind to
    /// directly, so this is what the Toggle below actually reads/writes.
    @State private var notifyOn = true
    /// A denied OS notification permission is entirely local to this device
    /// and this toggle — it is not shared state, so it does not belong in
    /// `CirclesStore` (that used to be `reportDetailError`, which put an
    /// iOS-only string into shared state, rendered under the wrong section,
    /// and could be wiped by any later `selecting()`/`detailStarting()`).
    @State private var notifyError: String?

    /// Compares the account id, not the handle (#133) — this used to compare
    /// `$0.username == username`, which agrees with the id-based check as long
    /// as nobody's handle has changed since the member list was last fetched,
    /// and disagrees the moment it has.
    private var mine: GroupMember? {
        circle.members.first { $0.id.value == riderId }
    }

    var body: some View {
        List {
            Section("Members") {
                ForEach(circle.members, id: \.id.value) { member in
                    CircleMemberRow(member: member, isMe: member.id.value == riderId)
                }
                if let mine {
                    Toggle(isOn: Binding(
                        get: { mine.sharing },
                        set: onToggleSharing
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Share my location").font(.body.weight(.medium))
                            Text(mine.sharing
                                 ? "Posting your position to this circle every couple of minutes"
                                 : "Paused — nothing is being shared")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .disabled(state.busy)
                    Toggle(isOn: Binding(
                        get: { notifyOn },
                        set: { on in
                            notifyOn = on // optimistic; reset below if denied
                            notifyError = nil
                            Task {
                                if on {
                                    let granted = await CircleNotifications.shared.requestAuthorizationIfNeeded()
                                    CircleNotifications.shared.setNotifyEnabled(circleId: circle.id, granted)
                                    if granted {
                                        ConvoyLiveClient.shared.addNotifyingCircle(circle.id)
                                    } else {
                                        // The toggle has to reflect reality,
                                        // not the tap that caused it.
                                        notifyOn = false
                                        // Local to this toggle, not
                                        // `CirclesState.detailError`: this is
                                        // a denial that happened entirely on
                                        // this device, with nothing to
                                        // reload, so it does not belong in
                                        // shared state (see `notifyError`'s
                                        // own doc above).
                                        notifyError = "Notifications are turned off for Detour in iOS Settings."
                                    }
                                } else {
                                    CircleNotifications.shared.setNotifyEnabled(circleId: circle.id, false)
                                    ConvoyLiveClient.shared.removeNotifyingCircle(circle.id)
                                }
                            }
                        }
                    )) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Notify me about arrivals").font(.body.weight(.medium))
                            Text("A notification when someone else in this circle arrives at or leaves a shared place. Only while Detour is open, or briefly after you reopen it — this app has no push service.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                    .disabled(state.busy)
                    if let notifyError {
                        Text(notifyError).font(.caption).foregroundStyle(.red)
                    }
                }
                HStack {
                    Button("Invite", action: onInvite).disabled(state.busy)
                    Spacer()
                    Button("Leave", role: .destructive, action: onLeave).disabled(state.busy)
                }
            }

            Section {
                if let detailError = state.detailError {
                    Text(detailError).font(.caption).foregroundStyle(.red)
                }
                if state.places.isEmpty {
                    Text("No places shared yet. Sharing one lets the circle see arrivals and departures there — the place stays yours, only revoked when you leave.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(state.places, id: \.serverId) { place in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(place.place.name).font(.body.weight(.medium))
                                // `CirclePlace` carries the owner's id now, not
                                // their handle (#133) — resolved from this
                                // circle's own membership, same as an event's
                                // author below.
                                Text("Shared by \(GroupsKt.handleFor(circle.members, riderId: place.ownerId)) · \(Int(place.radiusM)) m radius")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if place.ownerId.value == riderId {
                                Button(role: .destructive) {
                                    Task { _ = try? await CirclesStore.shared.unsharePlace(serverId: place.serverId) }
                                } label: {
                                    Image(systemName: "trash")
                                }
                                .disabled(state.detailBusy)
                            }
                        }
                    }
                }
            } header: {
                HStack {
                    Text("Shared places")
                    Spacer()
                    Button {
                        Task { try? await CirclesStore.shared.select(groupId: circle.id) }
                    } label: { Image(systemName: "arrow.clockwise") }
                    Button { shareOpen = true } label: { Image(systemName: "plus") }
                }
            }

            Section("Recent activity") {
                if state.events.isEmpty {
                    Text("No arrivals or departures yet.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(state.events.prefix(20), id: \.id) { event in
                        // The place may since have been unshared/deleted; say
                        // so rather than dropping the event, which happened
                        // and stays true either way.
                        let placeName = state.places.first { $0.place.id == event.placeId }?.place.name
                            ?? "a since-removed place"
                        let verb = event.kind == "arrive" ? "arrived at" : "left"
                        // `PlaceEvent` names its rider by id only now (#133) —
                        // resolved from this circle's own membership, same as
                        // a shared place's owner above.
                        Text("\(GroupsKt.handleFor(circle.members, riderId: event.riderId)) \(verb) \(placeName) — \(relativeAge(event.tsMs))")
                            .font(.footnote)
                    }
                }
            }
        }
        .onAppear { notifyOn = CircleNotifications.shared.notifyEnabled(circleId: circle.id) }
        .sheet(isPresented: $shareOpen) {
            SharePlaceSheet { place, radiusM in
                shareOpen = false
                Task {
                    _ = try? await CirclesStore.shared.sharePlace(groupId: circle.id, place: place, radiusM: radiusM)
                }
            }
        }
    }
}

private struct CreateCircleSheet: View {
    let onCreate: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Circle name (Family, Roommates…)", text: $name)
            }
            .navigationTitle("New circle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Create") { onCreate(name.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

/// Invite by username, same shape as convoy's — the server rejects anyone not
/// already a friend either way.
private struct InviteToCircleSheet: View {
    let circleName: String
    let onInvite: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var name = ""

    var body: some View {
        NavigationStack {
            Form {
                TextField("Friend's username", text: $name)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
            .navigationTitle("Invite to \(circleName)")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Invite") { onInvite(name.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        .disabled(name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
    }
}

/// Pick one of the signed-in user's saved places and a geofence radius to
/// share into the circle. Only lists `SavedPlace`s — a circle place is a
/// saved place plus a radius, not a separate thing to create from scratch.
private struct SharePlaceSheet: View {
    let onShare: (SavedPlace, Double) -> Void

    @Environment(\.dismiss) private var dismiss
    @StateObject private var saved = SavedPlacesModel()
    @State private var pickedId: Int64?
    @State private var radiusText = String(Int(defaultCirclePlaceRadiusM))

    private var picked: SavedPlace? {
        saved.items.first { $0.id == pickedId } ?? saved.items.first
    }

    private var radius: Double? {
        Double(radiusText)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Text("The circle sees arrivals and departures here, not your live position.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
                Section {
                    ForEach(saved.items, id: \.id) { place in
                        Button {
                            pickedId = place.id
                        } label: {
                            HStack {
                                Text(place.name)
                                Spacer()
                                if picked?.id == place.id {
                                    Image(systemName: "checkmark")
                                }
                            }
                        }
                    }
                }
                Section {
                    TextField("Radius (metres)", text: $radiusText)
                        .keyboardType(.numberPad)
                }
            }
            .navigationTitle("Share a place")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Share") {
                        if let picked, let radius, radius > 0 { onShare(picked, radius) }
                    }
                    .disabled(picked == nil || !(radius.map { $0 > 0 } ?? false))
                }
            }
        }
    }
}

/// Coarse relative age ("3m ago", "2h ago") — exact seconds never matter for a
/// feature whose fixes only update every couple of minutes anyway. Matches
/// Android's `relativeAge` in CirclesScreen.kt.
private func relativeAge(_ tsMs: Int64) -> String {
    let minutes = max(0, nowMs() - tsMs) / 60_000
    switch minutes {
    case ..<1: return "just now"
    case ..<60: return "\(minutes)m ago"
    case ..<(60 * 24): return "\(minutes / 60)h ago"
    default: return "\(minutes / (60 * 24))d ago"
    }
}
