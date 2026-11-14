# shellcheck shell=sh
# Sourced by prod-realm.test.sh and group-realm.test.sh: the web planner's client
# (#636) as both realm files must ship it. Needs jq and a fail() from the caller.

# $1: realm file. The redirect, origin and post-logout URI come from Keycloak's
# ${DETOUR_HOSTNAME} import placeholder, which the idp overlay fills.
check_web_client() {
    jq -e '[.clients[] | select(.clientId == "detour-web")] | length == 1' "$1" >/dev/null \
        || fail "$1: no single detour-web client"
    jq -e '.clients[] | select(.clientId == "detour-web")
        | .publicClient == true and .standardFlowEnabled == true
          and .implicitFlowEnabled == false and .directAccessGrantsEnabled == false
          and .serviceAccountsEnabled == false
          and .attributes["pkce.code.challenge.method"] == "S256"
          and .attributes["use.refresh.tokens"] == "false"' "$1" >/dev/null \
        || fail "$1: detour-web is not public PKCE S256, standard flow only, refresh tokens off"
    # shellcheck disable=SC2016
    jq -e '.clients[] | select(.clientId == "detour-web")
        | .redirectUris == ["https://${DETOUR_HOSTNAME}/planner/*"]
          and .webOrigins == ["https://${DETOUR_HOSTNAME}"]
          and .attributes["post.logout.redirect.uris"] == "https://${DETOUR_HOSTNAME}/planner/"' "$1" >/dev/null \
        || fail "$1: detour-web's URIs are not https://\${DETOUR_HOSTNAME}/planner"
    jq -e '(.clients[] | select(.clientId == "detour-web") | .protocolMappers)
        == (.clients[] | select(.clientId == "detour-app") | .protocolMappers)' "$1" >/dev/null \
        || fail "$1: detour-web does not carry detour-app's detour-api audience mapper"
}
