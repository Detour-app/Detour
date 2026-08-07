import SwiftUI
import MapLibre
import CoreLocation

/// MapLibre's iOS view, bridged into SwiftUI.
///
/// Same renderer and the same OpenFreeMap vector style the Android app uses, so
/// the two platforms draw identical maps rather than merely similar ones — which
/// is the reason for not reaching for MapKit here.
struct MapView: UIViewRepresentable {

    var center: CLLocationCoordinate2D?
    var destination: CLLocationCoordinate2D?
    var route: [CLLocationCoordinate2D]

    func makeUIView(context: Context) -> MLNMapView {
        let view = MLNMapView(frame: .zero)
        view.styleURL = URL(string: "https://tiles.openfreemap.org/styles/liberty")
        view.showsUserLocation = true
        view.logoView.isHidden = false // OpenFreeMap/OSM attribution stays.
        view.delegate = context.coordinator
        return view
    }

    func updateUIView(_ view: MLNMapView, context: Context) {
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

        func mapView(_ mapView: MLNMapView, lineWidthForPolylineAnnotation annotation: MLNPolyline) -> CGFloat {
            5
        }

        func mapView(_ mapView: MLNMapView, strokeColorForShapeAnnotation annotation: MLNShape) -> UIColor {
            .systemBlue
        }
    }
}
