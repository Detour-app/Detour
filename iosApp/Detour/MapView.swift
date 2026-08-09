import SwiftUI
import MapLibre
import CoreLocation
import DetourShared

/// MapLibre's iOS view, bridged into SwiftUI.
///
/// Same renderer and the same OpenFreeMap vector style the Android app uses, so
/// the two platforms draw identical maps rather than merely similar ones — which
/// is the reason for not reaching for MapKit here.
struct MapView: UIViewRepresentable {

    var center: CLLocationCoordinate2D?
    var destination: CLLocationCoordinate2D?
    var route: [CLLocationCoordinate2D]
    /// Extra pins beyond a single `destination` — the route editor's ordered
    /// stops. Empty everywhere else, so every other call site is unaffected.
    var stops: [CLLocationCoordinate2D] = []
    /// Tap-to-place, for the route editor to append a stop. Nil everywhere
    /// else: the spin flow and nav screen only ever display a route, they
    /// never build one, so they never pass this.
    var onTap: ((CLLocationCoordinate2D) -> Void)? = nil
    /// Last-known position per other member, across every circle you're in,
    /// fed by MapScreen's own polling loop on `CircleFixes`'s own cadence.
    /// Empty everywhere else. Deliberately drawn
    /// as plain point annotations rather than Android's always-visible
    /// "name · age" style-layer label — this view has no custom style layers
    /// at all yet, only annotations — so the label surfaces on tap, the same
    /// way the destination and stop pins already work in this file.
    var circleMembers: [MemberFix] = []

    func makeUIView(context: Context) -> MLNMapView {
        let view = MLNMapView(frame: .zero)
        view.styleURL = URL(string: "https://tiles.openfreemap.org/styles/liberty")
        view.showsUserLocation = true
        view.logoView.isHidden = false // OpenFreeMap/OSM attribution stays.
        view.delegate = context.coordinator
        let tap = UITapGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.handleTap(_:)))
        view.addGestureRecognizer(tap)
        return view
    }

    func updateUIView(_ view: MLNMapView, context: Context) {
        context.coordinator.onTap = onTap

        if let center, !context.coordinator.hasCentered {
            view.setCenter(center, zoomLevel: 13, animated: false)
            context.coordinator.hasCentered = true
        }

        // Annotations and the route line are small and redrawn wholesale: a spin
        // replaces both at once, so diffing them would be more code than it saves.
        view.removeAnnotations(view.annotations ?? [])

        if let destination {
            let pin = MLNPointAnnotation()
            pin.coordinate = destination
            pin.title = "Destination"
            view.addAnnotation(pin)
        }

        for (index, stop) in stops.enumerated() {
            let pin = MLNPointAnnotation()
            pin.coordinate = stop
            pin.title = "Stop \(index + 1)"
            view.addAnnotation(pin)
        }

        for member in circleMembers {
            let pin = MLNPointAnnotation()
            pin.coordinate = CLLocationCoordinate2D(latitude: member.lat, longitude: member.lon)
            pin.title = "\(member.username) · \(circleFixAge(member.tsMs))"
            view.addAnnotation(pin)
        }

        if route.count >= 2 {
            var coords = route
            view.addAnnotation(MLNPolyline(coordinates: &coords, count: UInt(coords.count)))
            // Frame the whole route rather than the user, which is what you want
            // the moment a spin lands.
            view.setVisibleCoordinates(
                &coords, count: UInt(coords.count),
                edgePadding: UIEdgeInsets(top: 80, left: 40, bottom: 160, right: 40),
                animated: true)
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator() }

    final class Coordinator: NSObject, MLNMapViewDelegate {
        /// The first fix should centre the map; later ones must not yank it back
        /// while the user is panning.
        var hasCentered = false
        var onTap: ((CLLocationCoordinate2D) -> Void)?

        @objc func handleTap(_ gesture: UITapGestureRecognizer) {
            guard let onTap, let mapView = gesture.view as? MLNMapView else { return }
            let point = gesture.location(in: mapView)
            onTap(mapView.convert(point, toCoordinateFrom: mapView))
        }

        func mapView(_ mapView: MLNMapView, lineWidthForPolylineAnnotation annotation: MLNPolyline) -> CGFloat {
            5
        }

        func mapView(_ mapView: MLNMapView, strokeColorForShapeAnnotation annotation: MLNShape) -> UIColor {
            .systemBlue
        }
    }
}

/// "just now" / "<n>m ago" with no hour/day rollover — a circle fix is always
/// on the order of minutes old, so anything past that is already stale enough
/// that the exact hour count doesn't matter. Matches the inline computation in
/// Android's `MapLibreMap.setCircleMembers` exactly (floor-divided minutes,
/// clamped to zero).
private func circleFixAge(_ tsMs: Int64) -> String {
    let minutes = max(0, nowMs() - tsMs) / 60_000
    return minutes < 1 ? "just now" : "\(minutes)m ago"
}
