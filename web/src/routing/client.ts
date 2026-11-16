import type { LatLon } from "../routes";
import { type ElevationProfile, infoHasElevation, profileFromRoute } from "./elevation";
import { encodedValues, type RouteRequest } from "./request";

// Same-origin /route and /info, as nginx forwards them to the stack's GraphHopper (#636
// "Routing"). nginx's route_per_ip zone (2 r/s, burst 20) answers 429 past that, which is why
// requests go through RouteThrottle and a 429 reads as "slow down", not as a failure.

/** nginx's rate limit refused the request. Not an error: the next drag will route. */
export class SlowDown extends Error {
  constructor() {
    super("Slow down: the routing server is limiting requests. Try again in a moment.");
  }
}

/** GraphHopper answered 400: no road between the stops, or a stop off its map. */
export class NoRoute extends Error {
  constructor() {
    super("No route between these stops. Move a stop onto a road the map covers.");
  }
}

export interface RoutedPath {
  polyline: LatLon[];
  distanceMeters: number | null;
  timeMs: number | null;
}

/** What `/info` says the graph has. */
export interface GraphInfo {
  /** The encoded values, or null when /info can't be read: every gated option then stays off. */
  values: Set<string> | null;
  /** Whether the graph has heights (GRAPHHOPPER_ELEVATION), so a profile can be drawn. */
  elevation: boolean;
}

/** `/info`, read once per sign-in. Unreadable (no routing on this server, a proxy without
 *  /info): nothing supported, no elevation. */
export async function fetchGraphInfo(): Promise<GraphInfo> {
  try {
    const res = await fetch("/info", { headers: { Accept: "application/json" } });
    if (!res.ok) return { values: null, elevation: false };
    const text = await res.text();
    return { values: encodedValues(text), elevation: infoHasElevation(text) };
  } catch {
    return { values: null, elevation: false };
  }
}

/** A routed path, and its elevation profile when the request asked for heights and got them. */
export interface RouteAnswer {
  path: RoutedPath;
  profile: ElevationProfile | null;
}

export async function fetchRoute(request: RouteRequest): Promise<RouteAnswer> {
  const res = await fetch(request.url, {
    method: request.body === null ? "GET" : "POST",
    headers:
      request.body === null
        ? { Accept: "application/json" }
        : { Accept: "application/json", "Content-Type": "application/json" },
    body: request.body ?? undefined,
  });
  if (res.status === 429) throw new SlowDown();
  if (res.status === 400) throw new NoRoute();
  if (!res.ok) throw new Error(`The routing server answered ${res.status}`);
  const text = await res.text();
  return { path: parseRoute(text), profile: profileFromRoute(text) };
}

/** RoutingClient.parseRoute, the part a saved route keeps: geometry, distance, time. */
export function parseRoute(text: string): RoutedPath {
  const body = JSON.parse(text) as { paths?: unknown };
  const path = Array.isArray(body.paths) ? (body.paths[0] as Record<string, unknown>) : undefined;
  if (path === undefined || path === null) throw new NoRoute();
  const coords = (path.points as { coordinates?: unknown } | undefined)?.coordinates;
  const polyline: LatLon[] = [];
  if (Array.isArray(coords)) {
    for (const c of coords) {
      // GeoJSON order: [lon, lat]
      if (Array.isArray(c) && typeof c[0] === "number" && typeof c[1] === "number") {
        polyline.push({ lat: c[1], lon: c[0] });
      }
    }
  }
  if (polyline.length < 2) throw new NoRoute();
  const distance = path.distance;
  const time = path.time;
  return {
    polyline,
    distanceMeters: typeof distance === "number" && Number.isFinite(distance) ? distance : null,
    timeMs: typeof time === "number" && time > 0 ? Math.round(time) : null,
  };
}

/**
 * At most one request per [intervalMs], latest wins: a call while one is waiting replaces it,
 * so a burst of edits costs one request, sent no sooner than [intervalMs] after the previous.
 * A result arriving after a newer call was made is dropped ([onResult] never sees a stale
 * route for stops that have since moved).
 */
export class RouteThrottle<T> {
  private lastStart = Number.NEGATIVE_INFINITY;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private generation = 0;
  private inFlight = 0;

  constructor(
    private readonly onResult: (result: T) => void,
    private readonly onError: (error: unknown) => void,
    private readonly intervalMs = 1000,
    private readonly now: () => number = () => Date.now(),
  ) {}

  /** Runs [task] now or as soon as the interval allows, unless another call replaces it. */
  request(task: () => Promise<T>): void {
    const generation = ++this.generation;
    if (this.timer !== null) clearTimeout(this.timer);
    const wait = Math.max(0, this.lastStart + this.intervalMs - this.now());
    this.timer = setTimeout(() => {
      this.timer = null;
      this.lastStart = this.now();
      this.inFlight++;
      // Settled before the callback runs, so a callback reading [pending] sees it done.
      task().then(
        (result) => {
          this.inFlight--;
          if (generation === this.generation) this.onResult(result);
        },
        (error: unknown) => {
          this.inFlight--;
          if (generation === this.generation) this.onError(error);
        },
      );
    }, wait);
  }

  /** Drops whatever is waiting or in flight, as when the rider leaves the route. */
  cancel(): void {
    this.generation++;
    if (this.timer !== null) clearTimeout(this.timer);
    this.timer = null;
  }

  /** True while a call is waiting for its slot, or the newest one sent has not answered. */
  get pending(): boolean {
    return this.timer !== null || this.inFlight > 0;
  }
}
