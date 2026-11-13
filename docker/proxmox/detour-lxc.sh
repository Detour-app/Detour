#!/usr/bin/env bash
# docker/proxmox/detour-lxc.sh: one-command Detour group server on Proxmox VE (#876).
# Run as root on the Proxmox host:
#
#   bash -c "$(curl -fsSL https://raw.githubusercontent.com/Detour-app/Detour/main/docker/proxmox/detour-lxc.sh)" -- --host detour.example.com
#
# It creates an unprivileged Debian LXC, installs Docker from Docker's apt repo,
# checks out docker/prod/ and then does exactly what the kit's README does by hand:
# `./detour setup` (secrets, layers) and `docker compose up -d`. Everything after
# that is the kit CLI inside the container; this script keeps no state of its own.
set -euo pipefail

REPO_URL=https://github.com/Detour-app/Detour.git
KIT_DIR=/opt/detour

usage() {
    cat <<'USAGE'
Usage: detour-lxc.sh --host <domain> [options]

  --host <domain>       Public hostname riders put in the app (required).
  --email <address>     ACME contact for Caddy's certificates.
  --routing <region>    Add the routing layer (docker/prod/config/regions.env).
                        Needs --memory: GraphHopper's heap plus ~4 GB.
  --tag <tag>           API image tag (default latest).
  --ref <git ref>       Branch or tag of this repo to check out (default main).
  --ctid <id>           Container id (default: the cluster's next free id).
  --hostname <name>     Container hostname (default detour).
  --storage <storage>   Storage for the root disk (default local-lvm).
  --template-storage <storage>
                        Storage holding LXC templates (default local).
  --bridge <bridge>     Network bridge (default vmbr0). The container uses DHCP.
  --cores <n>           CPU cores (default 2).
  --memory <MiB>        RAM (default 4096).
  --disk <GiB>          Root disk size (default 16).
USAGE
}

die() { echo "detour-lxc: $*" >&2; exit 1; }

# A value starting with a dash is the next option, not a value.
need_value() {
    [ "$#" -ge 2 ] || die "$1 needs a value"
    case "$2" in -*) die "$1 needs a value, got $2" ;; esac
}

need_number() {
    case "$2" in '' | *[!0-9]*) die "$1 takes a number: $2" ;; esac
}

need_name() {
    case "$2" in '' | -* | *[!A-Za-z0-9._-]*) die "$1: not a valid name: $2" ;; esac
}

# The in-container half, run by `pct exec` with bash -c; positional arguments are
# the setup options, already validated on the host.
read -r -d '' INNER <<'INNER_SCRIPT' || true
set -euo pipefail
repo_url="$1" ref="$2" kit_dir="$3"
shift 3
export DEBIAN_FRONTEND=noninteractive

echo "==> Waiting for the network"
for _ in $(seq 1 60); do
    getent hosts deb.debian.org >/dev/null 2>&1 && break
    sleep 2
done
getent hosts deb.debian.org >/dev/null 2>&1 || { echo "no DNS inside the container" >&2; exit 1; }

echo "==> Installing Docker"
apt-get update -qq
apt-get install -y -qq ca-certificates curl git openssl >/dev/null
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
. /etc/os-release
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/debian $VERSION_CODENAME stable" \
    > /etc/apt/sources.list.d/docker.list
apt-get update -qq
apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin >/dev/null
systemctl enable --now docker >/dev/null

echo "==> Checking out docker/prod ($ref)"
git clone -q --depth 1 --branch "$ref" --filter=blob:none --sparse "$repo_url" "$kit_dir"
git -C "$kit_dir" sparse-checkout set docker/prod

cd "$kit_dir/docker/prod"
./detour setup "$@"
echo "==> Starting the stack"
docker compose up -d
INNER_SCRIPT

main() {
    host='' email='' region='' tag='' ref=main ctid='' ct_hostname=detour
    storage=local-lvm tmpl_storage=local bridge=vmbr0 cores=2 memory='' disk=16
    while [ "$#" -gt 0 ]; do
        case "$1" in
            --host) need_value "$@"; host="$2"; shift 2 ;;
            --email) need_value "$@"; email="$2"; shift 2 ;;
            --routing) need_value "$@"; region="$2"; shift 2 ;;
            --tag) need_value "$@"; tag="$2"; shift 2 ;;
            --ref) need_value "$@"; ref="$2"; shift 2 ;;
            --ctid) need_value "$@"; need_number "$@"; ctid="$2"; shift 2 ;;
            --hostname) need_value "$@"; need_name "$@"; ct_hostname="$2"; shift 2 ;;
            --storage) need_value "$@"; need_name "$@"; storage="$2"; shift 2 ;;
            --template-storage) need_value "$@"; need_name "$@"; tmpl_storage="$2"; shift 2 ;;
            --bridge) need_value "$@"; need_name "$@"; bridge="$2"; shift 2 ;;
            --cores) need_value "$@"; need_number "$@"; cores="$2"; shift 2 ;;
            --memory) need_value "$@"; need_number "$@"; memory="$2"; shift 2 ;;
            --disk) need_value "$@"; need_number "$@"; disk="$2"; shift 2 ;;
            -h | --help) usage; exit 0 ;;
            --) shift ;;
            *) usage >&2; die "unknown argument: $1" ;;
        esac
    done

    # ./detour setup validates these again inside; failing here means no container
    # is created for a typo.
    [ -n "$host" ] || { usage >&2; die "--host <domain> is required"; }
    case "$host" in
        *[!A-Za-z0-9.-]* | -* | .* | *.) die "--host takes a bare domain, like detour.example.com: $host" ;;
    esac
    case "$email" in *[!A-Za-z0-9._%+@-]*) die "not an address: $email" ;; esac
    case "$region" in *[!a-z0-9-]*) die "unknown region: $region" ;; esac
    case "$tag" in *[!A-Za-z0-9._-]*) die "not an image tag: $tag" ;; esac
    case "$ref" in '' | -* | *[!A-Za-z0-9._/-]*) die "not a git ref: $ref" ;; esac
    if [ -n "$region" ] && [ -z "$memory" ]; then
        die "--routing needs --memory: GraphHopper's heap (docker/prod/README.md, Regions) plus ~4 GB, in MiB"
    fi
    memory="${memory:-4096}"

    [ "$(id -u)" -eq 0 ] || die "run as root on the Proxmox host"
    for tool in pct pveam pvesh pvesm; do
        command -v "$tool" >/dev/null 2>&1 || die "$tool not found; run this on a Proxmox VE host"
    done
    pvesm status --storage "$storage" >/dev/null 2>&1 || die "no storage named $storage (--storage)"

    if [ -z "$ctid" ]; then
        ctid="$(pvesh get /cluster/nextid)"
    elif pct status "$ctid" >/dev/null 2>&1; then
        die "container $ctid already exists"
    fi

    echo "==> Finding a Debian template"
    pveam update >/dev/null || echo "detour-lxc: pveam update failed; using the cached template list" >&2
    template="$(pveam available --section system \
        | awk '$2 ~ /^debian-[0-9]+-standard_/ { print $2 }' | sort -V | tail -n 1)"
    [ -n "$template" ] || die "no Debian template in pveam available"
    if ! pveam list "$tmpl_storage" | grep -q "vztmpl/$template"; then
        pveam download "$tmpl_storage" "$template"
    fi

    echo "==> Creating container $ctid ($template)"
    # nesting and keyctl let Docker run in an unprivileged container.
    pct create "$ctid" "$tmpl_storage:vztmpl/$template" \
        --hostname "$ct_hostname" \
        --unprivileged 1 \
        --features nesting=1,keyctl=1 \
        --cores "$cores" \
        --memory "$memory" \
        --swap 512 \
        --rootfs "$storage:$disk" \
        --net0 "name=eth0,bridge=$bridge,ip=dhcp" \
        --onboot 1 \
        --ostype debian
    pct start "$ctid"

    setup_args=(--host "$host")
    [ -z "$email" ] || setup_args+=(--email "$email")
    [ -z "$region" ] || setup_args+=(--routing "$region")
    [ -z "$tag" ] || setup_args+=(--tag "$tag")

    if ! pct exec "$ctid" -- bash -c "$INNER" detour-lxc "$REPO_URL" "$ref" "$KIT_DIR" "${setup_args[@]}"; then
        echo >&2
        echo "detour-lxc: the install inside container $ctid failed (output above)." >&2
        echo "Fix the cause, then remove it and run this again:" >&2
        echo "  pct stop $ctid && pct destroy $ctid" >&2
        exit 1
    fi

    ip="$(pct exec "$ctid" -- hostname -I 2>/dev/null | awk '{ print $1 }')" || ip=''
    cat <<DONE

Detour is starting in container $ctid${ip:+ at $ip}.

Next:
  1. Point DNS for $host at your public address and forward ports 80 and 443
     to ${ip:-the container}. Caddy fetches the certificate once both reach it.
  2. Invite riders from inside the container:
       pct enter $ctid
       cd $KIT_DIR/docker/prod && ./detour invite
  3. Upgrading later, same directory: docker compose pull && docker compose up -d
DONE
}

# Sourcing (the test) defines INNER and main without running anything. `return`
# succeeds only when sourced; BASH_SOURCE is unset under the documented
# `bash -c "$(curl ...)" --` form, so it can't be the test.
(return 0 2>/dev/null) || main "$@"
