// The planner's elevation profile (#916, #636 "Elevation (3B)"). Elevation is opt-in on the
// server (GRAPHHOPPER_ELEVATION in docker/prod/docker-compose.routing.yml): only when `/info`
// says the graph has it does the planner ask `/route` for `elevation=true`, and then only to
// draw this profile. Saved polylines stay 2-D (parseRoute drops the height), and the app never
// asks for elevation, so nothing here has a Kotlin original or a parity fixture.

import type { RouteRequest } from "./request";

/** One point of the profile: metres along the route, metres above sea level. */
export interface ProfilePoint {
  distance: number;
  height: number;
}

export interface ElevationProfile {
  points: ProfilePoint[];
  /** Sum of every rise along the route, in metres. */
  climb: number;
  minHeight: number;
  maxHeight: number;
  /** Length of the route the profile covers, in metres. */
  distance: number;
}

/** Whether a GraphHopper `/info` body says the graph has elevation. Anything else (not JSON,
 *  no `elevation` key, `false`): no, and the planner shows no profile. */
export function infoHasElevation(infoBody: string): boolean {
  try {
    const parsed: unknown = JSON.parse(infoBody);
    return (
      typeof parsed === "object" &&
      parsed !== null &&
      (parsed as Record<string, unknown>).elevation === true
    );
  } catch {
    return false;
  }
}

/** [request] asking for heights too: `elevation=true` on the GET, `"elevation": true` in the
 *  POST body. Everything else stays as routeViaRequest built it. */
export function withElevation(request: RouteRequest): RouteRequest {
  if (request.body === null) return { url: `${request.url}&elevation=true`, body: null };
  const body = JSON.parse(request.body) as Record<string, unknown>;
  return { url: request.url, body: JSON.stringify({ ...body, elevation: true }) };
}

const EARTH_RADIUS_M = 6_371_000;

function haversine(lat1: number, lon1: number, lat2: number, lon2: number): number {
  const rad = Math.PI / 180;
  const dLat = (lat2 - lat1) * rad;
  const dLon = (lon2 - lon1) * rad;
  const a =
    Math.sin(dLat / 2) ** 2 + Math.cos(lat1 * rad) * Math.cos(lat2 * rad) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * The profile of a `/route` answer's 3-D GeoJSON points (`[lon, lat, height]`), or null when
 * there is none to draw: fewer than two points, or any point without a height (a server whose
 * graph has no elevation answers 2-D points even to `elevation=true`). Distance is measured
 * along the points, so it matches the drawn line rather than GraphHopper's rounded total.
 */
export function profileFromCoordinates(coordinates: unknown): ElevationProfile | null {
  if (!Array.isArray(coordinates) || coordinates.length < 2) return null;
  const points: ProfilePoint[] = [];
  let distance = 0;
  let climb = 0;
  let prev: [number, number, number] | null = null;
  for (const c of coordinates) {
    if (
      !Array.isArray(c) ||
      c.length < 3 ||
      typeof c[0] !== "number" ||
      typeof c[1] !== "number" ||
      typeof c[2] !== "number" ||
      !Number.isFinite(c[2])
    ) {
      return null;
    }
    const [lon, lat, height] = c as [number, number, number];
    if (prev !== null) {
      distance += haversine(prev[1], prev[0], lat, lon);
      if (height > prev[2]) climb += height - prev[2];
    }
    points.push({ distance, height });
    prev = [lon, lat, height];
  }
  const heights = points.map((p) => p.height);
  return {
    points,
    climb,
    minHeight: Math.min(...heights),
    maxHeight: Math.max(...heights),
    distance,
  };
}

/** The profile in a `/route` response body, or null (see profileFromCoordinates). */
export function profileFromRoute(text: string): ElevationProfile | null {
  try {
    const body = JSON.parse(text) as { paths?: unknown };
    const path = Array.isArray(body.paths) ? (body.paths[0] as Record<string, unknown> | null) : null;
    const points = path?.points as { coordinates?: unknown } | undefined;
    return profileFromCoordinates(points?.coordinates);
  } catch {
    return null;
  }
}

/** "Climb 340 m · 12–410 m". */
export function profileSummary(profile: ElevationProfile): string {
  const m = (v: number) => `${Math.round(v)} m`;
  return `Climb ${m(profile.climb)} · ${Math.round(profile.minHeight)}–${m(profile.maxHeight)}`;
}

/**
 * The SVG path data of [profile] drawn into a [width]×[height] box: distance left to right,
 * height bottom to top, scaled to the route's own range (a flat route is a line across the
 * middle, not a divide by zero). `line` is the profile, `area` the same closed down to the
 * bottom edge for the fill.
 */
export function profilePaths(
  profile: ElevationProfile,
  width: number,
  height: number,
): { line: string; area: string } {
  const range = profile.maxHeight - profile.minHeight;
  const x = (d: number) => (profile.distance > 0 ? (d / profile.distance) * width : 0);
  const y = (h: number) => (range > 0 ? height - ((h - profile.minHeight) / range) * height : height / 2);
  const fmt = (v: number) => v.toFixed(1);
  const line = profile.points
    .map((p, i) => `${i === 0 ? "M" : "L"}${fmt(x(p.distance))},${fmt(y(p.height))}`)
    .join(" ");
  const last = profile.points[profile.points.length - 1];
  const lastX = last ? fmt(x(last.distance)) : "0";
  const area = `${line} L${lastX},${fmt(height)} L0.0,${fmt(height)} Z`;
  return { line, area };
}
