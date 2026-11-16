import { describe, expect, it } from "vitest";
import { parseRoute } from "./client";
import {
  infoHasElevation,
  profileFromCoordinates,
  profileFromRoute,
  profilePaths,
  profileSummary,
  withElevation,
} from "./elevation";
import { DEFAULT_PREFERENCES, routeViaRequest } from "./request";

// 0.001° of latitude is ~111.2 m anywhere, so points stepping north make distances easy to check.
const north = (i: number, height: number) => [4.4, 50.8 + i * 0.001, height];
const STEP_M = 111.19;

describe("infoHasElevation", () => {
  it("reads /info's elevation flag", () => {
    expect(infoHasElevation(JSON.stringify({ elevation: true, profiles: [] }))).toBe(true);
    expect(infoHasElevation(JSON.stringify({ elevation: false, profiles: [] }))).toBe(false);
  });

  it("is false for a body without the flag or not JSON", () => {
    expect(infoHasElevation(JSON.stringify({ profiles: [] }))).toBe(false);
    expect(infoHasElevation(JSON.stringify({ elevation: "true" }))).toBe(false);
    expect(infoHasElevation("<html>")).toBe(false);
    expect(infoHasElevation("null")).toBe(false);
  });
});

describe("withElevation", () => {
  const stops = [
    { lat: 50.8, lon: 4.4 },
    { lat: 50.9, lon: 4.5 },
  ];

  it("adds elevation=true to the GET", () => {
    const plain = routeViaRequest("", stops, "car", DEFAULT_PREFERENCES);
    expect(withElevation(plain)).toEqual({ url: `${plain.url}&elevation=true`, body: null });
  });

  it("adds elevation to the POST body and keeps the rest", () => {
    const post = routeViaRequest("", stops, "moto", { ...DEFAULT_PREFERENCES, curvy: "TWISTY" });
    const asked = withElevation(post);
    expect(asked.url).toBe(post.url);
    expect(JSON.parse(asked.body ?? "")).toEqual({ ...JSON.parse(post.body ?? ""), elevation: true });
  });
});

describe("profileFromCoordinates", () => {
  it("sums only the rises as climb, over an odd number of points", () => {
    const p = profileFromCoordinates([north(0, 100), north(1, 130), north(2, 120), north(3, 150), north(4, 140)]);
    expect(p).not.toBeNull();
    expect(p?.points).toHaveLength(5);
    expect(p?.climb).toBeCloseTo(60, 6); // +30, -10, +30, -10
    expect(p?.minHeight).toBe(100);
    expect(p?.maxHeight).toBe(150);
    expect(p?.distance).toBeCloseTo(4 * STEP_M, 0);
    expect(p?.points.map((q) => Math.round(q.distance))).toEqual([0, 111, 222, 334, 445]);
  });

  it("works over an even number of points, ending on a climb", () => {
    const p = profileFromCoordinates([north(0, 50), north(1, 40), north(2, 45), north(3, 80)]);
    expect(p?.points).toHaveLength(4);
    expect(p?.climb).toBeCloseTo(40, 6); // -10, +5, +35
    expect(p?.distance).toBeCloseTo(3 * STEP_M, 0);
  });

  it("is zero climb on a descent and on the flat", () => {
    expect(profileFromCoordinates([north(0, 300), north(1, 200)])?.climb).toBe(0);
    expect(profileFromCoordinates([north(0, 7), north(1, 7), north(2, 7)])?.climb).toBe(0);
  });

  it("is null for 2-D points, a mix, or fewer than two points", () => {
    expect(profileFromCoordinates([[4.4, 50.8], [4.4, 50.801]])).toBeNull();
    expect(profileFromCoordinates([north(0, 10), [4.4, 50.801], north(2, 12)])).toBeNull();
    expect(profileFromCoordinates([north(0, 10)])).toBeNull();
    expect(profileFromCoordinates([])).toBeNull();
    expect(profileFromCoordinates(undefined)).toBeNull();
    expect(profileFromCoordinates([north(0, 10), [4.4, 50.801, null]])).toBeNull();
  });
});

describe("profileFromRoute", () => {
  const body = (coordinates: unknown[]) =>
    JSON.stringify({ paths: [{ distance: 222, time: 30000, points: { coordinates } }] });

  it("reads a 3-D answer, and the saved geometry from it stays 2-D", () => {
    const text = body([north(0, 10), north(1, 20), north(2, 15)]);
    expect(profileFromRoute(text)?.climb).toBeCloseTo(10, 6);
    const routed = parseRoute(text);
    expect(routed.polyline).toHaveLength(3);
    for (const point of routed.polyline) expect(Object.keys(point).sort()).toEqual(["lat", "lon"]);
  });

  it("is null for a 2-D answer or one with no path", () => {
    expect(profileFromRoute(body([[4.4, 50.8], [4.4, 50.801]]))).toBeNull();
    expect(profileFromRoute(JSON.stringify({ paths: [] }))).toBeNull();
    expect(profileFromRoute("oops")).toBeNull();
  });
});

describe("profileSummary", () => {
  it("rounds to whole metres", () => {
    const p = profileFromCoordinates([north(0, 12.4), north(1, 410.6), north(2, 100)]);
    expect(p && profileSummary(p)).toBe("Climb 398 m · 12–411 m");
  });
});

describe("profilePaths", () => {
  it("spans the box, highest point at the top", () => {
    const p = profileFromCoordinates([north(0, 100), north(1, 200), north(2, 100)]);
    expect(p).not.toBeNull();
    if (!p) return;
    const { line, area } = profilePaths(p, 300, 60);
    expect(line).toBe("M0.0,60.0 L150.0,0.0 L300.0,60.0");
    expect(area).toBe("M0.0,60.0 L150.0,0.0 L300.0,60.0 L300.0,60.0 L0.0,60.0 Z");
  });

  it("draws a flat route across the middle", () => {
    const p = profileFromCoordinates([north(0, 5), north(1, 5)]);
    expect(p && profilePaths(p, 100, 40).line).toBe("M0.0,20.0 L100.0,20.0");
  });
});
