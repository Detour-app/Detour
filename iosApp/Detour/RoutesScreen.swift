import SwiftUI
import UniformTypeIdentifiers
import DetourShared

/// Saved multi-stop routes: build one in the map editor, export or send one,
/// or pull in whatever friends have sent back.
///
/// `RouteStore` publishes a StateFlow the same way `SavedPlaces` does, and
/// `RoutesModel` below watches it exactly the way `SavedPlacesModel` watches
/// `SavedPlaces` — every mutation (save, rename, remove, an inbox pull)
/// reaches this screen through the flow, with no manual reload anywhere.
struct RoutesScreen: View {

    @StateObject private var model = RoutesModel()
    @State private var renaming: SavedRoute?
    @State private var newName = ""
    @State private var creatingNew = false
    @State private var exportURL: URL?
    @State private var sharingRoute: SavedRoute?
    @State private var importing = false
    @State private var refreshing = false
    @State private var pulledCount: Int?
    @State private var errorMessage: String?
    @State private var showError = false

    var body: some View {
        NavigationStack {
            Group {
                if model.items.isEmpty {
                    ContentUnavailableView(
                        "No saved routes",
                        systemImage: "signpost.right.and.left",
                        description: Text("Build one with the map editor, or import a .gpx file.")
                    )
                } else {
                    List {
                        ForEach(model.items, id: \.id) { route in
                            NavigationLink {
                                RouteEditorScreen(existing: route)
                            } label: {
                                RouteRow(route: route)
                            }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    RouteStore.shared.remove(id: route.id)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                Button {
                                    newName = route.name
                                    renaming = route
                                } label: {
                                    Label("Rename", systemImage: "pencil")
                                }
                                .tint(.indigo)
                                Button {
                                    exportURL = writeGpx(route)
                                } label: {
                                    Label("Share", systemImage: "square.and.arrow.up")
                                }
                                .tint(.blue)
                                Button {
                                    sharingRoute = route
                                } label: {
                                    Label("Send", systemImage: "person.crop.circle.badge.plus")
                                }
                                .tint(.green)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Routes")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button {
                        Task { await pullInbox() }
                    } label: {
                        if refreshing {
                            ProgressView()
                        } else {
                            Image(systemName: "arrow.clockwise")
                        }
                    }
                    .disabled(refreshing)
                }
                ToolbarItemGroup(placement: .navigationBarTrailing) {
                    Button { importing = true } label: {
                        Image(systemName: "square.and.arrow.down")
                    }
                    Button { creatingNew = true } label: {
                        Image(systemName: "plus")
                    }
                }
            }
            .fullScreenCover(isPresented: $creatingNew) {
                NavigationStack {
                    RouteEditorScreen(existing: nil)
                }
            }
            .sheet(isPresented: exportPresented) {
                if let exportURL { ActivityView(activityItems: [exportURL]) }
            }
            .sheet(isPresented: sharingPresented) {
                if let sharingRoute { SendToFriendSheet(route: sharingRoute) }
            }
            // Several Files providers hand a .gpx over as generic `.data`
            // rather than a recognised gpx/xml type — AirDrop and some cloud
            // providers among them — so that has to be accepted too, on top
            // of the extensions Detour actually writes and reads.
            .fileImporter(
                isPresented: $importing,
                allowedContentTypes: [gpxType, .xml, .json, .data],
                allowsMultipleSelection: false
            ) { result in
                handleImport(result)
            }
            .alert("Rename route", isPresented: .constant(renaming != nil)) {
                TextField("Name", text: $newName)
                Button("Save") {
                    if let route = renaming {
                        RouteStore.shared.rename(id: route.id, name: newName)
                    }
                    renaming = nil
                }
                Button("Cancel", role: .cancel) { renaming = nil }
            }
            .alert("Something went wrong", isPresented: $showError) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(errorMessage ?? "")
            }
            .alert("New routes", isPresented: .constant(pulledCount != nil)) {
                Button("OK") { pulledCount = nil }
            } message: {
                Text("Pulled \(pulledCount ?? 0) route\(pulledCount == 1 ? "" : "s") from friends.")
            }
        }
    }

    // MARK: State plumbing

    private var exportPresented: Binding<Bool> {
        Binding(get: { exportURL != nil }, set: { if !$0 { exportURL = nil } })
    }

    private var sharingPresented: Binding<Bool> {
        Binding(get: { sharingRoute != nil }, set: { if !$0 { sharingRoute = nil } })
    }

    private let gpxType = UTType(filenameExtension: "gpx") ?? .xml

    private func report(_ error: Error) {
        errorMessage = (error as NSError).localizedDescription
        showError = true
    }

    /// Writes [route] to a temp file for the share sheet, exactly the way
    /// TripDetailScreen writes a trip's GPX — except the bytes come from the
    /// shared `RouteGpx` builder rather than a hand-rolled XML string, since a
    /// saved route already has one canonical writer in `:shared`.
    private func writeGpx(_ route: SavedRoute) -> URL {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent(RouteGpx.shared.fileName(route: route))
        try? RouteGpx.shared.buildGpx(route: route).write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    private func handleImport(_ result: Result<[URL], Error>) {
        guard case .success(let urls) = result, let url = urls.first else { return }
        guard url.startAccessingSecurityScopedResource() else {
            errorMessage = "Couldn't access that file."
            showError = true
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        guard let data = try? Data(contentsOf: url), let text = String(data: data, encoding: .utf8) else {
            errorMessage = "Couldn't read that file."
            showError = true
            return
        }
        guard let route = RouteGpx.shared.parseRouteFile(text: text, nowMs: nowMs()) else {
            errorMessage = "That file isn't a route Detour understands."
            showError = true
            return
        }
        RouteStore.shared.save(route: route)
    }

    /// Pulls whatever friends have sent: `RouteShare.pullInbox()` does the
    /// fetch-save-delete-count round trip in `:shared` (giving each route a
    /// fresh local id rather than the sender's, which `RouteStore.save`'s
    /// upsert-by-id would otherwise risk colliding with one of ours), so both
    /// platforms share one implementation instead of two hand-written copies.
    /// The list on screen updates on its own via `RouteStore`'s StateFlow.
    private func pullInbox() async {
        refreshing = true
        do {
            // A suspend fun returning Int arrives as KotlinInt, which is an
            // NSNumber and so compares to nothing on its own.
            let count = try await RouteShare.shared.pullInbox().intValue
            if count > 0 {
                pulledCount = count
            }
        } catch {
            report(error)
        }
        refreshing = false
    }
}

/// Bridges `RouteStore`'s Kotlin StateFlow onto `@Published`, exactly the way
/// `SavedPlacesModel` does for `SavedPlaces` — see that type in
/// SavedPlacesScreen.swift for the pattern this mirrors.
@MainActor
final class RoutesModel: ObservableObject {

    @Published private(set) var items: [SavedRoute] = []

    private let watcher = StoreFlows.shared.routes()

    init() {
        RouteStore.shared.ensureLoaded()
        watcher.watch { [weak self] in
            self?.items = self?.watcher.value ?? []
        }
    }

    deinit {
        watcher.cancel()
    }
}

private struct RouteRow: View {
    let route: SavedRoute

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(route.name).font(.body.weight(.medium))
            HStack(spacing: 12) {
                Label("\(route.stops.count)", systemImage: "mappin.and.ellipse")
                if let distance = route.distanceMeters?.doubleValue {
                    Text(formatDistanceKm(distance))
                }
                if let time = route.timeMs?.int64Value {
                    Text(formatDurationHistory(time))
                }
            }
            .font(.caption)
            .foregroundStyle(.secondary)
            if !route.sharedBy.isEmpty {
                Text("from \(route.sharedBy)")
                    .font(.caption2)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.vertical, 2)
    }
}

/// System share sheet for a route's exported .gpx. `ShareLink` (as
/// TripDetailScreen uses) needs a `Transferable` value ready up front;
/// `UIActivityViewController` is used here instead so the same sheet can
/// later grow a custom "send to a friend" activity without changing how it's
/// presented.
private struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

/// Picks a friend to send [route] to, over the sync server.
private struct SendToFriendSheet: View {
    let route: SavedRoute

    @Environment(\.dismiss) private var dismiss
    @State private var friends: [String] = []
    @State private var busy = false
    @State private var errorMessage: String?

    var body: some View {
        NavigationStack {
            List {
                if friends.isEmpty {
                    Text("Add a friend first, on the Friends tab.")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(friends, id: \.self) { name in
                        Button(name) { send(to: name) }
                            .disabled(busy)
                    }
                }
            }
            .overlay { if busy { ProgressView() } }
            .navigationTitle("Send \"\(route.name)\"")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                Button("Cancel") { dismiss() }
            }
            .task {
                friends = (try? await Friends.shared.lists().friends) ?? []
            }
            .alert("Couldn't send", isPresented: .constant(errorMessage != nil)) {
                Button("OK") { errorMessage = nil }
            } message: {
                Text(errorMessage ?? "")
            }
        }
    }

    private func send(to username: String) {
        busy = true
        Task {
            do {
                try await RouteShare.shared.share(username: username, route: route)
                dismiss()
            } catch {
                errorMessage = (error as NSError).localizedDescription
            }
            busy = false
        }
    }
}
