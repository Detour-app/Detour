import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { parityView, type RouteStop } from "../routes";
import { buildGpx, fileName, parseGpx, parseRouteFile, roundHalfEven } from "./routeGpx";

// The same fixtures WebParityGpxTest reads on the Kotlin side (testdata/web-parity/README.md).
const fixtures = join(__dirname, "..", "..", "..", "testdata", "web-parity", "gpx");

function cases(folder: string): string[] {
  return readdirSync(join(fixtures, folder))
    .filter((f) => f.endsWith(".input.json"))
    .map((f) => f.slice(0, -".input.json".length))
    .sort();
}

const read = (folder: string, name: string): unknown =>
  JSON.parse(readFileSync(join(fixtures, folder, name), "utf8"));

interface ExportInput {
  name: string;
  stops: RouteStop[];
  polyline: [number, number][];
}

interface ImportInput {
  nowMs: number;
  file: string;
}

describe("buildGpx parity with shared/.../data/RouteGpx.kt", () => {
  const names = cases("export");
  it("has fixtures to check", () => expect(names.length).toBeGreaterThan(0));

  it.each(names)("%s", (name) => {
    const input = read("export", `${name}.input.json`) as ExportInput;
    const route = { ...input, polyline: input.polyline.map(([lat, lon]) => ({ lat, lon })) };
    expect({ fileName: fileName(route), gpx: buildGpx(route) }).toEqual(read("export", `${name}.expected.json`));
  });
});

describe("parseRouteFile parity with shared/.../data/RouteGpx.kt", () => {
  const names = cases("import");
  it("has fixtures to check", () => expect(names.length).toBeGreaterThan(0));

  it.each(names)("%s", (name) => {
    const input = read("import", `${name}.input.json`) as ImportInput;
    expect(parityView(parseRouteFile(input.file, input.nowMs))).toEqual(read("import", `${name}.expected.json`));
  });
});

describe("round trip", () => {
  it("reads back the stops and line it wrote", () => {
    const route = {
      name: "Loop",
      stops: [
        { lat: 50.1234567, lon: 4.7654321, name: "Start & end" },
        { lat: 50.2, lon: 4.9, name: "" },
      ],
      polyline: [
        { lat: 50.1234567, lon: 4.7654321 },
        { lat: 50.2, lon: 4.9 },
      ],
    };
    const back = parseGpx(buildGpx(route), 1);
    expect(back?.name).toBe("Loop");
    expect(back?.stops).toEqual(route.stops);
    expect(back?.polyline).toEqual(route.polyline);
  });
});

describe("roundHalfEven", () => {
  it("rounds ties to the even neighbour, as Kotlin's round", () => {
    expect([0.5, 1.5, 2.5, 3.5, 2.4, 2.6].map(roundHalfEven)).toEqual([0, 2, 2, 4, 2, 3]);
  });
});
