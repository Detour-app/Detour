#!/usr/bin/env bash
# Nightly hot backup of the Detour sync database.
#
# Uses SQLite's own backup API rather than cp: a plain copy of a WAL database
# taken while the server is writing can be torn. Installed by install.sh and
# driven by detour-backup.timer.
set -euo pipefail

DB="${DB:-/var/lib/detour-sync/detour.db}"
DEST="${DEST:-/var/backups/detour}"
KEEP_DAYS="${KEEP_DAYS:-14}"

mkdir -p "$DEST"
OUT="$DEST/detour-$(date +%F).db"

# Note: the sqlite3 CLI is not installed by default on a Debian container, and
# `.backup` is a CLI-only command, so drive the same API from Python instead.
python3 - "$DB" "$OUT" <<'PY'
import sqlite3, sys
src = sqlite3.connect("file:%s?mode=ro" % sys.argv[1], uri=True)
dst = sqlite3.connect(sys.argv[2])
with dst:
    src.backup(dst)
dst.close(); src.close()
PY

# Check the copy, so a corrupt backup is never mistaken for a good one.
python3 - "$OUT" <<'PY'
import sqlite3, sys
result = sqlite3.connect(sys.argv[1]).execute("PRAGMA integrity_check").fetchone()[0]
if result != "ok":
    sys.exit("backup failed integrity_check: %s" % result)
PY

chmod 0600 "$OUT"
find "$DEST" -name 'detour-*.db' -mtime "+$KEEP_DAYS" -delete
echo "backup ok: $OUT ($(stat -c %s "$OUT") bytes)"
