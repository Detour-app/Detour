import { type User, UserManager, WebStorageStateStore } from "oidc-client-ts";

// Sign-in as the realm's `detour-web` client (#636 "Sign-in", answer 2A): authorization code
// with PKCE S256, tokens in sessionStorage, no refresh token and no silent renewal. A new tab,
// or a token past its expiry, shows "Sign in again"; while the Keycloak session lives that is
// one click without a password. Nothing long-lived is kept in the browser.

export const CLIENT_ID = "detour-web";

/** Where Keycloak sends the browser back: the planner's own root, which the realm's
 *  `https://<host>/planner/*` redirect pattern covers. */
export function plannerRoot(): string {
  return new URL(import.meta.env.BASE_URL, window.location.origin).toString();
}

export function createUserManager(issuer: string): UserManager {
  return new UserManager({
    authority: issuer,
    client_id: CLIENT_ID,
    redirect_uri: plannerRoot(),
    post_logout_redirect_uri: plannerRoot(),
    response_type: "code",
    scope: "openid",
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: false,
    monitorSession: false,
    loadUserInfo: false,
  });
}

/** True when this page load is Keycloak's redirect back with a code (or an error). */
export function isSignInCallback(location: Location): boolean {
  const params = new URLSearchParams(location.search);
  return params.has("state") && (params.has("code") || params.has("error"));
}

/**
 * Finishes a redirect back from Keycloak if this is one, then returns the signed-in user, or
 * null when there is none or the token has expired. The callback's query string is removed
 * from the address bar either way, so a reload does not replay a used code.
 */
export async function currentUser(manager: UserManager): Promise<User | null> {
  if (isSignInCallback(window.location)) {
    try {
      await manager.signinRedirectCallback();
    } finally {
      window.history.replaceState(null, "", plannerRoot());
    }
  }
  const user = await manager.getUser();
  if (user === null || user.expired) {
    if (user !== null) await manager.removeUser();
    return null;
  }
  return user;
}

/** The handle shown in the header: the username the rider signs in with. */
export function displayName(user: User): string {
  return user.profile.preferred_username ?? user.profile.name ?? "";
}
