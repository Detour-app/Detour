#!/usr/bin/env bash
#
# Map Roulette server installer.
#
# Installs the sync server (accounts, trips, fog of war), the GraphHopper
# routing engine, and/or the Photon geocoder that the Android app talks to.
#
#   On a Proxmox host : creates an unprivileged LXC and installs inside it.
#   Anywhere else     : installs into the current Debian/Ubuntu machine.
#
# Both services bind to localhost. Nothing is exposed to the internet; the
# installer prints your options at the end and configures none of them.
#
#   bash install.sh                     # interactive
#   bash install.sh --all --yes         # sync + routing + geocoder, no prompts
#   bash install.sh --sync              # sync server only
#   bash install.sh --routing --region europe/netherlands
#   bash install.sh --geocoder --geo-country nl   # Photon geocoder only
#   bash install.sh --uninstall         # remove services, keep data
#
set -euo pipefail

REPO="${MAPROULETTE_REPO:-maxke24/map_roulette}"
REF="${MAPROULETTE_REF:-main}"
RAW="https://raw.githubusercontent.com/${REPO}/${REF}"

# Defaults
DO_SYNC=0 DO_ROUTING=0 DO_GEOCODER=0 CHOSE=0
ASSUME_YES=0 UNINSTALL=0 PURGE=0 FORCE_IN_PLACE=0
REGION="${REGION:-europe/belgium}"
GEO_CC="${GEO_CC:-be}"            # Photon country-code index (single country)
PHOTON_VERSION="${PHOTON_VERSION:-0.4.4}"
CTID="" CT_HOSTNAME="maproulette" CT_BRIDGE="vmbr0" CT_STORAGE=""
OPEN_REGISTRATION=0
SYNC_PORT=8790 LIVE_PORT=8990 GH_PORT=8989 PHOTON_PORT=2322
# Which address the two Docker services publish on. Localhost by default —
# exposure is your decision (see the notes at the end). Set these when
# cloudflared, or anything else that has to reach them, runs on a *different*
# machine than the service: a re-run would otherwise quietly put the binding
# back to 127.0.0.1 and break a tunnel hostname that was working.
#   GH_BIND=10.0.0.5 PHOTON_BIND=10.0.0.6 bash server/install.sh …
GH_BIND="${GH_BIND:-127.0.0.1}" PHOTON_BIND="${PHOTON_BIND:-127.0.0.1}"

SYNC_USER=maproulette-sync
SYNC_DIR=/opt/maproulette-sync
SYNC_DATA=/var/lib/maproulette-sync
GH_DIR=/opt/graphhopper
PHOTON_DIR=/opt/photon

# ---------------------------------------------------------------- logging
if [ -t 1 ]; then B=$'\033[1m'; R=$'\033[0;31m'; Y=$'\033[0;33m'; G=$'\033[0;32m'; N=$'\033[0m'
else B= R= Y= G= N=; fi
step() { printf '\n%s==> %s%s\n' "$B" "$*" "$N"; }
info() { printf '    %s\n' "$*"; }
ok()   { printf '    %s✓%s %s\n' "$G" "$N" "$*"; }
warn() { printf '    %s!%s %s\n' "$Y" "$N" "$*" >&2; }
die()  { printf '\n%serror:%s %s\n' "$R" "$N" "$*" >&2; exit 1; }

confirm() {
  [ "$ASSUME_YES" = 1 ] && return 0
  [ -t 0 ] || die "not a terminal and --yes not given; refusing to guess"
  local reply
  read -r -p "    $1 [y/N] " reply
  [[ "$reply" =~ ^[Yy]$ ]]
}

# ---------------------------------------------------------------- args
while [ $# -gt 0 ]; do
  case "$1" in
    --sync)      DO_SYNC=1; CHOSE=1 ;;
    --routing)   DO_ROUTING=1; CHOSE=1 ;;
    --geocoder)  DO_GEOCODER=1; CHOSE=1 ;;
    --all)       DO_SYNC=1; DO_ROUTING=1; DO_GEOCODER=1; CHOSE=1 ;;
    --region)    REGION="${2:?--region needs a value}"; shift ;;
    --geo-country) GEO_CC="${2:?--geo-country needs a value}"; shift ;;
    --ctid)      CTID="${2:?--ctid needs a value}"; shift ;;
    --hostname)  CT_HOSTNAME="${2:?}"; shift ;;
    --bridge)    CT_BRIDGE="${2:?}"; shift ;;
    --storage)   CT_STORAGE="${2:?}"; shift ;;
    --in-place)  FORCE_IN_PLACE=1 ;;
    --open-registration) OPEN_REGISTRATION=1 ;;
    --uninstall) UNINSTALL=1 ;;
    --purge)     UNINSTALL=1; PURGE=1 ;;
    --yes|-y)    ASSUME_YES=1 ;;
    -h|--help)   sed -n '2,20p' "$0" | sed 's/^# \?//'; exit 0 ;;
    *)           die "unknown option: $1 (try --help)" ;;
  esac
  shift
done

[ "$(id -u)" = 0 ] || die "run as root"

# ---------------------------------------------------------------- sources
# Prefer files from a local checkout; fall back to fetching from GitHub so that
# `curl … | bash` works too.
SELF="$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || true)"
SRCDIR="${MAPROULETTE_SRC:-}"
if [ -z "$SRCDIR" ] && [ -n "$SELF" ] && [ -f "$(dirname "$SELF")/sync/sync_server.py" ]; then
  SRCDIR="$(dirname "$SELF")"
fi

# Files the installer needs beyond itself. When run from a checkout these are
# copied straight across (which also means an LXC install gets exactly the code
# you have, not whatever the default branch happens to hold today).
PAYLOAD=(sync/sync_server.py sync/maproulette-backup.sh verify.sh)

# fetch <path under server/> <destination>
fetch() {
  local rel="$1" dest="$2"
  if [ -n "$SRCDIR" ] && [ -f "$SRCDIR/$rel" ]; then
    install -m 0644 "$SRCDIR/$rel" "$dest"
  else
    curl -fsSL "$RAW/server/$rel" -o "$dest" || die \
"could not fetch server/$rel from $RAW

The repository may be private, or MAPROULETTE_REF may not exist. Either make it
public, or run this script from a clone so it can copy the files directly:

    git clone git@github.com:${REPO}.git && bash map_roulette/server/install.sh"
  fi
}

# ---------------------------------------------------------------- target
is_proxmox_host() { [ -d /etc/pve ] && command -v pct >/dev/null 2>&1; }

pick_components() {
  [ "$CHOSE" = 1 ] && return 0
  if [ "$ASSUME_YES" = 1 ] || [ ! -t 0 ]; then DO_SYNC=1; DO_ROUTING=1; DO_GEOCODER=1; return 0; fi
  echo
  echo "  What should I install?"
  echo "    1) Sync server only          — accounts, trips, fog of war. Small and fast."
  echo "    2) Routing server only       — GraphHopper. Multi-GB download, long import."
  echo "    3) Geocoder only             — Photon place/address search. ~1.6 GB download."
  echo "    4) All (default)"
  local reply
  read -r -p "  Choice [4]: " reply
  case "${reply:-4}" in
    1) DO_SYNC=1 ;;
    2) DO_ROUTING=1 ;;
    3) DO_GEOCODER=1 ;;
    4) DO_SYNC=1; DO_ROUTING=1; DO_GEOCODER=1 ;;
    *) die "invalid choice: $reply" ;;
  esac
}

# ================================================================= uninstall
do_uninstall() {
  step "Removing services"
  for unit in maproulette-sync maproulette-backup.timer maproulette-backup \
              graphhopper-refresh.timer graphhopper-refresh \
              photon-refresh.timer photon-refresh; do
    # `systemctl cat` rather than `list-unit-files | grep -q`: grep exits at the
    # first match and systemd complains about the broken pipe.
    if systemctl cat "$unit" >/dev/null 2>&1; then
      systemctl disable --now "$unit" >/dev/null 2>&1 || true
      rm -f "/etc/systemd/system/${unit}.service" "/etc/systemd/system/${unit}.timer"
      ok "removed $unit"
    fi
  done
  rm -rf /etc/systemd/system/maproulette-sync.service.d
  systemctl daemon-reload
  if [ -d "$GH_DIR" ] && command -v docker >/dev/null 2>&1; then
    (cd "$GH_DIR" && docker compose down 2>/dev/null) || true
    ok "stopped graphhopper"
  fi
  if [ -d "$PHOTON_DIR" ] && command -v docker >/dev/null 2>&1; then
    (cd "$PHOTON_DIR" && docker compose down 2>/dev/null) || true
    ok "stopped photon"
  fi
  rm -rf "$SYNC_DIR" /usr/local/bin/maproulette-backup.sh \
         /usr/local/bin/graphhopper-refresh.sh /usr/local/bin/photon-refresh.sh

  if [ "$PURGE" = 1 ]; then
    warn "--purge: deleting all trip data, accounts, the routing graph and the geocoder index"
    confirm "This destroys $SYNC_DATA, $GH_DIR and $PHOTON_DIR permanently. Continue?" \
      || die "aborted"
    rm -rf "$SYNC_DATA" "$GH_DIR" "$PHOTON_DIR"
    userdel "$SYNC_USER" 2>/dev/null || true
    ok "purged"
  else
    info "Kept your data: $SYNC_DATA and $GH_DIR"
    info "Backups (if any) are still in /var/backups/maproulette"
  fi
  echo; ok "Uninstalled."
}

# ================================================================= LXC path
create_lxc() {
  step "Proxmox host detected — creating a container"

  local cores ram disk
  cores=1; ram=512; disk=4
  if [ "$DO_ROUTING" = 1 ]; then cores=4; ram=8192; disk=24; fi
  if [ "$DO_GEOCODER" = 1 ]; then
    [ "$cores" -lt 2 ] && cores=2
    if [ "$ram" -lt 3072 ]; then ram=3072; else ram=$((ram + 2048)); fi
    disk=$((disk + 12))   # ~1.6 GB tar + ~6 GB extracted index + headroom
  fi
  info "sizing: ${cores} cores, ${ram} MB RAM, ${disk} GB disk"
  if [ "$DO_ROUTING" = 1 ]; then
    local host_ram_mb; host_ram_mb=$(free -m | awk '/^Mem:/{print $2}')
    [ "$host_ram_mb" -lt "$ram" ] && warn "host has ${host_ram_mb} MB RAM; the OSM import may OOM"
  fi

  [ -n "$CTID" ] || CTID="$(pvesh get /cluster/nextid)"
  pct status "$CTID" >/dev/null 2>&1 && die "CTID $CTID already exists (pass --ctid)"

  # Template: newest debian-12-standard, downloaded once.
  local tstore tmpl
  tstore="$(pvesm status -content vztmpl | awk 'NR>1{print $1; exit}')"
  [ -n "$tstore" ] || die "no storage accepts container templates (content=vztmpl)"
  pveam update >/dev/null 2>&1 || true
  tmpl="$(pveam available --section system | awk '/debian-12-standard/{print $2}' | sort -V | tail -1)"
  [ -n "$tmpl" ] || die "no debian-12-standard template offered by pveam"
  if ! pveam list "$tstore" 2>/dev/null | grep -q "$tmpl"; then
    info "downloading template $tmpl"
    pveam download "$tstore" "$tmpl" >/dev/null
  fi

  # Root filesystem storage: prefer whatever already holds containers.
  if [ -z "$CT_STORAGE" ]; then
    CT_STORAGE="$(pvesm status -content rootdir | awk 'NR>1{print $1; exit}')"
    [ -n "$CT_STORAGE" ] || die "no storage accepts container rootfs (content=rootdir)"
  fi
  info "CTID=$CTID storage=$CT_STORAGE bridge=$CT_BRIDGE"
  confirm "Create container $CTID ($CT_HOSTNAME)?" || die "aborted"

  # nesting+keyctl are what let Docker run inside an unprivileged container.
  pct create "$CTID" "${tstore}:vztmpl/${tmpl}" \
    --hostname "$CT_HOSTNAME" --cores "$cores" --memory "$ram" --swap 512 \
    --rootfs "${CT_STORAGE}:${disk}" \
    --net0 "name=eth0,bridge=${CT_BRIDGE},ip=dhcp" \
    --features nesting=1,keyctl=1 \
    --unprivileged 1 --onboot 1 >/dev/null
  ok "created CT $CTID"
  pct start "$CTID" >/dev/null
  ok "started"

  step "Waiting for the container's network"
  local i
  for i in $(seq 1 60); do
    if pct exec "$CTID" -- getent hosts deb.debian.org >/dev/null 2>&1; then break; fi
    [ "$i" = 60 ] && die "container $CTID never got DNS/network"
    sleep 2
  done
  ok "network up"

  step "Installing inside CT $CTID"
  local inner=/root/maproulette-install.sh src="$SELF"
  if [ -z "$src" ] || [ ! -f "$src" ]; then
    # Running via `curl … | bash`: there is no file to push, so fetch one.
    src="$(mktemp)"; trap 'rm -f "$src"' RETURN
    curl -fsSL "$RAW/server/install.sh" -o "$src" || die "could not fetch install.sh"
  fi
  pct push "$CTID" "$src" "$inner" --perms 0755

  # Carry the rest of the payload in, so the container never needs to reach
  # GitHub (and a private repo works fine).
  local inner_src=""
  if [ -n "$SRCDIR" ]; then
    inner_src=/root/maproulette-src
    pct exec "$CTID" -- mkdir -p "$inner_src/sync"
    local f
    for f in "${PAYLOAD[@]}"; do
      [ -f "$SRCDIR/$f" ] || die "missing $SRCDIR/$f"
      pct push "$CTID" "$SRCDIR/$f" "$inner_src/$f" --perms 0644
    done
    ok "copied $(( ${#PAYLOAD[@]} )) files from the local checkout"
  fi

  local flags="--in-place --yes --region $REGION --geo-country $GEO_CC"
  [ "$DO_SYNC" = 1 ] && flags="$flags --sync"
  [ "$DO_ROUTING" = 1 ] && flags="$flags --routing"
  [ "$DO_GEOCODER" = 1 ] && flags="$flags --geocoder"
  [ "$OPEN_REGISTRATION" = 1 ] && flags="$flags --open-registration"

  # LC_ALL=C silences the template's missing-locale warnings from apt/perl.
  pct exec "$CTID" -- env LC_ALL=C LANG=C \
    MAPROULETTE_REPO="$REPO" MAPROULETTE_REF="$REF" MAPROULETTE_SRC="$inner_src" \
    bash "$inner" $flags

  local ip
  ip="$(pct exec "$CTID" -- hostname -I 2>/dev/null | awk '{print $1}')"
  echo
  step "Container $CTID ready"
  info "LAN address : ${ip:-unknown}"
  info "Enter it    : pct enter $CTID"
  info "Services listen on localhost inside the container — see the notes above."
}

# ================================================================= in-place
require_debian() {
  command -v systemctl >/dev/null || die "this installer needs systemd"
  command -v apt-get   >/dev/null || die "this installer supports Debian/Ubuntu only"
}

install_sync() {
  step "Installing the sync server"
  # python3-websockets (Debian/Ubuntu's packaged build, not pip — keeps this
  # a plain apt install with no venv) powers the convoy live-location/PTT
  # relay. The rest of the server is stdlib-only and still runs fine without
  # it; the relay just logs a warning and stays off if this package is
  # missing for some reason.
  apt-get install -y -qq python3 python3-websockets curl >/dev/null
  id -u "$SYNC_USER" >/dev/null 2>&1 || useradd --system --home "$SYNC_DATA" --shell /usr/sbin/nologin "$SYNC_USER"
  install -d -m 0755 "$SYNC_DIR"
  install -d -o "$SYNC_USER" -g "$SYNC_USER" -m 0750 "$SYNC_DATA"
  install -d -o "$SYNC_USER" -g "$SYNC_USER" -m 0750 /var/backups/maproulette

  fetch "sync/sync_server.py" "$SYNC_DIR/sync_server.py"
  chmod 0644 "$SYNC_DIR/sync_server.py"
  fetch "sync/maproulette-backup.sh" /usr/local/bin/maproulette-backup.sh
  chmod 0755 /usr/local/bin/maproulette-backup.sh
  ok "installed $SYNC_DIR/sync_server.py"

  cat > /etc/systemd/system/maproulette-sync.service <<EOF
[Unit]
Description=Map Roulette sync server
After=network.target

[Service]
User=$SYNC_USER
Environment=DATA_DIR=$SYNC_DATA
Environment=HOST=127.0.0.1
Environment=PORT=$SYNC_PORT
Environment=LIVE_PORT=$LIVE_PORT
# This installer's documented topology puts the server behind the Cloudflare
# tunnel, so CF-Connecting-IP is trustworthy here — see sync_server.py.
Environment=TRUST_CF_HEADER=1
# The server itself defaults registration to closed; only bake this in when
# --open-registration was passed, so the fail-closed default holds otherwise.
$([ "$OPEN_REGISTRATION" = 1 ] && echo "Environment=REGISTRATION_OPEN=1")
ExecStart=/usr/bin/python3 $SYNC_DIR/sync_server.py
Restart=on-failure

# Hardening: the service only ever needs to write its own data directory.
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
ReadWritePaths=$SYNC_DATA /var/backups/maproulette

[Install]
WantedBy=multi-user.target
EOF

  # Registration is gated by an invite code unless explicitly opened. The server
  # is on localhost, but it will not stay there, and an open /auth/register is
  # the one thing a stranger could reach the moment it is exposed.
  local invite=""
  if [ "$OPEN_REGISTRATION" = 1 ]; then
    rm -rf /etc/systemd/system/maproulette-sync.service.d
    warn "registration is OPEN — anyone who can reach the server can create an account"
  elif [ ! -f /etc/systemd/system/maproulette-sync.service.d/invite.conf ]; then
    invite="$(python3 -c 'import secrets; print(secrets.token_urlsafe(18))')"
    install -d -m 0755 /etc/systemd/system/maproulette-sync.service.d
    printf '[Service]\nEnvironment=INVITE_CODE=%s\n' "$invite" \
      > /etc/systemd/system/maproulette-sync.service.d/invite.conf
    chmod 0600 /etc/systemd/system/maproulette-sync.service.d/invite.conf
  fi

  cat > /etc/systemd/system/maproulette-backup.service <<EOF
[Unit]
Description=Back up the Map Roulette sync database

[Service]
Type=oneshot
User=$SYNC_USER
ExecStart=/usr/local/bin/maproulette-backup.sh
EOF
  cat > /etc/systemd/system/maproulette-backup.timer <<'EOF'
[Unit]
Description=Nightly Map Roulette database backup

[Timer]
OnCalendar=daily
Persistent=true
RandomizedDelaySec=15m

[Install]
WantedBy=timers.target
EOF

  systemctl daemon-reload
  systemctl enable --now maproulette-sync >/dev/null 2>&1
  systemctl enable --now maproulette-backup.timer >/dev/null 2>&1

  local i
  for i in $(seq 1 20); do
    [ "$(curl -fsS "http://localhost:$SYNC_PORT/health" 2>/dev/null || true)" = "ok" ] && break
    [ "$i" = 20 ] && { journalctl -u maproulette-sync -n 20 --no-pager; die "sync server did not come up"; }
    sleep 1
  done
  ok "sync server healthy on 127.0.0.1:$SYNC_PORT"
  # Best-effort only: the live relay is optional (see the python3-websockets
  # note above), and its absence must not fail the whole install.
  if command -v python3 >/dev/null && python3 -c "import websockets" 2>/dev/null; then
    for i in $(seq 1 10); do
      python3 -c "import socket; socket.create_connection(('127.0.0.1', $LIVE_PORT), 1).close()" 2>/dev/null && break
      sleep 1
    done
    if python3 -c "import socket; socket.create_connection(('127.0.0.1', $LIVE_PORT), 1).close()" 2>/dev/null; then
      ok "convoy live relay listening on 127.0.0.1:$LIVE_PORT"
    else
      warn "convoy live relay did not come up on 127.0.0.1:$LIVE_PORT — check: journalctl -u maproulette-sync -n 40"
    fi
  else
    warn "python3-websockets not importable — convoy live location/PTT will stay disabled"
  fi
  ok "nightly backup timer enabled"
  [ -n "$invite" ] && { echo; info "${B}Invite code: ${invite}${N}"; info "Enter this in the app's sign-in screen to register."; }
  return 0
}

install_docker() {
  command -v docker >/dev/null 2>&1 && { ok "docker already present"; return 0; }
  step "Installing Docker"
  # Debian's docker.io has no compose plugin; the official script does.
  curl -fsSL https://get.docker.com | sh >/dev/null 2>&1 || die "docker install failed"
  systemctl enable --now docker >/dev/null 2>&1
  ok "docker installed"
}

install_routing() {
  step "Installing the routing server (GraphHopper)"
  apt-get install -y -qq curl ca-certificates >/dev/null
  install_docker

  local url name
  case "$REGION" in
    http*) url="$REGION" ;;
    *)     url="https://download.geofabrik.de/${REGION}-latest.osm.pbf" ;;
  esac
  name="$(basename "$url")"
  install -d -m 0755 "$GH_DIR/data"

  if [ ! -f "$GH_DIR/data/$name" ]; then
    info "downloading $url"
    curl -fL# "$url" -o "$GH_DIR/data/$name.part" || die "download failed — is '$REGION' a real Geofabrik path?"
    mv "$GH_DIR/data/$name.part" "$GH_DIR/data/$name"
  fi
  ok "extract: $name ($(du -h "$GH_DIR/data/$name" | cut -f1))"

  # Heap: GraphHopper wants a lot during import. Take ~70% of RAM, floor 2g.
  local ram_mb heap
  ram_mb=$(free -m | awk '/^Mem:/{print $2}')
  heap=$(( ram_mb * 70 / 100 / 1024 ))
  [ "$heap" -lt 2 ] && heap=2
  info "java heap: -Xmx${heap}g (host has ${ram_mb} MB)"

  # Profiles below are the ones the app asks for by name: moto (curvy, avoids
  # motorways), car (fastest, accepts a query custom_model), bike (cycleways).
  # `curvature` is GraphHopper's built-in per-edge sinuosity, and its edges run
  # junction-to-junction, so an intersection turn never counts as "curvy".
  cat > "$GH_DIR/data/config.yml" <<EOF
graphhopper:
  datareader.file: /data/${name}
  # This key must stay in sync with --graph-cache in docker-compose.yml, and
  # both are needed. The image's entrypoint always appends
  #   -Ddw.graphhopper.graph.location=<--graph-cache, default /data/default-gh>
  # and a Dropwizard -Ddw. property can only override a key that already exists
  # here. Omit this line and the flag is silently ignored, leaving the graph in
  # /data/<extract>-gh instead. Delete the directory to force a re-import.
  graph.location: /data/graph-cache
  graph.encoded_values: curvature, road_class, road_access, max_speed, car_access, car_average_speed, bike_access, bike_average_speed, bike_priority, roundabout
  # Bikes need cycleways and paths, so only foot-only ways are dropped.
  import.osm.ignored_highways: footway,pedestrian,steps

  profiles:
    - name: moto
      weighting: custom
      custom_model:
        speed:
          - { if: "true", limit_to: "car_average_speed" }
        priority:
          # Custom-model multipliers may only lower priority (0..1), so this
          # penalises boring roads rather than boosting curvy ones.
          - { if: "road_class == MOTORWAY || road_class == TRUNK", multiply_by: "0.1" }
          - { if: "road_class == RESIDENTIAL", multiply_by: "0.5" }
          - { if: "curvature > 0.95", multiply_by: "0.4" }
          - { else_if: "curvature > 0.85", multiply_by: "0.7" }

    - name: car
      weighting: custom
      custom_model:
        speed:
          - { if: "true", limit_to: "car_average_speed" }
        priority:
          - { if: "car_access == false", multiply_by: "0" }

    - name: bike
      weighting: custom
      custom_model:
        speed:
          - { if: "true", limit_to: "bike_average_speed" }
        priority:
          - { if: "bike_access == false", multiply_by: "0" }

  # No CH profiles: round_trip and query custom_models need flexible routing.
  profiles_ch: []

server:
  application_connectors:
    - type: http
      port: ${GH_PORT}
      bind_host: 0.0.0.0
EOF

  cat > "$GH_DIR/docker-compose.yml" <<EOF
services:
  graphhopper:
    image: israelhikingmap/graphhopper:latest
    container_name: graphhopper
    command: --config /data/config.yml --graph-cache /data/graph-cache
    environment:
      JAVA_OPTS: "-Xmx${heap}g -Xms1g"
    volumes:
      - ./data:/data
    # localhost only unless GH_BIND says otherwise: the exposure decision is
    # yours, see the notes at the end.
    ports:
      - "${GH_BIND}:${GH_PORT}:${GH_PORT}"
    restart: unless-stopped
EOF

  cat > /usr/local/bin/graphhopper-refresh.sh <<EOF
#!/usr/bin/env bash
# Refresh the OSM extract and force a re-import.
set -euo pipefail
cd "$GH_DIR"
curl -fL "$url" -o "data/${name}.part"
mv "data/${name}.part" "data/${name}"
docker compose down
rm -rf data/graph-cache   # deleting the graph is what forces a re-import
docker compose up -d
EOF
  chmod 0755 /usr/local/bin/graphhopper-refresh.sh

  cat > /etc/systemd/system/graphhopper-refresh.service <<'EOF'
[Unit]
Description=Refresh GraphHopper OSM data

[Service]
Type=oneshot
ExecStart=/usr/local/bin/graphhopper-refresh.sh
EOF
  cat > /etc/systemd/system/graphhopper-refresh.timer <<'EOF'
[Unit]
Description=Monthly GraphHopper OSM refresh

[Timer]
OnCalendar=monthly
Persistent=true
RandomizedDelaySec=2h

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable graphhopper-refresh.timer >/dev/null 2>&1

  step "Importing OSM data — this takes 5-40 minutes and cannot be hurried"
  info "the container restarts itself when the graph is built; watching for it"
  (cd "$GH_DIR" && docker compose up -d >/dev/null 2>&1) || die "docker compose up failed"

  local i
  for i in $(seq 1 240); do   # 240 * 15s = 60 min
    if curl -fsS "http://localhost:$GH_PORT/info" >/dev/null 2>&1; then break; fi
    if [ "$i" = 240 ]; then
      (cd "$GH_DIR" && docker compose logs --tail 30)
      die "GraphHopper never became ready. Common cause: OOM during import — raise JAVA_OPTS in $GH_DIR/docker-compose.yml, or use a smaller extract."
    fi
    if [ $((i % 8)) = 0 ]; then info "still importing … ($((i * 15 / 60)) min)"; fi
    sleep 15
  done

  local profiles
  profiles="$(curl -fsS "http://localhost:$GH_PORT/info" | python3 -c 'import sys,json; print(",".join(p["name"] for p in json.load(sys.stdin)["profiles"]))')"
  ok "graphhopper up on $GH_BIND:$GH_PORT — profiles: $profiles"
  ok "monthly OSM refresh timer enabled"
}

# Resolve the newest dated country index on the mirror. The `-latest` symlink is
# unreliable there (HTTP 404 on some countries), so we read the directory listing.
photon_index_url() {
  local base="https://download1.graphhopper.com/public/extracts/by-country-code/${GEO_CC}/"
  local file
  file="$(curl -fsS "$base" \
    | grep -oE "photon-db-${GEO_CC}-[0-9]+\.tar\.bz2" | sort -u | tail -1)" \
    || die "could not list the Photon index mirror for country '$GEO_CC'"
  [ -n "$file" ] || die "no Photon index found for country code '$GEO_CC' at $base"
  printf '%s%s\n' "$base" "$file"
}

install_geocoder() {
  step "Installing the geocoder (Photon, country=$GEO_CC)"
  apt-get install -y -qq curl ca-certificates bzip2 >/dev/null
  install_docker

  install -d -m 0755 "$PHOTON_DIR/data"

  # Photon's search index for one country (prebuilt by GraphHopper). Extracts to
  # a photon_data/ directory next to the jar; that's the -data-dir Photon reads.
  if [ ! -d "$PHOTON_DIR/data/photon_data" ]; then
    local url name
    url="$(photon_index_url)"; name="$(basename "$url")"
    info "downloading $url"
    curl -fL# "$url" -o "$PHOTON_DIR/data/$name.part" || die "index download failed"
    mv "$PHOTON_DIR/data/$name.part" "$PHOTON_DIR/data/$name"
    ok "index: $name ($(du -h "$PHOTON_DIR/data/$name" | cut -f1))"
    info "extracting (a few minutes) …"
    tar -xjf "$PHOTON_DIR/data/$name" -C "$PHOTON_DIR/data"
    rm -f "$PHOTON_DIR/data/$name"
    [ -d "$PHOTON_DIR/data/photon_data" ] || die "extract did not yield photon_data/"
    ok "index extracted"
  else
    ok "index already present ($(du -sh "$PHOTON_DIR/data/photon_data" | cut -f1))"
  fi

  # The jar version must match the index's search-engine format. The
  # by-country-code extracts on the mirror are still built with the legacy
  # embedded-Elasticsearch line (ES 5.6 / Lucene 6.2), NOT the OpenSearch line
  # the 1.x jars use — a 1.x jar reports the data dir as "empty". Photon 0.4.4
  # is the last ES-based release and reads these dumps. If a future dump bumps
  # its DB schema, Photon logs "Database has incompatible version 'X'"; set
  # PHOTON_VERSION to the release built for that schema and re-run.
  if [ ! -f "$PHOTON_DIR/data/photon.jar" ]; then
    local jar="https://github.com/komoot/photon/releases/download/${PHOTON_VERSION}/photon-${PHOTON_VERSION}.jar"
    info "downloading $jar"
    curl -fL# "$jar" -o "$PHOTON_DIR/data/photon.jar.part" || die "photon.jar download failed"
    mv "$PHOTON_DIR/data/photon.jar.part" "$PHOTON_DIR/data/photon.jar"
    ok "photon-${PHOTON_VERSION}.jar"
  fi

  # Heap: one country is small; 2g is plenty, and the embedded ES mmaps the
  # rest. Photon 0.4.4's bundled Netty needs Java 11 (it dies on 21's stricter
  # reflection), and the ES 5.6 node won't run on Java 8-only class files.
  cat > "$PHOTON_DIR/docker-compose.yml" <<EOF
services:
  photon:
    image: eclipse-temurin:11-jre
    container_name: photon
    working_dir: /photon
    command: java -Xmx2g -jar /photon/photon.jar -data-dir /photon -listen-ip 0.0.0.0 -listen-port ${PHOTON_PORT}
    volumes:
      - ./data:/photon
    # localhost only unless PHOTON_BIND says otherwise: exposure is your
    # decision, see the notes at the end.
    ports:
      - "${PHOTON_BIND}:${PHOTON_PORT}:${PHOTON_PORT}"
    restart: unless-stopped
EOF

  cat > /usr/local/bin/photon-refresh.sh <<EOF
#!/usr/bin/env bash
# Refresh the Photon country index and restart. Reads the newest dated file from
# the mirror (the -latest symlink there is unreliable).
set -euo pipefail
base="https://download1.graphhopper.com/public/extracts/by-country-code/${GEO_CC}/"
file="\$(curl -fsS "\$base" | grep -oE "photon-db-${GEO_CC}-[0-9]+\\.tar\\.bz2" | sort -u | tail -1)"
[ -n "\$file" ] || { echo "no index found for ${GEO_CC}"; exit 1; }
cd "$PHOTON_DIR/data"
curl -fL "\$base\$file" -o "\$file.part"
mv "\$file.part" "\$file"
cd "$PHOTON_DIR" && docker compose down
rm -rf "$PHOTON_DIR/data/photon_data"
tar -xjf "$PHOTON_DIR/data/\$file" -C "$PHOTON_DIR/data"
rm -f "$PHOTON_DIR/data/\$file"
docker compose up -d
EOF
  chmod 0755 /usr/local/bin/photon-refresh.sh

  cat > /etc/systemd/system/photon-refresh.service <<'EOF'
[Unit]
Description=Refresh Photon geocoder index

[Service]
Type=oneshot
ExecStart=/usr/local/bin/photon-refresh.sh
EOF
  cat > /etc/systemd/system/photon-refresh.timer <<'EOF'
[Unit]
Description=Monthly Photon index refresh

[Timer]
OnCalendar=monthly
Persistent=true
RandomizedDelaySec=2h

[Install]
WantedBy=timers.target
EOF
  systemctl daemon-reload
  systemctl enable photon-refresh.timer >/dev/null 2>&1

  step "Starting Photon — it loads the index into the embedded ES (1-3 minutes)"
  (cd "$PHOTON_DIR" && docker compose up -d >/dev/null 2>&1) || die "docker compose up failed"

  local i
  for i in $(seq 1 40); do   # 40 * 6s = 4 min
    if curl -fsS "http://localhost:$PHOTON_PORT/api?q=test&limit=1" >/dev/null 2>&1; then break; fi
    if [ "$i" = 40 ]; then
      (cd "$PHOTON_DIR" && docker compose logs --tail 30)
      die "Photon never became ready. A 'data dir empty' log means the jar is too new for the ES-format extract (use 0.4.x); an 'incompatible version' log means the dump's DB schema moved — set PHOTON_VERSION to match and re-run."
    fi
    sleep 6
  done
  ok "photon up on $PHOTON_BIND:$PHOTON_PORT"
  ok "monthly index refresh timer enabled"
}

print_next_steps() {
  cat <<EOF

$B==> Installed.$N

  Both services listen on 127.0.0.1 only. Nothing is reachable from the
  internet yet, and this installer will not change that for you.

$B  Exposing them — pick one:$N

  1. Cloudflare Tunnel + Access  (what the author runs; no open ports)
       - install cloudflared, create a tunnel, point a hostname at
EOF
  [ "$DO_SYNC" = 1 ]     && echo "           http://localhost:$SYNC_PORT   (sync)"
  [ "$DO_SYNC" = 1 ]     && echo "           http://localhost:$LIVE_PORT   (convoy live relay, WebSocket — optional)"
  [ "$DO_ROUTING" = 1 ]  && echo "           http://localhost:$GH_PORT   (routing)"
  [ "$DO_GEOCODER" = 1 ] && echo "           http://localhost:$PHOTON_PORT   (geocoder / search)"
  cat <<EOF
       - the live relay needs its OWN Cloudflare hostname (a WebSocket
         upgrade doesn't route through the sync hostname's rule) — same
         Access application/service token as the others.
       - protect EVERY hostname with an Access application whose policy is
         Action=Service Auth, including the SAME service token. The app sends
         that one token's headers to routing, sync, live and the geocoder alike.
       - Neither GraphHopper nor Photon has authentication of its own. If you
         expose either without Access in front, it is open to the internet.

  2. Tailscale / WireGuard — join the phone to the private network and point
     the app at the machine's VPN address. No public exposure at all.

  3. Reverse proxy (Caddy/nginx) with TLS. If you do this, put HTTP auth in
     front of the routing port; the sync server authenticates users itself,
     but its /auth/register endpoint should still be invite-gated.

$B  Then, in the app$N (Settings → Server), enter the routing URL, the sync URL,
  the geocoder (search) URL, and the CF Access service-token ID/secret if you
  used option 1. The one token's ID/secret is shared across all three.

$B  Verify anytime:$N
       bash verify.sh $([ "$DO_ROUTING" = 1 ] && echo --routing)

$B  Useful:$N
       systemctl status maproulette-sync
       journalctl -u maproulette-sync -f
       /usr/local/bin/maproulette-backup.sh          # backup now
       bash install.sh --uninstall                   # remove, keep data

EOF
  if [ "$DO_SYNC" = 1 ] && [ -f /etc/systemd/system/maproulette-sync.service.d/invite.conf ]; then
    echo "  Invite code (needed to register): $(sed -n 's/^Environment=INVITE_CODE=//p' /etc/systemd/system/maproulette-sync.service.d/invite.conf)"
    echo "  Close registration entirely once your friends have accounts:"
    echo "       echo 'Environment=REGISTRATION_OPEN=0' >> /etc/systemd/system/maproulette-sync.service.d/invite.conf"
    echo "       systemctl daemon-reload && systemctl restart maproulette-sync"
    echo
  fi
}

# ================================================================= main
main() {
  if [ "$UNINSTALL" = 1 ]; then do_uninstall; exit 0; fi

  pick_components
  [ "$DO_SYNC" = 1 ] || [ "$DO_ROUTING" = 1 ] || [ "$DO_GEOCODER" = 1 ] || die "nothing to do"

  if is_proxmox_host && [ "$FORCE_IN_PLACE" = 0 ]; then
    create_lxc
    exit 0
  fi

  require_debian
  step "Installing into this machine ($(. /etc/os-release && echo "$PRETTY_NAME"))"
  export DEBIAN_FRONTEND=noninteractive
  apt-get update -qq >/dev/null

  [ "$DO_SYNC" = 1 ]     && install_sync
  [ "$DO_ROUTING" = 1 ]  && install_routing
  [ "$DO_GEOCODER" = 1 ] && install_geocoder

  if [ -n "$SRCDIR" ] && [ -f "$SRCDIR/verify.sh" ]; then
    install -m 0755 "$SRCDIR/verify.sh" /usr/local/bin/maproulette-verify.sh
  else
    curl -fsSL "$RAW/server/verify.sh" -o /usr/local/bin/maproulette-verify.sh 2>/dev/null \
      && chmod 0755 /usr/local/bin/maproulette-verify.sh || true
  fi

  print_next_steps
}

main "$@"
