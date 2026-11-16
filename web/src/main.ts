import "./style.css";
import type { User, UserManager } from "oidc-client-ts";
import {
  deleteRoute,
  fetchCapabilities,
  fetchRoutes,
  NewerCopy,
  RouteDeleted,
  saveRoute,
  SignInRequired,
} from "./api";
import { createUserManager, currentUser, displayName } from "./auth";
import {
  addStop,
  canSave,
  clearDraft,
  type Draft,
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
  withPreferences,
} from "./draft";
import { createMap, type MapEditHandlers } from "./map";
import { formatDistance, formatDuration, type SavedRoute, type TravelMode } from "./routes";
import { fetchGraphInfo, fetchRoute, NoRoute, type RoutedPath, RouteThrottle, SlowDown } from "./routing/client";
import { type ElevationProfile, profilePaths, profileSummary, withElevation } from "./routing/elevation";
import {
  type CurvyPreset,
  EncodedValue,
  ghProfile,
  type RouteRequest,
  type RoutePreferences,
  routeViaRequest,
  supportedBy,
  supports,
} from "./routing/request";

// Sign in, list the saved routes, open one to edit it or start a new one (#913, #914).

const el = <T extends HTMLElement>(id: string): T => {
  const found = document.getElementById(id);
  if (!found) throw new Error(`#${id} missing from index.html`);
  return found as T;
};

const status = el<HTMLDivElement>("status");
const signIn = el<HTMLButtonElement>("sign-in");
const signOut = el<HTMLButtonElement>("sign-out");
const who = el<HTMLSpanElement>("who");
const listPanel = el<HTMLDivElement>("list-panel");
const newRoute = el<HTMLButtonElement>("new-route");
const list = el<HTMLUListElement>("routes");
const editor = el<HTMLFormElement>("editor");
const nameInput = el<HTMLInputElement>("route-name");
const modeSelect = el<HTMLSelectElement>("route-mode");
const curvySelect = el<HTMLSelectElement>("route-curvy");
const stopList = el<HTMLOListElement>("stops");
const summary = el<HTMLDivElement>("summary");
const routingStatus = el<HTMLDivElement>("routing-status");
const conflict = el<HTMLDivElement>("conflict");
const conflictText = el<HTMLSpanElement>("conflict-text");
const conflictAction = el<HTMLButtonElement>("conflict-action");
const saveButton = el<HTMLButtonElement>("save");
const deleteButton = el<HTMLButtonElement>("delete");
const closeButton = el<HTMLButtonElement>("close");
const profileBox = el<HTMLElement>("profile");
const profileArea = document.getElementById("profile-area");
const profileLine = document.getElementById("profile-line");
const profileText = el<HTMLElement>("profile-text");
const routeMap = createMap(el<HTMLDivElement>("map"));

/** The avoid checkboxes, with the encoded value each needs on the graph (null: works on any). */
const avoidBoxes: { key: keyof RoutePreferences; box: HTMLInputElement; needs: string | null }[] = [
  { key: "avoidHighways", box: el("avoid-highways"), needs: null },
  { key: "avoidSmallRoads", box: el("avoid-small-roads"), needs: null },
  { key: "avoidTolls", box: el("avoid-tolls"), needs: EncodedValue.TOLL },
  { key: "avoidFerries", box: el("avoid-ferries"), needs: EncodedValue.ROAD_ENVIRONMENT },
  { key: "avoidUnpaved", box: el("avoid-unpaved"), needs: EncodedValue.SURFACE },
];

const storage = window.sessionStorage;

interface Session {
  manager: UserManager;
  user: User;
  routes: SavedRoute[];
  /** What the routing server's graph has (`/info`), null when it can't tell. */
  graph: Set<string> | null;
  /** Whether `/info` reports heights, so routes are fetched with `elevation=true`. */
  elevation: boolean;
  /** The open draft's elevation profile; null hides it (no elevation, or nothing routed yet). */
  profile: ElevationProfile | null;
  draft: Draft | null;
  saving: boolean;
}

let session: Session | null = null;

/** What a throttled request brings back: a new line for the draft (null for a profile-only
 *  fetch, which leaves the saved line alone), and the profile to draw. */
interface Routed {
  path: RoutedPath | null;
  profile: ElevationProfile | null;
}

// One throttle for both routing and profile fetches, so together they stay inside nginx's
// rate limit, and an edit replaces a profile fetch still waiting (its answer carries one).
const throttle = new RouteThrottle<Routed>(
  ({ path, profile }) => {
    if (session?.draft == null) return;
    session.profile = profile;
    if (path !== null) {
      setDraft({ ...session.draft, ...path }, false);
      sayRouting("");
    } else {
      // A profile-only fetch leaves the draft alone, so setDraft's renderEditor() never ran:
      // without this, Save stays disabled on throttle.pending until the next edit.
      renderEditor();
    }
    renderProfile();
  },
  (error) => {
    if (session !== null) session.profile = null;
    renderProfile();
    if (error instanceof SlowDown || error instanceof NoRoute) sayRouting(error.message);
    else sayRouting(`Could not route: ${error instanceof Error ? error.message : String(error)}`);
    renderEditor();
  },
);

function say(text: string): void {
  status.textContent = text;
}

function sayRouting(text: string): void {
  routingStatus.textContent = text;
}

function showSignIn(manager: UserManager, again: boolean): void {
  list.replaceChildren();
  listPanel.hidden = true;
  editor.hidden = true;
  routeMap.setEditing(null);
  routeMap.show(null, false);
  profileBox.hidden = true;
  who.textContent = "";
  signOut.hidden = true;
  signIn.textContent = again ? "Sign in again" : "Sign in";
  signIn.hidden = false;
  const kept = again && storage.getItem("detour.draft") !== null;
  say(
    again
      ? `Your session ended.${kept ? " Your unsaved edit is kept and comes back after you sign in." : ""}`
      : "Sign in to see your saved routes.",
  );
  signIn.onclick = () => {
    signIn.disabled = true;
    // Rejects before leaving the page when the realm is unreachable, or on plain http to a
    // host other than localhost, where the browser withholds the crypto PKCE needs.
    manager.signinRedirect().catch((e: unknown) => {
      signIn.disabled = false;
      say(`Could not start sign-in: ${e instanceof Error ? e.message : String(e)}`);
    });
  };
}

/** The token is gone (expired, or a save met a 401): the draft is already in sessionStorage,
 *  so sign in again and it comes back. */
async function signInAgain(s: Session): Promise<void> {
  if (s.draft?.dirty === true) saveDraft(storage, s.draft);
  throttle.cancel();
  session = null;
  await s.manager.removeUser();
  showSignIn(s.manager, true);
}

function renderRoutes(s: Session): void {
  const routes = s.routes;
  if (s.draft === null) {
    say(
      routes.length === 0
        ? "No saved routes yet. Start a new one, or save one on the phone and sync it."
        : `${routes.length} saved route${routes.length === 1 ? "" : "s"}`,
    );
  }
  const items = routes.map((route) => {
    const li = document.createElement("li");
    if (s.draft?.id === route.id) li.classList.add("selected");
    const button = document.createElement("button");
    button.type = "button";
    const title = document.createElement("span");
    title.className = "name";
    title.textContent = route.name || "Unnamed route";
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = [
      route.mode === "MOTO" ? "Moto" : "Car",
      `${route.stops.length} stops`,
      formatDistance(route.distanceMeters),
      formatDuration(route.timeMs),
    ]
      .filter((part) => part !== "")
      .join(" · ");
    button.append(title, meta);
    button.onclick = () => {
      if (!leaveDraft(s)) return;
      openDraft(s, draftFromRoute(s.user.profile.sub, route), true);
    };
    li.append(button);
    return li;
  });
  list.replaceChildren(...items);
}

/** Whether the open draft may be dropped: nothing unsaved, or the rider says so. */
function leaveDraft(s: Session): boolean {
  if (s.draft?.dirty !== true) return true;
  return window.confirm("Discard your unsaved changes to this route?");
}

function openDraft(s: Session, draft: Draft, fit: boolean): void {
  throttle.cancel();
  sayRouting("");
  conflict.hidden = true;
  s.draft = draft;
  s.profile = null;
  renderProfile();
  if (draft.dirty) saveDraft(storage, draft);
  else clearDraft(storage);
  editor.hidden = false;
  routeMap.setEditing(editHandlers);
  routeMap.show(draft, fit);
  say(draft.createdMs === null ? "New route" : `Editing ${draft.name || "Unnamed route"}`);
  renderRoutes(s);
  renderEditor();
  // A route opened without geometry, or restored mid-edit, gets its line now; one with a line
  // gets only its heights, when the server has them.
  if (draft.polyline.length < 2) route(draft);
  else fetchProfile(draft);
}

function closeDraft(s: Session): void {
  throttle.cancel();
  s.draft = null;
  s.profile = null;
  renderProfile();
  clearDraft(storage);
  editor.hidden = true;
  routeMap.setEditing(null);
  routeMap.show(null, false);
  renderRoutes(s);
}

/** Replaces the draft after an edit; [reroute] when the stops, mode or options changed. */
function setDraft(draft: Draft, reroute: boolean): void {
  if (session === null) return;
  session.draft = draft;
  if (draft.dirty) saveDraft(storage, draft);
  conflict.hidden = true;
  routeMap.show(draft, false);
  if (reroute) route(draft);
  renderEditor();
}

/** The draft's `/route` request, asking for heights when the server has them. */
function routeRequest(s: Session, draft: Draft): RouteRequest {
  const request = routeViaRequest("", draft.stops, ghProfile(draft.mode), supportedBy(draft.preferences, s.graph));
  return s.elevation ? withElevation(request) : request;
}

function route(draft: Draft): void {
  const s = session;
  if (draft.stops.length < 2) {
    throttle.cancel();
    sayRouting("");
    if (s !== null) s.profile = null;
    renderProfile();
    return;
  }
  if (s === null) return;
  const request = routeRequest(s, draft);
  sayRouting("Routing…");
  throttle.request(() => fetchRoute(request));
}

/** Heights for a route opened with its saved line: one routing request through its stops, of
 *  which only the profile is used. The saved line stays as it was (#916: polylines stay 2-D).
 *  A failure just means no profile, so it is not shown as a routing error. */
function fetchProfile(draft: Draft): void {
  const s = session;
  if (s === null || !s.elevation || draft.stops.length < 2) return;
  const request = routeRequest(s, draft);
  throttle.request(() =>
    fetchRoute(request).then(
      ({ profile }) => ({ path: null, profile }),
      () => ({ path: null, profile: null }),
    ),
  );
}

const PROFILE_WIDTH = 1000;
const PROFILE_HEIGHT = 100;

/** The elevation profile under the map, or nothing at all when there is none to show. */
function renderProfile(): void {
  const profile = session?.profile ?? null;
  profileBox.hidden = profile === null;
  if (profile === null) return;
  const { line, area } = profilePaths(profile, PROFILE_WIDTH, PROFILE_HEIGHT);
  profileLine?.setAttribute("d", line);
  profileArea?.setAttribute("d", area);
  profileText.textContent = `${profileSummary(profile)} · ${formatDistance(profile.distance)}`;
}

const editHandlers: MapEditHandlers = {
  addStop(at) {
    if (session?.draft) setDraft(addStop(session.draft, at), true);
  },
  moveStop(index, at) {
    if (session?.draft) setDraft(moveStop(session.draft, index, at), true);
  },
  insertStop(at) {
    const draft = session?.draft;
    if (draft) setDraft(insertStop(draft, insertIndexFor(draft.stops, at), at), true);
  },
  removeStop(index) {
    if (session?.draft) setDraft(removeStop(session.draft, index), true);
  },
};

function renderEditor(): void {
  const s = session;
  const draft = s?.draft;
  if (!s || !draft) return;
  if (document.activeElement !== nameInput) nameInput.value = draft.name;
  modeSelect.value = draft.mode;

  const curvature = supports(s.graph, EncodedValue.CURVATURE);
  curvySelect.value = draft.preferences.curvy;
  curvySelect.disabled = draft.mode !== "MOTO" || !curvature;
  curvySelect.title = !curvature
    ? "This server's map has no curvature data"
    : draft.mode !== "MOTO"
      ? "Curvy routing shapes moto routes only"
      : "";
  for (const { key, box, needs } of avoidBoxes) {
    const ok = needs === null || supports(s.graph, needs);
    box.checked = draft.preferences[key] === true && ok;
    box.disabled = !ok;
    const label = box.parentElement;
    label?.classList.toggle("unsupported", !ok);
    if (label) label.title = ok ? "" : "This server's map can't tell these roads apart";
  }

  stopList.replaceChildren(
    ...draft.stops.map((stop, i) => {
      const li = document.createElement("li");
      const text = document.createElement("span");
      text.textContent = stop.name || `${stop.lat.toFixed(5)}, ${stop.lon.toFixed(5)}`;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.textContent = "×";
      remove.title = "Remove this stop";
      remove.onclick = () => editHandlers.removeStop(i);
      li.append(text, remove);
      return li;
    }),
  );
  summary.textContent =
    draft.stops.length < 2
      ? "Saving needs at least two stops."
      : [formatDistance(draft.distanceMeters), formatDuration(draft.timeMs)]
          .filter((part) => part !== "")
          .join(" · ");

  saveButton.disabled = s.saving || !canSave(draft) || !draft.dirty || throttle.pending;
  deleteButton.hidden = draft.createdMs === null;
  deleteButton.disabled = s.saving;
}

nameInput.oninput = () => {
  const draft = session?.draft;
  if (draft) setDraft({ ...draft, name: nameInput.value, dirty: true }, false);
};

modeSelect.onchange = () => {
  const draft = session?.draft;
  if (draft) setDraft(withMode(draft, modeSelect.value as TravelMode), true);
};

curvySelect.onchange = () => {
  const draft = session?.draft;
  if (!draft) return;
  setDraft(withPreferences(draft, { ...draft.preferences, curvy: curvySelect.value as CurvyPreset }), true);
};

for (const { key, box } of avoidBoxes) {
  box.onchange = () => {
    const draft = session?.draft;
    if (draft) setDraft(withPreferences(draft, { ...draft.preferences, [key]: box.checked }), true);
  };
}

newRoute.onclick = () => {
  const s = session;
  if (!s || !leaveDraft(s)) return;
  openDraft(s, newDraft(s.user.profile.sub, Date.now(), s.draft?.mode ?? "MOTO"), false);
};

closeButton.onclick = () => {
  const s = session;
  if (s && leaveDraft(s)) closeDraft(s);
};

function showConflict(text: string, action: string, onAction: () => void): void {
  conflictText.textContent = text;
  conflictAction.textContent = action;
  conflictAction.onclick = onAction;
  conflict.hidden = false;
}

editor.onsubmit = (e) => {
  e.preventDefault();
  void save();
};

async function save(): Promise<void> {
  const s = session;
  const draft = s?.draft;
  if (!s || !draft || !canSave(draft) || throttle.pending || s.saving) return;
  if (s.user.expired) {
    await signInAgain(s);
    return;
  }
  s.saving = true;
  renderEditor();
  try {
    const stored = await saveRoute(s.user.access_token, routeDocument(draft, Date.now()));
    clearDraft(storage);
    s.routes = await fetchRoutes(s.user.access_token);
    const saved = stored ?? s.routes.find((r) => r.id === draft.id);
    s.saving = false;
    if (saved) openDraft(s, draftFromRoute(s.user.profile.sub, saved), false);
    else closeDraft(s);
    say("Saved. It is in the phone's Routes list after its next sync.");
  } catch (e) {
    s.saving = false;
    if (e instanceof SignInRequired) {
      await signInAgain(s);
      return;
    }
    if (e instanceof NewerCopy) {
      const stored = e.stored;
      showConflict(e.message, stored ? "Load the newer copy" : "Close", () => {
        if (stored) openDraft(s, draftFromRoute(s.user.profile.sub, stored), true);
        else closeDraft(s);
      });
    } else if (e instanceof RouteDeleted) {
      showConflict(e.message, "Save it as a new route", () => {
        const current = s.draft;
        if (!current) return;
        setDraft({ ...current, id: Date.now(), createdMs: null, dirty: true }, false);
        void save();
      });
    } else {
      sayRouting(`Could not save: ${e instanceof Error ? e.message : String(e)}`);
    }
    renderEditor();
  }
}

deleteButton.onclick = () => {
  void remove();
};

async function remove(): Promise<void> {
  const s = session;
  const draft = s?.draft;
  if (!s || !draft || draft.createdMs === null || s.saving) return;
  if (!window.confirm(`Delete ${draft.name || "this route"}? It is deleted on your phone too.`)) return;
  if (s.user.expired) {
    await signInAgain(s);
    return;
  }
  try {
    await deleteRoute(s.user.access_token, draft.id);
    s.routes = s.routes.filter((r) => r.id !== draft.id);
    closeDraft(s);
    say("Deleted.");
  } catch (e) {
    if (e instanceof SignInRequired) {
      await signInAgain(s);
      return;
    }
    sayRouting(`Could not delete: ${e instanceof Error ? e.message : String(e)}`);
  }
}

async function start(): Promise<void> {
  say("Loading…");
  const { issuer } = await fetchCapabilities();
  const manager = createUserManager(issuer);
  const user = await currentUser(manager);
  // A token in sessionStorage outlived by its expiry, or none: the same screen, worded for
  // whether the rider had been signed in.
  if (user === null) {
    showSignIn(manager, window.sessionStorage.getItem("detour.signedIn") === "1");
    return;
  }
  window.sessionStorage.setItem("detour.signedIn", "1");

  who.textContent = displayName(user);
  signOut.hidden = false;
  signOut.onclick = () => {
    if (session && !leaveDraft(session)) return;
    window.sessionStorage.removeItem("detour.signedIn");
    clearDraft(storage);
    // Ends the Keycloak session too, so the next "Sign in" asks for the password.
    void manager.signoutRedirect({ id_token_hint: user.id_token });
  };

  let routes: SavedRoute[];
  try {
    routes = await fetchRoutes(user.access_token);
  } catch (e) {
    if (e instanceof SignInRequired) {
      await manager.removeUser();
      showSignIn(manager, true);
      return;
    }
    throw e;
  }
  const info = await fetchGraphInfo();
  const s: Session = {
    manager,
    user,
    routes,
    graph: info.values,
    elevation: info.elevation,
    profile: null,
    draft: null,
    saving: false,
  };
  session = s;
  listPanel.hidden = false;
  renderRoutes(s);

  const kept = loadDraft(storage, user.profile.sub);
  if (kept !== null) {
    openDraft(s, kept, true);
    say("Your unsaved edit is back. Save it to keep it.");
  }
}

start().catch((e: unknown) => {
  say(e instanceof Error ? e.message : String(e));
});
