#!/bin/sh
# POSIX-sh test for the kit CLI (docker/prod/detour, #857): what setup writes, and
# the argument parsing and kcadm calls of every subcommand, with docker stubbed on
# PATH. Runs on a copy, so a real .env beside the script is never touched.
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

KIT="$WORK/kit"
mkdir -p "$KIT/config" "$WORK/bin"
cp "$HERE/../detour" "$HERE/../.env.example" "$KIT/"
cp "$HERE/../config/regions.env" "$KIT/config/"
LOG="$WORK/docker.log"

# Logs each call. `get users` answers from STUB_LOOKUP (one user) or from the files
# STUB_PAGES/<offset> (the listing); anything matching STUB_FAIL exits 1.
cat > "$WORK/bin/docker" <<'STUB'
#!/bin/sh
echo "$*" >> "$STUB_LOG"
case "$*" in
    *"${STUB_FAIL:-no-failure-set}"*) echo "stub: refused" >&2; exit 1 ;;
    *"get users"*"exact=true"*) printf '%s\n' "${STUB_LOOKUP:-}" ;;
    *"get users"*)
        offset="$(printf '%s\n' "$*" | sed -n 's/.* -o \([0-9]*\) .*/\1/p')"
        cat "$STUB_PAGES/$offset" 2>/dev/null || true ;;
    *"create users -r"*) echo new-user-id ;;
esac
STUB
chmod +x "$WORK/bin/docker"
PATH="$WORK/bin:$PATH"
STUB_LOG="$LOG"
STUB_PAGES="$WORK/pages"
mkdir -p "$STUB_PAGES"
export PATH STUB_LOG STUB_PAGES

CLI="$KIT/detour"
ENV="$KIT/.env"

fail() { echo "FAIL: $1"; exit 1; }
key() { sed -n "s/^$1=//p" "$ENV"; }
expect_key() { [ "$(key "$1")" = "$2" ] || fail "$1 is '$(key "$1")', want '$2'"; }
expect_refused() {
    : > "$LOG"
    if sh "$CLI" "$@" >/dev/null 2>&1; then fail "should refuse: $*"; fi
}
expect_no_docker() {
    if [ -s "$LOG" ]; then fail "$1 reached docker: $(cat "$LOG")"; fi
}
expect_call() { grep -q -- "$1" "$LOG" || fail "no call matching '$1' in: $(cat "$LOG")"; }
expect_no_call() { if grep -q -- "$1" "$LOG"; then fail "unexpected '$1' in: $(cat "$LOG")"; fi; }

BASE=docker-compose.yml:docker-compose.idp.yml:docker-compose.group.yml

# --- setup: needs --host, refuses what would break .env, writes nothing then.
expect_refused setup
expect_refused setup --host
expect_refused setup --host https://a.example.com
expect_refused setup --host a.example.com/x
expect_refused setup --host 'a.example.com
EVIL=1'
expect_refused setup --host a.example.com --email 'a b@c.d'
expect_refused setup --host a.example.com --tag 'x y'
expect_refused setup --host a.example.com --routing nowhere
expect_refused setup --host a.example.com --routing ben
expect_refused setup --host a.example.com --bogus
# The next option is not a value: this used to write ACME_EMAIL=--force.
for o in --email --routing --tag; do
    expect_refused setup --host a.example.com "$o" --force
done
expect_refused setup --host --force
[ ! -e "$ENV" ] || fail "a refused setup wrote .env"

# --- setup: the keys.
OUT="$(sh "$CLI" setup --host a.example.com)"
expect_key COMPOSE_FILE "$BASE:docker-compose.proxy.yml:docker-compose.caddy.yml"
expect_key DETOUR_HOSTNAME a.example.com
expect_key KC_HTTP_RELATIVE_PATH /auth
expect_key KC_HOSTNAME https://a.example.com/auth
expect_key KC_HOSTNAME_ADMIN http://127.0.0.1:7580/auth
expect_key IDP_ISSUER https://a.example.com/auth/realms/detour
expect_key DETOUR_IMAGE_TAG latest
expect_key CAMERA_BASE_URL ''
expect_key ROUTING_BASE_URL ''
expect_key OSM_REGION benelux   # .env.example's, unused without the routing layer
[ -z "$(key ACME_EMAIL)" ] || fail "ACME_EMAIL set without --email"

for k in POSTGRES_PASSWORD KC_DB_PASSWORD KC_ADMIN_PASSWORD; do
    key "$k" | grep -Eq '^[0-9a-f]{48}$' || fail "$k is not 24 random bytes as hex"
done
[ "$(key POSTGRES_PASSWORD)" != "$(key KC_DB_PASSWORD)" ] || fail "database passwords match"
[ "$(key KC_DB_PASSWORD)" != "$(key KC_ADMIN_PASSWORD)" ] || fail "admin password matches the database's"

# One active line per key, or which value wins depends on the reader.
DUPES="$(grep -E '^[A-Z_]+=' "$ENV" | cut -d= -f1 | sort | uniq -d)"
[ -z "$DUPES" ] || fail "keys set twice: $DUPES"

[ -n "$(find "$ENV" -perm 600)" ] || fail ".env is not 600"
[ -z "$(find "$KIT" -maxdepth 1 -name '.env.*' ! -name .env.example)" ] || fail "setup left a temp file"

echo "$OUT" | grep -q "$(key KC_ADMIN_PASSWORD)" || fail "admin password not printed"
echo "$OUT" | grep -q 'docker compose up -d' || fail "next step not printed"
if echo "$OUT" | grep -q "$(key POSTGRES_PASSWORD)"; then fail "database password printed"; fi

# --- setup: refuses to overwrite, --force replaces.
BEFORE="$(key KC_ADMIN_PASSWORD)"
expect_refused setup --host b.example.com
expect_key DETOUR_HOSTNAME a.example.com
[ "$(key KC_ADMIN_PASSWORD)" = "$BEFORE" ] || fail "refused setup changed .env"
sh "$CLI" setup --host b.example.com --force >/dev/null
expect_key DETOUR_HOSTNAME b.example.com
[ "$(key KC_ADMIN_PASSWORD)" != "$BEFORE" ] || fail "--force kept the old password"

# --- setup --force: openssl failing on any of its three calls leaves .env alone.
# Without this, a failure inside a set_key argument wrote blank database passwords
# over a good .env, and exited 0.
OPENSSL_BIN="$WORK/openssl-bin"
mkdir -p "$OPENSSL_BIN"
cat > "$OPENSSL_BIN/openssl" <<'STUB'
#!/bin/sh
n=$(($(cat "$OPENSSL_CALLS") + 1))
echo "$n" > "$OPENSSL_CALLS"
[ "$n" -ne "$OPENSSL_FAIL_AT" ] || exit 1
echo 00112233445566778899aabbccddeeff0011223344556677
STUB
chmod +x "$OPENSSL_BIN/openssl"
OPENSSL_CALLS="$WORK/openssl-calls"
export OPENSSL_CALLS
GOOD_ENV="$(cat "$ENV")"
for n in 1 2 3; do
    echo 0 > "$OPENSSL_CALLS"
    if PATH="$OPENSSL_BIN:$PATH" OPENSSL_FAIL_AT="$n" sh "$CLI" setup --host d.example.com --force >/dev/null 2>&1; then
        fail "setup --force exited 0 with openssl failing on call $n"
    fi
    [ "$(cat "$ENV")" = "$GOOD_ENV" ] || fail "openssl failing on call $n changed .env"
done
[ -z "$(find "$KIT" -maxdepth 1 -name '.env.*' ! -name .env.example)" ] || fail "failed setup left a temp file"

# --- setup --force: Ctrl-C or a kill mid-write leaves .env alone and no temp file.
# The stub chmod signals the CLI (its parent) after the temp file is filled in.
# dash skips an EXIT-only trap on a signal, which left the secrets in .env.XXXXXX.
SIGNAL_BIN="$WORK/signal-bin"
mkdir -p "$SIGNAL_BIN"
cat > "$SIGNAL_BIN/chmod" <<'STUB'
#!/bin/sh
kill -s "$SIGNAL_CLI" "$PPID"
STUB
/bin/chmod +x "$SIGNAL_BIN/chmod"
for sig in INT TERM; do
    if PATH="$SIGNAL_BIN:$PATH" SIGNAL_CLI="$sig" sh "$CLI" setup --host e.example.com --force >/dev/null 2>&1; then
        fail "setup --force exited 0 after SIG$sig"
    fi
    [ "$(cat "$ENV")" = "$GOOD_ENV" ] || fail "SIG$sig during setup changed .env"
    [ -z "$(find "$KIT" -maxdepth 1 -name '.env.*' ! -name .env.example)" ] || fail "SIG$sig during setup left a temp file"
done

# --- setup: --routing, --email, --tag.
sh "$CLI" setup --host c.example.com --routing france --email me@c.example.com --tag abc123 --force >/dev/null
expect_key COMPOSE_FILE "$BASE:docker-compose.routing.yml:docker-compose.proxy.yml:docker-compose.caddy.yml"
expect_key OSM_REGION france
expect_key ROUTING_BASE_URL https://c.example.com
expect_key ACME_EMAIL me@c.example.com
expect_key DETOUR_IMAGE_TAG abc123

# --- Without .env, Keycloak commands stop before docker.
mv "$ENV" "$ENV.moved"
for c in invite invites 'revoke join-ab12' 'disable a.b' 'enable a.b' 'delete a.b' 'reset a.b'; do
    # shellcheck disable=SC2086
    expect_refused $c
    expect_no_docker "$c without .env"
done
mv "$ENV.moved" "$ENV"

# --- Wrong arguments stop before docker.
for c in revoke disable enable delete reset; do
    expect_refused "$c"
    expect_no_docker "$c with no user"
    expect_refused "$c" a.b c.d
    expect_no_docker "$c with two users"
    expect_refused "$c" '--server=http://evil'
    expect_no_docker "$c with an option as user"
    expect_refused "$c" 'a,b'
    expect_no_docker "$c with a comma in the user"
done
expect_refused invite extra
expect_no_docker "invite with an argument"
expect_refused invites extra
expect_no_docker "invites with an argument"
expect_refused
expect_refused nonsense
expect_no_docker "an unknown command"

# --- Login: exec -T, the container's own env, nothing secret on this side.
: > "$LOG"
: > "$STUB_PAGES/0"
sh "$CLI" invites >/dev/null
expect_call '^compose exec -T keycloak sh -c'
# shellcheck disable=SC2016
expect_call 'export KC_CLI_PASSWORD="$KC_BOOTSTRAP_ADMIN_PASSWORD"'
expect_no_call ' --password'
# shellcheck disable=SC2016
expect_call 'http://localhost:8080${KC_HTTP_RELATIVE_PATH%/}'
expect_no_call "$(key KC_ADMIN_PASSWORD)"

# --- invite.
: > "$LOG"
OUT="$(sh "$CLI" invite)"
expect_call 'exec -T keycloak /opt/keycloak/bin/kcadm.sh create users -r detour -s username=join-[0-9a-f]\{4\} -s enabled=true -s requiredActions=\["UPDATE_PASSWORD","UPDATE_PROFILE"\] -i'
expect_call 'set-password -r detour --username join-[0-9a-f]\{4\} --new-password [0-9a-f]\{12\} --temporary'
echo "$OUT" | grep -q 'https://c.example.com' || fail "invite lacks the server address"
echo "$OUT" | grep -Eq 'Username: join-[0-9a-f]{4}$' || fail "invite lacks the name"
echo "$OUT" | grep -Eq 'Code: [0-9a-f]{12}$' || fail "invite lacks the code"

# An invite with no password can't be redeemed: remove it, say so.
: > "$LOG"
if STUB_FAIL=set-password sh "$CLI" invite >/dev/null 2>&1; then fail "invite hid a failed set-password"; fi
expect_call 'delete users/new-user-id -r detour'

# --- invites: required actions, join- names, paging, empty.
cat > "$STUB_PAGES/0" <<'CSV'
id-less.member,true
join-ab12,true,UPDATE_PASSWORD,UPDATE_PROFILE
join-cd34,true
join-ef56,false,UPDATE_PROFILE
mia.k,true,UPDATE_PASSWORD
CSV
OUT="$(sh "$CLI" invites)"
echo "$OUT" | grep -q '^join-ab12 .*unredeemed$' || fail "open invite not listed: $OUT"
echo "$OUT" | grep -q '^mia.k .*waiting for a password$' || fail "half-finished not listed: $OUT"
echo "$OUT" | grep -q '^join-cd34 .*member, still named join-\*$' || fail "join-* member not listed: $OUT"
echo "$OUT" | grep -q '^join-ef56 .*unredeemed (disabled)$' || fail "disabled state not shown: $OUT"
if echo "$OUT" | grep -q 'id-less.member'; then fail "finished member listed: $OUT"; fi

i=0
: > "$STUB_PAGES/0"
while [ "$i" -lt 100 ]; do echo "member$i,true" >> "$STUB_PAGES/0"; i=$((i + 1)); done
echo 'join-late,true,UPDATE_PROFILE' > "$STUB_PAGES/100"
: > "$LOG"
OUT="$(sh "$CLI" invites)"
expect_call 'get users -r detour -o 100 -l 100'
echo "$OUT" | grep -q '^join-late ' || fail "second page ignored: $OUT"
rm -f "$STUB_PAGES/100"

: > "$STUB_PAGES/0"
[ "$(sh "$CLI" invites)" = "No open invites." ] || fail "empty list wrong"

# A failed read must not look like an empty list.
if OUT="$(STUB_FAIL='get users' sh "$CLI" invites 2>/dev/null)"; then fail "invites hid a failed read"; fi
if echo "$OUT" | grep -q 'No open invites'; then fail "failed read reported as empty"; fi
if OUT="$(STUB_FAIL='config credentials' sh "$CLI" invites 2>&1)"; then fail "invites hid a failed login"; fi
echo "$OUT" | grep -q 'stub: refused' || fail "login failure hid the reason: $OUT"

# --- revoke deletes only an unredeemed join- user.
STUB_LOOKUP='id-1,UPDATE_PASSWORD,UPDATE_PROFILE'
export STUB_LOOKUP
: > "$LOG"
sh "$CLI" revoke join-ab12 >/dev/null
expect_call 'delete users/id-1 -r detour'

for looked_up in 'id-1' 'id-1,UPDATE_PASSWORD'; do
    STUB_LOOKUP="$looked_up"
    expect_refused revoke join-ab12
    expect_no_call ' delete '
done
STUB_LOOKUP=''
expect_refused revoke join-ab12
expect_no_call ' delete '
STUB_LOOKUP='id-1,UPDATE_PROFILE'
expect_refused revoke mia.k
expect_no_docker "revoke of a name that isn't an invite"

# --- disable / enable / delete / reset.
STUB_LOOKUP='id-2'
: > "$LOG"
sh "$CLI" disable mia.k >/dev/null
expect_call 'get users -r detour -q username=mia.k -q exact=true'
expect_call 'update users/id-2 -r detour -s enabled=false'
expect_call 'create users/id-2/logout -r detour'

: > "$LOG"
sh "$CLI" enable mia.k >/dev/null
expect_call 'update users/id-2 -r detour -s enabled=true'
expect_no_call 'logout'

: > "$LOG"
sh "$CLI" delete mia.k >/dev/null
expect_call 'delete users/id-2 -r detour'

: > "$LOG"
OUT="$(sh "$CLI" reset mia.k)"
expect_call 'set-password -r detour --userid id-2 --new-password [0-9a-f]\{12\} --temporary'
expect_call 'create users/id-2/logout -r detour'
echo "$OUT" | grep -q 'Username: mia.k$' || fail "reset lacks the name"
echo "$OUT" | grep -Eq 'Code: [0-9a-f]{12}$' || fail "reset lacks the code"

# A step that fails stops the command, even when a later one would pass.
if STUB_FAIL='enabled=false' sh "$CLI" disable mia.k >/dev/null 2>&1; then fail "disable hid a failed update"; fi
if STUB_FAIL='/logout' sh "$CLI" disable mia.k >/dev/null 2>&1; then fail "disable hid a failed logout"; fi
STUB_LOOKUP=''
for c in disable enable delete reset; do
    expect_refused "$c" nobody
    expect_no_call 'users/'
done

echo "PASS"
