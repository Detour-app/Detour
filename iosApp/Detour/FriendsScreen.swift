import SwiftUI
import DetourShared

/// Account, friends, the shared leaderboard, and convoys.
///
/// The server keys everything on the signed-in user, so the whole screen is
/// gated on an account. What a friend can see is decided server-side and is
/// deliberately narrow: aggregate stats and badge ids, never trips or traces.
struct FriendsScreen: View {

    @StateObject private var model = FriendsModel()

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
            // session to load anything with, so `reload()`'s
            // `guard signedIn else { return }` bails and the task never runs
            // again on its own. Without the `id:`, a successful sign-in swaps
            // the Group to `signedInList` with nothing having been loaded —
            // an empty Leaderboard/Requests/Convoys that only a manual
            // pull-to-refresh or tab switch fixes. Keying on `signedIn` makes
            // SwiftUI re-run the task the moment a session appears.
            .task(id: model.signedIn) { await model.reload() }
            .refreshable { await model.reload() }
            .alert("Something went wrong", isPresented: $model.showError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(model.error ?? "")
            }
        }
    }

    private var signedInList: some View {
        List {
            if !model.incoming.isEmpty {
                Section("Requests") {
                    ForEach(model.incoming, id: \.self) { name in
                        HStack {
                            Text(name)
                            Spacer()
                            Button("Accept") { model.respond(to: name, accept: true) }
                                .buttonStyle(.borderless)
                            Button("Decline") { model.respond(to: name, accept: false) }
                                .buttonStyle(.borderless)
                                .tint(.secondary)
                        }
                    }
                }
            }

            Section("Leaderboard") {
                if model.leaderboard.isEmpty {
                    Text("Add a friend to compare rides.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(model.leaderboard, id: \.username) { friend in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(friend.username).font(.body.weight(.medium))
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

            Section("Add a friend") {
                HStack {
                    TextField("Username", text: $model.addName)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Button("Send") { model.sendRequest() }
                        .disabled(model.addName.isEmpty || model.busy)
                }
                ForEach(model.outgoing, id: \.self) { name in
                    HStack {
                        Text(name)
                        Spacer()
                        Text("pending").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }

            Section("Convoys") {
                ForEach(model.convoys, id: \.id) { convoy in
                    VStack(alignment: .leading, spacing: 4) {
                        HStack {
                            Text(convoy.name).font(.body.weight(.medium))
                            Spacer()
                            if convoy.status == "invited" {
                                Button("Join") { model.respondToConvoy(convoy, accept: true) }
                                    .buttonStyle(.borderless)
                                Button("Decline") { model.respondToConvoy(convoy, accept: false) }
                                    .buttonStyle(.borderless)
                                    .tint(.secondary)
                            } else if ConvoyLiveClient.shared.activeConvoyId == convoy.id {
                                Button("Go offline") { ConvoyLiveClient.shared.leave() }
                                    .buttonStyle(.borderless)
                            } else {
                                Button("Go live") {
                                    ConvoyLiveClient.shared.join(convoyId: convoy.id)
                                }
                                .buttonStyle(.borderless)
                                .disabled(!Features.shared.liveRelay)
                                Button("Leave", role: .destructive) { model.leave(convoy) }
                                    .buttonStyle(.borderless)
                            }
                        }
                        Text(convoy.members.map(\.username).joined(separator: ", "))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        if !Features.shared.liveRelay {
                            Text(Features.shared.liveRelayNotice)
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
                HStack {
                    TextField("New convoy", text: $model.newConvoyName)
                    Button("Create") { model.createConvoy() }
                        .disabled(model.newConvoyName.isEmpty || model.busy)
                }
            }

            Section {
                Toggle("Share fog of war", isOn: Binding(
                    get: { model.shareFog },
                    set: { model.setShareFog($0) }
                ))
                Button("Sign out", role: .destructive) { model.signOut() }
            } footer: {
                Text("Sharing is mutual: the server only hands you a friend's traces when you are sharing yours too.")
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
    // waiting for it — the app was killed behind the browser. Shown once,
    // then cleared, the same shape as CircleNotifications.PendingCircleOpen.
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
                    Text("""
                        No identity provider is configured, so there is nobody to \
                        sign in to. Set the sign-in realm under Settings → Own server.
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
        guard let message = orphaned.message else { return }
        orphanedMessage = message
        orphaned.message = nil
    }
}

@MainActor
final class FriendsModel: ObservableObject {

    @Published var signedIn = false
    @Published var username = ""
    @Published var addName = ""
    @Published var newConvoyName = ""

    @Published private(set) var friends: [String] = []
    @Published private(set) var incoming: [String] = []
    @Published private(set) var outgoing: [String] = []
    @Published private(set) var leaderboard: [FriendStats] = []
    // `DetourShared.Group`, qualified: SwiftUI also exports a `Group` type,
    // and the two would otherwise collide in every file that imports both.
    @Published private(set) var convoys: [DetourShared.Group] = []
    @Published var shareFog = false
    @Published var busy = false
    @Published var error: String?
    @Published var showError = false

    private let token = SettingsFlows.shared.authToken()
    private let name = SettingsFlows.shared.authUsername()
    private let fogSharing = SettingsFlows.shared.shareFog()

    init() {
        token.watch { [weak self] in
            self?.signedIn = !(self?.token.value.isEmpty ?? true)
        }
        name.watch { [weak self] in
            guard let self, self.signedIn else { return }
            self.username = self.name.value
        }
        fogSharing.watch { [weak self] in self?.shareFog = self?.fogSharing.value ?? false }
    }

    deinit {
        [token, name, fogSharing].forEach { $0.cancel() }
    }

    func reload() async {
        guard signedIn else { return }
        do {
            let lists = try await Friends.shared.lists()
            friends = lists.friends
            incoming = lists.incoming
            outgoing = lists.outgoing
            leaderboard = try await Friends.shared.stats()
            convoys = try await Groups.shared.list(kind: "convoy")
        } catch {
            report(error)
        }
    }

    // Every action follows the same shape: run it, then re-read the server's
    // view rather than patching the local copy, so a request that crossed with
    // someone else's can't leave the two disagreeing.
    private func act(_ block: @escaping () async throws -> Void) {
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

    func signOut() {
        act { try await Account.shared.signOut() }
    }

    func sendRequest() {
        act { [self] in
            _ = try await Friends.shared.request(username: addName.trimmed())
            addName = ""
        }
    }

    func respond(to name: String, accept: Bool) {
        act { try await Friends.shared.respond(username: name, accept: accept) }
    }

    func createConvoy() {
        act { [self] in
            _ = try await Groups.shared.create(kind: "convoy", name: newConvoyName.trimmed())
            newConvoyName = ""
        }
    }

    func respondToConvoy(_ convoy: DetourShared.Group, accept: Bool) {
        act { try await Groups.shared.respond(groupId: convoy.id, accept: accept) }
    }

    func leave(_ convoy: DetourShared.Group) {
        act { try await Groups.shared.leave(groupId: convoy.id) }
    }

    func setShareFog(_ value: Bool) {
        Settings.shared.setShareFog(value: value)
        // Tell the server now: leaving it to the next trip sync would keep
        // serving traces after the switch went off.
        Task { _ = try? await SyncClient.shared.sync() }
    }

    private func report(_ error: Error) {
        // The shared core's exceptions carry the server's own wording
        // ("username already taken"), which is what should reach the user.
        self.error = (error as NSError).localizedDescription
        showError = true
    }
}

private extension String {
    func trimmed() -> String { trimmingCharacters(in: .whitespacesAndNewlines) }
}
