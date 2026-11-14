import type { FeatureCollection } from "geojson";
import {
  type GeoJSONSource,
  LngLatBounds,
  Map as MapLibreMap,
  NavigationControl,
  setWorkerUrl,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
// MapLibre finds its worker next to its own module, which a bundle does not keep. Vite builds
// the worker (with the shared chunk it imports) into one same-origin file instead, so the CSP
// needs `worker-src 'self'` and no blob: or remote script.
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import type { LatLon, SavedRoute } from "./routes";

// OpenFreeMap, the same tiles the phone draws (app/.../ui/MapLibreMap.kt). The API's CSP for
// /planner allows this origin; a second tile host needs a line there too (PlannerHosting.cs).
const STYLE_URL = "https://tiles.openfreemap.org/styles/positron";

const ROUTE_SOURCE = "route";
const STOPS_SOURCE = "stops";
const ACCENT = "#e8a33d";

const emptyCollection: FeatureCollection = { type: "FeatureCollection", features: [] };

export interface RouteMap {
  show(route: SavedRoute | null): void;
}

export function createMap(container: HTMLElement): RouteMap {
  setWorkerUrl(workerUrl);
  const map = new MapLibreMap({
    container,
    style: STYLE_URL,
    center: [4.47, 50.5],
    zoom: 6,
    attributionControl: { compact: true },
  });
  map.addControl(new NavigationControl({ showCompass: false }), "top-right");

  let pending: SavedRoute | null = null;
  let loaded = false;

  map.on("load", () => {
    map.addSource(ROUTE_SOURCE, { type: "geojson", data: emptyCollection });
    map.addSource(STOPS_SOURCE, { type: "geojson", data: emptyCollection });
    map.addLayer({
      id: "route-line",
      type: "line",
      source: ROUTE_SOURCE,
      layout: { "line-join": "round", "line-cap": "round" },
      paint: { "line-color": ACCENT, "line-width": 5 },
    });
    map.addLayer({
      id: "route-stops",
      type: "circle",
      source: STOPS_SOURCE,
      paint: {
        "circle-radius": 7,
        "circle-color": "#ffffff",
        "circle-stroke-color": "#1d1f21",
        "circle-stroke-width": 3,
      },
    });
    loaded = true;
    draw(pending);
  });

  function draw(route: SavedRoute | null): void {
    const line = map.getSource<GeoJSONSource>(ROUTE_SOURCE);
    const stops = map.getSource<GeoJSONSource>(STOPS_SOURCE);
    if (!line || !stops) return;
    if (route === null) {
      line.setData(emptyCollection);
      stops.setData(emptyCollection);
      return;
    }
    // A route saved before it was routed (an imported file) has no geometry: draw the legs
    // straight, as the phone's list preview does, rather than nothing.
    const path: LatLon[] = route.polyline.length >= 2 ? route.polyline : route.stops;
    line.setData({
      type: "Feature",
      properties: {},
      geometry: { type: "LineString", coordinates: path.map((p) => [p.lon, p.lat]) },
    });
    stops.setData({
      type: "FeatureCollection",
      features: route.stops.map((s, i) => ({
        type: "Feature",
        properties: { index: i, name: s.name },
        geometry: { type: "Point", coordinates: [s.lon, s.lat] },
      })),
    });
    const bounds = new LngLatBounds();
    for (const p of [...path, ...route.stops]) bounds.extend([p.lon, p.lat]);
    map.fitBounds(bounds, { padding: 48, maxZoom: 14, duration: 0 });
  }

  return {
    show(route) {
      pending = route;
      if (loaded) draw(route);
    },
  };
}
