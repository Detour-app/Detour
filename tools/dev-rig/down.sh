#!/usr/bin/env bash
# Stop the local routing + road-data rig (#612).
#
#   tools/dev-rig/down.sh          stop the containers, keep the graph and the database
#   tools/dev-rig/down.sh --purge  also drop the rig's volumes and downloaded data
#
# Only ever touches the detour-rig compose project — a detour-dev stack and its
# volumes (including a Belgium graph) are a different project and are left alone.
# Nothing on any device is changed: the emulator keeps pointing at 10.0.2.2,
# which just stops answering until the next up.sh.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(git -C "$HERE" rev-parse --show-toplevel)"
DATA="$REPO/docker/dev/data/rig"
export RIG_REPO="$REPO" RIG_DATA="$DATA"
PROJECT=detour-rig

compose() {
    docker compose -p "$PROJECT" \
        -f "$REPO/docker/dev/docker-compose.yml" \
        -f "$HERE/compose.rig.yml" "$@"
}

case "${1:-}" in
    "")
        compose down
        echo "rig stopped; graph and database kept — up.sh restarts it in seconds"
        ;;
    --purge)
        compose down -v
        docker volume rm "${PROJECT}_pip-cache" >/dev/null 2>&1 || true
        rm -rf "$DATA"
        echo "rig stopped and purged — the next up.sh downloads and builds from scratch"
        ;;
    *)
        echo "usage: $0 [--purge]" >&2
        exit 2
        ;;
esac
