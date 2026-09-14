#!/usr/bin/env bash
# First-pass signal for the detour-ui-structure placement test (#184).
#
# This does not decide anything by itself — it only answers the test's first
# question ("does this file reference a shared/ data model?") and counts how
# many *other* ui/ files reference each file's top-level declarations, as a
# proxy for "used by more than one destination". Read the actual usages
# before acting on the count: a hit inside the same destination's own files
# (e.g. MapScreen.kt and MapCamera.kt both touching MapScreenState.kt) is not
# cross-destination use, and this script cannot tell the two apart.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." 2>/dev/null || cd "$(git rev-parse --show-toplevel)"

UI_DIR="app/src/main/java/com/jellemax/detour/ui"

printf '%-24s %8s   %s\n' "file" "data-ref" "referenced from (other files, top-level decl name match)"
for f in "$UI_DIR"/*.kt; do
  name=$(basename "$f")
  base="${name%.kt}"
  data_refs=$(grep -cE '^import com\.jellemax\.detour\.(data|presentation)\.' "$f" || true)
  # crude cross-reference: how many *other* files in ui/ mention this file's
  # base name (as a class/object/fun) — a proxy, not proof, of shared use.
  refs=$( { { grep -rl "$base" "$UI_DIR" --include='*.kt' || true; } | grep -v "/$name$" || true; } | wc -l | tr -d ' ')
  printf '%-24s %8s   %s files\n' "$name" "$data_refs" "$refs"
done
