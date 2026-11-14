#!/bin/sh
# POSIX-sh check for the prod realm the idp overlay imports when it is missing
# (#911). Needs jq.
#
# Security settings must match the group realm's, not Keycloak's defaults (under
# which an app rider is signed out after 30 min idle); it ships no users and no
# self-registration; and the overlay must mount it as <realm>-realm.json and fill
# its ${DETOUR_HOSTNAME} placeholders.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
REALM_FILE="$HERE/../config/keycloak/prod-realm.json"
GROUP_FILE="$HERE/../config/keycloak/group-realm.json"
OVERLAY="$HERE/../docker-compose.idp.yml"

fail() { echo "FAIL: $*" >&2; exit 1; }
# shellcheck source=docker/prod/scripts/realm-web-client.sh
. "$HERE/realm-web-client.sh"

jq -e '.realm == "detour" and .enabled == true' "$REALM_FILE" >/dev/null || fail "realm is not an enabled 'detour'"
jq -e 'has("users") | not' "$REALM_FILE" >/dev/null || fail "realm file ships users"
jq -e '.registrationAllowed == false' "$REALM_FILE" >/dev/null || fail "self-registration is on"
jq -e '.editUsernameAllowed == true' "$REALM_FILE" >/dev/null || fail "riders cannot pick their username"
jq -e '.bruteForceProtected == true' "$REALM_FILE" >/dev/null || fail "brute-force protection is off"

# Lifetimes, lockout and password policy: the group realm's, key by key
# (docker/dev/config/keycloak/REALM.md has the reasons).
for k in accessTokenLifespan ssoSessionIdleTimeout ssoSessionMaxLifespan offlineSessionIdleTimeout \
    refreshTokenMaxReuse revokeRefreshToken actionTokenGeneratedByUserLifespan passwordPolicy \
    permanentLockout failureFactor maxFailureWaitSeconds minimumQuickLoginWaitSeconds \
    waitIncrementSeconds quickLoginCheckMilliSeconds maxDeltaTimeSeconds; do
    [ "$(jq -c ".$k" "$REALM_FILE")" = "$(jq -c ".$k" "$GROUP_FILE")" ] \
        || fail "$k is $(jq -c ".$k" "$REALM_FILE"), the group realm's is $(jq -c ".$k" "$GROUP_FILE")"
    [ "$(jq -c ".$k" "$REALM_FILE")" != null ] || fail "$k is unset, so Keycloak's default applies"
done

jq -e '[.roles.realm[].name] | index("detour-user") and index("detour-admin")' "$REALM_FILE" >/dev/null \
    || fail "realm roles detour-user / detour-admin missing"
jq -e '.defaultRole.composites.realm | index("detour-user")' "$REALM_FILE" >/dev/null \
    || fail "new accounts do not get detour-user"

# The username rule the backend enforces, as the group realm states it.
profile() {
    jq -c '.components["org.keycloak.userprofile.UserProfileProvider"][0].config["kc.user.profile.config"][0]
        | fromjson | .attributes[] | select(.name == "username")' "$1"
}
[ -n "$(profile "$REALM_FILE")" ] || fail "no username rule in the user profile"
[ "$(profile "$REALM_FILE")" = "$(profile "$GROUP_FILE")" ] || fail "username rule differs from the group realm's"

jq -e '[.clients[].clientId] | sort == ["detour-api", "detour-app", "detour-web"]' "$REALM_FILE" >/dev/null \
    || fail "clients are not exactly detour-api, detour-app, detour-web"
jq -e '.clients[] | select(.clientId == "detour-api")
    | .bearerOnly == true and .publicClient == false and .standardFlowEnabled == false
      and .implicitFlowEnabled == false and .directAccessGrantsEnabled == false
      and .serviceAccountsEnabled == false' "$REALM_FILE" >/dev/null \
    || fail "detour-api is not a bearer-only audience with no flows"
jq -e '.clients[] | select(.clientId == "detour-app")
    | .publicClient == true and .standardFlowEnabled == true and .directAccessGrantsEnabled == false
      and .attributes["pkce.code.challenge.method"] == "S256"
      and .redirectUris == ["detour://auth/callback"]
      and (.protocolMappers[] | select(.protocolMapper == "oidc-audience-mapper")
           | .config["included.client.audience"] == "detour-api")' "$REALM_FILE" >/dev/null \
    || fail "detour-app is not public PKCE S256 to detour://auth/callback with the detour-api audience"
check_web_client "$REALM_FILE"

REALM="$(jq -r '.realm' "$REALM_FILE")"
grep -q "prod-realm.json:/opt/keycloak/data/import/$REALM-realm.json:ro" "$OVERLAY" \
    || fail "idp overlay does not mount the realm as /opt/keycloak/data/import/$REALM-realm.json"
grep -q 'command: start --import-realm' "$OVERLAY" || fail "idp overlay does not import the realm"
# shellcheck disable=SC2016  # a literal ${DETOUR_HOSTNAME} in the overlay
grep -q 'DETOUR_HOSTNAME: \${DETOUR_HOSTNAME' "$OVERLAY" \
    || fail "idp overlay does not pass DETOUR_HOSTNAME to Keycloak for the placeholders"

echo "OK   prod realm"
