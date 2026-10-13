#!/usr/bin/env bash
# Add Keycloak to a running local rig (#612) so signed-in features work:
# convoys, the live relay, friends. Needs `tools/dev-rig/up.sh` to have run.
#
#   tools/dev-rig/auth-up.sh [adb-serial]   (or ANDROID_SERIAL; none = no device touched)
#
# Idempotent. Creates the dev accounts `rider` (already in the realm) and
# `rider2`, both with password `detour-dev`, and turns on the password grant
# for `detour-app` so scripts can get tokens. Local rig only: the realm lives
# in this project's own volume.
#
# With a serial it also runs `adb reverse` for 7500, 7510 and 7580 and points
# the debug build at http://localhost:7500 / :7510. The app signs in through
# Chrome on the emulator, which has to reach Keycloak as `localhost:7580`
# because that is the issuer the API requires (see compose.auth.yml); 10.0.2.2
# would not match it.
#
# Env: KC_ADMIN_USERNAME / KC_ADMIN_PASSWORD (default admin/admin).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
DATA="$REPO/docker/dev/data/rig"
SERIAL="${1:-${ANDROID_SERIAL:-}}"
export RIG_REPO="$REPO" RIG_DATA="$DATA"
PROJECT=detour-rig
KC=http://localhost:7580
ADMIN_USER="${KC_ADMIN_USERNAME:-admin}"
ADMIN_PASS="${KC_ADMIN_PASSWORD:-admin}"

log() { printf '\n== %s\n' "$*"; }
die() { printf 'auth-up.sh: %s\n' "$*" >&2; exit 1; }

compose() {
    docker compose -p "$PROJECT" \
        -f "$REPO/docker/dev/docker-compose.yml" \
        -f "$HERE/compose.rig.yml" \
        -f "$HERE/compose.auth.yml" "$@"
}

for tool in docker curl python3; do
    command -v "$tool" >/dev/null || die "needs $tool on PATH"
done
[ -n "$(docker ps -q --filter "name=${PROJECT}-api-1" --filter status=running)" ] \
    || die "the rig's API is not running; run tools/dev-rig/up.sh first"

log "1. Keycloak"
compose up -d --wait --wait-timeout 300 keycloak-db keycloak
compose up -d idp-proxy
docker exec "${PROJECT}-api-1" bash -c \
    'exec 3<>/dev/tcp/localhost/7580 && printf "GET /realms/detour/.well-known/openid-configuration HTTP/1.0\r\n\r\n" >&3 && grep -q "200 OK" <&3' \
    || die "the API container cannot reach Keycloak as localhost:7580"
echo "API container reaches the issuer"

log "2. Accounts"
token="$(curl -fsS -X POST "$KC/realms/master/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=admin-cli \
    -d "username=$ADMIN_USER" -d "password=$ADMIN_PASS" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')"
admin() { curl -fsS -H "Authorization: Bearer $token" "$@"; }
cid="$(admin "$KC/admin/realms/detour/clients?clientId=detour-app" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')"
admin "$KC/admin/realms/detour/clients/$cid" \
    | python3 -c 'import sys,json;c=json.load(sys.stdin);c["directAccessGrantsEnabled"]=True;print(json.dumps(c))' \
    | admin -X PUT -H 'Content-Type: application/json' -d @- "$KC/admin/realms/detour/clients/$cid"
echo "password grant on for detour-app"
if [ "$(admin "$KC/admin/realms/detour/users?username=rider2&exact=true" | python3 -c 'import sys,json;print(len(json.load(sys.stdin)))')" = 0 ]; then
    admin -X POST -H 'Content-Type: application/json' "$KC/admin/realms/detour/users" -d '{
        "username":"rider2","enabled":true,"emailVerified":true,"email":"rider2@detour.local",
        "firstName":"Sam","lastName":"Two",
        "credentials":[{"type":"password","value":"detour-dev","temporary":false}]}'
    echo "created rider2"
else
    echo "rider2 exists"
fi

if [ -n "$SERIAL" ]; then
    log "3. Device $SERIAL"
    command -v adb >/dev/null || die "needs adb on PATH"
    for port in 7500 7510 7580; do adb -s "$SERIAL" reverse "tcp:$port" "tcp:$port" >/dev/null; done
    RIG_API_URL=http://localhost:7500 RIG_ROUTING_URL=http://localhost:7510 "$HERE/point-emulator.sh" "$SERIAL"
fi

cat <<MSG

Keycloak:  $KC   (admin: $ADMIN_USER / $ADMIN_PASS)
Riders:    rider, rider2   password detour-dev
Next:      sign in as rider in the app, then tools/dev-rig/convoy-rider.py (see the README)
MSG
