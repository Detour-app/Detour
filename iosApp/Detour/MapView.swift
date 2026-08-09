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
    /// The three rolls of a spin awaiting a pick, lettered A/B/C to match the
    /// rows of the card below the map. Empty once one is committed — it
    /// becomes `destination` then.
    var candidates: [CLLocationCoordinate2D] = []

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

        for (index, candidate) in candidates.enumerated() {
            let pin = MLNPointAnnotation()
            pin.coordinate = candidate
            pin.title = String(UnicodeScalar(UInt8(65 + min(index, 25))))
            view.addAnnotation(pin)
        }

        // Frame all three the moment a spin lands, the same way a route gets
        // framed below — otherwise two of them can be off screen and the pick
        // reads as a choice of one.
        //
        // Once only, per set of candidates: during a convoy vote this view is
        // rebuilt every time a vote arrives, and re-framing on each of those
        // would drag the map back from wherever the rider had just panned it
        // to look at one of the three.
        if !candidates.isEmpty,
           !sameCoordinates(candidates, context.coordinator.lastFramedCandidates) {
            context.coordinator.lastFramedCandidates = candidates
            var coords = candidates + [center].compactMap { $0 }
            view.setVisibleCoordinates(
                &coords, count: UInt(coords.count),
                edgePadding: UIEdgeInsets(top: 80, left: 40, bottom: 260, right: 40),
                animated: true)
        }
        if candidates.isEmpty { context.coordinator.lastFramedCandidates = [] }

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
        /// The candidate set the camera was last fitted to, so a redraw that
        /// changes nothing about them leaves the camera alone.
        var lastFramedCandidates: [CLLocationCoordinate2D] = []

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
/// `CLLocationCoordinate2D` is not Equatable, and these come straight from the
/// same values each redraw, so an exact field comparison is all this needs.
private func sameCoordinates(
    _ a: [CLLocationCoordinate2D], _ b: [CLLocationCoordinate2D]
) -> Bool {
    a.count == b.count && zip(a, b).allSatisfy {
        $0.latitude == $1.latitude && $0.longitude == $1.longitude
    }
}

private func circleFixAge(_ tsMs: Int64) -> String {
    let minutes = max(0, nowMs() - tsMs) / 60_000
    return minutes < 1 ? "just now" : "\(minutes)m ago"
}
