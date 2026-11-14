import "./style.css";
import type { UserManager } from "oidc-client-ts";
import { fetchCapabilities, fetchRoutes, SignInRequired } from "./api";
import { createUserManager, currentUser, displayName } from "./auth";
import { createMap } from "./map";
import { formatDistance, formatDuration, type SavedRoute } from "./routes";

// Read-only first slice (#913): sign in, list the saved routes, draw the selected one.

const el = <T extends HTMLElement>(id: string): T => {
  const found = document.getElementById(id);
  if (!found) throw new Error(`#${id} missing from index.html`);
  return found as T;
};

const status = el<HTMLDivElement>("status");
const signIn = el<HTMLButtonElement>("sign-in");
const signOut = el<HTMLButtonElement>("sign-out");
const who = el<HTMLSpanElement>("who");
const list = el<HTMLUListElement>("routes");
const routeMap = createMap(el<HTMLDivElement>("map"));

function say(text: string): void {
  status.textContent = text;
}

function showSignIn(manager: UserManager, again: boolean): void {
  list.replaceChildren();
  routeMap.show(null);
  who.textContent = "";
  signOut.hidden = true;
  signIn.textContent = again ? "Sign in again" : "Sign in";
  signIn.hidden = false;
  say(again ? "Your session ended." : "Sign in to see your saved routes.");
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

function renderRoutes(routes: SavedRoute[]): void {
  if (routes.length === 0) {
    say("No saved routes yet. Routes you save on the phone show up here after it syncs.");
    list.replaceChildren();
    return;
  }
  say(`${routes.length} saved route${routes.length === 1 ? "" : "s"}`);
  let selected: HTMLLIElement | null = null;
  const items = routes.map((route) => {
    const li = document.createElement("li");
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
      selected?.classList.remove("selected");
      li.classList.add("selected");
      selected = li;
      routeMap.show(route);
    };
    li.append(button);
    return li;
  });
  list.replaceChildren(...items);
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
    window.sessionStorage.removeItem("detour.signedIn");
    // Ends the Keycloak session too, so the next "Sign in" asks for the password.
    void manager.signoutRedirect({ id_token_hint: user.id_token });
  };

  try {
    renderRoutes(await fetchRoutes(user.access_token));
  } catch (e) {
    if (e instanceof SignInRequired) {
      await manager.removeUser();
      showSignIn(manager, true);
      return;
    }
    throw e;
  }
}

start().catch((e: unknown) => {
  say(e instanceof Error ? e.message : String(e));
});
