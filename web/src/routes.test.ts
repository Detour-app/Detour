import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";
import { formatDistance, formatDuration, parityView, routeFromJson } from "./routes";

// The same fixtures WebParityRoutesTest reads on the Kotlin side (testdata/web-parity/README.md).
const fixtures = join(__dirname, "..", "..", "testdata", "web-parity", "routes");
const cases = readdirSync(fixtures)
  .filter((f) => f.endsWith(".input.json"))
  .map((f) => f.slice(0, -".input.json".length))
  .sort();

const read = (name: string): unknown => JSON.parse(readFileSync(join(fixtures, name), "utf8"));

describe("routeFromJson parity with shared/.../data/Routes.kt", () => {
  it("has fixtures to check", () => {
    expect(cases.length).toBeGreaterThan(0);
  });

  it.each(cases)("%s", (name) => {
    const parsed = routeFromJson(read(`${name}.input.json`));
    expect(parityView(parsed)).toEqual(read(`${name}.expected.json`));
  });

  it("keeps keys it does not understand", () => {
    const input = read("full-moto.input.json");
    const parsed = routeFromJson(input);
    expect(parsed?.raw).toEqual(input);
  });
});

describe("formatting", () => {
  it("shows distance in km with one decimal", () => {
    expect(formatDistance(187654.3)).toBe("187.7 km");
    expect(formatDistance(null)).toBe("");
  });

  it("shows hours only past the hour", () => {
    expect(formatDuration(42 * 60000)).toBe("42 min");
    expect(formatDuration(65 * 60000)).toBe("1 h 05 min");
    expect(formatDuration(null)).toBe("");
  });
});
