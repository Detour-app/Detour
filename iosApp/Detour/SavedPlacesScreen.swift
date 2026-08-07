import SwiftUI
import DetourShared

/// Named shortcut destinations — Home, Work, a friend's place.
///
/// `SavedPlaces` publishes a StateFlow so the map's shortcut chips and this
/// screen stay in step; `FlowWatcher` is what lets SwiftUI subscribe to it.
struct SavedPlacesScreen: View {

    @StateObject private var places = SavedPlacesModel()
    @State private var renaming: SavedPlace?
    @State private var newName = ""

    var body: some View {
        NavigationStack {
            Group {
                if places.items.isEmpty {
                    ContentUnavailableView(
                        "No saved places",
                        systemImage: "mappin.and.ellipse",
                        description: Text("Long-press the map, or save a spin's destination, to keep it here.")
                    )
                } else {
                    List {
                        ForEach(places.items, id: \.id) { place in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(place.name)
                                Text(String(format: "%.5f, %.5f",
                                            place.location.lat, place.location.lon))
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                            }
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) {
                                    SavedPlaces.shared.remove(id: place.id)
                                } label: {
                                    Label("Delete", systemImage: "trash")
                                }
                                Button {
                                    newName = place.name
                                    renaming = place
                                } label: {
                                    Label("Rename", systemImage: "pencil")
                                }
                                .tint(.indigo)
                            }
                        }
                    }
                }
            }
            .navigationTitle("Places")
            .alert("Rename place", isPresented: .constant(renaming != nil)) {
                TextField("Name", text: $newName)
                Button("Save") {
                    if let place = renaming {
                        SavedPlaces.shared.rename(id: place.id, name: newName)
                    }
                    renaming = nil
                }
                Button("Cancel", role: .cancel) { renaming = nil }
            }
        }
    }
}

/// Bridges the Kotlin StateFlow onto `@Published` so SwiftUI can observe it.
@MainActor
final class SavedPlacesModel: ObservableObject {

    @Published private(set) var items: [SavedPlace] = []

    private let watcher = StoreFlows.shared.savedPlaces()

    init() {
        SavedPlaces.shared.ensureLoaded()
        watcher.watch { [weak self] in
            self?.items = self?.watcher.value ?? []
        }
    }

    deinit {
        watcher.cancel()
    }
}
