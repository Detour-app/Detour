import SwiftUI
import DetourShared

/// Account, friends, the shared leaderboard, and convoys.
///
/// The server keys everything on the signed-in user, so the whole screen is
/// gated on an account. What a friend can see is decided server-side and is
/// deliberately narrow: aggregate stats and badge ids, never trips or traces.
struct FriendsScreen: View {

    @StateObject private var model = FriendsModel()
    // Convoys section reads ConvoyLiveClient's own @Published state
    // (connected/lastError/activeConvoyId) directly below — @ObservedObject
    // here is what actually subscribes this view to those, the same pattern
    // ConvoyBar.swift and MapScreen.swift already use. Without it, SwiftUI
    // never re-renders on its own when they change; the caption only moved
    // when some unrelated @Published (model.convoysState) happened to fire
    // too, which is why a sign-out or a 401 used to leave a stale label and
    // caption on screen.
    @ObservedObject private var live = ConvoyLiveClient.shared

    // Not in FriendsStore: signing out is Account's business, not the friend
    // list's, so there is no store `busy` slot it could occupy. It still has
    // to gate the request rows — a revoke on a slow connection used to leave
    // Accept/Decline tappable, which is how you answer a friend request on
    // your way out the door. Matches Android's `FriendsSection` in
    // FriendsScreen.kt, which keeps the same local `signingOut`.
    @State private var signingOut = false

    // "Add a friend" stays local rather than routing through FriendsStore:
    // Android's own `AddFriendDialog` calls `Friends.request` directly, with
    // its own busy/error/status, so the screen's shared `busy` doesn't grey
    // out Accept/Decline while someone is mid-way through typing a username
    // here. Mirrors that boundary exactly.
    @State private var addName = ""
    @State private var addBusy = false
    @State private var addError: String?
    @State private var addStatus: String?

    @State private var newConvoyName = ""

    var body: some View {
        NavigationStack {
            SwiftUI.Group {
                if model.signedIn {
                    signedInList
                } else {
                    SignInForm()
                }
            }
            .navigationTitle(model.signedIn ? model.username : "Friends")
            // Keyed on `signedIn`, not bare: a bare `.task` runs exactly once,
            // at this Group's first appearance — which is before there is a
            // session to load anything with, so a reload's own signed-out
            // guard would just bail and never run again on its own. Without
            // the `id:`, a successful sign-in swaps the Group to
            // `signedInList` with nothing having been loaded — an empty
            // Leaderboard/Requests/Convoys that only a manual pull-to-refresh
            // or tab switch fixes. Keying on `signedIn` makes SwiftUI re-run
            // the task the moment a session appears.
            .task(id: model.signedIn) { await model.reload() }
            .refreshable { await model.reload() }
        }
    }

    private var signedInList: some View {
        List {
            Section {
                HStack {
                    VStack(alignment: .leading) {
                        Text("Signed in as").font(.caption).foregroundStyle(.secondary)
                        Text(model.username).font(.headline)
                    }
                    Spacer()
                    Button("Sign out") {
                        signingOut = true
                        Task {
                            try? await Account.shared.signOut()
                            signingOut = false
                        }
                    }
                    .disabled(signingOut)
                }
            }

            // A banner over the last known good screen, never a reason to
            // blank it — see `FriendsState.failed`'s doc in FriendsStore.kt.
            // Not a `.alert`: the store's error persists until the next
            // action attempt clears it, which doesn't fit a one-shot,
            // dismiss-to-clear alert the way it fits Android's inline Text.
            if let error = model.friendsState.error {
                Section {
                    Text(error).foregroundStyle(.red)
                }
            }

            if model.friendsState.lists == nil {
                Section { ProgressView() }
            } else {
                requestsSection
                leaderboardSection
                addFriendSection
                convoysSection
            }

            Section {
                Toggle("Share fog of war", isOn: Binding(
                    get: { model.shareFog },
                    set: { model.setShareFog($0) }
                ))
            } footer: {
                Text("Sharing is mutual: the server only hands you a friend's traces when you are sharing yours too.")
            }
        }
    }

    @ViewBuilder
    private var requestsSection: some View {
        let incoming = model.friendsState.lists?.incoming ?? []
        if !incoming.isEmpty {
            Section("Requests") {
                ForEach(incoming, id: \.self) { name in
                    HStack {
                        Text(name)
                        Spacer()
                        Button("Accept") {
                            Task { _ = try? await FriendsStore.shared.respond(username: name, accept: true) }
                        }
                        .buttonStyle(.borderless)
                        .disabled(model.friendsState.busy || signingOut)
                        Button("Decline") {
                            Task { _ = try? await FriendsStore.shared.respond(username: name, accept: false) }
                        }
                        .buttonStyle(.borderless)
                        .tint(.secondary)
                        .disabled(model.friendsState.busy || signingOut)
                    }
                }
            }
        }
    }

    /// The signed-in user's own row — `FriendsStore.refreshOwn`'s
    /// synthesized `FriendsState.own` — merged into the ranking the same way
    /// Android's `FriendsScreen.kt` does (`ranked = leaderboard + own,
    /// sortedByDescending`). This own-stats row is the one thing iOS gains
    /// in this release: the computation moved into `FriendsStore` with the
    /// rest of the leaderboard state, so both platforms get it for free.
    private var rankedLeaderboard: [FriendStats] {
        var all = model.friendsState.leaderboard
        if let own = model.friendsState.own { all.append(own) }
        return all.sorted { $0.stats.totalDistanceMeters > $1.stats.totalDistanceMeters }
    }

    private var leaderboardSection: some View {
        Section("Leaderboard") {
            if rankedLeaderboard.isEmpty {
                Text("Add a friend to compare rides.")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(rankedLeaderboard, id: \.username) { friend in
                    let isMe = friend.username == model.username
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(friend.username + (isMe ? " (you)" : ""))
                                .font(.body.weight(isMe ? .bold : .medium))
                            Spacer()
                            Text(formatDistanceKm(friend.stats.totalDistanceMeters))
                                .monospacedDigit()
                        }
                        HStack(spacing: 12) {
                            Label("\(friend.stats.tripCount)", systemImage: "road.lanes")
                            Label(String(format: "%.0f km/h", friend.stats.topSpeedKmh),
                                  systemImage: "speedometer")
                            Label("\(friend.badgeIds.count)", systemImage: "seal.fill")
                        }
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    }
                }
            }
        }
    }

    private var addFriendSection: some View {
        Section("Add a friend") {
            HStack {
                TextField("Username", text: $addName)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                Button("Send") { sendRequest() }
                    .disabled(addName.isEmpty || addBusy)
            }
            if let addError {
                Text(addError).font(.caption).foregroundStyle(.red)
            }
            if let addStatus {
                Text(addStatus).font(.caption).foregroundStyle(.secondary)
            }
            ForEach(model.friendsState.lists?.outgoing ?? [], id: \.self) { name in
                HStack {
                    Text(name)
                    Spacer()
                    Text("pending").font(.caption).foregroundStyle(.secondary)
                }
            }
        }
    }

    /// Bypasses `FriendsStore` on purpose — same boundary Android's
    /// `AddFriendDialog` draws in FriendsScreen.kt: a raw `Friends.request`
    /// call with its own local busy/error/status, not the store's, so a
    /// request in flight doesn't grey out Accept/Decline elsewhere on the
    /// screen. Not cancellable the way `model.reload()` is: this `Task {}`
    /// is a plain unstructured task kicked off from a button tap, nothing
    /// cancels it, so there's no `Task.isCancelled` path worth guarding —
    /// same reasoning the old `FriendsModel.act` doc gave.
    private func sendRequest() {
        let target = addName.trimmed()
        addBusy = true
        addError = nil
        Task {
            do {
                let result = try await Friends.shared.request(username: target)
                addStatus = result == "accepted"
                    ? "You are now friends with \(target)"
                    : "Request sent to \(target)"
                addName = ""
                // The pending row this produces renders directly under the
                // Send button, which is where the rider is looking — the
                // same reload the old `FriendsModel.sendRequest()` got for
                // free by going through `act`, which always reloaded on
                // success. Bypassing `FriendsStore` for the request itself
                // (see this function's own doc above) is not the same as
                // bypassing the refresh that used to follow it. The `catch`
                // below is unaffected: a refusal never reaches here.
                await model.reload()
            } catch {
                addError = (error as NSError).localizedDescription
            }
            addBusy = false
        }
    }

    private var convoysSection: some View {
        Section("Convoys") {
            if let error = model.convoysState.error {
                Text(error).font(.caption).foregroundStyle(.red)
            }
            ForEach(model.convoysState.convoys, id: \.id) { convoy in
                VStack(alignment: .leading, spacing: 4) {
                    HStack {
                        Text(convoy.name).font(.body.weight(.medium))
                        Spacer()
                        if convoy.status == "invited" {
                            Button("Join") {
                                Task { _ = try? await ConvoysStore.shared.respond(groupId: convoy.id, accept: true) }
                            }
                            .buttonStyle(.borderless)
                            .disabled(model.convoysState.busy)
                            Button("Decline") {
                                Task { _ = try? await ConvoysStore.shared.respond(groupId: convoy.id, accept: false) }
                            }
                            .buttonStyle(.borderless)
                            .tint(.secondary)
                            .disabled(model.convoysState.busy)
                        } else if live.activeConvoyId == convoy.id {
                            Button("Go offline") { ConvoyLiveClient.shared.leave() }
                                .buttonStyle(.borderless)
                        } else {
                            Button("Go live") {
                                ConvoyLiveClient.shared.join(convoyId: convoy.id)
                            }
                            .buttonStyle(.borderless)
                            .disabled(!Features.shared.liveRelay)
                            Button("Leave", role: .destructive) {
                                Task { _ = try? await ConvoysStore.shared.leave(groupId: convoy.id) }
                            }
                            .buttonStyle(.borderless)
                            .disabled(model.convoysState.busy)
                        }
                    }
                    Text(convoy.members.map(\.username).joined(separator: ", "))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    if !Features.shared.liveRelay {
                        Text(Features.shared.liveRelayNotice)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else if live.activeConvoyId == convoy.id, !live.connected {
                        // Mirrors Android's FriendsScreen.kt liveStatus: only
                        // shown for the convoy actually being connected to,
                        // and only until connected flips true - lastError is
                        // cleared the moment a "joined" reply arrives (see
                        // ConvoyRelay.applyEvent), so this never lingers.
                        Text(live.lastError ?? "Connecting…")
                            .font(.caption)
                            .foregroundStyle(live.lastError != nil ? .red : .secondary)
                    }
                }
            }
            HStack {
                TextField("New convoy", text: $newConvoyName)
                Button("Create") { createConvoy() }
                    .disabled(newConvoyName.isEmpty || model.convoysState.busy)
            }
        }
    }

    /// Clears the text field only on success — the one branch this screen's
    /// convoy actions take on a return value, same shape the "Add a friend"
    /// send button uses above.
    private func createConvoy() {
        let target = newConvoyName.trimmed()
        Task {
            // A suspend fun returning Boolean arrives as KotlinBoolean, which
            // is an NSNumber and so compares to nothing on its own — same
            // scar as RoutesScreen.swift's `pullInbox()` (~line 199) for
            // KotlinInt. `?.boolValue` is what actually unwraps it to `Bool`.
            if (try? await ConvoysStore.shared.create(name: target))?.boolValue == true {
                newConvoyName = ""
            }
        }
    }
}

/// Signing in is a trip out to the realm's own page and back — authorization
/// code with PKCE, in an `ASWebAuthenticationSession`. Creating an account,
/// changing a password and recovering one all happen on the realm's pages,
/// which is why none of them is offered here. Same copy as the Android
/// screen's, deliberately: one feature described two ways reads as two.
private struct SignInForm: View {

    @StateObject private var signIn = SignIn()
    // Set by DetourApp's onOpenURL when a redirect arrives with no session
    // waiting for it — the app was killed behind the browser. NOT the same
    // one-shot shape as CircleNotifications.PendingCircleOpen any more,
    // despite reading like it at a glance — see the comment on
    // `orphanedMessage` just below for why a local copy replaced clearing the
    // singleton straight from `.onAppear`.
    @ObservedObject private var orphaned = OrphanedSignIn.shared
    // A local copy of `orphaned.message`, captured the moment it arrives.
    // This is what the view renders, not `orphaned.message` directly.
    //
    // The previous shape cleared the singleton from `.onAppear` on the very
    // `Text` the `if let` above it gated — but here, unlike
    // `PendingCircleOpen` (consumed only after a tab switch, an effect with
    // its own trigger, so clearing loses nothing), the render *is* the
    // effect: the message publishes, the body recomputes with the branch
    // true, `onAppear` fires almost immediately after and sets the singleton
    // back to nil, which recomputes the body again with the branch now
    // false. The text was on screen for about one frame — indistinguishable
    // from a Sign in button that silently did nothing, which is the exact
    // failure this message exists to prevent. Copying into local `@State`
    // lets the singleton be cleared right away without the render depending
    // on it still being set.
    @State private var orphanedMessage: String?

    var body: some View {
        Form {
            Section {
                Text("""
                    Sign in to sync your rides and compare stats with friends. \
                    Your trips and explored map stay private — friends only ever \
                    see totals and badges.
                    """)
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                if signIn.configured {
                    if let message = orphanedMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    if let error = signIn.error {
                        Text(error)
                            .font(.footnote)
                            .foregroundStyle(.red)
                    }
                    Button {
                        // A stale orphaned-redirect message must not sit
                        // under a fresh attempt.
                        orphanedMessage = nil
                        Task { await signIn.start() }
                    } label: {
                        if signIn.busy {
                            ProgressView()
                        } else {
                            Text("Sign in")
                        }
                    }
                    .disabled(signIn.busy)
                } else {
                    // Reached only when there is neither a realm nor a server
                    // to ask for one — `signIn.configured` is optimistic now.
                    // A server that has an address but names no realm keeps the
                    // button and reports it on tap, which is actionable where a
                    // missing button is not.
                    Text("""
                        No server or sign-in realm is configured, so there is nobody \
                        to sign in to. Set your server address under Settings → Own server.
                        """)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } header: {
                Text("Account")
            } footer: {
                Text("Opens a browser. New accounts and password changes happen there too.")
            }
        }
        // Picks up a message set before this view ever appeared (the normal
        // case: DetourApp's onOpenURL runs at launch, before the rider has
        // necessarily navigated to this tab) as well as one that arrives
        // while this view is already on screen.
        .onAppear { captureOrphanedMessage() }
        .onChange(of: orphaned.message) { _, _ in captureOrphanedMessage() }
    }

    private func captureOrphanedMessage() {
        // Guarded on `signIn.configured`, matching the `Text` above that
        // renders `orphanedMessage` from inside the same `if signIn.configured`
        // branch. Without this, a `detour://auth/callback` deep link — which
        // `Oidc.isCallback` matches regardless of whether a realm is
        // configured — would capture and clear the singleton here with
        // nothing on screen to show for it: a silent swallow rather than a
        // decision. Chose "guard the capture" over "render outside the
        // branch" because there's nothing actionable to tell a rider about a
        // sign-in realm that isn't even set up; leaving the message parked
        // keeps it available for a later capture, if a realm gets configured
        // and this view reappears before the singleton is otherwise cleared
        // (see `OrphanedSignIn`'s doc and the sign-in-success clear in
        // `SignIn.start()`).
        guard signIn.configured, let message = orphaned.message else { return }
        orphanedMessage = message
        orphaned.message = nil
    }
}

/// The two feature stores this screen binds to, plus the token/username pair
/// every screen that gates on an account needs — same house shape
/// `SettingsModel` uses in SettingsScreen.swift: hold the watchers, mirror
/// their values into `@Published`, cancel every one in `deinit`. Owns
/// watchers, not decisions — the reload/act/error logic this used to hold is
/// the same logic the Compose screen held, and it now lives once in
/// `FriendsStore`/`ConvoysStore` (commonMain).
@MainActor
final class FriendsModel: ObservableObject {

    @Published var signedIn = false
    @Published var username = ""
    @Published private(set) var friendsState: FriendsState
    @Published private(set) var convoysState: ConvoysState
    @Published var shareFog = false

    private let token = SettingsFlows.shared.authToken()
    private let name = SettingsFlows.shared.authUsername()
    private let fogSharing = SettingsFlows.shared.shareFog()
    private let friendsFlow = FeatureFlows.shared.friends()
    private let convoysFlow = FeatureFlows.shared.convoys()

    init() {
        friendsState = friendsFlow.value
        convoysState = convoysFlow.value

        token.watch { [weak self] in
            self?.signedIn = !(self?.token.value.isEmpty ?? true)
        }
        name.watch { [weak self] in
            // Clears rather than freezes when the session goes away: this
            // used to bail out of the assignment entirely while signed out,
            // which left `username` parked on the departed rider's handle
            // indefinitely — and `reload()` below reads this field straight
            // into `refreshOwn(username:)`, which has no signedIn guard of
            // its own on the Kotlin side (see FriendsStore.kt), so a stale
            // handle here was a rider's own row committed under someone
            // else's name.
            guard let self else { return }
            self.username = self.signedIn ? self.name.value : ""
        }
        fogSharing.watch { [weak self] in self?.shareFog = self?.fogSharing.value ?? false }
        friendsFlow.watch { [weak self] in
            guard let self else { return }
            self.friendsState = self.friendsFlow.value
        }
        convoysFlow.watch { [weak self] in
            guard let self else { return }
            self.convoysState = self.convoysFlow.value
        }
    }

    deinit {
        [token, name, fogSharing, friendsFlow, convoysFlow].forEach { $0.cancel() }
    }

    /// Reloads both stores together — the same moment Android's
    /// `FriendsSection` (`LaunchedEffect(username)`) and `ConvoysSection`
    /// (`LaunchedEffect(Unit)`, which only ever enters composition once
    /// signed in) end up reloading at, in FriendsScreen.kt.
    ///
    /// `FriendsStore.reload`/`refreshOwn` and `ConvoysStore.reload` never
    /// throw for an ordinary failure — they report through their own
    /// `state.error` instead — but `CancellationException` still crosses
    /// (every one is `@Throws(Exception::class)`), so Swift still needs
    /// `try`. `try?` is what a cancelled reload deserves here: discarded
    /// silently, no error banner, the same effect the old
    /// `guard !Task.isCancelled else { return }` in `FriendsModel.reload()`
    /// had — this screen no longer needs to spell that guard out itself.
    func reload() async {
        guard signedIn else { return }
        try? await FriendsStore.shared.reload()
        // `refreshOwn` blocks on disk and CPU — see its doc in
        // FriendsStore.kt — and this method runs on `@MainActor` (this is a
        // `.refreshable`, so it repeats on every pull-to-refresh). Same
        // `Task.detached` precedent BadgesScreen.swift's `reload()` already
        // uses for the identical `Coverage.compute()` cost. The state update
        // it produces still reaches this view correctly either way: every
        // `Watcher` (FlowWatcher.kt) collects on `Dispatchers.Main`
        // regardless of which thread called the store action, so the
        // `@Published` mutation in `init` above always lands on the main
        // thread.
        let currentName = username
        _ = await Task.detached { try? await FriendsStore.shared.refreshOwn(username: currentName) }.value
        try? await ConvoysStore.shared.reload()
    }

    func setShareFog(_ value: Bool) {
        Settings.shared.setShareFog(value: value)
        // Tell the server now: leaving it to the next trip sync would keep
        // serving traces after the switch went off.
        Task { _ = try? await SyncClient.shared.sync() }
    }
}

private extension String {
    func trimmed() -> String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
