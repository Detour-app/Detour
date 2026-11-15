import { routeFromJson, type SavedRoute } from "./routes";

// Everything here is same-origin: the API under /api behind the one-hostname proxy
// (docker/prod/nginx), so there is no CORS to configure and no base URL to discover.

/** The token was refused: expired between the check and the call, or revoked. */
export class SignInRequired extends Error {
  constructor() {
    super("Sign in again");
  }
}

export interface Capabilities {
  issuer: string;
}

export async function fetchCapabilities(): Promise<Capabilities> {
  const res = await fetch("/api/capabilities", { headers: { Accept: "application/json" } });
  if (!res.ok) throw new Error(`/api/capabilities answered ${res.status}`);
  const body: unknown = await res.json();
  const issuer = (body as { idp?: { issuer?: unknown } }).idp?.issuer;
  if (typeof issuer !== "string" || issuer === "") {
    throw new Error("/api/capabilities names no sign-in issuer");
  }
  return { issuer };
}

/** The rider's saved routes, newest first. Documents too broken to show are left out, as the
 *  phone leaves them out. */
export async function fetchRoutes(accessToken: string): Promise<SavedRoute[]> {
  const res = await fetch("/api/routes", {
    headers: { Accept: "application/json", Authorization: `Bearer ${accessToken}` },
  });
  if (res.status === 401) throw new SignInRequired();
  if (!res.ok) throw new Error(`/api/routes answered ${res.status}`);
  const body: unknown = await res.json();
  const docs = (body as { routes?: unknown }).routes;
  return parseRouteList(docs);
}

export function parseRouteList(docs: unknown): SavedRoute[] {
  if (!Array.isArray(docs)) return [];
  return docs
    .map(routeFromJson)
    .filter((r): r is SavedRoute => r !== null)
    .sort((a, b) => b.id - a.id);
}

/** A save refused because the stored copy carries a newer edit (409). [stored] is that copy,
 *  parsed, or null when it is too broken to show. */
export class NewerCopy extends Error {
  constructor(readonly stored: SavedRoute | null) {
    super("This route was changed elsewhere after you opened it.");
  }
}

/** A save refused because the route was deleted, here or on a phone (410). */
export class RouteDeleted extends Error {
  constructor() {
    super("This route was deleted elsewhere.");
  }
}

/** `PUT /api/routes/{id}`: create or edit one route (#912). Returns the stored document. */
export async function saveRoute(
  accessToken: string,
  doc: Record<string, unknown>,
): Promise<SavedRoute | null> {
  const res = await fetch(`/api/routes/${String(doc.id)}`, {
    method: "PUT",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      Authorization: `Bearer ${accessToken}`,
    },
    body: JSON.stringify(doc),
  });
  if (res.status === 401) throw new SignInRequired();
  if (res.status === 410) throw new RouteDeleted();
  if (res.status === 409) {
    const body: unknown = await res.json().catch(() => null);
    throw new NewerCopy(routeFromJson((body as { stored?: unknown } | null)?.stored));
  }
  if (!res.ok) throw new Error(`Saving answered ${res.status}`);
  return routeFromJson(await res.json());
}

/** `DELETE /api/routes/{id}`: idempotent, a route already gone is fine. */
export async function deleteRoute(accessToken: string, id: number): Promise<void> {
  const res = await fetch(`/api/routes/${id}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${accessToken}` },
  });
  if (res.status === 401) throw new SignInRequired();
  if (!res.ok) throw new Error(`Deleting answered ${res.status}`);
}
