#!/bin/sh
# POSIX-sh check for the invite-only group realm (#856). Needs jq.
#
# The realm must ship no users and no self-registration, and the group overlay
# must mount it as <realm>-realm.json: Keycloak 26.7 refuses to start ("File
# name / realm name mismatch") when the import file is named anything else.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
REALM_FILE="$HERE/../config/keycloak/group-realm.json"
OVERLAY="$HERE/../docker-compose.group.yml"

fail() { echo "FAIL: $*" >&2; exit 1; }

jq -e 'has("users") | not' "$REALM_FILE" >/dev/null || fail "realm file ships users"
jq -e '.registrationAllowed == false' "$REALM_FILE" >/dev/null || fail "self-registration is on"
jq -e '.editUsernameAllowed == true' "$REALM_FILE" >/dev/null || fail "riders cannot pick their username"

REALM="$(jq -r '.realm' "$REALM_FILE")"
grep -q "group-realm.json:/opt/keycloak/data/import/$REALM-realm.json:ro" "$OVERLAY" \
    || fail "overlay does not mount the realm as /opt/keycloak/data/import/$REALM-realm.json"
grep -q 'command: start --import-realm' "$OVERLAY" || fail "overlay does not import the realm"

echo "OK   group realm"
