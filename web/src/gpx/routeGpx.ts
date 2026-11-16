import { type RouteStop, routeFromJson, type SavedRoute } from "../routes";

// GPX import and export for planned routes, as the phone does them.
//
// A copy of `RouteGpx` in shared/src/commonMain/kotlin/com/jellemax/detour/data/RouteGpx.kt.
// The web planner cannot link the Kotlin core, so the rule is copied, and
// testdata/web-parity/gpx/ pins both copies to the same answers: routeGpx.test.ts here,
// WebParityGpxTest in shared/src/androidHostTest. Change one side and the other side's test
// fails until the fixture or the copy follows.
//
// The Kotlin side is a tolerant regex scan, not an XML parser (commonMain has none), so this is
// one too: a DOMParser would read some files differently from the phone.

const FILE_PREFIX = "detour-route-";
const FILE_EXTENSION = ".gpx";
export const MIME_TYPE = "application/gpx+xml";

const MAX_STOPS = 25;
const DEFAULT_NAME = "Imported route";

/** What an export needs of a route: a draft has these too. */
export type GpxRoute = Pick<SavedRoute, "name" | "stops" | "polyline">;

/** Suggested export file name for [route]'s current name. */
export function fileName(route: Pick<SavedRoute, "name">): string {
  return FILE_PREFIX + slug(route.name) + FILE_EXTENSION;
}

/** [route] as GPX 1.1: the stops as an `<rte>`, the routed geometry (when there is one) as a
 *  `<trk>` beside it. No `<time>`: a planned route has none. */
export function buildGpx(route: GpxRoute): string {
  const out: string[] = [];
  out.push('<?xml version="1.0" encoding="UTF-8"?>\n');
  out.push('<gpx version="1.1" creator="Detour"');
  out.push(' xmlns="http://www.topografix.com/GPX/1/1">\n');
  out.push(`  <metadata><name>${escape(route.name)}</name></metadata>\n`);
  out.push("  <rte>\n");
  out.push(`    <name>${escape(route.name)}</name>\n`);
  for (const s of route.stops) {
    out.push(`    <rtept lat="${fixed(s.lat)}" lon="${fixed(s.lon)}">`);
    if (s.name !== "") out.push(`<name>${escape(s.name)}</name>`);
    out.push("</rtept>\n");
  }
  out.push("  </rte>\n");
  if (route.polyline.length > 0) {
    out.push("  <trk>\n    <trkseg>\n");
    for (const p of route.polyline) {
      out.push(`      <trkpt lat="${fixed(p.lat)}" lon="${fixed(p.lon)}"/>\n`);
    }
    out.push("    </trkseg>\n  </trk>\n");
  }
  out.push("</gpx>\n");
  return out.join("");
}

/**
 * Reads a GPX from any tool: `<rte>` stops when there are two, else `<trk>` points thinned to
 * at most 25, else the `<wpt>` list. Null when fewer than two usable points come out. The
 * polyline is every `<trk>` point, unthinned; empty for a file without one. Mode is CAR, as on
 * the phone: a GPX carries none.
 */
export function parseGpx(text: string, nowMs: number): SavedRoute | null {
  const rtept = points(text, "rtept");
  const trkpt = points(text, "trkpt");
  const stops = rtept.length >= 2 ? rtept : trkpt.length >= 2 ? thin(trkpt, MAX_STOPS) : points(text, "wpt");
  if (stops.length < 2) return null;
  return {
    id: nowMs,
    name: routeName(text),
    createdMs: nowMs,
    mode: "CAR",
    stops,
    polyline: trkpt.map((p) => ({ lat: p.lat, lon: p.lon })),
    distanceMeters: null,
    timeMs: null,
    sharedBy: "",
    origin: "PLANNED",
    editedAtMs: 0,
    raw: {},
  };
}

/** A file the phone could import: its JSON route export when it starts with `{`, else GPX. */
export function parseRouteFile(text: string, nowMs: number): SavedRoute | null {
  if (!kotlinTrimStart(text).startsWith("{")) return parseGpx(text, nowMs);
  try {
    return routeFromJson(JSON.parse(text));
  } catch {
    return null;
  }
}

// --- Writing ---------------------------------------------------------------------------------

/** Seven fixed places (~1 cm), never scientific notation, rounded half to even as Kotlin's
 *  `round`: `Math.round` would round 4.00000025 up where the phone rounds it down. */
function fixed(value: number, decimals = 7): string {
  let scale = 1;
  for (let i = 0; i < decimals; i++) scale *= 10;
  const scaled = roundHalfEven(Math.abs(value) * scale);
  const whole = Math.trunc(scaled / scale);
  const frac = String(scaled % scale).padStart(decimals, "0");
  const sign = value < 0 && scaled !== 0 ? "-" : "";
  return `${sign}${whole}.${frac}`;
}

export function roundHalfEven(x: number): number {
  const down = Math.floor(x);
  const diff = x - down;
  if (diff < 0.5) return down;
  if (diff > 0.5) return down + 1;
  return down % 2 === 0 ? down : down + 1;
}

/** The one part of the document that carries free-form text. Quotes stay: names are element
 *  text, never attributes. */
function escape(text: string): string {
  return text.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;");
}

/** Lowercased, everything but letters and digits collapsed to one `-`. Walks UTF-16 units as
 *  Kotlin's `map` over a String does, so an emoji (two units, neither a letter) is two dashes
 *  before the collapse on both sides. */
function slug(name: string): string {
  const lower = name.toLowerCase();
  let dashed = "";
  for (let i = 0; i < lower.length; i++) {
    const c = lower.charAt(i);
    dashed += LETTER_OR_DIGIT.test(c) ? c : "-";
  }
  const collapsed = dashed.replace(/-+/g, "-").replace(/^-+|-+$/g, "");
  return collapsed === "" ? "route" : collapsed;
}

/** Kotlin/JVM `Char.isLetterOrDigit`: letter categories and decimal digits. */
const LETTER_OR_DIGIT = /^[\p{L}\p{Nd}]$/u;

// --- Reading ---------------------------------------------------------------------------------

const LAT_ATTR = /lat=(["'])(-?[0-9.]+)\1/;
const LON_ATTR = /lon=(["'])(-?[0-9.]+)\1/;
const NAME_TAG = /<name>([\s\S]*?)<\/name>/;

/** Every `<tag .../>` or `<tag ...>...</tag>`, in document order, with lat/lon from the opening
 *  tag and the first inner `<name>`. A point missing either coordinate is skipped. */
function points(text: string, tag: string): RouteStop[] {
  const point = new RegExp(`<${tag}\\b([^>]*?)(?:/>|>([\\s\\S]*?)</${tag}>)`, "g");
  const out: RouteStop[] = [];
  for (const m of text.matchAll(point)) {
    const attrs = m[1] ?? "";
    const lat = number(LAT_ATTR.exec(attrs)?.[2]);
    const lon = number(LON_ATTR.exec(attrs)?.[2]);
    if (lat === null || lon === null) continue;
    const name = NAME_TAG.exec(m[2] ?? "")?.[1];
    out.push({ lat, lon, name: name === undefined ? "" : kotlinTrim(unescape(name)) });
  }
  return out;
}

/** Kotlin's `toDoubleOrNull` on what the attribute pattern lets through (`-?[0-9.]+`): null for
 *  "1.2.3" or ".", as `Number` gives NaN for exactly those. */
function number(text: string | undefined): number | null {
  if (text === undefined) return null;
  const n = Number(text);
  return Number.isNaN(n) ? null : n;
}

/** `<metadata><name>`, else `<trk><name>`, else `<rte><name>`, else the fallback. */
function routeName(text: string): string {
  for (const tag of ["metadata", "trk", "rte"]) {
    const block = new RegExp(`<${tag}\\b[\\s\\S]*?</${tag}>`).exec(text)?.[0];
    if (block === undefined) continue;
    const name = NAME_TAG.exec(block)?.[1];
    if (name === undefined) continue;
    const trimmed = kotlinTrim(unescape(name));
    if (trimmed !== "") return trimmed;
  }
  return DEFAULT_NAME;
}

/** First, last and evenly spaced points between: a trace can be thousands of points, far more
 *  than makes sense as routing stops. */
function thin(points: RouteStop[], max: number): RouteStop[] {
  if (points.length <= max) return points;
  const out: RouteStop[] = [];
  for (let i = 0; i < max; i++) {
    out.push(points[Math.floor((i * (points.length - 1)) / (max - 1))] as RouteStop);
  }
  return out;
}

function unescape(text: string): string {
  return text.replaceAll("&lt;", "<").replaceAll("&gt;", ">").replaceAll("&amp;", "&");
}

// Kotlin/JVM `isWhitespace` (Character.isWhitespace or isSpaceChar), which `trim` uses. JS's
// own trim differs: it strips U+FEFF, Kotlin doesn't; Kotlin strips U+001C-U+001F, JS doesn't.
const WS = "\\t\\n\\v\\f\\r \\x1C-\\x1F\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000";
const TRIM = new RegExp(`^[${WS}]+|[${WS}]+$`, "g");
const TRIM_START = new RegExp(`^[${WS}]+`);

function kotlinTrim(text: string): string {
  return text.replace(TRIM, "");
}

function kotlinTrimStart(text: string): string {
  return text.replace(TRIM_START, "");
}
