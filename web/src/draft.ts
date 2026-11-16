import { DEFAULT_PREFERENCES, CURVY_PRESETS, type RoutePreferences } from "./routing/request";
import type { LatLon, RouteStop, SavedRoute, TravelMode } from "./routes";

// The route being built or edited, and how it outlives a sign-in (#636 "Sign-in"): the token
// has no refresh, so a save can meet a 401; the draft is in sessionStorage the whole time, the
// rider signs in again in the same tab, and the edit is back.

export interface Draft {
  /** The signed-in rider the draft belongs to (the token's `sub`), so a draft left in a tab is
   *  never restored into, and saved to, another rider's account. */
  owner: string;
  id: number;
  /** Null for a route not saved yet. */
  createdMs: number | null;
  /** The stored document this edit started from, unknown keys included; null for a new route.
   *  [routeDocument] starts from it, so a save sends back what this build doesn't understand. */
  base: Record<string, unknown> | null;
  name: string;
  mode: TravelMode;
  stops: RouteStop[];
  /** Routed geometry for exactly these stops, or empty when not routed (yet). */
  polyline: LatLon[];
  distanceMeters: number | null;
  timeMs: number | null;
  preferences: RoutePreferences;
  /** True once the rider changed anything since it was loaded or saved. */
  dirty: boolean;
}

export function newDraft(owner: string, nowMs: number, mode: TravelMode = "MOTO"): Draft {
  return {
    owner,
    id: nowMs,
    createdMs: null,
    base: null,
    name: "",
    mode,
    stops: [],
    polyline: [],
    distanceMeters: null,
    timeMs: null,
    preferences: { ...DEFAULT_PREFERENCES },
    dirty: false,
  };
}

export function draftFromRoute(owner: string, route: SavedRoute): Draft {
  return {
    owner,
    id: route.id,
    createdMs: route.createdMs,
    base: route.raw,
    name: route.name,
    mode: route.mode,
    stops: route.stops.map((s) => ({ ...s })),
    polyline: route.polyline,
    distanceMeters: route.distanceMeters,
    timeMs: route.timeMs,
    preferences: { ...DEFAULT_PREFERENCES },
    dirty: false,
  };
}

/**
 * A route read from a file (src/gpx/routeGpx.ts `parseRouteFile`), opened as a new, unsaved
 * route: a fresh id as the phone's import gives it, so it never overwrites the route it was
 * exported from, and no geometry, so it is routed for this mode and these options like any edit.
 */
export function importedDraft(owner: string, route: SavedRoute, nowMs: number): Draft {
  return {
    ...draftFromRoute(owner, route),
    id: nowMs,
    createdMs: null,
    polyline: [],
    distanceMeters: null,
    timeMs: null,
    dirty: true,
  };
}

// --- The stop list. Each edit drops the old geometry: it was routed for other stops. ----------

function withStops(draft: Draft, stops: RouteStop[]): Draft {
  return { ...draft, stops, polyline: [], distanceMeters: null, timeMs: null, dirty: true };
}

export function addStop(draft: Draft, at: LatLon): Draft {
  return withStops(draft, [...draft.stops, { lat: at.lat, lon: at.lon, name: "" }]);
}

/** A stop at [index], the ones from there on shifted back. */
export function insertStop(draft: Draft, index: number, at: LatLon): Draft {
  const i = Math.max(0, Math.min(index, draft.stops.length));
  const stops = [...draft.stops];
  stops.splice(i, 0, { lat: at.lat, lon: at.lon, name: "" });
  return withStops(draft, stops);
}

/** Moving a stop keeps its name: dragging "Bastogne" a street over is still Bastogne. */
export function moveStop(draft: Draft, index: number, at: LatLon): Draft {
  const current = draft.stops[index];
  if (current === undefined) return draft;
  const stops = [...draft.stops];
  stops[index] = { ...current, lat: at.lat, lon: at.lon };
  return withStops(draft, stops);
}

export function removeStop(draft: Draft, index: number): Draft {
  if (index < 0 || index >= draft.stops.length) return draft;
  return withStops(
    draft,
    draft.stops.filter((_, i) => i !== index),
  );
}

/**
 * Where a stop dragged off the line at [at] goes: into the leg it costs the least detour to
 * pass through, which is the leg the rider grabbed, and the right one when legs cross. Index to
 * pass to [insertStop]; the end of the list when there is no leg yet.
 */
export function insertIndexFor(stops: readonly LatLon[], at: LatLon): number {
  if (stops.length < 2) return stops.length;
  let best = 1;
  let bestCost = Number.POSITIVE_INFINITY;
  for (let i = 0; i + 1 < stops.length; i++) {
    const a = stops[i] as LatLon;
    const b = stops[i + 1] as LatLon;
    const cost = distance(a, at) + distance(at, b) - distance(a, b);
    if (cost < bestCost) {
      bestCost = cost;
      best = i + 1;
    }
  }
  return best;
}

/** Equirectangular metres: plenty to rank legs against each other. */
function distance(a: LatLon, b: LatLon): number {
  const R = 6_371_000;
  const rad = Math.PI / 180;
  const x = (b.lon - a.lon) * rad * Math.cos(((a.lat + b.lat) / 2) * rad);
  const y = (b.lat - a.lat) * rad;
  return Math.sqrt(x * x + y * y) * R;
}

export function withMode(draft: Draft, mode: TravelMode): Draft {
  if (mode === draft.mode) return draft;
  return { ...draft, mode, polyline: [], distanceMeters: null, timeMs: null, dirty: true };
}

export function withPreferences(draft: Draft, preferences: RoutePreferences): Draft {
  return { ...draft, preferences, polyline: [], distanceMeters: null, timeMs: null, dirty: true };
}

export function canSave(draft: Draft): boolean {
  return draft.stops.length >= 2;
}

// --- The document a save sends. ----------------------------------------------------------------

/**
 * The route document for `PUT /api/routes/{id}`: [Draft.base] with every key SavedRoute.toJson
 * writes (shared/.../data/Routes.kt) replaced by the draft's, and optional ones it omits removed,
 * so the phone reads back exactly what the planner shows. [nowMs] is the edit stamp, newest wins;
 * a new route takes it as its creation time too.
 */
export function routeDocument(draft: Draft, nowMs: number): Record<string, unknown> {
  const doc: Record<string, unknown> = { ...(draft.base ?? {}) };
  doc.id = draft.id;
  doc.name = draft.name.trim();
  doc.createdMs = draft.createdMs ?? nowMs;
  doc.mode = draft.mode;
  doc.stops = draft.stops.map((s) =>
    s.name === "" ? { lat: s.lat, lon: s.lon } : { lat: s.lat, lon: s.lon, name: s.name },
  );
  doc.polyline = draft.polyline.flatMap((p) => [p.lat, p.lon]);
  if (draft.distanceMeters === null) delete doc.distanceMeters;
  else doc.distanceMeters = draft.distanceMeters;
  if (draft.timeMs === null) delete doc.timeMs;
  else doc.timeMs = draft.timeMs;
  doc.editedAtMs = nowMs;
  return doc;
}

// --- Keeping it across a sign-in. ---------------------------------------------------------------

const DRAFT_KEY = "detour.draft";

/** What [saveDraft] needs of `window.sessionStorage`; a Map-backed fake in the tests. */
export type DraftStorage = Pick<Storage, "getItem" | "setItem" | "removeItem">;

export function saveDraft(store: DraftStorage, draft: Draft): void {
  store.setItem(DRAFT_KEY, JSON.stringify(draft));
}

export function clearDraft(store: DraftStorage): void {
  store.removeItem(DRAFT_KEY);
}

/** The kept draft if it belongs to [owner] and reads back whole; anything else is dropped. */
export function loadDraft(store: DraftStorage, owner: string): Draft | null {
  const text = store.getItem(DRAFT_KEY);
  if (text === null) return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    clearDraft(store);
    return null;
  }
  const draft = readDraft(parsed);
  if (draft === null || draft.owner !== owner) {
    clearDraft(store);
    return null;
  }
  return draft;
}

function readDraft(v: unknown): Draft | null {
  if (typeof v !== "object" || v === null) return null;
  const d = v as Partial<Draft>;
  const isPoint = (p: unknown): p is LatLon =>
    typeof p === "object" &&
    p !== null &&
    typeof (p as LatLon).lat === "number" &&
    typeof (p as LatLon).lon === "number";
  if (
    typeof d.owner !== "string" ||
    typeof d.id !== "number" ||
    (d.mode !== "MOTO" && d.mode !== "CAR") ||
    !Array.isArray(d.stops) ||
    !d.stops.every((s) => isPoint(s) && typeof (s as RouteStop).name === "string") ||
    !Array.isArray(d.polyline) ||
    !d.polyline.every(isPoint)
  ) {
    return null;
  }
  const prefs = { ...DEFAULT_PREFERENCES, ...(d.preferences ?? {}) };
  if (!CURVY_PRESETS.includes(prefs.curvy)) prefs.curvy = "FAST";
  return {
    owner: d.owner,
    id: d.id,
    createdMs: typeof d.createdMs === "number" ? d.createdMs : null,
    base: typeof d.base === "object" && d.base !== null ? d.base : null,
    name: typeof d.name === "string" ? d.name : "",
    mode: d.mode,
    stops: d.stops,
    polyline: d.polyline,
    distanceMeters: typeof d.distanceMeters === "number" ? d.distanceMeters : null,
    timeMs: typeof d.timeMs === "number" ? d.timeMs : null,
    preferences: prefs,
    dirty: d.dirty === true,
  };
}
