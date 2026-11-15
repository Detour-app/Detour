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
