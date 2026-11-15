import type { FeatureCollection } from "geojson";
import {
  type GeoJSONSource,
  LngLatBounds,
  type MapLayerMouseEvent,
  type MapMouseEvent,
  Map as MapLibreMap,
  NavigationControl,
  setWorkerUrl,
} from "maplibre-gl";
import "maplibre-gl/dist/maplibre-gl.css";
// MapLibre finds its worker next to its own module, which a bundle does not keep. Vite builds
// the worker (with the shared chunk it imports) into one same-origin file instead, so the CSP
// needs `worker-src 'self'` and no blob: or remote script.
import workerUrl from "maplibre-gl/dist/maplibre-gl-worker.mjs?worker&url";
import type { LatLon, RouteStop } from "./routes";

// OpenFreeMap, the same tiles the phone draws (app/.../ui/MapLibreMap.kt). The API's CSP for
// /planner allows this origin; a second tile host needs a line there too (PlannerHosting.cs).
const STYLE_URL = "https://tiles.openfreemap.org/styles/positron";

const ROUTE_SOURCE = "route";
const STOPS_SOURCE = "stops";
const ACCENT = "#e8a33d";

const emptyCollection: FeatureCollection = { type: "FeatureCollection", features: [] };

/** What the map draws: the stops, and the routed line through them (straight legs when there
 *  is none). A SavedRoute and a Draft both are one. */
export interface MapView {
  stops: readonly RouteStop[];
  polyline: readonly LatLon[];
}

/** The edits a rider makes on the map; each one ends a gesture (drag end, click), so the caller
 *  routes once per edit rather than once per mouse move. */
export interface MapEditHandlers {
  /** A click on the map off the route: a new last stop. */
  addStop(at: LatLon): void;
  /** A stop dragged to [at]. */
  moveStop(index: number, at: LatLon): void;
  /** The line grabbed and dropped at [at]: a new stop in that leg. */
  insertStop(at: LatLon): void;
  /** Right-click on a stop. */
  removeStop(index: number): void;
}

export interface RouteMap {
  /** Draws [view], framing it when [fit] (a route just opened, not an edit to it). */
  show(view: MapView | null, fit: boolean): void;
  /** Editing on with [handlers], or off (read-only) with null. */
  setEditing(handlers: MapEditHandlers | null): void;
}

const at = (e: MapMouseEvent): LatLon => ({ lat: e.lngLat.lat, lon: e.lngLat.lng });

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

  let pending: { view: MapView | null; fit: boolean } = { view: null, fit: false };
  let shown: MapView | null = null;
  let loaded = false;
  let editing: MapEditHandlers | null = null;
  /** The gesture in progress: a stop or the line held down, with where it is now. */
  let drag: { kind: "stop"; index: number; to: LatLon | null } | { kind: "line"; to: LatLon | null } | null =
    null;
  /** Set when a drag ends, so a click fired after its mouseup adds no stop. MapLibre drops
   *  that click itself past its click tolerance, so the next press clears this rather than
   *  leaving it to swallow the rider's next real click. */
  let swallowClick = false;

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
    draw(pending.view, pending.fit);
  });

  map.on("mousedown", () => {
    swallowClick = false;
  });

  map.on("mousedown", "route-stops", (e: MapLayerMouseEvent) => {
    if (editing === null || e.originalEvent.button !== 0) return;
    const index = e.features?.[0]?.properties?.index;
    if (typeof index !== "number") return;
    e.preventDefault();
    drag = { kind: "stop", index, to: null };
    container.classList.add("dragging");
  });

  map.on("mousedown", "route-line", (e: MapLayerMouseEvent) => {
    if (editing === null || drag !== null || e.originalEvent.button !== 0) return;
    // A stop sits on the line; the stop layer's handler takes that one.
    if (map.queryRenderedFeatures(e.point, { layers: ["route-stops"] }).length > 0) return;
    e.preventDefault();
    drag = { kind: "line", to: null };
    container.classList.add("dragging");
  });

  map.on("mousemove", (e) => {
    if (drag === null || shown === null) return;
    drag.to = at(e);
    // Live feedback while dragging, without routing: the held stop follows the mouse, or a new
    // stop shows where the line was grabbed to.
    const stops = [...shown.stops];
    if (drag.kind === "stop") {
      const held = stops[drag.index];
      if (held !== undefined) stops[drag.index] = { ...held, ...drag.to };
    } else {
      stops.push({ ...drag.to, name: "" });
    }
    drawStops(stops);
  });

  map.on("mouseup", () => {
    const done = drag;
    drag = null;
    container.classList.remove("dragging");
    if (done === null) return;
    if (done.to === null) return; // pressed and released in place: nothing moved
    swallowClick = true;
    if (done.kind === "stop") editing?.moveStop(done.index, done.to);
    else editing?.insertStop(done.to);
  });

  map.on("click", (e) => {
    if (swallowClick) {
      swallowClick = false;
      return;
    }
    if (editing === null) return;
    if (map.queryRenderedFeatures(e.point, { layers: ["route-stops"] }).length > 0) return;
    editing.addStop(at(e));
  });

  map.on("contextmenu", "route-stops", (e: MapLayerMouseEvent) => {
    if (editing === null) return;
    const index = e.features?.[0]?.properties?.index;
    if (typeof index !== "number") return;
    e.preventDefault();
    editing.removeStop(index);
  });

  for (const layer of ["route-stops", "route-line"]) {
    map.on("mouseenter", layer, () => {
      if (editing !== null) map.getCanvas().style.cursor = layer === "route-stops" ? "grab" : "copy";
    });
    map.on("mouseleave", layer, () => {
      map.getCanvas().style.cursor = "";
    });
  }

  function drawStops(stops: readonly RouteStop[]): void {
    map.getSource<GeoJSONSource>(STOPS_SOURCE)?.setData({
      type: "FeatureCollection",
      features: stops.map((s, i) => ({
        type: "Feature",
        properties: { index: i, name: s.name },
        geometry: { type: "Point", coordinates: [s.lon, s.lat] },
      })),
    });
  }

  function draw(view: MapView | null, fit: boolean): void {
    shown = view;
    const line = map.getSource<GeoJSONSource>(ROUTE_SOURCE);
    const stops = map.getSource<GeoJSONSource>(STOPS_SOURCE);
    if (!line || !stops) return;
    if (view === null) {
      line.setData(emptyCollection);
      stops.setData(emptyCollection);
      return;
    }
    // A route saved before it was routed (an imported file), or one waiting for its route, has
    // no geometry: draw the legs straight, as the phone's list preview does, rather than nothing.
    const path: readonly LatLon[] = view.polyline.length >= 2 ? view.polyline : view.stops;
    line.setData(
      path.length >= 2
        ? {
            type: "Feature",
            properties: {},
            geometry: { type: "LineString", coordinates: path.map((p) => [p.lon, p.lat]) },
          }
        : emptyCollection,
    );
    drawStops(view.stops);
    if (!fit || path.length === 0) return;
    const bounds = new LngLatBounds();
    for (const p of [...path, ...view.stops]) bounds.extend([p.lon, p.lat]);
    map.fitBounds(bounds, { padding: 48, maxZoom: 14, duration: 0 });
  }

  return {
    show(view, fit) {
      pending = { view, fit };
      if (loaded) draw(view, fit);
    },
    setEditing(handlers) {
      editing = handlers;
      container.classList.toggle("editing", handlers !== null);
    },
  };
}
