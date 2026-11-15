// The saved-route document, read the way the phone reads it.
//
// A copy of `routeFromJson` in shared/src/commonMain/kotlin/com/jellemax/detour/data/Routes.kt
// and the lenient `opt*` accessors in data/Json.kt beside it. The web planner cannot link the
// Kotlin core, so the rule is copied, and testdata/web-parity/routes/ pins both copies to the
// same answers: routes.test.ts here, WebParityRoutesTest in shared/src/androidHostTest. Change
// one side and the other side's test fails until the fixture or the copy follows.

export type TravelMode = "MOTO" | "CAR";
export type RouteOrigin = "PLANNED" | "SPIN";

export interface LatLon {
  lat: number;
  lon: number;
}

export interface RouteStop extends LatLon {
  name: string;
}

export interface SavedRoute {
  id: number;
  name: string;
  createdMs: number;
  mode: TravelMode;
  stops: RouteStop[];
  /** Routed geometry. Empty when never routed; callers then draw the stops. */
  polyline: LatLon[];
  distanceMeters: number | null;
  timeMs: number | null;
  sharedBy: string;
  origin: RouteOrigin;
  editedAtMs: number;
  /** The document as the server sent it, unknown keys included, so a later save can send
   *  back what this build does not understand instead of dropping it. */
  raw: Record<string, unknown>;
}

const MODES: readonly TravelMode[] = ["MOTO", "CAR"];
const ORIGINS: readonly RouteOrigin[] = ["PLANNED", "SPIN"];

type Json = Record<string, unknown>;

function isObject(v: unknown): v is Json {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

// kotlinx's JsonPrimitive accessors read the literal's text, so a quoted number still reads as
// a number and a number still reads as a string. Mirrored, so a hand-edited document reads the
// same on both sides.

function optDouble(v: unknown): number {
  if (typeof v === "number") return v;
  if (typeof v === "string" && v.trim() !== "" && v.trim() === v) {
    const n = Number(v);
    return Number.isNaN(n) ? Number.NaN : n;
  }
  return Number.NaN;
}

function optLong(v: unknown): number {
  if (typeof v === "number") return Number.isInteger(v) ? v : 0;
  if (typeof v === "string" && /^[+-]?\d+$/.test(v)) return Number(v);
  return 0;
}

function optString(v: unknown): string {
  if (typeof v === "string") return v;
  if (typeof v === "number" || typeof v === "boolean") return String(v);
  return "";
}

/** Present and not null, as `JsonObject.has` in Json.kt. */
function has(o: Json, key: string): boolean {
  return o[key] !== undefined && o[key] !== null;
}

/** Null for anything with fewer than two stops or a stop without a usable coordinate, as
 *  `routeFromJson`: not enough to show, so dropped rather than drawn half-broken. */
export function routeFromJson(input: unknown): SavedRoute | null {
  if (!isObject(input)) return null;
  const o = input;

  const rawStops = Array.isArray(o.stops) ? o.stops : [];
  const stops = rawStops.filter(isObject).map((s) => ({
    lat: optDouble(s.lat),
    lon: optDouble(s.lon),
    name: optString(s.name),
  }));
  if (stops.length < 2 || stops.some((s) => Number.isNaN(s.lat) || Number.isNaN(s.lon))) {
    return null;
  }

  // Flat [lat, lon, lat, lon, ...]; a pair with either half unreadable is skipped, a trailing
  // half-pair too.
  const flat = Array.isArray(o.polyline) ? o.polyline : [];
  const polyline: LatLon[] = [];
  for (let i = 0; i < flat.length; i += 2) {
    const lat = optDouble(flat[i]);
    const lon = optDouble(flat[i + 1]);
    if (!Number.isNaN(lat) && !Number.isNaN(lon)) polyline.push({ lat, lon });
  }

  const mode = optString(o.mode);
  const origin = optString(o.origin);
  const distance = optDouble(o.distanceMeters);

  return {
    id: optLong(o.id),
    name: optString(o.name),
    createdMs: optLong(o.createdMs),
    mode: (MODES as readonly string[]).includes(mode) ? (mode as TravelMode) : "CAR",
    stops,
    polyline,
    distanceMeters: Number.isNaN(distance) ? null : distance,
    timeMs: has(o, "timeMs") ? optLong(o.timeMs) : null,
    sharedBy: optString(o.sharedBy),
    origin: (ORIGINS as readonly string[]).includes(origin) ? (origin as RouteOrigin) : "PLANNED",
    editedAtMs: optLong(o.editedAtMs),
    raw: o,
  };
}

/** The parity fixtures' `expected` shape: everything [routeFromJson] decides, nothing it
 *  passes through. WebParityRoutesTest builds the same object from the Kotlin result. */
export function parityView(route: SavedRoute | null): unknown {
  if (route === null) return null;
  return {
    id: route.id,
    name: route.name,
    createdMs: route.createdMs,
    mode: route.mode,
    stops: route.stops.map((s) => ({ lat: s.lat, lon: s.lon, name: s.name })),
    polyline: route.polyline.map((p) => [p.lat, p.lon]),
    distanceMeters: route.distanceMeters,
    timeMs: route.timeMs,
    sharedBy: route.sharedBy,
    origin: route.origin,
    editedAtMs: route.editedAtMs,
  };
}

/** "12.3 km", or "" when the route was never routed. */
export function formatDistance(meters: number | null): string {
  if (meters === null) return "";
  return `${(meters / 1000).toFixed(1)} km`;
}

/** "1 h 05 min" / "42 min", or "" when unknown. */
export function formatDuration(ms: number | null): string {
  if (ms === null) return "";
  const totalMin = Math.round(ms / 60000);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  return h > 0 ? `${h} h ${String(m).padStart(2, "0")} min` : `${m} min`;
}
