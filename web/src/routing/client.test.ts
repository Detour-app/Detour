import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NoRoute, parseRoute, RouteThrottle } from "./client";

describe("RouteThrottle", () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.useRealTimers();
  });

  function setup() {
    const results: number[] = [];
    const errors: unknown[] = [];
    const sent: number[] = [];
    const throttle = new RouteThrottle<number>(
      (r) => results.push(r),
      (e) => errors.push(e),
      1000,
      () => Date.now(),
    );
    const task = (n: number) => () => {
      sent.push(n);
      return Promise.resolve(n);
    };
    return { throttle, task, results, errors, sent };
  }

  it("sends the first request at once", async () => {
    const { throttle, task, sent, results } = setup();
    throttle.request(task(1));
    await vi.advanceTimersByTimeAsync(0);
    expect(sent).toEqual([1]);
    expect(results).toEqual([1]);
    expect(throttle.pending).toBe(false);
  });

  it("sends at most one request a second, the latest of a burst", async () => {
    const { throttle, task, sent, results } = setup();
    throttle.request(task(1));
    await vi.advanceTimersByTimeAsync(0);
    throttle.request(task(2));
    throttle.request(task(3));
    await vi.advanceTimersByTimeAsync(999);
    expect(sent).toEqual([1]);
    expect(throttle.pending).toBe(true);
    await vi.advanceTimersByTimeAsync(1);
    expect(sent).toEqual([1, 3]);
    expect(results).toEqual([1, 3]);
  });

  it("drops an answer that arrives after a newer request", async () => {
    const { throttle, results } = setup();
    let finishSlow: (n: number) => void = () => {};
    throttle.request(() => new Promise<number>((resolve) => (finishSlow = resolve)));
    await vi.advanceTimersByTimeAsync(0);
    throttle.request(() => Promise.resolve(2));
    finishSlow(1);
    await vi.advanceTimersByTimeAsync(1000);
    expect(results).toEqual([2]);
  });

  it("is no longer pending when its callback runs", async () => {
    const seen: boolean[] = [];
    const throttle: RouteThrottle<number> = new RouteThrottle<number>(
      () => seen.push(throttle.pending),
      () => {},
    );
    throttle.request(() => Promise.resolve(1));
    await vi.advanceTimersByTimeAsync(0);
    expect(seen).toEqual([false]);
  });

  it("passes failures on, and sends nothing after cancel", async () => {
    const { throttle, task, sent, errors } = setup();
    throttle.request(() => Promise.reject(new NoRoute()));
    await vi.advanceTimersByTimeAsync(0);
    expect(errors[0]).toBeInstanceOf(NoRoute);
    throttle.request(task(2));
    throttle.cancel();
    await vi.advanceTimersByTimeAsync(2000);
    expect(sent).toEqual([]);
  });
});

describe("parseRoute", () => {
  it("keeps geometry, distance and time", () => {
    const body = JSON.stringify({
      paths: [{ distance: 1234.5, time: 98765, points: { coordinates: [[4.35, 50.85], [4.4, 51.2]] } }],
    });
    expect(parseRoute(body)).toEqual({
      polyline: [
        { lat: 50.85, lon: 4.35 },
        { lat: 51.2, lon: 4.4 },
      ],
      distanceMeters: 1234.5,
      timeMs: 98765,
    });
  });

  it("reads no path, or a path of one point, as no route", () => {
    expect(() => parseRoute(JSON.stringify({ paths: [] }))).toThrow(NoRoute);
    expect(() => parseRoute(JSON.stringify({ paths: [{ points: { coordinates: [[4, 50]] } }] }))).toThrow(
      NoRoute,
    );
  });
});
