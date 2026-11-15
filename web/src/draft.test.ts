import { readFileSync } from "node:fs";
import { join } from "node:path";
import { WebStorageStateStore } from "oidc-client-ts";
import { describe, expect, it } from "vitest";
import {
  addStop,
  canSave,
  clearDraft,
  type DraftStorage,
  draftFromRoute,
  insertIndexFor,
  insertStop,
  loadDraft,
  moveStop,
  newDraft,
  removeStop,
  routeDocument,
  saveDraft,
  withMode,
} from "./draft";
import { routeFromJson, type SavedRoute } from "./routes";

const brussels = { lat: 50.8503, lon: 4.3517 };
const namur = { lat: 50.4674, lon: 4.8718 };
const bastogne = { lat: 50.0034, lon: 5.7186 };
const antwerp = { lat: 51.2194, lon: 4.4025 };

const fixture = join(__dirname, "..", "..", "testdata", "web-parity", "routes", "full-moto.input.json");
const fullMoto = (): SavedRoute => routeFromJson(JSON.parse(readFileSync(fixture, "utf8"))) as SavedRoute;

/** sessionStorage as a Map; also the Storage oidc-client-ts writes its user into. */
class FakeStorage implements Storage {
  private readonly items = new Map<string, string>();
  get length(): number {
    return this.items.size;
  }
  clear(): void {
    this.items.clear();
  }
  getItem(key: string): string | null {
    return this.items.get(key) ?? null;
  }
  key(index: number): string | null {
    return [...this.items.keys()][index] ?? null;
  }
  removeItem(key: string): void {
    this.items.delete(key);
  }
  setItem(key: string, value: string): void {
    this.items.set(key, value);
  }
}

describe("the stop list", () => {
  const two = addStop(addStop(newDraft("rider", 1), brussels), bastogne);

  it("adds at the end, inserts, moves and removes", () => {
    expect(two.stops.map((s) => [s.lat, s.lon])).toEqual([
      [brussels.lat, brussels.lon],
      [bastogne.lat, bastogne.lon],
    ]);
    const three = insertStop(two, 1, namur);
    expect(three.stops.map((s) => s.lat)).toEqual([brussels.lat, namur.lat, bastogne.lat]);
    const moved = moveStop(three, 1, antwerp);
    expect(moved.stops[1]).toEqual({ ...antwerp, name: "" });
    expect(removeStop(moved, 0).stops.map((s) => s.lat)).toEqual([antwerp.lat, bastogne.lat]);
  });

  it("keeps a stop's name when it is dragged", () => {
    const route = draftFromRoute("rider", fullMoto());
    expect(moveStop(route, 0, antwerp).stops[0]?.name).toBe("Brussels");
  });

  it("drops the routed line on every stop or mode edit, and marks the draft unsaved", () => {
    const routed = { ...two, polyline: [brussels, bastogne], distanceMeters: 1, timeMs: 1, dirty: false };
    for (const edited of [
      addStop(routed, namur),
      insertStop(routed, 1, namur),
      moveStop(routed, 0, namur),
      removeStop(routed, 0),
      withMode(routed, "CAR"),
    ]) {
      expect(edited.polyline).toEqual([]);
      expect(edited.distanceMeters).toBeNull();
      expect(edited.timeMs).toBeNull();
      expect(edited.dirty).toBe(true);
    }
  });

  it("ignores a move or remove of a stop that isn't there", () => {
    expect(moveStop(two, 5, namur)).toBe(two);
    expect(removeStop(two, -1)).toBe(two);
  });

  it("puts a stop dragged off the line into the leg it was grabbed from", () => {
    const stops = [brussels, namur, bastogne];
    // Halfway Brussels–Namur, then halfway Namur–Bastogne.
    expect(insertIndexFor(stops, { lat: 50.66, lon: 4.61 })).toBe(1);
    expect(insertIndexFor(stops, { lat: 50.24, lon: 5.29 })).toBe(2);
    expect(insertIndexFor([brussels], namur)).toBe(1);
  });

  it("needs two stops to save", () => {
    expect(canSave(addStop(newDraft("rider", 1), brussels))).toBe(false);
    expect(canSave(two)).toBe(true);
  });
});

describe("the saved document", () => {
  it("is what SavedRoute.toJson writes, unknown keys kept, stamped with the save time", () => {
    const route = fullMoto();
    const edited = { ...moveStop(draftFromRoute("rider", route), 1, namur), name: "  Ardennes  " };
    const doc = routeDocument(edited, 1_800_000_000_000);
    expect(doc).toEqual({
      id: 1731500000000,
      name: "Ardennes",
      createdMs: 1731500000000,
      mode: "MOTO",
      stops: [
        { lat: 50.8503, lon: 4.3517, name: "Brussels" },
        { lat: namur.lat, lon: namur.lon },
        { lat: 49.9667, lon: 5.9333, name: "Bastogne" },
      ],
      polyline: [],
      sharedBy: "jules",
      editedAtMs: 1_800_000_000_000,
      curvy: "extra",
      futureField: { nested: [1, 2, 3] },
    });
    // What the phone reads back is what the planner showed.
    expect(routeFromJson(doc)?.stops[1]).toEqual({ ...namur, name: "" });
  });

  it("gives a new route the save time as id and creation time", () => {
    const draft = addStop(addStop(newDraft("rider", 1_700_000_000_000), brussels), bastogne);
    const routed = { ...draft, polyline: [brussels, bastogne], distanceMeters: 150_000, timeMs: 6_000_000 };
    const doc = routeDocument(routed, 1_700_000_005_000);
    expect(doc).toMatchObject({
      id: 1_700_000_000_000,
      createdMs: 1_700_000_005_000,
      editedAtMs: 1_700_000_005_000,
      polyline: [brussels.lat, brussels.lon, bastogne.lat, bastogne.lon],
      distanceMeters: 150_000,
      timeMs: 6_000_000,
    });
  });
});

describe("the draft across a sign-in", () => {
  it("comes back after the token is dropped and the rider signs in again", async () => {
    const storage = new FakeStorage();
    const draft = moveStop(draftFromRoute("rider-sub", fullMoto()), 1, namur);
    saveDraft(storage, draft);

    // What a 401 on save and the sign-in that follows do to sessionStorage: oidc-client-ts
    // removes its user, writes sign-in state, then a new user. auth.ts points both at the tab's
    // sessionStorage, the same one the draft is in.
    const oidc = new WebStorageStateStore({ store: storage });
    await oidc.set("user:issuer:detour-web", "{\"access_token\":\"old\"}");
    await oidc.remove("user:issuer:detour-web");
    await oidc.set("state-123", "{}");
    await oidc.remove("state-123");
    await oidc.set("user:issuer:detour-web", "{\"access_token\":\"new\"}");

    expect(loadDraft(storage, "rider-sub")).toEqual(draft);
  });

  it("is not restored into another rider's account", () => {
    const storage = new FakeStorage();
    saveDraft(storage, addStop(newDraft("rider-a", 1), brussels));
    expect(loadDraft(storage, "rider-b")).toBeNull();
    expect(loadDraft(storage, "rider-a")).toBeNull(); // dropped, not left for later
  });

  it("drops what doesn't read back as a draft", () => {
    const storage = new FakeStorage();
    storage.setItem("detour.draft", "{not json");
    expect(loadDraft(storage, "rider")).toBeNull();
    storage.setItem("detour.draft", JSON.stringify({ owner: "rider", id: 1, mode: "BIKE", stops: [] }));
    expect(loadDraft(storage, "rider")).toBeNull();
  });

  it("is gone once cleared", () => {
    const storage = new FakeStorage();
    saveDraft(storage, newDraft("rider", 1));
    clearDraft(storage);
    expect(loadDraft(storage, "rider")).toBeNull();
  });
});
