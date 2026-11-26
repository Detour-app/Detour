// The GraphHopper request for an A→B route through the planner's stops, built the way the phone
// builds it.
//
// A copy of, in shared/src/commonMain/kotlin/com/jellemax/detour/data/:
// - `routeViaRequest` in RoutingClient.kt (no heading, no penalty area: the planner has neither),
// - `RoutePreferences.supportedBy` / `rulesFor` / `priorityRules` in RoutePreferences.kt,
// - `CurvyPreset.priorityRules` in CurvyRouting.kt,
// - `RoutingSupport.encodedValues(infoBody)` in RoutingSupport.kt.
// testdata/web-parity/routing/ pins both copies to the same requests: request.test.ts here,
// WebParityRoutingTest in shared/src/androidHostTest. Change one side and the other side's test
// fails until the fixture or the copy follows.

import type { LatLon, TravelMode } from "../routes";

export type CurvyPreset = "FAST" | "BALANCED" | "TWISTY";
export const CURVY_PRESETS: readonly CurvyPreset[] = ["FAST", "BALANCED", "TWISTY"];

/** RoutePreferences.kt's fields, same names and defaults. */
export interface RoutePreferences {
  avoidHighways: boolean;
  avoidSmallRoads: boolean;
  avoidTolls: boolean;
  avoidFerries: boolean;
  avoidUnpaved: boolean;
  curvy: CurvyPreset;
}

export const DEFAULT_PREFERENCES: RoutePreferences = {
  avoidHighways: false,
  avoidSmallRoads: false,
  avoidTolls: false,
  avoidFerries: false,
  avoidUnpaved: false,
  curvy: "FAST",
};

/** RoutingEncodedValue: the encoded values a rule may read only when the graph has them. */
export const EncodedValue = {
  TOLL: "toll",
  SURFACE: "surface",
  ROAD_ENVIRONMENT: "road_environment",
  CURVATURE: "curvature",
} as const;

export interface PriorityRule {
  if: string;
  multiply_by: number;
}

/** One `/route` request: a GET when [body] is null, else a POST of [body]. */
export interface RouteRequest {
  url: string;
  body: string | null;
}

/** TravelMode.ghProfile. */
export function ghProfile(mode: TravelMode): string {
  return mode === "MOTO" ? "moto" : "car";
}

/** The encoded values in a GraphHopper `/info` body, or null when the body is not one. Null is
 *  "can't tell"; a caller treats it as "none supported". */
export function encodedValues(infoBody: string): Set<string> | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(infoBody);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return null;
  const values = (parsed as Record<string, unknown>).encoded_values;
  if (typeof values !== "object" || values === null || Array.isArray(values)) return null;
  return new Set(Object.keys(values));
}

export function supports(values: Set<string> | null, value: string): boolean {
  return values?.has(value) === true;
}

/** [prefs] with every option the graph can't honour switched off: a rule on a missing encoded
 *  value is an HTTP 400, not a worse route. */
export function supportedBy(prefs: RoutePreferences, values: Set<string> | null): RoutePreferences {
  return {
    ...prefs,
    avoidTolls: prefs.avoidTolls && supports(values, EncodedValue.TOLL),
    avoidFerries: prefs.avoidFerries && supports(values, EncodedValue.ROAD_ENVIRONMENT),
    avoidUnpaved: prefs.avoidUnpaved && supports(values, EncodedValue.SURFACE),
    curvy: supports(values, EncodedValue.CURVATURE) ? prefs.curvy : "FAST",
  };
}

const rule = (condition: string, multiplyBy: number): PriorityRule => ({
  if: condition,
  multiply_by: multiplyBy,
});

/** RoutePreferences.priorityRules: the avoid options, in the Kotlin order. */
export function avoidRules(prefs: RoutePreferences): PriorityRule[] {
  const rules: PriorityRule[] = [];
  if (prefs.avoidHighways) rules.push(rule("road_class == MOTORWAY || road_class == TRUNK", 0.05));
  if (prefs.avoidSmallRoads) {
    rules.push(rule("road_class == UNCLASSIFIED || road_class == RESIDENTIAL", 0.2));
    rules.push(rule("road_class == LIVING_STREET || road_class == SERVICE", 0.1));
    rules.push(rule("road_class == TRACK || road_class == PATH", 0.02));
  }
  if (prefs.avoidTolls) rules.push(rule("toll == ALL", 0.05));
  if (prefs.avoidFerries) rules.push(rule("road_environment == FERRY", 0.05));
  if (prefs.avoidUnpaved) {
    rules.push(
      rule("surface == UNPAVED || surface == GRAVEL || surface == DIRT || surface == SAND", 0.05),
    );
  }
  return rules;
}

/** CurvyPreset.priorityRules. */
export function curvyRules(preset: CurvyPreset): PriorityRule[] {
  const STRAIGHT = 0.98;
  const NEARLY_STRAIGHT = 0.9;
  switch (preset) {
    case "FAST":
      return [];
    case "BALANCED":
      return [
        rule("road_class == MOTORWAY || road_class == TRUNK", 0.5),
        rule(`curvature >= ${STRAIGHT}`, 0.7),
      ];
    case "TWISTY":
      return [
        rule("road_class == MOTORWAY || road_class == TRUNK", 0.1),
        rule(`curvature >= ${STRAIGHT}`, 0.4),
        rule(`curvature >= ${NEARLY_STRAIGHT} && curvature < ${STRAIGHT}`, 0.7),
      ];
  }
}

/** RoutePreferences.rulesFor: the avoid rules, then the curvy preset's on `moto` only. */
export function rulesFor(prefs: RoutePreferences, profile: string): PriorityRule[] {
  const avoid = avoidRules(prefs);
  if (profile !== "moto") return avoid;
  return [...avoid, ...curvyRules(prefs.curvy)];
}

/**
 * routeViaRequest without a heading or a penalty area. No rule: a plain GET. Any rule: a POST
 * with a custom model, which needs flexible routing, hence `ch.disable`. [prefs] must already
 * have passed [supportedBy].
 */
export function routeViaRequest(
  base: string,
  points: readonly LatLon[],
  profile: string,
  prefs: RoutePreferences,
): RouteRequest {
  const rules = rulesFor(prefs, profile);
  if (rules.length === 0) {
    let query = `${base}/route?profile=${profile}`;
    for (const p of points) query += `&point=${p.lat},${p.lon}`;
    query += "&points_encoded=false&details=max_speed&details=roundabout&details=road_class";
    return { url: query, body: null };
  }
  const body = {
    profile,
    points: points.map((p) => [p.lon, p.lat]),
    points_encoded: false,
    details: ["max_speed", "roundabout", "road_class"],
    "ch.disable": true,
    custom_model: { priority: rules },
  };
  return { url: `${base}/route`, body: JSON.stringify(body) };
}

/** The parity fixtures' `expected` shape (testdata/web-parity/README.md): the path, the query as
 *  ordered pairs with `point` split into numbers, and the body parsed. WebParityRoutingTest
 *  builds the same from the Kotlin request, so number formatting (`4` vs `4.0`) doesn't count. */
export function requestView(request: RouteRequest): unknown {
  const [path = "", query] = request.url.split("?", 2);
  const pairs =
    query === undefined
      ? []
      : query.split("&").map((pair) => {
          const [key = "", value = ""] = pair.split("=", 2);
          return [key, key === "point" ? value.split(",").map(Number) : value];
        });
  return {
    method: request.body === null ? "GET" : "POST",
    path,
    query: pairs,
    body: request.body === null ? null : JSON.parse(request.body),
  };
}
