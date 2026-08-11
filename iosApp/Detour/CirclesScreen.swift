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
/// with.
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
            SwiftUI.Group {
                if !model.configured {
                    unavailable("No sync server configured. Set one in Settings first — circles live on your own server.")
                } else if !model.signedIn {
                    unavailable("Sign in under Friends first — circles share that same friends list.")
                } else if let selected = selectedCircle {
                    CircleDetailView(
                        circle: selected,
                        username: model.username,
                        busy: model.busy,
                        onInvite: { invitingTo = selected },
                        onLeave: {
                            mapState.setViewed(nil)
                            model.act { try await Groups.shared.leave(groupId: selected.id) }
                        },
                        onToggleSharing: { sharing in
                            model.act {
                                _ = try await Groups.shared.setSharing(groupId: selected.id, sharing: sharing)
                            }
                        }
                    )
                } else {
                    circleList
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
            .task { await model.reload() }
            .refreshable { await model.reload() }
            .alert("Something went wrong", isPresented: $model.showError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.errorMessage ?? "")
            }
            .sheet(isPresented: $creatingNew) {
                CreateCircleSheet { name in
                    creatingNew = false
                    model.act { _ = try await Groups.shared.create(kind: "circle", name: name) }
                }
            }
            .sheet(isPresented: invitingPresented) {
                if let circle = invitingTo {
                    InviteToCircleSheet(circleName: circle.name) { username in
                        invitingTo = nil
                        model.act {
                            _ = try await Groups.shared.invite(groupId: circle.id, username: username)
                        }
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
        model.circles.first { $0.id == mapState.viewedCircleId }
    }

    private var circleList: some View {
        List {
            Section {
                if model.circles.isEmpty {
                    Text("No circles yet. A circle is always-on, low-cadence location sharing with family or roommates — unlike a convoy it doesn't end when a ride does, and there's no push-to-talk.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.circles, id: \.id) { circle in
                        CircleRow(
                            circle: circle,
                            busy: model.busy,
                            onOpen: { mapState.setViewed(circle.id) },
                            onAccept: {
                                model.act { try await Groups.shared.respond(groupId: circle.id, accept: true) }
                            },
                            onDecline: {
                                model.act { try await Groups.shared.respond(groupId: circle.id, accept: false) }
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

@MainActor
final class CirclesModel: ObservableObject {

    @Published private(set) var circles: [DetourShared.Group] = []
    @Published var busy = false
    @Published var errorMessage: String?
    @Published var showError = false

    var configured: Bool { SyncClient.shared.configured() }
    var signedIn: Bool { Account.shared.signedIn }
    var username: String { SettingsValues.shared.authUsername }

    func reload() async {
        guard configured, signedIn else { return }
        do {
            circles = try await Groups.shared.list(kind: "circle")
        } catch {
            report(error)
        }
    }

    /// Every action follows the same shape as `FriendsModel.act`: run it, then
    /// re-read the server's view rather than patching the local copy.
    func act(_ block: @escaping () async throws -> Void) {
        busy = true
        Task {
            do {
                try await block()
                await reload()
            } catch {
                report(error)
            }
            busy = false
        }
    }

    private func report(_ error: Error) {
        errorMessage = (error as NSError).localizedDescription
        showError = true
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

private struct CircleDetailView: View {
    let circle: DetourShared.Group
    let username: String
    let busy: Bool
    let onInvite: () -> Void
    let onLeave: () -> Void
    let onToggleSharing: (Bool) -> Void

    @State private var places: [CirclePlace] = []
    @State private var events: [PlaceEvent] = []
    @State private var placesBusy = false
    @State private var placesError: String?
    @State private var shareOpen = false
    @State private var dataReloads = 0
    /// Local mirror of `CircleNotifications.notifyEnabled` — that store is
    /// plain `UserDefaults`, not a `StateFlow` the view can bind to
    /// directly, so this is what the Toggle below actually reads/writes.
    @State private var notifyOn = true

    private var mine: GroupMember? {
        circle.members.first { $0.username == username }
    }

    var body: some View {
        List {
            Section("Members") {
                ForEach(circle.members, id: \.username) { member in
                    CircleMemberRow(member: member, isMe: member.username == username)
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
                    .disabled(busy)
                    Toggle(isOn: Binding(
                        get: { notifyOn },
                        set: { on in
                            notifyOn = on // optimistic; reset below if denied
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
                                        placesError = "Notifications are turned off for Detour in iOS Settings."
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
                    .disabled(busy)
                }
                HStack {
                    Button("Invite", action: onInvite).disabled(busy)
                    Spacer()
                    Button("Leave", role: .destructive, action: onLeave).disabled(busy)
                }
            }

            Section {
                if let placesError {
                    Text(placesError).font(.caption).foregroundStyle(.red)
                }
                if places.isEmpty {
                    Text("No places shared yet. Sharing one lets the circle see arrivals and departures there — the place stays yours, only revoked when you leave.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(places, id: \.serverId) { place in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(place.place.name).font(.body.weight(.medium))
                                Text("Shared by \(place.owner) · \(Int(place.radiusM)) m radius")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            if place.owner == username {
                                Button(role: .destructive) {
                                    act { try await CirclePlaces.shared.delete(serverId: place.serverId) }
                                } label: {
                                    Image(systemName: "trash")
                                }
                                .disabled(placesBusy)
                            }
                        }
                    }
                }
            } header: {
                HStack {
                    Text("Shared places")
                    Spacer()
                    Button { dataReloads += 1 } label: { Image(systemName: "arrow.clockwise") }
                    Button { shareOpen = true } label: { Image(systemName: "plus") }
                }
            }

            Section("Recent activity") {
                if events.isEmpty {
                    Text("No arrivals or departures yet.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(events.prefix(20), id: \.id) { event in
                        // The place may since have been unshared/deleted; say
                        // so rather than dropping the event, which happened
                        // and stays true either way.
                        let placeName = places.first { $0.place.id == event.placeId }?.place.name
                            ?? "a since-removed place"
                        let verb = event.kind == "arrive" ? "arrived at" : "left"
                        Text("\(event.username) \(verb) \(placeName) — \(relativeAge(event.tsMs))")
                            .font(.footnote)
                    }
                }
            }
        }
        // No push (docs/CIRCLES_AND_CONVOYS.md section 6), so places and
        // events are only ever as fresh as the last time this screen loaded
        // them — on open, after a mutation, or the refresh button above.
        .task(id: dataReloads) { await loadPlacesAndEvents() }
        .onAppear { notifyOn = CircleNotifications.shared.notifyEnabled(circleId: circle.id) }
        .sheet(isPresented: $shareOpen) {
            SharePlaceSheet { place, radiusM in
                shareOpen = false
                act { try await CirclePlaces.shared.share(groupId: circle.id, place: place, radiusM: radiusM) }
            }
        }
    }

    private func loadPlacesAndEvents() async {
        do {
            async let p = CirclePlaces.shared.places(groupId: circle.id)
            async let e = CircleEvents.shared.events(groupId: circle.id, sinceMs: 0)
            places = try await p
            events = try await e.sorted { $0.tsMs > $1.tsMs }
            placesError = nil
        } catch {
            placesError = (error as NSError).localizedDescription
        }
    }

    private func act(_ block: @escaping () async throws -> Void) {
        placesBusy = true
        Task {
            do {
                try await block()
                dataReloads += 1
            } catch {
                placesError = (error as NSError).localizedDescription
            }
            placesBusy = false
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
