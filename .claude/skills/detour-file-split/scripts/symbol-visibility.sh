#!/usr/bin/env bash
#
# Show every reference to a moved symbol outside its new file, so its visibility is decided
# by evidence rather than by guess.
#
# The rule: any hit outside the new file -> internal; no hit -> keep private. `private` in
# Kotlin is file-scoped, so a symbol whose only caller moved into the same new file stays
# private — that is the case people guess wrong, and guessing "everything the source used
# becomes internal" widens APIs for no reason. The compiler catches the opposite mistake only
# in one direction: leaving something private when it needs internal fails the build naming
# the exact symbol, while an unnecessary internal compiles fine forever. That asymmetry is
# what this grep is for.
#
# It prints hits and a PROVISIONAL verdict, never a decision, because the grep lies in three
# known ways (printed with the output). Read the hits.
#
# Read-only: greps the working tree.
set -euo pipefail

usage() {
    cat >&2 <<'EOF'
usage: symbol-visibility.sh <SymbolName> <new-file> [search-root]

  SymbolName    the symbol that moved, e.g. SpinSheet or seedRouteNavigation
  new-file      the file it moved INTO; hits in this file are excluded
  search-root   defaults to app/src/main/java/com/jellemax/detour/

Exit 0 = no external reference found (keep private); 10 = external references exist
(promote to internal); 2 = bad arguments.
EOF
    exit 2
}

[ "$#" -ge 2 ] && [ "$#" -le 3 ] || usage
if [ "$1" = "-h" ] || [ "$1" = "--help" ]; then usage; fi

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$ROOT"

SYM="$1"
NEW="$2"
SEARCH="${3:-app/src/main/java/com/jellemax/detour/}"
[ -d "$SEARCH" ] || { echo "error: no such directory: $SEARCH" >&2; exit 2; }

hits="$(grep -rn "\\b$SYM\\b" "$SEARCH" 2>/dev/null | grep -v "$(basename "$NEW")" || true)"

printf 'references to %s outside %s:\n\n' "$SYM" "$(basename "$NEW")"
if [ -z "$hits" ]; then
    echo "  (none)"
else
    printf '%s\n' "$hits" | sed 's/^/  /'
fi

# app/src/test is inside the module, so internal reaches it — that is the precedent worth
# copying (HistoryScreen's internal matchTripPoints, tested by TripTraceMatchingTest).
tests="$(grep -rn "\\b$SYM\\b" app/src/test 2>/dev/null || true)"
if [ -n "$tests" ]; then
    printf '\nalso referenced from app/src/test (internal reaches it):\n'
    printf '%s\n' "$tests" | sed 's/^/  /'
fi

cat <<'EOF'

Three ways this grep lies — read the hits before deciding:
  1. An extension member has no call site containing its own name. `TravelMode.icon` greps to
     exactly one hit (its declaration); the real call sites read `m.icon`, `trip.mode.icon`.
     Grep the member with a leading dot instead, and read the receivers.
  2. The same simple name can be a different symbol — BadgesScreen declares its own private
     `BadgeKind.icon`. Read every hit; do not count them.
  3. A prose mention is not a reference. RoutesScreen names navigateGoogleMaps inside a KDoc
     cross-reference and never calls it. Open the file before deciding.
EOF

if [ -z "$hits" ]; then
    echo
    echo "PROVISIONAL: no external reference -> keep it private (file-scoped in Kotlin)."
    exit 0
fi
echo
echo "PROVISIONAL: external references exist -> internal (module-wide, not public)."
exit 10
