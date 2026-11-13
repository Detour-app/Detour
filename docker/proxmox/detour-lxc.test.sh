#!/usr/bin/env bash
# shellcheck disable=SC2016 # the stubs are written out literally, expanded when they run
# Test for docker/proxmox/detour-lxc.sh (#876), with the Proxmox tools stubbed on
# PATH. `pct exec` runs the in-container half locally, its system paths redirected
# into a temp dir, so the kit's real `./detour setup` writes a real .env from a copy
# of docker/prod/. Nothing outside the temp dir is touched.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
PROD="$HERE/../prod"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/etc/apt/sources.list.d"
LOG="$WORK/calls.log"
printf 'VERSION_CODENAME=trixie\n' > "$WORK/etc/os-release"
export STUB_LOG="$LOG" STUB_WORK="$WORK" STUB_PROD="$PROD"

stub() { printf '#!/usr/bin/env bash\n%s\n' "$2" > "$WORK/bin/$1"; chmod +x "$WORK/bin/$1"; }
# Every stub logs its argv; STUB_FAIL makes the matching call exit 1.
LOGLINE='echo "$(basename "$0") $*" >> "$STUB_LOG"; case "$(basename "$0") $*" in *"${STUB_FAIL:-no-failure-set}"*) echo "stub: refused" >&2; exit 1 ;; esac'

stub id "$LOGLINE"'; echo "${STUB_UID:-0}"'
stub pvesh "$LOGLINE"'; echo 200'
stub pvesm "$LOGLINE"'; [ "$3" = local-lvm ]'
stub pveam "$LOGLINE"'
case "$1" in
    available) printf "system debian-11-standard_11.7-1_amd64.tar.zst\nsystem debian-13-standard_13.6-1_amd64.tar.zst\nsystem debian-12-standard_12.12-1_amd64.tar.zst\nsystem alpine-3.22-default_20250617_amd64.tar.xz\n" ;;
    list) echo "local:vztmpl/debian-12-standard_12.12-1_amd64.tar.zst 118MB" ;;
esac'
# pct status <id> succeeds only for STUB_EXISTING; pct exec -- bash -c <script> runs
# the script here with /etc and /opt moved under $STUB_WORK.
stub pct "$LOGLINE"'
case "$1" in
    status) [ "$2" = "${STUB_EXISTING:-none}" ] ;;
    exec)
        shift 3
        if [ "$1" = bash ]; then
            script="$(printf "%s" "$3" | sed -e "s#/etc/#$STUB_WORK/etc/#g")"
            shift 3
            exec bash -c "$script" "$@"
        fi
        echo 10.0.0.9 ;;
esac'
for t in apt-get curl install chmod dpkg systemctl getent sleep; do stub "$t" "$LOGLINE"; done
stub git "$LOGLINE"'
if [ "$1" = clone ]; then
    dest="${*: -1}"; mkdir -p "$dest/docker"; cp -R "$STUB_PROD" "$dest/docker/prod"; rm -f "$dest/docker/prod/.env"
fi'
stub docker "$LOGLINE"

PATH="$WORK/bin:$PATH"
fail() { echo "FAIL: $1"; exit 1; }
expect_call() { grep -q -- "$1" "$LOG" || fail "no call matching '$1' in: $(cat "$LOG")"; }
expect_no_call() { if grep -q -- "$1" "$LOG"; then fail "unexpected '$1' in: $(cat "$LOG")"; fi; }

# Runs the script with /opt/detour moved into the temp dir.
run() {
    rm -rf "$WORK/opt"
    : > "$LOG"
    (
        # shellcheck source=detour-lxc.sh
        . "$HERE/detour-lxc.sh"
        KIT_DIR="$WORK/opt/detour"
        main "$@"
    )
}
ENV="$WORK/opt/detour/docker/prod/.env"
key() { sed -n "s/^$1=//p" "$ENV"; }

expect_refused() {
    if run "$@" > "$WORK/out" 2>&1; then fail "should refuse: $*"; fi
    expect_no_call "pct create"
}

# --- refusals: nothing is created.
expect_refused
expect_refused --host
expect_refused --host https://a.example.com
expect_refused --host 'a.example.com
EVIL=1'
expect_refused --host a.example.com --email 'a b@c.d'
expect_refused --host a.example.com --routing 'ben lux'
expect_refused --host a.example.com --routing benelux
expect_refused --host a.example.com --ctid abc
expect_refused --host a.example.com --memory 4g
expect_refused --host a.example.com --storage nope
expect_refused --host a.example.com --ref '--upload-pack=x'
expect_refused --host a.example.com --bogus
for o in --email --routing --tag --ref --ctid; do
    expect_refused --host a.example.com "$o" --memory
done
STUB_EXISTING=150 expect_refused --host a.example.com --ctid 150
STUB_UID=1000 expect_refused --host a.example.com

# --- the default install.
OUT="$(run --host a.example.com --email ops@example.com)"
expect_call "pvesh get /cluster/nextid"
# Newest Debian, downloaded because only 12 is cached.
expect_call "pveam download local debian-13-standard_13.6-1_amd64.tar.zst"
expect_call "pct create 200 local:vztmpl/debian-13-standard_13.6-1_amd64.tar.zst"
expect_call "--unprivileged 1 --features nesting=1,keyctl=1 --cores 2 --memory 4096"
expect_call "--rootfs local-lvm:16 --net0 name=eth0,bridge=vmbr0,ip=dhcp --onboot 1"
expect_call "pct start 200"
expect_call "apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin"
expect_call "systemctl enable --now docker"
expect_call "git clone -q --depth 1 --branch main --filter=blob:none --sparse https://github.com/Detour-app/Detour.git"
expect_call "git -C $WORK/opt/detour sparse-checkout set docker/prod"
expect_call "docker compose up -d"
grep -q 'download.docker.com/linux/debian trixie stable' "$WORK/etc/apt/sources.list.d/docker.list" \
    || fail "docker.list: $(cat "$WORK/etc/apt/sources.list.d/docker.list")"
[ -f "$ENV" ] || fail "./detour setup wrote no .env"
[ "$(key DETOUR_HOSTNAME)" = a.example.com ] || fail "DETOUR_HOSTNAME is $(key DETOUR_HOSTNAME)"
[ "$(key ACME_EMAIL)" = ops@example.com ] || fail "ACME_EMAIL is $(key ACME_EMAIL)"
case "$(key COMPOSE_FILE)" in *routing*) fail "routing layer without --routing" ;; esac
[ -n "$(key POSTGRES_PASSWORD)" ] || fail "no generated POSTGRES_PASSWORD"
case "$OUT" in *"Keycloak admin password"*) ;; *) fail "setup's admin password not shown: $OUT" ;; esac
case "$OUT" in *"container 200 at 10.0.0.9"*"./detour invite"*) ;; *) fail "next steps missing: $OUT" ;; esac

# --- routing, explicit placement and a pinned ref/tag.
run --host b.example.com --routing benelux --memory 14336 --ctid 150 --storage local-lvm \
    --bridge vmbr1 --disk 40 --cores 4 --ref v4.16.0 --tag abc123 >/dev/null
expect_no_call "pvesh get"
expect_call "pct create 150 "
expect_call "--cores 4 --memory 14336"
expect_call "--rootfs local-lvm:40 --net0 name=eth0,bridge=vmbr1,ip=dhcp"
expect_call "--branch v4.16.0"
case "$(key COMPOSE_FILE)" in *docker-compose.routing.yml*) ;; *) fail "no routing layer: $(key COMPOSE_FILE)" ;; esac
[ "$(key OSM_REGION)" = benelux ] || fail "OSM_REGION is $(key OSM_REGION)"
[ "$(key DETOUR_IMAGE_TAG)" = abc123 ] || fail "DETOUR_IMAGE_TAG is $(key DETOUR_IMAGE_TAG)"

# --- a failed install inside: nonzero, and says how to clean up.
if STUB_FAIL='docker compose up' run --host a.example.com > "$WORK/out" 2>&1; then
    fail "an inner failure exited 0"
fi
grep -q 'pct stop 200 && pct destroy 200' "$WORK/out" || fail "no clean-up hint: $(cat "$WORK/out")"

echo "detour-lxc.test.sh: OK"
