import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import type { LatLon, TravelMode } from "../routes";
import {
  DEFAULT_PREFERENCES,
  encodedValues,
  ghProfile,
  type RoutePreferences,
  requestView,
  routeViaRequest,
  supportedBy,
} from "./request";

// The same fixtures WebParityRoutingTest reads on the Kotlin side (testdata/web-parity/README.md).
const fixtures = join(__dirname, "..", "..", "..", "testdata", "web-parity", "routing");
const cases = readdirSync(fixtures)
  .filter((f) => f.endsWith(".input.json"))
  .map((f) => f.slice(0, -".input.json".length))
  .sort();

const read = (name: string): unknown => JSON.parse(readFileSync(join(fixtures, name), "utf8"));

interface RoutingInput {
  mode: TravelMode;
  points: LatLon[];
  preferences: Partial<RoutePreferences>;
  encodedValues: string[] | null;
}

/** A fixture input as the planner sends it: the preferences through the /info gate, then the
 *  request, as main.ts's `route` does. */
function requestFor(input: RoutingInput): unknown {
  const prefs = supportedBy(
    { ...DEFAULT_PREFERENCES, ...input.preferences },
    input.encodedValues === null ? null : new Set(input.encodedValues),
  );
  return requestView(routeViaRequest("", input.points, ghProfile(input.mode), prefs));
}

describe("routeViaRequest parity with shared/.../data/RoutingClient.kt", () => {
  it("has fixtures to check", () => {
    expect(cases.length).toBeGreaterThan(0);
  });

  it.each(cases)("%s", (name) => {
    const input = read(`${name}.input.json`) as RoutingInput;
    expect(requestFor(input)).toEqual(read(`${name}.expected.json`));
  });
});

describe("encodedValues", () => {
  it("reads the keys of /info's encoded_values", () => {
    const body = JSON.stringify({ encoded_values: { toll: ["ALL"], curvature: [] }, version: "12" });
    expect(encodedValues(body)).toEqual(new Set(["toll", "curvature"]));
  });

  it("can't tell from a body that is not GraphHopper's /info", () => {
    expect(encodedValues("<html>502</html>")).toBeNull();
    expect(encodedValues("{}")).toBeNull();
    expect(encodedValues("[]")).toBeNull();
  });
});
